package com.notcode.mobile.data

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class StoredChatItem(val role: String, val text: String, val id: String, val filePath: String? = null, val successful: Boolean? = null)

@Serializable
data class StoredTurn(val role: String, val text: String)

@Serializable
data class StoredConversation(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val items: List<StoredChatItem> = emptyList(),
    val turns: List<StoredTurn> = emptyList(),
)

/** Per-project chat history on private storage; bounded and written atomically. */
class ChatHistoryStore(private val directory: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private val file = File(directory, "chat-history.json")

    fun load(): List<StoredConversation> = runCatching {
        if (!file.exists() || file.length() > MAX_BYTES) return emptyList()
        json.decodeFromString(ListSerializer(StoredConversation.serializer()), file.readText())
    }.getOrDefault(emptyList())

    fun save(conversations: List<StoredConversation>) {
        val bounded = conversations.sortedByDescending { it.updatedAt }.take(MAX_CONVERSATIONS).map {
            it.copy(items = it.items.takeLast(MAX_ITEMS), turns = it.turns.takeLast(MAX_TURNS))
        }
        val text = json.encodeToString(ListSerializer(StoredConversation.serializer()), bounded)
        if (text.length > MAX_BYTES) return
        directory.mkdirs()
        val temp = File(directory, "chat-history.tmp")
        temp.writeText(text)
        if (!temp.renameTo(file)) {
            file.delete()
            temp.renameTo(file)
        }
    }

    companion object {
        private const val MAX_BYTES = 4L * 1024 * 1024
        private const val MAX_CONVERSATIONS = 50
        private const val MAX_ITEMS = 400
        private const val MAX_TURNS = 100
    }
}