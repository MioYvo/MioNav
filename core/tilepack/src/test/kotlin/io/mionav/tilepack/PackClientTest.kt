package io.mionav.tilepack

import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PackClientTest {

    private lateinit var dir: File
    private lateinit var body: ByteArray
    private lateinit var identity: PackIdentity
    private lateinit var dest: File
    private var server: PackServer? = null
    private var stub: StubServer? = null

    @BeforeTest
    fun setUp() {
        dir = kotlin.io.path.createTempDirectory("mionav-client").toFile()
        body = Random(11).nextBytes(250_000)
        identity = PackIdentity("region", "2026.10.29", body.size.toLong(), sha256Of(body), tiles = 9)
        dest = File(dir, "tiles.mbtiles")
    }

    @AfterTest
    fun tearDown() {
        server?.stop()
        stub?.close()
        dir.deleteRecursively()
    }

    private fun startRealServer(): String {
        val pack = File(dir, "source.mbtiles").apply { writeBytes(body) }
        val started = PackServer(pack, identity, preferredPort = 0)
        started.start("127.0.0.1")
        server = started
        return started.url("127.0.0.1")
    }

    private fun startStub(
        served: ByteArray = body,
        announces: PackIdentity = identity,
        truncateFirstGetAfter: Int? = null,
    ): String {
        val started = StubServer(served, announces, truncateFirstGetAfter)
        stub = started
        return started.baseUrl
    }

    @Test
    fun `first sync installs the pack`() {
        val url = startRealServer()
        val result = PackClient().sync(url, dest)
        assertIs<SyncResult.Updated>(result)
        assertTrue(dest.isFile)
        assertTrue(dest.readBytes().contentEquals(body))
        assertFalse(File(dir, "tiles.mbtiles.part").exists(), "part file survived")
        assertTrue(File(dir, "tiles.mbtiles.json").isFile, "sidecar missing")
    }

    @Test
    fun `refuses when the watch is short of space`() {
        val url = startRealServer()
        val result = PackClient(freeSpace = { 1_000L }).sync(url, dest)
        val failure = assertIs<SyncResult.Failed>(result)
        assertTrue(failure.reason.contains("存储空间不足"), "reason was ${failure.reason}")
        assertFalse(dest.exists(), "wrote a pack it could not fit")
        assertFalse(File(dir, "tiles.mbtiles.part").exists(), "left a partial file behind")
    }

    @Test
    fun `resume asks only for the rest`() {
        val half = File(dir, "tiles.mbtiles.part").apply { writeBytes(body.copyOfRange(0, 100_000)) }
        PackSidecar.write(File(dir, "tiles.mbtiles.part.json"), identity)
        val url = startStub()

        val result = PackClient().sync(url, dest)
        assertIs<SyncResult.Updated>(result)
        assertTrue(dest.readBytes().contentEquals(body))
        val rangeRequest = stub!!.seenHeaders.any { it["range"] == "bytes=100000-" }
        assertTrue(rangeRequest, "client did not resume; headers: ${stub!!.seenHeaders}")
        assertEquals(0, half.length().takeIf { half.exists() } ?: 0)
    }

    @Test
    fun `stale part identity restarts from zero`() {
        File(dir, "tiles.mbtiles.part").writeBytes(body.copyOfRange(0, 100_000))
        PackSidecar.write(File(dir, "tiles.mbtiles.part.json"), identity.copy(sha256 = "f".repeat(64)))
        val url = startStub()

        val result = PackClient().sync(url, dest)
        assertIs<SyncResult.Updated>(result)
        assertTrue(dest.readBytes().contentEquals(body))
        val ranged = stub!!.seenHeaders.any { it.containsKey("range") }
        assertFalse(ranged, "client resumed from a part that belonged to another pack")
    }

    @Test
    fun `bad checksum keeps the old pack`() {
        dest.writeBytes("old pack".toByteArray())
        val corrupted = body.copyOf().also { it[5] = (it[5] + 1).toByte() }
        val url = startStub(served = corrupted)

        val result = PackClient().sync(url, dest)
        assertIs<SyncResult.Failed>(result)
        assertEquals("old pack", dest.readText())
        assertFalse(File(dir, "tiles.mbtiles.part").exists(), "corrupt part was kept")
    }

    @Test
    fun `second sync is up to date and fetches nothing`() {
        val url = startRealServer()
        assertIs<SyncResult.Updated>(PackClient().sync(url, dest))
        val afterFirst = server!!.requests

        val second = PackClient().sync(url, dest)
        assertIs<SyncResult.UpToDate>(second)
        assertEquals(afterFirst + 1, server!!.requests, "up-to-date sync sent more than one HEAD")
    }

    @Test
    fun `interrupted download resumes on the next attempt`() {
        val url = startStub(truncateFirstGetAfter = 60_000)

        val first = PackClient().sync(url, dest)
        assertIs<SyncResult.Failed>(first)
        val part = File(dir, "tiles.mbtiles.part")
        assertTrue(part.isFile, "partial file was discarded")
        assertEquals(60_000L, part.length())

        val second = PackClient().sync(url, dest)
        assertIs<SyncResult.Updated>(second)
        assertTrue(dest.readBytes().contentEquals(body))
        val resumed = stub!!.seenHeaders.any { it["range"] == "bytes=60000-" }
        assertTrue(resumed, "second attempt did not resume; headers: ${stub!!.seenHeaders}")
    }

    private class StubServer(
        private val served: ByteArray,
        private val announces: PackIdentity,
        private val truncateFirstGetAfter: Int? = null,
    ) : AutoCloseable {

        private var truncated = false

        private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val running = java.util.concurrent.atomic.AtomicBoolean(true)
        val seenHeaders = java.util.Collections.synchronizedList(mutableListOf<Map<String, String>>())
        val baseUrl: String = "http://127.0.0.1:${socket.localPort}"

        private val worker = thread(isDaemon = true, name = "stub-http") {
            while (running.get()) {
                val client = try {
                    socket.accept()
                } catch (_: IOException) {
                    return@thread
                }
                client.use { serve(it) }
            }
        }

        private fun serve(client: Socket) {
            val input = client.getInputStream()
            val requestLine = readLine(input) ?: return
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val at = line.indexOf(':')
                if (at > 0) headers[line.substring(0, at).trim().lowercase()] = line.substring(at + 1).trim()
            }
            seenHeaders.add(headers)

            val method = requestLine.substringBefore(' ')
            val out = client.getOutputStream()
            val head = StringBuilder("HTTP/1.1 ")
            if (method == "HEAD") {
                out.write(
                    (head.append("200 OK\r\n")
                        .append("Content-Length: ${announces.bytes}\r\n")
                        .append("X-Mionav-Pack: ${announces.id}\r\n")
                        .append("X-Mionav-Version: ${announces.version}\r\n")
                        .append("X-Mionav-Sha256: ${announces.sha256}\r\n")
                        .append("X-Mionav-Tiles: ${announces.tiles}\r\n")
                        .append("Accept-Ranges: bytes\r\n\r\n")).toString().toByteArray(),
                )
                out.flush()
                return
            }

            val rangeStart = headers["range"]?.removePrefix("bytes=")?.removeSuffix("-")?.toLongOrNull()
            if (rangeStart != null && rangeStart < served.size) {
                val slice = served.copyOfRange(rangeStart.toInt(), served.size)
                val responseHead = "HTTP/1.1 206 Partial Content\r\n" +
                    "Content-Range: bytes $rangeStart-${served.size - 1}/${served.size}\r\n" +
                    "Content-Length: ${slice.size}\r\n\r\n"
                out.write(responseHead.toByteArray())
                out.write(slice)
            } else {
                val cut = if (!truncated) truncateFirstGetAfter else null
                truncated = true
                val slice = if (cut == null) served else served.copyOfRange(0, cut)
                out.write("HTTP/1.1 200 OK\r\nContent-Length: ${slice.size}\r\n\r\n".toByteArray())
                out.write(slice)
            }
            out.flush()
        }

        private fun readLine(input: java.io.InputStream): String? {
            val builder = StringBuilder()
            while (true) {
                val c = input.read()
                if (c == -1) return if (builder.isEmpty()) null else builder.toString()
                if (c == '\n'.code) return builder.toString().trimEnd('\r')
                builder.append(c.toChar())
            }
        }

        override fun close() {
            running.set(false)
            runCatching { socket.close() }
        }
    }

    private companion object {
        fun sha256Of(bytes: ByteArray): String =
            java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
    }
}
