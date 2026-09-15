package com.usportz.app

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.security.MessageDigest
import java.net.URL

/** Fast first-paint sports catalog. Uses category endpoints only as an optional accelerator. */
object FastXtreamSportsBootstrap {
    private const val CONNECT_TIMEOUT_MS = 3_500
    private const val READ_TIMEOUT_MS = 12_000
    private const val BATCH_SIZE = 1000
    private const val FIRST_PUBLISH_SIZE = 48
    private const val MAX_SPORT_CATEGORIES = 12
    private const val CATEGORY_CONCURRENCY = 6
    private val sportsWords = listOf("sport", "sports", "espn", "fox sports", "fs1", "fs2", "tnt sports", "nbc sports", "cbs sports", "sportsnet", "tsn", "bein sports", "sky sport", "nfl", "nba", "mlb", "nhl", "ufc", "mma", "boxing", "fight", "soccer", "football", "basketball", "baseball", "hockey", "tennis", "golf", "racing", "motorsport", "formula 1", "f1", "nascar", "indycar", "wwe", "wrestling", "ppv", "event")
    private val eventFeedWords = listOf("ncaaf", "ncaab", "ncaaw", "ncaa", "college football", "college basketball", "college", "nfl ", "nba ", "nhl ", "mlb ", "cfl ", "ufc ", "wwe ", "aew ", " ppv", "events-only", "event 01", "event 02", "event 03", "event 04", "feed")

    suspend fun bootstrap(context: Context, server: String, user: String, pass: String): Int = withContext(Dispatchers.IO) {
        val base = SportsChannelBridge.normalizeXtreamServer(server)
        if (base.isBlank() || user.isBlank() || pass.isBlank()) return@withContext 0
        val categories = fetchCategories(base, user, pass)
        if (categories.isEmpty()) return@withContext 0
        // Startup is about getting playable sports into the UI, not exhaustively scanning
        // every sports-looking bucket. Prefer the most specific/likely categories first.
        val selected = categories
            .filter { isSportsCategory(it.name) }
            .sortedByDescending { categoryPriority(it.name) }
            .take(MAX_SPORT_CATEGORIES)
        if (selected.isEmpty()) return@withContext 0
        val sourceKey = sha256("$base\u0000$user\u0000$pass\u0000")
        val store = SportsChannelDiskStore(context)
        val hadActiveSnapshot = store.activeCount(sourceKey) > 0
        val generation = store.beginGeneration(sourceKey)
        var total = 0
        var published = hadActiveSnapshot
        var pending = ArrayList<SportsChannel>(BATCH_SIZE)

        fun flush() {
            if (pending.isEmpty()) return
            store.insertBatch(sourceKey, generation, pending)
            total += pending.size
            pending.clear()
            if (!published && total >= FIRST_PUBLISH_SIZE) {
                store.activate(sourceKey, generation, total)
                published = true
            }
        }

        // Fetch category endpoints concurrently, but stream each response directly into SQLite.
        // This keeps peak RAM bounded and avoids the provider's slow uncategorized mega-response.
        val categoryResults = coroutineScope {
            selected.map { category ->
                async(Dispatchers.IO.limitedParallelism(CATEGORY_CONCURRENCY)) {
                    streamCategoryStreams(base, user, pass, category) { channel ->
                        synchronized(pending) {
                            pending += channel
                            if (pending.size >= BATCH_SIZE) flush()
                        }
                    }
                }
            }.awaitAll()
        }
        synchronized(pending) { flush() }
        if (total > 0) {
            store.activate(sourceKey, generation, total)
        }
        total
    }

