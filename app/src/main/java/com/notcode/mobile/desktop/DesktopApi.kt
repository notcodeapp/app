package com.notcode.mobile.desktop

import java.io.Closeable
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class DesktopHttpException(val status: Int) : IOException(when (status) {
    401 -> "ПК отозвал доступ. Создайте новое приглашение вручную."
    409 -> "Конфликт версии (409). Изменения сохранены в редакторе; перезагрузите или сохраните отдельной копией."
    403 -> "ПК не разрешил действие или preview-порт."
    429 -> "Слишком много запросов к ПК за минуту. Подождите немного и повторите."
    else -> "Desktop HTTP $status"
})

/** No global SSL changes, DNS, proxies, redirects, cookies or HTTP logging. */
internal class DesktopApi(val connection: DesktopConnection, private var bearer: String?) : Closeable {
    private val authority = validateAddress(connection.address)
    private val base = connection.address.toHttpUrl()
    private val trust = DesktopPinTrustManager(connection.fingerprint)
    private val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf<X509TrustManager>(trust), null) }
    val client: OkHttpClient = OkHttpClient.Builder()
        .sslSocketFactory(ssl.socketFactory, trust)
        .hostnameVerifier { hostname, session ->
            // The manually invited authority + exact DER identity are the only accepted identity.
            hostname == authority.host && runCatching {
                trust.checkServerTrusted(session.peerCertificates.map { it as java.security.cert.X509Certificate }.toTypedArray(), "RSA")
                true
            }.getOrDefault(false)
        }
        .proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(65, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS).callTimeout(70, TimeUnit.SECONDS)
        .build()

    private fun endpoint(segments: List<String>, query: Map<String, String> = emptyMap()): HttpUrl = base.newBuilder()
        .apply { segments.forEach { addPathSegment(it) }; query.forEach { (key, value) -> addQueryParameter(key, value) } }.build()

    fun request(segments: List<String>, method: String = "GET", body: JsonObject? = null, query: Map<String, String> = emptyMap()): Request {
        require(segments.firstOrNull() == "v1")
        segments.forEach(::safeWireId)
        body?.let(::validateDesktopJsonText)
        val bytes = body?.toString()?.let(::desktopUtf8Bytes)
        require(bytes == null || bytes.size <= MAX_JSON_BYTES)
        return Request.Builder().url(endpoint(segments, query))
            .apply { bearer?.let { header("Authorization", "Bearer $it") } }
            .header("Accept", "application/json")
            .method(method, if (method == "GET") null else (bytes ?: desktopUtf8Bytes("{}")).toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
    }

    suspend fun json(segments: List<String>, method: String = "GET", body: JsonObject? = null, query: Map<String, String> = emptyMap()): JsonElement =
        execute(request(segments, method, body, query)).use { response ->
            if (!response.isSuccessful) throw DesktopHttpException(response.code)
            val data = boundedDesktopBody(requireNotNull(response.body), MAX_JSON_BYTES)
            bridgeJson.parseToJsonElement(desktopUtf8Text(data)).also(::validateDesktopJsonText)
        }

    suspend fun execute(request: Request): Response = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response, onCancellation = { response.close() })
            }
        })
    }

    suspend fun status(): DesktopStatus = bridgeJson.decodeFromJsonElement(json(listOf("v1", "status")))
    suspend fun models(): DesktopModels = bridgeJson.decodeFromJsonElement(json(listOf("v1", "models")))
    suspend fun sessions(): List<DesktopSession> = bridgeJson.decodeFromJsonElement(json(listOf("v1", "sessions")))
    suspend fun chat(id: String): DesktopChat = bridgeJson.decodeFromJsonElement(json(listOf("v1", "sessions", safeWireId(id))))
    suspend fun files(path: String): List<DesktopFileEntry> = bridgeJson.decodeFromJsonElement(json(listOf("v1", "files"), query = mapOf("path" to path)))
    suspend fun file(path: String): DesktopFile = validateRemoteFile(bridgeJson.decodeFromJsonElement(json(listOf("v1", "file"), query = mapOf("path" to safeDesktopPath(path)))), path)
    suspend fun putFile(path: String, content: String, expectedHash: String?): DesktopFile {
        safeDesktopPath(path)
        require(desktopUtf8Bytes(content).size <= MAX_TEXT_BYTES && !content.contains('\u0000'))
        require(expectedHash == null || Regex("[a-f0-9]{64}").matches(expectedHash))
        val data = buildJsonObject { put("path", path); put("content", content); expectedHash?.let { put("expectedHash", it) } }
        return validateRemoteFile(bridgeJson.decodeFromJsonElement(json(listOf("v1", "file"), "PUT", data)), path)
    }
    suspend fun snapshot(): DesktopSnapshot {
        val snapshot: DesktopSnapshot = bridgeJson.decodeFromJsonElement(json(listOf("v1", "snapshot")))
        require(snapshot.files.size <= 10_000 && snapshot.skipped.size <= 10_000)
        require(snapshot.files.map { it.path }.distinct().size == snapshot.files.size)
        snapshot.files.forEach { require(it.size in 0..MAX_TEXT_BYTES.toLong() && Regex("[a-f0-9]{64}").matches(it.hash)) }
        return snapshot
    }
    suspend fun approvals(): List<DesktopApproval> {
        val response = json(listOf("v1", "approvals"))
        return bridgeJson.decodeFromJsonElement(if (response is JsonArray) response else response.jsonObject.getValue("pending"))
    }
    suspend fun decide(id: String, approved: Boolean) {
        val response = json(listOf("v1", "approvals", safeWireId(id)), "POST", buildJsonObject { put("approved", approved) })
        require(response.jsonObject["ok"]?.jsonPrimitive?.booleanOrNull == true)
    }

    /** Incoming URI cannot change the bridge authority or the fixed preview prefix. */
    fun previewRequest(port: Int, rawPath: String, rawQuery: String?, method: String, headers: Map<String, String>, bytes: ByteArray?): Request {
        val path = previewPath(rawPath)
        require(port in 1..65535 && method in setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"))
        require(bytes == null || bytes.size <= MAX_TEXT_BYTES)
        require(rawQuery == null || rawQuery.length <= 8192 && rawQuery.none { it.code < 32 })
        val url = base.newBuilder().addPathSegment("v1").addPathSegment("preview").addPathSegment(port.toString())
            .apply { path.split('/').forEach { addPathSegment(it) }; encodedQuery(rawQuery) }.build()
        check(url.encodedPath.startsWith("/v1/preview/$port/"))
        return Request.Builder().url(url).apply {
            headers.filterKeys { previewHeaderAllowed(it) }.forEach { (key, value) -> header(key, value) }
            // Do not forward browser credentials or native control headers.
            header("Authorization", "Bearer ${checkNotNull(bearer)}")
            method(method, if (method in setOf("GET", "HEAD")) null else (bytes ?: ByteArray(0)).toRequestBody(headers["content-type"]?.toMediaType()))
        }.build()
    }

    override fun close() {
        bearer = null
        client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
    }
}

