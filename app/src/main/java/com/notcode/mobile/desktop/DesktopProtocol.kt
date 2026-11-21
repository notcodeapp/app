package com.notcode.mobile.desktop

import java.net.URI
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

internal const val MAX_TEXT_BYTES = 1024 * 1024
internal const val MAX_JSON_BYTES = 8 * 1024 * 1024
internal val bridgeJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

@Serializable
data class DesktopInvitation(val version: Int, val address: String, val fingerprint: String, val token: String) {
    companion object {
        fun parse(input: String): DesktopInvitation {
            require(input.length <= 4096 && desktopUtf8Bytes(input).size <= 4096) { "Приглашение слишком длинное" }
            val invite = bridgeJson.decodeFromString<DesktopInvitation>(input)
            require(invite.version == 1) { "Неподдерживаемая версия приглашения" }
            validateAddress(invite.address)
            require(Regex("[a-fA-F0-9]{64}").matches(invite.fingerprint)) { "Нужен SHA-256 сертификата DER (64 hex)" }
            require(invite.token.length in 16..1024 && invite.token.all { it.code in 33..126 }) { "Неверный одноразовый токен" }
            return invite.copy(fingerprint = invite.fingerprint.lowercase())
        }
    }
}

fun validateAddress(address: String): URI {
    require(address.length <= 128)
    val uri = URI(address)
    require(uri.scheme == "https" && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
        uri.rawPath.isNullOrEmpty() && uri.port in 1..65535) { "Нужен https://частный-IPv4:порт без пути и параметров" }
    val host = requireNotNull(uri.host)
    require(Regex("(?:0|[1-9][0-9]{0,2})(?:\\.(?:0|[1-9][0-9]{0,2})){3}").matches(host)) { "Допустим только числовой IPv4" }
    val parts = host.split('.').map { it.toInt() }
    require(parts.all { it in 0..255 } && (parts[0] == 10 || parts[0] == 127 ||
        parts[0] == 172 && parts[1] in 16..31 || parts[0] == 192 && parts[1] == 168)) { "Адрес должен быть частным или loopback" }
    require(uri.rawAuthority == "$host:${uri.port}") { "Неканонический адрес" }
    return uri
}

internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(it.toInt() and 255) }
/** Exact UTF-8 boundaries: never replace invalid bytes or unpaired UTF-16 surrogates. */
internal fun desktopUtf8Bytes(text: String): ByteArray {
    val encoded = Charsets.UTF_8.newEncoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .encode(CharBuffer.wrap(text))
    return ByteArray(encoded.remaining()).also { encoded.get(it) }
}

internal fun desktopUtf8Text(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
    .decode(ByteBuffer.wrap(bytes)).toString()

/** JSON escapes can contain invalid UTF-16 even when the wire bytes are valid UTF-8. */
internal fun validateDesktopJsonText(element: JsonElement) {
    when (element) {
        is JsonObject -> element.forEach { (key, value) -> desktopUtf8Bytes(key); validateDesktopJsonText(value) }
        is JsonArray -> element.forEach(::validateDesktopJsonText)
        is JsonPrimitive -> desktopUtf8Bytes(element.content)
    }
}

internal fun textHash(text: String): String = sha256(desktopUtf8Bytes(text))

/** Identity is the explicitly invited leaf certificate, not a public CA or the whole chain. */
internal class DesktopPinTrustManager(private val fingerprint: String) : X509TrustManager {
    init { require(Regex("[a-fA-F0-9]{64}").matches(fingerprint)) }
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
        throw java.security.cert.CertificateException("Client certificates unsupported")
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw java.security.cert.CertificateException("Missing leaf certificate")
        leaf.checkValidity()
        if (!MessageDigest.isEqual(sha256(leaf.encoded).toByteArray(), fingerprint.lowercase().toByteArray())) {
            throw java.security.cert.CertificateException("Desktop certificate fingerprint mismatch")
        }
    }
}

