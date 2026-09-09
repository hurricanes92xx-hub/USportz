package com.usportz.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Public scoreboard integration enriched by the user's Xtream/M3U channel inventory. */
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
    @Volatile private var cached: List<SportsEvent> = emptyList()
    @Volatile private var cachedAt = 0L

    private val feeds = listOf(
        Feed("football", "nfl"), Feed("football", "college-football"),
        Feed("basketball", "nba"), Feed("basketball", "wnba"), Feed("basketball", "mens-college-basketball"), Feed("basketball", "womens-college-basketball"),
        Feed("baseball", "mlb"), Feed("baseball", "college-baseball"), Feed("hockey", "nhl"), Feed("hockey", "mens-college-hockey"),
        Feed("soccer", "usa.1"), Feed("soccer", "usa.nwsl"), Feed("soccer", "eng.1"), Feed("soccer", "eng.2"), Feed("soccer", "esp.1"),
        Feed("soccer", "ger.1"), Feed("soccer", "ita.1"), Feed("soccer", "fra.1"), Feed("soccer", "ned.1"), Feed("soccer", "por.1"),
        Feed("soccer", "sco.1"), Feed("soccer", "uefa.champions"), Feed("soccer", "uefa.europa"), Feed("soccer", "uefa.europa.conf"),
        Feed("soccer", "conmebol.libertadores"), Feed("soccer", "conmebol.sudamericana"), Feed("soccer", "fifa.world"), Feed("mma", "ufc"),
        Feed("racing", "nascar-cup-series"), Feed("racing", "nascar-xfinity-series"), Feed("racing", "nascar-truck-series"),
        Feed("racing", "formula-1"), Feed("racing", "indycar"), Feed("racing", "motogp"), Feed("tennis", "atp"), Feed("tennis", "wta")
    )

    suspend fun load(forceRefresh: Boolean = false, sourceChannels: List<SportsChannel> = emptyList()): List<SportsEvent> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val source = if (sourceChannels.isNotEmpty()) sourceChannels else SportsChannelBridge.cachedChannels()
        if (!forceRefresh && cached.isNotEmpty() && now - cachedAt < CACHE_TTL_MS) return@withContext prioritizeSourceMatches(cached, source)

        val (espn, dedicated) = coroutineScope {
            val espnJob = async(Dispatchers.IO) {
                feeds.map { feed -> async(Dispatchers.IO) { fetch(feed) } }.awaitAll().flatten()
            }
            val dedicatedJob = async(Dispatchers.IO) { DedicatedSchedule.load() }
            espnJob.await() to dedicatedJob.await()
        }
        val fresh = (espn + dedicated)
            .distinctBy { it.id }
            .sortedWith(compareByDescending<SportsEvent> { it.state == "in" }.thenBy { it.startTime })
            .take(800)

        if (fresh.isNotEmpty()) {
            cached = fresh
            cachedAt = now
            prioritizeSourceMatches(fresh, source)
        } else {
            prioritizeSourceMatches(cached, source)
        }
    }

    fun isCacheFresh(): Boolean = cached.isNotEmpty() && System.currentTimeMillis() - cachedAt < CACHE_TTL_MS
    fun lastUpdatedEpochMs(): Long = cachedAt
    fun liveEvents(events: List<SportsEvent>): List<SportsEvent> = events.filter { it.state == "in" }
    fun upcomingEvents(events: List<SportsEvent>): List<SportsEvent> = events.filter { it.state != "in" && it.state != "post" }
    fun forSport(events: List<SportsEvent>, sport: String): List<SportsEvent> = if (sport.isBlank() || sport == "All") events else events.filter { SportsCatalog.classify(it.name, it.league) == sport || it.sport.equals(sport, true) }

    private fun prioritizeSourceMatches(events: List<SportsEvent>, channels: List<SportsChannel>): List<SportsEvent> {
        if (events.isEmpty() || channels.isEmpty()) return events
        return events.asSequence().map { event -> event to sourceMatchScore(event, channels) }
            .sortedWith(compareByDescending<Pair<SportsEvent, Int>> { it.second > 0 }.thenByDescending { it.second }.thenByDescending { it.first.state == "in" }.thenBy { it.first.startTime })
            .map { it.first }.toList()
    }

    fun sourceMatchScore(event: SportsEvent, channels: List<SportsChannel>): Int = channels.asSequence().map { matchChannel(event, it.name, it.group) }.maxOrNull() ?: 0

    private fun fetch(feed: Feed): List<SportsEvent> {
        val url = "https://site.api.espn.com/apis/site/v2/sports/${feed.sport}/${feed.league}/scoreboard"
        return runCatching {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 5000; connection.readTimeout = 7000; connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "USportz/1.0")
            val body = connection.inputStream.bufferedReader().use { it.readText() }; connection.disconnect()
            val root = JSONObject(body); val events = root.optJSONArray("events") ?: return emptyList()
            val rootLeagueLogo = root.optJSONArray("leagues")?.optJSONObject(0)?.optJSONArray("logos")?.optJSONObject(0)?.optString("href").orEmpty()
            buildList {
                for (i in 0 until events.length()) {
                    val event = events.optJSONObject(i) ?: continue
                    val competition = event.optJSONArray("competitions")?.optJSONObject(0) ?: continue
                    val competitors = competition.optJSONArray("competitors") ?: continue
                    val names = ArrayList<String>(competitors.length()); val logos = ArrayList<String>(competitors.length())
                    for (j in 0 until competitors.length()) {
                        val team = competitors.optJSONObject(j)?.optJSONObject("team") ?: continue
                        val name = team.optString("displayName")
                        if (name.isNotBlank()) { names += name; logos += team.optString("logo").ifBlank { team.optJSONArray("logos")?.optJSONObject(0)?.optString("href").orEmpty() } }
                    }
                    val status = competition.optJSONObject("status")?.optJSONObject("type")
                    val rawName = event.optString("name"); val displayLeague = SportsBranding.label(rawName, feed.league)
                    val broadcast = competition.optJSONArray("broadcasts")?.optJSONObject(0)?.optString("names").orEmpty().ifBlank { competition.optJSONArray("broadcasts")?.optJSONObject(0)?.optString("market").orEmpty() }
                    val eventLogo = event.optJSONArray("logos")?.optJSONObject(0)?.optString("href").orEmpty()
                    add(SportsEvent(event.optString("id"), feed.sport, displayLeague, rawName, event.optString("shortName"), status?.optString("state").orEmpty(), event.optString("date"), names, logos, eventLogo.ifBlank { rootLeagueLogo }.ifBlank { null }, status?.optString("detail").orEmpty(), broadcast))
                }
            }
        }.getOrDefault(emptyList())
    }

    fun matchChannel(event: SportsEvent, channelName: String, group: String): Int {
        val haystack = normalize("$channelName $group"); val eventLeague = normalize(event.league); val eventSport = normalize(event.sport); var score = 0
        if (eventLeague.isNotBlank() && haystack.contains(eventLeague)) score += 5
        if (eventSport.isNotBlank() && haystack.contains(eventSport)) score += 2
        event.competitors.forEach { team -> val normalized = normalize(team); if (normalized.length >= 4 && haystack.contains(normalized)) score += 5; team.split(Regex("[^A-Za-z0-9]+" )).filter { it.length >= 4 }.forEach { token -> if (haystack.contains(token.lowercase())) score += 2 } }
        val aliases = mapOf("nfl" to listOf("nfl", "football"), "nba" to listOf("nba", "basketball"), "wnba" to listOf("wnba", "basketball"), "mlb" to listOf("mlb", "baseball"), "nhl" to listOf("nhl", "hockey"), "ufc" to listOf("ufc", "mma"), "premier league" to listOf("epl", "premier league"), "mls" to listOf("mls", "soccer"), "ncaa football" to listOf("ncaa", "college football", "football"), "ncaa basketball" to listOf("ncaa", "college basketball", "basketball"), "nascar" to listOf("nascar", "cup", "xfinity", "truck"), "indycar" to listOf("indycar"), "f1" to listOf("f1", "formula 1", "formula one"), "motogp" to listOf("motogp"), "tennis" to listOf("atp", "wta", "tennis"))
        aliases[eventLeague].orEmpty().forEach { alias -> if (haystack.contains(normalize(alias))) score += 2 }
        if (event.broadcast.isNotBlank() && haystack.contains(normalize(event.broadcast))) score += 4
        return score
    }

    private fun normalize(value: String): String = value.lowercase().replace("&", " and ").replace(Regex("[^a-z0-9]+"), " ").trim()
}
