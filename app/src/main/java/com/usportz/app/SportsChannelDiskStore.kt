package com.usportz.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Small SQLite catalog used as the fast startup source of truth for sports channels. */
class SportsChannelDiskStore(context: Context) : SQLiteOpenHelper(context, "usportz_channels.db", null, 5) {
    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        if (!db.isReadOnly) runCatching { db.enableWriteAheadLogging() }
        runCatching { db.execSQL("PRAGMA synchronous=NORMAL") }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE channels (generation INTEGER NOT NULL, source_key TEXT NOT NULL, id TEXT NOT NULL, name TEXT NOT NULL, grp TEXT NOT NULL, logo TEXT, url TEXT NOT NULL, tvg_name TEXT NOT NULL DEFAULT '', tvg_id TEXT NOT NULL DEFAULT '', category TEXT NOT NULL DEFAULT '', provider TEXT NOT NULL DEFAULT '', is_sports INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(generation, id))")
        db.execSQL("CREATE INDEX idx_channels_active_sports ON channels(source_key, generation, is_sports, name)")
        db.execSQL("CREATE TABLE meta (source_key TEXT PRIMARY KEY, active_generation INTEGER NOT NULL, saved_at INTEGER NOT NULL, channel_count INTEGER NOT NULL, complete INTEGER NOT NULL DEFAULT 1)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE channels ADD COLUMN tvg_name TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE channels ADD COLUMN tvg_id TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE channels ADD COLUMN category TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE channels ADD COLUMN provider TEXT NOT NULL DEFAULT ''")
        }
        if (oldVersion < 4) {
            db.execSQL("UPDATE channels SET is_sports=1 WHERE lower(name) LIKE '%espn%' OR lower(name) LIKE '%fox sports%' OR lower(name) LIKE '%fs1%' OR lower(name) LIKE '%fs2%' OR lower(name) LIKE '%cbs sports%' OR lower(name) LIKE '%cbssn%' OR lower(name) LIKE '%nbc sports%' OR lower(name) LIKE '%sportsnet%' OR lower(name) LIKE '%tsn%' OR lower(name) LIKE '%rds%' OR lower(name) LIKE '%tva sports%' OR lower(name) LIKE '%mlb network%' OR lower(name) LIKE '%nfl network%' OR lower(name) LIKE '%nba tv%' OR lower(name) LIKE '%nhl network%' OR lower(name) LIKE '%golf channel%' OR lower(name) LIKE '%tennis channel%' OR lower(name) LIKE '%bally sports%' OR lower(name) LIKE '%yes network%' OR lower(name) LIKE '%msg network%' OR lower(name) LIKE '%sny%' OR lower(name) LIKE '%root sports%' OR lower(name) LIKE '%fanduel sports%' OR lower(name) LIKE '%acc network%' OR lower(name) LIKE '%accn%' OR lower(name) LIKE '%sec network%' OR lower(name) LIKE '%secn%' OR lower(name) LIKE '%big ten network%' OR lower(name) LIKE '%btn%' OR lower(name) LIKE '%sports%' OR lower(grp) LIKE '%sport%' OR lower(category) LIKE '%sport%' OR lower(grp) LIKE '%ppv%' OR lower(category) LIKE '%ppv%'")
        }
        if (oldVersion < 5) {
            db.execSQL("ALTER TABLE meta ADD COLUMN complete INTEGER NOT NULL DEFAULT 1")
        }
    }

    fun activeSnapshot(sourceKey: String): List<SportsChannel> {
        val out = ArrayList<SportsChannel>()
        readableDatabase.rawQuery("SELECT c.id,c.name,c.grp,c.logo,c.url,c.tvg_name,c.tvg_id,c.category,c.provider FROM channels c JOIN meta m ON m.source_key=c.source_key AND m.active_generation=c.generation WHERE c.source_key=? AND c.is_sports=1 ORDER BY c.name COLLATE NOCASE", arrayOf(sourceKey)).use { cursor ->
            while (cursor.moveToNext()) out += SportsChannel(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getString(3)?.ifBlank { null }, cursor.getString(4), cursor.getString(5), cursor.getString(6), cursor.getString(7), cursor.getString(8))
        }
        return out
    }

    /** Returns a positive count only for a completed generation. */
    fun activeCount(sourceKey: String): Int = readableDatabase.rawQuery("SELECT channel_count FROM meta WHERE source_key=? AND complete=1", arrayOf(sourceKey)).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    fun beginGeneration(sourceKey: String): Long = System.currentTimeMillis()

    fun insertBatch(sourceKey: String, generation: Long, batch: List<SportsChannel>) {
        if (batch.isEmpty()) return
        val db = writableDatabase
        db.beginTransactionNonExclusive()
        val statement = db.compileStatement("INSERT OR REPLACE INTO channels(generation,source_key,id,name,grp,logo,url,tvg_name,tvg_id,category,provider,is_sports) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)")
        try {
            batch.forEach { channel ->
                statement.clearBindings()
                statement.bindLong(1, generation)
                statement.bindString(2, sourceKey)
                statement.bindString(3, channel.id)
                statement.bindString(4, channel.name)
                statement.bindString(5, channel.group)
                if (channel.logo.isNullOrBlank()) statement.bindNull(6) else statement.bindString(6, channel.logo)
                statement.bindString(7, channel.url)
                statement.bindString(8, channel.tvgName)
                statement.bindString(9, channel.tvgId)
                statement.bindString(10, channel.category)
                statement.bindString(11, channel.provider)
                statement.bindLong(12, if (SportsNetworkCatalog.isSportsChannel(channel)) 1L else 0L)
                statement.executeInsert()
            }
            db.setTransactionSuccessful()
        } finally {
            statement.close()
            db.endTransaction()
        }
    }

    /** First activation of a generation is a preview; activating the same generation again completes it. */
    fun activate(sourceKey: String, generation: Long, count: Int) {
        val db = writableDatabase
        db.beginTransactionNonExclusive()
        try {
            val previousGeneration = db.rawQuery("SELECT active_generation FROM meta WHERE source_key=?", arrayOf(sourceKey)).use { if (it.moveToFirst()) it.getLong(0) else Long.MIN_VALUE }
            val complete = if (previousGeneration == generation) 1 else 0
            db.execSQL("INSERT OR REPLACE INTO meta(source_key,active_generation,saved_at,channel_count,complete) VALUES(?,?,?,?,?)", arrayOf(sourceKey, generation, System.currentTimeMillis(), count, complete))
            db.delete("channels", "source_key=? AND generation<>?", arrayOf(sourceKey, generation.toString()))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun clearSource(sourceKey: String) {
        writableDatabase.delete("channels", "source_key=?", arrayOf(sourceKey))
        writableDatabase.delete("meta", "source_key=?", arrayOf(sourceKey))
    }
}
