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
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import androidx.media3.exoplayer.source.preload.TargetPreloadStatusControl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.OffsetDateTime
import java.util.concurrent.atomic.AtomicBoolean
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

    /** Media3 1.5.x uses TargetPreloadStatusControl.PreloadStatus/DefaultPreloadManager.Status. */
    private class StatusControl : TargetPreloadStatusControl<Int> {
        @Volatile var currentIndex: Int = 0

        override fun getTargetPreloadStatus(index: Int): TargetPreloadStatusControl.PreloadStatus {
            val distance = kotlin.math.abs(index - currentIndex)
            return when {
                distance <= 1 -> DefaultPreloadManager.Status(
                    DefaultPreloadManager.Status.STAGE_LOADED_FOR_DURATION_MS,
                    HOT_BUFFER_MS
                )
                distance <= 2 -> DefaultPreloadManager.Status(
                    DefaultPreloadManager.Status.STAGE_TRACKS_SELECTED
                )
                distance <= 4 -> DefaultPreloadManager.Status(
                    DefaultPreloadManager.Status.STAGE_SOURCE_PREPARED
                )
                else -> DefaultPreloadManager.Status(
                    DefaultPreloadManager.Status.STAGE_SOURCE_PREPARED
                )
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val managerRef = AtomicReference<DefaultPreloadManager?>()
    private val builderRef = AtomicReference<DefaultPreloadManager.Builder?>()
    private val status = StatusControl()
    private val urls = LinkedHashSet<String>()
    private val bootstrapScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bootstrapped = AtomicBoolean(false)

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
            val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(1_500, 12_000, 800, 1_500)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()
            val renderers = DefaultRenderersFactory(app)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                .setEnableDecoderFallback(true)
            val builder = DefaultPreloadManager.Builder(app, status)
                .setMediaSourceFactory(mediaSourceFactory)
                .setLoadControl(loadControl)
                .setRenderersFactory(renderers)
                .setBandwidthMeter(
                    androidx.media3.exoplayer.upstream.DefaultBandwidthMeter.getSingletonInstance(app)
                )
            val manager = builder.build()
            builderRef.set(builder)
            managerRef.set(manager)
            return manager
        }
    }

    /**
     * Starts a non-blocking startup prewarm using the already-cached catalogue and schedule.
     * If no cached catalogue exists, SportsChannelBridge performs its normal background refresh;
     * this routine never waits for the full provider catalogue before the first UI is drawn.
     */
    fun bootstrap(context: Context) {
        if (!bootstrapped.compareAndSet(false, true)) return
        val app = context.applicationContext
        bootstrapScope.launch {
            runCatching {
                val channelsDeferred = async { SportsChannelBridge.load(app, false) }
                val eventsDeferred = async {
                    (SportsSchedule.load(false) + MonsterJamSchedule.load()).distinctBy { it.id }
                }
                val channels = channelsDeferred.await()
                val events = eventsDeferred.await()
                val now = System.currentTimeMillis()
                val candidates = events.asSequence()
                    .mapNotNull { event ->
                        val start = parseEpoch(event.startTime)
                        val live = event.state.equals("in", ignoreCase = true)
                        val upcoming = start != null && start >= now && start <= now + 6 * 60 * 60 * 1000L
                        if (!live && !upcoming) return@mapNotNull null
                        val priority = when {
                            live -> 0
                            start != null -> 1
                            else -> 2
                        }
                        Triple(priority, start ?: Long.MAX_VALUE, event)
                    }
                    .sortedWith(compareBy<Triple<Int, Long, SportsEvent>> { it.first }.thenBy { it.second })
                    .take(16)
                    .mapNotNull { (_, _, event) ->
                        SportsResolver.resolve(event, channels, 1).firstOrNull()?.channel
                    }
                    .distinctBy { it.url.trim().lowercase() }
                    .take(MAX_PRELOAD_ITEMS)
                    .toList()
                if (candidates.isNotEmpty()) warm(app, candidates)
            }
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
        val index = urls.indexOf(url.trim())
        if (index >= 0) {
            status.currentIndex = index
            managerRef.get()?.let {
                it.setCurrentPlayingIndex(index)
                it.invalidate()
            }
        }
    }

    /** The player must be created from the same builder as the preload manager. */
    fun buildPlayer(context: Context): ExoPlayer {
        val builder = builderRef.get() ?: ensure(context).let { builderRef.get()!! }
        return builder.buildExoPlayer(ExoPlayer.Builder(context))
    }

    private fun parseEpoch(value: String): Long? =
        runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrNull()
            ?: value.toLongOrNull()?.let { if (it < 10_000_000_000L) it * 1000 else it }
}
