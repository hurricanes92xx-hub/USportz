package com.usportz.app

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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

private enum class StableTab { HOME, LIVE, SEARCH, SOURCES }

@Composable
internal fun USportzStableApp(store: SourceStore) {
    val context = LocalContext.current
    var tab by remember { mutableStateOf(StableTab.HOME) }
    var channels by remember { mutableStateOf(emptyList<SportsChannel>()) }
    var loading by remember { mutableStateOf(true) }
    var refresh by remember { mutableIntStateOf(0) }

    LaunchedEffect(refresh) {
        loading = true
        channels = withContext(Dispatchers.IO) {
            runCatching { SportsChannelBridge.load(context, refresh > 0) }.getOrDefault(emptyList())
        }
        loading = false
    }

    fun openPlayer(url: String) {
        val clean = url.trim()
        if (clean.isNotEmpty()) {
            context.startActivity(Intent(context, RichPlayerActivity::class.java).putExtra(RichPlayerActivity.EXTRA_URL, clean))
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = Color(0xFFBFA6FF),
        secondary = Color(0xFF63D7FF),
        background = Color(0xFF080A10),
        surface = Color(0xFF111624)
    )) {
        Scaffold(
            containerColor = Color(0xFF080A10),
            topBar = {
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("USPORTZ", fontSize = 29.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                        Text(if (loading) "LOADING SAFELY" else "${channels.size} CHANNELS READY", color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    IconButton(onClick = { refresh++ }, enabled = !loading) { Icon(Icons.Default.Refresh, "Refresh") }
                }
            },
            bottomBar = {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TabButton(StableTab.HOME, tab, Icons.Default.Home) { tab = it }
                    TabButton(StableTab.LIVE, tab, Icons.Default.LiveTv) { tab = it }
                    TabButton(StableTab.SEARCH, tab, Icons.Default.Search) { tab = it }
                    TabButton(StableTab.SOURCES, tab, Icons.Default.SettingsInputAntenna) { tab = it }
                }
            }
        ) { pad ->
            Column(Modifier.fillMaxSize().padding(pad)) {
                when (tab) {
                    StableTab.HOME -> HomeScreen(channels)
                    StableTab.LIVE -> LiveScreen(channels, ::openPlayer)
                    StableTab.SEARCH -> SearchScreen(channels, ::openPlayer)
                    StableTab.SOURCES -> StableSources(store) { refresh++ }
                }
            }
        }
    }
}

@Composable
private fun TabButton(tab: StableTab, selected: StableTab, icon: androidx.compose.ui.graphics.vector.ImageVector, onSelect: (StableTab) -> Unit) {
    Column(
        Modifier.widthIn(min = 70.dp).clickable { onSelect(tab) }.padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, null, tint = if (selected == tab) Color(0xFF63D7FF) else Color.Gray)
        Text(tab.name.lowercase().replaceFirstChar { it.uppercase() }, color = if (selected == tab) Color(0xFF63D7FF) else Color.Gray, fontSize = 10.sp)
    }
}

@Composable
private fun HomeScreen(channels: List<SportsChannel>) {
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card(Modifier.fillMaxWidth().padding(14.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF151124))) {
                Column(Modifier.padding(20.dp)) {
                    Text("SPORTS COMMAND CENTER", color = Color(0xFF63D7FF), fontWeight = FontWeight.Black, fontSize = 11.sp)
                    Text("Fast with huge Xtream sources", fontSize = 25.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(top = 6.dp))
                    Text("${channels.size} channels loaded without building a giant UI index.", color = Color.Gray, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
        item { Section("LIVE TV", "Open Live TV to choose a category") }
        item { Empty("Categories load on demand so large playlists do not freeze the UI.") }
    }
}

@Composable
private fun LiveScreen(channels: List<SportsChannel>, play: (String) -> Unit) {
    var selected by remember { mutableStateOf<String?>(null) }
    var categories by remember { mutableStateOf(emptyList<String>()) }
    var visible by remember { mutableStateOf(emptyList<SportsChannel>()) }

    LaunchedEffect(channels) {
        categories = withContext(Dispatchers.Default) {
            channels.asSequence().map { categoryFor(it) }.filter { it.isNotBlank() }.distinctBy { it.lowercase() }.sortedBy { it.lowercase() }.toList()
        }
        selected = null
        visible = emptyList()
    }
    LaunchedEffect(selected, channels) {
        val wanted = selected ?: return@LaunchedEffect
        visible = withContext(Dispatchers.Default) {
            channels.asSequence().filter { categoryFor(it).equals(wanted, true) }.take(500).toList()
        }
    }

    if (selected == null) {
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Section("LIVE TV", "${categories.size} categories") }
            if (categories.isEmpty()) item { Empty("No categories available yet. Connect Xtream in Sources.") }
            items(categories, key = { "category-$it" }) { category ->
                Card(Modifier.fillMaxWidth().padding(horizontal = 14.dp).clickable { selected = category }, colors = CardDefaults.cardColors(containerColor = Color(0xFF111624))) {
                    Row(Modifier.padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Folder, null, tint = Color(0xFF63D7FF))
                        Text(category, Modifier.weight(1f).padding(start = 12.dp), fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Icon(Icons.Default.ChevronRight, null, tint = Color.Gray)
                    }
                }
            }
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { selected = null }) { Icon(Icons.Default.ArrowBack, "Back") }
                Text(selected!!, Modifier.weight(1f), fontSize = 19.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${visible.size}", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(end = 12.dp))
            }
            if (visible.isEmpty()) Empty("No channels in this category")
            else LazyColumn(verticalArrangement = Arrangement.spacedBy(7.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(visible, key = { "channel-${it.id}-${it.url}" }) { channel -> ChannelRow(channel, play) }
            }
        }
    }
}

private fun categoryFor(channel: SportsChannel): String {
    val group = channel.group.trim()
    val name = channel.name.trim()
    val marker = Regex("^##\\s*(.+?)\\s*##$").find(name)?.groupValues?.getOrNull(1)
    return when {
        marker != null -> marker.trim()
        group.isNotBlank() && !group.equals("live tv", true) -> group.removePrefix("##").removeSuffix("##").trim()
        else -> "Uncategorized"
    }.ifBlank { "Uncategorized" }
}

@Composable
private fun ChannelRow(channel: SportsChannel, play: (String) -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 14.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF111624))) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(horizontal = 7.dp)) {
                Text(channel.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(channel.group, color = Color.Gray, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            FilledTonalButton(onClick = { play(channel.url) }) { Icon(Icons.Default.PlayArrow, null); Text("PLAY") }
        }
    }
}

@Composable
private fun SearchScreen(channels: List<SportsChannel>, play: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(emptyList<SportsChannel>()) }
    LaunchedEffect(query, channels) {
        val q = query.trim().lowercase()
        results = if (q.length < 2) emptyList() else withContext(Dispatchers.Default) {
            channels.asSequence().filter { it.name.lowercase().contains(q) || it.group.lowercase().contains(q) }.take(100).toList()
        }
    }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(14.dp), label = { Text("Search channels") }, singleLine = true)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(7.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(results, key = { "search-${it.id}-${it.url}" }) { ChannelRow(it, play) }
        }
    }
}

@Composable
private fun Section(title: String, subtitle: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 5.dp), verticalAlignment = Alignment.Bottom) {
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.width(7.dp))
        Text(subtitle, color = Color.Gray, fontSize = 11.sp)
    }
}

@Composable
private fun Empty(text: String) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 14.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF111624))) {
        Text(text, color = Color.Gray, modifier = Modifier.padding(18.dp))
    }
}
