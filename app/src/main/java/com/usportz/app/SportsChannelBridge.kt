package com.usportz.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/** Reliable channel source with cached-first startup and fast indexed event matching. */
data class SportsChannel(
    val id: String,
    val name: String,
    val group: String,
    val logo: String?,
    val url: String
)

object SportsChannelBridge {
    private const val CACHE_TTL_MS = 5 * 60 * 1000L
    private const val MAX_STALE_MS = 7 * 24 * 60 * 60 * 1000L
    private const val CACHE_FILE = "channel-index.json"
    @Volatile private var cached: List<SportsChannel> = emptyList()
    @Volatile private var cachedAt = 0L
    @Volatile private var cachedSourceKey = ""
    @Volatile private var channelIndex: ChannelIndex<SportsChannel>? = null

    fun restoreCached(context: Context): List<SportsChannel> {
        if (cached.isNotEmpty()) return cached
        val file = File(context.noBackupFilesDir, CACHE_FILE)
        val restored = runCatching {
            if (!file.exists()) return@runCatching emptyList()
            val root = JSONObject(file.readText())
            val array = root.optJSONArray("channels") ?: JSONArray()
            cachedSourceKey = root.optString("sourceKey")
            cachedAt = root.optLong("savedAt", file.lastModified())
            buildList(array.length()) {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val url = item.optString("url")
                    if (url.isBlank()) continue
                    add(SportsChannel(item.optString("id"), item.optString("name", "Channel"), item.optString("group", "Live TV"), item.optString("logo").ifBlank { null }, url))
                }
            }
        }.getOrElse { runCatching { parseCachedArray(file.readText()) }.getOrDefault(emptyList()) }
        if (restored.isNotEmpty()) {
            cached = restored
            channelIndex = ChannelIndex(restored, SportsChannel::name, SportsChannel::group)
        }
        return cached
    }

    suspend fun load(context: Context, forceRefresh: Boolean = false): List<SportsChannel> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (cached.isEmpty()) restoreCached(context)

        val sourceConfig = SourceStore(context)
        val server = sourceConfig.server.trimEnd('/')
        val user = sourceConfig.user
        val pass = sourceConfig.pass
        val playlist = sourceConfig.playlist
        val sourceKey = sha256("$server\u0000$user\u0000$pass\u0000$playlist")

        if (!forceRefresh && cached.isNotEmpty() && cachedSourceKey == sourceKey && now - cachedAt in 0 until CACHE_TTL_MS) return@withContext cached

        val source = when {
            server.isNotBlank() && user.isNotBlank() && pass.isNotBlank() ->
                "$server/get.php?username=${URLEncoder.encode(user, "UTF-8")}&password=${URLEncoder.encode(pass, "UTF-8")}&type=m3u_plus&output=ts"
            playlist.isNotBlank() -> playlist
            else -> return@withContext cached
        }

        val result = runCatching { fetchAndParse(source) }.getOrDefault(emptyList())
        if (result.isNotEmpty()) {
            cached = result
            cachedAt = now
            cachedSourceKey = sourceKey
            channelIndex = ChannelIndex(result, SportsChannel::name, SportsChannel::group)
            persist(context, result, sourceKey, now)
            result
        } else {
            if (cached.isNotEmpty() && cachedAt > 0L && now - cachedAt <= MAX_STALE_MS) cached else emptyList()
        }
    }

    fun cachedChannels(): List<SportsChannel> = cached

    fun bestMatch(event: SportsEvent, channels: List<SportsChannel>): SportsChannel? {
        if (channels.isEmpty()) return null
        val index = channelIndex ?: ChannelIndex(channels, SportsChannel::name, SportsChannel::group).also { channelIndex = it }
        val queryTerms = buildList {
            event.competitors.forEach { if (it.isNotBlank()) add(it) }
            if (event.league.isNotBlank()) add(event.league)
            if (event.broadcast.isNotBlank()) add(event.broadcast)
        }
        val candidates = linkedMapOf<String, SportsChannel>()
        queryTerms.take(4).forEach { query -> index.search(query, 80).forEach { candidates[it.id] = it } }
        if (candidates.isEmpty()) index.forSport(SportsCatalog.classify(event.name, event.league), 120).forEach { candidates[it.id] = it }
        val pool = if (candidates.isNotEmpty()) candidates.values else channels.take(500)
        return pool.asSequence()
            .map { it to SportsSchedule.matchChannel(event, it.name, it.group) }
            .filter { it.second >= 5 }
            .maxWithOrNull(compareBy<Pair<SportsChannel, Int>> { it.second }.thenBy { it.first.name.lowercase() })
            ?.first
    }

    /** Stream the playlist directly into the parser so huge inventories aren't duplicated as one String. */
    private fun fetchAndParse(source: String): List<SportsChannel> {
        val conn = URL(source).openConnection() as HttpURLConnection
        conn.connectTimeout = 8_000
        conn.readTimeout = 60_000
        conn.instanceFollowRedirects = true
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", "USportz/1.1")
        return try {
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            conn.inputStream.bufferedReader().use(::parse)
        } finally { conn.disconnect() }
    }

    private fun persist(context: Context, channels: List<SportsChannel>, sourceKey: String, savedAt: Long) {
        val array = JSONArray()
        channels.forEach { channel ->
            array.put(JSONObject().apply {
                put("id", channel.id); put("name", channel.name); put("group", channel.group)
                put("logo", channel.logo ?: ""); put("url", channel.url)
            })
        }
        val root = JSONObject().apply { put("version", 2); put("savedAt", savedAt); put("sourceKey", sourceKey); put("channels", array) }
        val dir = context.noBackupFilesDir
        val target = File(dir, CACHE_FILE)
        val temp = File(dir, "$CACHE_FILE.tmp")
        runCatching { temp.writeText(root.toString()); if (!temp.renameTo(target)) { target.delete(); temp.renameTo(target) } }
    }

    private fun parseCachedArray(text: String): List<SportsChannel> {
        val array = runCatching { JSONArray(text) }.getOrDefault(JSONArray())
        return buildList(array.length()) {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val url = item.optString("url")
                if (url.isNotBlank()) add(SportsChannel(item.optString("id"), item.optString("name", "Channel"), item.optString("group", "Live TV"), item.optString("logo").ifBlank { null }, url))
            }
        }
    }

    private fun parse(reader: BufferedReader): List<SportsChannel> {
        val result = ArrayList<SportsChannel>()
        var attrs = emptyMap<String, String>()
        while (true) {
            val raw = reader.readLine() ?: break
            val line = raw.trim()
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXTINF", true) -> attrs = parseAttrs(line)
                !line.startsWith("#") -> {
                    val name = attrs["name"] ?: line.substringAfterLast('/').substringBefore('?').ifBlank { "Channel" }
                    result += SportsChannel("${name.lowercase()}|$line".hashCode().toString(), name, attrs["group"] ?: "Live TV", attrs["logo"], line)
                    attrs = emptyMap()
                }
            }
        }
        return result.distinctBy { it.id }
    }

    private fun parseAttrs(line: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        Regex("([\\w-]+)=\"([^\"]*)\"").findAll(line).forEach { map[it.groupValues[1].lowercase()] = it.groupValues[2] }
        line.indexOf(',').takeIf { it >= 0 }?.let { map["name"] = line.substring(it + 1).trim() }
        return map
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
