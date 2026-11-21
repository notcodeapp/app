package com.notcode.mobile.desktop

import android.content.Context
import com.notcode.mobile.data.AppPreferencesRepository
import com.notcode.mobile.security.SecretStore
import java.io.Closeable
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable internal data class DesktopPendingSend(val sessionId: String, val content: String, val contextPath: String?, val requestId: String)

internal const val MAX_PENDING_BYTES = 512 * 1024
internal fun validatePendingSend(pending: DesktopPendingSend): DesktopPendingSend {
    safeWireId(pending.sessionId)
    require(pending.content.isNotBlank() && desktopUtf8Bytes(pending.content).size <= 64 * 1024)
    pending.contextPath?.let(::safeDesktopPath)
    require(UUID.fromString(pending.requestId).toString() == pending.requestId)
    return pending
}
internal fun encodePendingSend(pending: DesktopPendingSend): String =
    bridgeJson.encodeToString(DesktopPendingSend.serializer(), validatePendingSend(pending)).also {
        require(desktopUtf8Bytes(it).size <= MAX_PENDING_BYTES)
    }
internal fun decodePendingSend(encoded: String): DesktopPendingSend {
    require(encoded.length <= MAX_PENDING_BYTES && desktopUtf8Bytes(encoded).size <= MAX_PENDING_BYTES)
    return validatePendingSend(bridgeJson.decodeFromString<DesktopPendingSend>(encoded))
}
internal fun revokesDesktopCredential(error: Exception, authenticated: Boolean): Boolean =
    authenticated && error is DesktopHttpException && error.status == 401

data class DesktopLiveAction(val name: String, val status: String, val detail: String)

/** Pairs running→final action events by name so repeated tool names stay distinct. */
internal fun mergeLiveAction(list: List<DesktopLiveAction>, name: String, status: String, detail: String): List<DesktopLiveAction> {
    val active = status == "approval" || status == "running"
    val index = if (active) -1 else list.indexOfLast { it.name == name && (it.status == "approval" || it.status == "running") }
    val next = if (index >= 0) list.toMutableList().also { it[index] = DesktopLiveAction(name, status, detail) }
    else list + DesktopLiveAction(name, status, detail)
    return if (next.size > 500) next.takeLast(500) else next
}

data class DesktopPanelState(
    val connection: DesktopConnection? = null, val connected: Boolean = false, val busy: Boolean = false,
    val status: String = "Вставьте приглашение из NotCode Desktop", val error: String? = null,
    val models: List<DesktopModel> = emptyList(), val modelErrors: List<String> = emptyList(),
    val sessions: List<DesktopSession> = emptyList(), val selected: DesktopChat? = null,
    val partialText: String = "", val liveActions: List<DesktopLiveAction> = emptyList(), val agentStatus: String = "", val directory: String = "",
    val files: List<DesktopFileEntry> = emptyList(), val editor: DesktopFile? = null,
    val editorDraft: String? = null, val editorConflict: Boolean = false, val copy: DesktopCopy? = null,
    val transferReport: String = "", val previewUrl: String? = null, val previewStatus: String = "",
    val pendingRequestId: String? = null,
)

