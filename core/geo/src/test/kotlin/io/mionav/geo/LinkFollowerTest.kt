package io.mionav.geo

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LinkFollowerTest {

    private lateinit var server: HttpServer
    private var port = 0

    @BeforeTest
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        port = server.address.port
    }

    @AfterTest
    fun tearDown() = server.stop(0)

    private fun route(path: String, status: Int, body: String = "", location: String? = null) {
        server.createContext(path) { exchange ->
            location?.let { exchange.responseHeaders.add("Location", it) }
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1L else bytes.size.toLong())
            if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
    }

    private fun start(): String {
        server.start()
        return "http://127.0.0.1:$port"
    }

    @Test
    fun followsAnAbsoluteHeaderRedirect() {
        val base = "http://127.0.0.1:$port"
        route("/s/abs", 302, location = "$base/marker?position=120.15,30.28&name=x")
        route("/marker", 200, body = "<html>ok</html>")
        start()

        val result = LinkFollower().follow("$base/s/abs")

        val final = assertIs<LinkFollower.Result.Final>(result)
        assertTrue(final.url.contains("position=120.15,30.28"), "url was ${final.url}")
        assertIs<ShareTarget.Coordinates>(ShareLinkParser.parse(final.url))
    }

    @Test
    fun followsARelativeRedirect() {
        route("/s/rel", 302, location = "/marker?position=120.15,30.28")
        route("/marker", 200, body = "<html>ok</html>")
        val base = start()

        val final = assertIs<LinkFollower.Result.Final>(LinkFollower().follow("$base/s/rel"))
        assertTrue(final.url.startsWith("$base/marker"), "url was ${final.url}")
        assertIs<ShareTarget.Coordinates>(ShareLinkParser.parse(final.url))
    }

    @Test
    fun followsAJavaScriptBounce() {
        // Some short links answer 200 and jump with script instead of a 30x.
        val base = "http://127.0.0.1:$port"
        route(
            "/s/js",
            200,
            body = """<script>location.href="$base/marker?position=120.15,30.28"</script>""",
        )
        route("/marker", 200, body = "<html>ok</html>")
        start()

        val final = assertIs<LinkFollower.Result.Final>(LinkFollower().follow("$base/s/js"))
        assertTrue(final.url.contains("position=120.15,30.28"), "url was ${final.url}")
    }

    @Test
    fun reportsALoopInsteadOfSpinning() {
        route("/a", 302, location = "http://127.0.0.1:$port/b")
        route("/b", 302, location = "http://127.0.0.1:$port/a")
        val base = start()

        val result = LinkFollower().follow("$base/a")

        val failure = assertIs<LinkFollower.Result.Failed>(result)
        assertTrue(failure.reason.contains("loop"), "reason was ${failure.reason}")
    }

    @Test
    fun givesUpAfterTooManyHops() {
        (1..6).forEach { route("/hop$it", 302, location = "http://127.0.0.1:$port/hop${it + 1}") }
        route("/hop7", 200, body = "end")
        val base = start()

        val failure = assertIs<LinkFollower.Result.Failed>(LinkFollower(maxHops = 2).follow("$base/hop1"))
        assertTrue(failure.reason.contains("too many"), "reason was ${failure.reason}")
    }

    @Test
    fun resolvesLocationForms() {
        assertEquals(
            "https://uri.amap.com/marker?position=1,2",
            LinkFollower.absolute("https://surl.amap.com/abc", "https://uri.amap.com/marker?position=1,2"),
        )
        assertEquals(
            "https://suri.amap.com/marker",
            LinkFollower.absolute("https://surl.amap.com/abc", "//suri.amap.com/marker"),
        )
        assertEquals(
            "https://surl.amap.com/marker",
            LinkFollower.absolute("https://surl.amap.com/abc", "/marker"),
        )
    }
}