@Serializable
data class DesktopConnection(
    val address: String, val fingerprint: String, val deviceId: String,
    val projectId: String, val projectName: String, val secretAlias: String,
)
@Serializable data class DesktopStatus(val projectId: String, val projectName: String, val running: Boolean)
@Serializable data class DesktopModel(val provider: String, val id: String, val label: String)
@Serializable data class DesktopModels(val models: List<DesktopModel>, val errors: List<String> = emptyList())
@Serializable data class DesktopSession(
    val id: String, val title: String, val provider: String, val model: String,
    val updatedAt: JsonElement = JsonPrimitive(0), val externalId: String? = null,
)
@Serializable data class DesktopMessage(
    val id: String, val sessionId: String, val role: String, val content: String,
    val createdAt: JsonElement = JsonPrimitive(0),
)
@Serializable data class DesktopChat(val session: DesktopSession, val messages: List<DesktopMessage>, val running: Boolean)
@Serializable data class DesktopFileEntry(val name: String, val path: String, val isDir: Boolean)
@Serializable data class DesktopFile(val path: String, val content: String, val hash: String)
@Serializable data class SnapshotFile(val path: String, val hash: String, val size: Long)
@Serializable data class DesktopSnapshot(val projectId: String, val files: List<SnapshotFile>, val skipped: List<String>, val truncated: Boolean = false)
@Serializable internal data class DesktopEvent(val seq: ULong, val kind: String, val sessionId: String? = null, val data: JsonObject)
@Serializable internal data class DesktopEvents(val cursor: ULong, val reset: Boolean, val events: List<DesktopEvent>)
@Serializable internal data class DesktopApproval(val requestId: String, val sessionId: String, val name: String, val arguments: JsonObject)

/** Never use remote paths as OS paths, and never copy auth/config/dependencies. */
internal fun safeDesktopPath(path: String): String {
    require(path.length in 1..1024 && !path.startsWith('/') && !path.contains('\\')) { "Небезопасный путь" }
    desktopUtf8Bytes(path)
    val parts = path.split('/')
    val excluded = setOf(".git", "node_modules", ".gradle", "build", "vendor", ".keys", ".ssh", ".aws", ".claude", ".codex", ".config", ".notcode-attachments", ".notcode-staging")
    require(parts.size <= 64 && parts.all { part -> part.isNotEmpty() && part != "." && part != ".." &&
        part.none { it.code < 32 || it == ':' } && !part.endsWith('.') && !part.endsWith(' ') &&
        part.lowercase() !in excluded && !part.lowercase().startsWith(".env") &&
        !part.contains(".desktop-conflict-") && part == part.trim() &&
        part.lowercase() !in setOf("credentials.json", "auth.json", "token.json", "tokens.json", "credentials", ".npmrc", ".netrc", ".pypirc", ".git-credentials", "id_rsa", "id_ed25519", "id_ecdsa", "id_dsa") &&
        listOf(".key", ".pem", ".p12", ".pfx", ".jks", ".keystore").none { part.lowercase().endsWith(it) } }) { "Путь вне текстовой копии или содержит учётные данные" }
    return path
}

internal fun validateRemoteFile(file: DesktopFile, expectedPath: String): DesktopFile {
    require(file.path == safeDesktopPath(expectedPath)) { "Сервер вернул другой путь" }
    require(desktopUtf8Bytes(file.content).size <= MAX_TEXT_BYTES && !file.content.contains('\u0000')) { "Не текст или больше 1 МиБ" }
    require(Regex("[a-f0-9]{64}").matches(file.hash) && file.hash == textHash(file.content)) { "Неверный SHA-256 файла" }
    return file
}

internal enum class SyncDecision { SAME, PUSH, PULL, CONFLICT, DELETION_UNSUPPORTED }
internal fun syncDecision(baseline: String?, local: String?, remote: String?): SyncDecision = when {
    local == remote -> SyncDecision.SAME
    baseline != null && (local == null || remote == null) -> SyncDecision.DELETION_UNSUPPORTED
    local == baseline -> SyncDecision.PULL
    remote == baseline -> SyncDecision.PUSH
    else -> SyncDecision.CONFLICT
}

internal fun safeWireId(id: String): String {
    require(id.length in 1..256 && id != "." && id != ".." && id.none { it.code < 32 || it == '/' || it == '\\' || it == '%' })
    return id
}

fun desktopCliInstallCommand(adapter: String, version: String): String {
    val packages = mapOf("claude" to "@anthropic-ai/claude-code", "codex" to "@openai/codex", "opencode" to "opencode-ai")
    val pkg = requireNotNull(packages[adapter]) { "Неизвестный официальный CLI" }
    require(version == "latest" || Regex("[0-9]{1,6}\\.[0-9]{1,6}\\.[0-9]{1,6}(?:-[a-zA-Z0-9]+(?:[.-][a-zA-Z0-9]+)*)?").matches(version)) { "Версия: latest или x.y.z[-prerelease]" }
    return "command -v npm >/dev/null || { printf '%s\\n' 'Сначала установите Node.js/npm в Ubuntu'; exit 1; }; npm install --global '$pkg@$version'"
}
