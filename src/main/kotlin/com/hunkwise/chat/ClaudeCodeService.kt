package com.hunkwise.chat

import com.google.gson.JsonParser
import com.hunkwise.util.HunkwiseLogger
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter

@Service(Service.Level.PROJECT)
class ClaudeCodeService(private val project: Project) {

    private val log = HunkwiseLogger.LOG
    private var claudePath: String? = null
    @Volatile private var currentProcess: Process? = null
    @Volatile private var stdinWriter: BufferedWriter? = null
    @Volatile var autoAcceptCommands = false
    @Volatile var shouldContinue = false

    fun findClaudePath(): String? {
        if (claudePath != null) return claudePath
        val candidates = listOf(
            "${System.getProperty("user.home")}/.local/bin/claude",
            "/usr/local/bin/claude", "/opt/homebrew/bin/claude"
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

    fun buildSystemPrompt(): String {
        val root = project.basePath ?: return ""
        val branch = try {
            val p = ProcessBuilder("git", "rev-parse", "--abbrev-ref", "HEAD")
                .directory(java.io.File(root)).redirectErrorStream(true).start()
            p.inputStream.bufferedReader().readText().trim()
        } catch (_: Exception) { "unknown" }
        val langs = java.io.File(root).listFiles()?.mapNotNull {
            when (it.extension) {
                "kt", "kts" -> "Kotlin"; "java" -> "Java"; "go" -> "Go"; "py" -> "Python"
                "ts", "tsx" -> "TypeScript"; "js", "jsx" -> "JavaScript"; "rs" -> "Rust"
                "swift" -> "Swift"; "dart" -> "Dart"; else -> null
            }
        }?.distinct()?.joinToString(", ") ?: ""
        return """You are a code review assistant in an IntelliJ IDEA project.
Project: ${java.io.File(root).name}
Branch: $branch
Languages: $langs
Root: $root
RULES:
- Always respond in the same language the user writes in.
- Be concise and direct."""
    }

    // ══════════════════════════════════════════════════════════════════
    // DATA TYPES
    // ══════════════════════════════════════════════════════════════════

    data class ToolEvent(val toolName: String, val filePath: String?, val command: String?, val description: String?)
    data class PermissionDenial(val toolName: String, val toolInput: Map<String, String>)
    data class ChatResponse(val text: String?, val error: String?, val isError: Boolean)

    // ══════════════════════════════════════════════════════════════════
    // STREAMING — uses -p mode (non-interactive)
    // ══════════════════════════════════════════════════════════════════

    fun sendMessageStreaming(
        message: String,
        systemPrompt: String? = null,
        onToken: (String) -> Unit,
        onActivity: ((String) -> Unit)? = null,
        onToolUse: ((ToolEvent) -> Unit)? = null,
        onPermissionDenied: ((List<PermissionDenial>) -> Unit)? = null,
        onDone: (ChatResponse) -> Unit
    ) {
        val claude = findClaudePath() ?: run {
            onDone(ChatResponse(null, "Claude Code not found", true)); return
        }

        val cmd = mutableListOf(claude, "-p", "--output-format", "stream-json", "--verbose", "--effort", "high")

        if (autoAcceptCommands) {
            cmd.add("--dangerously-skip-permissions")
        } else {
            cmd.add("--permission-mode")
            cmd.add("acceptEdits")
        }

        if (shouldContinue) cmd.add("--continue")
        else if (systemPrompt != null) { cmd.add("--system-prompt"); cmd.add(systemPrompt) }

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
            // Track last tool_use for inline permission denial
            var lastToolName = ""
            var lastToolInput = mutableMapOf<String, String>()

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
                            if (json.get("subtype")?.asString == "init") onActivity?.invoke("\u25CF Initializing...")
                        }
                        "assistant" -> {
                            val msg = json.getAsJsonObject("message")
                            val content = msg?.getAsJsonArray("content")
                            if (content != null) {
                                for (item in content) {
                                    val obj = item.asJsonObject
                                    when (obj.get("type")?.asString) {
                                        "text" -> {
                                            val text = obj.get("text")?.asString ?: ""
                                            if (text.isNotEmpty()) {
                                                if (!hasStartedText) { onActivity?.invoke(""); hasStartedText = true }
                                                fullResponse.append(text); onToken(text)
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
                                                "Bash" -> "\u25CF Running: ${(cmd2 ?: "").take(50)}"
                                                else -> "\u25CF Using $toolName"
                                            }
                                            onActivity?.invoke(detail)
                                            // Track for inline permission denial
                                            lastToolName = toolName
                                            lastToolInput = mutableMapOf<String, String>()
                                            fp?.let { lastToolInput["file_path"] = it }
                                            cmd2?.let { lastToolInput["command"] = it }
                                            desc?.let { lastToolInput["description"] = it }
                                            input?.get("pattern")?.asString?.let { lastToolInput["pattern"] = it }
                                            input?.get("path")?.asString?.let { lastToolInput["path"] = it }

                                            if (toolName in listOf("Edit", "Write")) {
                                                onToolUse?.invoke(ToolEvent(toolName, fp, cmd2, desc))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        "user" -> {
                            val topResult = json.get("tool_use_result")?.asString ?: ""
                            if (topResult.contains("permission") || topResult.contains("haven't granted")) {
                                // Emit permission denial INLINE with the tool info
                                onActivity?.invoke("\u26A0 Permission denied: $lastToolName")
                                if (lastToolName.isNotEmpty()) {
                                    onPermissionDenied?.invoke(listOf(
                                        PermissionDenial(lastToolName, lastToolInput.toMap())
                                    ))
                                }
                            } else {
                                onActivity?.invoke("\u2713 Done")
                            }
                        }
                        "content_block_delta" -> {
                            val text = json.getAsJsonObject("delta")?.get("text")?.asString
                            if (text != null) {
                                if (!hasStartedText) { onActivity?.invoke(""); hasStartedText = true }
                                fullResponse.append(text); onToken(text)
                            }
                        }
                        "result" -> {
                            val result = json.get("result")?.asString
                            if (json.get("session_id")?.asString != null) shouldContinue = true
                            if (result != null && fullResponse.isEmpty()) {
                                fullResponse.append(result); onToken(result)
                            }
                            // Permission denials
                            val denials = json.getAsJsonArray("permission_denials")
                            if (denials != null && denials.size() > 0) {
                                val list = denials.mapNotNull { d ->
                                    val obj = d.asJsonObject
                                    val tn = obj.get("tool_name")?.asString ?: return@mapNotNull null
                                    val ti = obj.getAsJsonObject("tool_input")
                                    val map = mutableMapOf<String, String>()
                                    ti?.entrySet()?.forEach { (k, v) ->
                                        try { map[k] = v.asString } catch (_: Exception) { map[k] = v.toString().take(100) }
                                    }
                                    PermissionDenial(tn, map)
                                }
                                if (list.isNotEmpty()) onPermissionDenied?.invoke(list)
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
            onDone(ChatResponse(null, "Error: ${e.message}", true))
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // NON-STREAMING FALLBACK
    // ══════════════════════════════════════════════════════════════════

    fun sendMessage(message: String, systemPrompt: String? = null): ChatResponse {
        val claude = findClaudePath() ?: return ChatResponse(null, "Claude Code not found", true)
        val cmd = mutableListOf(claude, "-p", "--output-format", "json", "--effort", "high")
        if (autoAcceptCommands) cmd.add("--dangerously-skip-permissions")
        else { cmd.add("--permission-mode"); cmd.add("acceptEdits") }
        if (shouldContinue) cmd.add("--continue")
        else if (systemPrompt != null) { cmd.add("--system-prompt"); cmd.add(systemPrompt) }
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
            if (exitCode != 0 && stdout.isEmpty()) return ChatResponse(null, "Exit $exitCode", true)
            val jsonStart = stdout.indexOf('{')
            if (jsonStart < 0) return ChatResponse(stdout.ifEmpty { null }, null, false)
            return try {
                val json = JsonParser.parseString(stdout.substring(jsonStart)).asJsonObject
                val result = json.get("result")?.asString
                val isError = json.get("is_error")?.asBoolean ?: false
                if (json.get("session_id")?.asString != null) shouldContinue = true
                if (isError) ChatResponse(null, result ?: "Error", true) else ChatResponse(result ?: "", null, false)
            } catch (_: Exception) { ChatResponse(stdout, null, false) }
        } catch (e: Exception) {
            currentProcess = null; return ChatResponse(null, "Error: ${e.message}", true)
        }
    }

    fun cancel() {
        currentProcess?.let { try { it.destroyForcibly() } catch (_: Exception) {} }
        currentProcess = null
    }

    fun resetSession() { shouldContinue = false; cancel() }
}
