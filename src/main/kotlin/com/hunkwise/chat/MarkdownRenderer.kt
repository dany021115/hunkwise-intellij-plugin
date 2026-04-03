package com.hunkwise.chat

import java.awt.Color
import java.awt.Font
import javax.swing.JTextPane
import javax.swing.text.StyleConstants
import javax.swing.text.StyledDocument

/**
 * Renders markdown text into a JTextPane with proper formatting.
 * Handles: **bold**, `code`, ```code blocks```, # headers, - bullets, --- separators, numbered lists
 */
object MarkdownRenderer {

    private val ORANGE = Color(0xCE, 0x91, 0x78)
    private val CODE_BG = Color(0x1A, 0x1A, 0x1A)
    private val CODE_FG = Color(0xD4, 0xD4, 0xD4)
    private val GREEN = Color(0x10, 0xB9, 0x81)
    private val YELLOW = Color(0xF5, 0x9E, 0x0B)
    private val RED_TEXT = Color(0xF4, 0x47, 0x47)
    private val BLUE = Color(0x56, 0x9C, 0xD6)

    fun render(pane: JTextPane, text: String, baseColor: Color) {
        val doc = pane.styledDocument
        val lines = text.split("\n")
        var i = 0

        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()

            // Add newline between lines (except first)
            if (i > 0) insertPlain(doc, pane, "\n", baseColor)

            // Code block ```
            if (trimmed.startsWith("```")) {
                val codeLines = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    codeLines.add(lines[i])
                    i++
                }
                if (i < lines.size) i++ // skip closing ```
                insertCodeBlock(doc, pane, codeLines.joinToString("\n"))
                continue
            }

            // Horizontal rule ---
            if (trimmed.matches(Regex("^-{3,}$")) || trimmed.matches(Regex("^\\*{3,}$"))) {
                insertPlain(doc, pane, "────────────────────────────────", Color(0x3E, 0x3E, 0x3E))
                i++; continue
            }

            // Headers
            if (trimmed.startsWith("### ")) {
                insertStyled(doc, pane, trimmed.removePrefix("### "), baseColor, bold = true, size = 13)
                i++; continue
            }
            if (trimmed.startsWith("## ")) {
                insertStyled(doc, pane, trimmed.removePrefix("## "), baseColor, bold = true, size = 14)
                i++; continue
            }
            if (trimmed.startsWith("# ")) {
                insertStyled(doc, pane, trimmed.removePrefix("# "), baseColor, bold = true, size = 16)
                i++; continue
            }

            // Bullet points
            if (trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("• ")) {
                val bulletText = trimmed.removePrefix("- ").removePrefix("* ").removePrefix("• ")
                val indent = if (line.startsWith("  ")) "    " else "  "
                insertPlain(doc, pane, "$indent\u2022 ", BLUE)
                renderInline(doc, pane, bulletText, baseColor)
                i++; continue
            }

            // Numbered lists (1. 2. etc)
            val numMatch = Regex("^(\\d+)\\.\\s+(.*)").find(trimmed)
            if (numMatch != null) {
                val num = numMatch.groupValues[1]
                val content = numMatch.groupValues[2]
                insertPlain(doc, pane, "  $num. ", BLUE)
                renderInline(doc, pane, content, baseColor)
                i++; continue
            }

