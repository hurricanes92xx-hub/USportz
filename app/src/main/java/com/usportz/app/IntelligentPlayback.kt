package com.usportz.app

import java.net.URI
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Sprint 3: bounded, local playback intelligence. No provider credentials or URLs are persisted here. */
enum class StreamKind { HLS, MPEG_TS, UNKNOWN }

enum class PlaybackFailure { TIMEOUT, HTTP, MANIFEST, FORMAT, NETWORK, DECODER, EMPTY, UNKNOWN }

data class ChannelHealth(val failures: Int, val successes: Int, val lastFailureAt: Long, val lastSuccessAt: Long, val score: Int)

data class StreamCandidate(
    val channel: SportsChannel,
    val score: Int,
    val confidence: Int,
    val kind: StreamKind,
    val family: String,
    val preferredNetwork: Boolean,
    val remembered: Boolean
)

object StreamClassifier {
    fun kind(url: String): StreamKind {
        val path = runCatching { URI(url).path.orEmpty() }.getOrDefault(url.substringBefore('?')).lowercase(Locale.US)
        return when {
            path.endsWith(".m3u8") || path.contains("m3u8") -> StreamKind.HLS
            path.endsWith(".ts") || path.contains("/ts/") -> StreamKind.MPEG_TS
            else -> UNKNOWN
        }
    }

    fun family(channel: SportsChannel): String {
        val base = listOf(channel.name, channel.group, channel.tvgName, channel.tvgId)
            .joinToString(" ").lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), " ").trim()
        return base.split(" ").filter { it.length > 2 && it !in setOf("hd", "fhd", "uhd", "4k", "east", "west", "backup", "feed") }
            .take(6).joinToString(" ")
    }
}

object PlaybackHealth {
    private const val MAX_SCORE = 100
    private val state = ConcurrentHashMap<String, MutableHealth>()
    private data class MutableHealth(var failures: Int = 0, var successes: Int = 0, var lastFailureAt: Long = 0, var lastSuccessAt: Long = 0)

    fun snapshot(channel: SportsChannel): ChannelHealth = snapshot(channel.url)

    fun snapshot(url: String): ChannelHealth {
        val s = state[url] ?: return ChannelHealth(0, 0, 0, 0, 50)
        val score = (50 + s.successes * 10 - s.failures * 18).coerceIn(0, MAX_SCORE)
        return ChannelHealth(s.failures, s.successes, s.lastFailureAt, s.lastSuccessAt, score)
    }

    fun recordSuccess(channel: SportsChannel, now: Long = System.currentTimeMillis()) = recordSuccess(channel.url, now)

    fun recordSuccess(url: String, now: Long = System.currentTimeMillis()) {
        if (url.isBlank()) return
        val s = state.computeIfAbsent(url) { MutableHealth() }
        s.successes = (s.successes + 1).coerceAtMost(20); s.lastSuccessAt = now
    }

    fun recordFailure(channel: SportsChannel, failure: PlaybackFailure, now: Long = System.currentTimeMillis()) = recordFailure(channel.url, failure, now)

    fun recordFailure(url: String, failure: PlaybackFailure, now: Long = System.currentTimeMillis()) {
        if (url.isBlank()) return
        val s = state.computeIfAbsent(url) { MutableHealth() }
        val weight = when (failure) { PlaybackFailure.DECODER, PlaybackFailure.FORMAT -> 2; else -> 1 }
        s.failures = (s.failures + weight).coerceAtMost(20); s.lastFailureAt = now
    }
}

