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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RichSportsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { RichSportsApp() }
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
    var events by remember { mutableStateOf<List<SportsEvent>>(emptyList()) }
    var selectedSport by remember { mutableStateOf("All") }
    var selectedMode by remember { mutableStateOf("All") }
    var loading by remember { mutableStateOf(true) }
    var tab by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    fun refresh() {
        loading = true
        scope.launch {
            events = runCatching { SportsSchedule.load(forceRefresh = true) }.getOrDefault(events)
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        events = SportsSchedule.load()
        loading = false
    }

    val filtered = SportsSchedule.forSport(events, selectedSport)
    val live = SportsSchedule.liveEvents(filtered)
    val upcoming = SportsSchedule.upcomingEvents(filtered)

    MaterialTheme(colorScheme = darkColorScheme(primary = Cyan, secondary = Purple, background = Ink, surface = Panel)) {
        Scaffold(
            containerColor = Ink,
            bottomBar = {
                RichBottomBar(tab) {
                    if (it == 4) {
                        startActivity(Intent(this@RichSportsActivity, MainActivity::class.java))
                    } else tab = it
                }
            }
        ) { pad ->
            LazyColumn(
                Modifier.fillMaxSize().padding(pad),
                contentPadding = PaddingValues(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item { RichHeader(onRefresh = ::refresh, loading = loading) }
                item { NoticeStrip() }
                item { BrandRail(selectedSport) { selectedSport = it } }
                item { ModeRail(selectedMode) { selectedMode = it } }

                if (selectedMode == "Live now") {
                    item { SectionTitle("LIVE NOW", "${live.size} events", Pink) }
                    items(live, key = { "live-${it.id}" }) { RichEventCard(it, true) }
                } else {
                    if (live.isNotEmpty()) {
                        item { SectionTitle("LIVE NOW", "${live.size} on air", Pink) }
                        items(live.take(5), key = { "live-${it.id}" }) { RichEventCard(it, true) }
                    }
                    if (selectedMode != "Today's") {
                        item { SectionTitle("COMING UP", "${upcoming.size} events", Cyan) }
                        items(upcoming.take(18), key = { "up-${it.id}" }) { RichEventCard(it, false) }
                    } else {
                        item { SectionTitle("TODAY'S SCHEDULE", "${upcoming.size} events", Cyan) }
                        items(upcoming.take(18), key = { "today-${it.id}" }) { RichEventCard(it, false) }
                    }
                }
                if (!loading && live.isEmpty() && upcoming.isEmpty()) {
                    item { EmptyRich() }
                }
            }
        }
    }
}

@Composable
private fun RichHeader(onRefresh: () -> Unit, loading: Boolean) {
    Box(
        Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(Color(0xFF1A2034), Color(0xFF1B1027), Ink))).padding(horizontal = 18.dp, vertical = 18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Column {
                Text("USportz", fontSize = 31.sp, fontWeight = FontWeight.ExtraBold)
                Text("LIVE SPORTS HUB", fontSize = 12.sp, color = Color(0xFFAEB6C9), fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
            }
            Row {
                IconButton(onClick = onRefresh, enabled = !loading) { Icon(Icons.Default.Refresh, "Refresh", tint = Color.White) }
                IconButton(onClick = { }) { Icon(Icons.Default.FavoriteBorder, "Favorites", tint = Color.White) }
                IconButton(onClick = { }) { Icon(Icons.Default.Search, "Search", tint = Color.White) }
            }
        }
    }
}

@Composable
private fun NoticeStrip() {
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        shape = RoundedCornerShape(22.dp),
        color = Color(0xFF151823),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF292D3B))
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("◉", color = Cyan, fontSize = 17.sp)
            Spacer(Modifier.width(10.dp))
            Text("Live schedule • scores • channels", color = Color(0xFFE7E9F1), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text("AUTO", color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
        }
    }
}

