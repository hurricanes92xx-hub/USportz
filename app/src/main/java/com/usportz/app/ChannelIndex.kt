package com.usportz.app

/**
 * UI-safe channel view over very large Xtream/M3U inventories.
 *
 * The previous implementation built token and prefix maps for every channel
 * during construction. With 50K+ channels that could block Compose for seconds
 * and trigger Android's "isn't responding" dialog. This implementation keeps
 * construction O(1) and bounds any fallback search work.
 */
class ChannelIndex<T>(
    items: List<T>,
    private val name: (T) -> String,
    private val group: (T) -> String
) {
    private val allItems: List<T> = items

    fun isReady(): Boolean = true

    fun all(): List<T> = allItems

    fun search(query: String, limit: Int = 100): List<T> = searchInternal(query, null, limit)

    fun search(query: String, sport: String, limit: Int = 100): List<T> =
        searchInternal(query, sport.takeUnless { it.isBlank() || it.equals("All", true) }, limit)

    fun bySport(limit: Int = Int.MAX_VALUE): Map<String, List<T>> =
        allItems.asSequence().take(limit.coerceAtLeast(1)).groupBy { SportsCatalog.classify(name(it), group(it)) }

    fun forSport(sport: String, limit: Int = Int.MAX_VALUE): List<T> {
        val selected = sport.trim()
        if (selected.isEmpty() || selected.equals("All", true)) return allItems.take(limit.coerceAtLeast(1))
        return allItems.asSequence()
            .filter { SportsCatalog.classify(name(it), group(it)).equals(selected, true) }
            .take(limit.coerceAtLeast(1))
            .toList()
    }

    fun groups(): List<String> = allItems.asSequence()
        .map { group(it).trim() }
        .filter { it.isNotEmpty() }
        .distinctBy { it.lowercase() }
        .sortedBy { it.lowercase() }
        .take(2000)
        .toList()

    private fun searchInternal(query: String, sport: String?, limit: Int): List<T> {
        val q = normalize(query)
        if (q.isBlank()) return emptyList()
        val safeLimit = limit.coerceIn(1, 100)
        return allItems.asSequence()
            // A search should never turn into an unbounded 56K-row Compose operation.
            .take(5000)
            .filter { sport == null || SportsCatalog.classify(name(it), group(it)).equals(sport, true) }
            .map { it to score(it, q) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(safeLimit)
            .map { it.first }
            .toList()
    }

    private fun score(item: T, query: String): Int {
        val n = normalize(name(item))
        val g = normalize(group(item))
        var score = 0
        if (n == query) score += 1000
        if (n.startsWith(query)) score += 600
        if (g == query) score += 450
        if (g.startsWith(query)) score += 250
        if (n.contains(query)) score += 180
        if (g.contains(query)) score += 100
        return score
    }

    private fun normalize(value: String): String = value
        .lowercase()
        .replace('&', ' ')
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")
}
