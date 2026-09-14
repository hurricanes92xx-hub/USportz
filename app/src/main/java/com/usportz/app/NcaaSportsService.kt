package com.usportz.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/**
 * NCAA-specific supplemental data source.
 *
 * It never blocks Xtream catalogue loading and never replaces the general schedule feed.
 * The public service is rate-limited, so requests are deliberately bounded and cached.
 */
object NcaaSportsService {
    private const val BASE = "https://ncaa-api.henrygd.me"
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
        val datePath = date.format(DateTimeFormatter.ofPattern("yyyy/MM/dd"))
        val key = "$pathSport/$division/$datePath"
        val now = System.currentTimeMillis()
        cache[key]?.takeIf { it.expiresAt > now }?.let { return@withContext it.games }
        val result = fetch("$BASE/scoreboard/$pathSport/$division/${date.year}/${date.monthValue.toString().padStart(2, '0')}/${date.dayOfMonth.toString().padStart(2, '0')}/all-conf")
        cache[key] = CacheEntry(now + CACHE_MS, result.take(MAX_GAMES))
        result.take(MAX_GAMES)
    }

    suspend fun schools(): JSONArray? = withContext(Dispatchers.IO) { fetchJson("$BASE/schools-index") }

    private fun fetch(url: String): List<NcaaGame> = runCatching {
        val json = fetchJson(url) ?: return emptyList()
        val games = json.optJSONArray("games") ?: json.optJSONArray("contests") ?: JSONArray()
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
                    id = g.optString("gameID").ifBlank { g.optString("id") }.ifBlank { "ncaa:$i" },
                    sport = g.optString("sport").ifBlank { "college" },
                    division = g.optString("division").ifBlank { "d1" },
                    season = g.optString("season").ifBlank { g.optString("year") },
                    name = g.optString("gameName").ifBlank { g.optString("name") },
                    state = g.optString("status").ifBlank { g.optString("gameState") },
                    startTime = g.optString("startDate").ifBlank { g.optString("startTime") },
                    competitors = names.take(2),
                    scores = scores.take(2),
                    detail = g.optString("currentPeriod").ifBlank { g.optString("statusDetail") },
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
            c.setRequestProperty("User-Agent", "USPortz/2.0 Android")
            if (c.responseCode !in 200..299) null
            else JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        } finally { c.disconnect() }
    }
}
