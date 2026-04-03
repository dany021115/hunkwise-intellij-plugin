package com.hunkwise.watcher

import com.hunkwise.diff.DiffEngine
import com.hunkwise.git.GitignoreManager
import com.hunkwise.state.FileState
import com.hunkwise.state.FileStatus
import com.hunkwise.state.StateManager
import com.hunkwise.util.HunkwiseLogger
import com.hunkwise.util.PathNormalize
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.*
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.*
import java.io.File
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.ConcurrentHashMap

/**
 * Watches for file system and document changes, distinguishing user edits
 * from external tool writes to trigger hunk review mode appropriately.
 */
@Service(Service.Level.PROJECT)
class FileWatcher(private val project: Project) : Disposable {

    private val log = HunkwiseLogger.LOG
    private val selfEditFiles = ConcurrentHashMap.newKeySet<String>()
    private val pendingUserDeletes = ConcurrentHashMap.newKeySet<String>()
    private val pendingRenameOldPaths = ConcurrentHashMap.newKeySet<String>()
    private val debounceTimers = ConcurrentHashMap<String, Timer>()

    @Volatile
    private var suppressed = false

    private var stateManager: StateManager? = null
    private var shouldIgnore: ((String, Boolean) -> Boolean)? = null
    private var gitignoreManager: GitignoreManager? = null

