package com.usportz.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId

class RichSportsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { RichSportsApp() } }
    fun openSourceApp() = startActivity(Intent(this, SourceActivity::class.java))
    fun playChannel(channel: SportsChannel) = startActivity(Intent(this, RichPlayerActivity::class.java).putExtra(RichPlayerActivity.EXTRA_URL, channel.url))
    fun playEvent(event: SportsEvent) { if (isMonsterJamEvent(event)) openMonsterJamYouTube(this) }
}

private val Ink = Color(0xFF080A12)
private val Panel = Color(0xFF121522)
private val Panel2 = Color(0xFF181B2A)
private val Orange = Color(0xFFFF6A00)
private val Orange2 = Color(0xFFFF9A3D)
private val Cyan = Color(0xFF14D9FF)
private val LiveRed = Color(0xFFFF335C)

enum class ScheduleBucket(val label: String) { LIVE("LIVE NOW"), STARTING_SOON("STARTING SOON"), TODAY("TODAY"), TOMORROW("TOMORROW"), NEXT_3_DAYS("NEXT 3 DAYS"), COMPLETED("COMPLETED") }

@Composable private fun RichSportsApp() {
    val activity = androidx.compose.ui.platform.LocalContext.current as RichSportsActivity
    var events by remember { mutableStateOf(emptyList<SportsEvent>()) }
    var channels by remember { mutableStateOf(emptyList<SportsChannel>()) }
    var selectedSport by remember { mutableStateOf("All") }
    var bucket by remember { mutableStateOf(ScheduleBucket.LIVE) }
    var tab by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var refresh by remember { mutableIntStateOf(0) }
    var selectedEvent by remember { mutableStateOf<SportsEvent?>(null) }
    val favorites = remember { mutableStateMapOf<String, Boolean>() }

    suspend fun reload(force: Boolean) {
        if (force) refreshing = true else loading = true
        error = ""
        try {
            val (loadedChannels, loadedEvents) = coroutineScope {
                val c = async(Dispatchers.IO) { SportsChannelBridge.load(activity, force) }
                // Only force the schedule when the user explicitly refreshes. The normal
                // 30-second loop uses its 2-minute cache and background enrichment instead
                // of hammering 20+ schedule endpoints every tick.
                val e = async(Dispatchers.IO) { (SportsSchedule.load(force) + MonsterJamSchedule.load()).distinctBy { it.id } }
                c.await() to e.await()
            }
            channels = loadedChannels
            events = loadedEvents
            if (loadedEvents.isNotEmpty()) withContext(Dispatchers.IO) { SportsScheduleDiskCache.write(activity, loadedEvents) }
        } catch (t: Throwable) {
            error = t.message?.takeIf { it.isNotBlank() } ?: "Unable to refresh sports data"
        } finally { loading = false; refreshing = false; now = System.currentTimeMillis() }
    }

    LaunchedEffect(Unit) {
        val saved = withContext(Dispatchers.IO) { SportsScheduleDiskCache.read(activity) }
        if (saved.isNotEmpty()) { events = saved; loading = false; now = System.currentTimeMillis() }
        reload(false)
        while (true) { delay(30_000); reload(false) }
    }
    LaunchedEffect(refresh) { if (refresh > 0) reload(true) }

    val visibleEvents = remember(events, selectedSport, bucket, now) {
        SportsSchedule.forSport(events, selectedSport)
            .filter { scheduleBucket(it, now) == bucket }
            .sortedBy { eventEpoch(it.startTime) ?: Long.MAX_VALUE }
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = Orange, secondary = Cyan, background = Ink, surface = Panel)) {
        Scaffold(containerColor = Ink, bottomBar = { BottomBar(tab) { tab = it } }) { pad ->
            Box(Modifier.fillMaxSize().padding(pad)) {
                when (tab) {
                    0 -> HomeTab(events, channels, selectedSport, now, loading, refreshing, error, { selectedSport = it }, { refresh++ }, activity::playChannel, activity::playEvent) { selectedEvent = it }
                    1 -> SportsTab(visibleEvents, events, channels, selectedSport, bucket, now, { selectedSport = it }, { bucket = it }, { refresh++ }, activity::playChannel, activity::playEvent, favorites) { selectedEvent = it }
                    2 -> SportsNewsTab(channels, loading, activity::playChannel)
                    3 -> FavoritesTab(events, channels, favorites, activity::playChannel, activity::playEvent) { selectedEvent = it }
                    4 -> SourcesTab { activity.openSourceApp() }
                }
            }
        }
        selectedEvent?.let { event -> EventSourcesDialog(event, channels, activity::playChannel) { selectedEvent = null } }
    }
}

