package com.hunkwise.actions

import com.hunkwise.state.StateManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service

class DisableAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val stateManager = project.service<StateManager>()
        if (!stateManager.enabled) return
        stateManager.setEnabled(false)
        stateManager.fireStateChanged()
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val stateManager = project?.service<StateManager>()
        e.presentation.isEnabled = stateManager?.enabled == true
    }
}
