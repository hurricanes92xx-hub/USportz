package com.usportz.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.SvgDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Shared app services: image loading, persistent channel identity, sports providers, and event intelligence. */
class USPortzApplication : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        ChannelDnaRuntime.init(this)
        SportsRuntimeV2.init(this)
        DefaultSportsProviders.install()
        SportsExpansionProviders.BallDontLie.let { SportsProviderEngine.register(it) }
        SportsExpansionProviders.PwhlLeagueStat.let { SportsProviderEngine.register(it) }
        SportsExpansionProviders.SportsDataverse.let { SportsProviderEngine.register(it) }

        // Cold-start accelerator: fetch only the highest-value sports categories first so
        // live games can become playable while the complete IPTV catalogue refresh continues.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                val source = SourceStore(this@USPortzApplication)
                if (source.server.isNotBlank() && source.user.isNotBlank() && source.pass.isNotBlank()) {
                    FastXtreamSportsBootstrap.bootstrap(this@USPortzApplication, source.server, source.user, source.pass, source.playlist)
                    // Publish the isolated startup preview into the in-memory bridge immediately.
                    // This does not activate or replace the full provider generation.
                    SportsChannelBridge.load(this@USPortzApplication, false)
                }
            }
        }

        // Non-blocking: uses disk snapshots first, then warms only the top live/upcoming streams.
        SportsStreamPreloadManager.bootstrap(this)
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components { add(SvgDecoder.Factory()) }
        .crossfade(false)
        .build()
}
