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

/**
 * Fast first-paint Xtream bootstrap.
 *
 * Instead of waiting for the provider's entire live catalogue, ask for the live
 * categories first, select sports categories, and stream only those category
 * responses into the local SQLite catalog. The normal full index can continue
 * in the background after the sports shelf is already usable.
 */
object FastXtreamSportsBootstrap {
    private const val CONNECT_TIMEOUT_MS = 3_500
    private const val READ_TIMEOUT_MS = 12_000
    private const val BATCH_SIZE = 250
    private const val MAX_SPORT_CATEGORIES = 16
    private val sportsWords = listOf(
        "sport", "sports", "espn", "fox sports", "fs1", "fs2", "tnt sports",
        "nbc sports", "cbs sports", "sportsnet", "tsn", "bein sports", "sky sport",
        "nfl", "nba", "mlb", "nhl", "ufc", "mma", "boxing", "fight", "soccer",
        "football", "basketball", "baseball", "hockey", "tennis", "golf", "racing",
        "motorsport", "formula 1", "f1", "nascar", "indycar", "wwe", "wrestling",
        "ppv"
    )

    suspend fun bootstrap(context: Context, server: String, user: String, pass: String): Int = withContext(Dispatchers.IO) {
        val base = SportsChannelBridge.normalizeXtreamServer(server)
        if (base.isBlank() || user.isBlank() || pass.isBlank()) return@withContext 0
        val categories = fetchCategories(base, user, pass)
        if (categories.isEmpty()) return@withContext 0
        val selected = categories.filter { isSportsCategory(it.name) }.take(MAX_SPORT_CATEGORIES)
        if (selected.isEmpty()) return@withContext 0

        val sourceKey = sha256("$base\u0000$user\u0000$pass\u0000")
        val store = SportsChannelDiskStore(context)
        val generation = store.beginGeneration(sourceKey)
        var total = 0

        val categoryResults = coroutineScope {
            selected.map { category ->
                async(Dispatchers.IO.limitedParallelism(4)) {
                    category to fetchCategoryStreams(base, user, pass, category)
                }
            }.awaitAll()
        }

        for ((category, channels) in categoryResults) {
            if (channels.isEmpty()) continue
            val batches = channels.chunked(BATCH_SIZE)
            for (batch in batches) {
                store.insertBatch(sourceKey, generation, batch)
                total += batch.size
                // Activate after every batch so the UI can see the first sports
                // channels immediately while later categories are still arriving.
                store.activate(sourceKey, generation, total)
            }
        }
        total
    }

    private data class Category(val id: String, val name: String)

    private fun isSportsCategory(name: String): Boolean {
        val n = name.lowercase()
        return sportsWords.any { n.contains(it) }
    }

