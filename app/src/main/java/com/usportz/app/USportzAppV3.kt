package com.usportz.app

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class AppNavV3 { HOME, SPORTS, LIVE, FAV, SEARCH, SOURCES }

private data class ChannelCategory(
    val name: String,
    val channels: List<SportsChannel>,
    val logo: String?
)

@Composable
internal fun USportzAppV3(store: SourceStore) {
    val context = LocalContext.current
    var nav by remember { mutableStateOf(AppNavV3.HOME) }
    var sport by remember { mutableStateOf("All") }
    var query by remember { mutableStateOf("") }
    var events by remember { mutableStateOf(emptyList<SportsEvent>()) }
    var channels by remember { mutableStateOf(emptyList<SportsChannel>()) }
    var loading by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    var categorySearch by remember { mutableStateOf("") }
    var categories by remember { mutableStateOf(emptyList<ChannelCategory>()) }
    val favs = remember { Favs(context) }

    LaunchedEffect(Unit) {
        SportsChannelBridge.restoreCached(context).takeIf { it.isNotEmpty() }?.let { channels = it }
    }

    LaunchedEffect(channels) {
        categories = withContext(Dispatchers.Default) { buildChannelCategories(channels) }
        if (selectedCategory != null && categories.none { it.name == selectedCategory }) selectedCategory = null
    }

    LaunchedEffect(refresh) {
        loading = true
        SportsChannelBridge.restoreCached(context).takeIf { it.isNotEmpty() }?.let { channels = it }
        channels = SportsChannelBridge.load(context, refresh > 0)
        runCatching { SportsSchedule.load(context, refresh > 0, channels) }.onSuccess { events = it }
        loading = false
    }

    fun play(url: String) {
        context.startActivity(Intent(context, RichPlayerActivity::class.java).putExtra(RichPlayerActivity.EXTRA_URL, url))
    }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = Color(0xFFBFA6FF), secondary = Color(0xFF63D7FF), tertiary = Color(0xFFFF5FAF),
        background = Color(0xFF080A10), surface = Color(0xFF10131D)
    )) {
        Scaffold(containerColor = Color(0xFF080A10), bottomBar = {
            NavigationBar(containerColor = Color(0xFF0D1018)) {
                AppNavV3.values().forEach { n ->
                    NavigationBarItem(
                        selected = nav == n,
                        onClick = { nav = n; if (n != AppNavV3.LIVE) selectedCategory = null },
                        icon = { Icon(navIconV3(n), null) },
                        label = { Text(n.name.lowercase().replaceFirstChar { it.uppercase() }) }
                    )
                }
            }
        }) { pad ->
            LazyColumn(
                Modifier.fillMaxSize().padding(pad),
                contentPadding = PaddingValues(bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                item { HeaderV3(nav.name, loading, { refresh++ }, { nav = AppNavV3.SOURCES }) }
                when (nav) {
                    AppNavV3.HOME -> homeV3(events, channels, favs, ::play)
                    AppNavV3.SPORTS -> sportsV3(events, channels, sport, { sport = it }, favs, ::play)
                    AppNavV3.LIVE -> liveV3(categories, selectedCategory, { selectedCategory = it; categorySearch = "" }, { selectedCategory = null; categorySearch = "" }, categorySearch, { categorySearch = it }, favs, ::play)
                    AppNavV3.FAV -> favoritesV3(events, channels, favs, ::play)
                    AppNavV3.SEARCH -> searchV3(query, { query = it }, events, channels, favs, ::play)
                    AppNavV3.SOURCES -> sourcesV3(store) { refresh++ }
                }
            }
        }
    }
}

private fun navIconV3(n: AppNavV3) = when (n) {
    AppNavV3.HOME -> Icons.Default.Home
    AppNavV3.SPORTS -> Icons.Default.SportsScore
    AppNavV3.LIVE -> Icons.Default.LiveTv
    AppNavV3.FAV -> Icons.Default.Star
    AppNavV3.SEARCH -> Icons.Default.Search
    AppNavV3.SOURCES -> Icons.Default.SettingsInputAntenna
}

