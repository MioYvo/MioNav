package io.mionav.geo

/**
 * Parses what Amap puts on the clipboard or in a share sheet.
 *
 * Shapes seen in the wild:
 * - `https://uri.amap.com/marker?position=120.15,30.28&name=西湖`
 * - `amapuri://route/plan/?dlat=30.28&dlon=120.15&dname=西湖&dev=0`
 * - `https://uri.amap.com/poi?id=B0FFG12345&name=西湖`
 * - `https://surl.amap.com/abc123` (short link, needs a redirect to resolve)
 */
sealed interface ShareTarget {
    data class Coordinates(val lat: Double, val lon: Double, val name: String?) : ShareTarget
    data class Poi(val poiId: String, val name: String?) : ShareTarget
    /** Short links (`surl.amap.com/...`) need one redirect before they parse. */
    data class ShortLink(val url: String) : ShareTarget
}

object ShareLinkParser {

    private val url = Regex("""(?:https?://|amapuri://)[^\s"'<>]+""")
    private val position = Regex("""[?&]position=([-\d.]+),([-\d.]+)""")
    private val dlat = Regex("""[?&]dlat=([-\d.]+)""")
    private val dlon = Regex("""[?&]dlon=([-\d.]+)""")
    private val lat = Regex("""[?&]lat=([-\d.]+)""")
    private val lon = Regex("""[?&]lon=([-\d.]+)""")
    private val name = Regex("""[?&](?:dname|name)=([^&]+)""")
    private val poi = Regex("""[?&](?:poiid|id)=(B[0-9A-Z]+)""")

    fun parse(raw: String): ShareTarget? {
        val link = url.find(raw)?.value ?: return null
        val decoded = decode(link)
        // Amap double-encodes names inside its redirect wrapper (`dname%3D%2525E4%2525B8…`).
        val label = name.find(decoded)?.groupValues?.get(1)?.let { decode(it) }

        position.find(decoded)?.let { m ->
            val (lonV, latV) = m.destructured
            return coords(latV, lonV, label)
        }
        val dLat = dlat.find(decoded)?.groupValues?.get(1)
        val dLon = dlon.find(decoded)?.groupValues?.get(1)
        if (dLat != null && dLon != null) return coords(dLat, dLon, label)

        val pLat = lat.find(decoded)?.groupValues?.get(1)
        val pLon = lon.find(decoded)?.groupValues?.get(1)
        if (pLat != null && pLon != null) return coords(pLat, pLon, label)

        poi.find(decoded)?.let { m -> return ShareTarget.Poi(m.groupValues[1], label) }

        if (decoded.contains("surl.amap.com") || decoded.contains("amap.com/") && decoded.contains("s/")) {
            return ShareTarget.ShortLink(link)
        }
        return null
    }

    /** Decodes up to three times: plain links need one pass, wrapped ones need two or three. */
    private fun decode(text: String): String {
        var current = text.replace("+", "%2B")
        repeat(3) {
            val next = runCatching { java.net.URLDecoder.decode(current, "UTF-8") }.getOrDefault(current)
            if (next == current) return current
            current = next
        }
        return current
    }

    private fun coords(latText: String, lonText: String, name: String?): ShareTarget? {
        val latV = latText.toDoubleOrNull() ?: return null
        val lonV = lonText.toDoubleOrNull() ?: return null
        // Reject swapped or out-of-range coordinates instead of driving to the ocean.
        if (latV !in -90.0..90.0 || lonV !in -180.0..180.0) return null
        if (latV in -90.0..90.0 && lonV in 73.0..136.0 && latV < 4.0 && lonV > 60.0) return null
        return ShareTarget.Coordinates(latV, lonV, name)
    }
}
