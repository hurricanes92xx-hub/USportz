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
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay

@OptIn(UnstableApi::class)
class RichPlayerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val url = intent.getStringExtra(EXTRA_URL).orEmpty().trim()
        if (url.isBlank()) { finish(); return }
        setContent { ProductionPlayer(url) }
    }

    @OptIn(UnstableApi::class)
    @Composable
    private fun ProductionPlayer(initialUrl: String) {
        val context = LocalContext.current
        var activeUrl by remember(initialUrl) { mutableStateOf(initialUrl) }
        var retry by remember(initialUrl) { mutableIntStateOf(0) }
        var error by remember(initialUrl) { mutableStateOf<String?>(null) }
        var startedAt by remember(initialUrl, retry, activeUrl) { mutableLongStateOf(System.currentTimeMillis()) }

        val player = remember(activeUrl, retry) {
            startedAt = System.currentTimeMillis()
            val dataSourceFactory = DefaultHttpDataSource.Factory()
                .setConnectTimeoutMs(5_000).setReadTimeoutMs(12_000)
                .setAllowCrossProtocolRedirects(true).setUserAgent("USportz/2.0")
            val loadControl = DefaultLoadControl.Builder().setBufferDurationsMs(1_500, 12_000, 800, 1_500)
                .setPrioritizeTimeOverSizeThresholds(true).build()
            val renderers = DefaultRenderersFactory(context).setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER).setEnableDecoderFallback(true)
            ExoPlayer.Builder(context, renderers).setBandwidthMeter(DefaultBandwidthMeter.getSingletonInstance(context)).setLoadControl(loadControl).build().apply {
                val uri = Uri.parse(activeUrl)
                val path = uri.toString().substringBefore('?').lowercase()
                val kind = StreamClassifier.kind(activeUrl)
                val looksHls = kind == StreamKind.HLS || path.contains("hls") || path.contains("m3u8")
                val item = MediaItem.Builder().setUri(uri).setMediaMetadata(MediaMetadata.Builder().setTitle("USportz Live").build()).build()
                if (looksHls) setMediaSource(HlsMediaSource.Factory(dataSourceFactory).createMediaSource(item)) else setMediaItem(item)
                addListener(object : Player.Listener {
                    override fun onPlayerError(e: PlaybackException) { error = e.errorCodeName }
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY) { PlaybackStartupMeter.record(startedAt, System.currentTimeMillis(), true); error = null }
                    }
                })
                prepare(); playWhenReady = true
            }
        }
        DisposableEffect(player) { onDispose { player.release() } }
        LaunchedEffect(player, error) {
            if (error != null) {
                PlaybackStartupMeter.record(startedAt, System.currentTimeMillis(), false)
                delay(IntelligentPlayback.RETRY_DELAY_MS)
                if (error != null) retry++
            }
        }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(factory = { vc -> PlayerView(vc).apply { setPlayer(player); useController = true; controllerShowTimeoutMs = 3_000; setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS); keepScreenOn = true; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT } }, update = { it.setPlayer(player) }, modifier = Modifier.fillMaxSize())
            IconButton(onClick = { finish() }, modifier = Modifier.align(Alignment.TopStart).padding(12.dp)) { Icon(Icons.Default.ArrowBack, "Back", tint = Color.White) }
            if (error != null) Box(Modifier.align(Alignment.Center)) {
                IconButton(onClick = { error = null; retry++ }) { Icon(Icons.Default.Refresh, "Retry", tint = Color.White) }
                Text("Playback interrupted — retrying…", color = Color.White, modifier = Modifier.padding(top = 56.dp))
            }
        }
    }
    companion object { const val EXTRA_URL = "stream_url" }
}
