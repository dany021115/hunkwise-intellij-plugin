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
import java.io.File

/**
 * Opens a file in the editor with inline diff annotations:
 * - Green background on added lines
 * - Red block inlay showing deleted lines
 * - Accept/Discard buttons per hunk
 *
 * Like Cursor / VS Code hunkwise inline review.
 */
@Service(Service.Level.PROJECT)
class InlineDiffService(private val project: Project) {

    private val activeInlays = mutableListOf<Inlay<*>>()
    private val activeHighlighters = mutableListOf<RangeHighlighter>()

    fun openFileWithDiff(filePath: String) {
        val gitService = project.service<ProjectGitService>()

        // Get diff in background
        Thread {
            val diff = gitService.getFileDiff(filePath) ?: return@Thread
            if (diff.hunks.isEmpty()) return@Thread

            javax.swing.SwingUtilities.invokeLater {
                val vFile = LocalFileSystem.getInstance().findFileByPath(filePath) ?: return@invokeLater
                val fileEditors = FileEditorManager.getInstance(project).openFile(vFile, true)

                for (fe in fileEditors) {
                    if (fe is TextEditor) {
                        val editor = fe.editor
                        clearAnnotations(editor)
                        applyInlineDiff(editor, diff.hunks, filePath)
                        // Scroll to first hunk
                        if (diff.hunks.isNotEmpty()) {
                            val line = maxOf(0, diff.hunks[0].newStart - 1)
                            if (line < editor.document.lineCount) {
                                editor.caretModel.moveToOffset(editor.document.getLineStartOffset(line))
                                editor.scrollingModel.scrollToCaret(com.intellij.openapi.editor.ScrollType.CENTER)
                            }
                        }
                        break
                    }
                }
            }
        }.start()
    }

    private fun applyInlineDiff(editor: Editor, hunks: List<DiffHunk>, filePath: String) {
        val gitService = project.service<ProjectGitService>()

        for (hunk in hunks) {
            // 1. Highlight added lines (green background)
            if (hunk.newLines > 0) {
                val startLine = hunk.newStart - 1
                val endLine = startLine + hunk.newLines - 1
                if (startLine < editor.document.lineCount && endLine < editor.document.lineCount) {
                    val startOffset = editor.document.getLineStartOffset(startLine)
                    val endOffset = editor.document.getLineEndOffset(endLine)
                    val attrs = TextAttributes().apply {
                        backgroundColor = HunkwiseColors.ADDED_BG
                    }
                    val hl = editor.markupModel.addRangeHighlighter(
                        startOffset, endOffset,
                        HighlighterLayer.SELECTION - 1,
                        attrs,
                        HighlighterTargetArea.LINES_IN_RANGE
                    )
                    activeHighlighters.add(hl)
                }
            }

            // 2. Block inlay for deleted lines (above the hunk)
            if (hunk.removedContent.isNotEmpty()) {
                val inlayLine = maxOf(hunk.newStart - 1, 0)
                val offset = if (inlayLine < editor.document.lineCount) {
                    editor.document.getLineStartOffset(inlayLine)
                } else {
                    editor.document.textLength
                }
                val renderer = DeletedLinesBlockRenderer(hunk.removedContent, editor)
                val inlay = editor.inlayModel.addBlockElement(offset, true, true, 0, renderer)
                if (inlay != null) activeInlays.add(inlay)
            }

            // 3. Block inlay for Accept/Discard buttons (below the hunk)
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
            val actionRenderer = AcceptDiscardRenderer(editor, filePath, hunk, gitService) {
                // After accept/discard, clear and re-apply
                clearAnnotations(editor)
                Thread {
                    val newDiff = gitService.getFileDiff(filePath)
                    javax.swing.SwingUtilities.invokeLater {
                        if (newDiff != null && newDiff.hunks.isNotEmpty()) {
                            applyInlineDiff(editor, newDiff.hunks, filePath)
                        }
                    }
                }.start()
            }
            val actionInlay = editor.inlayModel.addBlockElement(actionOffset, true, false, 0, actionRenderer)
            if (actionInlay != null) activeInlays.add(actionInlay)
        }
    }

    fun clearAnnotations(editor: Editor) {
        activeHighlighters.forEach {
            try { editor.markupModel.removeHighlighter(it) } catch (_: Exception) {}
        }
        activeHighlighters.clear()
        activeInlays.forEach {
            try { it.dispose() } catch (_: Exception) {}
        }
        activeInlays.clear()
    }

