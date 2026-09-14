package com.usportz.app

import java.util.Locale

/** Sprint 5: league-aware normalization and scoring layered on the existing sports feeds/resolver. */
enum class MajorSport(val key: String, val label: String) {
    NCAA_FOOTBALL("ncaaf", "NCAA Football"),
    NCAA_BASKETBALL("ncaab", "NCAA Basketball"),
    NFL("nfl", "NFL"), NBA("nba", "NBA"), MLB("mlb", "MLB"), NHL("nhl", "NHL"),
    SOCCER("soccer", "Soccer"), UFC("ufc", "UFC"), TENNIS("tennis", "Tennis"), GOLF("golf", "Golf")
}

data class MajorSportsProfile(
    val sport: MajorSport,
    val leagueAliases: Set<String>,
    val teamAware: Boolean = true,
    val broadcastAliases: Set<String> = emptySet()
)

object MajorSportsIntelligence {
    private fun setOfAliases(vararg values: String): Set<String> = values.map(::normalize).filter { it.isNotBlank() }.toSet()

    private val profiles = listOf(
        MajorSportsProfile(MajorSport.NCAA_FOOTBALL, setOfAliases("college football", "ncaaf", "ncaa football", "ncaa"), true,
            setOfAliases("espn", "espn2", "espnu", "acc network", "sec network", "big ten network", "fox sports 1", "fox sports", "cbs sports network")),
        MajorSportsProfile(MajorSport.NCAA_BASKETBALL, setOfAliases("college basketball", "ncaab", "ncaaw", "ncaa basketball", "ncaa"), true,
            setOfAliases("espn", "espn2", "espnu", "acc network", "sec network", "big ten network", "fox sports 1", "cbs sports network", "tnt sports")),
        MajorSportsProfile(MajorSport.NFL, setOfAliases("nfl", "national football league"), true,
            setOfAliases("espn", "espn2", "fox", "fox sports", "cbs", "nbc", "usa", "nfl network")),
        MajorSportsProfile(MajorSport.NBA, setOfAliases("nba", "national basketball association"), true,
            setOfAliases("espn", "espn2", "abc", "tnt", "tnt sports", "nba tv")),
        MajorSportsProfile(MajorSport.MLB, setOfAliases("mlb", "major league baseball"), true,
            setOfAliases("espn", "espn2", "fox", "fox sports", "tbs", "mlb network")),
        MajorSportsProfile(MajorSport.NHL, setOfAliases("nhl", "national hockey league"), true,
            setOfAliases("espn", "espn2", "abc", "tnt", "tnt sports", "nhl network", "sportsnet", "tsn")),
        MajorSportsProfile(MajorSport.SOCCER, setOfAliases("soccer", "mls", "nwsl", "premier league", "epl", "la liga", "bundesliga", "serie a", "champions league", "europa league", "concacaf"), true,
            setOfAliases("espn", "espn2", "espn deportes", "fox sports", "fs1", "fs2", "univision", "tudn", "usa")),
        MajorSportsProfile(MajorSport.UFC, setOfAliases("ufc", "ultimate fighting championship", "mma"), false,
            setOfAliases("espn", "espn+", "espn plus", "ufc", "ppv")),
        MajorSportsProfile(MajorSport.TENNIS, setOfAliases("tennis", "atp", "wta", "grand slam", "us open", "wimbledon", "roland garros", "australian open"), false,
            setOfAliases("espn", "espn2", "tennis channel", "tsn")),
        MajorSportsProfile(MajorSport.GOLF, setOfAliases("golf", "pga", "lpga", "liv golf", "champions tour"), false,
            setOfAliases("golf channel", "espn", "cbs", "nbc", "usa"))
    )

    fun profile(event: SportsEvent): MajorSportsProfile? {
        val text = normalize(listOf(event.sport, event.league, event.name, event.shortName).joinToString(" "))
        return profiles.maxByOrNull { profile ->
            profile.leagueAliases.count { alias -> text.contains(alias) } * 10 +
                if (normalize(event.sport) == normalize(profile.sport.key)) 8 else 0
        }?.takeIf { profile ->
            profile.leagueAliases.any { alias -> text.contains(alias) } || normalize(event.sport) == normalize(profile.sport.key)
        }
    }

    /** Adds league-aware signal without replacing the existing broad matcher. */
    fun sourceBonus(event: SportsEvent, channel: SportsChannel): Int {
        val profile = profile(event) ?: return 0
        val channelText = normalize(listOf(channel.name, channel.group, channel.tvgName, channel.tvgId, channel.category, channel.provider).joinToString(" "))
        var bonus = 0
        if (profile.broadcastAliases.any { channelText.contains(it) }) bonus += 10
        if (profile.leagueAliases.any { channelText.contains(it) }) bonus += 8
        if (profile.sport == MajorSport.UFC && listOf("event", "ppv", "ufc").any { channelText.contains(it) }) bonus += 8
        if (profile.sport == MajorSport.TENNIS && listOf("tennis", "court", "atp", "wta").any { channelText.contains(it) }) bonus += 6
        if (profile.sport == MajorSport.GOLF && listOf("golf", "pga", "lpga", "liv").any { channelText.contains(it) }) bonus += 6
        return bonus
    }

    fun normalizeForMatch(value: String): String = normalize(value)

    fun allProfiles(): List<MajorSportsProfile> = profiles

    private fun normalize(value: String): String = value.lowercase(Locale.US)
        .replace("&", " and ").replace("+", " plus ")
        .replace(Regex("[^a-z0-9]+"), " ").trim().replace(Regex("\\s+"), " ")
}
