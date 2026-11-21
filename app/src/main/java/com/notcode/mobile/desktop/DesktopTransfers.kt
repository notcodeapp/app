package com.notcode.mobile.desktop

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.nio.file.Files
import com.notcode.mobile.data.StoredChatItem
import com.notcode.mobile.data.StoredConversation
import com.notcode.mobile.data.StoredTurn
import com.notcode.mobile.filesystem.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlin.coroutines.coroutineContext

@Serializable
data class DesktopChatSource(
    val address: String, val fingerprint: String, val projectId: String, val projectName: String,
    val sessionId: String, val provider: String, val model: String, val externalId: String? = null,
)

data class DesktopChatBundle(val source: DesktopChatSource, val chat: DesktopChat)

/** IDs do not depend on time, title, device token, model or destination project. */
fun importedDesktopChat(bundle: DesktopChatBundle): StoredConversation {
    val source = bundle.source
    listOf(source.address, source.fingerprint, source.projectId, source.projectName, source.sessionId,
        source.provider, source.model, source.externalId.orEmpty(), bundle.chat.session.id,
        bundle.chat.session.title, bundle.chat.session.provider, bundle.chat.session.model,
        bundle.chat.session.externalId.orEmpty()).forEach(::desktopUtf8Bytes)
    bundle.chat.messages.forEach { message ->
        listOf(message.id, message.sessionId, message.role, message.content).forEach(::desktopUtf8Bytes)
    }
    val stable = "desktop:${source.fingerprint}:${source.projectId}:${source.sessionId}"
    val id = UUID.nameUUIDFromBytes(desktopUtf8Bytes(stable)).toString()
    val messages = bundle.chat.messages.filter { it.role in setOf("user", "assistant") }
    require(bundle.chat.session.id == source.sessionId && messages.size <= 400 && messages.sumOf { desktopUtf8Bytes(it.content).size.toLong() } <= 3 * 1024 * 1024)
    require(messages.all { it.sessionId == source.sessionId } && messages.map { it.id }.distinct().size == messages.size)
    return StoredConversation(id, bundle.chat.session.title.take(120), System.currentTimeMillis(),
        items = messages.map { StoredChatItem(if (it.role == "user") "Вы" else "ИИ", it.content,
            UUID.nameUUIDFromBytes(desktopUtf8Bytes("$stable:${it.id}")).toString()) },
        turns = messages.takeLast(100).map { StoredTurn(it.role, it.content) }, desktopSource = source)
}

/** Repeat import merges new stable message IDs; it never erases a local continuation. */
fun mergeDesktopImport(existing: StoredConversation?, imported: StoredConversation): StoredConversation {
    if (existing == null) return imported
    require(existing.id == imported.id && existing.desktopSource?.fingerprint == imported.desktopSource?.fingerprint &&
        existing.desktopSource?.projectId == imported.desktopSource?.projectId && existing.desktopSource?.sessionId == imported.desktopSource?.sessionId)
    val messages = existing.items.toMutableList()
    imported.items.forEach { item ->
        val index = messages.indexOfFirst { it.id == item.id }
        if (index >= 0) messages[index] = item else messages += item
    }
    require(messages.size <= 400 && messages.sumOf { desktopUtf8Bytes(it.text).size.toLong() } <= 3 * 1024 * 1024)
    return existing.copy(items = messages, updatedAt = imported.updatedAt,
        turns = messages.filter { it.role == "Вы" || it.role == "ИИ" }.takeLast(100)
            .map { StoredTurn(if (it.role == "Вы") "user" else "assistant", it.text) })
}

@Serializable
internal data class DesktopCopyManifest(
    val version: Int = 1, val fingerprint: String, val projectId: String, val projectName: String,
    val baseline: Map<String, String> = emptyMap(), val skipped: List<String> = emptyList(),
    val complete: Boolean = false,
)

data class DesktopCopy(val directory: File, val projectName: String, val skipped: List<String>)
data class DesktopSyncResult(val uploaded: Int, val downloaded: Int, val conflicts: List<String>, val skipped: List<String>)

