package com.usportz.app

/**
 * Channels that should be discovered before generic international sports channels.
 * The goal is first-paint coverage for the U.S./Canada viewer while the complete
 * provider catalogue continues streaming in the background.
 */
object NorthAmericaSportsStartupPolicy {
    private val tier1 = listOf(
        "espn", "espn2", "espnu", "espn news", "espn deportes", "espn+",
        "fs1", "fs2", "fox sports", "cbs sports", "cbssn", "tnt", "tnt sports", "tbs", "trutv",
        "nbc", "nbc sports", "usa network", "usa", "abc", "cbs", "fox", "cw", "ion",
        "nfl network", "nfl redzone", "nba tv", "mlb network", "nhl network", "golf channel", "tennis channel",
        "acc network", "accn", "sec network", "secn", "big ten network", "btn", "b1g",
        "sportsnet", "sportsnet one", "sportsnet 360", "sportsnet east", "sportsnet ontario", "sportsnet west", "sportsnet pacific",
        "tsn", "tsn1", "tsn2", "tsn3", "tsn4", "tsn5", "rds", "rds2", "tva sports", "cbc", "cbc sports", "ctv", "ctv2",
        "yes network", "msg network", "msg", "sny", "masn", "bally sports", "fanduel sports network", "root sports"
    )

    private val tier2 = listOf(
        "tudn", "univision", "unimas", "universo", "telemundo", "fox deportes", "dazn", "fight network",
        "willow", "flosports", "flo sports", "mls season pass", "one soccer", "onesoccer",
        "peacock", "prime video", "prime", "paramount+", "apple tv", "apple tv+", "max",
        "nfl+", "nba league pass", "mlb.tv", "nhl.tv", "espn+ event", "espn plus event"
    )

    private val eventWords = listOf(
        "ppv", "event", "events", "event 01", "event 02", "event 03", "event 04", "event 05",
        "nfl ", "nba ", "nhl ", "mlb ", "ncaaf", "ncaab", "ncaaw", "ncaa", "ufc", "wwe", "aew",
        "college football", "college basketball", "fight", "soccer", "football", "basketball", "baseball", "hockey"
    )

    fun priority(channel: SportsChannel): Int {
        val text = normalize(listOf(channel.name, channel.tvgName, channel.tvgId, channel.group, channel.category).joinToString(" "))
        if (text.isBlank()) return 0

        val exact1 = tier1.firstOrNull { matches(text, normalize(it)) }
        if (exact1 != null) return 1000 - tier1.indexOf(exact1)

        val exact2 = tier2.firstOrNull { matches(text, normalize(it)) }
        if (exact2 != null) return 800 - tier2.indexOf(exact2)

        if (eventWords.any { text.contains(normalize(it)) }) return 650
        if (SportsNetworkCatalog.find(channel) != null) return 600
        if (SportsNetworkCatalog.isSportsChannel(channel)) return 400
        return 0
    }

    fun isNorthAmericaPriority(channel: SportsChannel): Boolean = priority(channel) >= 650

    private fun matches(text: String, alias: String): Boolean =
        text == alias || text.contains(" $alias ") || text.startsWith("$alias ") || text.endsWith(" $alias")

    private fun normalize(value: String): String = value.lowercase()
        .replace("&", " and ")
        .replace("+", " plus ")
        .replace("espn plus", "espn+")
        .replace("fox sports 1", "fs1")
        .replace("fox sports 2", "fs2")
        .replace("big ten network", "btn")
        .replace("sec network", "secn")
        .replace("acc network", "accn")
        .replace("usa network", "usa")
        .replace(Regex("[^a-z0-9+]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")
}
