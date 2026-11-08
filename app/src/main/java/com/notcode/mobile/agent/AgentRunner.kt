package com.notcode.mobile.agent

import com.notcode.mobile.llm.CompleteToolCall
import com.notcode.mobile.llm.LlmClient
import com.notcode.mobile.llm.LlmException
import com.notcode.mobile.llm.LlmMessage
import com.notcode.mobile.llm.LlmRequest
import com.notcode.mobile.llm.LlmStreamEvent
import com.notcode.mobile.llm.ProviderHttpException
import com.notcode.mobile.llm.ProviderProtocolException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import com.notcode.mobile.filesystem.ProjectPath
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

sealed interface AgentEvent {
    data class ModelTurnStarted(val number: Int) : AgentEvent
    data class TextDelta(val text: String) : AgentEvent
    data class ToolStarted(val id: String, val name: String, val filePath: String? = null) : AgentEvent
    data class ToolFinished(
        val id: String,
        val name: String,
        val summary: String,
        val successful: Boolean,
        val filePath: String? = null,
    ) : AgentEvent
    data class TurnRetrying(val attempt: Int, val maxAttempts: Int, val reason: String) : AgentEvent
    data class LimitReached(val maxModelTurns: Int) : AgentEvent
}

data class AgentRunResult(
    val history: List<LlmMessage>,
    val completed: Boolean,
)

