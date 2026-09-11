package com.usportz.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class RichSportsV2Activity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { ResilientSportsScreen(this) } }
}

private val V2Bg = Color(0xFF080A12)
private val V2Panel = Color(0xFF141827)
private val V2Orange = Color(0xFFFF6A00)
private val V2Red = Color(0xFFFF335C)
private val V2Cyan = Color(0xFF14D9FF)
private val V2Text = Color(0xFFF5F7FA)
private val V2Muted = Color(0xFF98A0B3)

enum class V2Bucket(val label: String) { LIVE("LIVE"), SOON("SOON"), TODAY("TODAY"), TOMORROW("TOMORROW"), NEXT("NEXT 7 DAYS") }

@Composable
private fun ResilientSportsScreen(activity: ComponentActivity) {
    var events by remember { mutableStateOf<List<SportsEvent>>(emptyList()) }
    var channels by remember { mutableStateOf<List<SportsChannel>>(emptyList()) }
    var sport by remember { mutableStateOf("All") }
    var bucket by remember { mutableStateOf(V2Bucket.LIVE) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val scope = rememberCoroutineScope()

    fun refresh() {
        scope.launch {
            loading = true; error = ""
            try {
                val result = withContext(Dispatchers.IO) {
                    val e = SportsScheduleV2.load(true)
                    val c = runCatching { SportsChannelBridge.load(activity, false) }.getOrDefault(emptyList())
                    e to c
                }
                events = result.first; channels = result.second; now = System.currentTimeMillis()
            } catch (t: Throwable) { error = t.message?.takeIf { it.isNotBlank() } ?: "Schedule service unavailable" }
            finally { loading = false }
        }
    }

    LaunchedEffect(Unit) {
        refresh()
        while (true) { delay(60_000); refresh() }
    }

    val filtered = SportsScheduleV2.forSport(events, sport)
    val visible = remember(filtered, bucket, now) { filtered.filter { inBucketV2(it, bucket, now) }.sortedBy { startMsV2(it.startTime) ?: Long.MAX_VALUE } }
    val liveCount = remember(events, now) { events.count { isLiveV2(it, now) } }
    val soonCount = remember(events, now) { events.count { isSoonV2(it, now) } }
    val todayCount = remember(events, now) { events.count { isTodayV2(it, now) } }

    MaterialTheme(colorScheme = darkColorScheme(primary = V2Orange, secondary = V2Cyan, background = V2Bg, surface = V2Panel)) {
        Column(Modifier.fillMaxSize().background(V2Bg)) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("USPORTZ", color = V2Text, fontSize = 30.sp, fontWeight = FontWeight.Black); Text("LIVE SPORTS COMMAND CENTER", color = V2Orange, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                IconButton(onClick = { refresh() }, enabled = !loading) { Icon(Icons.Default.Refresh, "Refresh", tint = if (loading) V2Muted else V2Orange) }
            }
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp), colors = CardDefaults.cardColors(containerColor = V2Panel), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(17.dp)) {
                    Text("$liveCount LIVE  •  $soonCount SOON  •  $todayCount TODAY", color = V2Text, fontSize = 17.sp, fontWeight = FontWeight.Black)
                    Text("${events.size} events loaded independently of Xtream  •  ${channels.size} sports channels", color = V2Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 5.dp))
                    if (error.isNotBlank()) Text(error, color = V2Orange, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
                }
            }
            LazyRow(contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FilterChip(selected = sport == "All", onClick = { sport = "All" }, label = { Text("All") }) }
                items(SportsCatalog.categories) { s -> FilterChip(selected = sport == s, onClick = { sport = s }, label = { Text(s) }) }
            }
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(V2Bucket.values().toList()) { b -> FilterChip(selected = bucket == b, onClick = { bucket = b }, label = { Text(b.label) }) }
            }
            if (loading && events.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = V2Orange) }
            else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp, 10.dp, 12.dp, 30.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (visible.isEmpty()) item { EmptyV2(bucket) }
                items(visible.take(100), key = { "v2-${bucket.name}-${it.id}" }) { event -> EventV2(event, bucket == V2Bucket.LIVE, channels, activity) }
            }
        }
    }
}

