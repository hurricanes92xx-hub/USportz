package com.usportz.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class RichSportsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { RichSportsApp() }
    }

    fun openSourceApp() = startActivity(Intent(this, SourceActivity::class.java))

    fun playChannel(channel: SportsChannel) {
        startActivity(Intent(this, RichPlayerActivity::class.java).putExtra(RichPlayerActivity.EXTRA_URL, channel.url))
    }
}

private val Ink = Color(0xFF080A12)
private val Panel = Color(0xFF121522)
private val Panel2 = Color(0xFF181B2A)
private val Cyan = Color(0xFF14D9FF)
private val Purple = Color(0xFF9B5CFF)
private val Pink = Color(0xFFFF3E91)
private val LiveRed = Color(0xFFFF335C)

private enum class ScheduleBucket(val label: String) {
    LIVE("LIVE NOW"), STARTING_SOON("STARTING SOON"), TODAY("TODAY"), TOMORROW("TOMORROW"), NEXT_3_DAYS("NEXT 3 DAYS"), COMPLETED("COMPLETED")
}

@Composable
private fun RichSportsApp() {
    val activity = androidx.compose.ui.platform.LocalContext.current as RichSportsActivity
    val context = activity
    var events by remember { mutableStateOf<List<SportsEvent>>(emptyList()) }
    var channels by remember { mutableStateOf<List<SportsChannel>>(emptyList()) }
    var categories by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedSport by remember { mutableStateOf("All") }
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    var scheduleBucket by remember { mutableStateOf(ScheduleBucket.LIVE) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var tab by remember { mutableIntStateOf(0) }

    suspend fun loadData(force: Boolean = false) {
        if (force) refreshing = true else loading = true
        val loadedChannels = withContext(Dispatchers.IO) { SportsChannelBridge.load(context, force) }
        val loadedEvents = withContext(Dispatchers.IO) {
            runCatching { SportsSchedule.load(force, loadedChannels) }.getOrDefault(emptyList())
        }
        channels = loadedChannels
        events = loadedEvents
        categories = withContext(Dispatchers.Default) {
            loadedChannels.asSequence()
                .map { categoryFor(it) }
                .filter { it.isNotBlank() }
                .groupingBy { it }
                .eachCount()
                .entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key.lowercase() })
                .take(250)
                .map { it.key }
        }
        loading = false
        refreshing = false
    }

    LaunchedEffect(Unit) {
        runCatching { loadData(false) }
        while (true) {
            delay(1_000)
            nowMs = System.currentTimeMillis()
            if (nowMs % 60_000L < 1_100L) runCatching { loadData(true) }
        }
    }

    val sportEvents = remember(events, selectedSport) { SportsSchedule.forSport(events, selectedSport) }
    val liveEvents = remember(sportEvents, nowMs) { sportEvents.filter { scheduleBucket(it, nowMs) == ScheduleBucket.LIVE } }

    MaterialTheme(colorScheme = darkColorScheme(primary = Cyan, secondary = Purple, background = Ink, surface = Panel)) {
        Scaffold(
            containerColor = Ink,
            bottomBar = { RichBottomBar(tab) { tab = it } }
        ) { pad ->
            when (tab) {
                0 -> HomeTab(loading, refreshing, channels.size, sportEvents, channels, selectedSport, nowMs, { selectedSport = it }, activity::playChannel, activity::openSourceApp, { runCatching { loadData(true) } })
                1 -> SportsTab(selectedSport, { selectedSport = it }, sportEvents, channels, nowMs, scheduleBucket, { scheduleBucket = it }, activity::playChannel, refreshing, { runCatching { loadData(true) } })
                2 -> LiveTvTab(categories, selectedCategory, { selectedCategory = it }, channels, activity::playChannel, loading)
                3 -> FavoritesTab(events, channels, nowMs, activity::playChannel)
                4 -> SourceTab { activity.openSourceApp() }
            }
        }
    }
}

