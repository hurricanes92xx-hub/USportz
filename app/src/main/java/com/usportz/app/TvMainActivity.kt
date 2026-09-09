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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { TvUSportzApp(this) }
    }
}

@Composable
private fun TvUSportzApp(context: Context) {
    val prefs = remember { context.getSharedPreferences("usportz", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    var selectedSport by remember { mutableStateOf("All") }
    var channels by remember { mutableStateOf(emptyList<TvChannel>()) }
    var favorites by remember { mutableStateOf(prefs.getStringSet("favorites", emptySet()) ?: emptySet()) }
    var playerUrl by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("Ready") }

    fun loadSavedSource() {
        scope.launch {
            status = "Loading source…"
            val result = withContext(Dispatchers.IO) { loadChannels(prefs) }
            channels = result.first
            status = result.second
        }
    }

    LaunchedEffect(Unit) { loadSavedSource() }

    MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF48B9FF), secondary = Color(0xFF8BD7FF), background = Color(0xFF070B10), surface = Color(0xFF101820))) {
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
                            Text(status, color = Color.Gray, fontSize = 14.sp)
                            Spacer(Modifier.width(14.dp))
                            TvButton("Refresh", Icons.Default.Refresh) { loadSavedSource() }
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                    when (tab) {
                        0 -> TvHome(channels, favorites, { playerUrl = it }, { id -> favorites = toggleTvFavorite(prefs, id) })
                        1 -> TvSports(selectedSport, { selectedSport = it }, channels, favorites, { playerUrl = it }, { id -> favorites = toggleTvFavorite(prefs, id) })
                        2 -> TvChannels(channels, favorites, { playerUrl = it }, { id -> favorites = toggleTvFavorite(prefs, id) })
                        else -> TvFavorites(channels, favorites, { playerUrl = it }, { id -> favorites = toggleTvFavorite(prefs, id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun TvRail(selected: Int, onSelect: (Int) -> Unit) {
    Column(Modifier.width(150.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("MENU", color = Color.Gray, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
        listOf("Home" to Icons.Default.Home, "Sports" to Icons.Default.SportsFootball, "Live TV" to Icons.Default.LiveTv, "Favorites" to Icons.Default.Star).forEachIndexed { index, item ->
            TvNavButton(item.first, item.second, selected == index) { onSelect(index) }
        }
    }
}

@Composable
private fun TvNavButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(14.dp))
        .background(if (selected) Color(0xFF163B52) else Color(0xFF101820))
        .border(if (focused) TvUi.focusBorder else BorderStroke(1.dp, Color.Transparent), RoundedCornerShape(14.dp))
        .onFocusChanged { focused = it.isFocused }.focusable().clickable(onClick = onClick).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null)
        Spacer(Modifier.width(12.dp))
        Text(label, fontSize = 17.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
private fun TvHome(channels: List<TvChannel>, favorites: Set<String>, play: (String) -> Unit, favorite: (String) -> Unit) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { TvHero("LIVE SPORTS", "Your sports, channels and events in one place", "Open Live TV or Sports to start watching") }
        item { TvSection("Sports") }
        item { TvSportRow() }
        item { TvSection("Your Channels") }
        item { if (channels.isEmpty()) TvEmpty("No channels loaded", "Connect a source from the mobile app first, then return to TV.") else TvChannelRow(channels.take(12), favorites, play, favorite) }
    }
}

@Composable
private fun TvSports(selected: String, onSelected: (String) -> Unit, channels: List<TvChannel>, favorites: Set<String>, play: (String) -> Unit, favorite: (String) -> Unit) {
    val categories = SportsCatalog.categories
    val filtered = if (selected == "All") channels else channels.filter { SportsCatalog.classify(it.name, it.group) == selected }
    Column(Modifier.fillMaxSize()) {
        TvSection("Sports")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) { items(categories) { sport -> TvFilter(sport, selected == sport) { onSelected(sport) } } }
        Spacer(Modifier.height(20.dp))
        if (selected == "All") TvEmpty("Choose a sport", "Use the remote to select a category and see matching channels.")
        else if (filtered.isEmpty()) TvEmpty("No ${selected.lowercase()} channels", "No indexed channels matched this sport yet.")
        else TvChannelList(filtered, favorites, play, favorite)
    }
}

@Composable
private fun TvChannels(channels: List<TvChannel>, favorites: Set<String>, play: (String) -> Unit, favorite: (String) -> Unit) {
    Column(Modifier.fillMaxSize()) { TvSection("Live TV"); if (channels.isEmpty()) TvEmpty("No channels loaded", "Connect Xtream Codes or M3U/M3U8 in the mobile app first.") else TvChannelList(channels, favorites, play, favorite) }
}

@Composable
private fun TvFavorites(channels: List<TvChannel>, favorites: Set<String>, play: (String) -> Unit, favorite: (String) -> Unit) {
    val list = channels.filter { favorites.contains(it.id) }
    Column(Modifier.fillMaxSize()) { TvSection("Favorites"); if (list.isEmpty()) TvEmpty("No favorites", "Select the star on a channel to keep it here.") else TvChannelList(list, favorites, play, favorite) }
}

@Composable
private fun TvHero(kicker: String, title: String, subtitle: String) {
    var focused by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(22.dp)).background(Color(0xFF102A3A))
        .border(if (focused) TvUi.focusBorder else BorderStroke(1.dp, Color.Transparent), RoundedCornerShape(22.dp))
        .onFocusChanged { focused = it.isFocused }.focusable().padding(28.dp)) {
        Text(kicker, color = Color(0xFF65C9FF), fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text(title, fontSize = 31.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(top = 8.dp))
        Text(subtitle, color = Color.Gray, fontSize = 16.sp, modifier = Modifier.padding(top = 7.dp))
    }
}

@Composable
private fun TvSportRow() { LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) { items(SportsCatalog.categories.filter { it != "All" }.take(8)) { sport -> TvTile(sport) {} } } }

@Composable
private fun TvFilter(text: String, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(Modifier.widthIn(min = 118.dp).height(52.dp).clip(RoundedCornerShape(14.dp)).background(if (selected) Color(0xFF17425B) else Color(0xFF101820))
        .border(if (focused) TvUi.focusBorder else BorderStroke(1.dp, Color.Transparent), RoundedCornerShape(14.dp))
        .onFocusChanged { focused = it.isFocused }.focusable().clickable(onClick = onClick).padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
        Text(text, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
private fun TvTile(text: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(Modifier.width(145.dp).height(100.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF101820))
        .border(if (focused) TvUi.focusBorder else BorderStroke(1.dp, Color.Transparent), RoundedCornerShape(16.dp))
        .onFocusChanged { focused = it.isFocused }.focusable().clickable(onClick = onClick).padding(16.dp), verticalArrangement = Arrangement.Center) {
        Icon(Icons.Default.Sports, null, tint = Color(0xFF65C9FF), modifier = Modifier.size(28.dp))
        Text(text, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 7.dp))
    }
}

@Composable
private fun TvChannelRow(channels: List<TvChannel>, favorites: Set<String>, play: (String) -> Unit, favorite: (String) -> Unit) { LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) { items(channels, key = { it.id }) { TvChannelCard(it, favorites.contains(it.id), play, favorite) } } }

@Composable
private fun TvChannelList(channels: List<TvChannel>, favorites: Set<String>, play: (String) -> Unit, favorite: (String) -> Unit) { LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) { items(channels, key = { it.id }) { TvChannelCard(it, favorites.contains(it.id), play, favorite) } } }

