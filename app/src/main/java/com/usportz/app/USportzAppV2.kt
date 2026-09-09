package com.usportz.app

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
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
    if (upcoming.isEmpty()) item { Empty("No scheduled events", "Refresh to retry the public schedule providers.") } else item { EventRailV2(upcoming, c, f, play) }
    item { PremiumSectionHeader("SPORTS", "Explore") }; item { PremiumBrandRail() }; item { SportRailV2() }
    item { PremiumSectionHeader("DEDICATED HUBS", "WWE • AEW • TNA • ROH • MOTORSPORTS • TENNIS • GOLF") }; item { SportsHubRail() }
    item { SectionV2("LIVE TV", "${c.size} indexed") }; item { ChannelRailV2(c.take(12), f, play) }
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

private fun eventTitleV2(e: SportsEvent): String = e.competitors.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.joinToString("  •  ") ?: e.shortName.ifBlank { e.name }.ifBlank { e.league }
private fun bestChannelV2(e: SportsEvent, c: List<SportsChannel>): SportsChannel? = SportsChannelBridge.bestMatch(e, c)
private fun formatClockV2(value: String): String = runCatching { java.time.Instant.parse(value).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("EEE • h:mm a")) }.getOrElse { "Scheduled" }

@Composable private fun SectionV2(a: String, b: String) = Text("$a  $b", fontSize = 17.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
@Composable private fun SportRailV2(sel: String = "All", set: (String) -> Unit = {}) { LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) { items(SportsCatalog.categories, key = { it }) { x -> FilterChip(selected = sel == x, onClick = { set(x) }, label = { Text(x) }) } } }
@Composable private fun HeaderV2(t: String, loading: Boolean, refresh: () -> Unit, sources: () -> Unit) { Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("USPORTZ", fontSize = 29.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp); Text(t.uppercase(), fontSize = 11.sp, color = Color.Gray) }; IconButton(refresh, enabled = !loading) { Icon(Icons.Default.Refresh, "Refresh") }; IconButton(sources) { Icon(Icons.Default.SettingsInputAntenna, "Sources") } } }

private fun LazyListScope.sportsV2(e: List<SportsEvent>, c: List<SportsChannel>, sel: String, set: (String) -> Unit, f: Favs, play: (String) -> Unit) { item { SportRailV2(sel, set) }; if (sel == "All") item { PremiumBrandRail() }; if (sel == "Wrestling") item { SportsHubRail() }; val x = SportsSchedule.forSport(e, sel); val live = SportsSchedule.liveEvents(x); if (live.isNotEmpty()) { item { SectionV2("LIVE", "$sel now") }; item { EventRailV2(live, c, f, play) } }; val upcoming = SportsSchedule.upcomingEvents(x); item { SectionV2("UPCOMING", "$sel schedule") }; if (upcoming.isEmpty()) item { Empty("No scheduled events", "Refresh to retry the schedule providers.") } else item { EventRailV2(upcoming, c, f, play) } }
private fun LazyListScope.liveV2(index: ChannelIndex<SportsChannel>, f: Favs, play: (String) -> Unit) { val c = index.all(); item { SectionV2("LIVE TV", "${c.size} channels") }; if (c.isEmpty()) item { Empty("No channels loaded", "Connect a source in Sources.") } else items(c, key = { it.id }) { ChannelRow(it, f.isChannelFav(it.id), { play(it.url) }, { f.toggleChannel(it.id) }) } }
private fun LazyListScope.favoritesV2(e: List<SportsEvent>, c: List<SportsChannel>, f: Favs, play: (String) -> Unit) { item { SectionV2("FAVORITES", "Saved on this device") }; val fe = e.filter { f.isEventFav(it.id) }; val fc = c.filter { f.isChannelFav(it.id) }; if (fe.isNotEmpty()) { item { EventRailV2(fe, c, f, play) } }; if (fc.isNotEmpty()) { item { ChannelRailV2(fc, f, play) } }; if (fe.isEmpty() && fc.isEmpty()) item { Empty("Nothing saved yet", "Star an event or channel to build Favorites.") } }
private fun LazyListScope.searchV2(q: String, set: (String) -> Unit, e: List<SportsEvent>, index: ChannelIndex<SportsChannel>, f: Favs, play: (String) -> Unit) { item { SearchBox(q, set) }; if (q.isBlank()) item { Text("Search teams • events • leagues • channels • sports", color = Color.Gray, modifier = Modifier.padding(horizontal = 16.dp)) } else { val ev = e.filter { it.name.contains(q, true) || it.shortName.contains(q, true) || it.competitors.any { t -> t.contains(q, true) } }.take(20); val ch = index.search(q, 40); item { Results("EVENTS", ev.map(::eventTitleV2)) }; item { SectionV2("CHANNELS", "${ch.size} matches") }; items(ch, key = { "search-${it.id}" }) { ChannelRow(it, f.isChannelFav(it.id), { play(it.url) }, { f.toggleChannel(it.id) }) } } }
private fun LazyListScope.sourcesV2(s: SourceStore, done: () -> Unit) { item { SectionV2("SOURCES", "Xtream Codes + M3U/M3U8") }; item { Editor(s, done) }; item { Text("Passwords remain local and are never bundled.", color = Color.Gray, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp)) } }

