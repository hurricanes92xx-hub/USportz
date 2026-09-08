package com.usportz.app

/** Fast in-memory index used by the next UI/source stage. */
class ChannelIndex<T>(
    items: List<T>,
    private val name: (T) -> String,
    private val group: (T) -> String
) {
    private val all = items
    private val normalizedNames = items.associateWith { name(it).lowercase() }
    private val normalizedGroups = items.associateWith { group(it).lowercase() }

    fun all(): List<T> = all

    fun search(query: String, limit: Int = 100): List<T> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return all.asSequence()
            .filter { normalizedNames[it]!!.contains(q) || normalizedGroups[it]!!.contains(q) }
            .take(limit.coerceIn(1, 500))
            .toList()
    }

    fun bySport(limit: Int = 3000): Map<String, List<T>> = all.asSequence()
        .take(limit.coerceIn(1, 3000))
        .groupBy { SportsCatalog.classify(name(it), group(it)) }

    fun groups(): List<String> = all.asSequence()
        .map { group(it).trim() }
        .filter { it.isNotEmpty() }
        .distinctBy { it.lowercase() }
        .sortedBy { it.lowercase() }
        .toList()
}
