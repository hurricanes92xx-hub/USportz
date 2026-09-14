package com.usportz.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsIntelligenceV2Test {
    @Test fun scoreAndFinalTransitionsAreDetected() {
        SportsStateEngine.clear()
        val a = SportsEventSnapshot("g", "football", "in", 7, 3, "Q1 10:00", 1)
        assertEquals(1, SportsStateEngine.update(a).size)
        val b = a.copy(homeScore = 14, detail = "Q2 02:00", timestampMs = 2)
        val transitions = SportsStateEngine.update(b)
        assertTrue(transitions.any { it.transition == SportsTransition.SCORE_CHANGED })
        assertTrue(transitions.any { it.transition == SportsTransition.PERIOD_CHANGED })
        val c = b.copy(state = "post", timestampMs = 3)
        assertTrue(SportsStateEngine.update(c).any { it.transition == SportsTransition.FINAL })
    }

    @Test fun preloadPolicyOnlyWarmsNearbyItems() {
        val channels = (0 until 8).map { SportsChannel(it.toString(), "CH$it", "Sports", "", "https://example.com/$it.m3u8", "", "", "", "p") }
        val targets = SportsPreloadPolicy.targets(channels, 4, 2)
        assertEquals(2, targets.size)
        assertEquals("CH3", targets[0].channel.name)
        assertEquals("CH5", targets[1].channel.name)
        assertTrue(targets.all { it.preloadMs > 0 })
    }

    @Test fun spoilerGuardNeverAddsMoreThanTenMinutes() {
        val now = 1000L
        assertEquals(now + 600_000L, SportsSpoilerGuard.releaseAt(now, 999_999_999L))
    }
}
