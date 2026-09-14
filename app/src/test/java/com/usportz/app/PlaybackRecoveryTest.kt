package com.usportz.app

import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackRecoveryTest {
    @Test fun boundsCandidateCount() {
        val c = SportsChannel("1", "ESPN", "Sports", null, "https://example.invalid/live/1.m3u8")
        assertTrue(PlaybackRecovery.candidates(c).size <= 3)
    }
}
