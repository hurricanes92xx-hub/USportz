package com.usportz.app

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal data class Channel(
    val id: String,
    val name: String,
    val group: String,
    val logo: String?,
    val url: String
)

private enum class ShellTab(val label: String) { HOME, SPORTS, LIVE_TV, FAVORITES, SEARCH, SOURCES }

@Composable
internal fun USportzApp(store: SourceStore) {
    var tab by remember { mutableStateOf(ShellTab.HOME) }
    var channels by remember { mutableStateOf(store.channels) }
    var selectedSport by remember { mutableStateOf("All") }
    var query by remember { mutableStateOf("") }
    var events by remember { mutableStateOf<List<SportsEvent>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    fun play(url: String) {
        context.startActivity(Intent(context, RichPlayerActivity::class.java).putExtra(RichPlayerActivity.EXTRA_URL, url))
    }
    fun refresh(force: Boolean) {
        loading = true
        scope.launch {
            runCatching { SportsSchedule.load(force) }
                .onSuccess { events = it; loading = false; error = if (it.isEmpty()) "No events are available right now." else null }
                .onFailure { loading = false; error = it.message ?: "Schedule unavailable" }
        }
    }
    LaunchedEffect(Unit) { refresh(false) }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = Color(0xFFBFA6FF), secondary = Color(0xFF63D7FF),
        tertiary = Color(0xFFFF6BCB), background = Color(0xFF070912), surface = Color(0xFF101522)
    )) {
        Scaffold(containerColor = Color(0xFF070912), bottomBar = {
            NavigationBar(containerColor = Color(0xFF0B0F1A)) {
                ShellTab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item, onClick = { tab = item },
                        icon = { Icon(tabIcon(item), item.label) }, label = { Text(item.label) }
                    )
                }
            }
        }) { padding ->
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item { PremiumTopBar(tab.label, loading, { refresh(true) }, { tab = ShellTab.SOURCES }) }
                when (tab) {
                    ShellTab.HOME -> homeContent(events, channels, loading, error, store, ::play)
                    ShellTab.SPORTS -> sportsContent(events, channels, selectedSport, { selectedSport = it }, loading, error, store, ::play)
                    ShellTab.LIVE_TV -> liveTvContent(channels, store, ::play)
                    ShellTab.FAVORITES -> favoritesContent(events, channels, store, ::play)
                    ShellTab.SEARCH -> searchContent(query, { query = it }, events, channels, store, ::play)
                    ShellTab.SOURCES -> sourcesContent(store) { channels = store.channels }
                }
            }
        }
    }
}

private fun tabIcon(tab: ShellTab) = when (tab) {
    ShellTab.HOME -> Icons.Default.Home
    ShellTab.SPORTS -> Icons.Default.SportsFootball
    ShellTab.LIVE_TV -> Icons.Default.LiveTv
    ShellTab.FAVORITES -> Icons.Default.Star
    ShellTab.SEARCH -> Icons.Default.Search
    ShellTab.SOURCES -> Icons.Default.SettingsInputAntenna
}

private fun androidx.compose.foundation.lazy.LazyListScope.homeContent(
    events: List<SportsEvent>, channels: List<Channel>, loading: Boolean, error: String?, store: SourceStore, play: (String) -> Unit
) {
    item { HeroCard(events.firstOrNull { it.state == "in" } ?: events.firstOrNull(), channels, play) }
    val live = SportsSchedule.liveEvents(events)
    if (live.isNotEmpty()) {
        item { SectionTitle("LIVE NOW", "On the air") }
        item { EventRail(live.take(8), channels, store, play) }
    }
    item { SectionTitle("TODAY'S EVENTS", "Live schedule") }
    if (events.isEmpty() && loading) item { LoadingCard() }
    else if (events.isEmpty()) item { EmptyCard("Schedule unavailable", error ?: "Try refresh or check your network.") }
    else item { EventRail(SportsSchedule.upcomingEvents(events).take(12), channels, store, play) }
    item { SectionTitle("SPORTS", "Browse by sport") }
    item { SportRail("All") }
    item { SectionTitle("WRESTLING HUB", "Dedicated experiences") }
    item { WrestlingRail() }
    item { SectionTitle("LIVE TV", "Your source") }
    item { ChannelRail(channels.take(10), store, play) }
}

