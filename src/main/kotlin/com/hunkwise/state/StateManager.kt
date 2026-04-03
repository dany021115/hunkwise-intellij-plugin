package com.hunkwise.state

import com.hunkwise.git.HunkwiseGit
import com.hunkwise.util.HunkwiseLogger
import com.hunkwise.util.PathNormalize
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import kotlinx.coroutines.*
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

interface StateChangeListener {
    fun stateChanged()
}

@Service(Service.Level.PROJECT)
class StateManager(private val project: Project) {

    companion object {
        @JvmField
        val STATE_CHANGED_TOPIC: Topic<StateChangeListener> =
            Topic.create("HunkwiseStateChanged", StateChangeListener::class.java)
    }

    private val log = HunkwiseLogger.LOG
    private val state = LinkedHashMap<String, FileState>()

    private var _hunkwiseDir: String? = null
    private var _workspaceRoot: String? = null
    private var _enabled = false
    private var _git: HunkwiseGit? = null
    private var _settings = HunkwiseSettings()

    // Serial queue for git operations (single-thread coroutine dispatcher)
    private val gitScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private var gitJob: Job = Job().also { it.complete() }

    val enabled: Boolean get() = _enabled
    val settings: HunkwiseSettings get() = _settings
    val hunkwiseDir: String? get() = _hunkwiseDir
    val workspaceRoot: String? get() = _workspaceRoot
    val git: HunkwiseGit? get() = _git

    init {
        val basePath = project.basePath
        if (basePath != null) {
            _workspaceRoot = basePath
            _hunkwiseDir = File(basePath, ".idea/hunkwise").absolutePath
        }
    }

    // ── state accessors ──────────────────────────────────────────────────

    fun getFile(filePath: String): FileState? {
        return state[PathNormalize.normalize(filePath)]
    }

    fun getAllFiles(): Map<String, FileState> = state.toMap()

    fun getReviewingFiles(): Map<String, FileState> {
        return state.filter { it.value.status == FileStatus.REVIEWING }
    }

    fun isReviewing(filePath: String): Boolean {
        return state[PathNormalize.normalize(filePath)]?.status == FileStatus.REVIEWING
    }

    // ── file state mutations ─────────────────────────────────────────────

    fun setFile(filePath: String, newState: FileState, skipSnapshot: Boolean = false) {
        val normalized = PathNormalize.normalize(filePath)
        val oldState = state[normalized]?.copy()
        state[normalized] = newState

        if (!skipSnapshot && _git != null && newState.baseline != null) {
            val g = _git!!
            val baseline = newState.baseline
            enqueueGit {
                try {
                    g.snapshot(normalized, baseline)
                } catch (e: Exception) {
                    log.warn("git queue error (setFile rollback): $e")
                    if (state[normalized] == newState) {
                        if (oldState != null) state[normalized] = oldState else state.remove(normalized)
                        fireStateChanged()
                    }
                }
            }
        }
    }

    fun removeFile(filePath: String) {
        val normalized = PathNormalize.normalize(filePath)
        val oldState = state[normalized]?.copy()
        state.remove(normalized)

        val skipGit = oldState != null && oldState.baseline == null
        if (_git != null && !skipGit) {
            val g = _git!!
            enqueueGit {
                try {
                    g.removeFile(normalized)
                } catch (e: Exception) {
                    log.warn("git queue error (removeFile rollback): $e")
                    if (!state.containsKey(normalized) && oldState != null) {
                        state[normalized] = oldState.copy()
                        fireStateChanged()
                    }
                }
            }
        }
    }

    fun renameFile(oldFilePath: String, newFilePath: String) {
        val oldNorm = PathNormalize.normalize(oldFilePath)
        val newNorm = PathNormalize.normalize(newFilePath)

        val oldPrefix = oldNorm + File.separator
        var hasDirChildren = false
        val fileState = state[oldNorm]

        if (fileState != null) {
            state.remove(oldNorm)
            state[newNorm] = fileState
        }

        val entriesToMove = state.entries.filter { it.key.startsWith(oldPrefix) }
        for ((fp, childState) in entriesToMove) {
            state.remove(fp)
            val newFp = newNorm + fp.substring(oldNorm.length)
            state[newFp] = childState
            hasDirChildren = true
        }

        val skipGit = fileState != null && fileState.baseline == null && !hasDirChildren
        if (_git != null && !skipGit) {
            val g = _git!!
            enqueueGit {
                try {
                    g.renameFile(oldNorm, newNorm)
                } catch (e: Exception) {
                    log.warn("git queue error (renameFile): $e")
                }
            }
        }
    }

