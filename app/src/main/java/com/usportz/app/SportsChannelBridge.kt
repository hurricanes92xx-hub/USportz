package com.usportz.app

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream

data class SportsChannel(val id: String, val name: String, val group: String, val logo: String?, val url: String)

/**
 * Fast Xtream/M3U bridge.
 *
 * Startup reads a small sports-only snapshot from SQLite and returns immediately. A refresh
 * streams the provider response directly into a new disk generation in 500-row batches, then
 * atomically activates it. We never build a giant IPTV playlist in memory.
 */
object SportsChannelBridge {
    private const val CACHE_TTL_MS = 15 * 60 * 1000L
    private const val BATCH_SIZE = 500
    @Volatile private var cached: List<SportsChannel> = emptyList()
    @Volatile private var cachedAt = 0L
    @Volatile private var cachedSourceKey = ""
    private val indexing = AtomicBoolean(false)
    private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun restoreCached(context: Context): List<SportsChannel> = cached

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

    private fun credentialsQuery(user: String, pass: String): String =
        "username=${URLEncoder.encode(user, "UTF-8")}&password=${URLEncoder.encode(pass, "UTF-8")}"

    private fun request(url: String, timeout: Int): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 4_500
            conn.readTimeout = timeout
            conn.instanceFollowRedirects = true
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/json, text/plain, */*")
            conn.setRequestProperty("Accept-Encoding", "gzip")
            conn.setRequestProperty("User-Agent", "USportz/1.8")
            if (conn.responseCode !in 200..299) return ""
            openDecoded(conn).bufferedReader().use { it.readText().take(8 * 1024 * 1024) }
        } finally { conn.disconnect() }
    }

    /** Returns instantly from the local sports snapshot. Refresh work is deliberately detached. */
    suspend fun load(context: Context, forceRefresh: Boolean = false): List<SportsChannel> = withContext(Dispatchers.IO) {
        val source = SourceStore(context)
        val server = normalizeXtreamServer(source.server)
        val user = source.user
        val pass = source.pass
        val playlist = source.playlist
        val sourceKey = sha256("$server\u0000$user\u0000$pass\u0000$playlist")
        val store = SportsChannelDiskStore(context)
        val diskSnapshot = runCatching { store.activeSnapshot(sourceKey) }.getOrDefault(emptyList())
        if (diskSnapshot.isNotEmpty()) {
            cached = diskSnapshot
            cachedAt = System.currentTimeMillis()
            cachedSourceKey = sourceKey
        }

        if (forceRefresh) {
            var waited = 0L
            while (indexing.get() && waited < 30_000L) { delay(100L); waited += 100L }
            refreshInternal(context, server, user, pass, playlist, sourceKey)
            val refreshed = store.activeSnapshot(sourceKey)
            if (refreshed.isNotEmpty()) {
                cached = refreshed
                cachedAt = System.currentTimeMillis()
                cachedSourceKey = sourceKey
            }
            return@withContext cached.takeIf { cachedSourceKey == sourceKey }.orEmpty()
        }

        val age = System.currentTimeMillis() - cachedAt
        val needsRefresh = diskSnapshot.isEmpty() || cachedSourceKey != sourceKey || age > CACHE_TTL_MS
        if (needsRefresh) refreshScope.launch {
            refreshInternal(context, server, user, pass, playlist, sourceKey)
        }
        // Critical startup behavior: return the disk snapshot immediately, even when a new
        // provider still needs indexing. The sports schedule is never blocked by IPTV ingest.
        if (cachedSourceKey == sourceKey) cached else diskSnapshot
    }

