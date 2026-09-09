package com.usportz.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
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

object SportsChannelBridge {
    private const val CACHE_TTL_MS = 5 * 60 * 1000L
    private const val MAX_STALE_MS = 7 * 24 * 60 * 60 * 1000L
    private const val CACHE_FILE = "channel-index.json"
    @Volatile private var cached: List<SportsChannel> = emptyList()
    @Volatile private var cachedAt = 0L
    @Volatile private var cachedSourceKey = ""
    @Volatile private var channelIndex: ChannelIndex<SportsChannel>? = null
    private val indexing = AtomicBoolean(false)

    fun restoreCached(context: Context): List<SportsChannel> {
        if (cached.isNotEmpty()) return cached
        val file = File(context.noBackupFilesDir, CACHE_FILE)
        val restored = runCatching {
            if (!file.exists()) return@runCatching emptyList()
            val root = JSONObject(file.readText()); val array = root.optJSONArray("channels") ?: JSONArray(); cachedSourceKey = root.optString("sourceKey"); cachedAt = root.optLong("savedAt", file.lastModified())
            buildList(array.length()) { for (i in 0 until array.length()) { val item = array.optJSONObject(i) ?: continue; val url = clean(item.optString("url")); if (url.isBlank()) continue; add(SportsChannel(item.optString("id"), clean(item.optString("name")).ifBlank { "Channel" }, clean(item.optString("group")).ifBlank { "Live TV" }, clean(item.optString("logo")).ifBlank { null }, url)) } }
        }.getOrElse { runCatching { parseCachedArray(file.readText()) }.getOrDefault(emptyList()) }
        if (restored.isNotEmpty()) { cached = restored; channelIndex = ChannelIndex(restored, SportsChannel::name, SportsChannel::group) }
        return cached
    }

    /**
     * Xtream-compatible login handshake. Providers vary: users may enter a bare host,
     * http/https URL, or paste a player_api.php/get.php URL. Normalize all of those
     * to the portal root, try both schemes when needed, and accept auth values returned
     * as either JSON numbers or strings.
     */
    fun validateXtream(server: String, user: String, pass: String): Boolean {
        val query = "username=${URLEncoder.encode(user, "UTF-8")}&password=${URLEncoder.encode(pass, "UTF-8")}"
        val candidates = normalizedServerCandidates(server)
        if (candidates.isEmpty()) return false

        var explicitAuthFailure = false
        for (base in candidates) {
            val apiUrl = "$base/player_api.php?$query"
            val apiResult = runCatching { request(apiUrl, 6_000) }.getOrNull()
            if (!apiResult.isNullOrBlank()) {
                val json = runCatching { JSONObject(apiResult) }.getOrNull()
                if (json != null) {
                    val info = json.optJSONObject("user_info")
                    val auth = info?.optString("auth")?.trim()
                        ?: json.optString("auth").trim()
                    val status = clean(info?.optString("status") ?: json.optString("status"))
                    if (auth == "1" || status.equals("Active", true) || status.equals("Enabled", true)) return true
                    if (auth == "0" || status.equals("Invalid", true) || status.equals("Expired", true) || status.equals("Disabled", true) || status.equals("Banned", true)) {
                        explicitAuthFailure = true
                        continue
                    }
                } else if (apiResult.contains("#EXTM3U", true) || apiResult.contains("#EXTINF", true)) {
                    return true
                }
            }

            // Some compatible panels have a broken/disabled player_api.php but still
            // expose the authenticated Xtream get.php playlist endpoint.
            val playlistUrl = "$base/get.php?$query&type=m3u_plus&output=ts"
            val playlistOk = runCatching {
                val conn = URL(playlistUrl).openConnection() as HttpURLConnection
                try {
                    conn.connectTimeout = 4_000
                    conn.readTimeout = 8_000
                    conn.instanceFollowRedirects = true
                    conn.requestMethod = "GET"
                    conn.setRequestProperty("Accept", "application/x-mpegURL, audio/x-mpegurl, text/plain, */*")
                    conn.setRequestProperty("Accept-Encoding", "gzip")
                    conn.setRequestProperty("User-Agent", "USportz/1.4")
                    if (conn.responseCode !in 200..299) return@runCatching false
                    val stream = if (conn.contentEncoding.equals("gzip", true)) GZIPInputStream(conn.inputStream) else conn.inputStream
                    stream.bufferedReader().use { reader ->
                        var seen = 0
                        var chars = 0
                        while (chars < 256 * 1024) {
                            val line = reader.readLine() ?: break
                            chars += line.length + 1
                            if (line.contains("#EXTM3U", true) || line.contains("#EXTINF", true)) seen++
                            if (seen >= 1) return@use true
                        }
                        false
                    }
                } finally { conn.disconnect() }
            }.getOrDefault(false)
            if (playlistOk) return true
        }
        return !explicitAuthFailure && false
    }

