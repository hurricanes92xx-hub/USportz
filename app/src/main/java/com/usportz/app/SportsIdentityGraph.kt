package com.usportz.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.security.MessageDigest
import java.util.Locale

/** Wave 1: one stable identity for a channel across Xtream/M3U/EPG/provider metadata. */
object CanonicalChannelIdentity {
    fun key(channel: SportsChannel): String = key(channel.tvgId, channel.tvgName, channel.name, channel.group, channel.provider)

    fun key(tvgId: String?, tvgName: String?, name: String?, group: String?, provider: String?): String {
        val stable = listOf(tvgId, tvgName, name, group, provider).map { normalize(it) }.filter { it.isNotBlank() }
        return sha256(stable.joinToString("|"))
    }

    fun callsign(value: String): String = normalize(value).replace(" ", "").take(12)

    private fun normalize(v: String?): String = v.orEmpty().lowercase(Locale.US)
        .replace("&", " and ")
        .replace(Regex("\\b(channel|network|hd|fhd|uhd|4k|east|west|central)\\b"), " ")
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()

    private fun sha256(v: String): String = MessageDigest.getInstance("SHA-256").digest(v.toByteArray()).joinToString("") { "%02x".format(it) }
}

data class EventBroadcastEdge(val eventId: String, val channelId: String, val network: String, val score: Int, val reason: String)
data class EventStreamEdge(val eventId: String, val channelId: String, val url: String, val score: Int, val variant: String)

/** Wave 1: persistent EPG + event -> broadcast -> stream graph. */
class SportsIdentityGraphStore(context: Context) : SQLiteOpenHelper(context, "usportz_sports_graph.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE epg_channels (id TEXT PRIMARY KEY, canonical_id TEXT NOT NULL, name TEXT NOT NULL, callsign TEXT, tvg_id TEXT, tvg_name TEXT, network TEXT, last_seen INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE epg_programs (id TEXT PRIMARY KEY, channel_id TEXT NOT NULL, title TEXT NOT NULL, start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL, subtitle TEXT, description TEXT)")
        db.execSQL("CREATE TABLE event_broadcast_edges (event_id TEXT NOT NULL, channel_id TEXT NOT NULL, network TEXT, score INTEGER NOT NULL, reason TEXT, PRIMARY KEY(event_id, channel_id))")
        db.execSQL("CREATE TABLE event_stream_edges (event_id TEXT NOT NULL, channel_id TEXT NOT NULL, url TEXT NOT NULL, score INTEGER NOT NULL, variant TEXT, PRIMARY KEY(event_id, url))")
        db.execSQL("CREATE INDEX idx_epg_programs_channel_time ON epg_programs(channel_id,start_ms,end_ms)")
        db.execSQL("CREATE INDEX idx_broadcast_event ON event_broadcast_edges(event_id,score)")
        db.execSQL("CREATE INDEX idx_stream_event ON event_stream_edges(event_id,score)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun upsertChannel(channel: SportsChannel, network: String = "") {
        writableDatabase.execSQL("INSERT OR REPLACE INTO epg_channels(id,canonical_id,name,callsign,tvg_id,tvg_name,network,last_seen) VALUES(?,?,?,?,?,?,?,?)", arrayOf(
            channel.id, CanonicalChannelIdentity.key(channel), channel.name, CanonicalChannelIdentity.callsign(channel.tvgName.ifBlank { channel.name }), channel.tvgId, channel.tvgName, network, System.currentTimeMillis()))
    }

    fun replacePrograms(programs: List<EpgProgram>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            programs.forEach { p ->
                db.execSQL("INSERT OR REPLACE INTO epg_programs(id,channel_id,title,start_ms,end_ms,subtitle,description) VALUES(?,?,?,?,?,?,?)", arrayOf(p.id, p.channelId, p.title, p.startMs, p.endMs, p.subtitle, p.description))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun connectEvent(event: SportsEvent, channels: List<SportsChannel>, limit: Int = 16): List<EventBroadcastEdge> {
        val ranked = channels.mapNotNull { channel ->
            val score = GameSourceMatcher.rankMatches(event, listOf(channel), 1).firstOrNull()?.score ?: 0
            if (score < 35) null else EventBroadcastEdge(event.id, channel.id, event.broadcast, score, "event/channel identity + broadcast")
        }.sortedByDescending { it.score }.take(limit)
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("event_broadcast_edges", "event_id=?", arrayOf(event.id))
            ranked.forEach { e -> db.execSQL("INSERT OR REPLACE INTO event_broadcast_edges(event_id,channel_id,network,score,reason) VALUES(?,?,?,?,?)", arrayOf(e.eventId,e.channelId,e.network,e.score,e.reason)) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return ranked
    }

    fun connectStreams(event: SportsEvent, channels: List<SportsChannel>, limit: Int = 16): List<EventStreamEdge> =
        connectEvent(event, channels, limit).map { edge ->
            val c = channels.firstOrNull { it.id == edge.channelId }
            EventStreamEdge(event.id, edge.channelId, c?.url.orEmpty(), edge.score, c?.url?.substringAfterLast('.', "") ?: "")
        }.filter { it.url.isNotBlank() }
}