    fun register(
        stateManager: StateManager,
        shouldIgnore: (String, Boolean) -> Boolean,
        gitignoreManager: GitignoreManager
    ) {
        this.stateManager = stateManager
        this.shouldIgnore = shouldIgnore
        this.gitignoreManager = gitignoreManager

        // Subscribe to VFS bulk events
        project.messageBus.connect(this).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun before(events: MutableList<out VFileEvent>) {
                    handleBeforeEvents(events)
                }

                override fun after(events: MutableList<out VFileEvent>) {
                    handleAfterEvents(events)
                }
            }
        )

        // Subscribe to document changes (in-memory buffer edits)
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(
            object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    onDocumentChange(event)
                }
            },
            this
        )
    }

    fun suppress() {
        suppressed = true
    }

    fun resume() {
        suppressed = false
    }

    fun markSelfEdit(filePath: String) {
        selfEditFiles.add(PathNormalize.normalize(filePath))
    }

    fun clearSelfEdit(filePath: String) {
        selfEditFiles.remove(PathNormalize.normalize(filePath))
    }

    // ── VFS event handling ───────────────────────────────────────────────

    private fun handleBeforeEvents(events: List<VFileEvent>) {
        val sm = stateManager ?: return
        if (!sm.enabled) return

        for (event in events) {
            when (event) {
                is VFileDeleteEvent -> {
                    // User-initiated delete via IDE
                    val filePath = PathNormalize.normalize(event.file.path)
                    pendingUserDeletes.add(filePath)
                }
                is VFileMoveEvent -> {
                    val oldPath = PathNormalize.normalize(event.file.path)
                    val newParent = event.newParent.path
                    val newPath = PathNormalize.normalize("$newParent/${event.file.name}")
                    pendingRenameOldPaths.add(oldPath)
                    selfEditFiles.add(newPath)
                    sm.renameFile(oldPath, newPath)
                }
                is VFilePropertyChangeEvent -> {
                    if (event.propertyName == VirtualFile.PROP_NAME) {
                        val parent = event.file.parent?.path ?: ""
                        val oldPath = PathNormalize.normalize("$parent/${event.oldValue}")
                        val newPath = PathNormalize.normalize("$parent/${event.newValue}")
                        pendingRenameOldPaths.add(oldPath)
                        selfEditFiles.add(newPath)
                        sm.renameFile(oldPath, newPath)
                    }
                }
            }
        }
    }

    private fun handleAfterEvents(events: List<VFileEvent>) {
        val sm = stateManager ?: return

        for (event in events) {
            when (event) {
                is VFileContentChangeEvent -> onDiskChange(event)
                is VFileDeleteEvent -> onDiskDelete(event)
                is VFileCreateEvent -> onDiskCreate(event)
                is VFileMoveEvent -> {
                    val oldPath = PathNormalize.normalize(
                        "${event.oldParent.path}/${event.file.name}"
                    )
                    val newPath = PathNormalize.normalize(event.file.path)
                    pendingRenameOldPaths.remove(oldPath)
                    selfEditFiles.remove(newPath)
                    if (sm.getFile(newPath) != null) {
                        sm.fireStateChanged()
                    }
                }
                is VFilePropertyChangeEvent -> {
                    if (event.propertyName == VirtualFile.PROP_NAME) {
                        val parent = event.file.parent?.path ?: ""
                        val oldPath = PathNormalize.normalize("$parent/${event.oldValue}")
                        val newPath = PathNormalize.normalize(event.file.path)
                        pendingRenameOldPaths.remove(oldPath)
                        selfEditFiles.remove(newPath)
                        if (sm.getFile(newPath) != null) {
                            sm.fireStateChanged()
                        }
                    }
                }
            }

            // Handle .gitignore changes
            if (event is VFileContentChangeEvent || event is VFileCreateEvent || event is VFileDeleteEvent) {
                val path = event.path ?: continue
                if (path.endsWith(".gitignore")) {
                    gitignoreManager?.loadGitignores()
                    if (sm.enabled) {
                        sm.syncIgnoreState(shouldIgnore ?: continue)
                        sm.fireStateChanged()
                    }
                }
            }
        }
    }

    private fun onDiskCreate(event: VFileCreateEvent) {
        if (suppressed) return
        val sm = stateManager ?: return
        if (!sm.enabled) return

        val vFile = event.file ?: return
        val filePath = PathNormalize.normalize(vFile.path)
        if (shouldIgnore?.invoke(filePath, vFile.isDirectory) == true) return
        if (selfEditFiles.contains(filePath)) return
        if (vFile.isDirectory) return

        val fileState = sm.getFile(filePath)

        if (fileState?.status == FileStatus.REVIEWING) {
            val diskContent = readFileContent(vFile) ?: return
            recomputeHunks(sm, filePath, fileState.baseline, diskContent)
            return
        }
        if (fileState != null) return

        val git = sm.git ?: return

        val diskContent = readFileContent(vFile) ?: return

        val gitBaseline = git.getBaseline(filePath)
        if (gitBaseline != null) {
            enterReviewing(sm, filePath, gitBaseline, diskContent)
            return
        }

        val document = FileDocumentManager.getInstance().getCachedDocument(vFile)
        if (document != null && document.text == diskContent) {
            sm.snapshotFile(filePath, diskContent)
            return
        }

        enterReviewing(sm, filePath, null, diskContent)
    }

    private fun onDiskDelete(event: VFileDeleteEvent) {
        if (suppressed) return
        val sm = stateManager ?: return
        if (!sm.enabled) return

        val filePath = PathNormalize.normalize(event.file.path)
        if (shouldIgnore?.invoke(filePath, false) == true) return
        if (selfEditFiles.contains(filePath)) return

        val fileState = sm.getFile(filePath)

        if (pendingRenameOldPaths.contains(filePath)) {
            pendingRenameOldPaths.remove(filePath)
            return
        }

        if (pendingUserDeletes.contains(filePath)) {
            // User-initiated delete — remove baseline
            pendingUserDeletes.remove(filePath)
            sm.removeFile(filePath)
            // Clean up child files for directory deletes
            val dirPrefix = filePath + File.separator
            var needsRefresh = fileState != null
            for (childPath in sm.getAllFiles().keys.toList()) {
                if (childPath.startsWith(dirPrefix)) {
                    sm.removeFile(childPath)
                    needsRefresh = true
                }
            }
            if (needsRefresh) sm.fireStateChanged()
            return
        }

        // External tool deleted the file
        val git = sm.git ?: return

        // New file deleted — just clean up
        if (fileState?.baseline == null && fileState != null) {
            sm.exitReviewing(filePath)
            sm.fireStateChanged()
            return
        }

        val baseline = fileState?.baseline ?: git.getBaseline(filePath)
        if (baseline == null) {
            if (fileState != null) {
                sm.removeFile(filePath)
                sm.fireStateChanged()
            }
            // Clean up children for directory deletes
            val dirPrefix = filePath + File.separator
            val allFiles = sm.getAllFiles()
            for ((childPath, childState) in allFiles) {
                if (!childPath.startsWith(dirPrefix)) continue
                if (childState.baseline == null) {
                    sm.exitReviewing(childPath)
                } else {
                    enterReviewing(sm, childPath, childState.baseline, "")
                }
            }
            return
        }

        // Show deletion diff
        enterReviewing(sm, filePath, baseline, "")
    }

    private fun onDiskChange(event: VFileContentChangeEvent) {
        if (suppressed) return
        val sm = stateManager ?: return
        if (!sm.enabled) return

        val filePath = PathNormalize.normalize(event.file.path)
        if (shouldIgnore?.invoke(filePath, false) == true) return
        if (selfEditFiles.contains(filePath)) return

        val diskContent = readFileContent(event.file) ?: return

        val fileState = sm.getFile(filePath)

        if (fileState?.status == FileStatus.REVIEWING) {
            recomputeHunks(sm, filePath, fileState.baseline, diskContent)
            return
        }

        val git = sm.git ?: return

        // Check if user saved in IDE (buffer matches disk)
        val document = FileDocumentManager.getInstance().getCachedDocument(event.file)
        if (document != null && document.text == diskContent) {
            sm.snapshotFile(filePath, diskContent)
            return
        }

        // External change
        val gitBaseline = git.getBaseline(filePath)
        if (gitBaseline == null) {
            // No baseline — silently adopt as baseline
            sm.snapshotFile(filePath, diskContent)
            return
        }
        enterReviewing(sm, filePath, gitBaseline, diskContent)
    }

    // ── Document change handling ─────────────────────────────────────────

    private fun onDocumentChange(event: DocumentEvent) {
        if (suppressed) return
        val sm = stateManager ?: return
        if (!sm.enabled) return

        val document = event.document
        val vFile = FileDocumentManager.getInstance().getFile(document) ?: return
        if (!vFile.isInLocalFileSystem) return

        val filePath = PathNormalize.normalize(vFile.path)
        if (shouldIgnore?.invoke(filePath, false) == true) return
        if (selfEditFiles.contains(filePath)) return

        val fileState = sm.getFile(filePath)
        if (fileState?.status != FileStatus.REVIEWING) return

        // Debounce recomputation
        debounceTimers[filePath]?.cancel()
        val timer = Timer()
        timer.schedule(object : TimerTask() {
            override fun run() {
                debounceTimers.remove(filePath)
                val latestState = sm.getFile(filePath)
                if (latestState == null || latestState.status != FileStatus.REVIEWING) return
                recomputeHunks(sm, filePath, latestState.baseline, document.text)
            }
        }, 50)
        debounceTimers[filePath] = timer
    }

    // ── Review logic ─────────────────────────────────────────────────────

    private fun enterReviewing(
        sm: StateManager,
        filePath: String,
        baseline: String?,
        current: String
    ) {
        val hunks = DiffEngine.computeHunks(baseline, current)
        val isNew = baseline == null
        val isDeleted = !File(filePath).exists() && baseline != null
        if (hunks.isEmpty() && !isNew && !isDeleted) return

        log.info("reviewing: ${File(filePath).name}${if (isNew) " (new)" else if (isDeleted) " (deleted)" else ""}")
        sm.setFile(filePath, FileState(FileStatus.REVIEWING, baseline))
        sm.fireStateChanged()
    }

    private fun recomputeHunks(
        sm: StateManager,
        filePath: String,
        baseline: String?,
        current: String
    ) {
        if (DiffEngine.computeHunks(baseline, current).isEmpty()) {
            if (baseline == null && current.isEmpty()) {
                sm.fireStateChanged()
                return
            }
            sm.exitReviewing(filePath)
        }
        sm.fireStateChanged()
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun readFileContent(vFile: VirtualFile): String? {
        return try {
            String(vFile.contentsToByteArray(), vFile.charset)
        } catch (_: Exception) {
            null
        }
    }

    override fun dispose() {
        debounceTimers.values.forEach { it.cancel() }
        debounceTimers.clear()
        pendingUserDeletes.clear()
        pendingRenameOldPaths.clear()
    }
}
