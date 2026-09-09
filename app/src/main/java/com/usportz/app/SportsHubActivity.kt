package com.usportz.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

class SportsHubActivity : ComponentActivity() {
    companion object { const val EXTRA_HUB = "hub" }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SportsHubScreen(intent.getStringExtra(EXTRA_HUB).orEmpty()) { finish() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SportsHubScreen(key: String, back: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val hub = remember(key) { SportsHubCatalog.find(key) }
    val favs = remember { Favs(context) }
    var channels by remember { mutableStateOf(emptyList<SportsChannel>()) }
    var events by remember { mutableStateOf(emptyList<SportsEvent>()) }
    var loading by remember { mutableStateOf(true) }

    fun reload() = scope.launch {
        loading = true
        channels = SportsChannelBridge.restoreCached(context).ifEmpty { SportsChannelBridge.load(context, false) }
        events = runCatching { SportsSchedule.load(false, channels) }.getOrDefault(emptyList()).filter { SportsHubCatalog.matches(hub, it) }
        loading = false
    }
    LaunchedEffect(key) { reload() }

    val live = events.filter { it.state == "in" }
    val upcoming = events.filter { it.state != "in" && it.state != "post" }.take(60)

    MaterialTheme(colorScheme = darkColorScheme()) {
        Scaffold(topBar = {
            TopAppBar(
                title = { Column { Text(hub.title, fontWeight = FontWeight.Black); Text(hub.subtitle, fontSize = 10.sp) } },
                navigationIcon = { IconButton(back) { Icon(Icons.Default.ArrowBack, "Back") } },
                actions = { IconButton(onClick = { reload() }, enabled = !loading) { Icon(Icons.Default.Refresh, "Refresh") } }
            )
        }) { pad ->
            LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { HubHeader(hub, live.size, events.size) }
                item { Text("LIVE NOW", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black) }
                if (live.isEmpty()) item { Text(if (loading) "Loading schedule…" else "No live events right now.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(live, key = { "live-${it.id}" }) { HubEvent(it, channels, favs) }
                item { Text("UPCOMING", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black, modifier = Modifier.padding(top = 8.dp)) }
                items(upcoming, key = { "up-${it.id}" }) { HubEvent(it, channels, favs) }
            }
        }
    }
}

@Composable
private fun HubHeader(hub: SportsHub, live: Int, total: Int) {
    Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(18.dp)) {
            Text(hub.title.uppercase(), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
            Text(hub.subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
                AssistChip(onClick = {}, label = { Text("${live} LIVE") })
                AssistChip(onClick = {}, label = { Text("${total} EVENTS") })
            }
        }
    }
}

@Composable
private fun HubEvent(event: SportsEvent, channels: List<SportsChannel>, favs: Favs) {
    val context = LocalContext.current
    val channel = SportsChannelBridge.bestMatch(event, channels)
    Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(10.dp)) {
            EventArtwork(event, SportsPresentation.brand(event))
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(SportsPresentation.matchup(event), fontWeight = FontWeight.Black, maxLines = 2)
                    Text(event.league, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = { favs.toggleEvent(event.id) }) { Icon(Icons.Default.Star, "Favorite") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AssistChip(onClick = {}, label = { Text(if (event.state == "in") "LIVE" else "UPCOMING", fontSize = 9.sp) })
                if (event.broadcast.isNotBlank()) AssistChip(onClick = {}, label = { Text(event.broadcast, fontSize = 9.sp) })
            }
            Text(event.detail.ifBlank { if (event.state == "in") "LIVE NOW" else event.startTime }, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            if (channel != null) {
                Button(onClick = { context.startActivity(Intent(context, RichPlayerActivity::class.java).putExtra(RichPlayerActivity.EXTRA_URL, channel.url)) }, modifier = Modifier.fillMaxWidth().padding(top = 7.dp)) {
                    Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text(if (event.state == "in") "WATCH LIVE" else "WATCH")
                }
                Text("Matched channel: ${channel.name}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