@Composable private fun HomeTab(events: List<SportsEvent>, channels: List<SportsChannel>, selectedSport: String, now: Long, loading: Boolean, refreshing: Boolean, error: String, onSport: (String) -> Unit, onRefresh: () -> Unit, play: (SportsChannel) -> Unit, playEvent: (SportsEvent) -> Unit, openEvent: (SportsEvent) -> Unit) {
    val filtered = SportsSchedule.forSport(events, selectedSport)
    val live = filtered.filter { scheduleBucket(it, now) == ScheduleBucket.LIVE }
    val soon = filtered.filter { scheduleBucket(it, now) == ScheduleBucket.STARTING_SOON }
    val today = filtered.filter { scheduleBucket(it, now) == ScheduleBucket.TODAY }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Header(refreshing, onRefresh) }
        item { Hero(channels.size, live.size, soon.size, today.size, error) }
        item { SportRail(selectedSport, onSport) }
        item { SectionTitle("LIVE NOW", "${live.size} events", LiveRed) }
        if (live.isEmpty()) item { EmptyCard("No live games detected", "The live schedule refreshes independently of your IPTV playlist.") }
        else items(live.take(20), key = { "live-${it.id}" }) { EventCard(it, true, channels, now, play, playEvent, openEvent) }
        item { SectionTitle("STARTING SOON", "${soon.size} events", Cyan) }
        if (soon.isEmpty()) item { EmptyCard("No events starting soon", "Events within the next six hours appear here.") }
        else items(soon.take(20), key = { "soon-${it.id}" }) { EventCard(it, false, channels, now, play, playEvent, openEvent) }
        item { SectionTitle("TODAY", "${today.size} events", Orange) }
        if (today.isEmpty()) item { EmptyCard("No more events today", "Try another sport or check TOMORROW.") }
        else items(today.take(30), key = { "today-${it.id}" }) { EventCard(it, false, channels, now, play, playEvent, openEvent) }
        if (loading) item { Text("Loading live schedule…", color = Color.Gray, modifier = Modifier.padding(18.dp)) }
    }
}

@Composable private fun SportsTab(visible: List<SportsEvent>, allEvents: List<SportsEvent>, channels: List<SportsChannel>, selectedSport: String, bucket: ScheduleBucket, now: Long, onSport: (String) -> Unit, onBucket: (ScheduleBucket) -> Unit, onRefresh: () -> Unit, play: (SportsChannel) -> Unit, playEvent: (SportsEvent) -> Unit, favorites: MutableMap<String, Boolean>, openEvent: (SportsEvent) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("SPORTS", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Black); Text("Tap any game to see every matched source", color = Color(0xFF9DA5B7), fontSize = 13.sp) }; IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "Refresh", tint = Orange) } } }
        item { SportRail(selectedSport, onSport) }
        item { ScheduleRail(bucket, onBucket, SportsSchedule.forSport(allEvents, selectedSport), now) }
        item { SectionTitle(bucket.label, "${visible.size} events", if (bucket == ScheduleBucket.LIVE) LiveRed else Orange) }
        if (visible.isEmpty()) item { EmptyCard(emptyTitle(bucket), emptySubtitle(bucket)) }
        else items(visible.take(60), key = { "schedule-${bucket.name}-${it.id}" }) { EventCard(it, bucket == ScheduleBucket.LIVE, channels, now, play, playEvent, openEvent, favorites) }
    }
}

