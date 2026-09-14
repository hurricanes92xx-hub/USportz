package com.usportz.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

/**
 * Expansion layer for secondary sports data sources.
 * Providers are optional and fail closed: Xtream remains the playback source.
 * All providers normalize into SportsEvent so the existing matcher/EPG/health pipeline is reused.
 */
object SportsExpansionProviders {
    object BallDontLie : SportsEventProvider {
        override val id = "balldontlie"
        override val priority = 72

        override suspend fun load(): List<SportsEvent> = withContext(Dispatchers.IO) {
            val key = BuildConfig.BALLDONTLIE_API_KEY
            if (key.isBlank()) return@withContext emptyList()
            val today = LocalDate.now()
            val end = today.plusDays(7)
            val url = "https://api.balldontlie.io/v1/games?start_date=$today&end_date=$end&per_page=100"
            runCatching { parseGames(get(url, mapOf("Authorization" to key))) }.getOrDefault(emptyList())
        }

        private fun parseGames(body: String): List<SportsEvent> {
            val data = JSONObject(body).optJSONArray("data") ?: return emptyList()
            val out = ArrayList<SportsEvent>()
            for (i in 0 until data.length()) {
                val g = data.optJSONObject(i) ?: continue
                val home = g.optJSONObject("home_team")?.optString("full_name").orEmpty()
                val away = g.optJSONObject("visitor_team")?.optString("full_name").orEmpty()
                if (home.isBlank() || away.isBlank()) continue
                val status = g.optString("status").lowercase()
                val state = when {
                    status.contains("final") -> "post"
                    status.contains("in progress") || status.contains("live") -> "in"
                    else -> "pre"
                }
                val id = g.optString("id").ifBlank { i.toString() }
                val date = g.optString("datetime").ifBlank { g.optString("date") }
                val detail = listOf(g.opt("time"), g.opt("period"), g.opt("home_team_score"), g.opt("visitor_team_score"))
                    .filter { it != null && it.toString() != "null" }.joinToString(" • ")
                out += SportsEvent(
                    "balldontlie:$id", "basketball", "NBA", "$away at $home", "$away • $home",
                    state, date, listOf(away, home), emptyList(), null, detail
                )
            }
            return out
        }
    }

    /** Optional PWHL/LeagueStat-compatible JSON adapter. Configure PWHL_LEAGUESTAT_URL when available. */
    object PwhlLeagueStat : SportsEventProvider {
        override val id = "pwhl-leaguestat"
        override val priority = 68

        override suspend fun load(): List<SportsEvent> = withContext(Dispatchers.IO) {
            val endpoint = BuildConfig.PWHL_LEAGUESTAT_URL
            if (endpoint.isBlank()) return@withContext emptyList()
            runCatching { parse(get(endpoint, emptyMap())) }.getOrDefault(emptyList())
        }

        private fun parse(body: String): List<SportsEvent> {
            val root = JSONObject(body)
            val games = root.optJSONArray("games") ?: root.optJSONArray("data") ?: return emptyList()
            val out = ArrayList<SportsEvent>()
            for (i in 0 until games.length()) {
                val g = games.optJSONObject(i) ?: continue
                val home = first(g, "homeTeam", "home_team", "home").trim()
                val away = first(g, "awayTeam", "away_team", "visitor").trim()
                if (home.isBlank() || away.isBlank()) continue
                val stateRaw = first(g, "status", "gameStatus", "state").lowercase()
                val state = when {
                    stateRaw.contains("live") || stateRaw.contains("progress") -> "in"
                    stateRaw.contains("final") || stateRaw.contains("complete") -> "post"
                    else -> "pre"
                }
                out += SportsEvent(
                    "pwhl:${first(g, "id", "gameId", "game_id").ifBlank { i.toString() }}",
                    "hockey", "PWHL", "$away at $home", "$away • $home", state,
                    first(g, "startTime", "start_time", "date", "gameDate"), listOf(away, home), emptyList(), null,
                    first(g, "detail", "statusDetail", "period")
                )
            }
            return out
        }

