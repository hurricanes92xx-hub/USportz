package com.usportz.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Optional licensed TV-broadcast enrichment for USportz.
 *
 * Ronin Sport (the owner of LiveSportsOnTV.com) offers REST/JSON sports-TV feeds.
 * USportz deliberately does not scrape LiveSportsOnTV.com. A licensed feed or a
 * server-side proxy should be configured through the local SourceStore settings.
 *
 * Expected feed shape is intentionally tolerant: the parser accepts either an
 * array at the root or common `events` / `matches` / `data` containers and maps
 * common event, league, channel and broadcast fields.
 */
object RoninTvSchedule {
    private const val PREFS = "usportz_tv_data"
    private const val URL_KEY = "ronin_feed_url"
    private const val TOKEN_KEY = "ronin_feed_token"
    private const val TIMEOUT_MS = 5000

    suspend fun load(context: Context): List<SportsEvent> = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val endpoint = prefs.getString(URL_KEY, "")?.trim().orEmpty()
        if (endpoint.isBlank()) return@withContext emptyList()
        runCatching {
            val connection = URL(endpoint).openConnection() as HttpURLConnection
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            prefs.getString(TOKEN_KEY, "")?.trim()?.takeIf { it.isNotBlank() }?.let {
                connection.setRequestProperty("Authorization", "Bearer $it")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()
            parse(body)
        }.getOrDefault(emptyList())
    }

    fun configure(context: Context, endpoint: String, token: String = "") {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(URL_KEY, endpoint.trim())
            .putString(TOKEN_KEY, token.trim())
            .apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun parse(body: String): List<SportsEvent> {
        val root = body.trim().let { if (it.startsWith("[") ) null else JSONObject(it) }
        val array = when {
            body.trim().startsWith("[") -> JSONArray(body)
            root?.optJSONArray("events") != null -> root.optJSONArray("events")!!
            root?.optJSONArray("matches") != null -> root.optJSONArray("matches")!!
            root?.optJSONArray("data") != null -> root.optJSONArray("data")!!
            else -> JSONArray()
        }
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val competitors = readCompetitors(item)
                val name = first(item, "name", "eventName", "title", "matchName").ifBlank {
                    competitors.joinToString(" vs ")
                }
                if (name.isBlank()) continue
                val league = first(item, "league", "competition", "tournament", "leagueName")
                val sport = first(item, "sport", "sportName").ifBlank { inferSport(league) }
                val broadcast = first(item, "channel", "tvChannel", "network", "broadcaster", "broadcast")
                val start = first(item, "startTime", "start", "date", "dateTime", "startDate")
                val id = first(item, "id", "eventId", "matchId").ifBlank { "ronin-${name.hashCode()}-$start" }
                val logos = readLogos(item)
                add(
                    SportsEvent(
                        id = id,
                        sport = sport.ifBlank { "sports" },
                        league = league.ifBlank { "TV Schedule" },
                        name = name,
                        shortName = name,
                        state = inferState(item),
                        startTime = start,
                        competitors = competitors,
                        competitorLogos = logos,
                        leagueLogo = first(item, "leagueLogo", "competitionLogo", "logo").ifBlank { null },
                        detail = first(item, "detail", "status", "description"),
                        broadcast = broadcast
                    )
                )
            }
        }
    }

    private fun readCompetitors(item: JSONObject): List<String> {
        val array = item.optJSONArray("competitors") ?: item.optJSONArray("teams")
        if (array != null) return (0 until array.length()).mapNotNull { index ->
            val value = array.opt(index)
            when (value) {
                is JSONObject -> first(value, "name", "displayName", "teamName", "shortName").takeIf { it.isNotBlank() }
                is String -> value.takeIf { it.isNotBlank() }
                else -> null
            }
        }
        return listOfNotNull(
            first(item, "homeTeam", "home", "homeName").takeIf { it.isNotBlank() },
            first(item, "awayTeam", "away", "awayName").takeIf { it.isNotBlank() }
        )
    }

    private fun readLogos(item: JSONObject): List<String> = listOfNotNull(
        first(item, "homeLogo", "homeTeamLogo").takeIf { it.isNotBlank() },
        first(item, "awayLogo", "awayTeamLogo").takeIf { it.isNotBlank() }
    )

    private fun first(item: JSONObject, vararg keys: String): String = keys.firstNotNullOfOrNull {
        item.optString(it).takeIf { value -> value.isNotBlank() }
    }.orEmpty()

    private fun inferSport(league: String): String = when {
        league.contains("football", true) || league.equals("NFL", true) -> "football"
        league.contains("basket", true) || league.contains("NBA", true) -> "basketball"
        league.contains("baseball", true) || league.equals("MLB", true) -> "baseball"
        league.contains("hockey", true) || league.equals("NHL", true) -> "hockey"
        league.contains("tennis", true) || league.contains("ATP", true) || league.contains("WTA", true) -> "tennis"
        league.contains("golf", true) || league.contains("PGA", true) -> "golf"
        league.contains("motor", true) || league.contains("F1", true) || league.contains("NASCAR", true) -> "racing"
        league.contains("UFC", true) || league.contains("fighting", true) -> "mma"
        else -> "soccer"
    }

    private fun inferState(item: JSONObject): String {
        val status = first(item, "state", "status", "eventStatus").lowercase()
        return when {
            status.contains("live") || status == "in" || status.contains("progress") -> "in"
            status.contains("final") || status.contains("complete") || status == "post" -> "post"
            else -> "pre"
        }
    }
}
