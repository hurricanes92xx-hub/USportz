package com.usportz.app

/** Fast, bounded event -> channel matcher with normalized teams, broadcaster confidence, EPG intelligence and hard exclusions. */
object GameSourceMatcher {
    data class Match(val channel: SportsChannel, val score: Int, val broadcastPriority: Int, val confidence: Int, val reasons: List<String> = emptyList())

    fun rankMatches(event: SportsEvent, channels: List<SportsChannel>, limit: Int = 16): List<Match> {
        if (channels.isEmpty()) return emptyList()
        val normalizedEvent = SportsEventNormalizer.normalize(event)
        val index = ChannelIndex(channels, SportsChannel::name, SportsChannel::group)
        val candidates = LinkedHashMap<String, SportsChannel>()
        event.competitors.take(4).forEach { q -> index.search(q, 100).forEach { candidates[it.id] = it } }
        if (event.league.isNotBlank()) index.search(event.league, 100).forEach { candidates[it.id] = it }
        if (event.broadcast.isNotBlank()) index.search(event.broadcast, 60).forEach { candidates[it.id] = it }
        SportsBroadcasts.preferredNetworks(event).take(12).forEach { network -> index.search(network, 50).forEach { candidates[it.id] = it } }
        if (candidates.isEmpty()) index.forSport(SportsCatalog.classify(event.name, event.league), 160).forEach { candidates[it.id] = it }

        val eventTeams = normalizedEvent.normalizedTeams
        return candidates.values.asSequence().mapNotNull { channel ->
            val haystack = SportsEventNormalizerText.channelText(channel)
            if (eventTeams.isNotEmpty() && containsUnrelatedTeam(haystack, eventTeams)) return@mapNotNull null
            val base = SportsSchedule.matchChannel(event, channel.name, channel.group)
            val broadcast = SportsBroadcasts.priority(event, channel.name, channel.group)
            val normalizedBroadcast = if (event.broadcast.isNotBlank() && BroadcasterNormalizer.matches(event.broadcast, channel.name)) 20 else 0
            val matchedTeams = event.competitors.count { TeamAliasEngine.matches(it, haystack) }
            val leagueHit = event.league.isNotBlank() && haystack.contains(BroadcasterNormalizer.canonical(event.league))
            val eventFeed = Regex("\\b(event|feed|ppv|live)\\b", RegexOption.IGNORE_CASE).containsMatchIn(channel.name)
            val majorSportsBonus = MajorSportsIntelligence.sourceBonus(event, channel)
            val epg = EpgIntelligence.score(event, channel)
            val epgBroadcast = EpgIntelligence.broadcasterScore(event, channel)
            val score = base * 10 + broadcast + normalizedBroadcast + matchedTeams * 15 + if (leagueHit) 10 else 0 + if (eventFeed) 4 else 0 + majorSportsBonus + epg.score / 4 + epgBroadcast
            val confidence = confidence(score, matchedTeams, eventTeams.size, broadcast, normalizedBroadcast, leagueHit)
            val reasons = buildList {
                if (matchedTeams > 0) add("team")
                if (broadcast > 0 || normalizedBroadcast > 0 || epgBroadcast > 0) add("broadcaster")
                if (leagueHit) add("league")
                if (eventFeed) add("event-feed")
                if (majorSportsBonus > 0) add("major-sports")
                if (epg.score > 0) add("epg")
            }
            Match(channel, score, broadcast, confidence, reasons)
        }.filter { it.confidence >= 55 || it.broadcastPriority >= 68 }
            .sortedWith(compareByDescending<Match> { it.confidence }.thenByDescending { it.score }.thenByDescending { it.broadcastPriority }.thenBy { it.channel.name.lowercase() })
            .take(limit.coerceIn(1, 30)).toList()
    }

    fun findMatches(event: SportsEvent, channels: List<SportsChannel>, limit: Int = 16): List<SportsChannel> = rankMatches(event, channels, limit).map { it.channel }

    private fun confidence(score: Int, matchedTeams: Int, teamCount: Int, broadcast: Int, normalizedBroadcast: Int, leagueHit: Boolean): Int {
        var value = (score * 100 / 190).coerceIn(0, 100)
        if (teamCount >= 2 && matchedTeams >= 2) value += 12 else if (matchedTeams == 1) value += 4
        if (broadcast >= 60 || normalizedBroadcast > 0) value += 8
        if (leagueHit) value += 5
        return value.coerceIn(0, 100)
    }

    private fun containsUnrelatedTeam(text: String, eventTeams: List<String>): Boolean {
        val normalized = TeamAliasEngine.canonical(text); val known = SportsTeamLexicon.knownTeams()
        return known.any { knownTeam -> val canonicalKnown = TeamAliasEngine.canonical(knownTeam); canonicalKnown.length >= 5 && normalized.contains(canonicalKnown) && eventTeams.none { it == canonicalKnown || it.contains(canonicalKnown) || canonicalKnown.contains(it) } }
    }
}

object SportsEventNormalizerText {
    fun channelText(channel: SportsChannel): String = listOf(channel.name, channel.group, channel.tvgName, channel.tvgId, channel.category, channel.provider).filter { it.isNotBlank() }.joinToString(" ")
}

object SportsTeamLexicon {
    private val teams = setOf("Alabama", "Auburn", "Arkansas", "Clemson", "Duke", "Florida", "Florida State", "Georgia", "Georgia Tech", "LSU", "Michigan", "Michigan State", "Notre Dame", "Ohio State", "Oklahoma", "Oklahoma State", "Ole Miss", "Oregon", "Penn State", "Texas", "Texas A&M", "Texas Tech", "USC", "UCLA", "Utah", "Washington", "Wisconsin", "Iowa", "Kansas", "Kansas State", "Kentucky", "Louisville", "NC State", "North Carolina", "Pittsburgh", "Syracuse", "Virginia", "Virginia Tech", "West Virginia", "Tennessee", "South Carolina", "Miami Dolphins", "Buffalo Bills", "New England Patriots", "New York Jets", "Baltimore Ravens", "Cincinnati Bengals", "Cleveland Browns", "Pittsburgh Steelers", "Jacksonville Jaguars", "Houston Texans", "Indianapolis Colts", "Tennessee Titans", "Kansas City Chiefs", "Denver Broncos", "Las Vegas Raiders", "Los Angeles Chargers", "Dallas Cowboys", "Philadelphia Eagles", "New York Giants", "Washington Commanders", "Green Bay Packers", "Chicago Bears", "Detroit Lions", "Minnesota Vikings", "Tampa Bay Buccaneers", "Atlanta Falcons", "Carolina Panthers", "New Orleans Saints", "Los Angeles Rams", "San Francisco 49ers", "Seattle Seahawks", "Arizona Cardinals")
    fun knownTeams(): Set<String> = teams
}