private fun androidx.compose.foundation.lazy.LazyListScope.sportsContent(
    events: List<SportsEvent>, channels: List<Channel>, selected: String, select: (String) -> Unit,
    loading: Boolean, error: String?, store: SourceStore, play: (String) -> Unit
) {
    item { SportRail(selected, select) }
    if (selected == "Wrestling") item { WrestlingRail() }
    val filtered = SportsSchedule.forSport(events, selected)
    val live = SportsSchedule.liveEvents(filtered)
    if (live.isNotEmpty()) { item { SectionTitle("LIVE", "$selected now") }; item { EventRail(live, channels, store, play) } }
    item { SectionTitle("UPCOMING", "$selected schedule") }
    if (filtered.isEmpty() && loading) item { LoadingCard() }
    else if (filtered.isEmpty()) item { EmptyCard("No events", error ?: "More events will appear as feeds update.") }
    else item { EventRail(SportsSchedule.upcomingEvents(filtered), channels, store, play) }
}

private fun androidx.compose.foundation.lazy.LazyListScope.liveTvContent(channels: List<Channel>, store: SourceStore, play: (String) -> Unit) {
    item { SourceSummary(store, channels.size) }
    item { SearchHint("Search Live TV from the Search tab") }
    item { SectionTitle("CHANNELS", "${channels.size} indexed") }
    if (channels.isEmpty()) item { EmptyCard("No channels loaded", "Open Sources and connect Xtream or an M3U/M3U8 playlist.") }
    else items(channels, key = { it.id }) { ChannelRow(it, store.isChannelFavorite(it.id), { play(it.url) }, { store.toggleChannelFavorite(it.id) }) }
}

private fun androidx.compose.foundation.lazy.LazyListScope.favoritesContent(events: List<SportsEvent>, channels: List<Channel>, store: SourceStore, play: (String) -> Unit) {
    item { SectionTitle("FAVORITES", "Saved across restarts") }
    val favoriteEvents = events.filter { store.isEventFavorite(it.id) }
    val favoriteChannels = channels.filter { store.isChannelFavorite(it.id) }
    val favoriteTeams = store.favoriteTeams().toList()
    val favoriteLeagues = store.favoriteLeagues().toList()
    item { FavoriteSummary(favoriteEvents.size, favoriteTeams.size, favoriteLeagues.size, favoriteChannels.size) }
    if (favoriteEvents.isNotEmpty()) { item { SectionTitle("EVENTS", "Your saved games") }; item { EventRail(favoriteEvents, channels, store, play) } }
    if (favoriteChannels.isNotEmpty()) { item { SectionTitle("CHANNELS", "Your saved channels") }; item { ChannelRail(favoriteChannels, store, play) } }
    if (favoriteTeams.isNotEmpty()) item { FavoriteChips("TEAMS", favoriteTeams) }
    if (favoriteLeagues.isNotEmpty()) item { FavoriteChips("LEAGUES", favoriteLeagues) }
    if (favoriteEvents.isEmpty() && favoriteChannels.isEmpty() && favoriteTeams.isEmpty() && favoriteLeagues.isEmpty()) item { EmptyCard("Nothing saved yet", "Star an event, team, league, or channel to build your personal hub.") }
}

