package com.usportz.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

class RichSportsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { RichSportsApp() }
    }

    fun openSourceApp() = startActivity(Intent(this, MainActivity::class.java))

    fun playChannel(channel: SportsChannel) {
        startActivity(Intent(this, RichPlayerActivity::class.java).putExtra(RichPlayerActivity.EXTRA_URL, channel.url))
    }
}

private val Ink = Color(0xFF080A12)
private val Panel = Color(0xFF121522)
private val Panel2 = Color(0xFF181B2A)
private val Purple = Color(0xFF9B5CFF)
private val Cyan = Color(0xFF14D9FF)
private val Pink = Color(0xFFFF3E91)

@Composable
private fun RichSportsApp() {
    val activity = androidx.compose.ui.platform.LocalContext.current as RichSportsActivity
    var events by remember { mutableStateOf<List<SportsEvent>>(emptyList()) }
    var channels by remember { mutableStateOf<List<SportsChannel>>(emptyList()) }
    var selectedSport by remember { mutableStateOf("All") }
    var selectedMode by remember { mutableStateOf("All") }
    var loading by remember { mutableStateOf(true) }
    var sourceLoading by remember { mutableStateOf(true) }
    var tab by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    fun refresh() {
        loading = true
        sourceLoading = true
        scope.launch {
            events = runCatching { SportsSchedule.load(forceRefresh = true) }.getOrDefault(events)
            channels = runCatching { SportsChannelBridge.load(activity, forceRefresh = true) }.getOrDefault(channels)
            loading = false
            sourceLoading = false
        }
    }

    LaunchedEffect(Unit) {
        events = SportsSchedule.load()
        channels = SportsChannelBridge.load(activity)
        loading = false
        sourceLoading = false
    }

    val filtered = SportsSchedule.forSport(events, selectedSport)
    val live = SportsSchedule.liveEvents(filtered)
    val upcomingAll = SportsSchedule.upcomingEvents(filtered)
    val upcoming = if (selectedMode == "Today's") upcomingAll.filter { SportsSchedule.isToday(it) } else upcomingAll
    val featured = live.firstOrNull() ?: upcoming.firstOrNull()
    val wrestling = events.filter { SportsCatalog.classify(it.name, it.league) == "Wrestling" && (it.state == "in" || SportsSchedule.isToday(it)) }.take(6)

    fun matched(event: SportsEvent): SportsChannel? = SportsChannelBridge.bestMatch(event, channels)

    MaterialTheme(colorScheme = darkColorScheme(primary = Cyan, secondary = Purple, background = Ink, surface = Panel)) {
        Scaffold(
            containerColor = Ink,
            bottomBar = { RichBottomBar(tab) { if (it >= 2) activity.openSourceApp() else tab = it } }
        ) { pad ->
            LazyColumn(
                Modifier.fillMaxSize().padding(pad),
                contentPadding = PaddingValues(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item { RichHeader(onRefresh = ::refresh, loading = loading, onSources = activity::openSourceApp) }
                item { NoticeStrip(loading = loading || sourceLoading, cached = SportsSchedule.isCacheFresh(), channels = channels.size) }
                if (featured != null) item { FeaturedEvent(featured, matched(featured)) { channel -> activity.playChannel(channel) } }
                item { BrandRail(events, selectedSport) { selectedSport = it } }
                item { ModeRail(selectedMode) { selectedMode = it } }

                if (selectedMode == "Live now") {
                    item { SectionTitle("LIVE NOW", "${live.size} events", Pink) }
                    items(live, key = { "live-${it.id}" }) { event -> RichEventCard(event, true, matched(event)) { activity.playChannel(it) } }
                } else {
                    if (live.isNotEmpty()) {
                        item { SectionTitle("LIVE NOW", "${live.size} on air", Pink) }
                        items(live.take(8), key = { "live-${it.id}" }) { event -> RichEventCard(event, true, matched(event)) { activity.playChannel(it) } }
                    }
                    if (selectedSport == "All" && wrestling.isNotEmpty()) item { WrestlingSpotlight(wrestling, channels) { activity.playChannel(it) } }
                    item { SectionTitle(if (selectedMode == "Today's") "TODAY'S SCHEDULE" else "COMING UP", "${upcoming.size} events", Cyan) }
                    items(upcoming.take(20), key = { "up-${it.id}" }) { event -> RichEventCard(event, false, matched(event)) { activity.playChannel(it) } }
                }
                if (!loading && live.isEmpty() && upcoming.isEmpty()) item { EmptyRich() }
            }
        }
    }
}

@Composable
private fun RichHeader(onRefresh: () -> Unit, loading: Boolean, onSources: () -> Unit) {
    Box(Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(Color(0xFF1A2034), Color(0xFF1B1027), Ink))).padding(horizontal = 18.dp, vertical = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Column {
                Text("USportz", fontSize = 31.sp, fontWeight = FontWeight.ExtraBold)
                Text("LIVE SPORTS HUB", fontSize = 12.sp, color = Color(0xFFAEB6C9), fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
            }
            Row {
                IconButton(onClick = onRefresh, enabled = !loading) { Icon(Icons.Default.Refresh, "Refresh", tint = Color.White) }
                IconButton(onClick = onSources) { Icon(Icons.Default.Settings, "Sources", tint = Color.White) }
            }
        }
    }
}

