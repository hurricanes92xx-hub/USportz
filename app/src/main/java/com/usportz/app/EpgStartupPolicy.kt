package com.usportz.app

/** EPG enrichment must not block the initial channel catalogue. */
object EpgStartupPolicy {
    const val INITIAL_VISIBLE_CHANNELS = 24
    const val MAX_PRIORITY_CHANNELS = 96

    fun canPublishChannels(channelCount: Int): Boolean = channelCount > 0
}