private fun buildChannelCategories(source: List<SportsChannel>): List<ChannelCategory> {
    if (source.isEmpty()) return emptyList()
    val generic = setOf("live tv", "live", "channels", "channel", "tv", "all")
    val meaningfulGroups = source.map { it.group.trim() }.filter { it.isNotBlank() && it.lowercase() !in generic }.distinctBy { it.lowercase() }
    if (meaningfulGroups.size > 1) {
        return source.groupBy { it.group.trim().ifBlank { "Live TV" } }
            .entries.sortedBy { it.key.lowercase() }
            .map { (name, list) -> ChannelCategory(name, list, list.firstOrNull { !it.logo.isNullOrBlank() }?.logo) }
    }

    // Some providers encode category headers as pseudo-channels named "## CATEGORY".
    val markerMode = source.any { it.name.trim().startsWith("##") }
    if (markerMode) {
        val buckets = LinkedHashMap<String, MutableList<SportsChannel>>()
        var current = "Live TV"
        source.forEach { channel ->
            val raw = channel.name.trim()
            if (raw.startsWith("##")) {
                val category = raw.trimStart('#').trim().trimEnd('#').trim()
                if (category.isNotBlank()) current = category
                buckets.putIfAbsent(current, mutableListOf())
            } else {
                buckets.getOrPut(current) { mutableListOf() }.add(channel)
            }
        }
        return buckets.entries.mapNotNull { (name, list) ->
            if (list.isEmpty()) null else ChannelCategory(name, list, list.firstOrNull { !it.logo.isNullOrBlank() }?.logo)
        }
    }

    return source.groupBy { it.group.trim().ifBlank { "Live TV" } }
        .entries.sortedBy { it.key.lowercase() }
        .map { (name, list) -> ChannelCategory(name, list, list.firstOrNull { !it.logo.isNullOrBlank() }?.logo) }
}

private fun LazyListScope.homeV3(e: List<SportsEvent>, c: List<SportsChannel>, f: Favs, play: (String) -> Unit) {
    val featured = SportsSchedule.liveEvents(e).firstOrNull() ?: SportsSchedule.upcomingEvents(e).firstOrNull()
    item { HeroV3(featured, c, play) }
    val live = SportsSchedule.liveEvents(e)
    if (live.isNotEmpty()) { item { SectionV3("LIVE NOW", "${live.size} on air") }; item { EventRailV3(live.take(10), c, f, play) } }
    val upcoming = SportsSchedule.upcomingEvents(e).take(16)
    item { SectionV3("TODAY'S EVENTS", "${upcoming.size} scheduled") }
    if (upcoming.isEmpty()) item { EmptyV3("No scheduled events", "Refresh to retry the public schedule providers.") } else item { EventRailV3(upcoming, c, f, play) }
    item { SectionV3("SPORTS", "Explore") }; item { BrandRailV3() }
    item { SectionV3("DEDICATED HUBS", "WWE • AEW • TNA • ROH • MOTORSPORTS • TENNIS • GOLF") }; item { HubRailV3() }
    item { SectionV3("LIVE TV", "${c.size} indexed channels") }
    item { Text("Open Live TV to browse channels by category.", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 14.dp)) }
}

