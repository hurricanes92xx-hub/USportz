package com.usportz.app

/** Canonical state used by every sports feed before UI or matching. */
enum class SportsEventState { PRE, IN, POST, UNKNOWN }

data class NormalizedSportsEvent(
    val event: SportsEvent,
    val state: SportsEventState,
    val normalizedLeague: String,
    val normalizedSport: String,
    val normalizedTeams: List<String>,
    val normalizedBroadcasters: List<String>,
    val canonicalKey: String
)

object SportsEventNormalizer {
    fun normalize(event: SportsEvent): NormalizedSportsEvent {
        val teams = event.competitors.map { TeamAliasEngine.canonical(it) }.filter { it.isNotBlank() }.distinct()
        val broadcasters = BroadcasterNormalizer.tokens(event.broadcast)
        val sport = canonicalToken(event.sport)
        val league = canonicalLeague(event.league)
        val minute = runCatching { java.time.Instant.parse(event.startTime).toEpochMilli() / 60_000L }.getOrNull()
        val key = buildString {
            append(sport).append('|').append(league).append('|')
            append(teams.sorted().joinToString("~"))
            append('|').append(minute ?: event.startTime.take(16))
        }
        return NormalizedSportsEvent(event, state(event), league, sport, teams, broadcasters, key)
    }

    fun state(event: SportsEvent): SportsEventState = when (event.state.lowercase()) {
        "in", "live", "playing", "ongoing" -> SportsEventState.IN
        "post", "final", "finished", "complete", "completed" -> SportsEventState.POST
        "pre", "scheduled", "upcoming" -> SportsEventState.PRE
        else -> {
            val start = runCatching { java.time.Instant.parse(event.startTime).toEpochMilli() }.getOrNull()
            when {
                start == null -> SportsEventState.UNKNOWN
                start <= System.currentTimeMillis() -> SportsEventState.IN
                else -> SportsEventState.PRE
            }
        }
    }

    private fun canonicalLeague(value: String): String {
        val n = canonicalToken(value)
        return when {
            n.contains("college football") || n == "ncaa football" || n == "ncaaf" -> "ncaa-football"
            n.contains("college basketball") || n == "ncaab" -> "ncaa-basketball"
            n.contains("womens basketball") || n == "wnba" -> "wnba"
            n.contains("premier league") -> "premier-league"
            n.contains("champions league") -> "champions-league"
            else -> n
        }
    }

    private fun canonicalToken(value: String): String = value.lowercase()
        .replace('&', ' ').replace(Regex("[^a-z0-9]+"), " ").trim().replace(Regex("\\s+"), " ")
}

/** Provider contract: a failing feed is isolated from the rest of the sports pipeline. */
interface SportsFeedSource {
    val id: String
    suspend fun load(): List<SportsEvent>
}

object SportsFeedMerger {
    fun merge(events: List<SportsEvent>, limit: Int = 2500): List<SportsEvent> = events.asSequence()
        .map(SportsEventNormalizer::normalize)
        .groupBy { it.canonicalKey }
        .values
        .mapNotNull { choose(it) }
        .sortedWith(
            compareByDescending<SportsEvent> { SportsEventNormalizer.state(it) == SportsEventState.IN }
                .thenBy { runCatching { java.time.Instant.parse(it.startTime).toEpochMilli() }.getOrDefault(Long.MAX_VALUE) }
        )
        .take(limit.coerceAtLeast(1))
        .toList()

    private fun choose(group: List<NormalizedSportsEvent>): SportsEvent? = group.maxWithOrNull(
        compareBy<NormalizedSportsEvent> { quality(it.event) }
            .thenBy { it.state == SportsEventState.IN }
    )?.event

    private fun quality(event: SportsEvent): Int =
        event.competitors.count { it.isNotBlank() } * 30 +
        event.competitorLogos.count { it.isNotBlank() } * 20 +
        (if (event.broadcast.isNotBlank()) 15 else 0) +
        (if (event.detail.isNotBlank()) 5 else 0) +
        (if (!event.leagueLogo.isNullOrBlank()) 5 else 0)
}

object TeamAliasEngine {
    private val aliases = mapOf(
        "usc" to "southern california", "usc trojans" to "southern california",
        "uconn" to "connecticut", "ole miss" to "mississippi",
        "miami hurricanes" to "miami", "miami fl" to "miami",
        "miami dolphins" to "miami dolphins", "oklahoma state" to "oklahoma st",
        "ohio state" to "ohio st", "penn state" to "penn st",
        "michigan state" to "michigan st", "florida state" to "florida st",
        "nc state" to "nc state", "north carolina state" to "nc state",
        "utep" to "texas el paso", "utsa" to "texas san antonio"
    )

    fun canonical(value: String): String {
        val base = value.lowercase().replace('&', ' ').replace(Regex("[^a-z0-9]+"), " ")
            .trim().replace(Regex("\\s+"), " ")
        if (base.isBlank()) return ""
        aliases[base]?.let { return it }
        return base.replace(Regex("\\b(university|college|the)\\b"), " ")
            .replace(Regex("\\s+"), " ").trim().replace(" state ", " st ")
    }

    fun matches(query: String, candidate: String): Boolean {
        val a = canonical(query); val b = canonical(candidate)
        if (a.isBlank() || b.isBlank()) return false
        if (a == b || a.contains(b) || b.contains(a)) return true
        val at = a.split(' ').filter { it.length >= 3 }.toSet()
        val bt = b.split(' ').filter { it.length >= 3 }.toSet()
        return at.intersect(bt).size >= minOf(2, at.size)
    }
}

object BroadcasterNormalizer {
    private val aliases = mapOf(
        "espn plus" to "espn+", "espn+" to "espn+", "espn news" to "espnews",
        "espn2" to "espn2", "sec network" to "sec network", "acc network" to "acc network",
        "big ten network" to "btn", "btn" to "btn", "fox sports 1" to "fs1", "fox sports 2" to "fs2",
        "cbs sports network" to "cbs sports", "nbc sports" to "nbc", "tnt sports" to "tnt",
        "sportsnet" to "sportsnet", "tsn" to "tsn", "fanduel sports network" to "fanduel sports network"
    )

    fun canonical(value: String): String {
        val base = value.lowercase().replace('&', ' ').replace(Regex("[^a-z0-9+]+"), " ")
            .trim().replace(Regex("\\s+"), " ")
        return aliases[base] ?: base
    }

    fun tokens(value: String): List<String> = value.split(',', '/', '|', ';')
        .map(::canonical).filter { it.isNotBlank() }.distinct()

    fun matches(expected: String, actual: String): Boolean {
        val e = canonical(expected); val a = canonical(actual)
        return e.isNotBlank() && a.isNotBlank() && (e == a || a.contains(e) || e.contains(a))
    }
}

object EventSourceDeduper {
    fun dedupe(events: List<SportsEvent>): List<SportsEvent> = SportsFeedMerger.merge(events)
}
