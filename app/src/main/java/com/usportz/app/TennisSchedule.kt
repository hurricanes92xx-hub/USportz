package com.usportz.app

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

/** Handles ESPN's tennis scoreboard shape, where matches live under events[].groupings[].competitions[]. */
object TennisSchedule {
    private const val TIMEOUT_MS = 5000L

    suspend fun load(): List<SportsEvent> = withContext(Dispatchers.IO) {
        coroutineScope {
            listOf("atp", "wta").map { league ->
                async(Dispatchers.IO) {
                    withTimeoutOrNull(TIMEOUT_MS) { fetch(league) }.orEmpty()
                }
            }.awaitAll().flatten()
        }
    }

    private fun fetch(league: String): List<SportsEvent> = runCatching {
        val url = "https://site.api.espn.com/apis/site/v2/sports/tennis/$league/scoreboard"
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 2500
            connection.readTimeout = 4500
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = true
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json,text/plain,*/*")
            connection.setRequestProperty("Cache-Control", "no-cache")
            connection.setRequestProperty("User-Agent", "USPortz/1.9 Android")
            if (connection.responseCode !in 200..299) return emptyList()
            parse(connection.inputStream.bufferedReader().use { it.readText() }, league)
        } finally {
            connection.disconnect()
        }
    }.getOrDefault(emptyList())

    private fun parse(body: String, league: String): List<SportsEvent> = runCatching {
        val root = JSONObject(body)
        val events = root.optJSONArray("events") ?: return emptyList()
        val leagueLogo = root.optJSONArray("leagues")?.optJSONObject(0)
            ?.optJSONArray("logos")?.optJSONObject(0)?.optString("href").orEmpty()
        val out = ArrayList<SportsEvent>()

        for (i in 0 until events.length()) {
            val event = events.optJSONObject(i) ?: continue
            val groupings = event.optJSONArray("groupings") ?: continue
            for (g in 0 until groupings.length()) {
                val grouping = groupings.optJSONObject(g) ?: continue
                val group = grouping.optJSONObject("grouping")
                val groupName = group?.optString("displayName").orEmpty().ifBlank { "${league.uppercase()} Tennis" }
                val competitions = grouping.optJSONArray("competitions") ?: continue
                for (j in 0 until competitions.length()) {
                    val competition = competitions.optJSONObject(j) ?: continue
                    val start = competition.optString("startDate").ifBlank { competition.optString("date") }
                    if (start.isBlank()) continue
                    val competitors = competition.optJSONArray("competitors") ?: continue
                    val names = ArrayList<String>()
                    val logos = ArrayList<String>()
                    for (k in 0 until competitors.length()) {
                        val competitor = competitors.optJSONObject(k) ?: continue
                        val athlete = competitor.optJSONObject("athlete") ?: continue
                        val name = athlete.optString("displayName").ifBlank { athlete.optString("shortName") }
                        if (name.isNotBlank()) {
                            names += name
                            logos += athlete.optJSONObject("flag")?.optString("href").orEmpty()
                        }
                    }
                    if (names.isEmpty()) continue
                    val statusType = competition.optJSONObject("status")?.optJSONObject("type")
                    val state = normalizeState(statusType?.optString("state"), statusType?.optString("name"))
                    val detail = statusType?.optString("detail").orEmpty().ifBlank { statusType?.optString("shortDetail").orEmpty() }
                    val broadcastObj = competition.optJSONArray("broadcasts")?.optJSONObject(0)
                    val broadcast = broadcastObj?.optJSONArray("names")?.optString(0).orEmpty()
                    val id = competition.optString("id").ifBlank { "tennis:$league:$start:$j" }
                    val short = if (names.size >= 2) "${names[0]} vs ${names[1]}" else names.first()
                    out += SportsEvent(
                        id = id,
                        sport = "tennis",
                        league = groupName,
                        name = short,
                        shortName = short,
                        state = state,
                        startTime = start,
                        competitors = names,
                        competitorLogos = logos,
                        leagueLogo = leagueLogo.ifBlank { null },
                        detail = detail,
                        broadcast = broadcast
                    )
                }
            }
        }
        out
    }.getOrDefault(emptyList())

    private fun normalizeState(state: String?, name: String?): String {
        val s = state.orEmpty().lowercase()
        val n = name.orEmpty().lowercase()
        return when {
            s == "in" || s == "live" || n.contains("in progress") || n.contains("live") -> "in"
            s == "post" || n.contains("final") || n.contains("completed") || n.contains("retired") -> "post"
            else -> "pre"
        }
    }
}
