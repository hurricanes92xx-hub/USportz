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
import java.time.ZoneOffset

/**
 * Targeted TheSportsDB fallback for sports that ESPN does not cover well (especially wrestling).
 * We use season schedules instead of eventsday: the free API permits 15 season calls and only 3
 * eventsday calls per minute, so this is both more complete and much less rate-limit prone.
 */
object DedicatedSchedule {
    private const val API = "https://www.thesportsdb.com/api/v1/json/123/eventsseason.php"

    private data class Season(val id: Int, val sport: String, val league: String)

    private val seasons = listOf(
        Season(4443, "mma", "UFC"),
        Season(4444, "wrestling", "WWE"),
        Season(4563, "wrestling", "AEW"),
        Season(4455, "wrestling", "TNA"),
        Season(4448, "wrestling", "ROH"),
        Season(4445, "boxing", "Boxing")
    )

    suspend fun load(): List<SportsEvent> = withContext(Dispatchers.IO) {
        val today = LocalDate.now()
        val last = today.plusDays(7)
        coroutineScope {
            seasons.map { season -> async(Dispatchers.IO) { fetch(season, today, last) } }
                .awaitAll()
                .flatten()
                .distinctBy { canonical(it) }
        }
    }

    private fun fetch(season: Season, today: LocalDate, last: LocalDate): List<SportsEvent> = runCatching {
        val url = "$API?id=${season.id}&s=${today.year}"
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 3500
            connection.readTimeout = 8000
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "USportz/1.4")
            if (connection.responseCode !in 200..299) return emptyList()
            val events = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                .optJSONArray("events") ?: return emptyList()
            buildList {
                for (i in 0 until events.length()) {
                    val e = events.optJSONObject(i) ?: continue
                    val timestamp = parseTimestamp(e.optString("strTimestamp"), e.optString("dateEvent"), e.optString("strTime"))
                    if (timestamp.isBlank()) continue
                    val start = runCatching { Instant.parse(timestamp) }.getOrNull() ?: continue
                    val localDay = start.atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                    if (localDay.isBefore(today) || localDay.isAfter(last)) continue

                    val home = clean(e.optString("strHomeTeam"))
                    val away = clean(e.optString("strAwayTeam"))
                    val rawTitle = clean(e.optString("strEvent"))
                    val title = rawTitle.ifBlank {
                        listOf(away, home).filter(String::isNotBlank).joinToString(" vs ")
                    }
                    val league = clean(e.optString("strLeague")).ifBlank { season.league }
                    val status = clean(e.optString("strStatus"))
                    val state = normalizeState(status, start.toEpochMilli())
                    val eventId = clean(e.optString("idEvent"))
                    val leagueBadge = clean(e.optString("strLeagueBadge")).ifBlank { clean(e.optString("strLeagueLogo")) }
                    val thumb = clean(e.optString("strThumb"))
                    val poster = clean(e.optString("strPoster"))

                    add(
                        SportsEvent(
                            id = "tsdb:${eventId.ifBlank { "${season.league}:$localDay:$i" }}",
                            sport = season.sport,
                            league = season.league,
                            name = title,
                            shortName = title,
                            state = state,
                            startTime = timestamp,
                            competitors = listOf(away, home).filter(String::isNotBlank),
                            competitorLogos = listOf(clean(e.optString("strAwayTeamBadge")), clean(e.optString("strHomeTeamBadge"))),
                            leagueLogo = leagueBadge.ifBlank { BrandAssets.logoUrl(SportsBranding.find(title, league)) },
                            detail = listOf(clean(e.optString("strVenue")), clean(e.optString("strCity")))
                                .filter(String::isNotBlank)
                                .joinToString(", "),
                            broadcast = clean(e.optString("strTVStation")).ifBlank { clean(e.optString("strChannel")) }
                        )
                    )
                }
            }
        } finally {
            connection.disconnect()
        }
    }.getOrDefault(emptyList())

    private fun parseTimestamp(raw: String?, date: String?, time: String?): String {
        val value = clean(raw)
        value.toLongOrNull()?.let { epoch ->
            return Instant.ofEpochMilli(if (epoch < 100000000000L) epoch * 1000L else epoch).toString()
        }
        if (value.isNotBlank()) {
            runCatching { return Instant.parse(value).toString() }
            runCatching { return java.time.LocalDateTime.parse(value).atZone(ZoneOffset.UTC).toInstant().toString() }
        }
        val d = clean(date)
        if (d.isNotBlank()) {
            val t = clean(time).ifBlank { "00:00:00" }.let { if (it.length == 5) "$it:00" else it }
            runCatching {
                return java.time.LocalDateTime.parse("$d $t", java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    .atZone(ZoneOffset.UTC).toInstant().toString()
            }
        }
        return ""
    }

    private fun normalizeState(status: String, startMs: Long): String {
        val s = status.lowercase()
        return when {
            s.contains("finish") || s.contains("complete") || s == "post" || s == "ft" -> "post"
            s.contains("live") || s == "in" || (s.firstOrNull()?.isDigit() == true) -> "in"
            startMs <= System.currentTimeMillis() -> "post"
            else -> "pre"
        }
    }

    private fun canonical(event: SportsEvent): String =
        "${event.league}|${event.name}|${event.startTime.take(16)}".lowercase()

    private fun clean(value: String?): String = value.orEmpty().trim()
        .takeIf { it.isNotBlank() && !it.equals("null", true) && !it.equals("undefined", true) }
        .orEmpty()
}
