package com.hunkwise.chat

import com.hunkwise.HunkwiseColors
import com.intellij.openapi.project.Project
import java.awt.*
import java.io.File
import javax.swing.*
import javax.swing.border.EmptyBorder

/**
 * Visual block components embeddable in the chat JTextPane.
 */
object ChatBlockRenderer {

    fun createPermissionBlock(
        project: Project,
        description: String,
        command: String,
        width: Int = 400,
        onResult: ((String) -> Unit)? = null
    ): JPanel {
        val panel = JPanel(BorderLayout())
        panel.background = HunkwiseColors.PERMISSION_BG
        panel.border = BorderFactory.createLineBorder(HunkwiseColors.PERMISSION_BORDER, 1, true)
        panel.maximumSize = Dimension(width, 200)

        val header = JPanel(BorderLayout())
        header.background = HunkwiseColors.PERMISSION_BG
        header.border = EmptyBorder(8, 12, 4, 12)
        header.add(label("Run outside sandbox: $description", HunkwiseColors.MUTED, 11), BorderLayout.WEST)
        panel.add(header, BorderLayout.NORTH)

        val cmdPanel = JPanel(BorderLayout())
        cmdPanel.background = Color(0x0A, 0x12, 0x20)
        cmdPanel.border = EmptyBorder(8, 12, 8, 12)
        val cmdText = if (command.length > 80) command.take(80) + "..." else command
        val cmdLabel = JLabel("\$ $cmdText")
        cmdLabel.font = Font("JetBrains Mono", Font.BOLD, 11)
        cmdLabel.foreground = HunkwiseColors.FG
        cmdPanel.add(cmdLabel, BorderLayout.WEST)
        panel.add(cmdPanel, BorderLayout.CENTER)

        val resultLabel = JLabel("")
        resultLabel.font = Font("JetBrains Mono", Font.PLAIN, 10)
        resultLabel.foreground = HunkwiseColors.MUTED
        resultLabel.border = EmptyBorder(4, 12, 4, 12)
        resultLabel.isVisible = false

        val btnPanel = JPanel(BorderLayout())
        btnPanel.background = HunkwiseColors.PERMISSION_BG
        btnPanel.border = EmptyBorder(6, 12, 8, 12)

        val skipBtn = label("Skip Esc", HunkwiseColors.MUTED, 11)
        skipBtn.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

        val runLabel = label("Run \u21B5", HunkwiseColors.FG, 11)
        runLabel.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        runLabel.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(HunkwiseColors.MUTED, 1, true),
            EmptyBorder(3, 10, 3, 10)
        )

        runLabel.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                runLabel.text = "Running..."
                runLabel.foreground = HunkwiseColors.GREEN
                skipBtn.isVisible = false
                Thread {
                    val root = project.basePath ?: "."
                    try {
                        val proc = ProcessBuilder("bash", "-c", command)
                            .directory(File(root)).redirectErrorStream(true).start()
                        val output = proc.inputStream.bufferedReader().readText()
                        val exitCode = proc.waitFor()
                        val short = if (output.length > 200) output.take(200) + "..." else output
                        SwingUtilities.invokeLater {
                            runLabel.text = if (exitCode == 0) "\u2713 Done" else "\u2715 Exit $exitCode"
                            runLabel.foreground = if (exitCode == 0) HunkwiseColors.GREEN else HunkwiseColors.RED
                            runLabel.border = EmptyBorder(3, 10, 3, 10)
                            if (short.isNotBlank()) { resultLabel.text = short; resultLabel.isVisible = true }
                            onResult?.invoke(output); panel.revalidate()
                        }
                    } catch (ex: Exception) {
                        SwingUtilities.invokeLater {
                            runLabel.text = "\u2715 Error"; runLabel.foreground = HunkwiseColors.RED
                            resultLabel.text = ex.message ?: "Error"; resultLabel.isVisible = true
                            panel.revalidate()
                        }
                    }
                }.start()
            }
        })

        skipBtn.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                runLabel.text = "Skipped"; runLabel.foreground = HunkwiseColors.MUTED
                runLabel.border = EmptyBorder(3, 10, 3, 10); skipBtn.isVisible = false
                panel.revalidate()
            }
        })

        btnPanel.add(skipBtn, BorderLayout.WEST)
        btnPanel.add(runLabel, BorderLayout.EAST)

        val south = JPanel(BorderLayout()).apply { isOpaque = false }
        south.add(resultLabel, BorderLayout.NORTH)
        south.add(btnPanel, BorderLayout.SOUTH)
        panel.add(south, BorderLayout.SOUTH)

        return panel
    }

    private fun label(text: String, color: Color, size: Int): JLabel {
        val lbl = JLabel(text)
        lbl.font = Font("JetBrains Mono", Font.PLAIN, size)
        lbl.foreground = color
        return lbl
    }
}
