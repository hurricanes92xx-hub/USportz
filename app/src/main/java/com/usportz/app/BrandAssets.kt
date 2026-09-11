package com.usportz.app

/** Real artwork resolver for league and event surfaces. */
object BrandAssets {
    private const val COMMONS = "https://commons.wikimedia.org/wiki/Special:Redirect/file/"

    private val brandLogos = mapOf(
        "nfl" to "https://a.espncdn.com/i/teamlogos/leagues/500/nfl.png",
        "ncaa-football" to "https://www.ncaa.com/modules/custom/casablanca_core/img/sportbanners/football.png",
        "nba" to "https://a.espncdn.com/i/teamlogos/leagues/500/nba.png",
        "wnba" to "https://a.espncdn.com/i/teamlogos/leagues/500/wnba.png",
        "ncaa-basketball" to "https://www.ncaa.com/modules/custom/casablanca_core/img/sportbanners/basketball-men.svg",
        "mlb" to "https://a.espncdn.com/i/teamlogos/leagues/500/mlb.png",
        "nhl" to "https://a.espncdn.com/i/teamlogos/leagues/500/nhl.png",
        "mls" to "https://a.espncdn.com/i/teamlogos/leagues/500/mls.png",
        "nwsl" to "https://a.espncdn.com/i/teamlogos/leagues/500/nwsl.png",
        "epl" to "https://a.espncdn.com/i/teamlogos/leagues/500/eng.1.png",
        "championship" to "https://a.espncdn.com/i/teamlogos/leagues/500/eng.2.png",
        "la-liga" to "https://a.espncdn.com/i/teamlogos/leagues/500/esp.1.png",
        "bundesliga" to "https://a.espncdn.com/i/teamlogos/leagues/500/ger.1.png",
        "serie-a" to "https://a.espncdn.com/i/teamlogos/leagues/500/ita.1.png",
        "ligue-1" to "https://a.espncdn.com/i/teamlogos/leagues/500/fra.1.png",
        "eredivisie" to "https://a.espncdn.com/i/teamlogos/leagues/500/ned.1.png",
        "primeira-liga" to "https://a.espncdn.com/i/teamlogos/leagues/500/por.1.png",
        "scottish-premiership" to "https://a.espncdn.com/i/teamlogos/leagues/500/sco.1.png",
        "champions-league" to "https://a.espncdn.com/i/teamlogos/leagues/500/uefa.champions.png",
        "europa-league" to "https://a.espncdn.com/i/teamlogos/leagues/500/uefa.europa.png",
        "libertadores" to "https://a.espncdn.com/i/teamlogos/leagues/500/conmebol.libertadores.png",
        "sudamericana" to "https://a.espncdn.com/i/teamlogos/leagues/500/conmebol.sudamericana.png",
        "fifa" to "https://a.espncdn.com/i/teamlogos/leagues/500/fifa.png",
        "ufc" to "https://a.espncdn.com/i/teamlogos/leagues/500/ufc.png",
        "dwcs" to COMMONS + "Dana_White%27s_Contender_Series-logo.jpg",
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
        // WWE weekly shows and specials
        Triple("wwe", "raw", COMMONS + "RAW.png"),
        Triple("wwe", "smackdown", COMMONS + "SmackDown_2019.png"),
        Triple("wwe", "nxt", COMMONS + "NXT_LOGO.png"),
        Triple("wwe", "evolve", COMMONS + "WWE_Evolve_logo.png"),
        Triple("wwe", "main event", COMMONS + "WWE_Main_Event_logo.png"),
        Triple("wwe", "worlds collide", COMMONS + "WWE_Worlds_Collide_logo.png"),
        Triple("wwe", "money in the bank", COMMONS + "Money_in_the_Bank_logo.png"),
        Triple("wwe", "survivor series", COMMONS + "Survivor_Series_logo.png"),
        Triple("wwe", "royal rumble", COMMONS + "Royal_Rumble_logo.png"),
        Triple("wwe", "wrestlemania", COMMONS + "WrestleMania_42_logo.png"),
        Triple("wwe", "elimination chamber", COMMONS + "Elimination_Chamber_logo.png"),
        // AEW weekly shows and major cards
        Triple("aew", "dynamite", COMMONS + "AEW_Dynamite_logo_%28simplified%29.jpg"),
        Triple("aew", "collision", COMMONS + "AEW_Collision_logo.png"),
        Triple("aew", "rebel heart", COMMONS + "AEW_Rebel_Heart_logo.png"),
        Triple("aew", "all out", COMMONS + "AEW_All_Out_logo.png"),
        Triple("aew", "wrestledream", COMMONS + "AEW_WrestleDream_logo.png"),
        Triple("aew", "full gear", COMMONS + "AEW_Full_Gear_logo.png"),
        Triple("aew", "grand slam", COMMONS + "AEW_Grand_Slam_logo.png"),
        Triple("aew", "double or nothing", COMMONS + "AEW_Double_or_Nothing_logo.png"),
        Triple("aew", "forbidden door", COMMONS + "AEW_x_NJPW_Forbidden_Door_logo.png"),
        Triple("aew", "revolution", COMMONS + "AEW_Revolution_logo.png"),
        // TNA weekly shows and PPV/specials
        Triple("tna", "impact", COMMONS + "TNA_iMPACT!_logo.png"),
        Triple("tna", "bound for glory", COMMONS + "Bound_for_Glory_logo.png"),
        Triple("tna", "slammiversary", COMMONS + "Slammiversary_logo.png"),
        Triple("tna", "destination x", COMMONS + "Destination_X_logo.png"),
        Triple("tna", "hard to kill", COMMONS + "Hard_to_Kill_logo.png"),
        Triple("tna", "sacrifice", COMMONS + "TNA_Sacrifice_logo.png"),
        Triple("tna", "rebellion", COMMONS + "TNA_Rebellion_logo.png"),
        // ROH
        Triple("roh", "ring of honor", COMMONS + "Ring_of_Honor_Logo_Final%281%29.png")
    )

    fun logoUrl(brand: SportsBrand?): String? = brand?.let { brandLogos[it.key] }

    fun eventLogoUrl(event: SportsEvent, brand: SportsBrand?): String? {
        val title = "${event.name} ${event.shortName}".lowercase()
        return eventLogos.firstOrNull { (key, token, _) -> key == brand?.key && title.contains(token) }?.third
            ?: when (brand?.key) {
                "wwe", "aew", "tna", "roh" -> logoUrl(brand)
                else -> null
            }
    }
}