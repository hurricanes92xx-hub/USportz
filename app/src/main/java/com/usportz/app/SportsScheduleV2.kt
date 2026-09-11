package com.usportz.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Independent schedule engine. Provider failures are isolated per league so one bad
 * endpoint can never erase the entire schedule.
 */
object SportsScheduleV2 {
    private data class Feed(val sport: String, val league: String)

    private const val LOOKAHEAD_DAYS = 7L
    private const val CONNECT_MS = 2500
    private const val READ_MS = 5000
    private const val FEED_TIMEOUT_MS = 6500L

    private val feeds = listOf(
        Feed("football", "nfl"), Feed("football", "college-football"), Feed("football", "cfl"), Feed("football", "ufl"),
        Feed("baseball", "mlb"), Feed("baseball", "college-baseball"),
        Feed("basketball", "nba"), Feed("basketball", "wnba"), Feed("basketball", "mens-college-basketball"), Feed("basketball", "womens-college-basketball"),
        Feed("hockey", "nhl"), Feed("hockey", "mens-college-hockey"), Feed("hockey", "womens-college-hockey"),
        Feed("soccer", "usa.1"), Feed("soccer", "usa.nwsl"), Feed("soccer", "usa.usl.1"), Feed("soccer", "eng.1"), Feed("soccer", "eng.2"),
        Feed("soccer", "esp.1"), Feed("soccer", "ger.1"), Feed("soccer", "ita.1"), Feed("soccer", "fra.1"), Feed("soccer", "ned.1"), Feed("soccer", "por.1"), Feed("soccer", "sco.1"),
        Feed("soccer", "mex.1"), Feed("soccer", "mex.2"), Feed("soccer", "uefa.champions"), Feed("soccer", "uefa.europa"), Feed("soccer", "conmebol.libertadores"), Feed("soccer", "conmebol.sudamericana"),
        Feed("tennis", "atp"), Feed("tennis", "wta"),
        Feed("golf", "pga"), Feed("golf", "lpga"), Feed("golf", "eur"), Feed("golf", "liv"),
        Feed("racing", "f1"), Feed("racing", "nascar-premier"), Feed("racing", "nascar-secondary"), Feed("racing", "nascar-truck"),
        Feed("mma", "ufc"), Feed("boxing", "boxing")
    )

    suspend fun load(forceRefresh: Boolean = false, sourceChannels: List<SportsChannel> = emptyList()): List<SportsEvent> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val today = LocalDate.now()
        val last = today.plusDays(LOOKAHEAD_DAYS)

        val espn = supervisorScope {
            feeds.map { feed ->
                async {
                    withTimeoutOrNull(FEED_TIMEOUT_MS) { fetchFeed(feed, today, last) }.orEmpty()
                }
            }.awaitAll().flatten()
        }

        // These are additive fallbacks. A failure in any one provider is deliberately ignored.
        val official = runCatching { OfficialScheduleProviders.load(today, last) }.getOrDefault(emptyList())
        val dedicated = runCatching { DedicatedSchedule.load() }.getOrDefault(emptyList())
        val monster = runCatching { MonsterJamSchedule.load(today, last) }.getOrDefault(emptyList())

        val all = (espn + official + dedicated + monster)
            .mapNotNull { normalizeEvent(it, now, today, last) }
            .groupBy { canonical(it) }
            .values
            .mapNotNull { group -> group.maxWithOrNull(compareBy<SportsEvent> { quality(it) }.thenBy { it.state == "in" }) }
            .sortedWith(compareByDescending<SportsEvent> { it.state == "in" }.thenBy { startMs(it.startTime) ?: Long.MAX_VALUE })
            .take(2500)

