package com.usportz.app

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

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
    val lastChannel = remember { LastChannelStore.load(activity) }

    suspend fun refresh(force: Boolean) {
        refreshing = force
        val loadedChannels = withContext(Dispatchers.IO) { SportsChannelBridge.load(activity, force) }
        val loadedEvents = withContext(Dispatchers.IO) { runCatching { SportsSchedule.load(force, loadedChannels) }.getOrDefault(emptyList()) }
        channels = loadedChannels
        events = loadedEvents
        loading = false
        refreshing = false
    }

    LaunchedEffect(Unit) {
        runCatching { refresh(false) }
        while (true) {
            delay(60_000)
            nowMs = System.currentTimeMillis()
            runCatching { refresh(false) }
        }
    }

    val live = remember(events, nowMs, selectedSport) {
        SportsSchedule.forSport(events, selectedSport).filter { isLiveEvent(it, nowMs) }.take(30)
    }
    val upcoming = remember(events, nowMs, selectedSport) {
        SportsSchedule.forSport(events, selectedSport).filter { !isLiveEvent(it, nowMs) }.take(30)
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = NeonOrange, secondary = NeonOrangeSoft, background = TvBlack, surface = TvPanel)) {
        TvRoot(activity, loading, refreshing, channels, events, live, upcoming, selectedSport, nowMs, lastChannel, { selectedSport = it }, { runCatching { refresh(true) } })
    }
}

