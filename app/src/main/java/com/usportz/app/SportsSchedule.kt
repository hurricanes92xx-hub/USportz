package com.usportz.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Public ESPN scoreboard integration used for the TV sports rail. */
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

    private val feeds = listOf(
        Feed("football", "nfl"),
        Feed("football", "college-football"),
        Feed("basketball", "nba"),
        Feed("basketball", "wnba"),
        Feed("basketball", "mens-college-basketball"),
        Feed("baseball", "mlb"),
        Feed("hockey", "nhl"),
        Feed("soccer", "usa.1"),
        Feed("soccer", "eng.1"),
        Feed("mma", "ufc")
    )

    suspend fun load(): List<SportsEvent> = withContext(Dispatchers.IO) {
        feeds.flatMap { feed -> fetch(feed) }
            .sortedBy { it.startTime }
            .distinctBy { it.id }
            .take(200)
    }

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
                            val team = c.optJSONObject("team")
                            val name = team?.optString("displayName").orEmpty()
                            if (name.isNotBlank()) add(name)
                        }
                    }
                    val status = competition.optJSONObject("status")?.optJSONObject("type")
                    val state = status?.optString("state").orEmpty()
                    val detail = status?.optString("detail").orEmpty()
                    add(SportsEvent(
                        id = event.optString("id"),
                        sport = feed.sport,
                        league = feed.league,
                        name = event.optString("name"),
                        shortName = event.optString("shortName"),
                        state = state,
                        startTime = event.optString("date"),
                        competitors = names,
                        detail = detail
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
            "mlb" to listOf("mlb", "baseball"),
            "nhl" to listOf("nhl", "hockey"),
            "ufc" to listOf("ufc", "mma"),
            "epl" to listOf("epl", "premier league"),
            "usa.1" to listOf("mls", "soccer")
        )
        leagueAliases[event.league].orEmpty().forEach { if (haystack.contains(it)) score += 2 }
        return score
    }
}
