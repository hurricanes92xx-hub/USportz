package com.usportz.app

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Presentation helpers shared by mobile and TV sports surfaces. */
object SportsPresentation {
    private val dayFormatter = DateTimeFormatter.ofPattern("EEE, MMM d")
    private val timeFormatter = DateTimeFormatter.ofPattern("h:mm a")

    fun brand(event: SportsEvent): SportsBrand? = SportsBranding.find(event.name, event.league)

    fun label(event: SportsEvent): String = SportsBranding.label(event.name, event.league)

    fun status(event: SportsEvent): String = when (event.state.lowercase()) {
        "in" -> "LIVE"
        "post" -> "FINAL"
        "pre" -> "UPCOMING"
        else -> event.detail.ifBlank { "UPCOMING" }
    }

    fun matchup(event: SportsEvent): String = when {
        event.competitors.size >= 2 -> "${event.competitors[0]}  •  ${event.competitors[1]}"
        event.competitors.size == 1 -> event.competitors[0]
        event.shortName.isNotBlank() -> event.shortName
        else -> event.name
    }

    /** Device-local event date/time. The stored event timestamp remains UTC/ISO-8601. */
    fun dateTime(event: SportsEvent): String = runCatching {
        val z = Instant.parse(event.startTime).atZone(ZoneId.systemDefault())
        "${z.format(dayFormatter)} • ${z.format(timeFormatter)}"
    }.getOrDefault("")

    fun watchScore(event: SportsEvent, channel: TvChannelLike): Int =
        SportsSchedule.matchChannel(event, channel.name, channel.group)
}

/** Small adapter so presentation logic does not depend on the TV channel class. */
data class TvChannelLike(val name: String, val group: String)
