package com.hunkwise.toolwindow

import com.hunkwise.actions.HunkActions
import com.hunkwise.actions.buildShouldIgnore
import com.hunkwise.diff.DiffEngine
import com.hunkwise.diff.ParsedHunk
import com.hunkwise.state.FileStatus
import com.hunkwise.state.StateChangeListener
import com.hunkwise.state.StateManager
import com.hunkwise.util.PathNormalize
import com.hunkwise.watcher.FileWatcher
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

/**
 * Tool window panel that shows all files with pending hunks,
 * with per-file and per-hunk Accept/Discard actions.
 */
class ReviewPanel(private val project: Project) : JBPanel<ReviewPanel>(BorderLayout()) {

    private val stateManager = project.service<StateManager>()
    private val contentPanel = JBPanel<JBPanel<*>>().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
    }
    private val headerLabel = JBLabel("Hunkwise")
    private val statsLabel = JBLabel("")
    private val enableButton = JButton("Enable Hunkwise")
    private val disableButton = JButton("Disable")
    private val acceptAllButton = JButton("\u2713 Accept All")
    private val discardAllButton = JButton("\u21BA Discard All")

    init {
        setupUI()
        refresh()

        // Listen for state changes
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
        // Header toolbar
        val toolbar = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 8, 4))
        toolbar.border = EmptyBorder(4, 8, 4, 8)

        enableButton.addActionListener { onEnable() }
        disableButton.addActionListener { onDisable() }
        acceptAllButton.addActionListener { HunkActions.acceptAll(project, stateManager) }
        discardAllButton.addActionListener { HunkActions.discardAll(project, stateManager) }

        toolbar.add(headerLabel)
        toolbar.add(statsLabel)
        toolbar.add(acceptAllButton)
        toolbar.add(discardAllButton)
        toolbar.add(enableButton)
        toolbar.add(disableButton)

        add(toolbar, BorderLayout.NORTH)
        add(JBScrollPane(contentPanel), BorderLayout.CENTER)
    }

    private fun refresh() {
        contentPanel.removeAll()

        val enabled = stateManager.enabled
        enableButton.isVisible = !enabled
        disableButton.isVisible = enabled
        acceptAllButton.isVisible = enabled
        discardAllButton.isVisible = enabled

        if (!enabled) {
            statsLabel.text = " — Disabled"
            contentPanel.add(createCenteredLabel("Hunkwise is disabled. Click 'Enable Hunkwise' to start tracking."))
            contentPanel.revalidate()
            contentPanel.repaint()
            return
        }

        val files = stateManager.getReviewingFiles()

        if (files.isEmpty()) {
            statsLabel.text = " — No pending changes"
            acceptAllButton.isEnabled = false
            discardAllButton.isEnabled = false
            contentPanel.add(createCenteredLabel("No pending changes. External modifications will appear here."))
            contentPanel.revalidate()
            contentPanel.repaint()
            return
        }

        var totalAdded = 0
        var totalRemoved = 0

        for ((filePath, fileState) in files) {
            val currentContent = try {
                val file = File(filePath)
                if (file.exists()) file.readText(Charsets.UTF_8) else ""
            } catch (_: Exception) {
                ""
            }
            val hunks = DiffEngine.computeHunks(fileState.baseline, currentContent)
            val added = hunks.sumOf { it.newLines }
            val removed = hunks.sumOf { it.oldLines }
            totalAdded += added
            totalRemoved += removed

            contentPanel.add(createFilePanel(filePath, fileState.isNew, hunks, added, removed))
        }

        statsLabel.text = " — ${files.size} file(s)  +$totalAdded -$totalRemoved"
        acceptAllButton.isEnabled = true
        discardAllButton.isEnabled = true

        contentPanel.add(Box.createVerticalGlue())
        contentPanel.revalidate()
        contentPanel.repaint()
    }

    private fun createFilePanel(
        filePath: String,
        isNew: Boolean,
        hunks: List<ParsedHunk>,
        added: Int,
        removed: Int
    ): JPanel {
        val panel = JBPanel<JBPanel<*>>(BorderLayout())
        panel.border = EmptyBorder(4, 8, 4, 8)
        panel.maximumSize = Dimension(Int.MAX_VALUE, panel.preferredSize.height + 200)

        // File header
        val fileName = File(filePath).name
        val dirName = File(filePath).parent?.let {
            stateManager.workspaceRoot?.let { root ->
                File(it).toRelativeString(File(root))
            } ?: it
        } ?: ""

        val headerPanel = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 4, 2))

        val nameLabel = JBLabel(fileName).apply {
            font = font.deriveFont(Font.BOLD)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }
        nameLabel.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent?) {
                openFile(filePath)
            }
        })

        headerPanel.add(nameLabel)
        if (dirName.isNotEmpty()) {
            headerPanel.add(JBLabel(dirName).apply {
                foreground = JBColor.GRAY
                font = font.deriveFont(Font.PLAIN, 11f)
            })
        }
        if (isNew) {
            headerPanel.add(JBLabel("NEW").apply {
                foreground = Color(0x2a, 0x7d, 0x3a)
                font = font.deriveFont(Font.BOLD, 10f)
            })
        }
        headerPanel.add(JBLabel("+$added -$removed").apply {
            foreground = JBColor.GRAY
            font = font.deriveFont(Font.PLAIN, 11f)
        })

        // File action buttons
        val fileAccept = JButton("\u2713").apply {
            toolTipText = "Accept all hunks in this file"
            addActionListener { HunkActions.acceptFile(project, stateManager, filePath) }
            margin = Insets(2, 6, 2, 6)
        }
        val fileDiscard = JButton("\u21BA").apply {
            toolTipText = "Discard all hunks in this file"
            addActionListener { HunkActions.discardFile(project, stateManager, filePath) }
            margin = Insets(2, 6, 2, 6)
        }
        val fileButtonPanel = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.RIGHT, 2, 0))
        fileButtonPanel.add(fileAccept)
        fileButtonPanel.add(fileDiscard)

        val headerRow = JBPanel<JBPanel<*>>(BorderLayout())
        headerRow.add(headerPanel, BorderLayout.CENTER)
        headerRow.add(fileButtonPanel, BorderLayout.EAST)
        panel.add(headerRow, BorderLayout.NORTH)

        // Hunks list
        if (hunks.isNotEmpty()) {
            val hunksPanel = JBPanel<JBPanel<*>>().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                border = EmptyBorder(0, 16, 0, 0)
            }
            for (hunk in hunks) {
                hunksPanel.add(createHunkRow(filePath, hunk))
            }
            panel.add(hunksPanel, BorderLayout.CENTER)
        }

        // Separator
        panel.add(JSeparator(), BorderLayout.SOUTH)

        return panel
    }

    private fun createHunkRow(filePath: String, hunk: ParsedHunk): JPanel {
        val row = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 4, 1))
        row.maximumSize = Dimension(Int.MAX_VALUE, 28)

        val locationLabel = JBLabel("@line ${hunk.newStart}  +${hunk.newLines} -${hunk.oldLines}").apply {
            foreground = JBColor.GRAY
            font = font.deriveFont(Font.PLAIN, 11f)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }
        locationLabel.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent?) {
                jumpToHunk(filePath, hunk)
            }
        })

        val acceptBtn = JButton("\u2713").apply {
            toolTipText = "Accept this hunk"
            addActionListener { HunkActions.acceptHunk(project, stateManager, filePath, hunk.id) }
            margin = Insets(1, 4, 1, 4)
            font = font.deriveFont(10f)
        }
        val discardBtn = JButton("\u21BA").apply {
            toolTipText = "Discard this hunk"
            addActionListener { HunkActions.discardHunk(project, stateManager, filePath, hunk.id) }
            margin = Insets(1, 4, 1, 4)
            font = font.deriveFont(10f)
        }

        row.add(locationLabel)
        row.add(acceptBtn)
        row.add(discardBtn)
        return row
    }

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
                    editor.editor.scrollingModel.scrollToCaret(
                        com.intellij.openapi.editor.ScrollType.CENTER
                    )
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
        stateManager.snapshotWorkspace(shouldIgnore)
        stateManager.fireStateChanged()
    }

    private fun onDisable() {
        stateManager.setEnabled(false)
        stateManager.fireStateChanged()
    }

    private fun createCenteredLabel(text: String): JPanel {
        val panel = JBPanel<JBPanel<*>>(GridBagLayout())
        panel.add(JBLabel(text).apply {
            foreground = JBColor.GRAY
        })
        return panel
    }
}