@Composable private fun EmptyV2(bucket: V2Bucket) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = V2Panel)) { Column(Modifier.padding(22.dp)) {
        Text(when (bucket) { V2Bucket.LIVE -> "No live games right now"; V2Bucket.SOON -> "Nothing starting soon"; V2Bucket.TODAY -> "No remaining events today"; V2Bucket.TOMORROW -> "Nothing listed tomorrow"; V2Bucket.NEXT -> "No upcoming events" }, color = V2Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text("Schedule data is independent from your IPTV/Xtream source.", color = V2Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
    } }
}

@Composable private fun EventV2(event: SportsEvent, live: Boolean, channels: List<SportsChannel>, activity: ComponentActivity) {
    val matches = remember(event.id, channels) { GameSourceMatcher.rankMatches(event, channels, 3) }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = V2Panel), shape = RoundedCornerShape(17.dp)) {
        Column(Modifier.padding(15.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (live) "● LIVE NOW" else "UPCOMING", color = if (live) V2Red else V2Orange, fontSize = 10.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.width(9.dp)); Text(event.league, color = V2Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis); Spacer(Modifier.weight(1f))
                if (!live) Text(eventClockV2(event.startTime), color = V2Muted, fontSize = 10.sp)
            }
            Text(SportsPresentation.matchup(event), color = V2Text, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(top = 8.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(event.detail.ifBlank { eventClockV2(event.startTime) }, color = V2Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp))
            matches.forEachIndexed { i, match ->
                OutlinedButton(onClick = { activity.startActivity(Intent(activity, RichPlayerActivity::class.java).putExtra(RichPlayerActivity.EXTRA_URL, match.channel.url)) }, modifier = Modifier.fillMaxWidth().padding(top = 7.dp)) {
                    Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text(if (i == 0) "WATCH • ${match.channel.name}" else "STREAM ${i + 1} • ${match.channel.name}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

private fun inBucketV2(event: SportsEvent, bucket: V2Bucket, now: Long): Boolean {
    val start = startMsV2(event.startTime) ?: return false
    val delta = start - now
    return when (bucket) {
        V2Bucket.LIVE -> isLiveV2(event, now)
        V2Bucket.SOON -> !isLiveV2(event, now) && delta > 0 && delta <= 6 * 60 * 60 * 1000L
        V2Bucket.TODAY -> !isLiveV2(event, now) && isTodayV2(event, now)
        V2Bucket.TOMORROW -> Instant.ofEpochMilli(start).atZone(ZoneId.systemDefault()).toLocalDate() == java.time.LocalDate.now().plusDays(1)
        V2Bucket.NEXT -> start > now
    }
}
private fun isLiveV2(event: SportsEvent, now: Long): Boolean { val start = startMsV2(event.startTime) ?: return false; if (event.state == "in") return true; if (event.state == "post") return false; return now >= start && now <= start + 6 * 60 * 60 * 1000L }
private fun isSoonV2(event: SportsEvent, now: Long): Boolean { val s = startMsV2(event.startTime) ?: return false; return !isLiveV2(event, now) && s > now && s - now <= 6 * 60 * 60 * 1000L }
private fun isTodayV2(event: SportsEvent, now: Long): Boolean { val s = startMsV2(event.startTime) ?: return false; return Instant.ofEpochMilli(s).atZone(ZoneId.systemDefault()).toLocalDate() == Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate() }
private fun startMsV2(value: String): Long? = runCatching { Instant.parse(value).toEpochMilli() }.getOrElse { runCatching { java.time.OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrNull() }
private fun eventClockV2(value: String): String = startMsV2(value)?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("EEE MMM d • h:mm a")) } ?: value