/** The IDE strips a UTF-8 BOM on read; sync must still compare/transfer the exact original bytes. */
internal fun preserveDesktopWorkspaceBom(document: TextDocument): TextDocument {
    val rawHash = document.revision.documentUri.substringAfterLast("#sha256=")
    if (textHash(document.text) == rawHash) return document
    val withBom = "\uFEFF${document.text}"
    require(textHash(withBom) == rawHash) { "Содержимое рабочей копии не совпало с хешем ревизии" }
    return document.copy(text = withBom)
}

/** No external CAS against PTY/PRoot: existing local files are NEVER replaced. */
internal fun desktopTransferDecision(baseline: String?, local: String?, remote: String?): SyncDecision =
    syncDecision(baseline, local, remote).let { if (it == SyncDecision.PULL && local != null) SyncDecision.CONFLICT else it }

private const val MAX_COPY_BYTES = 120L * 1024 * 1024
private const val MAX_LOCAL_ENTRIES = 14_000
private const val MAX_COPY_FILES = 10_000

internal data class DesktopLocalIdentity(val device: Long, val inode: Long, val size: Long, val modified: Long, val changed: Long)
internal data class DesktopLocalText(val text: String, val hash: String, val size: Long, val identity: DesktopLocalIdentity)
internal data class DesktopLocalSnapshot(
    val files: Map<String, DesktopLocalText>, val skipped: List<String>, val blocked: Set<String>,
    val bytes: Long, val entries: Int,
) {
    fun blocks(path: String): Boolean = blocked.any { path == it || path.startsWith("$it/") }
}

/**
 * Desktop-only Android/Linux I/O. Every component is opened NOFOLLOW relative to a retained
 * directory descriptor. /proc/self/fd names only our live, owned descriptors (Os has no openat).
 * No workspace pathname/canonicalization check authorizes reading file contents.
 */
internal class DesktopLocalIo private constructor(private val directory: ParcelFileDescriptor) : Closeable {
    private val rootedPath: String get() = "/proc/self/fd/${directory.fd}"
    private fun childPath(name: String): String = "$rootedPath/$name"

    companion object {
        // O_DIRECTORY is absent from the public OsConstants; 0x10000 is its stable Linux ABI value.
        private val directoryFlags = OsConstants.O_RDONLY or 0x10000 or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC
        private val readFlags = OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC or OsConstants.O_NONBLOCK
        private fun openFd(path: String, flags: Int, mode: Int = 0): ParcelFileDescriptor {
            val original = Os.open(path, flags, mode)
            return try { ParcelFileDescriptor.dup(original) } finally { Os.close(original) }
        }
        private fun parts(path: String): List<String> {
            require(path.isNotEmpty() && !path.startsWith('/') && !path.contains('\\') && path.length <= 1200)
            desktopUtf8Bytes(path)
            return path.split('/').also { parts ->
                require(parts.size <= 66 && parts.all { it.isNotEmpty() && it != "." && it != ".." && it.none { c -> c.code < 32 || c == ':' } })
            }
        }
        private fun identity(stat: StructStat) = DesktopLocalIdentity(stat.st_dev, stat.st_ino, stat.st_size, stat.st_mtime, stat.st_ctime)
        private fun sameInode(left: StructStat, right: StructStat) = left.st_dev == right.st_dev && left.st_ino == right.st_ino
        private fun regularSingleLink(stat: StructStat) {
            require(OsConstants.S_ISREG(stat.st_mode) && stat.st_nlink == 1L) { "Не обычный файл с единственной ссылкой (symlink/hardlink запрещён)" }
        }
        private fun stable(before: StructStat, after: StructStat) {
            regularSingleLink(after)
            check(identity(before) == identity(after) && before.st_mode == after.st_mode) { "Файл изменился во время безопасного чтения" }
        }
        private fun <T> interrupted(block: () -> T): T {
            while (true) {
                try { return block() } catch (error: ErrnoException) { if (error.errno != OsConstants.EINTR) throw error }
            }
        }
        private fun boundedBytes(fd: ParcelFileDescriptor, limit: Int): ByteArray {
            val before = Os.fstat(fd.fileDescriptor)
            regularSingleLink(before)
            require(before.st_size in 0..limit.toLong()) { "Файл больше разрешённого лимита: $limit байт" }
            val output = ByteArrayOutputStream(minOf(limit, 8192))
            val buffer = ByteArray(8192)
            try {
                while (true) {
                    regularSingleLink(Os.fstat(fd.fileDescriptor))
                    val count = interrupted { Os.read(fd.fileDescriptor, buffer, 0, minOf(buffer.size, limit - output.size() + 1)) }
                    if (count == 0) break
                    require(output.size().toLong() + count <= limit) { "Файл вырос больше разрешённого лимита" }
                    output.write(buffer, 0, count)
                }
                stable(before, Os.fstat(fd.fileDescriptor))
                return output.toByteArray()
            } finally { buffer.fill(0) }
        }
        private fun readOpened(fd: ParcelFileDescriptor): DesktopLocalText {
            val before = Os.fstat(fd.fileDescriptor)
            regularSingleLink(before)
            val bytes = boundedBytes(fd, MAX_TEXT_BYTES)
            try {
                require(bytes.none { it == 0.toByte() }) { "Бинарный файл" }
                val text = desktopUtf8Text(bytes) // Deliberately preserves a UTF-8 BOM.
                stable(before, Os.fstat(fd.fileDescriptor))
                return DesktopLocalText(text, sha256(bytes), bytes.size.toLong(), identity(before))
            } finally { bytes.fill(0) }
        }

        /** Caller supplies the trusted app-private anchor, not a remote/user-provided root. */
        fun open(root: File): DesktopLocalIo {
            val absolute = root.absoluteFile
            require(absolute.toPath().normalize().toFile() == absolute)
            var current = DesktopLocalIo(openFd("/", directoryFlags))
            try {
                for (part in absolute.path.replace('\\', '/').split('/').filter { it.isNotEmpty() }) {
                    require(part != "." && part != ".." && !part.contains('\u0000'))
                    val next = current.openChildDirectory(part, false)
                    current.close()
                    current = next
                }
                return current
            } catch (error: Throwable) { current.close(); throw error }
        }
    }

