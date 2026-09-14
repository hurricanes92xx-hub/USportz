package com.usportz.app

import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/** Shared, side-effect-free rules for retaining useful live-event data across midnight. */
object ReliabilityPolicy2 {
    fun eventDates(now: Instant): List<String> {
        val date = now.atZone(ZoneOffset.UTC).toLocalDate()
        return listOf(date.minusDays(1), date, date.plusDays(1), date.plusDays(2)).map { it.toString() }
    }

    fun keepEvent(start: Instant?, end: Instant?, effectiveState: String?, now: Instant): Boolean {
        val state = effectiveState?.lowercase()
        if (state == "in" || state == "live") return true
        if (end != null && now.isBefore(end)) return true
        return start?.isAfter(now.minus(6, ChronoUnit.HOURS)) == true
    }
}
