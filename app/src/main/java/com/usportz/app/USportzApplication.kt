package com.usportz.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.SvgDecoder

/** Shared app services: image loading, persistent channel identity, and provider registry. */
class USportzApplication : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        ChannelDnaRuntime.init(this)
        DefaultSportsProviders.install()
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components { add(SvgDecoder.Factory()) }
        .crossfade(false)
        .build()
}
