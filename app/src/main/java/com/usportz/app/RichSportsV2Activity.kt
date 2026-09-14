package com.usportz.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Sprint 2 entry point: feeds the new LiveSportsScreen from the disk-backed sports catalogue. */
class RichSportsV2Activity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ResilientSportsScreen() }
    }
}

@Composable
private fun ResilientSportsScreen() {
    val context = LocalContext.current
    var events by remember { mutableStateOf<List<SportsEvent>>(emptyList()) }
    var channels by remember { mutableStateOf<List<SportsChannel>>(emptyList()) }
    var refreshToken by remember { mutableIntStateOf(0) }
    val favorites = remember { mutableStateMapOf<String, Boolean>() }

    LaunchedEffect(refreshToken) {
        val force = refreshToken > 0
        val loadedChannels = withContext(Dispatchers.IO) {
            SportsChannelBridge.restoreCached(context).ifEmpty { SportsChannelBridge.load(context, force) }
        }
        channels = loadedChannels
        events = withContext(Dispatchers.IO) {
            runCatching { (SportsSchedule.load(force, loadedChannels) + MonsterJamSchedule.load()).distinctBy { it.id } }
                .getOrDefault(emptyList())
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            refreshToken++
        }
    }

    LiveSportsScreen(
        context = context,
        events = events,
        channels = channels,
        favorites = favorites,
        onRefresh = { refreshToken++ }
    )
}
