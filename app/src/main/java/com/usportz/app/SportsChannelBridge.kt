package com.usportz.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    @Volatile private var cached: List<SportsChannel> = emptyList()
    @Volatile private var cachedAt = 0L

    suspend fun load(context: Context, forceRefresh: Boolean = false): List<SportsChannel> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!forceRefresh && cached.isNotEmpty() && now - cachedAt < CACHE_TTL_MS) return@withContext cached

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
            result
        } else cached
    }

    fun bestMatch(event: SportsEvent, channels: List<SportsChannel>): SportsChannel? =
        channels.asSequence()
            .map { it to SportsSchedule.matchChannel(event, it.name, it.group) }
            .filter { it.second > 0 }
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
        Regex("([\\w-]+)=\\\"([^\\\"]*)\\\"").findAll(line).forEach {
            map[it.groupValues[1].lowercase()] = it.groupValues[2]
        }
        val comma = line.indexOf(',')
        if (comma >= 0) map["name"] = line.substring(comma + 1).trim()
        return map
    }
}
