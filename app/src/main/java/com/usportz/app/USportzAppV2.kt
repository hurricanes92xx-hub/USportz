package com.usportz.app

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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

private enum class AppNavV2 { HOME, SPORTS, LIVE, FAV, SEARCH, SOURCES }

@Composable
internal fun USportzAppV2(store: SourceStore) {
    val context = LocalContext.current
    var nav by remember { mutableStateOf(AppNavV2.HOME) }
    var sport by remember { mutableStateOf("All") }
    var query by remember { mutableStateOf("") }
    var events by remember { mutableStateOf(emptyList<SportsEvent>()) }
    var channels by remember { mutableStateOf(emptyList<SportsChannel>()) }
    var loading by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    val favs = remember { Favs(context) }
    val channelIndex = remember(channels) { ChannelIndex(channels, { it.name }, { it.group }) }

    LaunchedEffect(Unit) { SportsChannelBridge.restoreCached(context).takeIf { it.isNotEmpty() }?.let { channels = it } }
    LaunchedEffect(refresh) {
        loading = true
        SportsChannelBridge.restoreCached(context).takeIf { it.isNotEmpty() }?.let { channels = it }
        channels = SportsChannelBridge.load(context, refresh > 0)
        runCatching { SportsSchedule.load(context, refresh > 0, channels) }.onSuccess { events = it }
        loading = false
    }
    fun play(url: String) { context.startActivity(Intent(context, RichPlayerActivity::class.java).putExtra(RichPlayerActivity.EXTRA_URL, url)) }

    MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFBFA6FF), secondary = Color(0xFF63D7FF), tertiary = Color(0xFFFF5FAF), background = Color(0xFF080A10), surface = Color(0xFF10131D))) {
        Scaffold(containerColor = Color(0xFF080A10), bottomBar = {
            NavigationBar(containerColor = Color(0xFF0D1018)) { AppNavV2.values().forEach { n -> NavigationBarItem(selected = nav == n, onClick = { nav = n }, icon = { Icon(navIconV2(n), null) }, label = { Text(n.name.lowercase().replaceFirstChar { it.uppercase() }) }) } }
        }) { pad ->
            LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item { HeaderV2(nav.name, loading, { refresh++ }, { nav = AppNavV2.SOURCES }) }
                when (nav) {
                    AppNavV2.HOME -> homeV2(events, channels, favs, ::play)
                    AppNavV2.SPORTS -> sportsV2(events, channels, sport, { sport = it }, favs, ::play)
                    AppNavV2.LIVE -> liveV2(channelIndex, favs, ::play)
                    AppNavV2.FAV -> favoritesV2(events, channels, favs, ::play)
                    AppNavV2.SEARCH -> searchV2(query, { query = it }, events, channelIndex, favs, ::play)
                    AppNavV2.SOURCES -> sourcesV2(store) { refresh++ }
                }
            }
        }
    }
}

private fun navIconV2(n: AppNavV2) = when (n) { AppNavV2.HOME -> Icons.Default.Home; AppNavV2.SPORTS -> Icons.Default.SportsScore; AppNavV2.LIVE -> Icons.Default.LiveTv; AppNavV2.FAV -> Icons.Default.Star; AppNavV2.SEARCH -> Icons.Default.Search; AppNavV2.SOURCES -> Icons.Default.SettingsInputAntenna }

private fun LazyListScope.homeV2(e: List<SportsEvent>, c: List<SportsChannel>, f: Favs, play: (String) -> Unit) {
    val featured = SportsSchedule.liveEvents(e).firstOrNull() ?: SportsSchedule.upcomingEvents(e).firstOrNull()
    item { HeroV2(featured, c, play) }
    val live = SportsSchedule.liveEvents(e)
    if (live.isNotEmpty()) { item { SectionV2("LIVE NOW", "${live.size} on air") }; item { EventRailV2(live.take(10), c, f, play) } }
    val upcoming = SportsSchedule.upcomingEvents(e).take(16)
    item { SectionV2("TODAY'S EVENTS", "${upcoming.size} scheduled") }
    if (upcoming.isEmpty()) item { EmptyV2("No scheduled events", "Refresh to retry the public schedule providers.") } else item { EventRailV2(upcoming, c, f, play) }
    item { SectionV2("SPORTS", "Explore") }; item { BrandRailV2() }
    item { SectionV2("DEDICATED HUBS", "WWE • AEW • TNA • ROH • MOTORSPORTS • TENNIS • GOLF") }; item { HubRailV2() }
    item { SectionV2("LIVE TV", "${c.size} indexed channels") }; item { ChannelRailV2(c.take(12), f, play) }
}

