package com.usportz.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.format.DateTimeFormatter

private val TvBlack = Color(0xFF070809)
private val TvPanel = Color(0xFF111315)
private val TvPanel2 = Color(0xFF191C1F)
private val NeonOrange = Color(0xFFFF5A00)
private val NeonOrangeSoft = Color(0xFFFF8A3D)
private val TvText = Color(0xFFF5F7F8)
private val TvMuted = Color(0xFF9AA2A8)

class TvSportsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { TvSportsApp(this) }
    }
}

@Composable
private fun TvSportsApp(activity: Activity) {
    var channels by remember { mutableStateOf<List<SportsChannel>>(emptyList()) }
    var events by remember { mutableStateOf<List<SportsEvent>>(emptyList()) }
    var selectedSport by remember { mutableStateOf("All") }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }

    suspend fun loadData(force: Boolean) {
        refreshing = true
        val loadedChannels = withContext(Dispatchers.IO) { SportsChannelBridge.load(activity, force) }
        val loadedEvents = withContext(Dispatchers.IO) { runCatching { SportsSchedule.load(force, loadedChannels) }.getOrDefault(emptyList()) }
        channels = loadedChannels
        events = loadedEvents
        loading = false
        refreshing = false
    }

    LaunchedEffect(Unit) {
        runCatching { loadData(false) }
        while (true) {
            delay(60_000)
            nowMs = System.currentTimeMillis()
            runCatching { loadData(false) }
        }
    }

    val live = remember(events, nowMs, selectedSport) { SportsSchedule.forSport(events, selectedSport).filter { isLiveEvent(it, nowMs) }.take(30) }
    val upcoming = remember(events, nowMs, selectedSport) { SportsSchedule.forSport(events, selectedSport).filter { !isLiveEvent(it, nowMs) }.take(30) }
    val lastChannel = remember(channels) { LastChannelStore.load(activity) }

    MaterialTheme(colorScheme = darkColorScheme(primary = NeonOrange, secondary = NeonOrangeSoft, background = TvBlack, surface = TvPanel)) {
        TvRoot(activity, loading, refreshing, channels, live, upcoming, selectedSport, nowMs, lastChannel, { selectedSport = it }, { })
    }
}

@Composable
private fun TvRoot(activity: Activity, loading: Boolean, refreshing: Boolean, channels: List<SportsChannel>, live: List<SportsEvent>, upcoming: List<SportsEvent>, selectedSport: String, nowMs: Long, lastChannel: SportsChannel?, onSport: (String) -> Unit, onRefresh: () -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxSize().background(TvBlack), contentPadding = PaddingValues(44.dp, 30.dp, 44.dp, 70.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("USPORTZ", color = TvText, fontSize = 38.sp, fontWeight = FontWeight.Black); Text("SPORTS COMMAND CENTER / TV", color = NeonOrange, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp) }
                StatusPill("${channels.size} CHANNELS"); Spacer(Modifier.width(12.dp)); StatusPill(if (refreshing) "SYNCING" else "LIVE"); Spacer(Modifier.width(12.dp))
                OutlinedButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, contentDescription = null); Spacer(Modifier.width(7.dp)); Text("REFRESH") }
            }
        }
        item { HeroCard(live.size, upcoming.size, channels.size, loading) }
        if (lastChannel != null) item { ActionCard(lastChannel) { playTvChannel(activity, lastChannel) } }
        item { SectionTitle("SPORTS", "D-pad left/right") }
        item { SportRail(selectedSport, onSport) }
        item { SectionTitle("LIVE NOW", "${live.size} events") }
        item { EventRail(activity, live, channels, nowMs) }
        item { SectionTitle("UP NEXT", "${upcoming.size} events") }
        item { EventRail(activity, upcoming, channels, nowMs) }
        item { SectionTitle("QUICK CHANNELS", "OK to play") }
        item { ChannelRail(activity, channels.take(40)) }
    }
}

@Composable private fun StatusPill(text: String) { Surface(color = TvPanel, shape = RoundedCornerShape(50), border = BorderStroke(1.dp, NeonOrange.copy(alpha = .35f))) { Text(text, color = NeonOrange, fontSize = 10.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)) } }

@Composable private fun HeroCard(live: Int, upcoming: Int, channels: Int, loading: Boolean) { Box(Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(28.dp)).background(TvPanel2)) { Column(Modifier.align(Alignment.CenterStart).padding(28.dp)) { Text("LIVE SPORTS", color = NeonOrange, fontSize = 15.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp); Text("Your sports. Your streams. Zero hunting.", color = TvText, fontSize = 31.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(top = 7.dp)); Text("$live live  •  $upcoming upcoming  •  $channels channels${if (loading) "  •  loading" else ""}", color = TvMuted, fontSize = 14.sp, modifier = Modifier.padding(top = 9.dp)) } } }

@Composable private fun SectionTitle(title: String, detail: String) { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) { Text(title, color = TvText, fontSize = 22.sp, fontWeight = FontWeight.Black); Spacer(Modifier.width(14.dp)); Text(detail, color = TvMuted, fontSize = 12.sp) } }

@Composable private fun SportRail(selected: String, onSport: (String) -> Unit) { LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(8.dp)) { items(SportsCatalog.categories) { sport -> Surface(onClick = { onSport(sport) }, modifier = Modifier.widthIn(min = 145.dp).height(62.dp).focusable(), shape = RoundedCornerShape(18.dp), color = if (selected == sport) NeonOrange.copy(alpha = .20f) else TvPanel, border = BorderStroke(if (selected == sport) 3.dp else 1.dp, if (selected == sport) NeonOrange else Color(0xFF2A2E32))) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(sport, color = if (selected == sport) NeonOrange else TvText, fontSize = 16.sp, fontWeight = FontWeight.Bold) } } } } }

