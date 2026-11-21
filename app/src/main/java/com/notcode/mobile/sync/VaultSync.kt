package com.notcode.mobile.sync

import com.notcode.mobile.model.AuthMode
import com.notcode.mobile.model.CapabilityMode
import com.notcode.mobile.model.ProviderProfile
import com.notcode.mobile.security.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

internal val json = Json { ignoreUnknownKeys = true }

const val SESSION_ALIAS = "github_session_token"
const val API_BASE = "https://gentle-base-825d.notkovvik.workers.dev"

data class VaultProvider(
    val id: String,
    val name: String,
    val baseUrl: String,
    val models: List<String>,
    val key: String?,
    val updatedAt: Long,
)

private class VaultUnauthorizedException : Exception()

/// Чистое преобразование элемента vault → профиль приложения.
/// 127.0.0.1/localhost (Ollama) разрешены: помечаем allowInsecureHttp.
fun vaultProviderToProfile(v: VaultProvider): ProviderProfile? {
    if (v.id.isBlank() || v.baseUrl.isBlank() || v.models.isEmpty()) return null
    val local = v.baseUrl.startsWith("http://") &&
        (v.baseUrl.contains("127.0.0.1") || v.baseUrl.contains("localhost"))
    if (!v.baseUrl.startsWith("https://") && !local) return null
    return ProviderProfile(
        id = "vault-" + v.id,
        displayName = v.name.ifBlank { "Из аккаунта" },
        baseUrl = v.baseUrl,
        modelId = v.models.first(),
        authMode = if (v.key == null) AuthMode.NONE else AuthMode.BEARER,
        toolsMode = CapabilityMode.ENABLED,
        streamingMode = CapabilityMode.ENABLED,
        allowInsecureHttp = local,
        providerName = "аккаунт",
    )
}

/// Парсинг элементов /api/vault. Битые элементы отбрасываются.
fun parseVaultItems(array: JsonArray?): List<VaultProvider> =
    array.orEmpty().mapNotNull { element ->
        runCatching {
            val item = jsonParse(element.toString()).jsonObject
            if (item["kind"]?.jsonPrimitive?.content != "provider") return@mapNotNull null
            val data = item["data"]?.jsonObject ?: return@mapNotNull null
            VaultProvider(
                id = item["id"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                name = data["name"]?.jsonPrimitive?.content ?: "",
                baseUrl = data["baseUrl"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                models = data["models"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
                key = data["key"]?.jsonPrimitive?.content,
                updatedAt = item["updatedAt"]?.jsonPrimitive?.long ?: 0L,
            )
        }.getOrNull()
    }.filterNotNull()

/// Парсинг JSON-строки (helper для чистых функций).
fun jsonParse(text: String): JsonObject = json.parseToJsonElement(text).jsonObject

/// Синхронизация провайдеров с хранилищем аккаунта (/api/vault воркера).
/// Сервер хранит элементы зашифрованными (AES-GCM).
class VaultApi(private val secrets: SecretStore, private val client: OkHttpClient = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()) {

    private suspend fun bearer(): String {
        val bytes = secrets.get(SESSION_ALIAS) ?: throw VaultUnauthorizedException()
        val token = try { bytes.toString(Charsets.UTF_8) } finally { bytes.fill(0) }
        return token
    }

    suspend fun listProviders(): List<VaultProvider> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$API_BASE/api/vault")
            .header("Authorization", "Bearer ${bearer()}").get().build()
        client.newCall(request).execute().use { response ->
            if (response.code == 401) throw VaultUnauthorizedException()
            check(response.isSuccessful) { "Сервер аккаунта вернул HTTP ${response.code}" }
            val body = requireNotNull(response.body?.string()) { "Пустой ответ" }
            parseItems(jsonParse(body).jsonObject["items"]?.jsonArray)
        }
    }

    /// Все элементы хранилища (расшифрованные сервером), без фильтра по kind.
    suspend fun listAll(): List<VaultItem> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$API_BASE/api/vault")
            .header("Authorization", "Bearer ${bearer()}").get().build()
        client.newCall(request).execute().use { response ->
            if (response.code == 401) throw VaultUnauthorizedException()
            check(response.isSuccessful) { "Сервер аккаунта вернул HTTP ${response.code}" }
            val body = requireNotNull(response.body?.string()) { "Пустой ответ" }
            parseAllItems(jsonParse(body).jsonObject["items"]?.jsonArray)
        }
    }

    suspend fun pushProvider(provider: VaultProvider) = withContext(Dispatchers.IO) {
        pushItem("provider", provider.id, provider.updatedAt, buildJsonObject {
            put("name", provider.name)
            put("baseUrl", provider.baseUrl)
            put("models", JsonArray(provider.models.map { kotlinx.serialization.json.JsonPrimitive(it) }))
            provider.key?.let { put("key", it) }
        })
    }

    suspend fun pushItem(kind: String, id: String, updatedAt: Long, data: kotlinx.serialization.json.JsonObject) = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("kind", kind)
            put("id", id)
            put("updatedAt", updatedAt)
            put("data", data)
        }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder().url("$API_BASE/api/vault")
            .header("Authorization", "Bearer ${bearer()}").put(payload).build()
        client.newCall(request).execute().use { response ->
            if (response.code == 401) throw VaultUnauthorizedException()
            check(response.isSuccessful) { "Сервер аккаунта вернул HTTP ${response.code}" }
        }
    }

    internal fun parseItems(array: JsonArray?): List<VaultProvider> = parseVaultItems(array)

}