@Composable private fun HeroV2(e: SportsEvent?, c: List<SportsChannel>, play: (String) -> Unit) {
    val compact = e == null
    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(if (compact) 112.dp else 205.dp).clip(RoundedCornerShape(24.dp)).background(Brush.linearGradient(listOf(Color(0xFF30174D), Color(0xFF0B3141), Color(0xFF101522))))) {
        if (!compact && !e?.leagueLogo.isNullOrBlank()) AsyncImage(e?.leagueLogo, e?.league ?: "Sports", Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = 0.18f)
        Column(Modifier.fillMaxWidth().align(Alignment.BottomStart).padding(if (compact) 16.dp else 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.clip(RoundedCornerShape(7.dp)).background(if (e?.state == "in") Color(0xFFFF5FAF) else Color(0xFF9B5CFF)).padding(horizontal = 8.dp, vertical = 4.dp)) { Text(if (e?.state == "in") "LIVE" else "USPORTZ", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Black) }
                if (e != null) Text("  ${e.league}", color = Color.LightGray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            Text(if (e == null) "Your sports command center" else eventTitleV2(e), fontSize = if (compact) 20.sp else 25.sp, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp))
            Text(if (e == null) "Fast schedules • live channels • smart matching" else if (e.state == "in") "${e.detail.ifBlank { "Live now" }}${if (bestChannelV2(e, c) != null) " • WATCH READY" else ""}" else formatClockV2(e.startTime), color = Color.LightGray, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (e != null && e.state == "in") bestChannelV2(e, c)?.let { Button(onClick = { play(it.url) }, modifier = Modifier.padding(top = 6.dp), contentPadding = PaddingValues(horizontal = 15.dp, vertical = 1.dp)) { Icon(Icons.Default.PlayArrow, null); Text("WATCH LIVE") } }
        }
    }
}

private fun eventTitleV2(e: SportsEvent): String = e.competitors.map { it.trim() }.filter { it.isNotBlank() && !it.equals("null", true) }.takeIf { it.isNotEmpty() }?.joinToString("  •  ") ?: e.shortName.trim().takeIf { it.isNotBlank() && !it.equals("null", true) } ?: e.name.trim().takeIf { it.isNotBlank() && !it.equals("null", true) } ?: e.league
private fun bestChannelV2(e: SportsEvent, c: List<SportsChannel>): SportsChannel? = SportsChannelBridge.bestMatch(e, c)
private fun formatClockV2(value: String): String = runCatching { java.time.Instant.parse(value).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("EEE • h:mm a")) }.getOrElse { "Scheduled" }
@Composable private fun SectionV2(a: String, b: String) = Text("$a  $b", fontSize = 17.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
@Composable private fun HeaderV2(t: String, loading: Boolean, refresh: () -> Unit, sources: () -> Unit) { Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("USPORTZ", fontSize = 29.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp); Text(t.uppercase(), fontSize = 11.sp, color = Color.Gray) }; IconButton(refresh, enabled = !loading) { Icon(Icons.Default.Refresh, "Refresh") }; IconButton(sources) { Icon(Icons.Default.SettingsInputAntenna, "Sources") } } }

