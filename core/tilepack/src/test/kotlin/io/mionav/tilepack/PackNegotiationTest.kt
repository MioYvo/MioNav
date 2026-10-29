package io.mionav.tilepack

import kotlin.test.Test
import kotlin.test.assertEquals

class PackNegotiationTest {

    private val phone = PackIdentity("zhejiang", "2026.10.28", 92_431_123L, "abc123", tiles = 184_320)

    @Test
    fun `same pack means stay closed`() {
        val watch = phone.copy(tiles = 0)
        assertEquals(PackDecision.UpToDate, PackNegotiation.decide(phone, watch))
    }

    @Test
    fun `older version means serve`() {
        val watch = phone.copy(version = "2026.10.01")
        assertEquals(PackDecision.Serve, PackNegotiation.decide(phone, watch))
    }

    @Test
    fun `watch without a pack means serve`() {
        assertEquals(PackDecision.Serve, PackNegotiation.decide(phone, null))
    }

    @Test
    fun `phone without a pack means nothing to serve`() {
        assertEquals(PackDecision.NoLocalPack, PackNegotiation.decide(null, phone))
    }
}
