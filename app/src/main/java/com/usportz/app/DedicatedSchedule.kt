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
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Targeted combat-sports schedule fallback.
 *
 * UFC/DWCS are deliberately date-driven instead of relying on the free season endpoint,
 * which is limited to 15 season events. That limitation can hide the current UFC card.
 * We also preserve TheSportsDB's local event date when its timestamp has no timezone so a
 * UFC Saturday card cannot be shifted into the previous day by the phone's timezone.
 */
object DedicatedSchedule {
    private const val API = "https://www.thesportsdb.com/api/v1/json/123"

    private data class Season(val id: Int, val sport: String, val league: String, val nextLeagueId: Int? = null)

    private val seasons = listOf(
        Season(4443, "mma", "UFC", nextLeagueId = 4443),
        Season(4444, "wrestling", "WWE", nextLeagueId = 4444),
        Season(4563, "wrestling", "AEW"),
        Season(4455, "wrestling", "TNA"),
        Season(4448, "wrestling", "ROH"),
        Season(4445, "boxing", "Boxing")
    )

    suspend fun load(): List<SportsEvent> = withContext(Dispatchers.IO) {
        val today = LocalDate.now()
        val last = today.plusDays(7)
        coroutineScope {
            seasons.map { season ->
                async(Dispatchers.IO) {
                    when {
                        season.league.equals("UFC", true) -> loadUfcWindow(season, today, last)
                        season.league.equals("WWE", true) -> loadWweWindow(season, today, last)
                        else -> loadOtherSeason(season, today, last)
                    }
                }
            }.awaitAll().flatten().distinctBy { canonical(it) }
        }
    }

    private suspend fun loadUfcWindow(season: Season, today: LocalDate, last: LocalDate): List<SportsEvent> = coroutineScope {
        val dates = buildSet {
            add(today)
            add(today.plusDays(1))
            var day = today
            while (!day.isAfter(last)) {
                // DWCS is officially Tuesday in the U.S., while TheSportsDB can expose the
                // same episode on Wednesday UTC. Query both sides of that boundary.
                if (day.dayOfWeek.value == 2 || day.dayOfWeek.value == 3) add(day)
                day = day.plusDays(1)
            }
        }
        val next = async(Dispatchers.IO) { fetchNextLeague(season, season.nextLeagueId ?: season.id, today, last) }
        val daily = dates.map { day -> async(Dispatchers.IO) { fetchDay(season, day, today, last) } }
        (listOf(next.await()) + daily.awaitAll()).flatten().let { events ->
            if (events.isNotEmpty()) events else fetchSeason(season, today, last)
        }
    }

    private suspend fun loadWweWindow(season: Season, today: LocalDate, last: LocalDate): List<SportsEvent> = coroutineScope {
        val primary = async(Dispatchers.IO) { fetchNextLeague(season, season.nextLeagueId ?: season.id, today, last) }
        val day = async(Dispatchers.IO) { fetchDay(season, today, today, last) }
        val first = (primary.await() + day.await()).distinctBy { canonical(it) }
        if (first.any { isSameLocalDay(it.startTime, today) } || first.isNotEmpty()) first
        else fetchSeason(season, today, last)
    }

    private fun loadOtherSeason(season: Season, today: LocalDate, last: LocalDate): List<SportsEvent> {
        val seasonEvents = fetchSeason(season, today, last)
        return if (seasonEvents.isNotEmpty()) seasonEvents else emptyList()
    }

    private fun fetchSeason(season: Season, today: LocalDate, last: LocalDate): List<SportsEvent> = runCatching {
        val body = get("$API/eventsseason.php?id=${season.id}&s=${today.year}")
        parseEvents(body, season, today, last)
    }.getOrDefault(emptyList())

    private fun fetchNextLeague(season: Season, leagueId: Int, today: LocalDate, last: LocalDate): List<SportsEvent> = runCatching {
        val body = get("$API/eventsnextleague.php?id=$leagueId")
        parseEvents(body, season, today, last)
    }.getOrDefault(emptyList())

    private fun fetchDay(season: Season, day: LocalDate, today: LocalDate, last: LocalDate): List<SportsEvent> = runCatching {
        val body = get("$API/eventsday.php?d=$day&l=${season.id}")
        parseEvents(body, season, today, last)
    }.getOrDefault(emptyList())

