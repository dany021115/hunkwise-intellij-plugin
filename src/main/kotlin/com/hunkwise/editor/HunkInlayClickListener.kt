package com.hunkwise.editor

import com.hunkwise.actions.HunkActions
import com.hunkwise.state.StateManager
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseListener
import java.awt.Point

/**
 * Listens for mouse clicks on block inlay renderers (ActionBarRenderer)
 * and dispatches Accept/Discard actions accordingly.
 */
class HunkInlayClickListener : EditorMouseListener {

    override fun mouseClicked(event: EditorMouseEvent) {
        val editor = event.editor
        val project = editor.project ?: return
        val mousePoint = event.mouseEvent.point

        // Find all block inlays and check if click is within any ActionBarRenderer bounds
        val inlays = editor.inlayModel.getBlockElementsInRange(0, editor.document.textLength)

        for (inlay in inlays) {
            val renderer = inlay.renderer
            if (renderer !is ActionBarRenderer) continue

            val inlayBounds = inlay.bounds ?: continue

            // Translate button bounds relative to inlay position
            val acceptBounds = renderer.acceptBounds ?: continue
            val discardBounds = renderer.discardBounds ?: continue

            if (acceptBounds.contains(mousePoint)) {
                val stateManager = project.service<StateManager>()
                HunkActions.acceptHunk(project, stateManager, renderer.filePath, renderer.hunkId)
                event.consume()
                return
            }

            if (discardBounds.contains(mousePoint)) {
                val stateManager = project.service<StateManager>()
                HunkActions.discardHunk(project, stateManager, renderer.filePath, renderer.hunkId)
                event.consume()
                return
            }
        }
    }
}
