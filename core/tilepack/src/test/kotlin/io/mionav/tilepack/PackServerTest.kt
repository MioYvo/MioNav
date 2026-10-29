package io.mionav.tilepack

import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URL
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PackServerTest {

    private lateinit var dir: File
    private lateinit var pack: File
    private lateinit var identity: PackIdentity
    private var server: PackServer? = null

    @BeforeTest
    fun setUp() {
        dir = kotlin.io.path.createTempDirectory("mionav-pack").toFile()
        pack = File(dir, "region.mbtiles")
        // The server only moves bytes, so a real SQLite file would prove nothing extra.
        val bytes = Random(7).nextBytes(300_000)
        pack.writeBytes(bytes)
        identity = PackIdentity(
            id = "test-region",
            version = "2026.10.29",
            bytes = pack.length(),
            sha256 = PackVerifier.sha256(pack),
            tiles = 42,
        )
    }

    @AfterTest
    fun tearDown() {
        server?.stop()
        dir.deleteRecursively()
    }

    private fun start(
        preferredPort: Int = 0,
        idleTimeoutMs: Long = 0,
    ): PackServer {
        val started = PackServer(pack, identity, preferredPort = preferredPort, idleTimeoutMs = idleTimeoutMs)
        started.start("127.0.0.1")
        server = started
        return started
    }

    private fun connection(path: String, method: String = "GET", vararg headers: Pair<String, String>): HttpURLConnection {
        val running = requireNotNull(server) { "server not started" }
        val connection = URL("http://127.0.0.1:${running.port}$path").openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 2000
        connection.readTimeout = 5000
        for ((name, value) in headers) connection.setRequestProperty(name, value)
        return connection
    }

    @Test
    fun `head carries length and identity`() {
        start()
        val head = connection(PackServer.PACK_PATH, "HEAD")
        assertEquals(200, head.responseCode)
        assertEquals(pack.length().toString(), head.getHeaderField("Content-Length"))
        assertEquals(identity.id, head.getHeaderField(PackServer.HEADER_PACK))
        assertEquals(identity.version, head.getHeaderField(PackServer.HEADER_VERSION))
        assertEquals(identity.sha256, head.getHeaderField(PackServer.HEADER_SHA256))
        assertEquals("42", head.getHeaderField(PackServer.HEADER_TILES))
        assertEquals("bytes", head.getHeaderField("Accept-Ranges"))
    }

    @Test
    fun `get returns the same bytes`() {
        start()
        val body = connection(PackServer.PACK_PATH).inputStream.use { it.readBytes() }
        assertTrue(body.contentEquals(pack.readBytes()), "served bytes differ from the file")
    }

    @Test
    fun `range returns the suffix as 206`() {
        start()
        val from = 100_000L
        val response = connection(PackServer.PACK_PATH, "GET", "Range" to "bytes=$from-")
        assertEquals(206, response.responseCode)
        assertEquals("bytes $from-${pack.length() - 1}/${pack.length()}", response.getHeaderField("Content-Range"))
        val body = response.inputStream.use { it.readBytes() }
        val expected = pack.readBytes().copyOfRange(from.toInt(), pack.length().toInt())
        assertTrue(body.contentEquals(expected), "range body is not the file suffix")
    }

    @Test
    fun `unknown paths and traversal are 404`() {
        start()
        for (path in listOf("/", "/nope", "/pack.mbtiles.bak", "/../etc/passwd", "/..%2fpack.mbtiles")) {
            val response = connection(path)
            assertEquals(404, response.responseCode, "expected 404 for $path")
            response.disconnect()
        }
    }

    @Test
    fun `taken port falls back to an ephemeral one`() {
        val occupied = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress("127.0.0.1", 0), 1)
        }
        try {
            val started = start(preferredPort = occupied.localPort)
            assertNotEquals(occupied.localPort, started.port, "server kept the taken port")
            assertTrue(started.port > 0)
            assertEquals(200, connection(PackServer.PACK_PATH, "HEAD").responseCode)
        } finally {
            occupied.close()
        }
    }

    @Test
    fun `stop frees the port and start can run again`() {
        val first = start()
        val port = first.port
        first.stop()
        assertEquals(-1, first.port)
        ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress("127.0.0.1", port), 1)
            close()
        }
        first.start("127.0.0.1")
        assertTrue(first.port > 0)
        assertEquals(200, connection(PackServer.PACK_PATH, "HEAD").responseCode)
    }

    @Test
    fun `missing manifest is 404 and a present one is served`() {
        start()
        assertEquals(404, connection(PackServer.MANIFEST_PATH).responseCode)

        val manifest = File(dir, "manifest.json").apply { writeText("""{"id":"test-region"}""") }
        server?.stop()
        val withManifest = PackServer(pack, identity, manifest = manifest, preferredPort = 0)
        withManifest.start("127.0.0.1")
        server = withManifest
        val body = connection(PackServer.MANIFEST_PATH).inputStream.use { it.readBytes() }
        assertContains(String(body), "\"test-region\"")
    }

    @Test
    fun `idle server stops itself`() {
        val started = start(idleTimeoutMs = 250)
        assertTrue(started.port > 0)
        Thread.sleep(800)
        assertEquals(-1, started.port, "idle server is still listening")
    }

    @Test
    fun `range beyond the end is rejected`() {
        start()
        val response = connection(PackServer.PACK_PATH, "GET", "Range" to "bytes=${pack.length() + 10}-")
        assertEquals(416, response.responseCode)
    }
}
