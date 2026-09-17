package com.mirrly.tgproxy.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetAddress

class BackgroundCacheGenerationGuardTest {
    @Test
    fun `stale DNS answer and IP race rating do not overwrite B`() {
        val hostname = "mob020-dns.example"
        val aDns = DohResolver.currentResolutionEpoch()
        val aRace = HappyEyeballsEngine.currentRaceEpoch()
        val ipA = InetAddress.getByName("192.0.2.10")
        val ipB = InetAddress.getByName("192.0.2.20")

        DohResolver.invalidateInFlight()
        HappyEyeballsEngine.invalidateInFlight()
        val bDns = DohResolver.currentResolutionEpoch()
        val bRace = HappyEyeballsEngine.currentRaceEpoch()
        val bEntry = DohCacheEntry(hostname, listOf(ipB), System.currentTimeMillis() + 60_000L, "B")
        val aEntry = DohCacheEntry(hostname, listOf(ipA), System.currentTimeMillis() + 60_000L, "A")

        assertTrue(DohResolver.cacheIfCurrent(bDns, bEntry))
        assertFalse(DohResolver.cacheIfCurrent(aDns, aEntry))
        assertEquals(listOf(ipB), DohResolver.getFromCache(hostname)?.addresses)

        val ratingKey = "192.0.2.20"
        assertTrue(HappyEyeballsEngine.recordIpRttIfCurrent(bRace, ratingKey, 80L))
        assertFalse(HappyEyeballsEngine.recordIpRttIfCurrent(aRace, ratingKey, 1L))
        assertEquals(80L, HappyEyeballsEngine.getKnownRatings()[ratingKey])

        DohResolver.clearCache()
        assertFalse(DohResolver.cacheIfCurrent(bDns, bEntry))
        assertEquals(null, DohResolver.getFromCache(hostname))
    }
}