private fun androidx.compose.foundation.lazy.LazyListScope.searchContent(
    query: String, setQuery: (String) -> Unit, events: List<SportsEvent>, channels: List<Channel>, store: SourceStore, play: (String) -> Unit
) {
    item { SearchBox(query, setQuery) }
    if (query.isBlank()) {
        item { SearchHint("Search teams, events, leagues, channels, and sports") }
        item { SectionTitle("QUICK SEARCH", "Popular sports") }
        item { SportRail("All") }
    } else {
        val q = query.trim()
        val teamResults = events.flatMap { it.competitors }.distinct().filter { it.contains(q, true) }.take(8)
        val eventResults = events.filter { it.name.contains(q, true) || it.shortName.contains(q, true) }.take(8)
        val leagueResults = events.map { it.league }.distinct().filter { it.contains(q, true) }.take(8)
        val channelResults = channels.filter { it.name.contains(q, true) || it.group.contains(q, true) }.take(20)
        val sportsResults = SportsCatalog.categories.filter { it != "All" && it.contains(q, true) }
        SearchGroup("TEAMS", teamResults) { team -> store.toggleTeamFavorite(team) }
        SearchGroup("EVENTS", eventResults.map { SportsPresentation.matchup(it) }) { label -> events.firstOrNull { SportsPresentation.matchup(it) == label }?.let { store.toggleEventFavorite(it.id) } }
        SearchGroup("LEAGUES", leagueResults) { league -> store.toggleLeagueFavorite(league) }
        item { SectionTitle("CHANNELS", "${channelResults.size} matches") }
        if (channelResults.isEmpty()) item { EmptyCard("No channel matches", "Try a network, team, or group name.") }
        else items(channelResults, key = { "search-${it.id}" }) { ChannelRow(it, store.isChannelFavorite(it.id), { play(it.url) }, { store.toggleChannelFavorite(it.id) }) }
        SearchGroup("SPORTS", sportsResults) { }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.sourcesContent(store: SourceStore, onLoaded: () -> Unit) {
    item { SectionTitle("SOURCES", "Connect your sports streams") }
    item { SourceEditor(store, onLoaded) }
    item { SourceSummary(store, store.channels.size) }
    item { Text("Credentials stay on this device and are never bundled into the app or logged to GitHub.", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp)) }
}

@Composable private fun PremiumTopBar(title: String, loading: Boolean, refresh: () -> Unit, sources: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("USPORTZ", fontSize = 28.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
            Text(title, color = Color(0xFF9EA8BC), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        IconButton(onClick = refresh, enabled = !loading) { Icon(Icons.Default.Refresh, "Refresh") }
        IconButton(onClick = sources) { Icon(Icons.Default.SettingsInputAntenna, "Sources") }
    }
}

@Composable private fun HeroCard(event: SportsEvent?, channels: List<Channel>, play: (String) -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = RoundedCornerShape(26.dp)) {
        Box(Modifier.height(230.dp).fillMaxWidth().background(Brush.linearGradient(listOf(Color(0xFF22163D), Color(0xFF0C2234))))) {
            Column(Modifier.align(Alignment.BottomStart).padding(22.dp)) {
                Text(if (event?.state == "in") "LIVE NOW" else "FEATURED", color = Color(0xFFFF6BCB), fontWeight = FontWeight.Black, fontSize = 12.sp)
                Text(event?.let { SportsPresentation.matchup(it) } ?: "Your sports command center", fontSize = 25.sp, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(event?.league ?: "Connect a source to watch live", color = Color(0xFFB7C0D2), modifier = Modifier.padding(top = 4.dp))
                val best = event?.let { channels.maxByOrNull { c -> SportsSchedule.matchChannel(it, c.name, c.group) } }
                if (best != null && SportsSchedule.matchChannel(event, best.name, best.group) > 0) Button(onClick = { play(best.url) }, modifier = Modifier.padding(top = 10.dp), shape = RoundedCornerShape(12.dp)) { Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("WATCH") }
            }
        }
    }
}

@Composable private fun SectionTitle(title: String, subtitle: String) { Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.Bottom) { Column(Modifier.weight(1f)) { Text(title, fontSize = 19.sp, fontWeight = FontWeight.Black); Text(subtitle, color = Color(0xFF7F899D), fontSize = 11.sp) } } }

@Composable private fun EventRail(events: List<SportsEvent>, channels: List<Channel>, store: SourceStore, play: (String) -> Unit) { LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) { items(events, key = { it.id }) { EventCard(it, channels, store, play) } } }

