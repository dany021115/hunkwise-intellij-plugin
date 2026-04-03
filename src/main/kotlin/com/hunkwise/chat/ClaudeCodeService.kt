package com.hunkwise.chat

import com.google.gson.JsonParser
import com.hunkwise.util.HunkwiseLogger
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.io.BufferedReader
import java.io.InputStreamReader

@Service(Service.Level.PROJECT)
class ClaudeCodeService(private val project: Project) {

    private val log = HunkwiseLogger.LOG
    private var claudePath: String? = null
    private var shouldContinue = false
    @Volatile private var currentProcess: Process? = null
    @Volatile var autoAcceptCommands = false

    fun findClaudePath(): String? {
        if (claudePath != null) return claudePath
        val candidates = listOf(
            "${System.getProperty("user.home")}/.local/bin/claude",
            "/usr/local/bin/claude",
            "/opt/homebrew/bin/claude"
        )
        for (path in candidates) {
            if (java.io.File(path).exists() && java.io.File(path).canExecute()) {
                claudePath = path; return path
            }
        }
        try {
            val p = ProcessBuilder("which", "claude").redirectErrorStream(true).start()
            val r = p.inputStream.bufferedReader().readText().trim()
            if (p.waitFor() == 0 && r.isNotEmpty()) { claudePath = r; return r }
        } catch (_: Exception) {}
        return null
    }

    fun isAvailable(): Boolean = findClaudePath() != null

    /**
     * Build a system prompt with project context.
     */
    fun buildSystemPrompt(): String {
        val root = project.basePath ?: return ""
        val branch = try {
            val p = ProcessBuilder("git", "rev-parse", "--abbrev-ref", "HEAD")
                .directory(java.io.File(root)).redirectErrorStream(true).start()
            p.inputStream.bufferedReader().readText().trim()
        } catch (_: Exception) { "unknown" }

        val langs = java.io.File(root).listFiles()?.mapNotNull {
            when (it.extension) {
                "kt", "kts" -> "Kotlin"
                "java" -> "Java"
                "go" -> "Go"
                "py" -> "Python"
                "ts", "tsx" -> "TypeScript"
                "js", "jsx" -> "JavaScript"
                "rs" -> "Rust"
                "swift" -> "Swift"
                "dart" -> "Dart"
                else -> null
            }
        }?.distinct()?.joinToString(", ") ?: ""

        return """You are a code review assistant in an IntelliJ IDEA project.
Project: ${java.io.File(root).name}
Branch: $branch
Languages: $langs
Root: $root

RULES:
- Always respond in the same language the user writes in. Match their language automatically.
- You can see uncommitted git changes. Help the user understand, review, and decide on changes.
- When reviewing hunks, explain what changed and whether it's safe to accept.
- Be concise and direct. Avoid excessive markdown formatting.
- Remember context from previous messages in this conversation."""
    }

    /**
     * Send message with streaming support.
     * onToken: text chunks as they arrive
     * onActivity: status updates like "Reading file...", "Searching...", "Thinking..."
     */
    data class ToolEvent(
        val toolName: String,
        val filePath: String?,
        val command: String?,
        val description: String?
    )

