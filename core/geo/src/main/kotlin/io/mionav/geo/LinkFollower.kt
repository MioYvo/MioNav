package io.mionav.geo

import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * Follows `surl.amap.com` short links to the URL that carries the coordinates.
 *
 * `HttpURLConnection.getURL()` keeps returning the URL it was opened with even when redirects are
 * followed automatically, so the header has to be read and resolved by hand. Some short links
 * answer 200 with a JavaScript bounce instead of a 30x, which is why the body is inspected too.
 */
class LinkFollower(
    private val fetch: (String) -> Fetch = ::httpFetch,
    private val maxHops: Int = 5,
    private val onHop: (String) -> Unit = {},
) {

    class Fetch(val status: Int, val location: String?, val body: String?)

    sealed interface Result {
        data class Final(val url: String, val body: String?) : Result
        data class Failed(val reason: String) : Result
    }

    fun follow(link: String): Result {
        var current = link
        val seen = LinkedHashSet<String>()
        while (seen.size < maxHops) {
            if (!seen.add(current)) return Result.Failed("redirect loop at $current")
            val got = runCatching { fetch(current) }
                .getOrElse { return Result.Failed("network: ${it.message ?: it.javaClass.simpleName}") }
            onHop("$current -> ${got.status} ${got.location ?: ""}")

            val header = if (got.status in 300..399) got.location?.let { absolute(current, it) } else null
            if (header != null && header != current) {
                current = header
                continue
            }
            // A 200 that still carries an amap URL in the body is a JavaScript bounce.
            val bounced = bodyLink(got.body)
            if (bounced != null && bounced != current) {
                current = bounced
                continue
            }
            return Result.Final(current, got.body)
        }
        return Result.Failed("too many redirects from $link")
    }

    companion object {

        /**
         * Resolves `Location` values that are absolute, scheme-relative, root-relative or bare.
         * Plain `http` is upgraded to `https`: Amap serves both, and Android blocks cleartext.
         */
        fun absolute(base: String, location: String): String {
            val resolved = when {
                location.startsWith("http://", true) || location.startsWith("https://", true) -> location
                location.startsWith("//") -> base.substringBefore("://") + ":" + location
                else -> runCatching { URI(base).resolve(location).toString() }.getOrDefault(location)
            }
            return preferHttps(resolved)
        }

        /** Upgrades real hosts to https, but leaves loopback and bare IPs alone (tests live there). */
        fun preferHttps(url: String): String {
            if (!url.startsWith("http://", true)) return url
            val host = url.substringAfter("://").substringBefore('/').substringBefore(':')
            val numeric = host == "localhost" || host.contains("::") ||
                Regex("^[0-9.]+$").matches(host)
            return if (numeric) url else "https://" + url.substring(7)
        }

        /**
         * The first URL in the body that actually carries a destination. Amap pages are full of
         * static asset URLs (`a.amap.com/pc/static/…`), so "an amap URL" is not enough: it has to
         * parse into coordinates or a poi id.
         */
        fun bodyLink(body: String?): String? {
            if (body.isNullOrEmpty()) return null
            val pattern = Regex("""https?://[^\s"'<>\\]+""")
            return pattern.findAll(body)
                .map { preferHttps(it.value) }
                .firstOrNull { candidate ->
                    when (ShareLinkParser.parse(candidate)) {
                        is ShareTarget.Coordinates, is ShareTarget.Poi -> true
                        else -> false
                    }
                }
        }

        private const val UA =
            "Mozilla/5.0 (Linux; Android 13; PBEM00) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0 Mobile Safari/537.36 Amap/12.0"

        private fun httpFetch(url: String): Fetch {
            val conn = URL(url).openConnection() as HttpURLConnection
            return try {
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", UA)
                conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
                val status = conn.responseCode
                val stream = if (status in 200..299) conn.inputStream else conn.errorStream
                val body = stream?.let { input ->
                    input.use { it.readNBytes(200_000).toString(Charsets.UTF_8) }
                }
                Fetch(status, conn.getHeaderField("Location"), body)
            } finally {
                conn.disconnect()
            }
        }
    }
}
