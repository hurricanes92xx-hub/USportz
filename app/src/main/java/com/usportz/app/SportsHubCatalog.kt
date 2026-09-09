package com.usportz.app

data class SportsHub(
    val key: String,
    val title: String,
    val subtitle: String,
    val brandKeys: Set<String>,
    val sportKeys: Set<String> = emptySet()
)

object SportsHubCatalog {
    val hubs = listOf(
        SportsHub("wwe", "WWE", "RAW • SmackDown • NXT • Evolve • PLEs", setOf("wwe")),
        SportsHub("aew", "AEW", "Dynamite • Collision • PPVs", setOf("aew")),
        SportsHub("tna", "TNA", "Impact • Specials • Live Events", setOf("tna")),
        SportsHub("roh", "ROH", "Ring of Honor • Specials", setOf("roh")),
        SportsHub("motorsports", "Motorsports", "F1 • NASCAR • INDYCAR • MotoGP • Monster Jam", setOf("f1", "nascar", "indycar", "motogp", "monster-jam"), setOf("racing")),
        SportsHub("tennis", "Tennis", "ATP • WTA • Grand Slams", setOf("tennis"), setOf("tennis")),
        SportsHub("golf", "Golf", "PGA TOUR • LPGA • Majors", setOf("golf"), setOf("golf"))
    )

    fun find(key: String): SportsHub = hubs.firstOrNull { it.key == key } ?: hubs.first()

    fun matches(hub: SportsHub, event: SportsEvent): Boolean {
        val brand = SportsBranding.find(event.name, event.league)
        if (brand != null && brand.key in hub.brandKeys) return true
        if (event.sport.lowercase() in hub.sportKeys) return true
        val hay = "${event.name} ${event.league} ${event.shortName}".lowercase()
        return when (hub.key) {
            "wwe" -> listOf("wwe", "raw", "smackdown", "nxt", "evolve", "main event").any(hay::contains)
            "aew" -> listOf("aew", "dynamite", "collision", "all out", "full gear").any(hay::contains)
            "tna" -> listOf("tna", "impact", "slammiversary", "bound for glory").any(hay::contains)
            "roh" -> listOf("roh", "ring of honor", "supercard", "death before dishonor").any(hay::contains)
            "motorsports" -> listOf("formula 1", "formula one", "f1", "nascar", "indycar", "motogp", "monster jam").any(hay::contains)
            "tennis" -> listOf("atp", "wta", "tennis", "wimbledon", "us open", "roland garros", "australian open").any(hay::contains)
            "golf" -> listOf("pga", "lpga", "golf", "masters", "ryder cup").any(hay::contains)
            else -> false
        }
    }
}
