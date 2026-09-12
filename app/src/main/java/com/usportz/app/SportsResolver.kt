package com.usportz.app

/**
 * Event -> IPTV resolver. Understands normal networks plus the event-channel naming
 * patterns commonly used by sports IPTV playlists: NCAAF 01, NCAAB 12, NFL 03,
 * "TEAM A vs TEAM B - ESPN+", regional prefixes such as US/CA, and PPV/event feeds.
 */
object SportsResolver {
    data class WatchSource(val channel: SportsChannel, val score: Int, val reasons: List<String>)

    private val qualityWords = setOf("4k", "uhd", "fhd", "hd", "1080p", "720p", "576p", "sd", "2160p", "50fps", "60fps")
    private val regionWords = setOf("east", "west", "central", "coast", "north", "south", "backup", "alt", "alternate", "feed", "us", "usa", "ca", "canada")
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
        val rawMetadata = listOf(channel.name, channel.tvgName, channel.tvgId, channel.group, channel.category, channel.provider)
            .filter { it.isNotBlank() }
            .joinToString(" ")
        val metadata = normalize(rawMetadata)
        val reasons = ArrayList<String>()
        var score = 0

        // 1) Many IPTV providers create actual event channels, e.g. "NCAAF 48:
        // UConn Vs UCF ... ESPN+". Recognize the sport/category token even when
        // the channel has no normal network name.
        val labelMatch = channelLabelMatch(event, metadata)
        if (labelMatch != null) {
            score += labelMatch.first
            reasons += labelMatch.second
        }

        // 2) Team matching is the strongest signal for event-specific channels.
        val teamAliases = event.competitors.flatMap(::teamAliases).distinct()
        val teamHits = teamAliases.count { alias -> alias.length >= 3 && tokenOrCompactMatch(metadata, alias) }
        val distinctTeams = event.competitors.count { team -> teamAliasesForOne(team).any { tokenOrCompactMatch(metadata, it) } }
        if (distinctTeams >= 2) { score += 70; reasons += "both teams in EPG/channel metadata" }
        else if (teamHits > 0) { score += 42; reasons += "team in EPG/channel metadata" }

        // 3) Prefer the event's explicit broadcast when the schedule provides it.
        val broadcasts = event.broadcast.split(Regex("[,/|•]+"))
            .map(::normalize).filter { it.isNotBlank() }
        val exact = broadcasts.firstOrNull { tokenMatch(metadata, it) }
        if (exact != null) { score += 38; reasons += "broadcast network" }

        // 4) If the schedule has no broadcast, use league/sport broadcast intelligence.
        // A primary network match is intentionally worth enough to surface ESPN/TSN/etc.
        // for NCAA games whose feed omitted a broadcast field.
        val preferred = SportsBroadcasts.preferredNetworks(event).map(::normalize)
        val network = preferred.firstOrNull { tokenMatch(metadata, it) }
        if (network != null) {
            val primaryWeight = if (event.broadcast.isBlank()) 30 else 18
            score += if (exact != null) 22 else primaryWeight
            reasons += "likely ${network.uppercase()} broadcast"
        }

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

    /**
     * Recognizes the provider-side sports-event labels seen in real IPTV lists:
     * NCAAF 01..50, NCAAB 01..57, NFL 01, NBA 01, NHL 01, CFL 01, PPV/event feeds,
     * and variants such as "NCAAF: TEAM A AT TEAM B". Prefixes like US| and CA| are
     * deliberately ignored as region metadata rather than treated as team names.
     */
    private fun channelLabelMatch(event: SportsEvent, metadata: String): Pair<Int, String>? {
        val normalizedLeague = normalize(event.league)
        val sport = normalize(SportsCatalog.classify(event.name, event.league))
        val isFootball = sport.contains("football") || normalizedLeague.contains("football") || normalizedLeague.contains("ncaaf")
        val isBasketball = sport.contains("basketball") || normalizedLeague.contains("basketball") || normalizedLeague.contains("ncaab")
        val tags = when {
            isFootball -> listOf("ncaaf", "ncaa football", "college football", "nfl", "cfl", "football")
            isBasketball -> listOf("ncaab", "ncaa basketball", "college basketball", "nba", "basketball")
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
        val explicitEvent = metadata.contains(" vs ") || metadata.contains(" at ") || metadata.contains(" @ ") || metadata.contains(" feed ") || metadata.contains(" ppv ")
        return if (explicitEvent) 34 to "sports event feed: $hit" else 30 to "sports event channel: $hit"
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
            listOf("tsn", "tsn1", "tsn2", "tsn3", "tsn4", "tsn5").any { text.contains(it) } -> "tsn"
            listOf("sportsnet", "rds", "tva sports", "cbc").any { text.contains(it) } -> "sportsnet"
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

    private fun hasWord(text: String, value: String): Boolean {
        val n = normalize(value)
        return tokenMatch(text, n) || (n.length >= 4 && compact(text).contains(compact(n)))
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
