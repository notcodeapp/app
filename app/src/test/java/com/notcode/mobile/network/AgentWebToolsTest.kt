package com.notcode.mobile.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

class AgentWebToolsTest {
    private fun args(key: String, value: String) = buildJsonObject { put(key, value) }

    @Test fun definitionsExposeOnlyReadOnlyWebCallbacks() {
        assertEquals(listOf("web_search", "fetch_url"), AgentWebTools().definitions.map { it.name })
        assertEquals("query", AgentWebTools().definitions[0].parameters["required"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("url", AgentWebTools().definitions[1].parameters["required"]!!.jsonArray[0].jsonPrimitive.content)
    }

    @Test fun absentApprovalFailsClosedWithoutNetwork() = runBlocking {
        assertTrue(AgentWebTools().search(args("query", "Kotlin documentation")).json.contains("USER_DENIED"))
        assertTrue(AgentWebTools().fetch(args("url", "https://example.com/")).json.contains("USER_DENIED"))
    }

    @Test fun approvalIsPerQueryAndDoesNotLeakUrlQueryOrFragment() = runBlocking {
        val prompts = mutableListOf<String>()
        val tools = AgentWebTools { prompts.add(it); false }
        tools.search(args("query", "Kotlin coroutines"))
        tools.search(args("query", "Android testing"))
        val result = tools.fetch(args("url", "https://example.com/path?token=PRIVATE#SECRET"))
        assertEquals(listOf("Search DuckDuckGo for: Kotlin coroutines", "Search DuckDuckGo for: Android testing",
            "Allow public HTTPS access to example.com?"), prompts)
        assertFalse(result.json.contains("PRIVATE"))
        assertFalse(result.summary.contains("SECRET"))
    }

    @Test fun malformedArgumentsAndUnsafeUrlsNeverRequestApproval() = runBlocking {
        val tools = AgentWebTools { fail("No approval expected for invalid input"); true }
        listOf("http://example.com/", "https://user:password@example.com/", "https://@example.com/",
            "https://localhost/", "https://localhost./", "https://a.local/", "https://127.0.0.1/",
            "https://2130706433/", "https://10.0.0.1/", "https://[::1]/", "https://[::ffff:127.0.0.1]/",
            "https://example.com:8443/").forEach {
            assertFalse(tools.fetch(args("url", it)).json.contains("\"ok\":true"))
        }
        assertTrue(tools.search(buildJsonObject { put("query", 12) }).json.contains("INVALID_ARGUMENT"))
        assertTrue(tools.search(args("query", " ")).json.contains("INVALID_ARGUMENT"))
        assertTrue(tools.search(args("query", "x".repeat(1001))).json.contains("INVALID_ARGUMENT"))
        assertTrue(tools.fetch(JsonObject(emptyMap())).json.contains("INVALID_ARGUMENT"))
    }

    @Test fun cancellationFromApprovalIsPropagated() = runBlocking {
        val tools = AgentWebTools { throw CancellationException("cancelled") }
        try {
            tools.search(args("query", "test"))
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
    }

    @Test fun approvalFailuresDoNotExposeSecrets() = runBlocking {
        val result = AgentWebTools { error("https://example.com?token=SECRET") }
            .fetch(args("url", "https://example.com"))
        assertTrue(result.json.contains("NETWORK_UNAVAILABLE"))
        assertFalse(result.json.contains("SECRET"))
        assertFalse(result.summary.contains("SECRET"))
    }

    @Test fun addressPolicyRejectsPrivateSpecialAndTransitionAddresses() {
        listOf("0.0.0.0", "10.1.2.3", "127.0.0.2", "169.254.169.254", "172.16.0.1", "172.31.255.255",
            "192.168.1.1", "100.64.0.1", "100.127.255.255", "198.18.0.1", "192.0.2.1",
            "198.51.100.1", "203.0.113.1", "224.0.0.1", "240.0.0.1", "255.255.255.255",
            "::", "::1", "fc00::1", "fd00::1", "fe80::1", "ff02::1", "64:ff9b::a00:1",
            "2002:7f00:1::", "2001::1", "2001:db8::1").forEach {
            assertFalse(it, AgentWebTools.isPublicAddress(InetAddress.getByName(it)))
        }
        listOf("8.8.8.8", "1.1.1.1", "172.32.0.1", "100.128.0.1", "2606:4700:4700::1111",
            "2001:4860:4860::8888").forEach {
            assertTrue(it, AgentWebTools.isPublicAddress(InetAddress.getByName(it)))
        }
    }

    @Test fun searchParsesActualDirectAndDuckDuckGoLinksAndSanitizesSources() {
        val html = """
            <div class="result"><a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fkotlinlang.org%2Fdocs%2Fhome.html%3Ftoken%3DSECRET%23fragment">Kotlin &amp; docs</a>
              <div class="result__snippet">Useful <b>documentation</b><script>bad()</script><style>bad</style><nav>menu</nav></div></div>
            <div class="result"><a class="result__a" href="https://developer.android.com/">Android</a><div class="result__snippet">Android docs</div></div>
            <div class="result"><a class="result__a" href="http://insecure.example/">HTTP</a></div>
            <div class="result"><a class="result__a" href="https://127.0.0.1/">Private</a></div>
            <div class="result"><a class="result__a" href="javascript:alert(1)">JS</a></div>
            <div class="result"><a class="result__a" href="https://user:SECRET@example.com/">Credentials</a></div>
        """.trimIndent()
        val sources = AgentWebTools.parseSearch(html)
        assertEquals(sources.toString(), 2, sources.size)
        assertEquals("https://kotlinlang.org/docs/home.html", sources[0]["url"]!!.jsonPrimitive.content)
        assertEquals("Kotlin & docs", sources[0]["title"]!!.jsonPrimitive.content)
        assertEquals("Useful documentation", sources[0]["snippet"]!!.jsonPrimitive.content)
        assertFalse(sources.toString().contains("SECRET"))
        assertEquals("https://developer.android.com/", sources[1]["url"]!!.jsonPrimitive.content)
    }

    @Test fun searchBoundsDeduplicatesAndNeverFabricatesSources() {
        val html = (0..12).joinToString("") {
            "<div class='result'><a class='result__a' href='https://example.com/$it'>Title</a>" +
                "<div class='result__snippet'>${"a".repeat(600)}</div></div>"
        }
        assertEquals(8, AgentWebTools.parseSearch(html).size)
        assertEquals(500, AgentWebTools.parseSearch(html)[0]["snippet"]!!.jsonPrimitive.content.length)
        val duplicate = "<div class='result'><a class='result__a' href='https://example.com/'>Title</a></div>"
        assertEquals(1, AgentWebTools.parseSearch(duplicate.repeat(10)).size)
        assertTrue(AgentWebTools.parseSearch("<html>Please solve this captcha</html>").isEmpty())
        assertTrue(AgentWebTools.parseSearch("<div class='no-results'>No results</div>").isEmpty())
    }
}