@Composable
private fun HomeTab(
    loading: Boolean,
    refreshing: Boolean,
    channelCount: Int,
    events: List<SportsEvent>,
    channels: List<SportsChannel>,
    selectedSport: String,
    nowMs: Long,
    onSport: (String) -> Unit,
    onWatch: (SportsChannel) -> Unit,
    onSources: () -> Unit,
    onRefresh: () -> Unit
) {
    val live = remember(events, nowMs) { events.filter { scheduleBucket(it, nowMs) == ScheduleBucket.LIVE } }
    val soon = remember(events, nowMs) { events.filter { scheduleBucket(it, nowMs) == ScheduleBucket.STARTING_SOON } }
    LazyColumn(Modifier.fillMaxSize().padding(bottom = 8.dp), contentPadding = PaddingValues(bottom = 22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Header(onSources, refreshing, onRefresh) }
        item { Hero(channelCount, live.size, soon.size) }
        item { SportRail(selectedSport, onSport) }
        item { SectionTitle("LIVE NOW", "${live.size} events", LiveRed) }
        if (live.isEmpty()) item { EmptyCard("Nothing live right now", "USportz will refresh the schedule automatically.") }
        else items(live.take(8), key = { "home-live-${it.id}" }) { EventCard(it, true, channels, nowMs, onWatch) }
        item { SectionTitle("STARTING SOON", "${soon.size} events", Cyan) }
        if (soon.isEmpty()) item { EmptyCard("No events starting soon", "Check TODAY or TOMORROW for the next slate.") }
        else items(soon.take(8), key = { "home-soon-${it.id}" }) { EventCard(it, false, channels, nowMs, onWatch) }
        if (loading) item { Text("Loading sports and Xtream channels…", color = Color.Gray, modifier = Modifier.padding(18.dp)) }
    }
}

@Composable
private fun Header(onSources: () -> Unit, refreshing: Boolean, onRefresh: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(Color(0xFF171D30), Color(0xFF1B1027), Ink))).padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f)) {
            Text("USPORTZ", fontSize = 31.sp, fontWeight = FontWeight.Black, color = Color.White)
            Text("SPORTS COMMAND CENTER", fontSize = 11.sp, color = Cyan, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        }
        IconButton(onClick = onRefresh, enabled = !refreshing) { Icon(if (refreshing) Icons.Default.Sync else Icons.Default.Refresh, "Refresh", tint = if (refreshing) Color.Gray else Color.White) }
        IconButton(onClick = onSources) { Icon(Icons.Default.Settings, "Sources", tint = Color.White) }
    }
}

@Composable
private fun Hero(channels: Int, live: Int, soon: Int) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Panel2)) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LiveDot()
                Spacer(Modifier.width(8.dp))
                Text("LIVE SPORTS", color = LiveRed, fontSize = 12.sp, fontWeight = FontWeight.Black)
            }
            Text("Everything worth watching.", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(top = 5.dp))
            Text("$live live now  •  $soon starting soon  •  $channels channels", color = Color(0xFF9DA5B7), fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            Text("Schedule refreshes automatically every 60 seconds.", color = Color.Gray, fontSize = 11.sp, modifier = Modifier.padding(top = 7.dp))
        }
    }
}

@Composable
private fun SportRail(selected: String, onSport: (String) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(SportsCatalog.categories) { sport ->
            FilterChip(selected = selected == sport, onClick = { onSport(sport) }, label = { Text(sport) })
        }
    }
}

