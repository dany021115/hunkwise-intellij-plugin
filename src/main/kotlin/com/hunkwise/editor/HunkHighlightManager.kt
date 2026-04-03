package com.hunkwise.editor

import com.hunkwise.diff.DiffEngine
import com.hunkwise.diff.ParsedHunk
import com.hunkwise.state.FileStatus
import com.hunkwise.state.StateManager
import com.hunkwise.util.PathNormalize
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import java.awt.Color

/**
 * Manages green line highlights for added lines and all block inlays for hunks.
 */
object HunkHighlightManager {

    private val ADDED_LINE_COLOR = Color(0x2a, 0x7d, 0x3a, 0x30)
    private val ADDED_LINE_ATTRIBUTES = TextAttributes().apply {
        backgroundColor = ADDED_LINE_COLOR
    }

    // Track highlighters per editor to clean them up
    private val editorHighlighters = HashMap<Editor, MutableList<RangeHighlighter>>()
    private val editorInlays = HashMap<Editor, MutableList<com.intellij.openapi.editor.Inlay<*>>>()

    /**
     * Update all decorations for a given editor based on current file state.
     */
    fun updateDecorations(editor: Editor, stateManager: StateManager) {
        val vFile = editor.virtualFile ?: return
        val filePath = PathNormalize.normalize(vFile.path)
        val fileState = stateManager.getFile(filePath)

        // Clear existing decorations
        clearDecorations(editor)

        if (fileState == null || fileState.status != FileStatus.REVIEWING) return
        if (!stateManager.settings.showInlineDecorations) return

        val hunks = DiffEngine.computeHunks(fileState.baseline, editor.document.text)
        if (hunks.isEmpty()) return

        val highlighters = mutableListOf<RangeHighlighter>()
        val inlays = mutableListOf<com.intellij.openapi.editor.Inlay<*>>()

        for (hunk in hunks) {
            // Green highlights for added lines
            if (hunk.newLines > 0) {
                val startLine = hunk.newStart - 1
                val endLine = startLine + hunk.newLines - 1
                if (startLine < editor.document.lineCount && endLine < editor.document.lineCount) {
                    val startOffset = editor.document.getLineStartOffset(startLine)
                    val endOffset = editor.document.getLineEndOffset(endLine)
                    val highlighter = editor.markupModel.addRangeHighlighter(
                        startOffset, endOffset,
                        HighlighterLayer.SELECTION - 1,
                        ADDED_LINE_ATTRIBUTES,
                        HighlighterTargetArea.LINES_IN_RANGE
                    )
                    highlighters.add(highlighter)
                }
            }

            // Block inlay for deleted lines (above the hunk)
            if (hunk.removedContent.isNotEmpty()) {
                val inlayLine = maxOf(hunk.newStart - 1, 0)
                val offset = if (inlayLine < editor.document.lineCount) {
                    editor.document.getLineStartOffset(inlayLine)
                } else {
                    editor.document.textLength
                }
                val renderer = DeletedLinesRenderer(hunk.removedContent, editor)
                val inlay = editor.inlayModel.addBlockElement(
                    offset, true, true, 0, renderer
                )
                if (inlay != null) inlays.add(inlay)
            }

            // Block inlay for action bar (below the hunk)
            val actionLine = if (hunk.newLines > 0) {
                hunk.newStart - 1 + hunk.newLines - 1
            } else {
                maxOf(hunk.newStart - 1, 0)
            }
            val actionOffset = if (actionLine < editor.document.lineCount) {
                editor.document.getLineEndOffset(actionLine)
            } else {
                editor.document.textLength
            }
            val actionRenderer = ActionBarRenderer(filePath, hunk.id, editor)
            val actionInlay = editor.inlayModel.addBlockElement(
                actionOffset, true, false, 0, actionRenderer
            )
            if (actionInlay != null) inlays.add(actionInlay)
        }

        editorHighlighters[editor] = highlighters
        editorInlays[editor] = inlays
    }

    fun clearDecorations(editor: Editor) {
        editorHighlighters.remove(editor)?.forEach {
            try { editor.markupModel.removeHighlighter(it) } catch (_: Exception) {}
        }
        editorInlays.remove(editor)?.forEach {
            try { it.dispose() } catch (_: Exception) {}
        }
    }

    /**
     * Get the hunks currently displayed for an editor.
     */
    fun getHunksForEditor(editor: Editor, stateManager: StateManager): List<ParsedHunk> {
        val vFile = editor.virtualFile ?: return emptyList()
        val filePath = PathNormalize.normalize(vFile.path)
        val fileState = stateManager.getFile(filePath) ?: return emptyList()
        if (fileState.status != FileStatus.REVIEWING) return emptyList()
        return DiffEngine.computeHunks(fileState.baseline, editor.document.text)
    }
}
