package com.usportz.app

import java.time.Instant
import java.time.OffsetDateTime

/** Compatibility overload used by the dashboard when sorting SportsEvent objects. */
fun eventEpoch(event: SportsEvent): Long? = event.startTime.toEpochMillisCompat()

private fun String.toEpochMillisCompat(): Long? {
    val raw = trim()
    if (raw.isEmpty()) return null
    raw.toLongOrNull()?.let { value ->
        return if (kotlin.math.abs(value) < 100_000_000_000L) value * 1000L else value
    }
    return runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(raw).toInstant().toEpochMilli() }.getOrNull()
}
