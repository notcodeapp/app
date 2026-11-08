package com.notcode.mobile.browser

import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BrowserDownloadTest {
    @Test fun fileNamesAreSanitized() {
        assertEquals("report.pdf", BrowserDownloader.safeFileName("../../etc/report.pdf"))
        assertEquals("report.pdf", BrowserDownloader.safeFileName("C:\\temp\\report.pdf"))
        assertEquals("download", BrowserDownloader.safeFileName(null))
        assertEquals("download", BrowserDownloader.safeFileName("  "))
        assertEquals("a\u0000b".filter { !it.isISOControl() }, BrowserDownloader.safeFileName("a\u0000b"))
        assertTrue(BrowserDownloader.safeFileName("x".repeat(500)).length <= 128)
    }

    @Test fun unsupportedSchemesAndRemoteHttpAreRejected() = runBlocking {
        listOf("file:///etc/passwd", "content://provider/secret", "http://example.com/file", "javascript:alert(1)").forEach {
            assertTrue(it, runCatching {
                BrowserDownloader.save(DownloadRequest(it, "f", "", -1, null), null, ByteArrayOutputStream())
            }.isFailure)
        }
    }

    @Test fun loopbackDownloadStreamsBytes() = runBlocking {
        serve("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: 5\r\nConnection: close\r\n\r\nhello") { base ->
            val output = ByteArrayOutputStream()
            val saved = BrowserDownloader.save(DownloadRequest("$base/file.bin", "file.bin", "application/octet-stream", 5, null), null, output)
            assertEquals(5L, saved)
            assertEquals("hello", output.toString(Charsets.UTF_8))
        }
    }

    @Test fun declaredOversizeIsRejectedBeforeReading() = runBlocking {
        serve("HTTP/1.1 200 OK\r\nContent-Length: ${BrowserDownloader.MAX_BYTES + 1}\r\nConnection: close\r\n\r\n") { base ->
            val failure = runCatching {
                BrowserDownloader.save(DownloadRequest("$base/big.bin", "big.bin", "", -1, null), null, ByteArrayOutputStream())
            }.exceptionOrNull()
            assertNotNull(failure)
            assertTrue(failure!!.message!!.contains("200 MiB"))
        }
    }

    @Test fun redirectToFileSchemeIsBlocked() = runBlocking {
        serve("HTTP/1.1 302 Found\r\nLocation: file:///etc/passwd\r\nContent-Length: 0\r\nConnection: close\r\n\r\n") { base ->
            assertTrue(runCatching {
                BrowserDownloader.save(DownloadRequest("$base/redir", "f", "", -1, null), null, ByteArrayOutputStream())
            }.isFailure)
        }
    }

    @Test fun httpErrorIsReported() = runBlocking {
        serve("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n") { base ->
            val failure = runCatching {
                BrowserDownloader.save(DownloadRequest("$base/missing", "f", "", -1, null), null, ByteArrayOutputStream())
            }.exceptionOrNull()
            assertTrue(failure!!.message!!.contains("404"))
        }
    }

    private suspend fun serve(response: String, block: suspend (String) -> Unit) {
        ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { server ->
            val base = "http://127.0.0.1:${server.localPort}"
            val thread = Thread {
                runCatching {
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        val reader = socket.getInputStream().bufferedReader()
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                        }
                        socket.getOutputStream().write(response.toByteArray(Charsets.ISO_8859_1))
                        socket.getOutputStream().flush()
                    }
                }
            }
            thread.start()
            try {
                block(base)
            } finally {
                thread.join(5000)
            }
        }
    }
}