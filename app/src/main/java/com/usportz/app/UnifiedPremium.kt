package com.usportz.app

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

private enum class Nav { HOME, SPORTS, LIVE, FAV, SEARCH, SOURCES }

@Composable
internal fun USportzApp(store: SourceStore) {
    val context = LocalContext.current
    var nav by remember { mutableStateOf(Nav.HOME) }
    var sport by remember { mutableStateOf("All") }
    var query by remember { mutableStateOf("") }
    var events by remember { mutableStateOf(emptyList<SportsEvent>()) }
    var channels by remember { mutableStateOf(emptyList<SportsChannel>()) }
    var loading by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    val favs = remember { Favs(context) }
    val channelIndex = remember(channels) { ChannelIndex(channels, { it.name }, { it.group }) }

    LaunchedEffect(Unit) {
        val local = SportsChannelBridge.restoreCached(context)
        if (local.isNotEmpty()) channels = local
    }
    LaunchedEffect(refresh) {
        loading = true
        val local = SportsChannelBridge.restoreCached(context)
        if (local.isNotEmpty()) channels = local
        channels = SportsChannelBridge.load(context, refresh > 0)
        runCatching { SportsSchedule.load(context, refresh > 0, channels) }
            .onSuccess { events = it }
        loading = false
    }

    fun play(url: String) {
        context.startActivity(Intent(context, RichPlayerActivity::class.java)
            .putExtra(RichPlayerActivity.EXTRA_URL, url))
    }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = Color(0xFFBFA6FF), secondary = Color(0xFF63D7FF),
        tertiary = Color(0xFFFF5FAF), background = Color(0xFF080A10),
        surface = Color(0xFF10131D)
    )) {
        Scaffold(containerColor = Color(0xFF080A10), bottomBar = {
            NavigationBar(containerColor = Color(0xFF0D1018)) {
                Nav.values().forEach { n ->
                    NavigationBarItem(
                        selected = nav == n,
                        onClick = { nav = n },
                        icon = { Icon(iconFor(n), null) },
                        label = { Text(n.name.lowercase().replaceFirstChar { it.uppercase() }) }
                    )
                }
            }
        }) { pad ->
            LazyColumn(
                Modifier.fillMaxSize().padding(pad),
                contentPadding = PaddingValues(bottom = 12.dp)
            ) {
                item { Header(nav.name, loading, { refresh++ }, { nav = Nav.SOURCES }) }
                when (nav) {
                    Nav.HOME -> home(events, channels, favs, ::play)
                    Nav.SPORTS -> sports(events, channels, sport, { sport = it }, favs, ::play)
                    Nav.LIVE -> live(channelIndex, favs, ::play)
                    Nav.FAV -> favorites(events, channels, favs, ::play)
                    Nav.SEARCH -> search(query, { query = it }, events, channelIndex, favs, ::play)
                    Nav.SOURCES -> sources(store) { refresh++ }
                }
            }
        }
    }
}

private fun iconFor(n: Nav) = when (n) {
    Nav.HOME -> Icons.Default.Home
    Nav.SPORTS -> Icons.Default.SportsScore
    Nav.LIVE -> Icons.Default.LiveTv
    Nav.FAV -> Icons.Default.Star
    Nav.SEARCH -> Icons.Default.Search
    Nav.SOURCES -> Icons.Default.SettingsInputAntenna
}

private fun bestChannel(e: SportsEvent, c: List<SportsChannel>): SportsChannel? = SportsChannelBridge.bestMatch(e, c)

