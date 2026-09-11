package com.usportz.app

/** Curated US/Canada sports-TV network directory used by the Sports TV tab. */
data class SportsNetwork(
    val key: String,
    val label: String,
    val aliases: List<String>,
    val logoUrl: String
)

object SportsNetworkCatalog {
    private const val ESPN = "https://a.espncdn.com/i/teamlogos/leagues/500/espn.png"
    private const val TSN = "https://upload.wikimedia.org/wikipedia/commons/3/3a/TSN_%282014%29_logo.svg"

    val networks: List<SportsNetwork> = listOf(
        SportsNetwork("espn", "ESPN", listOf("espn", "espn hd", "espn us", "espn usa"), ESPN),
        SportsNetwork("espn2", "ESPN2", listOf("espn2", "espn 2", "espn2 hd", "espn 2 hd"), ESPN),
        SportsNetwork("espnu", "ESPNU", listOf("espnu", "espn u", "espnu hd"), ESPN),
        SportsNetwork("espnews", "ESPNews", listOf("espnews", "espn news", "espnews hd"), ESPN),
        SportsNetwork("espndeportes", "ESPN Deportes", listOf("espn deportes", "espn deportes hd"), ESPN),
        SportsNetwork("espnplus", "ESPN+", listOf("espn+", "espn plus", "espnplus"), ESPN),
        SportsNetwork("accn", "ACC Network", listOf("acc network", "accn", "acc nx", "acc network hd", "accn hd"), "https://a.espncdn.com/i/teamlogos/leagues/500/acc.png"),
        SportsNetwork("secn", "SEC Network", listOf("sec network", "secn", "sec network hd", "secn hd"), "https://a.espncdn.com/i/teamlogos/leagues/500/sec.png"),
        SportsNetwork("btn", "Big Ten Network", listOf("big ten network", "btn", "btn hd", "big ten network hd"), "https://a.espncdn.com/i/teamlogos/leagues/500/btn.png"),
        SportsNetwork("fs1", "FOX Sports 1", listOf("fs1", "fox sports 1", "fox sports1", "fs1 hd", "fox sports 1 hd"), "https://a.espncdn.com/i/teamlogos/leagues/500/fs1.png"),
        SportsNetwork("fs2", "FOX Sports 2", listOf("fs2", "fox sports 2", "fox sports2", "fs2 hd", "fox sports 2 hd"), "https://a.espncdn.com/i/teamlogos/leagues/500/fs2.png"),
        SportsNetwork("foxsports", "FOX Sports", listOf("fox sports", "fox sports network", "fox sports hd", "fox sports regional"), "https://a.espncdn.com/i/teamlogos/leagues/500/fox.png"),
        SportsNetwork("cbssports", "CBS Sports Network", listOf("cbs sports network", "cbssn", "cbs sports", "cbs sports network hd", "cbssn hd"), "https://a.espncdn.com/i/teamlogos/leagues/500/cbs.png"),
        SportsNetwork("nbcsports", "NBC Sports", listOf("nbc sports", "nbc sports network", "nbc sports hd"), "https://a.espncdn.com/i/teamlogos/leagues/500/nbc.png"),
        SportsNetwork("nbc", "NBC", listOf("nbc sports", "nbc", "nbc hd", "nbc network"), "https://upload.wikimedia.org/wikipedia/commons/3/3f/NBC_logo.svg"),
        SportsNetwork("usa", "USA Network", listOf("usa network", "usa", "usa hd", "usa network hd"), "https://a.espncdn.com/i/teamlogos/leagues/500/usa.png"),
        SportsNetwork("tntsports", "TNT Sports", listOf("tnt sports", "tnt", "tnt sports hd", "tnt hd"), "https://a.espncdn.com/i/teamlogos/leagues/500/tnt.png"),
        SportsNetwork("trutv", "truTV Sports", listOf("trutv", "tru tv", "trutv sports", "trutv hd"), "https://upload.wikimedia.org/wikipedia/commons/8/87/TruTV_2014_logo.svg"),
        SportsNetwork("tsn", "TSN", listOf("tsn", "tsn1", "tsn 1", "tsn2", "tsn 2", "tsn3", "tsn 3", "tsn4", "tsn 4", "tsn5", "tsn 5", "tsn direct", "tsn hd"), TSN),
        SportsNetwork("sportsnet", "Sportsnet", listOf("sportsnet", "sn1", "sn 1", "sn west", "sn pacific", "sn ontario", "sn east", "sportsnet hd"), "https://upload.wikimedia.org/wikipedia/commons/7/7f/Sportsnet_2011_logo.svg"),
        SportsNetwork("sportsnetone", "Sportsnet ONE", listOf("sportsnet one", "sn one", "sportsnet one hd"), "https://upload.wikimedia.org/wikipedia/commons/7/7f/Sportsnet_2011_logo.svg"),
        SportsNetwork("sportsnet360", "Sportsnet 360", listOf("sportsnet 360", "sn 360", "sportsnet 360 hd"), "https://upload.wikimedia.org/wikipedia/commons/7/7f/Sportsnet_2011_logo.svg"),
        SportsNetwork("rds", "RDS", listOf("rds", "rds2", "rds 2", "rds info", "rds hd"), "https://upload.wikimedia.org/wikipedia/commons/5/5f/R%C3%A9seau_des_sports_logo.svg"),
        SportsNetwork("cfl", "CFL+", listOf("cfl+", "cfl plus", "cfl plus hd"), "https://upload.wikimedia.org/wikipedia/en/5/5b/Canadian_Football_League_logo.svg"),
        SportsNetwork("mlbnetwork", "MLB Network", listOf("mlb network", "mlb net", "mlb network hd"), "https://a.espncdn.com/i/teamlogos/leagues/500/mlb.png"),
        SportsNetwork("nflnetwork", "NFL Network", listOf("nfl network", "nfl net", "nfl network hd"), "https://a.espncdn.com/i/teamlogos/leagues/500/nfl.png"),
        SportsNetwork("nhlnetwork", "NHL Network", listOf("nhl network", "nhl net", "nhl network hd"), "https://a.espncdn.com/i/teamlogos/leagues/500/nhl.png"),
        SportsNetwork("golftv", "Golf Channel", listOf("golf channel", "golf tv", "golf channel hd"), "https://a.espncdn.com/i/teamlogos/leagues/500/golf.png"),
        SportsNetwork("tennis", "Tennis Channel", listOf("tennis channel", "tennis channel hd"), "https://a.espncdn.com/i/teamlogos/leagues/500/tennis.png"),
        SportsNetwork("ballysports", "Bally Sports", listOf("bally sports", "bally", "bally sports hd"), "https://upload.wikimedia.org/wikipedia/commons/4/4d/Bally_Sports_logo.svg"),
        SportsNetwork("yes", "YES Network", listOf("yes network", "yes network hd", "yes"), "https://upload.wikimedia.org/wikipedia/commons/0/0d/YES_Network_logo.svg"),
        SportsNetwork("msg", "MSG Network", listOf("msg network", "msg network hd", "msg"), "https://upload.wikimedia.org/wikipedia/commons/1/10/MSG_Network_logo.svg"),
        SportsNetwork("sny", "SNY", listOf("sny", "sny hd", "sportsnet new york"), "https://upload.wikimedia.org/wikipedia/commons/0/0f/SNY_logo.svg"),
        SportsNetwork("root", "ROOT Sports", listOf("root sports", "root sports hd", "root"), "https://upload.wikimedia.org/wikipedia/commons/1/13/Root_Sports_logo.svg"),
        SportsNetwork("fanduel", "FanDuel Sports Network", listOf("fanduel sports network", "fanduel sports", "fanduel sports hd", "diamond sports"), "https://upload.wikimedia.org/wikipedia/commons/6/6d/FanDuel_logo.svg"),
        SportsNetwork("thebigten", "Big Ten Network", listOf("btn", "big ten network"), "https://a.espncdn.com/i/teamlogos/leagues/500/btn.png")
    )

    fun find(channel: SportsChannel): SportsNetwork? {
        val haystack = normalize("${channel.name} ${channel.group}")
        return networks.firstOrNull { network -> network.aliases.any { alias -> matchesAlias(haystack, normalize(alias)) } }
    }

    fun sportsChannels(channels: List<SportsChannel>): List<Pair<SportsChannel, SportsNetwork>> = channels.asSequence()
        .mapNotNull { channel -> find(channel)?.let { channel to it } }
        .distinctBy { "${it.second.key}|${normalize(it.first.name)}|${it.first.url}" }
        .sortedWith(compareBy({ it.second.label }, { it.first.name.lowercase() }))
        .toList()

    private fun matchesAlias(haystack: String, alias: String): Boolean = haystack == alias || haystack.contains(" $alias ") || haystack.startsWith("$alias ") || haystack.endsWith(" $alias")
    private fun normalize(value: String): String = value.lowercase().replace("&", " and ").replace("+", " plus ").replace(Regex("[^a-z0-9]+"), " ").trim().replace(Regex("\\s+"), " ")
}
