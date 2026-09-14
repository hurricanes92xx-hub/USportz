package com.usportz.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.SvgDecoder

/** Shared app services: image loading, persistent channel identity, sports providers, and event intelligence. */
class USportzApplication : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        ChannelDnaRuntime.init(this)
        SportsRuntimeV2.init(this)
        DefaultSportsProviders.install()
        SportsExpansionProviders.BallDontLie.let { SportsProviderEngine.register(it) }
        SportsExpansionProviders.PwhlLeagueStat.let { SportsProviderEngine.register(it) }
        SportsExpansionProviders.SportsDataverse.let { SportsProviderEngine.register(it) }
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components { add(SvgDecoder.Factory()) }
        .crossfade(false)
        .build()
}
