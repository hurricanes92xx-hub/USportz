package com.usportz.app

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class StableNav { HOME, SPORTS, LIVE, FAV, SEARCH, SOURCES }

@Composable
internal fun USportzStableApp(store: SourceStore) {
    val context = LocalContext.current
    var nav by remember { mutableStateOf(StableNav.HOME) }
    var events by remember { mutableStateOf(emptyList<SportsEvent>()) }
    var channels by remember { mutableStateOf(emptyList<SportsChannel>()) }
    var loading by remember { mutableStateOf(true) }
    var refresh by remember { mutableIntStateOf(0) }
    val favs = remember { Favs(context) }

    LaunchedEffect(refresh) {
        loading = true
        channels = runCatching { SportsChannelBridge.load(context, refresh > 0) }.getOrDefault(emptyList())
        events = runCatching {
            SportsSchedule.load(context, refresh > 0, emptyList())
        }.getOrDefault(emptyList())
        loading = false
    }

    fun play(url: String) {
        val clean = url.trim()
        if (clean.isNotEmpty()) {
            context.startActivity(Intent(context, RichPlayerActivity::class.java).putExtra(RichPlayerActivity.EXTRA_URL, clean))
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFBFA6FF), secondary = Color(0xFF63D7FF), background = Color(0xFF080A10), surface = Color(0xFF10131D))) {
        Scaffold(
            containerColor = Color(0xFF080A10),
            bottomBar = {
                NavigationBar(containerColor = Color(0xFF0D1018)) {
                    listOf(
                        StableNav.HOME to Icons.Default.Home,
                        StableNav.SPORTS to Icons.Default.SportsScore,
                        StableNav.LIVE to Icons.Default.LiveTv,
                        StableNav.FAV to Icons.Default.Star,
                        StableNav.SEARCH to Icons.Default.Search,
                        StableNav.SOURCES to Icons.Default.SettingsInputAntenna
                    ).forEach { (item, icon) ->
                        NavigationBarItem(selected = nav == item, onClick = { nav = item }, icon = { Icon(icon, null) }, label = { Text(item.name.lowercase().replaceFirstChar { it.uppercase() }) })
                    }
                }
            }
        ) { pad ->
            Column(Modifier.fillMaxSize().padding(pad)) {
                StableHeader(nav, loading) { refresh++ }
                when (nav) {
                    StableNav.HOME -> StableHome(events, channels, ::play)
                    StableNav.SPORTS -> StableSports(events)
                    StableNav.LIVE -> StableLive(channels, favs, ::play)
                    StableNav.FAV -> StableFavorites(channels, favs, ::play)
                    StableNav.SEARCH -> StableSearch(channels, ::play)
                    StableNav.SOURCES -> StableSources(store) { refresh++ }
                }
            }
        }
    }
}

@Composable
private fun StableHeader(nav: StableNav, loading: Boolean, refresh: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("USPORTZ", fontSize = 29.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
            Text(if (loading) "LOADING SAFELY…" else nav.name, fontSize = 11.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
        }
        IconButton(onClick = refresh, enabled = !loading) { Icon(Icons.Default.Refresh, "Refresh") }
    }
}

@Composable
private fun StableHome(events: List<SportsEvent>, channels: List<SportsChannel>, play: (String) -> Unit) {
    val live = SportsSchedule.liveEvents(events)
    val upcoming = SportsSchedule.upcomingEvents(events)
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Card(Modifier.fillMaxWidth().padding(horizontal = 14.dp), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF151124))) {
                Column(Modifier.padding(20.dp)) {
                    Text("YOUR SPORTS COMMAND CENTER", color = Color(0xFF63D7FF), fontSize = 11.sp, fontWeight = FontWeight.Black)
                    Text("Fast. Stable. Built for huge TV sources.", fontSize = 24.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(top = 6.dp))
                    Text("${channels.size} live channels indexed • ${live.size} live events • ${upcoming.size} upcoming", color = Color.Gray, fontSize = 13.sp, modifier = Modifier.padding(top = 7.dp))
                }
            }
        }
        item { StableSection("LIVE NOW", "${live.size} events") }
        if (live.isEmpty()) item { StableEmpty("No live events right now") } else items(live.take(12), key = { it.id }) { StableEvent(it) }
        item { StableSection("TODAY / UPCOMING", "${upcoming.size} events") }
        items(upcoming.take(20), key = { "up-${it.id}" }) { StableEvent(it) }
    }
}