            // Normal line
            renderInline(doc, pane, line, baseColor)
            i++
        }
    }

    /** Render inline formatting: **bold**, `code`, ~~strike~~, emoji indicators */
    private fun renderInline(doc: StyledDocument, pane: JTextPane, text: String, baseColor: Color) {
        var pos = 0
        while (pos < text.length) {
            when {
                // **bold**
                text.startsWith("**", pos) -> {
                    val end = text.indexOf("**", pos + 2)
                    if (end > pos) {
                        insertStyled(doc, pane, text.substring(pos + 2, end), baseColor, bold = true, size = 12)
                        pos = end + 2
                    } else {
                        insertPlain(doc, pane, "*", baseColor); pos++
                    }
                }
                // `inline code`
                text[pos] == '`' && !text.startsWith("```", pos) -> {
                    val end = text.indexOf('`', pos + 1)
                    if (end > pos) {
                        insertInlineCode(doc, pane, text.substring(pos + 1, end))
                        pos = end + 1
                    } else {
                        insertPlain(doc, pane, "`", baseColor); pos++
                    }
                }
                // Risk indicators with color
                text.startsWith("🔴", pos) || text.startsWith("High risk", pos) -> {
                    val emoji = if (text.startsWith("🔴", pos)) "🔴" else ""
                    if (emoji.isNotEmpty()) {
                        insertStyled(doc, pane, "\u25CF ", RED_TEXT, bold = true, size = 12)
                        pos += emoji.length
                    } else {
                        insertStyled(doc, pane, "HIGH RISK", RED_TEXT, bold = true, size = 12)
                        pos += 9
                    }
                }
                text.startsWith("⚠️", pos) || text.startsWith("Medium risk", pos) -> {
                    val emoji = if (text.startsWith("⚠️", pos)) "⚠️" else ""
                    if (emoji.isNotEmpty()) {
                        insertStyled(doc, pane, "\u25CF ", YELLOW, bold = true, size = 12)
                        pos += emoji.length
                    } else {
                        insertStyled(doc, pane, "MEDIUM RISK", YELLOW, bold = true, size = 12)
                        pos += 11
                    }
                }
                text.startsWith("✅", pos) || text.startsWith("Low risk", pos) -> {
                    val emoji = if (text.startsWith("✅", pos)) "✅" else ""
                    if (emoji.isNotEmpty()) {
                        insertStyled(doc, pane, "\u2713 ", GREEN, bold = true, size = 12)
                        pos += emoji.length
                    } else {
                        insertStyled(doc, pane, "LOW RISK", GREEN, bold = true, size = 12)
                        pos += 8
                    }
                }
                else -> {
                    // Find next special character
                    val specials = listOf(
                        text.indexOf("**", pos + 1),
                        text.indexOf('`', pos + 1),
                        text.indexOf("🔴", pos + 1),
                        text.indexOf("⚠️", pos + 1),
                        text.indexOf("✅", pos + 1)
                    ).filter { it >= 0 }

                    val nextSpecial = if (specials.isEmpty()) text.length else specials.min()
                    insertPlain(doc, pane, text.substring(pos, nextSpecial), baseColor)
                    pos = nextSpecial
                }
            }
        }
    }

    private fun insertPlain(doc: StyledDocument, pane: JTextPane, text: String, color: Color) {
        val s = pane.addStyle("p${System.nanoTime()}", null)
        StyleConstants.setForeground(s, color)
        StyleConstants.setFontFamily(s, "SansSerif")
        StyleConstants.setFontSize(s, 12)
        doc.insertString(doc.length, text, s)
    }

    private fun insertStyled(doc: StyledDocument, pane: JTextPane, text: String, color: Color, bold: Boolean, size: Int) {
        val s = pane.addStyle("b${System.nanoTime()}", null)
        StyleConstants.setForeground(s, color)
        StyleConstants.setBold(s, bold)
        StyleConstants.setFontFamily(s, "SansSerif")
        StyleConstants.setFontSize(s, size)
        doc.insertString(doc.length, text, s)
    }

    private fun insertInlineCode(doc: StyledDocument, pane: JTextPane, text: String) {
        val s = pane.addStyle("c${System.nanoTime()}", null)
        StyleConstants.setForeground(s, ORANGE)
        StyleConstants.setBackground(s, CODE_BG)
        StyleConstants.setFontFamily(s, "JetBrains Mono")
        StyleConstants.setFontSize(s, 11)
        doc.insertString(doc.length, " $text ", s)
    }

    private fun insertCodeBlock(doc: StyledDocument, pane: JTextPane, text: String) {
        // Add a newline before the code block
        insertPlain(doc, pane, "\n", Color(0x0A, 0x0A, 0x0A))

        val s = pane.addStyle("cb${System.nanoTime()}", null)
        StyleConstants.setForeground(s, CODE_FG)
        StyleConstants.setBackground(s, CODE_BG)
        StyleConstants.setFontFamily(s, "JetBrains Mono")
        StyleConstants.setFontSize(s, 11)

        // Add each line with padding
        for (line in text.split("\n")) {
            doc.insertString(doc.length, "  $line\n", s)
        }
    }
}