@Composable private fun HeroV3(e: SportsEvent?, c: List<SportsChannel>, play: (String) -> Unit) {
    val compact = e == null
    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(if (compact) 112.dp else 205.dp).clip(RoundedCornerShape(24.dp)).background(Brush.linearGradient(listOf(Color(0xFF30174D), Color(0xFF0B3141), Color(0xFF101522))))) {
        if (!compact && !e?.leagueLogo.isNullOrBlank()) AsyncImage(e?.leagueLogo, e?.league ?: "Sports", Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = 0.18f)
        Column(Modifier.fillMaxWidth().align(Alignment.BottomStart).padding(if (compact) 16.dp else 20.dp)) {
            Text(if (e?.state == "in") "LIVE" else "USPORTZ", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Black)
            Text(if (e == null) "Your sports command center" else eventTitleV3(e), fontSize = if (compact) 20.sp else 25.sp, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp))
            Text(if (e == null) "Fast schedules • live channels • smart matching" else if (e.state == "in") e.detail.ifBlank { "Live now" } else formatClockV3(e.startTime), color = Color.LightGray, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (e?.state == "in") SportsChannelBridge.bestMatch(e, c)?.let { Button(onClick = { play(it.url) }, modifier = Modifier.padding(top = 6.dp)) { Icon(Icons.Default.PlayArrow, null); Text("WATCH LIVE") } }
        }
    }
}

private fun eventTitleV3(e: SportsEvent): String = e.competitors.map { it.trim() }.filter { it.isNotBlank() && !it.equals("null", true) }.takeIf { it.isNotEmpty() }?.joinToString("  •  ") ?: e.shortName.trim().takeIf { it.isNotBlank() } ?: e.name.trim().takeIf { it.isNotBlank() } ?: e.league
private fun formatClockV3(value: String): String = runCatching { java.time.Instant.parse(value).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("EEE • h:mm a")) }.getOrElse { "Scheduled" }
@Composable private fun SectionV3(a: String, b: String) = Text("$a  $b", fontSize = 17.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
@Composable private fun HeaderV3(t: String, loading: Boolean, refresh: () -> Unit, sources: () -> Unit) { Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("USPORTZ", fontSize = 29.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp); Text(t.uppercase(), fontSize = 11.sp, color = Color.Gray) }; IconButton(refresh, enabled = !loading) { Icon(Icons.Default.Refresh, "Refresh") }; IconButton(sources) { Icon(Icons.Default.SettingsInputAntenna, "Sources") } } }

@Composable private fun BrandRailV3() { val brands = listOf("All", "NFL", "NCAA", "NBA", "WNBA", "MLB", "NHL", "MLS", "UFC", "WWE", "AEW", "TNA", "ROH", "NASCAR", "INDYCAR", "F1", "MotoGP", "Tennis", "Golf", "Boxing"); LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(brands, key = { it }) { x -> Card(Modifier.width(92.dp).height(70.dp), shape = RoundedCornerShape(15.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF121A29))) { Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.Center) { Text(x, fontWeight = FontWeight.Black, fontSize = 12.sp); Text(if (x == "All") "ALL SPORTS" else "OPEN", color = Color(0xFF63D7FF), fontSize = 8.sp, fontWeight = FontWeight.Bold) } } } } }

@Composable private fun HubRailV3() { val context = LocalContext.current; LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) { items(SportsHubCatalog.hubs, key = { it.key }) { hub -> Card(onClick = { context.startActivity(Intent(context, SportsHubActivity::class.java).putExtra(SportsHubActivity.EXTRA_HUB, hub.key)) }, modifier = Modifier.width(190.dp).height(116.dp), shape = RoundedCornerShape(17.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF17121F))) { Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.Center) { Text(hub.title, fontSize = 20.sp, fontWeight = FontWeight.Black); Text(hub.subtitle, fontSize = 10.sp, color = Color.Gray, maxLines = 2, overflow = TextOverflow.Ellipsis); Text("OPEN HUB →", fontSize = 9.sp, color = Color(0xFF63D7FF), fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 9.dp)) } } } } }

