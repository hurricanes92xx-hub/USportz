package com.usportz.app

/** Local sports classification layer, covering major U.S. sports plus international/event sports. */
object SportsCatalog {
    val categories = listOf(
        "All", "Football", "Basketball", "NCAA Basketball", "Baseball", "Hockey", "Soccer",
        "MMA", "Boxing", "Wrestling", "Motorsports", "Tennis", "Golf", "Racing", "Volleyball",
        "Rugby", "Cricket", "Lacrosse", "Track & Field", "Swimming", "Gymnastics", "Cycling",
        "Horse Racing", "Rodeo", "Olympics", "Paralympics", "Esports"
    )

    private val rules = linkedMapOf(
        "NCAA Basketball" to listOf("mens-college-basketball", "womens-college-basketball", "ncaa basketball", "college basketball", "ncaamb", "ncaaw"),
        "Football" to listOf("nfl", "cfl", "ncaa football", "college football", "football"),
        "Basketball" to listOf("nba", "wnba", "fiba", "basketball"),
        "Baseball" to listOf("mlb", "ncaa baseball", "college baseball", "baseball"),
        "Hockey" to listOf("nhl", "pwhl", "ncaa hockey", "college hockey", "hockey"),
        "Soccer" to listOf("mls", "nwsl", "epl", "premier league", "champions league", "europa league", "la liga", "bundesliga", "serie a", "ligue 1", "fifa", "world cup", "soccer"),
        "MMA" to listOf("ufc", "pfl", "bellator", "one championship", "mma"),
        "Boxing" to listOf("boxing", "wbc", "wba", "wbo", "ibf"),
        "Wrestling" to listOf("wwe", "aew", "tna", "roh", "nxt", "raw", "smackdown", "wrestling"),
        "Motorsports" to listOf("nascar", "indycar", "irl", "formula 1", "formula one", "f1", "motogp", "motocross", "motorsport", "grand prix"),
        "Tennis" to listOf("atp", "wta", "tennis", "us open", "wimbledon", "roland garros", "french open", "australian open"),
        "Golf" to listOf("pga", "lpga", "liv golf", "golf", "masters", "ryder cup", "solheim cup"),
        "Horse Racing" to listOf("horse racing", "kentucky derby", "belmont stakes", "preakness", "breeders cup", "triple crown"),
        "Racing" to listOf("monster jam", "drag racing", "nhra", "racing"),
        "Volleyball" to listOf("volleyball", "ncaa volleyball", "ncaaw volleyball", "beach volleyball"),
        "Rugby" to listOf("rugby", "nrl", "super rugby", "premiership rugby", "six nations", "rugby world cup"),
        "Cricket" to listOf("cricket", "ipl", "t20", "test cricket", "big bash", "world cup cricket"),
        "Lacrosse" to listOf("lacrosse", "pll", "premier lacrosse league", "ncaa lacrosse"),
        "Track & Field" to listOf("track and field", "track & field", "athletics", "world athletics", "diamond league"),
        "Swimming" to listOf("swimming", "world aquatics", "usa swimming"),
        "Gymnastics" to listOf("gymnastics", "usa gymnastics", "world gymnastics"),
        "Cycling" to listOf("cycling", "tour de france", "vuelta", "giro d'italia", "giro ditalia"),
        "Rodeo" to listOf("rodeo", "pbr", "professional bull riders", "prorodeo"),
        "Olympics" to listOf("olympics", "olympic", "summer games", "winter games"),
        "Paralympics" to listOf("paralympics", "paralympic"),
        "Esports" to listOf("esports", "league of legends", "valorant", "counter-strike", "cs2", "rocket league")
    )

    fun classify(name: String, group: String = ""): String {
        val haystack = "$name $group".lowercase()
        return rules.entries.firstOrNull { (_, keywords) -> keywords.any(haystack::contains) }?.key ?: "Other"
    }
}
