package com.usportz.app

/** Fast, bounded event -> channel matcher with broadcast-aware ranking. */
object GameSourceMatcher {
    data class Match(
        val channel: SportsChannel,
        val score: Int,
        val broadcastPriority: Int
    )

    fun rankMatches(event: SportsEvent, channels: List<SportsChannel>, limit: Int = 16): List<Match> {
        if (channels.isEmpty()) return emptyList()
        val index = ChannelIndex(channels, SportsChannel::name, SportsChannel::group)
        val candidates = LinkedHashMap<String, SportsChannel>()
        event.competitors.take(4).forEach { q -> index.search(q, 100).forEach { candidates[it.id] = it } }
        if (event.league.isNotBlank()) index.search(event.league, 100).forEach { candidates[it.id] = it }
        if (event.broadcast.isNotBlank()) index.search(event.broadcast, 60).forEach { candidates[it.id] = it }
        SportsBroadcasts.preferredNetworks(event).take(12).forEach { network ->
            index.search(network, 50).forEach { candidates[it.id] = it }
        }
        if (candidates.isEmpty()) {
            index.forSport(SportsCatalog.classify(event.name, event.league), 160).forEach { candidates[it.id] = it }
        }

        return candidates.values.asSequence()
            .map { channel ->
                val base = SportsSchedule.matchChannel(event, channel.name, channel.group)
                val broadcast = SportsBroadcasts.priority(event, channel.name, channel.group)
                val teamBonus = event.competitors.count { team ->
                    val n = SportsBroadcasts.normalize(team)
                    n.length >= 4 && SportsBroadcasts.normalize("${channel.name} ${channel.group}").contains(n)
                } * 8
                val score = base * 10 + broadcast + teamBonus
                Match(channel, score, broadcast)
            }
            .filter { it.score >= 50 || it.broadcastPriority >= 68 }
            .sortedWith(
                compareByDescending<Match> { it.score }
                    .thenByDescending { it.broadcastPriority }
                    .thenBy { it.channel.name.lowercase() }
            )
            .take(limit.coerceIn(1, 30))
            .toList()
    }

    fun findMatches(event: SportsEvent, channels: List<SportsChannel>, limit: Int = 16): List<SportsChannel> =
        rankMatches(event, channels, limit).map { it.channel }
}