    // ── Deleted lines renderer (red block above hunk) ───────────────

    private class DeletedLinesBlockRenderer(
        private val lines: List<String>,
        private val editor: Editor
    ) : EditorCustomElementRenderer {

        override fun calcWidthInPixels(inlay: Inlay<*>): Int =
            editor.scrollingModel.visibleArea.width.coerceAtLeast(600)

        override fun calcHeightInPixels(inlay: Inlay<*>): Int =
            editor.lineHeight * lines.size

        override fun paint(inlay: Inlay<*>, g: Graphics2D, targetRegion: Rectangle2D, textAttributes: TextAttributes) {
            val x = targetRegion.x.toInt()
            val y = targetRegion.y.toInt()
            val w = targetRegion.width.toInt()
            val lineH = editor.lineHeight
            val font = editor.colorsScheme.getFont(EditorFontType.PLAIN)
            g.font = font
            val ascent = g.fontMetrics.ascent

            for ((i, line) in lines.withIndex()) {
                val ly = y + i * lineH
                g.color = HunkwiseColors.REMOVED_BG
                g.fillRect(x, ly, w, lineH)
                g.color = HunkwiseColors.RED
                g.drawString("- $line", x + 8, ly + ascent)
            }

            // Bottom border
            g.color = Color(HunkwiseColors.RED.red, HunkwiseColors.RED.green, HunkwiseColors.RED.blue, 0x40)
            g.drawLine(x, y + lineH * lines.size - 1, x + w, y + lineH * lines.size - 1)
        }
    }

    // ── Accept/Discard buttons renderer ─────────────────────────────

    private class AcceptDiscardRenderer(
        private val editor: Editor,
        private val filePath: String,
        private val hunk: DiffHunk,
        private val gitService: ProjectGitService,
        private val onAction: () -> Unit
    ) : EditorCustomElementRenderer {

        private val ACCEPT_RECT = Rectangle()
        private val DISCARD_RECT = Rectangle()

        override fun calcWidthInPixels(inlay: Inlay<*>): Int =
            editor.scrollingModel.visibleArea.width.coerceAtLeast(600)

        override fun calcHeightInPixels(inlay: Inlay<*>): Int = 26

        override fun paint(inlay: Inlay<*>, g: Graphics2D, targetRegion: Rectangle2D, textAttributes: TextAttributes) {
            val x = targetRegion.x.toInt()
            val y = targetRegion.y.toInt()
            val w = targetRegion.width.toInt()
            val h = 26

            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            // Background
            g.color = HunkwiseColors.BAR_BG
            g.fillRect(x, y, w, h)

            val font = Font("JetBrains Mono", Font.BOLD, 12)
            g.font = font
            val fm = g.fontMetrics

            // Accept button
            val acceptText = "\u2713 Accept"
            val acceptW = fm.stringWidth(acceptText) + 20
            val btnY = y + 3
            val btnH = 20

            ACCEPT_RECT.setBounds(x + 8, btnY, acceptW, btnH)
            g.color = HunkwiseColors.ACCEPT
            g.fillRoundRect(x + 8, btnY, acceptW, btnH, 6, 6)
            g.color = Color.WHITE
            g.drawString(acceptText, x + 18, btnY + fm.ascent + (btnH - fm.height) / 2)

            // Discard button
            val discardText = "\u21BA Discard"
            val discardW = fm.stringWidth(discardText) + 20
            val discardX = x + 8 + acceptW + 8

            DISCARD_RECT.setBounds(discardX, btnY, discardW, btnH)
            g.color = HunkwiseColors.DISCARD
            g.fillRoundRect(discardX, btnY, discardW, btnH, 6, 6)
            g.color = Color.WHITE
            g.drawString(discardText, discardX + 10, btnY + fm.ascent + (btnH - fm.height) / 2)

            // Top border
            g.color = HunkwiseColors.BORDER
            g.drawLine(x, y, x + w, y)
        }

        // Handle clicks via EditorMouseListener (registered separately)
        fun getAcceptBounds() = ACCEPT_RECT
        fun getDiscardBounds() = DISCARD_RECT

        fun accept() {
            Thread {
                gitService.acceptHunk(filePath, hunk)
                javax.swing.SwingUtilities.invokeLater { onAction() }
            }.start()
        }

        fun discard() {
            Thread {
                gitService.discardHunk(filePath, hunk)
                javax.swing.SwingUtilities.invokeLater { onAction() }
            }.start()
        }
    }
}
