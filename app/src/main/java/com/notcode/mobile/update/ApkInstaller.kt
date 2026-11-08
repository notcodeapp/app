package com.notcode.mobile.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/** Downloads a release APK into the cache and hands it to the system installer. */
class ApkInstaller(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.MINUTES)
        .callTimeout(10, TimeUnit.MINUTES)
        .retryOnConnectionFailure(true)
        .build()

    val installedVersionName: String
        get() = runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        }.getOrDefault("")

    val installedVersionCode: Long
        get() = runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
            else info.versionCode.toLong()
        }.getOrDefault(0L)

    fun canInstallFromUnknownSources(): Boolean =
        context.packageManager.canRequestPackageInstalls()

    fun unknownSourcesSettingsIntent(): Intent = Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:${context.packageName}"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Saves the APK to the cache directory and returns the file ready for installation. */
    suspend fun download(rawUrl: String, expectedSize: Long, onProgress: (Long, Long) -> Unit): File =
        withContext(Dispatchers.IO) {
            val url = rawUrl.trim()
            require(url.startsWith("https://")) { "Обновление доступно только по HTTPS" }
            val directory = File(context.cacheDir, "updates").apply { mkdirs() }
            val target = File(directory, "notcode-update.apk")
            val partial = File(directory, "notcode-update.apk.part")
            partial.delete()
            val request = Request.Builder().url(url)
                .header("Accept", "application/vnd.android.package-archive, application/octet-stream")
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("Сервер вернул ${response.code}")
                val declaredLength = response.body?.contentLength() ?: -1L
                val total = listOf(declaredLength, expectedSize).filter { it > 0 }.minOrNull() ?: -1L
                if (total > GithubReleases.MAX_APK_BYTES) {
                    error("Файл слишком большой: ${total / (1024 * 1024)} МиБ")
                }
                val input = requireNotNull(response.body).byteStream()
                var written = 0L
                java.io.FileOutputStream(partial).use { output ->
                    input.use { source ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = source.read(buffer)
                            if (read < 0) break
                            written += read
                            if (written > GithubReleases.MAX_APK_BYTES) {
                                partial.delete()
                                error("Файл превысил лимит 200 МиБ")
                            }
                            output.write(buffer, 0, read)
                            onProgress(written, total)
                        }
                    }
                }
                if (written <= 0) error("Не удалось скачать обновление")
                require(isZip(partial)) { "Скачанный файл не похож на APK" }
                partial.copyTo(target, overwrite = true)
                partial.delete()
                target
            }
        }

    fun installIntent(file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /** Reads the public Releases list of `owner/repo` from the GitHub API. */
    suspend fun fetchReleases(repository: String): List<GithubRelease> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(GithubReleases.releasesUrl(repository))
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("GitHub вернул ${response.code}")
            val body = requireNotNull(response.body).byteStream().use { input ->
                val buffer = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(16 * 1024)
                var total = 0L
                while (true) {
                    val read = input.read(chunk)
                    if (read < 0) break
                    total += read
                    if (total > GithubReleases.MAX_RESPONSE_BYTES) error("Список релизов слишком большой")
                    buffer.write(chunk, 0, read)
                }
                buffer.toString(Charsets.UTF_8.name())
            }
            GithubReleases.parse(json.parseToJsonElement(body))
        }
    }

    private fun isZip(file: File): Boolean = file.inputStream().use { input ->
        val header = ByteArray(4)
        if (input.read(header) != header.size) return false
        header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte() &&
            (header[2] == 3.toByte() || header[2] == 5.toByte()) &&
            (header[3] == 4.toByte() || header[3] == 6.toByte())
    }
}
