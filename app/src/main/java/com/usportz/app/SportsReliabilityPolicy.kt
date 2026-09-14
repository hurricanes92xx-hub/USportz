package com.usportz.app

/** Shared limits for fast, cache-first sports startup. */
object SportsReliabilityPolicy {
    const val EVENT_CANDIDATE_LIMIT = 240
    const val RESOLVER_SOURCE_LIMIT = 8
    const val MAX_RETAINED_EVENTS = 2500
    const val LIVE_REFRESH_INTERVAL_MS = 60_000L
    const val EPG_INITIAL_CHANNEL_LIMIT = 24

    fun shouldPublishChannels(channelCount: Int): Boolean = channelCount > 0
}
