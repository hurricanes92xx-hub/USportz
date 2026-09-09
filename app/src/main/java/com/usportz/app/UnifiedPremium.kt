package com.usportz.app

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

private enum class Nav { HOME, SPORTS, LIVE, FAV, SEARCH, SOURCES }

@Composable
internal fun USportzApp(store: SourceStore) {
    val context = LocalContext.current
    var nav by remember { mutableStateOf(Nav.HOME) }
    var sport by remember { mutableStateOf("All") }
    var query by remember { mutableStateOf("") }
    var events by remember { mutableStateOf(emptyList<SportsEvent>()) }
    var channels by remember { mutableStateOf(emptyList<SportsChannel>()) }
    var loading by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    val favs = remember { Favs(context) }

    LaunchedEffect(refresh) {
        loading = true
        channels = SportsChannelBridge.load(context, refresh > 0)
        runCatching { SportsSchedule.load(refresh > 0, channels) }.onSuccess { events = it }
        loading = false
    }

    fun play(url: String) {
        context.startActivity(Intent(context, RichPlayerActivity::class.java).putExtra(RichPlayerActivity.EXTRA_URL, url))
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFBFA6FF), secondary = Color(0xFF63D7FF), tertiary = Color(0xFFFF5FAF), background = Color(0xFF080A10), surface = Color(0xFF10131D))) {
        Scaffold(containerColor = Color(0xFF080A10), bottomBar = {
            NavigationBar(containerColor = Color(0xFF0D1018)) {
                Nav.values().forEach { n -> NavigationBarItem(selected = nav == n, onClick = { nav = n }, icon = { Icon(iconFor(n), null) }, label = { Text(n.name.lowercase().replaceFirstChar { it.uppercase() }) }) }
            }
        }) { pad ->
            LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(bottom = 12.dp)) {
                item { Header(nav.name, loading, { refresh++ }, { nav = Nav.SOURCES }) }
                when (nav) {
                    Nav.HOME -> home(events, channels, favs, ::play)
                    Nav.SPORTS -> sports(events, channels, sport, { sport = it }, favs, ::play)
                    Nav.LIVE -> live(channels, favs, ::play)
                    Nav.FAV -> favorites(events, channels, favs, ::play)
                    Nav.SEARCH -> search(query, { query = it }, events, channels, favs, ::play)
                    Nav.SOURCES -> sources(store) { refresh++ }
                }
            }
        }
    }
}

private fun iconFor(n: Nav) = when (n) {
    Nav.HOME -> Icons.Default.Home
    Nav.SPORTS -> Icons.Default.SportsScore
    Nav.LIVE -> Icons.Default.LiveTv
    Nav.FAV -> Icons.Default.Star
    Nav.SEARCH -> Icons.Default.Search
    Nav.SOURCES -> Icons.Default.SettingsInputAntenna
}