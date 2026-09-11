package com.usportz.app

/** Local sports classification layer, including a dedicated NCAA basketball rail. */
object SportsCatalog {
    val categories = listOf("All", "Football", "Basketball", "NCAA Basketball", "Baseball", "Hockey", "Soccer", "MMA", "Boxing", "Wrestling", "Motorsports", "Tennis", "Golf", "Racing")

    private val rules = linkedMapOf(
        "NCAA Basketball" to listOf("mens-college-basketball", "womens-college-basketball", "ncaa basketball", "college basketball", "ncaamb", "ncaaw"),
        "Football" to listOf("nfl", "ncaa football", "college football", "football"),
        "Basketball" to listOf("nba", "wnba", "basketball"),
        "Baseball" to listOf("mlb", "ncaa baseball", "college baseball", "baseball"),
        "Hockey" to listOf("nhl", "ncaa hockey", "hockey"),
        "Soccer" to listOf("mls", "epl", "premier league", "champions league", "la liga", "soccer"),
        "MMA" to listOf("ufc", "bellator", "pfl", "mma"),
        "Boxing" to listOf("boxing", "wbc", "wba", "wbo", "ibf"),
        "Wrestling" to listOf("wwe", "aew", "tna", "roh", "nxt", "raw", "smackdown", "wrestling"),
        "Motorsports" to listOf("nascar", "indycar", "formula 1", "formula one", "motogp", "motorsport"),
        "Tennis" to listOf("atp", "wta", "tennis", "us open", "wimbledon"),
        "Golf" to listOf("pga", "lpga", "golf", "masters", "ryder cup"),
        "Racing" to listOf("monster jam", "drag racing", "nhra", "racing")
    )

    fun classify(name: String, group: String = ""): String {
        val haystack = "$name $group".lowercase()
        return rules.entries.firstOrNull { (_, keywords) -> keywords.any(haystack::contains) }?.key ?: "Other"
    }
}
