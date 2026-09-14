package com.usportz.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** Wave 3: sport-neutral game center, delta state and My Teams primitives. */
data class GameCenterSnapshot(
    val event: SportsEvent,
    val scores: List<Int> = emptyList(),
    val period: String = "",
    val clock: String = "",
    val statusDetail: String = event.detail,
    val venue: String = "",
    val broadcasters: List<String> = event.broadcast.split(",").map(String::trim).filter(String::isNotBlank),
    val updatedAt: Long = System.currentTimeMillis()
)

data class SportsDelta(val eventId: String, val changed: Boolean, val snapshot: GameCenterSnapshot?)

object SportsLiveDeltaEngine {
    private val snapshots = ConcurrentHashMap<String, GameCenterSnapshot>()

    fun apply(next: List<GameCenterSnapshot>): List<SportsDelta> = next.map { value ->
        val old = snapshots.put(value.event.id, value)
        SportsDelta(value.event.id, old != value, value)
    }

    fun get(eventId: String): GameCenterSnapshot? = snapshots[eventId]
}

data class TeamHub(val team: String, val sport: String, val upcoming: List<SportsEvent>, val live: List<SportsEvent>, val channels: List<SportsChannel>)

object TeamHubEngine {
    fun build(team: String, events: List<SportsEvent>, channels: List<SportsChannel>): TeamHub {
        val needle = team.lowercase()
        val matched = events.filter { it.competitors.any { c -> c.lowercase().contains(needle) } }
        return TeamHub(team, matched.firstOrNull()?.sport.orEmpty(), matched.filter { it.state == "in" }, matched.filter { it.state != "in" }.sortedBy { it.startTime }, channels.filter { c -> c.name.lowercase().contains(needle) || c.tvgName.lowercase().contains(needle) })
    }
}

/** Wave 4: multiview/timeshift state contracts. The player remains Media3-backed. */
data class MultiViewSlot(val slot: Int, val event: SportsEvent?, val channel: SportsChannel?, val muted: Boolean = slot != 0)
data class TimeShiftWindow(val enabled: Boolean, val durationMs: Long = 0L, val positionMs: Long = 0L, val seekable: Boolean = false)
data class MiniPlayerState(val visible: Boolean = false, val event: SportsEvent? = null, val channel: SportsChannel? = null)

object SportsPlaybackExperience {
    const val MAX_MULTIVIEW = 4
    const val MAX_TIMESHIFT_MS = 30 * 60 * 1000L

    fun clampSlots(slots: List<MultiViewSlot>): List<MultiViewSlot> = slots.take(MAX_MULTIVIEW).mapIndexed { i, slot -> slot.copy(slot = i) }
    fun normalizeTimeshift(window: TimeShiftWindow): TimeShiftWindow = window.copy(durationMs = window.durationMs.coerceIn(0L, MAX_TIMESHIFT_MS), positionMs = window.positionMs.coerceIn(0L, window.durationMs.coerceAtMost(MAX_TIMESHIFT_MS)))
}

/** Wave 5: adapters expose a common deep-detail surface without forcing every league into one schema. */
interface DeepLeagueAdapter {
    val league: String
    suspend fun snapshot(event: SportsEvent): GameCenterSnapshot
}

object DeepLeagueAdapters {
    val MLB = adapter("MLB") { event -> SportsGameDetailService.load(event) }
    val NHL = adapter("NHL") { event -> NativeLeagueAdapters.Nhl.snapshot(event) }
    val NBA = adapter("NBA") { event -> SportsGameDetailService.load(event) }
    val Soccer = adapter("SOCCER") { event -> SportsGameDetailService.load(event) }
    val Tennis = adapter("TENNIS") { event -> SportsGameDetailService.load(event) }
    val F1 = adapter("F1") { event -> SportsGameDetailService.load(event) }

    private fun adapter(name: String, loader: suspend (SportsEvent) -> GameCenterSnapshot): DeepLeagueAdapter = object : DeepLeagueAdapter {
        override val league: String = name
        override suspend fun snapshot(event: SportsEvent): GameCenterSnapshot = runCatching { loader(event) }.getOrElse { GameCenterSnapshot(event) }
    }
}
