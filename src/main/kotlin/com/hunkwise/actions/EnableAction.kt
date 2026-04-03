package com.hunkwise.actions

import com.hunkwise.state.StateManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task

class EnableAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val stateManager = project.service<StateManager>()
        if (stateManager.enabled) return

        stateManager.setEnabled(true)

        val workspaceRoot = stateManager.workspaceRoot ?: return
        val hunkwiseDir = stateManager.hunkwiseDir ?: return
        val shouldIgnore = buildShouldIgnore(stateManager, hunkwiseDir, workspaceRoot)

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Hunkwise: Snapshotting workspace...", false) {
            override fun run(indicator: ProgressIndicator) {
                stateManager.snapshotWorkspace(shouldIgnore)
            }

            override fun onSuccess() {
                stateManager.fireStateChanged()
            }
        })
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val stateManager = project?.service<StateManager>()
        e.presentation.isEnabled = stateManager != null && !stateManager.enabled
    }
}
