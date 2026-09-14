package com.usportz.app

/** Lightweight in-process provider health state. Failures cool down quickly; successful playback restores trust. */
object ProviderHealth {
    data class State(val failures: Int, val lastFailureAt: Long, val lastSuccessAt: Long)

    private const val FAILURE_COOLDOWN_MS = 30_000L
    private const val OPEN_AFTER = 3
    private val states = LinkedHashMap<String, State>()

    @Synchronized
    fun state(provider: String): State = states[normalize(provider)] ?: State(0, 0L, 0L)

    @Synchronized
    fun isOpen(provider: String, now: Long = System.currentTimeMillis()): Boolean {
        val s = state(provider)
        return s.failures >= OPEN_AFTER && now - s.lastFailureAt < FAILURE_COOLDOWN_MS
    }

    @Synchronized
    fun penalty(provider: String, now: Long = System.currentTimeMillis()): Int {
        val s = state(provider)
        if (s.failures == 0) return 0
        if (now - s.lastFailureAt >= FAILURE_COOLDOWN_MS) return (s.failures * 2).coerceAtMost(12)
        return (s.failures * 7).coerceAtMost(30)
    }

    @Synchronized
    fun recordFailure(provider: String, now: Long = System.currentTimeMillis()) {
        val key = normalize(provider)
        val old = states[key] ?: State(0, 0L, 0L)
        states[key] = old.copy(failures = (old.failures + 1).coerceAtMost(8), lastFailureAt = now)
    }

    @Synchronized
    fun recordSuccess(provider: String, now: Long = System.currentTimeMillis()) {
        states[normalize(provider)] = State(0, 0L, now)
    }

    @Synchronized
    fun snapshot(): Map<String, State> = states.toMap()

    private fun normalize(value: String): String = value.trim().lowercase().ifBlank { "unknown" }
}