@Composable
private fun TvChannelCard(channel: TvChannel, isFavorite: Boolean, play: (String) -> Unit, favorite: (String) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().height(82.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF101820))
        .border(if (focused) TvUi.focusBorder else BorderStroke(1.dp, Color.Transparent), RoundedCornerShape(16.dp))
        .onFocusChanged { focused = it.isFocused }.focusable().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF16242E)), contentAlignment = Alignment.Center) { Icon(Icons.Default.LiveTv, null) }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f).clickable { play(channel.url) }) { Text(channel.name, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1); Text(channel.group.ifBlank { "Live TV" }, color = Color.Gray, fontSize = 13.sp, maxLines = 1) }
        TvButton(if (isFavorite) "★" else "☆", Icons.Default.StarBorder) { favorite(channel.id) }
        Spacer(Modifier.width(8.dp))
        TvButton("Play", Icons.Default.PlayArrow) { play(channel.url) }
    }
}

@Composable
private fun TvButton(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(Modifier.height(52.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF16242E))
        .border(if (focused) TvUi.focusBorder else BorderStroke(1.dp, Color.Transparent), RoundedCornerShape(12.dp))
        .onFocusChanged { focused = it.isFocused }.focusable().clickable(onClick = onClick).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, modifier = Modifier.size(21.dp)); Spacer(Modifier.width(7.dp)); Text(text, fontWeight = FontWeight.Bold)
    }
}