    fun snapshotFile(filePath: String, content: String) {
        if (_git != null) {
            val g = _git!!
            enqueueGit {
                try {
                    g.snapshot(filePath, content)
                } catch (e: Exception) {
                    log.warn("git queue error (snapshotFile): $e")
                }
            }
        }
    }

    fun exitReviewing(filePath: String, newBaseline: String? = null) {
        val normalized = PathNormalize.normalize(filePath)
        val oldState = state[normalized]?.copy()
        state.remove(normalized)

        if (newBaseline != null && _git != null) {
            val g = _git!!
            enqueueGit {
                try {
                    g.snapshot(normalized, newBaseline)
                } catch (e: Exception) {
                    log.warn("git queue error (exitReviewing rollback): $e")
                    if (!state.containsKey(normalized) && oldState != null) {
                        state[normalized] = oldState.copy()
                        fireStateChanged()
                    }
                }
            }
        }
    }

    // ── enable / disable ─────────────────────────────────────────────────

    fun setEnabled(value: Boolean) {
        _enabled = value
        if (value) {
            ensureGit() // just create the HunkwiseGit instance, don't init yet
        } else {
            state.clear()
            _git?.destroyGit()
            _git = null
        }
    }

    /**
     * Snapshot all workspace files as baselines. Called after enable.
     */
    fun snapshotWorkspace(shouldIgnore: (String, Boolean) -> Boolean) {
        val g = ensureGit() ?: return
        val root = _workspaceRoot ?: return

        // Init git if not yet done (safe to call from background thread)
        g.initGit()
        val merged = mergeDefaultSettings(g)
        _settings = merged

        val files = collectFiles(root, shouldIgnore)
        val batch = files.mapNotNull { fp ->
            try {
                val content = File(fp).readText(Charsets.UTF_8)
                fp to content
            } catch (_: Exception) {
                null
            }
        }
        if (batch.isNotEmpty()) {
            g.snapshotBatch(batch)
        }
    }

    // ── load / rebuild ───────────────────────────────────────────────────

    fun load(shouldIgnore: ((String, Boolean) -> Boolean)? = null) {
        val g = ensureGit() ?: return
        val gitDirFile = File(_hunkwiseDir!!, "git")
        if (!gitDirFile.exists()) return

        _enabled = true
        _settings = g.loadSettings()

        g.initGit()
        val tracked = g.listTrackedFiles()
        val ignored = mutableListOf<String>()
        val reviewing = mutableListOf<String>()

        for (filePath in tracked) {
            if (shouldIgnore?.invoke(filePath, false) == true) {
                ignored.add(filePath)
                continue
            }
            val baseline = g.getBaseline(filePath) ?: continue

            var diskContent: String? = null
            var fileDeleted = false
            try {
                diskContent = File(filePath).readText(Charsets.UTF_8)
            } catch (_: java.io.FileNotFoundException) {
                fileDeleted = true
            } catch (_: Exception) {
                // permission errors etc — treat as idle
            }

            if (fileDeleted) {
                // File was deleted externally — show for review
                state[filePath] = FileState(FileStatus.REVIEWING, baseline)
                reviewing.add(filePath)
            } else if (diskContent != null && diskContent != baseline) {
                // Baseline is stale — silently update to current disk content
                // so only NEW changes from this point forward are tracked
                enqueueGit {
                    try {
                        g.snapshot(filePath, diskContent)
                    } catch (e: Exception) {
                        log.warn("load: failed to update baseline for $filePath: $e")
                    }
                }
            }
        }

        // Clean up ignored entries from git
        if (ignored.isNotEmpty()) {
            log.info("load: removing ${ignored.size} ignored file(s) from git")
            enqueueGit {
                try {
                    g.removeFileBatch(ignored)
                } catch (e: Exception) {
                    log.warn("git queue error: $e")
                }
            }
        }

        // Detect untracked new files on disk
        val trackedSet = tracked.toHashSet()
        val untrackedFiles = collectUntrackedFiles(trackedSet, shouldIgnore)
        for (fp in untrackedFiles) {
            state[fp] = FileState(FileStatus.REVIEWING, null)
        }

        if (reviewing.isNotEmpty()) {
            log.info("load: ${reviewing.size} file(s) have diffs")
        }
        if (untrackedFiles.isNotEmpty()) {
            log.info("load: ${untrackedFiles.size} untracked new file(s)")
        }
    }

