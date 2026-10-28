package com.piyushbodaa.autark.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID

data class Message(
    val role: String,  // "user" or "assistant"
    val text: String,
    val model: String? = null,
    val tokens: Int = 0,
    val seconds: Double = 0.0,
    /** The reply stopped short (the length limit, or the app closed while writing it); Continue carries on. */
    val cut: Boolean = false,
) {
    val isUser get() = role == "user"
}

data class Conversation(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
    val messages: List<Message> = emptyList(),
)

/** Conversations live as one small JSON file each, in the app's private storage. */
class ChatStore(private val dir: File) {

    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    // A reply is saved every few seconds while it streams in, so saves can overlap.
    private val writing = Mutex()

    suspend fun load() = withContext(Dispatchers.IO) {
        dir.mkdirs()
        _conversations.value = dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty()
            .mapNotNull { runCatching { decode(JSONObject(it.readText())) }.getOrNull() }
            .sortedByDescending { it.updatedAt }
    }

    /** False means the chat is still in memory but could not be saved to disk. */
    suspend fun save(conversation: Conversation): Boolean = withContext(Dispatchers.IO) {
        if (conversation.messages.isEmpty()) return@withContext true
        try {
            writing.withLock {
                dir.mkdirs()
                val file = File(dir, "${conversation.id}.json")
                val temp = File(dir, "${conversation.id}.json.tmp")
                temp.writeText(encode(conversation).toString())
                if (!temp.renameTo(file)) throw IOException("Couldn't replace the saved chat")
            }
        } catch (_: IOException) {
            return@withContext false
        }
        _conversations.update { list ->
            (listOf(conversation) + list.filter { it.id != conversation.id }).sortedByDescending { it.updatedAt }
        }
        true
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        File(dir, "$id.json").delete()
        _conversations.update { list -> list.filter { it.id != id } }
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        dir.listFiles().orEmpty().forEach { it.delete() }
        _conversations.value = emptyList()
    }

    private fun encode(c: Conversation) = JSONObject()
        .put("id", c.id)
        .put("title", c.title)
        .put("updatedAt", c.updatedAt)
        .put("messages", JSONArray().apply {
            c.messages.forEach { m ->
                put(JSONObject()
                    .put("role", m.role)
                    .put("text", m.text)
                    .put("model", m.model ?: JSONObject.NULL)
                    .put("tokens", m.tokens)
                    .put("seconds", m.seconds)
                    .put("cut", m.cut))
            }
        })

    private fun decode(o: JSONObject): Conversation {
        val messages = o.getJSONArray("messages")
        return Conversation(
            id = o.getString("id"),
            title = o.optString("title"),
            updatedAt = o.optLong("updatedAt"),
            messages = (0 until messages.length()).map { i ->
                val m = messages.getJSONObject(i)
                Message(
                    role = m.getString("role"),
                    text = m.getString("text"),
                    model = if (m.isNull("model")) null else m.optString("model"),
                    tokens = m.optInt("tokens"),
                    seconds = m.optDouble("seconds", 0.0),
                    cut = m.optBoolean("cut"),
                )
            },
        )
    }
}