        private fun first(o: JSONObject, vararg keys: String): String {
            for (key in keys) {
                val direct = o.opt(key)
                if (direct is JSONObject) {
                    val value = direct.optString("name").ifBlank { direct.optString("fullName") }
                    if (value.isNotBlank()) return value
                } else if (direct != null && direct.toString().isNotBlank() && direct.toString() != "null") return direct.toString()
            }
            return ""
        }
    }

    /** Generic SportsDataverse-compatible adapter for a self-hosted JSON snapshot. */
    object SportsDataverse : SportsEventProvider {
        override val id = "sportsdataverse"
        override val priority = 66

        override suspend fun load(): List<SportsEvent> = withContext(Dispatchers.IO) {
            val endpoint = BuildConfig.SPORTSDATAVERSE_URL
            if (endpoint.isBlank()) return@withContext emptyList()
            runCatching { parse(get(endpoint, emptyMap())) }.getOrDefault(emptyList())
        }

        private fun parse(body: String): List<SportsEvent> {
            val root = JSONObject(body)
            val events = root.optJSONArray("events") ?: root.optJSONArray("games") ?: root.optJSONArray("data") ?: return emptyList()
            val out = ArrayList<SportsEvent>()
            for (i in 0 until events.length()) {
                val e = events.optJSONObject(i) ?: continue
                val home = first(e, "homeTeam", "home_team", "home")
                val away = first(e, "awayTeam", "away_team", "away", "visitor")
                if (home.isBlank() || away.isBlank()) continue
                val sport = first(e, "sport").ifBlank { "sports" }.lowercase()
                val league = first(e, "league", "leagueName", "league_name").ifBlank { "Sports" }
                val rawState = first(e, "state", "status", "gameStatus").lowercase()
                val state = when {
                    rawState.contains("live") || rawState == "in" -> "in"
                    rawState.contains("final") || rawState.contains("post") || rawState.contains("complete") -> "post"
                    else -> "pre"
                }
                out += SportsEvent(
                    "sportsdataverse:${first(e, "id", "eventId", "event_id").ifBlank { i.toString() }}",
                    sport, league, "$away at $home", "$away • $home", state,
                    first(e, "startTime", "start_time", "date", "gameDate"), listOf(away, home), emptyList(), null,
                    first(e, "detail", "statusDetail", "clock", "period"), first(e, "broadcast", "network")
                )
            }
            return out
        }

        private fun first(o: JSONObject, vararg keys: String): String {
            for (key in keys) {
                val v = o.opt(key)
                if (v is JSONObject) {
                    val value = v.optString("displayName").ifBlank { v.optString("name") }
                    if (value.isNotBlank()) return value
                } else if (v != null && v.toString().isNotBlank() && v.toString() != "null") return v.toString()
            }
            return ""
        }
    }

    private fun get(url: String, headers: Map<String, String>): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 2500
            connection.readTimeout = 4500
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "USPortz/2.2 Android")
            headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
            if (connection.responseCode !in 200..299) ""
            else connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}

/** Static capability map keeps the UI and diagnostics aware of every requested coverage family. */
object SportsCoverageCatalog {
    val families = listOf(
        "NCAA football", "NCAA basketball", "NFL", "NBA", "MLB", "NHL", "Soccer",
        "UFC", "Tennis", "Golf", "PWHL", "MiLB", "F1", "NASCAR", "CFL", "UFL", "Boxing"
    )
}

/** Lightweight BrightSports-style followed-team state; persisted in app-private preferences. */
class FollowedTeams(private val prefs: android.content.SharedPreferences) {
    private val key = "followed_sports_teams"
    fun all(): Set<String> = prefs.getStringSet(key, emptySet()).orEmpty()
    fun isFollowing(team: String): Boolean = team.trim().lowercase() in all()
    fun toggle(team: String): Boolean {
        val normalized = team.trim().lowercase()
        if (normalized.isBlank()) return false
        val next = all().toMutableSet()
        val following = next.add(normalized)
        prefs.edit().putStringSet(key, next).apply()
        return following
    }
}
