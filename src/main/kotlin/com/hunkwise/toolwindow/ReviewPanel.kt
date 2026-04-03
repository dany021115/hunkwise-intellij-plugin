package com.hunkwise.toolwindow

import com.hunkwise.actions.HunkActions
import com.hunkwise.actions.buildShouldIgnore
import com.hunkwise.chat.ClaudeCodeService
import com.hunkwise.diff.DiffEngine
import com.hunkwise.diff.ParsedHunk
import com.hunkwise.state.StateChangeListener
import com.hunkwise.state.StateManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import java.awt.*
import java.io.File
import javax.swing.*
import javax.swing.border.EmptyBorder
import javax.swing.text.*

/**
 * Right-side vertical panel (like Android emulator):
 *
 *  ┌────────────────────────┐
 *  │  HUNKWISE              │
 *  │  24 files +672 -305    │
 *  │  ▶ user.go     +19    │  ← Click → opens file with diff
 *  │  ▶ config.go   +9 -5  │
 *  │  ...                   │
 *  ├────────────────────────┤
 *  │  CLAUDE CODE           │
 *  │                        │
 *  │  User: explain this    │  ← Chat messages
 *  │                        │
 *  │  Claude: I modified    │
 *  │  the config to add...  │
 *  │                        │
 *  ├────────────────────────┤
 *  │  > message...    [↑]   │  ← Input bar
 *  └────────────────────────┘
 */
class ReviewPanel(private val project: Project) : JBPanel<ReviewPanel>(BorderLayout()) {

    private val stateManager = project.service<StateManager>()
    private val claudeService = project.service<ClaudeCodeService>()

