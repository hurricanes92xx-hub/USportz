package com.usportz.app

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private data class TvChannel(val id: String, val name: String, val group: String, val url: String)

class TvMainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { TvUSportzApp(this) } }
}

@Composable
private fun TvUSportzApp(context: Context) {
    val prefs = remember { context.getSharedPreferences("usportz", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    var sport by remember { mutableStateOf("All") }
    var channels by remember { mutableStateOf(emptyList<TvChannel>()) }
    var events by remember { mutableStateOf(emptyList<SportsEvent>()) }
    var favorites by remember { mutableStateOf(prefs.getStringSet("favorites", emptySet()) ?: emptySet()) }
    var playerUrl by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("Ready") }

    fun refresh() = scope.launch {
        status = "Refreshing…"
        val channelResult = withContext(Dispatchers.IO) { loadChannels(prefs) }
        channels = channelResult.first
        events = SportsSchedule.load()
        status = "${channels.size} channels • ${events.size} events"
    }
    LaunchedEffect(Unit) { refresh() }

    MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF48B9FF), secondary = Color(0xFF8BD7FF), background = Color(0xFF070B10), surface = Color(0xFF101820))) {
        if (playerUrl != null) TvPlayer(playerUrl!!) { playerUrl = null }
        else Row(Modifier.fillMaxSize().background(Color(0xFF070B10)).padding(28.dp)) {
            TvRail(tab) { tab = it }
            Spacer(Modifier.width(28.dp))
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column { Text("USportz", fontSize = 38.sp, fontWeight = FontWeight.ExtraBold); Text("10-foot sports command center", color = Color.Gray, fontSize = 17.sp) }
                    Row(verticalAlignment = Alignment.CenterVertically) { Text(status, color = Color.Gray, fontSize = 14.sp); Spacer(Modifier.width(14.dp)); TvButton("Refresh", Icons.Default.Refresh) { refresh() } }
                }
                Spacer(Modifier.height(24.dp))
                when (tab) {
                    0 -> TvHome(events, channels, favorites, { playerUrl = it }, { id -> favorites = toggleFavorite(prefs, id) })
                    1 -> TvSports(sport, { sport = it }, events, channels, favorites, { playerUrl = it }, { id -> favorites = toggleFavorite(prefs, id) })
                    2 -> TvChannels(channels, favorites, { playerUrl = it }, { id -> favorites = toggleFavorite(prefs, id) })
                    else -> TvFavorites(channels, favorites, { playerUrl = it }, { id -> favorites = toggleFavorite(prefs, id) })
                }
            }
        }
    }
}

@Composable private fun TvRail(selected: Int, onSelect: (Int) -> Unit) {
    Column(Modifier.width(150.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("MENU", color = Color.Gray, fontWeight = FontWeight.Bold)
        listOf("Home" to Icons.Default.Home, "Sports" to Icons.Default.SportsFootball, "Live TV" to Icons.Default.LiveTv, "Favorites" to Icons.Default.Star).forEachIndexed { i, item -> TvButton(item.first, item.second, selected == i) { onSelect(i) } }
    }
}

@Composable private fun TvHome(events: List<SportsEvent>, channels: List<TvChannel>, favorites: Set<String>, play: (String) -> Unit, favorite: (String) -> Unit) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { TvHero(events.count { it.state == "in" }, events.count { it.state == "pre" }) }
        item { TvSection("Live & Upcoming") }
        item { if (events.isEmpty()) TvEmpty("No schedule available", "Refresh to load the latest public sports scoreboard.") else TvEventRow(events.take(16), channels, play) }
        item { TvSection("Sports") }
        item { TvSportRow() }
        item { TvSection("Your Channels") }
        item { if (channels.isEmpty()) TvEmpty("No channels loaded", "Connect Xtream Codes or M3U/M3U8 from the mobile app first.") else TvChannelRow(channels.take(12), favorites, play, favorite) }
    }
}

