package com.usportz.app

/**
 * Event -> IPTV resolver. Uses cheap indexed candidate retrieval first, then
 * identity/network/league scoring. Results are collapsed into stream families.
 * Provider health is deliberately a secondary signal: a temporarily bad
 * channel should lose priority, not make a valid event disappear.
 *
 * Confidence hierarchy:
 *  - Tier 1: both teams, or a verified broadcast/network identity.
 *  - Tier 2: one team plus strong league/network evidence.
 *  - Tier 3: generic league/sport/event-feed channels, never allowed to
 *    outrank a channel carrying a concrete team or exact broadcaster match.
 */
object SportsResolver {
    data class WatchSource(val channel: SportsChannel, val score: Int, val reasons: List<String>)

    private const val INDEX_LIMIT = 240
    private val qualityWords = setOf("4k", "uhd", "fhd", "hd", "1080p", "720p", "576p", "sd", "2160p", "50fps", "60fps")
    private val regionWords = setOf("east", "west", "central", "coast", "north", "south", "backup", "alt", "alternate", "feed", "us", "usa", "ca", "canada", "cl", "nl", "uk", "il", "it")
    private val noiseWords = setOf("news", "weather", "music", "kids", "movie", "movies", "entertainment")

    fun resolve(event: SportsEvent, channels: List<SportsChannel>, limit: Int = 8): List<WatchSource> {
        val indexed = runCatching { SportsChannelBridge.indexedCandidates(event, INDEX_LIMIT) }.getOrDefault(emptyList())
        val working = (indexed + channels).distinctBy { "${it.id}|${it.url}" }
        if (working.isEmpty()) return emptyList()
        val now = System.currentTimeMillis()
        return working.asSequence()
            .filterNot { ProviderHealth.isOpen(it.provider, now) }
            .mapNotNull { score(event, it, now) }
            .filter { it.score >= 45 }
            .groupBy { streamFamily(it.channel) }
            .values
            .mapNotNull { family -> family.maxWithOrNull(compareBy<WatchSource> { it.score }.thenBy { it.channel.name.length }) }
            .sortedWith(compareByDescending<WatchSource> { it.score }.thenBy { it.channel.name.lowercase() })
            .take(limit.coerceIn(1, 16))
    }

    private fun score(event: SportsEvent, channel: SportsChannel, now: Long): WatchSource? {
        val metadata = normalize(listOf(channel.name, channel.tvgName, channel.tvgId, channel.group, channel.category, channel.provider).filter { it.isNotBlank() }.joinToString(" "))
        val reasons = ArrayList<String>()
        var score = 0
        val labelMatch = channelLabelMatch(event, metadata)
        if (labelMatch != null) { score += labelMatch.first; reasons += labelMatch.second }

        val aliases = event.competitors.flatMap(::teamAliases).distinct()
        val distinctTeams = event.competitors.count { team -> teamAliases(team).any { tokenOrCompactMatch(metadata, it) } }
        val teamHits = aliases.count { it.length >= 3 && tokenOrCompactMatch(metadata, it) }
        if (distinctTeams >= 2) { score += 70; reasons += "both teams in channel metadata" }
        else if (teamHits > 0) { score += 42; reasons += "team in channel metadata" }

        val broadcasts = event.broadcast.split(Regex("[,/|•]+" )).map(::normalize).filter { it.isNotBlank() }
        val exact = broadcasts.firstOrNull { tokenMatch(metadata, it) }
        if (exact != null) { score += 38; reasons += "broadcast network" }
        val preferred = SportsBroadcasts.preferredNetworks(event).map(::normalize)
        val network = preferred.firstOrNull { tokenMatch(metadata, it) }
        if (network != null) { score += if (exact != null) 22 else if (event.broadcast.isBlank()) 30 else 18; reasons += "preferred ${network.uppercase()} network" }
        val family = broadcastFamily(broadcasts + preferred)
        if (family != null && network == null && tokenMatch(metadata, family)) { score += 12; reasons += "broadcast family" }

        val league = normalize(event.league)
        val leagueMatch = league.isNotBlank() && (tokenMatch(channel.group, league) || tokenMatch(channel.category, league) || tokenMatch(channel.tvgName, league))
        if (leagueMatch) { score += 18; reasons += "league/category" }
        val sport = normalize(SportsCatalog.classify(event.name, event.league))
        val sportMatch = sport.isNotBlank() && (tokenMatch(channel.group, sport) || tokenMatch(channel.category, sport))
        if (sportMatch) { score += 8; reasons += "sport category" }

        if (channel.url.isNotBlank()) { score += 3; reasons += "playable URL" }
        val healthPenalty = ProviderHealth.penalty(channel.provider, now)
        if (healthPenalty > 0) { score -= healthPenalty; reasons += "provider health -$healthPenalty" }
        if (noiseWords.any { metadata.contains(it) } && distinctTeams == 0 && network == null && exact == null && labelMatch == null) score -= 35

        val concreteEvidence = distinctTeams > 0 || teamHits > 0 || exact != null || network != null
        if (!concreteEvidence && labelMatch != null && leagueMatch) {
            score = score.coerceAtMost(59)
            reasons += "generic fallback tier"
        }

        if (!concreteEvidence && !leagueMatch && exact == null) return null

        val capped = score.coerceAtMost(100)
        return if (capped >= 45) WatchSource(channel, capped, reasons.distinct().take(6)) else null
    }

