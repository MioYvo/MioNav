package io.mionav.hud

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HudTextTest {
    @Test
    fun `distance buckets`() {
        assertEquals("0 m", HudText.distance(0))
        assertEquals("9 m", HudText.distance(9))
        assertEquals("320 m", HudText.distance(318))
        assertEquals("980 m", HudText.distance(980))
        assertEquals("1.0 km", HudText.distance(1_000))
        assertEquals("1.2 km", HudText.distance(1230))
        assertEquals("12 km", HudText.distance(12_340))
    }

    @Test
    fun `duration buckets`() {
        assertEquals("0 分", HudText.duration(0))
        assertEquals("58 分", HudText.duration(58))
        assertEquals("1 时 00", HudText.duration(60))
        assertEquals("1 时 05", HudText.duration(65))
        assertEquals("2 时 30", HudText.duration(150))
    }

    @Test
    fun `limit is empty when unknown`() {
        assertEquals("", HudText.limit(0))
        assertEquals("120", HudText.limit(120))
        assertEquals("", HudText.limit(300))
    }

    @Test
    fun `service area needs a flag`() {
        assertNull(HudText.serviceArea(2300, hasServiceArea = false))
        assertNull(HudText.serviceArea(0, hasServiceArea = true))
        assertEquals("服务区 2.3 km", HudText.serviceArea(2300, hasServiceArea = true))
    }

    @Test
    fun `lanes count bits and flag the recommendation`() {
        assertEquals(3, HudText.laneCount(0b0000_0111))
        assertEquals(6, HudText.laneCount(0xFF))
        assertTrue(HudText.laneRecommended(0b0000_0100, 2))
        assertEquals(false, HudText.laneRecommended(0b0000_0100, 1))
    }

    @Test
    fun `remaining joins both parts`() {
        assertEquals("9.4 km · 58 分", HudText.remaining(9_400, 58))
        assertEquals("12 km · 58 分", HudText.remaining(12_400, 58))
    }
}

class IconMapperTest {
    @Test
    fun `known values map and the rest stay unknown`() {
        // Numbers verified against IconType in AMapNavi_11.3.100.
        assertEquals(ManeuverKind.LEFT, IconMapper.map(2))
        assertEquals(ManeuverKind.RIGHT, IconMapper.map(3))
        assertEquals(ManeuverKind.SLIGHT_LEFT, IconMapper.map(4))
        assertEquals(ManeuverKind.UTURN_LEFT, IconMapper.map(8))
        assertEquals(ManeuverKind.STRAIGHT, IconMapper.map(9))
        assertEquals(ManeuverKind.ROUNDABOUT, IconMapper.map(11))
        assertEquals(ManeuverKind.EXIT_ROUNDABOUT, IconMapper.map(12))
        assertEquals(ManeuverKind.SERVICE_AREA, IconMapper.map(13))
        assertEquals(ManeuverKind.TOLLGATE, IconMapper.map(14))
        assertEquals(ManeuverKind.DESTINATION, IconMapper.map(15))
        assertEquals(ManeuverKind.UNKNOWN, IconMapper.map(0))
        assertEquals(ManeuverKind.UNKNOWN, IconMapper.map(200))
    }
}