@Composable private fun TvSports(selected: String, onSelected: (String) -> Unit, events: List<SportsEvent>, channels: List<TvChannel>, favorites: Set<String>, play: (String) -> Unit, favorite: (String) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TvSection("Sports")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) { items(SportsCatalog.categories) { TvFilter(it, selected == it) { onSelected(it) } } }
        Spacer(Modifier.height(18.dp))
        val filteredEvents = if (selected == "All") events else events.filter { SportsCatalog.classify(it.name, it.sport) == selected || it.sport.equals(selected, true) }
        if (filteredEvents.isNotEmpty()) { TvSection("Events"); TvEventRow(filteredEvents.take(20), channels, play) }
        Spacer(Modifier.height(18.dp))
        val filteredChannels = ChannelIndex(channels, { it.name }, { it.group }).forSport(selected)
        if (filteredChannels.isEmpty()) TvEmpty("No matching channels", "The schedule can still show events even when your source has no matching channel.") else TvChannelList(filteredChannels, favorites, play, favorite)
    }
}

@Composable private fun TvChannels(channels: List<TvChannel>, favorites: Set<String>, play: (String) -> Unit, favorite: (String) -> Unit) {
    Column(Modifier.fillMaxSize()) { TvSection("Live TV"); if (channels.isEmpty()) TvEmpty("No channels loaded", "Connect Xtream Codes or M3U/M3U8 from the mobile app first.") else TvChannelList(channels, favorites, play, favorite) }
}

@Composable private fun TvFavorites(channels: List<TvChannel>, favorites: Set<String>, play: (String) -> Unit, favorite: (String) -> Unit) {
    val list = channels.filter { favorites.contains(it.id) }
    Column(Modifier.fillMaxSize()) { TvSection("Favorites"); if (list.isEmpty()) TvEmpty("No favorites", "Select the star on a channel to keep it here.") else TvChannelList(list, favorites, play, favorite) }
}

@Composable private fun TvHero(live: Int, upcoming: Int) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Color(0xFF102A3A)).padding(28.dp)) {
        Text("LIVE SPORTS", color = Color(0xFF65C9FF), fontWeight = FontWeight.Bold)
        Text("What's on now", fontSize = 31.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(top = 8.dp))
        Text("$live live • $upcoming upcoming", color = Color.Gray, fontSize = 17.sp, modifier = Modifier.padding(top = 7.dp))
    }
}

@Composable private fun TvEventRow(events: List<SportsEvent>, channels: List<TvChannel>, play: (String) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) { items(events, key = { it.id }) { event ->
        val best = channels.map { it to SportsSchedule.matchChannel(event, it.name, it.group) }.maxByOrNull { it.second }
        TvEventCard(event, best?.takeIf { it.second >= 3 }?.first, play)
    } }
}

@Composable private fun TvEventCard(event: SportsEvent, channel: TvChannel?, play: (String) -> Unit) {
    Column(Modifier.width(270.dp).height(145.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF101820)).padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(event.league.uppercase(), color = Color(0xFF65C9FF), fontWeight = FontWeight.Bold); Text(if (event.state == "in") "LIVE" else event.detail.ifBlank { "UPCOMING" }, color = if (event.state == "in") Color(0xFF8BD7FF) else Color.Gray) }
        Text(event.shortName.ifBlank { event.name }, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 2, modifier = Modifier.padding(top = 10.dp))
        if (channel != null) TvButton("Watch ${channel.name}", Icons.Default.PlayArrow) { play(channel.url) }
        else Text("No matched channel", color = Color.Gray, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
    }
}

@Composable private fun TvSportRow() { LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) { items(SportsCatalog.categories.filter { it != "All" }.take(10)) { TvTile(it) } } }

@Composable private fun TvFilter(text: String, selected: Boolean, onClick: () -> Unit) { TvButton(text, Icons.Default.Sports, selected, onClick) }
@Composable private fun TvTile(text: String) { TvButton(text, Icons.Default.Sports, false) {} }

@Composable private fun TvChannelRow(channels: List<TvChannel>, favorites: Set<String>, play: (String) -> Unit, favorite: (String) -> Unit) { LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) { items(channels, key = { it.id }) { TvChannelCard(it, favorites.contains(it.id), play, favorite) } } }
@Composable private fun TvChannelList(channels: List<TvChannel>, favorites: Set<String>, play: (String) -> Unit, favorite: (String) -> Unit) { LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) { items(channels, key = { it.id }) { TvChannelCard(it, favorites.contains(it.id), play, favorite) } } }

