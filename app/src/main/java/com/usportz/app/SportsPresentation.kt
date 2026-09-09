package com.usportz.app

/** Presentation helpers shared by mobile and TV sports surfaces. */
object SportsPresentation {
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

    fun watchScore(event: SportsEvent, channel: TvChannelLike): Int =
        SportsSchedule.matchChannel(event, channel.name, channel.group)
}

/** Small adapter so presentation logic does not depend on the TV channel class. */
data class TvChannelLike(val name: String, val group: String)
