package com.usportz.app

/**
 * Fast in-memory channel index for large Xtream/M3U inventories.
 *
 * Search is backed by token + prefix buckets instead of scanning every channel.
 * Results are still ranked so exact names, prefixes and group matches win first.
 */
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
    private val nameTokenIndex: Map<String, List<T>> = buildIndex { item -> tokenize(normalizedNames[item].orEmpty()) }
    private val groupTokenIndex: Map<String, List<T>> = buildIndex { item -> tokenize(normalizedGroups[item].orEmpty()) }
    private val prefixIndex: Map<String, List<T>> = buildPrefixIndex()

    fun all(): List<T> = all

    /** Ranked search: exact name > name prefix > token match > group > substring. */
    fun search(query: String, limit: Int = 100): List<T> = rankedSearch(query, null, limit)

    fun search(query: String, sport: String, limit: Int = 100): List<T> =
        rankedSearch(query, sport.takeUnless { it.isBlank() || it == "All" }, limit)

    fun bySport(limit: Int = 3000): Map<String, List<T>> = all.asSequence()
        .take(limit.coerceIn(1, 3000))
        .groupBy { sports[it] ?: "Other" }

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

    private fun rankedSearch(query: String, sport: String?, limit: Int): List<T> {
        val q = normalize(query)
        if (q.isEmpty()) return emptyList()
        val safeLimit = limit.coerceIn(1, 500)
        val queryTokens = tokenize(q)
        if (queryTokens.isEmpty()) return emptyList()

        val candidates = linkedSetOf<T>()
        queryTokens.forEach { token ->
            tokenIndex[token]?.let(candidates::addAll)
            prefixIndex[token]?.let(candidates::addAll)
            prefixIndex.entries.asSequence()
                .filter { it.key.startsWith(token) }
                .take(24)
                .forEach { candidates.addAll(it.value) }
        }
        // Short/odd queries may not have useful buckets; fall back only for those.
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

    private fun tokenize(value: String): List<String> = value.split(' ').filter { it.length >= 1 }.distinct()

    private fun normalize(value: String): String = value
        .lowercase()
        .replace('&', ' ')
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")
}
