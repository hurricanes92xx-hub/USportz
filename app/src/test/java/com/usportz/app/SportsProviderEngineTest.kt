package com.usportz.app

import org.junit.Assert.assertTrue
import org.junit.Test

class SportsProviderEngineTest {
    @Test fun defaultRegistryContainsCoreProviders() {
        DefaultSportsProviders.install()
        val ids = SportsProviderEngine.registered()
        assertTrue(ids.contains("espn"))
        assertTrue(ids.contains("ncaa"))
        assertTrue(ids.contains("mlb-native"))
        assertTrue(ids.contains("nhl-native"))
    }
}