@Composable private fun EventCard(event: SportsEvent, live: Boolean, channels: List<SportsChannel>, now: Long, play: (SportsChannel) -> Unit, playEvent: (SportsEvent) -> Unit, openEvent: (SportsEvent) -> Unit, favorites: MutableMap<String, Boolean>? = null) {
    val ranked by produceState<List<SportsResolver.WatchSource>>(emptyList(), event.id, channels) {
        value = withContext(Dispatchers.Default) { SportsResolver.resolve(event, channels, 16) }
    }
    val brand = SportsBranding.find(event.name, event.league)
    val leagueLogo = event.leagueLogo?.takeIf { it.isNotBlank() } ?: BrandAssets.logoUrl(brand)
    val start = eventEpoch(event.startTime)
    val countdown = if (start != null && start > now) formatCountdown(start - now) else ""
    val isFavorite = favorites?.get(event.id) == true
    val monsterJam = isMonsterJamEvent(event)
    Card(Modifier.fillMaxWidth().clickable { openEvent(event) }, colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(15.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (live) { Box(Modifier.size(9.dp).background(LiveRed, RoundedCornerShape(50))); Spacer(Modifier.width(7.dp)); Text("LIVE NOW", color = LiveRed, fontSize = 10.sp, fontWeight = FontWeight.Black) }
                else Text("UPCOMING", color = Orange, fontSize = 10.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.width(9.dp)); Text(brand?.label ?: event.league, color = Color.Gray, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.weight(1f)); if (!live && countdown.isNotBlank()) Text("IN $countdown", color = Orange, fontSize = 10.sp, fontWeight = FontWeight.Black)
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!leagueLogo.isNullOrBlank()) AsyncImage(leagueLogo, brand?.label ?: event.league, Modifier.size(44.dp), contentScale = ContentScale.Fit)
                Column(Modifier.weight(1f).padding(start = 10.dp)) { Text(SportsPresentation.matchup(event), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis); Text(event.detail.ifBlank { formatClock(event.startTime) }, color = Color(0xFF9DA5B7), fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)) }
                if (favorites != null) IconButton(onClick = { favorites[event.id] = !isFavorite }) { Icon(if (isFavorite) Icons.Default.Star else Icons.Default.StarBorder, "Favorite", tint = if (isFavorite) Orange else Color.Gray) }
            }
            if (event.competitorLogos.any { it.isNotBlank() }) Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) { event.competitorLogos.take(2).forEachIndexed { i, logo -> if (logo.isNotBlank()) AsyncImage(logo, event.competitors.getOrNull(i).orEmpty(), Modifier.size(32.dp), contentScale = ContentScale.Fit) }; Text(event.competitors.take(2).joinToString("  •  "), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (ranked.isEmpty()) "MATCHING SOURCES…" else "${ranked.size} AVAILABLE SOURCES", color = if (ranked.isEmpty()) Color.Gray else Orange, fontSize = 10.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                Text("TAP FOR SOURCES", color = Color(0xFF9DA5B7), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(5.dp)); Icon(Icons.Default.ChevronRight, null, tint = Orange)
            }
            if (monsterJam) OutlinedButton(onClick = { playEvent(event) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("WATCH OFFICIAL MONSTER JAM STREAM", color = Orange, fontWeight = FontWeight.Black) }
            if (ranked.isNotEmpty()) { val best = ranked.first(); Text("BEST: ${best.channel.name} • ${best.score}%", color = Color(0xFFB8BECC), fontSize = 10.sp, modifier = Modifier.padding(top = 5.dp), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable private fun EventSourcesDialog(event: SportsEvent, channels: List<SportsChannel>, play: (SportsChannel) -> Unit, close: () -> Unit) {
    val ranked by produceState<List<SportsResolver.WatchSource>>(emptyList(), event.id, channels) {
        value = withContext(Dispatchers.Default) { SportsResolver.resolve(event, channels, 16) }
    }
    AlertDialog(onDismissRequest = close, containerColor = Panel2, title = { Column { Text(SportsPresentation.matchup(event), color = Color.White, fontWeight = FontWeight.Black); Text("${ranked.size} matched IPTV sources", color = Orange2, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp)) } }, text = {
        if (ranked.isEmpty()) Column { Text("GAME FOUND — NO CHANNEL MATCH", color = Orange2, fontWeight = FontWeight.Black); Text("No strong provider match was found yet. Try refreshing the sports source.", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)) }
        else LazyColumn(Modifier.heightIn(max = 430.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) { items(ranked, key = { "source-${it.channel.url}-${it.channel.name}" }) { match ->
            OutlinedButton(onClick = { close(); play(match.channel) }, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)) {
                Icon(Icons.Default.PlayArrow, null, tint = Orange); Spacer(Modifier.width(6.dp)); Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) { Text("${match.score}% MATCH", color = if (match.score >= 90) Orange else Color.White, fontSize = 11.sp, fontWeight = FontWeight.Black); Text(match.channel.name, color = Color(0xFFB8BECC), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(match.reasons.take(2).joinToString(" • "), color = Color.Gray, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }; Icon(Icons.Default.ChevronRight, null, tint = Color.Gray)
            }
        } }
    }, confirmButton = { TextButton(onClick = close) { Text("CLOSE", color = Orange) } })
}

@Composable private fun SportsNewsTab(channels: List<SportsChannel>, loading: Boolean, play: (SportsChannel) -> Unit) {
    val sports = remember(channels) { SportsNetworkCatalog.sportsChannels(channels) }
    val grouped = remember(sports) { sports.groupBy { it.second.key } }
    val networks = remember(sports) { sports.map { it.second }.distinctBy { it.key } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("SPORTS TV", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Black); Text("US + Canada sports networks", color = Orange2, fontSize = 13.sp, fontWeight = FontWeight.Bold); Text(if (loading) "Finding sports networks in your source…" else "${sports.size} sports channels found", color = Color.Gray, fontSize = 12.sp) }
        if (!loading && sports.isEmpty()) item { EmptyCard("No sports networks found", "Add a source containing ESPN, TSN, Sportsnet, ACC Network or another supported sports network.") }
        items(networks, key = { "network-${it.key}" }) { network -> val matches = grouped[network.key].orEmpty(); Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) { Column(Modifier.padding(14.dp)) { Row(verticalAlignment = Alignment.CenterVertically) { AsyncImage(network.logoUrl, network.label, Modifier.size(48.dp), contentScale = ContentScale.Fit); Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(network.label, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold); Text("${matches.size} channel${if (matches.size == 1) "" else "s"}", color = Color.Gray, fontSize = 11.sp) } }; Spacer(Modifier.height(8.dp)); matches.take(8).forEach { (channel, _) -> OutlinedButton(onClick = { play(channel) }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text(channel.name, color = Color.White, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis); Icon(Icons.Default.ChevronRight, null, tint = Color.Gray) } } } } }
    }
}

