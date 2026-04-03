package com.hunkwise.git

import com.hunkwise.state.HunkwiseSettings
import com.hunkwise.util.HunkwiseLogger
import com.hunkwise.util.PathNormalize
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.ProcessOutput
import com.intellij.execution.util.ExecUtil
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Path

/**
 * Manages persistent baseline storage via a private git repo at .idea/hunkwise/git/.
 *
 * The repo uses the workspace root as its work tree but keeps all git metadata
 * inside the hunkwise directory, so it never touches the project's own .git.
 *
 * GIT_DIR       = <hunkwiseDir>/git
 * GIT_WORK_TREE = <workspaceRoot>
 *
 * Each tracked file has exactly one entry in the single HEAD commit.
 * Every mutation rewrites that commit via --amend so the repo always has
 * at most one commit and stays compact.
 */
class HunkwiseGit(
    val hunkwiseDir: String,
    private val workspaceRoot: String
) {
    private val gitDir = File(hunkwiseDir, "git").absolutePath
    private var gitInitialized = false
    private var destroyed = false
    private val log = HunkwiseLogger.LOG

    // ── env / low-level git ──────────────────────────────────────────────

    private fun buildGitCommand(vararg args: String): GeneralCommandLine {
        return GeneralCommandLine("git", "-c", "core.quotepath=false", *args)
            .withWorkDirectory(workspaceRoot)
            .withEnvironment("GIT_DIR", gitDir)
            .withEnvironment("GIT_WORK_TREE", workspaceRoot)
            .withEnvironment("GIT_TERMINAL_PROMPT", "0")
            .withCharset(StandardCharsets.UTF_8)
    }

    private fun git(vararg args: String): String {
        val cmd = buildGitCommand(*args)
        val output: ProcessOutput = ExecUtil.execAndGetOutput(cmd, 30_000)
        if (output.exitCode != 0) {
            val stderr = output.stderr.trim()
            throw RuntimeException("git ${args.joinToString(" ")} failed (exit ${output.exitCode}): $stderr")
        }
        return output.stdout
    }

    /**
     * Hash content via stdin using git hash-object -w --stdin.
     */
    private fun hashObject(content: String): String {
        val cmd = buildGitCommand("hash-object", "-w", "--stdin")
        val process = cmd.createProcess()
        process.outputStream.bufferedWriter(StandardCharsets.UTF_8).use { it.write(content) }
        val hash = process.inputStream.bufferedReader(StandardCharsets.UTF_8).readText().trim()
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw RuntimeException("git hash-object failed (exit $exitCode)")
        }
        return hash
    }

    // ── settings.json ────────────────────────────────────────────────────

    fun loadSettings(): HunkwiseSettings = HunkwiseSettings.load(hunkwiseDir)

    fun saveSettings(settings: HunkwiseSettings) = HunkwiseSettings.save(hunkwiseDir, settings)

    // ── git init ─────────────────────────────────────────────────────────

    @Synchronized
    fun initGit() {
        if (destroyed || gitInitialized) return

        val gitDirFile = File(gitDir)
        val headFile = File(gitDir, "HEAD")

        // If git dir exists but HEAD is missing, repo is corrupted — reinit
        if (gitDirFile.exists() && !headFile.exists()) {
            log.warn("initGit: corrupted git dir detected (HEAD missing), re-initializing")
            gitDirFile.deleteRecursively()
        }

        if (!gitDirFile.exists() || !headFile.exists()) {
            gitDirFile.mkdirs()
            git("init")
            git("config", "user.email", "hunkwise@localhost")
            git("config", "user.name", "hunkwise")
        }

        gitInitialized = true
    }

    private fun hasHead(): Boolean {
        return try {
            git("rev-parse", "HEAD")
            true
        } catch (_: Exception) {
            false
        }
    }

    // ── snapshot / remove ────────────────────────────────────────────────

    fun snapshot(filePath: String, content: String) {
        initGit()
        val rel = relativize(filePath)
        try {
            val hash = hashObject(content)
            git("update-index", "--add", "--cacheinfo", "100644,$hash,$rel")
            commit()
        } catch (e: Exception) {
            log.warn("snapshot failed for $rel: $e")
            throw e
        }
    }

    fun renameFile(oldFilePath: String, newFilePath: String) {
        initGit()
        val oldRel = relativize(oldFilePath)
        val newRel = relativize(newFilePath)
        try {
            val lsOut = git("ls-files", "--stage", "--", oldRel)
            val lines = lsOut.trim().split("\n").filter { it.isNotBlank() }
            if (lines.isEmpty()) return

            data class Entry(val mode: String, val hash: String, val entryRel: String)

            val entries = lines.mapNotNull { line ->
                val regex = Regex("""^(\d+) ([0-9a-f]+) \d+\t(.+)$""")
                regex.matchEntire(line)?.let { m ->
                    Entry(m.groupValues[1], m.groupValues[2], PathNormalize.normalize(m.groupValues[3]))
                }
            }
            if (entries.isEmpty()) return

            // Remove old entries (chunked)
            entries.map { it.entryRel }.chunked(200).forEach { chunk ->
                git("update-index", "--force-remove", "--", *chunk.toTypedArray())
            }

            // Add entries with new paths
            entries.chunked(200).forEach { chunk ->
                val cacheArgs = chunk.flatMap { (mode, hash, entryRel) ->
                    val suffix = if (entryRel == oldRel) "" else entryRel.substring(oldRel.length)
                    val renamed = newRel + suffix
                    listOf("--add", "--cacheinfo", "$mode,$hash,$renamed")
                }
                git("update-index", *cacheArgs.toTypedArray())
            }
            commit()
        } catch (e: Exception) {
            log.warn("renameFile failed ($oldRel → $newRel): $e")
            throw e
        }
    }

    fun removeFile(filePath: String) {
        initGit()
        val rel = relativize(filePath)
        try {
            val lsOut = git("ls-files", "--stage", "--", rel)
            if (lsOut.isBlank()) return
            git("update-index", "--force-remove", "--", rel)
            commit()
        } catch (e: Exception) {
            log.warn("removeFile failed for $rel: $e")
            throw e
        }
    }

    fun snapshotBatch(files: List<Pair<String, String>>) {
        if (files.isEmpty()) return
        initGit()
        try {
            val entries = files.map { (filePath, content) ->
                val rel = relativize(filePath)
                val hash = hashObject(content)
                rel to hash
            }

            entries.chunked(100).forEach { chunk ->
                val cacheArgs = chunk.flatMap { (rel, hash) ->
                    listOf("--add", "--cacheinfo", "100644,$hash,$rel")
                }
                git("update-index", *cacheArgs.toTypedArray())
            }
            commit()
        } catch (e: Exception) {
            log.warn("snapshotBatch failed (${files.size} files): $e")
        }
    }

    fun removeFileBatch(filePaths: List<String>) {
        if (filePaths.isEmpty()) return
        initGit()
        try {
            val rels = filePaths.map { relativize(it) }
            rels.chunked(200).forEach { chunk ->
                git("update-index", "--force-remove", "--", *chunk.toTypedArray())
            }
            commit()
        } catch (e: Exception) {
            log.warn("removeFileBatch failed (${filePaths.size} files): $e")
        }
    }

    private fun commit() {
        if (hasHead()) {
            git("commit", "--amend", "--no-edit", "--allow-empty")
        } else {
            git("commit", "-m", "hunkwise baselines")
        }
    }

    /**
     * Return the baseline content for a file from the git index.
     */
    fun getBaseline(filePath: String): String? {
        initGit()
        val rel = relativize(filePath)
        return try {
            git("show", ":$rel")
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Return absolute paths of all files currently tracked in HEAD.
     */
    fun listTrackedFiles(): List<String> {
        initGit()
        return try {
            val out = git("ls-tree", "HEAD", "--name-only", "-r")
            out.split("\n")
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .map { rel -> PathNormalize.normalize(File(workspaceRoot, rel).absolutePath) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ── destroy ──────────────────────────────────────────────────────────

    fun destroyGit() {
        gitInitialized = false
        destroyed = true
        val gitDirFile = File(gitDir)
        if (gitDirFile.exists()) {
            try {
                gitDirFile.deleteRecursively()
            } catch (e: Exception) {
                log.warn("destroyGit failed: $e")
            }
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun relativize(filePath: String): String {
        val rel = Path.of(workspaceRoot).relativize(Path.of(filePath)).toString()
        return PathNormalize.normalize(rel)
    }
}
