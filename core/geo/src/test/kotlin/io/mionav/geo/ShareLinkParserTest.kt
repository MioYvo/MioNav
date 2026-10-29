package io.mionav.geo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShareLinkParserTest {

    @Test
    fun `marker link with position`() {
        val t = ShareLinkParser.parse("https://uri.amap.com/marker?position=120.1500,30.2800&name=%E8%A5%BF%E6%B9%96")
        assertEquals(ShareTarget.Coordinates(30.28, 120.15, "西湖"), t)
    }

    @Test
    fun `route link with dlat dlon and share text`() {
        val text = "我在高德地图给你发了一个位置：https://uri.amap.com/route/plan/?dlat=30.28&dlon=120.15&dname=%E8%A5%BF%E6%B9%96&dev=0 快来看看"
        val t = ShareLinkParser.parse(text)
        assertEquals(30.28, (t as ShareTarget.Coordinates).lat)
        assertEquals(120.15, t.lon)
    }

    @Test
    fun `poi link without coordinates`() {
        val t = ShareLinkParser.parse("https://uri.amap.com/poi?id=B0FFG12345&name=%E8%A5%BF%E6%B9%96")
        assertEquals(ShareTarget.Poi("B0FFG12345", "西湖"), t)
    }

    @Test
    fun `short link is flagged`() {
        assertEquals(ShareTarget.ShortLink("https://surl.amap.com/abc123"), ShareLinkParser.parse("https://surl.amap.com/abc123"))
    }

    @Test
    fun `rejects garbage and swapped coordinates`() {
        assertNull(ShareLinkParser.parse("no link here"))
        assertNull(ShareLinkParser.parse("https://uri.amap.com/marker?position=abc,def"))
        // lon/lat swapped: 120 as latitude is invalid for China.
        assertNull(ShareLinkParser.parse("https://uri.amap.com/marker?position=30.28,120.15"))
        assertTrue(ShareLinkParser.parse("https://uri.amap.com/marker?position=120.15,30.28") != null)
    }

    @Test
    fun `double encoded name from a redirect wrapper decodes once more`() {
        // Shape produced by surl.amap.com -> m.amap.com/callAPP: names arrive as %2525E4%2525B8…
        val wrapped = "https://m.amap.com/callAPP?android=androidamap%3Fdlat%3D34.2415%26dlon" +
            "%3D108.8799%26dname%3D%2525E4%2525B8%2525AD%2525E5%2525BB%2525BA"

        val target = ShareLinkParser.parse(wrapped)

        assertIs<ShareTarget.Coordinates>(target)
        assertEquals("中建", target.name)
        assertEquals(34.2415, target.lat, 0.0001)
        assertEquals(108.8799, target.lon, 0.0001)
    }
}
