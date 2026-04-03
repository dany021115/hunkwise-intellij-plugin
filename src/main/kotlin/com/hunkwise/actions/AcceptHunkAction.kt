package com.hunkwise.actions

import com.hunkwise.editor.HunkHighlightManager
import com.hunkwise.state.StateManager
import com.hunkwise.util.PathNormalize
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service

class AcceptHunkAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val stateManager = project.service<StateManager>()

        val vFile = editor.virtualFile ?: return
        val filePath = PathNormalize.normalize(vFile.path)
        val hunks = HunkHighlightManager.getHunksForEditor(editor, stateManager)

        // Find hunk at cursor position
        val caretLine = editor.caretModel.logicalPosition.line + 1
        val hunk = hunks.firstOrNull { caretLine in it.newStart..(it.newStart + it.newLines - 1) }
            ?: hunks.firstOrNull()
            ?: return

        HunkActions.acceptHunk(project, stateManager, filePath, hunk.id)
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val stateManager = project?.service<StateManager>()
        e.presentation.isEnabled = stateManager?.enabled == true
    }
}
