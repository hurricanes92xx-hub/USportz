package com.usportz.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Small SQLite catalog used as the fast startup source of truth for sports channels.
 * Ingest writes a new generation and activates it only after the generation is complete.
 */
class SportsChannelDiskStore(context: Context) : SQLiteOpenHelper(context, "usportz_channels.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE channels (generation INTEGER NOT NULL, source_key TEXT NOT NULL, id TEXT NOT NULL, name TEXT NOT NULL, grp TEXT NOT NULL, logo TEXT, url TEXT NOT NULL, is_sports INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(generation, id))")
        db.execSQL("CREATE INDEX idx_channels_active_sports ON channels(source_key, generation, is_sports, name)")
        db.execSQL("CREATE TABLE meta (source_key TEXT PRIMARY KEY, active_generation INTEGER NOT NULL, saved_at INTEGER NOT NULL, channel_count INTEGER NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun activeSnapshot(sourceKey: String): List<SportsChannel> {
        val db = readableDatabase
        val out = ArrayList<SportsChannel>()
        db.rawQuery("SELECT c.id,c.name,c.grp,c.logo,c.url FROM channels c JOIN meta m ON m.source_key=c.source_key AND m.active_generation=c.generation WHERE c.source_key=? AND c.is_sports=1 ORDER BY c.name COLLATE NOCASE", arrayOf(sourceKey)).use { cursor ->
            while (cursor.moveToNext()) {
                out += SportsChannel(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getString(3)?.ifBlank { null }, cursor.getString(4))
            }
        }
        return out
    }

    fun activeCount(sourceKey: String): Int = readableDatabase.rawQuery("SELECT channel_count FROM meta WHERE source_key=?", arrayOf(sourceKey)).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun beginGeneration(sourceKey: String): Long {
        val db = writableDatabase
        val generation = System.currentTimeMillis()
        db.beginTransaction()
        try {
            db.delete("channels", "source_key=? AND generation<?", arrayOf(sourceKey, generation - 1))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return generation
    }

    fun insertBatch(sourceKey: String, generation: Long, batch: List<SportsChannel>) {
        if (batch.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            batch.forEach { channel ->
                val values = ContentValues().apply {
                    put("generation", generation)
                    put("source_key", sourceKey)
                    put("id", channel.id)
                    put("name", channel.name)
                    put("grp", channel.group)
                    put("logo", channel.logo)
                    put("url", channel.url)
                    put("is_sports", if (SportsNetworkCatalog.find(channel) != null) 1 else 0)
                }
                db.insertWithOnConflict("channels", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun activate(sourceKey: String, generation: Long, count: Int) {
        writableDatabase.insertWithOnConflict("meta", null, ContentValues().apply {
            put("source_key", sourceKey)
            put("active_generation", generation)
            put("saved_at", System.currentTimeMillis())
            put("channel_count", count)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun clearSource(sourceKey: String) {
        writableDatabase.delete("channels", "source_key=?", arrayOf(sourceKey))
        writableDatabase.delete("meta", "source_key=?", arrayOf(sourceKey))
    }
}
