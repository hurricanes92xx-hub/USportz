package com.usportz.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class CatalogRefreshProgress(val imported: Int, val expected: Int, val generation: Long, val updatedAt: Long)

/** Disk-backed provider catalogue. Complete generations are immutable until an atomic swap. */
class SportsChannelDiskStore(context: Context) : SQLiteOpenHelper(context.applicationContext, "usportz_channels.db", null, 6) {
    private val appContext = context.applicationContext

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        if (!db.isReadOnly) runCatching { db.enableWriteAheadLogging() }
        runCatching { db.execSQL("PRAGMA synchronous=NORMAL") }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE channels (generation INTEGER NOT NULL, source_key TEXT NOT NULL, id TEXT NOT NULL, name TEXT NOT NULL, grp TEXT NOT NULL, logo TEXT, url TEXT NOT NULL, tvg_name TEXT NOT NULL DEFAULT '', tvg_id TEXT NOT NULL DEFAULT '', category TEXT NOT NULL DEFAULT '', provider TEXT NOT NULL DEFAULT '', is_sports INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(generation, id))")
        db.execSQL("CREATE INDEX idx_channels_active_sports ON channels(source_key, generation, is_sports, name)")
        db.execSQL("CREATE INDEX idx_channels_active_name ON channels(source_key, generation, name COLLATE NOCASE)")
        db.execSQL("CREATE TABLE meta (source_key TEXT PRIMARY KEY, active_generation INTEGER NOT NULL, saved_at INTEGER NOT NULL, channel_count INTEGER NOT NULL, complete INTEGER NOT NULL DEFAULT 1)")
        db.execSQL("CREATE TABLE refresh_state (source_key TEXT PRIMARY KEY, generation INTEGER NOT NULL, imported_count INTEGER NOT NULL, expected_count INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE channels ADD COLUMN tvg_name TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE channels ADD COLUMN tvg_id TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE channels ADD COLUMN category TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE channels ADD COLUMN provider TEXT NOT NULL DEFAULT ''")
        }
        if (oldVersion < 4) db.execSQL("UPDATE channels SET is_sports=1 WHERE lower(name) LIKE '%espn%' OR lower(name) LIKE '%fox sports%' OR lower(name) LIKE '%fs1%' OR lower(name) LIKE '%fs2%' OR lower(name) LIKE '%cbs sports%' OR lower(name) LIKE '%cbssn%' OR lower(name) LIKE '%nbc sports%' OR lower(name) LIKE '%sportsnet%' OR lower(name) LIKE '%tsn%' OR lower(name) LIKE '%rds%' OR lower(name) LIKE '%tva sports%' OR lower(name) LIKE '%mlb network%' OR lower(name) LIKE '%nfl network%' OR lower(name) LIKE '%nba tv%' OR lower(name) LIKE '%nhl network%' OR lower(name) LIKE '%golf channel%' OR lower(name) LIKE '%tennis channel%' OR lower(name) LIKE '%bally sports%' OR lower(name) LIKE '%yes network%' OR lower(name) LIKE '%msg network%' OR lower(name) LIKE '%sny%' OR lower(name) LIKE '%root sports%' OR lower(name) LIKE '%fanduel sports%' OR lower(name) LIKE '%acc network%' OR lower(name) LIKE '%accn%' OR lower(name) LIKE '%sec network%' OR lower(name) LIKE '%secn%' OR lower(name) LIKE '%big ten network%' OR lower(name) LIKE '%btn%' OR lower(name) LIKE '%sports%' OR lower(grp) LIKE '%sport%' OR lower(category) LIKE '%sport%' OR lower(grp) LIKE '%ppv%' OR lower(category) LIKE '%ppv%'")
        if (oldVersion < 5) db.execSQL("ALTER TABLE meta ADD COLUMN complete INTEGER NOT NULL DEFAULT 1")
        if (oldVersion < 6) db.execSQL("CREATE TABLE IF NOT EXISTS refresh_state (source_key TEXT PRIMARY KEY, generation INTEGER NOT NULL, imported_count INTEGER NOT NULL, expected_count INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL)")
    }

    /** Sports projection used by the matcher/UI. Falls back to the isolated cold-start preview only when no full snapshot exists. */
    fun activeSnapshot(sourceKey: String): List<SportsChannel> {
        val out = ArrayList<SportsChannel>()
        readableDatabase.rawQuery("SELECT c.id,c.name,c.grp,c.logo,c.url,c.tvg_name,c.tvg_id,c.category,c.provider FROM channels c JOIN meta m ON m.source_key=c.source_key AND m.active_generation=c.generation AND m.complete=1 WHERE c.source_key=? AND c.is_sports=1 ORDER BY c.name COLLATE NOCASE", arrayOf(sourceKey)).use { cursor ->
            while (cursor.moveToNext()) out += SportsChannel(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getString(3)?.ifBlank { null }, cursor.getString(4), cursor.getString(5), cursor.getString(6), cursor.getString(7), cursor.getString(8))
        }
        if (out.isEmpty() && activeCount(sourceKey) == 0) {
            return SportsStartupPreviewStore(appContext).read(sourceKey, 240)
        }
        return out
    }

    /** Full provider count, including non-sports IPTV channels. */
    fun activeCount(sourceKey: String): Int = readableDatabase.rawQuery("SELECT channel_count FROM meta WHERE source_key=? AND complete=1", arrayOf(sourceKey)).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    fun activeGeneration(sourceKey: String): Long = readableDatabase.rawQuery("SELECT active_generation FROM meta WHERE source_key=? AND complete=1", arrayOf(sourceKey)).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    /** Starts an isolated generation. It never touches the active complete generation. */
    fun beginGeneration(sourceKey: String): Long {
        val generation = System.currentTimeMillis()
        val expected = activeCount(sourceKey)
        writableDatabase.execSQL("INSERT OR REPLACE INTO refresh_state(source_key,generation,imported_count,expected_count,updated_at) VALUES(?,?,?,?,?)", arrayOf(sourceKey, generation, 0, expected, System.currentTimeMillis()))
        return generation
    }

    fun refreshProgress(sourceKey: String): CatalogRefreshProgress? = readableDatabase.rawQuery("SELECT imported_count,expected_count,generation,updated_at FROM refresh_state WHERE source_key=?", arrayOf(sourceKey)).use { if (it.moveToFirst()) CatalogRefreshProgress(it.getInt(0), it.getInt(1), it.getLong(2), it.getLong(3)) else null }
    fun updateRefreshProgress(sourceKey: String, generation: Long, imported: Int) { writableDatabase.execSQL("UPDATE refresh_state SET imported_count=?,updated_at=? WHERE source_key=? AND generation=?", arrayOf(imported, System.currentTimeMillis(), sourceKey, generation)) }

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

        // Critical large-playlist optimization: the streaming importer is already reading the
        // provider response. Publish the first sports rows from that same pass instead of
        // making the user wait for all 57k+ channels or starting a second provider request.
        runCatching {
            val preview = SportsStartupPreviewStore(appContext)
            val existing = preview.read(sourceKey, 240).size
            if (existing < 240) {
                val candidates = batch.filter { SportsNetworkCatalog.isSportsChannel(it) }.take(240 - existing)
                if (candidates.isNotEmpty()) preview.append(sourceKey, existing, candidates)
            }
        }
    }

    /** Atomically publishes only a fully parsed generation. The previous complete generation survives any failed refresh. */
    fun activate(sourceKey: String, generation: Long, count: Int) {
        val db = writableDatabase
        var activated = false
        db.beginTransactionNonExclusive()
        try {
            val stagedCount = db.rawQuery("SELECT COUNT(*) FROM channels WHERE source_key=? AND generation=?", arrayOf(sourceKey, generation.toString())).use { if (it.moveToFirst()) it.getInt(0) else 0 }
            if (count > 0 && stagedCount > 0) {
                db.execSQL("INSERT OR REPLACE INTO meta(source_key,active_generation,saved_at,channel_count,complete) VALUES(?,?,?,?,1)", arrayOf(sourceKey, generation, System.currentTimeMillis(), count))
                db.execSQL("DELETE FROM refresh_state WHERE source_key=? AND generation=?", arrayOf(sourceKey, generation.toString()))
                db.delete("channels", "source_key=? AND generation<>?", arrayOf(sourceKey, generation.toString()))
                db.setTransactionSuccessful()
                activated = true
            }
        } finally {
            db.endTransaction()
        }
        if (activated) runCatching { SportsStartupPreviewStore(appContext).clear(sourceKey) }
    }

    fun clearSource(sourceKey: String) {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.delete("channels", "source_key=?", arrayOf(sourceKey))
            writableDatabase.delete("meta", "source_key=?", arrayOf(sourceKey))
            writableDatabase.delete("refresh_state", "source_key=?", arrayOf(sourceKey))
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
        runCatching { SportsStartupPreviewStore(appContext).clear(sourceKey) }
    }
}
