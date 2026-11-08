package com.notcode.mobile.browser

import java.net.URI

/** Shared by address entry, redirects, subresources, and explicit external navigation. */
object PreviewUrlPolicy {
    fun normalize(raw: String): String {
        val value = raw.trim()
        require(value.isNotEmpty() && value.length <= 8192 && value.none { it.isISOControl() }) {
            "Enter an HTTP or HTTPS URL"
        }
        val explicitScheme = Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(value)
        val localAddress = Regex("^(localhost|127\\.0\\.0\\.1|\\[::1])(?::[0-9]+)?(?:/|$)", RegexOption.IGNORE_CASE)
            .containsMatchIn(value)
        val candidate = when {
            localAddress -> "http://$value"
            explicitScheme -> value
            else -> "https://$value"
        }
        val uri = URI(candidate)
        val scheme = uri.scheme?.lowercase()
        require(scheme == "http" || scheme == "https") { "Only HTTP and HTTPS pages are supported" }
        require(!uri.isOpaque && !uri.host.isNullOrBlank() && uri.rawUserInfo == null) {
            "Invalid URL or embedded credentials"
        }
        require(uri.port == -1 || uri.port in 1..65535) { "Invalid port" }
        require(scheme != "http" || isLoopback(uri.host)) { "Remote pages require HTTPS; HTTP is allowed only on loopback" }
        return uri.toASCIIString()
    }

    fun allows(url: String): Boolean = runCatching {
        // Navigation must already have a scheme: never turn file/content/intent into web URLs.
        val uri = URI(url)
        require(uri.scheme != null)
        normalize(url)
    }.isSuccess

    private fun isLoopback(host: String): Boolean =
        host.equals("localhost", true) || host == "127.0.0.1" || host == "[::1]" || host == "::1"
}