object PlaybackFailureClassifier {
    fun classify(message: String?, httpCode: Int? = null): PlaybackFailure {
        if (httpCode != null) return if (httpCode in 400..599) PlaybackFailure.HTTP else PlaybackFailure.UNKNOWN
        val text = message.orEmpty().lowercase(Locale.US)
        return when {
            "timeout" in text || "timed out" in text -> PlaybackFailure.TIMEOUT
            "manifest" in text || "m3u8" in text -> PlaybackFailure.MANIFEST
            "http" in text || "response code" in text -> PlaybackFailure.HTTP
            "decoder" in text || "codec" in text -> PlaybackFailure.DECODER
            "format" in text || "unsupported" in text -> PlaybackFailure.FORMAT
            "network" in text || "connection" in text || "socket" in text -> PlaybackFailure.NETWORK
            "empty" in text || "no data" in text -> PlaybackFailure.EMPTY
            else -> PlaybackFailure.UNKNOWN
        }
    }
}

object IntelligentPlayback {
    const val MAX_ATTEMPTS = 3
    const val RETRY_DELAY_MS = 900L
    const val STARTUP_WARN_MS = 4_000L
    const val STARTUP_FAIL_MS = 10_000L

    /** Reorders existing resolver matches; it never expands the provider catalogue. */
    fun rank(event: SportsEvent, matches: List<GameSourceMatcher.Match>): List<StreamCandidate> {
        val preferred = SportsBroadcasts.preferredNetworks(event).map { BroadcasterNormalizer.canonical(it) }.toSet()
        val remembered = BestSourceMemory.get(event.id)
        val ranked = matches.asSequence().map { match ->
            val kind = StreamClassifier.kind(match.channel.url)
            val family = StreamClassifier.family(match.channel)
            val preferredHit = preferred.any { BroadcasterNormalizer.matches(it, "${match.channel.name} ${match.channel.group}") }
            val rememberedHit = remembered == match.channel.url
            val health = PlaybackHealth.snapshot(match.channel)
            val bonus = (if (preferredHit) 12 else 0) + (if (rememberedHit) 20 else 0) + (health.score - 50) / 5 + when (kind) { StreamKind.HLS -> 4; StreamKind.MPEG_TS -> 2; StreamKind.UNKNOWN -> 0 }
            StreamCandidate(match.channel, (match.score + bonus).coerceAtLeast(0), match.confidence, kind, family, preferredHit, rememberedHit)
        }.sortedWith(compareByDescending<StreamCandidate> { it.remembered }
            .thenByDescending { it.score }
            .thenByDescending { it.confidence }
            .thenBy { it.channel.name.lowercase(Locale.US) }).toList()

        // Collapse duplicate stream families so the source selector exposes genuinely different options.
        val seenFamilies = HashSet<String>()
        return ranked.filter { candidate ->
            val key = candidate.family.ifBlank { candidate.channel.url }
            seenFamilies.add(key)
        }.take(MAX_ATTEMPTS)
    }

    fun recordSuccess(event: SportsEvent, channel: SportsChannel) = BestSourceMemory.put(event.id, channel.url)
}

object BestSourceMemory {
    private const val MAX_ENTRIES = 500
    private val memory = object : LinkedHashMap<String, String>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > MAX_ENTRIES
    }
    @Synchronized fun get(eventId: String): String? = memory[eventId]
    @Synchronized fun put(eventId: String, url: String) { if (eventId.isNotBlank() && url.isNotBlank()) memory[eventId] = url }
}

object PlaybackStartupMeter {
    data class Sample(val startedAt: Long, val readyAt: Long, val durationMs: Long, val successful: Boolean)
    private const val MAX_SAMPLES = 200
    private val samples = ArrayDeque<Sample>()

    @Synchronized fun record(startedAt: Long, readyAt: Long, successful: Boolean) {
        samples.addLast(Sample(startedAt, readyAt, (readyAt - startedAt).coerceAtLeast(0), successful))
        while (samples.size > MAX_SAMPLES) samples.removeFirst()
    }

    @Synchronized fun averageMs(): Long = samples.filter { it.successful }.map { it.durationMs }.average().takeIf { !it.isNaN() }?.toLong() ?: 0L
    @Synchronized fun successRate(): Int = if (samples.isEmpty()) 0 else (samples.count { it.successful } * 100 / samples.size)
}
