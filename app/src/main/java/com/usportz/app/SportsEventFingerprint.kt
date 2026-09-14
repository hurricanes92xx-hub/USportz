package com.usportz.app

import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/** Canonical event identity helpers. Keeps fuzzy matching as a fallback rather than the primary identity. */
object SportsEventFingerprint {
    fun of(event: SportsEvent): String = sha256(raw(event))

    fun raw(event: SportsEvent): String {
        val teams = event.competitors
            .map(::normalizeTeam)
            .filter(String::isNotBlank)
            .sorted()
            .joinToString("|")
        val bucket = runCatching {
            Instant.parse(event.startTime).truncatedTo(ChronoUnit.MINUTES).toString()
        }.getOrDefault(event.startTime.take(16))
        return listOf(
            normalize(event.sport), normalize(event.league), teams,
            bucket, normalize(event.name).takeIf { teams.isBlank() }.orEmpty()
        ).joinToString("\u0000")
    }

    fun normalizeTeam(value: String): String {
        var s = normalize(value)
        s = s.replace(Regex("\\bthe\\b"), " ")
            .replace(Regex("\\buniversity\\b"), " ")
            .replace(Regex("\\bcollege\\b"), " ")
            .replace(Regex("\\bstate\\b"), " st ")
            .replace(Regex("\\bsaint\\b"), " st ")
        return s.replace(Regex("\\s+"), " ").trim()
    }

    fun normalize(value: String): String = value
        .lowercase()
        .replace('&'.toString(), " and ")
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
