package com.usportz.app

/** Single-flight, bounded cache for event -> channel resolution. UI callers share one computation. */
object SportsResolutionCache {
    private data class Entry(val value: List<SportsResolver.WatchSource>, val expiresAt: Long, var lastAccess: Long)
    private const val TTL_MS = 60_000L
    private const val NEGATIVE_TTL_MS = 15_000L
    private const val MAX_ENTRIES = 120
    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)
    private val inFlight = HashMap<String, kotlinx.coroutines.Deferred<List<SportsResolver.WatchSource>>>()

    suspend fun getOrResolve(event: SportsEvent, channels: List<SportsChannel>, limit: Int = 8): List<SportsResolver.WatchSource> {
        val key = SportsEventFingerprint.of(event) + ":" + channels.size + ":" + limit
        val now = System.currentTimeMillis()
        synchronized(this) {
            val cached = entries[key]
            if (cached != null && cached.expiresAt > now) {
                cached.lastAccess = now
                return cached.value
            }
            entries.remove(key)
        }
        val deferred = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).async {
            SportsResolver.resolve(event, channels, limit)
        }
        synchronized(this) {
            val existing = inFlight[key]
            if (existing != null) return existing.await()
            inFlight[key] = deferred
        }
        return try {
            val value = deferred.await()
            synchronized(this) {
                entries[key] = Entry(value, System.currentTimeMillis() + if (value.isEmpty()) NEGATIVE_TTL_MS else TTL_MS, System.currentTimeMillis())
                while (entries.size > MAX_ENTRIES) entries.entries.firstOrNull()?.let { entries.remove(it.key) }
                inFlight.remove(key)
            }
            value
        } catch (t: Throwable) {
            synchronized(this) { inFlight.remove(key) }
            emptyList()
        }
    }

    @Synchronized
    fun clear() { entries.clear() }
}
