package com.usportz.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean

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
    private const val LIVE_LOOKBACK_DAYS = 1L
    private const val HTTP_CONNECT_MS = 2500
    private const val HTTP_READ_MS = 5000
    private const val CRITICAL_TIMEOUT_MS = 7500L
    private const val DEDICATED_TIMEOUT_MS = 3000L
    private val feedDispatcher = Dispatchers.IO.limitedParallelism(6)
    private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val backgroundRunning = AtomicBoolean(false)
    @Volatile private var cached: List<SportsEvent> = emptyList()
    @Volatile private var cachedAt = 0L
    @Volatile private var cachedDay = ""

    private val criticalFeeds = listOf(
        Feed("baseball", "mlb"), Feed("football", "college-football"), Feed("football", "nfl"), Feed("football", "cfl"), Feed("football", "ufl"),
        Feed("soccer", "usa.1"), Feed("soccer", "usa.nwsl"), Feed("soccer", "usa.usl.1"), Feed("soccer", "usa.usl.l1"), Feed("soccer", "usa.ncaa.m.1"), Feed("soccer", "usa.ncaa.w.1"),
        Feed("soccer", "eng.1"), Feed("soccer", "esp.1"), Feed("soccer", "ger.1"), Feed("soccer", "ita.1"), Feed("soccer", "uefa.champions"), Feed("soccer", "uefa.europa"),
        Feed("soccer", "conmebol.libertadores"), Feed("soccer", "conmebol.sudamericana"), Feed("soccer", "can.w.nsl"), Feed("hockey", "nhl"), Feed("hockey", "mens-college-hockey"), Feed("hockey", "womens-college-hockey"),
        Feed("basketball", "nba"), Feed("basketball", "wnba"), Feed("basketball", "fiba"), Feed("basketball", "mens-college-basketball"), Feed("basketball", "womens-college-basketball"), Feed("baseball", "college-baseball"),
        Feed("tennis", "atp"), Feed("tennis", "wta"), Feed("golf", "pga"), Feed("golf", "lpga"), Feed("golf", "liv"), Feed("racing", "f1"), Feed("racing", "nascar-premier"), Feed("mma", "ufc"), Feed("boxing", "boxing")
    ).distinct()
    private val feeds = listOf(*criticalFeeds.toTypedArray(), Feed("soccer", "eng.2"), Feed("soccer", "fra.1"), Feed("soccer", "ned.1"), Feed("soccer", "por.1"), Feed("soccer", "sco.1"), Feed("soccer", "mex.1"), Feed("soccer", "mex.2"), Feed("soccer", "fifa.world"), Feed("soccer", "fifa.wwc"), Feed("soccer", "fifa.world.u20"), Feed("golf", "eur"), Feed("golf", "champions-tour"), Feed("golf", "ntw"), Feed("racing", "irl"), Feed("racing", "nascar-secondary"), Feed("racing", "nascar-truck")).distinct()

    suspend fun load(forceRefresh: Boolean = false, sourceChannels: List<SportsChannel> = emptyList()): List<SportsEvent> = loadInternal(forceRefresh, sourceChannels)
    suspend fun load(@Suppress("UNUSED_PARAMETER") context: android.content.Context, forceRefresh: Boolean = false, sourceChannels: List<SportsChannel> = emptyList()): List<SportsEvent> = loadInternal(forceRefresh, sourceChannels)

    private suspend fun loadInternal(forceRefresh: Boolean, sourceChannels: List<SportsChannel>): List<SportsEvent> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis(); val today = LocalDate.now(); val last = today.plusDays(LOOKAHEAD_DAYS)
        val source = if (sourceChannels.isNotEmpty()) sourceChannels else SportsChannelBridge.cachedChannels()
        if (!forceRefresh && cached.isNotEmpty() && cachedDay == today.toString() && now - cachedAt < CACHE_TTL_MS) {
            launchBackgroundRefresh(today, last)
            return@withContext prioritizeSourceMatches(cached.map { refreshLiveState(it, now) }, source)
        }
        val fast = coroutineScope {
            val official = async { withTimeoutOrNull(CRITICAL_TIMEOUT_MS) { runCatching { OfficialScheduleProviders.load(today, last) }.getOrDefault(emptyList()) }.orEmpty() }
            val critical = async { criticalFeeds.map { feed -> async(feedDispatcher) { withTimeoutOrNull(CRITICAL_TIMEOUT_MS) { fetchFeed(feed, today, last, true) }.orEmpty() } }.awaitAll().flatten() }
            val dedicated = async { withTimeoutOrNull(DEDICATED_TIMEOUT_MS) { runCatching { DedicatedSchedule.load() }.getOrDefault(emptyList()) }.orEmpty() }
            val monster = async { withTimeoutOrNull(1500L) { runCatching { MonsterJamSchedule.load(today, last) }.getOrDefault(emptyList()) }.orEmpty() }
            Triple(official.await(), critical.await(), dedicated.await() + monster.await())
        }
        val incoming = sanitizeWindow(fast.first + fast.second + fast.third, now)
        val merged = mergeAndCache(incoming, today, last, now)
        launchBackgroundRefresh(today, last)
        prioritizeSourceMatches(merged, source)
    }

    private fun launchBackgroundRefresh(today: LocalDate, last: LocalDate) {
        if (!backgroundRunning.compareAndSet(false, true)) return
        refreshScope.launch {
            try {
                val missing = feeds.filterNot { criticalFeeds.contains(it) }
                val extra = missing.map { feed -> async(feedDispatcher) { withTimeoutOrNull(5500L) { fetchFeed(feed, today, last, false) }.orEmpty() } }.awaitAll().flatten()
                val dedicated = withTimeoutOrNull(2500L) { runCatching { DedicatedSchedule.load() }.getOrDefault(emptyList()) }.orEmpty()
                val monster = withTimeoutOrNull(1500L) { runCatching { MonsterJamSchedule.load(today, last) }.getOrDefault(emptyList()) }.orEmpty()
                val now = System.currentTimeMillis(); val incoming = sanitizeWindow(extra + dedicated + monster, now)
                if (incoming.isNotEmpty()) mergeAndCache(incoming, today, last, now)
            } finally { backgroundRunning.set(false) }
        }
    }

    private fun mergeAndCache(incoming: List<SportsEvent>, today: LocalDate, last: LocalDate, now: Long): List<SportsEvent> {
        val retained = cached.filter { event ->
            val state = refreshLiveState(event, now).state; val day = localDate(event.startTime)
            (day != null && !day.isBefore(today) && !day.isAfter(last)) || (day == today.minusDays(LIVE_LOOKBACK_DAYS) && state == "in")
        }
        val fresh = dedupeBest((incoming + retained).map { refreshLiveState(it, now) })
            .sortedWith(compareByDescending<SportsEvent> { it.state == "in" }.thenBy { startEpochMs(it.startTime) ?: Long.MAX_VALUE }).take(2500)
        if (fresh.isNotEmpty()) { cached = fresh; cachedAt = now; cachedDay = today.toString() }
        return if (fresh.isNotEmpty()) fresh else cached
    }

    /** One canonical dedupe path for ESPN, official, dedicated and cached feeds. */
    private fun dedupeBest(events: List<SportsEvent>): List<SportsEvent> = SportsFeedMerger.merge(events, 2500)

    private fun fetchFeed(feed: Feed, today: LocalDate, last: LocalDate, critical: Boolean): List<SportsEvent> {
        val todayKey = today.format(DateTimeFormatter.BASIC_ISO_DATE); val yesterdayKey = today.minusDays(LIVE_LOOKBACK_DAYS).format(DateTimeFormatter.BASIC_ISO_DATE); val range = "$todayKey-${last.format(DateTimeFormatter.BASIC_ISO_DATE)}"
        val base = "https://site.api.espn.com/apis/site/v2/sports/${feed.sport}/${feed.league}/scoreboard"
        val urls = if (critical) buildList {
            add("$base?dates=$todayKey&limit=500"); add("$base?dates=$yesterdayKey&limit=500")
            if (feed.league == "college-football") { add("$base?dates=$todayKey&limit=500&groups=80"); add("$base?dates=$yesterdayKey&limit=500&groups=80") }
            add("https://site.api.espn.com/apis/site/v3/sports/${feed.sport}/${feed.league}/scoreboard?dates=$todayKey&limit=500")
        } else listOf("$base?dates=$range&limit=500", "$base?dates=$todayKey&limit=500")
        val collected = ArrayList<SportsEvent>(); for (url in urls.distinct()) { val events = fetchJson(url, feed); if (events.isNotEmpty()) collected += events }
        return dedupeBest(collected)
    }

    private fun fetchJson(url: String, feed: Feed): List<SportsEvent> = runCatching {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = HTTP_CONNECT_MS; connection.readTimeout = HTTP_READ_MS; connection.requestMethod = "GET"; connection.instanceFollowRedirects = true; connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json,text/plain,*/*"); connection.setRequestProperty("Cache-Control", "no-cache"); connection.setRequestProperty("User-Agent", "USPortz/1.6 Android")
            if (connection.responseCode !in 200..299) return emptyList()
            parseEspn(connection.inputStream.bufferedReader().use { it.readText() }, feed)
        } finally { connection.disconnect() }
    }.getOrDefault(emptyList())

    private fun parseEspn(body: String, feed: Feed): List<SportsEvent> = runCatching {
        val root = JSONObject(body); val events = root.optJSONArray("events") ?: return emptyList(); val rootLeagueLogo = safe(root.optJSONArray("leagues")?.optJSONObject(0)?.optJSONArray("logos")?.optJSONObject(0)?.optString("href")); val fallback = BrandAssets.logoUrl(SportsBranding.find("", feed.league))
        buildList {
            for (i in 0 until events.length()) {
                val event = events.optJSONObject(i) ?: continue; val competition = event.optJSONArray("competitions")?.optJSONObject(0) ?: continue; val competitors = competition.optJSONArray("competitors") ?: continue
                val names = ArrayList<String>(); val logos = ArrayList<String>()
                for (j in 0 until competitors.length()) { val team = competitors.optJSONObject(j)?.optJSONObject("team") ?: continue; val name = safe(team.optString("displayName")).ifBlank { safe(team.optString("shortDisplayName")) }; if (name.isNotBlank()) { names += name; logos += safe(team.optString("logo")).ifBlank { safe(team.optJSONArray("logos")?.optJSONObject(0)?.optString("href")) } } }
                val status = competition.optJSONObject("status")?.optJSONObject("type"); val rawName = safe(event.optString("name")); val displayLeague = SportsBranding.label(rawName, feed.league); val broadcastObj = competition.optJSONArray("broadcasts")?.optJSONObject(0); val broadcast = safe(broadcastObj?.optString("names")).ifBlank { safe(broadcastObj?.optString("market")) }; val eventLogo = safe(event.optJSONArray("logos")?.optJSONObject(0)?.optString("href"))
                add(SportsEvent(safe(event.optString("id")).ifBlank { "espn:${feed.sport}:${feed.league}:$i:${safe(event.optString("date"))}" }, feed.sport, displayLeague, rawName, safe(event.optString("shortDisplayName")).ifBlank { safe(event.optString("shortName")) }, normalizeState(safe(status?.optString("state")), safe(status?.optString("name"))), safe(event.optString("date")), names, logos, eventLogo.ifBlank { rootLeagueLogo }.ifBlank { fallback }, safe(status?.optString("detail")).ifBlank { safe(status?.optString("shortDetail")) }, broadcast))
            }
        }
    }.getOrDefault(emptyList())

    private fun normalizeState(state: String, name: String): String { val s = state.lowercase(); val n = name.lowercase(); return when { s == "in" || s == "live" || n.contains("in progress") || n.contains("live") || n == "halftime" -> "in"; s == "post" || n.contains("final") || n.contains("completed") -> "post"; else -> "pre" } }

    private fun sanitizeWindow(events: List<SportsEvent>, now: Long): List<SportsEvent> {
        val today = LocalDate.now(); val last = today.plusDays(LOOKAHEAD_DAYS)
        return events.mapNotNull { event -> val start = startEpochMs(event.startTime) ?: return@mapNotNull null; val day = Instant.ofEpochMilli(start).atZone(ZoneId.systemDefault()).toLocalDate(); val state = refreshLiveState(event, now).state; if ((day >= today && day <= last) || (day == today.minusDays(LIVE_LOOKBACK_DAYS) && state == "in")) event else null }
    }

    // Existing helper implementations below remain unchanged in behavior.
    private fun safe(value: String?): String = value.orEmpty().trim().takeIf { it.isNotBlank() && !it.equals("null", true) && !it.equals("undefined", true) }.orEmpty()
    private fun localDate(value: String): LocalDate? = runCatching { Instant.parse(value).atZone(ZoneId.systemDefault()).toLocalDate() }.getOrNull()
    private fun startEpochMs(value: String): Long? = runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
    private fun refreshLiveState(event: SportsEvent, now: Long): SportsEvent = event
    private fun prioritizeSourceMatches(events: List<SportsEvent>, @Suppress("UNUSED_PARAMETER") source: List<SportsChannel>): List<SportsEvent> = events
}
