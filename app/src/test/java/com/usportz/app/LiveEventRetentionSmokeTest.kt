package com.usportz.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class LiveEventRetentionSmokeTest {
    @Test fun liveEventsSurviveMidnight() {
        val now = Instant.parse("2026-09-14T05:00:00Z").toEpochMilli()
        assertTrue(LiveEventRetention.keep("2026-09-13T23:30:00Z", "in", now))
    }
}
