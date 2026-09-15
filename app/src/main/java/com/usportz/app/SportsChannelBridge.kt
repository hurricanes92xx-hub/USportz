package com.usportz.app

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream

data class SportsChannel(val id: String, val name: String, val group: String, val logo: String?, val url: String, val tvgName: String = "", val tvgId: String = "", val category: String = "", val provider: String = "")

object SportsChannelBridge {
    private const val CACHE_TTL_MS = 15 * 60 * 1000L
    private const val BATCH_SIZE = 2000
    private const val CATEGORY_PARALLELISM = 6
    private const val CATEGORY_READ_TIMEOUT_MS = 30_000
    @Volatile private var cached: List<SportsChannel> = emptyList()
    @Volatile private var cachedAt = 0L
    @Volatile private var cachedSourceKey = ""
    @Volatile private var cachedGeneration = 0L
    @Volatile private var appContext: Context? = null
    private val indexing = AtomicBoolean(false)
    private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun restoreCached(context: Context): List<SportsChannel> = cached
    fun currentSourceKey(): String = cachedSourceKey
    fun currentCatalogGeneration(): Long = cachedGeneration
    fun refreshProgress(context: Context): CatalogRefreshProgress? {
        val source = SourceStore(context)
        val server = normalizeXtreamServer(source.server)
        val sourceKey = sha256("$server\u0000${source.user}\u0000${source.pass}\u0000${source.playlist}")
        return runCatching { SportsChannelDiskStore(context).refreshProgress(sourceKey) }.getOrNull()
    }

    fun indexedCandidates(event: SportsEvent, limit: Int = 1200): List<SportsChannel> {
        val context = appContext ?: return emptyList()
        val sourceKey = cachedSourceKey
        if (sourceKey.isBlank()) return emptyList()
        val terms = buildList {
            event.competitors.forEach { add(it) }
            if (event.league.isNotBlank()) add(event.league)
            if (event.broadcast.isNotBlank()) add(event.broadcast)
            add(SportsCatalog.classify(event.name, event.league))
            SportsBroadcasts.preferredNetworks(event).forEach { add(it) }
        }.map { it.trim() }.filter { it.length >= 3 }.distinct().take(18)
        return SportsChannelIndexQuery.eventCandidates(context, sourceKey, terms, limit)
    }

    fun authenticateXtream(server: String, user: String, pass: String): String? {
        val query = credentialsQuery(user, pass)
        for (base in normalizedServerCandidates(server)) {
            for (endpoint in listOf("player_api.php", "panel_api.php")) {
                val body = runCatching { request("$base/$endpoint?$query", 7_000) }.getOrNull().orEmpty()
                if (isAuthenticatedResponse(body)) return base
            }
            if (probeM3u("$base/get.php?$query&type=m3u_plus&output=ts")) return base
        }
        return null
    }

    private fun isAuthenticatedResponse(body: String): Boolean { if (body.isBlank()) return false; val json = runCatching { JSONObject(body) }.getOrNull() ?: return false; val info = json.optJSONObject("user_info"); val authValue = if (info != null && info.has("auth")) info.opt("auth") else json.opt("auth"); val status = clean(info?.optString("status") ?: json.optString("status")); return authValue == 1 || authValue == true || authValue.toString().equals("1", true) || status.equals("Active", true) || status.equals("Enabled", true) || status.equals("Trial", true) }
    private fun probeM3u(url: String): Boolean { val conn = runCatching { URL(url).openConnection() as HttpURLConnection }.getOrNull() ?: return false; return try { conn.connectTimeout = 4_500; conn.readTimeout = 7_000; conn.instanceFollowRedirects = true; conn.requestMethod = "GET"; conn.setRequestProperty("Accept", "application/x-mpegURL, audio/x-mpegurl, text/plain, */*"); conn.setRequestProperty("Accept-Encoding", "gzip"); conn.setRequestProperty("User-Agent", "USPortz/1.9"); if (conn.responseCode !in 200..299) return false; val text = openDecoded(conn).bufferedReader().use { reader -> val buffer = CharArray(32 * 1024); val read = reader.read(buffer); if (read > 0) String(buffer, 0, read) else "" }; text.contains("#EXTM3U", true) || text.contains("#EXTINF", true) } catch (_: Exception) { false } finally { conn.disconnect() } }
    fun validateXtream(server: String, user: String, pass: String): Boolean = authenticateXtream(server, user, pass) != null
    fun normalizeXtreamServer(server: String): String = normalizedServerCandidates(server).firstOrNull().orEmpty()