/** All child jobs and response writes are scoped to one connection generation. */
class DesktopController(
    context: Context,
    private val scope: CoroutineScope,
    private val preferences: AppPreferencesRepository,
    private val secrets: SecretStore,
    private val approval: suspend (String, String, String) -> Boolean,
) : Closeable {
    private val mutable = MutableStateFlow(DesktopPanelState())
    val state = mutable.asStateFlow()
    private val privateFiles = context.filesDir
    private val jobPreferences = context.getSharedPreferences("desktop_jobs_v1", Context.MODE_PRIVATE)
    private val transfers = DesktopTransfers(privateFiles)
    private val operation = Mutex()
    private val credentials = Mutex()
    @Volatile private var generation = 0L
    private var connectionJob: Job? = null
    private var api: DesktopApi? = null
    private var relay: DesktopPreviewRelay? = null
    private val approvals = linkedMapOf<String, Job>()
    private val handledApprovals = hashSetOf<String>()
    private val phoneSessions = hashSetOf<String>()
    private var pendingSend: DesktopPendingSend? = null
    private var pendingRestoreError: String? = null
    private var sessionRevision = 0L
    private var fileRevision = 0L
    @Volatile private var closed = false

    init {
        val epoch = generation
        scope.launch {
            val saved = preferences.desktopConnection.first()
            if (valid(epoch)) {
                restorePendingSend(saved?.secretAlias)
                mutable.update { it.copy(connection = saved, status = if (saved == null) it.status else "Сохранён ПК. Нажмите «Подключиться» для проверки статуса.", pendingRequestId = pendingSend?.requestId, error = pendingRestoreError) }
                if (saved != null) {
                    val copy = runCatching { transfers.existingCopy(saved) }.getOrNull()
                    mutate(epoch) { it.copy(copy = copy) }
                }
            }
        }
    }
    private fun restorePendingSend(alias: String?) {
        pendingSend = null
        pendingRestoreError = null
        val encoded = jobPreferences.getString("pending", null) ?: return
        try {
            check(alias != null && jobPreferences.getString("connection", null) == alias)
            pendingSend = decodePendingSend(encoded)
        }
        catch (_: Exception) {
            pendingRestoreError = "Не удалось восстановить requestId предыдущей отправки. Новые отправки заблокированы, чтобы не выполнить запрос дважды. Проверьте чат на ПК; удаление подключения сбросит ожидание."
        }
    }
    private fun valid(epoch: Long) = !closed && generation == epoch
    private fun connectionScope(): CoroutineScope = CoroutineScope(scope.coroutineContext + requireNotNull(connectionJob))
    private fun mutate(epoch: Long, block: (DesktopPanelState) -> DesktopPanelState) { if (valid(epoch)) mutable.update(block) }
    private fun requireConnected(): DesktopApi { check(state.value.connected) { "Подключитесь к ПК" }; return checkNotNull(api) }

    fun reportScannerError(message: String) {
        mutable.update { it.copy(error = message) }
    }

    fun pair(input: String, deviceName: String) {
        val invite = try { DesktopInvitation.parse(input) } catch (error: Exception) {
            mutable.update { it.copy(error = error.message ?: "Неверное приглашение") }; return
        }
        if (deviceName.length !in 1..80 || deviceName.any { it.code < 32 }) {
            mutable.update { it.copy(error = "Имя устройства: 1–80 символов") }; return
        }
        stopNetwork()
        val epoch = generation
        connectionJob = SupervisorJob(scope.coroutineContext[Job])
        connectionScope().launch {
            credentials.withLock {
                mutate(epoch) { it.copy(busy = true, error = null, status = "Подтвердите устройство на ПК (до 60 секунд)") }
                val alias = "desktop.${UUID.randomUUID()}.bearer"
                val pairing = DesktopApi(DesktopConnection(invite.address, invite.fingerprint, "", "", "", alias), null)
                api = pairing
                var authenticated = false
                try {
                    val response = withTimeout(65_000) {
                        pairing.json(listOf("v1", "pair"), "POST", buildJsonObject { put("token", invite.token); put("deviceName", deviceName) }).jsonObject
                    }
                    val token = response.getValue("token").jsonPrimitive.content
                    require(token.length in 16..4096 && token.all { it.code in 33..126 })
                    val connection = DesktopConnection(invite.address, invite.fingerprint,
                        safeWireId(response.getValue("deviceId").jsonPrimitive.content), safeWireId(response.getValue("projectId").jsonPrimitive.content),
                        response.getValue("projectName").jsonPrimitive.content.take(200), alias)
                    coroutineContext.ensureActive()
                    check(valid(epoch))
                    val old = preferences.desktopConnection.first()
                    withContext(NonCancellable) {
                        secrets.put(alias, token.toByteArray())
                        if (valid(epoch)) {
                            preferences.saveDesktopConnection(connection)
                            if (!valid(epoch)) {
                                if (preferences.desktopConnection.first()?.secretAlias == alias) preferences.saveDesktopConnection(old)
                                secrets.delete(alias)
                            } else if (old != null && old.secretAlias != alias) secrets.delete(old.secretAlias)
                        } else secrets.delete(alias)
                    }
                    coroutineContext.ensureActive(); check(valid(epoch))
                    pairing.close()
                    api = DesktopApi(connection, token)
                    mutate(epoch) { DesktopPanelState(connection = connection, busy = true) }
                    authenticated = true
                    check(jobPreferences.edit().clear().commit()) { "Новое подключение сохранено, но старое ожидание не удалось сбросить. Проверьте чат на ПК перед отправкой." }
                    phoneSessions.clear(); pendingSend = null; pendingRestoreError = null
                    connect(epoch, requireNotNull(api))
                } catch (error: TimeoutCancellationException) { fail(epoch, java.io.IOException("ПК не подтвердил устройство за отведённое время. Получите новое приглашение вручную.")) }
                catch (error: CancellationException) { throw error }
                catch (error: Exception) { fail(epoch, error, authenticated) }
                finally { mutate(epoch) { it.copy(busy = false) } }
            }
        }
    }

    fun reconnect() {
        stopNetwork()
        val epoch = generation
        connectionJob = SupervisorJob(scope.coroutineContext[Job])
        connectionScope().launch {
            credentials.withLock {
                mutate(epoch) { it.copy(busy = true, error = null, status = "Проверяем сохранённое подключение…") }
                try {
                    val connection = preferences.desktopConnection.first() ?: error("Создайте приглашение на ПК")
                    require(connection.secretAlias.startsWith("desktop.") && connection.secretAlias.endsWith(".bearer"))
                    val bytes = secrets.get(connection.secretAlias) ?: error("Ключ отсутствует. Удалите подключение и вставьте новое приглашение.")
                    val token = try { bytes.toString(Charsets.UTF_8) } finally { bytes.fill(0) }
                    coroutineContext.ensureActive(); check(valid(epoch))
                    api = DesktopApi(connection, token)
                    phoneSessions.clear()
                    phoneSessions.addAll(jobPreferences.getStringSet("sessions", emptySet()).orEmpty())
                    restorePendingSend(connection.secretAlias)
                    mutate(epoch) { it.copy(connection = connection, pendingRequestId = pendingSend?.requestId, error = pendingRestoreError) }
                    connect(epoch, requireNotNull(api))
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) { fail(epoch, error) }
                finally { mutate(epoch) { it.copy(busy = false) } }
            }
        }
    }

    private suspend fun connect(epoch: Long, client: DesktopApi) {
        val status = client.status()
        check(status.projectId == client.connection.projectId) { "На ПК сменился проект. Нужно новое приглашение." }
        val models = client.models()
        require(models.models.size <= 2000 && models.errors.size <= 200)
        val sessions = client.sessions()
        require(sessions.size <= 2000)
        mutate(epoch) { it.copy(connected = true, error = null, models = models.models, modelErrors = models.errors, sessions = sessions,
            status = "Подключено · ${status.projectName}${if (status.running) " · агент работает" else ""}") }
        val copy = transfers.existingCopy(client.connection)
        mutate(epoch) { it.copy(copy = copy) }
        connectionScope().launch { poll(epoch, client) }
    }

    private suspend fun fail(epoch: Long, error: Exception, authenticated: Boolean = true) {
        if (!valid(epoch)) return
        if (revokesDesktopCredential(error, authenticated)) {
            val connection = state.value.connection
            stopNetwork()
            val revokedEpoch = generation
            mutable.update { it.copy(connection = null, connected = false, busy = false, error = error.message, status = "Доступ отозван", pendingRequestId = null) }
            phoneSessions.clear(); pendingSend = null; jobPreferences.edit().clear().apply()
            scope.launch { credentials.withLock {
                if (preferences.desktopConnection.first()?.secretAlias == connection?.secretAlias) {
                    preferences.saveDesktopConnection(null)
                    connection?.let { secrets.delete(it.secretAlias) }
                }
                mutate(revokedEpoch) { it.copy(connection = null) }
            } }
        } else {
            stopNetwork()
            val message = if (!authenticated && error is DesktopHttpException && error.status == 401)
                "Приглашение недействительно или уже использовано. Старое подключение сохранено; получите новое приглашение на ПК."
            else error.message ?: "Сетевая ошибка"
            mutable.update { it.copy(connected = false, error = message, status = "Ошибка подключения", previewUrl = null) }
        }
        relay?.close(); relay = null
    }

    private suspend fun poll(epoch: Long, client: DesktopApi) {
        var cursor = 0uL
        var needReset = true
        var failures = 0
        while (valid(epoch) && currentCoroutineContext().isActive) {
            try {
                val events: DesktopEvents = bridgeJson.decodeFromJsonElement(client.json(listOf("v1", "events"), query = mapOf("after" to cursor.toString())))
                require(events.events.size <= 5000 && (events.reset || events.cursor >= cursor))
                if (needReset || events.reset) {
                    reloadSelected(epoch, client)
                    val sessions = client.sessions()
                    mutate(epoch) { it.copy(sessions = sessions) }
                    refreshApprovals(epoch, client)
                    needReset = false
                } else {
                    var previous = cursor
                    for (event in events.events) {
                        require(event.seq > previous && event.seq <= events.cursor)
                        previous = event.seq
                        handleEvent(epoch, client, event)
                    }
                }
                cursor = events.cursor
                failures = 0
                mutate(epoch) { it.copy(connected = true, error = null, status = "Подключено · ${client.connection.projectName}") }
                if (events.events.isEmpty()) delay(300)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (!valid(epoch)) return
                if (error is DesktopHttpException && error.status == 401) { fail(epoch, error); return }
                relay?.close(); relay = null
                mutate(epoch) { it.copy(connected = false, error = error.message ?: "Сетевая ошибка", status = "Восстановление связи (${failures + 1}/6)", previewUrl = null) }
                if (++failures >= 6) { mutate(epoch) { it.copy(status = "Связь потеряна. Нажмите «Подключиться».") }; return }
                needReset = true
                delay((1000L shl (failures - 1)).coerceAtMost(16_000L))
                val status = runCatching { client.status() }.getOrNull()
                if (status != null && status.projectId != client.connection.projectId) { fail(epoch, IllegalStateException("Проект на ПК изменился")); return }
            }
        }
    }

    private suspend fun handleEvent(epoch: Long, client: DesktopApi, event: DesktopEvent) {
        when (event.kind) {
            "agent" -> {
                val sessionId = event.data["sessionId"]?.jsonPrimitive?.content ?: event.sessionId ?: return
                if (sessionId != state.value.selected?.session?.id) return
                val type = event.data.getValue("type").jsonPrimitive.content
                val text = event.data.getValue("text").jsonPrimitive.content
                require(desktopUtf8Bytes(text).size <= MAX_TEXT_BYTES)
                when (type) {
                    "delta" -> mutate(epoch) {
                        val combined = it.partialText + text
                        it.copy(partialText = if (desktopUtf8Bytes(combined).size <= MAX_TEXT_BYTES) combined else combined.takeLast(MAX_TEXT_BYTES / 2))
                    }
                    "action" -> {
                        val action = runCatching { bridgeJson.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
                        val name = action["name"]?.jsonPrimitive?.content ?: return
                        val status = action["status"]?.jsonPrimitive?.content ?: "running"
                        val detail = action["detail"]?.jsonPrimitive?.content.orEmpty()
                        mutate(epoch) { it.copy(liveActions = mergeLiveAction(it.liveActions, name, status, detail)) }
                    }
                    "message" -> reloadSelected(epoch, client)
                    "done" -> { reloadSelected(epoch, client); mutate(epoch) { it.copy(partialText = "", liveActions = emptyList(), agentStatus = "Завершено") } }
                    "error" -> { reloadSelected(epoch, client); mutate(epoch) { it.copy(partialText = "", liveActions = emptyList(), agentStatus = text, error = text) } }
                    "status" -> mutate(epoch) { it.copy(agentStatus = text) }
                    "tool" -> mutate(epoch) { it.copy(agentStatus = text) }
                }
            }
            "approval" -> {
                val request = runCatching { bridgeJson.decodeFromJsonElement<DesktopApproval>(event.data) }.getOrNull()
                if (request != null) enqueueApproval(epoch, client, request)
                else mutate(epoch) { it.copy(error = "Неполный запрос разрешения от ПК: не подтверждён") }
            }
            "approval-closed" -> {
                event.data["requestId"]?.jsonPrimitive?.content?.let { approvals.remove(it)?.cancel() }
            }
            "workspace" -> if (event.data["projectId"]?.jsonPrimitive?.content == client.connection.projectId) {
                val directory = state.value.directory
                val revision = fileRevision
                val files = client.files(directory)
                if (revision == fileRevision) mutate(epoch) { it.copy(files = validateEntries(files, directory)) }
            }
        }
    }

    private suspend fun refreshApprovals(epoch: Long, client: DesktopApi) {
        val pending = client.approvals()
        require(pending.size <= 200)
        approvals.filterKeys { id -> pending.none { it.requestId == id } }.values.toList().forEach { it.cancel() }
        pending.forEach { enqueueApproval(epoch, client, it) }
    }
    private fun enqueueApproval(epoch: Long, client: DesktopApi, request: DesktopApproval) {
        if (request.requestId in approvals || request.requestId in handledApprovals) return
        if (request.requestId.isBlank() || request.sessionId.isBlank() || request.name.isBlank()) {
            mutate(epoch) { it.copy(error = "Неполное разрешение: действие не подтверждено") }; return
        }
        // The server must additionally enforce device/job ownership: the wire contract has no owner field.
        if (request.sessionId !in phoneSessions) {
            mutate(epoch) { it.copy(agentStatus = "Запрос ${request.name} не принадлежит запущенной с телефона сессии; подтверждение недоступно") }; return
        }
        val job = connectionScope().launch(start = CoroutineStart.LAZY) {
            var sent = false
            try {
                val approved = withTimeoutOrNull(60_000) {
                    approval("ПК · ${request.name} · ${request.sessionId}", "", request.arguments.toString())
                } == true
                val stillPending = client.approvals().any { it.requestId == request.requestId && it.sessionId == request.sessionId }
                if (valid(epoch) && state.value.connected && stillPending) { client.decide(request.requestId, approved); sent = true }
            } finally {
                if (!sent) withContext(NonCancellable) { withTimeoutOrNull(3000) { runCatching { client.decide(request.requestId, false) } } }
                if (valid(epoch)) {
                    approvals.remove(request.requestId)
                    if (handledApprovals.size >= 5000) handledApprovals.clear()
                    handledApprovals += request.requestId
                }
            }
        }
        approvals[request.requestId] = job
        job.start()
    }

    private fun action(block: suspend (Long, DesktopApi) -> Unit) {
        if (!state.value.connected || connectionJob?.isActive != true) { mutable.update { it.copy(error = "Подключитесь к ПК") }; return }
        val epoch = generation
        connectionScope().launch {
            operation.withLock {
                mutate(epoch) { it.copy(busy = true, error = null) }
                try { block(epoch, requireConnected()) }
                catch (error: CancellationException) { throw error }
                catch (error: Exception) {
                    if (error is DesktopHttpException && error.status == 401) fail(epoch, error)
                    else mutate(epoch) { it.copy(error = error.message ?: "Ошибка действия") }
                } finally { mutate(epoch) { it.copy(busy = false) } }
            }
        }
    }
    fun refreshSessions() = action { epoch, client ->
        val sessions = client.sessions()
        mutate(epoch) { it.copy(sessions = sessions) }
    }
    fun createSession(model: DesktopModel) = action { epoch, client ->
        check(model in state.value.models)
        val session: DesktopSession = bridgeJson.decodeFromJsonElement(client.json(listOf("v1", "sessions"), "POST", buildJsonObject { put("provider", model.provider); put("model", model.id) }))
        val sessions = client.sessions()
        val chat = client.chat(session.id)
        sessionRevision++
        mutate(epoch) { it.copy(sessions = sessions, selected = chat, partialText = "", liveActions = emptyList(), agentStatus = "") }
    }
    fun openSession(id: String) {
        val revision = ++sessionRevision
        action { epoch, client ->
            val chat = client.chat(id)
            require(chat.messages.size <= 10_000 && chat.session.id == id)
            if (revision == sessionRevision) mutate(epoch) { it.copy(selected = chat, partialText = "", liveActions = emptyList(), agentStatus = "") }
        }
    }
    private suspend fun reloadSelected(epoch: Long, client: DesktopApi) {
        val id = state.value.selected?.session?.id ?: return
        val revision = sessionRevision
        val chat = client.chat(id)
        require(chat.session.id == id && chat.messages.size <= 10_000)
        if (revision == sessionRevision && state.value.selected?.session?.id == id)
            mutate(epoch) { it.copy(selected = chat, partialText = "") }
    }
    fun send(content: String, contextPath: String?) {
        val id = state.value.selected?.session?.id
        action { epoch, client ->
            check(pendingRestoreError == null) { requireNotNull(pendingRestoreError) }
            check(id != null) { "Откройте сессию" }
            val path = contextPath?.takeIf { it.isNotBlank() }?.let(::safeDesktopPath)
            val previous = pendingSend
            check(previous == null || previous.sessionId == id && previous.content == content && previous.contextPath == path) {
                "Предыдущая отправка имеет неопределённый результат. Повторите её тем же requestId; не создавайте новый запрос."
            }
            sendPending(epoch, client, previous ?: DesktopPendingSend(id, content, path, UUID.randomUUID().toString()))
        }
    }
    private suspend fun sendPending(epoch: Long, client: DesktopApi, pending: DesktopPendingSend) {
        val encoded = encodePendingSend(pending)
        pendingSend = pending
        mutate(epoch) { it.copy(pendingRequestId = pending.requestId) }
        check(jobPreferences.edit().putString("pending", encoded).putString("connection", client.connection.secretAlias)
            .putStringSet("sessions", (phoneSessions + pending.sessionId).toSet()).commit()) {
            "Не удалось надёжно сохранить requestId. Запрос на ПК не отправлен."
        }
        phoneSessions += pending.sessionId
        val result = client.json(listOf("v1", "sessions", pending.sessionId, "send"), "POST", buildJsonObject {
            put("content", pending.content); put("requestId", pending.requestId); pending.contextPath?.let { put("contextPath", it) }
        })
        require(result.jsonObject["accepted"]?.jsonPrimitive?.booleanOrNull == true)
        if (valid(epoch)) {
            check(jobPreferences.edit().remove("pending").commit()) { "ПК принял запрос, но ожидание не удалось удалить. Повтор с тем же requestId безопасен." }
            pendingSend = null
            mutate(epoch) { it.copy(pendingRequestId = null, agentStatus = "Принято ПК",
                selected = it.selected?.let { chat -> if (chat.session.id == pending.sessionId) chat.copy(running = true) else chat }) }
            reloadSelected(epoch, client)
        }
    }
    fun retrySend() {
        val pending = pendingSend ?: return
        action { epoch, client ->
            check(pendingRestoreError == null) { requireNotNull(pendingRestoreError) }
            check(pendingSend == pending) { "Ожидающий запрос изменился" }
            sendPending(epoch, client, pending)
        }
    }
    fun cancelAgent() = action { epoch, client ->
        val id = state.value.selected?.session?.id ?: return@action
        val response = client.json(listOf("v1", "sessions", id, "cancel"), "POST")
        require(response.jsonObject["ok"]?.jsonPrimitive?.booleanOrNull == true)
        reloadSelected(epoch, client)
    }
    fun exportChat(id: String? = null, onBundle: suspend (DesktopChatBundle) -> Unit) = action { epoch, client ->
        val sessionId = id ?: state.value.selected?.session?.id ?: error("Выберите чат")
        val chat = client.chat(sessionId)
        check(!chat.running) { "Дождитесь завершения ответа перед импортом" }
        val source = DesktopChatSource(client.connection.address, client.connection.fingerprint, client.connection.projectId,
            client.connection.projectName, sessionId, chat.session.provider, chat.session.model, chat.session.externalId)
        check(valid(epoch)); onBundle(DesktopChatBundle(source, chat))
        mutate(epoch) { it.copy(transferReport = "Чат «${chat.session.title}» скопирован в историю локального проекта. Модель и источник сохранены; ключи не переносились.") }
    }
    private fun validateEntries(entries: List<DesktopFileEntry>, directory: String): List<DesktopFileEntry> {
        require(entries.size <= 10_000)
        return entries.filter { entry ->
            runCatching {
                safeDesktopPath(entry.path)
                require(entry.path.substringAfterLast('/') == entry.name && entry.path.substringBeforeLast('/', "") == directory)
            }.isSuccess
        }
    }
    fun browse(path: String = "") {
        if (path.isNotEmpty()) runCatching { safeDesktopPath(path) }.getOrElse { mutable.update { s -> s.copy(error = it.message) }; return }
        val revision = ++fileRevision
        action { epoch, client ->
            val entries = validateEntries(client.files(path), path)
            if (revision == fileRevision) mutate(epoch) { it.copy(directory = path, files = entries) }
        }
    }
    fun openFile(path: String) {
        val revision = ++fileRevision
        action { epoch, client ->
            val file = client.file(path)
            if (revision == fileRevision) mutate(epoch) { it.copy(editor = file, editorDraft = file.content, editorConflict = false) }
        }
    }
    fun updateEditorDraft(content: String) {
        if (content.toByteArray().size <= MAX_TEXT_BYTES) mutable.update { it.copy(editorDraft = content) }
    }
    fun closeEditor() { ++fileRevision; mutable.update { it.copy(editor = null, editorDraft = null, editorConflict = false) } }
    fun saveFile(content: String, keepBoth: Boolean = false) = action { epoch, client ->
        val original = state.value.editor ?: error("Откройте файл")
        val revision = fileRevision
        val path = if (keepBoth) "${original.path}.phone-${UUID.randomUUID()}" else original.path
        if (!approval("Записать на ПК · $path", if (keepBoth) "" else original.content, content)) return@action
        check(valid(epoch))
        try {
            val written = client.putFile(path, content, if (keepBoth) null else original.hash)
            if (revision == fileRevision) mutate(epoch) { it.copy(editor = written, editorDraft = if (it.editorDraft == content) written.content else it.editorDraft, editorConflict = false) }
        } catch (error: DesktopHttpException) {
            if (error.status == 409) mutate(epoch) { it.copy(editorConflict = true) }
            throw error
        }
    }
    fun copyProject() = action { epoch, client ->
        val copy = transfers.copy(client) { skipped ->
            withContext(scope.coroutineContext.minusKey(Job)) {
                approval("Неполная текстовая копия проекта ПК", "", "Не копируются бинарные/большие файлы, зависимости, .git и учётные данные. Пропущено ${skipped.size}:\n${skipped.joinToString("\n")}")
            }
        }
        mutate(epoch) { it.copy(copy = copy, transferReport = "Текстовая копия доступна. Проверьте пропуски/неполные передачи (${copy.skipped.size}):\n${copy.skipped.joinToString("\n")}") }
    }
    fun syncProject() = action { epoch, client ->
        if (!approval("Синхронизировать текстовую копию с ПК", "", "Изменённые файлы передаются в обе стороны с проверкой SHA-256/CAS. Конфликты сохраняются отдельно. Удаления не применяются: API не поддерживает delete.")) return@action
        val result = transfers.synchronize(client)
        val copy = transfers.existingCopy(client.connection)
        mutate(epoch) { it.copy(copy = copy, transferReport = "На ПК: ${result.uploaded}; на телефон: ${result.downloaded}.\n${result.conflicts.joinToString("\n")}\nПропущено ${result.skipped.size}:\n${result.skipped.joinToString("\n")}") }
    }
    fun startPreview(port: Int) = action { epoch, client ->
        require(port in 1..65535)
        relay?.close(); relay = null
        // This is not an allow-port control endpoint: Desktop must explicitly approve its loopback port.
        client.execute(client.previewRequest(port, "/", null, "GET", emptyMap(), null)).use {
            if (!it.isSuccessful) throw DesktopHttpException(it.code)
        }
        check(valid(epoch))
        relay = DesktopPreviewRelay(client, port,
            report = { message -> mutate(epoch) { it.copy(previewStatus = message) } },
            revoked = { scope.launch { fail(epoch, DesktopHttpException(401)) } })
        val url = withContext(Dispatchers.IO) { requireNotNull(relay).open() }
        mutate(epoch) { it.copy(previewUrl = url, previewStatus = "HTTP preview. WebSocket, HMR и трансляция экрана не поддерживаются.") }
    }
    fun stopPreview() { relay?.close(); relay = null; mutable.update { it.copy(previewUrl = null) } }

    /** UI unmount and explicit disconnect invalidate *all* in-flight responses and approvals. */
    fun disconnect() { stopNetwork() }
    private fun stopNetwork() {
        generation++; sessionRevision++; fileRevision++
        val oldJob = connectionJob
        val oldApi = api
        connectionJob = null; api = null
        oldJob?.cancel()
        relay?.close(); relay = null
        oldApi?.client?.dispatcher?.cancelAll()
        approvals.clear(); handledApprovals.clear()
        mutable.update { it.copy(connected = false, busy = false, previewUrl = null, status = "Отключено; подключение только вручную") }
        if (oldApi != null) scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try { withTimeoutOrNull(3500) { oldJob?.join() } }
            finally { oldApi.close() }
        }
    }
    fun forget() {
        val connection = state.value.connection
        stopNetwork()
        val epoch = generation
        scope.launch { credentials.withLock {
            if (!valid(epoch)) return@withLock
            if (preferences.desktopConnection.first()?.secretAlias == connection?.secretAlias) {
                preferences.saveDesktopConnection(null); connection?.let { secrets.delete(it.secretAlias) }
            }
            if (valid(epoch)) {
                mutable.update { DesktopPanelState(status = "Подключение удалено; новое приглашение создаётся на ПК") }
                phoneSessions.clear(); pendingSend = null; jobPreferences.edit().clear().apply()
            }
        } }
    }
    override fun close() {
        closed = true; generation++
        connectionJob?.cancel(); relay?.close(); api?.close()
        relay = null; api = null; approvals.clear()
    }
}
