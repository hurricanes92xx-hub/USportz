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
import androidx.compose.ui.graphics.Brush
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
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

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

@Composable
private fun RichSportsApp() {
    val activity = androidx.compose.ui.platform.LocalContext.current as RichSportsActivity
    var events by remember { mutableStateOf(emptyList<SportsEvent>()) }
    var channels by remember { mutableStateOf(emptyList<SportsChannel>()) }
    var selectedSport by remember { mutableStateOf("All") }
    var bucket by remember { mutableStateOf(ScheduleBucket.LIVE) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var refresh by remember { mutableIntStateOf(0) }
    var tab by remember { mutableIntStateOf(0) }
    val favorites = remember { mutableStateMapOf<String, Boolean>() }

    suspend fun reload(force: Boolean) {
        if (force) refreshing = true else loading = true
        error = ""
        try {
            // IPTV indexing and the sports schedule are independent. Never make a large
            // Xtream playlist determine whether the live-games dashboard can appear.
            val (loadedChannels, loadedEvents) = coroutineScope {
                val channelsDeferred = async(Dispatchers.IO) { SportsChannelBridge.load(activity, force) }
                val eventsDeferred = async(Dispatchers.IO) {
                    (SportsSchedule.load(force) + MonsterJamSchedule.load()).distinctBy { it.id }
                }
                channelsDeferred.await() to eventsDeferred.await()
            }
            channels = loadedChannels
            // Re-rank against the newly available local channel snapshot without forcing
            // another network schedule fetch. The schedule itself already loaded in parallel.
            events = if (loadedChannels.isEmpty()) loadedEvents else {
                withContext(Dispatchers.Default) { SportsSchedule.load(false, loadedChannels) }
                    .ifEmpty { loadedEvents }
            }
        } catch (t: Throwable) {
            error = t.message?.takeIf { it.isNotBlank() } ?: "Unable to refresh sports data"
        } finally {
            loading = false
            refreshing = false
        }
    }

    LaunchedEffect(Unit) {
        reload(false)
        while (true) {
            delay(1_000)
            now = System.currentTimeMillis()
            if (now % 60_000L < 1_100L) reload(true)
        }
    }
    LaunchedEffect(refresh) { if (refresh > 0) reload(true) }

    val visibleEvents = remember(events, selectedSport, bucket, now) {
        SportsSchedule.forSport(events, selectedSport)
            .filter { scheduleBucket(it, now) == bucket }
            .sortedBy { parseInstant(it.startTime)?.toEpochMilli() ?: Long.MAX_VALUE }
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = Orange, secondary = Cyan, background = Ink, surface = Panel)) {
        Scaffold(containerColor = Ink, bottomBar = { BottomBar(tab) { tab = it } }) { pad ->
            Box(Modifier.fillMaxSize().padding(pad)) {
                when (tab) {
                    0 -> HomeTab(events, channels, selectedSport, now, loading, refreshing, error, { selectedSport = it }, { refresh++ }, activity::playChannel, activity::playEvent)
                    1 -> SportsTab(visibleEvents, events, channels, selectedSport, bucket, now, { selectedSport = it }, { bucket = it }, { refresh++ }, activity::playChannel, activity::playEvent, favorites)
                    2 -> SportsNewsTab(channels, loading, activity::playChannel)
                    3 -> FavoritesTab(events, channels, favorites, activity::playChannel, activity::playEvent)
                    4 -> SourcesTab { activity.openSourceApp() }
                }
            }
        }
    }
}

