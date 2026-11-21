package com.notcode.mobile.desktop

import java.math.BigInteger
import java.net.Proxy
import java.security.Principal
import java.security.PublicKey
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.HttpsURLConnection
import org.junit.Assert.*
import org.junit.Test

class DesktopSecurityTest {
    private val pin = "ab".repeat(32)
    private val connection = DesktopConnection("https://192.168.1.20:43817", pin, "phone", "project", "Project", "desktop.test.bearer")
    private fun invitation(address: String = connection.address, fingerprint: String = pin, token: String = "one-use-token-for-tests") =
        """{"version":1,"address":"$address","fingerprint":"$fingerprint","token":"$token"}"""
    private fun rejects(block: () -> Unit) { assertTrue(runCatching(block).isFailure) }

    @Test fun invitationAcceptsOnlyCanonicalPrivateNumericHttps() {
        listOf("10.0.0.1", "172.16.0.2", "172.31.255.254", "192.168.0.1", "127.0.0.1").forEach {
            assertEquals("https://$it:443", DesktopInvitation.parse(invitation("https://$it:443")).address)
        }
        listOf("http://192.168.1.20:43817", "https://8.8.8.8:443", "https://172.32.0.1:443", "https://169.254.1.1:443",
            "https://192.168.1.20:43817/", "https://192.168.1.20:43817/v1", "https://192.168.1.20:43817?token=x",
            "https://user@192.168.1.20:43817", "https://192.168.1.20:43817#fragment", "https://localhost:443",
            "https://[::1]:443", "https://192.168.001.20:443", "https://2130706433:443", "https://127.1:443",
            "https://192.168.1.20", "https://192.168.1.20:0", "https://192.168.1.20:65536").forEach { address -> rejects { DesktopInvitation.parse(invitation(address)) } }
        rejects { DesktopInvitation.parse(invitation(fingerprint = "aa")) }
        rejects { DesktopInvitation.parse(invitation(token = "short")) }
        rejects { DesktopInvitation.parse(invitation() + " ".repeat(4096)) }
        rejects { DesktopInvitation.parse(invitation().replace("\"version\":1", "\"version\":2")) }
    }

    @Test fun leafDerPinAndValidityAreBothMandatory() {
        val cert = TestCertificate(byteArrayOf(1, 2, 3))
        val trust = DesktopPinTrustManager(sha256(cert.encoded))
        trust.checkServerTrusted(arrayOf(cert), "RSA")
        rejects { trust.checkServerTrusted(arrayOf(TestCertificate(byteArrayOf(1, 2, 4))), "RSA") }
        rejects { trust.checkServerTrusted(emptyArray(), "RSA") }
        rejects { trust.checkServerTrusted(arrayOf(TestCertificate(byteArrayOf(1, 2, 3), expired = true)), "RSA") }
        rejects { trust.checkClientTrusted(arrayOf(cert), "RSA") }
        // Pin is the leaf DER, never a matching chain/root/SPKI somewhere else.
        rejects { trust.checkServerTrusted(arrayOf(TestCertificate(byteArrayOf(7)), cert), "RSA") }
    }

    @Test fun clientNeverChangesGlobalTlsOrFollowsRedirectsOrProxies() {
        val globalSocket = HttpsURLConnection.getDefaultSSLSocketFactory()
        val globalVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
        DesktopApi(connection, "native-bearer-secret").use { api ->
            assertFalse(api.client.followRedirects)
            assertFalse(api.client.followSslRedirects)
            assertFalse(api.client.retryOnConnectionFailure)
            assertEquals(Proxy.NO_PROXY, api.client.proxy)
            assertSame(globalSocket, HttpsURLConnection.getDefaultSSLSocketFactory())
            assertSame(globalVerifier, HttpsURLConnection.getDefaultHostnameVerifier())
        }
    }

    @Test fun previewAlwaysMapsUnderFixedPortAndBrowserNeverSuppliesAuth() {
        DesktopApi(connection, "native-bearer-secret").use { api ->
            val request = api.previewRequest(3000, "/v1/approvals/id", "x=1", "GET", mapOf("authorization" to "attacker", "cookie" to "secret-cookie", "x-notcode-token" to "attacker", "host" to "evil", "accept" to "text/html"), null)
            assertEquals("/v1/preview/3000/v1/approvals/id", request.url.encodedPath)
            assertEquals("Bearer native-bearer-secret", request.header("Authorization"))
            assertFalse(request.url.toString().contains("native-bearer-secret"))
            assertNull(request.header("Cookie")); assertNull(request.header("x-notcode-token")); assertNull(request.header("Host"))
            assertEquals("192.168.1.20", request.url.host)
            listOf("//evil.com/x", "/../status", "/%2e%2e/status", "/%252e%252e/status", "/%25252e%25252e/status", "/a\\b", "/%00", "https://evil.com").forEach { path ->
                rejects { api.previewRequest(3000, path, null, "GET", emptyMap(), null) }
            }
            rejects { api.previewRequest(3000, "/", null, "CONNECT", emptyMap(), null) }
        }
        listOf("Authorization", "Cookie", "Set-Cookie", "Proxy-Authorization", "X-NotCode-Token", "X-Forwarded-Host", "Connection", "Upgrade", "Host", "Location").forEach { assertFalse(previewHeaderAllowed(it)) }
    }