@Composable
private fun StableEvent(event: SportsEvent) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 14.dp), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF111624))) {
        Column(Modifier.padding(15.dp)) {
            Text(event.league, color = Color(0xFF63D7FF), fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Text(event.competitors.joinToString("  •  ").ifBlank { event.shortName.ifBlank { event.name } }, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            Text(event.detail.ifBlank { if (event.state == "in") "LIVE" else "Scheduled" }, color = if (event.state == "in") Color(0xFFFF6B8A) else Color.Gray, fontSize = 11.sp, modifier = Modifier.padding(top = 5.dp))
        }
    }
}

@Composable
private fun StableSports(events: List<SportsEvent>) {
    var sport by remember { mutableStateOf("All") }
    val filtered = SportsSchedule.forSport(events, sport)
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { LazyRow(contentPadding = PaddingValues(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(SportsCatalog.categories) { x -> FilterChip(selected = sport == x, onClick = { sport = x }, label = { Text(x) }) } } }
        item { StableSection(sport, "${filtered.size} events") }
        items(filtered.take(100), key = { it.id }) { StableEvent(it) }
    }
}

@Composable
private fun StableLive(channels: List<SportsChannel>, favs: Favs, play: (String) -> Unit) {
    var selected by remember { mutableStateOf<String?>(null) }
    var categories by remember { mutableStateOf(emptyList<String>()) }
    var visible by remember { mutableStateOf(emptyList<SportsChannel>()) }
    var searching by remember { mutableStateOf(false) }

    LaunchedEffect(channels) {
        categories = withContext(Dispatchers.Default) {
            channels.asSequence().map { normalizeCategory(it.group) }.filter { it.isNotBlank() }.distinctBy { it.lowercase() }.sortedBy { it.lowercase() }.toList()
        }
        selected = null
        visible = emptyList()
    }
    LaunchedEffect(selected, channels) {
        val category = selected ?: return@LaunchedEffect
        searching = true
        visible = withContext(Dispatchers.Default) {
            channels.asSequence().filter { normalizeCategory(it.group).equals(category, true) }.take(500).toList()
        }
        searching = false
    }

    if (selected == null) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { StableSection("LIVE TV", "${channels.size} channels • choose a category") }
            if (categories.isEmpty()) item { StableEmpty("No categories available") }
            items(categories, key = { "cat-$it" }) { category ->
                Card(Modifier.fillMaxWidth().padding(horizontal = 14.dp).clickable { selected = category }, shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF111624))) {
                    Row(Modifier.padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Folder, null, tint = Color(0xFF63D7FF))
                        Column(Modifier.weight(1f).padding(start = 13.dp)) { Text(category, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis); Text("Open category", color = Color.Gray, fontSize = 11.sp) }
                        Icon(Icons.Default.ChevronRight, null, tint = Color.Gray)
                    }
                }
            }
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { selected = null }) { Icon(Icons.Default.ArrowBack, "Back") }
                Text(selected!!, fontSize = 19.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (searching) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            if (visible.isEmpty() && !searching) StableEmpty("No channels in this category")
            else LazyColumn(verticalArrangement = Arrangement.spacedBy(7.dp), contentPadding = PaddingValues(bottom = 24.dp)) { items(visible, key = { "ch-${it.id}-${it.url}" }) { StableChannel(it, favs.isChannelFav(it.id), { favs.toggleChannel(it.id) }) { play(it.url) } } }
        }
    }
}

private fun normalizeCategory(value: String): String = value.trim().removePrefix("##").removeSuffix("##").trim().ifBlank { "Uncategorized" }

