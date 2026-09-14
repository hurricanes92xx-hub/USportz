package com.usportz.app

/** Pure decision engine used by the player to choose the next bounded stream attempt. */
object PlaybackAttemptEngine {
    data class Attempt(val candidate: StreamCandidate, val url: String, val attempt: Int)

    fun plan(event: SportsEvent, matches: List<GameSourceMatcher.Match>): List<Attempt> {
        val ranked = IntelligentPlayback.rank(event, matches)
        val out = ArrayList<Attempt>(IntelligentPlayback.MAX_ATTEMPTS)
        ranked.forEachIndexed { index, candidate ->
            PlaybackRecovery.candidates(candidate.channel).firstOrNull()?.let { out += Attempt(candidate, it, index + 1) }
        }
        return out
    }

    fun classifyFailure(message: String?, httpCode: Int? = null): PlaybackFailure =
        PlaybackFailureClassifier.classify(message, httpCode)
}
