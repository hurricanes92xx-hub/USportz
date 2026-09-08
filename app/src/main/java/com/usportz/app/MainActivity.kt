package com.usportz.app

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

private data class Channel(
    val id: String,
    val name: String,
    val group: String,
    val logo: String?,
    val url: String
)

private data class SportEvent(
    val title: String,
    val league: String,
    val time: String,
    val sport: String,
    val live: Boolean = false
)

private val sports = listOf("All", "Football", "Basketball", "Baseball", "Hockey", "Soccer", "MMA", "Wrestling")

class MainActivity : ComponentActivity() {
    private val store by lazy { SourceStore(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { USportzApp(store) }
    }
}

@Composable
private fun USportzApp(store: SourceStore) {
    var tab by remember { mutableIntStateOf(0) }
    var settings by remember { mutableStateOf(false) }
    var playerUrl by remember { mutableStateOf<String?>(null) }
    var channels by remember { mutableStateOf(store.channels) }
    var favorites by remember { mutableStateOf(store.favorites) }
    var selectedSport by remember { mutableStateOf("All") }
    var query by remember { mutableStateOf("") }
    val events = remember { sampleEvents() }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF48B9FF),
            secondary = Color(0xFF8BD7FF),
            background = Color(0xFF070B10),
            surface = Color(0xFF101820)
        )
    ) {
        when {
            playerUrl != null -> PlayerScreen(playerUrl!!) { playerUrl = null }
            settings -> SettingsScreen(
                store = store,
                onDone = { channels = store.channels; settings = false },
                onBack = { settings = false }
            )
            else -> Scaffold(
                containerColor = Color(0xFF070B10),
                bottomBar = {
                    NavigationBar {
                        val labels = listOf("Home", "Sports", "Live TV", "Favorites", "Search")
                        val icons = listOf(Icons.Default.Home, Icons.Default.SportsFootball, Icons.Default.LiveTv, Icons.Default.Star, Icons.Default.Search)
                        labels.forEachIndexed { i, label ->
                            NavigationBarItem(
                                selected = tab == i,
                                onClick = { tab = i },
                                icon = { Icon(icons[i], label) },
                                label = { Text(label) }
                            )
                        }
                    }
                }
            ) { pad ->
                LazyColumn(
                    Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    item { Header { settings = true } }
                    when (tab) {
                        0 -> {
                            item { HeroCard(events.first()) { tab = 1 } }
                            item { Section("Live & Upcoming") }
                            items(events.drop(1), key = { it.title }) { event ->
                                EventCard(event, false) { }
                            }
                            item { Section("Your Channels") }
                            if (channels.isEmpty()) {
                                item { EmptyCard("No source loaded", "Open Settings to connect Xtream Codes or an M3U/M3U8 playlist.") }
                            } else {
                                items(channels.take(10), key = { it.id }) { channel ->
                                    ChannelCard(channel, favorites.contains(channel.id),
                                        onPlay = { playerUrl = channel.url },
                                        onFavorite = { favorites = store.toggleFavorite(channel.id) }
                                    )
                                }
                            }
                        }
                        1 -> {
                            item { SportFilters(selectedSport) { selectedSport = it } }
                            val filteredEvents = if (selectedSport == "All") events else events.filter { it.sport == selectedSport }
                            items(filteredEvents, key = { it.title }) { event -> EventCard(event, false) {} }
                        }
                        2 -> {
                            item { Section("Live TV") }
                            if (channels.isEmpty()) {
                                item { EmptyCard("No channels yet", "Connect a source in Settings.") }
                            } else {
                                items(channels, key = { it.id }) { channel ->
                                    ChannelCard(channel, favorites.contains(channel.id),
                                        onPlay = { playerUrl = channel.url },
                                        onFavorite = { favorites = store.toggleFavorite(channel.id) }
                                    )
                                }
                            }
                        }
                        3 -> {
                            item { Section("Favorites") }
                            val favoriteChannels = channels.filter { favorites.contains(it.id) }
                            if (favoriteChannels.isEmpty()) {
                                item { EmptyCard("No favorites", "Tap the star on any channel to keep it here.") }
                            } else {
                                items(favoriteChannels, key = { it.id }) { channel ->
                                    ChannelCard(channel, true,
                                        onPlay = { playerUrl = channel.url },
                                        onFavorite = { favorites = store.toggleFavorite(channel.id) }
                                    )
                                }
                            }
                        }
                        4 -> {
                            item {
                                OutlinedTextField(
                                    value = query,
                                    onValueChange = { query = it },
                                    label = { Text("Search channels") },
                                    leadingIcon = { Icon(Icons.Default.Search, null) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
                                )
                            }
                            val results = if (query.isBlank()) emptyList() else channels.filter {
                                it.name.contains(query, true) || it.group.contains(query, true)
                            }.take(100)
                            if (query.isNotBlank() && results.isEmpty()) {
                                item { EmptyCard("No matches", "Try a channel name or group.") }
                            } else {
                                items(results, key = { it.id }) { channel ->
                                    ChannelCard(channel, favorites.contains(channel.id),
                                        onPlay = { playerUrl = channel.url },
                                        onFavorite = { favorites = store.toggleFavorite(channel.id) }
                                    )
                                }
                            }
                        }
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}

@Composable
private fun Header(openSettings: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text("USportz", fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
            Text("Sports command center", color = Color.Gray)
        }
        IconButton(openSettings) { Icon(Icons.Default.Settings, "Settings") }
    }
}

@Composable
private fun Section(text: String) {
    Text(text, fontSize = 21.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 22.dp, bottom = 10.dp))
}

@Composable
private fun HeroCard(event: SportEvent, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(top = 16.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(if (event.live) "LIVE NOW" else "FEATURED", color = Color(0xFF65C9FF), fontWeight = FontWeight.Bold)
            Text(event.title, fontSize = 25.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(top = 5.dp))
            Text("${event.league} • ${event.time}", color = Color.Gray)
            Button(onClick = onClick, modifier = Modifier.padding(top = 14.dp)) { Text("Explore Sports") }
        }
    }
}

@Composable
private fun EventCard(event: SportEvent, favorite: Boolean, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 5.dp).clickable(onClick = onClick), shape = RoundedCornerShape(14.dp)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(46.dp).background(Color(0xFF183344), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Sports, null)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(event.title, fontWeight = FontWeight.Bold)
                Text("${event.league} • ${event.sport}", color = Color.Gray, fontSize = 13.sp)
            }
            Text(event.time, fontSize = 12.sp)
            Icon(if (favorite) Icons.Default.Star else Icons.Default.StarBorder, "Favorite")
        }
    }
}

