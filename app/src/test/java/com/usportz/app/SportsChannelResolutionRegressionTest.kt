package com.usportz.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SportsChannelResolutionRegressionTest {
    @Before
    fun clearResolverCache() {
        // Tests intentionally supply different channel snapshots for the same
        // event fingerprint; isolate the process-wide production cache.
        SportsResolutionCache.clear()
    }

    private fun channel(name: String, group: String = "Sports"): SportsChannel = SportsChannel(
        id = name,
        name = name,
        group = group,
        logo = null,
        url = "https://example.test/$name.m3u8",
        tvgName = name,
        tvgId = name,
        category = group,
        provider = "Xtream"
    )

    @Test
    fun sportsCategoryIsEnoughToClassifyGenericSportsChannel() {
        assertTrue(SportsNetworkCatalog.isSportsChannel(channel("Arena 1", "US Sports")))
        assertTrue(SportsNetworkCatalog.isSportsChannel(channel("Arena 2", "Sports HD")))
    }

    @Test
    fun tennisNetworkMatchesWhenScheduleOmitsBroadcaster() {
        val event = SportsEvent(
            id = "tennis-1",
            sport = "tennis",
            league = "WTA",
            name = "Lucia Cortez Llorca vs Alicia Herrero Linana",
            shortName = "Cortez Llorca vs Herrero Linana",
            state = "in",
            startTime = "2026-09-15T12:00:00Z",
            competitors = listOf("Lucia Cortez Llorca", "Alicia Herrero Linana"),
            competitorLogos = emptyList(),
            leagueLogo = null,
            detail = "3rd Set",
            broadcast = ""
        )
        val matches = SportsResolver.resolve(event, listOf(channel("Tennis Channel HD")))
        assertTrue(matches.isNotEmpty())
        assertEquals("Tennis Channel HD", matches.first().channel.name)
    }

    @Test
    fun mlbNetworkMatchesBaseballEvent() {
        val event = SportsEvent(
            id = "mlb-1",
            sport = "baseball",
            league = "MLB",
            name = "Philadelphia Phillies vs Washington Nationals",
            shortName = "Phillies vs Nationals",
            state = "pre",
            startTime = "2026-09-15T23:00:00Z",
            competitors = listOf("Philadelphia Phillies", "Washington Nationals"),
            competitorLogos = emptyList(),
            leagueLogo = null,
            detail = "",
            broadcast = ""
        )
        val matches = SportsResolver.resolve(event, listOf(channel("MLB Network HD")))
        assertTrue(matches.isNotEmpty())
        assertEquals("MLB Network HD", matches.first().channel.name)
    }

    @Test
    fun unrelatedChannelDoesNotMatchTennisEvent() {
        val event = SportsEvent(
            id = "tennis-2",
            sport = "tennis",
            league = "WTA",
            name = "Lucia Cortez Llorca vs Alicia Herrero Linana",
            shortName = "Cortez Llorca vs Herrero Linana",
            state = "in",
            startTime = "2026-09-15T12:00:00Z",
            competitors = listOf("Lucia Cortez Llorca", "Alicia Herrero Linana"),
            competitorLogos = emptyList(),
            leagueLogo = null,
            detail = "3rd Set",
            broadcast = ""
        )
        val matches = SportsResolver.resolve(event, listOf(channel("ESPN News")))
        assertTrue(matches.isEmpty())
    }
}
