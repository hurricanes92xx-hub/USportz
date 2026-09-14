package com.usportz.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** Small provider registry. Providers normalize into the existing SportsEvent model; video playback remains entirely in the Xtream channel catalogue. */
interface SportsEventProvider { val id: String; val priority: Int; suspend fun load(): List<SportsEvent> }

object SportsProviderEngine {
    private val providers = mutableListOf<SportsEventProvider>()

    @Synchronized
    fun register(provider: SportsEventProvider) {
        providers.removeAll { it.id == provider.id }
        providers += provider
    }

    @Synchronized
    fun registered(): List<String> = providers.sortedByDescending { it.priority }.map { it.id }

    suspend fun loadAll(): List<SportsEvent> = coroutineScope {
        val jobs = providers.sortedByDescending { it.priority }.map { provider ->
            async(Dispatchers.IO) { runCatching { provider.load() }.getOrDefault(emptyList()) }
        }
        jobs.flatMap { it.await() }
    }
}

object DefaultSportsProviders {
    fun install() {
        SportsProviderEngine.register(object : SportsEventProvider {
            override val id = "espn"
            override val priority = 100
            override suspend fun load(): List<SportsEvent> = SportsSchedule.load(forceRefresh = false)
        })
        SportsProviderEngine.register(object : SportsEventProvider {
            override val id = "ncaa"
            override val priority = 95
            override suspend fun load(): List<SportsEvent> {
                val today = java.time.LocalDate.now()
                val games = coroutineScope {
                    val football = async(Dispatchers.IO) { NcaaSportsService.liveAndUpcoming("football", "fbs", today) }
                    val men = async(Dispatchers.IO) { NcaaSportsService.liveAndUpcoming("basketball-men", "d1", today) }
                    val women = async(Dispatchers.IO) { NcaaSportsService.liveAndUpcoming("basketball-women", "d1", today) }
                    football.await() + men.await() + women.await()
                }
                return NcaaSportsService.toSportsEvents(games)
            }
        })
        SportsProviderEngine.register(NativeLeagueAdapters.Mlb)
        SportsProviderEngine.register(NativeLeagueAdapters.Nhl)
    }
}