    // ── Files section ────────────────────────────────────────────────
    private val filesPanel = JBPanel<JBPanel<*>>().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
    }
    private val expandedFiles = mutableSetOf<String>()

    // ── Chat section ─────────────────────────────────────────────────
    private val chatPane = JTextPane().apply {
        isEditable = false
        border = EmptyBorder(8, 10, 8, 10)
        contentType = "text/plain"
        font = Font("Monospaced", Font.PLAIN, 12)
    }
    private val chatInput = JTextField().apply {
        font = Font("SansSerif", Font.PLAIN, 13)
        border = EmptyBorder(6, 10, 6, 6)
    }
    private var isSending = false

    init {
        setupUI()
        refresh()

        project.messageBus.connect().subscribe(
            StateManager.STATE_CHANGED_TOPIC,
            object : StateChangeListener {
                override fun stateChanged() {
                    ApplicationManager.getApplication().invokeLater { refresh() }
                }
            }
        )
    }

    private fun setupUI() {
        val mainSplit = JSplitPane(JSplitPane.VERTICAL_SPLIT)
        mainSplit.border = null
        mainSplit.dividerSize = 4

        // ── Top: Files ───────────────────────────────────────────────
        val filesSection = JBPanel<JBPanel<*>>(BorderLayout())
        filesSection.minimumSize = Dimension(250, 120)
        filesSection.add(JBScrollPane(filesPanel).apply {
            border = null
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        }, BorderLayout.CENTER)

        // ── Bottom: Chat ─────────────────────────────────────────────
        val chatSection = JBPanel<JBPanel<*>>(BorderLayout())
        chatSection.minimumSize = Dimension(250, 100)

        // Chat header
        val chatHeader = JBPanel<JBPanel<*>>(BorderLayout())
        chatHeader.border = EmptyBorder(6, 10, 6, 10)
        chatHeader.maximumSize = Dimension(Int.MAX_VALUE, 28)

        val chatTitlePanel = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 6, 0))
        chatTitlePanel.isOpaque = false
        chatTitlePanel.add(JBLabel("CLAUDE CODE").apply {
            font = font.deriveFont(Font.BOLD, 11f)
            foreground = JBColor.GRAY
        })

        val claudeAvailable = claudeService.isAvailable()
        if (!claudeAvailable) {
            chatTitlePanel.add(JBLabel("(not installed)").apply {
                foreground = COLOR_REMOVED
                font = font.deriveFont(Font.ITALIC, 10f)
            })
        }

        chatHeader.add(chatTitlePanel, BorderLayout.WEST)
        chatSection.add(chatHeader, BorderLayout.NORTH)

        // Chat messages
        val chatScroll = JBScrollPane(chatPane).apply {
            border = BorderFactory.createMatteBorder(1, 0, 0, 0, JBColor.border())
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        }
        chatSection.add(chatScroll, BorderLayout.CENTER)

        // Input bar
        val inputBar = JBPanel<JBPanel<*>>(BorderLayout())
        inputBar.border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, JBColor.border()),
            EmptyBorder(4, 4, 4, 4)
        )

        val sendBtn = JButton("\u2191").apply {
            margin = Insets(4, 8, 4, 8)
            font = font.deriveFont(Font.BOLD, 14f)
            toolTipText = "Send to Claude Code"
            addActionListener { sendChatMessage() }
        }

        chatInput.addActionListener { sendChatMessage() } // Enter key

        val inputWrapper = JBPanel<JBPanel<*>>(BorderLayout())
        inputWrapper.border = BorderFactory.createLineBorder(JBColor.border(), 1, true)
        inputWrapper.add(chatInput, BorderLayout.CENTER)

        inputBar.add(inputWrapper, BorderLayout.CENTER)
        inputBar.add(Box.createHorizontalStrut(4), BorderLayout.WEST)
        inputBar.add(sendBtn, BorderLayout.EAST)

        chatSection.add(inputBar, BorderLayout.SOUTH)

        // Welcome message
        appendChat("Claude", "Ready. Type a message below to chat with Claude Code.\nI can see and modify files in your project.", false)

        mainSplit.topComponent = filesSection
        mainSplit.bottomComponent = chatSection
        mainSplit.resizeWeight = 0.5

        add(mainSplit, BorderLayout.CENTER)
    }

    // ── Chat logic ───────────────────────────────────────────────────

    private fun sendChatMessage() {
        val message = chatInput.text.trim()
        if (message.isEmpty() || isSending) return

        chatInput.text = ""
        appendChat("You", message, true)

        if (!claudeService.isAvailable()) {
            appendChat("System", "Claude Code CLI not found.\nInstall: npm install -g @anthropic-ai/claude-code", false)
            return
        }

        isSending = true
        chatInput.isEnabled = false

        // Run in background thread
        Thread {
            val response = claudeService.sendMessage(message)
            SwingUtilities.invokeLater {
                isSending = false
                chatInput.isEnabled = true
                chatInput.requestFocusInWindow()

                if (response.isError) {
                    appendChat("Error", response.error ?: response.text ?: "Unknown error", false)
                } else {
                    appendChat("Claude", response.text ?: "", false)
                }

                // Scroll to bottom
                chatPane.caretPosition = chatPane.document.length
            }
        }.start()
    }

    private fun appendChat(sender: String, text: String, isUser: Boolean) {
        val doc = chatPane.styledDocument

        // Add spacing
        if (doc.length > 0) {
            doc.insertString(doc.length, "\n\n", null)
        }

        // Sender label
        val senderStyle = chatPane.addStyle("sender", null)
        StyleConstants.setBold(senderStyle, true)
        StyleConstants.setForeground(senderStyle, if (isUser) {
            JBColor(Color(0x56, 0x9c, 0xd6), Color(0x6c, 0xb6, 0xff))
        } else {
            JBColor(Color(0xce, 0x91, 0x78), Color(0xce, 0x91, 0x78))
        })
        doc.insertString(doc.length, "$sender\n", senderStyle)

        // Message text
        val textStyle = chatPane.addStyle("text", null)
        StyleConstants.setForeground(textStyle, JBColor(Color(0x33, 0x33, 0x33), Color(0xcc, 0xcc, 0xcc)))
        doc.insertString(doc.length, text, textStyle)

        chatPane.caretPosition = doc.length
    }

    // ── Files refresh ────────────────────────────────────────────────

    private fun refresh() {
        filesPanel.removeAll()

        if (!stateManager.enabled) {
            filesPanel.add(createSetupPanel())
            filesPanel.add(Box.createVerticalGlue())
            filesPanel.revalidate()
            filesPanel.repaint()
            return
        }

        val files = stateManager.getReviewingFiles()

        if (files.isEmpty()) {
            filesPanel.add(createHeaderBar(0, 0, 0))
            filesPanel.add(createSeparator())
            filesPanel.add(Box.createVerticalStrut(20))
            filesPanel.add(createCenteredLabel("No pending changes"))
            filesPanel.add(Box.createVerticalGlue())
            filesPanel.revalidate()
            filesPanel.repaint()
            return
        }

        var totalAdded = 0
        var totalRemoved = 0
        val fileDataList = mutableListOf<FileData>()

        for ((filePath, fileState) in files) {
            val currentContent = try {
                val file = File(filePath)
                if (file.exists()) file.readText(Charsets.UTF_8) else ""
            } catch (_: Exception) { "" }
            val hunks = DiffEngine.computeHunks(fileState.baseline, currentContent)
            val added = hunks.sumOf { it.newLines }
            val removed = hunks.sumOf { it.oldLines }
            totalAdded += added
            totalRemoved += removed
            val isDeleted = !File(filePath).exists() && fileState.baseline != null
            fileDataList.add(FileData(filePath, fileState.isNew, isDeleted, hunks, added, removed))
        }

        filesPanel.add(createHeaderBar(files.size, totalAdded, totalRemoved))
        filesPanel.add(createSeparator())

        for (fileData in fileDataList) {
            filesPanel.add(createFileRow(fileData))
            if (expandedFiles.contains(fileData.filePath)) {
                for (hunk in fileData.hunks) {
                    filesPanel.add(createHunkRow(fileData.filePath, hunk))
                }
            }
        }

        filesPanel.add(Box.createVerticalGlue())
        filesPanel.revalidate()
        filesPanel.repaint()
    }

    // ── Header bar ───────────────────────────────────────────────────

    private fun createHeaderBar(fileCount: Int, totalAdded: Int, totalRemoved: Int): JPanel {
        val bar = JBPanel<JBPanel<*>>(BorderLayout())
        bar.border = EmptyBorder(8, 10, 6, 10)
        bar.maximumSize = Dimension(Int.MAX_VALUE, 50)

        // Title row
        val titleRow = JBPanel<JBPanel<*>>(BorderLayout())
        titleRow.isOpaque = false
        titleRow.add(JBLabel("HUNKWISE").apply {
            font = font.deriveFont(Font.BOLD, 11f); foreground = JBColor.GRAY
        }, BorderLayout.WEST)

        val disableBtn = createTinyButton("\u2715") { onDisable() }
        disableBtn.toolTipText = "Disable"
        titleRow.add(disableBtn, BorderLayout.EAST)
        bar.add(titleRow, BorderLayout.NORTH)

        if (fileCount > 0) {
            val statsRow = JBPanel<JBPanel<*>>(BorderLayout())
            statsRow.isOpaque = false
            statsRow.border = EmptyBorder(4, 0, 0, 0)

            val left = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 5, 0))
            left.isOpaque = false
            left.add(JBLabel("$fileCount files").apply { font = font.deriveFont(Font.BOLD, 12f) })
            left.add(JBLabel("+$totalAdded").apply {
                foreground = COLOR_ADDED; font = font.deriveFont(Font.BOLD, 12f)
            })
            left.add(JBLabel("-$totalRemoved").apply {
                foreground = COLOR_REMOVED; font = font.deriveFont(Font.BOLD, 12f)
            })

            val right = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.RIGHT, 3, 0))
            right.isOpaque = false
            right.add(createActionButton("\u2713 Accept", COLOR_ADDED) {
                HunkActions.acceptAll(project, stateManager)
            })
            right.add(createActionButton("\u21BA Discard", COLOR_REMOVED) {
                HunkActions.discardAll(project, stateManager)
            })

            statsRow.add(left, BorderLayout.WEST)
            statsRow.add(right, BorderLayout.EAST)
            bar.add(statsRow, BorderLayout.SOUTH)
        }

        return bar
    }

    // ── File row ─────────────────────────────────────────────────────

    private fun createFileRow(data: FileData): JPanel {
        val row = JBPanel<JBPanel<*>>(BorderLayout())
        row.border = EmptyBorder(3, 10, 3, 8)
        row.maximumSize = Dimension(Int.MAX_VALUE, 26)
        row.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

        val left = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 3, 0))
        left.isOpaque = false

        val expanded = expandedFiles.contains(data.filePath)
        left.add(JBLabel(if (expanded) "\u25BC" else "\u25B6").apply {
            font = font.deriveFont(9f); foreground = JBColor.GRAY
            preferredSize = Dimension(12, 14)
        })

        val ext = File(data.filePath).extension.uppercase()
        if (ext.isNotEmpty()) left.add(createExtBadge(ext))

        left.add(JBLabel(File(data.filePath).name).apply {
            font = font.deriveFont(Font.PLAIN, 12f)
        })

        if (data.isNew) left.add(createTagBadge("NEW", COLOR_ADDED))
        if (data.isDeleted) left.add(createTagBadge("DELETED", COLOR_REMOVED))

        val dirName = File(data.filePath).parent?.let { p ->
            stateManager.workspaceRoot?.let { r -> File(p).toRelativeString(File(r)) } ?: p
        } ?: ""
        if (dirName.isNotEmpty()) left.add(JBLabel(dirName).apply {
            foreground = JBColor.GRAY; font = font.deriveFont(Font.PLAIN, 10f)
        })

        val right = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.RIGHT, 2, 0))
        right.isOpaque = false
        if (data.added > 0) right.add(JBLabel("+${data.added}").apply {
            foreground = COLOR_ADDED; font = font.deriveFont(Font.BOLD, 11f)
        })
        if (data.removed > 0) right.add(JBLabel("-${data.removed}").apply {
            foreground = COLOR_REMOVED; font = font.deriveFont(Font.BOLD, 11f)
        })

        row.add(left, BorderLayout.WEST)
        row.add(right, BorderLayout.EAST)

        row.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (e.clickCount == 2) { openFile(data.filePath); return }
                if (expandedFiles.contains(data.filePath)) expandedFiles.remove(data.filePath)
                else expandedFiles.add(data.filePath)
                refresh()
            }
            override fun mouseEntered(e: java.awt.event.MouseEvent) {
                row.background = JBColor(Color(0, 0, 0, 15), Color(255, 255, 255, 15))
                row.isOpaque = true; row.repaint()
            }
            override fun mouseExited(e: java.awt.event.MouseEvent) { row.isOpaque = false; row.repaint() }
        })

        return row
    }

    // ── Hunk row ─────────────────────────────────────────────────────

    private fun createHunkRow(filePath: String, hunk: ParsedHunk): JPanel {
        val row = JBPanel<JBPanel<*>>(BorderLayout())
        row.border = EmptyBorder(1, 28, 1, 8)
        row.maximumSize = Dimension(Int.MAX_VALUE, 20)
        row.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

        val left = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 4, 0))
        left.isOpaque = false
        left.add(JBLabel("@line ${hunk.newStart}").apply {
            foreground = JBColor(Color(0x56, 0x9c, 0xd6), Color(0x6c, 0xb6, 0xff))
            font = font.deriveFont(Font.PLAIN, 11f)
        })
        if (hunk.newLines > 0) left.add(JBLabel("+${hunk.newLines}").apply {
            foreground = COLOR_ADDED; font = font.deriveFont(11f)
        })
        if (hunk.oldLines > 0) left.add(JBLabel("-${hunk.oldLines}").apply {
            foreground = COLOR_REMOVED; font = font.deriveFont(11f)
        })

        val right = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.RIGHT, 2, 0))
        right.isOpaque = false
        right.add(createTinyButton("\u2713") { HunkActions.acceptHunk(project, stateManager, filePath, hunk.id) })
        right.add(createTinyButton("\u21BA") { HunkActions.discardHunk(project, stateManager, filePath, hunk.id) })

        row.add(left, BorderLayout.WEST)
        row.add(right, BorderLayout.EAST)

        row.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) { jumpToHunk(filePath, hunk) }
            override fun mouseEntered(e: java.awt.event.MouseEvent) {
                row.background = JBColor(Color(0, 0, 0, 10), Color(255, 255, 255, 10))
                row.isOpaque = true; row.repaint()
            }
            override fun mouseExited(e: java.awt.event.MouseEvent) { row.isOpaque = false; row.repaint() }
        })

        return row
    }

    // ── Setup panel ──────────────────────────────────────────────────

    private fun createSetupPanel(): JPanel {
        val panel = JBPanel<JBPanel<*>>(GridBagLayout())
        val inner = JBPanel<JBPanel<*>>()
        inner.layout = BoxLayout(inner, BoxLayout.Y_AXIS)
        inner.border = EmptyBorder(30, 20, 30, 20)
        inner.add(JBLabel("HUNKWISE").apply {
            font = font.deriveFont(Font.BOLD, 14f); alignmentX = Component.CENTER_ALIGNMENT
        })
        inner.add(Box.createVerticalStrut(10))
        inner.add(JBLabel("Track & review external").apply {
            foreground = JBColor.GRAY; alignmentX = Component.CENTER_ALIGNMENT
        })
        inner.add(JBLabel("file changes per hunk.").apply {
            foreground = JBColor.GRAY; alignmentX = Component.CENTER_ALIGNMENT
        })
        inner.add(Box.createVerticalStrut(16))
        inner.add(JButton("Enable Hunkwise").apply {
            alignmentX = Component.CENTER_ALIGNMENT; addActionListener { onEnable() }
        })
        panel.add(inner)
        return panel
    }

    // ── Navigation ───────────────────────────────────────────────────

    private fun openFile(filePath: String) {
        val vFile = LocalFileSystem.getInstance().findFileByPath(filePath) ?: return
        FileEditorManager.getInstance(project).openFile(vFile, true)
    }

    private fun jumpToHunk(filePath: String, hunk: ParsedHunk) {
        val vFile = LocalFileSystem.getInstance().findFileByPath(filePath) ?: return
        val editors = FileEditorManager.getInstance(project).openFile(vFile, true)
        for (editor in editors) {
            if (editor is TextEditor) {
                val line = maxOf(0, hunk.newStart - 1)
                if (line < editor.editor.document.lineCount) {
                    val offset = editor.editor.document.getLineStartOffset(line)
                    editor.editor.caretModel.moveToOffset(offset)
                    editor.editor.scrollingModel.scrollToCaret(com.intellij.openapi.editor.ScrollType.CENTER)
                }
                break
            }
        }
    }

    private fun onEnable() {
        stateManager.setEnabled(true)
        val workspaceRoot = stateManager.workspaceRoot ?: return
        val hunkwiseDir = stateManager.hunkwiseDir ?: return
        val shouldIgnore = buildShouldIgnore(stateManager, hunkwiseDir, workspaceRoot)
        com.intellij.openapi.progress.ProgressManager.getInstance().run(
            object : com.intellij.openapi.progress.Task.Backgroundable(project, "Hunkwise: Snapshotting workspace...", false) {
                override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                    stateManager.snapshotWorkspace(shouldIgnore)
                }
                override fun onSuccess() { stateManager.fireStateChanged() }
            }
        )
    }

    private fun onDisable() {
        stateManager.setEnabled(false)
        stateManager.fireStateChanged()
    }

    // ── UI helpers ───────────────────────────────────────────────────

    private fun createExtBadge(ext: String): JLabel {
        val color = EXT_COLORS[ext.lowercase()] ?: Color(0x60, 0x60, 0x60)
        return object : JLabel(ext) {
            init {
                font = getFont().deriveFont(Font.BOLD, 8f); foreground = Color.WHITE
                horizontalAlignment = CENTER; isOpaque = false; border = EmptyBorder(1, 3, 1, 3)
                preferredSize = Dimension(getFontMetrics(font).stringWidth(ext) + 8, 14)
            }
            override fun paintComponent(g: Graphics) {
                (g as Graphics2D).setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g.color = color; g.fillRoundRect(0, 0, width, height, 4, 4)
                super.paintComponent(g)
            }
        }
    }

    private fun createTagBadge(text: String, color: Color): JLabel {
        return object : JLabel(text) {
            init {
                font = getFont().deriveFont(Font.BOLD, 8f); foreground = color
                isOpaque = false; border = EmptyBorder(1, 3, 1, 3)
            }
            override fun paintComponent(g: Graphics) {
                (g as Graphics2D).setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g.color = Color(color.red, color.green, color.blue, 25); g.fillRoundRect(0, 0, width, height, 4, 4)
                g.color = Color(color.red, color.green, color.blue, 80); g.drawRoundRect(0, 0, width - 1, height - 1, 4, 4)
                super.paintComponent(g)
            }
        }
    }

    private fun createActionButton(text: String, color: Color, onClick: () -> Unit) = JButton(text).apply {
        font = font.deriveFont(Font.BOLD, 10f); foreground = Color.WHITE; background = color
        margin = Insets(2, 6, 2, 6); isFocusPainted = false; addActionListener { onClick() }
    }

    private fun createTinyButton(text: String, onClick: () -> Unit) = JButton(text).apply {
        font = font.deriveFont(10f); margin = Insets(0, 3, 0, 3)
        isFocusPainted = false; preferredSize = Dimension(22, 18)
        addActionListener { onClick() }
    }

    private fun createSeparator() = JSeparator().apply { maximumSize = Dimension(Int.MAX_VALUE, 1) }
    private fun createCenteredLabel(text: String): JPanel {
        val p = JBPanel<JBPanel<*>>(GridBagLayout())
        p.add(JBLabel(text).apply { foreground = JBColor.GRAY })
        return p
    }

    private data class FileData(
        val filePath: String, val isNew: Boolean, val isDeleted: Boolean,
        val hunks: List<ParsedHunk>, val added: Int, val removed: Int
    )

    companion object {
        val COLOR_ADDED = Color(0x2a, 0x7d, 0x3a)
        val COLOR_REMOVED = Color(0xf8, 0x51, 0x49)

        val EXT_COLORS = mapOf(
            "go" to Color(0x00, 0xad, 0xd8), "kt" to Color(0x7f, 0x52, 0xff),
            "kts" to Color(0x7f, 0x52, 0xff), "java" to Color(0xb0, 0x71, 0x19),
            "ts" to Color(0x31, 0x78, 0xc6), "tsx" to Color(0x31, 0x78, 0xc6),
            "js" to Color(0xf1, 0xe0, 0x5a), "jsx" to Color(0xf1, 0xe0, 0x5a),
            "py" to Color(0x35, 0x72, 0xa5), "rs" to Color(0xde, 0xa5, 0x84),
            "rb" to Color(0xcc, 0x34, 0x2d), "css" to Color(0x56, 0x3d, 0x7c),
            "scss" to Color(0xcd, 0x66, 0x99), "html" to Color(0xe3, 0x4c, 0x26),
            "json" to Color(0x9b, 0x9b, 0x9b), "yml" to Color(0xcb, 0x17, 0x1e),
            "yaml" to Color(0xcb, 0x17, 0x1e), "xml" to Color(0xf0, 0x80, 0x00),
            "md" to Color(0x08, 0x3f, 0xa1), "sql" to Color(0xe3, 0x8c, 0x00),
            "sh" to Color(0x4e, 0xaa, 0x25), "dockerfile" to Color(0x38, 0x4d, 0x54),
            "toml" to Color(0x9c, 0x40, 0x21), "swift" to Color(0xf0, 0x52, 0x38),
            "c" to Color(0x55, 0x55, 0x99), "cpp" to Color(0x00, 0x59, 0x9c),
            "php" to Color(0x77, 0x7b, 0xb4), "vue" to Color(0x41, 0xb8, 0x83),
            "svelte" to Color(0xff, 0x3e, 0x00), "dart" to Color(0x00, 0xb4, 0xab),
        )
    }
}
