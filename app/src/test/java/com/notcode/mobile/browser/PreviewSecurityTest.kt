package com.notcode.mobile.browser

import java.io.File
import java.net.Socket
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PreviewSecurityTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun urlPolicy() {
        assertEquals("http://localhost:5173/", PreviewUrlPolicy.normalize("localhost:5173/"))
        assertTrue(PreviewUrlPolicy.allows("http://127.0.0.1:3000"))
        assertTrue(PreviewUrlPolicy.allows("http://[::1]:3000/"))
        assertTrue(PreviewUrlPolicy.allows("https://example.com"))
        listOf("file:///etc/passwd", "content://provider/secret", "intent://example", "javascript:alert(1)",
            "data:text/html,test", "http://example.com", "https://user:pass@example.com", "http://127.0.0.1.evil.test/",
            "http://localhost@evil.test/", "https://example.com:99999/").forEach { assertFalse(it, PreviewUrlPolicy.allows(it)) }
    }

    @Test fun containmentSensitivePathsAndBinary() {
        val root = temp.newFolder("work").canonicalFile
        File(root, "index.html").writeText("<h1>Preview</h1>")
        val image = byteArrayOf(0, 1, 2, -1, -128)
        File(root, "image.png").writeBytes(image)
        File(root, ".env").writeText("SECRET=hidden")
        ProjectPreviewServer(root).use { server ->
            listOf("/../outside", "/.env", "/.git/config", "/.ssh/id_rsa", "/foo/../../index.html", "/private.key", "/a\\b").forEach {
                assertTrue(it, runCatching { server.resolve(it) }.isFailure)
            }
            assertEquals(File(root, "index.html"), server.resolve("/"))
            val response = request(server, "GET", "/image.png")
            assertTrue(response.toString(Charsets.ISO_8859_1).startsWith("HTTP/1.1 200"))
            assertArrayEquals(image, response.takeLast(image.size).toByteArray())
            assertTrue(request(server, "GET", "/%2e%2e/outside").toString(Charsets.US_ASCII).startsWith("HTTP/1.1 403"))
            assertTrue(request(server, "POST", "/index.html").toString(Charsets.US_ASCII).startsWith("HTTP/1.1 405"))
            assertTrue(request(server, "HEAD", "/image.png").toString(Charsets.US_ASCII).endsWith("\r\n\r\n"))
        }
    }

    @Test fun oversizedFilesAndHostRebindingAreRejected() {
        val root = temp.newFolder("work").canonicalFile
        File(root, "index.html").writeText("preview")
        java.io.RandomAccessFile(File(root, "large.png"), "rw").use { it.setLength(33L * 1024 * 1024) }
        ProjectPreviewServer(root).use { server ->
            assertTrue(runCatching { server.resolve("/large.png") }.isFailure)
            val uri = java.net.URI(server.baseUrl)
            Socket(uri.host, uri.port).use { socket ->
                socket.soTimeout = 3000
                socket.getOutputStream().write("GET /index.html HTTP/1.1\r\nHost: attacker.example\r\n\r\n".toByteArray())
                assertTrue(socket.getInputStream().readBytes().toString(Charsets.US_ASCII).startsWith("HTTP/1.1 403"))
            }
        }
    }

    @Test fun symbolicLinksAreRejected() {
        val root = temp.newFolder("work").canonicalFile
        val outside = temp.newFile("outside.html")
        try { Files.createSymbolicLink(File(root, "linked.html").toPath(), outside.toPath()) }
        catch (_: UnsupportedOperationException) { return }
        catch (_: java.nio.file.FileSystemException) { return } // Windows without symlink privilege.
        ProjectPreviewServer(root).use { assertTrue(runCatching { it.resolve("/linked.html") }.isFailure) }
    }

    private fun request(server: ProjectPreviewServer, method: String, path: String): ByteArray {
        val uri = java.net.URI(server.baseUrl)
        return Socket(uri.host, uri.port).use {
            it.soTimeout = 3000
            it.getOutputStream().write("$method $path HTTP/1.1\r\nHost: ${uri.rawAuthority}\r\n\r\n".toByteArray())
            it.getInputStream().readBytes()
        }
    }
}
