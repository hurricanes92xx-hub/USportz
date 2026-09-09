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
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Fast, multi-provider sports schedule engine.
 *
 * ESPN is the primary broad scoreboard source. TheSportsDB is a secondary source for sports that
 * ESPN does not expose consistently (boxing, wrestling, motorsport, golf and tennis). Results are
 * merged by stable event identity, cached in memory for two minutes, and never replaced by an
 * empty provider response.
 */
data class SportsEvent(
    val id: String,
    val sport: String,
    val league: String,
    val name: String,
    val shortName: String,
    val state: String,
    val startTime: String,
    val competitors: List<String>,
    val competitorLogos: List<String>,
    val leagueLogo: String?,
    val detail: String,
    val broadcast: String = ""
)

object SportsSchedule {
    private data class Feed(val sport: String, val league: String)
    private const val CACHE_TTL_MS = 2 * 60 * 1000L
    private const val LOOKAHEAD_DAYS = 7L
    private const val HTTP_CONNECT_MS = 4500
    private const val HTTP_READ_MS = 9000

    @Volatile private var cached: List<SportsEvent> = emptyList()
    @Volatile private var cachedAt = 0L
    @Volatile private var cachedDay = ""

    // Validated public ESPN league slugs. Racing was previously using several invalid slugs.
    private val feeds = listOf(
        Feed("football", "nfl"), Feed("football", "college-football"), Feed("football", "cfl"), Feed("football", "ufl"),
        Feed("basketball", "nba"), Feed("basketball", "wnba"), Feed("basketball", "mens-college-basketball"), Feed("basketball", "womens-college-basketball"),
        Feed("baseball", "mlb"), Feed("baseball", "college-baseball"),
        Feed("hockey", "nhl"), Feed("hockey", "mens-college-hockey"), Feed("hockey", "womens-college-hockey"),
        Feed("soccer", "usa.1"), Feed("soccer", "usa.nwsl"), Feed("soccer", "eng.1"), Feed("soccer", "eng.2"),
        Feed("soccer", "esp.1"), Feed("soccer", "ger.1"), Feed("soccer", "ita.1"), Feed("soccer", "fra.1"), Feed("soccer", "ned.1"),
        Feed("soccer", "por.1"), Feed("soccer", "sco.1"), Feed("soccer", "uefa.champions"), Feed("soccer", "uefa.europa"),
        Feed("soccer", "conmebol.libertadores"), Feed("soccer", "conmebol.sudamericana"), Feed("soccer", "fifa.world"),
        Feed("mma", "ufc"), Feed("boxing", "boxing"),
        Feed("golf", "pga"), Feed("golf", "lpga"),
        Feed("tennis", "atp"), Feed("tennis", "wta"),
        Feed("racing", "f1"), Feed("racing", "irl"), Feed("racing", "nascar-premier"), Feed("racing", "nascar-secondary"), Feed("racing", "nascar-truck")
    )

    suspend fun load(forceRefresh: Boolean = false, sourceChannels: List<SportsChannel> = emptyList()): List<SportsEvent> =
        loadInternal(forceRefresh, sourceChannels)

    suspend fun load(@Suppress("UNUSED_PARAMETER") context: android.content.Context, forceRefresh: Boolean = false, sourceChannels: List<SportsChannel> = emptyList()): List<SportsEvent> =
        loadInternal(forceRefresh, sourceChannels)

    private suspend fun loadInternal(forceRefresh: Boolean, sourceChannels: List<SportsChannel>): List<SportsEvent> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val today = LocalDate.now().toString()
        val source = if (sourceChannels.isNotEmpty()) sourceChannels else SportsChannelBridge.cachedChannels()

        if (!forceRefresh && cached.isNotEmpty() && cachedDay == today && now - cachedAt < CACHE_TTL_MS) {
            return@withContext prioritizeSourceMatches(cached, source)
        }

        val (espn, dedicated) = coroutineScope {
            val espnJob = async(Dispatchers.IO) {
                feeds.map { feed -> async(Dispatchers.IO) { fetchFeed(feed) } }.awaitAll().flatten()
            }
            val dedicatedJob = async(Dispatchers.IO) { DedicatedSchedule.load() }
            espnJob.await() to dedicatedJob.await()
        }

        val fresh = sanitizeWindow((espn + dedicated).distinctBy { canonicalId(it) }, now)
            .sortedWith(compareByDescending<SportsEvent> { it.state == "in" }.thenBy { startEpochMs(it.startTime) ?: Long.MAX_VALUE })
            .take(2500)

        if (fresh.isNotEmpty()) {
            cached = fresh
            cachedAt = now
            cachedDay = today
        } else if (cachedDay != today) {
            cached = emptyList()
            cachedAt = 0L
            cachedDay = today
        }

