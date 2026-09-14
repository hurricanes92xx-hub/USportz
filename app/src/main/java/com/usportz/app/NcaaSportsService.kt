package com.usportz.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/** NCAA supplemental data source. Never blocks Xtream catalogue loading. */
object NcaaSportsService {
    private const val DEFAULT_BASE = "https://ncaa-api.henrygd.me"
    private const val CONNECT_MS = 1800
    private const val READ_MS = 3000
    private const val CACHE_MS = 45_000L
    private const val MAX_GAMES = 250
    private val cache = ConcurrentHashMap<String, CacheEntry>()

    data class NcaaGame(
        val id: String,
        val sport: String,
        val division: String,
        val season: String,
        val name: String,
        val state: String,
        val startTime: String,
        val competitors: List<String>,
        val scores: List<String>,
        val detail: String = "",
        val conference: String = "",
        val venue: String = ""
    )

    private data class CacheEntry(val expiresAt: Long, val games: List<NcaaGame>)

    private val baseUrl: String
        get() = BuildConfig.NCAA_API_BASE_URL.trim().ifBlank { DEFAULT_BASE }.trimEnd('/')

    private val apiKey: String
        get() = BuildConfig.NCAA_API_KEY.trim()

    suspend fun liveAndUpcoming(
        sport: String,
        division: String,
        date: LocalDate = LocalDate.now()
    ): List<NcaaGame> = withContext(Dispatchers.IO) {
        val pathSport = when (sport.lowercase()) {
            "football", "ncaaf" -> "football"
            "basketball-men", "ncaab" -> "basketball-men"
            "basketball-women", "ncaaw" -> "basketball-women"
            else -> return@withContext emptyList()
        }
        // The upstream API exposes the current scoreboard directly; date-aware routes vary
        // by sport. ESPN remains the broader upcoming feed, while NCAA is our live/current supplement.
        val key = "$pathSport/$division/${date}"
        val now = System.currentTimeMillis()
        cache[key]?.takeIf { it.expiresAt > now }?.let { return@withContext it.games }
        val result = fetch("$baseUrl/scoreboard/$pathSport/$division")
        val bounded = result.take(MAX_GAMES)
        cache[key] = CacheEntry(now + CACHE_MS, bounded)
        bounded
    }

    /** Raw schools-index response. */
    suspend fun schools(): JSONObject? = withContext(Dispatchers.IO) {
        fetchJson("$baseUrl/schools-index")
    }

    /** Convert NCAA records into the app's single normalized SportsEvent model. */
    fun toSportsEvents(games: List<NcaaGame>): List<SportsEvent> = games.mapNotNull { game ->
        val teams = game.competitors.map { it.trim() }.filter { it.isNotBlank() }.take(2)
        val start = normalizeStart(game.startTime) ?: return@mapNotNull null
        val sport = when {
            game.sport.contains("football", true) -> "football"
            game.sport.contains("basketball", true) -> "basketball"
            else -> "college"
        }
        val league = when {
            game.sport.contains("football", true) -> "NCAA Football"
            game.sport.contains("basketball-men", true) -> "NCAA Basketball"
            game.sport.contains("basketball-women", true) -> "NCAA Women's Basketball"
            else -> "NCAA ${game.sport}"
        }
        val title = game.name.ifBlank { teams.joinToString(" vs ") }
        SportsEvent(
            id = "ncaa:${game.id}",
            sport = sport,
            league = league,
            name = title,
            shortName = title,
            state = normalizeState(game.state),
            startTime = start,
            competitors = teams,
            competitorLogos = emptyList(),
            leagueLogo = null,
            detail = listOf(game.detail, game.conference, game.venue).filter { it.isNotBlank() }.joinToString(" • "),
            broadcast = ""
        )
    }

    private fun normalizeStart(value: String): String? = runCatching {
        java.time.Instant.parse(value).toString()
    }.getOrNull() ?: value.toLongOrNull()?.let {
        java.time.Instant.ofEpochMilli(if (it < 100_000_000_000L) it * 1000L else it).toString()
    }

    private fun normalizeState(value: String): String {
        val s = value.lowercase()
        return when {
            s.contains("live") || s == "in" || s.contains("progress") -> "in"
            s.contains("final") || s.contains("complete") || s == "post" -> "post"
            else -> "pre"
        }
    }

    private fun fetch(url: String): List<NcaaGame> = runCatching {
        val json = fetchJson(url) ?: return@runCatching emptyList()
        val games = json.optJSONArray("games") ?: json.optJSONArray("contests") ?: json.optJSONObject("data")?.optJSONArray("contests") ?: JSONArray()
        buildList {
            for (i in 0 until games.length()) {
                val g = games.optJSONObject(i) ?: continue
                val teams = g.optJSONArray("teams") ?: g.optJSONArray("competitors")
                val names = ArrayList<String>()
                val scores = ArrayList<String>()
                for (j in 0 until (teams?.length() ?: 0)) {
                    val t = teams?.optJSONObject(j) ?: continue
                    val name = t.optString("name").ifBlank { t.optString("shortName") }.ifBlank { t.optString("description") }
                    if (name.isNotBlank()) names += name
                    scores += t.optString("score").ifBlank { t.optString("scoreDisplay") }
                }
                add(NcaaGame(
                    id = g.optString("gameID").ifBlank { g.optString("contestId") }.ifBlank { g.optString("id") }.ifBlank { "ncaa:$i" },
                    sport = g.optString("sport").ifBlank { "college" },
                    division = g.optString("division").ifBlank { "d1" },
                    season = g.optString("season").ifBlank { g.optString("year") },
                    name = g.optString("gameName").ifBlank { g.optString("name") },
                    state = g.optString("status").ifBlank { g.optString("gameState") },
                    startTime = g.optString("startDate").ifBlank { g.optString("startTime") }.ifBlank { g.optString("startTimeEpoch") },
                    competitors = names.take(2),
                    scores = scores.take(2),
                    detail = g.optString("currentPeriod").ifBlank { g.optString("contestClock") }.ifBlank { g.optString("statusDetail") },
                    conference = g.optString("conference").ifBlank { g.optString("conferenceName") },
                    venue = g.optString("venue").ifBlank { g.optJSONObject("venue")?.optString("name").orEmpty() }
                ))
            }
        }
    }.getOrDefault(emptyList())

    private fun fetchJson(url: String): JSONObject? {
        val c = URL(url).openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = CONNECT_MS
            c.readTimeout = READ_MS
            c.instanceFollowRedirects = true
            c.requestMethod = "GET"
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("User-Agent", "USPortz/2.1 Android")
            if (apiKey.isNotBlank()) c.setRequestProperty("x-ncaa-key", apiKey)
            if (c.responseCode !in 200..299) null
            else JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        } finally {
            c.disconnect()
        }
    }
}
