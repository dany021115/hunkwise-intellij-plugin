package com.hunkwise.editor

import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.VirtualFile

/**
 * Registers click listener on all editors to handle Accept/Discard button clicks
 * on inline diff block inlays.
 */
class InlineDiffClickListener : ProjectActivity {

    override suspend fun execute(project: Project) {
        val listener = object : EditorMouseListener {
            override fun mouseClicked(event: EditorMouseEvent) {
                val editor = event.editor
                val point = event.mouseEvent.point

                val inlays = editor.inlayModel.getBlockElementsInRange(0, editor.document.textLength)
                for (inlay in inlays) {
                    val renderer = inlay.renderer
                    if (renderer is InlineDiffService.AcceptDiscardBlock) {
                        val bounds = inlay.bounds ?: continue
                        if (point.y >= bounds.y && point.y <= bounds.y + bounds.height) {
                            if (renderer.acceptBounds.contains(point)) {
                                renderer.onAccept()
                                event.consume()
                                return
                            }
                            if (renderer.discardBounds.contains(point)) {
                                renderer.onDiscard()
                                event.consume()
                                return
                            }
                        }
                    }
                }
            }
        }

        // Register on new editors
        project.messageBus.connect().subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                    for (fe in source.getEditors(file)) {
                        if (fe is TextEditor) fe.editor.addEditorMouseListener(listener)
                    }
                }
            }
        )

        // Register on already-open editors
        for (file in FileEditorManager.getInstance(project).openFiles) {
            for (fe in FileEditorManager.getInstance(project).getEditors(file)) {
                if (fe is TextEditor) fe.editor.addEditorMouseListener(listener)
            }
        }
    }
}
