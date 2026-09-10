package com.usportz.app

/** Fast, bounded matcher used only when the user opens an event's source picker. */
object GameSourceMatcher {
    fun findMatches(event: SportsEvent, channels: List<SportsChannel>, limit: Int = 16): List<SportsChannel> {
        if (channels.isEmpty()) return emptyList()
        val index = ChannelIndex(channels, SportsChannel::name, SportsChannel::group)
        val candidates = LinkedHashMap<String, SportsChannel>()
        event.competitors.take(4).forEach { q -> index.search(q, 80).forEach { candidates[it.id] = it } }
        if (event.league.isNotBlank()) index.search(event.league, 80).forEach { candidates[it.id] = it }
        if (event.broadcast.isNotBlank()) index.search(event.broadcast, 40).forEach { candidates[it.id] = it }
        if (candidates.isEmpty()) index.forSport(SportsCatalog.classify(event.name, event.league), 120).forEach { candidates[it.id] = it }
        return candidates.values.asSequence()
            .map { it to SportsSchedule.matchChannel(event, it.name, it.group) }
            .filter { it.second >= 5 }
            .sortedWith(compareByDescending<Pair<SportsChannel, Int>> { it.second }.thenBy { it.first.name.lowercase() })
            .take(limit.coerceIn(1, 30))
            .map { it.first }
            .toList()
    }
}