class AgentRunner(
    private val llm: LlmClient,
    private val tools: ToolExecutor,
    private val maxModelTurns: Int = MAX_MODEL_TURNS,
    private val maxExecutedCallsPerTurn: Int = MAX_EXECUTED_CALLS_PER_TURN,
    private val maxTurnRetries: Int = MAX_TURN_RETRIES,
    private val retryBaseDelayMillis: Long = RETRY_BASE_DELAY_MILLIS,
) {
    init {
        require(maxModelTurns > 0) { "maxModelTurns must be positive" }
        require(maxExecutedCallsPerTurn > 0) { "maxExecutedCallsPerTurn must be positive" }
        require(maxTurnRetries >= 0) { "maxTurnRetries cannot be negative" }
    }

    suspend fun run(
        profileId: String,
        initialHistory: List<LlmMessage>,
        toolsEnabled: Boolean,
        onEvent: suspend (AgentEvent) -> Unit,
    ): AgentRunResult {
        val history = initialHistory.toMutableList()
        val completedCalls = mutableMapOf<String, CachedToolResult>()

        repeat(maxModelTurns) { turnIndex ->
            onEvent(AgentEvent.ModelTurnStarted(turnIndex + 1))
            val assistant = streamTurn(profileId, history, toolsEnabled, onEvent)

            history += LlmMessage.assistant(assistant.content, assistant.toolCalls)
            if (assistant.toolCalls.isEmpty()) {
                return AgentRunResult(history, completed = true)
            }

            if (turnIndex == maxModelTurns - 1) {
                onEvent(AgentEvent.LimitReached(maxModelTurns))
                return AgentRunResult(history, completed = false)
            }

            assistant.toolCalls.forEachIndexed { callIndex, call ->
                onEvent(AgentEvent.ToolStarted(call.id, call.name, startedFilePath(call)))
                val result = when {
                    callIndex >= maxExecutedCallsPerTurn -> ToolExecutionResult(
                        json = """{"ok":false,"error":{"code":"TOOL_LIMIT","message":"Too many tool calls in one turn"}}""",
                        summary = "Skipped: per-turn tool limit reached",
                    )
                    !toolsEnabled -> ToolExecutionResult(
                        json = """{"ok":false,"error":{"code":"TOOLS_DISABLED","message":"Tools are disabled for this provider profile"}}""",
                        summary = "Tools are disabled",
                    )
                    else -> executeIdempotently(call, completedCalls)
                }
                history += LlmMessage.tool(call, result.json)
                val successful = !result.json.contains("\"ok\":false")
                onEvent(
                    AgentEvent.ToolFinished(
                        id = call.id,
                        name = call.name,
                        summary = result.summary,
                        successful = successful,
                        filePath = result.filePath,
                    ),
                )
            }
        }

        onEvent(AgentEvent.LimitReached(maxModelTurns))
        return AgentRunResult(history, completed = false)
    }

    private suspend fun streamTurn(
        profileId: String,
        history: List<LlmMessage>,
        toolsEnabled: Boolean,
        onEvent: suspend (AgentEvent) -> Unit,
    ): AssistantTurn {
        var attempt = 0
        while (true) {
            val accumulator = AssistantAccumulator()
            var emitted = false
            try {
                llm.streamChat(
                    profileId = profileId,
                    request = LlmRequest(
                        messages = history,
                        tools = if (toolsEnabled) tools.availableDefinitions else emptyList(),
                    ),
                ).collect { event ->
                    emitted = true
                    accumulator.accept(event)
                    if (event is LlmStreamEvent.TextDelta) {
                        onEvent(AgentEvent.TextDelta(event.text))
                    }
                }
                return accumulator.complete()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                // Repeating a turn that already produced output would duplicate the visible text.
                if (emitted || attempt >= maxTurnRetries || !isRetryable(error)) throw error
                attempt++
                onEvent(AgentEvent.TurnRetrying(attempt, maxTurnRetries, failureReason(error)))
                delay(retryDelayMillis(error, attempt))
            }
        }
    }

    private fun retryDelayMillis(error: Exception, attempt: Int): Long {
        val requested = (error as? ProviderHttpException)?.retryAfterSeconds?.times(1000) ?: 0L
        return requested.takeIf { it > 0 }?.coerceAtMost(MAX_RETRY_DELAY_MILLIS)
            ?: (retryBaseDelayMillis * (1L shl (attempt - 1))).coerceAtMost(MAX_RETRY_DELAY_MILLIS)
    }

    private fun isRetryable(error: Exception): Boolean = when (error) {
        is ProviderHttpException -> error.status in RETRYABLE_STATUS || error.status >= 500
        is LlmException -> false
        else -> error is IOException
    }

    private fun failureReason(error: Exception): String = when (error) {
        is ProviderHttpException -> "провайдер вернул ${error.status}"
        is java.net.SocketTimeoutException -> "провайдер не ответил вовремя"
        is IOException -> "нет связи с провайдером"
        else -> error.message?.take(80)?.ifBlank { null } ?: "сбой запроса"
    }

    private fun startedFilePath(call: CompleteToolCall): String? {
        if (call.name !in setOf(
                ToolExecutor.READ_FILE, ToolExecutor.WRITE_FILE, ToolExecutor.CREATE_FILE,
                ToolExecutor.RENAME_FILE, ToolExecutor.DELETE_FILE,
            ) || call.arguments.length > ToolExecutor.MAX_TOOL_ARGUMENT_BYTES
        ) return null
        return runCatching {
            val arguments = Json.parseToJsonElement(call.arguments) as? JsonObject
            val path = arguments?.get("filePath") as? JsonPrimitive
            if (path?.isString == true) ProjectPath.parse(path.content).value.takeIf { it.isNotEmpty() }
            else null
        }.getOrNull()
    }

    private suspend fun executeIdempotently(
        call: CompleteToolCall,
        completedCalls: MutableMap<String, CachedToolResult>,
    ): ToolExecutionResult {
        val hash = sha256("${call.name}\u0000${call.arguments}")
        val cached = completedCalls[call.id]
        if (cached != null) {
            return if (cached.argumentsHash == hash) {
                cached.result.copy(summary = "Reused result: ${cached.result.summary}")
            } else {
                ToolExecutionResult(
                    json = """{"ok":false,"error":{"code":"DUPLICATE_TOOL_ID","message":"Tool call ID was reused with different arguments"}}""",
                    summary = "Rejected reused tool call ID",
                )
            }
        }

        val result = runCatching { tools.execute(call) }.getOrElse { error ->
            if (error is CancellationException) throw error
            val safe = error.message?.take(500) ?: "Tool execution failed"
            ToolExecutionResult(
                json = """{"ok":false,"error":{"code":"TOOL_FAILED","message":${jsonString(safe)}}}""",
                summary = safe.take(120),
            )
        }
        completedCalls[call.id] = CachedToolResult(hash, result)
        return result
    }

    private data class CachedToolResult(
        val argumentsHash: String,
        val result: ToolExecutionResult,
    )

    private class AssistantAccumulator {
        private val text = StringBuilder()
        private val calls = sortedMapOf<Int, ToolCallAccumulator>()

        fun accept(event: LlmStreamEvent) {
            when (event) {
                is LlmStreamEvent.TextDelta -> text.append(event.text)
                is LlmStreamEvent.ToolCallDelta -> {
                    if (event.index < 0 || (event.index !in calls && calls.size >= 64)) {
                        throw ProviderProtocolException("Too many tool calls or invalid tool index")
                    }
                    calls.getOrPut(event.index) { ToolCallAccumulator() }.apply {
                        event.idPart?.let(id::append)
                        event.namePart?.let(name::append)
                        event.argumentsPart?.let(arguments::append)
                        if (name.length > ToolExecutor.MAX_TOOL_NAME_CHARS || id.length > 512 ||
                            arguments.length > ToolExecutor.MAX_TOOL_ARGUMENT_BYTES
                        ) throw ProviderProtocolException("Tool call exceeds name, ID or argument limits")
                    }
                }
                is LlmStreamEvent.Finished -> Unit
            }
        }

        fun complete(): AssistantTurn {
            val completeCalls = calls.map { (index, partial) ->
                val name = partial.name.toString()
                if (name.isBlank()) {
                    throw ProviderProtocolException("Provider returned a tool call without a name")
                }
                CompleteToolCall(
                    id = partial.id.toString().ifBlank { "call_$index" },
                    name = name,
                    arguments = partial.arguments.toString().ifBlank { "{}" },
                )
            }
            return AssistantTurn(
                content = text.toString().takeIf { it.isNotEmpty() },
                toolCalls = completeCalls,
            )
        }
    }

    private data class ToolCallAccumulator(
        val id: StringBuilder = StringBuilder(),
        val name: StringBuilder = StringBuilder(),
        val arguments: StringBuilder = StringBuilder(),
    )

    private data class AssistantTurn(
        val content: String?,
        val toolCalls: List<CompleteToolCall>,
    )

    companion object {
        const val MAX_MODEL_TURNS = 10
        const val MAX_EXECUTED_CALLS_PER_TURN = 8
        const val MAX_TURN_RETRIES = 2
        const val RETRY_BASE_DELAY_MILLIS = 1_500L
        const val MAX_RETRY_DELAY_MILLIS = 30_000L
        private val RETRYABLE_STATUS = setOf(408, 425, 429)
    }
}

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }

private fun jsonString(value: String): String = buildString {
    append('"')
    value.forEach { char ->
        when (char) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (char.code < 32) append("\\u%04x".format(char.code)) else append(char)
        }
    }
    append('"')
}
