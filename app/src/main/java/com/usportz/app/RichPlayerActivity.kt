package com.usportz.app

import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.ui.PlayerView

@androidx.annotation.OptIn(UnstableApi::class)
class RichPlayerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val url = intent.getStringExtra(EXTRA_URL).orEmpty().trim()
        if (url.isBlank()) { finish(); return }
        setContent { ProductionPlayer(url) }
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    @androidx.compose.runtime.Composable
    private fun ProductionPlayer(url: String) {
        val context = LocalContext.current
        var retry by remember(url) { mutableIntStateOf(0) }
        var error by remember(url) { mutableStateOf<String?>(null) }
        val player = remember(url, retry) {
            val dataSourceFactory = DefaultHttpDataSource.Factory()
                .setConnectTimeoutMs(8_000)
                .setReadTimeoutMs(15_000)
                .setAllowCrossProtocolRedirects(true)
                .setUserAgent("USportz/1.0")
            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(2_500, 15_000, 1_000, 2_000)
                .build()
            ExoPlayer.Builder(context)
                .setBandwidthMeter(DefaultBandwidthMeter.getSingletonInstance(context))
                .setLoadControl(loadControl)
                .build().apply {
                    val uri = Uri.parse(url)
                    val item = MediaItem.Builder().setUri(uri).build()
                    if (uri.toString().substringBefore('?').endsWith(".m3u8", true)) {
                        setMediaSource(HlsMediaSource.Factory(dataSourceFactory).createMediaSource(item))
                    } else {
                        setMediaItem(item)
                    }
                    addListener(object : Player.Listener {
                        override fun onPlayerError(playbackException: PlaybackException) {
                            error = playbackException.errorCodeName
                        }
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            if (playbackState == Player.STATE_READY) error = null
                        }
                    })
                    prepare()
                    playWhenReady = true
                }
        }
        DisposableEffect(player) { onDispose { player.release() } }
        LaunchedEffect(player) { player.playWhenReady = true }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                factory = { PlayerView(it).apply {
                    this.player = player
                    useController = true
                    controllerShowTimeoutMs = 3_000
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    keepScreenOn = true
                } },
                update = { it.player = player },
                modifier = Modifier.fillMaxSize()
            )
            IconButton(
                onClick = { finish() },
                modifier = Modifier.align(Alignment.TopStart).padding(12.dp)
            ) { Icon(Icons.Default.ArrowBack, "Back", tint = Color.White) }
            if (error != null) {
                Box(Modifier.align(Alignment.Center)) {
                    IconButton(onClick = { error = null; retry++ }) {
                        Icon(Icons.Default.Refresh, "Retry", tint = Color.White)
                    }
                    Text("Playback error — tap retry", color = Color.White, modifier = Modifier.padding(top = 56.dp))
                }
            }
        }
    }

    companion object { const val EXTRA_URL = "stream_url" }
}
