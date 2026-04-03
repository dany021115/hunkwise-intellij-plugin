package com.hunkwise.actions

import com.hunkwise.state.StateManager
import com.hunkwise.util.PathNormalize
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service

class AcceptFileAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val stateManager = project.service<StateManager>()
        val vFile = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val filePath = PathNormalize.normalize(vFile.path)
        HunkActions.acceptFile(project, stateManager, filePath)
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val stateManager = project?.service<StateManager>()
        e.presentation.isEnabled = stateManager?.enabled == true
    }
}