    fun rebuildState(shouldIgnore: ((String, Boolean) -> Boolean)? = null) {
        val g = _git ?: return
        if (!_enabled) return

        log.info("rebuildState: begin")

        // Wait for pending git ops
        waitForGitQueue()

        state.clear()
        g.initGit()
        val tracked = g.listTrackedFiles()
        val filtered = tracked.filter { shouldIgnore?.invoke(it, false) != true }

        for (filePath in filtered) {
            val baseline = g.getBaseline(filePath) ?: continue
            var diskContent: String? = null
            var fileDeleted = false
            try {
                diskContent = File(filePath).readText(Charsets.UTF_8)
            } catch (_: java.io.FileNotFoundException) {
                fileDeleted = true
            } catch (_: Exception) { }

            if (fileDeleted || (diskContent != null && diskContent != baseline)) {
                state[filePath] = FileState(FileStatus.REVIEWING, baseline)
            }
        }

        // Detect untracked files
        val trackedSet = tracked.toHashSet()
        val untrackedFiles = collectUntrackedFiles(trackedSet, shouldIgnore)
        for (fp in untrackedFiles) {
            state[fp] = FileState(FileStatus.REVIEWING, null)
        }

        log.info("rebuildState: done — ${state.size} file(s) in reviewing state")
    }

    // ── settings ─────────────────────────────────────────────────────────

    fun setIgnorePatterns(patterns: List<String>) {
        _settings = _settings.copy(ignorePatterns = patterns)
        saveCurrentSettings()
    }

    fun setRespectGitignore(value: Boolean) {
        _settings = _settings.copy(respectGitignore = value)
        saveCurrentSettings()
    }

    fun setClearOnBranchSwitch(value: Boolean) {
        _settings = _settings.copy(clearOnBranchSwitch = value)
        saveCurrentSettings()
    }

    fun setShowInlineDecorations(value: Boolean) {
        _settings = _settings.copy(showInlineDecorations = value)
        saveCurrentSettings()
    }

    fun reloadSettings(): HunkwiseSettings? {
        if (!_enabled || _git == null) return null
        _settings = _git!!.loadSettings()
        return _settings
    }

    /**
     * Sync tracked files with current ignore rules.
     */
    fun syncIgnoreState(shouldIgnore: (String, Boolean) -> Boolean) {
        val g = _git ?: return
        val root = _workspaceRoot ?: return

        val allowedFiles = collectFiles(root, shouldIgnore)
        val trackedFiles = g.listTrackedFiles()

        val allowedSet = allowedFiles.toHashSet()

        // Remove tracked files that are now ignored
        val toRemove = trackedFiles.filter { it !in allowedSet }
        // Also remove in-memory state entries that are ignored
        state.keys.removeAll { it !in allowedSet }

        if (toRemove.isNotEmpty()) {
            log.info("syncIgnoreState: removing ${toRemove.size} file(s)")
            enqueueGit {
                try {
                    g.removeFileBatch(toRemove)
                } catch (e: Exception) {
                    log.warn("git queue error: $e")
                }
            }
        }

        // Add newly allowed files not yet tracked
        val trackedSet = trackedFiles.toHashSet()
        state.keys.forEach { trackedSet.add(it) }
        val toAdd = allowedFiles.filter { it !in trackedSet }

        if (toAdd.isNotEmpty()) {
            log.info("syncIgnoreState: adding ${toAdd.size} file(s)")
            val batch = toAdd.mapNotNull { fp ->
                try {
                    fp to File(fp).readText(Charsets.UTF_8)
                } catch (_: Exception) {
                    null
                }
            }
            if (batch.isNotEmpty()) {
                enqueueGit {
                    try {
                        g.snapshotBatch(batch)
                    } catch (e: Exception) {
                        log.warn("git queue error: $e")
                    }
                }
            }
        }

        // Wait for git ops
        waitForGitQueue()
    }