@Composable
private fun StableChannel(channel: SportsChannel, favorite: Boolean, toggle: () -> Unit, play: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 14.dp), shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF111624))) {
        Row(Modifier.padding(9.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(horizontal = 7.dp)) { Text(channel.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(channel.group, color = Color.Gray, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            IconButton(onClick = toggle) { Icon(if (favorite) Icons.Default.Star else Icons.Default.StarBorder, "Favorite") }
            FilledTonalButton(onClick = play) { Icon(Icons.Default.PlayArrow, null); Text("PLAY") }
        }
    }
}

@Composable
private fun StableFavorites(channels: List<SportsChannel>, favs: Favs, play: (String) -> Unit) {
    val saved = channels.filter { favs.isChannelFav(it.id) }.take(300)
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        item { StableSection("FAVORITES", "${saved.size} saved channels") }
        if (saved.isEmpty()) item { StableEmpty("Nothing saved yet") }
        items(saved, key = { "fav-${it.id}" }) { StableChannel(it, true, { favs.toggleChannel(it.id) }) { play(it.url) } }
    }
}

@Composable
private fun StableSearch(channels: List<SportsChannel>, play: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(emptyList<SportsChannel>()) }
    LaunchedEffect(query, channels) {
        results = if (query.trim().length < 2) emptyList() else withContext(Dispatchers.Default) {
            val q = query.trim().lowercase()
            channels.asSequence().filter { it.name.lowercase().contains(q) || it.group.lowercase().contains(q) }.take(100).toList()
        }
    }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(14.dp), label = { Text("Search channels") }, singleLine = true)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(7.dp), contentPadding = PaddingValues(bottom = 24.dp)) { items(results, key = { "search-${it.id}" }) { StableChannel(it, false, {}, { play(it.url) }) } }
    }
}

@Composable
private fun StableSources(store: SourceStore, onChanged: () -> Unit) {
    var server by remember { mutableStateOf(store.server) }
    var user by remember { mutableStateOf(store.user) }
    var pass by remember { mutableStateOf(store.pass) }
    var m3u by remember { mutableStateOf(store.playlist) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(14.dp)) {
        item { StableSection("SOURCES", "Xtream Codes + M3U/M3U8") }
        item { OutlinedTextField(server, { server = it }, Modifier.fillMaxWidth(), label = { Text("Xtream server") }, singleLine = true) }
        item { OutlinedTextField(user, { user = it }, Modifier.fillMaxWidth(), label = { Text("Username") }, singleLine = true) }
        item { OutlinedTextField(pass, { pass = it }, Modifier.fillMaxWidth(), label = { Text("Password") }, singleLine = true) }
        item { Button(onClick = { busy = true; status = "Connecting…"; store.saveXtream(server, user, pass, { ok, message -> busy = false; status = message; if (ok) onChanged() }, { status = it }) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "CONNECTING…" else "CONNECT XTREAM") } }
        item { OutlinedTextField(m3u, { m3u = it }, Modifier.fillMaxWidth(), label = { Text("Playlist URL") }, singleLine = true) }
        item { Button(onClick = { busy = true; status = "Loading playlist…"; store.saveM3u(m3u) { ok, message -> busy = false; status = message; if (ok) onChanged() } }, enabled = !busy && m3u.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("LOAD PLAYLIST") } }
        if (status.isNotBlank()) item { Text(status, color = if (status.startsWith("Connected")) Color(0xFF63D7FF) else Color.Gray, fontSize = 12.sp) }
        item { Text("Credentials remain encrypted on this device and are never bundled into the APK.", color = Color.Gray, fontSize = 11.sp) }
    }
}

@Composable private fun StableSection(title: String, subtitle: String) = Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 5.dp), verticalAlignment = Alignment.Bottom) { Text(title, fontSize = 17.sp, fontWeight = FontWeight.Black); Spacer(Modifier.width(7.dp)); Text(subtitle, color = Color.Gray, fontSize = 11.sp) }
@Composable private fun StableEmpty(text: String) = Card(Modifier.fillMaxWidth().padding(horizontal = 14.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF111624))) { Text(text, color = Color.Gray, modifier = Modifier.padding(18.dp)) }
