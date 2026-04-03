package com.hunkwise.chat

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Manages chat session history. Sessions are stored as JSON files
 * in .idea/hunkwise/sessions/. Each session has an ID, title,
 * timestamp, and list of messages.
 */
@Service(Service.Level.PROJECT)
class SessionManager(private val project: Project) {

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private var currentSessionId: String? = null
    private val sessions = mutableListOf<SessionInfo>()

    data class ChatMessage(
        val sender: String,       // "You", "Claude", "system", "error", "diff", "activity"
        val text: String,
        val filePath: String? = null,   // for diff messages
        val addedCount: Int = 0,        // for diff messages
        val removedCount: Int = 0,      // for diff messages
        val timestamp: Long = System.currentTimeMillis()
    )

    data class SessionData(
        val id: String,
        val title: String,
        val createdAt: Long,
        val updatedAt: Long,
        val messages: MutableList<ChatMessage>
    )

    data class SessionInfo(
        val id: String,
        val title: String,
        val createdAt: Long,
        val updatedAt: Long,
        val messageCount: Int,
        val preview: String  // first few words of last message
    )

    private fun sessionsDir(): File {
        val dir = File(project.basePath ?: return File(""), ".idea/hunkwise/sessions")
        dir.mkdirs()
        return dir
    }

    private fun sessionFile(id: String): File = File(sessionsDir(), "$id.json")

    /** Get or create current session */
    fun getCurrentSession(): SessionData {
        val id = currentSessionId
        if (id != null) {
            val session = loadSession(id)
            if (session != null) return session
        }
        return createNewSession()
    }

    /** Create a new empty session */
    fun createNewSession(title: String? = null): SessionData {
        val id = UUID.randomUUID().toString().take(8)
        val now = System.currentTimeMillis()
        val sessionTitle = title ?: "Chat ${formatTime(now)}"
        val session = SessionData(id, sessionTitle, now, now, mutableListOf())
        currentSessionId = id
        saveSession(session)
        refreshSessionList()
        return session
    }

    /** Add a diff block to the session */
    fun addDiffMessage(filePath: String, addedCount: Int, removedCount: Int) {
        val session = getCurrentSession()
        session.messages.add(ChatMessage("diff", "", filePath, addedCount, removedCount))
        saveSession(session.copy(updatedAt = System.currentTimeMillis()))
    }

    /** Add an activity message */
    fun addActivityMessage(text: String) {
        val session = getCurrentSession()
        session.messages.add(ChatMessage("activity", text))
        saveSession(session.copy(updatedAt = System.currentTimeMillis()))
    }

    /** Add a message to the current session and save */
    fun addMessage(sender: String, text: String) {
        val session = getCurrentSession()
        session.messages.add(ChatMessage(sender, text))
        val updated = session.copy(updatedAt = System.currentTimeMillis())
        // Auto-title from first user message
        val updatedSession = if (updated.title.startsWith("Chat ") && sender == "You" && updated.messages.size <= 2) {
            val shortTitle = if (text.length > 40) text.take(40) + "..." else text
            updated.copy(title = shortTitle)
        } else updated
        saveSession(updatedSession)
        refreshSessionList()
    }

    /** Switch to a different session */
    fun switchToSession(id: String): SessionData? {
        val session = loadSession(id)
        if (session != null) {
            currentSessionId = id
        }
        return session
    }

    /** Get list of all sessions, sorted by most recent */
    fun getSessionList(): List<SessionInfo> {
        if (sessions.isEmpty()) refreshSessionList()
        return sessions.toList()
    }

    /** Delete a session */
    fun deleteSession(id: String) {
        val file = sessionFile(id)
        if (file.exists()) file.delete()
        if (currentSessionId == id) currentSessionId = null
        refreshSessionList()
    }

    fun getCurrentSessionId(): String? = currentSessionId

    // ── Private helpers ──────────────────────────────────────────────

    private fun loadSession(id: String): SessionData? {
        val file = sessionFile(id)
        if (!file.exists()) return null
        return try {
            gson.fromJson(file.readText(), SessionData::class.java)
        } catch (_: Exception) {
            null
        }
    }

    private fun saveSession(session: SessionData) {
        try {
            sessionFile(session.id).writeText(gson.toJson(session))
        } catch (_: Exception) {}
    }

    private fun refreshSessionList() {
        sessions.clear()
        val dir = sessionsDir()
        if (!dir.exists()) return

        dir.listFiles()?.filter { it.extension == "json" }?.mapNotNull { file ->
            try {
                val session = gson.fromJson(file.readText(), SessionData::class.java)
                val lastMsg = session.messages.lastOrNull()
                val preview = lastMsg?.text?.take(50)?.replace("\n", " ") ?: "Empty"
                SessionInfo(
                    session.id, session.title, session.createdAt,
                    session.updatedAt, session.messages.size, preview
                )
            } catch (_: Exception) { null }
        }?.sortedByDescending { it.updatedAt }?.let { sessions.addAll(it) }
    }

    private fun formatTime(millis: Long): String {
        return SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(millis))
    }
}
