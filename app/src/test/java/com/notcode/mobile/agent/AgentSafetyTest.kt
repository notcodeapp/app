package com.notcode.mobile.agent

import com.notcode.mobile.filesystem.*
import com.notcode.mobile.llm.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AgentSafetyTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun fs() = WorkspaceProjectFileSystem(File(temporary.root, "work"))
    private fun call(name: String, arguments: String) = CompleteToolCall("one", name, arguments)

    @Test fun rejectedWriteDoesNotTouchDisk() = runBlocking {
        val fs = fs()
        fs.writeText(ProjectPath.parse("main.txt"), "before")
        var prompted = false
        val executor = ToolExecutor(fs, Json, WriteApprovalGateway { prompted = true; false })
        val result = executor.execute(call("write_file", """{"filePath":"main.txt","content":"after"}"""))
        assertTrue(prompted)
        assertTrue(result.json.contains("USER_DENIED"))
        assertEquals("before", fs.readText(ProjectPath.parse("main.txt"), 100).text)
    }

    @Test fun approvedWriteChecksConcurrentChanges() = runBlocking {
        val fs = fs()
        val path = ProjectPath.parse("main.txt")
        fs.writeText(path, "before")
        val executor = ToolExecutor(fs, Json, WriteApprovalGateway {
            fs.writeText(path, "external change")
            true
        })
        try {
            executor.execute(call("write_file", """{"filePath":"main.txt","content":"after"}"""))
            fail("Expected conflict")
        } catch (_: ProjectFileConflictException) { }
        assertEquals("external change", fs.readText(path, 100).text)
    }

    @Test fun sensitiveFilesCannotBeReadOrSearched() = runBlocking {
        val fs = fs()
        fs.writeText(ProjectPath.parse(".env"), "TOP_SECRET_TOKEN")
        fs.writeText(ProjectPath.parse("main.txt"), "public")
        val executor = ToolExecutor(fs, Json, WriteApprovalGateway { true })
        try {
            executor.execute(call("read_file", """{"filePath":".env"}"""))
            fail("Expected sensitive path rejection")
        } catch (_: IllegalArgumentException) { }
        assertFalse(executor.execute(call("search_in_files", """{"query":"TOP_SECRET"}""")).json.contains("TOP_SECRET_TOKEN"))
        assertFalse(executor.execute(call("list_files", """{"dirPath":""}""")).json.contains(".env"))
    }

    @Test fun agentListsAndPreviewsBinaryFilesOutsideTextWorkspace() = runBlocking {
        val workspace = fs()
        val source = WorkspaceProjectFileSystem(File(temporary.root, "source"))
        val apk = File(source.directory, "app/build/outputs/app.apk")
        apk.parentFile!!.mkdirs()
        apk.writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00, 0x01, 0x02))
        val agentFs = AgentProjectFileSystem(workspace, source)
        val executor = ToolExecutor(agentFs, Json, WriteApprovalGateway { error("Must not prompt") }, readOnly = true)

        val listing = executor.execute(call("list_files", """{"dirPath":"app/build/outputs"}"""))
        assertTrue(listing.json.contains("app.apk"))
        assertTrue(listing.json.contains("\"editable\":false"))

        val preview = executor.execute(call("read_file", """{"filePath":"app/build/outputs/app.apk"}"""))
        assertTrue(preview.json.contains("\"previewOnly\":true"))
        assertTrue(preview.json.contains("\"format\":\"hex\""))
        assertTrue(preview.json.contains("50 4B 03 04"))
    }

    @Test fun unknownToolsAreNotExecuted() = runBlocking {
        val executor = ToolExecutor(fs(), Json, WriteApprovalGateway { error("Must not prompt") })
        assertTrue(executor.execute(call("run_command", """{"command":"ls"}""")).json.contains("UNKNOWN_TOOL"))
    }

    @Test fun rejectedFileMutationsHaveNoSideEffects() = runBlocking {
        val fs = fs()
        val source = ProjectPath.parse("main.txt")
        fs.writeText(source, "before")
        val proposals = mutableListOf<WriteProposal>()
        val executor = ToolExecutor(fs, Json, WriteApprovalGateway { proposals += it; false })
        listOf(
            call("create_file", """{"filePath":"new.txt","content":"new"}"""),
            call("rename_file", """{"filePath":"main.txt","targetPath":"new.txt"}"""),
            call("delete_file", """{"filePath":"main.txt"}"""),
        ).forEach { assertTrue(executor.execute(it).json.contains("USER_DENIED")) }
        assertEquals(listOf("create", "rename", "delete"), proposals.map { it.operation })
        assertNull(proposals[0].previousContent)
        assertEquals(ProjectPath.parse("new.txt"), proposals[1].targetPath)
        assertEquals("before", proposals[2].previousContent)
        assertEquals("", proposals[2].proposedContent)
        assertFalse(fs.exists(ProjectPath.parse("new.txt")))
        assertEquals("before", fs.readText(source, 100).text)
    }

    @Test fun createNeverOverwritesExistingOrApprovalTimeFiles() = runBlocking {
        val fs = fs()
        val path = ProjectPath.parse("new.txt")
        fs.writeText(path, "existing")
        val noPrompt = ToolExecutor(fs, Json, WriteApprovalGateway { error("Must not prompt") })
        val create = call("create_file", """{"filePath":"new.txt","content":"replacement"}""")
        assertTrue(noPrompt.execute(create).json.contains("FILE_EXISTS"))
        assertEquals("existing", fs.readText(path, 100).text)
        fs.deleteFile(path)
        val executor = ToolExecutor(fs, Json, WriteApprovalGateway {
            fs.writeText(path, "concurrent")
            true
        })
        assertTrue(executor.execute(create).json.contains("FILE_EXISTS"))
        assertEquals("concurrent", fs.readText(path, 100).text)
    }

    @Test fun renameAndDeleteCheckApprovedRevision() = runBlocking {
        val fs = fs()
        val path = ProjectPath.parse("main.txt")
        for (mutation in listOf(
            call("rename_file", """{"filePath":"main.txt","targetPath":"new.txt"}"""),
            call("delete_file", """{"filePath":"main.txt"}"""),
        )) {
            fs.writeText(path, "before")
            val executor = ToolExecutor(fs, Json, WriteApprovalGateway {
                fs.writeText(path, "concurrent")
                true
            })
            try {
                executor.execute(mutation)
                fail("Expected conflict")
            } catch (_: ProjectFileConflictException) { }
            assertEquals("concurrent", fs.readText(path, 100).text)
            assertFalse(fs.exists(ProjectPath.parse("new.txt")))
        }
    }

    @Test fun mutationsProtectBothPathsAndExistingRenameTargets() = runBlocking {
        val fs = fs()
        fs.writeText(ProjectPath.parse("main.txt"), "source")
        fs.writeText(ProjectPath.parse("other.txt"), "target")
        val executor = ToolExecutor(fs, Json, WriteApprovalGateway { error("Must not prompt") })
        listOf(
            call("create_file", """{"filePath":".env","content":"secret"}"""),
            call("delete_file", """{"filePath":".ssh/key"}"""),
            call("rename_file", """{"filePath":".env","targetPath":"new.txt"}"""),
            call("rename_file", """{"filePath":"main.txt","targetPath":".env"}"""),
        ).forEach {
            try {
                executor.execute(it)
                fail("Expected sensitive path rejection")
            } catch (_: IllegalArgumentException) { }
        }
        assertTrue(executor.execute(call("rename_file",
            """{"filePath":"main.txt","targetPath":"other.txt"}""")).json.contains("FILE_EXISTS"))
        assertEquals("source", fs.readText(ProjectPath.parse("main.txt"), 100).text)
        assertEquals("target", fs.readText(ProjectPath.parse("other.txt"), 100).text)
    }

    @Test fun renameDoesNotOverwriteDestinationCreatedDuringApproval() = runBlocking {
        val fs = fs()
        val source = ProjectPath.parse("main.txt")
        val target = ProjectPath.parse("new.txt")
        fs.writeText(source, "source")
        val executor = ToolExecutor(fs, Json, WriteApprovalGateway {
            fs.writeText(target, "concurrent")
            true
        })
        try {
            executor.execute(call("rename_file", """{"filePath":"main.txt","targetPath":"new.txt"}"""))
            fail("Expected conflict")
        } catch (_: ProjectFileConflictException) { }
        assertEquals("source", fs.readText(source, 100).text)
        assertEquals("concurrent", fs.readText(target, 100).text)
    }

    @Test fun fileSummariesReportLinesAndResultPaths() = runBlocking {
        val fs = fs()
        val executor = ToolExecutor(fs, Json, WriteApprovalGateway { true })
        val created = executor.execute(call("create_file", """{"filePath":"main.txt","content":"one\ntwo"}"""))
        val read = executor.execute(call("read_file", """{"filePath":"main.txt"}"""))
        val written = executor.execute(call("write_file", """{"filePath":"main.txt","content":"three\nfour"}"""))
        val renamed = executor.execute(call("rename_file", """{"filePath":"main.txt","targetPath":"new.txt"}"""))
        val deleted = executor.execute(call("delete_file", """{"filePath":"new.txt"}"""))
        listOf(created, read, written, renamed, deleted).forEach {
            assertTrue(it.summary.contains("2 lines"))
            assertFalse(it.summary.contains("chars"))
        }
        assertEquals(listOf("main.txt", "main.txt", "main.txt", "new.txt", "new.txt"),
            listOf(created, read, written, renamed, deleted).map { it.filePath })
        assertFalse(fs.exists(ProjectPath.parse("main.txt")))
        assertFalse(fs.exists(ProjectPath.parse("new.txt")))
        val empty = executor.execute(call("create_file", """{"filePath":"empty.txt","content":""}"""))
        assertTrue(empty.summary.contains("0 lines"))
    }

    @Test fun cancellationDuringApprovalStopsAgent() = runBlocking {
        val llm = object : LlmClient {
            override suspend fun chat(profileId: String, request: LlmRequest): LlmResponse = error("unused")
            override suspend fun testConnection(profileId: String): ConnectionTestResult = error("unused")
            override fun streamChat(profileId: String, request: LlmRequest): Flow<LlmStreamEvent> = flow {
                emit(LlmStreamEvent.ToolCallDelta(0, "one", "write_file", """{"filePath":"main.txt","content":"after"}"""))
                emit(LlmStreamEvent.Finished("tool_calls"))
            }
        }
        val fs = fs()
        val runner = AgentRunner(llm, ToolExecutor(fs, Json, WriteApprovalGateway { throw CancellationException("Stop") }))
        try {
            runner.run("test", listOf(LlmMessage.user("hello")), true) { }
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
        assertFalse(fs.exists(ProjectPath.parse("main.txt")))
    }
}
