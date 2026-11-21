package com.notcode.mobile.llm

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ModelsDiscoveryTest {
    @Test
    fun candidatesPreserveOriginAndPrefixInBothDirections() {
        val cases = mapOf(
            "https://example.test" to listOf("/models", "/v1/models"),
            "https://example.test/v1/" to listOf("/v1/models", "/models"),
            "https://example.test:8443/proxy/v1///" to listOf("/proxy/v1/models", "/proxy/models"),
            "https://example.test/proxy" to listOf("/proxy/models", "/proxy/v1/models"),
            "https://example.test/v10" to listOf("/v10/models", "/v10/v1/models"),
            "https://example.test/a%2Fb/v1" to listOf("/a%2Fb/v1/models", "/a%2Fb/models"),
        )
        cases.forEach { (raw, paths) ->
            val base = raw.toHttpUrl()
            val candidates = ModelsDiscovery.endpoints(base)
            assertEquals(paths, candidates.map { it.encodedPath })
            candidates.forEach {
                assertEquals(base.scheme, it.scheme)
                assertEquals(base.host, it.host)
                assertEquals(base.port, it.port)
            }
        }
    }

    @Test
    fun successfulEnteredPathStopsAndNormalizesIds() = runBlocking {
        var calls = 0
        val result = ModelsDiscovery.fetch(BASE, Json) { url ->
            calls++
            response(url, 200, """{"data":[{"id":" alpha "},{"id":"alpha"},{"name":"beta"},{"id":" "},null]}""")
        }
        assertEquals(DiscoveredModels("https://example.test/proxy", listOf("alpha", "beta")), result)
        assertEquals(1, calls)
    }

    @Test
    fun httpAndProtocolFailuresTryAlternate() = runBlocking {
        val failures = listOf(
            401 to "secret HTTP response",
            404 to "not found",
            500 to "server error",
            302 to "redirect to another origin",
            200 to "secret invalid JSON",
            200 to "[]",
            200 to "{}",
            200 to "",
            200 to """{"data":[]}""",
            200 to """{"models":[]}""",
            200 to """{"data":[null,{"id":" "}]}""",
            200 to """{"error":{"message":"secret provider error"}}""",
            200 to "x".repeat(4 * 1024 * 1024 + 1),
        )
        for ((status, body) in failures) {
            val visited = mutableListOf<HttpUrl>()
            val result = ModelsDiscovery.fetch(BASE, Json) { url ->
                visited += url
                if (visited.size == 1) response(url, status, body)
                else response(url, 200, """{"models":[{"name":"fallback"}]}""")
            }
            assertEquals(DiscoveredModels("https://example.test/proxy/v1", listOf("fallback")), result)
            assertEquals(listOf("/proxy/models", "/proxy/v1/models"), visited.map { it.encodedPath })
        }
    }

    @Test
    fun networkFailureTriesRemovingTerminalV1() = runBlocking {
        val visited = mutableListOf<String>()
        val result = ModelsDiscovery.fetch("https://example.test/proxy/v1".toHttpUrl(), Json) { url ->
            visited += url.encodedPath
            if (visited.size == 1) throw IOException("secret URL")
            response(url, 200, """{"data":[{"id":"ok"}]}""")
        }
        assertEquals(DiscoveredModels("https://example.test/proxy", listOf("ok")), result)
        assertEquals(listOf("/proxy/v1/models", "/proxy/models"), visited)
    }

    @Test
    fun cancellationIsUnchangedAndDoesNotTryFallback() = runBlocking {
        val cancellation = CancellationException("cancelled")
        var calls = 0
        try {
            ModelsDiscovery.fetch(BASE, Json) {
                calls++
                throw cancellation
            }
            fail("Expected cancellation")
        } catch (error: CancellationException) {
            assertSame(cancellation, error)
        }
        assertEquals(1, calls)
    }

    @Test
    fun exhaustedFailuresDoNotExposeBodyOrNetworkDetails() = runBlocking {
        for (kind in listOf("http", "json", "network", "limit", "empty", "error")) {
            var calls = 0
            try {
                ModelsDiscovery.fetch(BASE, Json) { url ->
                    calls++
                    when (kind) {
                        "network" -> throw IOException("SECRET credential URL")
                        "http" -> response(url, 403, "SECRET provider body")
                        "limit" -> response(url, 200, "SECRET" + "x".repeat(4 * 1024 * 1024))
                        "empty" -> response(url, 200, """{"data":[]}""")
                        "error" -> response(url, 200, """{"error":{"message":"SECRET"}}""")
                        else -> response(url, 200, "SECRET malformed JSON")
                    }
                }
                fail("Expected discovery failure")
            } catch (error: LlmException) {
                assertFalse(error.toString().contains("SECRET"))
                assertNull(error.cause)
                assertTrue(error.message.orEmpty().isNotBlank())
            }
            assertEquals(2, calls)
        }
    }

    @Test
    fun validResponseExactlyAtLimitIsAccepted() = runBlocking {
        val body = """{"data":[{"id":"ok"}]}""".padEnd(4 * 1024 * 1024, ' ')
        assertEquals(
            DiscoveredModels("https://example.test/proxy", listOf("ok")),
            ModelsDiscovery.fetch(BASE, Json) { response(it, 200, body) },
        )
    }

    @Test
    fun successfulBasesAreCanonicalAndRetainEncodedPrefix() = runBlocking {
        val cases = mapOf(
            "https://example.test/" to "https://example.test",
            "https://example.test/v1///" to "https://example.test/v1",
            "https://example.test:8443/a%2Fb/v1/" to "https://example.test:8443/a%2Fb/v1",
        )
        for ((entered, expected) in cases) {
            val visited = mutableListOf<HttpUrl>()
            val result = ModelsDiscovery.fetch(entered.toHttpUrl(), Json) { url ->
                visited += url
                response(url, 200, """{"data":[{"id":"ok"}]}""")
            }
            assertEquals(DiscoveredModels(expected, listOf("ok")), result)
            assertEquals(listOf("$expected/models"), visited.map { it.toString() })
        }
    }

    @Test
    fun alternateBaseIsUsedForSubsequentChatEndpointInBothDirections() = runBlocking {
        val cases = mapOf(
            "https://example.test" to "https://example.test/v1",
            "https://example.test/v1/" to "https://example.test",
            "https://example.test:8443/a%2Fb" to "https://example.test:8443/a%2Fb/v1",
            "https://example.test:8443/a%2Fb/v1/" to "https://example.test:8443/a%2Fb",
        )
        for ((entered, expected) in cases) {
            val visited = mutableListOf<HttpUrl>()
            val result = ModelsDiscovery.fetch(entered.toHttpUrl(), Json) { url ->
                visited += url
                response(url, if (visited.size == 1) 404 else 200, """{"data":[{"id":"ok"}]}""")
            }
            assertEquals(DiscoveredModels(expected, listOf("ok")), result)
            assertEquals("$expected/models", visited.last().toString())
            // Mirror the client's concatenation after the user explicitly saves this base.
            val chatUrl = (result.baseUrl.trimEnd('/') + "/chat/completions").toHttpUrl()
            assertEquals("$expected/chat/completions", chatUrl.toString())
        }
    }

    @Test
    fun cancellationAfterResponseClosesBodyAndDoesNotReturnModels() = runBlocking {
        val cancellation = CancellationException("cancelled")
        val body = TrackingBody("""{"data":[{"id":"ok"}]}""")
        var calls = 0
        try {
            withContext(Job()) {
                ModelsDiscovery.fetch(BASE, Json) { url ->
                    calls++
                    currentCoroutineContext()[Job]!!.cancel(cancellation)
                    response(url, 200, body)
                }
            }
            fail("Expected cancellation")
        } catch (error: CancellationException) {
            assertSame(cancellation, error)
        }
        assertEquals(1, calls)
        assertTrue(body.closed)
    }

    @Test
    fun cancelledContextDoesNotFallbackOnNetworkFailure() = runBlocking {
        val cancellation = CancellationException("cancelled")
        var calls = 0
        try {
            withContext(Job()) {
                ModelsDiscovery.fetch(BASE, Json) {
                    calls++
                    currentCoroutineContext()[Job]!!.cancel(cancellation)
                    throw IOException("network interrupted")
                }
            }
            fail("Expected cancellation")
        } catch (error: CancellationException) {
            assertSame(cancellation, error)
        }
        assertEquals(1, calls)
    }

    @Test
    fun fallbackAndSuccessBothCloseResponseBodies() = runBlocking {
        val empty = TrackingBody("""{"data":[]}""")
        val success = TrackingBody("""{"data":[{"id":"ok"}]}""")
        var calls = 0
        val result = ModelsDiscovery.fetch(BASE, Json) { url ->
            calls++
            if (calls == 2) assertTrue(empty.closed)
            response(url, 200, if (calls == 1) empty else success)
        }
        assertEquals(DiscoveredModels("https://example.test/proxy/v1", listOf("ok")), result)
        assertEquals(2, calls)
        assertTrue(empty.closed)
        assertTrue(success.closed)
    }

    private fun response(url: HttpUrl, status: Int, body: String): Response =
        response(url, status, body.toResponseBody("application/json".toMediaType()))

    private fun response(url: HttpUrl, status: Int, body: ResponseBody): Response = Response.Builder()
        .request(Request.Builder().url(url).build())
        .protocol(Protocol.HTTP_1_1)
        .code(status)
        .message("test")
        .body(body)
        .build()

    private class TrackingBody(text: String) : ResponseBody() {
        var closed = false
            private set
        private val bytes = text.toByteArray(Charsets.UTF_8)
        private val buffered = object : ForwardingSource(Buffer().write(bytes)) {
            override fun close() {
                closed = true
                super.close()
            }
        }.buffer()

        override fun contentType() = "application/json".toMediaType()
        override fun contentLength() = bytes.size.toLong()
        override fun source() = buffered
    }

    private companion object {
        val BASE = "https://example.test/proxy".toHttpUrl()
    }
}
