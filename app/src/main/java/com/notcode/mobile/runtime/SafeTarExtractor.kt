package com.notcode.mobile.runtime

import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream

object SafeTarExtractor {
    /** The caller owns this empty staging directory and must clean it up on failure. */
    suspend fun extract(
        archive: File,
        destination: File,
        maxBytes: Long = 2L * 1024 * 1024 * 1024,
        maxEntries: Int = 200000,
    ) {
        require(maxBytes >= 0) { "maxBytes must be nonnegative" }
        require(maxEntries >= 0) { "maxEntries must be nonnegative" }
        val context = currentCoroutineContext()
        context.ensureActive()
        val requestedRoot = destination.toPath().toAbsolutePath().normalize()
        if (Files.isSymbolicLink(requestedRoot) || !Files.isDirectory(requestedRoot, NOFOLLOW_LINKS)) {
            throw IOException("Destination must be an existing, non-symlink directory")
        }
        val root = requestedRoot.toRealPath()
        Files.newDirectoryStream(root).use {
            if (it.iterator().hasNext()) throw IOException("Destination must be empty")
        }
        val names = mutableMapOf<Path, Boolean>()
        val regularFiles = mutableSetOf<Path>()
        val hardLinks = mutableListOf<Pair<Path, Path>>()
        val directoryModes = linkedMapOf<Path, Int>()
        val buffer = ByteArray(32 * 1024)
        var entries = 0

        archive.inputStream().buffered().use { compressed ->
            GzipCompressorInputStream(compressed).use { gzip ->
                // Bound all expanded tar bytes, including padding, skipped entries and PAX metadata.
                val expanded = BoundedInput(gzip, maxBytes, context)
                TarArchiveInputStream(expanded).use { tar ->
                    while (true) {
                        context.ensureActive()
                        val entry = tar.nextTarEntry ?: break
                        if (entries >= maxEntries) throw IOException("Archive entry limit exceeded")
                        entries++
                        val path = entryPath(root, entry.name)
                        checkAncestors(root, path)
                        if (path == root && !entry.isDirectory) throw IOException("Root entry is not a directory")
                        val previousDirectory = names.put(path, entry.isDirectory)
                        if (previousDirectory != null && !(previousDirectory && entry.isDirectory)) {
                            throw IOException("Duplicate entry: ${entry.name}")
                        }
                        if (entry.isDirectory) {
                            if (Files.exists(path, NOFOLLOW_LINKS) && !Files.isDirectory(path, NOFOLLOW_LINKS)) {
                                throw IOException("Directory collision: ${entry.name}")
                            }
                            makeParents(root, path)
                            if (!Files.exists(path, NOFOLLOW_LINKS)) Files.createDirectory(path)
                            directoryModes[path] = entry.mode
                            continue
                        }
                        if (Files.exists(path, NOFOLLOW_LINKS)) throw IOException("Entry collision: ${entry.name}")
                        when {
                            entry.isBlockDevice || entry.isCharacterDevice || entry.isFIFO -> Unit
                            entry.isSymbolicLink -> {
                                val target = symlinkTarget(root, path, entry.linkName)
                                makeParents(root, path)
                                Files.createSymbolicLink(path, target)
                            }
                            entry.isLink -> {
                                hardLinks += path to entryPath(root, entry.linkName)
                            }
                            entry.isFile && !entry.isSparse -> {
                                if (!tar.canReadEntryData(entry)) throw IOException("Unsupported tar data")
                                makeParents(root, path)
                                Files.newOutputStream(path, CREATE_NEW, WRITE).use { output ->
                                    while (true) {
                                        context.ensureActive()
                                        val count = tar.read(buffer)
                                        if (count < 0) break
                                        output.write(buffer, 0, count)
                                    }
                                }
                                executable(path, entry.mode)
                                regularFiles.add(path)
                            }
                            else -> throw IOException("Unsupported tar entry: ${entry.name}")
                        }
                    }
                    // Include trailing expanded bytes and force gzip integrity verification.
                    while (expanded.read(buffer) >= 0) context.ensureActive()
                }
            }
        }
        for ((path, source) in hardLinks) {
            context.ensureActive()
            checkAncestors(root, source)
            if (source !in regularFiles || !Files.isRegularFile(source, NOFOLLOW_LINKS)) {
                throw IOException("Hardlink source must be an extracted normal file")
            }
            makeParents(root, path)
            try {
                Files.createLink(path, source)
            } catch (_: java.nio.file.AccessDeniedException) {
                Files.createSymbolicLink(path, path.parent.relativize(source))
            }
        }
        // Do not remove directory search permission until all children and links exist.
        directoryModes.entries.sortedByDescending { it.key.nameCount }.forEach { (path, mode) ->
            context.ensureActive()
            executable(path, mode)
        }
    }

