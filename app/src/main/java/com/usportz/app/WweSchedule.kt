package com.usportz.app

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** WWE is not exposed by ESPN's scoreboard feeds. Keep an explicit first-party TV schedule rail. */
object WweSchedule {
    private val knownRawDates = listOf(
        LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 21), LocalDate.of(2026, 9, 28),
        LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 19),
        LocalDate.of(2026, 10, 26), LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 9),
        LocalDate.of(2026, 11, 16), LocalDate.of(2026, 11, 23), LocalDate.of(2026, 11, 30), LocalDate.of(2026, 12, 7)
    )
    private val knownSmackdown = listOf(
        LocalDate.of(2026, 9, 18), LocalDate.of(2026, 9, 25), LocalDate.of(2026, 10, 2),
        LocalDate.of(2026, 10, 9), LocalDate.of(2026, 10, 16), LocalDate.of(2026, 10, 30),
        LocalDate.of(2026, 11, 13), LocalDate.of(2026, 11, 20), LocalDate.of(2026, 12, 4)
    )

    fun load(today: LocalDate = LocalDate.now(), through: LocalDate = today.plusDays(7)): List<SportsEvent> = buildList {
        knownRawDates.filter { it in today..through }.forEach { add(show(it, "Monday Night Raw", "Wrestling", 20)) }
        knownSmackdown.filter { it in today..through }.forEach { add(show(it, "Friday Night SmackDown", "Wrestling", 20)) }
    }

    private fun show(date: LocalDate, title: String, league: String, hourEt: Int): SportsEvent {
        val instant = ZonedDateTime.of(date, java.time.LocalTime.of(hourEt, 0), ZoneId.of("America/New_York")).toInstant()
        val state = if (System.currentTimeMillis() >= instant.toEpochMilli()) "in" else "pre"
        val network = if (title.contains("Raw")) "Netflix" else "USA Network"
        return SportsEvent("wwe:${title.lowercase().replace(" ", "-")}:$date", "wrestling", league, title, title, state, instant.toString(), emptyList(), emptyList(), null, "WWE • $network", network)
    }
}
