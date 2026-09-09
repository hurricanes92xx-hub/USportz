package com.usportz.app

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.draw.scale
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

private val TvBg = Color(0xFF05080D)
private val TvSurface = Color(0xFF0D141D)
private val TvSurface2 = Color(0xFF131D28)
private val TvAccent = Color(0xFF55C7FF)
private val TvPurple = Color(0xFF9B72FF)

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
        val cached = SportsChannelBridge.restoreCached(context)
        if (cached.isNotEmpty()) channels = cached
        refresh(false)
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = TvAccent, secondary = TvPurple, background = TvBg, surface = TvSurface)) {
        if (playerUrl != null) {
            TvPlayer(playerUrl!!) { playerUrl = null }
            return@MaterialTheme
        }

        Row(Modifier.fillMaxSize().background(TvBg).padding(horizontal = 48.dp, vertical = 30.dp)) {
            TvRail(tab) { tab = it }
            Spacer(Modifier.width(34.dp))
            Column(Modifier.weight(1f).fillMaxHeight()) {
                TvTopBar(loading, channels.size, events.size) { refresh(true) }
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

@Composable
private fun TvRail(selected: Int, onSelect: (Int) -> Unit) {
    Column(Modifier.width(168.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("USPORTZ", color = TvAccent, fontSize = 24.sp, fontWeight = FontWeight.Black)
        Text("PREMIUM TV", color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(20.dp))
        listOf("Home" to Icons.Default.Home, "Sports" to Icons.Default.SportsScore, "Live TV" to Icons.Default.LiveTv, "Favorites" to Icons.Default.Star).forEachIndexed { i, item ->
            TvNavButton(item.first, item.second, selected == i) { onSelect(i) }
        }
        Spacer(Modifier.weight(1f))
        Text("D-PAD READY", color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun TvNavButton(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, onClick: () -> Unit) {
    TvFocusable(onClick = onClick) { focused ->
        Row(Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(16.dp)).background(if (selected) TvSurface2 else Color.Transparent).padding(horizontal = 15.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = if (selected || focused) TvAccent else Color.Gray, modifier = Modifier.size(23.dp))
            Spacer(Modifier.width(13.dp))
            Text(text, color = if (selected || focused) Color.White else Color.LightGray, fontSize = 16.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
        }
    }
}

@Composable
private fun TvTopBar(loading: Boolean, channelCount: Int, eventCount: Int, refresh: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Column {
            Text("Sports Command Center", fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
            Text("${channelCount} channels  •  ${eventCount} events", color = Color.Gray, fontSize = 14.sp, modifier = Modifier.padding(top = 3.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (loading) Text("UPDATING…", color = TvAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(18.dp))
            TvAction("Refresh", Icons.Default.Refresh, refresh)
        }
    }
}

@Composable
private fun TvHome(events: List<SportsEvent>, channels: List<SportsChannel>, favs: Favs, play: (String) -> Unit) {
    val live = SportsSchedule.liveEvents(events)
    val upcoming = SportsSchedule.upcomingEvents(events)
    LazyColumn(verticalArrangement = Arrangement.spacedBy(25.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
        item { TvHero(live.size, upcoming.size) }
        if (live.isNotEmpty()) {
            item { TvSection("LIVE NOW", "Watch events matched to your TV source") }
            item { TvEventRow(live.take(14), channels, favs, play) }
        }
        item { TvSection("SPORTS", "Jump straight into a category") }
        item { TvSportRow() }
        if (upcoming.isNotEmpty()) {
            item { TvSection("UP NEXT", "The next games and events") }
            item { TvEventRow(upcoming.take(14), channels, favs, play) }
        }
        item { TvSection("YOUR TV", "Channels from Xtream / M3U") }
        item { if (channels.isEmpty()) TvEmpty("No TV source connected", "Connect Xtream Codes or M3U/M3U8 from the mobile app.") else TvChannelRow(channels.take(16), favs, play) }
    }
}

@Composable
private fun TvHero(live: Int, upcoming: Int) {
    Row(Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(26.dp)).background(Brush.linearGradient(listOf(Color(0xFF102A3B), Color(0xFF1D1533)))).padding(28.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("LIVE SPORTS", color = TvAccent, fontSize = 13.sp, fontWeight = FontWeight.Black)
            Text("Everything worth watching.", fontSize = 31.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(top = 7.dp))
            Text("$live live now  •  $upcoming coming up", color = Color.LightGray, fontSize = 16.sp, modifier = Modifier.padding(top = 8.dp))
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("FAST", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Black)
            Text("CACHE + LIVE", color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun TvSports(selected: String, onSelected: (String) -> Unit, events: List<SportsEvent>, channels: List<SportsChannel>, favs: Favs, play: (String) -> Unit) {
    val index = remember(channels) { ChannelIndex(channels, { it.name }, { it.group }) }
    val filteredEvents = SportsSchedule.forSport(events, selected)
    val filteredChannels = index.forSport(selected)
    LazyColumn(verticalArrangement = Arrangement.spacedBy(22.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
        item { TvSection("SPORTS", "Choose a league or category") }
        item { LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) { items(SportsCatalog.categories) { TvFilter(it, selected == it) { onSelected(it) } } } }
        if (filteredEvents.isNotEmpty()) {
            item { TvSection(selected.uppercase(), "Events and matched channels") }
            item { TvEventRow(filteredEvents.take(24), channels, favs, play) }
        } else item { TvEmpty("No events here yet", "Try another sport or refresh the schedule.") }
        if (filteredChannels.isNotEmpty()) {
            item { TvSection("CHANNELS", "Matching live channels") }
            item { TvChannelList(filteredChannels.take(80), favs, play) }
        }
    }
}

@Composable
private fun TvChannels(channels: List<SportsChannel>, favs: Favs, play: (String) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TvSection("LIVE TV", "Your complete indexed channel guide")
        Spacer(Modifier.height(18.dp))
        if (channels.isEmpty()) TvEmpty("No channels loaded", "Connect Xtream Codes or M3U/M3U8 from the mobile app.") else TvChannelList(channels, favs, play)
    }
}

@Composable
private fun TvFavorites(events: List<SportsEvent>, channels: List<SportsChannel>, favs: Favs, play: (String) -> Unit) {
    val eventFavs = events.filter { favs.isEventFav(it.id) }
    val channelFavs = channels.filter { favs.isChannelFav(it.id) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(22.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
        item { TvSection("FAVORITES", "Your saved events and channels") }
        if (eventFavs.isNotEmpty()) { item { TvSection("EVENTS", "Saved games and shows") }; item { TvEventRow(eventFavs, channels, favs, play) } }
        if (channelFavs.isNotEmpty()) { item { TvSection("CHANNELS", "Saved live channels") }; item { TvChannelList(channelFavs, favs, play) } }
        if (eventFavs.isEmpty() && channelFavs.isEmpty()) item { TvEmpty("Nothing saved yet", "Favorite an event or channel and it will appear here on every device.") }
    }
}

@Composable
private fun TvEventRow(events: List<SportsEvent>, channels: List<SportsChannel>, favs: Favs, play: (String) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) { items(events, key = { it.id }) { event ->
        val channel = SportsChannelBridge.bestMatch(event, channels)
        TvEventCard(event, channel, favs.isEventFav(event.id), { favs.toggleEvent(event.id) }, play)
    } }
}

@Composable
private fun TvEventCard(event: SportsEvent, channel: SportsChannel?, favorite: Boolean, toggleFavorite: () -> Unit, play: (String) -> Unit) {
    val live = event.state == "in"
    val time = formatEventTime(event.startTime)
    TvFocusable(onClick = { if (channel != null) play(channel.url) }) { focused ->
        Column(Modifier.width(318.dp).height(314.dp).clip(RoundedCornerShape(20.dp)).background(if (focused) Color(0xFF182838) else TvSurface).border(1.dp, if (focused) TvAccent else Color(0xFF243241), RoundedCornerShape(20.dp)).padding(12.dp)) {
            EventArtwork(event = event, brand = SportsPresentation.brand(event), compact = true)
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(SportsPresentation.label(event).uppercase(), color = TvAccent, fontSize = 11.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (live) Text("LIVE", color = Color(0xFFFF6B6B), fontSize = 11.sp, fontWeight = FontWeight.Black)
                    else Text(event.detail.ifBlank { time.ifBlank { "UPCOMING" } }, color = Color.Gray, fontSize = 11.sp, maxLines = 1)
                    Spacer(Modifier.width(6.dp))
                    TvIconButton(if (favorite) Icons.Default.Star else Icons.Default.StarBorder, toggleFavorite)
                }
            }
            Text(event.shortName.ifBlank { event.name }, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 7.dp))
            if (event.competitors.isNotEmpty()) Text(event.competitors.joinToString("  •  "), color = Color.LightGray, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.weight(1f))
            if (channel != null) {
                Text(if (live) "WATCH LIVE" else "WATCH", color = TvAccent, fontSize = 12.sp, fontWeight = FontWeight.Black)
                Text(channel.name, color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            } else Text("No matched channel", color = Color.Gray, fontSize = 11.sp)
        }
    }
}

@Composable
private fun TvSportRow() {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(13.dp)) { items(SportsCatalog.categories.filter { it != "All" }) { TvFilter(it, false) {} } }
}

@Composable
private fun TvFilter(text: String, selected: Boolean, onClick: () -> Unit) {
    TvFocusable(onClick = onClick) { focused ->
        Box(Modifier.height(50.dp).clip(RoundedCornerShape(14.dp)).background(if (selected) Color(0xFF16445E) else TvSurface2).border(1.dp, if (focused) TvAccent else Color.Transparent, RoundedCornerShape(14.dp)).padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
            Text(text, color = if (selected || focused) Color.White else Color.LightGray, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun TvChannelRow(channels: List<SportsChannel>, favs: Favs, play: (String) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(13.dp)) { items(channels, key = { it.id }) { TvChannelCard(it, favs.isChannelFav(it.id), favs, play, compact = true) } }
}

@Composable
private fun TvChannelList(channels: List<SportsChannel>, favs: Favs, play: (String) -> Unit) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(9.dp), contentPadding = PaddingValues(bottom = 35.dp)) { items(channels, key = { it.id }) { TvChannelCard(it, favs.isChannelFav(it.id), favs, play, compact = false) } }
}

@Composable
private fun TvChannelCard(channel: SportsChannel, favorite: Boolean, favs: Favs, play: (String) -> Unit, compact: Boolean) {
    TvFocusable(onClick = { play(channel.url) }) { focused ->
        Row(Modifier.then(if (compact) Modifier.width(280.dp) else Modifier.fillMaxWidth()).height(if (compact) 92.dp else 72.dp).clip(RoundedCornerShape(16.dp)).background(if (focused) Color(0xFF182838) else TvSurface).border(1.dp, if (focused) TvAccent else Color.Transparent, RoundedCornerShape(16.dp)).padding(horizontal = 15.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!channel.logo.isNullOrBlank()) AsyncImage(channel.logo, channel.name, Modifier.size(if (compact) 48.dp else 38.dp), contentScale = ContentScale.Fit) else Icon(Icons.Default.LiveTv, null, tint = TvAccent, modifier = Modifier.size(30.dp))
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(channel.name, fontSize = if (compact) 15.sp else 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(channel.group.ifBlank { "Live TV" }, color = Color.Gray, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TvIconButton(if (favorite) Icons.Default.Star else Icons.Default.StarBorder) { favs.toggleChannel(channel.id) }
            if (!compact) { Spacer(Modifier.width(10.dp)); Icon(Icons.Default.PlayArrow, null, tint = TvAccent) }
        }
    }
}

@Composable
private fun TvAction(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    TvFocusable(onClick = onClick) { focused ->
        Row(Modifier.height(48.dp).clip(RoundedCornerShape(14.dp)).background(TvSurface2).border(1.dp, if (focused) TvAccent else Color.Transparent, RoundedCornerShape(14.dp)).padding(horizontal = 15.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = TvAccent, modifier = Modifier.size(19.dp)); Spacer(Modifier.width(8.dp)); Text(text, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun TvIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    TvFocusable(onClick = onClick) { focused ->
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(if (focused) Color(0xFF203548) else Color.Transparent).border(1.dp, if (focused) TvAccent else Color.Transparent, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = if (focused) TvAccent else Color.Gray, modifier = Modifier.size(19.dp)) }
    }
}

@Composable
private fun TvFocusable(onClick: () -> Unit, content: @Composable (Boolean) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.025f else 1f, label = "tvFocus")
    Box(Modifier.scale(scale).onFocusChanged { focused = it.isFocused }.focusable().clickable(onClick = onClick)) { content(focused) }
}

@Composable
private fun TvSection(title: String, subtitle: String) {
    Column {
        Text(title, fontSize = 21.sp, fontWeight = FontWeight.Black)
        Text(subtitle, color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
    }
}

@Composable
private fun TvEmpty(title: String, message: String) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = TvSurface)) { Column(Modifier.padding(24.dp)) { Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold); Text(message, color = Color.Gray, modifier = Modifier.padding(top = 7.dp)) } }
}

@Composable
private fun TvPlayer(url: String, onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val player = remember(url) { ExoPlayer.Builder(context).build().apply { setMediaItem(MediaItem.fromUri(url)); prepare(); playWhenReady = true } }
    DisposableEffect(player) { onDispose { player.release() } }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { PlayerView(it).apply { this.player = player; useController = true; isFocusable = true } }, modifier = Modifier.fillMaxSize().focusable())
        Box(Modifier.padding(28.dp)) { TvAction("Back", Icons.Default.ArrowBack, onBack) }
    }
}

private fun formatEventTime(value: String): String = runCatching {
    val parsed = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.parse(value) ?: return ""
    SimpleDateFormat("EEE h:mm a", Locale.US).format(Date(parsed.time))
}.getOrElse { "" }
