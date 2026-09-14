package com.usportz.app

import android.content.Context
import android.util.Xml
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** Sprint 4: XMLTV sports EPG with visible-first Now/Next, matching and stale retention. */
data class EpgProgram(
    val channelId: String,
    val title: String,
    val description: String,
    val startMs: Long,
    val endMs: Long,
    val category: String = "",
    val icon: String? = null
) {
    fun isNow(atMs: Long = System.currentTimeMillis()): Boolean = startMs <= atMs && atMs < endMs
    fun isNext(atMs: Long = System.currentTimeMillis()): Boolean = startMs > atMs
}

data class EpgNowNext(val channelId: String, val now: EpgProgram?, val next: EpgProgram?)
data class EpgMatch(val program: EpgProgram, val score: Int, val reasons: List<String>)

object SportsEpg {
    private const val MAX_PROGRAMS = 8000
    private const val MAX_VISIBLE_CHANNELS = 96
    private const val CONNECT_MS = 4500
    private const val READ_MS = 15000
    private const val CACHE_TTL_MS = 15 * 60 * 1000L
    private const val MAX_STALE_MS = 6 * 60 * 60 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshing = AtomicBoolean(false)
    private val lock = Any()
    private var programs: List<EpgProgram> = emptyList()
    private var cachedAt = 0L
    private var sourceKey = ""

    suspend fun load(context: Context, visibleChannels: List<SportsChannel> = emptyList(), forceRefresh: Boolean = false): List<EpgProgram> = withContext(Dispatchers.IO) {
        val source = SourceStore(context)
        val key = sourceKey(source)
        val now = System.currentTimeMillis()
        synchronized(lock) {
            if (!forceRefresh && programs.isNotEmpty() && sourceKey == key && now - cachedAt < CACHE_TTL_MS) {
                launchRefresh(context, visibleChannels, key)
                return@withContext visibleFirst(programs, visibleChannels)
            }
        }
        val fresh = fetchXmlTv(source.server, source.user, source.pass, source.playlist, visibleChannels)
        if (fresh.isNotEmpty()) {
            synchronized(lock) { programs = fresh.take(MAX_PROGRAMS); cachedAt = now; sourceKey = key }
            visibleFirst(fresh, visibleChannels)
        } else {
            val stale = synchronized(lock) { if (sourceKey == key && now - cachedAt <= MAX_STALE_MS) programs else emptyList() }
            launchRefresh(context, visibleChannels, key)
            visibleFirst(stale, visibleChannels)
        }
    }

    fun nowNext(channel: SportsChannel, atMs: Long = System.currentTimeMillis()): EpgNowNext {
        val ids = normalizedIds(channel)
        val relevant = synchronized(lock) { programs.filter { normalizeId(it.channelId) in ids } }
        return EpgNowNext(channel.tvgId.ifBlank { channel.id }, relevant.filter { it.isNow(atMs) }.maxByOrNull { it.startMs }, relevant.filter { it.isNext(atMs) }.minByOrNull { it.startMs })
    }

    fun eventMatches(event: SportsEvent, channel: SportsChannel, limit: Int = 5): List<EpgMatch> {
        val now = System.currentTimeMillis()
        val ids = normalizedIds(channel)
        val candidates = synchronized(lock) { programs.filter { normalizeId(it.channelId) in ids && it.endMs >= now - 30 * 60 * 1000L && it.startMs <= now + 7 * 24 * 60 * 60 * 1000L } }
        val teamTerms = event.competitors.map(::normalize).filter { it.length >= 3 }
        val league = normalize(event.league)
        val broadcast = normalize(event.broadcast)
        return candidates.map { p ->
            val text = normalize("${p.title} ${p.description} ${p.category}")
            var score = 0
            val reasons = ArrayList<String>()
            val teams = teamTerms.count { it in text }
            if (teams > 0) { score += teams * 35; reasons += "$teams team match" }
            if (league.isNotBlank() && (league in text || normalize(event.sport) in text)) { score += 22; reasons += "league/sport" }
            if (broadcast.isNotBlank() && broadcast in text) { score += 25; reasons += "broadcaster" }
            if (p.isNow(now)) { score += 18; reasons += "now" } else if (p.startMs > now) score += 8
            EpgMatch(p, score, reasons)
        }.filter { it.score >= 20 }.sortedByDescending { it.score }.take(limit.coerceIn(1, 16))
    }

    fun broadcasterMatches(channel: SportsChannel, broadcaster: String, limit: Int = 5): List<EpgProgram> {
        val needle = normalize(broadcaster)
        if (needle.isBlank()) return emptyList()
        val ids = normalizedIds(channel)
        return synchronized(lock) { programs.filter { normalizeId(it.channelId) in ids && (needle in normalize(it.title) || needle in normalize(it.description) || needle in normalize(it.category)) }.sortedBy { it.startMs }.take(limit) }
    }

    fun isStale(): Boolean = synchronized(lock) { programs.isEmpty() || System.currentTimeMillis() - cachedAt >= CACHE_TTL_MS }
    fun cachedAt(): Long = synchronized(lock) { cachedAt }

    private fun launchRefresh(context: Context, visible: List<SportsChannel>, key: String) {
        if (!refreshing.compareAndSet(false, true)) return
        scope.launch {
            try {
                val source = SourceStore(context)
                val fresh = fetchXmlTv(source.server, source.user, source.pass, source.playlist, visible)
                if (fresh.isNotEmpty()) synchronized(lock) { programs = fresh.take(MAX_PROGRAMS); cachedAt = System.currentTimeMillis(); sourceKey = key }
            } finally { refreshing.set(false) }
        }
    }

