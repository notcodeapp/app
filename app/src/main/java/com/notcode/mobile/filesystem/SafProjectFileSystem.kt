package com.notcode.mobile.filesystem

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import java.io.IOException
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

class SafProjectFileSystem(
    context: Context,
    private val rootTreeUri: Uri,
) : ProjectFileSystem {
    private val resolver = context.applicationContext.contentResolver
    private val writeLocks = ConcurrentHashMap<String, Mutex>()

    override suspend fun list(directory: ProjectPath): List<ProjectEntry> =
        withContext(Dispatchers.IO) {
            guardPermission {
                val document = resolve(directory) ?: throw ProjectFileNotFoundException(directory)
                if (!document.isDirectory) {
                    throw ProjectFileException("Not a directory: ${directory.value}")
                }
                val children = listChildren(directory, document.uri) ?: document.listFiles()
                    .mapNotNull { child ->
                        val name = child.name ?: return@mapNotNull null
                        ProjectEntry(
                            path = directory.child(name),
                            name = name,
                            isDirectory = child.isDirectory,
                            size = child.length(),
                            lastModifiedEpochMs = child.lastModified(),
                        )
                    }
                children.sortedWith(
                    compareByDescending<ProjectEntry> { it.isDirectory }
                        .thenBy { it.name.lowercase() },
                )
            }
        }

    /**
     * One provider query for the whole directory. DocumentFile.listFiles would need two
     * extra queries per child for size and modification time, which dominated tree loading.
     * Returns null when the provider does not answer the children query.
     */
    private fun listChildren(directory: ProjectPath, parentUri: Uri): List<ProjectEntry>? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            rootTreeUri,
            DocumentsContract.getDocumentId(parentUri),
        )
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val result = mutableListOf<ProjectEntry>()
        val cursor = try {
            resolver.query(childrenUri, projection, null, null, null)
        } catch (_: RuntimeException) {
            null
        } ?: return null
        return try {
            cursor.use {
                val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                val modifiedColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameColumn) ?: continue
                    val isDirectory = cursor.getString(mimeColumn) == DocumentsContract.Document.MIME_TYPE_DIR
                    result += ProjectEntry(
                        path = directory.child(name),
                        name = name,
                        isDirectory = isDirectory,
                        size = if (sizeColumn >= 0) cursor.getLong(sizeColumn) else 0L,
                        lastModifiedEpochMs = if (modifiedColumn >= 0) cursor.getLong(modifiedColumn) else 0L,
                    )
                }
            }
            result
        } catch (_: RuntimeException) {
            null
        }
    }

    override suspend fun readText(path: ProjectPath, maxBytes: Long): TextDocument =
        withContext(Dispatchers.IO) {
            require(!path.isRoot) { "Cannot read the project root as a file" }
            guardPermission {
                val document = resolve(path) ?: throw ProjectFileNotFoundException(path)
                if (!document.isFile) throw ProjectFileException("Not a file: ${path.value}")
                val size = document.length()
                if (size > maxBytes && size > 0L) {
                    throw ProjectFileTooLargeException(path, maxBytes)
                }

                val bytes = resolver.openInputStream(document.uri)?.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > maxBytes) throw ProjectFileTooLargeException(path, maxBytes)
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                } ?: throw ProjectFileException("Unable to open: ${path.value}")

                try {
                    if (bytes.any { it == 0.toByte() }) throw ProjectBinaryFileException(path)
                    val decoder = Charsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                    val text = runCatching {
                        decoder.decode(ByteBuffer.wrap(bytes)).toString()
                    }.getOrElse { throw ProjectBinaryFileException(path) }
                        .removePrefix("\uFEFF")
                    TextDocument(path, text, document.revision())
                } finally {
                    bytes.fill(0)
                }
            }
        }

    override suspend fun readPreview(path: ProjectPath, maxBytes: Long): FilePreviewDocument =
        withContext(Dispatchers.IO) {
            require(maxBytes in 0..MAX_PREVIEW_BYTES) { "Preview limit must be between 0 and 4 MiB" }
            require(!path.isRoot) { "Cannot read the project root as a file" }
            guardPermission {
                val document = resolve(path) ?: throw ProjectFileNotFoundException(path)
                if (!document.isFile) throw ProjectFileException("Not a file: ${path.value}")
                val bytes = resolver.openInputStream(document.uri)?.use { input ->
                    val output = ByteArrayOutputStream(minOf(maxBytes, 8192L).toInt())
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (total < maxBytes) {
                        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), maxBytes - total).toInt())
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        total += count
                    }
                    val truncated = if (total >= maxBytes) input.read() >= 0 else false
                    output.toByteArray() to truncated
                } ?: throw ProjectFileException("Unable to open: ${path.value}")
                FilePreviewDocument(path, bytes.first, document.revision(), bytes.second)
            }
        }

    /** Copies an arbitrary SAF document to app-private staging storage without decoding it. */
    suspend fun copyRawFileTo(path: ProjectPath, destination: java.io.File, maxBytes: Long): Long =
        withContext(Dispatchers.IO) {
            require(!path.isRoot && maxBytes >= 0)
            guardPermission {
                val document = resolve(path) ?: throw ProjectFileNotFoundException(path)
                if (!document.isFile) throw ProjectFileException("Not a file: ${path.value}")
                if (document.length() > maxBytes) throw ProjectFileTooLargeException(path, maxBytes)
                val target = destination.absoluteFile.toPath().normalize()
                val parent = requireNotNull(target.parent).toFile()
                if (!parent.isDirectory && !parent.mkdirs()) throw ProjectFileException("Cannot create staging folder")
                if (Files.exists(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)) throw ProjectFileException("Staging destination already exists")
                val temporary = java.io.File(parent, ".${target.fileName}-${java.util.UUID.randomUUID()}.tmp")
                var total = 0L
                try {
                    resolver.openInputStream(document.uri)?.use { input ->
                        Files.newOutputStream(temporary.toPath(), java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE).use { output ->
                            val buffer = ByteArray(32 * 1024)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                if (total > maxBytes) throw ProjectFileTooLargeException(path, maxBytes)
                                output.write(buffer, 0, count)
                            }
                            buffer.fill(0)
                        }
                    } ?: throw ProjectFileException("Unable to open: ${path.value}")
                    Files.move(temporary.toPath(), target, StandardCopyOption.ATOMIC_MOVE)
                    total
                } finally { Files.deleteIfExists(temporary.toPath()) }
            }
        }

    override suspend fun writeText(
        path: ProjectPath,
        content: String,
        expectedRevision: FileRevision?,
    ): FileRevision = withContext(Dispatchers.IO) {
        require(!path.isRoot) { "Cannot write the project root" }
        val lock = writeLocks.getOrPut(path.value) { Mutex() }
        lock.withLock {
            guardPermission {
                val writable = writableFile(resolveOrCreateDirectories(path.parent), path, expectedRevision)
                val bytes = content.toByteArray(Charsets.UTF_8)
                try {
                    writeTruncated(writable.uri, bytes)
                } finally {
                    bytes.fill(0)
                }
                val verified = readText(path, content.toByteArray(Charsets.UTF_8).size.toLong())
                if (verified.text != content) {
                    throw ProjectFileException("Write verification failed: ${path.value}")
                }
                verified.revision
            }
        }
    }

    private fun writableFile(
        parent: DocumentFile,
        path: ProjectPath,
        expectedRevision: FileRevision?,
    ): DocumentFile {
        var document = parent.findFile(path.name)

        if (document != null && document.isDirectory) {
            throw ProjectFileException("A directory already exists at ${path.value}")
        }
        if (document == null && expectedRevision != null) throw ProjectFileConflictException(path)
        if (document != null && expectedRevision != null) {
            val current = document.revision()
            if (current.documentUri != expectedRevision.documentUri ||
                current.size != expectedRevision.size ||
                (current.lastModifiedEpochMs > 0L &&
                    expectedRevision.lastModifiedEpochMs > 0L &&
                    current.lastModifiedEpochMs != expectedRevision.lastModifiedEpochMs)
            ) {
                throw ProjectFileConflictException(path)
            }
        }

        if (document == null) {
            // text/plain makes ExternalStorageProvider append .txt to unknown
            // extensions (e.g. foo.kt). Request an opaque file, then verify the
            // display name: providers are still allowed to adjust requested names.
            document = parent.createFile("application/octet-stream", path.name)
                ?: throw ProjectFileException("Unable to create ${path.value}")
            if (document.name != path.name) {
                if (parent.findFile(path.name) != null || !document.renameTo(path.name) ||
                    document.name != path.name) {
                    throw ProjectFileException("Provider did not preserve filename: ${path.value}")
                }
            }
            if (parent.findFile(path.name)?.uri != document.uri) {
                throw ProjectFileException("Created file cannot be resolved: ${path.value}")
            }
        }
        return document ?: throw ProjectFileException("Unable to access ${path.value}")
    }

    override suspend fun writeBytes(
        path: ProjectPath,
        content: ByteArray,
        expectedRevision: FileRevision?,
    ): FileRevision = withContext(Dispatchers.IO) {
        require(!path.isRoot) { "Cannot write the project root" }
        val lock = writeLocks.getOrPut(path.value) { Mutex() }
        lock.withLock {
            guardPermission {
                val writable = writableFile(resolveOrCreateDirectories(path.parent), path, expectedRevision)
                writeTruncated(writable.uri, content)
                val verified = resolve(path) ?: throw ProjectFileException("Write vanished: ${path.value}")
                if (verified.length() != content.size.toLong()) {
                    throw ProjectFileException("Write verification failed: ${path.value}")
                }
                verified.revision()
            }
        }
    }

    override suspend fun renameFile(
        path: ProjectPath,
        target: ProjectPath,
        expectedRevision: FileRevision?,
    ): FileRevision = withContext(Dispatchers.IO) {
        require(!path.isRoot && !target.isRoot) { "Cannot rename the project root" }
        if (path == target) throw ProjectFileConflictException(target)
        // Stable ordering avoids deadlocks for opposing moves.
        val names = listOf(path.value, target.value).sorted()
        writeLocks.getOrPut(names[0]) { Mutex() }.withLock {
            writeLocks.getOrPut(names[1]) { Mutex() }.withLock {
                guardPermission {
                    var document = mutationDocument(path, expectedRevision)
                    if (resolve(target) != null) throw ProjectFileConflictException(target)
                    val parent = resolveOrCreateDirectories(target.parent)
                    if (path.parent != target.parent) {
                        if (parent.findFile(path.name) != null) throw ProjectFileConflictException(target)
                        val oldParent = resolve(path.parent) ?: throw ProjectFileNotFoundException(path.parent)
                        // Native provider move preserves bytes; never emulate with text copy/delete.
                        val moved = DocumentsContract.moveDocument(resolver, document.uri, oldParent.uri, parent.uri)
                            ?: throw ProjectFileException("Provider cannot move $path")
                        document = DocumentFile.fromSingleUri(resolverContext, moved)
                            ?: throw ProjectFileException("Cannot resolve moved file: $target")
                    }
                    val renamedUri = if (document.name != target.name) {
                        DocumentsContract.renameDocument(resolver, document.uri, target.name)
                            ?: throw ProjectFileException("Provider cannot rename $path to $target")
                    } else document.uri
                    val verified = resolve(target)
                    if (verified == null || !verified.isFile || verified.uri != renamedUri || resolve(path) != null) {
                        throw ProjectFileException("Provider did not preserve rename target: $target")
                    }
                    verified.revision()
                }
            }
        }
    }

    override suspend fun deleteFile(path: ProjectPath, expectedRevision: FileRevision?): Unit =
        withContext(Dispatchers.IO) {
            require(!path.isRoot) { "Cannot delete the project root" }
            writeLocks.getOrPut(path.value) { Mutex() }.withLock {
                guardPermission {
                    val document = mutationDocument(path, expectedRevision)
                    if (!document.delete()) throw ProjectFileException("Unable to delete $path")
                    if (resolve(path) != null) throw ProjectFileException("Delete verification failed: $path")
                }
            }
        }

    private fun mutationDocument(path: ProjectPath, expected: FileRevision?): DocumentFile {
        val document = resolve(path) ?: run {
            if (expected != null) throw ProjectFileConflictException(path)
            throw ProjectFileNotFoundException(path)
        }
        if (!document.isFile) throw ProjectFileException("Not a file: $path")
        if (expected != null) {
            val current = document.revision()
            if (current.documentUri != expected.documentUri || current.size != expected.size ||
                (current.lastModifiedEpochMs > 0 && expected.lastModifiedEpochMs > 0 &&
                    current.lastModifiedEpochMs != expected.lastModifiedEpochMs)) {
                throw ProjectFileConflictException(path)
            }
        }
        return document
    }

    override suspend fun exists(path: ProjectPath): Boolean = withContext(Dispatchers.IO) {
        guardPermission { resolve(path) != null }
    }

    private fun root(): DocumentFile = DocumentFile.fromTreeUri(
        resolverContext,
        rootTreeUri,
    ) ?: throw ProjectFileException("The selected project provider is unavailable")

    // DocumentFile needs a Context. ContentResolver does not expose it, so retain only
    // the application context rather than an Activity.
    private val resolverContext: Context = context.applicationContext

    private fun resolve(path: ProjectPath): DocumentFile? {
        var current = root()
        if (path.isRoot) return current
        for (segment in path.value.split('/')) {
            current = current.findFile(segment) ?: return null
        }
        return current
    }

    private fun resolveOrCreateDirectories(path: ProjectPath): DocumentFile {
        var current = root()
        if (path.isRoot) return current
        for (segment in path.value.split('/')) {
            val existing = current.findFile(segment)
            current = when {
                existing == null -> current.createDirectory(segment)
                    ?: throw ProjectFileException("Unable to create directory ${path.value}")
                existing.isDirectory -> existing
                else -> throw ProjectFileException("Not a directory: $segment")
            }
        }
        return current
    }

    private fun writeTruncated(uri: Uri, bytes: ByteArray) {
        // Fall back only if opening rwt is unsupported, not after a write/close
        // failure. AutoCloseOutputStream owns the descriptor and closes it once.
        val descriptor = try {
            resolver.openFileDescriptor(uri, "rwt")
        } catch (_: IOException) {
            null
        } catch (_: UnsupportedOperationException) {
            null
        }
        if (descriptor != null) {
            ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
                output.write(bytes)
                output.flush()
            }
        } else {
            resolver.openOutputStream(uri, "wt")?.use { output ->
                output.write(bytes)
                output.flush()
            } ?: throw ProjectFileException("Unable to write document")
        }
    }

    /** Reads size and modification time in one query; DocumentFile needs two. */
    private fun DocumentFile.revision(): FileRevision {
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        var size = -1L
        var modified = -1L
        try {
            resolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val sizeColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                    val modifiedColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                    if (sizeColumn >= 0) size = cursor.getLong(sizeColumn)
                    if (modifiedColumn >= 0) modified = cursor.getLong(modifiedColumn)
                }
            }
        } catch (_: RuntimeException) {
            // Providers that reject the projection still answer the DocumentFile accessors.
        }
        return FileRevision(
            documentUri = uri.toString(),
            size = if (size >= 0) size else length(),
            lastModifiedEpochMs = if (modified >= 0) modified else lastModified(),
        )
    }

    private inline fun <T> guardPermission(block: () -> T): T = try {
        block()
    } catch (error: SecurityException) {
        throw ProjectAccessRevokedException(error)
    } catch (error: FileNotFoundException) {
        throw ProjectFileException("Document provider could not open the file", error)
    }

    companion object {
        private const val MAX_PREVIEW_BYTES = 4L * 1024 * 1024
    }
}