@Composable
private fun ChannelCard(channel: Channel, favorite: Boolean, onPlay: () -> Unit, onFavorite: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp), shape = RoundedCornerShape(12.dp)) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).background(Color(0xFF16242E), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.LiveTv, null)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f).clickable(onClick = onPlay)) {
                Text(channel.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(channel.group, color = Color.Gray, fontSize = 12.sp, maxLines = 1)
            }
            IconButton(onFavorite) { Icon(if (favorite) Icons.Default.Star else Icons.Default.StarBorder, "Favorite") }
            IconButton(onPlay) { Icon(Icons.Default.PlayArrow, "Play") }
        }
    }
}

@Composable
private fun EmptyCard(title: String, message: String) {
    Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column(Modifier.padding(20.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(message, color = Color.Gray, modifier = Modifier.padding(top = 5.dp))
        }
    }
}

@Composable
private fun SportFilters(selected: String, onSelected: (String) -> Unit) {
    LazyRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(sports) { sport ->
            FilterChip(selected = selected == sport, onClick = { onSelected(sport) }, label = { Text(sport) })
        }
    }
}

@Composable
private fun SettingsScreen(store: SourceStore, onDone: () -> Unit, onBack: () -> Unit) {
    var server by remember { mutableStateOf(store.server) }
    var user by remember { mutableStateOf(store.user) }
    var pass by remember { mutableStateOf(store.pass) }
    var playlist by remember { mutableStateOf(store.playlist) }
    var status by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.Default.ArrowBack, "Back") }
            Text("Sources", fontSize = 27.sp, fontWeight = FontWeight.Bold)
        }
        Text("Xtream Codes", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp))
        OutlinedTextField(server, { server = it }, label = { Text("Server URL") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(user, { user = it }, label = { Text("Username") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        OutlinedTextField(
            pass, { pass = it }, label = { Text("Password") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        )
        Button(
            enabled = !loading && server.isNotBlank() && user.isNotBlank() && pass.isNotBlank(),
            onClick = {
                loading = true; status = "Connecting…"
                store.saveXtream(server, user, pass) { ok, msg -> loading = false; status = msg; if (ok) onDone() }
            },
            modifier = Modifier.padding(top = 10.dp)
        ) { Text(if (loading) "Loading…" else "Connect Xtream") }

        HorizontalDivider(Modifier.padding(vertical = 22.dp))
        Text("M3U / M3U8", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        OutlinedTextField(playlist, { playlist = it }, label = { Text("Playlist URL") }, modifier = Modifier.fillMaxWidth())
        Button(
            enabled = !loading && playlist.isNotBlank(),
            onClick = {
                loading = true; status = "Loading playlist…"
                store.saveM3u(playlist) { ok, msg -> loading = false; status = msg; if (ok) onDone() }
            },
            modifier = Modifier.padding(top = 10.dp)
        ) { Text("Load playlist") }

        if (status.isNotBlank()) Text(status, color = Color.Gray, modifier = Modifier.padding(top = 12.dp))
        Text(
            "Credentials are stored locally. USportz does not bundle provider credentials or proprietary APK files.",
            color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(top = 18.dp)
        )
    }
}

@Composable
private fun PlayerScreen(url: String, onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val player = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { PlayerView(it).apply { this.player = player; useController = true } },
            modifier = Modifier.fillMaxSize()
        )
        IconButton(onBack, Modifier.align(Alignment.TopStart).padding(12.dp)) {
            Icon(Icons.Default.ArrowBack, "Back", tint = Color.White)
        }
    }
}

private class SourceStore(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("usportz", Context.MODE_PRIVATE)

    var server: String
        get() = prefs.getString("server", "") ?: ""
        private set(value) { prefs.edit().putString("server", value).apply() }
    var user: String
        get() = prefs.getString("user", "") ?: ""
        private set(value) { prefs.edit().putString("user", value).apply() }
    var pass: String
        get() = prefs.getString("pass", "") ?: ""
        private set(value) { prefs.edit().putString("pass", value).apply() }
    var playlist: String
        get() = prefs.getString("playlist", "") ?: ""
        private set(value) { prefs.edit().putString("playlist", value).apply() }

    var channels: List<Channel> = emptyList()
        private set

    val favorites: Set<String>
        get() = prefs.getStringSet("favorites", emptySet()) ?: emptySet()

    fun toggleFavorite(id: String): Set<String> {
        val next = favorites.toMutableSet()
        if (!next.add(id)) next.remove(id)
        prefs.edit().putStringSet("favorites", next).apply()
        return next
    }

    fun saveXtream(base: String, username: String, password: String, done: (Boolean, String) -> Unit) {
        server = base.trimEnd('/'); user = username; pass = password
        val encodedUser = URLEncoder.encode(username, "UTF-8")
        val encodedPass = URLEncoder.encode(password, "UTF-8")
        val url = "$server/get.php?username=$encodedUser&password=$encodedPass&type=m3u_plus&output=ts"
        load(url, done)
    }

    fun saveM3u(url: String, done: (Boolean, String) -> Unit) {
        playlist = url.trim(); load(playlist, done)
    }

    private fun load(url: String, done: (Boolean, String) -> Unit) {
        Thread {
            try {
                val parsed = parseM3u(download(url))
                if (parsed.isEmpty()) main.post { done(false, "No playable channels found") }
                else { channels = parsed; main.post { done(true, "Loaded ${parsed.size} channels") } }
            } catch (e: Exception) {
                main.post { done(false, "Source error: ${e.message ?: "unknown error"}") }
            }
        }.start()
    }

    private fun download(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 20_000
        connection.requestMethod = "GET"
        connection.instanceFollowRedirects = true
        return try {
            if (connection.responseCode !in 200..299) throw IllegalStateException("HTTP ${connection.responseCode}")
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally { connection.disconnect() }
    }
}

private fun parseM3u(text: String): List<Channel> {
    val result = ArrayList<Channel>(minOf(3000, text.length / 100))
    var name = ""
    var group = ""
    var logo: String? = null
    for (raw in text.lineSequence()) {
        val line = raw.trim()
        when {
            line.startsWith("#EXTINF", true) -> {
                name = line.substringAfter(",", "Channel").trim()
                group = Regex("group-title=\"([^\"]*)\"").find(line)?.groupValues?.get(1).orEmpty()
                logo = Regex("tvg-logo=\"([^\"]*)\"").find(line)?.groupValues?.get(1)
            }
            line.isNotBlank() && !line.startsWith("#") && (line.startsWith("http://") || line.startsWith("https://")) -> {
                val safeName = name.ifBlank { "Channel" }
                result += Channel(
                    id = (safeName + "|" + line).hashCode().toString(),
                    name = safeName,
                    group = group.ifBlank { "Uncategorized" },
                    logo = logo,
                    url = line
                )
                name = ""; group = ""; logo = null
                if (result.size >= 3000) break
            }
        }
    }
    return result
}

private fun sampleEvents() = listOf(
    SportEvent("Live Sports Center", "Featured", "NOW", "All", true),
    SportEvent("College Football", "NCAA", "Tonight", "Football"),
    SportEvent("NBA", "Basketball", "Tonight", "Basketball"),
    SportEvent("NHL", "Hockey", "Tonight", "Hockey"),
    SportEvent("UFC Fight Night", "UFC", "Sat 8:00 PM", "MMA"),
    SportEvent("WWE Raw", "WWE", "Mon 8:00 PM", "Wrestling")
)