@Composable
private fun NoticeStrip(loading: Boolean, cached: Boolean, channels: Int) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp), shape = RoundedCornerShape(22.dp), color = Color(0xFF151823), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF292D3B))) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("●", color = if (loading) Purple else Cyan, fontSize = 13.sp)
            Spacer(Modifier.width(10.dp))
            Text(if (loading) "Updating sports hub…" else "Live schedule • scores • channels", color = Color(0xFFE7E9F1), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(if (channels > 0) "$channels CH" else if (cached) "FAST" else "LIVE", color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
        }
    }
}

@Composable
private fun FeaturedEvent(event: SportsEvent, channel: SportsChannel?, onWatch: (SportsChannel) -> Unit) {
    val live = event.state == "in"
    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(190.dp).clip(RoundedCornerShape(24.dp)).border(1.dp, Brush.linearGradient(listOf(Purple, Cyan, Pink)), RoundedCornerShape(24.dp)).background(Brush.linearGradient(listOf(Color(0xFF231642), Color(0xFF101A2A), Color(0xFF160D1A))))) {
        if (!event.leagueLogo.isNullOrBlank()) AsyncImage(event.leagueLogo, event.league, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = 0.20f)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xDD080A12)))))
        Column(Modifier.align(Alignment.BottomStart).padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.background(if (live) Pink else Purple, RoundedCornerShape(14.dp)).padding(horizontal = 9.dp, vertical = 5.dp)) { Text(if (live) "LIVE NOW" else "FEATURED", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold) }
                Spacer(Modifier.width(9.dp))
                Text(event.league.uppercase(), color = Color(0xFFCFD5E5), fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            Text(SportsPresentation.matchup(event), color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
            Text(if (live) event.detail.ifBlank { if (channel != null) "${channel.name} • ready to watch" else "No matched channel yet" } else formatClock(event.startTime), color = Color(0xFFAEB6C9), fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
            if (live && channel != null) TextButton(onClick = { onWatch(channel) }) { Icon(Icons.Default.PlayArrow, null, tint = Cyan); Spacer(Modifier.width(5.dp)); Text("WATCH LIVE", color = Cyan, fontWeight = FontWeight.ExtraBold) }
        }
        if (live && channel != null) Icon(Icons.Default.PlayCircle, "Watch live", tint = Cyan, modifier = Modifier.align(Alignment.TopEnd).padding(18.dp).size(38.dp).clickable { onWatch(channel) })
    }
}

