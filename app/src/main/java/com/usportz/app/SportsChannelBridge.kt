package com.usportz.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Lightweight bridge from saved Xtream/M3U settings into the rich sports hub. */
data class SportsChannel(
    val id: String,
    val name: String,
    val group: String,
    val logo: String?,
    val url: String
)

object SportsChannelBridge {
    private const val CACHE_TTL_MS = 5 * 60 * 1000L
    private const val CACHE_FILE = "channel-index.json"
    @Volatile private var cached: List<SportsChannel> = emptyList()
    @Volatile private var cachedAt = 0L

    /** Restores the last local channel index without touching the network. */
    fun restoreCached(context: Context): List<SportsChannel> {
        if (cached.isNotEmpty()) return cached
        val file = File(context.noBackupFilesDir, CACHE_FILE)
        val restored = runCatching {
            if (!file.exists()) return@runCatching emptyList()
            val array = JSONArray(file.readText())
            buildList(array.length()) {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val url = item.optString("url")
                    if (url.isBlank()) continue
                    add(
                        SportsChannel(
                            item.optString("id"), item.optString("name", "Channel"),
                            item.optString("group", "Live TV"), item.optString("logo").ifBlank { null }, url
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
        if (restored.isNotEmpty()) {
            cached = restored
            cachedAt = file.lastModified()
        }
        return cached
    }

    suspend fun load(context: Context, forceRefresh: Boolean = false): List<SportsChannel> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (cached.isEmpty()) restoreCached(context)
        if (!forceRefresh && cached.isNotEmpty() && cachedAt > 0L && now - cachedAt < CACHE_TTL_MS) return@withContext cached

        val prefs = context.getSharedPreferences("usportz", Context.MODE_PRIVATE)
        val server = prefs.getString("server", "").orEmpty().trimEnd('/')
        val user = prefs.getString("user", "").orEmpty()
        val pass = prefs.getString("pass", "").orEmpty()
        val playlist = prefs.getString("playlist", "").orEmpty()
        val source = when {
            server.isNotBlank() && user.isNotBlank() && pass.isNotBlank() ->
                "$server/get.php?username=${URLEncoder.encode(user, "UTF-8")}&password=${URLEncoder.encode(pass, "UTF-8")}&type=m3u_plus&output=ts"
            playlist.isNotBlank() -> playlist
            else -> return@withContext cached
        }

        val result = runCatching { parse(fetch(source)) }.getOrDefault(emptyList())
        if (result.isNotEmpty()) {
            cached = result
            cachedAt = now
            persist(context, result)
            result
        } else cached
    }

    /** Current source inventory, populated by the premium shell before schedule loading. */
    fun cachedChannels(): List<SportsChannel> = cached

    fun bestMatch(event: SportsEvent, channels: List<SportsChannel>): SportsChannel? =
        channels.asSequence()
            .map { it to SportsSchedule.matchChannel(event, it.name, it.group) }
            .filter { it.second >= 5 }
            .maxByOrNull { it.second }
            ?.first

    private fun fetch(source: String): String {
        val conn = URL(source).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 12000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "USportz/1.0")
        return try {
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun persist(context: Context, channels: List<SportsChannel>) {
        val array = JSONArray()
        channels.forEach { channel ->
            array.put(
                JSONObject().apply {
                    put("id", channel.id)
                    put("name", channel.name)
                    put("group", channel.group)
                    put("logo", channel.logo ?: "")
                    put("url", channel.url)
                }
            )
        }
        val target = File(context.noBackupFilesDir, CACHE_FILE)
        val temp = File(context.noBackupFilesDir, "$CACHE_FILE.tmp")
        runCatching {
            temp.writeText(array.toString())
            if (!temp.renameTo(target)) {
                target.delete()
                temp.renameTo(target)
            }
        }
    }

    private fun parse(text: String): List<SportsChannel> {
        val lines = text.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        val result = ArrayList<SportsChannel>(minOf(3000, lines.size / 2))
        var attrs = emptyMap<String, String>()
        for (line in lines) {
            when {
                line.startsWith("#EXTINF", true) -> attrs = parseAttrs(line)
                !line.startsWith("#") -> {
                    val name = attrs["name"] ?: line.substringAfterLast('/').substringBefore('?').ifBlank { "Channel" }
                    val group = attrs["group"] ?: "Live TV"
                    val logo = attrs["logo"]
                    result += SportsChannel("${name.lowercase()}|$line".hashCode().toString(), name, group, logo, line)
                    attrs = emptyMap()
                    if (result.size >= 3000) break
                }
            }
        }
        return result.distinctBy { it.id }
    }

    private fun parseAttrs(line: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        Regex("([\\w-]+)=\"([^\"]*)\"").findAll(line).forEach {
            map[it.groupValues[1].lowercase()] = it.groupValues[2]
        }
        val comma = line.indexOf(',')
        if (comma >= 0) map["name"] = line.substring(comma + 1).trim()
        return map
    }
}
