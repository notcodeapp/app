package com.notcode.mobile.ssh

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpATTRS
import com.jcraft.jsch.SftpException
import com.notcode.mobile.filesystem.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.ByteArrayInputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** SFTP-backed project root; all paths exposed to the editor are relative to the selected directory. */
class SshProjectFileSystem private constructor(
    private val session: Session,
    private val sftp: ChannelSftp,
    val rootPath: String,
) : ProjectFileSystem, AutoCloseable {
    private val lock = Mutex()

    override suspend fun list(directory: ProjectPath): List<ProjectEntry> = ioLocked {
        val remote = resolveExisting(directory)
        sftp.ls(remote).asSequence().mapNotNull { item ->
            val entry = item as? ChannelSftp.LsEntry ?: return@mapNotNull null
            if (entry.filename == "." || entry.filename == ".." || entry.attrs.isLink) return@mapNotNull null
            val path = if (directory.isRoot) directory.child(entry.filename) else directory.child(entry.filename)
            ProjectEntry(path, entry.filename, entry.attrs.isDir, entry.attrs.size, entry.attrs.mTime.toLong() * 1000L)
        }.sortedWith(compareByDescending<ProjectEntry> { it.isDirectory }.thenBy { it.name.lowercase(Locale.ROOT) })
            .toList()
    }

    override suspend fun exists(path: ProjectPath): Boolean = ioLocked {
        if (path.isRoot) return@ioLocked true
        runCatching { resolveExisting(path); true }.getOrDefault(false)
    }

    override suspend fun readText(path: ProjectPath, maxBytes: Long): TextDocument = ioLocked {
        val remote = resolveExisting(path)
        val attrs = sftp.lstat(remote)
        require(!attrs.isDir && !attrs.isLink) { "Это не обычный файл: ${path.value}" }
        if (attrs.size > maxBytes) throw ProjectFileTooLargeException(path, maxBytes)
        val bytes = readBytes(remote, maxBytes, path)
        val digest = digest(bytes)
        val text = decodeUtf8(bytes, path)
        TextDocument(path, text, revision(remote, attrs, digest))
    }

    override suspend fun readPreview(path: ProjectPath, maxBytes: Long): FilePreviewDocument = ioLocked {
        require(maxBytes in 0..MAX_PREVIEW_BYTES) { "Preview limit must be between 0 and 4 MiB" }
        val remote = resolveExisting(path)
        val attrs = sftp.lstat(remote)
        require(!attrs.isDir && !attrs.isLink) { "Это не обычный файл: ${path.value}" }
        val output = ByteArrayOutputStream(minOf(maxBytes, 8192L).toInt())
        sftp.get(remote).use { input -> copyBounded(input, output, maxBytes) }
        val bytes = output.toByteArray()
        FilePreviewDocument(path, bytes, revision(remote, attrs), attrs.size > bytes.size)
    }

    override suspend fun writeText(path: ProjectPath, content: String, expectedRevision: FileRevision?): FileRevision = ioLocked {
        require(!path.isRoot) { "Cannot write the project root" }
        val bytes = content.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_FILE_BYTES) { "File exceeds the 16 MiB SSH write limit" }
        val remote = resolveForWrite(path)
        val before = attributesIfPresent(remote)
        if (before?.isLink == true) throw ProjectFileException("Запись по символическим ссылкам запрещена: ${path.value}")
        if (expectedRevision != null) {
            if (before == null || currentRevision(remote, before, path) != expectedRevision) throw ProjectFileConflictException(path)
        } else if (before != null) {
            throw ProjectFileConflictException(path)
        }
        val temp = "$remote.notcode-${java.util.UUID.randomUUID()}.tmp"
        val backup = "$remote.notcode-${java.util.UUID.randomUUID()}.bak"
        val contentDigest = digest(bytes)
        try {
            sftp.put(ByteArrayInputStream(bytes), temp, ChannelSftp.OVERWRITE)
            if (before != null) sftp.rename(remote, backup)
            try {
                sftp.rename(temp, remote)
            } catch (error: Exception) {
                if (before != null) runCatching { sftp.rename(backup, remote) }
                throw error
            }
            if (before != null) runCatching { sftp.rm(backup) }
        } catch (error: Exception) {
            runCatching { sftp.rm(temp) }
            throw error
        } finally { bytes.fill(0) }
        revision(remote, sftp.lstat(remote), contentDigest)
    }

    override suspend fun renameFile(path: ProjectPath, target: ProjectPath, expectedRevision: FileRevision?): FileRevision = ioLocked {
        require(!path.isRoot && !target.isRoot) { "Cannot rename project root" }
        val source = resolveExisting(path)
        val attrs = sftp.lstat(source)
        require(!attrs.isDir && !attrs.isLink) { "Можно переименовать только обычный файл" }
        if (expectedRevision != null && !matchesRevision(source, attrs, path, expectedRevision)) throw ProjectFileConflictException(path)
        val destination = resolveForWrite(target)
        if (attributesIfPresent(destination) != null) throw ProjectFileConflictException(target)
        sftp.rename(source, destination)
        revision(destination, sftp.lstat(destination))
    }

    override suspend fun deleteFile(path: ProjectPath, expectedRevision: FileRevision?) = ioLocked {
        require(!path.isRoot) { "Cannot delete project root" }
        val remote = resolveExisting(path)
        val attrs = sftp.lstat(remote)
        require(!attrs.isDir && !attrs.isLink) { "Можно удалить только обычный файл" }
        if (expectedRevision != null && !matchesRevision(remote, attrs, path, expectedRevision)) throw ProjectFileConflictException(path)
        sftp.rm(remote)
    }

    suspend fun execute(command: String): String = ioLocked {
        require(command.isNotBlank() && command.length <= 8192) { "Команда пустая или слишком длинная" }
        val escapedRoot = "'${rootPath.replace("'", "'\\''")}'"
        val channel = session.openChannel("exec") as ChannelExec
        val stdout = ByteArrayOutputStream()
        val stderr = BoundedOutputStream(MAX_COMMAND_ERROR_OUTPUT)
        channel.setCommand("cd $escapedRoot && $command")
        channel.setInputStream(null)
        channel.setErrStream(stderr, true)
        val input = channel.inputStream
        try {
            channel.connect(10_000)
            val started = System.currentTimeMillis()
            while (!channel.isClosed || input.available() > 0) {
                while (input.available() > 0) {
                    val chunk = ByteArray(minOf(input.available(), 8192))
                    val count = input.read(chunk)
                    if (count <= 0) break
                    val remaining = (MAX_COMMAND_OUTPUT - stdout.size()).coerceAtLeast(0)
                    if (remaining > 0) stdout.write(chunk, 0, minOf(count, remaining))
                    chunk.fill(0)
                }
                if (System.currentTimeMillis() - started > 180_000) throw ProjectFileException("SSH-команда выполняется дольше 3 минут")
                if (!channel.isClosed) Thread.sleep(40)
            }
            val output = (stdout.toString(Charsets.UTF_8.name()) + stderr.text()).take(MAX_COMMAND_OUTPUT)
            val status = channel.exitStatus
            "${output.ifBlank { "(команда завершилась без вывода)" }}\n[код завершения: $status]"
        } finally {
            channel.disconnect()
            input.close()
        }
    }

    override fun close() {
        runCatching { sftp.disconnect() }
        runCatching { session.disconnect() }
    }

    private fun resolveExisting(path: ProjectPath): String {
        if (path.isRoot) return rootPath
        val candidate = join(path)
        if (sftp.lstat(candidate).isLink) throw ProjectFileException("Символические ссылки не входят в проект")
        val resolved = sftp.realpath(candidate)
        if (!isInsideRoot(resolved)) throw ProjectFileException("Путь выходит за выбранную папку проекта")
        return resolved
    }

    private fun resolveForWrite(path: ProjectPath): String {
        val candidate = join(path)
        val parent = sftp.realpath(candidate.substringBeforeLast('/').ifEmpty { "/" })
        if (!isInsideRoot(parent)) throw ProjectFileException("Папка назначения выходит за корень проекта")
        return "$parent/${path.name}"
    }

    private fun join(path: ProjectPath): String = if (rootPath == "/") "/${path.value}" else "$rootPath/${path.value}"
    private fun isInsideRoot(path: String): Boolean = path == rootPath || path.startsWith(if (rootPath == "/") "/" else "$rootPath/")

    private fun attributesIfPresent(path: String): SftpATTRS? = try { sftp.lstat(path) } catch (error: SftpException) {
        if (error.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) null else throw error
    }

    private fun readBytes(remote: String, maxBytes: Long, path: ProjectPath): ByteArray {
        val output = ByteArrayOutputStream()
        sftp.get(remote).use { input ->
            copyBounded(input, output, maxBytes)
            if (input.read() >= 0) throw ProjectFileTooLargeException(path, maxBytes)
        }
        return output.toByteArray()
    }

    private fun revision(remote: String, attrs: SftpATTRS): FileRevision = FileRevision(
        "ssh://${session.host}:${session.port}$remote",
        attrs.size,
        attrs.mTime.toLong() * 1000L,
    )

    private fun revision(remote: String, attrs: SftpATTRS, digest: String): FileRevision = FileRevision(
        "ssh://${session.host}:${session.port}$remote#$digest",
        attrs.size,
        attrs.mTime.toLong() * 1000L,
    )

    private fun currentRevision(remote: String, attrs: SftpATTRS, path: ProjectPath): FileRevision {
        if (attrs.size > MAX_FILE_BYTES) throw ProjectFileTooLargeException(path, MAX_FILE_BYTES)
        val bytes = readBytes(remote, MAX_FILE_BYTES, path)
        return try { revision(remote, attrs, digest(bytes)) } finally { bytes.fill(0) }
    }

    private fun matchesRevision(remote: String, attrs: SftpATTRS, path: ProjectPath, expected: FileRevision): Boolean {
        return if (expected.documentUri.contains('#')) currentRevision(remote, attrs, path) == expected
        else revision(remote, attrs) == expected
    }

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(Locale.ROOT, it) }

    private fun decodeUtf8(bytes: ByteArray, path: ProjectPath): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    } catch (_: java.nio.charset.CharacterCodingException) {
        throw ProjectBinaryFileException(path)
    } finally { bytes.fill(0) }

    private suspend fun <T> ioLocked(block: () -> T): T = withContext(Dispatchers.IO) {
        lock.withLock {
            if (!session.isConnected) throw ProjectFileException("SSH-соединение потеряно")
            block()
        }
    }

    companion object {
        const val MAX_PREVIEW_BYTES = 4L * 1024 * 1024
        private const val MAX_FILE_BYTES = 16L * 1024 * 1024
        private const val MAX_COMMAND_OUTPUT = 128 * 1024
        private const val MAX_COMMAND_ERROR_OUTPUT = 64 * 1024

        private class BoundedOutputStream(private val limit: Int) : OutputStream() {
            private val buffer = ByteArrayOutputStream()
            override fun write(value: Int) { if (buffer.size() < limit) buffer.write(value) }
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                val remaining = (limit - buffer.size()).coerceAtLeast(0)
                if (remaining > 0) buffer.write(bytes, offset, minOf(length, remaining))
            }
            fun text(): String = buffer.toString(Charsets.UTF_8.name())
        }

        fun open(session: Session, remoteDirectory: String): SshProjectFileSystem {
            val sftp = session.openChannel("sftp") as ChannelSftp
            try {
                sftp.connect(10_000)
                val supplied = remoteDirectory.trim().ifBlank { "/" }
                require(supplied.startsWith("/")) { "Удалённая папка должна быть абсолютным путём, например /home/user/project" }
                val root = sftp.realpath(supplied)
                require(sftp.stat(root).isDir) { "Указанный путь на сервере не является папкой" }
                return SshProjectFileSystem(session, sftp, root)
            } catch (error: Exception) {
                sftp.disconnect()
                session.disconnect()
                throw error
            }
        }

        private fun copyBounded(input: InputStream, output: ByteArrayOutputStream, maxBytes: Long) {
            val buffer = ByteArray(8192)
            var total = 0L
            while (total < maxBytes) {
                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), maxBytes - total).toInt())
                if (count < 0) break
                output.write(buffer, 0, count)
                total += count
            }
            buffer.fill(0)
        }
    }
}
