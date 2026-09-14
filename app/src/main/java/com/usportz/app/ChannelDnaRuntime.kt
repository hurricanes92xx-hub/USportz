package com.usportz.app

import android.content.Context

object ChannelDnaRuntime {
    private var vault: ChannelVault? = null
    fun init(context: Context) { if (vault == null) vault = ChannelVault(context.applicationContext) }
    fun observe(channel: SportsChannel) { vault?.observe(channel) }
    fun success(channel: SportsChannel) { vault?.recordSuccess(channel) }
    fun failure(channel: SportsChannel) { vault?.recordFailure(channel) }
    fun bestUrl(channel: SportsChannel): String = vault?.bestUrl(channel) ?: channel.url
    fun score(channel: SportsChannel): Int = vault?.dna(channel)?.score ?: 50
}