@Composable
private fun SportsTab(
    selected: String,
    onSelected: (String) -> Unit,
    events: List<SportsEvent>,
    channels: List<SportsChannel>,
    nowMs: Long,
    selectedBucket: ScheduleBucket,
    onBucket: (ScheduleBucket) -> Unit,
    play: (SportsChannel) -> Unit,
    refreshing: Boolean,
    onRefresh: () -> Unit
) {
    val visible = remember(events, nowMs, selectedBucket) { events.filter { scheduleBucket(it, nowMs) == selectedBucket }.sortedBy { parseInstant(it.startTime)?.toEpochMilli() ?: Long.MAX_VALUE } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Row(verticalAlignment = Alignment.CenterVertically) { BigTitle("SPORTS", "A live schedule that moves with the clock."); Spacer(Modifier.weight(1f)); IconButton(onClick = onRefresh, enabled = !refreshing) { Icon(Icons.Default.Refresh, "Refresh", tint = Cyan) } } }
        item { SportRail(selected, onSelected) }
        item { ScheduleRail(selectedBucket, onBucket, events, nowMs) }
        item { SectionTitle(selectedBucket.label, "${visible.size} events", if (selectedBucket == ScheduleBucket.LIVE) LiveRed else Cyan) }
        if (visible.isEmpty()) item { EmptyCard(emptyTitle(selectedBucket), emptySubtitle(selectedBucket)) }
        else items(visible.take(50), key = { "schedule-${selectedBucket.name}-${it.id}" }) { EventCard(it, selectedBucket == ScheduleBucket.LIVE, channels, nowMs, play) }
    }
}