internal fun boundedDesktopBody(body: ResponseBody, limit: Int): ByteArray {
    require(body.contentLength() <= limit || body.contentLength() < 0) { "Ответ ПК слишком большой" }
    val source = body.source()
    source.request((limit + 1).toLong())
    require(source.buffer.size <= limit) { "Ответ ПК слишком большой" }
    return source.readByteArray()
}

internal fun previewPath(raw: String): String {
    require(raw.length <= 8192 && raw.startsWith('/') && !raw.startsWith("//"))
    var decoded = raw
    repeat(3) {
        val next = java.net.URLDecoder.decode(decoded.replace("+", "%2B"), "UTF-8")
        if (next != decoded) decoded = next
    }
    require(!decoded.contains('%') && !decoded.contains('\\') && decoded.none { it.code < 32 } &&
        !decoded.startsWith("//") && decoded.split('/').none { it == ".." || it == "." }) { "Небезопасный preview-путь" }
    return decoded.removePrefix("/")
}

internal fun previewHeaderAllowed(name: String): Boolean {
    val lower = name.lowercase()
    return lower !in setOf("authorization", "proxy-authorization", "authentication-info", "www-authenticate", "x-api-key", "x-auth-token", "x-access-token", "cookie", "set-cookie", "host", "origin", "referer", "connection", "upgrade", "keep-alive", "proxy-authenticate", "te", "trailer", "transfer-encoding", "content-length", "location") &&
        !lower.startsWith("sec-websocket") && !lower.startsWith("sec-fetch") && !lower.startsWith("x-notcode") && !lower.startsWith("x-forwarded") && !lower.startsWith("proxy-")
}