@Composable
private fun HomeTab(events: List<SportsEvent>, channels: List<SportsChannel>, selectedSport: String, now: Long, loading: Boolean, refreshing: Boolean, error: String, onSport: (String) -> Unit, onRefresh: () -> Unit, play: (SportsChannel) -> Unit, playEvent: (SportsEvent) -> Unit) {
    val filtered = SportsSchedule.forSport(events, selectedSport)
    val live = filtered.filter { scheduleBucket(it, now) == ScheduleBucket.LIVE }
    val soon = filtered.filter { scheduleBucket(it, now) == ScheduleBucket.STARTING_SOON }
    val today = filtered.filter { scheduleBucket(it, now) == ScheduleBucket.TODAY }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Header(refreshing, onRefresh) }
        item { Hero(channels.size, live.size, soon.size, today.size, error) }
        item { SportRail(selectedSport, onSport) }
        item { SectionTitle("LIVE NOW", "${live.size} events", LiveRed) }
        if (live.isEmpty()) item { EmptyCard("No live games detected", "The live schedule is checked independently of your Xtream playlist and refreshes automatically.") }
        else items(live.take(20), key = { "live-${it.id}" }) { EventCard(it, true, channels, now, play, playEvent) }
        item { SectionTitle("STARTING SOON", "${soon.size} events", Cyan) }
        if (soon.isEmpty()) item { EmptyCard("No events starting soon", "Events within the next six hours appear here.") }
        else items(soon.take(20), key = { "soon-${it.id}" }) { EventCard(it, false, channels, now, play, playEvent) }
        item { SectionTitle("TODAY", "${today.size} events", Orange) }
        if (today.isEmpty()) item { EmptyCard("No more events today", "Try another sport or check TOMORROW for the next slate.") }
        else items(today.take(30), key = { "today-${it.id}" }) { EventCard(it, false, channels, now, play, playEvent) }
        if (loading) item { Text("Loading live schedule…", color = Color.Gray, modifier = Modifier.padding(18.dp)) }
    }
}

@Composable
private fun Header(refreshing: Boolean, onRefresh: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(Color(0xFF201306), Color(0xFF17100D), Ink))).padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text("USPORTZ", fontSize = 31.sp, fontWeight = FontWeight.Black, color = Color.White); Text("SPORTS COMMAND CENTER", fontSize = 11.sp, color = Orange2, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp) }
        IconButton(onClick = onRefresh, enabled = !refreshing) { Icon(if (refreshing) Icons.Default.Sync else Icons.Default.Refresh, "Refresh", tint = if (refreshing) Color.Gray else Orange) }
    }
}

@Composable
private fun Hero(channels: Int, live: Int, soon: Int, today: Int, error: String) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Panel2)) { Column(Modifier.padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(9.dp).background(Orange, RoundedCornerShape(50))); Spacer(Modifier.width(8.dp)); Text("LIVE SPORTS", color = Orange, fontSize = 12.sp, fontWeight = FontWeight.Black) }
        Text("Everything worth watching.", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(top = 5.dp))
        Text("$live live now  •  $soon starting soon  •  $today later today  •  $channels channels", color = Color(0xFF9DA5B7), fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
        Text("Live schedule refreshes automatically every 60 seconds.", color = Color.Gray, fontSize = 11.sp, modifier = Modifier.padding(top = 7.dp))
        if (error.isNotBlank()) Text("Data warning: $error", color = Orange2, fontSize = 10.sp, modifier = Modifier.padding(top = 7.dp))
    } }
}

@Composable
private fun SportsTab(visible: List<SportsEvent>, allEvents: List<SportsEvent>, channels: List<SportsChannel>, selectedSport: String, bucket: ScheduleBucket, now: Long, onSport: (String) -> Unit, onBucket: (ScheduleBucket) -> Unit, onRefresh: () -> Unit, play: (SportsChannel) -> Unit, playEvent: (SportsEvent) -> Unit, favorites: MutableMap<String, Boolean>) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("SPORTS", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Black); Text("Accurate live and upcoming schedules", color = Color(0xFF9DA5B7), fontSize = 13.sp) }; IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "Refresh", tint = Orange) } } }
        item { SportRail(selectedSport, onSport) }
        item { ScheduleRail(bucket, onBucket, SportsSchedule.forSport(allEvents, selectedSport), now) }
        item { SectionTitle(bucket.label, "${visible.size} events", if (bucket == ScheduleBucket.LIVE) LiveRed else Orange) }
        if (visible.isEmpty()) item { EmptyCard(emptyTitle(bucket), emptySubtitle(bucket)) }
        else items(visible.take(60), key = { "schedule-${bucket.name}-${it.id}" }) { EventCard(it, bucket == ScheduleBucket.LIVE, channels, now, play, playEvent, favorites) }
    }
}