@Composable
private fun ScheduleRail(selected: ScheduleBucket, onSelected: (ScheduleBucket) -> Unit, events: List<SportsEvent>, nowMs: Long) {
    LazyRow(contentPadding = PaddingValues(horizontal = 0.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        items(ScheduleBucket.values().toList()) { bucket ->
            val count = events.count { scheduleBucket(it, nowMs) == bucket }
            FilterChip(selected = selected == bucket, onClick = { onSelected(bucket) }, label = { Text("${bucket.label}  $count") })
        }
    }
}

@Composable
private fun LiveDot() {
    val transition = rememberInfiniteTransition(label = "live-dot")
    val alpha by transition.animateFloat(0.35f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "live-alpha")
    Box(Modifier.size(9.dp).background(LiveRed.copy(alpha = alpha), RoundedCornerShape(50)))
}

@Composable
private fun EventCard(event: SportsEvent, live: Boolean, channels: List<SportsChannel>?, nowMs: Long, play: (SportsChannel) -> Unit) {
    val rankedMatches = remember(event.id, channels) { channels?.let { GameSourceMatcher.rankMatches(event, it, limit = 3) }.orEmpty() }
    val start = parseInstant(event.startTime)?.toEpochMilli()
    val countdown = if (start != null && start > nowMs) formatCountdown(start - nowMs) else ""
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (live) { LiveDot(); Spacer(Modifier.width(7.dp)); Text("LIVE NOW", color = LiveRed, fontSize = 10.sp, fontWeight = FontWeight.Black) }
                else { Text("UPCOMING", color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.Black) }
                Spacer(Modifier.width(9.dp))
                Text(event.league, color = Color.Gray, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.weight(1f))
                if (!live && countdown.isNotBlank()) Text("IN $countdown", color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.Black)
            }
            Text(SportsPresentation.matchup(event), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
            Text(event.detail.ifBlank { formatClock(event.startTime) }, color = Color(0xFF9DA5B7), fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            if (rankedMatches.isNotEmpty()) {
                Column(Modifier.fillMaxWidth().padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    rankedMatches.forEachIndexed { index, match ->
                        OutlinedButton(onClick = { play(match.channel) }, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                            Icon(Icons.Default.PlayArrow, null)
                            Spacer(Modifier.width(6.dp))
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                                Text(if (index == 0) "STREAM 1 • BEST" else "STREAM ${index + 1}", color = if (index == 0) Cyan else Color.White, fontSize = 11.sp, fontWeight = FontWeight.Black)
                                Text(match.channel.name, color = Color(0xFFB8BECC), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Icon(Icons.Default.ChevronRight, null, tint = Color.Gray)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveTvTab(categories: List<String>, selected: String?, onCategory: (String?) -> Unit, channels: List<SportsChannel>, play: (SportsChannel) -> Unit, loading: Boolean) {
    val visible = remember(channels, selected) { if (selected == null) emptyList() else channels.asSequence().filter { categoryFor(it) == selected }.take(500).toList() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { BigTitle("LIVE TV", if (loading) "Loading your Xtream source…" else "Choose a category to open its channels.") }
        if (selected != null) item { TextButton(onClick = { onCategory(null) }) { Text("← ALL CATEGORIES") } }
        if (selected == null) {
            item { Text("${categories.size} categories • ${channels.size} channels", color = Color.Gray, modifier = Modifier.padding(horizontal = 4.dp)) }
            items(categories, key = { "cat-$it" }) { category ->
                Card(Modifier.fillMaxWidth().clickable { onCategory(category) }, colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
                    Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Folder, null, tint = Cyan); Spacer(Modifier.width(14.dp)); Text(category, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.weight(1f)); Icon(Icons.Default.ChevronRight, null, tint = Color.Gray)
                    }
                }
            }
        } else {
            item { SectionTitle(selected.uppercase(), "${visible.size}+ channels", Cyan) }
            items(visible, key = { "tv-${it.id}" }) { ChannelCard(it, play) }
        }
    }
}

@Composable
private fun FavoritesTab(events: List<SportsEvent>, channels: List<SportsChannel>, nowMs: Long, play: (SportsChannel) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val favs = remember { Favs(context) }
    val favoriteChannels = remember(channels) { channels.filter { favs.isChannelFav(it.id) }.take(200) }
    val favoriteEvents = remember(events) { events.filter { favs.isEventFav(it.id) }.take(50) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { BigTitle("FAVORITES", "Your saved games and channels.") }
        if (favoriteChannels.isEmpty() && favoriteEvents.isEmpty()) item { EmptyCard("Nothing saved yet", "Use the star controls in the TV experience to save favorites.") }
        if (favoriteEvents.isNotEmpty()) item { SectionTitle("EVENTS", "${favoriteEvents.size}", Purple) }
        items(favoriteEvents, key = { "fav-e-${it.id}" }) { EventCard(it, scheduleBucket(it, nowMs) == ScheduleBucket.LIVE, channels, nowMs, play) }
        if (favoriteChannels.isNotEmpty()) item { SectionTitle("CHANNELS", "${favoriteChannels.size}", Cyan) }
        items(favoriteChannels, key = { "fav-c-${it.id}" }) { ChannelCard(it, play) }
    }
}

@Composable
private fun SourceTab(open: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        BigTitle("SOURCES", "Connect Xtream Codes or an M3U/M3U8 playlist.")
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(20.dp)) {
                Text("XTREAM / M3U", color = Cyan, fontWeight = FontWeight.Black)
                Text("Your credentials stay encrypted on this device. They are not bundled into the APK.", color = Color.Gray, fontSize = 13.sp, modifier = Modifier.padding(top = 7.dp))
                Button(onClick = open, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) { Icon(Icons.Default.Login, null); Spacer(Modifier.width(8.dp)); Text("SIGN IN / MANAGE SOURCE") }
            }
        }
    }
}

@Composable
private fun BigTitle(title: String, subtitle: String) {
    Column(Modifier.padding(bottom = 4.dp)) { Text(title, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Black); Text(subtitle, color = Color(0xFF9DA5B7), fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp)) }
}

@Composable
private fun SectionTitle(title: String, count: String, accent: Color) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) { Text("●", color = accent, fontSize = 10.sp); Spacer(Modifier.width(7.dp)); Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold); Spacer(Modifier.weight(1f)); Text(count, color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun ChannelCard(channel: SportsChannel, play: (SportsChannel) -> Unit) {
    Card(Modifier.fillMaxWidth().clickable { play(channel) }, colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.LiveTv, null, tint = Cyan); Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(channel.name, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(channel.group, color = Color.Gray, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }; Icon(Icons.Default.PlayArrow, null, tint = Cyan) }
    }
}

@Composable
private fun EmptyCard(title: String, subtitle: String) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Panel2), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Default.SportsScore, null, tint = Cyan, modifier = Modifier.size(38.dp)); Text(title, color = Color.White, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(top = 8.dp)); Text(subtitle, color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)) }
    }
}