    /** Return portal roots without leaking or persisting credentials. */
    private fun normalizedServerCandidates(server: String): List<String> {
        val raw = server.trim().trimEnd('/')
        if (raw.isBlank()) return emptyList()
        val withScheme = when {
            raw.startsWith("http://", true) || raw.startsWith("https://", true) -> raw
            else -> "http://$raw"
        }
        val uri = runCatching { URI(withScheme) }.getOrNull() ?: return emptyList()
        val scheme = uri.scheme?.lowercase() ?: return emptyList()
        val authority = uri.rawAuthority?.takeIf { it.isNotBlank() } ?: return emptyList()
        val path = uri.path.orEmpty().trimEnd('/')
        val basePath = when {
            path.endsWith("/player_api.php", true) -> path.dropLast("/player_api.php".length).trimEnd('/')
            path.endsWith("/get.php", true) -> path.dropLast("/get.php".length).trimEnd('/')
            else -> path
        }
        val root = "$scheme://$authority${basePath.ifBlank { "" }}"
        val alternate = if (scheme == "https") "http://$authority${basePath.ifBlank { "" }}" else "https://$authority${basePath.ifBlank { "" }}"
        return listOf(root, alternate).distinct()
    }

    private fun request(url: String, timeout: Int): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 4_000
            conn.readTimeout = timeout
            conn.instanceFollowRedirects = true
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/json, text/plain, */*")
            conn.setRequestProperty("Accept-Encoding", "gzip")
            conn.setRequestProperty("User-Agent", "USportz/1.4")
            if (conn.responseCode !in 200..299) return ""
            val stream = if (conn.contentEncoding.equals("gzip", true)) GZIPInputStream(conn.inputStream) else conn.inputStream
            stream.bufferedReader().use { it.readText().take(128_000) }
        } finally { conn.disconnect() }
    }

    suspend fun load(context: Context, forceRefresh: Boolean = false): List<SportsChannel> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis(); if (cached.isEmpty()) restoreCached(context)
        val sourceConfig = SourceStore(context); val server = sourceConfig.server.trimEnd('/'); val user = sourceConfig.user; val pass = sourceConfig.pass; val playlist = sourceConfig.playlist
        val sourceKey = sha256("$server\u0000$user\u0000$pass\u0000$playlist")
        if (!forceRefresh && cached.isNotEmpty() && cachedSourceKey == sourceKey && now - cachedAt in 0 until CACHE_TTL_MS) return@withContext cached
        if (!indexing.compareAndSet(false, true)) return@withContext cached
        try {
            val source = when { server.isNotBlank() && user.isNotBlank() && pass.isNotBlank() -> "$server/get.php?username=${URLEncoder.encode(user, "UTF-8")}&password=${URLEncoder.encode(pass, "UTF-8")}&type=m3u_plus&output=ts"; playlist.isNotBlank() -> playlist; else -> return@withContext cached }
            val result = runCatching { fetchAndParse(source) }.getOrDefault(emptyList())
            if (result.isNotEmpty()) { cached = result; cachedAt = System.currentTimeMillis(); cachedSourceKey = sourceKey; channelIndex = ChannelIndex(result, SportsChannel::name, SportsChannel::group); persist(context, result, sourceKey, cachedAt); result }
            else { if (cached.isNotEmpty() && cachedAt > 0L && now - cachedAt <= MAX_STALE_MS) cached else emptyList() }
        } finally { indexing.set(false) }
    }

    fun cachedChannels(): List<SportsChannel> = cached
    fun isIndexing(): Boolean = indexing.get()
    fun bestMatch(event: SportsEvent, channels: List<SportsChannel>): SportsChannel? {
        if (channels.isEmpty()) return null
        val index = channelIndex ?: ChannelIndex(channels, SportsChannel::name, SportsChannel::group).also { channelIndex = it }
        val queryTerms = buildList { event.competitors.forEach { if (it.isNotBlank()) add(it) }; if (event.league.isNotBlank()) add(event.league); if (event.broadcast.isNotBlank()) add(event.broadcast) }
        val candidates = linkedMapOf<String, SportsChannel>(); queryTerms.take(4).forEach { q -> index.search(q, 80).forEach { candidates[it.id] = it } }; if (candidates.isEmpty()) index.forSport(SportsCatalog.classify(event.name, event.league), 120).forEach { candidates[it.id] = it }
        val pool = if (candidates.isNotEmpty()) candidates.values else channels.take(500)
        return pool.asSequence().map { it to SportsSchedule.matchChannel(event, it.name, it.group) }.filter { it.second >= 5 }.maxWithOrNull(compareBy<Pair<SportsChannel, Int>> { it.second }.thenBy { it.first.name.lowercase() })?.first
    }

    private fun fetchAndParse(source: String): List<SportsChannel> { val conn = URL(source).openConnection() as HttpURLConnection; conn.connectTimeout = 6_000; conn.readTimeout = 60_000; conn.instanceFollowRedirects = true; conn.requestMethod = "GET"; conn.setRequestProperty("Accept", "application/x-mpegURL, audio/x-mpegurl, text/plain, */*"); conn.setRequestProperty("Accept-Encoding", "gzip"); conn.setRequestProperty("User-Agent", "USportz/1.4"); return try { val code = conn.responseCode; if (code !in 200..299) throw IllegalStateException("HTTP $code"); val raw = conn.inputStream; val stream = if (conn.contentEncoding.equals("gzip", true)) GZIPInputStream(raw) else raw; stream.bufferedReader().use(::parse) } finally { conn.disconnect() } }
    private fun persist(context: Context, channels: List<SportsChannel>, sourceKey: String, savedAt: Long) { val array = JSONArray(); channels.forEach { c -> array.put(JSONObject().apply { put("id", c.id); put("name", c.name); put("group", c.group); put("logo", c.logo ?: ""); put("url", c.url) }) }; val root = JSONObject().apply { put("version", 2); put("savedAt", savedAt); put("sourceKey", sourceKey); put("channels", array) }; val dir = context.noBackupFilesDir; val target = File(dir, CACHE_FILE); val temp = File(dir, "$CACHE_FILE.tmp"); runCatching { temp.writeText(root.toString()); if (!temp.renameTo(target)) { target.delete(); temp.renameTo(target) } } }
    private fun parseCachedArray(text: String): List<SportsChannel> { val array = runCatching { JSONArray(text) }.getOrDefault(JSONArray()); return buildList(array.length()) { for (i in 0 until array.length()) { val item = array.optJSONObject(i) ?: continue; val url = clean(item.optString("url")); if (url.isNotBlank()) add(SportsChannel(item.optString("id"), clean(item.optString("name")).ifBlank { "Channel" }, clean(item.optString("group")).ifBlank { "Live TV" }, clean(item.optString("logo")).ifBlank { null }, url)) } } }
    private fun parse(reader: BufferedReader): List<SportsChannel> { val result = ArrayList<SportsChannel>(); var attrs = emptyMap<String, String>(); while (true) { val raw = reader.readLine() ?: break; val line = raw.trim(); if (line.isEmpty()) continue; when { line.startsWith("#EXTINF", true) -> attrs = parseAttrs(line); !line.startsWith("#") -> { val name = clean(attrs["name"]).ifBlank { line.substringAfterLast('/').substringBefore('?').ifBlank { "Channel" } }; result += SportsChannel("${name.lowercase()}|$line".hashCode().toString(), name, clean(attrs["group"]).ifBlank { "Live TV" }, clean(attrs["logo"]).ifBlank { null }, line); attrs = emptyMap() } } }; return result.distinctBy { it.id } }
    private fun parseAttrs(line: String): Map<String, String> { val map = mutableMapOf<String, String>(); Regex("([\\w-]+)=\"([^\"]*)\"").findAll(line).forEach { map[it.groupValues[1].lowercase()] = it.groupValues[2] }; line.indexOf(',').takeIf { it >= 0 }?.let { map["name"] = line.substring(it + 1).trim() }; return map }
    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun clean(value: String?): String = value.orEmpty().trim().takeIf { it.isNotBlank() && !it.equals("null", true) && !it.equals("undefined", true) }.orEmpty()
}