/// Импорт в приложение: возвращает число новых провайдеров.
/// keyWriter(alias, key) сохраняет ключ в Android Keystore.
suspend fun syncPull(
    api: VaultApi,
    keyWriter: suspend (alias: String, key: String) -> Unit,
    existingIds: Set<String> = emptySet(),
): Int {
    var imported = 0
    for (provider in api.listProviders()) {
        if (provider.id in existingIds) continue
        val profile = vaultProviderToProfile(provider) ?: continue
        provider.key?.let { keyWriter(requireNotNull(profile.secretAlias), it) }
        imported++
    }
    return imported
}

/// Отправка профилей (с ключами из хранилища) в аккаунт.
suspend fun syncPush(
    api: VaultApi,
    keyReader: suspend (alias: String) -> String?,
    profiles: List<ProviderProfile>,
): Int {
    var sent = 0
    for (profile in profiles) {
        val alias = profile.secretAlias ?: continue
        api.pushProvider(VaultProvider(
            id = profile.id,
            name = profile.displayName.ifBlank { "Профиль" },
            baseUrl = profile.baseUrl,
            models = listOf(profile.modelId),
            key = keyReader(alias),
            updatedAt = profile.updatedAtEpochMs,
        ))
        sent++
    }
    return sent
}

// ---- Элементы vault (общий формат: provider + chat) ----------------------------

data class VaultItem(val kind: String, val id: String, val updatedAt: Long, val data: JsonObject)

fun parseAllItems(array: JsonArray?): List<VaultItem> =
    array.orEmpty().mapNotNull { element ->
        runCatching {
            val obj = jsonParse(element.toString())
            VaultItem(
                kind = obj["kind"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                id = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                updatedAt = obj["updatedAt"]?.jsonPrimitive?.long ?: 0L,
                data = obj["data"]?.jsonObject ?: return@mapNotNull null,
            )
        }.getOrNull()
    }

fun VaultItem.toProvider(): VaultProvider {
    val data = this.data
    return VaultProvider(
        id = id,
        name = data["name"]?.jsonPrimitive?.content ?: "",
        baseUrl = data["baseUrl"]?.jsonPrimitive?.content ?: "",
        models = data["models"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
        key = data["key"]?.jsonPrimitive?.content,
        updatedAt = updatedAt,
    )
}
