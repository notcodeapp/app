package com.notcode.mobile.llm

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl
import okhttp3.Response

/** The successful provider base URL (without /models) and its discovered model IDs. */
data class DiscoveredModels(val baseUrl: String, val models: List<String>)

/** Receives an already validated base URL; requests must not follow redirects. */
internal object ModelsDiscovery {
    private const val MAX_RESPONSE_BYTES = 4L * 1024 * 1024

    fun endpoints(base: HttpUrl): List<HttpUrl> {
        val path = base.encodedPath.trimEnd('/')
        val alternate = if (base.pathSegments.lastOrNull { it.isNotEmpty() } == "v1") {
            path.substringBeforeLast('/')
        } else {
            "$path/v1"
        }
        return listOf(path, alternate).map {
            base.newBuilder().encodedPath("$it/models").build()
        }
    }

    suspend fun fetch(
        base: HttpUrl,
        json: Json,
        request: suspend (HttpUrl) -> Response,
    ): DiscoveredModels {
        var failure: LlmException = ProviderProtocolException("Provider returned no model IDs")
        for (url in endpoints(base)) {
            currentCoroutineContext().ensureActive()
            try {
                return request(url).use { response ->
                    currentCoroutineContext().ensureActive()
                    if (!response.isSuccessful) {
                        throw ProviderHttpException(
                            status = response.code,
                            providerCode = null,
                            safeMessage = "Models request returned HTTP ${response.code}",
                        )
                    }
                    val source = response.body?.source()
                        ?: throw ProviderProtocolException("Provider returned an empty response")
                    if (source.request(MAX_RESPONSE_BYTES + 1)) {
                        throw ProviderProtocolException("Models response exceeded 4 MiB")
                    }
                    val root = try {
                        json.parseToJsonElement(source.readUtf8()).jsonObject
                    } catch (_: IllegalArgumentException) {
                        // Parser exceptions can contain raw response fragments.
                        throw ProviderProtocolException("Provider returned invalid models JSON")
                    }
                    val entries = (root["data"] as? JsonArray) ?: (root["models"] as? JsonArray)
                    val models = entries.orEmpty().mapNotNull { element ->
                        val item = element as? JsonObject ?: return@mapNotNull null
                        ((item["id"] ?: item["name"]) as? JsonPrimitive)?.contentOrNull
                            ?.trim()?.takeIf { it.isNotEmpty() }
                    }.distinct()
                    if (models.isEmpty()) throw ProviderProtocolException("Provider returned no model IDs")
                    currentCoroutineContext().ensureActive()
                    DiscoveredModels(
                        baseUrl = url.newBuilder().removePathSegment(url.pathSegments.lastIndex)
                            .build().toString().trimEnd('/'),
                        models = models,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: LlmException) {
                failure = error
            } catch (_: IOException) {
                failure = LlmException("Models network request failed")
            } catch (_: IllegalArgumentException) {
                failure = LlmException("Unable to build models request")
            }
        }
        currentCoroutineContext().ensureActive()
        throw failure
    }
}
