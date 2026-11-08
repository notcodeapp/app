package com.notcode.mobile.browser

import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Serves only the supplied workspace, never an application directory or a directory listing. */
class ProjectPreviewServer(workspace: File) : Closeable {
    private val root = workspace.absoluteFile
    private val canonicalRoot = root.canonicalFile
    private val listener = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private val deadlines = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "preview-deadline").apply { isDaemon = true }
    }
    private val workers = ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, ArrayBlockingQueue(8),
        { task -> Thread(task, "preview-request").apply { isDaemon = true } })
    val baseUrl: String = "http://127.0.0.1:${listener.localPort}"

    init {
        try {
            require(root.isDirectory && root == canonicalRoot) { "Workspace must be a canonical directory" }
            rejectLinks(root)
        } catch (error: Exception) {
            listener.close()
            workers.shutdownNow()
            deadlines.shutdownNow()
            throw error
        }
        Thread({
            while (!listener.isClosed) {
                val socket = try { listener.accept() } catch (_: Exception) { break }
                clients.add(socket)
                try {
                    val timeout = deadlines.schedule({ runCatching { socket.close() } }, 20, TimeUnit.SECONDS)
                    workers.execute {
                        try { socket.use { runCatching { handle(it) } } }
                        finally { timeout.cancel(false); clients.remove(socket) }
                    }
                } catch (_: Exception) {
                    clients.remove(socket)
                    socket.close()
                }
            }
        }, "preview-listener").apply { isDaemon = true; start() }
    }

    fun urlFor(relativePath: String): String {
        resolve("/$relativePath")
        return baseUrl + URI(null, null, "/$relativePath", null).rawPath
    }

    internal fun resolve(decodedPath: String): File {
        require(decodedPath.startsWith('/') && '\\' !in decodedPath && decodedPath.none { it.isISOControl() })
        val parts = decodedPath.removePrefix("/").split('/')
        require(parts.none { it == "." || it == ".." || sensitive(it) }) { "Path is not available for preview" }
        var target = root
        for (part in parts.filter { it.isNotEmpty() }) {
            target = File(target, part)
            require(!Files.isSymbolicLink(target.toPath())) { "Symbolic links are not served" }
        }
        rejectLinks(root)
        val canonical = target.canonicalFile
        require(canonical.toPath().startsWith(canonicalRoot.toPath()) && canonicalRoot == root.canonicalFile)
        if (canonical.isDirectory) return resolve(decodedPath.trimEnd('/') + "/index.html")
        require(Files.isRegularFile(target.toPath(), LinkOption.NOFOLLOW_LINKS)) { "Page not found" }
        require(target.length() <= MAX_FILE_BYTES) { "Preview file exceeds 32 MiB" }
        return target
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = 5000
        val input = socket.getInputStream()
        var remaining = 16384
        fun line(): String {
            val bytes = ArrayList<Byte>()
            while (true) {
                require(--remaining >= 0) { "Headers too large" }
                val b = input.read()
                require(b != -1)
                if (b == 10) break
                bytes.add(b.toByte())
            }
            return bytes.toByteArray().toString(Charsets.US_ASCII).removeSuffix("\r")
        }
        var head = false
        fun respond(code: String, type: String = "text/plain", size: Long = 0, body: InputStream? = null) {
            val output = socket.getOutputStream()
            output.write(("HTTP/1.1 $code\r\nContent-Type: $type\r\nContent-Length: $size\r\n" +
                "Connection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\n" +
                "Referrer-Policy: no-referrer\r\nCross-Origin-Resource-Policy: same-origin\r\n\r\n").toByteArray(Charsets.US_ASCII))
            if (!head && body != null) {
                val buffer = ByteArray(16384)
                var left = size
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
                while (left > 0 && System.nanoTime() < deadline && !listener.isClosed) {
                    val count = body.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    left -= count
                }
            }
            output.flush()
        }
        try {
            val request = line().split(' ')
            require(request.size == 3 && request[2] in setOf("HTTP/1.0", "HTTP/1.1"))
            head = request[0] == "HEAD"
            if (request[0] !in setOf("GET", "HEAD")) { respond("405 Method Not Allowed"); return }
            val headers = mutableMapOf<String, String>()
            while (true) {
                val header = line()
                if (header.isEmpty()) break
                require(':' in header)
                val key = header.substringBefore(':').lowercase()
                require(key !in headers)
                headers[key] = header.substringAfter(':').trim()
            }
            require(headers["host"] == "127.0.0.1:${listener.localPort}")
            require(headers["origin"] == null || headers["origin"] == baseUrl)
            require(headers["sec-fetch-site"] != "cross-site")
            require(headers["transfer-encoding"] == null && (headers["content-length"] ?: "0") == "0")
            val uri = URI(request[1])
            require(!uri.isAbsolute && uri.rawAuthority == null && uri.rawFragment == null)
            val file = resolve(uri.path)
            // NOFOLLOW prevents a final-component symlink swap between validation and open.
            Files.newByteChannel(file.toPath(), setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)).use { channel ->
                require(resolve(uri.path) == file && channel.size() <= MAX_FILE_BYTES)
                respond("200 OK", contentType(file.extension.lowercase()), channel.size(), Channels.newInputStream(channel))
            }
        } catch (_: Exception) {
            runCatching { respond("403 Forbidden") }
        }
    }

    override fun close() {
        listener.close()
        clients.forEach { runCatching { it.close() } }
        workers.shutdownNow()
        deadlines.shutdownNow()
    }

    companion object {
        private const val MAX_FILE_BYTES = 32L * 1024 * 1024
        private fun rejectLinks(file: File) {
            var node: File? = file
            while (node != null) {
                require(!Files.isSymbolicLink(node.toPath())) { "Symbolic workspace path is not allowed" }
                node = node.parentFile
            }
        }
        private fun sensitive(segment: String): Boolean {
            val name = segment.lowercase()
            return name.startsWith('.') || name in setOf("node_modules", "id_rsa", "id_ed25519", "id_dsa", "credentials", "secrets", "private", "keystore") ||
                name.endsWith("-sync.json") || name.contains("secret") || name.contains("credential") ||
                name.endsWith(".key") || name.endsWith(".pem") || name.endsWith(".p12") || name.endsWith(".pfx") ||
                name.endsWith(".jks") || name.endsWith(".keystore") || name == "local.properties"
        }
        private fun contentType(extension: String): String = when (extension) {
            "html", "htm" -> "text/html; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "js", "mjs" -> "text/javascript; charset=utf-8"
            "json", "map" -> "application/json"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "ico" -> "image/x-icon"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "txt" -> "text/plain; charset=utf-8"
            else -> "application/octet-stream"
        }
    }
}
