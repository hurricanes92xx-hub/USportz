package com.usportz.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class LiveEventRetentionTest {
    private val now = Instant.parse("2026-09-14T05:00:00Z").toEpochMilli()

    @Test fun retainsYesterdayLiveEvent() {
        assertTrue(LiveEventRetention.keep("2026-09-14T00:30:00Z", "in", now))
    }

    @Test fun retainsYesterdayRecentlyStartedEvent() {
        assertTrue(LiveEventRetention.keep("2026-09-13T23:30:00Z", "pre", now))
    }

    @Test fun dropsOldFinishedEvent() {
        assertFalse(LiveEventRetention.keep("2026-09-13T00:00:00Z", "post", now))
    }
}
