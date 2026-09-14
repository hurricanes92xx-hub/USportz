package com.usportz.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/** Single-flight, bounded cache for event -> channel resolution. UI callers share one computation. */
object SportsResolutionCache {
    private data class Entry(val value: List<SportsResolver.WatchSource>, val expiresAt: Long)
    private const val TTL_MS = 60_000L
    private const val NEGATIVE_TTL_MS = 15_000L
    private const val MAX_ENTRIES = 120
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)
    private val inFlight = HashMap<String, Deferred<List<SportsResolver.WatchSource>>>()

    suspend fun getOrResolve(event: SportsEvent, channels: List<SportsChannel>, limit: Int = 8): List<SportsResolver.WatchSource> {
        val key = SportsEventFingerprint.of(event) + ":" + limit
        val now = System.currentTimeMillis()
        synchronized(this) {
            val cached = entries[key]
            if (cached != null && cached.expiresAt > now) return cached.value
            entries.remove(key)
        }

        val work: Deferred<List<SportsResolver.WatchSource>>
        synchronized(this) {
            val existing = inFlight[key]
            if (existing != null) work = existing
            else {
                work = scope.async { SportsResolver.resolve(event, channels, limit) }
                inFlight[key] = work
            }
        }

        return try {
            val value = work.await()
            synchronized(this) {
                if (inFlight[key] === work) inFlight.remove(key)
                entries[key] = Entry(value, System.currentTimeMillis() + if (value.isEmpty()) NEGATIVE_TTL_MS else TTL_MS)
                while (entries.size > MAX_ENTRIES) entries.entries.firstOrNull()?.let { entries.remove(it.key) }
            }
            value
        } catch (_: Throwable) {
            synchronized(this) { if (inFlight[key] === work) inFlight.remove(key) }
            emptyList()
        }
    }

    @Synchronized
    fun clear() { entries.clear() }
}