    private suspend fun refreshInternal(context: Context, server: String, user: String, pass: String, playlist: String, sourceKey: String) = withContext(Dispatchers.IO) {
        if (!indexing.compareAndSet(false, true)) return@withContext
        try {
            val store = SportsChannelDiskStore(context)
            var generation: Long? = null
            var count = 0
            fun accept(channel: SportsChannel) {
                if (generation == null) generation = store.beginGeneration(sourceKey)
                pending += channel
                count++
                if (pending.size >= BATCH_SIZE) flush(store, sourceKey, generation!!)
            }

            pending.clear()
            if (server.isNotBlank() && user.isNotBlank() && pass.isNotBlank()) {
                for (base in normalizedServerCandidates(server)) {
                    val before = count
                    runCatching { fetchLiveStreamsStreaming(base, user, pass, ::accept) }
                    if (count > before) break
                }
                if (count == 0) {
                    for (base in normalizedServerCandidates(server)) {
                        val before = count
                        runCatching { fetchM3uStreaming("$base/get.php?${credentialsQuery(user, pass)}&type=m3u_plus&output=ts", ::accept) }
                        if (count > before) break
                    }
                }
            } else if (playlist.isNotBlank()) {
                runCatching { fetchM3uStreaming(playlist, ::accept) }
            }
            if (generation != null && count > 0) {
                flush(store, sourceKey, generation!!)
                store.activate(sourceKey, generation!!, count)
                val snapshot = store.activeSnapshot(sourceKey)
                cached = snapshot
                cachedAt = System.currentTimeMillis()
                cachedSourceKey = sourceKey
            }
        } finally {
            pending.clear()
            indexing.set(false)
        }
    }

    private val pending = ArrayList<SportsChannel>(BATCH_SIZE)
    private fun flush(store: SportsChannelDiskStore, sourceKey: String, generation: Long) {
        if (pending.isEmpty()) return
        store.insertBatch(sourceKey, generation, pending.toList())
        pending.clear()
    }

    private fun fetchLiveStreamsStreaming(base: String, user: String, pass: String, sink: (SportsChannel) -> Unit): Int {
        val query = "${credentialsQuery(user, pass)}&action=get_live_streams"
        for (endpoint in listOf("player_api.php", "panel_api.php")) {
            val conn = runCatching { URL("$base/$endpoint?$query").openConnection() as HttpURLConnection }.getOrNull() ?: continue
            try {
                conn.connectTimeout = 4_500
                conn.readTimeout = 60_000
                conn.instanceFollowRedirects = true
                conn.requestMethod = "GET"
                conn.setRequestProperty("Accept", "application/json, text/plain, */*")
                conn.setRequestProperty("Accept-Encoding", "gzip")
                conn.setRequestProperty("User-Agent", "USPortz/1.8")
                if (conn.responseCode !in 200..299) continue
                val parsed = streamJson(openDecoded(conn), base, user, pass, sink)
                if (parsed > 0) return parsed
            } finally { conn.disconnect() }
        }
        return 0
    }

    private fun streamJson(input: InputStream, base: String, user: String, pass: String, sink: (SportsChannel) -> Unit): Int {
        input.use { stream ->
            JsonReader(InputStreamReader(stream)).use { reader ->
                return when (reader.peek()) {
                    JsonToken.BEGIN_ARRAY -> readStreamArray(reader, base, user, pass, sink)
                    JsonToken.BEGIN_OBJECT -> {
                        var count = 0
                        reader.beginObject()
                        while (reader.hasNext()) {
                            val key = reader.nextName()
                            if (key.equals("live_streams", true) || key.equals("streams", true) || key.equals("channels", true) || key.equals("data", true)) {
                                if (reader.peek() == JsonToken.BEGIN_ARRAY) count += readStreamArray(reader, base, user, pass, sink) else reader.skipValue()
                            } else reader.skipValue()
                        }
                        reader.endObject()
                        count
                    }
                    else -> 0
                }
            }
        }
    }

    private fun readStreamArray(reader: JsonReader, base: String, user: String, pass: String, sink: (SportsChannel) -> Unit): Int {
        var count = 0
        reader.beginArray()
        while (reader.hasNext()) {
            val channel = readStreamObject(reader, base, user, pass)
            if (channel != null) { sink(channel); count++ }
        }
        reader.endArray()
        return count
    }

