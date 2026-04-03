package com.hunkwise.git

import com.hunkwise.util.HunkwiseLogger
import com.hunkwise.util.PathNormalize
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.io.File
import java.nio.charset.StandardCharsets

@Service(Service.Level.PROJECT)
class ProjectGitService(private val project: Project) {

    private val log = HunkwiseLogger.LOG

    data class ChangedFile(
        val filePath: String,
        val status: ChangeStatus,
        val addedLines: Int,
        val removedLines: Int
    )

    enum class ChangeStatus {
        MODIFIED, ADDED, DELETED, RENAMED, UNTRACKED
    }

    data class FileDiff(
        val filePath: String,
        val status: ChangeStatus,
        val hunks: List<DiffHunk>
    )

    data class DiffHunk(
        val oldStart: Int,
        val oldLines: Int,
        val newStart: Int,
        val newLines: Int,
        val addedContent: List<String>,
        val removedContent: List<String>
    ) {
        val id: String get() = "$newStart:$newLines:$oldStart:$oldLines"
    }

    private fun git(vararg args: String): String? {
        val root = project.basePath ?: return null
        val cmd = GeneralCommandLine("git", *args)
            .withWorkDirectory(root)
            .withCharset(StandardCharsets.UTF_8)
        return try {
            val output = ExecUtil.execAndGetOutput(cmd, 15_000)
            if (output.exitCode == 0) output.stdout else null
        } catch (e: Exception) {
            log.warn("git ${args.joinToString(" ")} failed: $e")
            null
        }
    }

    fun isGitRepo(): Boolean = git("rev-parse", "--git-dir") != null

    fun getChangedFiles(): List<ChangedFile> {
        val root = project.basePath ?: return emptyList()
        val result = mutableListOf<ChangedFile>()

        val diffOutput = git("diff", "HEAD", "--numstat", "--diff-filter=AMDRT") ?: ""
        for (line in diffOutput.trim().split("\n").filter { it.isNotBlank() }) {
            val parts = line.split("\t")
            if (parts.size < 3) continue
            val added = parts[0].toIntOrNull() ?: 0
            val removed = parts[1].toIntOrNull() ?: 0
            val filePath = PathNormalize.normalize(File(root, parts[2]).absolutePath)
            val status = when {
                !File(filePath).exists() -> ChangeStatus.DELETED
                removed == 0 -> ChangeStatus.ADDED
                else -> ChangeStatus.MODIFIED
            }
            result.add(ChangedFile(filePath, status, added, removed))
        }

        val untrackedOutput = git("ls-files", "--others", "--exclude-standard") ?: ""
        for (line in untrackedOutput.trim().split("\n").filter { it.isNotBlank() }) {
            val filePath = PathNormalize.normalize(File(root, line.trim()).absolutePath)
            if (result.any { it.filePath == filePath }) continue
            val lineCount = try { File(filePath).readLines().size } catch (_: Exception) { 0 }
            result.add(ChangedFile(filePath, ChangeStatus.UNTRACKED, lineCount, 0))
        }

        return result
    }

    fun getFileDiff(filePath: String): FileDiff? {
        val root = project.basePath ?: return null
        val relPath = File(filePath).toRelativeString(File(root))

        val statusOut = git("status", "--porcelain", "--", relPath) ?: return null
        val isUntracked = statusOut.trim().startsWith("??")

        if (isUntracked) {
            val content = try { File(filePath).readLines() } catch (_: Exception) { return null }
            val hunk = DiffHunk(0, 0, 1, content.size, content, emptyList())
            return FileDiff(filePath, ChangeStatus.UNTRACKED, listOf(hunk))
        }

        val diffOutput = git("diff", "HEAD", "--unified=0", "--", relPath) ?: return null
        val hunks = parseDiffHunks(diffOutput)
        val status = if (File(filePath).exists()) {
            if (hunks.all { it.oldLines == 0 }) ChangeStatus.ADDED else ChangeStatus.MODIFIED
        } else ChangeStatus.DELETED

        return FileDiff(filePath, status, hunks)
    }

    /** Get HEAD version content for a file */
    fun getHeadContent(filePath: String): String? {
        val root = project.basePath ?: return null
        val relPath = File(filePath).toRelativeString(File(root))
        return git("show", "HEAD:$relPath")
    }

    // ══════════════════════════════════════════════════════════════════
    // ACCEPT / DISCARD operations
    // ══════════════════════════════════════════════════════════════════

