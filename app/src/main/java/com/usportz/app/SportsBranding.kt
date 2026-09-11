package com.usportz.app

/** Canonical sports/league branding metadata used by mobile and TV event surfaces. */
data class SportsBrand(
    val key: String,
    val label: String,
    val aliases: List<String>,
    val icon: String,
    val accent: String = ""
)

object SportsBranding {
    val brands = listOf(
        SportsBrand("nfl", "NFL", listOf("nfl", "national football league"), "NFL", "PRO"),
        SportsBrand("ncaa-football", "NCAA Football", listOf("ncaa football", "college football", "college-football"), "NCAA", "COLLEGE"),
        SportsBrand("nba", "NBA", listOf("nba", "national basketball association"), "NBA", "PRO"),
        SportsBrand("wnba", "WNBA", listOf("wnba", "women's national basketball association"), "WNBA", "PRO"),
        SportsBrand("ncaa-basketball", "NCAA Basketball", listOf("ncaa basketball", "college basketball", "mens-college-basketball", "womens-college-basketball"), "NCAA", "COLLEGE"),
        SportsBrand("mlb", "MLB", listOf("mlb", "major league baseball"), "MLB", "PRO"),
        SportsBrand("nhl", "NHL", listOf("nhl", "national hockey league"), "NHL", "PRO"),
        SportsBrand("mls", "MLS", listOf("mls", "major league soccer", "usa.1"), "MLS", "PRO"),
        SportsBrand("nwsl", "NWSL", listOf("nwsl", "usa.nwsl"), "NWSL", "PRO"),
        SportsBrand("epl", "Premier League", listOf("epl", "premier league", "england premier league", "eng.1"), "EPL", "SOCCER"),
        SportsBrand("championship", "EFL Championship", listOf("championship", "efl championship", "eng.2"), "EFL", "SOCCER"),
        SportsBrand("la-liga", "La Liga", listOf("la liga", "laliga", "esp.1"), "LALIGA", "SOCCER"),
        SportsBrand("bundesliga", "Bundesliga", listOf("bundesliga", "ger.1"), "BUND", "SOCCER"),
        SportsBrand("serie-a", "Serie A", listOf("serie a", "ita.1"), "SERIE A", "SOCCER"),
        SportsBrand("ligue-1", "Ligue 1", listOf("ligue 1", "fra.1"), "L1", "SOCCER"),
        SportsBrand("eredivisie", "Eredivisie", listOf("eredivisie", "ned.1"), "ERE", "SOCCER"),
        SportsBrand("primeira-liga", "Primeira Liga", listOf("primeira liga", "liga portugal", "por.1"), "PT", "SOCCER"),
        SportsBrand("scottish-premiership", "Scottish Premiership", listOf("scottish premiership", "sco.1"), "SCO", "SOCCER"),
        SportsBrand("champions-league", "UEFA Champions League", listOf("uefa champions league", "champions league", "uefa.champions"), "UCL", "SOCCER"),
        SportsBrand("europa-league", "UEFA Europa League", listOf("uefa europa league", "europa league", "uefa.europa"), "UEL", "SOCCER"),
        SportsBrand("libertadores", "Copa Libertadores", listOf("copa libertadores", "libertadores", "conmebol.libertadores"), "LIB", "SOCCER"),
        SportsBrand("sudamericana", "Copa Sudamericana", listOf("copa sudamericana", "sudamericana", "conmebol.sudamericana"), "SUD", "SOCCER"),
        SportsBrand("fifa", "FIFA", listOf("fifa", "fifa.world"), "FIFA", "SOCCER"),
        SportsBrand("dwcs", "Dana White's Contender Series", listOf("dana white's contender series", "dana whites contender series", "contender series", "dwtcs"), "DWCS", "FIGHT"),
        SportsBrand("ufc", "UFC", listOf("ufc", "ultimate fighting championship"), "UFC", "FIGHT"),
        SportsBrand("boxing", "Boxing", listOf("boxing", "wbc", "wba", "wbo", "ibf"), "BOX", "FIGHT"),
        SportsBrand("wwe", "WWE", listOf("wwe", "raw", "smackdown", "nxt", "evolve"), "WWE", "WRESTLING"),
        SportsBrand("aew", "AEW", listOf("aew", "all elite wrestling", "dynamite", "collision"), "AEW", "WRESTLING"),
        SportsBrand("tna", "TNA", listOf("tna", "impact wrestling", "impact"), "TNA", "WRESTLING"),
        SportsBrand("roh", "ROH", listOf("roh", "ring of honor"), "ROH", "WRESTLING"),
        SportsBrand("nascar", "NASCAR", listOf("nascar", "nascar-premier", "nascar-secondary", "nascar-truck"), "NASCAR", "RACING"),
        SportsBrand("indycar", "INDYCAR", listOf("indycar", "irl"), "INDY", "RACING"),
        SportsBrand("f1", "Formula 1", listOf("formula 1", "formula one", "f1"), "F1", "RACING"),
        SportsBrand("motogp", "MotoGP", listOf("motogp"), "MGP", "RACING"),
        SportsBrand("monster-jam", "Monster Jam", listOf("monster jam"), "MJ", "RACING"),
        SportsBrand("tennis", "Tennis", listOf("atp", "wta", "tennis", "us open", "wimbledon"), "TENNIS", "COURT"),
        SportsBrand("golf", "Golf", listOf("pga", "lpga", "liv", "golf", "masters", "ryder cup"), "GOLF", "COURSE")
    )

    fun find(name: String, league: String = ""): SportsBrand? {
        val haystack = "$name $league".lowercase().replace('-', ' ')
        return brands.firstOrNull { brand -> brand.aliases.any { alias -> haystack.contains(alias.lowercase().replace('-', ' ')) } }
    }

    fun label(name: String, league: String = ""): String = find(name, league)?.label ?: league.ifBlank { "Sports" }
    fun wrestlingBrands(): List<SportsBrand> = brands.filter { it.key in setOf("wwe", "aew", "tna", "roh") }
}