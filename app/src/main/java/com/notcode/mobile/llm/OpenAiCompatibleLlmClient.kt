package com.notcode.mobile.llm

import com.notcode.mobile.data.AppPreferencesRepository
import com.notcode.mobile.model.AuthMode
import com.notcode.mobile.model.ProviderProfile
import com.notcode.mobile.security.SecretStore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import com.notcode.mobile.model.CapabilityMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.buffer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class OpenAiCompatibleLlmClient(
    private val profiles: AppPreferencesRepository,
    private val secrets: SecretStore,
    private val json: Json,
) : LlmClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()

    private val eventSources = EventSources.createFactory(http)

    override suspend fun chat(profileId: String, request: LlmRequest): LlmResponse {
        val target = loadTarget(profileId)
        val payload = request.toPayload(target.profile, stream = false)
        return executeChat(target, payload)
    }

    override fun streamChat(
        profileId: String,
        request: LlmRequest,
    ): Flow<LlmStreamEvent> = callbackFlow {
        val target = loadTarget(profileId)
        if (target.profile.streamingMode == CapabilityMode.DISABLED) {
            val response = executeChat(target, request.toPayload(target.profile, stream = false))
            response.content?.let { send(LlmStreamEvent.TextDelta(it)) }
            response.toolCalls.forEachIndexed { index, call ->
                send(LlmStreamEvent.ToolCallDelta(index, call.id, call.name, call.arguments))
            }
            send(LlmStreamEvent.Finished(response.finishReason))
            close()
            return@callbackFlow
        }
        val payload = request.toPayload(target.profile, stream = true)
        val httpRequest = buildRequest(
            target = target,
            endpoint = endpoint(target.profile, "chat/completions"),
            method = "POST",
            body = payload,
            accept = "text/event-stream",
        )

        var receivedChars = 0L
        val listener = object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String,
            ) {
                receivedChars += data.length
                if (receivedChars > 4L * 1024 * 1024) {
                    close(ProviderProtocolException("Ответ превысил лимит 4 МиБ"))
                    eventSource.cancel()
                    return
                }
                if (data.trim() == "[DONE]") {
                    trySend(LlmStreamEvent.Finished(null))
                    close()
                    eventSource.cancel()
                    return
                }

                runCatching { parseStreamChunk(data) }
                    .onSuccess { events -> events.forEach { trySend(it) } }
                    .onFailure { error ->
                        close(
                            if (error is LlmException) error
                            else ProviderProtocolException("Invalid streaming response", error),
                        )
                        eventSource.cancel()
                    }
            }

            override fun onFailure(
                eventSource: EventSource,
                t: Throwable?,
                response: Response?,
            ) {
                val error = when {
                    response != null -> response.toProviderException()
                    t is LlmException -> t
                    else -> LlmException(
                        t?.message?.takeIf { it.isNotBlank() } ?: "Network request failed",
                        t,
                    )
                }
                response?.close()
                close(error)
            }

            override fun onClosed(eventSource: EventSource) {
                close()
            }
        }

        val eventSource = eventSources.newEventSource(httpRequest, listener)
        awaitClose { eventSource.cancel() }
    }.buffer(Channel.UNLIMITED)

    override suspend fun testConnection(profileId: String): ConnectionTestResult {
        val target = loadTarget(profileId)
        val request = buildRequest(
            target = target,
            endpoint = endpoint(target.profile, "models"),
            method = "GET",
            body = null,
            accept = "application/json",
        )

        return http.newCall(request).awaitResponse().use { response ->
            if (response.isSuccessful) {
                val root = response.body?.string()?.let {
                    runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull()
                }
                val modelIds = root?.get("data")
                    ?.let { it as? JsonArray }
                    ?.mapNotNull { element ->
                        (element as? JsonObject)?.get("id")
                            ?.let { it as? JsonPrimitive }
                            ?.contentOrNull
                    }
                    .orEmpty()
                val found = modelIds.takeIf { it.isNotEmpty() }
                    ?.contains(target.profile.modelId)
                ConnectionTestResult(
                    message = when (found) {
                        true -> "Connection established; model is available"
                        false -> "Connection established, but the configured model is not in /models"
                        null -> "Connection established"
                    },
                    modelsEndpointSupported = true,
                    configuredModelFound = found,
                )
            } else if (response.code == 404 || response.code == 405) {
                response.close()
                val probe = LlmRequest(
                    messages = listOf(LlmMessage.user("Reply with OK")),
                    maxTokens = 1,
                ).toPayload(target.profile, stream = false)
                executeChat(target, probe)
                ConnectionTestResult(
                    message = "Connection established through /chat/completions; /models is unavailable",
                    modelsEndpointSupported = false,
                    configuredModelFound = null,
                )
            } else {
                throw response.toProviderException()
            }
        }
    }

    suspend fun fetchModels(baseUrl: String, apiKey: String?, noAuth: Boolean): List<String> {
        val profile = ProviderProfile(
            baseUrl = baseUrl.trim().trimEnd('/'),
            modelId = "_fetch_models",
        )
        val authorization = apiKey?.trim()?.takeIf { it.isNotEmpty() && !noAuth }
            ?.let { "Bearer $it" }
        val request = buildRequest(
            target = ProviderTarget(profile, authorization),
            endpoint = endpoint(profile, "models"),
            method = "GET",
            body = null,
            accept = "application/json",
        )

        return http.newCall(request).awaitResponse().use { response ->
            if (!response.isSuccessful) throw response.toProviderException()
            val body = response.body ?: throw ProviderProtocolException("Provider returned an empty response")
            val source = body.source()
            if (source.request(4L * 1024 * 1024 + 1)) {
                throw ProviderProtocolException("Models response exceeded 4 MiB")
            }
            val root = runCatching { json.parseToJsonElement(source.readUtf8()).jsonObject }
                .getOrElse { throw ProviderProtocolException("Provider returned invalid models JSON", it) }
            val entries = (root["data"] as? JsonArray) ?: (root["models"] as? JsonArray)
            val models = entries.orEmpty().mapNotNull { element ->
                val item = element as? JsonObject ?: return@mapNotNull null
                ((item["id"] ?: item["name"]) as? JsonPrimitive)?.contentOrNull
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
            }.distinct()
            if (models.isEmpty()) {
                throw ProviderProtocolException("Provider returned no model IDs")
            }
            models
        }
    }

    /** Calls the OpenAI-compatible Images API and returns the generated bytes. */
    suspend fun generateImage(
        profileId: String,
        prompt: String,
        modelId: String? = null,
        size: String? = null,
    ): GeneratedImage {
        require(prompt.isNotBlank()) { "Image prompt is empty" }
        val target = loadTarget(profileId)
        val payload = ImageGenerationProtocol.requestPayload(
            model = modelId?.trim()?.takeIf { it.isNotEmpty() } ?: target.profile.modelId,
            prompt = prompt.trim(),
            size = size,
        )
        val request = buildRequest(
            target = target,
            endpoint = endpoint(target.profile, "images/generations"),
            method = "POST",
            body = payload,
            accept = "application/json",
        )

        return http.newCall(request).awaitResponse().use { response ->
            if (!response.isSuccessful) throw response.toProviderException()
            val body = response.body ?: throw ProviderProtocolException("Provider returned an empty response")
            val source = body.source()
            if (source.request(ImageGenerationProtocol.MAX_RESPONSE_BYTES + 1)) {
                throw ProviderProtocolException("Images response exceeded 48 MiB")
            }
            val root = runCatching { json.parseToJsonElement(source.readUtf8()).jsonObject }
                .getOrElse { throw ProviderProtocolException("Provider returned invalid images JSON", it) }
            when (val parsed = ImageGenerationProtocol.parse(root)) {
                is ParsedImage.Base64 -> GeneratedImage(
                    bytes = ImageGenerationProtocol.decodeBase64(parsed.data),
                    mimeType = parsed.mimeType,
                    revisedPrompt = parsed.revisedPrompt,
                )

                is ParsedImage.RemoteUrl -> GeneratedImage(
                    bytes = downloadImage(parsed.url),
                    mimeType = ImageGenerationProtocol.guessMime(null, parsed.url),
                    revisedPrompt = parsed.revisedPrompt,
                )
            }
        }
    }

    private suspend fun downloadImage(rawUrl: String): ByteArray {
        val url = rawUrl.toHttpUrlOrNull() ?: throw ProviderProtocolException("Image URL is invalid")
        if (url.scheme != "https" || url.username.isNotEmpty() || url.password.isNotEmpty()) {
            throw ProviderProtocolException("Only credential-free HTTPS image URLs are allowed")
        }
        val request = Request.Builder()
            .url(url)
            .header("Accept", "image/*")
            .header("Cache-Control", "no-store")
            .get()
            .build()
        return http.newCall(request).awaitResponse().use { response ->
            if (!response.isSuccessful) throw response.toProviderException()
            val body = response.body ?: throw ProviderProtocolException("Image download returned no body")
            if (body.contentLength() > MAX_DOWNLOADED_IMAGE_BYTES) {
                throw ProviderProtocolException("Image is larger than the download limit")
            }
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            body.byteStream().use { input ->
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_DOWNLOADED_IMAGE_BYTES) {
                        throw ProviderProtocolException("Image is larger than the download limit")
                    }
                    output.write(buffer, 0, count)
                }
            }
            output.toByteArray()
        }
    }

    private suspend fun executeChat(
        target: ProviderTarget,
        payload: JsonObject,
    ): LlmResponse {
        val request = buildRequest(
            target = target,
            endpoint = endpoint(target.profile, "chat/completions"),
            method = "POST",
            body = payload,
            accept = "application/json",
        )
        return http.newCall(request).awaitResponse().use { response ->
            if (!response.isSuccessful) throw response.toProviderException()
            val body = response.body ?: throw ProviderProtocolException("Provider returned an empty response")
            val source = body.source()
            if (source.request(4L * 1024 * 1024 + 1)) {
                throw ProviderProtocolException("Ответ превысил лимит 4 МиБ")
            }
            parseChatResponse(source.readUtf8())
        }
    }

    private suspend fun loadTarget(profileId: String): ProviderTarget {
        val profile = profiles.requireProfile(profileId)
        validateBaseUrl(profile)
        val secretBytes = profile.secretAlias?.let { secrets.get(it) }
        return try {
            val auth = when (profile.authMode) {
                AuthMode.NONE -> null
                AuthMode.BEARER -> {
                    if (secretBytes == null || secretBytes.isEmpty()) {
                        throw MissingApiKeyException()
                    }
                    "Bearer ${secretBytes.decodeToString()}"
                }
            }
            ProviderTarget(profile, auth)
        } finally {
            secretBytes?.fill(0)
        }
    }

    private fun validateBaseUrl(profile: ProviderProfile): HttpUrl {
        val parsed = profile.baseUrl.trim().toHttpUrlOrNull()
            ?: throw LlmException("Base URL is invalid")
        if (parsed.scheme !in setOf("http", "https")) {
            throw LlmException("Only HTTP and HTTPS Base URLs are supported")
        }
        if (parsed.scheme == "http" && !profile.allowInsecureHttp) {
            throw InsecureHttpBlockedException()
        }
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) {
            throw LlmException("Credentials in Base URL are not allowed")
        }
        if (parsed.query != null || parsed.fragment != null) {
            throw LlmException("Base URL must not contain query parameters or a fragment")
        }
        return parsed
    }

    private fun endpoint(profile: ProviderProfile, suffix: String): HttpUrl {
        validateBaseUrl(profile)
        val value = profile.baseUrl.trim().trimEnd('/') + "/" + suffix.trimStart('/')
        return value.toHttpUrlOrNull() ?: throw LlmException("Unable to build provider endpoint")
    }

    private fun buildRequest(
        target: ProviderTarget,
        endpoint: HttpUrl,
        method: String,
        body: JsonObject?,
        accept: String,
    ): Request {
        val builder = Request.Builder()
            .url(endpoint)
            .header("Accept", accept)
            .header("Cache-Control", "no-store")
        target.authorization?.let { builder.header("Authorization", it) }

        if (method == "POST") {
            val encoded = json.encodeToString(JsonElement.serializer(), body ?: JsonObject(emptyMap()))
            builder.post(encoded.toRequestBody(JSON_MEDIA_TYPE))
        } else {
            builder.get()
        }
        return builder.build()
    }

    private fun LlmRequest.toPayload(
        profile: ProviderProfile,
        stream: Boolean,
    ): JsonObject = buildJsonObject {
        put("model", profile.modelId)
        put("messages", buildJsonArray {
            messages.forEach { add(it.toWireJson()) }
        })
        if (tools.isNotEmpty()) {
            put("tools", buildJsonArray {
                tools.forEach { tool ->
                    add(buildJsonObject {
                        put("type", "function")
                        put("function", buildJsonObject {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("parameters", tool.parameters)
                        })
                    })
                }
            })
            put("tool_choice", "auto")
        }
        maxTokens?.let { put("max_tokens", it) }
        put("stream", stream)
    }

    private fun LlmMessage.toWireJson(): JsonObject = buildJsonObject {
        put("role", role.wireName)
        if (imageDataUrls.isNotEmpty() && role == LlmRole.USER) {
            put("content", buildJsonArray {
                content?.takeIf { it.isNotBlank() }?.let { add(buildJsonObject { put("type", "text"); put("text", it) }) }
                imageDataUrls.forEach { url -> add(buildJsonObject { put("type", "image_url"); put("image_url", buildJsonObject { put("url", url) }) }) }
            })
        } else content?.let { put("content", it) }
        if (toolCalls.isNotEmpty()) {
            put("tool_calls", buildJsonArray {
                toolCalls.forEach { call ->
                    add(buildJsonObject {
                        put("id", call.id)
                        put("type", "function")
                        put("function", buildJsonObject {
                            put("name", call.name)
                            put("arguments", call.arguments)
                        })
                    })
                }
            })
        }
        toolCallId?.let { put("tool_call_id", it) }
        if (role == LlmRole.TOOL) name?.let { put("name", it) }
    }

    private fun parseChatResponse(body: String): LlmResponse {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }
            .getOrElse { throw ProviderProtocolException("Provider returned invalid JSON", it) }
        root["error"]?.let { throw providerErrorFromElement(it) }
        val choice = (root["choices"] as? JsonArray)
            ?.firstOrNull() as? JsonObject
            ?: throw ProviderProtocolException("Response has no choices")
        val message = choice["message"] as? JsonObject
            ?: throw ProviderProtocolException("Response has no assistant message")
        return LlmResponse(
            content = (message["content"] as? JsonPrimitive)?.contentOrNull,
            toolCalls = parseCompleteToolCalls(message["tool_calls"]),
            finishReason = (choice["finish_reason"] as? JsonPrimitive)?.contentOrNull,
        )
    }

    private fun parseCompleteToolCalls(element: JsonElement?): List<CompleteToolCall> {
        val calls = element as? JsonArray ?: return emptyList()
        return calls.mapIndexedNotNull { index, item ->
            val call = item as? JsonObject ?: return@mapIndexedNotNull null
            val function = call["function"] as? JsonObject ?: return@mapIndexedNotNull null
            val name = (function["name"] as? JsonPrimitive)?.contentOrNull
                ?: return@mapIndexedNotNull null
            CompleteToolCall(
                id = (call["id"] as? JsonPrimitive)?.contentOrNull ?: "call_$index",
                name = name,
                arguments = (function["arguments"] as? JsonPrimitive)?.contentOrNull ?: "{}",
            )
        }
    }

    private fun parseStreamChunk(data: String): List<LlmStreamEvent> {
        val root = runCatching { json.parseToJsonElement(data).jsonObject }
            .getOrElse { throw ProviderProtocolException("Invalid SSE JSON", it) }
        root["error"]?.let { throw providerErrorFromElement(it) }
        val choice = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
            ?: return emptyList()
        val delta = choice["delta"] as? JsonObject ?: return emptyList()
        val events = mutableListOf<LlmStreamEvent>()

        (delta["content"] as? JsonPrimitive)?.contentOrNull
            ?.takeIf { it.isNotEmpty() }
            ?.let { events += LlmStreamEvent.TextDelta(it) }

        (delta["tool_calls"] as? JsonArray)?.forEach { item ->
            val call = item as? JsonObject ?: return@forEach
            val function = call["function"] as? JsonObject
            events += LlmStreamEvent.ToolCallDelta(
                index = (call["index"] as? JsonPrimitive)?.intOrNull ?: 0,
                idPart = (call["id"] as? JsonPrimitive)?.contentOrNull,
                namePart = (function?.get("name") as? JsonPrimitive)?.contentOrNull,
                argumentsPart = (function?.get("arguments") as? JsonPrimitive)?.contentOrNull,
            )
        }

        (choice["finish_reason"] as? JsonPrimitive)?.contentOrNull?.let {
            events += LlmStreamEvent.Finished(it)
        }
        return events
    }

    private fun Response.toProviderException(): ProviderHttpException {
        val safeBody = runCatching { peekBody(MAX_ERROR_BODY_BYTES).string() }
            .getOrDefault("")
        val root = runCatching { json.parseToJsonElement(safeBody).jsonObject }.getOrNull()
        val error = root?.get("error") as? JsonObject
        val message = (error?.get("message") as? JsonPrimitive)?.contentOrNull
            ?: (root?.get("message") as? JsonPrimitive)?.contentOrNull
            ?: (root?.get("detail") as? JsonPrimitive)?.contentOrNull
            ?: safeBody.takeIf { it.isNotBlank() && !it.trimStart().startsWith("<") }
            ?: "Provider returned HTTP $code"
        val providerCode = (error?.get("code") as? JsonPrimitive)?.contentOrNull
        val retryAfter = header("Retry-After")?.toLongOrNull()
        return ProviderHttpException(
            status = code,
            providerCode = providerCode,
            safeMessage = message.take(MAX_SAFE_MESSAGE_LENGTH),
            retryAfterSeconds = retryAfter,
        )
    }

    private fun providerErrorFromElement(element: JsonElement): ProviderHttpException {
        val error = element as? JsonObject
        val message = (error?.get("message") as? JsonPrimitive)?.contentOrNull
            ?: "Provider returned an error"
        val code = (error?.get("code") as? JsonPrimitive)?.contentOrNull
        return ProviderHttpException(200, code, message.take(MAX_SAFE_MESSAGE_LENGTH))
    }

    private data class ProviderTarget(
        val profile: ProviderProfile,
        val authorization: String?,
    )

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val MAX_ERROR_BODY_BYTES = 64L * 1024L
        const val MAX_SAFE_MESSAGE_LENGTH = 2_000
        const val MAX_DOWNLOADED_IMAGE_BYTES = 32L * 1024L * 1024L
    }
}

private suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, error: IOException) {
            if (continuation.isActive) continuation.resumeWithException(error)
        }

        override fun onResponse(call: Call, response: Response) {
            if (continuation.isActive) continuation.resume(response)
            else response.close()
        }
    })
}
