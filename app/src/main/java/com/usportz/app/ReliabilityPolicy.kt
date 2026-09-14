package com.usportz.app

import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/** Shared rules for retaining useful live data across provider/date boundaries. */
object ReliabilityPolicy {
    fun eventDates(now: Instant): List<String> {
        val date = now.atZone(ZoneOffset.UTC).toLocalDate()
        return listOf(date.minusDays(1), date, date.plusDays(1), date.plusDays(2)).map { it.toString() }
    }

    fun keepEvent(event: SportsEvent, now: Instant): Boolean {
        val start = runCatching { Instant.parse(event.startTime) }.getOrNull() ?: return true
        val end = event.endTime?.let { runCatching { Instant.parse(it) }.getOrNull() }
        val state = event.state?.lowercase()
        if (state == "in" || state == "live") return true
        if (end != null && now.isBefore(end)) return true
        return start.isAfter(now.minus(6, ChronoUnit.HOURS))
    }
}