@Composable private fun EventCard(event: SportsEvent, channels: List<Channel>, store: SourceStore, play: (String) -> Unit) {
    val best = channels.maxByOrNull { SportsSchedule.matchChannel(event, it.name, it.group) }
    val score = best?.let { SportsSchedule.matchChannel(event, it.name, it.group) } ?: 0
    Card(Modifier.width(285.dp), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(model = event.leagueLogo, contentDescription = event.league, modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Fit)
                Spacer(Modifier.width(10.dp)); Column(Modifier.weight(1f)) { Text(event.league, color = Color(0xFF8F9AAF), fontSize = 11.sp, fontWeight = FontWeight.Bold); Text(formatEventTime(event.startTime), fontSize = 11.sp, color = Color.Gray) }
                IconButton(onClick = { store.toggleEventFavorite(event.id) }, modifier = Modifier.size(36.dp)) { Icon(if (store.isEventFavorite(event.id)) Icons.Default.Star else Icons.Default.StarBorder, "Favorite") }
            }
            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                event.competitorLogos.getOrNull(0)?.let { AsyncImage(it, null, Modifier.size(46.dp), contentScale = ContentScale.Fit) }
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) { Text(event.competitors.getOrNull(0) ?: event.shortName, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis); Text("vs", color = Color.Gray, fontSize = 11.sp); Text(event.competitors.getOrNull(1) ?: "", fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                event.competitorLogos.getOrNull(1)?.let { AsyncImage(it, null, Modifier.size(46.dp), contentScale = ContentScale.Fit) }
            }
            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) { if (event.state == "in") Text("● LIVE", color = Color(0xFFFF5E6C), fontWeight = FontWeight.Black, fontSize = 11.sp); Spacer(Modifier.weight(1f)); Text(event.detail.ifBlank { "Scheduled" }, color = Color.Gray, fontSize = 11.sp) }
            if (best != null && score > 0) Button(onClick = { play(best.url) }, Modifier.fillMaxWidth().padding(top = 10.dp), shape = RoundedCornerShape(11.dp)) { Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(5.dp)); Text(if (event.state == "in") "WATCH LIVE" else "WATCH") }
        }
    }
}

@Composable private fun SportRail(selected: String, select: (String) -> Unit = {}) { LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(SportsCatalog.categories) { sport -> FilterChip(selected == sport, { select(sport) }, label = { Text(sport) }) } } }

@Composable private fun WrestlingRail() { LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) { listOf("WWE" to "RAW • SmackDown • NXT", "AEW" to "Dynamite • Collision", "TNA" to "Impact • Events", "ROH" to "Events").forEach { (name, detail) -> Card(Modifier.width(210.dp), shape = RoundedCornerShape(16.dp)) { Column(Modifier.padding(16.dp)) { Text(name, fontSize = 21.sp, fontWeight = FontWeight.Black); Text(detail, color = Color.Gray, fontSize = 11.sp, modifier = Modifier.padding(top = 5.dp)); Text("OPEN HUB", color = Color(0xFFBFA6FF), fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp)) } } } } }

@Composable private fun ChannelRail(channels: List<Channel>, store: SourceStore, play: (String) -> Unit) { LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) { items(channels, key = { it.id }) { ChannelMini(it, store.isChannelFavorite(it.id), { play(it.url) }, { store.toggleChannelFavorite(it.id) }) } } }