@Composable private fun BrandRailV2() { val brands = listOf("All", "NFL", "NCAA", "NBA", "WNBA", "MLB", "NHL", "MLS", "UFC", "WWE", "AEW", "TNA", "ROH", "NASCAR", "INDYCAR", "F1", "MotoGP", "Tennis", "Golf", "Boxing"); LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(brands, key = { it }) { x -> Card(Modifier.width(92.dp).height(70.dp), shape = RoundedCornerShape(15.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF121A29))) { Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.Center) { Text(x, fontWeight = FontWeight.Black, fontSize = 12.sp); Text(if (x == "All") "ALL SPORTS" else "OPEN", color = Color(0xFF63D7FF), fontSize = 8.sp, fontWeight = FontWeight.Bold) } } } } }
@Composable private fun HubRailV2() { val context = LocalContext.current; LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) { items(SportsHubCatalog.hubs, key = { it.key }) { hub -> Card(onClick = { context.startActivity(Intent(context, SportsHubActivity::class.java).putExtra(SportsHubActivity.EXTRA_HUB, hub.key)) }, modifier = Modifier.width(190.dp).height(116.dp), shape = RoundedCornerShape(17.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF17121F))) { Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.Center) { Text(hub.title, fontSize = 20.sp, fontWeight = FontWeight.Black); Text(hub.subtitle, fontSize = 10.sp, color = Color.Gray, maxLines = 2, overflow = TextOverflow.Ellipsis); Text("OPEN HUB →", fontSize = 9.sp, color = Color(0xFF63D7FF), fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 9.dp)) } } } } }

@Composable private fun EventCardV2(e: SportsEvent, c: List<SportsChannel>, f: Favs, play: (String) -> Unit) { val b = bestChannelV2(e, c); val brand = SportsPresentation.brand(e); Card(Modifier.width(294.dp), shape = RoundedCornerShape(19.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF121522))) { Column(Modifier.padding(10.dp)) { Box(Modifier.fillMaxWidth().height(116.dp).clip(RoundedCornerShape(15.dp)).background(Brush.linearGradient(listOf(Color(0xFF1B2940), Color(0xFF131622))))) { val logo = e.leagueLogo ?: BrandAssets.logoUrl(brand); if (!logo.isNullOrBlank()) AsyncImage(logo, e.league, Modifier.fillMaxSize().padding(18.dp), contentScale = ContentScale.Fit); Column(Modifier.align(Alignment.BottomStart).padding(10.dp)) { Text(brand?.label ?: e.league, color = Color(0xFF63D7FF), fontSize = 10.sp, fontWeight = FontWeight.Bold); Text(eventTitleV2(e), color = Color.White, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis) } }; Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(if (e.state == "in") "LIVE" else formatClockV2(e.startTime), color = if (e.state == "in") Color(0xFFFF5FAF) else Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold); if (e.broadcast.isNotBlank()) Text(e.broadcast, color = Color.Gray, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }; IconButton({ f.toggleEvent(e.id) }, Modifier.size(34.dp)) { Icon(if (f.isEventFav(e.id)) Icons.Default.Star else Icons.Default.StarBorder, "Favorite") } }; if (b != null) Button(onClick = { play(b.url) }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) { Icon(Icons.Default.PlayArrow, null); Text(if (e.state == "in") "WATCH LIVE" else "WATCH") } } } }
@Composable private fun EventRailV2(e: List<SportsEvent>, c: List<SportsChannel>, f: Favs, play: (String) -> Unit) = LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) { items(e, key = { it.id }) { EventCardV2(it, c, f, play) } }
@Composable private fun ChannelRowV2(c: SportsChannel, fav: Boolean, play: () -> Unit, toggle: () -> Unit) { Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp), shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF111624))) { Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) { if (!c.logo.isNullOrBlank()) AsyncImage(c.logo, c.name, Modifier.size(48.dp), contentScale = ContentScale.Fit); Column(Modifier.weight(1f).padding(horizontal = 9.dp)) { Text(c.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(c.group, color = Color.Gray, fontSize = 10.sp) }; IconButton(toggle) { Icon(if (fav) Icons.Default.Star else Icons.Default.StarBorder, "Favorite") }; FilledTonalButton(onClick = play) { Icon(Icons.Default.PlayArrow, null) } } } }
@Composable private fun EmptyV2(title: String, body: String) { Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF111624))) { Column(Modifier.padding(18.dp)) { Text(title, fontWeight = FontWeight.Black); Text(body, color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)) } } }
@Composable private fun SearchBoxV2(v: String, set: (String) -> Unit) = OutlinedTextField(v, set, Modifier.fillMaxWidth().padding(horizontal = 12.dp), label = { Text("Search everything") }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true, shape = RoundedCornerShape(15.dp))

