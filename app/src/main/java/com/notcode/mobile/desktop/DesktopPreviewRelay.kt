package com.notcode.mobile.desktop

import fi.iki.elonen.NanoHTTPD
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import okhttp3.*

internal class DesktopPreviewRelay(
    private val api: DesktopApi,
    private val remotePort: Int,
    private val report: (String) -> Unit,
    private val revoked: () -> Unit,
) : NanoHTTPD("127.0.0.1", 0), Closeable {
    private val calls = ConcurrentHashMap.newKeySet<Call>()
    @Volatile private var stopped = false
    fun open(): String { start(15_000, true); return "http://127.0.0.1:$listeningPort/" }
    private fun checkSession(session: IHTTPSession) {
        check(!stopped)
        require(session.headers["host"] == "127.0.0.1:$listeningPort") { "Unexpected local authority" }
        session.headers["origin"]?.let { require(it == "http://127.0.0.1:$listeningPort") { "Cross-origin relay request denied" } }
        require(session.headers["connection"]?.contains("upgrade", true) != true)
        previewPath(session.uri)
    }

    override fun serve(session: IHTTPSession): Response {
        if (session.headers["upgrade"] != null || session.headers.keys.any { it.startsWith("sec-websocket") }) {
            report("WebSocket и HMR не поддерживаются; используйте HTTP preview.")
            return newFixedLengthResponse(Response.Status.NOT_IMPLEMENTED, "text/plain", "WebSocket and HMR are not supported")
        }
        return try {
            checkSession(session)
            val connectionHeaders = session.headers["connection"].orEmpty().split(',').map { it.trim().lowercase() }.toSet()
            require(session.headers["transfer-encoding"] == null) { "Chunked local request bodies unsupported" }
            val length = session.headers["content-length"]?.let { it.toLongOrNull() ?: error("Invalid content length") } ?: 0
            require(length in 0..MAX_TEXT_BYTES.toLong())
            val body = if (length > 0) ByteArray(length.toInt()).also { bytes ->
                var read = 0
                while (read < bytes.size) {
                    val count = session.inputStream.read(bytes, read, bytes.size - read)
                    if (count < 0) throw IOException("Truncated request")
                    read += count
                }
            } else null
            val request = api.previewRequest(remotePort, session.uri, session.queryParameterString, session.method.name,
                session.headers.filterKeys { it.lowercase() !in connectionHeaders }, body)
            val call = api.client.newCall(request)
            calls += call
            try {
                check(!stopped)
                call.execute().use { upstream ->
                    if (upstream.code == 401) revoked()
                    val bytes = upstream.body?.let { boundedDesktopBody(it, MAX_JSON_BYTES) } ?: ByteArray(0)
                    val status = object : Response.IStatus { override fun getRequestStatus() = upstream.code; override fun getDescription() = "${upstream.code} Preview" }
                    val response = newFixedLengthResponse(status, upstream.header("Content-Type") ?: "application/octet-stream", bytes.inputStream(), bytes.size.toLong())
                    val hopHeaders = upstream.header("Connection").orEmpty().split(',').map { it.trim().lowercase() }.toSet()
                    upstream.headers.forEach { (key, value) ->
                        if (previewHeaderAllowed(key) && key.lowercase() !in hopHeaders && !key.equals("content-security-policy", true)) response.addHeader(key, value)
                    }
                    response.addHeader("Content-Security-Policy", "default-src 'self' data: blob:; script-src 'self' 'unsafe-inline' 'unsafe-eval' blob:; style-src 'self' 'unsafe-inline'; connect-src 'self'; frame-src 'none'; object-src 'none'; form-action 'self'; base-uri 'self'")
                    response.addHeader("Cache-Control", "no-store")
                    response.addHeader("Referrer-Policy", "no-referrer")
                    response
                }
            } finally { calls -= call }
        } catch (_: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Preview недоступен или запрос не разрешён. Разрешите loopback-порт на ПК; произвольные URL и control API недоступны.")
        }
    }

    override fun close() {
        stopped = true
        calls.forEach { it.cancel() }; calls.clear()
        stop()
    }
}
