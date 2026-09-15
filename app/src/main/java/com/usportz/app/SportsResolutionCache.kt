package com.usportz.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/** Bounded event -> channel cache shared by every Compose card for a catalogue generation. */
object SportsResolutionCache {
    private data class Entry(val value: List<SportsResolver.WatchSource>, val expiresAt: Long)
    private const val TTL_MS = 5 * 60_000L
    private const val NEGATIVE_TTL_MS = 30_000L
    private const val MAX_ENTRIES = 240
    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)

    /**
     * Returns a cached result immediately when available. Unlike getOrResolve(), this never
     * performs the expensive catalogue scan and is used by the startup UI after prewarming.
     */
    fun getCached(event: SportsEvent, limit: Int = 8): List<SportsResolver.WatchSource> {
        val key = keyFor(event, limit)
        val now = System.currentTimeMillis()
        synchronized(this) {
            val entry = entries[key] ?: return emptyList()
            if (entry.expiresAt > now) return entry.value
            entries.remove(key)
            return emptyList()
        }
    }

    fun getOrResolve(event: SportsEvent, channels: List<SportsChannel>, limit: Int = 8): List<SportsResolver.WatchSource> {
        val key = keyFor(event, limit)
        val now = System.currentTimeMillis()
        synchronized(this) {
            entries[key]?.let { if (it.expiresAt > now) return it.value else entries.remove(key) }
        }

        // Do not hold the global cache lock while scanning the catalogue. The old implementation
        // serialized every first-render card behind one expensive resolver pass, which made a
        // sports-heavy startup feel frozen. Prewarming is bounded separately below.
        val value = SportsResolver.resolveUncached(event, channels, limit)
        synchronized(this) {
            putLocked(key, value)
        }
        return value
    }

    /** Pre-resolve only the games the user can see immediately. Bounded parallelism prevents a
     * large sports catalogue from spawning dozens of full scans at once. */
    suspend fun prewarm(
        events: List<SportsEvent>,
        channels: List<SportsChannel>,
        limit: Int = 16,
        maxConcurrent: Int = 3
    ) = withContext(Dispatchers.Default) {
        val unique = events.distinctBy { SportsEventFingerprint.of(it) }.take(60)
        if (unique.isEmpty() || channels.isEmpty()) return@withContext
        unique.chunked(maxConcurrent.coerceIn(1, 6)).forEach { batch ->
            coroutineScope {
                batch.map { event ->
                    async { getOrResolve(event, channels, limit) }
                }.awaitAll()
            }
        }
    }

    private fun keyFor(event: SportsEvent, limit: Int): String {
        val generation = SportsChannelBridge.currentCatalogGeneration()
        val source = SportsChannelBridge.currentSourceKey()
        return "$source:$generation:${SportsEventFingerprint.of(event)}:$limit"
    }

    private fun putLocked(key: String, value: List<SportsResolver.WatchSource>) {
        entries[key] = Entry(value, System.currentTimeMillis() + if (value.isEmpty()) NEGATIVE_TTL_MS else TTL_MS)
        while (entries.size > MAX_ENTRIES) entries.entries.firstOrNull()?.let { entries.remove(it.key) }
    }

    @Synchronized
    fun clear() { entries.clear() }
}
