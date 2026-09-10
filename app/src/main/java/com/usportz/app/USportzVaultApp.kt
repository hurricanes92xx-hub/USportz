package com.usportz.app

import android.content.Context
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

private enum class VaultNav { HOME, SPORTS, LIVE, SEARCH, SOURCES }

@Composable
internal fun USportzVaultApp(store: SourceStore) {
    val context = LocalContext.current
    val vault = remember { ChannelVault(context) }
    val sourceKey = remember(store.server, store.user, store.pass, store.playlist) { ChannelVault.key(store) }
    var nav by remember { mutableStateOf(VaultNav.HOME) }
    var events by remember { mutableStateOf(emptyList<SportsEvent>()) }
    var channelCount by remember { mutableIntStateOf(0) }
    var categories by remember { mutableStateOf(emptyList<String>()) }
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    var categoryChannels by remember { mutableStateOf(emptyList<SportsChannel>()) }
    var selectedEvent by remember { mutableStateOf<SportsEvent?>(null) }
    var eventChannels by remember { mutableStateOf(emptyList<SportsChannel>()) }
    var loading by remember { mutableStateOf(true) }
    var loadingEvent by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }

    LaunchedEffect(sourceKey, refresh) {
        loading = true
        selectedCategory = null
        categoryChannels = emptyList()
        val existing = withContext(Dispatchers.IO) { vault.count(sourceKey) }
        if (existing == 0 || refresh > 0) {
            val loaded = runCatching { SportsChannelBridge.load(context, refresh > 0) }.getOrDefault(emptyList())
            if (loaded.isNotEmpty()) vault.replaceAll(sourceKey, loaded)
        }
        channelCount = vault.count(sourceKey)
        categories = vault.categories(sourceKey)
        events = runCatching { SportsSchedule.load(context, refresh > 0, emptyList()) }.getOrDefault(emptyList())
        loading = false
    }

    LaunchedEffect(selectedCategory, sourceKey) {
        val category = selectedCategory ?: return@LaunchedEffect
        categoryChannels = vault.channelsForCategory(sourceKey, category, 500)
    }

    LaunchedEffect(selectedEvent, sourceKey) {
        val event = selectedEvent ?: return@LaunchedEffect
        loadingEvent = true
        eventChannels = vault.channelsForEvent(sourceKey, event, 250)
        loadingEvent = false
    }

    fun play(url: String) {
        val clean = url.trim()
        if (clean.isNotEmpty()) context.startActivity(Intent(context, RichPlayerActivity::class.java).putExtra(RichPlayerActivity.EXTRA_URL, clean))
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFBFA6FF), secondary = Color(0xFF63D7FF), background = Color(0xFF080A10), surface = Color(0xFF10131D))) {
        Scaffold(
            containerColor = Color(0xFF080A10),
            bottomBar = {
                NavigationBar(containerColor = Color(0xFF0D1018)) {
                    listOf(
                        VaultNav.HOME to Icons.Default.Home,
                        VaultNav.SPORTS to Icons.Default.SportsScore,
                        VaultNav.LIVE to Icons.Default.LiveTv,
                        VaultNav.SEARCH to Icons.Default.Search,
                        VaultNav.SOURCES to Icons.Default.SettingsInputAntenna
                    ).forEach { (item, icon) ->
                        NavigationBarItem(selected = nav == item, onClick = { nav = item }, icon = { Icon(icon, null) }, label = { Text(item.name.lowercase().replaceFirstChar { it.uppercase() }) })
                    }
                }
            }
        ) { pad ->
            Column(Modifier.fillMaxSize().padding(pad)) {
                VaultHeader(nav, loading) { refresh++ }
                when (nav) {
                    VaultNav.HOME -> VaultHome(events, channelCount, onEvent = { selectedEvent = it })
                    VaultNav.SPORTS -> VaultSports(events, onEvent = { selectedEvent = it })
                    VaultNav.LIVE -> VaultLive(categories, selectedCategory, categoryChannels, { selectedCategory = it }, { selectedCategory = null }, play)
                    VaultNav.SEARCH -> VaultSearch(sourceKey, vault, play)
                    VaultNav.SOURCES -> StableSources(store) { refresh++ }
                }
            }
        }

        selectedEvent?.let { event ->
            AlertDialog(
                onDismissRequest = { selectedEvent = null },
                title = { Text("WATCH THIS GAME") },
                text = {
                    Column(Modifier.fillMaxWidth()) {
                        Text(event.competitors.joinToString("  •  ").ifBlank { event.shortName.ifBlank { event.name } }, fontWeight = FontWeight.Bold)
                        Text("${event.league} • ${event.broadcast.ifBlank { "Matching provider feeds" }}", color = Color.Gray, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                        if (loadingEvent) CircularProgressIndicator(Modifier.size(24.dp).padding(top = 8.dp))
                        else if (eventChannels.isEmpty()) Text("No matching Xtream feeds found in your channel vault.", color = Color.Gray, modifier = Modifier.padding(top = 12.dp))
                        else LazyColumn(Modifier.heightIn(max = 360.dp).padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(eventChannels, key = { "event-${it.id}-${it.url}" }) { channel ->
                                Card(Modifier.fillMaxWidth().clickable { play(channel.url) }, colors = CardDefaults.cardColors(containerColor = Color(0xFF111624))) {
                                    Column(Modifier.padding(11.dp)) {
                                        Text(channel.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(channel.group, color = Color.Gray, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { selectedEvent = null }) { Text("CLOSE") } }
            )
        }
    }
}

@Composable
private fun VaultHeader(nav: VaultNav, loading: Boolean, refresh: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("USPORTZ", fontSize = 29.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
            Text(if (loading) "CHANNEL VAULT LOADING…" else nav.name, fontSize = 11.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
        }
        IconButton(onClick = refresh, enabled = !loading) { Icon(Icons.Default.Refresh, "Refresh") }
    }
}

@Composable
private fun VaultHome(events: List<SportsEvent>, channelCount: Int, onEvent: (SportsEvent) -> Unit) {
    val live = SportsSchedule.liveEvents(events)
    val upcoming = SportsSchedule.upcomingEvents(events)
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Card(Modifier.fillMaxWidth().padding(horizontal = 14.dp), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF151124))) {
                Column(Modifier.padding(20.dp)) {
                    Text("CHANNEL VAULT", color = Color(0xFF63D7FF), fontSize = 11.sp, fontWeight = FontWeight.Black)
                    Text("Only load channels when you ask for them.", fontSize = 23.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(top = 6.dp))
                    Text("$channelCount channels stored locally • ${live.size} live events • ${upcoming.size} upcoming", color = Color.Gray, fontSize = 13.sp, modifier = Modifier.padding(top = 7.dp))
                }
            }
        }
        item { VaultSection("LIVE NOW", "Tap a game to find every matching feed") }
        items(live.take(20), key = { "live-${it.id}" }) { VaultEvent(it, onEvent) }
        item { VaultSection("UPCOMING", "Tap a game for provider feeds") }
        items(upcoming.take(30), key = { "up-${it.id}" }) { VaultEvent(it, onEvent) }
    }
}

@Composable
private fun VaultSports(events: List<SportsEvent>, onEvent: (SportsEvent) -> Unit) {
    var sport by remember { mutableStateOf("All") }
    val filtered = SportsSchedule.forSport(events, sport)
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { LazyRow(contentPadding = PaddingValues(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(SportsCatalog.categories) { x -> FilterChip(selected = sport == x, onClick = { sport = x }, label = { Text(x) }) } } }
        item { VaultSection(sport, "${filtered.size} events • tap one for all matching feeds") }
        items(filtered.take(100), key = { it.id }) { VaultEvent(it, onEvent) }
    }
}

@Composable
private fun VaultEvent(event: SportsEvent, onEvent: (SportsEvent) -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 14.dp).clickable { onEvent(event) }, shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF111624))) {
        Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(event.league, color = Color(0xFF63D7FF), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text(event.competitors.joinToString("  •  ").ifBlank { event.shortName.ifBlank { event.name } }, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                Text(event.detail.ifBlank { if (event.state == "in") "LIVE" else "Scheduled" }, color = if (event.state == "in") Color(0xFFFF6B8A) else Color.Gray, fontSize = 11.sp, modifier = Modifier.padding(top = 5.dp))
            }
            Icon(Icons.Default.ChevronRight, null, tint = Color.Gray)
        }
    }
}