    @Test fun httpRelayRejectsWebsocketWithoutOpeningAnUpstream() {
        DesktopApi(connection, "native-bearer-secret").use { api ->
            var report = ""
            val relay = DesktopPreviewRelay(api, 3000, { report = it }, { fail("No network should be opened") })
            val session = java.lang.reflect.Proxy.newProxyInstance(
                fi.iki.elonen.NanoHTTPD.IHTTPSession::class.java.classLoader,
                arrayOf(fi.iki.elonen.NanoHTTPD.IHTTPSession::class.java),
            ) { _, method, _ ->
                when (method.name) {
                    "getHeaders" -> mapOf("upgrade" to "websocket")
                    else -> error("Unexpected session access: ${method.name}")
                }
            } as fi.iki.elonen.NanoHTTPD.IHTTPSession
            val response = relay.serve(session)
            assertEquals(501, response.status.requestStatus)
            assertTrue(report.contains("не поддерживаются"))
            assertTrue(api.client.dispatcher.queuedCalls().isEmpty())
            assertTrue(api.client.dispatcher.runningCalls().isEmpty())
        }
    }

    @Test fun jsonBodiesAreBoundedAndShortBodiesDoNotNeedFullLimit() {
        okhttp3.ResponseBody.create(null, "{}").use { assertEquals("{}", desktopUtf8Text(boundedDesktopBody(it, 8))) }
        rejects { okhttp3.ResponseBody.create(null, "x".repeat(9)).use { boundedDesktopBody(it, 8) } }
    }

    @Test fun desktopUtf8IsExactForCyrillicNonBmpAndBomAndRejectsInvalidInput() {
        val valid = "\uFEFFПривет, \uD83D\uDE80"
        assertEquals(valid, desktopUtf8Text(desktopUtf8Bytes(valid)))
        assertArrayEquals(valid.toByteArray(Charsets.UTF_8), desktopUtf8Bytes(valid))
        assertEquals(0xEF.toByte(), desktopUtf8Bytes(valid)[0])
        rejects { desktopUtf8Bytes("broken-\uD800") }
        rejects { desktopUtf8Bytes("broken-\uDC00") }
        listOf(byteArrayOf(0x80.toByte()), byteArrayOf(0xC0.toByte(), 0xAF.toByte()),
            byteArrayOf(0xE2.toByte(), 0x82.toByte()), byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()))
            .forEach { malformed -> rejects { desktopUtf8Text(malformed) } }
    }

    @Test fun jsonRequestRejectsEscapedUnpairedSurrogateBeforeNetwork() {
        DesktopApi(connection, "native-bearer-secret").use { api ->
            rejects { api.request(listOf("v1", "test"), "POST", kotlinx.serialization.json.buildJsonObject {
                put("content", kotlinx.serialization.json.JsonPrimitive("broken-\uD800"))
            }) }
        }
    }

    @Test fun unsafePathsAndCredentialsAreExcluded() {
        listOf("../x", "/x", "C:/x", "a\\x", ".git/config", "node_modules/x", ".keys/secret", ".env", "a/.env.local",
            ".claude/auth", ".codex/auth.json", "credentials.json", "a/../x", "a//b", "a/ x", "x.desktop-conflict-abc").forEach { path -> rejects { safeDesktopPath(path) } }
        assertEquals("src/main.kt", safeDesktopPath("src/main.kt"))
        rejects { validateRemoteFile(DesktopFile("a", "text", pin), "a") }
        rejects { validateRemoteFile(DesktopFile("a", "text", textHash("text")), "b") }
        rejects { validateRemoteFile(DesktopFile("a", "\u0000", textHash("\u0000")), "a") }
    }

    @Test fun cliVersionCannotInjectShellAndNeverCopiesAuth() {
        val command = desktopCliInstallCommand("codex", "1.2.3-beta.1")
        assertTrue(command.contains("'@openai/codex@1.2.3-beta.1'"))
        assertFalse(command.contains(".exe")); assertFalse(command.contains("auth.json"))
        listOf("latest;curl evil", "1.0.0'", "$(id)", "--prefix=/tmp", "https://evil/x", "").forEach { rejects { desktopCliInstallCommand("codex", it) } }
        rejects { desktopCliInstallCommand("evil", "latest") }
    }

    /** Deterministic DER and clock failures keep identity policy testable without native I/O. */
    private class TestCertificate(private val der: ByteArray, private val expired: Boolean = false) : X509Certificate() {
        override fun checkValidity() { if (expired) throw CertificateExpiredException("expired") }
        override fun checkValidity(date: Date) = checkValidity()
        override fun getEncoded() = der.copyOf()
        override fun getVersion() = 3
        override fun getSerialNumber() = BigInteger.ONE
        override fun getIssuerDN(): Principal = Principal { "issuer" }
        override fun getSubjectDN(): Principal = Principal { "subject" }
        override fun getNotBefore() = Date(0)
        override fun getNotAfter() = Date(Long.MAX_VALUE)
        override fun getTBSCertificate() = der.copyOf()
        override fun getSignature() = ByteArray(0)
        override fun getSigAlgName() = "SHA256withRSA"
        override fun getSigAlgOID() = "1.2.840.113549.1.1.11"
        override fun getSigAlgParams(): ByteArray? = null
        override fun getIssuerUniqueID(): BooleanArray? = null
        override fun getSubjectUniqueID(): BooleanArray? = null
        override fun getKeyUsage(): BooleanArray? = null
        override fun getBasicConstraints() = -1
        override fun verify(key: PublicKey?) = Unit
        override fun verify(key: PublicKey?, sigProvider: String?) = Unit
        override fun toString() = "TestCertificate"
        override fun getPublicKey(): PublicKey? = null
        override fun hasUnsupportedCriticalExtension() = false
        override fun getCriticalExtensionOIDs(): MutableSet<String>? = null
        override fun getNonCriticalExtensionOIDs(): MutableSet<String>? = null
        override fun getExtensionValue(oid: String?): ByteArray? = null
    }
}