    private fun openChildDirectory(name: String, create: Boolean): DesktopLocalIo {
        val path = childPath(name)
        val fd = try { openFd(path, directoryFlags) } catch (error: ErrnoException) {
            if (!create || error.errno != OsConstants.ENOENT) throw error
            try { Os.mkdir(path, 448) } catch (mkdirError: ErrnoException) { if (mkdirError.errno != OsConstants.EEXIST) throw mkdirError }
            openFd(path, directoryFlags) // A mkdir race never authorizes following a symlink.
        }
        return try {
            check(OsConstants.S_ISDIR(Os.fstat(fd.fileDescriptor).st_mode))
            DesktopLocalIo(fd)
        } catch (error: Throwable) { fd.close(); throw error }
    }

    fun childDirectory(path: String, create: Boolean = false): DesktopLocalIo {
        var current = DesktopLocalIo(ParcelFileDescriptor.dup(directory.fileDescriptor))
        try {
            for (part in parts(path)) {
                val next = current.openChildDirectory(part, create)
                current.close()
                current = next
            }
            return current
        } catch (error: Throwable) { current.close(); throw error }
    }

    private fun <T> withParent(path: String, create: Boolean = false, block: (DesktopLocalIo, String) -> T): T {
        val components = parts(path)
        val parent = if (components.size == 1) DesktopLocalIo(ParcelFileDescriptor.dup(directory.fileDescriptor))
            else childDirectory(components.dropLast(1).joinToString("/"), create)
        return parent.use { block(it, components.last()) }
    }

    fun isEmpty(): Boolean = Files.newDirectoryStream(File(rootedPath).toPath()).use { !it.iterator().hasNext() }

    fun readText(path: String): DesktopLocalText {
        safeDesktopPath(path)
        return withParent(path) { parent, name -> openFd(parent.childPath(name), readFlags).use(::readOpened) }
    }

    fun readBytesOrNull(path: String, limit: Int): ByteArray? {
        require(limit in 0..MAX_JSON_BYTES)
        return withParent(path) { parent, name ->
            val fd = try { openFd(parent.childPath(name), readFlags) } catch (error: ErrnoException) {
                if (error.errno == OsConstants.ENOENT) return@withParent null else throw error
            }
            fd.use { boundedBytes(it, limit) }
        }
    }