@Composable
private fun BrandRail(events: List<SportsEvent>, selected: String, onSelected: (String) -> Unit) {
    val keys = listOf("All" to "ALL", "Football" to "NFL", "Basketball" to "NBA", "Baseball" to "MLB", "Hockey" to "NHL", "Soccer" to "⚽", "MMA" to "UFC", "Wrestling" to "WWE", "Tennis" to "TNS", "Golf" to "GOLF", "Motorsports" to "RACE")
    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(keys) { (key, label) ->
            val active = selected == key
            val logo = events.firstOrNull { SportsCatalog.classify(it.name, it.league) == key && !it.leagueLogo.isNullOrBlank() }?.leagueLogo
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(72.dp).clickable { onSelected(key) }) {
                Box(Modifier.size(58.dp).shadow(if (active) 8.dp else 0.dp, CircleShape).background(if (active) Brush.linearGradient(listOf(Purple, Cyan)) else Brush.linearGradient(listOf(Color(0xFF202433), Color(0xFF10131D))), CircleShape).border(1.dp, if (active) Cyan else Color(0xFF303546), CircleShape), contentAlignment = Alignment.Center) {
                    if (!logo.isNullOrBlank()) AsyncImage(logo, key, Modifier.size(39.dp), contentScale = ContentScale.Fit) else Text(label, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = if (label.length > 4) 9.sp else 13.sp)
                }
                Text(key, color = if (active) Color.White else Color(0xFF858DA2), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp))
            }
        }
    }
}

@Composable
private fun ModeRail(selected: String, onSelected: (String) -> Unit) {
    val modes = listOf("All", "Live now", "Today's", "Upcoming")
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        modes.forEach { mode ->
            FilterChip(selected = selected == mode, onClick = { onSelected(mode) }, label = { Text(mode, fontWeight = FontWeight.Bold) }, leadingIcon = { Icon(if (mode == "Live now") Icons.Default.Sensors else if (mode == "Today's") Icons.Default.Today else if (mode == "Upcoming") Icons.Default.Schedule else Icons.Default.Check, null, Modifier.size(17.dp)) })
        }
    }
}

