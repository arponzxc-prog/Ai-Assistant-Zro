package com.arponzxc.novaai.devstudio.preview

import com.arponzxc.novaai.devstudio.security.DevSandboxGuard
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class LocalWebPreviewServer {

    private var serverSocket: ServerSocket? = null
    private var rootDir: File? = null
    private var executor: ExecutorService? = null
    private val running = AtomicBoolean(false)

    var port: Int = -1
        private set

    val isRunning: Boolean
        get() = running.get()

    val baseUrl: String?
        get() = if (isRunning && port > 0) "http://127.0.0.1:$port" else null

    @Synchronized
    fun start(projectRoot: File): String {
        if (isRunning && rootDir?.canonicalPath == projectRoot.canonicalPath && port > 0) {
            return "http://127.0.0.1:$port"
        }
        stop()

        require(projectRoot.exists() && projectRoot.isDirectory) {
            "Preview directory does not exist: ${projectRoot.absolutePath}"
        }
        rootDir = projectRoot.canonicalFile

        val socket = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
        serverSocket = socket
        port = socket.localPort
        running.set(true)

        val pool = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "NovaWebPreviewWorker").apply { isDaemon = true }
        }
        executor = pool

        pool.execute {
            while (running.get() && !socket.isClosed) {
                try {
                    val client = socket.accept()
                    pool.execute { handleClient(client) }
                } catch (_: Exception) {
                    if (!running.get()) break
                }
            }
        }
        return "http://127.0.0.1:$port"
    }

    @Synchronized
    fun updateRoot(projectRoot: File) {
        if (projectRoot.exists() && projectRoot.isDirectory) {
            rootDir = projectRoot.canonicalFile
        }
    }

    @Synchronized
    fun stop() {
        running.set(false)
        port = -1
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        executor?.shutdownNow()
        executor = null
    }

    fun getDirectoryChangeToken(root: File? = rootDir): Long {
        val dir = root ?: return 0L
        if (!dir.exists() || !dir.isDirectory) return 0L
        var count = 0L
        var maxModified = 0L
        var totalSize = 0L
        dir.walkTopDown()
            .onEnter { it.name !in setOf(".nova_backups", ".git") }
            .filter { it.isFile }
            .forEach { f ->
                count++
                totalSize += f.length()
                if (f.lastModified() > maxModified) maxModified = f.lastModified()
            }
        return (maxModified * 31L) xor (totalSize shl 8) xor count
    }

    private fun handleClient(client: Socket) {
        client.use { socket ->
            socket.soTimeout = 5000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
            val requestLine = reader.readLine() ?: return
            // Drain headers
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }

            val parts = requestLine.split(" ")
            val method = parts.getOrNull(0)?.uppercase(Locale.ROOT) ?: "GET"
            val rawTarget = parts.getOrNull(1) ?: "/"
            val out = socket.getOutputStream()

            if (method != "GET" && method != "HEAD") {
                sendText(out, 405, "Method Not Allowed", "text/plain", "Method Not Allowed")
                return
            }

            val rawPath = rawTarget.substringBefore('?')
            val decodedPath = try {
                URLDecoder.decode(rawPath, "UTF-8")
            } catch (_: Exception) {
                rawPath
            }
            val cleanRel = decodedPath.trimStart('/')
            val workspace = rootDir ?: run {
                sendText(out, 500, "Internal Server Error", "text/plain", "Server root missing")
                return
            }

            val resolvedFile = try {
                val candidateRel = if (cleanRel.isBlank()) "index.html" else cleanRel
                val candidate = DevSandboxGuard.resolveSafeWorkspacePath(workspace, candidateRel)
                if (candidate.isDirectory) {
                    DevSandboxGuard.resolveSafeWorkspacePath(candidate, "index.html")
                } else {
                    candidate
                }
            } catch (_: SecurityException) {
                sendText(
                    out,
                    403,
                    "Forbidden",
                    "text/html",
                    "<html><body style='font-family:sans-serif;padding:24px;'><h2>403 Forbidden</h2><p>Access outside project workspace blocked.</p></body></html>"
                )
                return
            }

            if (!resolvedFile.exists() || !resolvedFile.isFile) {
                sendText(
                    out,
                    404,
                    "Not Found",
                    "text/html",
                    "<html><body style='font-family:sans-serif;padding:24px;background:#0D1117;color:#E6EDF3;'><h2>404 Not Found</h2><p>Requested resource <code>$cleanRel</code> does not exist in this project.</p></body></html>"
                )
                return
            }

            val bytes = resolvedFile.readBytes()
            val mime = mimeTypeFor(resolvedFile.name)
            sendBytes(out, 200, "OK", mime, bytes, headOnly = (method == "HEAD"))
        }
    }

    private fun sendText(
        out: OutputStream,
        code: Int,
        status: String,
        contentType: String,
        body: String
    ) {
        sendBytes(out, code, status, "$contentType; charset=utf-8", body.toByteArray(StandardCharsets.UTF_8), false)
    }

    private fun sendBytes(
        out: OutputStream,
        code: Int,
        status: String,
        contentType: String,
        body: ByteArray,
        headOnly: Boolean
    ) {
        val header = buildString {
            append("HTTP/1.1 ").append(code).append(' ').append(status).append("\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            append("Cache-Control: no-store, no-cache, must-revalidate\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Connection: close\r\n\r\n")
        }
        val baos = ByteArrayOutputStream()
        baos.write(header.toByteArray(StandardCharsets.US_ASCII))
        if (!headOnly) {
            baos.write(body)
        }
        out.write(baos.toByteArray())
        out.flush()
    }

    private fun mimeTypeFor(fileName: String): String {
        return when (fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "html", "htm" -> "text/html; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "js", "mjs" -> "application/javascript; charset=utf-8"
            "json" -> "application/json; charset=utf-8"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "ico" -> "image/x-icon"
            "woff2" -> "font/woff2"
            "woff" -> "font/woff"
            "ttf" -> "font/ttf"
            "wasm" -> "application/wasm"
            "xml" -> "application/xml"
            "txt", "md" -> "text/plain; charset=utf-8"
            else -> "application/octet-stream"
        }
    }
}
