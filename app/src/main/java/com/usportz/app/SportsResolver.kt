package com.usportz.app

/**
 * Event -> IPTV resolver. Uses schedule/EPG metadata first, then broadcast intelligence,
 * and returns ranked watch alternatives. Scores below 45 are intentionally hidden.
 */
object SportsResolver {
    data class WatchSource(val channel: SportsChannel, val score: Int, val reasons: List<String>)

    private val qualityWords = setOf("4k", "uhd", "fhd", "hd", "1080p", "720p", "576p", "sd", "2160p", "50fps", "60fps")
    private val regionWords = setOf("east", "west", "central", "coast", "north", "south", "backup", "alt", "alternate", "feed", "us", "usa")
    private val noiseWords = setOf("news", "weather", "music", "kids", "movie", "movies", "entertainment")

    fun resolve(event: SportsEvent, channels: List<SportsChannel>, limit: Int = 8): List<WatchSource> {
        if (channels.isEmpty()) return emptyList()
        return channels.asSequence()
            .mapNotNull { score(event, it) }
            .filter { it.score >= 45 }
            .groupBy { streamFamily(it.channel) }
            .values
            .map { family -> family.maxWithOrNull(compareBy<WatchSource> { it.score }.thenBy { it.channel.name.length })!! }
            .sortedWith(compareByDescending<WatchSource> { it.score }.thenBy { it.channel.name.lowercase() })
            .take(limit.coerceIn(1, 16))
    }

    private fun score(event: SportsEvent, channel: SportsChannel): WatchSource? {
        val metadata = listOf(channel.name, channel.tvgName, channel.tvgId, channel.group, channel.category, channel.provider)
            .filter { it.isNotBlank() }.joinToString(" ") { normalize(it) }
        val reasons = ArrayList<String>()
        var score = 0

        val teamAliases = event.competitors.flatMap(::teamAliases).distinct()
        val teamHits = teamAliases.count { alias -> alias.length >= 3 && tokenOrCompactMatch(metadata, alias) }
        val distinctTeams = event.competitors.count { team -> teamAliasesForOne(team).any { tokenOrCompactMatch(metadata, it) } }
        if (distinctTeams >= 2) { score += 70; reasons += "both teams in EPG/channel metadata" }
        else if (teamHits > 0) { score += 42; reasons += "team in EPG/channel metadata" }

        val broadcasts = event.broadcast.split(Regex("[,/|•]+"))
            .map(::normalize).filter { it.isNotBlank() }
        val exact = broadcasts.firstOrNull { tokenMatch(metadata, it) }
        if (exact != null) { score += 38; reasons += "broadcast network" }

        val preferred = SportsBroadcasts.preferredNetworks(event).map(::normalize)
        val network = preferred.firstOrNull { tokenMatch(metadata, it) }
        if (network != null) {
            score += if (exact != null) 22 else 18
            reasons += network.uppercase()
        }

        val family = broadcastFamily(broadcasts + preferred)
        if (family != null && network == null && tokenMatch(metadata, family)) { score += 12; reasons += "broadcast family" }

        val league = normalize(event.league)
        if (league.isNotBlank() && (tokenMatch(channel.group, league) || tokenMatch(channel.category, league) || tokenMatch(channel.tvgName, league))) { score += 18; reasons += "league/category" }

        val sport = normalize(SportsCatalog.classify(event.name, event.league))
        if (sport.isNotBlank() && (tokenMatch(channel.group, sport) || tokenMatch(channel.category, sport))) { score += 8; reasons += "sport category" }

        if (noiseWords.any { metadata.contains(it) } && distinctTeams == 0 && network == null && exact == null) score -= 35
        val capped = score.coerceAtMost(100)
        return if (capped >= 45) WatchSource(channel, capped, reasons.distinct().take(4)) else null
    }

    /** Builds human-name, abbreviation and college-friendly aliases without requiring a database. */
    private fun teamAliases(team: String): List<String> {
        val n = normalize(team); if (n.isBlank()) return emptyList()
        val words = n.split(' ').filter { it.isNotBlank() }
        val aliases = linkedSetOf(n)
        if (words.size >= 2) aliases += words.takeLast(2).joinToString(" ")
        aliases += words.lastOrNull().orEmpty()
        if (words.size >= 2) aliases += words.mapNotNull { it.firstOrNull() }.joinToString("")
        val replacements = listOf("university" to "u", "state" to "st", "college" to "col", "saint" to "st", "mount" to "mt", "north" to "n", "south" to "s", "east" to "e", "west" to "w")
        replacements.forEach { (a, b) -> if (n.contains(a)) aliases += n.replace(a, b) }
        if (words.size >= 3) aliases += words.filterNot { it in setOf("the", "university", "college", "of", "at") }.joinToString(" ")
        // Common pro team abbreviations: NYY, NYM, BOS, LAD, etc. are matched by compact initials
        // and by the compact form of the final team token.
        aliases += compact(words.filterNot { it in setOf("the", "university", "college", "of", "at") }.joinToString(""))
        return aliases.filter { it.length >= 3 }
    }

    private fun teamAliasesForOne(team: String): List<String> = teamAliases(team)

    private fun broadcastFamily(values: List<String>): String? {
        val text = values.joinToString(" ")
        return when {
            listOf("espn", "espn2", "espnu", "espn+").any { text.contains(it) } -> "espn"
            listOf("fox", "fs1", "fs2").any { text.contains(it) } -> "fox"
            listOf("nbc", "usa", "peacock").any { text.contains(it) } -> "nbc"
            listOf("cbs", "cbssn", "paramount").any { text.contains(it) } -> "cbs"
            listOf("accn", "sec network", "secn", "btn", "big ten").any { text.contains(it) } -> "conference"
            listOf("sportsnet", "bally", "msg", "sny", "yes", "root", "fanduel").any { text.contains(it) } -> "regional"
            else -> null
        }
    }

    /** Collapse quality/region/feed suffixes so ESPN HD/FHD/1080P/East are one source family. */
    private fun streamFamily(channel: SportsChannel): String {
        val base = normalize(channel.tvgId.ifBlank { channel.tvgName.ifBlank { channel.name } })
        val words = base.split(' ').filter { it.isNotBlank() && it !in qualityWords && it !in regionWords }
        return compact(words.joinToString(" ")).ifBlank { normalizeUrl(channel.url) }
    }

    private fun tokenOrCompactMatch(haystack: String, needle: String): Boolean {
        return tokenMatch(haystack, needle) || (compact(needle).length >= 4 && compact(haystack).contains(compact(needle)))
    }

    private fun tokenMatch(haystack: String, needle: String): Boolean {
        val h = normalize(haystack); val n = normalize(needle)
        if (n.isBlank()) return false
        if (h == n || h.contains(" $n ")) return true
        return h.startsWith("$n ") || h.endsWith(" $n")
    }

    private fun normalize(v: String): String = SportsBroadcasts.normalize(v)
    private fun compact(v: String): String = v.filter(Char::isLetterOrDigit)
    private fun normalizeUrl(v: String): String = v.trim().lowercase().substringBefore("#").substringBefore("?")
}
