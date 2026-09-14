package com.usportz.app

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Sprint 2: focused live-sports presentation with bounded lists and one-tap playback. */
@Composable
fun LiveSportsScreen(context: Context, events: List<SportsEvent>, channels: List<SportsChannel>, favorites: Favs, onRefresh: () -> Unit = {}) {
    var tab by remember { mutableStateOf("Live Now") }
    val now = System.currentTimeMillis()
    val live = events.map { it.copy(state = liveState(it, now)) }.filter { it.state == "in" }
    val upcoming = events.map { it.copy(state = liveState(it, now)) }.filter { it.state == "pre" }.sortedBy { epoch(it.startTime) ?: Long.MAX_VALUE }.take(60)
    val mine = events.filter { favorites.isEventFav(it.id) }
    val shown = when (tab) { "Upcoming" -> upcoming; "My Sports" -> mine; else -> live }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Live Now", "Upcoming", "My Sports").forEach { label -> FilterChip(selected = tab == label, onClick = { tab = label }, label = { Text(label) }) }
            Spacer(Modifier.weight(1f)); TextButton(onClick = onRefresh) { Text("REFRESH") }
        }
        Spacer(Modifier.height(10.dp)); Text(if (tab == "Live Now") "LIVE NOW • ${shown.size}" else tab.uppercase(), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        if (shown.isEmpty()) Text(if (tab == "My Sports") "Save an event to see it here." else "No games in this section right now.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        else LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) { items(shown, key = { it.id }) { SportsEventCard(context, it, channels, favorites) } }
    }
}

@Composable
private fun SportsEventCard(context: Context, event: SportsEvent, channels: List<SportsChannel>, favorites: Favs) {
    val sources = remember(event.id, channels) { GameSourceMatcher.rankMatches(event, channels, 8) }
    val best = sources.firstOrNull()?.channel
    Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AssistChip(onClick = {}, label = { Text(SportsPresentation.status(event)) }); Spacer(Modifier.width(8.dp))
                Text(SportsPresentation.label(event), style = MaterialTheme.typography.labelLarge); Spacer(Modifier.weight(1f))
                Text(eventTimeLabel(event), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp)); Text(SportsPresentation.matchup(event), style = MaterialTheme.typography.titleMedium)
            if (event.detail.isNotBlank()) { Spacer(Modifier.height(3.dp)); Text(event.detail, style = MaterialTheme.typography.bodyMedium) }
            if (event.broadcast.isNotBlank()) { Spacer(Modifier.height(5.dp)); Text("Broadcast: ${BroadcasterNormalizer.canonical(event.broadcast)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { favorites.toggleEvent(event.id) }) { Text(if (favorites.isEventFav(event.id)) "★ MY SPORTS" else "☆ MY SPORTS") }
                if (best != null) Button(onClick = { context.startActivity(Intent(context, RichPlayerActivity::class.java).putExtra(RichPlayerActivity.EXTRA_URL, best.url)) }) { Text("WATCH LIVE") }
                Spacer(Modifier.weight(1f)); Text("${sources.size} SOURCES", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (sources.isNotEmpty()) {
                Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp)).padding(8.dp)) {
                    Text("SOURCES", style = MaterialTheme.typography.labelMedium)
                    sources.take(4).forEachIndexed { index, match ->
                        Row(Modifier.fillMaxWidth().padding(top = 5.dp).clickable { context.startActivity(Intent(context, RichPlayerActivity::class.java).putExtra(RichPlayerActivity.EXTRA_URL, match.channel.url)) }, verticalAlignment = Alignment.CenterVertically) {
                            Text("${index + 1}. ${match.channel.name}", modifier = Modifier.weight(1f), fontSize = 12.sp); Text("${match.score}", fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

private fun liveState(event: SportsEvent, now: Long): String {
    if (event.state.equals("post", true)) return "post"; if (event.state.equals("in", true)) return "in"
    val start = epoch(event.startTime) ?: return event.state
    return when { now < start -> "pre"; now <= start + 4 * 60 * 60 * 1000L -> "in"; else -> "post" }
}
private fun epoch(value: String): Long? = runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
private fun eventTimeLabel(event: SportsEvent): String = runCatching {
    val z = Instant.parse(event.startTime).atZone(ZoneId.systemDefault()); if (event.state == "in") "LIVE • ${z.format(DateTimeFormatter.ofPattern("h:mm a"))}" else z.format(DateTimeFormatter.ofPattern("EEE h:mm a"))
}.getOrDefault("")
