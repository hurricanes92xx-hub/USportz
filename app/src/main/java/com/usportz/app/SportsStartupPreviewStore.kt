package com.usportz.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Tiny cold-start cache for the sports experience. */
class SportsStartupPreviewStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    "usportz_sports_preview.db",
    null,
    1
) {
    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        runCatching { db.enableWriteAheadLogging() }
        runCatching { db.execSQL("PRAGMA synchronous=NORMAL") }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE preview (
                source_key TEXT NOT NULL,
                ord INTEGER NOT NULL,
                id TEXT NOT NULL,
                name TEXT NOT NULL,
                grp TEXT NOT NULL,
                logo TEXT,
                url TEXT NOT NULL,
                tvg_name TEXT NOT NULL DEFAULT '',
                tvg_id TEXT NOT NULL DEFAULT '',
                category TEXT NOT NULL DEFAULT '',
                provider TEXT NOT NULL DEFAULT '',
                PRIMARY KEY(source_key, ord)
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_preview_source_id ON preview(source_key, id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun replaceBatch(sourceKey: String, batch: List<SportsChannel>) {
        if (sourceKey.isBlank() || batch.isEmpty()) return
        replaceAll(sourceKey, batch.take(240))
    }

    fun replaceAll(sourceKey: String, channels: List<SportsChannel>) {
        if (sourceKey.isBlank()) return
        val db = writableDatabase
        db.beginTransactionNonExclusive()
        try {
            db.delete("preview", "source_key=?", arrayOf(sourceKey))
            if (channels.isNotEmpty()) {
                val statement = db.compileStatement(
                    "INSERT INTO preview(source_key,ord,id,name,grp,logo,url,tvg_name,tvg_id,category,provider) VALUES(?,?,?,?,?,?,?,?,?,?,?)"
                )
                try {
                    channels.take(240).forEachIndexed { index, channel ->
                        statement.clearBindings()
                        statement.bindString(1, sourceKey)
                        statement.bindLong(2, index.toLong())
                        statement.bindString(3, channel.id)
                        statement.bindString(4, channel.name)
                        statement.bindString(5, channel.group)
                        if (channel.logo.isNullOrBlank()) statement.bindNull(6) else statement.bindString(6, channel.logo)
                        statement.bindString(7, channel.url)
                        statement.bindString(8, channel.tvgName)
                        statement.bindString(9, channel.tvgId)
                        statement.bindString(10, channel.category)
                        statement.bindString(11, channel.provider)
                        statement.executeInsert()
                    }
                } finally { statement.close() }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** Keep the best 240 startup channels as the provider stream is discovered. */
    fun mergeRanked(sourceKey: String, candidates: List<SportsChannel>, limit: Int = 240) {
        if (sourceKey.isBlank() || candidates.isEmpty()) return
        val current = read(sourceKey, limit)
        val merged = (current + candidates)
            .filter { it.id.isNotBlank() && it.url.isNotBlank() }
            .distinctBy { "${it.id}|${it.url}" }
            .sortedWith(compareByDescending<SportsChannel> { SportsNetworkCatalog.startupPriority(it) }.thenBy { it.name.lowercase() })
            .take(limit.coerceIn(32, 500))
        replaceAll(sourceKey, merged)
    }

    fun append(sourceKey: String, startOrdinal: Int, channels: List<SportsChannel>) {
        if (sourceKey.isBlank() || channels.isEmpty()) return
        val db = writableDatabase
        db.beginTransactionNonExclusive()
        try {
            val statement = db.compileStatement(
                "INSERT OR REPLACE INTO preview(source_key,ord,id,name,grp,logo,url,tvg_name,tvg_id,category,provider) VALUES(?,?,?,?,?,?,?,?,?,?,?)"
            )
            try {
                channels.forEachIndexed { index, channel ->
                    statement.clearBindings()
                    statement.bindString(1, sourceKey)
                    statement.bindLong(2, (startOrdinal + index).toLong())
                    statement.bindString(3, channel.id)
                    statement.bindString(4, channel.name)
                    statement.bindString(5, channel.group)
                    if (channel.logo.isNullOrBlank()) statement.bindNull(6) else statement.bindString(6, channel.logo)
                    statement.bindString(7, channel.url)
                    statement.bindString(8, channel.tvgName)
                    statement.bindString(9, channel.tvgId)
                    statement.bindString(10, channel.category)
                    statement.bindString(11, channel.provider)
                    statement.executeInsert()
                }
            } finally { statement.close() }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun read(sourceKey: String, limit: Int = 240): List<SportsChannel> {
        if (sourceKey.isBlank()) return emptyList()
        val safeLimit = limit.coerceIn(1, 500)
        return readableDatabase.rawQuery(
            "SELECT id,name,grp,logo,url,tvg_name,tvg_id,category,provider FROM preview WHERE source_key=? ORDER BY ord LIMIT $safeLimit",
            arrayOf(sourceKey)
        ).use { cursor ->
            val out = ArrayList<SportsChannel>(safeLimit)
            while (cursor.moveToNext()) {
                out += SportsChannel(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getString(3)?.ifBlank { null }, cursor.getString(4), cursor.getString(5), cursor.getString(6), cursor.getString(7), cursor.getString(8))
            }
            out
        }
    }

    fun clear(sourceKey: String) {
        if (sourceKey.isBlank()) return
        writableDatabase.delete("preview", "source_key=?", arrayOf(sourceKey))
    }
}
