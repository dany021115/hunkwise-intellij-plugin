package com.hunkwise.toolwindow

import com.hunkwise.HunkwiseColors
import com.hunkwise.chat.*
import com.hunkwise.git.ProjectGitService
import com.hunkwise.git.ProjectGitService.*
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.ui.components.JBScrollPane
import java.awt.*
import java.io.File
import javax.swing.*
import javax.swing.border.EmptyBorder
import javax.swing.text.StyleConstants

/**
 * Full chat panel — Claude runs in background.
 * Shows text responses + persistent inline diff blocks (Cursor-style).
 * All activity (read, search, edit, run) stays visible as a log.
 * Click a diff block → opens IntelliJ diff viewer with per-line accept/reject.
 */
class ReviewPanel(
    private val project: Project,
    private val parentDisposable: Disposable
) : JPanel(BorderLayout()), Disposable {

    private val claudeService = project.service<ClaudeCodeService>()
    private val sessionManager = project.service<SessionManager>()
    private val gitService = project.service<ProjectGitService>()

    private val chatPane = JTextPane().apply {
        isEditable = false; background = HunkwiseColors.BG; foreground = HunkwiseColors.FG
        font = Font("SansSerif", Font.PLAIN, 12)
        border = EmptyBorder(12, 16, 12, 16)
    }

    private val chatInput = JTextField().apply {
        background = HunkwiseColors.SURFACE_ALT; foreground = HunkwiseColors.MUTED
        caretColor = HunkwiseColors.FG; font = Font("SansSerif", Font.PLAIN, 13)
        border = EmptyBorder(10, 14, 10, 14)
        text = "Reject, suggest, follow up?"
    }

    private var isSending = false
    private var streamingStartOffset = -1
    private val streamingBuffer = StringBuilder()
    private val trackedFiles = mutableSetOf<String>()
    private var lastActivity = ""

    init {
        background = HunkwiseColors.BG
        Disposer.register(parentDisposable, this)
        buildLayout()
        listenForFileChanges()

        chatInput.addFocusListener(object : java.awt.event.FocusAdapter() {
            override fun focusGained(e: java.awt.event.FocusEvent?) {
                if (chatInput.text == "Reject, suggest, follow up?") {
                    chatInput.text = ""; chatInput.foreground = HunkwiseColors.FG
                }
            }
            override fun focusLost(e: java.awt.event.FocusEvent?) {
                if (chatInput.text.isEmpty()) {
                    chatInput.text = "Reject, suggest, follow up?"
                    chatInput.foreground = HunkwiseColors.MUTED
                }
            }
        })
        chatInput.addActionListener { sendChat() }
    }

    private fun buildLayout() {
        // Header
        val header = JPanel(BorderLayout())
        header.background = HunkwiseColors.BG
        header.border = EmptyBorder(8, 14, 8, 14)
        header.preferredSize = Dimension(0, 38)

        val hLeft = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply { isOpaque = false }
        hLeft.add(lbl("Hunkwise", HunkwiseColors.FG, 13, true))

        val hRight = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply { isOpaque = false }
        hRight.add(lbl("+", HunkwiseColors.MUTED, 16, false).apply {
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR); toolTipText = "New conversation"
            addMouseListener(click { newSession() })
        })
        hRight.add(lbl("\u23F1", HunkwiseColors.MUTED, 14, false).apply {
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR); toolTipText = "History"
            addMouseListener(click { showHistory(this) })
        })
        hRight.add(lbl("\u25A0", HunkwiseColors.RED, 12, true).apply {
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR); toolTipText = "Cancel"
            addMouseListener(click { claudeService.cancel(); isSending = false })
        })
        header.add(hLeft, BorderLayout.WEST)
        header.add(hRight, BorderLayout.EAST)

        // Chat scroll
        val chatScroll = JBScrollPane(chatPane).apply {
            border = null; background = HunkwiseColors.BG; viewport.background = HunkwiseColors.BG
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        }

        // Input bar
        val bottomPanel = JPanel(BorderLayout()).apply { background = HunkwiseColors.BG }
        val inputBar = JPanel(BorderLayout()).apply {
            background = HunkwiseColors.SURFACE
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, HunkwiseColors.BORDER),
                EmptyBorder(6, 8, 6, 8)
            )
        }
        val inputWrapper = JPanel(BorderLayout()).apply {
            background = HunkwiseColors.SURFACE_ALT
            border = BorderFactory.createLineBorder(HunkwiseColors.BORDER, 1, true)
            add(chatInput, BorderLayout.CENTER)
        }
        val sendBtn = object : JLabel("\u2191") {
            init {
                font = getFont().deriveFont(Font.BOLD, 16f); foreground = Color.WHITE
                horizontalAlignment = CENTER; isOpaque = false
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                preferredSize = Dimension(34, 34); border = EmptyBorder(0, 4, 0, 6)
            }
            override fun paintComponent(g: Graphics) {
                (g as Graphics2D).setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g.color = HunkwiseColors.ORANGE; g.fillRoundRect(2, 5, width - 4, height - 10, 8, 8)
                super.paintComponent(g)
            }
        }
        sendBtn.addMouseListener(click { sendChat() })
        inputBar.add(inputWrapper, BorderLayout.CENTER)
        inputBar.add(sendBtn, BorderLayout.EAST)

        val toolbar = JPanel(BorderLayout()).apply {
            background = HunkwiseColors.BG; border = EmptyBorder(4, 14, 6, 14)
        }
        val tbLeft = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply { isOpaque = false }
        tbLeft.add(lbl("\u221E Agent \u25BE", HunkwiseColors.MUTED, 11, false))
        tbLeft.add(lbl("Auto \u25BE", HunkwiseColors.MUTED, 11, false))

        val tbRight = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply { isOpaque = false }
        val bypassLbl = lbl("\uD83D\uDD12 Bypass OFF", HunkwiseColors.MUTED, 11, false)
        bypassLbl.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        bypassLbl.toolTipText = "Toggle auto-accept all commands"
        bypassLbl.addMouseListener(click {
            claudeService.autoAcceptCommands = !claudeService.autoAcceptCommands
            if (claudeService.autoAcceptCommands) {
                bypassLbl.text = "\uD83D\uDD13 Bypass ON"
                bypassLbl.foreground = HunkwiseColors.GREEN
            } else {
                bypassLbl.text = "\uD83D\uDD12 Bypass OFF"
                bypassLbl.foreground = HunkwiseColors.MUTED
            }
        })
        tbRight.add(bypassLbl)

        toolbar.add(tbLeft, BorderLayout.WEST)
        toolbar.add(tbRight, BorderLayout.EAST)

        bottomPanel.add(inputBar, BorderLayout.CENTER)
        bottomPanel.add(toolbar, BorderLayout.SOUTH)

        add(header, BorderLayout.NORTH)
        add(chatScroll, BorderLayout.CENTER)
        add(bottomPanel, BorderLayout.SOUTH)
    }

    // ══════════════════════════════════════════════════════════════════
    // CHAT
    // ══════════════════════════════════════════════════════════════════

    private fun sendChat() {
        val msg = chatInput.text.trim()
        if (msg.isEmpty() || msg == "Reject, suggest, follow up?" || isSending) return
        chatInput.text = ""; chatInput.foreground = HunkwiseColors.FG
        appendMsg("You", msg, HunkwiseColors.SENDER_USER)

        if (!claudeService.isAvailable()) {
            appendMsg("system", "Claude Code not found.\nnpm install -g @anthropic-ai/claude-code", HunkwiseColors.RED)
            return
        }

        val contextMsg = buildMessageWithHistory(msg)
        val systemPrompt = claudeService.buildSystemPrompt()
        trackedFiles.clear()
        isSending = true
        startStreaming()

        Thread {
            claudeService.sendMessageStreaming(
                message = contextMsg,
                systemPrompt = systemPrompt,
                onToken = { token -> SwingUtilities.invokeLater { appendStreamToken(token) } },
                onActivity = { status -> SwingUtilities.invokeLater { showActivity(status) } },
                onToolUse = { event -> SwingUtilities.invokeLater { handleToolEvent(event) } },
                onDone = { response ->
                    SwingUtilities.invokeLater {
                        isSending = false
                        lastActivity = ""
                        finishStreaming()
                        if (response.isError) appendMsg("error", response.error ?: "Error", HunkwiseColors.RED)
                    }
                }
            )
        }.start()
    }

    // ── Tool events ─────────────────────────────────────────────────

    private fun handleToolEvent(event: ClaudeCodeService.ToolEvent) {
        when (event.toolName) {
            "Edit", "Write" -> {
                val filePath = event.filePath ?: return
                Thread {
                    val diff = gitService.getFileDiff(filePath)
                    if (diff != null && diff.hunks.isNotEmpty()) {
                        SwingUtilities.invokeLater {
                            trackedFiles.add(filePath)
                            insertDiffBlock(filePath, diff)
                        }
                    }
                }.start()
            }
            "Bash" -> {
                val cmd = event.command ?: return
                val desc = event.description ?: cmd.take(40)
                insertBlock(ChatBlockRenderer.createPermissionBlock(
                    project, desc, cmd, chatPane.width.coerceAtLeast(300) - 40
                ))
            }
        }
    }

    /** Insert diff block — click opens IntelliJ diff viewer with accept/reject per line */
    private fun insertDiffBlock(filePath: String, diff: FileDiff) {
        val added = diff.hunks.flatMap { it.addedContent }
        val removed = diff.hunks.flatMap { it.removedContent }
        val addCount = diff.hunks.sumOf { it.newLines }
        val remCount = diff.hunks.sumOf { it.oldLines }
        val w = chatPane.width.coerceAtLeast(300) - 40

        val block = JPanel(BorderLayout())
        block.background = HunkwiseColors.SURFACE
        block.border = BorderFactory.createLineBorder(HunkwiseColors.BORDER, 1, true)
        block.maximumSize = Dimension(w, 350)
        block.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

        // Header: ✎ filename +N -M
        val header = JPanel(BorderLayout())
        header.background = HunkwiseColors.SURFACE_ALT
        header.border = EmptyBorder(6, 10, 6, 10)

        val hL = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply { isOpaque = false }
        hL.add(lbl("\u270E", HunkwiseColors.MUTED, 12, false))
        hL.add(lbl(File(filePath).name, HunkwiseColors.FG, 12, true))
        if (addCount > 0) hL.add(lbl("+$addCount", HunkwiseColors.GREEN, 11, false))
        if (remCount > 0) hL.add(lbl("-$remCount", HunkwiseColors.RED, 11, false))
        header.add(hL, BorderLayout.WEST)
        block.add(header, BorderLayout.NORTH)

        // Diff content
        val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS); background = HunkwiseColors.SURFACE
        }
        for (line in removed.take(8)) {
            val row = JPanel(BorderLayout()).apply {
                background = HunkwiseColors.REMOVED_BG; border = EmptyBorder(1, 10, 1, 10)
                maximumSize = Dimension(w, 18)
            }
            row.add(lbl("- $line", HunkwiseColors.RED, 11, false), BorderLayout.WEST)
            content.add(row)
        }
        if (removed.size > 8) content.add(lbl("  ... ${removed.size - 8} more", HunkwiseColors.MUTED, 10, false))
        for (line in added.take(8)) {
            val row = JPanel(BorderLayout()).apply {
                background = HunkwiseColors.ADDED_BG; border = EmptyBorder(1, 10, 1, 10)
                maximumSize = Dimension(w, 18)
            }
            row.add(lbl("+ $line", HunkwiseColors.GREEN, 11, false), BorderLayout.WEST)
            content.add(row)
        }
        if (added.size > 8) content.add(lbl("  ... ${added.size - 8} more", HunkwiseColors.MUTED, 10, false))
        block.add(content, BorderLayout.CENTER)

        // Click anywhere on block → open IntelliJ diff viewer
        block.addMouseListener(click { openDiffViewer(filePath) })

        insertBlock(block)
    }

    // ── Open file with inline diff + Accept/Discard per hunk ──────

    private fun openDiffViewer(filePath: String) {
        project.service<com.hunkwise.editor.InlineDiffService>().openFileWithDiff(filePath)
    }

    // ── File change detection ───────────────────────────────────────

    private fun listenForFileChanges() {
        project.messageBus.connect(this).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: MutableList<out VFileEvent>) {
                    if (!isSending) return
                    for (event in events) {
                        if (event is VFileContentChangeEvent) {
                            val path = event.file.path
                            val basePath = project.basePath ?: continue
                            if (!path.startsWith(basePath) || path.contains("/.git/") || path.contains("/.idea/")) continue
                            if (trackedFiles.contains(path)) continue
                            Thread {
                                val diff = gitService.getFileDiff(path)
                                if (diff != null && diff.hunks.isNotEmpty()) {
                                    SwingUtilities.invokeLater {
                                        trackedFiles.add(path)
                                        insertDiffBlock(path, diff)
                                    }
                                }
                            }.start()
                        }
                    }
                }
            }
        )
    }

    // ── Streaming ───────────────────────────────────────────────────

    private fun startStreaming() {
        streamingBuffer.clear()
        val doc = chatPane.styledDocument
        if (doc.length > 0) doc.insertString(doc.length, "\n\n", null)
        val s = chatPane.addStyle("ss${doc.length}", null)
        StyleConstants.setBold(s, true)
        StyleConstants.setForeground(s, HunkwiseColors.SENDER_CLAUDE)
        StyleConstants.setFontSize(s, 12)
        StyleConstants.setFontFamily(s, "JetBrains Mono")
        doc.insertString(doc.length, "Claude\n", s)
        streamingStartOffset = doc.length
        chatPane.caretPosition = doc.length
    }

    private fun appendStreamToken(token: String) {
        streamingBuffer.append(token)
        val doc = chatPane.styledDocument
        val s = chatPane.addStyle("st${doc.length}", null)
        StyleConstants.setForeground(s, HunkwiseColors.FG)
        StyleConstants.setFontSize(s, 12); StyleConstants.setFontFamily(s, "SansSerif")
        doc.insertString(doc.length, token, s)
        chatPane.caretPosition = doc.length
    }

    private fun finishStreaming() {
        val fullText = streamingBuffer.toString()
        if (fullText.isNotBlank()) {
            // Save to session history
            sessionManager.addMessage("Claude", fullText)
            // DON'T remove/replace existing content — keep activity log, diffs, and raw text visible
            // Just add a formatted summary separator below everything
        }
        streamingBuffer.clear(); streamingStartOffset = -1
    }

    // ── Activity — persistent log ───────────────────────────────────

    private fun showActivity(status: String) {
        if (status.isEmpty() || status == lastActivity) return
        lastActivity = status
        val doc = chatPane.styledDocument
        val s = chatPane.addStyle("act${doc.length}", null)
        StyleConstants.setForeground(s, HunkwiseColors.MUTED)
        StyleConstants.setFontSize(s, 11); StyleConstants.setFontFamily(s, "JetBrains Mono")
        StyleConstants.setItalic(s, true)
        doc.insertString(doc.length, "\n$status", s)
        chatPane.caretPosition = doc.length
    }

    // ── Insert block into chat ──────────────────────────────────────

    private fun insertBlock(component: JPanel) {
        lastActivity = ""
        val doc = chatPane.styledDocument
        doc.insertString(doc.length, "\n", null)
        chatPane.caretPosition = doc.length
        chatPane.insertComponent(component)
        doc.insertString(doc.length, "\n", null)
        chatPane.caretPosition = doc.length
    }

    // ── Messages ────────────────────────────────────────────────────

    private fun appendMsg(sender: String, text: String, color: Color) {
        sessionManager.addMessage(sender, text)
        appendMsgNoSave(sender, text, color)
    }

    private fun appendMsgNoSave(sender: String, text: String, color: Color) {
        val doc = chatPane.styledDocument
        if (doc.length > 0) doc.insertString(doc.length, "\n\n", null)
        val s = chatPane.addStyle("s${doc.length}", null)
        StyleConstants.setBold(s, true); StyleConstants.setForeground(s, color)
        StyleConstants.setFontSize(s, 12); StyleConstants.setFontFamily(s, "JetBrains Mono")
        doc.insertString(doc.length, "$sender\n", s)
        MarkdownRenderer.render(chatPane, text, HunkwiseColors.FG)
        chatPane.caretPosition = doc.length
    }

    // ── Sessions ────────────────────────────────────────────────────

    private fun newSession() {
        claudeService.resetSession(); sessionManager.createNewSession()
        chatPane.text = ""; trackedFiles.clear()
    }

    private fun showHistory(anchor: Component) {
        val sessions = sessionManager.getSessionList()
        if (sessions.isEmpty()) return
        val popup = JPopupMenu().apply { background = HunkwiseColors.SURFACE }
        for (session in sessions.take(15)) {
            val cur = session.id == sessionManager.getCurrentSessionId()
            val item = JMenuItem(if (cur) "\u25CF ${session.title}" else "  ${session.title}")
            item.font = Font("JetBrains Mono", Font.PLAIN, 11)
            item.foreground = if (cur) HunkwiseColors.GREEN else HunkwiseColors.FG
            item.background = HunkwiseColors.SURFACE
            item.addActionListener { loadSession(session.id) }
            popup.add(item)
        }
        popup.show(anchor, 0, anchor.height)
    }

    private fun loadSession(id: String) {
        val session = sessionManager.switchToSession(id) ?: return
        claudeService.resetSession(); chatPane.text = ""
        for (msg in session.messages) {
            val color = when (msg.sender) {
                "You" -> HunkwiseColors.SENDER_USER; "Claude" -> HunkwiseColors.SENDER_CLAUDE
                "system" -> HunkwiseColors.SENDER_SYSTEM; "error" -> HunkwiseColors.SENDER_ERROR
                else -> HunkwiseColors.MUTED
            }
            appendMsgNoSave(msg.sender, msg.text, color)
        }
    }

    // ── Context ─────────────────────────────────────────────────────

    private fun buildMessageWithHistory(msg: String): String {
        val session = sessionManager.getCurrentSession()
        val recent = session.messages.filter { it.sender == "You" || it.sender == "Claude" }
        val sb = StringBuilder()
        if (recent.size > 1) {
            sb.appendLine("<conversation_history>")
            for (m in recent.dropLast(1)) {
                sb.appendLine("${if (m.sender == "You") "User" else "Assistant"}: ${m.text}\n")
            }
            sb.appendLine("</conversation_history>\n")
        }
        sb.append(msg); return sb.toString()
    }

    private fun openFile(filePath: String) {
        val vFile = LocalFileSystem.getInstance().findFileByPath(filePath) ?: return
        FileEditorManager.getInstance(project).openFile(vFile, true)
    }

    override fun dispose() {}

    private fun lbl(t: String, c: Color, sz: Int, b: Boolean) = JLabel(t).apply {
        foreground = c; font = Font("JetBrains Mono", if (b) Font.BOLD else Font.PLAIN, sz)
    }
    private fun click(action: () -> Unit) = object : java.awt.event.MouseAdapter() {
        override fun mouseClicked(e: java.awt.event.MouseEvent) { action() }
    }
}
