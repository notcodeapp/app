package com.notcode.mobile.agent

import com.notcode.mobile.filesystem.WorkspaceProjectFileSystem
import com.notcode.mobile.llm.ConnectionTestResult
import com.notcode.mobile.llm.LlmClient
import com.notcode.mobile.llm.LlmMessage
import com.notcode.mobile.llm.LlmRequest
import com.notcode.mobile.llm.LlmResponse
import com.notcode.mobile.llm.LlmStreamEvent
import com.notcode.mobile.llm.ProviderHttpException
import com.notcode.mobile.llm.ProviderProtocolException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.SocketTimeoutException

class AgentRunnerRetryTest {
    @get:Rule val temporary = TemporaryFolder()

    private class FakeLlm(
        private val turn: suspend FlowCollector<LlmStreamEvent>.(attempt: Int) -> Unit,
    ) : LlmClient {
        val attempts = mutableListOf<Int>()
        override suspend fun chat(profileId: String, request: LlmRequest): LlmResponse = error("unused")
        override suspend fun testConnection(profileId: String): ConnectionTestResult = error("unused")
        override fun streamChat(profileId: String, request: LlmRequest): Flow<LlmStreamEvent> = flow {
            attempts += attempts.size + 1
            turn(attempts.size)
        }
    }

    private suspend fun FlowCollector<LlmStreamEvent>.finish() {
        emit(LlmStreamEvent.TextDelta("готово"))
        emit(LlmStreamEvent.Finished("stop"))
    }

    private fun runner(llm: LlmClient) = AgentRunner(
        llm = llm,
        tools = ToolExecutor(WorkspaceProjectFileSystem(File(temporary.root, "work")), Json, WriteApprovalGateway { false }),
        retryBaseDelayMillis = 1L,
    )

    private fun run(llm: LlmClient, events: MutableList<AgentEvent>) = runBlocking {
        runner(llm).run("profile", listOf(LlmMessage.user("привет")), toolsEnabled = false) { events += it }
    }

    @Test fun rateLimitedTurnIsRetried() {
        val events = mutableListOf<AgentEvent>()
        val llm = FakeLlm { attempt ->
            if (attempt == 1) throw ProviderHttpException(429, "rate_limit", "slow down") else finish()
        }
        val result = run(llm, events)
        assertTrue(result.completed)
        assertEquals(2, llm.attempts.size)
        assertEquals(1, events.count { it is AgentEvent.TurnRetrying })
        assertEquals("готово", (events.filterIsInstance<AgentEvent.TextDelta>().single()).text)
    }

    @Test fun networkTimeoutIsRetried() {
        val events = mutableListOf<AgentEvent>()
        val llm = FakeLlm { attempt -> if (attempt == 1) throw SocketTimeoutException("timeout") else finish() }
        assertTrue(run(llm, events).completed)
        assertEquals(2, llm.attempts.size)
    }

    @Test fun retriesAreExhaustedThenReported() {
        val events = mutableListOf<AgentEvent>()
        val llm = FakeLlm { throw ProviderHttpException(503, null, "overloaded") }
        assertThrows(ProviderHttpException::class.java) { run(llm, events) }
        assertEquals(3, llm.attempts.size)
        assertEquals(2, events.count { it is AgentEvent.TurnRetrying })
    }

    @Test fun partiallyStreamedTurnIsNotRetried() {
        val events = mutableListOf<AgentEvent>()
        val llm = FakeLlm {
            emit(LlmStreamEvent.TextDelta("начало"))
            throw ProviderHttpException(429, null, "slow down")
        }
        assertThrows(ProviderHttpException::class.java) { run(llm, events) }
        assertEquals(1, llm.attempts.size)
        assertEquals(0, events.count { it is AgentEvent.TurnRetrying })
    }

    @Test fun protocolErrorsAreNeverRetried() {
        val events = mutableListOf<AgentEvent>()
        val llm = FakeLlm { throw ProviderProtocolException("bad json") }
        assertThrows(ProviderProtocolException::class.java) { run(llm, events) }
        assertEquals(1, llm.attempts.size)
    }

    @Test fun rejectedRequestIsNeverRetried() {
        val events = mutableListOf<AgentEvent>()
        val llm = FakeLlm { throw ProviderHttpException(400, "invalid_request", "bad prompt") }
        assertThrows(ProviderHttpException::class.java) { run(llm, events) }
        assertEquals(1, llm.attempts.size)
    }
}
