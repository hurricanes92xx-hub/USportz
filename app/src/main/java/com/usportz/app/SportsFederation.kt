package com.usportz.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate

/** Wave 2: additive multi-provider federation. Provider failures never erase another provider's data. */
object SportsFederation {
    private const val BASE = "https://api.sportspuff.net"
    private const val TIMEOUT = 4500

    suspend fun loadToday(): List<SportsEvent> = withContext(Dispatchers.IO) {
        coroutineScope {
            listOf("mlb", "nba", "nfl", "nhl", "wnba", "mls", "atp", "wta", "cycling", "ipl", "mlc")
                .map { sport -> async { fetchSport(sport) } }
                .awaitAll().flatten()
        }
    }

    private fun fetchSport(sport: String): List<SportsEvent> = runCatching {
        val body = get("$BASE/v1/schedule/$sport/today?tz=et")
        parse(body, sport)
    }.getOrDefault(emptyList())

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = TIMEOUT
            c.readTimeout = TIMEOUT
            c.requestMethod = "GET"
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("User-Agent", "USPortz/3.0 Android")
            if (c.responseCode !in 200..299) return ""
            c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    private fun parse(body: String, sport: String): List<SportsEvent> = runCatching {
        val root = JSONObject(body)
        val array = root.optJSONArray("games") ?: root.optJSONArray("events") ?: root.optJSONArray("schedule") ?: JSONArray()
        buildList {
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val away = first(o, "away", "away_team", "visitor", "visitor_team", "awayTeam")
                val home = first(o, "home", "home_team", "host", "homeTeam")
                val awayName = teamName(away)
                val homeName = teamName(home)
                val title = first(o, "name", "title", "matchup", "description")
                val competitors = listOf(awayName, homeName).filter { it.isNotBlank() }.ifEmpty { splitTitle(title) }
                if (competitors.isEmpty()) continue
                val start = first(o, "startTime", "start_time", "date", "datetime", "gameDate", "scheduled")
                if (start.isBlank()) continue
                val state = first(o, "state", "status", "game_state", "gameStatus").lowercase().let {
                    when {
                        it.contains("live") || it.contains("progress") -> "in"
                        it.contains("final") || it.contains("complete") || it == "post" -> "post"
                        else -> "pre"
                    }
                }
                val league = first(o, "league", "competition", "tournament").ifBlank { sport.uppercase() }
                add(SportsEvent(
                    id = "sportspuff:$sport:${first(o, "id", "game_id", "gamePk").ifBlank { "$i:$start" }}",
                    sport = sport,
                    league = league,
                    name = title.ifBlank { competitors.joinToString(" at ") },
                    shortName = competitors.joinToString(" • "),
                    state = state,
                    startTime = start,
                    competitors = competitors.take(2),
                    competitorLogos = emptyList(),
                    leagueLogo = null,
                    detail = first(o, "detail", "status_detail", "venue").ifBlank { "Scheduled" },
                    broadcast = first(o, "broadcast", "network", "tv")
                ))
            }
        }
    }.getOrDefault(emptyList())

    private fun first(o: JSONObject, vararg keys: String): String {
        for (key in keys) {
            val v = o.opt(key)
            when (v) {
                is String -> if (v.isNotBlank() && v != "null") return v.trim()
                is JSONObject -> {
                    val n = teamName(v)
                    if (n.isNotBlank()) return n
                }
            }
        }
        return ""
    }

    private fun teamName(o: JSONObject): String = first(o, "displayName", "name", "fullName", "shortName", "teamName", "abbreviation", "abbr")

    private fun splitTitle(value: String): List<String> = value.split(" at ", " vs ", " v ", " - ", " @ ").map(String::trim).filter(String::isNotBlank).take(2)
}

/** Wave 2: quality variants and adaptive source health without retaining stream URLs in memory. */
data class StreamVariant(
    val url: String,
    val kind: String,
    val quality: Int = 0,
    val bitrate: Long = 0L,
    val provider: String = ""
)

data class AdaptiveHealth(val successes: Int = 0, val failures: Int = 0, val lastFailureAt: Long = 0L) {
    val score: Int get() = ((successes * 100) / (successes + failures).coerceAtLeast(1)).coerceIn(0, 100)
}

object StreamVariantRanker {
    fun rank(variants: List<StreamVariant>, health: Map<String, AdaptiveHealth> = emptyMap()): List<StreamVariant> =
        variants.distinctBy { it.url }.sortedByDescending { v ->
            val h = health[v.url]?.score ?: 50
            h * 1000 + v.quality * 10 + when (v.kind.lowercase()) { "hls" -> 3; "dash" -> 2; else -> 1 }
        }
}
