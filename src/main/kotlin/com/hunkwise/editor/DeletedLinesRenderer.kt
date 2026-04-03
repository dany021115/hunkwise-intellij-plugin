package com.hunkwise.editor

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.markup.TextAttributes
import java.awt.Color
import java.awt.Graphics2D
import java.awt.geom.Rectangle2D

/**
 * Renders deleted lines as a red-tinted block inlay between editor lines.
 */
class DeletedLinesRenderer(
    private val removedLines: List<String>,
    private val editor: Editor
) : EditorCustomElementRenderer {

    companion object {
        private val DELETED_BG_COLOR = Color(0xff, 0x00, 0x00, 0x1a) // semi-transparent red
        private val DELETED_LINE_BG = Color(0xff, 0x00, 0x00, 0x10)
        private val DELETED_TEXT_COLOR = Color(0xcc, 0x33, 0x33)
        private val LINE_PREFIX = "- "
    }

    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        return editor.scrollingModel.visibleArea.width.coerceAtLeast(800)
    }

    override fun calcHeightInPixels(inlay: Inlay<*>): Int {
        return editor.lineHeight * removedLines.size
    }

    override fun paint(
        inlay: Inlay<*>,
        g: Graphics2D,
        targetRegion: Rectangle2D,
        textAttributes: TextAttributes
    ) {
        val x = targetRegion.x.toInt()
        val y = targetRegion.y.toInt()
        val width = targetRegion.width.toInt()
        val lineHeight = editor.lineHeight

        // Background
        g.color = DELETED_BG_COLOR
        g.fillRect(x, y, width, lineHeight * removedLines.size)

        // Text
        val font = editor.colorsScheme.getFont(EditorFontType.PLAIN)
        g.font = font
        val metrics = g.fontMetrics
        val ascent = metrics.ascent
        val gutterWidth = 0

        for ((index, line) in removedLines.withIndex()) {
            val lineY = y + index * lineHeight

            // Line background stripe
            g.color = DELETED_LINE_BG
            g.fillRect(x, lineY, width, lineHeight)

            // Line text
            g.color = DELETED_TEXT_COLOR
            val displayText = LINE_PREFIX + line
            g.drawString(displayText, x + 8, lineY + ascent)
        }

        // Bottom border
        g.color = Color(0xff, 0x00, 0x00, 0x30)
        g.drawLine(x, y + lineHeight * removedLines.size - 1, x + width, y + lineHeight * removedLines.size - 1)
    }
}
