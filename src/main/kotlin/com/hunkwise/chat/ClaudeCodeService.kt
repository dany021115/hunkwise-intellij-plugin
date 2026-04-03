package com.hunkwise.chat

import com.google.gson.JsonParser
import com.hunkwise.util.HunkwiseLogger
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Integrates with Claude Code CLI (`claude -p`) for AI chat.
 * Runs `claude` as a subprocess with stdin closed to avoid the stdin warning.
 */
@Service(Service.Level.PROJECT)
class ClaudeCodeService(private val project: Project) {

    private val log = HunkwiseLogger.LOG
    private var sessionId: String? = null
    private var claudePath: String? = null

    fun findClaudePath(): String? {
        if (claudePath != null) return claudePath

        val candidates = listOf(
            "${System.getProperty("user.home")}/.local/bin/claude",
            "/usr/local/bin/claude",
            "/opt/homebrew/bin/claude"
        )

        for (path in candidates) {
            if (java.io.File(path).exists() && java.io.File(path).canExecute()) {
                claudePath = path
                return path
            }
        }

        try {
            val process = ProcessBuilder("which", "claude")
                .redirectErrorStream(true).start()
            val result = process.inputStream.bufferedReader().readText().trim()
            if (process.waitFor() == 0 && result.isNotEmpty()) {
                claudePath = result
                return result
            }
        } catch (_: Exception) {}

        return null
    }

    fun isAvailable(): Boolean = findClaudePath() != null

    /**
     * Send a message to Claude Code and get the response.
     * Runs synchronously — call from a background thread.
     */
    fun sendMessage(message: String, workingDir: String? = null): ChatResponse {
        val claude = findClaudePath()
            ?: return ChatResponse(null, "Claude Code not found. Install from https://claude.ai/code", true)

        try {
            val cmd = mutableListOf(claude, "-p", "--output-format", "json")

            sessionId?.let {
                cmd.add("--session-id")
                cmd.add(it)
            }

            cmd.add(message)

            val pb = ProcessBuilder(cmd)
            // Do NOT redirectErrorStream — keep stderr separate so warnings don't corrupt JSON
            pb.redirectInput(ProcessBuilder.Redirect.from(java.io.File("/dev/null"))) // avoid stdin warning

            if (workingDir != null) {
                pb.directory(java.io.File(workingDir))
            } else {
                project.basePath?.let { pb.directory(java.io.File(it)) }
            }

            val process = pb.start()

            // Read stdout (JSON response)
            val stdout = StringBuilder()
            val stdoutReader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?
            while (stdoutReader.readLine().also { line = it } != null) {
                stdout.appendLine(line)
            }

            // Read stderr (warnings, can ignore)
            val stderr = StringBuilder()
            val stderrReader = BufferedReader(InputStreamReader(process.errorStream))
            while (stderrReader.readLine().also { line = it } != null) {
                stderr.appendLine(line)
            }

            val exitCode = process.waitFor()
            val rawStdout = stdout.toString().trim()

            if (exitCode != 0 && rawStdout.isEmpty()) {
                val errMsg = stderr.toString().trim()
                return ChatResponse(null, "Claude Code error (exit $exitCode):\n$errMsg", true)
            }

            // Parse JSON — find the JSON object in stdout (skip any non-JSON lines)
            val jsonStart = rawStdout.indexOf('{')
            if (jsonStart < 0) {
                return ChatResponse(rawStdout.ifEmpty { null }, "No JSON response", rawStdout.isEmpty())
            }

            val jsonStr = rawStdout.substring(jsonStart)

            return try {
                val json = JsonParser.parseString(jsonStr).asJsonObject
                val result = json.get("result")?.asString
                val newSessionId = json.get("session_id")?.asString
                val isError = json.get("is_error")?.asBoolean ?: false

                if (newSessionId != null) {
                    sessionId = newSessionId
                }

                if (isError) {
                    ChatResponse(null, result ?: "Unknown error", true)
                } else {
                    ChatResponse(result ?: "", null, false)
                }
            } catch (e: Exception) {
                log.warn("JSON parse error: $e, raw: $jsonStr")
                ChatResponse(rawStdout, null, false)
            }

        } catch (e: Exception) {
            log.warn("Claude Code error: $e")
            return ChatResponse(null, "Error: ${e.message}", true)
        }
    }

    fun resetSession() {
        sessionId = null
    }

    data class ChatResponse(
        val text: String?,
        val error: String?,
        val isError: Boolean
    )
}