@Composable private fun ChannelMini(channel: Channel, favorite: Boolean, play: () -> Unit, fav: () -> Unit) { Card(Modifier.width(220.dp).clickable(onClick = play), shape = RoundedCornerShape(15.dp)) { Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) { AsyncImage(channel.logo, channel.name, Modifier.size(44.dp).clip(RoundedCornerShape(9.dp)), contentScale = ContentScale.Fit); Column(Modifier.weight(1f).padding(horizontal = 10.dp)) { Text(channel.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(channel.group, color = Color.Gray, fontSize = 10.sp, maxLines = 1) }; IconButton(onClick = fav, Modifier.size(34.dp)) { Icon(if (favorite) Icons.Default.Star else Icons.Default.StarBorder, "Favorite") } } } }

@Composable private fun ChannelRow(channel: Channel, favorite: Boolean, play: () -> Unit, fav: () -> Unit) { Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = RoundedCornerShape(15.dp)) { Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) { AsyncImage(channel.logo, channel.name, Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Fit); Column(Modifier.weight(1f).padding(horizontal = 12.dp)) { Text(channel.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis); Text("${channel.group} • ${SportsCatalog.classify(channel.name, channel.group)}", color = Color.Gray, fontSize = 11.sp) }; IconButton(onClick = fav) { Icon(if (favorite) Icons.Default.Star else Icons.Default.StarBorder, "Favorite") }; FilledTonalButton(onClick = play, shape = RoundedCornerShape(10.dp)) { Icon(Icons.Default.PlayArrow, null) } } } }

@Composable private fun SearchBox(value: String, change: (String) -> Unit) { OutlinedTextField(value, change, Modifier.fillMaxWidth().padding(horizontal = 16.dp), label = { Text("Search everything") }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true, shape = RoundedCornerShape(16.dp)) }
@Composable private fun SearchHint(text: String) { Text(text, color = Color.Gray, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
@Composable private fun SearchGroup(title: String, values: List<String>, onClick: (String) -> Unit) { if (values.isNotEmpty()) { SectionTitle(title, "${values.size} matches"); item {} ; values.forEach { value -> Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clickable { onClick(value) }, shape = RoundedCornerShape(12.dp)) { Text(value, Modifier.padding(15.dp), fontWeight = FontWeight.SemiBold) } } } }
@Composable private fun FavoriteSummary(events: Int, teams: Int, leagues: Int, channels: Int) { Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("EVENTS" to events, "TEAMS" to teams, "LEAGUES" to leagues, "CHANNELS" to channels).forEach { (label, count) -> Card(Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) { Column(Modifier.padding(12.dp)) { Text(count.toString(), fontSize = 20.sp, fontWeight = FontWeight.Black); Text(label, color = Color.Gray, fontSize = 9.sp) } } } } }
@Composable private fun FavoriteChips(title: String, values: List<String>) { Column(Modifier.padding(horizontal = 16.dp)) { Text(title, fontWeight = FontWeight.Bold, fontSize = 12.sp); LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 7.dp)) { items(values) { AssistChip(onClick = {}, label = { Text(it) }) } } } }
@Composable private fun SourceSummary(store: SourceStore, count: Int) { Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = RoundedCornerShape(18.dp)) { Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.CloudDone, null); Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(if (count > 0) "CONNECTED" else "NO SOURCE", fontWeight = FontWeight.Black); Text("$count channels indexed", color = Color.Gray, fontSize = 11.sp) }; Text(if (store.server.isNotBlank()) "XTREAM" else if (store.playlist.isNotBlank()) "M3U" else "—", color = Color(0xFF63D7FF), fontWeight = FontWeight.Bold) } } }
@Composable private fun LoadingCard() { Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = RoundedCornerShape(18.dp)) { Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(26.dp)); Spacer(Modifier.width(12.dp)); Text("Refreshing sports data…", fontWeight = FontWeight.Bold) } } }
@Composable private fun EmptyCard(title: String, message: String) { Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = RoundedCornerShape(18.dp)) { Column(Modifier.padding(18.dp)) { Text(title, fontWeight = FontWeight.Bold); Text(message, color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp)) } } }

