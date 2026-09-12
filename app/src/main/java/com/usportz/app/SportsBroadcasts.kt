package com.usportz.app

/**
 * Broadcast intelligence used only to rank an already-available IPTV channel.
 * Includes common U.S. and Canadian network aliases plus conference/regional feeds.
 */
object SportsBroadcasts {
    private val common = listOf(
        "ABC", "CBS", "NBC", "FOX", "CW", "ESPN", "ESPN2", "ESPNU", "ESPNNews", "ESPN+",
        "FS1", "FS2", "CBS Sports Network", "CBSSN", "TNT", "TBS", "truTV", "USA Network", "USA",
        "Peacock", "Prime Video", "Prime", "Apple TV", "Apple TV+", "Paramount+", "Netflix",
        "Tennis Channel", "Golf Channel", "NBC Sports", "NFL Network", "NBA TV", "MLB Network", "NHL Network",
        "ACC Network", "ACC Network Extra", "SEC Network", "Big Ten Network", "BTN", "B1G+", "Longhorn Network",
        "YES Network", "MASN", "SNY", "ION", "Telemundo", "Univision", "Unimas",
        // Canada: TSN's five national feeds, Sportsnet's regional feeds, and French networks.
        "TSN", "TSN1", "TSN2", "TSN3", "TSN4", "TSN5", "TSN+", "TSN Plus",
        "Sportsnet", "Sportsnet One", "Sportsnet 360", "Sportsnet East", "Sportsnet Ontario",
        "Sportsnet West", "Sportsnet Pacific", "Sportsnet World", "Sportsnet PPV",
        "RDS", "RDS2", "RDS Info", "TVA Sports", "TVA Sports 2", "CBC", "CBC Sports",
        "CTV", "CTV2", "DAZN Canada", "DAZN", "Fight Network"
    )

    private val bySport = linkedMapOf(
        "Football" to listOf(
            "NFL Network", "CBS", "FOX", "NBC", "ESPN", "ESPN2", "ABC", "Prime Video", "Peacock",
            "NFL+", "USA Network", "CW", "SEC Network", "ACC Network", "Big Ten Network", "BTN",
            "FS1", "FS2", "CBS Sports Network", "CBSSN", "ESPN+", "TNT",
            "TSN", "TSN1", "TSN2", "TSN3", "TSN4", "TSN5", "RDS", "DAZN"
        ),
        "Basketball" to listOf(
            "ABC", "ESPN", "ESPN2", "ESPNU", "ESPN+", "CBS", "CBS Sports Network", "CBSSN", "FOX",
            "FS1", "FS2", "NBC", "Peacock", "Prime Video", "TNT", "TBS", "truTV", "USA Network", "CW",
            "NBA TV", "ACC Network", "SEC Network", "Big Ten Network", "BTN", "B1G+", "Longhorn Network",
            "ION", "Paramount+", "Max", "TSN", "TSN1", "TSN2", "TSN3", "TSN4", "TSN5", "Sportsnet", "RDS"
        ),
        "NCAA Basketball" to listOf(
            "ESPN", "ESPN2", "ESPNU", "ESPN+", "ABC", "CBS", "CBS Sports Network", "CBSSN", "FOX", "FS1", "FS2",
            "NBC", "Peacock", "Prime Video", "TNT", "TBS", "truTV", "CW", "ACC Network", "SEC Network",
            "Big Ten Network", "BTN", "B1G+", "Longhorn Network", "Paramount+", "Max",
            "TSN", "TSN1", "TSN2", "TSN3", "TSN4", "TSN5", "RDS", "DAZN"
        ),
        "Baseball" to listOf(
            "FOX", "FS1", "ESPN", "ESPN2", "ABC", "TBS", "MLB Network", "MLB.TV", "Apple TV+", "Apple TV",
            "Peacock", "NBC", "CBS", "FOX Deportes", "ESPN+", "Roku", "Sportsnet", "Sportsnet One",
            "Sportsnet Ontario", "TSN", "TSN2", "TSN3", "TSN4", "TSN5"
        ),
        "Hockey" to listOf(
            "ESPN", "ESPN2", "ABC", "ESPN+", "TNT", "TBS", "truTV", "NHL Network", "NBC", "Peacock",
            "CBS Sports Network", "CBSSN", "Big Ten Network", "BTN", "Sportsnet", "Sportsnet East", "Sportsnet Ontario",
            "Sportsnet West", "Sportsnet Pacific", "Sportsnet 360", "TSN", "TSN1", "TSN2", "TSN3", "TSN4", "TSN5",
            "RDS", "RDS2", "TVA Sports", "CBC"
        ),
        "Soccer" to listOf(
            "Apple TV", "Apple TV+", "MLS Season Pass", "FOX", "FS1", "FS2", "ESPN", "ESPN2", "ESPN+", "ABC",
            "CBS", "CBS Sports Network", "Paramount+", "NBC", "USA Network", "Peacock", "Telemundo", "Universo",
            "Univision", "TUDN", "TNT", "TBS", "truTV", "TSN", "TSN2", "TSN3", "TSN4", "TSN5",
            "Sportsnet", "RDS", "DAZN"
        ),
        "MMA" to listOf("Paramount+", "CBS", "ESPN", "ESPN+", "UFC Fight Pass", "USA Network", "ABC", "TNT", "TSN", "Sportsnet", "DAZN", "Fight Network"),
        "Boxing" to listOf("ESPN", "ESPN+", "ABC", "FOX", "FS1", "FS2", "CBS", "Paramount+", "DAZN", "TNT", "TBS", "truTV", "Prime Video", "TSN", "Sportsnet", "RDS", "Fight Network"),
        "Wrestling" to listOf("USA Network", "USA", "CW", "Peacock", "ESPN", "Netflix", "FOX", "FS1", "ABC", "CBS", "TSN", "Sportsnet"),
        "Motorsports" to listOf("FOX", "FS1", "FS2", "NBC", "USA Network", "Peacock", "TNT", "Prime Video", "ABC", "ESPN", "ESPN2", "ESPN+", "CW", "Apple TV+", "Apple TV", "TSN", "TSN2", "RDS", "Sportsnet"),
        "Tennis" to listOf("ESPN", "ESPN2", "ABC", "ESPN+", "Tennis Channel", "NBC", "Peacock", "CBS", "TNT", "TBS", "TSN", "TSN2", "TSN3", "TSN4", "TSN5", "RDS", "Sportsnet"),
        "Golf" to listOf("CBS", "NBC", "Golf Channel", "USA Network", "USA", "Peacock", "ESPN", "ESPN+", "FOX", "FS1", "CW", "CBS Sports Network", "CBSSN", "TSN", "TSN2", "TSN4", "RDS")
    )

