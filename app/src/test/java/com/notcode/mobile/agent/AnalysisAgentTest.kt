package com.notcode.mobile.agent

import com.notcode.mobile.filesystem.ProjectPath
import com.notcode.mobile.filesystem.WorkspaceProjectFileSystem
import com.notcode.mobile.llm.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AnalysisAgentTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun fs() = WorkspaceProjectFileSystem(File(temporary.root, "work"))
    private fun call(name: String, arguments: String = "{}") = CompleteToolCall("one", name, arguments)

    private class FakeLlm(
        private val turn: suspend (Int, LlmRequest) -> List<LlmStreamEvent>,
    ) : LlmClient {
        val requests = mutableListOf<LlmRequest>()
        override suspend fun chat(profileId: String, request: LlmRequest): LlmResponse = error("unused")
        override suspend fun testConnection(profileId: String): ConnectionTestResult = error("unused")
        override fun streamChat(profileId: String, request: LlmRequest): Flow<LlmStreamEvent> = flow {
            // Snapshot history because the runner appends to its mutable copy after this request.
            requests += request.copy(messages = request.messages.toList())
            turn(requests.size, request).forEach { emit(it) }
        }
    }

    @Test fun readOnlyRejectsWritesAndAllExternalCallbacks() = runBlocking {
        val fs = fs()
        fs.writeText(ProjectPath.parse("main.txt"), "before")
        var callbacks = 0
        val executor = ToolExecutor(
            fs, Json, WriteApprovalGateway { error("Must not prompt") },
            externalTools = mapOf(
                "run_command" to { callbacks++; ToolExecutionResult("{}", "unexpected") },
                "delegate_analysis" to { callbacks++; ToolExecutionResult("{}", "unexpected") },
                "custom" to { callbacks++; ToolExecutionResult("{}", "unexpected") },
            ),
            readOnly = true,
        )
        listOf("write_file", "create_file", "rename_file", "delete_file").forEach { name ->
            assertTrue(executor.execute(call(name,
                """{"filePath":"main.txt","targetPath":"new.txt","content":"after"}""")).json.contains("READ_ONLY"))
        }
        assertFalse(fs.exists(ProjectPath.parse("new.txt")))
        listOf("run_command", "git", "delegate_analysis", "custom", "unknown").forEach {
            assertTrue(executor.execute(call(it)).json.contains("UNKNOWN_TOOL"))
        }
        assertEquals(0, callbacks)
        assertEquals("before", fs.readText(ProjectPath.parse("main.txt"), 100).text)
        assertTrue(executor.execute(call("read_file", """{"filePath":"main.txt"}""")).json.contains("before"))
    }

    @Test fun definitionsAreOptInAndMatchLocalGitContract() {
        val basic = ToolExecutor(fs(), Json, WriteApprovalGateway { false })
        assertEquals(ToolExecutor.definitions, basic.availableDefinitions)
        assertFalse(basic.availableDefinitions.any { it.name == "run_command" })
        assertTrue(basic.availableDefinitions.map { it.name }.containsAll(
            listOf("create_file", "rename_file", "delete_file"),
        ))
        val readOnly = ToolExecutor(fs(), Json, WriteApprovalGateway { false },
            extraDefinitions = ToolExecutor.developmentDefinitions, readOnly = true)
        assertEquals(setOf("list_files", "read_file", "search_in_files"), readOnly.availableDefinitions.map { it.name }.toSet())
        val extended = ToolExecutor(fs(), Json, WriteApprovalGateway { false }, extraDefinitions = ToolExecutor.developmentDefinitions)
        assertEquals(ToolExecutor.definitions + ToolExecutor.developmentDefinitions, extended.availableDefinitions)
        val git = ToolExecutor.developmentDefinitions.single { it.name == "git" }
        assertEquals(
            setOf("init", "status", "diff", "log", "commit"),
            git.parameters["properties"]!!.jsonObject["action"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet(),
        )
        assertEquals(listOf("action"), git.parameters["required"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test fun runnerUsesExecutorDefinitionsAndExternalCallback() = runBlocking {
        var executed = 0
        val llm = FakeLlm { turn, _ ->
            if (turn == 1) listOf(LlmStreamEvent.ToolCallDelta(0, "one", "run_command", """{"command":"test"}"""))
            else listOf(LlmStreamEvent.TextDelta("done"))
        }
        val tools = ToolExecutor(
            fs(), Json, WriteApprovalGateway { false },
            externalTools = mapOf("run_command" to { arguments ->
                assertEquals("test", arguments["command"]!!.jsonPrimitive.content)
                executed++
                ToolExecutionResult("""{"ok":true}""", "ran")
            }),
            extraDefinitions = ToolExecutor.developmentDefinitions,
        )
        val result = AgentRunner(llm, tools).run("test", listOf(LlmMessage.user("test")), true) { }
        assertTrue(result.completed)
        assertEquals(1, executed)
        assertEquals(tools.availableDefinitions, llm.requests.first().tools)
    }

    @Test fun runnerReportsBuiltInPathsAndUsesOnlyExplicitExternalResultPaths() = runBlocking {
        val events = mutableListOf<AgentEvent>()
        val llm = FakeLlm { turn, _ ->
            when (turn) {
                1 -> listOf(LlmStreamEvent.ToolCallDelta(0, "create", "create_file",
                    """{"filePath":"main.txt","content":"one\ntwo"}"""))
                2 -> listOf(LlmStreamEvent.ToolCallDelta(0, "rename", "rename_file",
                    """{"filePath":"main.txt","targetPath":"new.txt"}"""))
                3 -> listOf(LlmStreamEvent.ToolCallDelta(0, "external", "custom",
                    """{"filePath":"not-a-project-path"}"""))
                4 -> listOf(LlmStreamEvent.ToolCallDelta(0, "explicit", "custom_path", "{}"))
                5 -> listOf(LlmStreamEvent.ToolCallDelta(0, "invalid", "read_file", "not json"))
                else -> listOf(LlmStreamEvent.TextDelta("done"))
            }
        }
        val tools = ToolExecutor(fs(), Json, WriteApprovalGateway { true }, externalTools = mapOf(
            "custom" to { ToolExecutionResult("""{"ok":true}""", "external") },
            "custom_path" to { ToolExecutionResult("""{"ok":true}""", "explicit", "provided.txt") },
        ))
        AgentRunner(llm, tools).run("test", emptyList(), true) { events += it }
        assertEquals(listOf("main.txt", "main.txt", null, null, null),
            events.filterIsInstance<AgentEvent.ToolStarted>().map { it.filePath })
        val finished = events.filterIsInstance<AgentEvent.ToolFinished>()
        assertEquals(listOf("main.txt", "new.txt", null, "provided.txt", null), finished.map { it.filePath })
        assertTrue(finished.take(2).all { it.summary.contains("2 lines") })
        assertFalse(finished.last().successful)
    }

    @Test fun workerCannotDelegateOrWriteAndStopsAtThreeTurns() = runBlocking {
        val fs = fs()
        val events = mutableListOf<AgentEvent>()
        val llm = FakeLlm { turn, _ ->
            when (turn) {
                1 -> listOf(LlmStreamEvent.ToolCallDelta(0, "nested", "delegate_analysis", """{"task":"nested"}"""))
                2 -> listOf(LlmStreamEvent.ToolCallDelta(0, "write", "write_file", """{"filePath":"out.txt","content":"bad"}"""))
                else -> listOf(LlmStreamEvent.ToolCallDelta(0, "last", "read_file", """{"filePath":"missing"}"""))
            }
        }
        AnalysisAgent(llm, fs, Json).run("test", "inspect", { events.add(it); Unit })
        assertEquals(3, llm.requests.size)
        llm.requests.forEach { request ->
            assertEquals(setOf("list_files", "read_file", "search_in_files"), request.tools.map { it.name }.toSet())
        }
        assertEquals(listOf(LlmRole.SYSTEM, LlmRole.USER), llm.requests.first().messages.map { it.role })
        assertTrue(llm.requests.first().messages.first().content!!.contains("untrusted data"))
        val replies = llm.requests.last().messages.filter { it.role == LlmRole.TOOL }
        assertTrue(replies[0].content!!.contains("UNKNOWN_TOOL"))
        assertTrue(replies[1].content!!.contains("READ_ONLY"))
        assertFalse(fs.exists(ProjectPath.parse("out.txt")))
        assertEquals(2, events.filterIsInstance<AgentEvent.ToolStarted>().size)
        assertTrue(events.contains(AgentEvent.LimitReached(3)))
    }

    @Test fun workerExecutesAtMostFourCallsPerTurn() = runBlocking {
        val llm = FakeLlm { turn, _ ->
            if (turn == 1) (0..4).map {
                LlmStreamEvent.ToolCallDelta(it, "call_$it", "list_files", """{"dirPath":""}""")
            } else listOf(LlmStreamEvent.TextDelta("summary"))
        }
        val workspace = fs()
        workspace.writeText(ProjectPath.parse("sample.txt"), "sample")
        assertEquals("summary", AnalysisAgent(llm, workspace, Json).run("test", "inspect") { })
        val replies = llm.requests.last().messages.filter { it.role == LlmRole.TOOL }
        assertEquals(5, replies.size)
        assertTrue(replies.take(4).all { it.content!!.contains("\"ok\":true") })
        assertTrue(replies.last().content!!.contains("TOOL_LIMIT"))
    }

    @Test fun finalTurnNeverExecutesCallsButCanCompleteWithText() = runBlocking {
        var executions = 0
        val tools = ToolExecutor(fs(), Json, WriteApprovalGateway { false }, externalTools = mapOf(
            "custom" to { executions++; ToolExecutionResult("{}", "ran") },
        ))
        val llm = FakeLlm { _, _ -> listOf(LlmStreamEvent.ToolCallDelta(0, "one", "custom", "{}")) }
        val events = mutableListOf<AgentEvent>()
        val result = AgentRunner(llm, tools, maxModelTurns = 1).run("test", emptyList(), true, { events.add(it); Unit })
        assertFalse(result.completed)
        assertEquals(1, llm.requests.size)
        assertEquals(0, executions)
        assertTrue(events.contains(AgentEvent.LimitReached(1)))
        val textLlm = FakeLlm { _, _ -> listOf(LlmStreamEvent.TextDelta("done")) }
        assertTrue(AgentRunner(textLlm, tools, maxModelTurns = 1).run("test", emptyList(), true) { }.completed)
    }

    @Test fun workerSummaryIsUtf8BoundedAndHistoryIsIndependent() = runBlocking {
        val llm = FakeLlm { _, _ -> listOf(LlmStreamEvent.TextDelta("\uD83D\uDE00".repeat(5000))) }
        val worker = AnalysisAgent(llm, fs(), Json)
        repeat(2) {
            val result = worker.run("test", "task $it") { }
            assertEquals(16 * 1024, result.toByteArray(Charsets.UTF_8).size)
            assertFalse(result.last().isHighSurrogate())
        }
        assertEquals(2, llm.requests.size)
        assertTrue(llm.requests.all { it.messages.size == 2 })
        assertEquals("task 1", llm.requests.last().messages.last().content)
    }

    @Test fun workerAndExternalToolsPreserveCancellation() = runBlocking {
        val llm = FakeLlm { _, _ -> throw CancellationException("stop") }
        try {
            AnalysisAgent(llm, fs(), Json).run("test", "inspect") { }
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
        val callbackLlm = FakeLlm { _, _ -> listOf(LlmStreamEvent.ToolCallDelta(0, "one", "custom", "{}")) }
        val tools = ToolExecutor(fs(), Json, WriteApprovalGateway { false }, externalTools = mapOf(
            "custom" to { throw CancellationException("stop") },
        ))
        try {
            AgentRunner(callbackLlm, tools).run("test", emptyList(), true) { }
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
    }

    @Test fun oversizedToolNamesAndArgumentsAreRejectedButLargeChatContextIsSent() = runBlocking {
        val tools = ToolExecutor(fs(), Json, WriteApprovalGateway { false })
        for (invalid in listOf(call("x".repeat(129)), call("read_file", "x".repeat(512 * 1024 + 1)))) {
            try {
                tools.execute(invalid)
                fail("Expected tool limit rejection")
            } catch (_: IllegalArgumentException) { }
        }
        val largeMessage = "я".repeat(256 * 1024 + 1)
        val llm = FakeLlm { _, _ -> listOf(LlmStreamEvent.TextDelta("done")) }
        AgentRunner(llm, tools).run("test", listOf(LlmMessage.user(largeMessage)), true) { }
        assertEquals(1, llm.requests.size)
        assertEquals(largeMessage, llm.requests.single().messages.single().content)
    }
}
