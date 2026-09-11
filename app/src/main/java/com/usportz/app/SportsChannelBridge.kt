package com.usportz.app

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream

data class SportsChannel(val id: String, val name: String, val group: String, val logo: String?, val url: String)

/** Xtream-compatible source bridge. Network work and cache/index construction stay off the UI thread. */
object SportsChannelBridge {
    private const val CACHE_TTL_MS = 15 * 60 * 1000L
    private const val MAX_STALE_MS = 7 * 24 * 60 * 60 * 1000L
    private const val CACHE_FILE = "channel-index.json"
    @Volatile private var cached: List<SportsChannel> = emptyList()
    @Volatile private var cachedAt = 0L
    @Volatile private var cachedSourceKey = ""
    @Volatile private var channelIndex: ChannelIndex<SportsChannel>? = null
    private val indexing = AtomicBoolean(false)
    private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
                    val url = clean(item.optString("url"))
                    if (url.isBlank()) continue
                    add(SportsChannel(item.optString("id"), clean(item.optString("name")).ifBlank { "Channel" }, clean(item.optString("group")).ifBlank { "Live TV" }, clean(item.optString("logo")).ifBlank { null }, url))
                }
            }
        }.getOrElse { emptyList() }
        if (restored.isNotEmpty()) {
            cached = restored
            channelIndex = ChannelIndex(restored, SportsChannel::name, SportsChannel::group)
        }
        return cached
    }

    fun authenticateXtream(server: String, user: String, pass: String): String? {
        val query = credentialsQuery(user, pass)
        for (base in normalizedServerCandidates(server)) {
            for (endpoint in listOf("player_api.php", "panel_api.php")) {
                val body = runCatching { request("$base/$endpoint?$query", 7_000) }.getOrNull().orEmpty()
                val json = runCatching { JSONObject(body) }.getOrNull()
                if (json != null) {
                    val info = json.optJSONObject("user_info")
                    val auth = clean(info?.optString("auth") ?: json.optString("auth"))
                    val status = clean(info?.optString("status") ?: json.optString("status"))
                    if (auth == "1" || status.equals("Active", true) || status.equals("Enabled", true)) return base
                }
            }
            if (probeM3u("$base/get.php?$query&type=m3u_plus&output=ts") || probeM3u("$base/get.php?$query&type=m3u_plus&output=m3u8")) return base
        }
        return null
    }

    fun validateXtream(server: String, user: String, pass: String): Boolean = authenticateXtream(server, user, pass) != null
    fun normalizeXtreamServer(server: String): String = normalizedServerCandidates(server).firstOrNull().orEmpty()

    private fun normalizedServerCandidates(server: String): List<String> {
        val raw = server.trim().trimEnd('/')
        if (raw.isBlank()) return emptyList()
        val withScheme = if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) raw else "http://$raw"
        val uri = runCatching { URI(withScheme) }.getOrNull() ?: return emptyList()
        val scheme = uri.scheme?.lowercase() ?: return emptyList()
        val authority = uri.rawAuthority?.takeIf { it.isNotBlank() } ?: return emptyList()
        val path = uri.path.orEmpty().trimEnd('/')
        val basePath = when {
            path.endsWith("/player_api.php", true) -> path.dropLast("/player_api.php".length).trimEnd('/')
            path.endsWith("/panel_api.php", true) -> path.dropLast("/panel_api.php".length).trimEnd('/')
            path.endsWith("/get.php", true) -> path.dropLast("/get.php".length).trimEnd('/')
            path.endsWith("/xmltv.php", true) -> path.dropLast("/xmltv.php".length).trimEnd('/')
            else -> path
        }
        val root = "$scheme://$authority${basePath.ifBlank { "" }}"
        val alternate = "${if (scheme == "https") "http" else "https"}://$authority${basePath.ifBlank { "" }}"
        return listOf(root, alternate).distinct()
    }

    private fun credentialsQuery(user: String, pass: String): String = "username=${URLEncoder.encode(user, "UTF-8")}&password=${URLEncoder.encode(pass, "UTF-8")}"

    private fun request(url: String, timeout: Int): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 4_500
            conn.readTimeout = timeout
            conn.instanceFollowRedirects = true
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/json, text/plain, */*")
            conn.setRequestProperty("Accept-Encoding", "gzip")
            conn.setRequestProperty("User-Agent", "USportz/1.7")
            if (conn.responseCode !in 200..299) return ""
            val stream = if (conn.contentEncoding.equals("gzip", true)) GZIPInputStream(conn.inputStream) else conn.inputStream
            stream.bufferedReader().use { it.readText().take(64 * 1024 * 1024) }
        } finally { conn.disconnect() }
    }

    private fun probeM3u(url: String): Boolean = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 4_500
            conn.readTimeout = 8_000
            conn.instanceFollowRedirects = true
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/x-mpegURL, audio/x-mpegurl, text/plain, */*")
            conn.setRequestProperty("Accept-Encoding", "gzip")
            conn.setRequestProperty("User-Agent", "USportz/1.7")
            if (conn.responseCode !in 200..299) return@runCatching false
            val stream = if (conn.contentEncoding.equals("gzip", true)) GZIPInputStream(conn.inputStream) else conn.inputStream
            stream.bufferedReader().use { reader ->
                repeat(1000) {
                    val line = reader.readLine() ?: return@use false
                    if (line.contains("#EXTM3U", true) || line.contains("#EXTINF", true)) return@use true
                }
                false
            }
        } finally { conn.disconnect() }
    }.getOrDefault(false)

    /**
     * Returns the current snapshot immediately whenever one exists. If the snapshot is
     * older than CACHE_TTL_MS, a provider refresh is started in the background. The caller
     * never waits for a 20K-100K channel playlist to download, parse, index, or persist.
     * A cold start with no cache still performs one foreground load so the UI has data.
     */
    suspend fun load(context: Context, forceRefresh: Boolean = false): List<SportsChannel> = withContext(Dispatchers.IO) {
        if (cached.isEmpty()) restoreCached(context)

        val source = SourceStore(context)
        val server = normalizeXtreamServer(source.server)
        val user = source.user
        val pass = source.pass
        val playlist = source.playlist
        val sourceKey = sha256("$server\u0000$user\u0000$pass\u0000$playlist")
        val now = System.currentTimeMillis()
        val hasUsableCache = cached.isNotEmpty() && cachedSourceKey == sourceKey && now - cachedAt <= MAX_STALE_MS
        val cacheAge = if (cachedAt > 0L) now - cachedAt else Long.MAX_VALUE
        val needsRefresh = forceRefresh || cacheAge > CACHE_TTL_MS || cached.isEmpty() || cachedSourceKey != sourceKey

        if (hasUsableCache) {
            if (needsRefresh) refreshScope.launch {
                refreshInternal(context, server, user, pass, playlist, sourceKey)
            }
            return@withContext cached
        }

        if (cached.isNotEmpty() && cachedAt > 0L && now - cachedAt <= MAX_STALE_MS) {
            refreshScope.launch {
                refreshInternal(context, server, user, pass, playlist, sourceKey)
            }
            return@withContext cached
        }

        // Cold start: no safe snapshot exists, so fetch once before returning.
        refreshInternal(context, server, user, pass, playlist, sourceKey)
        cached
    }

    private suspend fun refreshInternal(
        context: Context,
        server: String,
        user: String,
        pass: String,
        playlist: String,
        sourceKey: String
    ) = withContext(Dispatchers.IO) {
        if (!indexing.compareAndSet(false, true)) return@withContext
        try {
            // Another refresh may have completed while this coroutine was queued.
            if (cachedSourceKey == sourceKey && cached.isNotEmpty() && System.currentTimeMillis() - cachedAt < CACHE_TTL_MS) return@withContext
            var result = emptyList<SportsChannel>()
            if (server.isNotBlank() && user.isNotBlank() && pass.isNotBlank()) {
                for (base in normalizedServerCandidates(server)) {
                    result = runCatching { fetchLiveStreams(base, user, pass) }.getOrDefault(emptyList())
                    if (result.isNotEmpty()) break
                }
                if (result.isEmpty()) {
                    for (base in normalizedServerCandidates(server)) {
                        result = fetchXtreamPlaylist(base, user, pass)
                        if (result.isNotEmpty()) break
                    }
                }
            } else if (playlist.isNotBlank()) {
                result = runCatching { fetchAndParse(playlist) }.getOrDefault(emptyList())
            }
            if (result.isNotEmpty()) {
                val savedAt = System.currentTimeMillis()
                cached = result
                cachedAt = savedAt
                cachedSourceKey = sourceKey
                channelIndex = ChannelIndex(result, SportsChannel::name, SportsChannel::group)
                persist(context, result, sourceKey, savedAt)
            }
        } finally { indexing.set(false) }
    }

    private fun fetchLiveStreams(base: String, user: String, pass: String): List<SportsChannel> {
        val query = "${credentialsQuery(user, pass)}&action=get_live_streams"
        val bodies = listOf("$base/player_api.php?$query", "$base/panel_api.php?$query")
        for (url in bodies) {
            val body = runCatching { request(url, 20_000) }.getOrNull().orEmpty()
            val array = parseLiveResponse(body) ?: continue
            val result = parseLiveArray(array, base, user, pass)
            if (result.isNotEmpty()) return result
        }
        return emptyList()
    }

    private fun parseLiveResponse(body: String): JSONArray? {
        val direct = runCatching { JSONArray(body) }.getOrNull()
        if (direct != null) return direct
        val obj = runCatching { JSONObject(body) }.getOrNull() ?: return null
        listOf("live_streams", "streams", "available_channels", "channels", "data").forEach { key ->
            val array = obj.optJSONArray(key)
            if (array != null && array.length() > 0) return array
        }
        return null
    }

    private fun parseLiveArray(array: JSONArray, base: String, user: String, pass: String): List<SportsChannel> = buildList(array.length()) {
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val id = clean(item.optString("stream_id")).ifBlank { clean(item.optString("id")) }
            val name = clean(item.optString("name")).ifBlank { clean(item.optString("title")) }.ifBlank { "Channel" }
            if (id.isBlank()) continue
            val group = clean(item.optString("category_name")).ifBlank { clean(item.optString("category")) }.ifBlank { "Live TV" }
            val logo = clean(item.optString("stream_icon")).ifBlank { clean(item.optString("icon")) }.ifBlank { clean(item.optString("logo")) }.ifBlank { null }
            val ext = clean(item.optString("container_extension")).ifBlank { "m3u8" }
            val direct = clean(item.optString("direct_source"))
            val url = direct.ifBlank { "$base/live/$user/$pass/$id.$ext" }
            add(SportsChannel(id, name, group, logo, url))
        }
    }

    private fun fetchXtreamPlaylist(base: String, user: String, pass: String): List<SportsChannel> {
        val query = credentialsQuery(user, pass)
        val urls = listOf(
            "$base/get.php?$query&type=m3u_plus&output=ts",
            "$base/get.php?$query&type=m3u_plus&output=m3u8",
            "$base/get.php?$query&type=m3u&output=ts",
            "$base/get.php?$query&type=m3u&output=m3u8"
        )
        for (url in urls) {
            val result = runCatching { fetchAndParse(url) }.getOrDefault(emptyList())
            if (result.isNotEmpty()) return result
        }
        return emptyList()
    }

    fun cachedChannels(): List<SportsChannel> = cached
    fun isIndexing(): Boolean = indexing.get()

    fun bestMatch(event: SportsEvent, channels: List<SportsChannel>): SportsChannel? =
        GameSourceMatcher.rankMatches(event, channels, 1).firstOrNull()?.channel

    private fun fetchAndParse(source: String): List<SportsChannel> {
        val conn = URL(source).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 6_000
            conn.readTimeout = 60_000
            conn.instanceFollowRedirects = true
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/x-mpegURL, audio/x-mpegurl, text/plain, */*")
            conn.setRequestProperty("Accept-Encoding", "gzip")
            conn.setRequestProperty("User-Agent", "USportz/1.7")
            if (conn.responseCode !in 200..299) return emptyList()
            val raw = conn.inputStream
            val stream = if (conn.contentEncoding.equals("gzip", true)) GZIPInputStream(raw) else raw
            stream.bufferedReader().use(::parse)
        } finally { conn.disconnect() }
    }

    private fun persist(context: Context, channels: List<SportsChannel>, sourceKey: String, savedAt: Long) {
        val array = JSONArray()
        channels.forEach { c -> array.put(JSONObject().apply { put("id", c.id); put("name", c.name); put("group", c.group); put("logo", c.logo ?: ""); put("url", c.url) }) }
        val root = JSONObject().apply { put("version", 7); put("savedAt", savedAt); put("sourceKey", sourceKey); put("channels", array) }
        val target = File(context.noBackupFilesDir, CACHE_FILE)
        val temp = File(context.noBackupFilesDir, "$CACHE_FILE.tmp")
        runCatching { temp.writeText(root.toString()); if (!temp.renameTo(target)) { target.delete(); temp.renameTo(target) } }
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
                    val name = clean(attrs["name"]).ifBlank { line.substringAfterLast('/').substringBefore('?').ifBlank { "Channel" } }
                    result += SportsChannel("${name.lowercase()}|$line".hashCode().toString(), name, clean(attrs["group"]).ifBlank { "Live TV" }, clean(attrs["logo"]).ifBlank { null }, line)
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
    private fun clean(value: String?): String = value.orEmpty().trim()
}