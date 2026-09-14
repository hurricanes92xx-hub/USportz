package com.usportz.app

/** Small provider registry. Providers normalize into the existing SportsEvent model; video playback remains entirely in the Xtream channel catalogue. */
interface SportsEventProvider { val id: String; val priority: Int; suspend fun load(): List<SportsEvent> }

object SportsProviderEngine {
    private val providers = mutableListOf<SportsEventProvider>()
    @Synchronized fun register(provider: SportsEventProvider) { providers.removeAll { it.id == provider.id }; providers += provider }
    @Synchronized fun registered(): List<String> = providers.sortedByDescending { it.priority }.map { it.id }
    suspend fun loadAll(): List<SportsEvent> = kotlinx.coroutines.coroutineScope { providers.sortedByDescending { it.priority }.map { provider -> kotlinx.coroutines.async(kotlinx.coroutines.Dispatchers.IO) { runCatching { provider.load() }.getOrDefault(emptyList()) } }.map { it.await() }.flatten() }
}

object DefaultSportsProviders {
    fun install() {
        SportsProviderEngine.register(object : SportsEventProvider { override val id = "espn"; override val priority = 100; override suspend fun load() = SportsSchedule.load(forceRefresh = false) })
        SportsProviderEngine.register(object : SportsEventProvider {
            override val id = "ncaa"; override val priority = 95
            override suspend fun load(): List<SportsEvent> {
                val today = java.time.LocalDate.now()
                val games = kotlinx.coroutines.coroutineScope { listOf(kotlinx.coroutines.async { NcaaSportsService.liveAndUpcoming("football", "fbs", today) }, kotlinx.coroutines.async { NcaaSportsService.liveAndUpcoming("basketball-men", "d1", today) }, kotlinx.coroutines.async { NcaaSportsService.liveAndUpcoming("basketball-women", "d1", today) }).flatMap { it.await() } }
                return NcaaSportsService.toSportsEvents(games)
            }
        })
        SportsProviderEngine.register(NativeLeagueAdapters.Mlb)
        SportsProviderEngine.register(NativeLeagueAdapters.Nhl)
    }
}
