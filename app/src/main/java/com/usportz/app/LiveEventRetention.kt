package com.usportz.app

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Centralized event-window policy. A calendar-date rollover must never hide an
 * event that is still live. This helper is deliberately pure so it can be unit-tested.
 */
object LiveEventRetention {
    fun requestedDates(today: LocalDate, lookaheadDays: Long = 7L): List<LocalDate> =
        listOf(today.minusDays(1), today) + (1..lookaheadDays.toInt()).map(today::plusDays)

    fun keep(startTime: String, state: String, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val start = runCatching { Instant.parse(startTime) }.getOrNull() ?: return false
        val now = Instant.ofEpochMilli(nowMillis)
        if (state.equals("in", true) || state.equals("live", true)) return true
        val today = now.atZone(ZoneId.systemDefault()).toLocalDate()
        val day = start.atZone(ZoneId.systemDefault()).toLocalDate()
        if (!day.isBefore(today) && !day.isAfter(today.plusDays(7))) return true
        return day == today.minusDays(1) && start.isAfter(now.minusSeconds(6 * 3600L))
    }
}
