package com.usportz.app

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Official Monster Jam free-YouTube live stream bridge.
 *
 * Monster Jam publishes its live-event schedule on its official streaming page and
 * sends viewers to the official @MonsterJam YouTube channel. We keep the published
 * live-stream dates/times here as a small, fast schedule layer and always open the
 * official YouTube Live page rather than extracting or proxying YouTube media URLs.
 */
object MonsterJamSchedule {
    const val YOUTUBE_LIVE_URL = "https://www.youtube.com/@MonsterJam/live"
    const val YOUTUBE_STREAMS_URL = "https://www.youtube.com/@MonsterJam/streams"

    private data class LiveShow(val date: LocalDate, val hour: Int, val minute: Int, val city: String)

    private val shows = listOf(
        LiveShow(LocalDate.of(2026, 9, 12), 19, 0, "Duluth"),
        LiveShow(LocalDate.of(2026, 9, 19), 20, 0, "Seattle"),
        LiveShow(LocalDate.of(2026, 10, 3), 20, 0, "Arlington"),
        LiveShow(LocalDate.of(2026, 10, 17), 20, 0, "Houston"),
        LiveShow(LocalDate.of(2026, 10, 24), 17, 0, "Syracuse"),
        LiveShow(LocalDate.of(2026, 11, 1), 16, 0, "Glendale"),
        LiveShow(LocalDate.of(2026, 12, 19), 20, 0, "Anaheim"),
        LiveShow(LocalDate.of(2026, 12, 20), 18, 0, "Anaheim")
    )

    fun load(today: LocalDate = LocalDate.now(), last: LocalDate = today.plusDays(7)): List<SportsEvent> = shows
        .asSequence()
        .filter { !it.date.isBefore(today) && !it.date.isAfter(last) }
        .map { show ->
            val local = LocalDateTime.of(show.date, java.time.LocalTime.of(show.hour, show.minute))
            val instant = local.atZone(ZoneId.of("America/New_York")).toInstant()
            SportsEvent(
                id = "monster-jam-youtube:${show.date}:${show.city}",
                sport = "racing",
                league = "Monster Jam",
                name = "Monster Jam: ${show.city}",
                shortName = show.city,
                state = if (instant.isBefore(java.time.Instant.now())) "post" else "pre",
                startTime = instant.toString(),
                competitors = emptyList(),
                competitorLogos = emptyList(),
                leagueLogo = BrandAssets.logoUrl(SportsBranding.find("Monster Jam")),
                detail = "${show.city} • YouTube Live • ${show.hour % 12}${if (show.minute == 0) "" else ":%02d".format(show.minute)} ${if (show.hour >= 12) "PM" else "AM"} ET",
                broadcast = "YouTube Live"
            )
        }
        .toList()
}

fun isMonsterJamEvent(event: SportsEvent): Boolean =
    event.league.equals("Monster Jam", true) || event.name.contains("Monster Jam", true)

fun monsterJamYouTubeUrl(event: SportsEvent): String? =
    if (isMonsterJamEvent(event)) MonsterJamSchedule.YOUTUBE_LIVE_URL else null

fun openMonsterJamYouTube(activity: android.app.Activity) {
    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(MonsterJamSchedule.YOUTUBE_LIVE_URL))
    val youtubePackage = "com.google.android.youtube"
    if (runCatching { activity.packageManager.getPackageInfo(youtubePackage, 0) }.isSuccess) {
        intent.setPackage(youtubePackage)
    }
    activity.startActivity(intent)
}
