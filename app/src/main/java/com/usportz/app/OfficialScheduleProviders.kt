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
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** High-confidence public fallbacks for leagues that must not disappear when a broad provider fails. */
object OfficialScheduleProviders {
    // nflverse schedule data is automated and updated every ~5 minutes during the NFL season.
    private const val NFL_CSV = "https://github.com/nflverse/nflverse-data/releases/download/schedules/games.csv"
    private const val NCAA_API = "https://ncaa-api.henrygd.me"
    private const val MLB_API = "https://statsapi.mlb.com/api/v1/schedule"

    suspend fun load(today: LocalDate, lastDate: LocalDate): List<SportsEvent> = withContext(Dispatchers.IO) {
        coroutineScope {
            listOf(
                async { loadNfl(today, lastDate) },
                async { loadNcaaFootball(today, lastDate) },
                async { loadMlb(today, lastDate) },
                async { TennisSchedule.load() }
            ).awaitAll().flatten()
        }
    }

    private fun loadNfl(today: LocalDate, lastDate: LocalDate): List<SportsEvent> = runCatching {
        val text = get(NFL_CSV, 12_000)
        val lines = text.lineSequence().filter(String::isNotBlank).toList()
        if (lines.isEmpty()) return emptyList()
        val header = csv(lines.first())
        fun ix(name: String) = header.indexOf(name)
        val season = ix("season"); val type = ix("game_type"); val day = ix("gameday")
        val time = ix("gametime"); val away = ix("away_team"); val home = ix("home_team")
        if (listOf(season, type, day, time, away, home).any { it < 0 }) return emptyList()
        val now = System.currentTimeMillis()
        buildList {
            lines.drop(1).forEach { line ->
                val r = csv(line); if (r.size <= home) return@forEach
                if (r[season].toIntOrNull() != today.year || r[type] != "REG") return@forEach
                val date = runCatching { LocalDate.parse(r[day]) }.getOrNull() ?: return@forEach
                if (date.isBefore(today) || date.isAfter(lastDate)) return@forEach
                val a = clean(r[away]); val h = clean(r[home]); if (a.isBlank() || h.isBlank()) return@forEach
                val start = runCatching {
                    val t = r[time].trim().let { if (it.length == 5) "$it:00" else it }
                    LocalDateTime.parse("$date $t", DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                        .atZone(ZoneId.of("America/New_York")).toInstant().toString()
                }.getOrNull() ?: return@forEach
                val startMs = runCatching { Instant.parse(start).toEpochMilli() }.getOrDefault(Long.MAX_VALUE)
                val awayScore = r.getOrNull(ix("away_score")).orEmpty()
                val homeScore = r.getOrNull(ix("home_score")).orEmpty()
                val final = awayScore.isNotBlank() && homeScore.isNotBlank()
                val location = r.getOrNull(ix("location")).orEmpty()
                val stadium = r.getOrNull(ix("stadium")).orEmpty()
                add(
                    SportsEvent(
                        id = "nflverse:${r.firstOrNull().orEmpty()}:$a:$h",
                        sport = "football", league = "NFL", name = "$a at $h", shortName = "$a  •  $h",
                        state = if (final) "post" else if (startMs <= now) "in" else "pre", startTime = start,
                        competitors = listOf(a, h), competitorLogos = listOf(nflLogo(a), nflLogo(h)),
                        leagueLogo = BrandAssets.logoUrl(SportsBranding.brands.first { it.key == "nfl" }),
                        detail = when {
                            final -> "Final $awayScore-$homeScore"
                            stadium.isNotBlank() && location.equals("Neutral", true) -> "Scheduled • $stadium"
                            stadium.isNotBlank() -> "Scheduled • $stadium"
                            else -> "Scheduled"
                        }
                    )
                )
            }
        }
    }.getOrDefault(emptyList())

    private fun loadNcaaFootball(today: LocalDate, lastDate: LocalDate): List<SportsEvent> = runCatching {
        val anchor = LocalDate.of(today.year, 8, 29)
        val week = (ChronoUnit.DAYS.between(anchor, today).coerceAtLeast(0) / 7).toInt() + 1
        listOf(week, week + 1).distinct().flatMap { w ->
            val body = get("$NCAA_API/scoreboard/football/fbs/${today.year}/$w/all-conf", 8_000)
            if (body.isBlank()) return@flatMap emptyList()
            val root = JSONObject(body)
            val games = root.optJSONArray("games") ?: return@flatMap emptyList()
            buildList {
                for (i in 0 until games.length()) {
                    val game = games.optJSONObject(i)?.optJSONObject("game") ?: continue
                    val home = game.optJSONObject("home") ?: continue
                    val away = game.optJSONObject("away") ?: continue
                    val hNames = home.optJSONObject("names")
                    val aNames = away.optJSONObject("names")
                    val h = clean(hNames?.optString("full")).ifBlank { clean(hNames?.optString("short")) }
                    val a = clean(aNames?.optString("full")).ifBlank { clean(aNames?.optString("short")) }
                    if (a.isBlank() || h.isBlank()) continue
                    val epoch = game.optString("startTimeEpoch").toLongOrNull()
                    val start = epoch?.let { Instant.ofEpochSecond(it).toString() } ?: runCatching {
                        val dateRaw = clean(game.optString("startDate"))
                        val timeRaw = clean(game.optString("startTime")).removeSuffix(" ET")
                        val parsedDate = if (dateRaw.matches(Regex("\\d{2}-\\d{2}-\\d{4}"))) {
                            LocalDate.parse(dateRaw, DateTimeFormatter.ofPattern("MM-dd-yyyy"))
                        } else LocalDate.parse(dateRaw)
                        LocalDateTime.parse("$parsedDate ${timeRaw.ifBlank { "12:00AM" }}", DateTimeFormatter.ofPattern("yyyy-MM-dd h:mma"))
                            .atZone(ZoneId.of("America/New_York")).toInstant().toString()
                    }.getOrNull() ?: continue
                    val localDate = runCatching { Instant.parse(start).atZone(ZoneId.systemDefault()).toLocalDate() }.getOrNull() ?: continue
                    if (localDate.isBefore(today) || localDate.isAfter(lastDate)) continue
                    val state = when (game.optString("gameState").lowercase()) {
                        "live", "in", "i" -> "in"
                        "final", "f", "post" -> "post"
                        else -> "pre"
                    }
                    val hs = clean(home.optString("seoname")).ifBlank { clean(hNames?.optString("seo")) }
                    val aslug = clean(away.optString("seoname")).ifBlank { clean(aNames?.optString("seo")) }
                    val finalMessage = clean(game.optString("finalMessage"))
                    val network = clean(game.optString("network"))
                    add(
                        SportsEvent(
                            id = "ncaa:${game.optString("gameID")}:$a:$h", sport = "football", league = "NCAA Football",
                            name = "$a at $h", shortName = "$a  •  $h", state = state, startTime = start,
                            competitors = listOf(a, h), competitorLogos = listOf(ncaaLogo(aslug), ncaaLogo(hs)),
                            leagueLogo = "https://www.ncaa.com/modules/custom/casablanca_core/img/sportbanners/football.png",
                            detail = finalMessage.ifBlank { "Scheduled" }, broadcast = network
                        )
                    )
                }
            }
        }
    }.getOrDefault(emptyList())

    private fun loadMlb(today: LocalDate, lastDate: LocalDate): List<SportsEvent> = runCatching {
        val root = JSONObject(get("$MLB_API?sportId=1&startDate=$today&endDate=$lastDate&hydrate=team", 10_000))
        val dates = root.optJSONArray("dates") ?: return emptyList()
        buildList {
            for (i in 0 until dates.length()) {
                val games = dates.optJSONObject(i)?.optJSONArray("games") ?: continue
                for (j in 0 until games.length()) {
                    val g = games.optJSONObject(j) ?: continue
                    val teams = g.optJSONObject("teams") ?: continue
                    val a = teams.optJSONObject("away")?.optJSONObject("team") ?: continue
                    val h = teams.optJSONObject("home")?.optJSONObject("team") ?: continue
                    val an = clean(a.optString("name")); val hn = clean(h.optString("name")); if (an.isBlank() || hn.isBlank()) continue
                    val statusObj = g.optJSONObject("status")
                    val stateRaw = statusObj?.optString("abstractGameState").orEmpty().lowercase()
                    val state = when (stateRaw) { "final", "completed" -> "post"; "live" -> "in"; else -> "pre" }
                    val detail = statusObj?.optString("detailedState").orEmpty().ifBlank { "Scheduled" }
                    add(
                        SportsEvent(
                            id = "mlb:${g.optString("gamePk")}", sport = "baseball", league = "MLB", name = "$an at $hn", shortName = "$an  •  $hn",
                            state = state, startTime = g.optString("gameDate"), competitors = listOf(an, hn),
                            competitorLogos = listOf(mlbLogo(a.optString("id")), mlbLogo(h.optString("id"))),
                            leagueLogo = BrandAssets.logoUrl(SportsBranding.brands.first { it.key == "mlb" }), detail = detail
                        )
                    )
                }
            }
        }
    }.getOrDefault(emptyList())

    private fun get(url: String, timeout: Int = 8_000): String {
        val c = URL(url).openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = 3500
            c.readTimeout = timeout
            c.requestMethod = "GET"
            c.setRequestProperty("Accept", "application/json,text/csv,text/plain,*/*")
            c.setRequestProperty("User-Agent", "USPortz/1.4")
            if (c.responseCode !in 200..299) return ""
            c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    private fun csv(line: String): List<String> {
        val out = ArrayList<String>(); val b = StringBuilder(); var quoted = false; var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' && i + 1 < line.length && line[i + 1] == '"' -> { b.append('"'); i++ }
                ch == '"' -> quoted = !quoted
                ch == ',' && !quoted -> { out += b.toString(); b.setLength(0) }
                else -> b.append(ch)
            }
            i++
        }
        out += b.toString()
        return out
    }

    private fun clean(v: String?) = v.orEmpty().trim().takeIf { it.isNotBlank() && !it.equals("null", true) && !it.equals("undefined", true) }.orEmpty()
    private fun nflLogo(abbr: String) = "https://a.espncdn.com/i/teamlogos/nfl/500/${abbr.lowercase()}.png"
    private fun ncaaLogo(slug: String) = slug.takeIf { it.isNotBlank() }?.let { "$NCAA_API/logo/$it.svg?dark=true" }.orEmpty()
    private fun mlbLogo(id: String) = id.toIntOrNull()?.let { "https://www.mlbstatic.com/team-logos/${it}.svg" }.orEmpty()
}
