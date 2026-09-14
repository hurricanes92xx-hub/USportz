package com.usportz.app

/** Pure decision engine used by the player to choose the next bounded stream attempt. */
object PlaybackAttemptEngine {
    data class Attempt(val candidate: StreamCandidate, val url: String, val attempt: Int)

    fun plan(event: SportsEvent, matches: List<GameSourceMatcher.Match>): List<Attempt> {
        val ranked = IntelligentPlayback.rank(event, matches)
        val primary = ranked.mapNotNull { candidate ->
            PlaybackRecovery.candidates(candidate.channel).firstOrNull()?.let { candidate to it }
        }
        val alternates = ranked.flatMap { candidate -> PlaybackRecovery.candidates(candidate.channel).drop(1).map { candidate to it } }
        return (primary + alternates).take(IntelligentPlayback.MAX_ATTEMPTS).mapIndexed { index, pair ->
            Attempt(pair.first, pair.second, index + 1)
        }
    }

    fun classifyFailure(message: String?, httpCode: Int? = null): PlaybackFailure =
        PlaybackFailureClassifier.classify(message, httpCode)
}
