package com.hunkwise.actions

import com.hunkwise.git.GitignoreManager
import com.hunkwise.state.StateManager
import com.hunkwise.watcher.FileWatcher
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service

class EnableAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val stateManager = project.service<StateManager>()
        if (stateManager.enabled) return

        stateManager.setEnabled(true)

        val fileWatcher = project.service<FileWatcher>()
        val workspaceRoot = stateManager.workspaceRoot ?: return
        val hunkwiseDir = stateManager.hunkwiseDir ?: return
        val shouldIgnore = buildShouldIgnore(stateManager, hunkwiseDir, workspaceRoot)

        stateManager.snapshotWorkspace(shouldIgnore)
        stateManager.fireStateChanged()
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val stateManager = project?.service<StateManager>()
        e.presentation.isEnabled = stateManager != null && !stateManager.enabled
    }
}