    /** Accept file = stage it (git add) */
    fun acceptFile(filePath: String): Boolean {
        val root = project.basePath ?: return false
        val relPath = File(filePath).toRelativeString(File(root))
        return git("add", "--", relPath) != null
    }

    /** Discard file = restore from HEAD (git checkout HEAD -- file) */
    fun discardFile(filePath: String): Boolean {
        val root = project.basePath ?: return false
        val relPath = File(filePath).toRelativeString(File(root))
        val statusOut = git("status", "--porcelain", "--", relPath) ?: return false

        return if (statusOut.trim().startsWith("??")) {
            // Untracked file — delete it
            try { File(filePath).delete(); true } catch (_: Exception) { false }
        } else {
            // Tracked file — restore from HEAD
            git("checkout", "HEAD", "--", relPath) != null
        }
    }

    /** Accept all = stage all changes */
    fun acceptAll(): Boolean = git("add", "-A") != null

    /** Discard all = restore all from HEAD + clean untracked */
    fun discardAll(): Boolean {
        git("checkout", "HEAD", "--", ".")
        git("clean", "-fd")
        return true
    }

    /** Discard a single hunk by generating a reverse patch and applying it */
    fun discardHunk(filePath: String, hunk: DiffHunk): Boolean {
        val root = project.basePath ?: return false
        val relPath = File(filePath).toRelativeString(File(root))

        // Build a reverse patch: swap added/removed
        val patch = buildString {
            appendLine("--- a/$relPath")
            appendLine("+++ b/$relPath")
            appendLine("@@ -${hunk.newStart},${hunk.newLines} +${hunk.oldStart},${hunk.oldLines} @@")
            for (line in hunk.addedContent) appendLine("-$line")
            for (line in hunk.removedContent) appendLine("+$line")
        }

        return applyPatch(root, patch)
    }

    /** Accept a single hunk = stage just that hunk */
    fun acceptHunk(filePath: String, hunk: DiffHunk): Boolean {
        val root = project.basePath ?: return false
        val relPath = File(filePath).toRelativeString(File(root))

        // Build forward patch for staging
        val patch = buildString {
            appendLine("--- a/$relPath")
            appendLine("+++ b/$relPath")
            appendLine("@@ -${hunk.oldStart},${hunk.oldLines} +${hunk.newStart},${hunk.newLines} @@")
            for (line in hunk.removedContent) appendLine("-$line")
            for (line in hunk.addedContent) appendLine("+$line")
        }

        return applyPatchToIndex(root, patch)
    }

    private fun applyPatch(root: String, patch: String): Boolean {
        return try {
            val cmd = GeneralCommandLine("git", "apply", "--whitespace=nowarn", "-")
                .withWorkDirectory(root)
                .withCharset(StandardCharsets.UTF_8)
            val process = cmd.createProcess()
            process.outputStream.bufferedWriter().use { it.write(patch) }
            process.waitFor() == 0
        } catch (e: Exception) {
            log.warn("applyPatch failed: $e")
            false
        }
    }

    private fun applyPatchToIndex(root: String, patch: String): Boolean {
        return try {
            val cmd = GeneralCommandLine("git", "apply", "--cached", "--whitespace=nowarn", "-")
                .withWorkDirectory(root)
                .withCharset(StandardCharsets.UTF_8)
            val process = cmd.createProcess()
            process.outputStream.bufferedWriter().use { it.write(patch) }
            process.waitFor() == 0
        } catch (e: Exception) {
            log.warn("applyPatchToIndex failed: $e")
            false
        }
    }

    private fun parseDiffHunks(diffOutput: String): List<DiffHunk> {
        val hunks = mutableListOf<DiffHunk>()
        val lines = diffOutput.split("\n")
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("@@")) {
                val match = Regex("""@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@""").find(line)
                if (match != null) {
                    val oldStart = match.groupValues[1].toInt()
                    val oldLines = match.groupValues[2].toIntOrNull() ?: 1
                    val newStart = match.groupValues[3].toInt()
                    val newLines = match.groupValues[4].toIntOrNull() ?: 1
                    val added = mutableListOf<String>()
                    val removed = mutableListOf<String>()
                    var j = i + 1
                    while (j < lines.size && !lines[j].startsWith("@@") && !lines[j].startsWith("diff ")) {
                        when {
                            lines[j].startsWith("+") -> added.add(lines[j].substring(1))
                            lines[j].startsWith("-") -> removed.add(lines[j].substring(1))
                        }
                        j++
                    }
                    hunks.add(DiffHunk(oldStart, oldLines, newStart, newLines, added, removed))
                    i = j; continue
                }
            }
            i++
        }
        return hunks
    }
}
