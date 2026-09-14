package com.usportz.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Explicit lazy boundary for rich game data. Nothing is fetched until load() is called for an opened game. */
object LazyGameDetails {
    private const val MAX = 80
    private val cache = object : LinkedHashMap<String, SportsGameDetail>(MAX, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SportsGameDetail>?): Boolean = size > MAX
    }

    suspend fun load(event: SportsEvent): SportsGameDetail? = withContext(Dispatchers.IO) {
        synchronized(cache) { cache[event.id] } ?: SportsGameDetailService.load(event)?.also { synchronized(cache) { cache[event.id] = it } }
    }

    @Synchronized fun clear() { cache.clear() }
}