    private data class Category(val id: String, val name: String)
    private fun isSportsCategory(name: String) = sportsWords.any { name.contains(it, true) }
    private fun categoryPriority(name: String): Int {
        val n = name.lowercase()
        return when {
            listOf("espn", "fox sports", "fs1", "fs2", "nbc sports", "cbs sports", "sportsnet", "tsn", "tnt sports").any { n.contains(it) } -> 100
            listOf("nfl", "nba", "mlb", "nhl", "ufc", "wwe", "tennis", "golf", "soccer", "football", "basketball", "baseball", "hockey").any { n.contains(it) } -> 90
            listOf("ncaa", "college", "ppv", "event", "feed").any { n.contains(it) } -> 80
            n.contains("sport") -> 70
            else -> 50
        }
    }
    private fun isResolverSportsChannel(channel: SportsChannel): Boolean {
        if (SportsNetworkCatalog.find(channel) != null) return true
        val metadata = listOf(channel.name, channel.tvgName, channel.tvgId, channel.group, channel.category, channel.provider).joinToString(" ").lowercase()
        return eventFeedWords.any { metadata.contains(it) }
    }
    private fun fetchCategories(base: String, user: String, pass: String): List<Category> {
        val query = "username=${enc(user)}&password=${enc(pass)}&action=get_live_categories"
        for (endpoint in listOf("player_api.php", "panel_api.php")) {
            val body = requestText("$base/$endpoint?$query") ?: continue
            val array = runCatching { JSONArray(body) }.getOrNull() ?: continue
            val out = ArrayList<Category>(array.length())
            for (i in 0 until array.length()) { val item = array.optJSONObject(i) ?: continue; val id = item.optString("category_id").ifBlank { item.optString("id") }; val name = item.optString("category_name").ifBlank { item.optString("name") }.trim(); if (id.isNotBlank() && name.isNotBlank()) out += Category(id, name) }
            if (out.isNotEmpty()) return out
        }
        return emptyList()
    }
    private fun streamCategoryStreams(base: String, user: String, pass: String, category: Category, sink: (SportsChannel) -> Unit): Int {
        val query = "username=${enc(user)}&password=${enc(pass)}&action=get_live_streams&category_id=${enc(category.id)}"
        for (endpoint in listOf("player_api.php", "panel_api.php")) {
            val conn = runCatching { URL("$base/$endpoint?$query").openConnection() as HttpURLConnection }.getOrNull() ?: continue
            try {
                conn.connectTimeout = CONNECT_TIMEOUT_MS; conn.readTimeout = READ_TIMEOUT_MS; conn.instanceFollowRedirects = true; conn.requestMethod = "GET"
                conn.setRequestProperty("Accept", "application/json, text/plain, */*"); conn.setRequestProperty("Accept-Encoding", "gzip"); conn.setRequestProperty("User-Agent", "USPortz/2.1 Android")
                if (conn.responseCode !in 200..299) continue
                val parsed = parseStreams(conn.inputStream, base, user, pass, category.name, sink)
                if (parsed > 0) return parsed
            } catch (_: Exception) {} finally { conn.disconnect() }
        }
        return 0
    }
    private fun parseStreams(input: InputStream, base: String, user: String, pass: String, categoryName: String, sink: (SportsChannel) -> Unit): Int = input.use { stream -> JsonReader(InputStreamReader(stream)).use { reader -> when (reader.peek()) { JsonToken.BEGIN_ARRAY -> readArray(reader, base, user, pass, categoryName, sink); JsonToken.BEGIN_OBJECT -> { var count = 0; reader.beginObject(); while (reader.hasNext()) { val key = reader.nextName(); if (key.equals("live_streams", true) || key.equals("streams", true) || key.equals("channels", true) || key.equals("data", true)) { if (reader.peek() == JsonToken.BEGIN_ARRAY) count += readArray(reader, base, user, pass, categoryName, sink) else reader.skipValue() } else reader.skipValue() }; reader.endObject(); count }; else -> 0 } } }
    private fun readArray(reader: JsonReader, base: String, user: String, pass: String, categoryName: String, sink: (SportsChannel) -> Unit): Int { var count = 0; reader.beginArray(); while (reader.hasNext()) { val channel = readChannel(reader, base, user, pass, categoryName); if (channel != null && isResolverSportsChannel(channel)) { sink(channel); count++ } }; reader.endArray(); return count }
    private fun readChannel(reader: JsonReader, base: String, user: String, pass: String, categoryName: String): SportsChannel? { if (reader.peek() != JsonToken.BEGIN_OBJECT) { reader.skipValue(); return null }; var id = ""; var name = ""; var tvgName = ""; var tvgId = ""; var logo: String? = null; var ext = "m3u8"; var direct = ""; var group = categoryName; var provider = "Xtream"; reader.beginObject(); while (reader.hasNext()) when (reader.nextName().lowercase()) { "stream_id", "id" -> id = nextString(reader); "name", "title" -> name = nextString(reader); "tvg_name", "tvg-name", "epg_channel_name" -> tvgName = nextString(reader); "epg_channel_id", "tvg_id", "tvg-id" -> tvgId = nextString(reader); "category_name", "category" -> group = nextString(reader).ifBlank { group }; "stream_icon", "icon", "logo" -> logo = nextString(reader).ifBlank { null }; "container_extension" -> ext = nextString(reader).ifBlank { "m3u8" }; "direct_source" -> direct = nextString(reader); "provider", "provider_name" -> provider = nextString(reader).ifBlank { provider }; else -> reader.skipValue() }; reader.endObject(); if (id.isBlank()) return null; val displayName = name.ifBlank { tvgName }.ifBlank { "Channel" }; val url = direct.ifBlank { "$base/live/$user/$pass/$id.$ext" }; return SportsChannel(id, displayName, group, logo, url, tvgName.ifBlank { displayName }, tvgId, group, provider) }
    private fun nextString(reader: JsonReader): String = when (reader.peek()) { JsonToken.NULL -> { reader.nextNull(); "" }; else -> runCatching { reader.nextString() }.getOrElse { reader.skipValue(); "" } }
    private fun requestText(url: String): String? = runCatching { val conn = URL(url).openConnection() as HttpURLConnection; try { conn.connectTimeout = CONNECT_TIMEOUT_MS; conn.readTimeout = READ_TIMEOUT_MS; conn.instanceFollowRedirects = true; conn.requestMethod = "GET"; conn.setRequestProperty("Accept", "application/json, text/plain, */*" ); conn.setRequestProperty("Accept-Encoding", "gzip"); conn.setRequestProperty("User-Agent", "USPortz/2.1 Android"); if (conn.responseCode !in 200..299) return null; conn.inputStream.bufferedReader().use { it.readText().take(2 * 1024 * 1024) } } finally { conn.disconnect() } }.getOrNull()
    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")
    private fun sha256(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
