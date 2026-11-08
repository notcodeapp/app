package com.notcode.mobile.runtime

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

internal class VerifiedDownload(private val cache: File) {
    private val http = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS).followRedirects(true).followSslRedirects(false).build()

    suspend fun fetch(artifact: RuntimeArtifact, destination: File, progress: (String) -> Unit) = withContext(Dispatchers.IO) {
        require(artifact.url.startsWith("https://") && artifact.sha256.matches(Regex("[a-fA-F0-9]{64}")))
        cache.mkdirs()
        val cached = File(cache, artifact.sha256 + ".download")
        if (cached.isFile && cached.length() <= artifact.maxBytes) {
            val hash = MessageDigest.getInstance("SHA-256")
            cached.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    hash.update(buffer, 0, count)
                }
            }
            if (hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }.equals(artifact.sha256, true)) {
                cached.copyTo(destination, overwrite = true); return@withContext
            }
            cached.delete()
        }
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            http.newCall(Request.Builder().url(artifact.url).build()).execute().use { response ->
                check(response.isSuccessful) { "Ошибка загрузки HTTP ${response.code}" }
                val body = requireNotNull(response.body)
                require(body.contentLength() <= artifact.maxBytes) { "Файл превышает допустимый размер" }
                body.byteStream().use { input ->
                    destination.outputStream().use { output ->
                        val buffer = ByteArray(65536)
                        var total = 0L
                        var lastMegabyte = -1L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            check(total <= artifact.maxBytes) { "Превышен лимит загрузки" }
                            output.write(buffer, 0, count); digest.update(buffer, 0, count)
                            val megabytes = total / (1024 * 1024)
                            if (megabytes != lastMegabyte) { progress("Загружено $megabytes МиБ"); lastMegabyte = megabytes }
                        }
                        output.fd.sync()
                    }
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            check(hash.equals(artifact.sha256, true)) { "SHA-256 не совпал. Установка отменена." }
            destination.copyTo(cached, overwrite = true)
        } catch (error: Exception) { destination.delete(); throw error }
    }
}
