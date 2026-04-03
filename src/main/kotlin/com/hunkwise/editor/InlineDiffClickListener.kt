package com.hunkwise.editor

import com.intellij.openapi.components.service
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.VirtualFile

/**
 * Listens for mouse clicks on inline diff Accept/Discard buttons.
 * Registers on all editors that open.
 */
class InlineDiffClickListener : ProjectActivity {

    override suspend fun execute(project: Project) {
        val clickListener = object : EditorMouseListener {
            override fun mouseClicked(event: EditorMouseEvent) {
                val editor = event.editor
                val point = event.mouseEvent.point

                // Check all block inlays for AcceptDiscardRenderer
                val inlays = editor.inlayModel.getBlockElementsInRange(0, editor.document.textLength)
                for (inlay in inlays) {
                    val renderer = inlay.renderer
                    // Use reflection-free check
                    if (renderer.javaClass.name.contains("AcceptDiscardRenderer")) {
                        val bounds = inlay.bounds ?: continue

                        // Check if click is within inlay bounds
                        if (point.y >= bounds.y && point.y <= bounds.y + bounds.height) {
                            try {
                                val acceptMethod = renderer.javaClass.getMethod("getAcceptBounds")
                                val discardMethod = renderer.javaClass.getMethod("getDiscardBounds")
                                val acceptBounds = acceptMethod.invoke(renderer) as java.awt.Rectangle
                                val discardBounds = discardMethod.invoke(renderer) as java.awt.Rectangle

                                if (acceptBounds.contains(point)) {
                                    val accept = renderer.javaClass.getMethod("accept")
                                    accept.invoke(renderer)
                                    event.consume()
                                    return
                                }
                                if (discardBounds.contains(point)) {
                                    val discard = renderer.javaClass.getMethod("discard")
                                    discard.invoke(renderer)
                                    event.consume()
                                    return
                                }
                            } catch (_: Exception) {}
                        }
                    }
                }
            }
        }

        // Register on all editors
        project.messageBus.connect().subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                    for (fe in source.getEditors(file)) {
                        if (fe is TextEditor) {
                            fe.editor.addEditorMouseListener(clickListener)
                        }
                    }
                }
            }
        )
    }
}
