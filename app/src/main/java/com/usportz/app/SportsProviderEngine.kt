package com.usportz.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** Provider registry. Providers normalize into SportsEvent; Xtream remains the playback source. */
interface SportsEventProvider {
    val id: String
    val priority: Int
    suspend fun load(): List<SportsEvent>
}

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
        val snapshot = synchronized(this@SportsProviderEngine) {
            providers.sortedByDescending { it.priority }.toList()
        }
        snapshot.map { provider: SportsEventProvider ->
            async(Dispatchers.IO) {
                runCatching { provider.load() }.getOrElse { emptyList<SportsEvent>() }
            }
        }.awaitAll().flatten()
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
            override suspend fun load(): List<SportsEvent> = coroutineScope {
                val today = java.time.LocalDate.now()
                val football = async(Dispatchers.IO) { NcaaSportsService.liveAndUpcoming("football", "fbs", today) }
                val men = async(Dispatchers.IO) { NcaaSportsService.liveAndUpcoming("basketball-men", "d1", today) }
                val women = async(Dispatchers.IO) { NcaaSportsService.liveAndUpcoming("basketball-women", "d1", today) }
                NcaaSportsService.toSportsEvents(football.await() + men.await() + women.await())
            }
        })
        SportsProviderEngine.register(NativeLeagueAdapters.Mlb)
        SportsProviderEngine.register(NativeLeagueAdapters.Nhl)
    }
}
