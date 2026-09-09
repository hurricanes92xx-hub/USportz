package com.usportz.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

class LeagueHubActivity : ComponentActivity() {
    companion object { const val EXTRA_BRAND = "brand" }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val key = intent.getStringExtra(EXTRA_BRAND).orEmpty()
        setContent { LeagueHub(key, ::finish) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LeagueHub(key: String, back: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val brand = remember(key) { SportsBranding.brands.firstOrNull { it.key == key } ?: SportsBranding.brands.first() }
    val favs = remember { Favs(context) }
    var channels by remember { mutableStateOf(emptyList<SportsChannel>()) }
    var events by remember { mutableStateOf(emptyList<SportsEvent>()) }
    var loading by remember { mutableStateOf(true) }
    fun load() = scope.launch {
        loading = true
        channels = SportsChannelBridge.restoreCached(context)
        if (channels.isEmpty()) channels = SportsChannelBridge.load(context, false)
        events = runCatching { SportsSchedule.load(false, channels) }.getOrDefault(emptyList())
            .filter { SportsBranding.find(it.name, it.league)?.key == brand.key }
        loading = false
    }
    LaunchedEffect(key) { load() }
    val live = events.filter { it.state == "in" }
    val upcoming = events.filter { it.state != "in" && it.state != "post" }.take(30)
    MaterialTheme(colorScheme = darkColorScheme()) {
        Scaffold(topBar = {
            TopAppBar(title = { Text(brand.label) }, navigationIcon = { IconButton(back) { Icon(Icons.Default.ArrowBack, "Back") } }, actions = { TextButton(onClick = { load() }, enabled = !loading) { Text("REFRESH") } })
        }) { pad ->
            LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { LeagueHero(brand, live.size, events.size) }
                item { Text("LIVE NOW", style = MaterialTheme.typography.titleMedium) }
                if (live.isEmpty()) item { Text(if (loading) "Loading schedule…" else "No live events right now.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(live, key = { it.id }) { event -> LeagueEvent(event, channels, favs) }
                item { Text("UPCOMING", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
                items(upcoming, key = { it.id }) { event -> LeagueEvent(event, channels, favs) }
            }
        }
    }
}

@Composable
private fun LeagueEvent(event: SportsEvent, channels: List<SportsChannel>, favs: Favs) {
    val channel = SportsChannelBridge.bestMatch(event, channels)
    Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(10.dp)) {
            EventArtwork(event, compact = false)
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = {}, label = { Text(if (event.state == "in") "LIVE" else "UPCOMING", fontSize = 9.sp) })
                if (event.broadcast.isNotBlank()) AssistChip(onClick = {}, label = { Text(event.broadcast, fontSize = 9.sp) })
            }
            Text(SportsPresentation.matchup(event), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 7.dp))
            Text(event.detail.ifBlank { if (event.state == "in") "LIVE NOW" else "Scheduled" }, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { favs.toggleEvent(event.id) }) { Text(if (favs.isEventFav(event.id)) "★ SAVED" else "☆ SAVE") }
                if (channel != null) Text(channel.name, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}