@Composable private fun EventRailV2(e: List<SportsEvent>, c: List<SportsChannel>, f: Favs, play: (String) -> Unit) = LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) { items(e, key = { it.id }) { EventCardV2(it, c, f, play) } }
@Composable private fun EventCardV2(e: SportsEvent, c: List<SportsChannel>, f: Favs, play: (String) -> Unit) { val b = bestChannelV2(e, c); val brand = SportsPresentation.brand(e); Card(Modifier.width(294.dp), shape = RoundedCornerShape(19.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF121522))) { Column(Modifier.padding(10.dp)) { EventArtwork(e, brand); Row(Modifier.padding(top = 9.dp), verticalAlignment = Alignment.CenterVertically) { val logo = e.leagueLogo ?: BrandAssets.logoUrl(brand); if (!logo.isNullOrBlank()) AsyncImage(logo, e.league, Modifier.size(34.dp), contentScale = ContentScale.Fit); Column(Modifier.weight(1f).padding(horizontal = 9.dp)) { Text(brand?.label ?: e.league, fontSize = 10.sp, color = Color(0xFF63D7FF), fontWeight = FontWeight.Bold); Text(eventTitleV2(e), fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis) }; IconButton({ f.toggleEvent(e.id) }, Modifier.size(34.dp)) { Icon(if (f.isEventFav(e.id)) Icons.Default.Star else Icons.Default.StarBorder, "Favorite") } }; Row(Modifier.padding(top = 7.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) { AssistChip(onClick = {}, label = { Text(if (e.state == "in") "LIVE" else "UPCOMING", fontSize = 9.sp) }); if (e.broadcast.isNotBlank()) AssistChip(onClick = {}, label = { Text(e.broadcast, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }) }; Text(if (e.state == "in") e.detail.ifBlank { "Live now" } else formatClockV2(e.startTime), color = if (e.state == "in") Color(0xFFFF5FAF) else Color.Gray, fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp)); if (b != null) Button(onClick = { play(b.url) }, modifier = Modifier.fillMaxWidth().padding(top = 7.dp)) { Icon(Icons.Default.PlayArrow, null); Text(if (e.state == "in") "WATCH LIVE" else "WATCH") } } } }
@Composable private fun ChannelRailV2(c: List<SportsChannel>, f: Favs, play: (String) -> Unit) = LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(c, key = { it.id }) { ChannelRow(it, f.isChannelFav(it.id), { play(it.url) }, { f.toggleChannel(it.id) }) } }
