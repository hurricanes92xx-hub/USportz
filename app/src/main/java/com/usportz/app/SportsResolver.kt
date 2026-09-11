package com.usportz.app

/**
 * Sports-first resolver: an event is resolved against the user's IPTV catalog using
 * channel name, group/category and EPG-style metadata. It never invents a stream URL.
 */
object SportsResolver {
    data class WatchSource(val channel: SportsChannel, val score: Int, val reasons: List<String>)

    fun resolve(event: SportsEvent, channels: List<SportsChannel>, limit: Int = 8): List<WatchSource> {
        if (channels.isEmpty()) return emptyList()
        val seen = HashSet<String>()
        return channels.asSequence()
            .filter { seen.add(normalizeUrl(it.url)) }
            .mapNotNull { score(event, it) }
            .filter { it.score >= 45 }
            .sortedWith(compareByDescending<WatchSource> { it.score }.thenBy { it.channel.name.lowercase() })
            .take(limit.coerceIn(1, 16))
            .toList()
    }

    private fun score(event: SportsEvent, channel: SportsChannel): WatchSource? {
        val name = normalize(channel.name)
        val group = normalize(channel.group)
        val haystack = "$name $group"
        val reasons = ArrayList<String>()
        var score = 0

        val teams = event.competitors.map(::normalize).filter { it.length >= 4 }
        val teamHits = teams.count { team ->
            val compact = compact(team)
            haystack.contains(team) || (compact.length >= 5 && compact(haystack).contains(compact))
        }
        if (teamHits > 0) {
            score += 42 * teamHits
            reasons += if (teamHits > 1) "both teams in channel metadata" else "team in channel metadata"
        }

        val exactBroadcasts = event.broadcast.split(Regex("[,/|•]+"))
            .map(::normalize).filter { it.isNotBlank() }
        if (exactBroadcasts.any { b -> tokenMatch(haystack, b) }) {
            score += 38
            reasons += "broadcast network"
        }

        val preferred = SportsBroadcasts.preferredNetworks(event).map(::normalize)
        val networkHit = preferred.firstOrNull { tokenMatch(haystack, it) }
        if (networkHit != null) {
            score += if (exactBroadcasts.isNotEmpty()) 22 else 16
            reasons += networkHit.uppercase()
        }

        val league = normalize(event.league)
        if (league.isNotBlank() && (tokenMatch(group, league) || tokenMatch(name, league))) {
            score += 18
            reasons += "league/category"
        }

        val sport = normalize(SportsCatalog.classify(event.name, event.league))
        if (sport.isNotBlank() && (tokenMatch(group, sport) || haystack.contains(sport))) {
            score += 8
            reasons += "sport category"
        }

        // Strong penalty for generic unrelated entertainment/news channels.
        if (listOf("news", "weather", "music", "kids", "movie", "movies").any { haystack.contains(it) } && teamHits == 0 && networkHit == null) score -= 35

        val capped = score.coerceAtMost(100)
        return if (capped >= 45) WatchSource(channel, capped, reasons.distinct().take(4)) else null
    }

    private fun tokenMatch(haystack: String, needle: String): Boolean {
        if (needle.isBlank()) return false
        if (haystack == needle || haystack.contains(" $needle ")) return true
        return haystack.startsWith("$needle ") || haystack.endsWith(" $needle")
    }

    private fun normalize(v: String): String = SportsBroadcasts.normalize(v)
    private fun compact(v: String): String = v.filter(Char::isLetterOrDigit)
    private fun normalizeUrl(v: String): String = v.trim().lowercase().substringBefore("#")
}
