package com.usportz.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Persistent channel inventory kept outside the Compose/UI state.
 * The UI queries only the category/search/event slice it needs.
 */
class ChannelVault(context: Context) {
    private val helper = VaultDb(context.applicationContext)

    suspend fun replaceAll(sourceKey: String, channels: List<SportsChannel>) = withContext(Dispatchers.IO) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            db.delete(TABLE, "$COL_SOURCE = ?", arrayOf(sourceKey))
            val statement = db.compileStatement(
                "INSERT OR REPLACE INTO $TABLE(" +
                    "$COL_ROW,$COL_SOURCE,$COL_ID,$COL_NAME,$COL_GROUP,$COL_LOGO,$COL_URL,$COL_NORMALIZED,$COL_CATEGORY" +
                    ") VALUES(?,?,?,?,?,?,?,?,?)"
            )
            channels.forEach { channel ->
                val category = categoryFor(channel)
                statement.clearBindings()
                statement.bindString(1, "$sourceKey|${channel.id}|${channel.url}".hashCode().toString())
                statement.bindString(2, sourceKey)
                statement.bindString(3, channel.id)
                statement.bindString(4, channel.name)
                statement.bindString(5, channel.group)
                statement.bindString(6, channel.logo.orEmpty())
                statement.bindString(7, channel.url)
                statement.bindString(8, normalize(channel.name))
                statement.bindString(9, category)
                statement.executeInsert()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    suspend fun count(sourceKey: String): Int = withContext(Dispatchers.IO) {
        queryInt("SELECT COUNT(*) FROM $TABLE WHERE $COL_SOURCE = ?", arrayOf(sourceKey))
    }

    suspend fun categories(sourceKey: String): List<String> = withContext(Dispatchers.IO) {
        val db = helper.readableDatabase
        val out = ArrayList<String>()
        db.rawQuery(
            "SELECT $COL_CATEGORY, COUNT(*) AS n FROM $TABLE WHERE $COL_SOURCE = ? AND $COL_CATEGORY <> '' GROUP BY $COL_CATEGORY ORDER BY LOWER($COL_CATEGORY)",
            arrayOf(sourceKey)
        ).use { c ->
            while (c.moveToNext()) out += c.getString(0)
        }
        out
    }

    suspend fun channelsForCategory(sourceKey: String, category: String, limit: Int = 500): List<SportsChannel> = withContext(Dispatchers.IO) {
        queryChannels(
            "SELECT $COL_ID,$COL_NAME,$COL_GROUP,$COL_LOGO,$COL_URL FROM $TABLE WHERE $COL_SOURCE = ? AND $COL_CATEGORY = ? ORDER BY LOWER($COL_NAME) LIMIT ?",
            arrayOf(sourceKey, category, limit.toString())
        )
    }

    suspend fun search(sourceKey: String, query: String, limit: Int = 100): List<SportsChannel> = withContext(Dispatchers.IO) {
        val q = "%${normalize(query).replace("%", "")}%"
        queryChannels(
            "SELECT $COL_ID,$COL_NAME,$COL_GROUP,$COL_LOGO,$COL_URL FROM $TABLE WHERE $COL_SOURCE = ? AND ($COL_NORMALIZED LIKE ? OR LOWER($COL_GROUP) LIKE ?) ORDER BY LOWER($COL_NAME) LIMIT ?",
            arrayOf(sourceKey, q, q, limit.toString())
        )
    }

    /** Returns every plausible provider feed for an event, not just one best match. */
    suspend fun channelsForEvent(sourceKey: String, event: SportsEvent, limit: Int = 250): List<SportsChannel> = withContext(Dispatchers.IO) {
        val terms = buildList {
            event.competitors.forEach { term ->
                val normalized = normalize(term)
                if (normalized.length >= 3) add(normalized)
            }
            if (event.broadcast.isNotBlank()) add(normalize(event.broadcast))
            if (event.league.isNotBlank()) add(normalize(event.league))
        }.distinct().take(6)

        if (terms.isEmpty()) return@withContext emptyList()
        val where = terms.joinToString(" OR ") { "($COL_NORMALIZED LIKE ? OR LOWER($COL_GROUP) LIKE ? OR LOWER($COL_CATEGORY) LIKE ?)" }
        val args = ArrayList<String>(1 + terms.size * 3)
        args += sourceKey
        terms.forEach { term ->
            val like = "%${term.replace("%", "")}%"
            args += like
            args += like
            args += like
        }
        queryChannels(
            "SELECT $COL_ID,$COL_NAME,$COL_GROUP,$COL_LOGO,$COL_URL FROM $TABLE WHERE $COL_SOURCE = ? AND ($where) ORDER BY LOWER($COL_NAME) LIMIT ?",
            args + limit.toString()
        )
    }

    private fun queryChannels(sql: String, args: Array<String>): List<SportsChannel> {
        val db = helper.readableDatabase
        val out = ArrayList<SportsChannel>()
        db.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) {
                out += SportsChannel(
                    id = c.getString(0),
                    name = c.getString(1),
                    group = c.getString(2),
                    logo = c.getString(3).ifBlank { null },
                    url = c.getString(4)
                )
            }
        }
        return out
    }

    private fun queryInt(sql: String, args: Array<String>): Int {
        helper.readableDatabase.rawQuery(sql, args).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    private fun categoryFor(channel: SportsChannel): String {
        val group = cleanCategory(channel.group)
        if (group.isNotBlank() && !group.equals("live tv", true)) return group
        val name = channel.name.trim()
        if (name.startsWith("##")) return cleanCategory(name)
        return group.ifBlank { "Uncategorized" }
    }

    private fun cleanCategory(value: String): String = value.trim().removePrefix("##").removeSuffix("##").trim()

    private fun normalize(value: String): String = value.lowercase()
        .replace("&", " and ")
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()

    private class VaultDb(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE $TABLE($COL_ROW TEXT PRIMARY KEY,$COL_SOURCE TEXT NOT NULL,$COL_ID TEXT NOT NULL,$COL_NAME TEXT NOT NULL,$COL_GROUP TEXT NOT NULL,$COL_LOGO TEXT,$COL_URL TEXT NOT NULL,$COL_NORMALIZED TEXT NOT NULL,$COL_CATEGORY TEXT NOT NULL)")
            db.execSQL("CREATE INDEX idx_vault_source_category ON $TABLE($COL_SOURCE,$COL_CATEGORY)")
            db.execSQL("CREATE INDEX idx_vault_source_normalized ON $TABLE($COL_SOURCE,$COL_NORMALIZED)")
            db.execSQL("CREATE INDEX idx_vault_source_group ON $TABLE($COL_SOURCE,$COL_GROUP)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    private companion object {
        const val DB_NAME = "usportz_channel_vault.db"
        const val TABLE = "channels"
        const val COL_ROW = "row_key"
        const val COL_SOURCE = "source_key"
        const val COL_ID = "channel_id"
        const val COL_NAME = "name"
        const val COL_GROUP = "group_name"
        const val COL_LOGO = "logo"
        const val COL_URL = "url"
        const val COL_NORMALIZED = "normalized_name"
        const val COL_CATEGORY = "category"
    }
}
