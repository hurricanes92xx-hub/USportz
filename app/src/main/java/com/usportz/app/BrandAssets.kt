package com.usportz.app

/**
 * Real artwork resolver for league and event surfaces.
 *
 * First-party feed artwork remains authoritative. These curated fallbacks prevent blank cards
 * when a provider omits league/team artwork, especially for wrestling and motorsports.
 */
object BrandAssets {
    private const val COMMONS = "https://commons.wikimedia.org/wiki/Special:Redirect/file/"

    private val brandLogos = mapOf(
        "nfl" to "https://a.espncdn.com/i/teamlogos/leagues/500/nfl.png",
        "ncaa-football" to "https://a.espncdn.com/i/teamlogos/leagues/500/college-football.png",
        "nba" to "https://a.espncdn.com/i/teamlogos/leagues/500/nba.png",
        "wnba" to "https://a.espncdn.com/i/teamlogos/leagues/500/wnba.png",
        "ncaa-basketball" to "https://a.espncdn.com/i/teamlogos/leagues/500/mens-college-basketball.png",
        "mlb" to "https://a.espncdn.com/i/teamlogos/leagues/500/mlb.png",
        "nhl" to "https://a.espncdn.com/i/teamlogos/leagues/500/nhl.png",
        "mls" to "https://a.espncdn.com/i/teamlogos/leagues/500/mls.png",
        "epl" to "https://a.espncdn.com/i/teamlogos/leagues/500/eng.1.png",
        "ufc" to "https://a.espncdn.com/i/teamlogos/leagues/500/ufc.png",
        "wwe" to COMMONS + "WWElogo2014.png",
        "aew" to COMMONS + "All_Elite_Wrestling_logo_2023.png",
        "tna" to COMMONS + "TNA-logo-June-2024-v2.png",
        "roh" to COMMONS + "Ring_of_Honor_Logo_Final%281%29.png",
        "nascar" to COMMONS + "NASCAR_Logo.svg.png",
        "indycar" to COMMONS + "IndyCar_Series_logo.svg.png",
        "f1" to COMMONS + "Formula_1.svg.png",
        "motogp" to COMMONS + "Moto_Gp_logo.svg.png",
        "monster-jam" to COMMONS + "Monster_Jam_logo.svg.png",
        "boxing" to COMMONS + "Boxing_Pictogram.svg.png",
        "tennis" to COMMONS + "Tennis_pictogram.svg.png",
        "golf" to COMMONS + "Golf_pictogram.svg.png"
    )

    private val eventLogos = listOf(
        Triple("wwe", "raw", COMMONS + "RAW.png"),
        Triple("wwe", "smackdown", COMMONS + "SmackDown_2019.png"),
        Triple("wwe", "nxt", COMMONS + "NXT_LOGO.png"),
        Triple("aew", "dynamite", COMMONS + "AEW_Dynamite_logo_%28simplified%29.jpg"),
        Triple("aew", "double or nothing", COMMONS + "AEW_Double_or_Nothing_logo.png"),
        Triple("aew", "battle of the belts", COMMONS + "AEW_Battle_of_the_Belts_logo.png")
    )

    fun logoUrl(brand: SportsBrand?): String? = brand?.let { brandLogos[it.key] }

    fun eventLogoUrl(event: SportsEvent, brand: SportsBrand?): String? {
        val title = "${event.name} ${event.shortName}".lowercase()
        return eventLogos.firstOrNull { (key, token, _) -> key == brand?.key && title.contains(token) }?.third
    }
}