@Composable
private fun TvRoot(
    activity: Activity,
    loading: Boolean,
    refreshing: Boolean,
    channels: List<SportsChannel>,
    events: List<SportsEvent>,
    live: List<SportsEvent>,
    upcoming: List<SportsEvent>,
    selectedSport: String,
    nowMs: Long,
    lastChannel: SportsChannel?,
    onSport: (String) -> Unit,
    onRefresh: () -> Unit
) {
    val rootRequester = remember { FocusRequester() }
    var focusedLabel by remember { mutableStateOf("HOME") }

    LazyColumn(
        Modifier.fillMaxSize().background(TvBlack).focusRequester(rootRequester).focusable(),
        contentPadding = PaddingValues(start = 44.dp, end = 44.dp, top = 30.dp, bottom = 70.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("USPORTZ", color = TvText, fontSize = 38.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                    Text("SPORTS COMMAND CENTER  /  TV", color = NeonOrange, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                }
                TvStatus("${channels.size} CHANNELS")
                Spacer(Modifier.width(12.dp))
                TvStatus(if (refreshing) "SYNCING" else "LIVE")
                Spacer(Modifier.width(12.dp))
                TvFocusButton("REFRESH", Icons.Default.Refresh, onRefresh, focusedLabel, { focusedLabel = "REFRESH" })
            }
        }
        item { TvHero(live.size, upcoming.size, channels.size, loading) }
        if (lastChannel != null) item {
            TvActionCard("LAST CHANNEL", lastChannel.name, "Press OK to resume the last watched channel") {
                playTvChannel(activity, lastChannel)
            }
        }
        item { TvSectionTitle("SPORTS", "D-pad left/right • OK to select") }
        item { TvSportRail(selectedSport, onSport, focusedLabel) { focusedLabel = it } }
        item { TvSectionTitle("LIVE NOW", "${live.size} events") }
        item { TvEventRail(activity, live, channels, nowMs, focusedLabel) { focusedLabel = it } }
        item { TvSectionTitle("UP NEXT", "${upcoming.size} events") }
        item { TvEventRail(activity, upcoming, channels, nowMs, focusedLabel) { focusedLabel = it } }
        item { TvSectionTitle("QUICK CHANNELS", "D-pad left/right • OK to play") }
        item { TvChannelRail(activity, channels.take(40), focusedLabel) { focusedLabel = it } }
        item {
            Text(
                "BACK = previous screen   •   HOME = Android TV Home   •   OK = play   •   LEFT / RIGHT = rails   •   UP / DOWN = sections",
                color = TvMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

@Composable
private fun TvHero(live: Int, upcoming: Int, channels: Int, loading: Boolean) {
    Box(Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(28.dp)).background(TvPanel2).border(1.dp, NeonOrange.copy(alpha = .35f), RoundedCornerShape(28.dp))) {
        Column(Modifier.padding(28.dp).align(Alignment.CenterStart)) {
            Text("LIVE SPORTS", color = NeonOrange, fontSize = 15.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
            Text("Your sports. Your streams. Zero hunting.", color = TvText, fontSize = 31.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(top = 7.dp))
            Text("$live live  •  $upcoming upcoming  •  $channels channels${if (loading) "  •  loading" else ""}", color = TvMuted, fontSize = 14.sp, modifier = Modifier.padding(top = 9.dp))
        }
        Box(Modifier.align(Alignment.BottomEnd).padding(28.dp).size(110.dp).border(2.dp, NeonOrange, RoundedCornerShape(55.dp)))
    }
}

@Composable
private fun TvSectionTitle(title: String, detail: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Text(title, color = TvText, fontSize = 22.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
        Spacer(Modifier.width(14.dp))
        Text(detail, color = TvMuted, fontSize = 12.sp)
    }
}

@Composable
private fun TvSportRail(selected: String, onSport: (String) -> Unit, focused: String, onFocus: (String) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(8.dp)) {
        items(SportsCatalog.categories) { sport ->
            TvFocusable("SPORT:$sport", focused, onFocus) { focusedHere ->
                Surface(
                    Modifier.widthIn(min = 145.dp).height(62.dp),
                    shape = RoundedCornerShape(18.dp),
                    color = if (selected == sport) NeonOrange.copy(alpha = .20f) else TvPanel,
                    border = BorderStroke(if (focusedHere || selected == sport) 3.dp else 1.dp, if (focusedHere || selected == sport) NeonOrange else Color.Transparent),
                    onClick = { onSport(sport) }
                ) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(sport, color = if (selected == sport) NeonOrange else TvText, fontSize = 16.sp, fontWeight = FontWeight.Bold) } }
            }
        }
    }
}

@Composable
private fun TvEventRail(activity: Activity, events: List<SportsEvent>, channels: List<SportsChannel>, nowMs: Long, focused: String, onFocus: (String) -> Unit) {
    val state = rememberLazyListState()
    LazyRow(state = state, horizontalArrangement = Arrangement.spacedBy(18.dp), contentPadding = PaddingValues(8.dp)) {
        items(events, key = { "tv-event-${it.id}" }) { event ->
            val matches = remember(event.id, channels) { GameSourceMatcher.rankMatches(event, channels, 3) }
            TvFocusable("EVENT:${event.id}", focused, onFocus) { focusedHere ->
                Card(
                    Modifier.width(330.dp).height(190.dp),
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = if (focusedHere) Color(0xFF25282B) else TvPanel),
                    border = BorderStroke(if (focusedHere) 4.dp else 1.dp, if (focusedHere) NeonOrange else Color(0xFF2A2E32))
                ) {
                    Column(Modifier.padding(18.dp)) {
                        Text(if (isLiveEvent(event, nowMs)) "● LIVE" else formatClock(event.startTime), color = if (isLiveEvent(event, nowMs)) NeonOrange else NeonOrangeSoft, fontSize = 11.sp, fontWeight = FontWeight.Black)
                        Text(SportsPresentation.matchup(event), color = TvText, fontSize = 19.sp, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 7.dp))
                        Text(event.league, color = TvMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp))
                        Spacer(Modifier.weight(1f))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (matches.isEmpty()) "NO MATCH" else "${matches.size} STREAMS", color = NeonOrange, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.weight(1f))
                            if (matches.isNotEmpty()) TextButton(onClick = { playTvChannel(activity, matches.first().channel) }) { Text("WATCH  ›", color = NeonOrange) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TvChannelRail(activity: Activity, channels: List<SportsChannel>, focused: String, onFocus: (String) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(8.dp)) {
        items(channels, key = { "tv-channel-${it.id}" }) { channel ->
            TvFocusable("CHANNEL:${channel.id}", focused, onFocus) { focusedHere ->
                Card(
                    Modifier.width(245.dp).height(115.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = if (focusedHere) Color(0xFF25282B) else TvPanel),
                    border = BorderStroke(if (focusedHere) 4.dp else 1.dp, if (focusedHere) NeonOrange else Color(0xFF2A2E32)),
                    onClick = { playTvChannel(activity, channel) }
                ) { Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.Center) {
                    Text("LIVE TV", color = NeonOrange, fontSize = 10.sp, fontWeight = FontWeight.Black)
                    Text(channel.name, color = TvText, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp))
                    Text(channel.group, color = TvMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                } }
            }
        }
    }
}

@Composable
private fun TvActionCard(label: String, title: String, hint: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().height(100.dp).focusable().onFocusChanged { }, onClick = onClick, colors = CardDefaults.cardColors(containerColor = TvPanel), border = BorderStroke(1.dp, NeonOrange.copy(alpha = .45f))) {
        Row(Modifier.fillMaxSize().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.History, null, tint = NeonOrange, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) { Text(label, color = NeonOrange, fontSize = 10.sp, fontWeight = FontWeight.Black); Text(title, color = TvText, fontSize = 18.sp, fontWeight = FontWeight.Bold); Text(hint, color = TvMuted, fontSize = 11.sp) }
            Icon(Icons.Default.PlayArrow, null, tint = NeonOrange)
        }
    }
}