private fun LazyListScope.home(e: List<SportsEvent>, c: List<SportsChannel>, f: Favs, play: (String) -> Unit) {
    item { Hero(e.firstOrNull { it.state == "in" } ?: e.firstOrNull(), c, play) }
    val live = SportsSchedule.liveEvents(e)
    if (live.isNotEmpty()) {
        item { Title("LIVE NOW", "${live.size} on air") }
        item { EventRail(live.take(10), c, f, play) }
    }
    item { Title("TODAY'S EVENTS", "Live schedule") }
    item { EventRail(SportsSchedule.upcomingEvents(e).take(16), c, f, play) }
    item { PremiumSectionHeader("SPORTS", "Explore") }
    item { PremiumBrandRail() }
    item { SportRail() }
    item { PremiumSectionHeader("DEDICATED HUBS", "WWE • AEW • TNA • ROH • MOTORSPORTS • TENNIS • GOLF") }
    item { SportsHubRail() }
    item { Title("LIVE TV", "Indexed channels") }
    item { ChannelRail(c.take(12), f, play) }
}

private fun LazyListScope.sports(e: List<SportsEvent>, c: List<SportsChannel>, sel: String, set: (String) -> Unit, f: Favs, play: (String) -> Unit) {
    item { SportRail(sel, set) }
    if (sel == "All") item { PremiumBrandRail() }
    if (sel == "Wrestling") item { SportsHubRail() }
    val x = SportsSchedule.forSport(e, sel)
    val live = SportsSchedule.liveEvents(x)
    if (live.isNotEmpty()) {
        item { Title("LIVE", "$sel now") }
        item { EventRail(live, c, f, play) }
    }
    item { Title("UPCOMING", "$sel schedule") }
    item { EventRail(SportsSchedule.upcomingEvents(x), c, f, play) }
}

private fun LazyListScope.live(index: ChannelIndex<SportsChannel>, f: Favs, play: (String) -> Unit) {
    val c = index.all()
    item { Title("LIVE TV", "${c.size} channels") }
    if (c.isEmpty()) item { Empty("No channels loaded", "Connect a source in Sources.") }
    else items(c, key = { it.id }) { ChannelRow(it, f.isChannelFav(it.id), { play(it.url) }, { f.toggleChannel(it.id) }) }
}

private fun LazyListScope.favorites(e: List<SportsEvent>, c: List<SportsChannel>, f: Favs, play: (String) -> Unit) {
    item { Title("FAVORITES", "Saved on this device") }
    val fe = e.filter { f.isEventFav(it.id) }
    val fc = c.filter { f.isChannelFav(it.id) }
    if (fe.isNotEmpty()) { item { Title("EVENTS", "Saved games") }; item { EventRail(fe, c, f, play) } }
    if (fc.isNotEmpty()) { item { Title("CHANNELS", "Saved channels") }; item { ChannelRail(fc, f, play) } }
    if (fe.isEmpty() && fc.isEmpty()) item { Empty("Nothing saved yet", "Star an event or channel to build Favorites.") }
}

private fun LazyListScope.search(q: String, set: (String) -> Unit, e: List<SportsEvent>, index: ChannelIndex<SportsChannel>, f: Favs, play: (String) -> Unit) {
    item { SearchBox(q, set) }
    if (q.isBlank()) {
        item { Text("Search teams • events • leagues • channels • sports", color = Color.Gray, modifier = Modifier.padding(horizontal = 16.dp)) }
    } else {
        val ev = e.filter { it.name.contains(q, true) || it.shortName.contains(q, true) }.take(20)
        val teams = e.flatMap { it.competitors }.distinct().filter { it.contains(q, true) }.take(12)
        val leagues = e.map { it.league }.distinct().filter { it.contains(q, true) }.take(12)
        val ch = index.search(q, 40)
        item { Results("TEAMS", teams) }
        item { Results("EVENTS", ev.map { SportsPresentation.matchup(it) }) }
        item { Results("LEAGUES", leagues) }
        item { Title("CHANNELS", "${ch.size} matches") }
        items(ch, key = { "search-${it.id}" }) { ChannelRow(it, f.isChannelFav(it.id), { play(it.url) }, { f.toggleChannel(it.id) }) }
    }
}