@Composable
private fun VaultLive(categories: List<String>, selected: String?, channels: List<SportsChannel>, open: (String) -> Unit, back: () -> Unit, play: (String) -> Unit) {
    if (selected == null) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { VaultSection("LIVE TV", "${categories.size} categories • choose one") }
            if (categories.isEmpty()) item { VaultEmpty("No categories stored yet. Connect Xtream in Sources.") }
            items(categories, key = { "cat-$it" }) { category ->
                Card(Modifier.fillMaxWidth().padding(horizontal = 14.dp).clickable { open(category) }, shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF111624))) {
                    Row(Modifier.padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Folder, null, tint = Color(0xFF63D7FF))
                        Column(Modifier.weight(1f).padding(start = 13.dp)) { Text(category, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis); Text("Load channels from vault", color = Color.Gray, fontSize = 11.sp) }
                        Icon(Icons.Default.ChevronRight, null, tint = Color.Gray)
                    }
                }
            }
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = back) { Icon(Icons.Default.ArrowBack, "Back") }
                Text(selected, fontSize = 19.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text("${channels.size}", color = Color.Gray, fontSize = 12.sp)
            }
            if (channels.isEmpty()) VaultEmpty("No channels in this category")
            else LazyColumn(verticalArrangement = Arrangement.spacedBy(7.dp), contentPadding = PaddingValues(bottom = 24.dp)) { items(channels, key = { "ch-${it.id}-${it.url}" }) { channel -> VaultChannel(channel, play) } }
        }
    }
}

@Composable
private fun VaultChannel(channel: SportsChannel, play: (String) -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 14.dp), shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF111624))) {
        Row(Modifier.padding(11.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(horizontal = 7.dp)) { Text(channel.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(channel.group, color = Color.Gray, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            FilledTonalButton(onClick = { play(channel.url) }) { Icon(Icons.Default.PlayArrow, null); Text("PLAY") }
        }
    }
}

@Composable
private fun VaultSearch(sourceKey: String, vault: ChannelVault, play: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(emptyList<SportsChannel>()) }
    LaunchedEffect(query, sourceKey) {
        results = if (query.trim().length < 2) emptyList() else vault.search(sourceKey, query, 100)
    }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(14.dp), label = { Text("Search the channel vault") }, singleLine = true)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(7.dp), contentPadding = PaddingValues(bottom = 24.dp)) { items(results, key = { "search-${it.id}-${it.url}" }) { VaultChannel(it, play) } }
    }
}

@Composable private fun VaultSection(title: String, subtitle: String) = Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 5.dp), verticalAlignment = Alignment.Bottom) { Text(title, fontSize = 17.sp, fontWeight = FontWeight.Black); Spacer(Modifier.width(7.dp)); Text(subtitle, color = Color.Gray, fontSize = 11.sp) }
@Composable private fun VaultEmpty(text: String) = Card(Modifier.fillMaxWidth().padding(horizontal = 14.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF111624))) { Text(text, color = Color.Gray, modifier = Modifier.padding(18.dp)) }
