package com.notcode.mobile.browser

import java.io.IOException
import java.io.OutputStream
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.coroutines.coroutineContext

data class DownloadRequest(
    val url: String,
    val fileName: String,
    val mimeType: String,
    val contentLength: Long,
    val userAgent: String?,
)

/** User-confirmed downloads only; same URL policy as navigation, never auto-opened or executed. */
object BrowserDownloader {
    const val MAX_BYTES = 200L * 1024 * 1024

    fun safeFileName(raw: String?): String {
        val name = raw.orEmpty().substringAfterLast('/').substringAfterLast('\\')
            .filter { !it.isISOControl() && it != '/' && it != '\\' }
            .trim().take(128)
        return name.ifBlank { "download" }
    }

    suspend fun save(request: DownloadRequest, cookieHeader: String?, output: OutputStream): Long {
        val initial = PreviewUrlPolicy.normalize(request.url)
        val job = coroutineContext[Job]
        val client = OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
        try {
            return runInterruptible(Dispatchers.IO) {
                var current = initial
                var response: okhttp3.Response? = null
                for (hop in 0 until 5) {
                    val builder = Request.Builder().url(current)
                    request.userAgent?.let { builder.header("User-Agent", it) }
                    // WebView cookies apply to the original download URL only. Never forward
                    // them to a redirect target, which may be a third-party file host.
                    if (hop == 0) cookieHeader?.takeIf { it.isNotBlank() }?.let { builder.header("Cookie", it) }
                    val candidate = client.newCall(builder.build()).execute()
                    if (candidate.code in setOf(301, 302, 303, 307, 308)) {
                        val location = candidate.header("Location")
                        val next = candidate.request.url.resolve(location ?: throw IOException("Invalid redirect"))
                        candidate.close()
                        val target = next?.toString() ?: throw IOException("Invalid redirect")
                        // Every hop is revalidated: no downgrade to file/content or remote cleartext.
                        require(PreviewUrlPolicy.allows(target)) { "Redirect to an unsupported or insecure URL was blocked" }
                        current = target
                        continue
                    }
                    response = candidate
                    break
                }
                val result = response ?: throw IOException("Too many redirects")
                result.use {
                    if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
                    val body = it.body ?: throw IOException("Empty response")
                    if (body.contentLength() > MAX_BYTES) throw IOException("File exceeds the 200 MiB limit")
                    var total = 0L
                    body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            job?.ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > MAX_BYTES) throw IOException("File exceeds the 200 MiB limit")
                            output.write(buffer, 0, read)
                        }
                    }
                    output.flush()
                    total
                }
            }
        } finally {
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }
}
