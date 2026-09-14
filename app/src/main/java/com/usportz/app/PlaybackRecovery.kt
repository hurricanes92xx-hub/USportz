package com.usportz.app

import java.net.URI

/** Builds a small, deterministic retry set without exposing credentials or proxying streams. */
object PlaybackRecovery {
    fun candidates(channel: SportsChannel): List<String> {
        val original = channel.url.trim()
        if (original.isBlank()) return emptyList()
        val out = LinkedHashSet<String>()
        out += original
        val uri = runCatching { URI(original) }.getOrNull()
        if (uri != null) {
            val scheme = uri.scheme?.lowercase()
            val alternate = when (scheme) {
                "https" -> original.replaceFirst("https://", "http://", true)
                "http" -> original.replaceFirst("http://", "https://", true)
                else -> null
            }
            if (!alternate.isNullOrBlank()) out += alternate
            val path = uri.path.orEmpty()
            if (path.endsWith(".m3u8", true)) out += original.replace(Regex("\\.m3u8$", RegexOption.IGNORE_CASE), ".ts")
            if (path.endsWith(".ts", true)) out += original.replace(Regex("\\.ts$", RegexOption.IGNORE_CASE), ".m3u8")
        }
        return out.take(3)
    }
}
