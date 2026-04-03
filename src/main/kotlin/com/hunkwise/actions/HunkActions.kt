package com.hunkwise.actions

import com.hunkwise.diff.DiffEngine
import com.hunkwise.state.FileState
import com.hunkwise.state.FileStatus
import com.hunkwise.state.StateManager
import com.hunkwise.util.HunkwiseLogger
import com.hunkwise.watcher.FileWatcher
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.io.File

object HunkActions {

    private val log = HunkwiseLogger.LOG

    fun acceptHunk(project: Project, stateManager: StateManager, filePath: String, hunkId: String) {
        val fileState = stateManager.getFile(filePath) ?: return

        val vFile = LocalFileSystem.getInstance().findFileByPath(filePath) ?: return
        val document = FileDocumentManager.getInstance().getDocument(vFile) ?: return

        val currentText = document.text
        val baselineStr = fileState.baseline ?: ""

        val hunks = DiffEngine.computeHunks(fileState.baseline, currentText)
        val hunk = hunks.find { it.id == hunkId } ?: return

        val originalNewStart = hunk.newStart

        val currentLines = currentText.split("\n")
        val baselineLines = baselineStr.split("\n")
        val newBaseline = (
            baselineLines.take(hunk.oldStart - 1) +
            currentLines.subList(hunk.newStart - 1, hunk.newStart - 1 + hunk.newLines) +
            baselineLines.drop(hunk.oldStart - 1 + hunk.oldLines)
        ).joinToString("\n")

        val remainingHunks = DiffEngine.computeHunks(newBaseline, currentText)
        if (remainingHunks.isEmpty()) {
            stateManager.exitReviewing(filePath, currentText)
        } else {
            stateManager.setFile(filePath, FileState(FileStatus.REVIEWING, newBaseline))
            revealNextHunk(project, filePath, remainingHunks, originalNewStart)
        }
        stateManager.fireStateChanged()
    }

    fun discardHunk(project: Project, stateManager: StateManager, filePath: String, hunkId: String) {
        val fileState = stateManager.getFile(filePath) ?: return
        val fileWatcher = project.service<FileWatcher>()

        val vFile = LocalFileSystem.getInstance().findFileByPath(filePath) ?: return
        val document = FileDocumentManager.getInstance().getDocument(vFile) ?: return

        val hunks = DiffEngine.computeHunks(fileState.baseline, document.text)
        val hunk = hunks.find { it.id == hunkId } ?: return

        val originalNewStart = hunk.newStart
        val baselineStr = fileState.baseline ?: ""
        val baselineLines = baselineStr.split("\n")
        val originalLines = baselineLines.subList(hunk.oldStart - 1, hunk.oldStart - 1 + hunk.oldLines)

        fileWatcher.markSelfEdit(filePath)
        try {
            WriteCommandAction.runWriteCommandAction(project) {
                val startOffset = document.getLineStartOffset(hunk.newStart - 1)
                val endOffset = if (hunk.newLines == 0) {
                    startOffset
                } else {
                    val lastNewLine = hunk.newStart - 1 + hunk.newLines - 1
                    if (lastNewLine < document.lineCount - 1) {
                        document.getLineStartOffset(lastNewLine + 1)
                    } else {
                        document.getLineEndOffset(lastNewLine)
                    }
                }

                val replacement = if (originalLines.isNotEmpty()) {
                    originalLines.joinToString("\n") + "\n"
                } else {
                    ""
                }
                document.replaceString(startOffset, endOffset, replacement)
            }

            FileDocumentManager.getInstance().saveDocument(document)

            val currentText = document.text
            val remainingHunks = DiffEngine.computeHunks(fileState.baseline, currentText)
            if (remainingHunks.isEmpty()) {
                if (fileState.baseline == null && File(filePath).exists()) {
                    // New file fully discarded — delete from disk
                    try {
                        File(filePath).delete()
                    } catch (e: Exception) {
                        log.warn("discardHunk: delete failed: $e")
                    }
                }
                stateManager.exitReviewing(filePath)
            } else {
                revealNextHunk(project, filePath, remainingHunks, originalNewStart)
            }
            stateManager.fireStateChanged()
        } finally {
            fileWatcher.clearSelfEdit(filePath)
        }
    }

    fun acceptFile(project: Project, stateManager: StateManager, filePath: String) {
        if (stateManager.getFile(filePath) == null) return
        val file = File(filePath)
        if (!file.exists()) {
            stateManager.removeFile(filePath)
        } else {
            val content = file.readText(Charsets.UTF_8)
            stateManager.exitReviewing(filePath, content)
        }
        stateManager.fireStateChanged()
    }

    fun discardFile(project: Project, stateManager: StateManager, filePath: String) {
        val fileState = stateManager.getFile(filePath) ?: return
        val fileWatcher = project.service<FileWatcher>()

        fileWatcher.markSelfEdit(filePath)
        try {
            if (fileState.baseline == null) {
                // New file — delete it
                val file = File(filePath)
                if (file.exists()) file.delete()
            } else if (!File(filePath).exists()) {
                // Deleted file — restore from baseline
                val file = File(filePath)
                file.parentFile.mkdirs()
                file.writeText(fileState.baseline, Charsets.UTF_8)
            } else {
                // Replace content with baseline
                val vFile = LocalFileSystem.getInstance().findFileByPath(filePath)
                if (vFile != null) {
                    val document = FileDocumentManager.getInstance().getDocument(vFile)
                    if (document != null) {
                        WriteCommandAction.runWriteCommandAction(project) {
                            document.setText(fileState.baseline ?: "")
                        }
                        FileDocumentManager.getInstance().saveDocument(document)
                    }
                }
            }
        } finally {
            fileWatcher.clearSelfEdit(filePath)
        }

        if (fileState.baseline == null) {
            stateManager.removeFile(filePath)
        } else {
            stateManager.exitReviewing(filePath)
        }
        stateManager.fireStateChanged()
    }

    fun acceptAll(project: Project, stateManager: StateManager) {
        for (filePath in stateManager.getAllFiles().keys.toList()) {
            acceptFile(project, stateManager, filePath)
        }
    }

    fun discardAll(project: Project, stateManager: StateManager) {
        for (filePath in stateManager.getAllFiles().keys.toList()) {
            try {
                discardFile(project, stateManager, filePath)
            } catch (e: Exception) {
                log.warn("discardAll: failed for $filePath: $e")
            }
        }
    }

    private fun revealNextHunk(
        project: Project,
        filePath: String,
        remainingHunks: List<com.hunkwise.diff.ParsedHunk>,
        originalNewStart: Int
    ) {
        val fem = FileEditorManager.getInstance(project)
        val vFile = LocalFileSystem.getInstance().findFileByPath(filePath) ?: return
        for (editor in fem.getEditors(vFile)) {
            if (editor is TextEditor) {
                val next = remainingHunks.firstOrNull { it.newStart >= originalNewStart }
                    ?: remainingHunks.firstOrNull() ?: return
                val offset = editor.editor.document.getLineStartOffset(maxOf(0, next.newStart - 1))
                editor.editor.caretModel.moveToOffset(offset)
                editor.editor.scrollingModel.scrollToCaret(com.intellij.openapi.editor.ScrollType.CENTER)
                break
            }
        }
    }
}
