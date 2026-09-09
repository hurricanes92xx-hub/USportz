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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class Channel(
    val id: String,
    val name: String,
    val group: String,
    val logo: String?,
    val url: String
)

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
    var events by remember { mutableStateOf<List<SportsEvent>>(emptyList()) }
    var scheduleLoading by remember { mutableStateOf(true) }
    var scheduleError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun refreshSchedule(force: Boolean) {
        scheduleLoading = true
        scheduleError = null
        scope.launch {
            runCatching { SportsSchedule.load(forceRefresh = force) }
                .onSuccess {
                    events = it
                    scheduleLoading = false
                    if (it.isEmpty()) scheduleError = "No games are available right now."
                }
                .onFailure {
                    scheduleLoading = false
                    scheduleError = it.message ?: "Schedule unavailable"
                }
        }
    }

    LaunchedEffect(Unit) {
        refreshSchedule(force = false)
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFFBDA8E8),
            secondary = Color(0xFF6CCBFF),
            background = Color(0xFF070B10),
            surface = Color(0xFF171A21)
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
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    item { Header(onSettings = { settings = true }, onRefresh = { refreshSchedule(true) }, loading = scheduleLoading) }
                    when (tab) {
                        0 -> {
                            if (scheduleLoading && events.isEmpty()) item { ScheduleLoadingCard() }
                            val live = SportsSchedule.liveEvents(events)
                            val upcoming = SportsSchedule.upcomingEvents(events)
                            if (live.isNotEmpty()) {
                                item { Section("LIVE NOW") }
                                items(live.take(6), key = { "live-${it.id}" }) { event ->
                                    SportsEventCard(event, channels, onWatch = { playerUrl = it.url })
                                }
                            }
                            item { Section(if (live.isEmpty()) "Sports Schedule" else "Coming Up") }
                            if (upcoming.isEmpty()) {
                                item { EmptyCard(scheduleError ?: "No upcoming games found", "Pull to refresh from the live sports feed.") }
                            } else {
                                items(upcoming.take(12), key = { "up-${it.id}" }) { event ->
                                    SportsEventCard(event, channels, onWatch = { playerUrl = it.url })
                                }
                            }
                            if (scheduleError != null && events.isNotEmpty()) item { FeedStatus(scheduleError!!) }
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
                            if (scheduleLoading && events.isEmpty()) item { ScheduleLoadingCard() }
                            val filtered = SportsSchedule.forSport(events, selectedSport)
                            val live = SportsSchedule.liveEvents(filtered)
                            val upcoming = SportsSchedule.upcomingEvents(filtered)
                            if (live.isNotEmpty()) {
                                item { Section("LIVE NOW") }
                                items(live, key = { "sports-live-${it.id}" }) { event -> SportsEventCard(event, channels) { playerUrl = it.url } }
                            }
                            item { Section("UPCOMING") }
                            if (upcoming.isEmpty()) {
                                item { EmptyCard("No ${selectedSport.lowercase()} events", "The live schedule will populate as games are posted.") }
                            } else {
                                items(upcoming, key = { "sports-up-${it.id}" }) { event -> SportsEventCard(event, channels) { playerUrl = it.url } }
                            }
                            if (scheduleError != null) item { FeedStatus(scheduleError!!) }
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
private fun Header(onSettings: () -> Unit, onRefresh: () -> Unit, loading: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(top = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text("USportz", fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
            Text("Live sports command center", color = Color.Gray)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onRefresh, enabled = !loading) { Icon(Icons.Default.Refresh, "Refresh schedule") }
            IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings") }
        }
    }
}

@Composable
private fun ScheduleLoadingCard() {
    Card(Modifier.fillMaxWidth().padding(top = 16.dp), shape = RoundedCornerShape(20.dp)) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
            Spacer(Modifier.width(14.dp))
            Column {
                Text("Loading live schedule…", fontWeight = FontWeight.Bold)
                Text("Getting games, logos and broadcast details", color = Color.Gray, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun Section(text: String) {
    Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.5.sp)
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun SportsEventCard(event: SportsEvent, channels: List<Channel>, onWatch: (Channel) -> Unit) {
    val best = channels.maxByOrNull { SportsSchedule.matchChannel(event, it.name, it.group) }
    val bestScore = best?.let { SportsSchedule.matchChannel(event, it.name, it.group) } ?: 0
    val brand = SportsPresentation.brand(event)
    val status = SportsPresentation.status(event)
    val isLive = event.state == "in"

    Card(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = if (isLive) Color(0xFF20242B) else Color(0xFF191C22))
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!event.leagueLogo.isNullOrBlank()) {
                    AsyncImage(
                        model = event.leagueLogo,
                        contentDescription = event.league,
                        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    BrandBadge(brand?.icon ?: event.league.take(4))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isLive) {
                            Text("LIVE", color = Color(0xFFFF5E6C), fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(event.league, color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(SportsPresentation.matchup(event), fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(top = 3.dp))
                    Text(formatEventTime(event.startTime), color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(status, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (isLive) Color(0xFFFF5E6C) else Color.Gray)
                    if (event.detail.isNotBlank()) Text(event.detail, fontSize = 11.sp, color = Color.Gray, maxLines = 1)
                }
            }
            if (event.competitorLogos.any { it.isNotBlank() }) {
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    event.competitorLogos.take(2).forEachIndexed { index, logo ->
                        if (index > 0) {
                            Text("VS", color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 14.dp))
                        }
                        if (logo.isNotBlank()) {
                            AsyncImage(
                                model = logo,
                                contentDescription = event.competitors.getOrNull(index),
                                modifier = Modifier.size(44.dp),
                                contentScale = ContentScale.Fit
                            )
                        }
                    }
                }
            }
            if (event.broadcast.isNotBlank()) {
                Text("Watch on ${event.broadcast}", color = Color.Gray, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
            }
            if (best != null && bestScore > 0) {
                Button(onClick = { onWatch(best) }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp), shape = RoundedCornerShape(12.dp)) {
                    Icon(Icons.Default.PlayArrow, null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (isLive) "WATCH LIVE" else "WATCH")
                }
            }
        }
    }
}

@Composable
private fun BrandBadge(label: String) {
    Box(
        Modifier.size(48.dp).background(Color(0xFF183344), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center
    ) { Text(label.take(5), fontWeight = FontWeight.ExtraBold, fontSize = 11.sp) }
}

@Composable
private fun FeedStatus(message: String) {
    Text(message, color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
}

@Composable
private fun ChannelCard(channel: Channel, favorite: Boolean, onPlay: () -> Unit, onFavorite: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp), shape = RoundedCornerShape(14.dp)) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!channel.logo.isNullOrBlank()) {
                AsyncImage(
                    model = channel.logo,
                    contentDescription = channel.name,
                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)),
                    contentScale = ContentScale.Fit
                )
            } else {
                Box(Modifier.size(48.dp).background(Color(0xFF16242E), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.LiveTv, null)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f).clickable(onClick = onPlay)) {
                Text(channel.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(channel.group, color = Color.Gray, fontSize = 12.sp, maxLines = 1)
            }
            IconButton(onClick = onFavorite) { Icon(if (favorite) Icons.Default.Star else Icons.Default.StarBorder, "Favorite") }
            IconButton(onClick = onPlay) { Icon(Icons.Default.PlayArrow, "Play") }
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
        items(SportsCatalog.categories) { sport ->
            FilterChip(selected = selected == sport, onClick = { onSelected(sport) }, label = { Text(sport) })
        }
    }
}