        prioritizeSourceMatches(cached, source)
    }

    fun isCacheFresh(): Boolean = cached.isNotEmpty() && cachedDay == LocalDate.now().toString() && System.currentTimeMillis() - cachedAt < CACHE_TTL_MS
    fun lastUpdatedEpochMs(): Long = cachedAt
    fun liveEvents(events: List<SportsEvent>): List<SportsEvent> = events.filter { it.state == "in" }
    fun upcomingEvents(events: List<SportsEvent>): List<SportsEvent> = events.filter {
        it.state != "post" && (it.state == "in" || startEpochMs(it.startTime)?.let { time -> time > System.currentTimeMillis() } == true)
    }
    fun isToday(event: SportsEvent): Boolean = localDate(event.startTime) == LocalDate.now()

    fun forSport(events: List<SportsEvent>, sport: String): List<SportsEvent> =
        if (sport.isBlank() || sport == "All") events else events.filter {
            SportsCatalog.classify(it.name, it.league) == sport || it.sport.equals(sport, true)
        }

    private fun fetchFeed(feed: Feed): List<SportsEvent> {
        val today = LocalDate.now()
        val dates = "${today.format(DateTimeFormatter.BASIC_ISO_DATE)}-${today.plusDays(LOOKAHEAD_DAYS).format(DateTimeFormatter.BASIC_ISO_DATE)}"
        val base = "https://site.api.espn.com/apis/site/v2/sports/${feed.sport}/${feed.league}/scoreboard"
        val urls = listOf(
            "$base?dates=$dates",
            "$base?dates=${today.format(DateTimeFormatter.BASIC_ISO_DATE)}",
            "https://site.api.espn.com/apis/site/v3/sports/${feed.sport}/${feed.league}/scoreboard?dates=$dates"
        )
        for (url in urls) {
            val events = fetchJson(url, feed)
            if (events.isNotEmpty()) return events
        }
        return emptyList()
    }

    private fun fetchJson(url: String, feed: Feed): List<SportsEvent> = runCatching {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = HTTP_CONNECT_MS
            connection.readTimeout = HTTP_READ_MS
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "USportz/1.1")
            if (connection.responseCode !in 200..299) return emptyList()
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            parseEspn(body, feed)
        } finally {
            connection.disconnect()
        }
    }.getOrDefault(emptyList())

    private fun parseEspn(body: String, feed: Feed): List<SportsEvent> {
        val root = JSONObject(body)
        val events = root.optJSONArray("events") ?: return emptyList()
        val rootLeagueLogo = root.optJSONArray("leagues")?.optJSONObject(0)?.optJSONArray("logos")?.optJSONObject(0)?.optString("href").orEmpty()
        return buildList {
            for (i in 0 until events.length()) {
                val event = events.optJSONObject(i) ?: continue
                val competition = event.optJSONArray("competitions")?.optJSONObject(0) ?: continue
                val competitors = competition.optJSONArray("competitors") ?: continue
                val names = ArrayList<String>(competitors.length())
                val logos = ArrayList<String>(competitors.length())
                for (j in 0 until competitors.length()) {
                    val team = competitors.optJSONObject(j)?.optJSONObject("team") ?: continue
                    val name = team.optString("displayName").ifBlank { team.optString("shortDisplayName") }
                    if (name.isNotBlank()) {
                        names += name
                        logos += team.optString("logo").ifBlank { team.optJSONArray("logos")?.optJSONObject(0)?.optString("href").orEmpty() }
                    }
                }
                val status = competition.optJSONObject("status")?.optJSONObject("type")
                val rawName = event.optString("name")
                val displayLeague = SportsBranding.label(rawName, feed.league)
                val broadcast = competition.optJSONArray("broadcasts")?.optJSONObject(0)?.optString("names").orEmpty()
                    .ifBlank { competition.optJSONArray("broadcasts")?.optJSONObject(0)?.optString("market").orEmpty() }
                val eventLogo = event.optJSONArray("logos")?.optJSONObject(0)?.optString("href").orEmpty()
                add(SportsEvent(
                    id = event.optString("id").ifBlank { "espn:${feed.sport}:${feed.league}:$i:${event.optString("date")}" },
                    sport = feed.sport,
                    league = displayLeague,
                    name = rawName,
                    shortName = event.optString("shortName"),
                    state = normalizeState(status?.optString("state").orEmpty(), status?.optString("name").orEmpty()),
                    startTime = event.optString("date"),
                    competitors = names,
                    competitorLogos = logos,
                    leagueLogo = eventLogo.ifBlank { rootLeagueLogo }.ifBlank { null },
                    detail = status?.optString("detail").orEmpty().ifBlank { status?.optString("shortDetail").orEmpty() },
                    broadcast = broadcast
                ))
            }
        }
    }

    private fun normalizeState(state: String, name: String): String {
        val s = state.lowercase()
        val n = name.lowercase()
        return when {
            s == "in" || n.contains("in progress") || n.contains("live") || n == "halftime" -> "in"
            s == "post" || n.contains("final") || n.contains("completed") -> "post"
            else -> "pre"
        }
    }

    private fun sanitizeWindow(events: List<SportsEvent>, now: Long): List<SportsEvent> {
        val today = LocalDate.now()
        val lastDate = today.plusDays(LOOKAHEAD_DAYS)
        return events.mapNotNull { event ->
            val start = startEpochMs(event.startTime)
            val localDay = start?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() }
            val isLive = event.state == "in"
            val inWindow = isLive || (localDay != null && !localDay.isBefore(today) && !localDay.isAfter(lastDate))
            if (!inWindow) null
            else if (!isLive && event.state != "post" && start != null && start <= now) event.copy(state = "post") else event
        }
    }

    private fun startEpochMs(value: String): Long? = runCatching { Instant.parse(value).toEpochMilli() }.getOrElse {
        runCatching { java.time.OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrElse {
            value.toLongOrNull()?.let { raw -> if (raw < 100000000000L) raw * 1000L else raw }
        }
    }

    private fun localDate(value: String): LocalDate? = startEpochMs(value)?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() }

    private fun canonicalId(event: SportsEvent): String {
        val teams = event.competitors.map { normalize(it) }.sorted().joinToString("|")
        return "${normalize(event.league)}|$teams|${event.startTime.take(16)}".ifBlank { event.id }
    }

    private fun prioritizeSourceMatches(events: List<SportsEvent>, channels: List<SportsChannel>): List<SportsEvent> {
        if (events.isEmpty() || channels.isEmpty()) return events
        return events.asSequence().map { event -> event to sourceMatchScore(event, channels) }
            .sortedWith(compareByDescending<Pair<SportsEvent, Int>> { it.second > 0 }.thenByDescending { it.second }.thenByDescending { it.first.state == "in" }.thenBy { startEpochMs(it.first.startTime) ?: Long.MAX_VALUE })
            .map { it.first }.toList()
    }

    fun sourceMatchScore(event: SportsEvent, channels: List<SportsChannel>): Int = channels.asSequence().map { matchChannel(event, it.name, it.group) }.maxOrNull() ?: 0

    fun matchChannel(event: SportsEvent, channelName: String, group: String): Int {
        val haystack = normalize("$channelName $group")
        val eventLeague = normalize(event.league)
        val eventSport = normalize(event.sport)
        var score = 0
        if (eventLeague.isNotBlank() && haystack.contains(eventLeague)) score += 5
        if (eventSport.isNotBlank() && haystack.contains(eventSport)) score += 2
        event.competitors.forEach { team ->
            val normalized = normalize(team)
            if (normalized.length >= 4 && haystack.contains(normalized)) score += 5
            team.split(Regex("[^A-Za-z0-9]+" )).filter { it.length >= 4 }.forEach { token -> if (haystack.contains(token.lowercase())) score += 2 }
        }
        val aliases = mapOf(
            "nfl" to listOf("nfl", "football"), "nba" to listOf("nba", "basketball"), "wnba" to listOf("wnba", "basketball"),
            "mlb" to listOf("mlb", "baseball"), "nhl" to listOf("nhl", "hockey"), "ufc" to listOf("ufc", "mma"),
            "premier league" to listOf("epl", "premier league"), "mls" to listOf("mls", "soccer"),
            "ncaa football" to listOf("ncaa", "college football", "football"), "ncaa basketball" to listOf("ncaa", "college basketball", "basketball"),
            "nascar" to listOf("nascar", "cup", "xfinity", "truck"), "indycar" to listOf("indycar"), "formula 1" to listOf("f1", "formula 1", "formula one"),
            "motogp" to listOf("motogp"), "tennis" to listOf("atp", "wta", "tennis"), "boxing" to listOf("boxing", "wbc", "wba", "wbo", "ibf")
        )
        aliases[eventLeague].orEmpty().forEach { alias -> if (haystack.contains(normalize(alias))) score += 2 }
        if (event.broadcast.isNotBlank() && haystack.contains(normalize(event.broadcast))) score += 4
        return score
    }

    private fun normalize(value: String): String = value.lowercase().replace("&", " and ").replace(Regex("[^a-z0-9]+"), " ").trim()
}
