package com.usportz.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Public scoreboard integration with short-lived caching for fast TV/mobile startup. */
data class SportsEvent(
    val id: String,
    val sport: String,
    val league: String,
    val name: String,
    val shortName: String,
    val state: String,
    val startTime: String,
    val competitors: List<String>,
    val detail: String
)

object SportsSchedule {
    private data class Feed(val sport: String, val league: String)

    private const val CACHE_TTL_MS = 2 * 60 * 1000L
    @Volatile private var cached: List<SportsEvent> = emptyList()
    @Volatile private var cachedAt = 0L

    private val feeds = listOf(
        Feed("football", "nfl"), Feed("football", "college-football"),
        Feed("basketball", "nba"), Feed("basketball", "wnba"),
        Feed("basketball", "mens-college-basketball"), Feed("baseball", "mlb"),
        Feed("hockey", "nhl"), Feed("soccer", "usa.1"), Feed("soccer", "eng.1"),
        Feed("mma", "ufc")
    )

    suspend fun load(forceRefresh: Boolean = false): List<SportsEvent> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!forceRefresh && cached.isNotEmpty() && now - cachedAt < CACHE_TTL_MS) return@withContext cached

        val fresh = feeds.flatMap { feed -> fetch(feed) }
            .distinctBy { it.id }
            .sortedWith(compareByDescending<SportsEvent> { it.state == "in" }.thenBy { it.startTime })
            .take(200)

        if (fresh.isNotEmpty()) {
            cached = fresh
            cachedAt = now
            fresh
        } else {
            cached
        }
    }

    fun isCacheFresh(): Boolean = cached.isNotEmpty() && System.currentTimeMillis() - cachedAt < CACHE_TTL_MS
    fun lastUpdatedEpochMs(): Long = cachedAt

    fun liveEvents(events: List<SportsEvent>): List<SportsEvent> = events.filter { it.state == "in" }
    fun upcomingEvents(events: List<SportsEvent>): List<SportsEvent> = events.filter { it.state != "in" && it.state != "post" }
    fun forSport(events: List<SportsEvent>, sport: String): List<SportsEvent> =
        if (sport.isBlank() || sport == "All") events else events.filter { SportsCatalog.classify(it.name, it.league) == sport || it.sport.equals(sport, true) }

    private fun fetch(feed: Feed): List<SportsEvent> {
        val url = "https://site.api.espn.com/apis/site/v2/sports/${feed.sport}/${feed.league}/scoreboard"
        return runCatching {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 7000
            connection.requestMethod = "GET"
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()
            val events = JSONObject(body).optJSONArray("events") ?: return emptyList()
            buildList {
                for (i in 0 until events.length()) {
                    val event = events.optJSONObject(i) ?: continue
                    val competition = event.optJSONArray("competitions")?.optJSONObject(0) ?: continue
                    val competitors = competition.optJSONArray("competitors") ?: continue
                    val names = buildList {
                        for (j in 0 until competitors.length()) {
                            val c = competitors.optJSONObject(j) ?: continue
                            val name = c.optJSONObject("team")?.optString("displayName").orEmpty()
                            if (name.isNotBlank()) add(name)
                        }
                    }
                    val status = competition.optJSONObject("status")?.optJSONObject("type")
                    val rawName = event.optString("name")
                    val displayLeague = SportsBranding.label(rawName, feed.league)
                    add(SportsEvent(
                        id = event.optString("id"), sport = feed.sport, league = displayLeague,
                        name = rawName, shortName = event.optString("shortName"),
                        state = status?.optString("state").orEmpty(), startTime = event.optString("date"),
                        competitors = names, detail = status?.optString("detail").orEmpty()
                    ))
                }
            }
        }.getOrDefault(emptyList())
    }

    fun matchChannel(event: SportsEvent, channelName: String, group: String): Int {
        val haystack = "$channelName $group".lowercase()
        val tokens = buildList {
            add(event.league.lowercase())
            add(event.sport.lowercase())
            event.competitors.forEach { add(it.lowercase()) }
        }
        var score = 0
        tokens.filter { it.length >= 4 }.forEach { token ->
            if (haystack.contains(token)) score += if (token == event.league.lowercase()) 4 else 3
        }
        val leagueAliases = mapOf(
            "nfl" to listOf("nfl", "football"),
            "nba" to listOf("nba", "basketball"),
            "wnba" to listOf("wnba", "basketball"),
            "mlb" to listOf("mlb", "baseball"),
            "nhl" to listOf("nhl", "hockey"),
            "ufc" to listOf("ufc", "mma"),
            "premier league" to listOf("epl", "premier league"),
            "mls" to listOf("mls", "soccer"),
            "ncaa football" to listOf("ncaa", "college football", "football"),
            "ncaa basketball" to listOf("ncaa", "college basketball", "basketball")
        )
        leagueAliases[event.league.lowercase()].orEmpty().forEach { if (haystack.contains(it)) score += 2 }
        return score
    }
}
