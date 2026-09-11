package com.usportz.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Small SQLite catalog used as the fast startup source of truth for sports channels. */
class SportsChannelDiskStore(context: Context) : SQLiteOpenHelper(context, "usportz_channels.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE channels (generation INTEGER NOT NULL, source_key TEXT NOT NULL, id TEXT NOT NULL, name TEXT NOT NULL, grp TEXT NOT NULL, logo TEXT, url TEXT NOT NULL, tvg_name TEXT NOT NULL DEFAULT '', tvg_id TEXT NOT NULL DEFAULT '', category TEXT NOT NULL DEFAULT '', provider TEXT NOT NULL DEFAULT '', is_sports INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(generation, id))")
        db.execSQL("CREATE INDEX idx_channels_active_sports ON channels(source_key, generation, is_sports, name)")
        db.execSQL("CREATE TABLE meta (source_key TEXT PRIMARY KEY, active_generation INTEGER NOT NULL, saved_at INTEGER NOT NULL, channel_count INTEGER NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE channels ADD COLUMN tvg_name TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE channels ADD COLUMN tvg_id TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE channels ADD COLUMN category TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE channels ADD COLUMN provider TEXT NOT NULL DEFAULT ''")
        }
    }

    fun activeSnapshot(sourceKey: String): List<SportsChannel> {
        val out = ArrayList<SportsChannel>()
        readableDatabase.rawQuery("SELECT c.id,c.name,c.grp,c.logo,c.url,c.tvg_name,c.tvg_id,c.category,c.provider FROM channels c JOIN meta m ON m.source_key=c.source_key AND m.active_generation=c.generation WHERE c.source_key=? AND c.is_sports=1 ORDER BY c.name COLLATE NOCASE", arrayOf(sourceKey)).use { cursor ->
            while (cursor.moveToNext()) out += SportsChannel(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getString(3)?.ifBlank { null }, cursor.getString(4), cursor.getString(5), cursor.getString(6), cursor.getString(7), cursor.getString(8))
        }
        return out
    }

    fun activeCount(sourceKey: String): Int = readableDatabase.rawQuery("SELECT channel_count FROM meta WHERE source_key=?", arrayOf(sourceKey)).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    fun beginGeneration(sourceKey: String): Long = System.currentTimeMillis()

    fun insertBatch(sourceKey: String, generation: Long, batch: List<SportsChannel>) {
        if (batch.isEmpty()) return
        val db = writableDatabase; db.beginTransactionNonExclusive()
        try {
            batch.forEach { channel ->
                db.insertWithOnConflict("channels", null, ContentValues().apply {
                    put("generation", generation); put("source_key", sourceKey); put("id", channel.id); put("name", channel.name); put("grp", channel.group); put("logo", channel.logo); put("url", channel.url)
                    put("tvg_name", channel.tvgName); put("tvg_id", channel.tvgId); put("category", channel.category); put("provider", channel.provider)
                    put("is_sports", if (SportsNetworkCatalog.find(channel) != null) 1 else 0)
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun activate(sourceKey: String, generation: Long, count: Int) {
        val db = writableDatabase; db.beginTransactionNonExclusive()
        try {
            db.insertWithOnConflict("meta", null, ContentValues().apply { put("source_key", sourceKey); put("active_generation", generation); put("saved_at", System.currentTimeMillis()); put("channel_count", count) }, SQLiteDatabase.CONFLICT_REPLACE)
            db.delete("channels", "source_key=? AND generation<>?", arrayOf(sourceKey, generation.toString())); db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun clearSource(sourceKey: String) { writableDatabase.delete("channels", "source_key=?", arrayOf(sourceKey)); writableDatabase.delete("meta", "source_key=?", arrayOf(sourceKey)) }
}
