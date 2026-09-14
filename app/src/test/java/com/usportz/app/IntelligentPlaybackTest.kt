package com.usportz.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IntelligentPlaybackTest {
    // SportsChannel stores logo before URL; keep the test fixture aligned with the production model.
    private val channel = SportsChannel("1", "ESPN HD", "Sports", "", "https://example.com/live.m3u8", "ESPN", "espn", "ESPN", "p")
    private val event = SportsEvent("1", "football", "NCAA", "Miami vs Utah", "", "in", "2026-09-14T20:00:00Z", listOf("Miami", "Utah"), emptyList(), "", "", "ESPN")

    @Test fun detectsStreamFamilies() {
        assertEquals(StreamKind.HLS, StreamClassifier.kind(channel.url))
        assertTrue(StreamClassifier.family(channel).contains("espn"))
    }

    @Test fun failureClassificationIsDeterministic() {
        assertEquals(PlaybackFailure.TIMEOUT, PlaybackFailureClassifier.classify("connection timed out"))
        assertEquals(PlaybackFailure.HTTP, PlaybackFailureClassifier.classify("bad response", 503))
        assertEquals(PlaybackFailure.DECODER, PlaybackFailureClassifier.classify("decoder init failed"))
    }

    @Test fun recoveryIsBounded() {
        val candidates = PlaybackRecovery.candidates(channel)
        assertTrue(candidates.isNotEmpty())
        assertTrue(candidates.size <= 3)
    }

    @Test fun bestSourceMemoryPrefersSuccessfulSource() {
        BestSourceMemory.put(event.id, channel.url)
        val match = GameSourceMatcher.Match(channel, 100, 80, 95, listOf("team"))
        val ranked = IntelligentPlayback.rank(event, listOf(match))
        assertTrue(ranked.first().remembered)
    }
}