@Composable
private fun BrandRail(selected: String, onSelected: (String) -> Unit) {
    val brands = listOf("All" to "ALL", "Football" to "NFL", "Basketball" to "NBA", "Baseball" to "MLB", "Hockey" to "NHL", "Soccer" to "⚽", "MMA" to "UFC", "Wrestling" to "WWE", "Tennis" to "TNS", "Golf" to "GOLF", "Motorsports" to "RACE")
    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(brands) { (key, label) ->
            val active = selected == key
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(72.dp).clickable { onSelected(key) }) {
                Box(
                    Modifier.size(58.dp).shadow(if (active) 8.dp else 0.dp, CircleShape).background(if (active) Brush.linearGradient(listOf(Purple, Cyan)) else Brush.linearGradient(listOf(Color(0xFF202433), Color(0xFF10131D))), CircleShape).border(1.dp, if (active) Cyan else Color(0xFF303546), CircleShape),
                    contentAlignment = Alignment.Center
                ) { Text(label, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = if (label.length > 4) 9.sp else 13.sp) }
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
            FilterChip(
                selected = selected == mode,
                onClick = { onSelected(mode) },
                label = { Text(mode, fontWeight = FontWeight.Bold) },
                leadingIcon = { Icon(if (mode == "Live now") Icons.Default.Sensors else if (mode == "Today's") Icons.Default.Today else if (mode == "Upcoming") Icons.Default.Schedule else Icons.Default.Check, null, Modifier.size(17.dp)) }
            )
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
private fun RichEventCard(event: SportsEvent, live: Boolean) {
    val borderBrush = Brush.linearGradient(listOf(Purple, Color(0xFF4B1F6F), Cyan))
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp).border(1.5.dp, borderBrush, RoundedCornerShape(20.dp)),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Panel)
    ) {
        Column {
            if (!event.leagueLogo.isNullOrBlank()) {
                Box(Modifier.fillMaxWidth().height(54.dp).background(Brush.horizontalGradient(listOf(Color(0xFF1C1630), Color(0xFF0E1924))))) {
                    AsyncImage(event.leagueLogo, event.league, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = 0.35f)
                    Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(event.leagueLogo, event.league, Modifier.size(28.dp), contentScale = ContentScale.Fit)
                        Spacer(Modifier.width(9.dp))
                        Text("${event.league.uppercase()}  •  ${event.name}", color = Color(0xFFD7DBE7), fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
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
                    if (live) {
                        Box(Modifier.background(Color(0x3322D9FF), RoundedCornerShape(20.dp)).padding(horizontal = 9.dp, vertical = 5.dp)) { Text("● LIVE", color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold) }
                    } else {
                        Text(SportsPresentation.status(event), color = Color(0xFF8E96AB), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                    if (event.detail.isNotBlank()) Text(event.detail, color = Color(0xFF7C8498), fontSize = 10.sp, maxLines = 1, modifier = Modifier.padding(top = 5.dp))
                }
            }
            Row(Modifier.fillMaxWidth().background(Color(0x22000000)).padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (event.broadcast.isBlank()) "Sports event" else event.broadcast, color = Color(0xFF8F98AD), fontSize = 11.sp, modifier = Modifier.weight(1f))
                if (live) {
                    Text("WATCH LIVE", color = Cyan, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.width(5.dp))
                    Icon(Icons.Default.PlayCircle, null, tint = Cyan, modifier = Modifier.size(20.dp))
                } else {
                    Text("EVENT", color = Color(0xFF8F98AD), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun TeamRow(name: String, logo: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (!logo.isNullOrBlank()) {
            AsyncImage(logo, name, Modifier.size(36.dp), contentScale = ContentScale.Fit)
        } else {
            Box(Modifier.size(36.dp).background(Color(0xFF252A39), CircleShape), contentAlignment = Alignment.Center) { Text(name.take(2).uppercase(), fontSize = 10.sp, fontWeight = FontWeight.ExtraBold) }
        }
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
        items.forEachIndexed { index, item ->
            NavigationBarItem(selected = selected == index, onClick = { onSelect(index) }, icon = { Icon(item.first, item.second) }, label = { Text(item.second) })
        }
    }
}

private fun formatClock(value: String): String = runCatching {
    val date = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.parse(value) ?: return@runCatching value
    SimpleDateFormat("h:mm a", Locale.getDefault()).format(date)
}.getOrDefault(value)

private fun formatDay(value: String): String = runCatching {
    val date = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.parse(value) ?: return@runCatching ""
    SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(date)
}.getOrDefault("")
