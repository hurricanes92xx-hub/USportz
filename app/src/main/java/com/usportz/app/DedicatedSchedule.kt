package com.usportz.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

/**
 * Secondary schedule provider for sports where ESPN does not expose a dependable scoreboard.
 * TheSportsDB supplies event schedules for fighting, wrestling, motorsport and golf; official
 * sources remain the preferred authority for major league-specific integrations.
 */
object DedicatedSchedule {
    private const val API = "https://www.thesportsdb.com/api/v1/json/123/eventsday.php"
    private val sportFilters = listOf("Fighting", "Wrestling", "Motorsport", "Golf")

    suspend fun load(): List<SportsEvent> = withContext(Dispatchers.IO) {
        val dates = (0L..2L).map { LocalDate.now().plusDays(it).toString() }
        coroutineScope {
            dates.flatMap { date ->
                sportFilters.map { sport -> async(Dispatchers.IO) { fetch(date, sport) } }
            }.awaitAll().flatten()
        }.distinctBy { it.id }
    }

    private fun fetch(date: String, sport: String): List<SportsEvent> {
        val url = "$API?d=$date&s=${sport.replace(" ", "%20")}"
        return runCatching {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 4000
            connection.readTimeout = 6000
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "USportz/1.0")
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()
            val events = JSONObject(body).optJSONArray("events") ?: return emptyList()
            buildList {
                for (i in 0 until events.length()) {
                    val e = events.optJSONObject(i) ?: continue
                    val home = e.optString("strHomeTeam")
                    val away = e.optString("strAwayTeam")
                    val title = e.optString("strEvent").ifBlank { listOf(home, away).filter { it.isNotBlank() }.joinToString(" vs ") }
                    val league = e.optString("strLeague").ifBlank { sport }
                    val brand = brandFor(sport, league, title)
                    val logos = listOf(e.optString("strHomeTeamBadge"), e.optString("strAwayTeamBadge"))
                        .filter { it.isNotBlank() }
                    val competitors = listOf(home, away).filter { it.isNotBlank() }
                    add(SportsEvent(
                        id = "tsdb:${e.optString("idEvent")}",
                        sport = sport.lowercase(),
                        league = brand.ifBlank { league },
                        name = title,
                        shortName = title,
                        state = if (e.optString("strStatus").equals("Match Finished", true)) "post" else "pre",
                        startTime = e.optString("strTimestamp").ifBlank { "${e.optString("dateEvent")}T${e.optString("strTime", "00:00:00")}" },
                        competitors = competitors,
                        competitorLogos = logos,
                        leagueLogo = e.optString("strLeagueBadge").ifBlank { e.optString("strLeagueLogo") }.ifBlank { null },
                        detail = listOf(e.optString("strVenue"), e.optString("strCity")).filter { it.isNotBlank() }.joinToString(", "),
                        broadcast = e.optString("strTVStation")
                    ))
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun brandFor(sport: String, league: String, title: String): String {
        val text = "$league $title".lowercase()
        return when {
            "wwe" in text || "raw" in text || "smackdown" in text || "nxt" in text || "evolve" in text -> "WWE"
            "aew" in text || "dynamite" in text || "collision" in text || "all out" in text || "full gear" in text -> "AEW"
            "tna" in text || "impact" in text -> "TNA"
            "roh" in text || "ring of honor" in text -> "ROH"
            "boxing" in text -> "Boxing"
            "monster jam" in text -> "Monster Jam"
            sport.equals("golf", true) -> "Golf"
            "nascar" in text -> "NASCAR"
            "indy" in text || "indycar" in text -> "INDYCAR"
            "formula 1" in text || "f1" in text -> "F1"
            "motogp" in text -> "MotoGP"
            else -> league
        }
    }
}
