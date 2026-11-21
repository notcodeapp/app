package com.notcode.mobile.data

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.IdentityHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class StoredChatItem(val role: String, val text: String, val id: String, val filePath: String? = null, val successful: Boolean? = null)

@Serializable
data class StoredTurn(val role: String, val text: String)

@Serializable
data class StoredPlanStep(val title: String, val status: String = "pending")

@Serializable
data class StoredConversation(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val items: List<StoredChatItem> = emptyList(),
    val turns: List<StoredTurn> = emptyList(),
    val plan: List<StoredPlanStep> = emptyList(),
    val desktopSource: com.notcode.mobile.desktop.DesktopChatSource? = null,
)

/** Per-project chat history on private storage; bounded and written atomically. */
class ChatHistoryStore(private val directory: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private val file = File(directory, "chat-history.json")

    @Synchronized
    fun load(): List<StoredConversation> = runCatching {
        if (!file.exists() || file.length() > MAX_BYTES) return emptyList()
        val bytes = file.readBytes()
        if (bytes.size.toLong() > MAX_BYTES) return emptyList()
        json.decodeFromString(ListSerializer(StoredConversation.serializer()), bytes.toString(Charsets.UTF_8))
    }.getOrDefault(emptyList())

    /** Imports must fit in full, before the VM publishes them. No disk IO here. */
    internal fun validateExact(conversations: List<StoredConversation>) {
        encode(conversations, requireExact = true)
    }

    private fun encode(conversations: List<StoredConversation>, requireExact: Boolean): ByteArray {
        if (requireExact) {
            require(conversations.size <= MAX_CONVERSATIONS &&
                conversations.all { it.items.size <= MAX_ITEMS && it.turns.size <= MAX_TURNS }) {
                "Импорт не помещается в историю проекта. Удалите старые чаты перед импортом."
            }
        }
        val bounded = conversations.sortedByDescending { it.updatedAt }.take(MAX_CONVERSATIONS).map {
            it.copy(items = it.items.takeLast(MAX_ITEMS), turns = it.turns.takeLast(MAX_TURNS))
        }
        val bytes = json.encodeToString(ListSerializer(StoredConversation.serializer()), bounded).toByteArray(Charsets.UTF_8)
        if (bytes.size.toLong() > MAX_BYTES) {
            throw IOException("История проекта заполнена (4 МиБ). Удалите старые чаты перед сохранением.")
        }
        return bytes
    }

    @Synchronized
    fun save(conversations: List<StoredConversation>, requireExact: Boolean = false) {
        val provenance = load().associate { it.id to it.desktopSource }
        val bytes = encode(conversations.map { it.copy(desktopSource = it.desktopSource ?: provenance[it.id]) }, requireExact)
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Не удалось создать папку истории")
        val temp = File.createTempFile("chat-history-", ".tmp", directory)
        try {
            FileOutputStream(temp).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            // Same-directory atomic replacement only. If unsupported or unsuccessful, fail
            // without deleting the original. The replacement's contents are already fsynced.
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temp.delete()
        }
    }

    companion object {
        private const val MAX_BYTES = 4L * 1024 * 1024
        private const val MAX_CONVERSATIONS = 50
        private const val MAX_ITEMS = 400
        private const val MAX_TURNS = 100
    }
}

/** Captured at the export button invocation, not after the remote fetch. */
internal class ChatImportDestination(val store: ChatHistoryStore, private val workspace: Any) {
    fun requireCurrent(currentStore: ChatHistoryStore?, currentWorkspace: Any?, sshActive: Boolean) {
        check(!sshActive && currentStore === store && currentWorkspace === workspace) {
            "Проект назначения изменился во время импорта; повторите в нужном локальном проекте"
        }
    }
}

/** One IO lane, with independent revisions and retained pending state for each project store. */
internal class ChatHistoryWriter(private val dispatcher: CoroutineDispatcher = Dispatchers.IO) {
    internal data class Snapshot(
        val store: ChatHistoryStore,
        val revision: Long,
        val conversations: List<StoredConversation>,
        val requireExact: Boolean,
    )

    private class State {
        var revision = 0L
        var writtenRevision = 0L
        var latest: Snapshot? = null
    }

    private val states = IdentityHashMap<ChatHistoryStore, State>()
    private val io = Mutex()

    @Synchronized
    fun snapshot(store: ChatHistoryStore, conversations: List<StoredConversation>, requireExact: Boolean = false): Snapshot {
        val copied = conversations.map { it.copy(items = it.items.toList(), turns = it.turns.toList()) }
        if (requireExact) store.validateExact(copied)
        val state = states.getOrPut(store) { State() }
        check(state.revision < Long.MAX_VALUE) { "Исчерпаны ревизии истории" }
        return Snapshot(store, ++state.revision, copied, requireExact).also { state.latest = it }
    }

    @Synchronized
    fun latest(store: ChatHistoryStore): List<StoredConversation>? = states[store]?.latest?.conversations

    suspend fun <T> serialized(block: suspend () -> T): T = io.withLock { block() }

    // Only called inside serialized: imports retain the lane across read, Main publication
    // and write, while normal UI snapshots can still advance on Main during suspended IO.
    suspend fun loadLocked(store: ChatHistoryStore): List<StoredConversation> = withContext(dispatcher) { store.load() }

    suspend fun save(snapshot: Snapshot) = serialized { flushLocked(snapshot.store) }

    @Synchronized
    private fun pending(store: ChatHistoryStore): Snapshot? = states[store]?.let { state ->
        state.latest?.takeIf { it.revision > state.writtenRevision }
    }

    @Synchronized
    private fun isCurrent(snapshot: Snapshot): Boolean = states[snapshot.store]?.revision == snapshot.revision

    @Synchronized
    private fun written(snapshot: Snapshot) {
        val state = checkNotNull(states[snapshot.store])
        state.writtenRevision = maxOf(state.writtenRevision, snapshot.revision)
    }

    suspend fun flushLocked(store: ChatHistoryStore) {
        while (true) {
            // Coalesce older queued snapshots into the latest for this store. Never fence a
            // captured save on the current UI destination: switched-away projects must save.
            val next = pending(store) ?: return
            withContext(dispatcher) {
                // A newer Main snapshot can arrive after selection but before IO starts.
                // Reject that stale revision; the loop immediately picks the replacement.
                if (isCurrent(next)) store.save(next.conversations, next.requireExact)
            }
            written(next)
            // A local continuation may have been published while save suspended. Persist it
            // next, before releasing the lane; older tasks now become no-ops.
        }
    }
}