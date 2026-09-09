package com.usportz.app

/** Fast in-memory ranked index for large Xtream/M3U inventories. */
class ChannelIndex<T>(
    items: List<T>,
    private val name: (T) -> String,
    private val group: (T) -> String
) {
    private val all: List<T> = items.distinctBy { name(it).trim().lowercase() + "\u0000" + group(it).trim().lowercase() }
    private val normalizedNames = all.associateWith { normalize(name(it)) }
    private val normalizedGroups = all.associateWith { normalize(group(it)) }
    private val sports = all.associateWith { SportsCatalog.classify(name(it), group(it)) }

    private val tokenIndex: Map<String, List<T>> = buildIndex { item ->
        tokenize(normalizedNames[item].orEmpty()) + tokenize(normalizedGroups[item].orEmpty())
    }
    private val prefixIndex: Map<String, List<T>> = buildPrefixIndex()

    fun all(): List<T> = all

    fun search(query: String, limit: Int = 100): List<T> = rankedSearch(query, null, limit)

    fun search(query: String, sport: String, limit: Int = 100): List<T> =
        rankedSearch(query, sport.takeUnless { it.isBlank() || it == "All" }, limit)

    fun bySport(limit: Int = Int.MAX_VALUE): Map<String, List<T>> = all.asSequence()
        .take(limit.coerceAtLeast(1))
        .groupBy { sports[it] ?: "Other" }

    fun forSport(sport: String, limit: Int = Int.MAX_VALUE): List<T> {
        val selected = sport.trim()
        if (selected.isEmpty() || selected == "All") return all.take(limit.coerceAtLeast(1))
        return all.asSequence()
            .filter { sports[it] == selected }
            .take(limit.coerceAtLeast(1))
            .toList()
    }

    fun groups(): List<String> = all.asSequence()
        .map { group(it).trim() }
        .filter { it.isNotEmpty() }
        .distinctBy { it.lowercase() }
        .sortedBy { it.lowercase() }
        .toList()

    private fun rankedSearch(query: String, sport: String?, limit: Int): List<T> {
        val q = normalize(query)
        if (q.isEmpty()) return emptyList()
        val safeLimit = limit.coerceIn(1, 500)
        val queryTokens = tokenize(q)
        if (queryTokens.isEmpty()) return emptyList()

        val candidates = linkedSetOf<T>()
        queryTokens.forEach { token ->
            tokenIndex[token]?.let(candidates::addAll)
            // The prefix index already contains every prefix up to eight characters.
            prefixIndex[token]?.let(candidates::addAll)
        }
        // Only an unindexed/very unusual query falls back to the full inventory.
        if (candidates.isEmpty()) candidates.addAll(all)

        return candidates.asSequence()
            .filter { sport == null || sports[it] == sport }
            .map { it to score(it, q, queryTokens) }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<T, Int>> { it.second }.thenBy { normalize(name(it.first)) })
            .take(safeLimit)
            .map { it.first }
            .toList()
    }

    private fun score(item: T, q: String, tokens: List<String>): Int {
        val n = normalizedNames[item].orEmpty()
        val g = normalizedGroups[item].orEmpty()
        var score = 0
        if (n == q) score += 1000
        if (n.startsWith(q)) score += 600
        if (g == q) score += 450
        if (g.startsWith(q)) score += 250
        if (n.contains(q)) score += 180
        if (g.contains(q)) score += 100

        val nameTokens = tokenize(n)
        val groupTokens = tokenize(g)
        tokens.forEach { token ->
            if (nameTokens.contains(token)) score += 120
            else if (nameTokens.any { it.startsWith(token) }) score += 75
            if (groupTokens.contains(token)) score += 55
            else if (groupTokens.any { it.startsWith(token) }) score += 30
        }
        return score
    }

    private fun buildIndex(tokensFor: (T) -> List<String>): Map<String, List<T>> {
        val map = HashMap<String, MutableList<T>>()
        all.forEach { item ->
            tokensFor(item).distinct().forEach { token -> map.getOrPut(token) { mutableListOf() }.add(item) }
        }
        return map
    }

    private fun buildPrefixIndex(): Map<String, List<T>> {
        val map = HashMap<String, MutableList<T>>()
        all.forEach { item ->
            tokenize(normalizedNames[item].orEmpty()).forEach { token ->
                val max = minOf(token.length, 8)
                for (length in 1..max) map.getOrPut(token.substring(0, length)) { mutableListOf() }.add(item)
            }
        }
        return map.mapValues { (_, values) -> values.distinct() }
    }

    private fun tokenize(value: String): List<String> = value.split(' ').filter { it.isNotEmpty() }.distinct()

    private fun normalize(value: String): String = value
        .lowercase()
        .replace('&', ' ')
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")
}
