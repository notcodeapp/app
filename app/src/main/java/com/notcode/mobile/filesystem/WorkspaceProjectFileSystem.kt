package com.notcode.mobile.filesystem

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/** Private, text-only working copy. The sibling sync manifest must travel with this directory. */
class WorkspaceProjectFileSystem(val directory: File) : ProjectFileSystem {
    private val root = directory.absoluteFile.let { requested ->
        var ancestor: File? = requested
        while (ancestor != null) {
            if (Files.isSymbolicLink(ancestor.toPath())) throw ProjectFileException("Symbolic workspace path is not allowed")
            ancestor = ancestor.parentFile
        }
        requested.canonicalFile
    }
    private val manifest = File(requireNotNull(root.parentFile), "${root.name}-sync.json")
    private val lock = locks.getOrPut(root.toPath().normalize().toString()) { Mutex() }

    override suspend fun list(directory: ProjectPath): List<ProjectEntry> = io {
        entries(directory)
    }

    override suspend fun exists(path: ProjectPath): Boolean = io { resolve(path).exists() }

    override suspend fun readText(path: ProjectPath, maxBytes: Long): TextDocument = io {
        read(path, maxBytes)
    }

    override suspend fun readPreview(path: ProjectPath, maxBytes: Long): FilePreviewDocument = io {
        require(maxBytes in 0..MAX_PREVIEW_BYTES) { "Preview limit must be between 0 and 4 MiB" }
        require(!path.isRoot) { "Cannot read the workspace root as a file" }
        val file = resolve(path)
        if (!file.exists()) throw ProjectFileNotFoundException(path)
        if (!file.isFile) throw ProjectFileException("Not a file: $path")
        val bytes = Files.newInputStream(file.toPath(), LinkOption.NOFOLLOW_LINKS).use { input ->
            val output = ByteArrayOutputStream(minOf(maxBytes, 8192L).toInt())
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (total < maxBytes) {
                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), maxBytes - total).toInt())
                if (count < 0) break
                output.write(buffer, 0, count)
                total += count
            }
            output.toByteArray() to (if (total >= maxBytes) input.read() >= 0 else false)
        }
        FilePreviewDocument(
            path,
            bytes.first,
            FileRevision(file.toURI().toString(), file.length(), file.lastModified()),
            bytes.second,
        )
    }

    /** Copies a workspace file byte-for-byte to a private staging path, without adding it to sync state. */
    suspend fun copyRawFileTo(path: ProjectPath, destination: File, maxBytes: Long): Long = io {
        require(!path.isRoot && maxBytes >= 0)
        val source = resolve(path)
        if (!source.exists()) throw ProjectFileNotFoundException(path)
        if (!source.isFile || Files.isSymbolicLink(source.toPath())) throw ProjectFileException("Not a regular file: $path")
        if (source.length() > maxBytes) throw ProjectFileTooLargeException(path, maxBytes)
        val parentFile = requireNotNull(destination.absoluteFile.parentFile).canonicalFile
        if (!parentFile.toPath().startsWith(root.toPath())) throw ProjectFileException("Staging destination escapes the workspace")
        val target = File(parentFile, destination.name).toPath().normalize()
        var parent: File? = parentFile
        while (parent != null && parent.canonicalFile.toPath().startsWith(root.toPath())) {
            if (Files.isSymbolicLink(parent.toPath())) throw ProjectFileException("Symbolic staging path is not allowed")
            if (parent == root) break
            parent = parent.parentFile
        }
        if (!parentFile.isDirectory && !parentFile.mkdirs()) throw ProjectFileException("Cannot create staging folder")
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw ProjectFileException("Staging destination already exists")
        val temporary = File(parentFile, ".${target.fileName}-${java.util.UUID.randomUUID()}.tmp")
        var total = 0L
        try {
            Files.newInputStream(source.toPath(), LinkOption.NOFOLLOW_LINKS).use { input ->
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
            }
            Files.move(temporary.toPath(), target, StandardCopyOption.ATOMIC_MOVE)
            total
        } finally { Files.deleteIfExists(temporary.toPath()) }
    }

    override suspend fun writeText(
        path: ProjectPath,
        content: String,
        expectedRevision: FileRevision?,
    ): FileRevision = io { write(path, content, expectedRevision) }

    override suspend fun writeBytes(
        path: ProjectPath,
        content: ByteArray,
        expectedRevision: FileRevision?,
    ): FileRevision = io {
        require(!path.isRoot) { "Cannot write the workspace root" }
        val file = resolve(path)
        if (file.exists() && !file.isFile) throw ProjectFileException("Not a file: $path")
        if (expectedRevision != null) {
            if (!file.isFile) throw ProjectFileConflictException(path)
            if (revision(file, boundedBytes(file, path, MAX_BYTES)) != expectedRevision) {
                throw ProjectFileConflictException(path)
            }
        }
        val parent = resolve(path.parent)
        if (!parent.isDirectory && !parent.mkdirs()) throw ProjectFileException("Cannot create $parent")
        atomicWrite(file, content)
        revision(file, content)
    }

    override suspend fun renameFile(
        path: ProjectPath,
        target: ProjectPath,
        expectedRevision: FileRevision?,
    ): FileRevision = io {
        require(!path.isRoot && !target.isRoot) { "Cannot rename the workspace root" }
        val file = mutationFile(path, expectedRevision)
        val bytes = boundedBytes(file, path, MAX_BYTES)
        val destination = resolve(target)
        if (destination.exists()) throw ProjectFileConflictException(target)
        val parent = resolve(target.parent)
        if (!parent.isDirectory && !parent.mkdirs()) throw ProjectFileException("Cannot create $parent")
        val state = loadManifest()
        val alreadyDeleted = state?.tombstones?.contains(path.value) == true
        if (state != null && path.value in state) {
            state.tombstones.add(path.value)
            saveManifest(state)
        }
        try {
            // No REPLACE_EXISTING (and no ATOMIC_MOVE, which may replace an existing target).
            Files.move(resolve(path).toPath(), resolve(target).toPath())
        } catch (error: Exception) {
            if (state != null && !alreadyDeleted) {
                state.tombstones.remove(path.value)
                saveManifest(state)
            }
            throw ProjectFileException("Cannot rename $path to $target", error)
        }
        revision(destination, bytes)
    }

    override suspend fun deleteFile(path: ProjectPath, expectedRevision: FileRevision?): Unit = io {
        require(!path.isRoot) { "Cannot delete the workspace root" }
        mutationFile(path, expectedRevision)
        val state = loadManifest()
        val alreadyDeleted = state?.tombstones?.contains(path.value) == true
        if (state != null && path.value in state) {
            state.tombstones.add(path.value)
            saveManifest(state)
        }
        try {
            Files.delete(resolve(path).toPath())
        } catch (error: Exception) {
            if (state != null && !alreadyDeleted) {
                state.tombstones.remove(path.value)
                saveManifest(state)
            }
            throw ProjectFileException("Cannot delete $path", error)
        }
    }

    private fun mutationFile(path: ProjectPath, expected: FileRevision?): File {
        val file = resolve(path)
        if (!file.exists()) {
            if (expected != null) throw ProjectFileConflictException(path)
            throw ProjectFileNotFoundException(path)
        }
        if (!file.isFile) throw ProjectFileException("Not a file: $path")
        if (expected != null && revision(file, boundedBytes(file, path, MAX_BYTES)) != expected) {
            throw ProjectFileConflictException(path)
        }
        return file
    }

    /** Initial import only. An existing marker makes this a no-op, including for dirty files. */
    suspend fun importFrom(source: ProjectFileSystem) = io {
        require(source !== this) { "Cannot import a workspace from itself" }
        if (loadManifest() != null) return@io
        ensureRoot()
        // Never overwrite an unmarked working copy (including an interrupted import).
        if (root.listFiles()?.isNotEmpty() != false) {
            throw ProjectFileException("Initial import requires an empty workspace")
        }
        val pending = mutableListOf<TextDocument>()
        var bytes = 0L
        walk(source) { entry ->
            if (source is SafProjectFileSystem) {
                val preview = source.readPreview(entry.path, 4096)
                val binary = try { looksBinary(preview.bytes) } finally { preview.bytes.fill(0) }
                if (binary) return@walk
            }
            val document = try {
                source.readText(entry.path, MAX_BYTES - bytes)
            } catch (_: ProjectBinaryFileException) {
                // Keep the source tree as the authority for opaque files. The IDE can
                // still show them through a bounded, read-only preview.
                return@walk
            } catch (tooLarge: ProjectFileTooLargeException) {
                val preview = try {
                    source.readPreview(entry.path, 4096)
                } catch (_: ProjectFileException) {
                    throw tooLarge
                }
                val binary = try { looksBinary(preview.bytes) } finally { preview.bytes.fill(0) }
                if (binary) return@walk else throw tooLarge
            }
            bytes += maxOf(document.revision.size, document.text.toByteArray(Charsets.UTF_8).size.toLong())
            if (bytes > MAX_BYTES) throw ProjectFileTooLargeException(entry.path, MAX_BYTES)
            pending += document
        }
        val baseline = linkedMapOf<String, Baseline>()
        for (document in pending) {
            write(document.path, document.text, null)
            baseline[document.path.value] = Baseline(document.revision, hash(document.text))
        }
        saveManifest(baseline)
    }

    /** Writes only local changes; never deletes remote files. Returns the number written. */
    suspend fun exportTo(source: ProjectFileSystem): Int = io {
        require(source !== this) { "Cannot export a workspace to itself" }
        val baseline = loadManifest()
            ?: throw ProjectFileException("Import the project before exporting")
        if (baseline.tombstones.isNotEmpty()) {
            throw ProjectFileException("Use synchronize to apply pending file deletions or renames")
        }
        val changed = mutableListOf<TextDocument>()
        var bytes = 0L
        walk(this) { entry ->
            // Call the unlocked helper: the workspace mutex is already held.
            val document = read(entry.path, MAX_BYTES - bytes)
            bytes += document.revision.size
            if (bytes > MAX_BYTES) throw ProjectFileTooLargeException(entry.path, MAX_BYTES)
            if (baseline[entry.path.value]?.hash != hash(document.text)) changed += document
        }
        // Preflight all conflicts before writing anything. A provider must still enforce its
        // expectedRevision at write time; its API has no atomic create-if-absent operation.
        for (document in changed) {
            val original = baseline[document.path.value]
            if (original == null) {
                if (source.exists(document.path)) throw ProjectFileConflictException(document.path)
            } else {
                if (!source.exists(document.path)) throw ProjectFileConflictException(document.path)
                val external = source.readText(document.path, MAX_BYTES)
                if (external.revision != original.revision || hash(external.text) != original.hash) {
                    throw ProjectFileConflictException(document.path)
                }
            }
        }
        for (document in changed) {
            val original = baseline[document.path.value]
            // Repeat the existence check immediately before creating a new document.
            if (original == null && source.exists(document.path)) {
                throw ProjectFileConflictException(document.path)
            }
            val revision = source.writeText(document.path, document.text, original?.revision)
            baseline[document.path.value] = Baseline(revision, hash(document.text))
            // Persist each success so a later failure does not cause already-exported files
            // to be offered again or compared against stale remote revisions.
            saveManifest(baseline)
        }
        changed.size
    }

    data class SyncResult(val uploaded: Int, val downloaded: Int)

    /**
     * Three-way, text-content sync. Metadata is not a content change. Missing files are
     * restored from the surviving side unless explicitly deleted/renamed through this API.
     * Persisted tombstones delete only unchanged remote content; unintentional absence on
     * both sides retains the baseline. All snapshot conflicts (including collisions) are
     * checked before any writes. Providers cannot offer a multi-file transaction, so
     * successful files are checkpointed and concurrent changes can still abort a run.
     */
    suspend fun synchronize(source: ProjectFileSystem): SyncResult = io {
        require(source !== this) { "Cannot synchronize a workspace with itself" }
        val baseline = loadManifest()
            ?: throw ProjectFileException("Import the project before synchronizing")
        val opaquePaths = linkedSetOf<String>()
        suspend fun snapshot(fs: ProjectFileSystem): Map<String, TextDocument> {
            val documents = linkedMapOf<String, TextDocument>()
            var bytes = 0L
            walk(fs) { entry ->
                if (fs is SafProjectFileSystem && entry.size > 1024 * 1024) {
                    val preview = fs.readPreview(entry.path, 4096)
                    val binary = try { looksBinary(preview.bytes) } finally { preview.bytes.fill(0) }
                    if (binary) {
                        opaquePaths += entry.path.value
                        return@walk
                    }
                }
                val document = try {
                    if (fs === this) read(entry.path, MAX_BYTES - bytes)
                    else fs.readText(entry.path, MAX_BYTES - bytes)
                } catch (_: ProjectBinaryFileException) {
                    opaquePaths += entry.path.value
                    return@walk
                } catch (tooLarge: ProjectFileTooLargeException) {
                    if (fs === this) throw tooLarge
                    val preview = try {
                        fs.readPreview(entry.path, 4096)
                    } catch (_: ProjectFileException) {
                        throw tooLarge
                    }
                    val binary = try { looksBinary(preview.bytes) } finally { preview.bytes.fill(0) }
                    if (binary) {
                        opaquePaths += entry.path.value
                        return@walk
                    }
                    throw tooLarge
                }
                bytes += maxOf(document.revision.size, document.text.toByteArray(Charsets.UTF_8).size.toLong())
                if (bytes > MAX_BYTES) throw ProjectFileTooLargeException(entry.path, MAX_BYTES)
                documents[entry.path.value] = document
            }
            return documents
        }
        val local = snapshot(this)
        val remote = snapshot(source)
        for (name in opaquePaths) {
            if (name in baseline || name in local || name in remote) {
                throw ProjectFileConflictException(ProjectPath.parse(name))
            }
        }
        val names = (baseline.keys + local.keys + remote.keys).toSortedSet()
        if (names.size > MAX_FILES) throw ProjectFileException("Project exceeds $MAX_FILES files")
        data class Transfer(val document: TextDocument, val previous: TextDocument?)
        val uploads = mutableListOf<Transfer>()
        val downloads = mutableListOf<Transfer>()
        val converged = linkedMapOf<String, Baseline>()
        val deletions = linkedMapOf<ProjectPath, TextDocument?>()
        for (name in names) {
            val ours = local[name]
            val theirs = remote[name]
            val original = baseline[name]?.hash
            val oursHash = ours?.let { hash(it.text) }
            val theirsHash = theirs?.let { hash(it.text) }
            val path = ProjectPath.parse(name)
            if (name in baseline.tombstones) {
                // Recreating a deleted path is allowed, but it must not hide an edit to
                // the original remote file. Only unchanged content may be replaced/deleted.
                if (theirs != null && theirsHash != original ||
                    theirs == null && source.exists(path) ||
                    ours == null && resolve(path).exists()) {
                    throw ProjectFileConflictException(path)
                }
                if (ours != null) {
                    uploads += Transfer(ours, theirs)
                } else {
                    deletions[path] = theirs
                }
                continue
            }
            // An absent file may actually be a directory (including an empty one).
            if (ours == null && theirs != null && resolve(path).exists() ||
                theirs == null && ours != null && source.exists(path)) {
                throw ProjectFileConflictException(path)
            }
            when {
                ours == null && theirs == null -> Unit
                ours == null -> downloads += Transfer(theirs!!, null)
                theirs == null -> uploads += Transfer(ours, null)
                oursHash == theirsHash -> converged[name] = Baseline(theirs.revision, theirsHash!!)
                oursHash == original -> downloads += Transfer(theirs, ours)
                theirsHash == original -> uploads += Transfer(ours, theirs)
                else -> throw ProjectFileConflictException(path)
            }
        }
        // Check parent collisions as well: local file 'a' vs remote file 'a/b'.
        val files = local.keys + remote.keys
        for (name in files) {
            var parent = ProjectPath.parse(name).parent
            while (!parent.isRoot) {
                if (parent.value in files) throw ProjectFileConflictException(parent)
                parent = parent.parent
            }
        }
        suspend fun checkRemote(path: ProjectPath, previous: TextDocument?) {
            if (previous == null) {
                if (source.exists(path)) throw ProjectFileConflictException(path)
            } else {
                if (!source.exists(path)) throw ProjectFileConflictException(path)
                val current = source.readText(path, MAX_BYTES)
                if (current.revision != previous.revision || hash(current.text) != hash(previous.text)) {
                    throw ProjectFileConflictException(path)
                }
            }
        }
        // Recheck every pending deletion before any upload/download, notably the old
        // name of a rename. Check again at delete time to catch provider races.
        for ((path, previous) in deletions) checkRemote(path, previous)
        for ((document, previous) in uploads) {
            checkRemote(document.path, previous)
            source.writeText(document.path, document.text, previous?.revision)
            val verified = source.readText(document.path, MAX_BYTES)
            if (verified.text != document.text) throw ProjectFileException("Write verification failed: ${document.path}")
            baseline[document.path.value] = Baseline(verified.revision, hash(verified.text))
            baseline.tombstones.remove(document.path.value)
            saveManifest(baseline)
        }
        for ((document, previous) in downloads) {
            checkRemote(document.path, document)
            if (previous == null && resolve(document.path).exists()) throw ProjectFileConflictException(document.path)
            write(document.path, document.text, previous?.revision)
            baseline[document.path.value] = Baseline(document.revision, hash(document.text))
            saveManifest(baseline)
        }
        for ((path, previous) in deletions) {
            checkRemote(path, previous)
            if (previous != null) source.deleteFile(path, previous.revision)
            if (source.exists(path)) throw ProjectFileConflictException(path)
            baseline.remove(path.value)
            baseline.tombstones.remove(path.value)
            saveManifest(baseline)
        }
        baseline.putAll(converged)
        saveManifest(baseline)
        SyncResult(uploads.size, downloads.size)
    }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        lock.withLock { block() }
    }

    private fun checked(file: File): File {
        val absolute = file.absoluteFile
        var ancestor: File? = absolute
        while (ancestor != null) {
            if (Files.isSymbolicLink(ancestor.toPath())) {
                throw ProjectFileException("Symbolic links are not allowed: $file")
            }
            ancestor = ancestor.parentFile
        }
        if (absolute.toPath().normalize().toFile() != absolute || absolute.canonicalFile != absolute) {
            throw ProjectFileException("Non-canonical path: $file")
        }
        return absolute
    }

    private fun resolve(path: ProjectPath): File {
        checked(root)
        if (!path.isRoot) {
            for (part in path.value.split('/')) {
                require(part.isNotEmpty() && part != "." && part != ".." &&
                    part.none { it == ':' || it == '\\' || it == '\u0000' } &&
                    !part.endsWith('.') && !part.endsWith(' ')) { "Unsafe workspace path" }
            }
        }
        val file = checked(if (path.isRoot) root else File(root, path.value))
        if (!file.toPath().startsWith(root.toPath())) {
            throw ProjectFileException("Path escapes the workspace: $path")
        }
        return file
    }

    private fun ensureRoot() {
        resolve(ProjectPath.Root)
        if (!root.isDirectory && !root.mkdirs()) throw ProjectFileException("Cannot create workspace")
        resolve(ProjectPath.Root)
    }

    private fun entries(path: ProjectPath): List<ProjectEntry> {
        val folder = resolve(path)
        if (!folder.exists()) throw ProjectFileNotFoundException(path)
        if (!folder.isDirectory) throw ProjectFileException("Not a directory: $path")
        return (folder.listFiles() ?: throw ProjectFileException("Cannot list $path"))
            .filterNot { path.isRoot && it.name in INTERNAL_FOLDERS }
            .map { file ->
                val child = path.child(file.name)
                val safe = resolve(child)
                if (!safe.isFile && !safe.isDirectory) throw ProjectFileException("Not a regular file: $child")
                ProjectEntry(child, file.name, safe.isDirectory, safe.length(), safe.lastModified())
            }.sortedWith(compareByDescending<ProjectEntry> { it.isDirectory }.thenBy { it.name })
    }

    private fun read(path: ProjectPath, maxBytes: Long): TextDocument {
        require(maxBytes >= 0) { "maxBytes must be non-negative" }
        require(!path.isRoot) { "Cannot read the workspace root" }
        val file = resolve(path)
        if (!file.exists()) throw ProjectFileNotFoundException(path)
        if (!file.isFile) throw ProjectFileException("Not a file: $path")
        val modified = file.lastModified()
        val bytes = boundedBytes(file, path, maxBytes)
        try {
            if (bytes.any { it == 0.toByte() }) throw ProjectBinaryFileException(path)
            val text = try {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
            } catch (error: java.nio.charset.CharacterCodingException) {
                throw ProjectBinaryFileException(path)
            }
            return TextDocument(path, text, revision(file, bytes, modified))
        } finally {
            bytes.fill(0)
        }
    }

    private fun boundedBytes(file: File, path: ProjectPath, limit: Long): ByteArray {
        if (file.length() > limit) throw ProjectFileTooLargeException(path, limit)
        return Files.newInputStream(file.toPath(), LinkOption.NOFOLLOW_LINKS).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val count = input.read(buffer, 0, (minOf(buffer.size.toLong() - 1, limit - total) + 1).toInt())
                if (count < 0) {
                    break
                }
                total += count
                if (total > limit) throw ProjectFileTooLargeException(path, limit)
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    private fun revision(file: File, bytes: ByteArray, modified: Long = file.lastModified()) =
        FileRevision("${file.toURI()}#sha256=${hash(bytes)}", bytes.size.toLong(), modified)

    private fun looksBinary(bytes: ByteArray): Boolean {
        if (bytes.any { it == 0.toByte() }) return true
        val signatures = listOf(
            "%PDF-".toByteArray(Charsets.US_ASCII),
            "Rar!".toByteArray(Charsets.US_ASCII),
            "SQLite format 3".toByteArray(Charsets.US_ASCII),
        )
        if (signatures.any { signature -> bytes.size >= signature.size && signature.indices.all { bytes[it] == signature[it] } }) return true
        val validLength = (bytes.size - 4).coerceAtLeast(0)
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, 0, validLength))
            false
        } catch (_: java.nio.charset.CharacterCodingException) {
            true
        }
    }

    private fun write(path: ProjectPath, content: String, expected: FileRevision?): FileRevision {
        require(!path.isRoot) { "Cannot write the workspace root" }
        val file = resolve(path)
        if (file.exists() && !file.isFile) throw ProjectFileException("Not a file: $path")
        if (expected != null) {
            if (!file.isFile) throw ProjectFileConflictException(path)
            val bytes = boundedBytes(file, path, MAX_BYTES)
            if (revision(file, bytes) != expected) throw ProjectFileConflictException(path)
        }
        val parent = resolve(path.parent)
        if (!parent.isDirectory && !parent.mkdirs()) throw ProjectFileException("Cannot create $parent")
        resolve(path)
        val bytes = content.toByteArray(Charsets.UTF_8)
        atomicWrite(file, bytes)
        return revision(file, bytes)
    }

    private fun atomicWrite(file: File, bytes: ByteArray) {
        checked(file)
        val temporary = File.createTempFile(".notcode-", ".tmp", checked(file.parentFile!!))
        try {
            temporary.outputStream().use { it.write(bytes); it.flush(); it.fd.sync() }
            checked(file)
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temporary.delete()
        }
    }

    private suspend fun walk(source: ProjectFileSystem, visit: suspend (ProjectEntry) -> Unit) {
        val folders = java.util.ArrayDeque<ProjectPath>()
        folders.add(ProjectPath.Root)
        val seen = hashSetOf<String>()
        var files = 0
        while (folders.isNotEmpty()) {
            val parent = folders.removeFirst()
            val children = if (source === this) entries(parent) else source.list(parent)
            for (entry in children) {
                if (entry.path.isRoot || entry.path.parent != parent || entry.path.name != entry.name) {
                    throw ProjectFileException("Invalid source entry: ${entry.path}")
                }
                resolve(entry.path)
                if (entry.name in IGNORED) continue
                if (!seen.add(entry.path.value)) throw ProjectFileException("Duplicate source entry")
                if (seen.size > 20000) throw ProjectFileException("Too many project entries")
                if (entry.isDirectory) folders.add(entry.path) else {
                    if (++files > MAX_FILES) throw ProjectFileException("Project exceeds $MAX_FILES files")
                    visit(entry)
                }
            }
        }
    }

    private data class Baseline(val revision: FileRevision, val hash: String)

    private class Manifest : LinkedHashMap<String, Baseline>() {
        val tombstones = linkedSetOf<String>()
    }

    private fun loadManifest(): Manifest? {
        checked(manifest)
        if (!manifest.exists()) return null
        try {
            val data = Json.parseToJsonElement(
                boundedBytes(manifest, ProjectPath.Root, 8L * 1024 * 1024).toString(Charsets.UTF_8),
            ).jsonObject
            require(data.getValue("version").jsonPrimitive.long == 1L)
            val savedRoot = File(data.getValue("workspace").jsonPrimitive.content)
            require(savedRoot.isAbsolute && (savedRoot.path == root.path ||
                savedRoot.canonicalFile == root.canonicalFile ||
                Files.isSameFile(savedRoot.toPath(), root.toPath()))) {
                "Путь рабочей копии не соответствует манифесту"
            }
            val result = Manifest()
            val items = data.getValue("files").jsonArray
            require(items.size <= MAX_FILES)
            for (item in items) {
                val obj = item.jsonObject
                val path = ProjectPath.parse(obj.getValue("path").jsonPrimitive.content)
                require(!path.isRoot && path.value !in result)
                resolve(path)
                result[path.value] = Baseline(
                    FileRevision(obj.getValue("uri").jsonPrimitive.content,
                        obj.getValue("size").jsonPrimitive.long,
                        obj.getValue("modified").jsonPrimitive.long),
                    obj.getValue("hash").jsonPrimitive.content,
                )
            }
            val tombstones = data["tombstones"]?.jsonArray ?: JsonArray(emptyList())
            require(tombstones.size <= MAX_FILES)
            for (item in tombstones) {
                val path = ProjectPath.parse(item.jsonPrimitive.content)
                require(!path.isRoot && path.value in result && result.tombstones.add(path.value))
                resolve(path)
            }
            return result
        } catch (error: Exception) {
            throw ProjectFileException("Не удалось прочитать данные синхронизации: ${error.message ?: error.javaClass.simpleName}. Файлы не изменены.", error)
        }
    }

    private fun saveManifest(baseline: Map<String, Baseline>) {
        val json = buildJsonObject {
            put("version", 1)
            put("workspace", root.path)
            if (baseline is Manifest) {
                put("tombstones", JsonArray(baseline.tombstones.map {
                    kotlinx.serialization.json.JsonPrimitive(it)
                }))
            }
            put("files", JsonArray(baseline.map { (path, original) ->
                buildJsonObject {
                    put("path", path)
                    put("uri", original.revision.documentUri)
                    put("size", original.revision.size)
                    put("modified", original.revision.lastModifiedEpochMs)
                    put("hash", original.hash)
                }
            }))
        }
        atomicWrite(manifest, json.toString().toByteArray(Charsets.UTF_8))
    }

    companion object {
        private const val MAX_FILES = 2000
        private const val MAX_BYTES = 120L * 1024 * 1024
        private const val MAX_PREVIEW_BYTES = 4L * 1024 * 1024
        const val ATTACHMENTS_DIRECTORY = ".notcode-attachments"
        val INTERNAL_FOLDERS = setOf(".notcode-staging", ATTACHMENTS_DIRECTORY)
        private val IGNORED = setOf(".git", ".gradle", ".notcode-staging", ATTACHMENTS_DIRECTORY, "node_modules", "build")
        private val locks = ConcurrentHashMap<String, Mutex>()
        private fun hash(text: String): String = hash(text.toByteArray(Charsets.UTF_8))
        private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
