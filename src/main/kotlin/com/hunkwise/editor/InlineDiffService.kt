package com.hunkwise.editor

import com.hunkwise.HunkwiseColors
import com.hunkwise.git.ProjectGitService
import com.hunkwise.git.ProjectGitService.DiffHunk
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.awt.*
import java.awt.geom.Rectangle2D

@Service(Service.Level.PROJECT)
class InlineDiffService(private val project: Project) {

    // Track per-editor to avoid clearing wrong ones
    private val editorAnnotations = mutableMapOf<Editor, MutableList<Any>>()

    fun openFileWithDiff(filePath: String) {
        val gitService = project.service<ProjectGitService>()

        Thread {
            val diff = gitService.getFileDiff(filePath)
            if (diff == null || diff.hunks.isEmpty()) {
                // No diff — just open the file
                javax.swing.SwingUtilities.invokeLater {
                    val vFile = LocalFileSystem.getInstance().findFileByPath(filePath) ?: return@invokeLater
                    FileEditorManager.getInstance(project).openFile(vFile, true)
                }
                return@Thread
            }

            javax.swing.SwingUtilities.invokeLater {
                val vFile = LocalFileSystem.getInstance().findFileByPath(filePath) ?: return@invokeLater

                // Force open in a new tab or focus existing
                val fileEditors = FileEditorManager.getInstance(project).openFile(vFile, true, true)

                for (fe in fileEditors) {
                    if (fe is TextEditor) {
                        val editor = fe.editor

                        // Clear any existing annotations on THIS editor
                        clearEditor(editor)

                        // Apply inline diff
                        applyInlineDiff(editor, diff.hunks, filePath)

                        // Scroll to first hunk
                        val line = maxOf(0, diff.hunks[0].newStart - 1)
                        if (line < editor.document.lineCount) {
                            editor.caretModel.moveToOffset(editor.document.getLineStartOffset(line))
                            editor.scrollingModel.scrollToCaret(com.intellij.openapi.editor.ScrollType.CENTER)
                        }
                        break
                    }
                }
            }
        }.start()
    }

    private fun applyInlineDiff(editor: Editor, hunks: List<DiffHunk>, filePath: String) {
        val gitService = project.service<ProjectGitService>()
        val annotations = mutableListOf<Any>()

        for (hunk in hunks) {
            // 1. Green background on added lines
            if (hunk.newLines > 0) {
                val startLine = hunk.newStart - 1
                val endLine = startLine + hunk.newLines - 1
                if (startLine < editor.document.lineCount && endLine < editor.document.lineCount) {
                    val startOffset = editor.document.getLineStartOffset(startLine)
                    val endOffset = editor.document.getLineEndOffset(endLine)
                    val attrs = TextAttributes().apply { backgroundColor = HunkwiseColors.ADDED_BG }
                    val hl = editor.markupModel.addRangeHighlighter(
                        startOffset, endOffset, HighlighterLayer.SELECTION - 1,
                        attrs, HighlighterTargetArea.LINES_IN_RANGE
                    )
                    annotations.add(hl)
                }
            }

            // 2. Red block inlay for deleted lines (above hunk)
            if (hunk.removedContent.isNotEmpty()) {
                val inlayLine = maxOf(hunk.newStart - 1, 0)
                val offset = if (inlayLine < editor.document.lineCount)
                    editor.document.getLineStartOffset(inlayLine) else editor.document.textLength
                val renderer = DeletedLinesBlock(hunk.removedContent, editor)
                val inlay = editor.inlayModel.addBlockElement(offset, true, true, 0, renderer)
                if (inlay != null) annotations.add(inlay)
            }

            // 3. Accept/Discard buttons (below hunk)
            val actionLine = if (hunk.newLines > 0) hunk.newStart - 1 + hunk.newLines - 1
                else maxOf(hunk.newStart - 1, 0)
            val actionOffset = if (actionLine < editor.document.lineCount)
                editor.document.getLineEndOffset(actionLine) else editor.document.textLength

            val onAction = {
                // After accept/discard → clear and re-apply with fresh diff
                clearEditor(editor)
                Thread {
                    val newDiff = gitService.getFileDiff(filePath)
                    javax.swing.SwingUtilities.invokeLater {
                        if (newDiff != null && newDiff.hunks.isNotEmpty()) {
                            applyInlineDiff(editor, newDiff.hunks, filePath)
                        }
                    }
                }.start()
            }

            val acceptAction = {
                Thread { gitService.acceptHunk(filePath, hunk); javax.swing.SwingUtilities.invokeLater(onAction) }.start()
            }
            val discardAction = {
                Thread { gitService.discardHunk(filePath, hunk); javax.swing.SwingUtilities.invokeLater(onAction) }.start()
            }

            val actionRenderer = AcceptDiscardBlock(editor, acceptAction, discardAction)
            val actionInlay = editor.inlayModel.addBlockElement(actionOffset, true, false, 0, actionRenderer)
            if (actionInlay != null) annotations.add(actionInlay)
        }

        editorAnnotations[editor] = annotations
    }