    fun checkCurrent(path: String, expected: DesktopLocalText) {
        check(readText(path) == expected) { "Рабочая копия изменилась; baseline не изменён" }
    }

    private fun unlinkOwnTemporary(path: String, expected: StructStat) {
        try {
            if (sameInode(Os.lstat(path), expected)) Os.remove(path)
        } catch (error: ErrnoException) { if (error.errno != OsConstants.ENOENT) throw error }
    }

    /** link(2), unlike move/rename, atomically fails if ANY target entry already exists. */
    private fun publish(name: String, bytes: ByteArray, replaceManifest: Boolean) {
        val temporaryPath = childPath(".desktop-${UUID.randomUUID()}.tmp")
        val targetPath = childPath(name)
        val flags = OsConstants.O_RDWR or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC or OsConstants.O_NONBLOCK
        openFd(temporaryPath, flags, 384).use { temporary ->
            val original = Os.fstat(temporary.fileDescriptor)
            try {
                regularSingleLink(original)
                var offset = 0
                while (offset < bytes.size) {
                    regularSingleLink(Os.fstat(temporary.fileDescriptor))
                    val count = interrupted { Os.write(temporary.fileDescriptor, bytes, offset, bytes.size - offset) }
                    check(count > 0)
                    offset += count
                }
                Os.fsync(temporary.fileDescriptor)
                Os.lseek(temporary.fileDescriptor, 0, OsConstants.SEEK_SET)
                val written = boundedBytes(temporary, bytes.size)
                try { check(written.contentEquals(bytes)) { "Временный файл изменился" } } finally { written.fill(0) }
                check(sameInode(Os.lstat(temporaryPath), original)) { "Временный путь был заменён" }
                if (replaceManifest) Os.rename(temporaryPath, targetPath) else Os.link(temporaryPath, targetPath)
                unlinkOwnTemporary(temporaryPath, original)
                // Never read a substituted target: compare the OPENED inode to our exclusive temp.
                openFd(targetPath, readFlags).use { target ->
                    val stat = Os.fstat(target.fileDescriptor)
                    regularSingleLink(stat)
                    check(sameInode(stat, original)) { "Созданный путь был заменён" }
                }
                Os.lseek(temporary.fileDescriptor, 0, OsConstants.SEEK_SET)
                val verified = boundedBytes(temporary, bytes.size)
                try { check(verified.contentEquals(bytes)) { "Созданный файл изменился" } } finally { verified.fill(0) }
                Os.fsync(directory.fileDescriptor)
            } finally { unlinkOwnTemporary(temporaryPath, original) }
        }
    }

    fun createText(path: String, content: String) {
        safeDesktopPath(path)
        createTextEntry(path, content)
    }

    fun createConflict(original: String, content: String): String {
        val path = "${safeDesktopPath(original)}.desktop-conflict-${UUID.randomUUID()}"
        createTextEntry(path, content)
        return path
    }

    private fun createTextEntry(path: String, content: String) {
        val bytes = desktopUtf8Bytes(content)
        try {
            require(bytes.size <= MAX_TEXT_BYTES && !content.contains('\u0000'))
            withParent(path, true) { parent, name -> parent.publish(name, bytes, false) }
        } finally { bytes.fill(0) }
    }

    /** Replacement is restricted to Desktop's sibling metadata, NEVER the working tree. */
    fun checkpoint(bytes: ByteArray) {
        require(bytes.size <= MAX_JSON_BYTES)
        desktopUtf8Text(bytes)
        publish("desktop-sync.json", bytes, true)
    }

