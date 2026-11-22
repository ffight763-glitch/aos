package com.dozy.pocketai.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
data class WebSource(val title: String, val url: String)

@Serializable
data class Message(
    val id: String = UUID.randomUUID().toString(),
    val role: String,               // "user" | "assistant"
    val text: String,
    val webContext: String? = null, // search results fed to the model with this user message
    val sources: List<WebSource> = emptyList(),
    val repoContext: String? = null,         // attached repository files sent with this message
    val repoFiles: List<String> = emptyList(),
    val tokensPerSec: Float? = null,
)

@Serializable
data class Conversation(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "Новый чат",
    val updatedAt: Long = System.currentTimeMillis(),
    val messages: List<Message> = emptyList(),
)

class ChatStore(context: Context) {
    private val dir = File(context.filesDir, "chats").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun list(): List<Conversation> =
        dir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { runCatching { json.decodeFromString<Conversation>(it.readText()) }.getOrNull() }
            ?.sortedByDescending { it.updatedAt }
            ?: emptyList()

    fun save(c: Conversation) {
        if (c.messages.isEmpty()) return
        val tmp = File(dir, "${c.id}.tmp")
        tmp.writeText(json.encodeToString(Conversation.serializer(), c))
        tmp.renameTo(File(dir, "${c.id}.json"))
    }

    fun delete(id: String) {
        File(dir, "$id.json").delete()
    }
}