@Composable
private fun SectionTitle(title: String, count: String, accent: Color) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(accent, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
        Spacer(Modifier.weight(1f))
        Text(count, color = Color(0xFF8991A7), fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun WrestlingSpotlight(events: List<SportsEvent>, channels: List<SportsChannel>, onWatch: (SportsChannel) -> Unit) {
    Column {
        SectionTitle("WRESTLING", "WWE • AEW • TNA • ROH", Purple)
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(events, key = { "wrestle-${it.id}" }) { event ->
                val channel = SportsChannelBridge.bestMatch(event, channels)
                Box(Modifier.width(245.dp).height(112.dp).clip(RoundedCornerShape(18.dp)).background(Brush.linearGradient(listOf(Color(0xFF241735), Color(0xFF151A29)))).border(1.dp, Color(0xFF3B3152), RoundedCornerShape(18.dp)).clickable(enabled = channel != null) { if (channel != null) onWatch(channel) }) {
                    if (!event.leagueLogo.isNullOrBlank()) AsyncImage(event.leagueLogo, event.league, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = 0.22f)
                    Column(Modifier.padding(14.dp)) {
                        Text(event.league.uppercase(), color = Purple, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
                        Text(SportsPresentation.matchup(event), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp))
                        Text(if (event.state == "in") if (channel != null) "● LIVE • READY" else "● LIVE • NO MATCH" else formatClock(event.startTime), color = if (event.state == "in") Pink else Color(0xFF8991A7), fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun RichEventCard(event: SportsEvent, live: Boolean, channel: SportsChannel?, onWatch: (SportsChannel) -> Unit) {
    val borderBrush = Brush.linearGradient(listOf(Purple, Color(0xFF4B1F6F), Cyan))
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp).border(1.5.dp, borderBrush, RoundedCornerShape(20.dp)), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column {
            Box(Modifier.fillMaxWidth().height(58.dp).background(Brush.horizontalGradient(listOf(Color(0xFF1C1630), Color(0xFF0E1924))))) {
                if (!event.leagueLogo.isNullOrBlank()) AsyncImage(event.leagueLogo, event.league, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = 0.30f)
                Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!event.leagueLogo.isNullOrBlank()) AsyncImage(event.leagueLogo, event.league, Modifier.size(30.dp), contentScale = ContentScale.Fit)
                    else Box(Modifier.size(30.dp).background(Purple, CircleShape), contentAlignment = Alignment.Center) { Text(event.league.take(2), fontSize = 9.sp, fontWeight = FontWeight.ExtraBold) }
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f)) {
                        Text(event.league.uppercase(), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
                        Text(event.name, color = Color(0xFF9AA2B6), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (live) Text("● LIVE", color = Pink, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
            Row(Modifier.padding(horizontal = 14.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.width(76.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(formatClock(event.startTime), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
                    Text(if (live) "LIVE" else formatDay(event.startTime), color = if (live) Pink else Color(0xFF8790A7), fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                }
                Box(Modifier.width(1.dp).height(82.dp).background(Color(0xFF2B2F3D)))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    TeamRow(event.competitors.getOrNull(0) ?: event.shortName, event.competitorLogos.getOrNull(0))
                    Spacer(Modifier.height(10.dp))
                    TeamRow(event.competitors.getOrNull(1) ?: "TBD", event.competitorLogos.getOrNull(1))
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(SportsPresentation.status(event), color = if (live) Cyan else Color(0xFF8E96AB), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    if (event.detail.isNotBlank()) Text(event.detail, color = Color(0xFF7C8498), fontSize = 10.sp, maxLines = 1, modifier = Modifier.padding(top = 5.dp))
                }
            }
            Row(Modifier.fillMaxWidth().background(Color(0x22000000)).padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (channel != null) "${channel.name} • ${channel.group}" else if (event.broadcast.isBlank()) "No matched source channel" else event.broadcast, color = if (channel != null) Color(0xFFB9C4D8) else Color(0xFF8F98AD), fontSize = 11.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (channel != null) {
                    Text(if (live) "WATCH LIVE" else "WATCH", color = Cyan, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.clickable { onWatch(channel) })
                    Spacer(Modifier.width(5.dp))
                    Icon(Icons.Default.PlayCircle, null, tint = Cyan, modifier = Modifier.size(20.dp).clickable { onWatch(channel) })
                } else Text("NO MATCH", color = Color(0xFF697185), fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun TeamRow(name: String, logo: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (!logo.isNullOrBlank()) AsyncImage(logo, name, Modifier.size(36.dp), contentScale = ContentScale.Fit)
        else Box(Modifier.size(36.dp).background(Color(0xFF252A39), CircleShape), contentAlignment = Alignment.Center) { Text(name.take(2).uppercase(), fontSize = 10.sp, fontWeight = FontWeight.ExtraBold) }
        Spacer(Modifier.width(10.dp))
        Text(name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun EmptyRich() {
    Card(Modifier.fillMaxWidth().padding(12.dp), colors = CardDefaults.cardColors(containerColor = Panel2), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.SportsScore, null, tint = Cyan, modifier = Modifier.size(42.dp))
            Text("No events in this view", fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, modifier = Modifier.padding(top = 10.dp))
            Text("Refresh the schedule or choose another sport.", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun RichBottomBar(selected: Int, onSelect: (Int) -> Unit) {
    NavigationBar(containerColor = Color(0xFF11131D)) {
        val items = listOf(Icons.Default.Home to "Home", Icons.Default.SportsScore to "Sports", Icons.Default.LiveTv to "Live TV", Icons.Default.Star to "Favorites", Icons.Default.Settings to "Sources")
        items.forEachIndexed { index, item -> NavigationBarItem(selected = selected == index, onClick = { onSelect(index) }, icon = { Icon(item.first, item.second) }, label = { Text(item.second) }) }
    }
}

private fun parseInstant(value: String): Instant? = runCatching { Instant.parse(value) }.getOrElse {
    runCatching { java.time.OffsetDateTime.parse(value).toInstant() }.getOrNull()
}

private fun formatClock(value: String): String = parseInstant(value)?.let {
    DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()).withZone(ZoneId.systemDefault()).format(it)
} ?: value

private fun formatDay(value: String): String = parseInstant(value)?.let {
    DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault()).withZone(ZoneId.systemDefault()).format(it)
} ?: ""