    fun snapshot(checkActive: () -> Unit = {}): DesktopLocalSnapshot {
        val files = linkedMapOf<String, DesktopLocalText>()
        val skipped = mutableListOf<String>()
        val blocked = linkedSetOf<String>()
        var total = 0L
        var seen = 0
        fun skip(path: String, reason: String) { blocked += path; skipped += "$path ($reason)" }
        fun addBytes(size: Long) {
            require(size >= 0 && size <= MAX_COPY_BYTES - total) { "Рабочая копия больше 120 МиБ" }
            total += size
        }
        fun visit(parent: DesktopLocalIo, prefix: String) {
            Files.newDirectoryStream(File(parent.rootedPath).toPath()).use { entries ->
                for (entry in entries) {
                    checkActive()
                    require(++seen <= MAX_LOCAL_ENTRIES) { "Слишком много файлов/каталогов в копии" }
                    val name = entry.fileName.toString()
                    val path = if (prefix.isEmpty()) name else "$prefix/$name"
                    if (runCatching { safeDesktopPath(path) }.isFailure) {
                        // Existing conflict copies also consume the aggregate storage budget.
                        if (name.contains(".desktop-conflict-")) {
                            val size = try {
                                openFd(parent.childPath(name), readFlags).use { fd ->
                                    val stat = Os.fstat(fd.fileDescriptor)
                                    regularSingleLink(stat)
                                    stat.st_size
                                }
                            } catch (error: Exception) { skip(path, "небезопасная conflict-копия: ${error.message}"); continue }
                            addBytes(size)
                        }
                        skip(path, "исключён безопасностью клиента")
                        continue
                    }
                    val fd = try { openFd(parent.childPath(name), readFlags) } catch (error: Exception) {
                        skip(path, "небезопасный/недоступный путь: ${error.message}"); continue
                    }
                    fd.use fileUse@{
                        val stat = Os.fstat(fd.fileDescriptor)
                        if (OsConstants.S_ISDIR(stat.st_mode)) {
                            val child = try { parent.openChildDirectory(name, false) } catch (error: Exception) {
                                skip(path, "каталог недоступен: ${error.message}"); return@fileUse
                            }
                            child.use childUse@{
                                if (!sameInode(stat, Os.fstat(child.directory.fileDescriptor))) {
                                    skip(path, "каталог был заменён"); return@childUse
                                }
                                visit(child, path)
                            }
                        } else {
                            val document = try { readOpened(fd) } catch (error: Exception) {
                                skip(path, "небезопасный/бинарный/большой файл: ${error.message}"); return@fileUse
                            }
                            require(files.size < MAX_COPY_FILES) { "Текстовых файлов больше 10000" }
                            addBytes(document.size)
                            files[path] = document
                        }
                    }
                }
            }
        }
        visit(this, "")
        return DesktopLocalSnapshot(files, skipped, blocked, total, seen)
    }

    override fun close() = directory.close()
}

/** Separate manifest: Desktop has hash-CAS but no delete endpoint. Do not call SAF synchronize. */
internal class DesktopTransfers(private val privateFiles: File) {
    private fun relativeParent(connection: DesktopConnection): String =
        "desktop-copies/${sha256(desktopUtf8Bytes("${connection.fingerprint}:${connection.projectId}")).take(40)}"
    // Canonicalize ONLY the trusted Android app-private anchor (/data/user/0 -> /data/data).
    private fun parent(connection: DesktopConnection) = File(privateFiles.canonicalFile, relativeParent(connection))
    private fun directory(connection: DesktopConnection) = File(parent(connection), "work")
    private fun storage(connection: DesktopConnection, create: Boolean): DesktopLocalIo? {
        return try {
            DesktopLocalIo.open(privateFiles.canonicalFile).use { it.childDirectory(relativeParent(connection), create) }
        } catch (error: ErrnoException) { if (!create && error.errno == OsConstants.ENOENT) null else throw error }
    }
    private fun load(storage: DesktopLocalIo, connection: DesktopConnection): DesktopCopyManifest? {
        val bytes = storage.readBytesOrNull("desktop-sync.json", MAX_JSON_BYTES) ?: return null
        val manifest = try {
            val element = bridgeJson.parseToJsonElement(desktopUtf8Text(bytes)).also(::validateDesktopJsonText)
            bridgeJson.decodeFromJsonElement(DesktopCopyManifest.serializer(), element)
        } finally { bytes.fill(0) }
        require(manifest.version == 1 && manifest.fingerprint == connection.fingerprint && manifest.projectId == connection.projectId)
        require(manifest.baseline.size <= MAX_COPY_FILES && manifest.skipped.size <= 28_000)
        manifest.baseline.forEach { (path, hash) -> safeDesktopPath(path); require(Regex("[a-f0-9]{64}").matches(hash)) }
        return manifest
    }
    private fun checkpoint(storage: DesktopLocalIo, manifest: DesktopCopyManifest) {
        require(manifest.baseline.size <= MAX_COPY_FILES && manifest.skipped.size <= 28_000)
        val element = bridgeJson.encodeToJsonElement(DesktopCopyManifest.serializer(), manifest)
        validateDesktopJsonText(element)
        val bytes = desktopUtf8Bytes(element.toString())
        try { storage.checkpoint(bytes) } finally { bytes.fill(0) }
    }
    private fun describe(connection: DesktopConnection, manifest: DesktopCopyManifest) = DesktopCopy(directory(connection), manifest.projectName,
        manifest.skipped + if (manifest.complete) emptyList() else listOf("Импорт был прерван: копия неполная. Выполните Sync; существующие файлы не перезаписываются."))

