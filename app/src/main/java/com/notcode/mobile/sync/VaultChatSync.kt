package com.notcode.mobile.sync

import com.notcode.mobile.data.StoredChatItem
import com.notcode.mobile.data.StoredConversation
import com.notcode.mobile.data.StoredTurn
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

const val CHAT_KIND = "chat"

@Serializable
data class VaultChatPayload(
    val name: String,
    val model: String,
    val items: List<VaultChatItem>,
    val turns: List<VaultChatTurn> = emptyList(),
)

@Serializable
data class VaultChatItem(val role: String, val text: String, val id: String)

@Serializable
data class VaultChatTurn(val role: String, val text: String)

/// Чат в формате хранилища аккаунта: сериализация и парсинг. Чистые функции.
object VaultChatCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun toVaultItem(conversation: StoredConversation): VaultChatPayload = VaultChatPayload(
        name = conversation.title,
        model = "",
        items = conversation.items.map { VaultChatItem(it.role, it.text, it.id) },
        turns = conversation.turns.map { VaultChatTurn(it.role, it.text) },
    )

    fun fromVaultItem(id: String, updatedAt: Long, payload: VaultChatPayload): StoredConversation =
        StoredConversation(
            id = id,
            title = payload.name.ifBlank { "чат из аккаунта" },
            updatedAt = updatedAt,
            items = payload.items.map { StoredChatItem(it.role, it.text, it.id) },
            turns = payload.turns.map { StoredTurn(it.role, it.text) },
        )

    /// Парсит элемент vault (kind=chat) → (vaultId, updatedAt, payload). null = битый.
    fun parseChatItem(element: kotlinx.serialization.json.JsonElement): Triple<String, Long, VaultChatPayload>? = runCatching {
        val obj = element.jsonObject
        if (obj["kind"]?.jsonPrimitive?.content != CHAT_KIND) return@runCatching null
        val data = obj["data"]?.jsonObject ?: return@runCatching null
        val payload = json.decodeFromString(VaultChatPayload.serializer(), data.toString())
        val id = obj["id"]?.jsonPrimitive?.content ?: return@runCatching null
        val updatedAt = obj["updatedAt"]?.jsonPrimitive?.long ?: return@runCatching null
        Triple(id, updatedAt, payload)
    }.getOrNull()?.takeIf { (_, _, payload) -> payload.items.isNotEmpty() }

    /// Перегрузка для уже разобранного VaultItem.
    fun parseChatItem(item: VaultItem): Triple<String, Long, VaultChatPayload>? = runCatching {
        if (item.kind != CHAT_KIND) return@runCatching null
        val payload = json.decodeFromString(VaultChatPayload.serializer(), item.data.toString())
        Triple(item.id, item.updatedAt, payload)
    }.getOrNull()?.takeIf { (_, _, payload) -> payload.items.isNotEmpty() }
}

/// Двусторонняя синхронизация чатов телефона с хранилищем аккаунта.
/// Локально — StoredConversation; на сервере — зашифрованный vault (kind=chat).
class VaultChatSync(private val api: VaultApi) {

    /// Выгружает локальные чаты в аккаунт (пропускает уже синхронизированные).
    suspend fun push(local: List<StoredConversation>, vaultIds: Set<String> = emptySet()): Int {
        var sent = 0
        for (conversation in local) {
            if (conversation.id in vaultIds) continue
            val payload = VaultChatCodec.toVaultItem(conversation)
            if (payload.items.isEmpty()) continue
            val data = json.encodeToJsonElement(VaultChatPayload.serializer(), payload).jsonObject
            api.pushItem(CHAT_KIND, conversation.id, conversation.updatedAt, data)
            sent++
        }
        return sent
    }

    /// Загружает чаты из аккаунта, которых нет локально.
    suspend fun pull(existingIds: Set<String>): List<StoredConversation> {
        val all = api.listAll()
        return all.filter { it.kind == CHAT_KIND && it.id !in existingIds }.mapNotNull { item ->
            VaultChatCodec.parseChatItem(item)?.let { (id, updatedAt, payload) ->
                VaultChatCodec.fromVaultItem(id, updatedAt, payload)
            }
        }
    }
}