@Composable private fun EventRail(activity: Activity, events: List<SportsEvent>, channels: List<SportsChannel>, nowMs: Long) { LazyRow(horizontalArrangement = Arrangement.spacedBy(18.dp), contentPadding = PaddingValues(8.dp)) { items(events, key = { "event-${it.id}" }) { event -> val matches = remember(event.id, channels) { GameSourceMatcher.rankMatches(event, channels, 3) }; val monsterJam = isMonsterJamEvent(event); Card(modifier = Modifier.width(330.dp).height(210.dp).focusable(), shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = TvPanel), border = BorderStroke(1.dp, NeonOrange.copy(alpha = .35f))) { Column(Modifier.padding(18.dp)) { Text(if (isLiveEvent(event, nowMs)) "● LIVE" else formatEventClock(event.startTime), color = NeonOrange, fontSize = 11.sp, fontWeight = FontWeight.Black); Text(SportsPresentation.matchup(event), color = TvText, fontSize = 19.sp, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 7.dp)); Text(event.league, color = TvMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp)); Spacer(Modifier.weight(1f)); if (monsterJam) { Button(onClick = { openMonsterJamYouTube(activity) }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(7.dp)); Text(if (isLiveEvent(event, nowMs)) "WATCH LIVE ON YOUTUBE" else "WATCH ON YOUTUBE") } } else Row(verticalAlignment = Alignment.CenterVertically) { Text(if (matches.isEmpty()) "NO MATCH" else "${matches.size} STREAMS", color = NeonOrange, fontSize = 11.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.weight(1f)); if (matches.isNotEmpty()) TextButton(onClick = { playTvChannel(activity, matches.first().channel) }) { Text("WATCH  ›", color = NeonOrange) } } } } } } }

@Composable private fun ChannelRail(activity: Activity, channels: List<SportsChannel>) { LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(8.dp)) { items(channels, key = { "channel-${it.id}" }) { channel -> Card(modifier = Modifier.width(245.dp).height(115.dp).focusable(), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = TvPanel), border = BorderStroke(1.dp, NeonOrange.copy(alpha = .35f)), onClick = { playTvChannel(activity, channel) }) { Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.Center) { Text("LIVE TV", color = NeonOrange, fontSize = 10.sp, fontWeight = FontWeight.Black); Text(channel.name, color = TvText, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp)); Text(channel.group, color = TvMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp)) } } } } }

@Composable private fun ActionCard(channel: SportsChannel, onClick: () -> Unit) { Card(modifier = Modifier.fillMaxWidth().height(100.dp), onClick = onClick, colors = CardDefaults.cardColors(containerColor = TvPanel), border = BorderStroke(1.dp, NeonOrange.copy(alpha = .45f))) { Row(Modifier.fillMaxSize().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.History, null, tint = NeonOrange, modifier = Modifier.size(32.dp)); Spacer(Modifier.width(18.dp)); Column(Modifier.weight(1f)) { Text("LAST CHANNEL", color = NeonOrange, fontSize = 10.sp, fontWeight = FontWeight.Black); Text(channel.name, color = TvText, fontSize = 18.sp, fontWeight = FontWeight.Bold); Text("Press OK to resume", color = TvMuted, fontSize = 11.sp) }; Icon(Icons.Default.PlayArrow, null, tint = NeonOrange) } } }

private fun playTvChannel(activity: Activity, channel: SportsChannel) { LastChannelStore.save(activity, channel); activity.startActivity(Intent(activity, RichPlayerActivity::class.java).putExtra(RichPlayerActivity.EXTRA_URL, channel.url)) }
private fun isLiveEvent(event: SportsEvent, nowMs: Long): Boolean { val start = parseEventInstant(event.startTime) ?: return false; return nowMs in (start.toEpochMilli() - 15 * 60_000L)..(start.toEpochMilli() + 4 * 60 * 60_000L) }
private fun parseEventInstant(value: String): Instant? = runCatching { Instant.parse(value) }.getOrNull()
private fun formatEventClock(value: String): String = runCatching { DateTimeFormatter.ISO_INSTANT.format(Instant.parse(value)) }.getOrDefault(value.take(16).replace('T', ' '))

private object LastChannelStore {
    private const val PREFS = "usportz_tv_state"
    private const val KEY_ID = "last_id"
    private const val KEY_NAME = "last_name"
    private const val KEY_GROUP = "last_group"
    private const val KEY_LOGO = "last_logo"
    private const val KEY_URL = "last_url"
    fun save(activity: Activity, channel: SportsChannel) { activity.getSharedPreferences(PREFS, 0).edit().putString(KEY_ID, channel.id).putString(KEY_NAME, channel.name).putString(KEY_GROUP, channel.group).putString(KEY_LOGO, channel.logo).putString(KEY_URL, channel.url).apply() }
    fun load(activity: Activity): SportsChannel? { val p = activity.getSharedPreferences(PREFS, 0); val url = p.getString(KEY_URL, null)?.takeIf { it.isNotBlank() } ?: return null; return SportsChannel(p.getString(KEY_ID, "last") ?: "last", p.getString(KEY_NAME, "Last Channel") ?: "Last Channel", p.getString(KEY_GROUP, "Live TV") ?: "Live TV", p.getString(KEY_LOGO, null), url) }
}