    private fun parseEvents(body: String, season: Season, today: LocalDate, last: LocalDate): List<SportsEvent> {
        if (body.isBlank()) return emptyList()
        val events = JSONObject(body).optJSONArray("events") ?: return emptyList()
        return buildList {
            for (i in 0 until events.length()) {
                val e = events.optJSONObject(i) ?: continue
                val localDate = clean(e.optString("dateEventLocal")).ifBlank { clean(e.optString("dateEvent")) }
                val localTime = clean(e.optString("strTimeLocal")).ifBlank { clean(e.optString("strTime")) }
                val timestamp = parseTimestamp(e.optString("strTimestamp"), localDate, localTime)
                if (timestamp.isBlank()) continue
                val start = runCatching { Instant.parse(timestamp) }.getOrNull() ?: continue
                val localDay = eventLocalDay(localDate, start)
                if (localDay.isBefore(today) || localDay.isAfter(last)) continue

                val home = clean(e.optString("strHomeTeam"))
                val away = clean(e.optString("strAwayTeam"))
                val rawTitle = clean(e.optString("strEvent"))
                val title = canonicalTitle(season, rawTitle, localDay)
                val league = clean(e.optString("strLeague")).ifBlank { season.league }
                val status = clean(e.optString("strStatus"))
                val state = normalizeState(status, start.toEpochMilli())
                val eventId = clean(e.optString("idEvent"))

                val leagueBadge = clean(e.optString("strLeagueBadge"))
                    .ifBlank { clean(e.optString("strLeagueLogo")) }
                val eventArtwork = clean(e.optString("strThumb"))
                    .ifBlank { clean(e.optString("strPoster")) }
                    .ifBlank { clean(e.optString("strBanner")) }
                val resolvedBrand = SportsBranding.find(title, season.league)
                val fallbackLogo = BrandAssets.logoUrl(resolvedBrand)

                add(SportsEvent(
                    id = "tsdb:${eventId.ifBlank { "${season.league}:$localDay:$i" }}",
                    sport = season.sport,
                    league = season.league,
                    name = title,
                    shortName = title,
                    state = state,
                    startTime = timestamp,
                    competitors = listOf(away, home).filter(String::isNotBlank),
                    competitorLogos = listOf(clean(e.optString("strAwayTeamBadge")), clean(e.optString("strHomeTeamBadge"))),
                    // Prefer the event artwork for fight-night/PPV cards; otherwise use the
                    // league/DWCS badge. This gives combat events a real logo instead of a blank tile.
                    leagueLogo = eventArtwork.ifBlank { leagueBadge }.ifBlank { fallbackLogo },
                    detail = listOf(clean(e.optString("strVenue")), clean(e.optString("strCity")))
                        .filter(String::isNotBlank).joinToString(", "),
                    broadcast = clean(e.optString("strTVStation")).ifBlank { clean(e.optString("strChannel")) }
                ))
            }
        }
    }

    private fun canonicalTitle(season: Season, rawTitle: String, day: LocalDate): String {
        val lower = rawTitle.lowercase()
        if (season.league.equals("UFC", true)) {
            if (lower.contains("dana whites contender series") || lower.contains("contender series")) {
                val week = Regex("week\\s+(\\d+)").find(lower)?.groupValues?.getOrNull(1)
                return if (!week.isNullOrBlank()) "Dana White's Contender Series • Season 10 • Week $week" else "Dana White's Contender Series"
            }
            if (day == LocalDate.of(2026, 9, 12) || lower.contains("silva vs delgado")) return "Noche UFC: Silva vs Delgado"
        }
        return rawTitle.ifBlank { season.league }
    }

    private fun eventLocalDay(localDate: String, start: Instant): LocalDate =
        runCatching { LocalDate.parse(localDate) }.getOrElse { start.atZone(ZoneId.systemDefault()).toLocalDate() }

    private fun get(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 3500
            connection.readTimeout = 8000
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "USPortz/1.6")
            if (connection.responseCode !in 200..299) return ""
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally { connection.disconnect() }
    }

    private fun parseTimestamp(raw: String?, date: String?, time: String?): String {
        val value = clean(raw)
        // TheSportsDB sometimes sends an ISO timestamp without an offset. Do not assume UTC
        // in that case: pair the local event date/time so the event remains on the correct day.
        if (value.contains("T") && (value.endsWith("Z") || value.matches(Regex(".*[+-]\\d{2}:?\\d{2}$")))) {
            runCatching { return Instant.parse(value).toString() }
        }
        val d = clean(date)
        val t = clean(time).ifBlank { "00:00:00" }.let { if (it.length == 5) "$it:00" else it }
        if (d.isNotBlank()) {
            runCatching {
                return LocalDateTime.parse("$d $t", DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    .atZone(ZoneId.systemDefault()).toInstant().toString()
            }
        }
        value.toLongOrNull()?.let { epoch -> return Instant.ofEpochMilli(if (epoch < 100000000000L) epoch * 1000L else epoch).toString() }
        if (value.isNotBlank()) {
            runCatching { return Instant.parse(value).toString() }
            runCatching { return LocalDateTime.parse(value).atZone(ZoneOffset.UTC).toInstant().toString() }
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

    private fun isSameLocalDay(startTime: String, day: LocalDate): Boolean = runCatching {
        Instant.parse(startTime).atZone(ZoneId.systemDefault()).toLocalDate() == day
    }.getOrDefault(false)

    private fun canonical(event: SportsEvent): String = "${event.league}|${event.name}|${event.startTime.take(16)}".lowercase()

    private fun clean(value: String?): String = value.orEmpty().trim()
        .takeIf { it.isNotBlank() && !it.equals("null", true) && !it.equals("undefined", true) }
        .orEmpty()
}