    suspend fun existingCopy(connection: DesktopConnection): DesktopCopy? = withContext(Dispatchers.IO) {
        storage(connection, false)?.use { store -> load(store, connection)?.let { describe(connection, it) } }
    }

    suspend fun copy(api: DesktopApi, approveSkipped: suspend (List<String>) -> Boolean): DesktopCopy = withContext(Dispatchers.IO) {
        val connection = api.connection
        requireNotNull(storage(connection, true)).use { store ->
            val previous = load(store, connection)
            if (previous != null) return@withContext describe(connection, previous)
            val snapshot = api.snapshot()
            require(snapshot.projectId == connection.projectId)
            val skipped = snapshot.skipped.toMutableList()
            if (snapshot.truncated) skipped += "Снапшот ПК обрезан лимитами: часть файлов не попала в копию. Выполните Sync позже."
            val files = snapshot.files.filter { entry ->
                if (runCatching { safeDesktopPath(entry.path) }.isFailure) { skipped += "${entry.path} (исключён безопасностью клиента)"; false } else true
            }
            require(files.sumOf { it.size } <= MAX_COPY_BYTES) { "Текстовая копия больше 120 МиБ" }
            if (skipped.isNotEmpty()) check(approveSkipped(skipped)) { "Копирование отменено: неполная текстовая копия" }
            store.childDirectory("work", true).use { local ->
                check(local.isEmpty()) { "Незавершённая копия уже содержит файлы. Они не перезаписаны; удалите или перенесите её вручную." }
                var manifest = DesktopCopyManifest(fingerprint = connection.fingerprint, projectId = connection.projectId,
                    projectName = connection.projectName, skipped = skipped)
                checkpoint(store, manifest)
                var total = 0L
                for (entry in files) {
                    coroutineContext.ensureActive()
                    val remote = api.file(entry.path)
                    require(remote.hash == entry.hash && desktopUtf8Bytes(remote.content).size.toLong() == entry.size) {
                        "ПК изменился во время копирования: ${entry.path}. Повторите sync."
                    }
                    require(entry.size <= MAX_COPY_BYTES - total)
                    local.createText(entry.path, remote.content)
                    check(local.readText(entry.path).hash == remote.hash)
                    total += entry.size
                    val next = manifest.copy(baseline = manifest.baseline + (entry.path to remote.hash))
                    checkpoint(store, next)
                    manifest = next
                }
                checkpoint(store, manifest.copy(complete = !snapshot.truncated))
                DesktopCopy(directory(connection), connection.projectName, skipped)
            }
        }
    }