private fun LazyListScope.sources(s: SourceStore, done: () -> Unit) {
    item { Title("SOURCES", "Xtream Codes + M3U/M3U8") }
    item { Editor(s, done) }
    item { Text("Passwords remain local and are never bundled.", color = Color.Gray, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp)) }
}

@Composable
private fun Header(t: String, l: Boolean, r: () -> Unit, src: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("USPORTZ", fontSize = 29.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
            Text(t.uppercase(), fontSize = 11.sp, color = Color.Gray)
        }
        IconButton(r, enabled = !l) { Icon(Icons.Default.Refresh, "Refresh") }
        IconButton(src) { Icon(Icons.Default.SettingsInputAntenna, "Sources") }
    }
}

@Composable private fun Title(a: String, b: String) = Text("$a  $b", fontSize = 17.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 14.dp))

@Composable
private fun Hero(e: SportsEvent?, c: List<SportsChannel>, play: (String) -> Unit) {
    val height = if (e == null) 126.dp else 210.dp
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(height)
            .clip(RoundedCornerShape(26.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF30174D), Color(0xFF0B3141), Color(0xFF101522))))
    ) {
        Column(Modifier.fillMaxWidth().align(Alignment.BottomStart).padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.clip(RoundedCornerShape(7.dp)).background(Color(0xFFFF5FAF)).padding(horizontal = 8.dp, vertical = 4.dp)) {
                    Text(if (e?.state == "in") "LIVE" else "FEATURED", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Black)
                }
                if (e != null) Text("  ${SportsPresentation.status(e)}", color = Color.LightGray, fontSize = 10.sp)
            }
            Spacer(Modifier.height(6.dp))
            Text(e?.let { SportsPresentation.matchup(it) } ?: "Your sports command center", fontSize = if (e == null) 20.sp else 25.sp, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(e?.let { SportsPresentation.dateTime(it) }?.ifBlank { e.league } ?: "Schedules • live scores • channels", color = Color.LightGray, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val b = e?.let { bestChannel(it, c) }
            if (e != null && b != null) Button(onClick = { play(b.url) }, modifier = Modifier.padding(top = 7.dp), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 2.dp)) {
                Icon(Icons.Default.PlayArrow, null); Text(if (e.state == "in") "WATCH LIVE" else "WATCH")
            }
        }
    }
}

@Composable private fun EventRail(e: List<SportsEvent>, c: List<SportsChannel>, f: Favs, play: (String) -> Unit) = LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) { items(e, key = { it.id }) { EventCard(it, c, f, play) } }

private fun cleanBroadcast(value: String): String = value
    .replace("[", "").replace("]", "").replace("\"", "")
    .split(',').map { it.trim() }.filter { it.isNotBlank() }.distinct().joinToString(", ")

