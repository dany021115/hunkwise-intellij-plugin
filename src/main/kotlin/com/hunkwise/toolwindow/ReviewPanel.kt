package com.hunkwise.toolwindow

import com.hunkwise.chat.ClaudeCodeService
import com.hunkwise.chat.MarkdownRenderer
import com.hunkwise.chat.SessionManager
import com.hunkwise.git.ProjectGitService
import com.hunkwise.git.ProjectGitService.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileListener
import com.intellij.openapi.vfs.VirtualFileEvent
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import java.awt.*
import java.io.File
import javax.swing.*
import javax.swing.border.EmptyBorder
import javax.swing.text.StyleConstants

class ReviewPanel(private val project: Project) : JBPanel<ReviewPanel>(BorderLayout()) {

    private val gitService = project.service<ProjectGitService>()
    private val claudeService = project.service<ClaudeCodeService>()
    private val sessionManager = project.service<SessionManager>()

    private val changesPanel = JBPanel<JBPanel<*>>().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS); background = BG
    }
    private val expandedFiles = mutableSetOf<String>()

    private val chatPane = JTextPane().apply {
        isEditable = false; background = BG; foreground = FG
        font = Font("SansSerif", Font.PLAIN, 12)
        border = EmptyBorder(8, 12, 8, 12)
    }
    private val chatInput = JTextField().apply {
        background = Color(0x2d, 0x2d, 0x2d); foreground = Color(0x6B, 0x72, 0x80)
        caretColor = FG; font = Font("JetBrains Mono", Font.PLAIN, 12)
        border = EmptyBorder(10, 12, 10, 12)
        text = "\u2318 Esc to focus or unfocus Claude"
    }
    private var isSending = false
    private val refreshTimer = Timer(3000) { bgRefresh() }

    // Cached data for rendering on EDT
    private var cachedIsRepo = false
    private var cachedFiles = emptyList<ChangedFile>()
    private var cachedDiffs = emptyMap<String, FileDiff?>()

    init {
        background = BG
        buildLayout()
        bgRefresh()
        refreshTimer.start()

        chatInput.addFocusListener(object : java.awt.event.FocusAdapter() {
            override fun focusGained(e: java.awt.event.FocusEvent?) {
                if (chatInput.text == "\u2318 Esc to focus or unfocus Claude") {
                    chatInput.text = ""; chatInput.foreground = FG
                }
            }
            override fun focusLost(e: java.awt.event.FocusEvent?) {
                if (chatInput.text.isEmpty()) {
                    chatInput.text = "\u2318 Esc to focus or unfocus Claude"
                    chatInput.foreground = Color(0x6B, 0x72, 0x80)
                }
            }
        })
        chatInput.addActionListener { sendChat() }

        // Auto-refresh on git changes
        VirtualFileManager.getInstance().addVirtualFileListener(object : VirtualFileListener {
            override fun contentsChanged(event: VirtualFileEvent) {
                if (event.file.path.contains(".git/")) bgRefresh()
            }
        })
    }

    // ══════════════════════════════════════════════════════════════════
    // LAYOUT — matches Pencil mockup exactly
    // ══════════════════════════════════════════════════════════════════

    private fun buildLayout() {
        val changesScroll = JBScrollPane(changesPanel).apply {
            border = null; background = BG; viewport.background = BG
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            minimumSize = Dimension(250, 100)
        }

        // Claude Code chat area
        val chatSection = JBPanel<JBPanel<*>>(BorderLayout())
        chatSection.background = BG; chatSection.minimumSize = Dimension(250, 80)

        // Claude Code header
        val ccHeader = hRow(BorderLayout())
        ccHeader.fill(BG_DARK); ccHeader.border = EmptyBorder(0, 16, 0, 16)
        ccHeader.preferredSize = Dimension(0, 36)
        val ccLeft = hFlow(8)
        ccLeft.add(icon("chevron-down", GRAY, 12))
        ccLeft.add(txt("CLAUDE CODE", GRAY, 11f, true))
        val ccRight = hFlow(4)

        // AI Review button
        ccRight.add(greenBtn("\u2728 Review") { sendAiReview() })

        // Cancel button
        val cancelBtn = txt("\u25A0", RED, 12f, true).apply {
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR); toolTipText = "Cancel request"
        }
        cancelBtn.addMouseListener(click { cancelChat() })
        ccRight.add(cancelBtn)

        // History button — shows previous sessions
        val historyBtn = txt("\u23F1", GRAY, 13f, false).apply {
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR); toolTipText = "Session history"
        }
        historyBtn.addMouseListener(click { showSessionHistory(historyBtn) })
        ccRight.add(historyBtn)

        // New session button
        val newSessBtn = txt("\u2295", GRAY, 14f, false).apply {
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR); toolTipText = "New conversation"
        }
        newSessBtn.addMouseListener(click { startNewSession() })
        ccRight.add(newSessBtn)
        ccHeader.add(ccLeft, BorderLayout.WEST)
        ccHeader.add(ccRight, BorderLayout.EAST)

        val chatScroll = JBScrollPane(chatPane).apply {
            border = null; background = BG_DARKEST; viewport.background = BG_DARKEST
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        }
        chatPane.background = BG_DARKEST

        chatSection.add(ccHeader, BorderLayout.NORTH)
        chatSection.add(chatScroll, BorderLayout.CENTER)

        // Splitter
        val splitter = JSplitPane(JSplitPane.VERTICAL_SPLIT, changesScroll, chatSection)
        splitter.border = null; splitter.dividerSize = 4; splitter.resizeWeight = 0.55
        splitter.background = BG

        // Fixed input bar at bottom
        val inputBar = JBPanel<JBPanel<*>>(BorderLayout())
        inputBar.background = BG_DARK
        inputBar.border = BorderFactory.createMatteBorder(1, 0, 0, 0, BORDER)
        inputBar.preferredSize = Dimension(0, 44); inputBar.minimumSize = Dimension(0, 44)
        inputBar.maximumSize = Dimension(Int.MAX_VALUE, 44)

        // Command hint
        val cmdHint = hRow(BorderLayout())
        cmdHint.fill(BG_DARK); cmdHint.border = EmptyBorder(8, 16, 8, 16)
        inputBar.add(chatInput, BorderLayout.CENTER)

        // Send button (orange circle)
        val sendBtn = object : JLabel("\u2191") {
            init {
                font = getFont().deriveFont(Font.BOLD, 16f); foreground = Color.WHITE
                horizontalAlignment = CENTER; isOpaque = false
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                preferredSize = Dimension(34, 34); border = EmptyBorder(0, 4, 0, 4)
            }
            override fun paintComponent(g: Graphics) {
                (g as Graphics2D).setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g.color = Color(0xD4, 0x7A, 0x2A); g.fillRoundRect(2, 5, width - 4, height - 10, 8, 8)
                super.paintComponent(g)
            }
        }
        sendBtn.addMouseListener(click { sendChat() })
        inputBar.add(sendBtn, BorderLayout.EAST)

        // Footer bar
        val footer = hRow(BorderLayout())
        footer.fill(BG_DARK); footer.border = EmptyBorder(0, 16, 0, 16)
        footer.preferredSize = Dimension(0, 36)
        footer.border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, BORDER),
            EmptyBorder(0, 16, 0, 16)
        )
        val fLeft = hFlow(8)
        fLeft.add(txt("+", GRAY, 14f, false))
        fLeft.add(icon("pencil", GRAY, 14))
        val fRight = hFlow(8)
        fRight.add(txt("Bypass permissions", GRAY, 11f, false))
        footer.add(fLeft, BorderLayout.WEST)
        footer.add(fRight, BorderLayout.EAST)

        val bottomStack = JBPanel<JBPanel<*>>(BorderLayout())
        bottomStack.add(inputBar, BorderLayout.CENTER)
        bottomStack.add(footer, BorderLayout.SOUTH)

        add(splitter, BorderLayout.CENTER)
        add(bottomStack, BorderLayout.SOUTH)
    }

    // ══════════════════════════════════════════════════════════════════
    // BACKGROUND GIT READ → EDT RENDER
    // ══════════════════════════════════════════════════════════════════

    private fun bgRefresh() {
        Thread {
            val isRepo = gitService.isGitRepo()
            val files = if (isRepo) gitService.getChangedFiles() else emptyList()
            val diffs = mutableMapOf<String, FileDiff?>()
            for (cf in files) {
                if (expandedFiles.contains(cf.filePath)) {
                    diffs[cf.filePath] = gitService.getFileDiff(cf.filePath)
                }
            }
            SwingUtilities.invokeLater {
                cachedIsRepo = isRepo; cachedFiles = files; cachedDiffs = diffs
                renderChanges()
            }
        }.start()
    }

    private fun bgAction(action: () -> Unit) {
        Thread { action(); bgRefresh() }.start()
    }

    // ══════════════════════════════════════════════════════════════════
    // RENDER CHANGES — matches Pencil design
    // ══════════════════════════════════════════════════════════════════

    private fun renderChanges() {
        changesPanel.removeAll()
        changesPanel.background = BG

        if (!cachedIsRepo) {
            changesPanel.add(centeredLabel("Not a git repository"))
            changesPanel.add(Box.createVerticalGlue())
            changesPanel.revalidate(); changesPanel.repaint(); return
        }

        val totalAdded = cachedFiles.sumOf { it.addedLines }
        val totalRemoved = cachedFiles.sumOf { it.removedLines }

        // ── Header: HUNKWISE 24 files +672 -305  [✓ Accept] [✕ Discard]
        val header = hRow(BorderLayout())
        header.fill(BG_DARK); header.border = EmptyBorder(0, 16, 0, 16)
        val hLeft = hFlow(8)
        hLeft.add(icon("chevron-down", GRAY, 12))
        hLeft.add(txt("HUNKWISE", FG, 12f, true))
        if (cachedFiles.isNotEmpty()) {
            hLeft.add(txt("${cachedFiles.size} files", GRAY, 11f, false))
            hLeft.add(txt("+$totalAdded", GREEN, 11f, true))
            if (totalRemoved > 0) hLeft.add(txt("-$totalRemoved", RED, 11f, true))
        }
        val hRight = hFlow(8)
        if (cachedFiles.isNotEmpty()) {
            hRight.add(greenBtn("\u2713 Accept") { bgAction { gitService.acceptAll() } })
            hRight.add(redBtn("\u2715 Discard") { confirmDiscard("Discard ALL uncommitted changes? This cannot be undone.") { bgAction { gitService.discardAll() } } })
        }
        header.add(hLeft, BorderLayout.WEST)
        header.add(hRight, BorderLayout.EAST)
        changesPanel.add(fixH(header, 40))

        if (cachedFiles.isEmpty()) {
            changesPanel.add(Box.createVerticalStrut(30))
            changesPanel.add(centeredLabel("No uncommitted changes"))
            changesPanel.add(Box.createVerticalGlue())
            changesPanel.revalidate(); changesPanel.repaint(); return
        }

        // ── File rows
        for (cf in cachedFiles) {
            changesPanel.add(fileRow(cf))
            if (expandedFiles.contains(cf.filePath)) {
                val diff = cachedDiffs[cf.filePath]
                diff?.hunks?.forEach { hunk -> changesPanel.add(hunkRow(cf.filePath, hunk)) }
            }
        }

        changesPanel.add(Box.createVerticalGlue())
        changesPanel.revalidate(); changesPanel.repaint()
    }

    // ── File row: ▶ [GO] user.go handlers  +15 [✓][✕]
    private fun fileRow(cf: ChangedFile): JPanel {
        val row = hRow(BorderLayout())
        row.border = EmptyBorder(0, 16, 0, 16)
        row.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

        val left = hFlow(6)
        val expanded = expandedFiles.contains(cf.filePath)
        left.add(icon(if (expanded) "chevron-down" else "chevron-right",
            if (expanded) Color(0xF5, 0x9E, 0x0B) else GRAY, 10))

        val ext = File(cf.filePath).extension.uppercase()
        if (ext.isNotEmpty()) left.add(extBadge(ext, cf.status))

        left.add(txt(File(cf.filePath).name, FG, 12f, false))

        when (cf.status) {
            ChangeStatus.DELETED -> left.add(statusBadge("DELETED", Color(0xD3, 0x2F, 0x2F)))
            ChangeStatus.UNTRACKED -> left.add(statusBadge("NEW", Color(0x2E, 0x7D, 0x32)))
            else -> {}
        }

        val dirName = File(cf.filePath).parent?.let { p ->
            project.basePath?.let { r -> File(p).toRelativeString(File(r)).ifEmpty { null } }
        }
        if (dirName != null) left.add(txt(dirName, GRAY, 12f, false))

        val right = hFlow(4)
        if (cf.addedLines > 0) right.add(txt("+${cf.addedLines}", GREEN, 11f, false))
        if (cf.removedLines > 0) right.add(txt("-${cf.removedLines}", RED, 11f, false))

        // Per-file accept/discard icons (only when expanded)
        if (expanded) {
            right.add(miniGreenBtn { bgAction { gitService.acceptFile(cf.filePath) } })
            right.add(miniRedBtn { confirmDiscard("Discard changes in ${File(cf.filePath).name}?") { bgAction { gitService.discardFile(cf.filePath) } } })
        }

        row.add(left, BorderLayout.WEST)
        row.add(right, BorderLayout.EAST)

        row.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (e.clickCount == 2) { openFile(cf.filePath); return }
                if (expandedFiles.contains(cf.filePath)) expandedFiles.remove(cf.filePath)
                else expandedFiles.add(cf.filePath)
                bgRefresh()
            }
            override fun mouseEntered(e: java.awt.event.MouseEvent) {
                row.background = HOVER; row.isOpaque = true; row.repaint()
            }
            override fun mouseExited(e: java.awt.event.MouseEvent) {
                row.isOpaque = false; row.repaint()
            }
        })

        return fixH(row, 28)
    }

    // ── Hunk row: @line 9  +9 -5  [✓ Accept] [✕ Discard]
    private fun hunkRow(filePath: String, hunk: DiffHunk): JPanel {
        val row = hRow(BorderLayout())
        row.fill(BG_DARK); row.border = EmptyBorder(0, 32, 0, 16)
        row.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

        val left = hFlow(6)
        left.add(txt("@line ${hunk.newStart}", GRAY, 11f, false))

        val right = hFlow(4)
        if (hunk.newLines > 0) right.add(txt("+${hunk.newLines}", GREEN, 11f, false))
        if (hunk.oldLines > 0) right.add(txt("-${hunk.oldLines}", RED, 11f, false))

        // Accept/Discard buttons per hunk
        right.add(greenBtn("\u2713 Accept") { bgAction { gitService.acceptHunk(filePath, hunk) } })
        right.add(redBtn("\u2715 Discard") { bgAction { gitService.discardHunk(filePath, hunk) } })

        row.add(left, BorderLayout.WEST)
        row.add(right, BorderLayout.EAST)

        row.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) { jumpToLine(filePath, hunk.newStart) }
            override fun mouseEntered(e: java.awt.event.MouseEvent) {
                row.background = HOVER; row.isOpaque = true; row.repaint()
            }
            override fun mouseExited(e: java.awt.event.MouseEvent) {
                row.background = BG_DARK; row.repaint()
            }
        })

        return fixH(row, 24)
    }

    // ══════════════════════════════════════════════════════════════════
    // CHAT — streaming + hunk context + cancel + AI Review
    // ══════════════════════════════════════════════════════════════════

    private var streamingStartOffset = -1
    private val streamingBuffer = StringBuilder() // accumulates full response for saving

    private fun sendChat() {
        val msg = chatInput.text.trim()
        if (msg.isEmpty() || msg.startsWith("\u2318") || isSending) return
        chatInput.text = ""; chatInput.foreground = FG
        appendMsg("You", msg, Color(0x56, 0x9C, 0xD6))

        if (!claudeService.isAvailable()) {
            appendMsg("system", "Claude Code CLI not found.\nnpm install -g @anthropic-ai/claude-code", RED)
            return
        }

        // Build message with conversation history for context
        val contextMsg = buildMessageWithHistory(msg)
        val systemPrompt = claudeService.buildSystemPrompt()

        isSending = true
        startStreamingResponse()

        Thread {
            claudeService.sendMessageStreaming(
                message = contextMsg,
                systemPrompt = systemPrompt,
                onToken = { token ->
                    SwingUtilities.invokeLater { appendStreamToken(token) }
                },
                onActivity = { status ->
                    SwingUtilities.invokeLater { showActivity(status) }
                },
                onDone = { response ->
                    SwingUtilities.invokeLater {
                        isSending = false
                        clearActivity()
                        finishStreaming()
                        if (response.isError) {
                            appendMsg("error", response.error ?: "Unknown error", RED)
                        }
                        bgRefresh()
                    }
                }
            )
        }.start()
    }

    /** Send AI Review for all pending hunks */
    private fun sendAiReview() {
        if (isSending || cachedFiles.isEmpty()) return

        if (!claudeService.isAvailable()) {
            appendMsg("system", "Claude Code CLI not found.", RED)
            return
        }

        appendMsg("You", "\u2728 AI Review — reviewing all pending changes", Color(0x56, 0x9C, 0xD6))

        val diffSummary = buildFullDiffContext()
        val prompt = """Review these uncommitted code changes. For each file/hunk:
1. **Risk level**: high/medium/low
2. **Summary**: what changed in 1 sentence
3. **Recommendation**: accept, discard, or review manually

Be concise. Use bullet points.

$diffSummary"""

        val systemPrompt = claudeService.buildSystemPrompt()
        isSending = true
        startStreamingResponse()

        Thread {
            claudeService.sendMessageStreaming(
                message = prompt,
                systemPrompt = systemPrompt,
                onToken = { token -> SwingUtilities.invokeLater { appendStreamToken(token) } },
                onActivity = { status -> SwingUtilities.invokeLater { showActivity(status) } },
                onDone = { response ->
                    SwingUtilities.invokeLater {
                        isSending = false
                        clearActivity()
                        finishStreaming()
                        if (response.isError) appendMsg("error", response.error ?: "Error", RED)
                        bgRefresh()
                    }
                }
            )
        }.start()
    }

    private fun cancelChat() {
        claudeService.cancel()
        isSending = false
        finishStreaming()
        appendMsg("system", "Cancelled.", GRAY)
    }

    // ══════════════════════════════════════════════════════════════════
    // SESSION MANAGEMENT
    // ══════════════════════════════════════════════════════════════════

    private fun startNewSession() {
        claudeService.resetSession()
        sessionManager.createNewSession()
        chatPane.text = ""
        appendMsg("system", "New conversation started.", Color(0x10, 0xB9, 0x81))
    }

    private fun showSessionHistory(anchor: java.awt.Component) {
        val sessions = sessionManager.getSessionList()
        if (sessions.isEmpty()) {
            appendMsg("system", "No previous sessions.", GRAY)
            return
        }

        val popup = JPopupMenu()
        popup.background = BG_DARK

        for (session in sessions.take(15)) {
            val isCurrent = session.id == sessionManager.getCurrentSessionId()
            val label = "${session.title}  (${session.messageCount} msgs)"
            val item = JMenuItem(if (isCurrent) "\u25CF $label" else "  $label")
            item.font = Font("JetBrains Mono", Font.PLAIN, 11)
            item.foreground = if (isCurrent) GREEN else FG
            item.background = BG_DARK
            item.addActionListener { loadSession(session.id) }
            popup.add(item)
        }

        popup.addSeparator()
        val clearItem = JMenuItem("  Clear all history")
        clearItem.font = Font("JetBrains Mono", Font.PLAIN, 11)
        clearItem.foreground = RED; clearItem.background = BG_DARK
        clearItem.addActionListener {
            val result = JOptionPane.showConfirmDialog(
                this, "Delete all chat history?", "Clear History",
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE
            )
            if (result == JOptionPane.YES_OPTION) {
                for (s in sessionManager.getSessionList()) sessionManager.deleteSession(s.id)
                chatPane.text = ""; startNewSession()
            }
        }
        popup.add(clearItem)
        popup.show(anchor, 0, anchor.height)
    }

    private fun loadSession(sessionId: String) {
        val session = sessionManager.switchToSession(sessionId) ?: return
        claudeService.resetSession()
        chatPane.text = ""
        for (msg in session.messages) {
            val color = when (msg.sender) {
                "You" -> Color(0x56, 0x9C, 0xD6)
                "Claude" -> Color(0xCE, 0x91, 0x78)
                "system" -> Color(0x10, 0xB9, 0x81)
                "error" -> RED
                else -> GRAY
            }
            appendMsgNoSave(msg.sender, msg.text, color)
        }
    }

    /**
     * Build message with conversation history so Claude has context.
     * Includes last N messages + hunk context if relevant.
     */
    private fun buildMessageWithHistory(msg: String): String {
        val session = sessionManager.getCurrentSession()
        val recentMessages = session.messages
            .filter { it.sender == "You" || it.sender == "Claude" }

        val sb = StringBuilder()

        // Include full conversation history — no truncation, let Claude manage its context
        if (recentMessages.size > 1) {
            sb.appendLine("<conversation_history>")
            for (m in recentMessages.dropLast(1)) {
                val role = if (m.sender == "You") "User" else "Assistant"
                sb.appendLine("$role: ${m.text}")
                sb.appendLine()
            }
            sb.appendLine("</conversation_history>")
            sb.appendLine()
        }

        // Add hunk context if user asks about changes
        val lowerMsg = msg.lowercase()
        val needsContext = lowerMsg.contains("cambio") || lowerMsg.contains("change") ||
            lowerMsg.contains("hunk") || lowerMsg.contains("diff") ||
            lowerMsg.contains("explica") || lowerMsg.contains("explain") ||
            lowerMsg.contains("seguro") || lowerMsg.contains("safe") ||
            lowerMsg.contains("review") || lowerMsg.contains("revisa")

        if (needsContext && cachedFiles.isNotEmpty()) {
            sb.appendLine("<uncommitted_changes>")
            sb.append(buildFullDiffContext())
            sb.appendLine("</uncommitted_changes>")
            sb.appendLine()
        }

        sb.append(msg)
        return sb.toString()
    }

    private fun buildFullDiffContext(): String {
        val sb = StringBuilder()
        for (cf in cachedFiles) {
            val root = project.basePath ?: continue
            val relPath = File(cf.filePath).toRelativeString(File(root))
            sb.appendLine("--- $relPath (${cf.status}) +${cf.addedLines} -${cf.removedLines}")
            val diff = cachedDiffs[cf.filePath]
            diff?.hunks?.forEach { h ->
                sb.appendLine("  @@ -${h.oldStart},${h.oldLines} +${h.newStart},${h.newLines} @@")
                h.removedContent.forEach { sb.appendLine("  -$it") }
                h.addedContent.forEach { sb.appendLine("  +$it") }
            }
        }
        return sb.toString()
    }

    // ── Activity indicator (shows what Claude is doing) ────────────

    private var activityOffset = -1

    private fun showActivity(status: String) {
        if (status.isEmpty()) { clearActivity(); return }

        val doc = chatPane.styledDocument

        // If we already have activity text, replace it
        if (activityOffset >= 0) {
            try {
                doc.remove(activityOffset, doc.length - activityOffset)
            } catch (_: Exception) {}
        } else {
            activityOffset = doc.length
        }

        // Activity line: dim background bar with tool icon
        val barStyle = chatPane.addStyle("act_bar_${doc.length}", null)
        StyleConstants.setForeground(barStyle, Color(0x10, 0xB9, 0x81))
        StyleConstants.setFontSize(barStyle, 11)
        StyleConstants.setFontFamily(barStyle, "JetBrains Mono")
        StyleConstants.setItalic(barStyle, true)
        doc.insertString(doc.length, "\n$status", barStyle)
        chatPane.caretPosition = doc.length
    }

    private fun clearActivity() {
        if (activityOffset >= 0) {
            try {
                val doc = chatPane.styledDocument
                if (activityOffset <= doc.length) {
                    doc.remove(activityOffset, doc.length - activityOffset)
                }
            } catch (_: Exception) {}
            activityOffset = -1
        }
    }

    // ── Streaming response rendering ─────────────────────────────────

    private fun startStreamingResponse() {
        streamingBuffer.clear()
        val doc = chatPane.styledDocument
        if (doc.length > 0) doc.insertString(doc.length, "\n\n", null)

        // "Claude" sender label
        val s = chatPane.addStyle("stream_sender", null)
        StyleConstants.setBold(s, true)
        StyleConstants.setForeground(s, Color(0xCE, 0x91, 0x78))
        StyleConstants.setFontSize(s, 12)
        StyleConstants.setFontFamily(s, "JetBrains Mono")
        doc.insertString(doc.length, "Claude\n", s)

        streamingStartOffset = doc.length
        chatPane.caretPosition = doc.length
    }

    private fun appendStreamToken(token: String) {
        streamingBuffer.append(token)
        // Show raw text while streaming (no markdown yet)
        val doc = chatPane.styledDocument
        val s = chatPane.addStyle("stream_${doc.length}", null)
        StyleConstants.setForeground(s, FG)
        StyleConstants.setFontSize(s, 12)
        StyleConstants.setFontFamily(s, "SansSerif")
        doc.insertString(doc.length, token, s)
        chatPane.caretPosition = doc.length
    }

    private fun finishStreaming() {
        val fullText = streamingBuffer.toString()
        if (fullText.isNotBlank()) {
            // Save to session
            sessionManager.addMessage("Claude", fullText)

            // Re-render with markdown formatting:
            // Remove the raw streamed text, replace with formatted version
            try {
                val doc = chatPane.styledDocument
                if (streamingStartOffset >= 0 && streamingStartOffset <= doc.length) {
                    doc.remove(streamingStartOffset, doc.length - streamingStartOffset)
                    MarkdownRenderer.render(chatPane, fullText, FG)
                    chatPane.caretPosition = doc.length
                }
            } catch (_: Exception) {}
        }
        streamingBuffer.clear()
        streamingStartOffset = -1
    }

    /** Append message and save to session history */
    private fun appendMsg(sender: String, text: String, color: Color) {
        sessionManager.addMessage(sender, text)
        appendMsgNoSave(sender, text, color)
    }

    /** Append message to UI only (used when restoring history) */
    private fun appendMsgNoSave(sender: String, text: String, color: Color) {
        val doc = chatPane.styledDocument
        if (doc.length > 0) doc.insertString(doc.length, "\n\n", null)
        val s = chatPane.addStyle("s${doc.length}", null)
        StyleConstants.setBold(s, true); StyleConstants.setForeground(s, color)
        StyleConstants.setFontSize(s, 12); StyleConstants.setFontFamily(s, "JetBrains Mono")
        doc.insertString(doc.length, "$sender\n", s)
        MarkdownRenderer.render(chatPane, text, FG)
        chatPane.caretPosition = doc.length
    }

    // ══════════════════════════════════════════════════════════════════
    // NAVIGATION
    // ══════════════════════════════════════════════════════════════════

    private fun confirmDiscard(message: String, action: () -> Unit) {
        val result = JOptionPane.showConfirmDialog(
            this, message, "Confirm Discard",
            JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE
        )
        if (result == JOptionPane.YES_OPTION) action()
    }

    private fun openFile(filePath: String) {
        val vFile = LocalFileSystem.getInstance().findFileByPath(filePath) ?: return
        FileEditorManager.getInstance(project).openFile(vFile, true)
    }

    private fun jumpToLine(filePath: String, line: Int) {
        val vFile = LocalFileSystem.getInstance().findFileByPath(filePath) ?: return
        val editors = FileEditorManager.getInstance(project).openFile(vFile, true)
        for (e in editors) {
            if (e is TextEditor) {
                val l = maxOf(0, line - 1)
                if (l < e.editor.document.lineCount) {
                    e.editor.caretModel.moveToOffset(e.editor.document.getLineStartOffset(l))
                    e.editor.scrollingModel.scrollToCaret(com.intellij.openapi.editor.ScrollType.CENTER)
                }
                break
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // UI PRIMITIVES — matching Pencil mockup dark theme
    // ══════════════════════════════════════════════════════════════════

    private fun hRow(l: LayoutManager) = JBPanel<JBPanel<*>>(l).apply { background = BG; isOpaque = false }
    private fun JPanel.fill(c: Color) { background = c; isOpaque = true }
    private fun hFlow(g: Int) = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, g, 0)).apply { isOpaque = false }
    private fun txt(t: String, c: Color, sz: Float, b: Boolean) = JBLabel(t).apply {
        foreground = c; font = Font("JetBrains Mono", if (b) Font.BOLD else Font.PLAIN, sz.toInt())
    }
    private fun JBLabel.letterSpacing(v: Float): JBLabel { /* no-op in swing */ return this }
    private fun icon(name: String, color: Color, size: Int) = com.intellij.ui.components.JBLabel().apply {
        val ico = com.intellij.openapi.util.IconLoader.getIcon("/icons/hunkwise13.svg", ReviewPanel::class.java)
        // Fallback to text-based icons
        text = when (name) {
            "chevron-down" -> "\u25BC"
            "chevron-right" -> "\u25B6"
            "check" -> "\u2713"
            "x" -> "\u2715"
            "pencil" -> "\u270E"
            else -> "\u25CF"
        }
        foreground = color; font = font.deriveFont(size.toFloat())
        preferredSize = Dimension(size + 2, size + 2)
    }
    private fun fixH(p: JPanel, h: Int): JPanel {
        p.maximumSize = Dimension(Int.MAX_VALUE, h); p.preferredSize = Dimension(p.preferredSize.width, h); return p
    }
    private fun centeredLabel(t: String): JPanel {
        val p = hRow(GridBagLayout()); p.add(txt(t, GRAY, 12f, false)); return p
    }
    private fun click(action: () -> Unit) = object : java.awt.event.MouseAdapter() {
        override fun mouseClicked(e: java.awt.event.MouseEvent) { action() }
    }

    // ── Buttons matching Pencil design ──

    private fun greenBtn(text: String, action: () -> Unit): JPanel {
        val btn = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.CENTER, 3, 0))
        btn.background = Color(0x2E, 0xA0, 0x43, 0x80); btn.isOpaque = true
        btn.border = EmptyBorder(2, 6, 2, 6)
        btn.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        btn.add(txt("\u2713", Color(0x4E, 0xC9, 0xB0), 9f, true))
        btn.add(txt(text.replace("\u2713 ", ""), Color(0x4E, 0xC9, 0xB0), 9f, true))
        btn.addMouseListener(click { action() })
        return btn
    }

    private fun redBtn(text: String, action: () -> Unit): JPanel {
        val btn = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.CENTER, 3, 0))
        btn.background = Color(0xA0, 0x2E, 0x2E, 0x80); btn.isOpaque = true
        btn.border = EmptyBorder(2, 6, 2, 6)
        btn.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        btn.add(txt("\u2715", Color(0xF4, 0x47, 0x47), 9f, true))
        btn.add(txt(text.replace("\u2715 ", ""), Color(0xF4, 0x47, 0x47), 9f, true))
        btn.addMouseListener(click { action() })
        return btn
    }

    private fun miniGreenBtn(action: () -> Unit): JPanel {
        val btn = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.CENTER, 0, 0))
        btn.background = Color(0x2E, 0xA0, 0x43, 0x80); btn.isOpaque = true
        btn.border = EmptyBorder(2, 5, 2, 5)
        btn.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        btn.add(txt("\u2713", Color(0x4E, 0xC9, 0xB0), 10f, true))
        btn.addMouseListener(click { action() })
        return btn
    }

    private fun miniRedBtn(action: () -> Unit): JPanel {
        val btn = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.CENTER, 0, 0))
        btn.background = Color(0xA0, 0x2E, 0x2E, 0x80); btn.isOpaque = true
        btn.border = EmptyBorder(2, 5, 2, 5)
        btn.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        btn.add(txt("\u2715", Color(0xF4, 0x47, 0x47), 10f, true))
        btn.addMouseListener(click { action() })
        return btn
    }

    // ── Badges matching Pencil design ──

    private fun extBadge(ext: String, status: ChangeStatus): JLabel {
        val c = EXT_COLORS[ext.lowercase()] ?: Color(0x60, 0x60, 0x60)
        return object : JLabel(ext) {
            init {
                font = Font("JetBrains Mono", Font.BOLD, 9); foreground = Color.WHITE
                horizontalAlignment = CENTER; isOpaque = false; border = EmptyBorder(1, 5, 1, 5)
            }
            override fun getPreferredSize() = Dimension(getFontMetrics(font).stringWidth(text) + 12, 16)
            override fun paintComponent(g: Graphics) {
                (g as Graphics2D).setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g.color = Color(c.red, c.green, c.blue, 0x80)
                g.fillRoundRect(0, 1, width, height - 2, 4, 4); super.paintComponent(g)
            }
        }
    }

    private fun statusBadge(text: String, bg: Color): JLabel {
        return object : JLabel(text) {
            init {
                font = Font("JetBrains Mono", Font.BOLD, 9)
                foreground = if (bg.red > 150) Color(0xFF, 0xCC, 0xCC) else Color(0xCC, 0xFF, 0xCC)
                horizontalAlignment = CENTER; isOpaque = false; border = EmptyBorder(1, 5, 1, 5)
            }
            override fun getPreferredSize() = Dimension(getFontMetrics(font).stringWidth(text) + 12, 16)
            override fun paintComponent(g: Graphics) {
                (g as Graphics2D).setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g.color = bg; g.fillRoundRect(0, 1, width, height - 2, 4, 4); super.paintComponent(g)
            }
        }
    }

    companion object {
        val BG = Color(0x0A, 0x0A, 0x0A)
        val BG_DARK = Color(0x0F, 0x0F, 0x0F)
        val BG_DARKEST = Color(0x0A, 0x0A, 0x0A)
        val FG = Color(0xFA, 0xFA, 0xFA)
        val GRAY = Color(0x6B, 0x72, 0x80)
        val GREEN = Color(0x10, 0xB9, 0x81)
        val RED = Color(0xF4, 0x47, 0x47)
        val HOVER = Color(0x1F, 0x1F, 0x1F)
        val BORDER = Color(0x2A, 0x2A, 0x2A)

        val EXT_COLORS = mapOf(
            "go" to Color(0x00, 0xAD, 0xD8), "kt" to Color(0x7F, 0x52, 0xFF),
            "kts" to Color(0x7F, 0x52, 0xFF), "java" to Color(0xB0, 0x71, 0x19),
            "ts" to Color(0x31, 0x78, 0xC6), "tsx" to Color(0x31, 0x78, 0xC6),
            "js" to Color(0xF1, 0xE0, 0x5A), "jsx" to Color(0xF1, 0xE0, 0x5A),
            "py" to Color(0x35, 0x72, 0xA5), "rs" to Color(0xDE, 0xA5, 0x84),
            "rb" to Color(0xCC, 0x34, 0x2D), "css" to Color(0x56, 0x3D, 0x7C),
            "html" to Color(0xE3, 0x4C, 0x26), "json" to Color(0x9B, 0x9B, 0x9B),
            "yml" to Color(0xCB, 0x17, 0x1E), "yaml" to Color(0xCB, 0x17, 0x1E),
            "xml" to Color(0xF0, 0x80, 0x00), "md" to Color(0x08, 0x3F, 0xA1),
            "sql" to Color(0xE3, 0x8C, 0x00), "sh" to Color(0x4E, 0xAA, 0x25),
            "swift" to Color(0xF0, 0x52, 0x38), "dart" to Color(0x00, 0xB4, 0xAB),
            "vue" to Color(0x41, 0xB8, 0x83), "svelte" to Color(0xFF, 0x3E, 0x00),
            "mak" to Color(0x6D, 0x8A, 0x6D), "git" to Color(0xF0, 0x50, 0x33),
            "dockerfile" to Color(0x38, 0x4D, 0x54), "toml" to Color(0x9C, 0x40, 0x21),
            "c" to Color(0x55, 0x55, 0x99), "cpp" to Color(0x00, 0x59, 0x9C),
            "php" to Color(0x77, 0x7B, 0xB4), "scss" to Color(0xCD, 0x66, 0x99),
            "exa" to Color(0x7C, 0x7C, 0x7C), "jso" to Color(0x9B, 0x9B, 0x9B),
        )
    }
}
