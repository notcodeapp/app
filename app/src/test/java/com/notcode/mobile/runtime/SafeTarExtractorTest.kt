package com.notcode.mobile.runtime

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SafeTarExtractorTest {
    @get:Rule val temporary = TemporaryFolder()

    private data class Item(
        val name: String,
        val type: Byte = TarConstants.LF_NORMAL,
        val content: ByteArray = byteArrayOf(),
        val target: String = "",
        val mode: Int = 420,
        val paxPath: String? = null,
    )

    private fun archive(vararg items: Item): File {
        val archive = temporary.newFile()
        GzipCompressorOutputStream(archive.outputStream()).use { gzip ->
            TarArchiveOutputStream(gzip).use { tar ->
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                for (item in items) {
                    val entry = TarArchiveEntry(item.name, item.type, true)
                    entry.mode = item.mode
                    entry.linkName = item.target
                    entry.size = item.content.size.toLong()
                    item.paxPath?.let { entry.addPaxHeader("path", it) }
                    tar.putArchiveEntry(entry)
                    tar.write(item.content)
                    tar.closeArchiveEntry()
                }
            }
        }
        return archive
    }

    private fun file(name: String, text: String = "payload", mode: Int = 420) =
        Item(name, content = text.toByteArray(), mode = mode)

    private fun directory(name: String) = Item(name, TarConstants.LF_DIR, mode = 493)
    private fun symlink(name: String, target: String) = Item(name, TarConstants.LF_SYMLINK, target = target)
    private fun hardlink(name: String, target: String) = Item(name, TarConstants.LF_LINK, target = target)

    private suspend fun rejects(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected IOException")
        } catch (_: IOException) {
            // Expected validation failure; the caller owns cleanup.
        }
    }

    private fun requireUnix() {
        assumeTrue("Unix filesystem semantics required", File.separatorChar == '/')
    }

    @Test fun extractsFilesAndRepeatedDirectoryMetadataAndRootEntries() = runBlocking {
        val destination = temporary.newFolder()
        SafeTarExtractor.extract(archive(
            directory("./"), directory("usr/"), file("./usr/message", "hello"),
            directory("usr/"), directory("."),
        ), destination)
        assertEquals("hello", File(destination, "usr/message").readText())
    }

    @Test fun preservesExecutableMode() = runBlocking {
        requireUnix()
        val destination = temporary.newFolder()
        SafeTarExtractor.extract(archive(file("pwsh", mode = 493), file("data")), destination)
        assertTrue(File(destination, "pwsh").canExecute())
        assertFalse(File(destination, "data").canExecute())
    }

    @Test fun mapsAbsoluteSymlinksAndNormalizesInternalRelativeTargets() = runBlocking {
        requireUnix()
        val destination = temporary.newFolder()
        SafeTarExtractor.extract(archive(
            file("usr/bin/pwsh", "shell"), directory("bin/"),
            symlink("bin/pwsh", "/usr/bin/pwsh"),
            symlink("bin/relative", "../usr/unused/../bin/pwsh"),
            symlink("root", "/"), symlink("bin/self", "."),
        ), destination)
        assertEquals("shell", File(destination, "bin/pwsh").readText())
        assertEquals("shell", File(destination, "bin/relative").readText())
        assertEquals("../usr/bin/pwsh", Files.readSymbolicLink(File(destination, "bin/pwsh").toPath()).toString())
        assertEquals(destination.canonicalFile, File(destination, "root").canonicalFile)
        assertFalse(Files.readSymbolicLink(File(destination, "root").toPath()).isAbsolute)
        assertEquals(".", Files.readSymbolicLink(File(destination, "bin/self").toPath()).toString())
    }

    @Test fun defersHardlinksUntilNormalTargetsExist() = runBlocking {
        requireUnix()
        val destination = temporary.newFolder()
        SafeTarExtractor.extract(archive(hardlink("bin/copy", "./usr/pwsh"), file("usr/pwsh")), destination)
        assertTrue(Files.isSameFile(File(destination, "bin/copy").toPath(), File(destination, "usr/pwsh").toPath()))
    }

    @Test fun rejectsHardlinksToSymlinksMissingFilesAndDirectories() = runBlocking {
        requireUnix()
        val cases = listOf(
            arrayOf(hardlink("copy", "link"), symlink("link", "normal"), file("normal")),
            arrayOf(hardlink("copy", "missing")),
            arrayOf(hardlink("copy", "dir"), directory("dir/")),
            arrayOf(hardlink("copy", "../outside")),
            arrayOf(hardlink("copy", "/etc/passwd")),
            arrayOf(hardlink("copy", "link/data"), symlink("link", "real"), file("real/data")),
        )
        for (items in cases) rejects { SafeTarExtractor.extract(archive(*items), temporary.newFolder()) }
    }

    @Test fun rejectsTraversalAndAbsoluteEntryNames() = runBlocking {
        for (name in listOf("../outside", "a/../../outside", "a/../outside", "/etc/passwd", "C:/outside", "a\\..\\outside")) {
            rejects { SafeTarExtractor.extract(archive(file(name)), temporary.newFolder()) }
        }
    }

    @Test fun rejectsPaxPathTraversal() = runBlocking {
        rejects {
            SafeTarExtractor.extract(archive(Item("safe", content = byteArrayOf(1), paxPath = "../escaped")), temporary.newFolder())
        }
    }

    @Test fun rejectsWritingThroughSymlinkAncestors() = runBlocking {
        requireUnix()
        for (child in listOf(file("alias/data"), directory("alias/sub/"), hardlink("alias/copy", "real/data"))) {
            val destination = temporary.newFolder()
            rejects {
                SafeTarExtractor.extract(archive(directory("real/"), symlink("alias", "real"), child), destination)
            }
            assertFalse(File(destination, "real/data").exists())
            assertFalse(File(destination, "real/sub").exists())
        }
    }

    @Test fun rejectsEscapingSymlinkTargets() = runBlocking {
        requireUnix()
        for (target in listOf("../../outside", "/../../outside", "C:/outside", "..\\outside")) {
            rejects { SafeTarExtractor.extract(archive(symlink("bin/link", target)), temporary.newFolder()) }
        }
    }

    @Test fun rejectsDuplicateAndTypeCollisions() = runBlocking {
        val cases = listOf(
            arrayOf(file("data"), file("./data")),
            arrayOf(directory("data/"), file("data")),
            arrayOf(file("data"), directory("data/")),
            arrayOf(hardlink("data", "normal"), file("data"), file("normal")),
            arrayOf(hardlink("data", "normal"), directory("data/"), file("normal")),
            arrayOf(file("data/child"), file("data")),
        )
        for (items in cases) rejects { SafeTarExtractor.extract(archive(*items), temporary.newFolder()) }
    }

    @Test fun rejectsSymlinkReplacementEvenWhenDangling() = runBlocking {
        requireUnix()
        rejects {
            SafeTarExtractor.extract(archive(symlink("data", "missing"), file("data")), temporary.newFolder())
        }
    }

    @Test fun boundsExpandedBytesIncludingSkippedSpecialEntriesAndPaxMetadata() = runBlocking {
        rejects {
            SafeTarExtractor.extract(archive(Item("bomb", content = ByteArray(128 * 1024))), temporary.newFolder(), maxBytes = 4096)
        }
        rejects {
            SafeTarExtractor.extract(archive(Item("dev/fifo", TarConstants.LF_FIFO)), temporary.newFolder(), maxBytes = 1)
        }
        rejects {
            SafeTarExtractor.extract(archive(Item("safe", paxPath = "a".repeat(8192))), temporary.newFolder(), maxBytes = 4096)
        }
    }

    @Test fun boundsEntryCountIncludingRepeatedDirectoriesAndSpecialEntries() = runBlocking {
        rejects {
            SafeTarExtractor.extract(archive(directory("./"), directory("./")), temporary.newFolder(), maxEntries = 1)
        }
        rejects {
            SafeTarExtractor.extract(archive(Item("dev/fifo", TarConstants.LF_FIFO), file("data")), temporary.newFolder(), maxEntries = 1)
        }
        rejects { SafeTarExtractor.extract(archive(file("data")), temporary.newFolder(), maxEntries = 0) }
    }

    @Test fun skipsDeviceAndFifoEntries() = runBlocking {
        val destination = temporary.newFolder()
        SafeTarExtractor.extract(archive(
            Item("dev/char", TarConstants.LF_CHR), Item("dev/block", TarConstants.LF_BLK),
            Item("dev/fifo", TarConstants.LF_FIFO), file("normal"),
        ), destination)
        for (name in listOf("char", "block", "fifo")) {
            assertFalse(Files.exists(File(destination, "dev/$name").toPath(), NOFOLLOW_LINKS))
        }
        assertEquals("payload", File(destination, "normal").readText())
    }

    @Test fun requiresExistingEmptyNonSymlinkDestination() = runBlocking {
        val source = archive(file("data"))
        val occupied = temporary.newFolder()
        File(occupied, "owned").writeText("keep")
        rejects { SafeTarExtractor.extract(source, occupied) }
        assertEquals("keep", File(occupied, "owned").readText())
        rejects { SafeTarExtractor.extract(source, File(temporary.root, "missing")) }
        requireUnix()
        val link = File(temporary.root, "destination-link").toPath()
        Files.createSymbolicLink(link, temporary.newFolder().toPath())
        rejects { SafeTarExtractor.extract(source, link.toFile()) }
    }

    @Test fun honorsCancellationBeforeReading() {
        val source = archive(file("data"))
        val destination = temporary.newFolder()
        val job = Job().apply { cancel() }
        try {
            runBlocking(job) { SafeTarExtractor.extract(source, destination) }
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            assertTrue(destination.listFiles()!!.isEmpty())
        }
    }
}