@Composable private fun TvSection(text: String) { Text(text, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold) }

@Composable private fun TvEmpty(title: String, message: String) { Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) { Column(Modifier.padding(24.dp)) { Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold); Text(message, color = Color.Gray, modifier = Modifier.padding(top = 7.dp)) } } }

@Composable
private fun TvPlayer(url: String, onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val player = remember(url) { ExoPlayer.Builder(context).build().apply { setMediaItem(MediaItem.fromUri(url)); prepare(); playWhenReady = true } }
    DisposableEffect(player) { onDispose { player.release() } }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { PlayerView(it).apply { this.player = player; useController = true } }, modifier = Modifier.fillMaxSize())
        Box(Modifier.align(Alignment.TopStart).padding(20.dp)) { TvButton("Back", Icons.Default.ArrowBack) { onBack() } }
    }
}

private fun toggleTvFavorite(prefs: android.content.SharedPreferences, id: String): Set<String> {
    val next = (prefs.getStringSet("favorites", emptySet()) ?: emptySet()).toMutableSet()
    if (!next.add(id)) next.remove(id)
    prefs.edit().putStringSet("favorites", next).apply()
    return next
}

private fun loadChannels(prefs: android.content.SharedPreferences): Pair<List<TvChannel>, String> {
    val server = prefs.getString("server", "") ?: ""
    val user = prefs.getString("user", "") ?: ""
    val pass = prefs.getString("pass", "") ?: ""
    val playlist = prefs.getString("playlist", "") ?: ""
    val source = when {
        server.isNotBlank() && user.isNotBlank() && pass.isNotBlank() -> "$server/get.php?username=${URLEncoder.encode(user, "UTF-8")}&password=${URLEncoder.encode(pass, "UTF-8")}&type=m3u_plus&output=ts"
        playlist.isNotBlank() -> playlist
        else -> return emptyList<TvChannel>() to "No saved source"
    }
    return try {
        val connection = URL(source).openConnection() as HttpURLConnection
        connection.connectTimeout = 8000
        connection.readTimeout = 12000
        connection.requestMethod = "GET"
        val text = connection.inputStream.bufferedReader().use { it.readText() }
        connection.disconnect()
        val parsed = parseTvM3u(text)
        parsed to "Loaded ${parsed.size} channels"
    } catch (e: Exception) {
        emptyList<TvChannel>() to "Source error: ${e.message ?: "unable to load"}"
    }
}

private fun parseTvM3u(text: String): List<TvChannel> {
    val result = ArrayList<TvChannel>(minOf(3000, text.length / 80))
    var name = ""
    var group = ""
    var id = ""
    for (raw in text.lineSequence()) {
        val line = raw.trim()
        if (line.startsWith("#EXTINF", true)) {
            name = line.substringAfterLast(',').trim().ifBlank { "Channel" }
            group = Regex("group-title=\"([^\"]*)\"").find(line)?.groupValues?.getOrNull(1).orEmpty()
            id = Regex("tvg-id=\"([^\"]*)\"").find(line)?.groupValues?.getOrNull(1).orEmpty()
        } else if (line.isNotBlank() && !line.startsWith("#") && name.isNotBlank()) {
            val stableId = id.ifBlank { "$name|$line" }.hashCode().toString()
            result += TvChannel(stableId, name, group, line)
            name = ""
            group = ""
            id = ""
            if (result.size >= 3000) break
        }
    }
    return result
}
