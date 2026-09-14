package com.usportz.app

/** Retry policy for live IPTV: bounded, deterministic, and provider-local. */
object PlaybackRecoveryPolicy {
    const val MAX_ATTEMPTS = 3
    const val RETRY_DELAY_MS = 1200L

    fun candidates(channel: SportsChannel): List<String> = PlaybackRecovery.candidates(channel).take(MAX_ATTEMPTS)
}