    private fun normalizedServerCandidates(server: String): List<String> { val raw = server.trim().trimEnd('/'); if (raw.isBlank()) return emptyList(); val withScheme = if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) raw else "http://$raw"; val uri = runCatching { URI(withScheme) }.getOrNull() ?: return emptyList(); val scheme = uri.scheme?.lowercase() ?: return emptyList(); val authority = uri.rawAuthority?.takeIf { it.isNotBlank() } ?: return emptyList(); val path = uri.path.orEmpty().trimEnd('/'); val basePath = when { path.endsWith("/player_api.php", true) -> path.dropLast("/player_api.php".length).trimEnd('/'); path.endsWith("/panel_api.php", true) -> path.dropLast("/panel_api.php".length).trimEnd('/'); path.endsWith("/get.php", true) -> path.dropLast("/get.php".length).trimEnd('/'); path.endsWith("/xmltv.php", true) -> path.dropLast("/xmltv.php".length).trimEnd('/'); else -> path }; val root = "$scheme://$authority${basePath.ifBlank { "" }}"; val alternate = "${if (scheme == "https") "http" else "https"}://$authority${basePath.ifBlank { "" }}"; return listOf(root, alternate).distinct() }
    private fun credentialsQuery(user: String, pass: String): String = "username=${URLEncoder.encode(user, "UTF-8")}&password=${URLEncoder.encode(pass, "UTF-8")}"
    private fun request(url: String, timeout: Int): String { val conn = URL(url).openConnection() as HttpURLConnection; return try { conn.connectTimeout = 4_500; conn.readTimeout = timeout; conn.instanceFollowRedirects = true; conn.requestMethod = "GET"; conn.setRequestProperty("Accept", "application/json, text/plain, */*"); conn.setRequestProperty("Accept-Encoding", "gzip"); conn.setRequestProperty("User-Agent", "USPortz/1.9"); if (conn.responseCode !in 200..299) return ""; openDecoded(conn).bufferedReader().use { it.readText().take(8 * 1024 * 1024) } } finally { conn.disconnect() } }

    suspend fun load(context: Context, forceRefresh: Boolean = false): List<SportsChannel> = withContext(Dispatchers.IO) {
        appContext = context.applicationContext
        val source = SourceStore(context)
        val server = normalizeXtreamServer(source.server)
        val user = source.user
        val pass = source.pass
        val playlist = source.playlist
        val sourceKey = sha256("$server\u0000$user\u0000$pass\u0000$playlist")
        val store = SportsChannelDiskStore(context)
        val diskSnapshot = runCatching { store.activeSnapshot(sourceKey) }.getOrDefault(emptyList())
        if (diskSnapshot.isNotEmpty() && (cachedSourceKey != sourceKey || cached.isEmpty())) {
            cached = diskSnapshot
            cachedAt = System.currentTimeMillis()
            cachedSourceKey = sourceKey
            cachedGeneration = store.activeGeneration(sourceKey)
        }
        if (forceRefresh) {
            if (!indexing.get()) refreshScope.launch { refreshInternal(context, server, user, pass, playlist, sourceKey) }
            return@withContext if (cachedSourceKey == sourceKey && cached.isNotEmpty()) cached else diskSnapshot
        }
        val age = System.currentTimeMillis() - cachedAt
        val needsRefresh = diskSnapshot.isEmpty() || cachedSourceKey != sourceKey || age > CACHE_TTL_MS
        if (needsRefresh && !indexing.get()) refreshScope.launch { refreshInternal(context, server, user, pass, playlist, sourceKey) }
        if (cachedSourceKey == sourceKey && cached.isNotEmpty()) cached else diskSnapshot
    }

    private suspend fun refreshInternal(context: Context, server: String, user: String, pass: String, playlist: String, sourceKey: String) = withContext(Dispatchers.IO) {
        if (!indexing.compareAndSet(false, true)) return@withContext
        try {
            val store = SportsChannelDiskStore(context)
            val generation = store.beginGeneration(sourceKey)
            val imported = AtomicInteger(0)
            var completedResponse = false
            fun accept(channel: SportsChannel) {
                synchronized(pending) {
                    pending += channel
                    val count = imported.incrementAndGet()
                    if (pending.size >= BATCH_SIZE) {
                        flush(store, sourceKey, generation)
                        store.updateRefreshProgress(sourceKey, generation, count)
                    }
                }
            }
            pending.clear()
            if (server.isNotBlank() && user.isNotBlank() && pass.isNotBlank()) {
                for (base in normalizedServerCandidates(server)) {
                    val before = imported.get()
                    try {
                        val categories = fetchLiveCategories(base, user, pass)
                        if (categories.isNotEmpty()) {
                            val parsed = fetchLiveStreamsByCategoryParallel(base, user, pass, categories, ::accept)
                            if (parsed > 0 || imported.get() > before) {
                                completedResponse = true
                                break
                            }
                        }
                        // Some older providers do not return usable categories. Fall back to the
                        // standard all-stream endpoint only in that case.
                        val parsed = fetchLiveStreamsStreaming(base, user, pass, categories, ::accept)
                        if (parsed > 0 || imported.get() > before) {
                            completedResponse = true
                            break
                        }
                    } catch (_: Throwable) { }
                }
                if (!completedResponse) for (base in normalizedServerCandidates(server)) {
                    try {
                        val parsed = fetchM3uStreaming("$base/get.php?${credentialsQuery(user, pass)}&type=m3u_plus&output=ts", ::accept)
                        if (parsed > 0) { completedResponse = true; break }
                    } catch (_: Throwable) { }
                }
            } else if (playlist.isNotBlank()) {
                completedResponse = runCatching { fetchM3uStreaming(playlist, ::accept) > 0 }.getOrDefault(false)
            }
            val count = imported.get()
            if (completedResponse && count > 0) {
                synchronized(pending) { flush(store, sourceKey, generation) }
                store.updateRefreshProgress(sourceKey, generation, count)
                store.activate(sourceKey, generation, count)
                val fresh = store.activeSnapshot(sourceKey)
                if (fresh.isNotEmpty()) {
                    cached = fresh
                    cachedAt = System.currentTimeMillis()
                    cachedSourceKey = sourceKey
                    cachedGeneration = generation
                }
            }
        } finally { synchronized(pending) { pending.clear() }; indexing.set(false) }
    }

    private val pending = ArrayList<SportsChannel>(BATCH_SIZE)

    private suspend fun fetchLiveStreamsByCategoryParallel(base: String, user: String, pass: String, categories: Map<String, String>, sink: (SportsChannel) -> Unit): Int = coroutineScope {
        val limiter = Dispatchers.IO.limitedParallelism(CATEGORY_PARALLELISM)
        categories.entries.map { entry ->
            async(limiter) {
                fetchLiveStreamsCategory(base, user, pass, entry.key, entry.value, sink)
            }
        }.awaitAll().sum()
    }

    private fun fetchLiveStreamsCategory(base: String, user: String, pass: String, categoryId: String, categoryName: String, sink: (SportsChannel) -> Unit): Int {
        val query = "${credentialsQuery(user, pass)}&action=get_live_streams&category_id=${URLEncoder.encode(categoryId, "UTF-8")}"
        for (endpoint in listOf("player_api.php", "panel_api.php")) {
            val conn = runCatching { URL("$base/$endpoint?$query").openConnection() as HttpURLConnection }.getOrNull() ?: continue
            try {
                conn.connectTimeout = 4_500
                conn.readTimeout = CATEGORY_READ_TIMEOUT_MS
                conn.instanceFollowRedirects = true
                conn.requestMethod = "GET"
                conn.setRequestProperty("Accept", "application/json, text/plain, */*")
                conn.setRequestProperty("Accept-Encoding", "gzip")
                conn.setRequestProperty("User-Agent", "USPortz/2.1 Android")
                if (conn.responseCode !in 200..299) continue
                return streamJson(openDecoded(conn), base, user, pass, mapOf(categoryId to categoryName), sink)
            } catch (_: Throwable) {
                // Try the alternate Xtream endpoint for this category.
            } finally { conn.disconnect() }
        }
        return 0
    }

    private fun flush(store: SportsChannelDiskStore, sourceKey: String, generation: Long) { if (pending.isNotEmpty()) { store.insertBatch(sourceKey, generation, pending.toList()); pending.clear() } }
    private fun fetchLiveCategories(base: String, user: String, pass: String): Map<String, String> { val query = "${credentialsQuery(user, pass)}&action=get_live_categories"; for (endpoint in listOf("player_api.php", "panel_api.php")) { val conn = runCatching { URL("$base/$endpoint?$query").openConnection() as HttpURLConnection }.getOrNull() ?: continue; try { conn.connectTimeout = 4_500; conn.readTimeout = 12_000; conn.instanceFollowRedirects = true; conn.requestMethod = "GET"; conn.setRequestProperty("Accept", "application/json, text/plain, */*"); conn.setRequestProperty("Accept-Encoding", "gzip"); conn.setRequestProperty("User-Agent", "USPortz/1.9"); if (conn.responseCode !in 200..299) continue; return parseCategoryNames(openDecoded(conn).bufferedReader().use { it.readText().take(2 * 1024 * 1024) }) } finally { conn.disconnect() } }; return emptyMap() }
    private fun parseCategoryNames(text: String): Map<String, String> { val out = HashMap<String, String>(); runCatching { val root = text.trim(); if (root.startsWith("[")) { val array = org.json.JSONArray(root); for (i in 0 until array.length()) { val item = array.optJSONObject(i) ?: continue; val id = item.optString("category_id").ifBlank { item.optString("id") }; val name = item.optString("category_name").ifBlank { item.optString("name") }; if (id.isNotBlank() && name.isNotBlank()) out[id] = name } } else { val obj = JSONObject(root); val array = obj.optJSONArray("categories") ?: obj.optJSONArray("data") ?: return@runCatching; for (i in 0 until array.length()) { val item = array.optJSONObject(i) ?: continue; val id = item.optString("category_id").ifBlank { item.optString("id") }; val name = item.optString("category_name").ifBlank { item.optString("name") }; if (id.isNotBlank() && name.isNotBlank()) out[id] = name } } }; return out }
    private fun fetchLiveStreamsStreaming(base: String, user: String, pass: String, categories: Map<String, String>, sink: (SportsChannel) -> Unit): Int { val query = "${credentialsQuery(user, pass)}&action=get_live_streams"; var lastError: Throwable? = null; for (endpoint in listOf("player_api.php", "panel_api.php")) { val conn = runCatching { URL("$base/$endpoint?$query").openConnection() as HttpURLConnection }.getOrNull() ?: continue; try { conn.connectTimeout = 4_500; conn.readTimeout = 60_000; conn.instanceFollowRedirects = true; conn.requestMethod = "GET"; conn.setRequestProperty("Accept", "application/json, text/plain, */*"); conn.setRequestProperty("Accept-Encoding", "gzip"); conn.setRequestProperty("User-Agent", "USPortz/1.9"); if (conn.responseCode !in 200..299) continue; return streamJson(openDecoded(conn), base, user, pass, categories, sink) } catch (t: Throwable) { lastError = t } finally { conn.disconnect() } }; if (lastError != null) throw lastError; return 0 }
    private fun streamJson(input: InputStream, base: String, user: String, pass: String, categories: Map<String, String>, sink: (SportsChannel) -> Unit): Int = input.use { stream -> JsonReader(InputStreamReader(stream)).use { reader -> when (reader.peek()) { JsonToken.BEGIN_ARRAY -> readStreamArray(reader, base, user, pass, categories, sink); JsonToken.BEGIN_OBJECT -> readStreamContainer(reader, base, user, pass, categories, sink); else -> 0 } } }
    private fun readStreamContainer(reader: JsonReader, base: String, user: String, pass: String, categories: Map<String, String>, sink: (SportsChannel) -> Unit): Int { var count = 0; reader.beginObject(); while (reader.hasNext()) { val key = reader.nextName(); if (key.equals("live_streams", true) || key.equals("streams", true) || key.equals("channels", true) || key.equals("data", true) || key.equals("items", true)) { count += when (reader.peek()) { JsonToken.BEGIN_ARRAY -> readStreamArray(reader, base, user, pass, categories, sink); JsonToken.BEGIN_OBJECT -> readStreamContainer(reader, base, user, pass, categories, sink); else -> { reader.skipValue(); 0 } } } else reader.skipValue() }; reader.endObject(); return count }
    private fun readStreamArray(reader: JsonReader, base: String, user: String, pass: String, categories: Map<String, String>, sink: (SportsChannel) -> Unit): Int { var count = 0; reader.beginArray(); while (reader.hasNext()) { val channel = readStreamObject(reader, base, user, pass, categories); if (channel != null) { sink(channel); count++ } }; reader.endArray(); return count }
    private fun readStreamObject(reader: JsonReader, base: String, user: String, pass: String, categories: Map<String, String>): SportsChannel? { if (reader.peek() != JsonToken.BEGIN_OBJECT) { reader.skipValue(); return null }; var id = ""; var name = ""; var group = "Live TV"; var logo: String? = null; var ext = "m3u8"; var direct = ""; var tvgId = ""; var tvgName = ""; var provider = "Xtream"; var categoryId = ""; reader.beginObject(); while (reader.hasNext()) when (reader.nextName().lowercase()) { "stream_id", "id", "channel_id" -> id = nextString(reader); "name", "title", "stream_display_name", "stream_name", "channel_name", "channel_title" -> name = nextString(reader); "tvg_name", "tvg-name", "epg_channel_name", "channel_display_name" -> tvgName = nextString(reader); "epg_channel_id", "tvg_id", "tvg-id" -> tvgId = nextString(reader); "category_name", "category", "group_title", "group-title", "group" -> group = nextString(reader).ifBlank { "Live TV" }; "category_id" -> categoryId = nextString(reader); "provider", "provider_name" -> provider = nextString(reader).ifBlank { provider }; "stream_icon", "icon", "logo" -> logo = nextString(reader).ifBlank { null }; "container_extension", "extension", "ext" -> ext = nextString(reader).ifBlank { "m3u8" }; "direct_source" -> direct = nextString(reader); else -> reader.skipValue() }; reader.endObject(); if (id.isBlank()) return null; val resolvedCategory = categories[categoryId].orEmpty().trim(); if (group.equals("Live TV", true) && resolvedCategory.isNotBlank()) group = resolvedCategory; val displayName = name.ifBlank { tvgName }.ifBlank { "Channel" }; val url = direct.ifBlank { "$base/live/$user/$pass/$id.$ext" }; return SportsChannel(id, displayName, group, logo, url, tvgName.ifBlank { displayName }, tvgId, group, provider.ifBlank { "Xtream" }) }
    private fun nextString(reader: JsonReader): String = when (reader.peek()) { JsonToken.NULL -> { reader.nextNull(); "" }; else -> runCatching { reader.nextString() }.getOrElse { reader.skipValue(); "" } }
    private fun fetchM3uStreaming(source: String, sink: (SportsChannel) -> Unit): Int { val conn = URL(source).openConnection() as HttpURLConnection; return try { conn.connectTimeout = 6_000; conn.readTimeout = 60_000; conn.instanceFollowRedirects = true; conn.requestMethod = "GET"; conn.setRequestProperty("Accept", "application/x-mpegURL, audio/x-mpegurl, text/plain, */*"); conn.setRequestProperty("Accept-Encoding", "gzip"); conn.setRequestProperty("User-Agent", "USPortz/1.9"); if (conn.responseCode !in 200..299) return 0; openDecoded(conn).bufferedReader().use { parseM3u(it, sink) } } finally { conn.disconnect() } }
    private fun parseM3u(reader: BufferedReader, sink: (SportsChannel) -> Unit): Int { var attrs = emptyMap<String, String>(); var count = 0; while (true) { val raw = reader.readLine() ?: break; val line = raw.trim(); if (line.isEmpty()) continue; when { line.startsWith("#EXTINF", true) -> attrs = parseAttrs(line); !line.startsWith("#") -> { val name = clean(attrs["name"]).ifBlank { clean(attrs["tvg-name"]) }.ifBlank { line.substringAfterLast('/').substringBefore('?').ifBlank { "Channel" } }; val tvgName = clean(attrs["tvg-name"]).ifBlank { name }; val tvgId = clean(attrs["tvg-id"]); val group = clean(attrs["group-title"]).ifBlank { clean(attrs["group"]) }.ifBlank { clean(attrs["category-name"]) }.ifBlank { "Live TV" }; val category = clean(attrs["category"]).ifBlank { clean(attrs["category-name"]) }.ifBlank { group }; val provider = clean(attrs["provider"]).ifBlank { clean(attrs["provider-name"]) }.ifBlank { "M3U" }; val logo = clean(attrs["tvg-logo"]).ifBlank { clean(attrs["logo"]) }.ifBlank { null }; sink(SportsChannel("${name.lowercase()}|$line".hashCode().toString(), name, group, logo, line, tvgName, tvgId, category, provider)); count++; attrs = emptyMap() } } }; return count }
    private fun parseAttrs(line: String): Map<String, String> { val map = mutableMapOf<String, String>(); Regex("([\\w-]+)=\"([^\"]*)\"").findAll(line).forEach { map[it.groupValues[1].lowercase()] = it.groupValues[2] }; line.indexOf(',').takeIf { it >= 0 }?.let { map["name"] = line.substring(it + 1).trim() }; return map }
    private fun openDecoded(conn: HttpURLConnection): InputStream { val raw = conn.inputStream; return if (conn.contentEncoding.equals("gzip", true)) GZIPInputStream(raw) else raw }
    fun cachedChannels(): List<SportsChannel> = cached
    fun isIndexing(): Boolean = indexing.get()
    fun bestMatch(event: SportsEvent, channels: List<SportsChannel>): SportsChannel? = SportsResolver.resolve(event, channels, 1).firstOrNull()?.channel
    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun clean(value: String?): String = value.orEmpty().trim()
}