        if (all.isNotEmpty()) {
            ScheduleMemory.replace(all)
        } else {
            ScheduleMemory.snapshot()?.let { return@withContext prioritize(it, sourceChannels) }
        }
        prioritize(all, sourceChannels)
    }

    fun forSport(events: List<SportsEvent>, sport: String): List<SportsEvent> =
        if (sport.isBlank() || sport == "All") events
        else events.filter { SportsCatalog.classify(it.name, it.league) == sport || it.sport.equals(sport, true) }

    private fun prioritize(events: List<SportsEvent>, channels: List<SportsChannel>): List<SportsEvent> {
        if (channels.isEmpty()) return events
        return events.sortedWith(compareByDescending<SportsEvent> { it.state == "in" }.thenByDescending { sourceScore(it, channels) }.thenBy { startMs(it.startTime) ?: Long.MAX_VALUE })
    }

    private fun sourceScore(event: SportsEvent, channels: List<SportsChannel>): Int =
        channels.maxOfOrNull { GameSourceMatcher.rankMatches(event, listOf(it), 1).firstOrNull()?.score ?: 0 } ?: 0

    private fun fetchFeed(feed: Feed, today: LocalDate, last: LocalDate): List<SportsEvent> {
        val day = today.format(DateTimeFormatter.BASIC_ISO_DATE)
        val range = "$day-${last.format(DateTimeFormatter.BASIC_ISO_DATE)}"
        val base = "https://site.api.espn.com/apis/site/v2/sports/${feed.sport}/${feed.league}/scoreboard"
        val urls = listOf(
            "$base?dates=$day&limit=500",
            "$base?dates=$range&limit=500",
            "https://site.api.espn.com/apis/site/v3/sports/${feed.sport}/${feed.league}/scoreboard?dates=$day&limit=500"
        )
        for (url in urls) {
            val parsed = fetchJson(url, feed)
            if (parsed.isNotEmpty()) return parsed
        }
        return emptyList()
    }

    private fun fetchJson(url: String, feed: Feed): List<SportsEvent> = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = CONNECT_MS
            c.readTimeout = READ_MS
            c.requestMethod = "GET"
            c.instanceFollowRedirects = true
            c.useCaches = false
            c.setRequestProperty("Accept", "application/json,text/plain,*/*")
            c.setRequestProperty("Cache-Control", "no-cache")
            c.setRequestProperty("User-Agent", "USPortz/2.0 Android")
            if (c.responseCode !in 200..299) return emptyList()
            parse(c.inputStream.bufferedReader().use { it.readText() }, feed)
        } finally { c.disconnect() }
    }.getOrDefault(emptyList())

    private fun parse(body: String, feed: Feed): List<SportsEvent> = runCatching {
        val root = JSONObject(body)
        val array = root.optJSONArray("events") ?: return emptyList()
        val leagueLogo = safe(root.optJSONArray("leagues")?.optJSONObject(0)?.optJSONArray("logos")?.optJSONObject(0)?.optString("href"))
        buildList {
            for (i in 0 until array.length()) {
                val event = array.optJSONObject(i) ?: continue
                val competition = event.optJSONArray("competitions")?.optJSONObject(0) ?: continue
                val competitors = competition.optJSONArray("competitors") ?: continue
                val names = ArrayList<String>()
                val logos = ArrayList<String>()
                for (j in 0 until competitors.length()) {
                    val team = competitors.optJSONObject(j)?.optJSONObject("team") ?: continue
                    val name = safe(team.optString("displayName")).ifBlank { safe(team.optString("shortDisplayName")) }
                    if (name.isNotBlank()) {
                        names += name
                        logos += safe(team.optString("logo")).ifBlank { safe(team.optJSONArray("logos")?.optJSONObject(0)?.optString("href")) }
                    }
                }
                val start = safe(event.optString("date")).ifBlank { safe(competition.optString("date")) }
                if (start.isBlank()) continue
                val status = competition.optJSONObject("status")?.optJSONObject("type")
                val rawName = safe(event.optString("name"))
                val broadcast = safe(competition.optJSONArray("broadcasts")?.optJSONObject(0)?.optString("names"))
                    .ifBlank { safe(competition.optJSONArray("broadcasts")?.optJSONObject(0)?.optString("market")) }
                add(SportsEvent(
                    id = safe(event.optString("id")).ifBlank { "espn:${feed.sport}:${feed.league}:$i:$start" },
                    sport = feed.sport,
                    league = SportsBranding.label(rawName, feed.league),
                    name = rawName,
                    shortName = safe(event.optString("shortName")),
                    state = rawState(safe(status?.optString("state")), safe(status?.optString("name"))),
                    startTime = start,
                    competitors = names,
                    competitorLogos = logos,
                    leagueLogo = safe(event.optJSONArray("logos")?.optJSONObject(0)?.optString("href")).ifBlank { leagueLogo },
                    detail = safe(status?.optString("detail")).ifBlank { safe(status?.optString("shortDetail")) },
                    broadcast = broadcast
                ))
            }
        }
    }.getOrDefault(emptyList())

    private fun normalizeEvent(event: SportsEvent, now: Long, today: LocalDate, last: LocalDate): SportsEvent? {
        val start = startMs(event.startTime) ?: return null
        val day = Instant.ofEpochMilli(start).atZone(ZoneId.systemDefault()).toLocalDate()
        if (day.isBefore(today) || day.isAfter(last)) return null
        val state = when {
            event.state == "post" -> "post"
            event.state == "in" -> "in"
            now < start -> "pre"
            now <= start + 6 * 60 * 60 * 1000L -> "in"
            else -> "post"
        }
        return event.copy(state = state)
    }

    private fun rawState(state: String, name: String): String {
        val s = state.lowercase(); val n = name.lowercase()
        return when {
            s == "in" || n.contains("in progress") || n.contains("live") || n == "halftime" -> "in"
            s == "post" || n.contains("final") || n.contains("completed") -> "post"
            else -> "pre"
        }
    }

    private fun canonical(e: SportsEvent): String {
        val teams = e.competitors.map(::norm).sorted().joinToString("|")
        val minute = startMs(e.startTime)?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDateTime().withSecond(0).withNano(0).toString() } ?: e.startTime.take(16)
        return "${norm(e.sport)}|${norm(e.league)}|$teams|$minute"
    }

    private fun quality(e: SportsEvent): Int = e.competitorLogos.count { it.isNotBlank() } * 100 + if (e.leagueLogo?.isNotBlank() == true) 50 else 0 + if (e.broadcast.isNotBlank()) 15 else 0 + if (e.detail.isNotBlank()) 5 else 0
    private fun startMs(v: String): Long? = runCatching { Instant.parse(v).toEpochMilli() }.getOrElse { runCatching { java.time.OffsetDateTime.parse(v).toInstant().toEpochMilli() }.getOrNull() }
    private fun safe(v: String?): String = v.orEmpty().trim().takeIf { it.isNotBlank() && !it.equals("null", true) && !it.equals("undefined", true) }.orEmpty()
    private fun norm(v: String): String = v.lowercase().replace("&", " and ").replace(Regex("[^a-z0-9]+"), " ").trim()

    private object ScheduleMemory {
        @Volatile private var data: List<SportsEvent>? = null
        fun replace(value: List<SportsEvent>) { data = value }
        fun snapshot(): List<SportsEvent>? = data
    }
}