@Composable private fun FavoritesTab(events: List<SportsEvent>, channels: List<SportsChannel>, favorites: MutableMap<String, Boolean>, play: (SportsChannel) -> Unit, playEvent: (SportsEvent) -> Unit, openEvent: (SportsEvent) -> Unit) { val saved = events.filter { favorites[it.id] == true }; LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { item { Text("FAVORITES", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Black) }; if (saved.isEmpty()) item { EmptyCard("Nothing saved yet", "Star an event to keep it here.") } else items(saved, key = { "fav-${it.id}" }) { EventCard(it, it.state == "in", channels, System.currentTimeMillis(), play, playEvent, openEvent, favorites) } } }
@Composable private fun SourcesTab(open: () -> Unit) { Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { Text("SOURCES", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Black); Text("Xtream Codes + M3U/M3U8", color = Color.Gray); Card(Modifier.fillMaxWidth().clickable { open() }, colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) { Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.SettingsInputAntenna, null, tint = Orange); Spacer(Modifier.width(14.dp)); Column(Modifier.weight(1f)) { Text("Manage source", color = Color.White, fontWeight = FontWeight.Bold); Text("Connect, test and index your playlist", color = Color.Gray, fontSize = 12.sp) }; Icon(Icons.Default.ChevronRight, null, tint = Color.Gray) } } } }
@Composable private fun BottomBar(selected: Int, onSelect: (Int) -> Unit) { NavigationBar(containerColor = Color(0xFF11131D)) { listOf(Icons.Default.Home to "Home", Icons.Default.SportsScore to "Sports", Icons.Default.LiveTv to "Sports TV", Icons.Default.Star to "Favorites", Icons.Default.Settings to "Sources").forEachIndexed { i, item -> NavigationBarItem(selected = selected == i, onClick = { onSelect(i) }, icon = { Icon(item.first, item.second) }, label = { Text(item.second) }) } } }
@Composable private fun Header(refreshing: Boolean, onRefresh: () -> Unit) { Row(Modifier.fillMaxWidth().background(Panel2).padding(18.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("USPORTZ", fontSize = 31.sp, fontWeight = FontWeight.Black, color = Color.White); Text("SPORTS COMMAND CENTER", fontSize = 11.sp, color = Orange2, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp) }; IconButton(onClick = onRefresh, enabled = !refreshing) { Icon(if (refreshing) Icons.Default.Sync else Icons.Default.Refresh, "Refresh", tint = if (refreshing) Color.Gray else Orange) } } }
@Composable private fun Hero(channels: Int, live: Int, soon: Int, today: Int, error: String) { Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Panel2)) { Column(Modifier.padding(20.dp)) { Text("LIVE SPORTS", color = Orange, fontSize = 12.sp, fontWeight = FontWeight.Black); Text("Tap a game to choose a source.", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(top = 5.dp)); Text("$live live  •  $soon starting soon  •  $today later today  •  $channels channels", color = Color(0xFF9DA5B7), fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)); if (error.isNotBlank()) Text("Data warning: $error", color = Orange2, fontSize = 10.sp, modifier = Modifier.padding(top = 7.dp)) } } }
@Composable private fun SportRail(selected: String, onSport: (String) -> Unit) { LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(SportsCatalog.categories, key = { it }) { sport -> FilterChip(selected = selected == sport, onClick = { onSport(sport) }, label = { Text(sport) }) } } }
@Composable private fun ScheduleRail(selected: ScheduleBucket, onSelected: (ScheduleBucket) -> Unit, events: List<SportsEvent>, now: Long) { LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) { items(ScheduleBucket.values().toList(), key = { it.name }) { item -> FilterChip(selected = selected == item, onClick = { onSelected(item) }, label = { Text("${item.label}  ${events.count { scheduleBucket(it, now) == item }}") }) } } }
@Composable private fun SectionTitle(title: String, detail: String, accent: Color) { Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) { Box(Modifier.width(4.dp).height(24.dp).background(accent, RoundedCornerShape(3.dp))); Spacer(Modifier.width(9.dp)); Column(Modifier.weight(1f)) { Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Black); Text(detail, color = Color.Gray, fontSize = 11.sp) } } }
@Composable private fun EmptyCard(title: String, subtitle: String) { Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) { Column(Modifier.padding(18.dp)) { Text(title, color = Color.White, fontWeight = FontWeight.Bold); Text(subtitle, color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)) } } }
private fun emptyTitle(bucket: ScheduleBucket) = when (bucket) { ScheduleBucket.LIVE -> "No live games detected"; ScheduleBucket.STARTING_SOON -> "No events starting soon"; ScheduleBucket.TODAY -> "No more events today"; ScheduleBucket.TOMORROW -> "Nothing scheduled tomorrow"; ScheduleBucket.NEXT_3_DAYS -> "No events in the next three days"; ScheduleBucket.COMPLETED -> "No completed events" }
private fun emptySubtitle(bucket: ScheduleBucket) = when (bucket) { ScheduleBucket.LIVE -> "The live schedule is checked independently of your Xtream playlist."; ScheduleBucket.STARTING_SOON -> "Events within the next six hours appear here."; ScheduleBucket.TODAY -> "Try another sport or check TOMORROW."; ScheduleBucket.TOMORROW -> "The schedule will update as events are published."; ScheduleBucket.NEXT_3_DAYS -> "Try another sport or refresh."; ScheduleBucket.COMPLETED -> "Finished events remain available for reference." }
private fun eventEpoch(value: String): Long? = runCatching { Instant.parse(value).toEpochMilli() }.getOrNull() ?: runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrNull() ?: value.toLongOrNull()?.let { if (it < 10_000_000_000L) it * 1000 else it }
private fun scheduleBucket(event: SportsEvent, nowMs: Long): ScheduleBucket { val start = eventEpoch(event.startTime) ?: return if (event.state == "in") ScheduleBucket.LIVE else ScheduleBucket.TODAY; val delta = start - nowMs; val today = Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault()).toLocalDate(); val eventDay = Instant.ofEpochMilli(start).atZone(ZoneId.systemDefault()).toLocalDate(); return when { event.state == "in" -> ScheduleBucket.LIVE; event.state != "post" && delta <= 0 && delta > -6 * 60 * 60 * 1000L -> ScheduleBucket.LIVE; delta > 0 && delta <= 6 * 60 * 60 * 1000L -> ScheduleBucket.STARTING_SOON; eventDay == today -> ScheduleBucket.TODAY; eventDay == today.plusDays(1) -> ScheduleBucket.TOMORROW; eventDay.isAfter(today) && eventDay <= today.plusDays(3) -> ScheduleBucket.NEXT_3_DAYS; start < nowMs -> ScheduleBucket.COMPLETED; else -> ScheduleBucket.NEXT_3_DAYS } }
private fun formatCountdown(ms: Long): String { val total = (ms / 1000).coerceAtLeast(0); val h = total / 3600; val m = (total % 3600) / 60; return if (h > 0) "${h}h ${m}m" else "${m}m" }
private fun formatClock(value: String): String = runCatching { java.time.format.DateTimeFormatter.ofPattern("h:mm a").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(eventEpoch(value) ?: 0)) }.getOrDefault(value)
