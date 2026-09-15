package com.usportz.app

/** Bounded event -> channel cache shared by every Compose card for a catalogue generation. */
object SportsResolutionCache {
    private data class Entry(val value: List<SportsResolver.WatchSource>, val expiresAt: Long)
    private const val TTL_MS = 5 * 60_000L
    private const val NEGATIVE_TTL_MS = 30_000L
    private const val MAX_ENTRIES = 240
    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)

    fun getOrResolve(event: SportsEvent, channels: List<SportsChannel>, limit: Int = 8): List<SportsResolver.WatchSource> {
        val generation = SportsChannelBridge.currentCatalogGeneration()
        val source = SportsChannelBridge.currentSourceKey()
        val key = "$source:$generation:${SportsEventFingerprint.of(event)}:$limit"
        val now = System.currentTimeMillis()
        synchronized(this) {
            entries[key]?.let { if (it.expiresAt > now) return it.value else entries.remove(key) }
            val value = SportsResolver.resolveUncached(event, channels, limit)
            entries[key] = Entry(value, System.currentTimeMillis() + if (value.isEmpty()) NEGATIVE_TTL_MS else TTL_MS)
            while (entries.size > MAX_ENTRIES) entries.entries.firstOrNull()?.let { entries.remove(it.key) }
            return value
        }
    }

    @Synchronized
    fun clear() { entries.clear() }
}