    private fun entryPath(root: Path, name: String): Path {
        if (name.isEmpty() || name.startsWith('/') || name.contains('\\') || name.contains('\u0000') ||
            Regex("^[A-Za-z]:").containsMatchIn(name) || name.split('/').any { it == ".." }
        ) throw IOException("Unsafe archive path: $name")
        val path = root.resolve(name).normalize()
        if (!path.startsWith(root)) throw IOException("Archive path escapes destination")
        return path
    }

    private fun symlinkTarget(root: Path, link: Path, name: String): Path {
        if (name.isEmpty() || name.contains('\\') || name.contains('\u0000') ||
            Regex("^[A-Za-z]:").containsMatchIn(name)
        ) throw IOException("Unsafe symlink target")
        val target = if (name.startsWith('/')) root.resolve(name.trimStart('/')).normalize()
        else link.parent.resolve(name).normalize()
        if (!target.startsWith(root)) throw IOException("Symlink target escapes destination")
        // Normalization removes internal '..' before any symlink can change its meaning.
        val relative = link.parent.relativize(target)
        return if (relative.toString().isEmpty()) link.fileSystem.getPath(".") else relative
    }

    private fun checkAncestors(root: Path, path: Path) {
        var parent = path.parent
        while (parent != null && parent.startsWith(root)) {
            if (Files.exists(parent, NOFOLLOW_LINKS) && !Files.isDirectory(parent, NOFOLLOW_LINKS)) {
                throw IOException("Entry has a non-directory or symlink ancestor")
            }
            if (parent == root) break
            parent = parent.parent
        }
    }

    private fun makeParents(root: Path, path: Path) {
        checkAncestors(root, path)
        if (path != root) Files.createDirectories(path.parent)
    }

    private fun executable(path: Path, mode: Int) {
        val needsExecute = mode and 0b001001001 != 0
        if (!needsExecute && File.separatorChar == '\\') return
        if (!path.toFile().setExecutable(needsExecute, false)) {
            throw IOException("Cannot set executable mode: $path")
        }
    }

    private class BoundedInput(
        input: InputStream,
        private val limit: Long,
        private val context: CoroutineContext,
    ) : FilterInputStream(input) {
        private var total = 0L
        private val skipBuffer = ByteArray(8192)

        private fun account(count: Int): Int {
            if (count > 0) {
                if (count.toLong() > limit - total) throw IOException("Uncompressed byte limit exceeded")
                total += count
            }
            return count
        }

        override fun read(): Int {
            context.ensureActive()
            val value = `in`.read()
            if (value >= 0) account(1)
            return value
        }

        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            context.ensureActive()
            // Read at most one byte past the budget, without overflowing Long.MAX_VALUE.
            val remaining = limit - total
            val allowed = if (remaining >= length.toLong()) length else (remaining + 1).toInt()
            return account(`in`.read(bytes, offset, allowed))
        }

        override fun skip(count: Long): Long {
            var skipped = 0L
            while (skipped < count) {
                val read = read(skipBuffer, 0, minOf(skipBuffer.size.toLong(), count - skipped).toInt())
                if (read < 0) break
                skipped += read
            }
            return skipped
        }
    }
}
