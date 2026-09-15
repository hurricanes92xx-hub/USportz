package com.usportz.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import androidx.media3.exoplayer.source.preload.TargetPreloadStatusControl
import java.util.concurrent.atomic.AtomicReference

/**
 * Keeps the first few game streams warm while the user is looking at the sports home screen.
 * Only a tiny live buffer is preloaded for the highest-priority game; the rest get source/track
 * preparation. This avoids downloading the whole catalogue or wasting bandwidth on unseen games.
 */
@OptIn(UnstableApi::class)
object SportsStreamPreloadManager {
    private const val MAX_PRELOAD_ITEMS = 6
    private const val HOT_BUFFER_MS = 1_500L
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 12_000

    private class StatusControl : TargetPreloadStatusControl<Int, DefaultPreloadManager.PreloadStatus> {
        @Volatile var currentIndex: Int = 0
        override fun getTargetPreloadStatus(index: Int): DefaultPreloadManager.PreloadStatus {
            val distance = kotlin.math.abs(index - currentIndex)
            return when {
                distance == 0 -> DefaultPreloadManager.PreloadStatus.specifiedRangeLoaded(HOT_BUFFER_MS)
                distance == 1 -> DefaultPreloadManager.PreloadStatus.specifiedRangeLoaded(HOT_BUFFER_MS)
                distance <= 2 -> DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_TRACKS_SELECTED
                distance <= 4 -> DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_SOURCE_PREPARED
                else -> DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_NOT_PRELOADED
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val managerRef = AtomicReference<DefaultPreloadManager?>()
    private val builderRef = AtomicReference<DefaultPreloadManager.Builder?>()
    private val status = StatusControl()
    private val urls = LinkedHashSet<String>()

    private fun ensure(context: Context): DefaultPreloadManager {
        managerRef.get()?.let { return it }
        synchronized(this) {
            managerRef.get()?.let { return it }
            val app = context.applicationContext
            val dataSourceFactory = DefaultHttpDataSource.Factory()
                .setConnectTimeoutMs(CONNECT_TIMEOUT_MS)
                .setReadTimeoutMs(READ_TIMEOUT_MS)
                .setAllowCrossProtocolRedirects(true)
                .setUserAgent("USPortz/2.0")
            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(1_500, 12_000, 800, 1_500)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()
            val renderers = DefaultRenderersFactory(app)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                .setEnableDecoderFallback(true)
            val builder = DefaultPreloadManager.Builder(app, status)
                .setDataSourceFactory(dataSourceFactory)
                .setLoadControl(loadControl)
                .setRenderersFactory(renderers)
                .setBandwidthMeter(androidx.media3.exoplayer.upstream.DefaultBandwidthMeter.getSingletonInstance(app))
            val manager = builder.build()
            builderRef.set(builder)
            managerRef.set(manager)
            return manager
        }
    }

    /** Replace the small set of highest-priority visible game streams to warm. Must be called on main. */
    fun warm(context: Context, channels: List<SportsChannel>) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { warm(context, channels) }
            return
        }
        val manager = ensure(context)
        manager.reset()
        urls.clear()
        val selected = channels.asSequence()
            .map { it.url.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(MAX_PRELOAD_ITEMS)
            .toList()
        selected.forEachIndexed { index, url ->
            manager.add(MediaItem.fromUri(url), index)
            urls += url
        }
        status.currentIndex = 0
        manager.invalidate()
    }

    /** Returns a managed/preloaded MediaSource; if this is a cold tap, it is added immediately. */
    fun mediaSource(context: Context, url: String): MediaSource? {
        if (Looper.myLooper() != Looper.getMainLooper()) return null
        val clean = url.trim()
        if (clean.isBlank()) return null
        val manager = ensure(context)
        if (!urls.contains(clean)) {
            manager.add(MediaItem.fromUri(clean), 0)
            urls.add(clean)
            status.currentIndex = 0
            manager.invalidate()
        }
        return manager.getMediaSource(MediaItem.fromUri(clean))
    }

    fun setCurrentPlayingUrl(url: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { setCurrentPlayingUrl(url) }
            return
        }
        val index = urls.indexOf(url)
        if (index >= 0) {
            status.currentIndex = index
            managerRef.get()?.setCurrentPlayingIndex(index)
        }
    }

    /** The player must be created from the same builder as the preload manager. */
    fun buildPlayer(context: Context): ExoPlayer {
        val builder = builderRef.get() ?: ensure(context).let { builderRef.get()!! }
        val renderers = DefaultRenderersFactory(context.applicationContext)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            .setEnableDecoderFallback(true)
        return builder.buildExoPlayer(ExoPlayer.Builder(context, renderers))
    }
}