@Composable
private fun TvStatus(text: String) {
    Surface(color = TvPanel, shape = RoundedCornerShape(50), border = BorderStroke(1.dp, NeonOrange.copy(alpha = .35f))) { Text(text, color = NeonOrange, fontSize = 10.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)) }
}

@Composable
private fun TvFocusButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit, focused: String, setFocus: () -> Unit) {
    TvFocusable(label, focused, { setFocus() }) { focusedHere ->
        OutlinedButton(onClick = onClick, modifier = Modifier.height(54.dp), border = BorderStroke(if (focusedHere) 3.dp else 1.dp, if (focusedHere) NeonOrange else Color(0xFF3A3F43)), colors = ButtonDefaults.outlinedButtonColors(contentColor = TvText)) {
            Icon(icon, null); Spacer(Modifier.width(7.dp)); Text(label, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun TvFocusable(id: String, focused: String, onFocus: (String) -> Unit, content: @Composable (Boolean) -> Unit) {
    var hasFocus by remember { mutableStateOf(false) }
    Box(Modifier.onFocusChanged { hasFocus = it.hasFocus; if (it.hasFocus) onFocus(id) }.focusable()) { content(hasFocus || focused == id) }
}

private fun playTvChannel(activity: Activity, channel: SportsChannel) {
    LastChannelStore.save(activity, channel)
    activity.startActivity(Intent(activity, RichPlayerActivity::class.java).putExtra(RichPlayerActivity.EXTRA_URL, channel.url))
}

private fun isLiveEvent(event: SportsEvent, nowMs: Long): Boolean {
    val start = parseInstant(event.startTime)?.toEpochMilli() ?: return false
    return nowMs in (start - 15 * 60_000L)..(start + 4 * 60 * 60_000L)
}

private object LastChannelStore {
    private const val PREFS = "usportz_tv_state"
    private const val KEY_ID = "last_id"
    private const val KEY_NAME = "last_name"
    private const val KEY_GROUP = "last_group"
    private const val KEY_LOGO = "last_logo"
    private const val KEY_URL = "last_url"

    fun save(activity: Activity, channel: SportsChannel) = activity.getSharedPreferences(PREFS, 0).edit()
        .putString(KEY_ID, channel.id).putString(KEY_NAME, channel.name).putString(KEY_GROUP, channel.group)
        .putString(KEY_LOGO, channel.logo).putString(KEY_URL, channel.url).apply()

    fun load(activity: Activity): SportsChannel? {
        val p = activity.getSharedPreferences(PREFS, 0)
        val url = p.getString(KEY_URL, null)?.takeIf { it.isNotBlank() } ?: return null
        return SportsChannel(p.getString(KEY_ID, "last") ?: "last", p.getString(KEY_NAME, "Last Channel") ?: "Last Channel", p.getString(KEY_GROUP, "Live TV") ?: "Live TV", p.getString(KEY_LOGO, null), url)
    }
}
