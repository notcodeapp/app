package com.notcode.mobile.llm

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

enum class LlmRole(val wireName: String) {
    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant"),
    TOOL("tool"),
}

data class CompleteToolCall(
    val id: String,
    val name: String,
    val arguments: String,
)

data class LlmMessage(
    val role: LlmRole,
    val content: String? = null,
    val toolCalls: List<CompleteToolCall> = emptyList(),
    val toolCallId: String? = null,
    val name: String? = null,
    val imageDataUrls: List<String> = emptyList(),
) {
    companion object {
        fun system(content: String) = LlmMessage(LlmRole.SYSTEM, content)
        fun user(content: String, images: List<String> = emptyList()) = LlmMessage(LlmRole.USER, content, imageDataUrls = images)
        fun assistant(content: String?, calls: List<CompleteToolCall> = emptyList()) =
            LlmMessage(LlmRole.ASSISTANT, content, calls)
        fun tool(call: CompleteToolCall, content: String) = LlmMessage(
            role = LlmRole.TOOL,
            content = content,
            toolCallId = call.id,
            name = call.name,
        )
    }
}

data class LlmTool(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

data class LlmRequest(
    val messages: List<LlmMessage>,
    val tools: List<LlmTool> = emptyList(),
    val maxTokens: Int? = null,
)

data class LlmResponse(
    val content: String?,
    val toolCalls: List<CompleteToolCall>,
    val finishReason: String?,
)

sealed interface LlmStreamEvent {
    data class TextDelta(val text: String) : LlmStreamEvent
    data class ToolCallDelta(
        val index: Int,
        val idPart: String?,
        val namePart: String?,
        val argumentsPart: String?,
    ) : LlmStreamEvent
    data class Finished(val reason: String?) : LlmStreamEvent
}

data class ConnectionTestResult(
    val message: String,
    val modelsEndpointSupported: Boolean,
    val configuredModelFound: Boolean?,
)

interface LlmClient {
    suspend fun chat(profileId: String, request: LlmRequest): LlmResponse
    fun streamChat(profileId: String, request: LlmRequest): Flow<LlmStreamEvent>
    suspend fun testConnection(profileId: String): ConnectionTestResult
}

open class LlmException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

class ProviderHttpException(
    val status: Int,
    val providerCode: String?,
    safeMessage: String,
    val retryAfterSeconds: Long? = null,
) : LlmException(safeMessage)

class ProviderProtocolException(message: String, cause: Throwable? = null) :
    LlmException(message, cause)

class MissingApiKeyException : LlmException("API key is missing")

class InsecureHttpBlockedException : LlmException(
    "HTTP is disabled for this profile. Enable insecure HTTP only for a trusted local server.",
)
