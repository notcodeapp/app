package com.notcode.mobile.filesystem

import android.Manifest
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Requires: adb shell mkdir -p /sdcard/Documents/NotCodeSyncTest. */
class SafProjectFileSystemTest {
    @Test fun realExternalStorageProviderPreservesNamesAndSynchronizes() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val automation = instrumentation.uiAutomation
        val tree = DocumentsContract.buildTreeDocumentUri(
            "com.android.externalstorage.documents", "primary:Documents/NotCodeSyncTest",
        )
        org.junit.Assume.assumeTrue("Select Documents/NotCodeSyncTest through the app folder picker before this test", context.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission && it.isWritePermission })
        val parent = requireNotNull(DocumentFile.fromTreeUri(context, tree))
        val testName = "run-${System.nanoTime()}"
        val folder = requireNotNull(parent.createDirectory(testName))
        val rootUri = DocumentsContract.buildTreeDocumentUri(
            tree.authority, DocumentsContract.getDocumentId(folder.uri),
        )
        val cache = File(context.cacheDir.canonicalFile, testName)
        try {
            // Reproduce the old MIME choice directly against the actual provider.
            val legacy = requireNotNull(folder.createFile("text/plain", "legacy.kt"))
            assertEquals("legacy.kt.txt", legacy.name)
            assertTrue(legacy.delete())

            val source = SafProjectFileSystem(context, rootUri)
            for (name in listOf("foo.kt", "script.unknownext", "README", ".gitignore", "file.txt")) {
                val path = ProjectPath.parse(name)
                val revision = source.writeText(path, "long initial content Я")
                assertEquals(name, folder.findFile(name)?.name)
                source.writeText(path, "Я", revision)
                assertEquals("Я", source.readText(path, 2).text)
                assertEquals(2L, folder.findFile(name)?.length())
            }
            val fs = WorkspaceProjectFileSystem(cache)
            fs.importFrom(source)
            fs.writeText(ProjectPath.parse("foo.kt"), "local edit")
            fs.writeText(ProjectPath.parse("src/new.kt"), "new local")
            source.writeText(ProjectPath.parse("external.kt"), "new external")
            source.writeText(ProjectPath.parse("README"), "external edit")
            assertEquals(WorkspaceProjectFileSystem.SyncResult(2, 2), fs.synchronize(source))
            assertEquals("local edit", source.readText(ProjectPath.parse("foo.kt"), 100).text)
            assertEquals("new local", source.readText(ProjectPath.parse("src/new.kt"), 100).text)
            assertEquals("new external", fs.readText(ProjectPath.parse("external.kt"), 100).text)
            assertEquals("external edit", fs.readText(ProjectPath.parse("README"), 100).text)
            assertEquals(WorkspaceProjectFileSystem.SyncResult(0, 0), fs.synchronize(source))
            assertFalse(source.exists(ProjectPath.parse("foo.kt.txt")))
            assertTrue(requireNotNull(folder.findFile("foo.kt")).delete())
            assertEquals(WorkspaceProjectFileSystem.SyncResult(1, 0), fs.synchronize(source))
            assertEquals("local edit", source.readText(ProjectPath.parse("foo.kt"), 100).text)

            val direct = ProjectPath.parse("direct.kt")
            val renamed = ProjectPath.parse("renamed.unknownext")
            val revision = source.writeText(direct, "direct")
            val renamedRevision = source.renameFile(direct, renamed, revision)
            assertFalse(source.exists(direct))
            assertEquals("direct", source.readText(renamed, 100).text)
            source.deleteFile(renamed, renamedRevision)
            assertFalse(source.exists(renamed))

            val old = ProjectPath.parse("foo.kt")
            val target = ProjectPath.parse("moved/new.unknownext")
            fs.renameFile(old, target, fs.readText(old, 100).revision)
            fs.deleteFile(ProjectPath.parse("script.unknownext"))
            val reopened = WorkspaceProjectFileSystem(cache)
            assertEquals(WorkspaceProjectFileSystem.SyncResult(1, 0), reopened.synchronize(source))
            assertFalse(source.exists(old))
            assertFalse(source.exists(ProjectPath.parse("script.unknownext")))
            assertEquals("local edit", source.readText(target, 100).text)
            assertEquals(WorkspaceProjectFileSystem.SyncResult(0, 0), reopened.synchronize(source))
        } finally {
            cache.deleteRecursively()
            File(cache.parentFile, "${cache.name}-sync.json").delete()
            folder.delete()
            automation.dropShellPermissionIdentity()
        }
    }
}
