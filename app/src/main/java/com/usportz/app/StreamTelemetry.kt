package com.usportz.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.Locale
import kotlin.math.roundToInt

/** Measurements used to rank real playback sources instead of relying only on URL existence. */
data class StreamMeasurement(
    val channelKey: String,
    val url: String,
    val httpCode: Int? = null,
    val ttfbMs: Long? = null,
    val startupMs: Long? = null,
    val durationMs: Long? = null,
    val bitrateKbps: Int? = null,
    val width: Int? = null,
    val height: Int? = null,
    val fps: Float? = null,
    val videoCodec: String = "",
    val audioCodec: String = "",
    val success: Boolean,
    val failure: PlaybackFailure? = null,
    val timestampMs: Long = System.currentTimeMillis()
)

data class StreamHealth(
    val score: Int,
    val samples: Int,
    val successRate: Float,
    val averageTtfbMs: Long,
    val averageStartupMs: Long,
    val retiredUntilMs: Long,
    val lastHttpCode: Int?
) { val retired: Boolean get() = retiredUntilMs > System.currentTimeMillis() }

class StreamTelemetryStore(context: Context) {
    private val helper = Helper(context.applicationContext)

    fun record(m: StreamMeasurement) {
        val db = helper.writableDatabase
        db.execSQL("INSERT INTO samples(channel_key,url,http_code,ttfb_ms,startup_ms,duration_ms,bitrate_kbps,width,height,fps,video_codec,audio_codec,success,failure,at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", arrayOf(m.channelKey,m.url,m.httpCode,m.ttfbMs,m.startupMs,m.durationMs,m.bitrateKbps,m.width,m.height,m.fps,m.videoCodec,m.audioCodec,if(m.success)1 else 0,m.failure?.name,m.timestampMs))
        db.execSQL("DELETE FROM samples WHERE channel_key=? AND at < ?", arrayOf(m.channelKey,System.currentTimeMillis()-7L*24*60*60*1000))
        recompute(db,m.channelKey)
    }

    fun health(channelKey: String): StreamHealth? = helper.writableDatabase.rawQuery("SELECT score,samples,success_rate,avg_ttfb,avg_startup,retired_until,last_http FROM health WHERE channel_key=?", arrayOf(channelKey)).use { c -> if(!c.moveToFirst()) null else StreamHealth(c.getInt(0),c.getInt(1),c.getFloat(2),c.getLong(3),c.getLong(4),c.getLong(5),if(c.isNull(6)) null else c.getInt(6)) }

    fun close() = helper.close()

    private fun recompute(db: SQLiteDatabase, key: String) {
        db.rawQuery("SELECT COUNT(*),SUM(success),AVG(ttfb_ms),AVG(startup_ms),MAX(at),SUM(CASE WHEN failure='FORMAT' OR failure='DECODER' THEN 2 ELSE 1 END) FROM samples WHERE channel_key=?", arrayOf(key)).use { c ->
            if(!c.moveToFirst()) return
            val n=c.getInt(0); val successes=c.getInt(1); val avgTtfb=if(c.isNull(2))0 else c.getLong(2); val avgStartup=if(c.isNull(3))0 else c.getLong(3); val weightedFailures=if(c.isNull(5))0 else c.getInt(5)
            val rate=if(n==0)0f else successes.toFloat()/n
            var score=(50 + (rate*50) - (avgTtfb/250.0) - (avgStartup/300.0) - weightedFailures*2).roundToInt().coerceIn(0,100)
            if(successes==0 && n>=3) score=score.coerceAtMost(25)
            val retire = if(n>=5 && rate < .2f) System.currentTimeMillis()+6*60*60*1000L else 0L
            db.execSQL("INSERT OR REPLACE INTO health(channel_key,score,samples,success_rate,avg_ttfb,avg_startup,retired_until,last_http) SELECT ?,?,?,?,?,?, ?,http_code FROM samples WHERE channel_key=? ORDER BY at DESC LIMIT 1", arrayOf(key,score,n,rate,avgTtfb,avgStartup,retire,key))
        }
    }

    private class Helper(context: Context) : SQLiteOpenHelper(context,"usportz_stream_telemetry.db",null,1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE samples(id INTEGER PRIMARY KEY AUTOINCREMENT,channel_key TEXT,url TEXT,http_code INTEGER,ttfb_ms INTEGER,startup_ms INTEGER,duration_ms INTEGER,bitrate_kbps INTEGER,width INTEGER,height INTEGER,fps REAL,video_codec TEXT,audio_codec TEXT,success INTEGER,failure TEXT,at INTEGER)")
            db.execSQL("CREATE INDEX idx_samples_key_time ON samples(channel_key,at DESC)")
            db.execSQL("CREATE TABLE health(channel_key TEXT PRIMARY KEY,score INTEGER,samples INTEGER,success_rate REAL,avg_ttfb INTEGER,avg_startup INTEGER,retired_until INTEGER,last_http INTEGER)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    }
}

/** Converts measured source health into a stable ranking bonus. */
object StreamHealthRanker {
    fun bonus(health: StreamHealth?): Int = when {
        health == null -> 0
        health.retired -> -1000
        else -> (health.score - 50).coerceIn(-50,50)
    }
    fun shouldTry(health: StreamHealth?): Boolean = health == null || !health.retired
}
