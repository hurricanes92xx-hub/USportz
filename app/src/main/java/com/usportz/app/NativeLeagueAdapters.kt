package com.usportz.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

/** Keyless native adapters. They only produce SportsEvent; Xtream remains the playback source. */
object NativeLeagueAdapters {
    object Mlb : SportsEventProvider {
        override val id = "mlb-native"
        override val priority = 90
        override suspend fun load(): List<SportsEvent> = withContext(Dispatchers.IO) {
            val today = LocalDate.now(); val url = "https://statsapi.mlb.com/api/v1/schedule?sportId=1&startDate=$today&endDate=${today.plusDays(7)}&hydrate=team"
            runCatching { parseMlb(get(url)) }.getOrDefault(emptyList())
        }
        private fun parseMlb(body: String): List<SportsEvent> {
            val root = JSONObject(body); val dates = root.optJSONArray("dates") ?: return emptyList(); val out = ArrayList<SportsEvent>()
            for (i in 0 until dates.length()) { val games = dates.optJSONObject(i)?.optJSONArray("games") ?: continue; for (j in 0 until games.length()) { val g = games.optJSONObject(j) ?: continue; val t = g.optJSONObject("teams") ?: continue; val a = t.optJSONObject("away")?.optJSONObject("team")?.optString("name").orEmpty(); val h = t.optJSONObject("home")?.optJSONObject("team")?.optString("name").orEmpty(); if (a.isBlank() || h.isBlank()) continue; val state = g.optJSONObject("status")?.optString("abstractGameState").orEmpty().lowercase(); out += SportsEvent("mlb-native:${g.optString("gamePk")}", "baseball", "MLB", "$a at $h", "$a  •  $h", when (state) { "live" -> "in"; "final", "completed" -> "post"; else -> "pre" }, g.optString("gameDate"), listOf(a, h), emptyList(), null, g.optJSONObject("status")?.optString("detailedState").orEmpty()) } }
            return out
        }
    }

    object Nhl : SportsEventProvider {
        override val id = "nhl-native"
        override val priority = 89
        override suspend fun load(): List<SportsEvent> = withContext(Dispatchers.IO) {
            val url = "https://api-web.nhle.com/v1/schedule/now"
            runCatching { parseNhl(get(url)) }.getOrDefault(emptyList())
        }
        private fun parseNhl(body: String): List<SportsEvent> {
            val root = JSONObject(body); val days = root.optJSONArray("gameWeek") ?: return emptyList(); val out = ArrayList<SportsEvent>()
            for (i in 0 until days.length()) { val day = days.optJSONObject(i) ?: continue; val games = day.optJSONArray("games") ?: continue; for (j in 0 until games.length()) { val g = games.optJSONObject(j) ?: continue; val away = g.optJSONObject("awayTeam")?.optString("placeName").orEmpty(); val home = g.optJSONObject("homeTeam")?.optString("placeName").orEmpty(); if (away.isBlank() || home.isBlank()) continue; val state = g.optString("gameState").lowercase(); out += SportsEvent("nhl-native:${g.optString("id")}", "hockey", "NHL", "$away at $home", "$away  •  $home", when (state) { "live", "crit" -> "in"; "final", "off" -> "post"; else -> "pre" }, g.optString("startTimeUTC"), listOf(away, home), emptyList(), null, state) } }
            return out
        }
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        return try { c.connectTimeout = 2500; c.readTimeout = 4500; c.setRequestProperty("Accept", "application/json"); c.setRequestProperty("User-Agent", "USPortz/2.1 Android"); if (c.responseCode !in 200..299) "" else c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
    }
}