@Composable
private fun EventCard(e: SportsEvent, c: List<SportsChannel>, f: Favs, play: (String) -> Unit) {
    val b = bestChannel(e, c)
    val brand = SportsPresentation.brand(e)
    val broadcast = cleanBroadcast(e.broadcast)
    Card(Modifier.width(294.dp), shape = RoundedCornerShape(19.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF121522))) {
        Column(Modifier.padding(10.dp)) {
            EventArtwork(e, brand)
            Row(Modifier.padding(top = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(e.leagueLogo, e.league, Modifier.size(34.dp), contentScale = ContentScale.Fit)
                Column(Modifier.weight(1f).padding(horizontal = 9.dp)) {
                    Text(brand?.label ?: e.league, fontSize = 10.sp, color = Color(0xFF63D7FF), fontWeight = FontWeight.Bold)
                    Text(e.shortName.ifBlank { e.name }, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton({ f.toggleEvent(e.id) }, Modifier.size(34.dp)) { Icon(if (f.isEventFav(e.id)) Icons.Default.Star else Icons.Default.StarBorder, "Favorite") }
            }
            Text(SportsPresentation.dateTime(e), color = Color.LightGray, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 5.dp))
            Row(Modifier.padding(top = 7.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AssistChip(onClick = {}, label = { Text(if (e.state == "in") "LIVE" else if (e.state == "post") "FINAL" else "UPCOMING", fontSize = 9.sp) })
                if (broadcast.isNotBlank()) AssistChip(onClick = {}, label = { Text(broadcast, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) })
            }
            Row(Modifier.padding(top = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                e.competitorLogos.take(2).forEachIndexed { i, logo ->
                    if (logo.isNotBlank()) AsyncImage(logo, e.competitors.getOrNull(i).orEmpty(), Modifier.size(30.dp).clip(RoundedCornerShape(7.dp)), contentScale = ContentScale.Fit)
                    if (i == 0) Spacer(Modifier.width(5.dp))
                }
                Text(e.competitors.joinToString("  •  "), fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 5.dp).weight(1f))
            }
            Text(if (e.state == "in") e.detail.ifBlank { "Live now" } else e.detail.ifBlank { "Scheduled" }, color = if (e.state == "in") Color(0xFFFF5FAF) else Color.Gray, fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp))
            if (b != null) Button(onClick = { play(b.url) }, modifier = Modifier.fillMaxWidth().padding(top = 7.dp)) { Icon(Icons.Default.PlayArrow, null); Text(if (e.state == "in") "WATCH LIVE" else "WATCH") }
        }
    }
}

@Composable private fun SportRail(sel: String = "All", set: (String) -> Unit = {}) { val categories = SportsCatalog.categories; LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) { items(categories, key = { it }) { category -> FilterChip(selected = sel == category, onClick = { set(category) }, label = { Text(category) }) } } }

@Composable private fun SportsHubRail() { val context = LocalContext.current; LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) { items(SportsHubCatalog.hubs, key = { it.key }) { hub -> Card(onClick = { context.startActivity(Intent(context, SportsHubActivity::class.java).putExtra(SportsHubActivity.EXTRA_HUB, hub.key)) }, modifier = Modifier.width(190.dp).height(116.dp), shape = RoundedCornerShape(17.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF17121F))) { Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.Center) { Text(hub.title, fontSize = 20.sp, fontWeight = FontWeight.Black); Text(hub.subtitle, fontSize = 10.sp, color = Color.Gray, maxLines = 2, overflow = TextOverflow.Ellipsis); Text("OPEN HUB →", fontSize = 9.sp, color = Color(0xFF63D7FF), fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 9.dp)) } } } } }

@Composable private fun ChannelRail(c: List<SportsChannel>, f: Favs, play: (String) -> Unit) = LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(c, key = { it.id }) { x -> ChannelRow(x, f.isChannelFav(x.id), { play(x.url) }, { f.toggleChannel(x.id) }) } }

@Composable private fun ChannelRow(c: SportsChannel, f: Boolean, play: () -> Unit, fav: () -> Unit) { Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp), shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF111624))) { Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) { AsyncImage(c.logo, c.name, Modifier.size(48.dp), contentScale = ContentScale.Fit); Column(Modifier.weight(1f).padding(horizontal = 9.dp)) { Text(c.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(c.group, color = Color.Gray, fontSize = 10.sp) }; IconButton(fav) { Icon(if (f) Icons.Default.Star else Icons.Default.StarBorder, "Favorite") }; FilledTonalButton(onClick = play) { Icon(Icons.Default.PlayArrow, null) } } } }

@Composable private fun SearchBox(v: String, set: (String) -> Unit) = OutlinedTextField(value = v, onValueChange = set, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), label = { Text("Search everything") }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true, shape = RoundedCornerShape(15.dp))
@Composable private fun Results(t: String, v: List<String>) { if (v.isNotEmpty()) Column { Title(t, "${v.size} matches"); v.forEach { x -> Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)) { Text(x, Modifier.padding(13.dp)) } } } }
