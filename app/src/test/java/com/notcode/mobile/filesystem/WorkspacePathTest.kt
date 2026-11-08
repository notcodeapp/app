package com.notcode.mobile.filesystem

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspacePathTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun path(value: String) = ProjectPath.parse(value)
    private fun workspace() = WorkspaceProjectFileSystem(File(temporary.root, "workspace"))

    private suspend inline fun <reified T : Throwable> fails(noinline action: suspend () -> Unit) {
        try {
            action()
            fail("Expected ${T::class.java.simpleName}")
        } catch (error: Throwable) {
            if (error !is T) throw error
        }
    }

    @Test fun rejectsTraversalAbsoluteAndDrivePaths() = runBlocking {
        for (raw in listOf("../outside", "a/../../outside", "/outside", "C:/outside", "a/./b", "a\u0000b")) {
            fails<IllegalArgumentException> { path(raw) }
        }
        val fs = workspace()
        for (raw in listOf("C:outside", "a:b", "folder./file", "folder /file")) {
            fails<IllegalArgumentException> { fs.writeText(path(raw), "escape") }
        }
        fs.writeText(path("nested/file.txt"), "inside")
        assertEquals("inside", fs.readText(path("nested/file.txt"), 6).text)
        assertFalse(File(temporary.root, "outside").exists())
        assertTrue(fs.exists(ProjectPath.Root))
    }

    @Test fun rawStagingCopyPreservesBinaryBytesAndHidesStagingFolder() = runBlocking {
        val fs = workspace()
        fs.directory.mkdirs()
        val source = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00, 0x7F, 0xFF.toByte())
        File(fs.directory, "sample.apk").writeBytes(source)
        val staging = File(fs.directory, ".notcode-staging").apply { mkdirs() }
        val destination = File(staging, "sample.apk")

        assertEquals(source.size.toLong(), fs.copyRawFileTo(path("sample.apk"), destination, 1024))
        assertArrayEquals(source, destination.readBytes())
        assertTrue(fs.list(ProjectPath.Root).none { it.name == ".notcode-staging" })
        fails<ProjectFileTooLargeException> {
            fs.copyRawFileTo(path("sample.apk"), File(staging, "too-large.apk"), 2)
        }
    }

    @Test fun rejectsSymlinksIncludingLinksPointingInside() = runBlocking {
        val fs = workspace()
        fs.writeText(path("real/file.txt"), "safe")
        val link = File(fs.directory, "link")
        try {
            Files.createSymbolicLink(link.toPath(), File(fs.directory, "real").toPath())
        } catch (error: Exception) {
            assumeNoException("Symbolic links unavailable on this host", error)
        }
        fails<ProjectFileException> { fs.readText(path("link/file.txt"), 100) }
        fails<ProjectFileException> { fs.writeText(path("link/new.txt"), "bad") }
        fails<ProjectFileException> { fs.exists(path("link")) }
        fails<ProjectFileException> { fs.deleteFile(path("link/file.txt")) }
        fails<ProjectFileException> { fs.renameFile(path("link/file.txt"), path("new")) }
        fails<ProjectFileException> { fs.renameFile(path("real/file.txt"), path("link/new.txt")) }
        assertEquals("safe", fs.readText(path("real/file.txt"), 100).text)
        fails<ProjectFileException> { fs.list(ProjectPath.Root) }
    }

    @Test fun rejectsSymlinkedWorkspaceAndOutsideSibling() = runBlocking {
        val target = temporary.newFolder("outside")
        val link = File(temporary.root, "workspace")
        try {
            Files.createSymbolicLink(link.toPath(), target.toPath())
        } catch (error: Exception) {
            assumeNoException("Symbolic links unavailable on this host", error)
        }
        fails<ProjectFileException> { workspace().writeText(path("file.txt"), "bad") }
        assertFalse(File(target, "file.txt").exists())
    }

    @Test fun boundedReadsRejectBinaryAndMalformedUtf8() = runBlocking {
        val fs = workspace()
        fs.writeText(path("utf8.txt"), "Я")
        assertEquals("Я", fs.readText(path("utf8.txt"), 2).text)
        fails<ProjectFileTooLargeException> { fs.readText(path("utf8.txt"), 1) }
        fails<IllegalArgumentException> { fs.readText(path("utf8.txt"), -1) }
        assertEquals("Я", fs.readText(path("utf8.txt"), Long.MAX_VALUE).text)
        File(fs.directory, "binary.dat").writeBytes(byteArrayOf(65, 0, 66))
        File(fs.directory, "invalid").writeBytes(byteArrayOf(0xC3.toByte(), 0x28))
        fails<ProjectBinaryFileException> { fs.readText(path("binary.dat"), 3) }
        fails<ProjectBinaryFileException> { fs.readText(path("invalid"), 2) }
        fs.writeText(path("empty"), "")
        assertEquals("", fs.readText(path("empty"), 0).text)
    }

    @Test fun hashRevisionDetectsSameSizeSameTimestampEditsAndDeletion() = runBlocking {
        val fs = workspace()
        val original = fs.writeText(path("a"), "one")
        val file = File(fs.directory, "a")
        file.writeText("two")
        assertTrue(file.setLastModified(original.lastModifiedEpochMs))
        fails<ProjectFileConflictException> { fs.writeText(path("a"), "new", original) }
        assertEquals("two", file.readText())
        file.delete()
        fails<ProjectFileConflictException> { fs.writeText(path("a"), "new", original) }
    }

    @Test fun importsOncePersistsManifestAndExportsOnlyChanges() = runBlocking {
        val source = FakeFs()
        source.put("src/a.txt", "initial")
        source.put("unchanged.txt", "keep")
        for (ignored in listOf(".git", ".gradle", "node_modules", "build")) {
            source.put("$ignored/ignored", "skip")
        }
        val fs = workspace()
        fs.importFrom(source)
        assertTrue(File(temporary.root, "workspace-sync.json").isFile)
        assertFalse(File(fs.directory, "workspace-sync.json").exists())
        assertFalse(fs.exists(path(".git")))
        fs.writeText(path("src/a.txt"), "local")
        source.put("unchanged.txt", "external change is not ours to overwrite")
        WorkspaceProjectFileSystem(fs.directory).importFrom(source)
        assertEquals("local", fs.readText(path("src/a.txt"), 100).text)
        assertEquals(1, WorkspaceProjectFileSystem(fs.directory).exportTo(source))
        assertEquals(listOf("src/a.txt"), source.writes)
        assertEquals("local", source.contents["src/a.txt"])
        assertNotNull(source.expected.single())
        assertEquals(0, WorkspaceProjectFileSystem(fs.directory).exportTo(source))
    }

    @Test fun exportDetectsExternalContentChangesEvenWithUnchangedMetadata() = runBlocking {
        val source = FakeFs()
        source.put("a", "one")
        val fs = workspace()
        fs.importFrom(source)
        fs.writeText(path("a"), "local")
        source.put("a", "two", changeRevision = false)
        fails<ProjectFileConflictException> { fs.exportTo(source) }
        assertTrue(source.writes.isEmpty())
        assertEquals("two", source.contents["a"])
    }

    @Test fun newFilesRefuseExistingRemoteAndLocalDeletionNeverDeletesRemote() = runBlocking {
        val source = FakeFs()
        source.put("old", "keep")
        val fs = workspace()
        fs.importFrom(source)
        File(fs.directory, "old").delete()
        fs.writeText(path("new"), "local")
        source.put("new", "remote")
        fails<ProjectFileConflictException> { fs.exportTo(source) }
        assertEquals("remote", source.contents["new"])
        source.contents.remove("new")
        source.revisions.remove("new")
        assertEquals(1, fs.exportTo(source))
        assertEquals("keep", source.contents["old"])
        assertNull(source.expected.single())
        assertEquals(0, fs.exportTo(source))
    }

    @Test fun deletedRemoteAndWriteTimeRaceAreConflicts() = runBlocking {
        val source = FakeFs()
        source.put("a", "one")
        val fs = workspace()
        fs.importFrom(source)
        fs.writeText(path("a"), "local")
        source.contents.remove("a")
        fails<ProjectFileConflictException> { fs.exportTo(source) }
        source.contents["a"] = "one"
        source.raceOnWrite = true
        fails<ProjectFileConflictException> { fs.exportTo(source) }
        assertEquals("raced", source.contents["a"])
    }

    @Test fun revertedChangesNeedNoExportAndExportHonorsLimits() = runBlocking {
        val source = FakeFs()
        source.put("a", "original")
        val fs = workspace()
        fs.importFrom(source)
        fs.writeText(path("a"), "edited")
        fs.writeText(path("a"), "original")
        assertEquals(0, fs.exportTo(source))
        repeat(2000) { File(fs.directory, "new$it").writeText("") }
        fails<ProjectFileException> { fs.exportTo(source) }
        assertTrue(source.writes.isEmpty())
    }

    @Test fun refusesUnmarkedDirtyWorkspaceAndCorruptManifest() = runBlocking {
        val fs = workspace()
        fs.writeText(path("dirty"), "keep")
        fails<ProjectFileException> { fs.importFrom(FakeFs()) }
        File(temporary.root, "workspace-sync.json").writeText("invalid")
        fails<ProjectFileException> { fs.importFrom(FakeFs()) }
        fails<ProjectFileException> { fs.exportTo(FakeFs()) }
        assertEquals("keep", fs.readText(path("dirty"), 100).text)
    }

    @Test fun importLimitsFilesAndBytesBeforeCopying() = runBlocking {
        val source = FakeFs()
        repeat(2001) { source.put("file$it", "") }
        val fs = workspace()
        fails<ProjectFileException> { fs.importFrom(source) }
        assertTrue(fs.directory.listFiles()!!.isEmpty())
        source.contents.clear()
        source.revisions.clear()
        source.put("huge", "a")
        source.reportedReadSize = 120L * 1024 * 1024 + 1
        fails<ProjectFileTooLargeException> { fs.importFrom(source) }
        assertFalse(File(temporary.root, "workspace-sync.json").exists())
    }

    @Test fun synchronizeNewAndChangedFilesBothWaysAndRepeat() = runBlocking {
        val source = FakeFs().apply { put("external", "old"); put("local", "old") }
        val fs = workspace()
        fs.importFrom(source)
        source.put("external", "new", changeRevision = false)
        source.put("remote/new.kt", "remote")
        fs.writeText(path("local"), "edited")
        fs.writeText(path("src/new.kt"), "local")
        // Initial import remains deliberately a no-op even for new remote files.
        fs.importFrom(source)
        assertFalse(fs.exists(path("remote/new.kt")))
        assertEquals(WorkspaceProjectFileSystem.SyncResult(2, 2), fs.synchronize(source))
        assertEquals("new", fs.readText(path("external"), 100).text)
        assertEquals("remote", fs.readText(path("remote/new.kt"), 100).text)
        assertEquals("edited", source.contents["local"])
        assertEquals("local", source.contents["src/new.kt"])
        assertEquals(WorkspaceProjectFileSystem.SyncResult(0, 0), WorkspaceProjectFileSystem(fs.directory).synchronize(source))
        assertEquals(2, source.writes.size)
    }

    @Test fun synchronizePreflightsAllConflictsBeforeLocalRemoteOrManifestWrites() = runBlocking {
        val source = FakeFs().apply { put("z", "base"); put("download", "base") }
        val fs = workspace()
        fs.importFrom(source)
        fs.writeText(path("a-upload"), "upload")
        fs.writeText(path("z"), "ours")
        source.put("z", "theirs", changeRevision = false)
        source.put("download", "changed")
        val marker = File(temporary.root, "workspace-sync.json")
        val before = marker.readText()
        fails<ProjectFileConflictException> { fs.synchronize(source) }
        assertEquals(before, marker.readText())
        assertTrue(source.writes.isEmpty())
        assertEquals("base", fs.readText(path("download"), 100).text)
        assertEquals("ours", fs.readText(path("z"), 100).text)
        assertFalse(source.exists(path("a-upload")))
    }

    @Test fun synchronizeConvergesIdenticalEditsAndNewFilesWithoutWrites() = runBlocking {
        val source = FakeFs().apply { put("a", "base") }
        val fs = workspace()
        fs.importFrom(source)
        fs.writeText(path("a"), "same edit")
        source.put("a", "same edit")
        fs.writeText(path("new"), "same new")
        source.put("new", "same new")
        assertEquals(WorkspaceProjectFileSystem.SyncResult(0, 0), fs.synchronize(source))
        assertTrue(source.writes.isEmpty())
        fs.writeText(path("a"), "next local edit")
        assertEquals(WorkspaceProjectFileSystem.SyncResult(1, 0), WorkspaceProjectFileSystem(fs.directory).synchronize(source))
        source.put("new", "next remote edit")
        assertEquals(WorkspaceProjectFileSystem.SyncResult(0, 1), fs.synchronize(source))
    }

    @Test fun synchronizeRefreshesMetadataWithoutTreatingItAsAnEdit() = runBlocking {
        val source = FakeFs().apply { put("a", "base") }
        val fs = workspace()
        fs.importFrom(source)
        val marker = File(temporary.root, "workspace-sync.json")
        val before = marker.readText()
        source.put("a", "base")
        assertEquals(WorkspaceProjectFileSystem.SyncResult(0, 0), fs.synchronize(source))
        assertNotEquals(before, marker.readText())
        assertTrue(source.writes.isEmpty())
        source.put("a", "base") // Metadata can change again alongside a local edit.
        val expected = source.revisions["a"]
        fs.writeText(path("a"), "edited")
        assertEquals(WorkspaceProjectFileSystem.SyncResult(1, 0), fs.synchronize(source))
        assertEquals(expected, source.expected.single())
        assertEquals(0, fs.exportTo(source))
    }

    @Test fun synchronizeRestoresMissingFilesWithoutPropagatingDeletions() = runBlocking {
        val source = FakeFs().apply {
            for (name in listOf("remote-missing", "local-missing", "remote-missing-edited", "local-missing-edited", "both")) put(name, "base")
        }
        val fs = workspace()
        fs.importFrom(source)
        for (name in listOf("remote-missing", "remote-missing-edited", "both")) source.contents.remove(name)
        for (name in listOf("local-missing", "local-missing-edited", "both")) assertTrue(File(fs.directory, name).delete())
        fs.writeText(path("remote-missing-edited"), "local survives")
        source.put("local-missing-edited", "remote survives")
        assertEquals(WorkspaceProjectFileSystem.SyncResult(2, 2), fs.synchronize(source))
        assertEquals("base", source.contents["remote-missing"])
        assertEquals("local survives", source.contents["remote-missing-edited"])
        assertEquals("base", fs.readText(path("local-missing"), 100).text)
        assertEquals("remote survives", fs.readText(path("local-missing-edited"), 100).text)
        assertFalse(fs.exists(path("both")))
        assertFalse(source.exists(path("both")))
        assertEquals(WorkspaceProjectFileSystem.SyncResult(0, 0), fs.synchronize(source))
    }

    @Test fun synchronizeRejectsNewDivergentFilesAndFileDirectoryCollisions() = runBlocking {
        val source = FakeFs()
        val fs = workspace()
        fs.importFrom(source)
        fs.writeText(path("a"), "ours")
        source.put("a", "theirs")
        fails<ProjectFileConflictException> { fs.synchronize(source) }
        source.contents.clear()
        source.put("a/child", "theirs")
        fails<ProjectFileConflictException> { fs.synchronize(source) }
        assertTrue(source.writes.isEmpty())
        assertEquals("ours", fs.readText(path("a"), 100).text)
    }

    @Test fun synchronizeDoesNotCheckpointFailedWriteVerification() = runBlocking {
        val source = FakeFs().apply { put("a", "base") }
        val fs = workspace()
        fs.importFrom(source)
        fs.writeText(path("a"), "edit")
        val marker = File(temporary.root, "workspace-sync.json")
        val before = marker.readText()
        source.ignoreWrites = true
        fails<ProjectFileException> { fs.synchronize(source) }
        assertEquals(before, marker.readText())
        source.ignoreWrites = false
        assertEquals(WorkspaceProjectFileSystem.SyncResult(1, 0), fs.synchronize(source))
    }

    @Test fun explicitCreateRenameDeleteSurvivesReopenAndSyncs() = runBlocking {
        val source = FakeFs().apply { put("old", "base"); put("remove", "gone") }
        val fs = workspace()
        fs.importFrom(source)
        val original = fs.readText(path("old"), 100).revision
        val moved = fs.renameFile(path("old"), path("nested/new.kt"), original)
        assertEquals(moved, fs.readText(path("nested/new.kt"), 100).revision)
        fs.deleteFile(path("remove"), fs.readText(path("remove"), 100).revision)
        fs.writeText(path("created"), "new")
        assertFalse(fs.exists(path("old")))
        val reopened = WorkspaceProjectFileSystem(fs.directory)
        assertEquals(WorkspaceProjectFileSystem.SyncResult(2, 0), reopened.synchronize(source))
        assertEquals("base", source.contents["nested/new.kt"])
        assertEquals("new", source.contents["created"])
        assertFalse(source.exists(path("old")))
        assertFalse(source.exists(path("remove")))
        assertEquals(setOf("old", "remove"), source.deletes.toSet())
        assertEquals(WorkspaceProjectFileSystem.SyncResult(0, 0), reopened.synchronize(source))
        assertFalse(File(temporary.root, "workspace-sync.json").readText().contains("\"old\""))
    }

    @Test fun deletionAndRenamePreflightRemoteEditsBeforeAnyWrites() = runBlocking {
        for (rename in listOf(false, true)) {
            val source = FakeFs().apply { put("z", "base"); put("download", "base") }
            val fs = WorkspaceProjectFileSystem(File(temporary.root, "workspace-$rename"))
            fs.importFrom(source)
            if (rename) fs.renameFile(path("z"), path("a-renamed")) else fs.deleteFile(path("z"))
            fs.writeText(path("a-created"), "new")
            source.put("z", "edit", changeRevision = false)
            source.put("download", "changed")
            val manifest = File(temporary.root, "workspace-$rename-sync.json")
            val before = manifest.readText()
            fails<ProjectFileConflictException> { WorkspaceProjectFileSystem(fs.directory).synchronize(source) }
            assertEquals(before, manifest.readText())
            assertTrue(source.writes.isEmpty())
            assertTrue(source.deletes.isEmpty())
            assertEquals("base", fs.readText(path("download"), 100).text)
        }
    }

    @Test fun deletionAlreadyMissingRemotelyAndNewLocalDeletionNeedNoUpload() = runBlocking {
        val source = FakeFs().apply { put("old", "base") }
        val fs = workspace()
        fs.importFrom(source)
        fs.deleteFile(path("old"))
        source.contents.remove("old")
        fs.writeText(path("new"), "temporary")
        fs.deleteFile(path("new"))
        assertEquals(WorkspaceProjectFileSystem.SyncResult(0, 0), fs.synchronize(source))
        assertTrue(source.deletes.isEmpty())
        assertTrue(source.writes.isEmpty())
        source.put("old", "new remote file")
        fs.synchronize(source)
        assertEquals("new remote file", fs.readText(path("old"), 100).text)
    }

    @Test fun partialFailurePreservesRemainingTombstonesAndCheckpointsUploads() = runBlocking {
        val source = FakeFs().apply { put("a", "one"); put("b", "two") }
        val fs = workspace()
        fs.importFrom(source)
        fs.renameFile(path("a"), path("renamed"))
        fs.deleteFile(path("b"))
        source.failDelete = "b"
        fails<ProjectFileException> { fs.synchronize(source) }
        assertFalse(source.exists(path("a")))
        assertEquals("one", source.contents["renamed"])
        assertEquals("two", source.contents["b"])
        source.failDelete = null
        assertEquals(WorkspaceProjectFileSystem.SyncResult(0, 0), WorkspaceProjectFileSystem(fs.directory).synchronize(source))
        assertFalse(source.exists(path("b")))
        assertEquals(listOf("renamed"), source.writes)
    }

    @Test fun mutationsRejectStaleRevisionsDirectoriesExistingTargetsAndUnsafePaths() = runBlocking {
        val fs = workspace()
        val revision = fs.writeText(path("a"), "one")
        fs.writeText(path("a"), "two")
        fails<ProjectFileConflictException> { fs.deleteFile(path("a"), revision) }
        fails<ProjectFileConflictException> { fs.renameFile(path("a"), path("new"), revision) }
        fs.writeText(path("nested/child"), "keep")
        fails<ProjectFileException> { fs.deleteFile(path("nested")) }
        fails<ProjectFileException> { fs.renameFile(path("nested"), path("new")) }
        fails<ProjectFileConflictException> { fs.renameFile(path("a"), path("nested/child")) }
        fails<ProjectFileConflictException> { fs.renameFile(path("a"), path("a")) }
        fails<IllegalArgumentException> { fs.deleteFile(ProjectPath.Root) }
        fails<IllegalArgumentException> { fs.renameFile(path("a"), ProjectPath.Root) }
        for (raw in listOf("../outside", "/outside", "C:/outside", "C:outside")) {
            fails<IllegalArgumentException> { fs.deleteFile(path(raw)) }
            fails<IllegalArgumentException> { fs.renameFile(path("a"), path(raw)) }
        }
        assertEquals("two", fs.readText(path("a"), 100).text)
        assertEquals("keep", fs.readText(path("nested/child"), 100).text)
    }

    @Test fun deleteThenRecreateStillChecksOriginalRemoteContent() = runBlocking {
        val source = FakeFs().apply { put("a", "base") }
        val fs = workspace()
        fs.importFrom(source)
        fs.deleteFile(path("a"))
        fs.writeText(path("a"), "replacement")
        source.put("a", "remote edit")
        fails<ProjectFileConflictException> { fs.synchronize(source) }
        source.put("a", "base")
        assertEquals(WorkspaceProjectFileSystem.SyncResult(1, 0), fs.synchronize(source))
        assertEquals("replacement", source.contents["a"])
        assertEquals(WorkspaceProjectFileSystem.SyncResult(0, 0), fs.synchronize(source))
    }

    private class FakeFs : ProjectFileSystem {
        val contents = linkedMapOf<String, String>()
        val revisions = linkedMapOf<String, FileRevision>()
        val writes = mutableListOf<String>()
        val deletes = mutableListOf<String>()
        var failDelete: String? = null
        val expected = mutableListOf<FileRevision?>()
        var reportedReadSize: Long? = null
        var raceOnWrite = false
        var ignoreWrites = false
        private var sequence = 0L

        fun put(path: String, text: String, changeRevision: Boolean = true) {
            contents[path] = text
            if (changeRevision) revisions[path] = FileRevision("fake:$path", text.toByteArray().size.toLong(), ++sequence)
        }

        override suspend fun list(directory: ProjectPath): List<ProjectEntry> {
            val prefix = if (directory.isRoot) "" else "${directory.value}/"
            return contents.keys.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix).substringBefore('/') }
                .distinct().map { name ->
                    val child = directory.child(name)
                    val folder = child.value !in contents
                    ProjectEntry(child, name, folder, revisions[child.value]?.size ?: 0, 0)
                }
        }

        override suspend fun readText(path: ProjectPath, maxBytes: Long): TextDocument {
            val text = contents[path.value] ?: throw ProjectFileNotFoundException(path)
            if ((reportedReadSize ?: text.toByteArray().size.toLong()) > maxBytes) {
                throw ProjectFileTooLargeException(path, maxBytes)
            }
            return TextDocument(path, text, revisions.getValue(path.value))
        }

        override suspend fun exists(path: ProjectPath) = path.isRoot || path.value in contents ||
            contents.keys.any { it.startsWith("${path.value}/") }

        override suspend fun deleteFile(path: ProjectPath, expectedRevision: FileRevision?) {
            if (path.value == failDelete) throw ProjectFileException("Injected deletion failure")
            if (expectedRevision != null && revisions[path.value] != expectedRevision) {
                throw ProjectFileConflictException(path)
            }
            if (contents.remove(path.value) == null) throw ProjectFileNotFoundException(path)
            revisions.remove(path.value)
            deletes += path.value
        }

        override suspend fun writeText(path: ProjectPath, content: String, expectedRevision: FileRevision?): FileRevision {
            if (raceOnWrite) {
                raceOnWrite = false
                put(path.value, "raced")
            }
            if (expectedRevision != null && revisions[path.value] != expectedRevision) {
                throw ProjectFileConflictException(path)
            }
            writes += path.value
            expected += expectedRevision
            if (!ignoreWrites) put(path.value, content)
            return revisions.getValue(path.value)
        }
    }
}