private fun formatEventTime(value: String): String {
    if (value.isBlank()) return "Time TBD"
    val parsed = runCatching {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).parse(value)
    }.getOrNull() ?: runCatching {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).parse(value)
    }.getOrNull()
    return parsed?.let { SimpleDateFormat("EEE, MMM d • h:mm a", Locale.getDefault()).format(Date(it.time)) } ?: value
}

@Composable
private fun SettingsScreen(store: SourceStore, onDone: () -> Unit, onBack: () -> Unit) {
    var server by remember { mutableStateOf(store.server) }
    var user by remember { mutableStateOf(store.user) }
    var pass by remember { mutableStateOf(store.pass) }
    var playlist by remember { mutableStateOf(store.playlist) }
    var status by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }; Text("Sources", fontSize = 27.sp, fontWeight = FontWeight.Bold) }
        Text("Xtream Codes", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp))
        OutlinedTextField(server, { server = it }, label = { Text("Server URL") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(user, { user = it }, label = { Text("Username") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        OutlinedTextField(pass, { pass = it }, label = { Text("Password") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        Button(enabled = !loading && server.isNotBlank() && user.isNotBlank() && pass.isNotBlank(), onClick = { loading = true; status = "Connecting…"; store.saveXtream(server, user, pass) { ok, msg -> loading = false; status = msg; if (ok) onDone() } }, modifier = Modifier.padding(top = 10.dp)) { Text(if (loading) "Loading…" else "Connect Xtream") }
        HorizontalDivider(Modifier.padding(vertical = 22.dp))
        Text("M3U / M3U8", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        OutlinedTextField(playlist, { playlist = it }, label = { Text("Playlist URL") }, modifier = Modifier.fillMaxWidth())
        Button(enabled = !loading && playlist.isNotBlank(), onClick = { loading = true; status = "Loading playlist…"; store.saveM3u(playlist) { ok, msg -> loading = false; status = msg; if (ok) onDone() } }, modifier = Modifier.padding(top = 10.dp)) { Text("Load playlist") }
        if (status.isNotBlank()) Text(status, color = Color.Gray, modifier = Modifier.padding(top = 12.dp))
        Text("Credentials are stored locally. USportz does not bundle provider credentials or proprietary APK files.", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(top = 18.dp))
    }
}

@Composable
private fun PlayerScreen(url: String, onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val player = remember(url) { ExoPlayer.Builder(context).build().apply { setMediaItem(MediaItem.fromUri(url)); prepare(); playWhenReady = true } }
    DisposableEffect(player) { onDispose { player.release() } }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { PlayerView(it).apply { this.player = player; useController = true } }, modifier = Modifier.fillMaxSize())
        IconButton(onClick = onBack, modifier = Modifier.align(Alignment.TopStart).padding(12.dp)) { Icon(Icons.Default.ArrowBack, "Back", tint = Color.White) }
    }
}

private class SourceStore(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("usportz", Context.MODE_PRIVATE)
    var server: String get() = prefs.getString("server", "") ?: ""; private set(value) { prefs.edit().putString("server", value).apply() }
    var user: String get() = prefs.getString("user", "") ?: ""; private set(value) { prefs.edit().putString("user", value).apply() }
    var pass: String get() = prefs.getString("pass", "") ?: ""; private set(value) { prefs.edit().putString("pass", value).apply() }
    var playlist: String get() = prefs.getString("playlist", "") ?: ""; private set(value) { prefs.edit().putString("playlist", value).apply() }
    var channels: List<Channel> = emptyList(); private set
    val favorites: Set<String> get() = prefs.getStringSet("favorites", emptySet()) ?: emptySet()
    fun toggleFavorite(id: String): Set<String> { val next = favorites.toMutableSet(); if (!next.add(id)) next.remove(id); prefs.edit().putStringSet("favorites", next).apply(); return next }

    fun saveXtream(base: String, username: String, password: String, done: (Boolean, String) -> Unit) {
        server = base.trimEnd('/'); user = username; pass = password
        val url = "$server/get.php?username=${URLEncoder.encode(username, "UTF-8")}&password=${URLEncoder.encode(password, "UTF-8")}&type=m3u_plus&output=ts"
        loadUrl(url) { ok, result -> if (ok) { channels = parseM3u(result, url); main.post { done(true, "Loaded ${channels.size} channels") } } else main.post { done(false, result) } }
    }
    fun saveM3u(value: String, done: (Boolean, String) -> Unit) { playlist = value; loadUrl(value) { ok, result -> if (ok) { channels = parseM3u(result, value); main.post { done(true, "Loaded ${channels.size} channels") } } else main.post { done(false, result) } } }

    private fun loadUrl(value: String, done: (Boolean, String) -> Unit) {
        Thread {
            try {
                val conn = URL(value).openConnection() as HttpURLConnection
                conn.connectTimeout = 10000; conn.readTimeout = 15000; conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "USportz/1.0")
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect(); done(true, text)
            } catch (e: Exception) { done(false, "Source error: ${e.message ?: "Unable to load source"}") }
        }.start()
    }

    private fun parseM3u(text: String, source: String): List<Channel> {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val result = ArrayList<Channel>(minOf(3000, lines.size / 2))
        var pending = emptyMap<String, String>()
        for (line in lines) {
            if (line.startsWith("#EXTINF", true)) pending = attrs(line)
            else if (!line.startsWith("#")) {
                val name = pending["name"] ?: line.substringAfterLast('/').substringBefore('?').ifBlank { "Channel" }
                val group = pending["group"] ?: "Live TV"
                val logo = pending["logo"]
                val id = "${name.lowercase()}|$line".hashCode().toString()
                result += Channel(id, name, group, logo, line); pending = emptyMap()
                if (result.size >= 3000) break
            }
        }
        return result.distinctBy { it.id }
    }

    private fun attrs(extinf: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        Regex("([\\w-]+)=\\\"([^\\\"]*)\\\"").findAll(extinf).forEach { map[it.groupValues[1].lowercase()] = it.groupValues[2] }
        val comma = extinf.indexOf(',')
        if (comma >= 0) map["name"] = extinf.substring(comma + 1).trim()
        return map
    }
}
