package com.usportz.app

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TvMainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { TvUSportzApp(this) }
    }
}

@Composable
private fun TvUSportzApp(context: Context) {
    val scope = rememberCoroutineScope()
    val favs = remember { Favs(context) }
    var tab by remember { mutableIntStateOf(0) }
    var sport by remember { mutableStateOf("All") }
    var channels by remember { mutableStateOf(emptyList<SportsChannel>()) }
    var events by remember { mutableStateOf(emptyList<SportsEvent>()) }
    var loading by remember { mutableStateOf(false) }
    var playerUrl by remember { mutableStateOf<String?>(null) }

    fun refresh(force: Boolean = false) {
        scope.launch {
            loading = true
            channels = SportsChannelBridge.load(context, force)
            events = runCatching { SportsSchedule.load(force, channels) }.getOrDefault(events)
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        val local = SportsChannelBridge.restoreCached(context)
        if (local.isNotEmpty()) channels = local
        refresh(false)
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF48B9FF), secondary = Color(0xFF9B5CFF), background = Color(0xFF070B10), surface = Color(0xFF101820))) {
        if (playerUrl != null) {
            TvPlayer(playerUrl!!) { playerUrl = null }
        } else {
            Row(Modifier.fillMaxSize().background(Color(0xFF070B10)).padding(28.dp)) {
                TvRail(tab) { tab = it }
                Spacer(Modifier.width(28.dp))
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text("USportz", fontSize = 38.sp, fontWeight = FontWeight.ExtraBold)
                            Text("10-foot sports command center", color = Color.Gray, fontSize = 17.sp)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (loading) "Updating…" else "${channels.size} channels • ${events.size} events", color = Color.Gray, fontSize = 14.sp)
                            Spacer(Modifier.width(14.dp))
                            TvButton("Refresh", Icons.Default.Refresh, onClick = { refresh(true) })
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                    when (tab) {
                        0 -> TvHome(events, channels, favs) { playerUrl = it }
                        1 -> TvSports(sport, { sport = it }, events, channels, favs) { playerUrl = it }
                        2 -> TvChannels(channels, favs) { playerUrl = it }
                        else -> TvFavorites(events, channels, favs) { playerUrl = it }
                    }
                }
            }
        }
    }
}

@Composable private fun TvRail(selected: Int, onSelect: (Int) -> Unit) {
    Column(Modifier.width(150.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("MENU", color = Color.Gray, fontWeight = FontWeight.Bold)
        listOf("Home" to Icons.Default.Home, "Sports" to Icons.Default.SportsScore, "Live TV" to Icons.Default.LiveTv, "Favorites" to Icons.Default.Star).forEachIndexed { i, item ->
            TvButton(item.first, item.second, selected == i) { onSelect(i) }
        }
    }
}

@Composable private fun TvHome(events: List<SportsEvent>, channels: List<SportsChannel>, favs: Favs, play: (String) -> Unit) {
    val live = SportsSchedule.liveEvents(events)
    val upcoming = SportsSchedule.upcomingEvents(events)
    LazyColumn(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { TvHero(live.size, upcoming.size) }
        if (live.isNotEmpty()) { item { TvSection("Live Now") }; item { TvEventRow(live.take(12), channels, favs, play) } }
        if (upcoming.isNotEmpty()) { item { TvSection("Coming Up") }; item { TvEventRow(upcoming.take(12), channels, favs, play) } }
        if (events.isEmpty()) item { TvEmpty("No schedule available", "Refresh to load the latest public sports scoreboard.") }
        item { TvSection("Sports") }
        item { TvSportRow() }
        item { TvSection("Your Channels") }
        item { if (channels.isEmpty()) TvEmpty("No channels loaded", "Connect Xtream Codes or M3U/M3U8 from the mobile app.") else TvChannelRow(channels.take(12), favs, play) }
    }
}

@Composable private fun TvSports(selected: String, onSelected: (String) -> Unit, events: List<SportsEvent>, channels: List<SportsChannel>, favs: Favs, play: (String) -> Unit) {
    val index = remember(channels) { ChannelIndex(channels, { it.name }, { it.group }) }
    val filteredEvents = SportsSchedule.forSport(events, selected)
    val filteredChannels = index.forSport(selected)
    Column(Modifier.fillMaxSize()) {
        TvSection("Sports")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) { items(SportsCatalog.categories) { TvFilter(it, selected == it) { onSelected(it) } } }
        Spacer(Modifier.height(18.dp))
        if (filteredEvents.isNotEmpty()) { TvSection("Events"); TvEventRow(filteredEvents.take(20), channels, favs, play) } else TvEmpty("No events in this sport", "Try another sport or refresh the schedule.")
        Spacer(Modifier.height(18.dp))
        if (filteredChannels.isNotEmpty()) TvChannelList(filteredChannels, favs, play) else TvEmpty("No matching channels", "Your source may not contain channels for this sport.")
    }
}