    suspend fun synchronize(api: DesktopApi): DesktopSyncResult = withContext(Dispatchers.IO) {
        val connection = api.connection
        val store = storage(connection, false) ?: error("Сначала скопируйте проект")
        store.use {
            var manifest = load(store, connection) ?: error("Сначала скопируйте проект")
            store.childDirectory("work").use { local ->
                val snapshot = api.snapshot()
                require(snapshot.projectId == connection.projectId)
                val skipped = snapshot.skipped.toMutableList()
                if (snapshot.truncated) skipped += "Снапшот ПК обрезан лимитами: новые файлы ПК могли не попасть в sync."
                val excludedRemote = linkedSetOf<String>()
                val remote = snapshot.files.filter {
                    if (runCatching { safeDesktopPath(it.path) }.isFailure) { skipped += it.path; excludedRemote += it.path; false } else true
                }.associateBy { it.path }
                require(remote.values.sumOf { it.size } <= MAX_COPY_BYTES) { "Текстовая копия ПК больше 120 МиБ" }
                val context = coroutineContext
                val observed = local.snapshot { context.ensureActive() }
                skipped += observed.skipped
                var total = observed.bytes
                var entries = observed.entries
                val conflicts = mutableListOf<String>()
                var uploaded = 0
                var downloaded = 0
                fun recordBaseline(name: String, hash: String) {
                    val next = manifest.copy(baseline = manifest.baseline + (name to hash))
                    checkpoint(store, next)
                    manifest = next
                }
                for (name in (manifest.baseline.keys + remote.keys + observed.files.keys).sorted()) {
                    coroutineContext.ensureActive()
                    if (name in excludedRemote || observed.blocks(name)) {
                        conflicts += "$name: небезопасный/бинарный/большой/исключённый путь; сохранён без изменений; baseline не изменён"
                        continue
                    }
                    val ours = observed.files[name]
                    val theirs = remote[name]
                    val localHash = ours?.hash
                    try {
                        when (desktopTransferDecision(manifest.baseline[name], localHash, theirs?.hash)) {
                            SyncDecision.SAME -> {
                                if (localHash != null) {
                                    check(api.file(name).hash == localHash)
                                    local.checkCurrent(name, checkNotNull(ours))
                                    recordBaseline(name, localHash)
                                }
                            }
                            SyncDecision.PUSH -> {
                                check(ours != null)
                                local.checkCurrent(name, ours)
                                if (theirs != null) check(api.file(name).hash == theirs.hash)
                                // Recheck safely AFTER the remote network round trip.
                                local.checkCurrent(name, ours)
                                val written = api.putFile(name, ours.text, theirs?.hash)
                                check(written.hash == localHash)
                                local.checkCurrent(name, ours)
                                recordBaseline(name, written.hash)
                                uploaded++
                            }
                            SyncDecision.PULL -> {
                                check(theirs != null && ours == null)
                                val document = api.file(name)
                                val bytes = desktopUtf8Bytes(document.content).size.toLong()
                                check(document.hash == theirs.hash && bytes == theirs.size)
                                require(bytes <= MAX_COPY_BYTES - total && entries < MAX_LOCAL_ENTRIES) { "Рабочая копия достигла лимита; файл оставлен на ПК" }
                                // No pathname exists/CAS check: link(2) is the atomic absence guard.
                                local.createText(name, document.content)
                                total += bytes
                                entries++
                                check(local.readText(name).hash == document.hash)
                                recordBaseline(name, document.hash)
                                downloaded++
                            }
                            SyncDecision.CONFLICT -> {
                                if (theirs != null) {
                                    val document = api.file(name)
                                    val bytes = desktopUtf8Bytes(document.content).size.toLong()
                                    check(document.hash == theirs.hash && bytes == theirs.size)
                                    require(bytes <= MAX_COPY_BYTES - total && entries < MAX_LOCAL_ENTRIES) {
                                        "Обе исходные версии сохранены на своих устройствах; лимит не позволяет дополнительную conflict-копию"
                                    }
                                    val conflict = local.createConflict(name, document.content)
                                    total += bytes
                                    entries++
                                    skipped += conflict
                                    conflicts += "$name: оригинал не изменён; версия ПК → $conflict; baseline не изменён. Требуется ручное разрешение конфликта."
                                } else conflicts += "$name: конфликт; исходные файлы и baseline не изменены; требуется ручное решение"
                            }
                            SyncDecision.DELETION_UNSUPPORTED -> conflicts += "$name: удаление требует ручного решения; API ПК не поддерживает delete. Ничего не удалено/восстановлено."
                        }
                    } catch (error: kotlinx.coroutines.CancellationException) { throw error }
                    catch (error: Exception) {
                        if (error is DesktopHttpException && error.status == 401) throw error
                        conflicts += "$name: ${error.message ?: "CAS-конфликт"}; baseline не изменён"
                    }
                }
                val next = manifest.copy(skipped = (manifest.skipped + skipped).distinct(), complete = manifest.complete || (conflicts.isEmpty() && !snapshot.truncated))
                checkpoint(store, next)
                DesktopSyncResult(uploaded, downloaded, conflicts, next.skipped)
            }
        }
    }
}