    /**
     * Clear all reviewing state on branch switch, re-snapshot to current disk content.
     */
    fun clearHunksOnBranchSwitch(shouldIgnore: ((String, Boolean) -> Boolean)? = null) {
        val g = _git ?: return
        val root = _workspaceRoot ?: return

        state.clear()

        val diskFiles = collectFiles(root) { path, isDir ->
            shouldIgnore?.invoke(path, isDir) ?: false
        }
        val trackedFiles = g.listTrackedFiles()

        val diskSet = diskFiles.toHashSet()
        val batch = diskFiles.mapNotNull { fp ->
            try {
                fp to File(fp).readText(Charsets.UTF_8)
            } catch (_: Exception) {
                null
            }
        }

        val toRemove = trackedFiles.filter { it !in diskSet }

        if (toRemove.isNotEmpty()) {
            enqueueGit {
                try {
                    g.removeFileBatch(toRemove)
                } catch (e: Exception) {
                    log.warn("git queue error: $e")
                }
            }
        }
        if (batch.isNotEmpty()) {
            enqueueGit {
                try {
                    g.snapshotBatch(batch)
                } catch (e: Exception) {
                    log.warn("git queue error: $e")
                }
            }
        }

        waitForGitQueue()
    }

    fun resetToDisabled() {
        _enabled = false
        _settings = HunkwiseSettings()
        state.clear()
        _git = null
        gitJob = Job().also { it.complete() }
    }

    fun flush() {
        waitForGitQueue()
    }

    // ── notification ─────────────────────────────────────────────────────

    fun fireStateChanged() {
        ApplicationManager.getApplication().invokeLater {
            project.messageBus.syncPublisher(STATE_CHANGED_TOPIC).stateChanged()
        }
    }

    // ── private helpers ──────────────────────────────────────────────────

    private fun ensureGit(): HunkwiseGit? {
        val dir = _hunkwiseDir ?: return null
        val root = _workspaceRoot ?: return null
        if (_git == null) {
            _git = HunkwiseGit(dir, root)
        }
        return _git
    }

    /**
     * Wait for pending git operations. Safe to call from any thread.
     * Uses a CountDownLatch to avoid runBlocking on EDT.
     */
    private fun waitForGitQueue() {
        val latch = java.util.concurrent.CountDownLatch(1)
        enqueueGit { latch.countDown() }
        try {
            latch.await(30, java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            // timeout — proceed anyway
        }
    }

    private fun enqueueGit(action: suspend () -> Unit) {
        gitJob = gitScope.launch {
            gitJob.join()
            action()
        }
    }

    private fun saveCurrentSettings() {
        if (_enabled && _git != null) {
            _git!!.saveSettings(_settings)
        }
    }

    private fun mergeDefaultSettings(g: HunkwiseGit): HunkwiseSettings {
        val existing = g.loadSettings()
        g.saveSettings(existing)
        return existing
    }

    private fun collectFiles(
        dir: String,
        shouldIgnore: (String, Boolean) -> Boolean
    ): List<String> {
        val results = mutableListOf<String>()
        val dirFile = File(dir)
        if (!dirFile.isDirectory) return results

        dirFile.listFiles()?.forEach { entry ->
            val full = PathNormalize.normalize(entry.absolutePath)
            val isDir = entry.isDirectory
            if (shouldIgnore(full, isDir)) return@forEach
            if (isDir) {
                results.addAll(collectFiles(full, shouldIgnore))
            } else if (entry.isFile) {
                results.add(full)
            }
        }
        return results
    }

    private fun collectUntrackedFiles(
        trackedSet: Set<String>,
        shouldIgnore: ((String, Boolean) -> Boolean)?
    ): List<String> {
        val root = _workspaceRoot ?: return emptyList()
        val results = mutableListOf<String>()

        fun collect(dir: File) {
            dir.listFiles()?.forEach { entry ->
                val full = PathNormalize.normalize(entry.absolutePath)
                val isDir = entry.isDirectory
                if (shouldIgnore?.invoke(full, isDir) == true) return@forEach
                if (isDir) {
                    collect(entry)
                } else if (entry.isFile && full !in trackedSet) {
                    if (Files.isReadable(entry.toPath())) {
                        results.add(full)
                    }
                }
            }
        }

        collect(File(root))
        return results
    }
}