    private val conferenceNetworks = linkedMapOf(
        "acc" to listOf("ACC Network", "ACCN", "ACC Network Extra", "ESPN", "ESPN2", "ESPNU", "ESPN+", "ABC", "CW"),
        "big ten" to listOf("Big Ten Network", "BTN", "B1G+", "CBS", "FOX", "FS1", "NBC", "Peacock", "FOX Sports", "TSN", "DAZN"),
        "big 12" to listOf("ESPN", "ESPN2", "ESPNU", "ESPN+", "ABC", "CBS", "FOX", "FS1", "TNT", "TBS", "truTV", "Peacock", "TSN", "DAZN"),
        "sec" to listOf("SEC Network", "SECN", "ESPN", "ESPN2", "ESPNU", "ESPN+", "ABC", "CBS", "TSN", "RDS"),
        "big east" to listOf("FOX", "FS1", "FS2", "CBS", "CBS Sports Network", "NBC", "Peacock", "TNT", "TBS", "truTV", "Max", "TSN"),
        "aac" to listOf("ESPN", "ESPN2", "ESPNU", "ESPN+", "CBS", "CBS Sports Network", "TSN", "RDS"),
        "a10" to listOf("ESPN", "ESPN2", "ESPNU", "ESPN+", "CBS Sports Network", "CBS", "TSN"),
        "mountain west" to listOf("CBS", "CBS Sports Network", "FOX", "FS1", "FS2", "ESPN", "ESPN2", "truTV", "TSN", "RDS"),
        "pac 12" to listOf("CW", "USA Network", "CBS", "FOX", "FS1", "ESPN", "ESPN2", "ESPN+", "TSN"),
        "missouri valley" to listOf("CBS", "CBS Sports Network", "ESPN", "ESPN2", "ESPNU", "ESPN+", "TSN"),
        "ivy" to listOf("ESPN+", "ESPNU", "CBS Sports Network", "TSN"),
        "cusa" to listOf("CBS Sports Network", "ESPN+", "CBSSN", "TSN"),
        "conference usa" to listOf("CBS Sports Network", "ESPN+", "CBSSN", "TSN")
    )

    fun preferredNetworks(event: SportsEvent): List<String> {
        val sport = SportsCatalog.classify(event.name, event.league)
        val result = LinkedHashSet<String>()
        event.broadcast.split(Regex("[,/|•]+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .forEach(result::add)
        val league = normalize(event.league)
        conferenceNetworks.entries.firstOrNull { league.contains(it.key) }
            ?.value?.forEach(result::add)
        bySport[sport].orEmpty().forEach(result::add)
        common.forEach { if (result.none { existing -> normalize(existing) == normalize(it) }) result.add(it) }
        return result.toList()
    }

    fun priority(event: SportsEvent, channelName: String, group: String): Int {
        val haystack = normalize("$channelName $group")
        val exactBroadcasts = event.broadcast.split(Regex("[,/|•]+"))
            .map(::normalize)
            .filter { it.isNotBlank() }
        if (exactBroadcasts.any { haystack.contains(it) }) return 100
        val preferred = preferredNetworks(event)
        val matched = preferred.indexOfFirst { alias ->
            val n = normalize(alias)
            n.isNotBlank() && (haystack == n || haystack.contains(" $n ") || haystack.startsWith("$n ") || haystack.endsWith(" $n"))
        }
        return when {
            matched == 0 && event.broadcast.isNotBlank() -> 95
            matched in 0..5 -> 82 - matched
            matched >= 0 -> 68 - matched.coerceAtMost(15)
            else -> 0
        }
    }

    fun normalize(value: String): String = value.lowercase()
        .replace("&", " and ")
        .replace("espn plus", "espn+")
        .replace("fox sports 1", "fs1")
        .replace("fox sports 2", "fs2")
        .replace("big ten network", "btn")
        .replace("sec network", "secn")
        .replace("acc network", "accn")
        .replace("usa network", "usa")
        .replace("paramount plus", "paramount+")
        .replace("peacock premium", "peacock")
        .replace("prime video", "prime")
        .replace("apple tv plus", "apple tv+")
        .replace(Regex("\\b(canada|ca|us|usa)\\b"), " ")
        .replace(Regex("[^a-z0-9+]+"), " ")
        .trim()
}
