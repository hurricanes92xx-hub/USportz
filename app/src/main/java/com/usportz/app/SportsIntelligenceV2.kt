package com.usportz.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Sport-specific live state. The generic SportsEvent remains backward compatible. */
sealed class SportLiveState {
    data class Football(val period: Int = 0, val clock: String = "", val down: Int? = null, val distance: Int? = null, val possession: String = "") : SportLiveState()
    data class Basketball(val period: Int = 0, val clock: String = "", val possession: String = "") : SportLiveState()
    data class Baseball(val inning: Int = 0, val half: String = "", val outs: Int = 0, val balls: Int = 0, val strikes: Int = 0) : SportLiveState()
    data class Hockey(val period: Int = 0, val clock: String = "", val shotsHome: Int? = null, val shotsAway: Int? = null) : SportLiveState()
    data class Soccer(val minute: Int? = null, val half: Int = 0, val cards: Int = 0) : SportLiveState()
    data class Tennis(val set: Int = 0, val game: String = "", val point: String = "", val server: String = "") : SportLiveState()
    data class Golf(val hole: Int? = null, val position: Int? = null, val scoreToPar: Int? = null) : SportLiveState()
    data class Motorsport(val lap: Int? = null, val totalLaps: Int? = null, val position: Int? = null, val sector: Int? = null) : SportLiveState()
    data class Cricket(val innings: Int = 0, val overs: String = "", val wickets: Int = 0, val runRate: Double? = null) : SportLiveState()
    data class Generic(val period: Int = 0, val clock: String = "") : SportLiveState()
}

data class SportsEventSnapshot(
    val eventId: String,
    val sport: String,
    val state: String,
    val homeScore: Int? = null,
    val awayScore: Int? = null,
    val detail: String = "",
    val timestampMs: Long = System.currentTimeMillis()
)

enum class SportsTransition { STARTED, SCORE_CHANGED, PERIOD_CHANGED, HALFTIME, INTERMISSION, OVERTIME, FINAL, LEAD_CHANGED }

data class SportsTransitionEvent(val eventId: String, val transition: SportsTransition, val previous: SportsEventSnapshot?, val current: SportsEventSnapshot)

/** Pure state-transition engine; callers decide whether/how to notify the user. */
object SportsStateEngine {
    private val previous = ConcurrentHashMap<String, SportsEventSnapshot>()

    fun update(current: SportsEventSnapshot): List<SportsTransitionEvent> {
        val old = previous.put(current.eventId, current) ?: return if (current.state == "in") listOf(SportsTransitionEvent(current.eventId, SportsTransition.STARTED, null, current)) else emptyList()
        if (old.timestampMs == current.timestampMs && old.state == current.state && old.homeScore == current.homeScore && old.awayScore == current.awayScore && old.detail == current.detail) return emptyList()
        val result = ArrayList<SportsTransitionEvent>(3)
        if (old.homeScore != current.homeScore || old.awayScore != current.awayScore) result += SportsTransitionEvent(current.eventId, SportsTransition.SCORE_CHANGED, old, current)
        if (old.state != current.state) {
            when {
                current.state == "post" -> result += SportsTransitionEvent(current.eventId, SportsTransition.FINAL, old, current)
                current.state == "in" && old.state == "pre" -> result += SportsTransitionEvent(current.eventId, SportsTransition.STARTED, old, current)
            }
        }
        if (period(old.detail) != period(current.detail)) result += SportsTransitionEvent(current.eventId, SportsTransition.PERIOD_CHANGED, old, current)
        val oldLead = lead(old.homeScore, old.awayScore); val newLead = lead(current.homeScore, current.awayScore)
        if (oldLead != newLead) result += SportsTransitionEvent(current.eventId, SportsTransition.LEAD_CHANGED, old, current)
        return result
    }

    fun clear(eventId: String? = null) { if (eventId == null) previous.clear() else previous.remove(eventId) }
    private fun period(detail: String): String = Regex("(?i)(Q[1-4]|OT|[1-3]P|HALFTIME|INTERMISSION|SET [0-9]+)").find(detail)?.value?.uppercase(Locale.US) ?: ""
    private fun lead(home: Int?, away: Int?): Int = when { home == null || away == null -> 0; home > away -> 1; home < away -> -1; else -> 0 }
}

/** Persistent Event DNA: remembers broadcast/source relationships across restarts. */
class EventDnaStore(context: Context) {
    private val helper = Helper(context.applicationContext)
    fun observe(event: SportsEvent, channel: SportsChannel? = null) {
        val key = canonical(event)
        helper.writableDatabase.execSQL("INSERT OR IGNORE INTO events(event_key,sport,league,name,teams,last_seen) VALUES(?,?,?,?,?,?)", arrayOf(key,event.sport,event.league,event.name,event.competitors.joinToString("|"),System.currentTimeMillis()))
        if (channel != null) helper.writableDatabase.execSQL("INSERT OR REPLACE INTO event_sources(event_key,channel_key,last_seen) VALUES(?,?,?)", arrayOf(key, channel.id.ifBlank { channel.url }, System.currentTimeMillis()))
    }
    fun recordResult(event: SportsEvent, channel: SportsChannel, success: Boolean) {
        observe(event, channel)
        val key = canonical(event); val now = System.currentTimeMillis()
        helper.writableDatabase.execSQL("UPDATE event_sources SET successes=successes+?, failures=failures+?, last_seen=? WHERE event_key=? AND channel_key=?", arrayOf(if(success)1 else 0, if(success)0 else 1, now, key, channel.id.ifBlank { channel.url }))
    }
    fun preferredSources(event: SportsEvent, limit: Int = 8): List<String> = helper.writableDatabase.rawQuery("SELECT channel_key FROM event_sources WHERE event_key=? ORDER BY (successes*3-failures) DESC,last_seen DESC LIMIT ?", arrayOf(canonical(event),limit.toString())).use { c -> buildList { while(c.moveToNext()) add(c.getString(0)) } }
    fun close() = helper.close()
    companion object { fun canonical(event: SportsEvent): String = buildString { append(norm(event.sport)); append('|'); append(norm(event.league)); append('|'); append(event.competitors.map(::norm).sorted().joinToString("|")); append('|'); append(event.startTime.take(16)); if(event.competitors.isEmpty()) { append('|'); append(norm(event.name)) } }
        private fun norm(v: String): String = v.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), " ").trim().replace(Regex("\\s+"), "-") }
    private class Helper(context: Context) : SQLiteOpenHelper(context,"usportz_event_dna.db",null,1) {
        override fun onCreate(db: SQLiteDatabase) { db.execSQL("CREATE TABLE events(event_key TEXT PRIMARY KEY,sport TEXT,league TEXT,name TEXT,teams TEXT,last_seen INTEGER)"); db.execSQL("CREATE TABLE event_sources(event_key TEXT,channel_key TEXT,successes INTEGER NOT NULL DEFAULT 0,failures INTEGER NOT NULL DEFAULT 0,last_seen INTEGER,PRIMARY KEY(event_key,channel_key))"); db.execSQL("CREATE INDEX idx_event_sources_score ON event_sources(event_key,successes,failures)") }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    }
}

/** Spoiler-aware timing helper. It intentionally never guesses a delay unless measured. */
object SportsSpoilerGuard {
    fun shouldDelay(lastKnownStreamDelayMs: Long, spoilerFree: Boolean): Boolean = spoilerFree && lastKnownStreamDelayMs > 0
    fun releaseAt(eventTimestampMs: Long, streamDelayMs: Long): Long = eventTimestampMs + streamDelayMs.coerceIn(0, 10 * 60 * 1000L)
}