@Composable
private fun EventCard(event: SportsEvent, live: Boolean, channels: List<SportsChannel>, now: Long, play: (SportsChannel) -> Unit, playEvent: (SportsEvent) -> Unit, favorites: MutableMap<String, Boolean>? = null) {
    val ranked = remember(event.id, channels) { GameSourceMatcher.rankMatches(event, channels, limit = 3) }
    val brand = SportsBranding.find(event.name, event.league)
    val leagueLogo = event.leagueLogo?.takeIf { it.isNotBlank() } ?: BrandAssets.logoUrl(brand)
    val start = parseInstant(event.startTime)?.toEpochMilli()
    val countdown = if (start != null && start > now) formatCountdown(start - now) else ""
    val isFavorite = favorites?.get(event.id) == true
    val monsterJam = isMonsterJamEvent(event)
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) { Column(Modifier.padding(15.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (live) { Box(Modifier.size(9.dp).background(LiveRed, RoundedCornerShape(50))); Spacer(Modifier.width(7.dp)); Text("LIVE NOW", color = LiveRed, fontSize = 10.sp, fontWeight = FontWeight.Black) }
            else Text("UPCOMING", color = Orange, fontSize = 10.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.width(9.dp)); Text(brand?.label ?: event.league, color = Color.Gray, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis); Spacer(Modifier.weight(1f))
            if (!live && countdown.isNotBlank()) Text("IN $countdown", color = Orange, fontSize = 10.sp, fontWeight = FontWeight.Black)
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!leagueLogo.isNullOrBlank()) AsyncImage(leagueLogo, brand?.label ?: event.league, Modifier.size(44.dp), contentScale = ContentScale.Fit)
            Column(Modifier.weight(1f).padding(start = 10.dp)) { Text(SportsPresentation.matchup(event), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis); Text(event.detail.ifBlank { formatClock(event.startTime) }, color = Color(0xFF9DA5B7), fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)) }
            if (favorites != null) IconButton(onClick = { favorites[event.id] = !isFavorite }) { Icon(if (isFavorite) Icons.Default.Star else Icons.Default.StarBorder, "Favorite", tint = if (isFavorite) Orange else Color.Gray) }
        }
        if (event.competitorLogos.any { it.isNotBlank() }) Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            event.competitorLogos.take(2).forEachIndexed { i, logo -> if (logo.isNotBlank()) AsyncImage(logo, event.competitors.getOrNull(i).orEmpty(), Modifier.size(32.dp), contentScale = ContentScale.Fit) }
            Text(event.competitors.take(2).joinToString("  •  "), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (monsterJam) {
            OutlinedButton(onClick = { playEvent(event) }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                Icon(Icons.Default.PlayArrow, null, tint = Orange); Spacer(Modifier.width(7.dp)); Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) { Text(if (live) "WATCH LIVE • YOUTUBE" else "WATCH ON YOUTUBE", color = Orange, fontSize = 11.sp, fontWeight = FontWeight.Black); Text("Official Monster Jam stream", color = Color(0xFFB8BECC), fontSize = 11.sp) }; Icon(Icons.Default.OpenInNew, null, tint = Color.Gray)
            }
        }
        if (ranked.isNotEmpty()) Column(Modifier.fillMaxWidth().padding(top = if (monsterJam) 7.dp else 10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            ranked.forEachIndexed { index, match -> OutlinedButton(onClick = { play(match.channel) }, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp)) {
                Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp)); Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) { Text(if (index == 0) "STREAM 1 • BEST MATCH" else "STREAM ${index + 1}", color = if (index == 0) Orange else Color.White, fontSize = 11.sp, fontWeight = FontWeight.Black); Text(match.channel.name, color = Color(0xFFB8BECC), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }; Icon(Icons.Default.ChevronRight, null, tint = Color.Gray)
            } }
        }
    } }
}