    private fun fetchCategories(base: String, user: String, pass: String): List<Category> {
        val query = "username=${enc(user)}&password=${enc(pass)}&action=get_live_categories"
        for (endpoint in listOf("player_api.php", "panel_api.php")) {
            val body = requestText("$base/$endpoint?$query") ?: continue
            val array = runCatching { JSONArray(body) }.getOrNull() ?: continue
            val out = ArrayList<Category>(array.length())
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val id = item.optString("category_id").ifBlank { item.optString("id") }
                val name = item.optString("category_name").ifBlank { item.optString("name") }.trim()
                if (id.isNotBlank() && name.isNotBlank()) out += Category(id, name)
            }
            if (out.isNotEmpty()) return out
        }
        return emptyList()
    }

    private fun fetchCategoryStreams(base: String, user: String, pass: String, category: Category): List<SportsChannel> {
        val query = "username=${enc(user)}&password=${enc(pass)}&action=get_live_streams&category_id=${enc(category.id)}"
        for (endpoint in listOf("player_api.php", "panel_api.php")) {
            val conn = runCatching { URL("$base/$endpoint?$query").openConnection() as HttpURLConnection }.getOrNull() ?: continue
            try {
                conn.connectTimeout = CONNECT_TIMEOUT_MS
                conn.readTimeout = READ_TIMEOUT_MS
                conn.instanceFollowRedirects = true
                conn.requestMethod = "GET"
                conn.setRequestProperty("Accept", "application/json, text/plain, */*")
                conn.setRequestProperty("Accept-Encoding", "gzip")
                conn.setRequestProperty("User-Agent", "USPortz/2.0 Android")
                if (conn.responseCode !in 200..299) continue
                val parsed = parseStreams(conn.inputStream, base, user, pass, category.name)
                if (parsed.isNotEmpty()) return parsed
            } catch (_: Exception) {
                // Try the alternate Xtream endpoint before falling back to the full index.
            } finally {
                conn.disconnect()
            }
        }
        return emptyList()
    }

    private fun parseStreams(input: InputStream, base: String, user: String, pass: String, categoryName: String): List<SportsChannel> = input.use { stream ->
        JsonReader(InputStreamReader(stream)).use { reader ->
            when (reader.peek()) {
                JsonToken.BEGIN_ARRAY -> readArray(reader, base, user, pass, categoryName)
                JsonToken.BEGIN_OBJECT -> {
                    var result = emptyList<SportsChannel>()
                    reader.beginObject()
                    while (reader.hasNext()) {
                        val key = reader.nextName()
                        if (key.equals("live_streams", true) || key.equals("streams", true) || key.equals("channels", true) || key.equals("data", true)) {
                            if (reader.peek() == JsonToken.BEGIN_ARRAY) result = readArray(reader, base, user, pass, categoryName)
                            else reader.skipValue()
                        } else reader.skipValue()
                    }
                    reader.endObject()
                    result
                }
                else -> emptyList()
            }
        }
    }

    private fun readArray(reader: JsonReader, base: String, user: String, pass: String, categoryName: String): List<SportsChannel> {
        val out = ArrayList<SportsChannel>()
        reader.beginArray()
        while (reader.hasNext()) {
            val channel = readChannel(reader, base, user, pass, categoryName)
            if (channel != null && SportsNetworkCatalog.find(channel) != null) out += channel
        }
        reader.endArray()
        return out
    }

    private fun readChannel(reader: JsonReader, base: String, user: String, pass: String, categoryName: String): SportsChannel? {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) { reader.skipValue(); return null }
        var id = ""
        var name = ""
        var tvgName = ""
        var tvgId = ""
        var logo: String? = null
        var ext = "m3u8"
        var direct = ""
        var group = categoryName
        var provider = "Xtream"
        reader.beginObject()
        while (reader.hasNext()) when (reader.nextName().lowercase()) {
            "stream_id", "id" -> id = nextString(reader)
            "name", "title" -> name = nextString(reader)
            "tvg_name", "tvg-name", "epg_channel_name" -> tvgName = nextString(reader)
            "epg_channel_id", "tvg_id", "tvg-id" -> tvgId = nextString(reader)
            "category_name", "category" -> group = nextString(reader).ifBlank { group }
            "stream_icon", "icon", "logo" -> logo = nextString(reader).ifBlank { null }
            "container_extension" -> ext = nextString(reader).ifBlank { "m3u8" }
            "direct_source" -> direct = nextString(reader)
            "provider", "provider_name" -> provider = nextString(reader).ifBlank { provider }
            else -> reader.skipValue()
        }
        reader.endObject()
        if (id.isBlank()) return null
        val displayName = name.ifBlank { tvgName }.ifBlank { "Channel" }
        val url = direct.ifBlank { "$base/live/$user/$pass/$id.$ext" }
        return SportsChannel(id, displayName, group, logo, url, tvgName.ifBlank { displayName }, tvgId, group, provider)
    }

    private fun nextString(reader: JsonReader): String = when (reader.peek()) {
        JsonToken.NULL -> { reader.nextNull(); "" }
        else -> runCatching { reader.nextString() }.getOrElse { reader.skipValue(); "" }
    }

    private fun requestText(url: String): String? = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.instanceFollowRedirects = true
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/json, text/plain, */*")
            conn.setRequestProperty("Accept-Encoding", "gzip")
            conn.setRequestProperty("User-Agent", "USPortz/2.0 Android")
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.bufferedReader().use { it.readText().take(2 * 1024 * 1024) }
        } finally { conn.disconnect() }
    }.getOrNull()

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
