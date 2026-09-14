package com.usportz.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.selects.select
import java.util.Locale

/**
 * Memory-safe preloading policy. It produces a tiny ranked warm set; the actual player is owned by the UI.
 * This keeps the app from creating a player per channel while still making focused-neighbor warming explicit.
 */
data class PreloadTarget(val channel: SportsChannel, val rank: Int, val preloadMs: Long)

object SportsPreloadPolicy {
    fun targets(channels: List<SportsChannel>, focusedIndex: Int, maxTargets: Int = 2): List<PreloadTarget> {
        if (channels.isEmpty()) return emptyList()
        val safeIndex = focusedIndex.coerceIn(0, channels.lastIndex)
        return channels.withIndex()
            .map { (index, channel) -> index to kotlin.math.abs(index - safeIndex) }
            .filter { it.second in 1..maxTargets }
            .sortedBy { it.second }
            .take(maxTargets)
            .map { (index, distance) -> PreloadTarget(channels[index], distance, if(distance == 1) 2500L else 1200L) }
    }
}

data class RaceResult<T>(val value: T, val winnerIndex: Int, val elapsedMs: Long)

/**
 * Races only a small number of already-ranked candidates. The caller supplies a cheap startup probe;
 * this is deliberately generic so it can be used with Media3, a manifest probe, or a player prepare call.
 */
object SmartSourceRace {
    suspend fun <T> firstHealthy(candidates: List<T>, maxParallel: Int = 2, timeoutMs: Long = 8_000L, probe: suspend (T) -> Boolean): RaceResult<T>? = coroutineScope {
        if (candidates.isEmpty()) return@coroutineScope null
        val start = System.currentTimeMillis()
        val jobs = candidates.take(maxParallel.coerceAtLeast(1)).mapIndexed { index, candidate ->
            async(Dispatchers.IO) {
                runCatching { probe(candidate) }.getOrDefault(false) to index
            }
        }
        try {
            val winner = awaitFirstSuccess(jobs, timeoutMs) ?: return@coroutineScope null
            RaceResult(candidates[winner.second], winner.second, System.currentTimeMillis() - start)
        } finally { jobs.forEach { it.cancel() } }
    }

    private suspend fun <T> awaitFirstSuccess(jobs: List<Deferred<Pair<Boolean, Int>>>, timeoutMs: Long): Pair<Boolean, Int>? = kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
        val pending = jobs.toMutableList()
        while (pending.isNotEmpty()) {
            val result = select<Pair<Boolean, Int>?> {
                pending.forEach { job -> job.onAwait { it } }
            }
            pending.removeAll { it.isCompleted }
            if (result?.first == true) return@withTimeoutOrNull result
        }
        null
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
    val resolution: String get() = if(width != null && height != null) "${width}x$height" else "unknown"
    val healthy: Boolean get() = failure == null && (httpCode == null || httpCode in 200..399)
}

object PlaybackFailurePolicy {
    fun nextDelayMs(attempt: Int): Long = when(attempt.coerceAtLeast(1)) { 1 -> 400L; 2 -> 900L; else -> 1500L }
    fun shouldRetire(failures: Int, nowMs: Long = System.currentTimeMillis()): Boolean = failures >= 5 && nowMs > 0
}

/** Stable human-readable diagnostics for TV troubleshooting and telemetry logs. */
object PlaybackDiagnosticsFormatter {
    fun compact(d: PlaybackDiagnostics): String = buildString {
        append(if(d.healthy) "OK" else "FAIL"); append(" • "); append(d.kind.name); append(" • ")
        append(d.resolution); if(d.bitrateKbps != null) append(" • ${d.bitrateKbps}kbps")
        if(d.startupMs > 0) append(" • start ${d.startupMs}ms"); if(d.ttfbMs > 0) append(" • TTFB ${d.ttfbMs}ms")
        if(d.httpCode != null) append(" • HTTP ${d.httpCode}"); if(d.failure != null) append(" • ${d.failure.name}")
    }
}
