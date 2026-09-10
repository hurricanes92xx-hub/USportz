package com.usportz.app

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lightweight channel index designed for very large Xtream/M3U inventories.
 *
 * IMPORTANT: construction never builds millions of token/prefix entries on the
 * Compose/UI thread. The searchable index is prepared asynchronously. Until it
 * is ready, callers get bounded fallback results instead of blocking the UI.
 */
class ChannelIndex<T>(
    items: List<T>,
    private val name: (T) -> String,
    private val group: (T) -> String
) {
    private val allItems: List<T> = items
    private val ready = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "USportz-ChannelIndex").apply { isDaemon = true }
    }

    @Volatile private var normalizedNames: Map<T, String> = emptyMap()
    @Volatile private var normalizedGroups: Map<T, String> = emptyMap()
    @Volatile private var sports: Map<T, String> = emptyMap()
    @Volatile private var tokenIndex: Map<String, List<T>> = emptyMap()
    @Volatile private var groupList: List<String> = emptyList()

    init {
        executor.execute {
            try {
                val names = HashMap<T, String>(allItems.size)
                val groups = HashMap<T, String>(allItems.size)
                val sportMap = HashMap<T, String>(allItems.size)
                val tokens = HashMap<String, MutableList<T>>()
                val groupSet = LinkedHashMap<String, String>()

                allItems.forEach { item ->
                    val n = normalize(name(item))
                    val g = normalize(group(item))
                    names[item] = n
                    groups[item] = g
                    sportMap[item] = SportsCatalog.classify(name(item), group(item))
                    tokenize(n).forEach { token -> tokens.getOrPut(token) { mutableListOf() }.add(item) }
                    tokenize(g).forEach { token -> tokens.getOrPut(token) { mutableListOf() }.add(item) }
                    val rawGroup = group(item).trim()
                    if (rawGroup.isNotEmpty()) groupSet.putIfAbsent(rawGroup.lowercase(), rawGroup)
                }

                normalizedNames = names
                normalizedGroups = groups
                sports = sportMap
                tokenIndex = tokens.mapValues { (_, value) -> value.distinct() }
                groupList = groupSet.values.sortedBy { it.lowercase() }
                ready.set(true)
            } finally {
                executor.shutdown()
            }
        }
    }

    fun isReady(): Boolean = ready.get()

    fun all(): List<T> = allItems

    fun search(query: String, limit: Int = 100): List<T> = rankedSearch(query, null, limit)

    fun search(query: String, sport: String, limit: Int = 100): List<T> =
        rankedSearch(query, sport.takeUnless { it.isBlank() || it == "All" }, limit)

    fun bySport(limit: Int = Int.MAX_VALUE): Map<String, List<T>> {
        if (!ready.get()) return emptyMap()
        return allItems.asSequence().take(limit.coerceAtLeast(1)).groupBy { sports[it] ?: "Other" }
    }

    fun forSport(sport: String, limit: Int = Int.MAX_VALUE): List<T> {
        val selected = sport.trim()
        if (selected.isEmpty() || selected == "All") return allItems.take(limit.coerceAtLeast(1))
        if (!ready.get()) return emptyList()
        return allItems.asSequence()
            .filter { sports[it] == selected }
            .take(limit.coerceAtLeast(1))
            .toList()
    }

    fun groups(): List<String> = groupList

    private fun rankedSearch(query: String, sport: String?, limit: Int): List<T> {
        val q = normalize(query)
        if (q.isEmpty()) return emptyList()
        val safeLimit = limit.coerceIn(1, 500)
        if (!ready.get()) {
            // Never walk tens of thousands of provider rows on the UI thread.
            return allItems.asSequence()
                .take(1500)
                .filter { sport == null || SportsCatalog.classify(name(it), group(it)) == sport }
                .filter { normalize(name(it)).contains(q) || normalize(group(it)).contains(q) }
                .take(safeLimit)
                .toList()
        }

        val queryTokens = tokenize(q)
        if (queryTokens.isEmpty()) return emptyList()
        val candidates = linkedSetOf<T>()
        queryTokens.forEach { token -> tokenIndex[token]?.let(candidates::addAll) }
        if (candidates.isEmpty()) candidates.addAll(allItems.take(3000))

        return candidates.asSequence()
            .filter { sport == null || sports[it] == sport }
            .map { it to score(it, q, queryTokens) }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<T, Int>> { it.second }.thenBy { normalizedNames[it.first].orEmpty() })
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
        tokens.forEach { token ->
            if (n.split(' ').contains(token)) score += 120
            else if (n.split(' ').any { it.startsWith(token) }) score += 75
            if (g.split(' ').contains(token)) score += 55
            else if (g.split(' ').any { it.startsWith(token) }) score += 30
        }
        return score
    }

    private fun tokenize(value: String): List<String> = value.split(' ').filter { it.isNotEmpty() }.distinct()

    private fun normalize(value: String): String = value
        .lowercase()
        .replace('&', ' ')
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")
}