@Composable
private fun SportsNewsTab(channels: List<SportsChannel>, loading: Boolean, play: (SportsChannel) -> Unit) {
    val sportsChannels = remember(channels) { SportsNetworkCatalog.sportsChannels(channels) }
    val grouped = remember(sportsChannels) { sportsChannels.groupBy { it.second.key } }
    val networks = remember(sportsChannels) { sportsChannels.map { it.second }.distinctBy { it.key } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("SPORTS TV", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Black)
            Text("US + Canada sports networks", color = Orange2, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(if (loading) "Finding sports networks in your source…" else "${sportsChannels.size} sports channels found", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
        }
        if (!loading && sportsChannels.isEmpty()) item { EmptyCard("No sports networks found", "Add a source containing ESPN, TSN, Sportsnet, ACC Network or another supported sports network.") }
        items(networks, key = { "network-${it.key}" }) { network ->
            val matches = grouped[network.key].orEmpty()
            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) { Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { AsyncImage(network.logoUrl, network.label, Modifier.size(48.dp), contentScale = ContentScale.Fit); Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(network.label, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold); Text("${matches.size} channel${if (matches.size == 1) "" else "s"}", color = Color.Gray, fontSize = 11.sp) } }
                Spacer(Modifier.height(8.dp))
                matches.take(8).forEach { (channel, _) -> OutlinedButton(onClick = { play(channel) }, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp)) { Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text(channel.name, color = Color.White, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis); Icon(Icons.Default.ChevronRight, null, tint = Color.Gray) } }
            } }
        }
    }
}

@Composable
private fun FavoritesTab(events: List<SportsEvent>, channels: List<SportsChannel>, favorites: MutableMap<String, Boolean>, play: (SportsChannel) -> Unit, playEvent: (SportsEvent) -> Unit) {
    val saved = events.filter { favorites[it.id] == true }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("FAVORITES", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Black) }
        if (saved.isEmpty()) item { EmptyCard("Nothing saved yet", "Star an event to keep it here.") }
        else items(saved, key = { "fav-${it.id}" }) { EventCard(it, it.state == "in", channels, System.currentTimeMillis(), play, playEvent, favorites) }
    }
}

@Composable
private fun SourcesTab(open: () -> Unit) { Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { Text("SOURCES", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Black); Text("Xtream Codes + M3U/M3U8", color = Color.Gray); Card(Modifier.fillMaxWidth().clickable { open() }, colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) { Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.SettingsInputAntenna, null, tint = Orange); Spacer(Modifier.width(14.dp)); Column(Modifier.weight(1f)) { Text("Manage source", color = Color.White, fontWeight = FontWeight.Bold); Text("Connect, test and index your playlist", color = Color.Gray, fontSize = 12.sp) }; Icon(Icons.Default.ChevronRight, null, tint = Color.Gray) } } } }

@Composable
private fun BottomBar(selected: Int, onSelect: (Int) -> Unit) { NavigationBar(containerColor = Color(0xFF11131D)) { val items = listOf(Icons.Default.Home to "Home", Icons.Default.SportsScore to "Sports", Icons.Default.LiveTv to "Sports TV", Icons.Default.Star to "Favorites", Icons.Default.Settings to "Sources"); items.forEachIndexed { i, item -> NavigationBarItem(selected = selected == i, onClick = { onSelect(i) }, icon = { Icon(item.first, item.second) }, label = { Text(item.second) }) } }
}

@Composable
private fun SportRail(selected: String, onSport: (String) -> Unit) { LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(SportsCatalog.categories, key = { it }) { sport -> FilterChip(selected = selected == sport, onClick = { onSport(sport) }, label = { Text(sport) }) } } }