@Composable private fun SourceEditor(store: SourceStore, onLoaded: () -> Unit) {
    var server by remember { mutableStateOf(store.server) }; var user by remember { mutableStateOf(store.user) }; var pass by remember { mutableStateOf(store.pass) }; var playlist by remember { mutableStateOf(store.playlist) }; var busy by remember { mutableStateOf(false) }; var status by remember { mutableStateOf("") }
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = RoundedCornerShape(20.dp)) { Column(Modifier.padding(16.dp)) {
        Text("XTREAM CODES", fontWeight = FontWeight.Black, fontSize = 16.sp)
        OutlinedTextField(server, { server = it }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text("Server") }, singleLine = true)
        OutlinedTextField(user, { user = it }, Modifier.fillMaxWidth().padding(top = 7.dp), label = { Text("Username") }, singleLine = true)
        OutlinedTextField(pass, { pass = it }, Modifier.fillMaxWidth().padding(top = 7.dp), label = { Text("Password") }, singleLine = true)
        Button(enabled = !busy && server.isNotBlank() && user.isNotBlank() && pass.isNotBlank(), onClick = { busy = true; status = "Connecting…"; store.saveXtream(server, user, pass) { ok, msg -> busy = false; status = msg; if (ok) onLoaded() } }, Modifier.fillMaxWidth().padding(top = 10.dp), shape = RoundedCornerShape(11.dp)) { Text(if (busy) "CONNECTING…" else "CONNECT XTREAM") }
        HorizontalDivider(Modifier.padding(vertical = 18.dp))
        Text("M3U / M3U8", fontWeight = FontWeight.Black, fontSize = 16.sp)
        OutlinedTextField(playlist, { playlist = it }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text("Playlist URL") }, singleLine = true)
        Button(enabled = !busy && playlist.isNotBlank(), onClick = { busy = true; status = "Loading playlist…"; store.saveM3u(playlist) { ok, msg -> busy = false; status = msg; if (ok) onLoaded() } }, Modifier.fillMaxWidth().padding(top = 10.dp), shape = RoundedCornerShape(11.dp)) { Text(if (busy) "LOADING…" else "LOAD PLAYLIST") }
        if (status.isNotBlank()) Text(status, color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
    } }
}

private fun formatEventTime(value: String): String { if (value.isBlank()) return "Time TBD"; val parsed = runCatching { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).parse(value) }.getOrNull() ?: runCatching { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).parse(value) }.getOrNull(); return parsed?.let { SimpleDateFormat("EEE, MMM d • h:mm a", Locale.getDefault()).format(Date(it.time)) } ?: value }

private class SourceStore(private val context: Context) {
    private val main = Handler(Looper.getMainLooper()); private val prefs = context.getSharedPreferences("usportz", Context.MODE_PRIVATE)
    var server: String get() = prefs.getString("server", "") ?: ""; private set(v) { prefs.edit().putString("server", v).apply() }
    var user: String get() = prefs.getString("user", "") ?: ""; private set(v) { prefs.edit().putString("user", v).apply() }
    var pass: String get() = prefs.getString("pass", "") ?: ""; private set(v) { prefs.edit().putString("pass", v).apply() }
    var playlist: String get() = prefs.getString("playlist", "") ?: ""; private set(v) { prefs.edit().putString("playlist", v).apply() }
    var channels: List<Channel> = loadChannels(); private set
    private fun setChannels(value: List<Channel>) { channels = value; saveChannels(value) }
    private fun setOf(key: String) = prefs.getStringSet(key, emptySet()) ?: emptySet()
    val favorites: Set<String> get() = setOf("favorites_channels")
    fun isChannelFavorite(id: String) = setOf("favorites_channels").contains(id)
    fun isEventFavorite(id: String) = setOf("favorites_events").contains(id)
    fun favoriteTeams() = setOf("favorites_teams"); fun favoriteLeagues() = setOf("favorites_leagues")
    private fun toggle(key: String, value: String) { val next = setOf(key).toMutableSet(); if (!next.add(value)) next.remove(value); prefs.edit().putStringSet(key, next).apply() }
    fun toggleChannelFavorite(id: String) = toggle("favorites_channels", id)
    fun toggleEventFavorite(id: String) = toggle("favorites_events", id)
    fun toggleTeamFavorite(team: String) = toggle("favorites_teams", team)
    fun toggleLeagueFavorite(league: String) = toggle("favorites_leagues", league)

