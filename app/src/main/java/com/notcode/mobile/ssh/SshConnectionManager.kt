package com.notcode.mobile.ssh

import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import com.notcode.mobile.model.SshAuthType
import com.notcode.mobile.model.SshConnectionProfile
import java.io.File
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable

@Serializable
data class SshCredentials(
    val password: String = "",
    val privateKey: String = "",
    val passphrase: String = "",
)

/** Establishes a pinned SSH session. Unknown server keys are shown to the user before auth. */
class SshConnectionManager(private val knownHostsFile: File) {
    fun connect(
        profile: SshConnectionProfile,
        credentials: SshCredentials,
        confirmHostKey: (fingerprint: String, changed: Boolean) -> Boolean,
    ): Session {
        require(profile.host.isNotBlank() && profile.username.isNotBlank()) { "Укажите сервер и имя пользователя" }
        require(profile.port in 1..65535) { "Порт должен быть от 1 до 65535" }
        val jsch = JSch()
        val repository = FileHostKeyRepository(knownHostsFile)
        jsch.hostKeyRepository = repository
        val session = jsch.getSession(profile.username, profile.host.trim(), profile.port)
        session.setConfig("StrictHostKeyChecking", "ask")
        session.setConfig("PreferredAuthentications", "publickey,password,keyboard-interactive")
        session.setConfig("ConnectTimeout", "15000")
        session.setConfig("ServerAliveInterval", "15000")
        session.setConfig("ServerAliveCountMax", "3")
        session.userInfo = object : UserInfo {
            override fun getPassphrase(): String = credentials.passphrase
            override fun getPassword(): String = credentials.password
            override fun promptPassword(message: String): Boolean = false
            override fun promptPassphrase(message: String): Boolean = false
            override fun promptYesNo(message: String): Boolean {
                val fingerprint = repository.pendingFingerprint ?: return false
                if (repository.pendingStatus !in setOf(HostKeyRepository.NOT_INCLUDED, HostKeyRepository.CHANGED)) return false
                return confirmHostKey(fingerprint, repository.pendingStatus == HostKeyRepository.CHANGED)
            }
            override fun showMessage(message: String) = Unit
        }
        if (profile.authType == SshAuthType.PASSWORD) {
            require(credentials.password.isNotEmpty()) { "Введите пароль SSH" }
            session.setPassword(credentials.password)
        } else {
            require(credentials.privateKey.isNotBlank()) { "Вставьте закрытый SSH-ключ" }
            val key = credentials.privateKey.toByteArray(Charsets.UTF_8)
            val passphrase = credentials.passphrase.toByteArray(Charsets.UTF_8).takeIf { it.isNotEmpty() }
            try {
                jsch.addIdentity("notcode-${profile.id}", key, null, passphrase)
            } finally {
                key.fill(0)
                passphrase?.fill(0)
            }
        }
        try {
            session.connect(15_000)
            return session
        } catch (error: Exception) {
            session.disconnect()
            throw error
        }
    }

    private class FileHostKeyRepository(private val file: File) : HostKeyRepository {
        private val keys = ConcurrentHashMap<String, Pair<String, String>>()
        @Volatile var pendingFingerprint: String? = null
            private set
        @Volatile var pendingStatus: Int = HostKeyRepository.NOT_INCLUDED
            private set

        init {
            file.parentFile?.mkdirs()
            if (file.isFile) file.readLines().forEach { line ->
                val fields = line.trim().split(Regex("\\s+"))
                if (fields.size >= 3 && !fields[0].startsWith("#")) {
                    keys["${fields[0]} ${fields[1]}"] = fields[1] to fields[2]
                }
            }
        }

        override fun check(host: String, key: ByteArray): Int {
            val encoded = Base64.getEncoder().encodeToString(key)
            val type = runCatching { HostKey(host, key).type }.getOrDefault("")
            val existing = keys["$host $type"] ?: keys["* $type"]
            val status = when {
                existing == null -> HostKeyRepository.NOT_INCLUDED
                existing.second == encoded -> HostKeyRepository.OK
                else -> HostKeyRepository.CHANGED
            }
            if (status != HostKeyRepository.OK) {
                val digest = java.security.MessageDigest.getInstance("SHA-256").digest(key)
                pendingFingerprint = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
            } else {
                pendingFingerprint = null
            }
            pendingStatus = status
            return status
        }

        @Synchronized override fun add(hostkey: HostKey, ui: UserInfo?) {
            val host = hostkey.host
            val type = hostkey.type
            val encoded = hostkey.key
            keys["$host $type"] = type to encoded
            val contents = keys.entries.sortedBy { it.key }.joinToString("\n") { (identity, pair) -> "$identity ${pair.second}" } + "\n"
            file.writeText(contents)
            pendingFingerprint = null
            pendingStatus = HostKeyRepository.OK
        }

        override fun remove(host: String, type: String) {
            keys.keys.removeAll { it.startsWith("$host ") && (type.isEmpty() || it.endsWith(" $type")) }
            persist()
        }
        override fun remove(host: String, type: String, key: ByteArray) {
            val encoded = Base64.getEncoder().encodeToString(key)
            keys.remove("$host $type", type to encoded)
            persist()
        }
        override fun getKnownHostsRepositoryID(): String = file.absolutePath
        override fun getHostKey(): Array<HostKey> = keys.entries.mapNotNull { (identity, value) ->
            runCatching { HostKey(identity.substringBeforeLast(' '), Base64.getDecoder().decode(value.second)) }.getOrNull()
        }.toTypedArray()
        override fun getHostKey(host: String?, type: String?): Array<HostKey> = getHostKey().filter {
            (host == null || it.host == host) && (type == null || it.type == type)
        }.toTypedArray()

        @Synchronized private fun persist() {
            file.writeText(keys.entries.sortedBy { it.key }.joinToString("\n") { (identity, pair) -> "$identity ${pair.second}" } + "\n")
        }
    }
}
