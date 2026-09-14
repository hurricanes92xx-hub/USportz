package com.usportz.app

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

data class PreloadTarget(val channel: SportsChannel, val rank: Int, val preloadMs: Long)

object SportsPreloadPolicy {
    fun targets(channels: List<SportsChannel>, focusedIndex: Int, maxTargets: Int = 2): List<PreloadTarget> {
        if (channels.isEmpty()) return emptyList()
        val safeIndex = focusedIndex.coerceIn(0, channels.lastIndex)
        return channels.withIndex()
            .map { (index, _) -> index to kotlin.math.abs(index - safeIndex) }
            .filter { it.second in 1..maxTargets }
            .sortedBy { it.second }
            .take(maxTargets)
            .map { (index, distance) -> PreloadTarget(channels[index], distance, if (distance == 1) 2500L else 1200L) }
    }
}

data class RaceResult<T>(val value: T, val winnerIndex: Int, val elapsedMs: Long)

object SmartSourceRace {
    suspend fun <T> firstHealthy(
        candidates: List<T>,
        maxParallel: Int = 2,
        timeoutMs: Long = 8_000L,
        probe: suspend (T) -> Boolean
    ): RaceResult<T>? = coroutineScope {
        if (candidates.isEmpty()) return@coroutineScope null
        val start = System.currentTimeMillis()
        val jobs: List<Deferred<Pair<Boolean, Int>>> = candidates.take(maxParallel.coerceAtLeast(1)).mapIndexed { index, candidate ->
            async(Dispatchers.IO) { runCatching { probe(candidate) }.getOrDefault(false) to index }
        }
        try {
            val deadline = start + timeoutMs.coerceAtLeast(1L)
            while (System.currentTimeMillis() < deadline && jobs.any { !it.isCompleted }) {
                for (job in jobs.filter { it.isCompleted }) {
                    val result = job.await()
                    if (result.first) return@coroutineScope RaceResult(candidates[result.second], result.second, System.currentTimeMillis() - start)
                }
                delay(25L)
            }
            for (job in jobs.filter { it.isCompleted }) {
                val result = job.await()
                if (result.first) return@coroutineScope RaceResult(candidates[result.second], result.second, System.currentTimeMillis() - start)
            }
            null
        } finally {
            jobs.forEach { it.cancel() }
        }
    }
}

data class PlaybackDiagnostics(
    val source: String,
    val kind: StreamKind,
    val startupMs: Long = 0,
    val ttfbMs: Long = 0,
    val httpCode: Int? = null,
    val bitrateKbps: Int? = null,
    val width: Int? = null,
    val height: Int? = null,
    val fps: Float? = null,
    val videoCodec: String = "",
    val audioCodec: String = "",
    val bufferMs: Long = 0,
    val failure: PlaybackFailure? = null
) {
    val resolution: String get() = if (width != null && height != null) "${width}x$height" else "unknown"
    val healthy: Boolean get() = failure == null && (httpCode == null || httpCode in 200..399)
}

object PlaybackFailurePolicy {
    fun nextDelayMs(attempt: Int): Long = when (attempt.coerceAtLeast(1)) { 1 -> 400L; 2 -> 900L; else -> 1500L }
    fun shouldRetire(failures: Int, nowMs: Long = System.currentTimeMillis()): Boolean = failures >= 5 && nowMs > 0
}

object PlaybackDiagnosticsFormatter {
    fun compact(d: PlaybackDiagnostics): String = buildString {
        append(if (d.healthy) "OK" else "FAIL"); append(" • "); append(d.kind.name); append(" • ")
        append(d.resolution)
        if (d.bitrateKbps != null) append(" • ${d.bitrateKbps}kbps")
        if (d.startupMs > 0) append(" • start ${d.startupMs}ms")
        if (d.ttfbMs > 0) append(" • TTFB ${d.ttfbMs}ms")
        if (d.httpCode != null) append(" • HTTP ${d.httpCode}")
        if (d.failure != null) append(" • ${d.failure.name}")
    }
}
