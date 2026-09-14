package com.usportz.app

/**
 * Event -> IPTV resolver. Pairs provider event feeds ahead of generic network
 * channels, while collapsing quality/region variants into one stream family.
 */
object SportsResolver {
    data class WatchSource(val channel: SportsChannel, val score: Int, val reasons: List<String>)
    private const val INDEX_LIMIT = 240
    private val qualityWords = setOf("4k", "uhd", "fhd", "hd", "1080p", "720p", "576p", "sd", "2160p", "50fps", "60fps")
    private val regionWords = setOf("east", "west", "central", "coast", "north", "south", "backup", "alt", "alternate", "feed", "us", "usa", "ca", "canada", "cl", "nl", "uk", "il", "it")
    private val noiseWords = setOf("news", "weather", "music", "kids", "movie", "movies", "entertainment")

    fun resolve(event: SportsEvent, channels: List<SportsChannel>, limit: Int = 8): List<WatchSource> {
        val indexed = runCatching { SportsChannelBridge.indexedCandidates(event, INDEX_LIMIT) }.getOrDefault(emptyList())
        val working = if (indexed.isEmpty()) channels else (indexed + channels).distinctBy { "${it.id}|${it.url}" }
        if (working.isEmpty()) return emptyList()
        return working.asSequence().mapNotNull { score(event, it) }.filter { it.score >= 45 }
            .groupBy { streamFamily(it.channel) }.values
            .mapNotNull { family -> family.maxWithOrNull(compareBy<WatchSource> { it.score }.thenBy { it.channel.name.length }) }
            .sortedWith(compareByDescending<WatchSource> { it.score }.thenBy { it.channel.name.lowercase() })
            .take(limit.coerceIn(1, 16))
    }

    private fun score(event: SportsEvent, channel: SportsChannel): WatchSource? {
        val metadata = normalize(listOf(channel.name, channel.tvgName, channel.tvgId, channel.group, channel.category, channel.provider).filter { it.isNotBlank() }.joinToString(" "))
        val reasons = ArrayList<String>(); var score = 0
        val labelMatch = channelLabelMatch(event, metadata)
        if (labelMatch != null) { score += labelMatch.first; reasons += labelMatch.second }
        val aliases = event.competitors.flatMap(::teamAliases).distinct()
        val distinctTeams = event.competitors.count { team -> teamAliases(team).any { tokenOrCompactMatch(metadata, it) } }
        val teamHits = aliases.count { it.length >= 3 && tokenOrCompactMatch(metadata, it) }
        if (distinctTeams >= 2) { score += 70; reasons += "both teams in EPG/channel metadata" }
        else if (teamHits > 0) { score += 42; reasons += "team in EPG/channel metadata" }
        val broadcasts = event.broadcast.split(Regex("[,/|•]+" )).map(::normalize).filter { it.isNotBlank() }
        val exact = broadcasts.firstOrNull { tokenMatch(metadata, it) }
        if (exact != null) { score += 38; reasons += "broadcast network" }
        val preferred = SportsBroadcasts.preferredNetworks(event).map(::normalize)
        val network = preferred.firstOrNull { tokenMatch(metadata, it) }
        if (network != null) { score += if (exact != null) 22 else if (event.broadcast.isBlank()) 30 else 18; reasons += "likely ${network.uppercase()} broadcast" }
        val family = broadcastFamily(broadcasts + preferred)
        if (family != null && network == null && tokenMatch(metadata, family)) { score += 12; reasons += "broadcast family" }
        val league = normalize(event.league)
        if (league.isNotBlank() && (tokenMatch(channel.group, league) || tokenMatch(channel.category, league) || tokenMatch(channel.tvgName, league))) { score += 18; reasons += "league/category" }
        val sport = normalize(SportsCatalog.classify(event.name, event.league))
        if (sport.isNotBlank() && (tokenMatch(channel.group, sport) || tokenMatch(channel.category, sport))) { score += 8; reasons += "sport category" }
        if (noiseWords.any { metadata.contains(it) } && distinctTeams == 0 && network == null && exact == null && labelMatch == null) score -= 35
        val capped = score.coerceAtMost(100)
        return if (capped >= 45) WatchSource(channel, capped, reasons.distinct().take(5)) else null
    }

    private fun channelLabelMatch(event: SportsEvent, metadata: String): Pair<Int, String>? {
        val league = normalize(event.league); val sport = normalize(SportsCatalog.classify(event.name, event.league))
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
        val n = normalize(team); if (n.isBlank()) return emptyList(); val words = n.split(' ').filter { it.isNotBlank() }; val aliases = linkedSetOf(n)
        if (words.size >= 2) aliases += words.takeLast(2).joinToString(" "); aliases += words.lastOrNull().orEmpty()
        if (words.size >= 2) aliases += words.mapNotNull { it.firstOrNull() }.joinToString("")
        listOf("university" to "u", "state" to "st", "college" to "col", "saint" to "st", "mount" to "mt", "north" to "n", "south" to "s", "east" to "e", "west" to "w").forEach { (a,b) -> if (n.contains(a)) aliases += n.replace(a,b) }
        if (words.size >= 3) aliases += words.filterNot { it in setOf("the", "university", "college", "of", "at") }.joinToString(" ")
        aliases += compact(words.filterNot { it in setOf("the", "university", "college", "of", "at") }.joinToString(""))
        return aliases.filter { it.length >= 3 }
    }
    private fun broadcastFamily(values: List<String>): String? { val text = values.joinToString(" "); return when { listOf("espn", "espn2", "espnu", "espn+").any { text.contains(it) } -> "espn"; listOf("fox", "fs1", "fs2").any { text.contains(it) } -> "fox"; listOf("nbc", "usa", "peacock").any { text.contains(it) } -> "nbc"; listOf("cbs", "cbssn", "paramount").any { text.contains(it) } -> "cbs"; listOf("tsn", "tsn1", "tsn2", "tsn3", "tsn4", "tsn5").any { text.contains(it) } -> "tsn"; listOf("sportsnet", "rds", "tva sports", "cbc").any { text.contains(it) } -> "sportsnet"; listOf("accn", "sec network", "secn", "btn", "big ten").any { text.contains(it) } -> "conference"; listOf("bally", "msg", "sny", "yes", "root", "fanduel").any { text.contains(it) } -> "regional"; else -> null } }
    private fun streamFamily(channel: SportsChannel): String { val base = normalize(channel.tvgId.ifBlank { channel.tvgName.ifBlank { channel.name } }); val words = base.split(' ').filter { it.isNotBlank() && it !in qualityWords && it !in regionWords }; return compact(words.joinToString(" ")).ifBlank { normalizeUrl(channel.url) } }
    private fun hasWord(text: String, value: String): Boolean { val n = normalize(value); return tokenMatch(text, n) || (n.length >= 4 && compact(text).contains(compact(n))) }
    private fun tokenOrCompactMatch(haystack: String, needle: String): Boolean = tokenMatch(haystack, needle) || (compact(needle).length >= 4 && compact(haystack).contains(compact(needle)))
    private fun tokenMatch(haystack: String, needle: String): Boolean { val h = normalize(haystack); val n = normalize(needle); if (n.isBlank()) return false; if (h == n || h.contains(" $n ")) return true; return h.startsWith("$n ") || h.endsWith(" $n") }
    private fun normalize(v: String): String = SportsBroadcasts.normalize(v)
    private fun compact(v: String): String = v.filter(Char::isLetterOrDigit)
    private fun normalizeUrl(v: String): String = v.trim().lowercase().substringBefore('#').substringBefore('?')
}
