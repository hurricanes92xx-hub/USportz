package com.usportz.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsCoreNormalizationTest {
    private fun event(
        id: String = "1",
        state: String = "pre",
        teams: List<String> = listOf("Ohio State", "Michigan")
    ) = SportsEvent(
        id = id,
        sport = "football",
        league = "NCAA Football",
        name = teams.joinToString(" at "),
        shortName = teams.joinToString(" • "),
        state = state,
        startTime = "2026-09-14T19:00:00Z",
        competitors = teams,
        competitorLogos = emptyList(),
        leagueLogo = null,
        detail = "",
        broadcast = "FOX Sports 1"
    )

    @Test fun normalizesTeamAliases() {
        assertEquals("ohio st", TeamAliasEngine.canonical("Ohio State"))
        assertEquals("ohio st", TeamAliasEngine.canonical("Ohio St."))
        assertTrue(TeamAliasEngine.matches("Ohio State", "Ohio St"))
    }

    @Test fun normalizesBroadcasters() {
        assertEquals("fs1", BroadcasterNormalizer.canonical("FOX Sports 1"))
        assertTrue(BroadcasterNormalizer.matches("FOX Sports 1", "FS1 HD"))
    }

    @Test fun normalizesLiveState() {
        assertEquals(SportsEventState.IN, SportsEventNormalizer.state(event(state = "live")))
        assertEquals(SportsEventState.POST, SportsEventNormalizer.state(event(state = "final")))
    }

    @Test fun mergesDuplicateSources() {
        val a = event(id = "espn-1", state = "pre")
        val b = event(id = "official-1", state = "in")
        val merged = SportsFeedMerger.merge(listOf(a, b))
        assertEquals(1, merged.size)
        assertEquals("in", merged.first().state)
    }
}
