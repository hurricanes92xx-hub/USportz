package com.usportz.app

/** Canonical sports/league branding metadata used by mobile and TV event surfaces. */
data class SportsBrand(
    val key: String,
    val label: String,
    val aliases: List<String>,
    val icon: String
)

object SportsBranding {
    val brands = listOf(
        SportsBrand("nfl", "NFL", listOf("nfl", "national football league"), "NFL"),
        SportsBrand("ncaa-football", "NCAA Football", listOf("ncaa football", "college football"), "NCAA"),
        SportsBrand("nba", "NBA", listOf("nba", "national basketball association"), "NBA"),
        SportsBrand("wnba", "WNBA", listOf("wnba", "women's national basketball association"), "WNBA"),
        SportsBrand("ncaa-basketball", "NCAA Basketball", listOf("ncaa basketball", "college basketball"), "NCAA"),
        SportsBrand("mlb", "MLB", listOf("mlb", "major league baseball"), "MLB"),
        SportsBrand("nhl", "NHL", listOf("nhl", "national hockey league"), "NHL"),
        SportsBrand("mls", "MLS", listOf("mls", "major league soccer"), "MLS"),
        SportsBrand("epl", "Premier League", listOf("epl", "premier league", "england premier league"), "EPL"),
        SportsBrand("ufc", "UFC", listOf("ufc", "ultimate fighting championship"), "UFC"),
        SportsBrand("boxing", "Boxing", listOf("boxing", "wbc", "wba", "wbo", "ibf"), "BOX"),
        SportsBrand("wwe", "WWE", listOf("wwe", "raw", "smackdown", "nxt"), "WWE"),
        SportsBrand("aew", "AEW", listOf("aew", "all elite wrestling", "dynamite", "collision"), "AEW"),
        SportsBrand("tna", "TNA", listOf("tna", "impact wrestling", "impact"), "TNA"),
        SportsBrand("roh", "ROH", listOf("roh", "ring of honor"), "ROH"),
        SportsBrand("nascar", "NASCAR", listOf("nascar"), "NASCAR"),
        SportsBrand("indycar", "INDYCAR", listOf("indycar"), "INDY"),
        SportsBrand("f1", "Formula 1", listOf("formula 1", "formula one", "f1"), "F1"),
        SportsBrand("motogp", "MotoGP", listOf("motogp"), "MGP"),
        SportsBrand("monster-jam", "Monster Jam", listOf("monster jam"), "MJ"),
        SportsBrand("tennis", "Tennis", listOf("atp", "wta", "tennis", "us open", "wimbledon"), "TENNIS"),
        SportsBrand("golf", "Golf", listOf("pga", "lpga", "golf", "masters", "ryder cup"), "GOLF")
    )

    fun find(name: String, league: String = ""): SportsBrand? {
        val haystack = "$name $league".lowercase()
        return brands.firstOrNull { brand -> brand.aliases.any(haystack::contains) }
    }

    fun label(name: String, league: String = ""): String = find(name, league)?.label ?: league.ifBlank { "Sports" }

    fun wrestlingBrands(): List<SportsBrand> = brands.filter { it.key in setOf("wwe", "aew", "tna", "roh") }
}