@Composable private fun TvChannels(channels: List<SportsChannel>, favs: Favs, play: (String) -> Unit) {
    val index = remember(channels) { ChannelIndex(channels, { it.name }, { it.group }) }
    Column(Modifier.fillMaxSize()) { TvSection("Live TV"); if (channels.isEmpty()) TvEmpty("No channels loaded", "Connect Xtream Codes or M3U/M3U8 from the mobile app.") else TvChannelList(index.all(), favs, play) }
}

@Composable private fun TvFavorites(events: List<SportsEvent>, channels: List<SportsChannel>, favs: Favs, play: (String) -> Unit) {
    val eventFavs = events.filter { favs.isEventFav(it.id) }
    val channelFavs = channels.filter { favs.isChannelFav(it.id) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { TvSection("Favorites") }
        if (eventFavs.isNotEmpty()) { item { TvSection("Events") }; item { TvEventRow(eventFavs, channels, favs, play) } }
        if (channelFavs.isNotEmpty()) { item { TvSection("Channels") }; item { TvChannelList(channelFavs, favs, play) } }
        if (eventFavs.isEmpty() && channelFavs.isEmpty()) item { TvEmpty("No favorites", "Favorite an event or channel on mobile or TV and it will appear here.") }
    }
}

@Composable private fun TvHero(live: Int, upcoming: Int) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Brush.linearGradient(listOf(Color(0xFF102A3A), Color(0xFF211535)))).padding(28.dp)) {
        Text("LIVE SPORTS", color = Color(0xFF65C9FF), fontWeight = FontWeight.Bold)
        Text("What's on now", fontSize = 31.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(top = 8.dp))
        Text("$live live • $upcoming upcoming", color = Color.Gray, fontSize = 17.sp, modifier = Modifier.padding(top = 7.dp))
    }
}

@Composable private fun TvEventRow(events: List<SportsEvent>, channels: List<SportsChannel>, favs: Favs, play: (String) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) { items(events, key = { it.id }) { event ->
        val channel = SportsChannelBridge.bestMatch(event, channels)
        TvEventCard(event, channel, favs.isEventFav(event.id), { favs.toggleEvent(event.id) }, play)
    } }
}

@Composable private fun TvEventCard(event: SportsEvent, channel: SportsChannel?, favorite: Boolean, toggleFavorite: () -> Unit, play: (String) -> Unit) {
    val live = event.state == "in"
    val time = formatEventTime(event.startTime)
    Column(Modifier.width(300.dp).height(215.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xFF101820)).border(1.dp, Color(0xFF253143), RoundedCornerShape(18.dp)).padding(17.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!event.leagueLogo.isNullOrBlank()) AsyncImage(event.leagueLogo, event.league, Modifier.size(28.dp), contentScale = ContentScale.Fit)
                Spacer(Modifier.width(7.dp))
                Text(SportsPresentation.label(event).uppercase(), color = Color(0xFF65C9FF), fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (live) "LIVE" else event.detail.ifBlank { time.ifBlank { "UPCOMING" } }, color = if (live) Color(0xFF8BD7FF) else Color.Gray, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                Spacer(Modifier.width(5.dp))
                TvButton(if (favorite) "★" else "☆", Icons.Default.Star, onClick = toggleFavorite)
            }
        }
        Text(event.shortName.ifBlank { event.name }, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 9.dp))
        if (event.competitors.isNotEmpty()) Text(event.competitors.joinToString("  •  "), color = Color.LightGray, fontSize = 13.sp, maxLines = 2, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(8.dp))
        if (channel != null) TvButton(if (live) "WATCH LIVE • ${channel.name}" else "WATCH • ${channel.name}", Icons.Default.PlayArrow) { play(channel.url) } else Text("No matched channel", color = Color.Gray, fontSize = 12.sp)
    }
}