    fun sendMessageStreaming(
        message: String,
        systemPrompt: String? = null,
        onToken: (String) -> Unit,
        onActivity: ((String) -> Unit)? = null,
        onToolUse: ((ToolEvent) -> Unit)? = null,
        onDone: (ChatResponse) -> Unit
    ) {
        val claude = findClaudePath() ?: run {
            onDone(ChatResponse(null, "Claude Code not found", true))
            return
        }

        val cmd = mutableListOf(claude, "-p", "--output-format", "stream-json", "--verbose", "--effort", "high")

        // Permission mode: auto-accept or accept edits only
        if (autoAcceptCommands) {
            cmd.add("--dangerously-skip-permissions")
        } else {
            cmd.add("--permission-mode")
            cmd.add("acceptEdits")
        }

        if (shouldContinue) {
            cmd.add("--continue")
        } else if (systemPrompt != null) {
            // First message only — set system prompt
            cmd.add("--system-prompt")
            cmd.add(systemPrompt)
        }

        cmd.add(message)

        val pb = ProcessBuilder(cmd)
        pb.redirectInput(ProcessBuilder.Redirect.from(java.io.File("/dev/null")))
        project.basePath?.let { pb.directory(java.io.File(it)) }

        try {
            val process = pb.start()
            currentProcess = process

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val fullResponse = StringBuilder()
            var hasStartedText = false

            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (currentProcess == null) break

                val l = line ?: continue
                if (!l.startsWith("{")) continue

                try {
                    val json = JsonParser.parseString(l).asJsonObject
                    val type = json.get("type")?.asString ?: continue

                    when (type) {
                        "system" -> {
                            val subtype = json.get("subtype")?.asString
                            when (subtype) {
                                "init" -> onActivity?.invoke("\u25CF Initializing...")
                            }
                        }
                        "assistant" -> {
                            // Parse content array for text and tool_use
                            val msg = json.getAsJsonObject("message")
                            val content = msg?.getAsJsonArray("content")
                            if (content != null) {
                                for (item in content) {
                                    val obj = item.asJsonObject
                                    val itemType = obj.get("type")?.asString
                                    when (itemType) {
                                        "text" -> {
                                            val text = obj.get("text")?.asString ?: ""
                                            if (text.isNotEmpty()) {
                                                if (!hasStartedText) {
                                                    onActivity?.invoke("") // clear activity
                                                    hasStartedText = true
                                                }
                                                fullResponse.append(text)
                                                onToken(text)
                                            }
                                        }
                                        "tool_use" -> {
                                            val toolName = obj.get("name")?.asString ?: "tool"
                                            val input = obj.getAsJsonObject("input")
                                            val fp = input?.get("file_path")?.asString
                                            val cmd2 = input?.get("command")?.asString
                                            val desc = input?.get("description")?.asString

                                            val detail = when (toolName) {
                                                "Read" -> "\u25CF Reading ${fp?.substringAfterLast("/") ?: ""}"
                                                "Grep" -> "\u25CF Searching: ${input?.get("pattern")?.asString ?: ""}"
                                                "Glob" -> "\u25CF Finding: ${input?.get("pattern")?.asString ?: ""}"
                                                "Edit" -> "\u25CF Editing ${fp?.substringAfterLast("/") ?: ""}"
                                                "Write" -> "\u25CF Writing ${fp?.substringAfterLast("/") ?: ""}"
                                                "Bash" -> {
                                                    val short = if ((cmd2?.length ?: 0) > 50) cmd2?.take(50) + "..." else cmd2 ?: ""
                                                    "\u25CF Running: $short"
                                                }
                                                else -> "\u25CF Using $toolName"
                                            }
                                            onActivity?.invoke(detail)

                                            // Emit tool event for diff/permission blocks
                                            if (toolName in listOf("Edit", "Write", "Bash")) {
                                                onToolUse?.invoke(ToolEvent(
                                                    toolName = toolName,
                                                    filePath = fp,
                                                    command = cmd2,
                                                    description = desc
                                                ))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        "user" -> {
                            // Tool result — show what the tool returned
                            val msg = json.getAsJsonObject("message")
                            val content = msg?.getAsJsonArray("content")
                            if (content != null) {
                                for (item in content) {
                                    val obj = item.asJsonObject
                                    if (obj.get("type")?.asString == "tool_result") {
                                        val toolId = obj.get("tool_use_id")?.asString ?: ""
                                        // Brief indication that tool completed
                                        onActivity?.invoke("\u2713 Done")
                                    }
                                }
                            }
                        }
                        "content_block_delta" -> {
                            val delta = json.getAsJsonObject("delta")
                            val text = delta?.get("text")?.asString
                            if (text != null) {
                                if (!hasStartedText) {
                                    onActivity?.invoke("")
                                    hasStartedText = true
                                }
                                fullResponse.append(text)
                                onToken(text)
                            }
                        }
                        "result" -> {
                            val result = json.get("result")?.asString
                            if (json.get("session_id")?.asString != null) shouldContinue = true
                            if (result != null && fullResponse.isEmpty()) {
                                fullResponse.append(result)
                                onToken(result)
                            }
                        }
                    }
                } catch (_: Exception) {}
            }

            val exitCode = process.waitFor()
            currentProcess = null

            if (fullResponse.isEmpty()) {
                val stderr = try { process.errorStream.bufferedReader().readText() } catch (_: Exception) { "" }
                onDone(ChatResponse(null, "No response. $stderr", exitCode != 0))
            } else {
                onDone(ChatResponse(fullResponse.toString(), null, false))
            }

        } catch (e: Exception) {
            currentProcess = null
            log.warn("Claude streaming error: $e")
            onDone(ChatResponse(null, "Error: ${e.message}", true))
        }
    }

    /**
     * Non-streaming fallback (simpler, more reliable).
     */
    fun sendMessage(message: String, systemPrompt: String? = null): ChatResponse {
        val claude = findClaudePath()
            ?: return ChatResponse(null, "Claude Code not found", true)

        val cmd = mutableListOf(claude, "-p", "--output-format", "json", "--effort", "high")

        if (autoAcceptCommands) {
            cmd.add("--dangerously-skip-permissions")
        } else {
            cmd.add("--permission-mode")
            cmd.add("acceptEdits")
        }

        if (shouldContinue) {
            cmd.add("--continue")
        } else if (systemPrompt != null) {
            cmd.add("--system-prompt")
            cmd.add(systemPrompt)
        }

        cmd.add(message)

        val pb = ProcessBuilder(cmd)
        pb.redirectInput(ProcessBuilder.Redirect.from(java.io.File("/dev/null")))
        project.basePath?.let { pb.directory(java.io.File(it)) }

        try {
            val process = pb.start()
            currentProcess = process

            val stdout = process.inputStream.bufferedReader().readText().trim()
            val exitCode = process.waitFor()
            currentProcess = null

            if (exitCode != 0 && stdout.isEmpty()) {
                val err = process.errorStream.bufferedReader().readText().trim()
                return ChatResponse(null, "Exit $exitCode: $err", true)
            }

            val jsonStart = stdout.indexOf('{')
            if (jsonStart < 0) return ChatResponse(stdout.ifEmpty { null }, null, false)
            val jsonStr = stdout.substring(jsonStart)

            return try {
                val json = JsonParser.parseString(jsonStr).asJsonObject
                val result = json.get("result")?.asString
                val isError = json.get("is_error")?.asBoolean ?: false
                if (json.get("session_id")?.asString != null) shouldContinue = true
                if (isError) ChatResponse(null, result ?: "Error", true)
                else ChatResponse(result ?: "", null, false)
            } catch (_: Exception) {
                ChatResponse(stdout, null, false)
            }

        } catch (e: Exception) {
            currentProcess = null
            return ChatResponse(null, "Error: ${e.message}", true)
        }
    }

    fun cancel() {
        currentProcess?.let {
            try { it.destroyForcibly() } catch (_: Exception) {}
            currentProcess = null
        }
    }

    fun resetSession() {
        shouldContinue = false
        cancel()
    }

    data class ChatResponse(
        val text: String?,
        val error: String?,
        val isError: Boolean
    )
}