    private fun channelLabelMatch(event: SportsEvent, metadata: String): Pair<Int, String>? {
        val league = normalize(event.league)
        val sport = normalize(SportsCatalog.classify(event.name, event.league))
        val tags = when {
            sport.contains("football") || league.contains("football") || league.contains("ncaaf") -> listOf("ncaaf", "ncaa football", "college football", "nfl", "cfl", "football")
            sport.contains("basketball") || league.contains("basketball") || league.contains("ncaab") -> listOf("ncaab", "ncaa basketball", "college basketball", "nba", "basketball")
            sport.contains("baseball") -> listOf("mlb", "baseball")
            sport.contains("hockey") -> listOf("nhl", "hockey")
            sport.contains("soccer") -> listOf("mls", "soccer", "football")
            sport.contains("tennis") -> listOf("tennis")
            sport.contains("golf") -> listOf("golf")
            sport.contains("motorsport") -> listOf("f1", "formula 1", "nascar", "racing", "motorsport")
            sport.contains("mma") -> listOf("ufc", "mma", "fight")
            sport.contains("boxing") -> listOf("boxing", "fight")
            sport.contains("wrestling") -> listOf("wwe", "aew", "wrestling")
            else -> emptyList()
        }
        val hit = tags.firstOrNull { hasWord(metadata, it) } ?: return null
        val explicit = metadata.contains(" vs ") || metadata.contains(" at ") || metadata.contains(" @ ") || metadata.contains(" feed ") || metadata.contains(" ppv ")
        return if (explicit) 34 to "sports event feed: $hit" else 30 to "sports event channel: $hit"
    }

    private fun teamAliases(team: String): List<String> {
        val n = SportsEventFingerprint.normalizeTeam(team)
        if (n.isBlank()) return emptyList()
        val words = n.split(' ').filter(String::isNotBlank)
        val aliases = linkedSetOf(n)
        if (words.size >= 2) aliases += words.takeLast(2).joinToString(" ")
        words.lastOrNull()?.let { if (it.length >= 3) aliases += it }
        if (words.size >= 2) aliases += words.mapNotNull { it.firstOrNull() }.joinToString("")
        listOf("university" to "u", "state" to "st", "college" to "col", "saint" to "st", "mount" to "mt", "north" to "n", "south" to "s", "east" to "e", "west" to "w").forEach { (a, b) -> if (n.contains(a)) aliases += n.replace(a, b) }
        if (words.size >= 3) aliases += words.filterNot { it in setOf("the", "university", "college", "of", "at") }.joinToString(" ")
        aliases += compact(words.filterNot { it in setOf("the", "university", "college", "of", "at") }.joinToString(""))
        return aliases.filter { it.length >= 3 }
    }

    private fun broadcastFamily(values: List<String>): String? {
        val text = values.joinToString(" ")
        return when {
            listOf("espn", "espn2", "espnu", "espn+").any { text.contains(it) } -> "espn"
            listOf("fox", "fs1", "fs2").any { text.contains(it) } -> "fox"
            listOf("nbc", "usa", "peacock").any { text.contains(it) } -> "nbc"
            listOf("cbs", "cbssn", "paramount").any { text.contains(it) } -> "cbs"
            listOf("tsn", "tsn1", "tsn2", "tsn3", "tsn4", "tsn5").any { text.contains(it) } -> "tsn"
            listOf("sportsnet", "rds", "tva sports", "cbc").any { text.contains(it) } -> "sportsnet"
            listOf("accn", "sec network", "secn", "btn", "big ten").any { text.contains(it) } -> "conference"
            listOf("bally", "msg", "sny", "yes", "root", "fanduel").any { text.contains(it) } -> "regional"
            else -> null
        }
    }

    private fun streamFamily(channel: SportsChannel): String {
        val base = normalize(channel.tvgId.ifBlank { channel.tvgName.ifBlank { channel.name } })
        val words = base.split(' ').filter { it.isNotBlank() && it !in qualityWords && it !in regionWords }
        return compact(words.joinToString(" ")).ifBlank { normalizeUrl(channel.url) }
    }

    private fun hasWord(text: String, value: String): Boolean {
        val n = normalize(value)
        return tokenMatch(text, n) || (n.length >= 4 && compact(text).contains(compact(n)))
    }
    private fun tokenOrCompactMatch(haystack: String, needle: String): Boolean = tokenMatch(haystack, needle) || (compact(needle).length >= 4 && compact(haystack).contains(compact(needle)))
    private fun tokenMatch(haystack: String, needle: String): Boolean {
        val h = normalize(haystack); val n = normalize(needle)
        if (n.isBlank()) return false
        if (h == n) return true
        val padded = " $h "
        if (!padded.contains(" $n ")) return false
        if (!h.startsWith("$n ")) return true
        val suffix = h.removePrefix("$n ").trim()
        return suffix in qualityWords || suffix in setOf("network", "channel")
    }
    private fun normalize(v: String): String = SportsBroadcasts.normalize(v)
    private fun compact(v: String): String = v.filter(Char::isLetterOrDigit)
    private fun normalizeUrl(v: String): String = v.trim().lowercase().substringBefore('#').substringBefore('?')
}