@Composable private fun TvSportRow() { LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) { items(SportsCatalog.categories.filter { it != "All" }) { TvTile(it) } } }
@Composable private fun TvFilter(text: String, selected: Boolean, onClick: () -> Unit) { TvButton(text, Icons.Default.Sports, selected, onClick) }
@Composable private fun TvTile(text: String) { TvButton(text, Icons.Default.Sports, false) {} }

@Composable private fun TvChannelRow(channels: List<SportsChannel>, favs: Favs, play: (String) -> Unit) { LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) { items(channels, key = { it.id }) { TvChannelCard(it, favs.isChannelFav(it.id), favs, play) } } }
@Composable private fun TvChannelList(channels: List<SportsChannel>, favs: Favs, play: (String) -> Unit) { LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) { items(channels, key = { it.id }) { TvChannelCard(it, favs.isChannelFav(it.id), favs, play) } } }

@Composable private fun TvChannelCard(channel: SportsChannel, favorite: Boolean, favs: Favs, play: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFF101820)).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        if (!channel.logo.isNullOrBlank()) AsyncImage(channel.logo, channel.name, Modifier.size(42.dp), contentScale = ContentScale.Fit) else Icon(Icons.Default.LiveTv, null, modifier = Modifier.size(32.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) { Text(channel.name, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(channel.group.ifBlank { "Live TV" }, color = Color.Gray, fontSize = 13.sp) }
        TvButton(if (favorite) "★" else "☆", Icons.Default.Star) { favs.toggleChannel(channel.id) }
        Spacer(Modifier.width(8.dp))
        TvButton("Play", Icons.Default.PlayArrow) { play(channel.url) }
    }
}

@Composable private fun TvButton(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean = false, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(Modifier.height(48.dp).clip(RoundedCornerShape(12.dp)).background(if (selected) Color(0xFF17425B) else Color(0xFF16242E)).border(if (focused) TvUi.focusBorder else androidx.compose.foundation.BorderStroke(1.dp, Color.Transparent), RoundedCornerShape(12.dp)).onFocusChanged { focused = it.isFocused }.focusable().clickable(onClick = onClick).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, modifier = Modifier.size(19.dp)); Spacer(Modifier.width(6.dp)); Text(text, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable private fun TvSection(text: String) { Text(text, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold) }
@Composable private fun TvEmpty(title: String, message: String) { Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) { Column(Modifier.padding(24.dp)) { Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold); Text(message, color = Color.Gray, modifier = Modifier.padding(top = 7.dp)) } } }

@Composable private fun TvPlayer(url: String, onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val player = remember(url) { ExoPlayer.Builder(context).build().apply { setMediaItem(MediaItem.fromUri(url)); prepare(); playWhenReady = true } }
    DisposableEffect(player) { onDispose { player.release() } }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { PlayerView(it).apply { this.player = player; useController = true } }, modifier = Modifier.fillMaxSize())
        Box(Modifier.padding(20.dp)) { TvButton("Back", Icons.Default.ArrowBack) { onBack() } }
    }
}

private fun formatEventTime(value: String): String = runCatching {
    val parsed = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.parse(value) ?: return ""
    SimpleDateFormat("EEE h:mm a", Locale.US).format(Date(parsed.time))
}.getOrElse { "" }