    fun saveXtream(base: String, username: String, password: String, done: (Boolean, String) -> Unit) { server = base.trimEnd('/'); user = username; pass = password; val url = "$server/get.php?username=${URLEncoder.encode(username, "UTF-8")}&password=${URLEncoder.encode(password, "UTF-8")}&type=m3u_plus&output=ts"; loadUrl(url) { ok, result -> if (ok) { setChannels(parseM3u(result)); main.post { done(true, "Connected • ${channels.size} channels indexed") } } else main.post { done(false, result) } } }
    fun saveM3u(value: String, done: (Boolean, String) -> Unit) { playlist = value; loadUrl(value) { ok, result -> if (ok) { setChannels(parseM3u(result)); main.post { done(true, "Connected • ${channels.size} channels indexed") } } else main.post { done(false, result) } } }
    private fun loadUrl(value: String, done: (Boolean, String) -> Unit) { Thread { try { val conn = URL(value).openConnection() as HttpURLConnection; conn.connectTimeout = 10000; conn.readTimeout = 15000; conn.instanceFollowRedirects = true; conn.setRequestProperty("User-Agent", "USportz/1.0"); val text = conn.inputStream.bufferedReader().use { it.readText() }; conn.disconnect(); done(true, text) } catch (e: Exception) { done(false, "Source error: ${e.message ?: "Unable to load source"}") } }.start() }
    private fun parseM3u(text: String): List<Channel> { val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList(); val result = ArrayList<Channel>(minOf(3000, lines.size / 2)); var pending = emptyMap<String,String>(); for (line in lines) { if (line.startsWith("#EXTINF", true)) pending = attrs(line) else if (!line.startsWith("#")) { val name = pending["name"] ?: line.substringAfterLast('/').substringBefore('?').ifBlank { "Channel" }; val group = pending["group"] ?: "Live TV"; val logo = pending["logo"]; val id = "${name.lowercase()}|$line".hashCode().toString(); result += Channel(id, name, group, logo, line); pending = emptyMap(); if (result.size >= 3000) break } }; return result.distinctBy { it.id } }
    private fun attrs(extinf: String): Map<String,String> { val map = mutableMapOf<String,String>(); Regex("([\\w-]+)=\\\"([^\\\"]*)\\\"").findAll(extinf).forEach { map[it.groupValues[1].lowercase()] = it.groupValues[2] }; val comma = extinf.indexOf(','); if (comma >= 0) map["name"] = extinf.substring(comma + 1).trim(); return map }
    private fun saveChannels(value: List<Channel>) { val arr = JSONArray(); value.forEach { c -> arr.put(JSONObject().put("id", c.id).put("name", c.name).put("group", c.group).put("logo", c.logo ?: "").put("url", c.url)) }; prefs.edit().putString("channel_index", arr.toString()).apply() }
    private fun loadChannels(): List<Channel> = runCatching { val arr = JSONArray(prefs.getString("channel_index", "[]") ?: "[]"); buildList { for (i in 0 until arr.length()) { val o = arr.optJSONObject(i) ?: continue; add(Channel(o.optString("id"), o.optString("name"), o.optString("group"), o.optString("logo").ifBlank { null }, o.optString("url"))) } } }.getOrDefault(emptyList())
}