private fun LazyListScope.sportsV2(e: List<SportsEvent>, c: List<SportsChannel>, sel: String, set: (String) -> Unit, f: Favs, play: (String) -> Unit) { item { SportRailFilterV2(sel, set) }; val x = SportsSchedule.forSport(e, sel); val live = SportsSchedule.liveEvents(x); if (live.isNotEmpty()) { item { SectionV2("LIVE", "$sel now") }; item { EventRailV2(live, c, f, play) } }; val upcoming = SportsSchedule.upcomingEvents(x); item { SectionV2("UPCOMING", "$sel schedule") }; if (upcoming.isEmpty()) item { EmptyV2("No scheduled events", "Refresh to retry the schedule providers.") } else item { EventRailV2(upcoming, c, f, play) } }
@Composable private fun SportRailFilterV2(sel: String, set: (String) -> Unit) { LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) { items(SportsCatalog.categories, key = { it }) { x -> FilterChip(selected = sel == x, onClick = { set(x) }, label = { Text(x) }) } } }
private fun LazyListScope.liveV2(index: ChannelIndex<SportsChannel>, f: Favs, play: (String) -> Unit) { val c = index.all(); item { SectionV2("LIVE TV", "${c.size} channels") }; if (c.isEmpty()) item { EmptyV2("No channels loaded", "Connect a source in Sources.") } else items(c, key = { it.id }) { ChannelRowV2(it, f.isChannelFav(it.id), { play(it.url) }, { f.toggleChannel(it.id) }) } }
private fun LazyListScope.favoritesV2(e: List<SportsEvent>, c: List<SportsChannel>, f: Favs, play: (String) -> Unit) { item { SectionV2("FAVORITES", "Saved on this device") }; val fe = e.filter { f.isEventFav(it.id) }; val fc = c.filter { f.isChannelFav(it.id) }; if (fe.isNotEmpty()) item { EventRailV2(fe, c, f, play) }; if (fc.isNotEmpty()) item { ChannelRailV2(fc, f, play) }; if (fe.isEmpty() && fc.isEmpty()) item { EmptyV2("Nothing saved yet", "Star an event or channel to build Favorites.") } }
private fun LazyListScope.searchV2(q: String, set: (String) -> Unit, e: List<SportsEvent>, index: ChannelIndex<SportsChannel>, f: Favs, play: (String) -> Unit) { item { SearchBoxV2(q, set) }; if (q.isBlank()) item { Text("Search teams • events • leagues • channels • sports", color = Color.Gray, modifier = Modifier.padding(horizontal = 16.dp)) } else { val ev = e.filter { it.name.contains(q, true) || it.shortName.contains(q, true) || it.competitors.any { t -> t.contains(q, true) } }.take(20); val ch = index.search(q, 40); if (ev.isNotEmpty()) { item { SectionV2("EVENTS", "${ev.size} matches") }; ev.forEach { event -> item { Text(eventTitleV2(event), Modifier.padding(horizontal = 16.dp, vertical = 4.dp), fontWeight = FontWeight.Bold) } } }; item { SectionV2("CHANNELS", "${ch.size} matches") }; items(ch, key = { "search-${it.id}" }) { ChannelRowV2(it, f.isChannelFav(it.id), { play(it.url) }, { f.toggleChannel(it.id) }) } } }
private fun LazyListScope.sourcesV2(s: SourceStore, done: () -> Unit) { item { SectionV2("SOURCES", "Xtream Codes + M3U/M3U8") }; item { Editor(s, done) }; item { Text("Passwords remain local and are never bundled.", color = Color.Gray, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp)) } }
@Composable private fun ChannelRailV2(c: List<SportsChannel>, f: Favs, play: (String) -> Unit) = LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(c, key = { it.id }) { ChannelRowV2(it, f.isChannelFav(it.id), { play(it.url) }, { f.toggleChannel(it.id) }) } }
