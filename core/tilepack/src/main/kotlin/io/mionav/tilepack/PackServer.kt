package io.mionav.tilepack

import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Read-only HTTP server for one pack. Three routes, no dependencies, never lists a directory.
 *
 * The port is not a contract. 8080 is tried first so a laptop can pull the pack from a known
 * address; if it is taken the server binds an ephemeral port instead. The phone always sends the
 * full URL to the watch over BLE, so the watch never guesses.
 *
 * One request per connection, `Connection: close`. One watch is the whole client set.
 */
class PackServer(
    private val pack: File,
    private val identity: PackIdentity,
    private val manifest: File? = null,
    private val preferredPort: Int = DEFAULT_PORT,
    private val idleTimeoutMs: Long = DEFAULT_IDLE_MS,
) : AutoCloseable {

    companion object {
        const val DEFAULT_PORT = 8080
        const val DEFAULT_IDLE_MS = 10 * 60 * 1000L

        const val PACK_PATH = "/pack.mbtiles"
        const val MANIFEST_PATH = "/manifest.json"

        const val HEADER_PACK = "X-Mionav-Pack"
        const val HEADER_VERSION = "X-Mionav-Version"
        const val HEADER_SHA256 = "X-Mionav-Sha256"
        const val HEADER_TILES = "X-Mionav-Tiles"
    }

    private val running = AtomicBoolean(false)
    private val lastRequestAt = AtomicLong(0)
    private val requestCounter = AtomicInteger(0)

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var acceptThread: Thread? = null

    @Volatile
    private var idleThread: Thread? = null

    /** The port actually bound, or -1 when the server is stopped. */
    val port: Int
        get() = serverSocket?.localPort ?: -1

    /** Requests served since [start]; lets callers prove that nothing was fetched. */
    val requests: Int
        get() = requestCounter.get()

    fun url(host: String): String = "http://$host:$port"

    fun start(host: String = "0.0.0.0") {
        check(!running.get()) { "PackServer already running" }
        val bound = bind(host)
        serverSocket = bound
        lastRequestAt.set(System.currentTimeMillis())
        running.set(true)
        acceptThread = Thread({ acceptLoop(bound) }, "pack-http").apply {
            isDaemon = true
            start()
        }
        if (idleTimeoutMs > 0) {
            idleThread = Thread({ idleLoop() }, "pack-idle").apply {
                isDaemon = true
                start()
            }
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { serverSocket?.close() }
        serverSocket = null
        idleThread?.let { runCatching { it.interrupt() } }
        idleThread = null
        acceptThread?.takeIf { it !== Thread.currentThread() }?.let { runCatching { it.join(1000) } }
        acceptThread = null
    }

    override fun close() = stop()

    // --- binding ---------------------------------------------------------------------------

    private fun bind(host: String): ServerSocket {
        return try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(host, preferredPort), 8)
            }
        } catch (_: IOException) {
            // Preferred port taken (or not permitted): let the kernel pick one.
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(host, 0), 8)
            }
        }
    }

    private fun acceptLoop(bound: ServerSocket) {
        while (running.get()) {
            val client = try {
                bound.accept()
            } catch (_: IOException) {
                break
            }
            runCatching { serve(client) }
        }
    }

    private fun idleLoop() {
        while (running.get()) {
            try {
                Thread.sleep(50)
            } catch (_: InterruptedException) {
                return
            }
            val idleFor = System.currentTimeMillis() - lastRequestAt.get()
            if (idleFor > idleTimeoutMs) {
                stop()
                return
            }
        }
    }

    // --- one request -----------------------------------------------------------------------

    private fun serve(client: Socket) {
        client.use { socket ->
            socket.soTimeout = 5000
            val input = socket.getInputStream()
            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val path = parts[1].substringBefore('?')
            val headers = readHeaders(input)
            lastRequestAt.set(System.currentTimeMillis())
            requestCounter.incrementAndGet()

            val out = BufferedOutputStream(socket.getOutputStream())
            try {
                when {
                    path.contains("..") -> textResponse(out, 404, "Not Found", "not found")
                    path == PACK_PATH -> servePack(method, headers, out)
                    path == MANIFEST_PATH -> serveManifest(method, out)
                    else -> textResponse(out, 404, "Not Found", "not found")
                }
                out.flush()
            } catch (_: IOException) {
                // Client vanished mid-response; the next request starts clean.
            }
        }
    }

    private fun servePack(method: String, headers: Map<String, String>, out: OutputStream) {
        val size = pack.length()
        val identityHeaders = listOf(
            "$HEADER_PACK: ${identity.id}",
            "$HEADER_VERSION: ${identity.version}",
            "$HEADER_SHA256: ${identity.sha256}",
            "$HEADER_TILES: ${identity.tiles}",
            "Accept-Ranges: bytes",
            "Content-Type: application/octet-stream",
            "Cache-Control: no-store",
        )

        if (method == "HEAD") {
            writeHead(out, 200, "OK", identityHeaders + "Content-Length: $size")
            return
        }
        if (method != "GET") {
            textResponse(out, 405, "Method Not Allowed", "method not allowed")
            return
        }

        val start = when (val range = parseRange(headers["range"], size)) {
            Range.Invalid -> {
                textResponse(out, 416, "Range Not Satisfiable", "bad range")
                return
            }
            is Range.From -> range.start
            Range.None -> 0L
        }

        if (start == 0L && headers["range"] == null) {
            writeHead(out, 200, "OK", identityHeaders + "Content-Length: $size")
        } else {
            val length = size - start
            writeHead(
                out,
                206,
                "Partial Content",
                identityHeaders + listOf(
                    "Content-Range: bytes $start-${size - 1}/$size",
                    "Content-Length: $length",
                ),
            )
        }
        copy(pack, start, out)
    }

    private fun serveManifest(method: String, out: OutputStream) {
        val file = manifest
        if (file == null || !file.isFile) {
            textResponse(out, 404, "Not Found", "no manifest")
            return
        }
        if (method == "HEAD") {
            writeHead(out, 200, "OK", listOf("Content-Length: ${file.length()}"))
            return
        }
        if (method != "GET") {
            textResponse(out, 405, "Method Not Allowed", "method not allowed")
            return
        }
        writeHead(
            out,
            200,
            "OK",
            listOf(
                "Content-Type: application/json",
                "Content-Length: ${file.length()}",
            ),
        )
        file.inputStream().use { it.copyTo(out, 16 * 1024) }
    }

    // --- helpers ---------------------------------------------------------------------------

    private sealed interface Range {
        data object None : Range
        data class From(val start: Long) : Range
        data object Invalid : Range
    }

    /** Only `bytes=N-` is understood; multi-range requests fall back to the whole file. */
    private fun parseRange(header: String?, size: Long): Range {
        if (header == null) return Range.None
        val spec = header.trim()
        if (!spec.startsWith("bytes=")) return Range.None
        val value = spec.removePrefix("bytes=")
        if (value.contains(',')) return Range.None
        if (!value.endsWith("-")) return Range.None
        val start = value.removeSuffix("-").toLongOrNull() ?: return Range.Invalid
        if (start < 0) return Range.Invalid
        if (start >= size) return Range.Invalid
        return Range.From(start)
    }

    private fun writeHead(out: OutputStream, code: Int, reason: String, headers: List<String>) {
        val head = buildString {
            append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n")
            append("Connection: close\r\n")
            for (header in headers) append(header).append("\r\n")
            append("\r\n")
        }
        out.write(head.toByteArray(Charsets.ISO_8859_1))
    }

    private fun textResponse(out: OutputStream, code: Int, reason: String, body: String) {
        val bytes = body.toByteArray()
        writeHead(
            out,
            code,
            reason,
            listOf("Content-Type: text/plain; charset=utf-8", "Content-Length: ${bytes.size}"),
        )
        out.write(bytes)
    }

    private fun copy(file: File, start: Long, out: OutputStream) {
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(start)
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = raf.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
            }
        }
    }

    private fun readLine(input: InputStream): String? {
        val builder = StringBuilder()
        while (true) {
            val c = input.read()
            if (c == -1) return if (builder.isEmpty()) null else builder.toString()
            if (c == '\n'.code) return builder.toString().trimEnd('\r')
            builder.append(c.toChar())
            if (builder.length > 8192) return null
        }
    }

    private fun readHeaders(input: InputStream): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        return headers
    }
}