@Composable private fun EventCardV3(e: SportsEvent, c: List<SportsChannel>, f: Favs, play: (String) -> Unit) { val b = SportsChannelBridge.bestMatch(e, c); Card(Modifier.width(294.dp), shape = RoundedCornerShape(19.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF121522))) { Column(Modifier.padding(10.dp)) { EventArtwork(e, SportsPresentation.brand(e)); Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(if (e.state == "in") "LIVE" else formatClockV3(e.startTime), color = if (e.state == "in") Color(0xFFFF5FAF) else Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold); Text(eventTitleV3(e), fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis) }; IconButton({ f.toggleEvent(e.id) }) { Icon(if (f.isEventFav(e.id)) Icons.Default.Star else Icons.Default.StarBorder, "Favorite") } }; if (b != null) Button(onClick = { play(b.url) }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) { Icon(Icons.Default.PlayArrow, null); Text(if (e.state == "in") "WATCH LIVE" else "WATCH") } } } }
@Composable private fun EventRailV3(e: List<SportsEvent>, c: List<SportsChannel>, f: Favs, play: (String) -> Unit) = LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) { items(e, key = { it.id }) { EventCardV3(it, c, f, play) } }

private fun LazyListScope.liveV3(categories: List<ChannelCategory>, selected: String?, open: (String) -> Unit, back: () -> Unit, search: String, setSearch: (String) -> Unit, f: Favs, play: (String) -> Unit) {
    if (selected == null) {
        item { SectionV3("LIVE TV", "${categories.sumOf { it.channels.size }} channels • ${categories.size} categories") }
        item { Text("Choose a category", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 14.dp)) }
        if (categories.isEmpty()) item { EmptyV3("No categories loaded", "Connect Xtream Codes or M3U/M3U8 from Sources.") }
        else items(categories, key = { "category-${it.name.lowercase()}" }) { category -> CategoryCardV3(category, open) }
    } else {
        val category = categories.firstOrNull { it.name == selected }
        if (category == null) {
            item { EmptyV3("Category unavailable", "Return to Live TV and choose another category.") }
        } else {
            item { CategoryHeaderV3(category, back) }
            item { OutlinedTextField(search, setSearch, Modifier.fillMaxWidth().padding(horizontal = 12.dp), label = { Text("Search ${category.name}") }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true, shape = RoundedCornerShape(15.dp)) }
            val filtered = if (search.isBlank()) category.channels else category.channels.filter { it.name.contains(search, true) }
            item { Text("${filtered.size} channels", color = Color.Gray, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp)) }
            itemsIndexed(filtered, key = { index, channel -> "channel-${channel.id}-$index" }) { _, channel -> ChannelRowV3(channel, f.isChannelFav(channel.id), { play(channel.url) }, { f.toggleChannel(channel.id) }) }
        }
    }
}

@Composable private fun CategoryCardV3(category: ChannelCategory, open: (String) -> Unit) {
    Card(onClick = { open(category.name) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF111624))) {
        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(54.dp).clip(RoundedCornerShape(12.dp)).background(Brush.linearGradient(listOf(Color(0xFF252A3B), Color(0xFF171A26)))), contentAlignment = Alignment.Center) {
                if (!category.logo.isNullOrBlank()) AsyncImage(category.logo, category.name, Modifier.size(42.dp), contentScale = ContentScale.Fit) else Icon(Icons.Default.Folder, null, tint = Color(0xFF63D7FF), modifier = Modifier.size(30.dp))
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(category.name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${category.channels.size} channels", color = Color.Gray, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
            }
            Icon(Icons.Default.ChevronRight, null, tint = Color(0xFF63D7FF))
        }
    }
}

@Composable private fun CategoryHeaderV3(category: ChannelCategory, back: () -> Unit) { Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) { IconButton(back) { Icon(Icons.Default.ArrowBack, "Back") }; Column(Modifier.weight(1f)) { Text(category.name, fontSize = 20.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis); Text("${category.channels.size} channels", color = Color.Gray, fontSize = 11.sp) } } }
@Composable private fun ChannelRowV3(c: SportsChannel, fav: Boolean, play: () -> Unit, toggle: () -> Unit) { Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp), shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF111624))) { Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) { if (!c.logo.isNullOrBlank()) AsyncImage(c.logo, c.name, Modifier.size(48.dp), contentScale = ContentScale.Fit); Column(Modifier.weight(1f).padding(horizontal = 9.dp)) { Text(c.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(c.group, color = Color.Gray, fontSize = 10.sp) }; IconButton(toggle) { Icon(if (fav) Icons.Default.Star else Icons.Default.StarBorder, "Favorite") }; FilledTonalButton(onClick = play) { Icon(Icons.Default.PlayArrow, null) } } } }

