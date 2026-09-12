package com.usportz.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Small last-known schedule snapshot so a cold start never has to begin with a blank screen. */
object SportsScheduleDiskCache {
    private const val FILE_NAME = "sports_schedule_snapshot.json"
    private const val MAX_EVENTS = 1200

    fun read(context: Context): List<SportsEvent> = runCatching {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return emptyList()
        val array = JSONArray(file.readText())
        buildList {
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val competitors = o.optJSONArray("competitors")?.let { a -> buildList { for (j in 0 until a.length()) add(a.optString(j)) } } ?: emptyList()
                val logos = o.optJSONArray("competitorLogos")?.let { a -> buildList { for (j in 0 until a.length()) add(a.optString(j)) } } ?: emptyList()
                val start = o.optString("startTime")
                if (o.optString("id").isBlank() || start.isBlank()) continue
                add(SportsEvent(
                    id = o.optString("id"), sport = o.optString("sport"), league = o.optString("league"),
                    name = o.optString("name"), shortName = o.optString("shortName"), state = o.optString("state", "pre"),
                    startTime = start, competitors = competitors, competitorLogos = logos,
                    leagueLogo = o.optString("leagueLogo").ifBlank { null }, detail = o.optString("detail"),
                    broadcast = o.optString("broadcast")
                ))
            }
        }.take(MAX_EVENTS)
    }.getOrDefault(emptyList())

    fun write(context: Context, events: List<SportsEvent>) {
        runCatching {
            val array = JSONArray()
            events.take(MAX_EVENTS).forEach { event ->
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
}