@Composable
private fun ScheduleRail(selected: ScheduleBucket, onSelected: (ScheduleBucket) -> Unit, events: List<SportsEvent>, now: Long) {
    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        items(ScheduleBucket.values().toList(), key = { it.name }) { item ->
            val count = events.count { scheduleBucket(it, now) == item }
            FilterChip(selected = selected == item, onClick = { onSelected(item) }, label = { Text("${item.label}  $count") })
        }
    }
}

@Composable
private fun SectionTitle(title: String, detail: String, accent: Color) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(4.dp).height(24.dp).background(accent, RoundedCornerShape(3.dp)))
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) { Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Black); Text(detail, color = Color.Gray, fontSize = 11.sp) }
    }
}

@Composable
private fun EmptyCard(title: String, subtitle: String) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) { Column(Modifier.padding(18.dp)) { Text(title, color = Color.White, fontWeight = FontWeight.Bold); Text(subtitle, color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)) } }
}

private fun emptyTitle(bucket: ScheduleBucket): String = when (bucket) {
    ScheduleBucket.LIVE -> "No live games detected"
    ScheduleBucket.STARTING_SOON -> "No events starting soon"
    ScheduleBucket.TODAY -> "No more events today"
    ScheduleBucket.TOMORROW -> "Nothing scheduled tomorrow"
    ScheduleBucket.NEXT_3_DAYS -> "No events in the next three days"
    ScheduleBucket.COMPLETED -> "No completed events"
}

private fun emptySubtitle(bucket: ScheduleBucket): String = when (bucket) {
    ScheduleBucket.LIVE -> "The live schedule is checked independently of your Xtream playlist and refreshes automatically."
    ScheduleBucket.STARTING_SOON -> "Events within the next six hours appear here."
    ScheduleBucket.TODAY -> "Try another sport or check TOMORROW for the next slate."
    ScheduleBucket.TOMORROW -> "The schedule will update as providers publish events."
    ScheduleBucket.NEXT_3_DAYS -> "Try another sport or refresh the schedule."
    ScheduleBucket.COMPLETED -> "Finished events remain available here for reference."
}

private fun parseInstant(value: String): Instant? = runCatching { Instant.parse(value) }.getOrNull()

private fun scheduleBucket(event: SportsEvent, nowMs: Long): ScheduleBucket {
    val start = parseInstant(event.startTime)?.toEpochMilli() ?: return ScheduleBucket.TODAY
    val delta = start - nowMs
    val localZone = ZoneId.systemDefault()
    val today = Instant.ofEpochMilli(nowMs).atZone(localZone).toLocalDate()
    val eventDay = Instant.ofEpochMilli(start).atZone(localZone).toLocalDate()
    return when {
        event.state == "in" -> ScheduleBucket.LIVE
        event.state != "post" && delta <= 0 && delta > -6 * 60 * 60 * 1000L -> ScheduleBucket.LIVE
        delta > 0 && delta <= 6 * 60 * 60 * 1000L -> ScheduleBucket.STARTING_SOON
        eventDay == today -> ScheduleBucket.TODAY
        eventDay == today.plusDays(1) -> ScheduleBucket.TOMORROW
        eventDay.isAfter(today) && eventDay <= today.plusDays(3) -> ScheduleBucket.NEXT_3_DAYS
        start < nowMs -> ScheduleBucket.COMPLETED
        else -> ScheduleBucket.NEXT_3_DAYS
    }
}

private fun formatCountdown(ms: Long): String {
    val totalMinutes = (ms.coerceAtLeast(0L) / 60_000L)
    val days = totalMinutes / (24 * 60)
    val hours = (totalMinutes / 60) % 24
    val minutes = totalMinutes % 60
    return when {
        days > 0 -> "${days}d ${hours}h"
        hours > 0 -> "${hours}h ${minutes}m"
        else -> "${minutes}m"
    }
}

private fun formatClock(value: String): String = parseInstant(value)?.atZone(ZoneId.systemDefault())?.format(DateTimeFormatter.ofPattern("EEE, MMM d • h:mm a", Locale.getDefault())) ?: value
