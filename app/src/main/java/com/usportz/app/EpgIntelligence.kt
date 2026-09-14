package com.usportz.app

/** EPG identity bridge: channel identity first, then event/broadcaster confidence. */
object EpgIntelligence {
    data class Result(val score: Int, val reasons: List<String>)

    fun score(event: SportsEvent, channel: SportsChannel): Result {
        val matches = SportsEpg.eventMatches(event, channel, 3)
        val best = matches.maxByOrNull { it.score } ?: return Result(0, emptyList())
        return Result(best.score.coerceAtMost(100), best.reasons)
    }

    fun broadcasterScore(event: SportsEvent, channel: SportsChannel): Int {
        if (event.broadcast.isBlank()) return 0
        return SportsEpg.broadcasterMatches(channel, event.broadcast, 2).firstOrNull()?.let { 20 } ?: 0
    }
}
