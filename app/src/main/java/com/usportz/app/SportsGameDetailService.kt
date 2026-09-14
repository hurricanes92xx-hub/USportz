package com.usportz.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Lazy game-detail layer: keeps startup light and only fetches rich data when a game is opened. */
data class SportsGameDetail(
    val eventId: String,
    val venue: String = "",
    val attendance: String = "",
    val homeScore: String = "",
    val awayScore: String = "",
    val periodLabels: List<String> = emptyList(),
    val homePeriodScores: List<String> = emptyList(),
    val awayPeriodScores: List<String> = emptyList(),
    val situation: String = "",
    val broadcasts: List<String> = emptyList(),
    val lastPlay: String = ""
)

object SportsGameDetailService {
    private const val CONNECT_MS = 2000
    private const val READ_MS = 3500

    suspend fun load(event: SportsEvent): SportsGameDetail? = withContext(Dispatchers.IO) {
        val id = event.id.removePrefix("espn:").takeIf { it.all(Char::isDigit) } ?: return@withContext null
        val sport = normalizeSport(event.sport)
        val league = normalizeLeague(event.league, event.sport)
        val url = "https://site.api.espn.com/apis/site/v2/sports/$sport/$league/summary?event=$id"
        runCatching { fetch(url, event) }.getOrNull()
    }

    private fun fetch(url: String, event: SportsEvent): SportsGameDetail? {
        val c = URL(url).openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = CONNECT_MS
            c.readTimeout = READ_MS
            c.instanceFollowRedirects = true
            c.requestMethod = "GET"
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("User-Agent", "USPortz/1.9")
            if (c.responseCode !in 200..299) return null
            parse(c.inputStream.bufferedReader().use { it.readText() }, event)
        } finally {
            c.disconnect()
        }
    }

    private fun parse(body: String, event: SportsEvent): SportsGameDetail = runCatching {
        val root = JSONObject(body)
        val competition = root.optJSONObject("header")?.optJSONArray("competitions")?.optJSONObject(0)
            ?: root.optJSONArray("competitions")?.optJSONObject(0)
        val competitors = competition?.optJSONArray("competitors")
        val scores = ArrayList<String>()
        var home = ""
        var away = ""
        for (i in 0 until (competitors?.length() ?: 0)) {
            val item = competitors?.optJSONObject(i) ?: continue
            val score = item.optString("score")
            val homeAway = item.optString("homeAway")
            if (homeAway == "home") home = score
            if (homeAway == "away") away = score
            scores += score
        }

        val periodLabels = ArrayList<String>()
        val homePeriods = ArrayList<String>()
        val awayPeriods = ArrayList<String>()
        for (i in 0 until (competitors?.length() ?: 0)) {
            val item = competitors?.optJSONObject(i) ?: continue
            val linescores = item.optJSONArray("linescores") ?: continue
            val target = if (item.optString("homeAway") == "home") homePeriods else awayPeriods
            for (j in 0 until linescores.length()) {
                val line = linescores.optJSONObject(j) ?: continue
                target += line.optString("displayValue").ifBlank { line.optString("value") }
                if (periodLabels.size <= j) periodLabels += line.optString("period").ifBlank { "${j + 1}" }
            }
        }

        val broadcasts = ArrayList<String>()
        val broadcastArray = competition?.optJSONArray("broadcasts")
        for (i in 0 until (broadcastArray?.length() ?: 0)) {
            val b = broadcastArray?.optJSONObject(i) ?: continue
            val names = b.optJSONArray("names")
            if (names != null) for (j in 0 until names.length()) names.optString(j).takeIf { it.isNotBlank() }?.let { broadcasts += it }
            b.optString("market").takeIf { it.isNotBlank() }?.let { broadcasts += it }
        }

        val venue = competition?.optJSONObject("venue")?.optString("fullName").orEmpty()
            .ifBlank { root.optJSONObject("gameInfo")?.optJSONObject("venue")?.optString("fullName").orEmpty() }
        val attendance = competition?.optString("attendance").orEmpty()
        val situation = root.optJSONObject("situation")?.let { s ->
            listOf(s.optString("possessionText"), s.optString("downDistanceText"), s.optString("lastPlay")).filter { it.isNotBlank() }.joinToString(" • ")
        }.orEmpty()
        val plays = root.optJSONArray("plays")
        val lastPlay = plays?.optJSONObject(plays.length() - 1)?.optString("text").orEmpty()

        SportsGameDetail(event.id, venue, attendance, home.ifBlank { scores.getOrNull(0).orEmpty() }, away.ifBlank { scores.getOrNull(1).orEmpty() }, periodLabels, homePeriods, awayPeriods, situation.ifBlank { lastPlay }, broadcasts.distinct().take(6), lastPlay)
    }.getOrElse {
        SportsGameDetail(event.id, broadcasts = listOf(event.broadcast).filter { it.isNotBlank() })
    }

    private fun normalizeSport(sport: String): String = when (sport.lowercase()) {
        "football", "american football" -> "football"
        "basketball" -> "basketball"
        "baseball" -> "baseball"
        "hockey", "ice hockey" -> "hockey"
        "soccer", "football soccer" -> "soccer"
        "tennis" -> "tennis"
        "golf" -> "golf"
        "mma" -> "mma"
        else -> sport.lowercase().ifBlank { "football" }
    }

    private fun normalizeLeague(league: String, sport: String): String {
        val text = league.lowercase()
        return when {
            text.contains("college football") || text.contains("ncaaf") -> "college-football"
            text.contains("college basketball") || text.contains("ncaab") || text.contains("men's college") -> "mens-college-basketball"
            text.contains("women's college") || text.contains("ncaaw") -> "womens-college-basketball"
            text.contains("national football") || text == "nfl" -> "nfl"
            text.contains("national basketball") || text == "nba" -> "nba"
            text.contains("major league baseball") || text == "mlb" -> "mlb"
            text.contains("national hockey") || text == "nhl" -> "nhl"
            text.contains("ufc") -> "ufc"
            text == "atp" -> "atp"
            text == "wta" -> "wta"
            text.contains("pga") -> "pga"
            text.contains("lpga") -> "lpga"
            else -> league.lowercase().replace(Regex("[^a-z0-9.]+"), "-").trim('-').ifBlank { sport.lowercase() }
        }
    }
}