    private fun clearEditor(editor: Editor) {
        editorAnnotations.remove(editor)?.forEach { ann ->
            try {
                when (ann) {
                    is RangeHighlighter -> editor.markupModel.removeHighlighter(ann)
                    is Inlay<*> -> ann.dispose()
                }
            } catch (_: Exception) {}
        }
    }

    // ── Deleted lines block ─────────────────────────────────────────

    private class DeletedLinesBlock(
        private val lines: List<String>,
        private val editor: Editor
    ) : EditorCustomElementRenderer {

        override fun calcWidthInPixels(inlay: Inlay<*>) =
            editor.scrollingModel.visibleArea.width.coerceAtLeast(600)

        override fun calcHeightInPixels(inlay: Inlay<*>) =
            editor.lineHeight * lines.size

        override fun paint(inlay: Inlay<*>, g: Graphics2D, r: Rectangle2D, ta: TextAttributes) {
            val x = r.x.toInt(); val y = r.y.toInt(); val w = r.width.toInt()
            val lh = editor.lineHeight
            g.font = editor.colorsScheme.getFont(EditorFontType.PLAIN)
            val ascent = g.fontMetrics.ascent

            for ((i, line) in lines.withIndex()) {
                val ly = y + i * lh
                g.color = HunkwiseColors.REMOVED_BG; g.fillRect(x, ly, w, lh)
                g.color = HunkwiseColors.RED; g.drawString("- $line", x + 8, ly + ascent)
            }
            g.color = Color(HunkwiseColors.RED.red, HunkwiseColors.RED.green, HunkwiseColors.RED.blue, 0x40)
            g.drawLine(x, y + lh * lines.size - 1, x + w, y + lh * lines.size - 1)
        }
    }

    // ── Accept/Discard buttons block ────────────────────────────────

    class AcceptDiscardBlock(
        private val editor: Editor,
        val onAccept: () -> Unit,
        val onDiscard: () -> Unit
    ) : EditorCustomElementRenderer {

        val acceptBounds = Rectangle()
        val discardBounds = Rectangle()

        override fun calcWidthInPixels(inlay: Inlay<*>) =
            editor.scrollingModel.visibleArea.width.coerceAtLeast(600)

        override fun calcHeightInPixels(inlay: Inlay<*>) = 26

        override fun paint(inlay: Inlay<*>, g: Graphics2D, r: Rectangle2D, ta: TextAttributes) {
            val x = r.x.toInt(); val y = r.y.toInt(); val w = r.width.toInt()
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = HunkwiseColors.BAR_BG; g.fillRect(x, y, w, 26)

            val font = Font("SansSerif", Font.BOLD, 12); g.font = font
            val fm = g.fontMetrics; val btnY = y + 3; val btnH = 20

            // Accept
            val aText = "\u2713 Accept"; val aW = fm.stringWidth(aText) + 20
            acceptBounds.setBounds(x + 8, btnY, aW, btnH)
            g.color = HunkwiseColors.ACCEPT; g.fillRoundRect(x + 8, btnY, aW, btnH, 6, 6)
            g.color = Color.WHITE; g.drawString(aText, x + 18, btnY + fm.ascent + (btnH - fm.height) / 2)

            // Discard
            val dText = "\u21BA Discard"; val dW = fm.stringWidth(dText) + 20; val dX = x + 8 + aW + 8
            discardBounds.setBounds(dX, btnY, dW, btnH)
            g.color = HunkwiseColors.DISCARD; g.fillRoundRect(dX, btnY, dW, btnH, 6, 6)
            g.color = Color.WHITE; g.drawString(dText, dX + 10, btnY + fm.ascent + (btnH - fm.height) / 2)

            g.color = HunkwiseColors.BORDER; g.drawLine(x, y, x + w, y)
        }
    }
}