@Composable private fun TvChannelCard(channel: TvChannel, isFavorite: Boolean, play: (String) -> Unit, favorite: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFF101820)).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.LiveTv, null, modifier = Modifier.size(32.dp)); Spacer(Modifier.width(14.dp)); Column(Modifier.weight(1f)) { Text(channel.name, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1); Text(channel.group.ifBlank { "Live TV" }, color = Color.Gray, fontSize = 13.sp) }
        TvButton(if (isFavorite) "★" else "☆", Icons.Default.StarBorder) { favorite(channel.id) }; Spacer(Modifier.width(8.dp)); TvButton("Play", Icons.Default.PlayArrow) { play(channel.url) }
    }
}

@Composable private fun TvButton(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean = false, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(Modifier.height(52.dp).clip(RoundedCornerShape(12.dp)).background(if (selected) Color(0xFF17425B) else Color(0xFF16242E)).border(if (focused) TvUi.focusBorder else BorderStroke(1.dp, Color.Transparent), RoundedCornerShape(12.dp)).onFocusChanged { focused = it.isFocused }.focusable().clickable(onClick = onClick).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, modifier = Modifier.size(21.dp)); Spacer(Modifier.width(7.dp)); Text(text, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal) }
}

@Composable private fun TvSection(text: String) { Text(text, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold) }
@Composable private fun TvEmpty(title: String, message: String) { Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) { Column(Modifier.padding(24.dp)) { Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold); Text(message, color = Color.Gray, modifier = Modifier.padding(top = 7.dp)) } } }

@Composable private fun TvPlayer(url: String, onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val player = remember(url) { ExoPlayer.Builder(context).build().apply { setMediaItem(MediaItem.fromUri(url)); prepare(); playWhenReady = true } }
    DisposableEffect(player) { onDispose { player.release() } }
    Box(Modifier.fillMaxSize().background(Color.Black)) { AndroidView(factory = { PlayerView(it).apply { this.player = player; useController = true } }, modifier = Modifier.fillMaxSize()); Box(Modifier.padding(20.dp)) { TvButton("Back", Icons.Default.ArrowBack) { onBack() } } }
}

private fun toggleFavorite(prefs: android.content.SharedPreferences, id: String): Set<String> { val next = (prefs.getStringSet("favorites", emptySet()) ?: emptySet()).toMutableSet(); if (!next.add(id)) next.remove(id); prefs.edit().putStringSet("favorites", next).apply(); return next }

private fun loadChannels(prefs: android.content.SharedPreferences): Pair<List<TvChannel>, String> {
    val server = prefs.getString("server", "") ?: ""; val user = prefs.getString("user", "") ?: ""; val pass = prefs.getString("pass", "") ?: ""; val playlist = prefs.getString("playlist", "") ?: ""
    val source = when { server.isNotBlank() && user.isNotBlank() && pass.isNotBlank() -> "$server/get.php?username=${URLEncoder.encode(user, "UTF-8")}&password=${URLEncoder.encode(pass, "UTF-8")}&type=m3u_plus&output=ts"; playlist.isNotBlank() -> playlist; else -> return emptyList<TvChannel>() to "No saved source" }
    return try { val c = URL(source).openConnection() as HttpURLConnection; c.connectTimeout = 8000; c.readTimeout = 12000; val text = c.inputStream.bufferedReader().use { it.readText() }; c.disconnect(); val parsed = parseM3u(text); parsed to "Loaded ${parsed.size} channels" } catch (e: Exception) { emptyList<TvChannel>() to "Source error" }
}

private fun parseM3u(text: String): List<TvChannel> {
    val result = ArrayList<TvChannel>(3000); var name = ""; var group = ""; var id = ""
    text.lineSequence().forEach { raw ->
        if (result.size >= 3000) return@forEach
        val line = raw.trim()
        if (line.startsWith("#EXTINF", true)) { id = Regex("tvg-id=\"([^\"]*)\"").find(line)?.groupValues?.getOrNull(1).orEmpty(); group = Regex("group-title=\"([^\"]*)\"").find(line)?.groupValues?.getOrNull(1).orEmpty(); name = line.substringAfterLast(',').trim() }
        else if (line.isNotBlank() && !line.startsWith("#") && name.isNotBlank()) { val stable = id.ifBlank { "$name|$line" }.hashCode().toString(); result += TvChannel(stable, name, group, line); name = ""; group = ""; id = "" }
    }
    return result
}