    private fun fetchXmlTv(server: String, user: String, pass: String, playlist: String, visible: List<SportsChannel>): List<EpgProgram> {
        val bases = SportsChannelBridge.normalizeXtreamServer(server).let { if (it.isBlank()) emptyList() else listOf(it) }
        val urls = ArrayList<String>()
        if (user.isNotBlank() && pass.isNotBlank()) for (base in bases) urls += "$base/xmltv.php?username=${enc(user)}&password=${enc(pass)}"
        if (playlist.isNotBlank()) urls += playlist.takeIf { it.contains("xmltv", true) || it.contains("epg", true) }.orEmpty()
        for (url in urls.distinct()) {
            val parsed = runCatching { open(url) { parseXmlTv(it, visible) } }.getOrNull().orEmpty()
            if (parsed.isNotEmpty()) return parsed
        }
        return emptyList()
    }

    private fun open(url: String, block: (InputStream) -> List<EpgProgram>): List<EpgProgram> {
        val c = URL(url).openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = CONNECT_MS; c.readTimeout = READ_MS; c.instanceFollowRedirects = true; c.requestMethod = "GET"
            c.setRequestProperty("Accept", "application/xml,text/xml,*/*"); c.setRequestProperty("Accept-Encoding", "gzip"); c.setRequestProperty("User-Agent", "USPortz/1.9")
            if (c.responseCode !in 200..299) emptyList() else block(c.inputStream)
        } finally { c.disconnect() }
    }

    private fun parseXmlTv(input: InputStream, visible: List<SportsChannel>): List<EpgProgram> {
        val parser = Xml.newPullParser(); parser.setInput(input, "UTF-8")
        val out = ArrayList<EpgProgram>(); var event = parser.eventType
        var channel = ""; var title = ""; var desc = ""; var category = ""; var icon: String? = null; var start = 0L; var end = 0L; var inProgramme = false; var field = ""
        while (event != XmlPullParser.END_DOCUMENT && out.size < MAX_PROGRAMS * 2) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name.lowercase(Locale.US)) {
                    "programme" -> { inProgramme = true; channel = parser.getAttributeValue(null, "channel").orEmpty(); start = parseXmlTvTime(parser.getAttributeValue(null, "start")); end = parseXmlTvTime(parser.getAttributeValue(null, "stop")); title = ""; desc = ""; category = ""; icon = null }
                    "title", "desc", "category" -> if (inProgramme) field = parser.name.lowercase(Locale.US)
                    "icon" -> if (inProgramme) icon = parser.getAttributeValue(null, "src")
                }
                XmlPullParser.TEXT -> if (inProgramme) when (field) { "title" -> title += parser.text; "desc" -> desc += parser.text; "category" -> category += parser.text }
                XmlPullParser.END_TAG -> if (parser.name.equals("programme", true)) {
                    if (channel.isNotBlank() && title.isNotBlank() && end > start) out += EpgProgram(normalizeId(channel), clean(title), clean(desc), start, end, clean(category), icon)
                    inProgramme = false; field = ""
                } else if (parser.name.equals(field, true)) field = ""
            }
            event = parser.next()
        }
        return visibleFirst(out, visible)
    }

    private fun visibleFirst(all: List<EpgProgram>, visible: List<SportsChannel>): List<EpgProgram> {
        if (visible.isEmpty()) return all.sortedBy { it.startMs }.take(MAX_PROGRAMS)
        val priority = visible.take(MAX_VISIBLE_CHANNELS).flatMap(::normalizedIds).toSet()
        return all.sortedWith(compareBy<EpgProgram> { if (normalizeId(it.channelId) in priority) 0 else 1 }.thenBy { it.startMs }).take(MAX_PROGRAMS)
    }

    private fun normalizedIds(channel: SportsChannel): Set<String> = setOf(channel.tvgId, channel.tvgName, channel.name, channel.id).map(::normalizeId).filter { it.isNotBlank() }.toSet()
    private fun normalizeId(value: String): String = normalize(value).replace(" ", "-")
    private fun normalize(value: String): String = value.lowercase(Locale.US).replace("&", " and ").replace(Regex("[^a-z0-9]+"), " ").trim().replace(Regex("\\s+"), " ")
    private fun clean(value: String): String = value.replace(Regex("\\s+"), " ").trim()
    private fun enc(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

    private fun parseXmlTvTime(raw: String?): Long {
        val text = raw.orEmpty().trim(); if (text.isBlank()) return 0L
        return runCatching {
            val base = text.substring(0, 14)
            val year = base.substring(0, 4).toInt(); val month = base.substring(4, 6).toInt(); val day = base.substring(6, 8).toInt(); val hour = base.substring(8, 10).toInt(); val minute = base.substring(10, 12).toInt(); val second = base.substring(12, 14).toInt()
            val zoneText = text.drop(14).trim()
            val zone = if (zoneText.matches(Regex("[+-]\\d{4}"))) java.time.ZoneOffset.of(zoneText.substring(0, 3) + ":" + zoneText.substring(3)) else ZoneId.systemDefault().rules.getOffset(Instant.now())
            java.time.LocalDateTime.of(year, month, day, hour, minute, second).atOffset(zone).toInstant().toEpochMilli()
        }.getOrElse { runCatching { Instant.from(DateTimeFormatter.ISO_INSTANT.parse(text)).toEpochMilli() }.getOrDefault(0L) }
    }

    private fun sourceKey(s: SourceStore): String = listOf(s.server, s.user, s.pass, s.playlist).joinToString("\u0000").hashCode().toString()
}
