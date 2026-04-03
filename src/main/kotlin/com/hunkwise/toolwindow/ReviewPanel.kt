package com.hunkwise.toolwindow

import com.hunkwise.HunkwiseColors
import com.hunkwise.chat.*
import com.hunkwise.editor.InlineDiffService
import com.hunkwise.git.ProjectGitService
import com.hunkwise.git.ProjectGitService.*
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
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
 * - Streaming text responses
 * - Persistent activity log (Reading, Editing, etc.)
 * - Inline diff blocks (collapsible, click opens file with Accept/Discard)
 * - Permission denials shown with Allow/Allow always/Deny
 * - All content persists (nothing gets deleted)
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
    private val streamingBuffer = StringBuilder()
    private val trackedFiles = mutableSetOf<String>()
    private var lastActivity = ""
    private var lastSentMessage = ""
    private var actionButton: JLabel? = null
    private var pendingImagePath: String? = null

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
        val header = JPanel(BorderLayout()).apply {
            background = HunkwiseColors.BG; border = EmptyBorder(8, 14, 8, 14)
            preferredSize = Dimension(0, 38)
        }
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
        header.add(hLeft, BorderLayout.WEST); header.add(hRight, BorderLayout.EAST)

        val chatScroll = JBScrollPane(chatPane).apply {
            border = null; background = HunkwiseColors.BG; viewport.background = HunkwiseColors.BG
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        }

        val bottomPanel = JPanel(BorderLayout()).apply { background = HunkwiseColors.BG }
        val inputBar = JPanel(BorderLayout()).apply {
            background = HunkwiseColors.SURFACE
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, HunkwiseColors.BORDER), EmptyBorder(6, 8, 6, 8))
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
                g.color = if (isSending) HunkwiseColors.RED else HunkwiseColors.ORANGE
                g.fillRoundRect(2, 5, width - 4, height - 10, 8, 8)
                super.paintComponent(g)
            }
        }
        actionButton = sendBtn
        sendBtn.addMouseListener(click {
            if (isSending) { claudeService.cancel(); isSending = false; updateBtn() }
            else sendChat()
        })
        // Image attach button
        val imgBtn = lbl("\uD83D\uDDBC", HunkwiseColors.MUTED, 16, false).apply {
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = "Attach image"
            border = EmptyBorder(0, 6, 0, 4)
        }
        imgBtn.addMouseListener(click { attachImage() })

        val rightBtns = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply { isOpaque = false }
        rightBtns.add(imgBtn); rightBtns.add(sendBtn)

        inputBar.add(inputWrapper, BorderLayout.CENTER); inputBar.add(rightBtns, BorderLayout.EAST)

        val toolbar = JPanel(BorderLayout()).apply {
            background = HunkwiseColors.BG; border = EmptyBorder(4, 14, 6, 14)
        }
        val tbL = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply { isOpaque = false }
        tbL.add(lbl("\u221E Agent \u25BE", HunkwiseColors.MUTED, 11, false))
        tbL.add(lbl("Auto \u25BE", HunkwiseColors.MUTED, 11, false))
        val tbR = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply { isOpaque = false }
        val bypassLbl = lbl("\uD83D\uDD12 Bypass OFF", HunkwiseColors.MUTED, 11, false).apply {
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }
        bypassLbl.addMouseListener(click {
            claudeService.autoAcceptCommands = !claudeService.autoAcceptCommands
            bypassLbl.text = if (claudeService.autoAcceptCommands) "\uD83D\uDD13 Bypass ON" else "\uD83D\uDD12 Bypass OFF"
            bypassLbl.foreground = if (claudeService.autoAcceptCommands) HunkwiseColors.GREEN else HunkwiseColors.MUTED
        })
        tbR.add(bypassLbl)
        toolbar.add(tbL, BorderLayout.WEST); toolbar.add(tbR, BorderLayout.EAST)

        bottomPanel.add(inputBar, BorderLayout.CENTER); bottomPanel.add(toolbar, BorderLayout.SOUTH)
        add(header, BorderLayout.NORTH); add(chatScroll, BorderLayout.CENTER); add(bottomPanel, BorderLayout.SOUTH)
    }

    private fun updateBtn() { actionButton?.text = if (isSending) "\u25A0" else "\u2191"; actionButton?.repaint() }

    private fun attachImage() {
        val chooser = JFileChooser().apply {
            dialogTitle = "Select Image"
            fileFilter = javax.swing.filechooser.FileNameExtensionFilter(
                "Images", "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg"
            )
            currentDirectory = java.io.File(System.getProperty("user.home"), "Desktop")
        }
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            val file = chooser.selectedFile
            pendingImagePath = file.absolutePath

            // Show attached image indicator in input
            if (chatInput.text == "Reject, suggest, follow up?" || chatInput.text.isEmpty()) {
                chatInput.text = ""
                chatInput.foreground = HunkwiseColors.FG
            }

            // Show thumbnail indicator in chat
            val doc = chatPane.styledDocument
            if (doc.length > 0) doc.insertString(doc.length, "\n", null)
            val s = chatPane.addStyle("img${doc.length}", null)
            StyleConstants.setForeground(s, HunkwiseColors.BLUE)
            StyleConstants.setFontSize(s, 11); StyleConstants.setFontFamily(s, "JetBrains Mono")
            StyleConstants.setItalic(s, true)
            doc.insertString(doc.length, "\uD83D\uDDBC Attached: ${file.name}", s)
            chatPane.caretPosition = doc.length

            // Focus input for message
            chatInput.requestFocusInWindow()
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // CHAT
    // ══════════════════════════════════════════════════════════════════

    private fun sendChat() {
        var msg = chatInput.text.trim()
        if (msg.isEmpty() || msg == "Reject, suggest, follow up?") {
            if (pendingImagePath == null) return
            msg = "describe this image"
        }
        chatInput.text = ""; chatInput.foreground = HunkwiseColors.FG

        // If image is attached, include path in message
        val imagePath = pendingImagePath
        if (imagePath != null) {
            msg = "$msg\n\nImage file: $imagePath\nPlease read and analyze this image file."
            appendMsg("You", "${msg.substringBefore("\n")}\n\uD83D\uDDBC ${File(imagePath).name}", HunkwiseColors.SENDER_USER)
            pendingImagePath = null
        } else {
            appendMsg("You", msg, HunkwiseColors.SENDER_USER)
        }
        lastSentMessage = msg
        if (!claudeService.isAvailable()) {
            appendMsg("system", "Claude Code not found.", HunkwiseColors.RED); return
        }
        val ctx = buildContext(msg)
        val sys = claudeService.buildSystemPrompt()
        trackedFiles.clear(); isSending = true; updateBtn()
        streamingBuffer.clear()

        // Show "Claude" header
        val doc = chatPane.styledDocument
        if (doc.length > 0) doc.insertString(doc.length, "\n\n", null)
        val s = chatPane.addStyle("hdr${doc.length}", null)
        StyleConstants.setBold(s, true); StyleConstants.setForeground(s, HunkwiseColors.SENDER_CLAUDE)
        StyleConstants.setFontSize(s, 12); StyleConstants.setFontFamily(s, "JetBrains Mono")
        doc.insertString(doc.length, "Claude\n", s)
        chatPane.caretPosition = doc.length

        Thread {
            claudeService.sendMessageStreaming(
                message = ctx, systemPrompt = sys,
                onToken = { t -> SwingUtilities.invokeLater { appendToken(t) } },
                onActivity = { a -> SwingUtilities.invokeLater { showActivity(a) } },
                onToolUse = { e -> SwingUtilities.invokeLater { handleTool(e) } },
                onPermissionDenied = { d -> SwingUtilities.invokeLater { showPermissions(d) } },
                onDone = { r ->
                    SwingUtilities.invokeLater {
                        isSending = false; updateBtn(); lastActivity = ""
                        val text = streamingBuffer.toString()
                        if (text.isNotBlank()) sessionManager.addMessage("Claude", text)
                        if (r.isError) appendMsg("error", r.error ?: "Error", HunkwiseColors.RED)
                    }
                }
            )
        }.start()
    }

    private fun appendToken(token: String) {
        streamingBuffer.append(token)
        val doc = chatPane.styledDocument
        val s = chatPane.addStyle("tk${doc.length}", null)
        StyleConstants.setForeground(s, HunkwiseColors.FG)
        StyleConstants.setFontSize(s, 12); StyleConstants.setFontFamily(s, "SansSerif")
        doc.insertString(doc.length, token, s)
        chatPane.caretPosition = doc.length
    }

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

    // ── Tool events → diff blocks ───────────────────────────────────

    private fun handleTool(event: ClaudeCodeService.ToolEvent) {
        if (event.toolName in listOf("Edit", "Write")) {
            val fp = event.filePath ?: return
            Thread {
                val diff = gitService.getFileDiff(fp)
                if (diff != null && diff.hunks.isNotEmpty()) {
                    SwingUtilities.invokeLater { trackedFiles.add(fp); insertDiff(fp, diff) }
                }
            }.start()
        }
    }

    private fun insertDiff(filePath: String, diff: FileDiff) {
        val w = chatPane.width.coerceAtLeast(300) - 40
        val added = diff.hunks.flatMap { it.addedContent }
        val removed = diff.hunks.flatMap { it.removedContent }
        val addCount = diff.hunks.sumOf { it.newLines }
        val remCount = diff.hunks.sumOf { it.oldLines }

        val block = JPanel(BorderLayout())
        block.background = HunkwiseColors.SURFACE
        block.border = BorderFactory.createLineBorder(HunkwiseColors.BORDER, 1, true)
        block.maximumSize = Dimension(w, 350)

        val content = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); background = HunkwiseColors.SURFACE }
        for (line in removed.take(8)) {
            val r = JPanel(BorderLayout()).apply { background = HunkwiseColors.REMOVED_BG; border = EmptyBorder(1, 10, 1, 10); maximumSize = Dimension(w, 18) }
            r.add(lbl("- $line", HunkwiseColors.RED, 11, false), BorderLayout.WEST); content.add(r)
        }
        if (removed.size > 8) content.add(lbl("  ... ${removed.size - 8} more", HunkwiseColors.MUTED, 10, false))
        for (line in added.take(8)) {
            val r = JPanel(BorderLayout()).apply { background = HunkwiseColors.ADDED_BG; border = EmptyBorder(1, 10, 1, 10); maximumSize = Dimension(w, 18) }
            r.add(lbl("+ $line", HunkwiseColors.GREEN, 11, false), BorderLayout.WEST); content.add(r)
        }
        if (added.size > 8) content.add(lbl("  ... ${added.size - 8} more", HunkwiseColors.MUTED, 10, false))

        val arrow = lbl("\u25BC", HunkwiseColors.MUTED, 9, false)
        val header = JPanel(BorderLayout()).apply {
            background = HunkwiseColors.SURFACE_ALT; border = EmptyBorder(6, 10, 6, 10)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }
        val hL = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply { isOpaque = false }
        hL.add(arrow); hL.add(lbl("\u270E", HunkwiseColors.MUTED, 12, false))
        hL.add(lbl(File(filePath).name, HunkwiseColors.FG, 12, true))
        if (addCount > 0) hL.add(lbl("+$addCount", HunkwiseColors.GREEN, 11, false))
        if (remCount > 0) hL.add(lbl("-$remCount", HunkwiseColors.RED, 11, false))
        header.add(hL, BorderLayout.WEST)
        header.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (e.clickCount == 2) project.service<InlineDiffService>().openFileWithDiff(filePath)
                else { content.isVisible = !content.isVisible; arrow.text = if (content.isVisible) "\u25BC" else "\u25B6"; block.revalidate() }
            }
        })
        content.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        content.addMouseListener(click { project.service<InlineDiffService>().openFileWithDiff(filePath) })

        block.add(header, BorderLayout.NORTH); block.add(content, BorderLayout.CENTER)
        insertComponent(block)
        sessionManager.addDiffMessage(filePath, addCount, remCount)
    }

    // ── Permission denials ──────────────────────────────────────────

    private fun showPermissions(denials: List<ClaudeCodeService.PermissionDenial>) {
        val w = chatPane.width.coerceAtLeast(300) - 40
        for (denial in denials) {
            val desc = when (denial.toolName) {
                "Read" -> "Read ${denial.toolInput["file_path"] ?: ""}"
                "Write" -> "Write to ${denial.toolInput["file_path"] ?: ""}"
                "Edit" -> "Edit ${denial.toolInput["file_path"] ?: ""}"
                "Bash" -> "Bash command\n  ${(denial.toolInput["command"] ?: "").take(80)}"
                "Glob" -> "Search ${denial.toolInput["pattern"] ?: ""} in ${denial.toolInput["path"] ?: ""}"
                else -> denial.toolName
            }
            val block = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS); background = HunkwiseColors.SURFACE
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(HunkwiseColors.PERMISSION_BORDER, 1, true), EmptyBorder(10, 14, 10, 14))
                maximumSize = Dimension(w, 200)
            }
            block.add(lbl(desc, HunkwiseColors.FG, 12, true))
            block.add(Box.createVerticalStrut(6))

            val btnRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply { isOpaque = false }
            val allowBtn = styledBtn("Allow", HunkwiseColors.GREEN)
            val alwaysBtn = styledBtn("Allow always", HunkwiseColors.CYAN)
            val denyBtn = styledBtn("Deny", HunkwiseColors.RED)

            fun done(status: String, color: Color) {
                allowBtn.isVisible = false; alwaysBtn.isVisible = false; denyBtn.isVisible = false
                btnRow.add(lbl(status, color, 11, false))
                block.border = BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(color, 1, true), EmptyBorder(10, 14, 10, 14))
                block.revalidate()
            }

            fun rerun(permanent: Boolean) {
                val saved = lastSentMessage; if (saved.isEmpty()) return
                if (permanent) claudeService.autoAcceptCommands = true
                isSending = true; updateBtn(); streamingBuffer.clear()
                val doc = chatPane.styledDocument
                if (doc.length > 0) doc.insertString(doc.length, "\n\n", null)
                val s = chatPane.addStyle("rhdr${doc.length}", null)
                StyleConstants.setBold(s, true); StyleConstants.setForeground(s, HunkwiseColors.SENDER_CLAUDE)
                StyleConstants.setFontSize(s, 12); StyleConstants.setFontFamily(s, "JetBrains Mono")
                doc.insertString(doc.length, "Claude\n", s)
                Thread {
                    val prev = claudeService.autoAcceptCommands
                    if (!permanent) claudeService.autoAcceptCommands = true
                    claudeService.sendMessageStreaming(message = saved,
                        onToken = { t -> SwingUtilities.invokeLater { appendToken(t) } },
                        onActivity = { a -> SwingUtilities.invokeLater { showActivity(a) } },
                        onToolUse = { e -> SwingUtilities.invokeLater { handleTool(e) } },
                        onPermissionDenied = { d -> SwingUtilities.invokeLater { showPermissions(d) } },
                        onDone = { r ->
                            if (!permanent) claudeService.autoAcceptCommands = prev
                            SwingUtilities.invokeLater {
                                isSending = false; updateBtn()
                                val text = streamingBuffer.toString()
                                if (text.isNotBlank()) sessionManager.addMessage("Claude", text)
                                if (r.isError) appendMsg("error", r.error ?: "Error", HunkwiseColors.RED)
                            }
                        })
                }.start()
            }

            allowBtn.addMouseListener(click { done("\u2713 Allowed", HunkwiseColors.GREEN); rerun(false) })
            alwaysBtn.addMouseListener(click { done("\u2713 Always allowed", HunkwiseColors.CYAN); rerun(true) })
            denyBtn.addMouseListener(click { done("\u2715 Denied", HunkwiseColors.RED) })

            btnRow.add(allowBtn); btnRow.add(alwaysBtn); btnRow.add(denyBtn)
            block.add(btnRow)
            insertComponent(block)
        }
    }

    // ── Insert component into chat ──────────────────────────────────

    private fun insertComponent(comp: JPanel) {
        lastActivity = ""
        val doc = chatPane.styledDocument
        doc.insertString(doc.length, "\n", null)
        chatPane.caretPosition = doc.length
        chatPane.insertComponent(comp)
        doc.insertString(doc.length, "\n", null)
        chatPane.caretPosition = doc.length
    }

    // ── Messages ────────────────────────────────────────────────────

    private fun appendMsg(sender: String, text: String, color: Color) {
        sessionManager.addMessage(sender, text)
        val doc = chatPane.styledDocument
        if (doc.length > 0) doc.insertString(doc.length, "\n\n", null)
        val s = chatPane.addStyle("msg${doc.length}", null)
        StyleConstants.setBold(s, true); StyleConstants.setForeground(s, color)
        StyleConstants.setFontSize(s, 12); StyleConstants.setFontFamily(s, "JetBrains Mono")
        doc.insertString(doc.length, "$sender\n", s)
        MarkdownRenderer.render(chatPane, text, HunkwiseColors.FG)
        chatPane.caretPosition = doc.length
    }

    // ── File change detection ───────────────────────────────────────

    private fun listenForFileChanges() {
        project.messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: MutableList<out VFileEvent>) {
                if (!isSending) return
                for (event in events) {
                    if (event is VFileContentChangeEvent) {
                        val path = event.file.path; val bp = project.basePath ?: continue
                        if (!path.startsWith(bp) || path.contains("/.git/") || path.contains("/.idea/")) continue
                        if (trackedFiles.contains(path)) continue
                        Thread {
                            val diff = gitService.getFileDiff(path)
                            if (diff != null && diff.hunks.isNotEmpty())
                                SwingUtilities.invokeLater { trackedFiles.add(path); insertDiff(path, diff) }
                        }.start()
                    }
                }
            }
        })
    }

    // ── Sessions ────────────────────────────────────────────────────

    private fun newSession() { claudeService.resetSession(); sessionManager.createNewSession(); chatPane.text = "" }

    private fun showHistory(anchor: Component) {
        val sessions = sessionManager.getSessionList(); if (sessions.isEmpty()) return
        val popup = JPopupMenu().apply { background = HunkwiseColors.SURFACE }
        for (sess in sessions.take(15)) {
            val cur = sess.id == sessionManager.getCurrentSessionId()
            val item = JMenuItem(if (cur) "\u25CF ${sess.title}" else "  ${sess.title}")
            item.font = Font("JetBrains Mono", Font.PLAIN, 11)
            item.foreground = if (cur) HunkwiseColors.GREEN else HunkwiseColors.FG
            item.background = HunkwiseColors.SURFACE
            item.addActionListener { loadSession(sess.id) }
            popup.add(item)
        }
        popup.show(anchor, 0, anchor.height)
    }

    private fun loadSession(id: String) {
        val sess = sessionManager.switchToSession(id) ?: return
        claudeService.resetSession(); chatPane.text = ""
        for (msg in sess.messages) {
            when (msg.sender) {
                "activity" -> showActivity(msg.text)
                "diff" -> msg.filePath?.let { fp ->
                    Thread {
                        val diff = gitService.getFileDiff(fp)
                        if (diff != null && diff.hunks.isNotEmpty())
                            SwingUtilities.invokeLater { insertDiff(fp, diff) }
                    }.start()
                }
                else -> {
                    val color = when (msg.sender) {
                        "You" -> HunkwiseColors.SENDER_USER; "Claude" -> HunkwiseColors.SENDER_CLAUDE
                        "system" -> HunkwiseColors.SENDER_SYSTEM; "error" -> HunkwiseColors.SENDER_ERROR
                        else -> HunkwiseColors.MUTED
                    }
                    val doc = chatPane.styledDocument
                    if (doc.length > 0) doc.insertString(doc.length, "\n\n", null)
                    val s = chatPane.addStyle("ld${doc.length}", null)
                    StyleConstants.setBold(s, true); StyleConstants.setForeground(s, color)
                    StyleConstants.setFontSize(s, 12); StyleConstants.setFontFamily(s, "JetBrains Mono")
                    doc.insertString(doc.length, "${msg.sender}\n", s)
                    MarkdownRenderer.render(chatPane, msg.text, HunkwiseColors.FG)
                    chatPane.caretPosition = doc.length
                }
            }
        }
    }

    private fun buildContext(msg: String): String {
        val sess = sessionManager.getCurrentSession()
        val recent = sess.messages.filter { it.sender == "You" || it.sender == "Claude" }
        val sb = StringBuilder()
        if (recent.size > 1) {
            sb.appendLine("<conversation_history>")
            for (m in recent.dropLast(1)) sb.appendLine("${if (m.sender == "You") "User" else "Assistant"}: ${m.text}\n")
            sb.appendLine("</conversation_history>\n")
        }
        sb.append(msg); return sb.toString()
    }

    override fun dispose() {}

    private fun lbl(t: String, c: Color, sz: Int, b: Boolean) = JLabel(t).apply {
        foreground = c; font = Font("JetBrains Mono", if (b) Font.BOLD else Font.PLAIN, sz)
    }
    private fun styledBtn(text: String, color: Color) = lbl(text, color, 11, false).apply {
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(color, 1, true), EmptyBorder(4, 12, 4, 12))
    }
    private fun click(action: () -> Unit) = object : java.awt.event.MouseAdapter() {
        override fun mouseClicked(e: java.awt.event.MouseEvent) { action() }
    }
}