@Composable
private fun RichBottomBar(selected: Int, onSelect: (Int) -> Unit) {
    NavigationBar(containerColor = Color(0xFF11131D)) {
        val items = listOf(Icons.Default.Home to "Home", Icons.Default.SportsScore to "Sports", Icons.Default.LiveTv to "Live TV", Icons.Default.Star to "Favorites", Icons.Default.Settings to "Sources")
        items.forEachIndexed { index, item -> NavigationBarItem(selected = selected == index, onClick = { onSelect(index) }, icon = { Icon(item.first, item.second) }, label = { Text(item.second) }) }
    }
}

private fun scheduleBucket(event: SportsEvent, nowMs: Long): ScheduleBucket {
    val state = event.state.lowercase(Locale.US)
    if (state == "in" || state == "live" || state == "playing") return ScheduleBucket.LIVE
    if (state == "post" || state == "completed" || state == "final" || state == "finished") return ScheduleBucket.COMPLETED
    val start = parseInstant(event.startTime)?.toEpochMilli() ?: return ScheduleBucket.TODAY
    val now = Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault())
    val startDate = Instant.ofEpochMilli(start).atZone(ZoneId.systemDefault()).toLocalDate()
    val today = now.toLocalDate()
    val deltaDays = java.time.temporal.ChronoUnit.DAYS.between(today, startDate)
    if (start < nowMs && start > nowMs - 6L * 60L * 60L * 1000L) return ScheduleBucket.LIVE
    return when {
        start < nowMs -> ScheduleBucket.COMPLETED
        start - nowMs <= 2L * 60L * 60L * 1000L -> ScheduleBucket.STARTING_SOON
        deltaDays == 0L -> ScheduleBucket.TODAY
        deltaDays == 1L -> ScheduleBucket.TOMORROW
        deltaDays in 2L..3L -> ScheduleBucket.NEXT_3_DAYS
        else -> ScheduleBucket.NEXT_3_DAYS
    }
}

private fun emptyTitle(bucket: ScheduleBucket): String = when (bucket) {
    ScheduleBucket.LIVE -> "Nothing live right now"
    ScheduleBucket.STARTING_SOON -> "No events starting soon"
    ScheduleBucket.TODAY -> "No more events today"
    ScheduleBucket.TOMORROW -> "Tomorrow is clear"
    ScheduleBucket.NEXT_3_DAYS -> "No events in the next 3 days"
    ScheduleBucket.COMPLETED -> "No completed events"
}

private fun emptySubtitle(bucket: ScheduleBucket): String = when (bucket) {
    ScheduleBucket.LIVE -> "The schedule automatically checks again every minute."
    ScheduleBucket.STARTING_SOON -> "Starting-soon means the event begins within two hours."
    ScheduleBucket.TODAY -> "Try another sport or check TOMORROW."
    ScheduleBucket.TOMORROW -> "Try TODAY or NEXT 3 DAYS."
    ScheduleBucket.NEXT_3_DAYS -> "The schedule feed may not have farther-out events yet."
    ScheduleBucket.COMPLETED -> "Completed events remain here for quick reference."
}

private fun formatCountdown(millis: Long): String {
    val totalSeconds = (millis / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return when {
        hours > 0 -> String.format(Locale.US, "%dh %02dm", hours, minutes)
        minutes > 0 -> String.format(Locale.US, "%dm %02ds", minutes, seconds)
        else -> String.format(Locale.US, "%ds", seconds)
    }
}

private fun categoryFor(channel: SportsChannel): String {
    val group = channel.group.trim()
    val name = channel.name.trim()
    val marker = Regex("^##\\s*(.+?)\\s*##$").find(name)?.groupValues?.getOrNull(1)
    return when {
        marker != null -> marker.trim()
        group.isNotBlank() && !group.equals("live tv", true) -> group.removePrefix("##").removeSuffix("##").trim()
        else -> "Uncategorized"
    }.ifBlank { "Uncategorized" }
}

private fun parseInstant(value: String): Instant? = runCatching { Instant.parse(value) }.getOrElse { runCatching { java.time.OffsetDateTime.parse(value).toInstant() }.getOrNull() }

private fun formatClock(value: String): String = parseInstant(value)?.let { DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()).withZone(ZoneId.systemDefault()).format(it) } ?: value