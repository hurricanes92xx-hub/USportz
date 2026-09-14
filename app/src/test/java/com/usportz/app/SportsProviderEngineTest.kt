package com.usportz.app

import org.junit.Assert.assertTrue
import org.junit.Test

class SportsProviderEngineTest {
    @Test fun defaultRegistryContainsCoreProviders() {
        DefaultSportsProviders.install()
        SportsProviderEngine.register(SportsExpansionProviders.BallDontLie)
        SportsProviderEngine.register(SportsExpansionProviders.PwhlLeagueStat)
        SportsProviderEngine.register(SportsExpansionProviders.SportsDataverse)
        val ids = SportsProviderEngine.registered()
        assertTrue(ids.contains("espn"))
        assertTrue(ids.contains("ncaa"))
        assertTrue(ids.contains("mlb-native"))
        assertTrue(ids.contains("nhl-native"))
        assertTrue(ids.contains("balldontlie"))
        assertTrue(ids.contains("pwhl-leaguestat"))
        assertTrue(ids.contains("sportsdataverse"))
    }

    @Test fun requestedCoverageFamiliesAreRepresented() {
        val families = SportsCoverageCatalog.families.map { it.lowercase() }
        listOf("ncaa football", "ncaa basketball", "nfl", "nba", "mlb", "nhl", "soccer", "ufc", "tennis", "golf", "pwhl", "milb", "f1").forEach {
            assertTrue("missing $it", families.contains(it))
        }
    }
}
