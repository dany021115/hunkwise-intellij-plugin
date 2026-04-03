package com.hunkwise

import com.hunkwise.git.GitignoreManager
import com.hunkwise.state.StateManager
import com.hunkwise.util.HunkwiseLogger
import com.hunkwise.watcher.FileWatcher
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.VirtualFileListener
import com.intellij.openapi.vfs.VirtualFileEvent
import com.intellij.openapi.vfs.VirtualFileManager
import java.io.File

/**
 * Plugin entry point. Initializes hunkwise services on project open.
 */
class HunkwisePlugin : ProjectActivity {

    override suspend fun execute(project: Project) {
        val log = HunkwiseLogger.LOG
        val stateManager = project.service<StateManager>()
        val fileWatcher = project.service<FileWatcher>()

        val workspaceRoot = stateManager.workspaceRoot ?: return
        val hunkwiseDir = stateManager.hunkwiseDir ?: return

        // Setup gitignore manager
        val gitignoreManager = GitignoreManager(workspaceRoot)
        if (File(workspaceRoot, ".gitignore").exists()) {
            gitignoreManager.loadGitignores()
        }

        val shouldIgnore = { filePath: String, isDirectory: Boolean ->
            val settings = stateManager.settings
            // Always ignore the hunkwise dir itself
            filePath.startsWith(hunkwiseDir) ||
                // Check custom ignore patterns
                settings.ignorePatterns.any { pattern ->
                    val components = filePath.split(File.separator)
                    components.any { it == pattern }
                } ||
                // Check gitignore if enabled
                (settings.respectGitignore && gitignoreManager.shouldIgnore(filePath, isDirectory))
        }

        // Register file watcher
        fileWatcher.register(stateManager, shouldIgnore, gitignoreManager)

        // Load persistent state
        fileWatcher.suppress()
        try {
            stateManager.load(shouldIgnore)
        } finally {
            fileWatcher.resume()
        }

        // Fire initial state change to update UI
        if (stateManager.enabled) {
            stateManager.fireStateChanged()
        }

        // Watch .git/HEAD for branch switches
        watchGitHead(project, stateManager, fileWatcher, shouldIgnore)

        // Watch for hunkwise dir deletion
        watchHunkwiseDir(project, stateManager, fileWatcher)

        log.info("Hunkwise initialized for project: ${project.name}")
    }

    private fun watchGitHead(
        project: Project,
        stateManager: StateManager,
        fileWatcher: FileWatcher,
        shouldIgnore: (String, Boolean) -> Boolean
    ) {
        val basePath = project.basePath ?: return
        val gitHeadPath = "$basePath/.git/HEAD"
        var lastHead = try {
            File(gitHeadPath).readText().trim()
        } catch (_: Exception) {
            return
        }

        VirtualFileManager.getInstance().addVirtualFileListener(object : VirtualFileListener {
            override fun contentsChanged(event: VirtualFileEvent) {
                if (!event.file.path.endsWith(".git/HEAD")) return
                if (!stateManager.enabled || !stateManager.settings.clearOnBranchSwitch) return

                val currentHead = try {
                    File(gitHeadPath).readText().trim()
                } catch (_: Exception) {
                    return
                }
                if (currentHead != lastHead) {
                    lastHead = currentHead
                    fileWatcher.suppress()
                    try {
                        stateManager.clearHunksOnBranchSwitch(shouldIgnore)
                    } finally {
                        fileWatcher.resume()
                    }
                    stateManager.fireStateChanged()
                }
            }
        }, project)
    }

    private fun watchHunkwiseDir(
        project: Project,
        stateManager: StateManager,
        fileWatcher: FileWatcher
    ) {
        val hunkwiseDir = stateManager.hunkwiseDir ?: return

        VirtualFileManager.getInstance().addVirtualFileListener(object : VirtualFileListener {
            override fun fileDeleted(event: VirtualFileEvent) {
                if (event.file.path == hunkwiseDir || event.file.path.startsWith("$hunkwiseDir/")) {
                    if (stateManager.enabled && !File(hunkwiseDir, "git").exists()) {
                        stateManager.resetToDisabled()
                        stateManager.fireStateChanged()
                    }
                }
            }
        }, project)
    }
}
