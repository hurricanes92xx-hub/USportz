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

/** TheSportsDB fallback. Uses three daily requests instead of exceeding the free day-schedule limit. */
object DedicatedSchedule {
    private const val API = "https://www.thesportsdb.com/api/v1/json/123/eventsday.php"

    suspend fun load(): List<SportsEvent> = withContext(Dispatchers.IO) {
        val dates = (0L..2L).map { LocalDate.now().plusDays(it).toString() }
        coroutineScope { dates.map { date -> async(Dispatchers.IO) { fetch(date) } }.awaitAll().flatten() }.distinctBy { canonical(it) }
    }

    private fun fetch(date: String): List<SportsEvent> = runCatching {
        val connection = URL("$API?d=$date").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 3500; connection.readTimeout = 7000; connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json"); connection.setRequestProperty("User-Agent", "USportz/1.3")
            if (connection.responseCode !in 200..299) return emptyList()
            val events = JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).optJSONArray("events") ?: return emptyList()
            buildList {
                for (i in 0 until events.length()) {
                    val e = events.optJSONObject(i) ?: continue
                    val home = clean(e.optString("strHomeTeam")); val away = clean(e.optString("strAwayTeam"))
                    val rawTitle = clean(e.optString("strEvent")); val title = rawTitle.ifBlank { listOf(away, home).filter(String::isNotBlank).joinToString(" vs ") }
                    val sport = clean(e.optString("strSport")).ifBlank { "Sports" }; val league = clean(e.optString("strLeague")).ifBlank { sport }; val brand = brandFor(sport, league, title)
                    val timestamp = parseTimestamp(e.opt("strTimestamp"), clean(e.optString("dateEvent")), clean(e.optString("strTime"))); if (timestamp.isBlank()) continue
                    add(SportsEvent(
                        id = "tsdb:${clean(e.optString("idEvent"))}".ifBlank { "tsdb:$sport:$date:$i" }, sport = sport.lowercase(), league = brand.ifBlank { league },
                        name = title, shortName = title, state = normalizeState(clean(e.optString("strStatus"))), startTime = timestamp,
                        competitors = listOf(away, home).filter(String::isNotBlank), competitorLogos = listOf(clean(e.optString("strAwayTeamBadge")), clean(e.optString("strHomeTeamBadge"))),
                        leagueLogo = clean(e.optString("strLeagueBadge")).ifBlank { clean(e.optString("strLeagueLogo")) }.ifBlank { BrandAssets.logoUrl(SportsBranding.find(title, league)) },
                        detail = listOf(clean(e.optString("strVenue")), clean(e.optString("strCity"))).filter(String::isNotBlank).joinToString(", "), broadcast = clean(e.optString("strTVStation"))
                    ))
                }
            }
        } finally { connection.disconnect() }
    }.getOrDefault(emptyList())

    private fun parseTimestamp(raw: Any?, date: String, time: String): String {
        val value = clean(raw?.toString()); value.toLongOrNull()?.let { epoch -> return Instant.ofEpochMilli(if (epoch < 100000000000L) epoch * 1000L else epoch).toString() }
        if (value.isNotBlank()) { runCatching { return Instant.parse(value).toString() }; runCatching { return java.time.LocalDateTime.parse(value).atZone(java.time.ZoneOffset.UTC).toInstant().toString() } }
        if (date.isNotBlank()) { val cleanTime = time.ifBlank { "00:00:00" }.let { if (it.length == 5) "$it:00" else it }; runCatching { return java.time.LocalDateTime.parse("$date $cleanTime", java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).atZone(java.time.ZoneOffset.UTC).toInstant().toString() } }
        return ""
    }

    private fun normalizeState(status: String): String { val s = status.lowercase(); return when { s.contains("finish") || s.contains("complete") || s == "post" -> "post"; s.contains("live") || s == "in" || (s.firstOrNull()?.isDigit() == true) -> "in"; else -> "pre" } }
    private fun brandFor(sport: String, league: String, title: String): String {
        val text = "$league $title".lowercase()
        return when { "wwe" in text || "raw" in text || "smackdown" in text || "nxt" in text || "evolve" in text -> "WWE"; "aew" in text || "dynamite" in text || "collision" in text || "all out" in text || "full gear" in text -> "AEW"; "tna" in text || "impact wrestling" in text || "impact!" in text -> "TNA"; "roh" in text || "ring of honor" in text -> "ROH"; "boxing" in text || "wbc" in text || "wba" in text || "wbo" in text || "ibf" in text -> "Boxing"; "monster jam" in text -> "Monster Jam"; "nascar" in text -> "NASCAR"; "indy" in text || "indycar" in text -> "INDYCAR"; "formula 1" in text || "f1" in text -> "Formula 1"; "motogp" in text -> "MotoGP"; sport.equals("golf", true) -> "Golf"; sport.equals("tennis", true) -> if ("wta" in text) "WTA" else if ("atp" in text) "ATP" else "Tennis"; sport.equals("cycling", true) -> "Cycling"; sport.equals("darts", true) -> "Darts"; else -> league }
    }
    private fun canonical(event: SportsEvent): String = "${event.league}|${event.competitors.sorted()}|${event.startTime.take(16)}".lowercase()
    private fun clean(value: String?): String = value.orEmpty().trim().takeIf { it.isNotBlank() && !it.equals("null", true) && !it.equals("undefined", true) }.orEmpty()
}