private fun LazyListScope.sportsV3(e: List<SportsEvent>, c: List<SportsChannel>, sel: String, set: (String) -> Unit, f: Favs, play: (String) -> Unit) { item { SportFilterV3(sel, set) }; val x = SportsSchedule.forSport(e, sel); val live = SportsSchedule.liveEvents(x); if (live.isNotEmpty()) { item { SectionV3("LIVE", "$sel now") }; item { EventRailV3(live, c, f, play) } }; val upcoming = SportsSchedule.upcomingEvents(x); item { SectionV3("UPCOMING", "$sel schedule") }; if (upcoming.isEmpty()) item { EmptyV3("No scheduled events", "Refresh to retry the schedule providers.") } else item { EventRailV3(upcoming, c, f, play) } }
@Composable private fun SportFilterV3(sel: String, set: (String) -> Unit) { LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) { items(SportsCatalog.categories, key = { it }) { x -> FilterChip(selected = sel == x, onClick = { set(x) }, label = { Text(x) }) } } }

private fun LazyListScope.favoritesV3(e: List<SportsEvent>, c: List<SportsChannel>, f: Favs, play: (String) -> Unit) { item { SectionV3("FAVORITES", "Saved on this device") }; val fe = e.filter { f.isEventFav(it.id) }; val fc = c.filter { f.isChannelFav(it.id) }; if (fe.isNotEmpty()) item { EventRailV3(fe, c, f, play) }; if (fc.isNotEmpty()) { item { SectionV3("CHANNELS", "Saved channels") }; itemsIndexed(fc, key = { index, channel -> "fav-${channel.id}-$index" }) { _, channel -> ChannelRowV3(channel, true, { play(channel.url) }, { f.toggleChannel(channel.id) }) } }; if (fe.isEmpty() && fc.isEmpty()) item { EmptyV3("Nothing saved yet", "Star an event or channel to build Favorites.") } }

private fun LazyListScope.searchV3(q: String, set: (String) -> Unit, e: List<SportsEvent>, c: List<SportsChannel>, f: Favs, play: (String) -> Unit) { item { OutlinedTextField(q, set, Modifier.fillMaxWidth().padding(horizontal = 12.dp), label = { Text("Search everything") }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true, shape = RoundedCornerShape(15.dp)) }; if (q.isBlank()) item { Text("Search teams • events • leagues • channels • sports", color = Color.Gray, modifier = Modifier.padding(horizontal = 16.dp)) } else { val ev = e.filter { it.name.contains(q, true) || it.shortName.contains(q, true) || it.competitors.any { t -> t.contains(q, true) } }.take(20); item { SectionV3("EVENTS", "${ev.size} matches") }; ev.forEach { event -> item { Text(eventTitleV3(event), Modifier.padding(horizontal = 16.dp, vertical = 4.dp), fontWeight = FontWeight.Bold) } }; val ch = c.asSequence().filter { it.name.contains(q, true) || it.group.contains(q, true) }.take(80).toList(); item { SectionV3("CHANNELS", "${ch.size} matches") }; itemsIndexed(ch, key = { index, channel -> "search-${channel.id}-$index" }) { _, channel -> ChannelRowV3(channel, f.isChannelFav(channel.id), { play(channel.url) }, { f.toggleChannel(channel.id) }) } } }

private fun LazyListScope.sourcesV3(s: SourceStore, done: () -> Unit) { item { SectionV3("SOURCES", "Xtream Codes + M3U/M3U8") }; item { Editor(s, done) }; item { Text("Passwords remain local and are never bundled.", color = Color.Gray, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp)) } }
@Composable private fun EmptyV3(title: String, body: String) { Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF111624))) { Column(Modifier.padding(18.dp)) { Text(title, fontWeight = FontWeight.Black); Text(body, color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)) } } }
