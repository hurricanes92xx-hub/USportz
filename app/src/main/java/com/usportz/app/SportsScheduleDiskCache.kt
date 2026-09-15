package com.usportz.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant

/** Small last-known schedule snapshot so a cold start never has to begin with a blank screen. */
object SportsScheduleDiskCache {
    private const val FILE_NAME = "sports_schedule_snapshot.json"
    private const val MAX_EVENTS = 1200

    fun read(context: Context): List<SportsEvent> = runCatching {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return emptyList()
        val array = JSONArray(file.readText())
        val events = buildList {
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val competitors = o.optJSONArray("competitors")?.let { a -> buildList { for (j in 0 until a.length()) add(a.optString(j)) } } ?: emptyList()
                val logos = o.optJSONArray("competitorLogos")?.let { a -> buildList { for (j in 0 until a.length()) add(a.optString(j)) } } ?: emptyList()
                val start = o.optString("startTime")
                if (o.optString("id").isBlank() || start.isBlank()) continue
                val event = SportsEvent(
                    id = o.optString("id"), sport = o.optString("sport"), league = o.optString("league"),
                    name = o.optString("name"), shortName = o.optString("shortName"), state = o.optString("state", "pre"),
                    startTime = start, competitors = competitors, competitorLogos = logos,
                    leagueLogo = o.optString("leagueLogo").ifBlank { null }, detail = o.optString("detail"),
                    broadcast = o.optString("broadcast")
                )
                if (isPlausible(event)) add(event)
            }
        }
        dedupe(events).take(MAX_EVENTS)
    }.getOrDefault(emptyList())

    fun write(context: Context, events: List<SportsEvent>) {
        runCatching {
            val array = JSONArray()
            dedupe(events.filter(::isPlausible)).take(MAX_EVENTS).forEach { event ->
                val o = JSONObject()
                    .put("id", event.id)
                    .put("sport", event.sport)
                    .put("league", event.league)
                    .put("name", event.name)
                    .put("shortName", event.shortName)
                    .put("state", event.state)
                    .put("startTime", event.startTime)
                    .put("leagueLogo", event.leagueLogo.orEmpty())
                    .put("detail", event.detail)
                    .put("broadcast", event.broadcast)
                o.put("competitors", JSONArray(event.competitors))
                o.put("competitorLogos", JSONArray(event.competitorLogos))
                array.put(o)
            }
            val target = File(context.filesDir, FILE_NAME)
            val temp = File(context.filesDir, "$FILE_NAME.tmp")
            temp.writeText(array.toString())
            if (!temp.renameTo(target)) {
                target.delete()
                temp.renameTo(target)
            }
        }
    }

    private fun isPlausible(event: SportsEvent): Boolean {
        if (event.sport.equals("racing", true)) {
            val text = (event.name + " " + event.league + " " + event.competitors.joinToString(" ")).lowercase()
            val collegeTeamMarkers = listOf("university", "college", "ncaa", "knights", "pioneers", "bulldogs", "wildcats", "spartans", "terriers", "bears")
            if (collegeTeamMarkers.any(text::contains)) return false
        }
        return true
    }

    private fun dedupe(events: List<SportsEvent>): List<SportsEvent> {
        val best = LinkedHashMap<String, SportsEvent>()
        for (event in events) {
            val teams = event.competitors.map(::normalize).filter { it.isNotBlank() }.sorted().joinToString("|")
            val minute = runCatching { Instant.parse(event.startTime).toEpochMilli() / 60_000L }.getOrElse { event.startTime.take(16) }
            val key = "${normalize(event.sport)}|$teams|$minute"
            val old = best[key]
            if (old == null || quality(event) > quality(old)) best[key] = event
        }
        return best.values.toList()
    }

    private fun quality(event: SportsEvent): Int =
        event.competitorLogos.count { it.isNotBlank() } * 10 +
            (if (event.leagueLogo?.isNotBlank() == true) 4 else 0) +
            (if (event.broadcast.isNotBlank()) 3 else 0) +
            (if (event.detail.isNotBlank()) 1 else 0)

    private fun normalize(value: String): String = value.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
}
