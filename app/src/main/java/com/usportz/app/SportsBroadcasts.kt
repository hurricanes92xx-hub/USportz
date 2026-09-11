package com.usportz.app

/**
 * Known U.S. broadcast/network aliases used to improve event -> IPTV channel ranking.
 * These are matching hints only; they do not provide or proxy streams.
 */
object SportsBroadcasts {
    private val common = listOf(
        "ABC", "CBS", "NBC", "FOX", "CW", "ESPN", "ESPN2", "ESPNU", "ESPNEWS",
        "ESPN+", "FS1", "FS2", "CBS Sports Network", "CBSSN", "TNT", "TBS", "truTV",
        "USA Network", "USA", "Peacock", "Prime Video", "Prime", "Apple TV", "Apple TV+",
        "Paramount+", "Netflix", "YouTube TV", "Max", "Tennis Channel", "Golf Channel",
        "NBC Sports", "NFL Network", "NBA TV", "MLB Network", "NHL Network", "ACC Network",
        "SEC Network", "Big Ten Network", "BTN", "B1G+", "Longhorn Network", "YES Network",
        "MASN", "SNY", "ION", "Telemundo", "Univision", "Unimas"
    )

    private val bySport = linkedMapOf(
        "Football" to listOf("NFL Network", "CBS", "FOX", "NBC", "ESPN", "ESPN2", "ABC", "Prime Video", "Peacock", "NFL+", "USA Network", "CW", "SEC Network", "ACC Network", "Big Ten Network", "BTN", "FS1", "FS2", "CBS Sports Network", "CBSSN", "ESPN+", "TNT"),
        "Basketball" to listOf("ABC", "ESPN", "ESPN2", "ESPNU", "ESPN+", "CBS", "CBS Sports Network", "CBSSN", "FOX", "FS1", "FS2", "NBC", "Peacock", "Prime Video", "TNT", "TBS", "truTV", "USA Network", "CW", "NBA TV", "ACC Network", "SEC Network", "Big Ten Network", "BTN", "B1G+", "Longhorn Network", "ION", "Paramount+", "Max"),
        "NCAA Basketball" to listOf("ESPN", "ESPN2", "ESPNU", "ESPN+", "ABC", "CBS", "CBS Sports Network", "CBSSN", "FOX", "FS1", "FS2", "NBC", "Peacock", "Prime Video", "TNT", "TBS", "truTV", "CW", "ACC Network", "SEC Network", "Big Ten Network", "BTN", "B1G+", "Longhorn Network", "Paramount+", "Max"),
        "Baseball" to listOf("FOX", "FS1", "ESPN", "ESPN2", "ABC", "TBS", "MLB Network", "MLB.TV", "Apple TV+", "Apple TV", "Peacock", "NBC", "CBS", "FOX Deportes", "ESPN+", "Roku"),
        "Hockey" to listOf("ESPN", "ESPN2", "ABC", "ESPN+", "TNT", "TBS", "truTV", "NHL Network", "NBC", "Peacock", "CBS Sports Network", "CBSSN", "Big Ten Network", "BTN"),
        "Soccer" to listOf("Apple TV", "Apple TV+", "MLS Season Pass", "FOX", "FS1", "FS2", "ESPN", "ESPN2", "ESPN+", "ABC", "CBS", "CBS Sports Network", "Paramount+", "NBC", "USA Network", "Peacock", "Telemundo", "Universo", "Univision", "TUDN", "TNT", "TBS", "truTV"),
        "MMA" to listOf("Paramount+", "CBS", "ESPN", "ESPN+", "UFC Fight Pass", "USA Network", "ABC", "TNT"),
        "Boxing" to listOf("ESPN", "ESPN+", "ABC", "FOX", "FS1", "FS2", "CBS", "Paramount+", "DAZN", "TNT", "TBS", "truTV", "Prime Video"),
        "Wrestling" to listOf("USA Network", "USA", "CW", "Peacock", "ESPN", "Netflix", "FOX", "FS1", "ABC", "CBS"),
        "Motorsports" to listOf("FOX", "FS1", "FS2", "NBC", "USA Network", "Peacock", "TNT", "Prime Video", "ABC", "ESPN", "ESPN2", "ESPN+", "CW", "Apple TV+", "Apple TV"),
        "Tennis" to listOf("ESPN", "ESPN2", "ABC", "ESPN+", "Tennis Channel", "NBC", "Peacock", "CBS", "TNT", "TBS"),
        "Golf" to listOf("CBS", "NBC", "Golf Channel", "USA Network", "USA", "Peacock", "ESPN", "ESPN+", "FOX", "FS1", "CW", "CBS Sports Network", "CBSSN")
    )

    private val conferenceNetworks = linkedMapOf(
        "acc" to listOf("ACC Network", "ACCN", "ACC Network Extra", "ESPN", "ESPN2", "ESPNU", "ESPN+", "ABC", "CW"),
        "big ten" to listOf("Big Ten Network", "BTN", "B1G+", "CBS", "FOX", "FS1", "NBC", "Peacock", "FOX Sports"),
        "big 12" to listOf("ESPN", "ESPN2", "ESPNU", "ESPN+", "ABC", "CBS", "FOX", "FS1", "TNT", "TBS", "truTV", "Peacock"),
        "sec" to listOf("SEC Network", "SECN", "ESPN", "ESPN2", "ESPNU", "ESPN+", "ABC", "CBS"),
        "big east" to listOf("FOX", "FS1", "FS2", "CBS", "CBS Sports Network", "NBC", "Peacock", "TNT", "TBS", "truTV", "Max"),
        "aac" to listOf("ESPN", "ESPN2", "ESPNU", "ESPN+", "CBS", "CBS Sports Network"),
        "a10" to listOf("ESPN", "ESPN2", "ESPNU", "ESPN+", "CBS Sports Network", "CBS"),
        "mountain west" to listOf("CBS", "CBS Sports Network", "FOX", "FS1", "FS2", "ESPN", "ESPN2", "truTV"),
        "pac 12" to listOf("CW", "USA Network", "CBS", "FOX", "FS1", "ESPN", "ESPN2", "ESPN+"),
        "missouri valley" to listOf("CBS", "CBS Sports Network", "ESPN", "ESPN2", "ESPNU", "ESPN+"),
        "ivy" to listOf("ESPN+", "ESPNU", "CBS Sports Network"),
        "cusa" to listOf("CBS Sports Network", "ESPN+", "CBSSN"),
        "conference usa" to listOf("CBS Sports Network", "ESPN+", "CBSSN")
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
        .replace(Regex("[^a-z0-9+]+"), " ")
        .trim()
}