    private fun readStreamObject(reader: JsonReader, base: String, user: String, pass: String): SportsChannel? {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) { reader.skipValue(); return null }
        var id = ""; var name = ""; var group = "Live TV"; var logo: String? = null; var ext = "m3u8"; var direct = ""
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName().lowercase()) {
                "stream_id", "id" -> id = nextString(reader)
                "name", "title" -> name = nextString(reader)
                "category_name", "category" -> group = nextString(reader).ifBlank { "Live TV" }
                "stream_icon", "icon", "logo" -> logo = nextString(reader).ifBlank { null }
                "container_extension" -> ext = nextString(reader).ifBlank { "m3u8" }
                "direct_source" -> direct = nextString(reader)
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        if (id.isBlank()) return null
        val displayName = name.ifBlank { "Channel" }
        val url = direct.ifBlank { "$base/live/$user/$pass/$id.$ext" }
        return SportsChannel(id, displayName, group, logo, url)
    }

    private fun nextString(reader: JsonReader): String = when (reader.peek()) {
        JsonToken.NULL -> { reader.nextNull(); "" }
        else -> runCatching { reader.nextString() }.getOrElse { reader.skipValue(); "" }
    }

    private fun fetchM3uStreaming(source: String, sink: (SportsChannel) -> Unit): Int {
        val conn = URL(source).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 6_000
            conn.readTimeout = 60_000
            conn.instanceFollowRedirects = true
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/x-mpegURL, audio/x-mpegurl, text/plain, */*")
            conn.setRequestProperty("Accept-Encoding", "gzip")
            conn.setRequestProperty("User-Agent", "USPortz/1.8")
            if (conn.responseCode !in 200..299) return 0
            openDecoded(conn).bufferedReader().use { parseM3u(it, sink) }
        } finally { conn.disconnect() }
    }

    private fun parseM3u(reader: BufferedReader, sink: (SportsChannel) -> Unit): Int {
        var attrs = emptyMap<String, String>()
        var count = 0
        while (true) {
            val raw = reader.readLine() ?: break
            val line = raw.trim()
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXTINF", true) -> attrs = parseAttrs(line)
                !line.startsWith("#") -> {
                    val name = clean(attrs["name"]).ifBlank { clean(attrs["tvg-name"]) }.ifBlank { line.substringAfterLast('/').substringBefore('?').ifBlank { "Channel" } }
                    val group = clean(attrs["group-title"]).ifBlank { clean(attrs["group"]) }.ifBlank { clean(attrs["category-name"]) }.ifBlank { "Live TV" }
                    val logo = clean(attrs["tvg-logo"]).ifBlank { clean(attrs["logo"]) }.ifBlank { null }
                    sink(SportsChannel("${name.lowercase()}|$line".hashCode().toString(), name, group, logo, line))
                    count++
                    attrs = emptyMap()
                }
            }
        }
        return count
    }

    private fun parseAttrs(line: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        Regex("([\\w-]+)=\"([^\"]*)\"").findAll(line).forEach { map[it.groupValues[1].lowercase()] = it.groupValues[2] }
        line.indexOf(',').takeIf { it >= 0 }?.let { map["name"] = line.substring(it + 1).trim() }
        return map
    }

    private fun openDecoded(conn: HttpURLConnection): InputStream {
        val raw = conn.inputStream
        return if (conn.contentEncoding.equals("gzip", true)) GZIPInputStream(raw) else raw
    }

    fun cachedChannels(): List<SportsChannel> = cached
    fun isIndexing(): Boolean = indexing.get()
    fun bestMatch(event: SportsEvent, channels: List<SportsChannel>): SportsChannel? = GameSourceMatcher.rankMatches(event, channels, 1).firstOrNull()?.channel
    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun clean(value: String?): String = value.orEmpty().trim()
}
