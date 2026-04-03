package com.hunkwise.editor

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.markup.TextAttributes
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.geom.Rectangle2D

/**
 * Renders Accept/Discard action buttons as a block inlay below hunk additions.
 * Click handling is done by HunkInlayClickListener which checks hit regions.
 */
class ActionBarRenderer(
    val filePath: String,
    val hunkId: String,
    private val editor: Editor
) : EditorCustomElementRenderer {

    companion object {
        private val ACCEPT_COLOR = Color(0x2a, 0x7d, 0x3a)
        private val ACCEPT_HOVER_COLOR = Color(0x3a, 0x9d, 0x4a)
        private val DISCARD_COLOR = Color(0xf8, 0x51, 0x49)
        private val DISCARD_HOVER_COLOR = Color(0xff, 0x71, 0x69)
        private val BAR_BG_COLOR = Color(0x00, 0x00, 0x00, 0x08)
        private val BUTTON_HEIGHT = 20
        private val BUTTON_PADDING_H = 12
        private val BUTTON_SPACING = 8
        private val BAR_PADDING_LEFT = 8

        const val ACCEPT_TEXT = "\u2713 Accept"
        const val DISCARD_TEXT = "\u21BA Discard"
    }

    // Computed button regions for click detection
    var acceptBounds: Rectangle? = null
        private set
    var discardBounds: Rectangle? = null
        private set

    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        return editor.scrollingModel.visibleArea.width.coerceAtLeast(800)
    }

    override fun calcHeightInPixels(inlay: Inlay<*>): Int {
        return BUTTON_HEIGHT + 6 // button + vertical padding
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
        val barHeight = calcHeightInPixels(inlay)

        // Bar background
        g.color = BAR_BG_COLOR
        g.fillRect(x, y, width, barHeight)

        val font = editor.colorsScheme.getFont(EditorFontType.PLAIN).deriveFont(Font.PLAIN, 12f)
        g.font = font
        val metrics = g.fontMetrics
        val ascent = metrics.ascent

        val buttonY = y + 3

        // Accept button
        val acceptWidth = metrics.stringWidth(ACCEPT_TEXT) + BUTTON_PADDING_H * 2
        val acceptX = x + BAR_PADDING_LEFT
        acceptBounds = Rectangle(acceptX, buttonY, acceptWidth, BUTTON_HEIGHT)

        g.color = ACCEPT_COLOR
        g.fillRoundRect(acceptX, buttonY, acceptWidth, BUTTON_HEIGHT, 6, 6)
        g.color = Color.WHITE
        g.drawString(ACCEPT_TEXT, acceptX + BUTTON_PADDING_H, buttonY + ascent + (BUTTON_HEIGHT - metrics.height) / 2)

        // Discard button
        val discardWidth = metrics.stringWidth(DISCARD_TEXT) + BUTTON_PADDING_H * 2
        val discardX = acceptX + acceptWidth + BUTTON_SPACING
        discardBounds = Rectangle(discardX, buttonY, discardWidth, BUTTON_HEIGHT)

        g.color = DISCARD_COLOR
        g.fillRoundRect(discardX, buttonY, discardWidth, BUTTON_HEIGHT, 6, 6)
        g.color = Color.WHITE
        g.drawString(DISCARD_TEXT, discardX + BUTTON_PADDING_H, buttonY + ascent + (BUTTON_HEIGHT - metrics.height) / 2)

        // Top border
        g.color = Color(0x00, 0x00, 0x00, 0x10)
        g.drawLine(x, y, x + width, y)
    }
}
