package com.usportz.app

/** Fast in-memory channel index for sports filtering and search. */
class ChannelIndex<T>(
    items: List<T>,
    private val name: (T) -> String,
    private val group: (T) -> String
) {
    private val all = items
    private val normalizedNames = items.associateWith { name(it).trim().lowercase() }
    private val normalizedGroups = items.associateWith { group(it).trim().lowercase() }
    private val sports = items.associateWith { SportsCatalog.classify(name(it), group(it)) }

    fun all(): List<T> = all

    fun search(query: String, limit: Int = 100): List<T> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return all.asSequence()
            .filter { normalizedNames[it]!!.contains(q) || normalizedGroups[it]!!.contains(q) }
            .take(limit.coerceIn(1, 500))
            .toList()
    }

    fun search(query: String, sport: String, limit: Int = 100): List<T> {
        val q = query.trim().lowercase()
        val selected = sport.trim()
        if (q.isEmpty() && selected.isEmpty()) return emptyList()
        return all.asSequence()
            .filter { selected.isEmpty() || selected == "All" || sports[it] == selected }
            .filter { q.isEmpty() || normalizedNames[it]!!.contains(q) || normalizedGroups[it]!!.contains(q) }
            .take(limit.coerceIn(1, 500))
            .toList()
    }

    fun bySport(limit: Int = 3000): Map<String, List<T>> = all.asSequence()
        .take(limit.coerceIn(1, 3000))
        .groupBy { sports[it]!! }

    fun forSport(sport: String, limit: Int = 3000): List<T> {
        val selected = sport.trim()
        if (selected.isEmpty() || selected == "All") return all.take(limit.coerceIn(1, 3000))
        return all.asSequence()
            .filter { sports[it] == selected }
            .take(limit.coerceIn(1, 3000))
            .toList()
    }

    fun groups(): List<String> = all.asSequence()
        .map { group(it).trim() }
        .filter { it.isNotEmpty() }
        .distinctBy { it.lowercase() }
        .sortedBy { it.lowercase() }
        .toList()
}
