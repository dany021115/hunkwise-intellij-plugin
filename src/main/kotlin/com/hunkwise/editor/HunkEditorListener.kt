package com.hunkwise.editor

import com.hunkwise.state.StateChangeListener
import com.hunkwise.state.StateManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.VirtualFile

/**
 * Listens for editor events and state changes to keep hunk decorations in sync.
 * Registers the click listener on editors and refreshes decorations on state change.
 */
class HunkEditorListener : ProjectActivity {

    override suspend fun execute(project: Project) {
        val stateManager = project.service<StateManager>()
        val clickListener = HunkInlayClickListener()

        // Register click listener on all existing editors
        EditorFactory.getInstance().allEditors.forEach { editor ->
            if (editor.project == project) {
                editor.addEditorMouseListener(clickListener)
            }
        }

        // Listen for new editors opening
        project.messageBus.connect().subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                    source.getEditors(file).forEach { fileEditor ->
                        if (fileEditor is TextEditor) {
                            val editor = fileEditor.editor
                            editor.addEditorMouseListener(clickListener)
                            // Initial decoration
                            ApplicationManager.getApplication().invokeLater {
                                HunkHighlightManager.updateDecorations(editor, stateManager)
                            }
                        }
                    }
                }

                override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
                    // Cleanup handled by editor disposal
                }
            }
        )

        // Refresh all editor decorations on state change
        project.messageBus.connect().subscribe(
            StateManager.STATE_CHANGED_TOPIC,
            object : StateChangeListener {
                override fun stateChanged() {
                    ApplicationManager.getApplication().invokeLater {
                        refreshAllEditors(project, stateManager)
                    }
                }
            }
        )

        // Initial decoration for already-open editors
        ApplicationManager.getApplication().invokeLater {
            refreshAllEditors(project, stateManager)
        }
    }

    private fun refreshAllEditors(project: Project, stateManager: StateManager) {
        val fem = FileEditorManager.getInstance(project)
        for (file in fem.openFiles) {
            for (editor in fem.getEditors(file)) {
                if (editor is TextEditor) {
                    HunkHighlightManager.updateDecorations(editor.editor, stateManager)
                }
            }
        }
    }
}
