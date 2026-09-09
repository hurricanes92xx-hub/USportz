package com.usportz.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate

/**
 * Secondary schedule provider using TheSportsDB's free v1 day schedule endpoint.
 * It fills gaps for sports where ESPN's public scoreboard is incomplete or inconsistent.
 * The free API is limited, so this layer is deliberately scoped to high-value uncovered sports.
 */
object DedicatedSchedule {
    private const val API = "https://www.thesportsdb.com/api/v1/json/123/eventsday.php"
    private val sportFilters = listOf("Fighting", "Wrestling", "Motorsport", "Golf", "Boxing", "Tennis", "Cycling", "Darts")

    suspend fun load(): List<SportsEvent> = withContext(Dispatchers.IO) {
        val dates = (0L..2L).map { LocalDate.now().plusDays(it).toString() }
        coroutineScope {
            dates.flatMap { date -> sportFilters.map { sport -> async(Dispatchers.IO) { fetch(date, sport) } } }
                .awaitAll().flatten()
        }.distinctBy { canonical(it) }
    }

    private fun fetch(date: String, sport: String): List<SportsEvent> = runCatching {
        val url = "$API?d=$date&s=${sport.replace(" ", "%20")}"
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 4500
            connection.readTimeout = 8000
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "USportz/1.1")
            if (connection.responseCode !in 200..299) return emptyList()
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val events = JSONObject(body).optJSONArray("events") ?: return emptyList()
            buildList {
                for (i in 0 until events.length()) {
                    val e = events.optJSONObject(i) ?: continue
                    val home = e.optString("strHomeTeam")
                    val away = e.optString("strAwayTeam")
                    val title = e.optString("strEvent").ifBlank { listOf(home, away).filter { it.isNotBlank() }.joinToString(" vs ") }
                    val league = e.optString("strLeague").ifBlank { sport }
                    val brand = brandFor(sport, league, title)
                    val competitors = listOf(home, away).filter { it.isNotBlank() }
                    val logos = listOf(e.optString("strHomeTeamBadge"), e.optString("strAwayTeamBadge"))
                    val timestamp = parseTimestamp(e.opt("strTimestamp"), e.optString("dateEvent"), e.optString("strTime"))
                    if (timestamp.isBlank()) continue
                    add(SportsEvent(
                        id = "tsdb:${e.optString("idEvent")}".ifBlank { "tsdb:$sport:$date:$i" },
                        sport = sport.lowercase(),
                        league = brand.ifBlank { league },
                        name = title,
                        shortName = title,
                        state = normalizeState(e.optString("strStatus")),
                        startTime = timestamp,
                        competitors = competitors,
                        competitorLogos = logos,
                        leagueLogo = e.optString("strLeagueBadge").ifBlank { e.optString("strLeagueLogo") }.ifBlank { null },
                        detail = listOf(e.optString("strVenue"), e.optString("strCity")).filter { it.isNotBlank() }.joinToString(", "),
                        broadcast = e.optString("strTVStation")
                    ))
                }
            }
        } finally {
            connection.disconnect()
        }
    }.getOrDefault(emptyList())

    private fun parseTimestamp(raw: Any?, date: String, time: String): String {
        val value = raw?.toString().orEmpty().trim()
        value.toLongOrNull()?.let { epoch -> return Instant.ofEpochMilli(if (epoch < 100000000000L) epoch * 1000L else epoch).toString() }
        if (value.isNotBlank()) {
            runCatching { return Instant.parse(value).toString() }
            runCatching { return java.time.LocalDateTime.parse(value).atZone(java.time.ZoneOffset.UTC).toInstant().toString() }
        }
        if (date.isNotBlank()) {
            val clean = time.trim().ifBlank { "00:00:00" }.let { if (it.length == 5) "$it:00" else it }
            runCatching { return java.time.LocalDateTime.parse("$date $clean", java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).atZone(java.time.ZoneOffset.UTC).toInstant().toString() }
        }
        return ""
    }

    private fun normalizeState(status: String): String {
        val s = status.lowercase()
        return when {
            s.contains("finish") || s.contains("complete") || s == "post" -> "post"
            s.contains("live") || s.matches(Regex("\u005c\u005cd+[HhQq].*")) || s == "in" -> "in"
            else -> "pre"
        }
    }

    private fun brandFor(sport: String, league: String, title: String): String {
        val text = "$league $title".lowercase()
        return when {
            "wwe" in text || "raw" in text || "smackdown" in text || "nxt" in text || "evolve" in text -> "WWE"
            "aew" in text || "dynamite" in text || "collision" in text || "all out" in text || "full gear" in text -> "AEW"
            "tna" in text || "impact wrestling" in text || "impact!" in text -> "TNA"
            "roh" in text || "ring of honor" in text -> "ROH"
            "boxing" in text || "wbc" in text || "wba" in text || "wbo" in text || "ibf" in text -> "Boxing"
            "monster jam" in text -> "Monster Jam"
            "nascar" in text -> "NASCAR"
            "indy" in text || "indycar" in text -> "INDYCAR"
            "formula 1" in text || "f1" in text -> "Formula 1"
            "motogp" in text -> "MotoGP"
            sport.equals("golf", true) -> "Golf"
            sport.equals("tennis", true) -> if ("wta" in text) "WTA" else if ("atp" in text) "ATP" else "Tennis"
            sport.equals("cycling", true) -> "Cycling"
            sport.equals("darts", true) -> "Darts"
            else -> league
        }
    }

    private fun canonical(event: SportsEvent): String = "${event.league}|${event.competitors.sorted()}|${event.startTime.take(16)}".lowercase()
}
