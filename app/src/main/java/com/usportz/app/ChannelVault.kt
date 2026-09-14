package com.usportz.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/** Persistent channel inventory kept outside the Compose/UI state. Search fields are normalized once at import. */
class ChannelVault(context: Context) {
    private val helper = VaultDb(context.applicationContext)

    suspend fun replaceAll(sourceKey: String, channels: List<SportsChannel>) = withContext(Dispatchers.IO) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            db.delete(TABLE, "$SOURCE = ?", arrayOf(sourceKey))
            val statement = db.compileStatement("INSERT OR REPLACE INTO $TABLE($ROW,$SOURCE,$ID,$NAME,$GROUP,$LOGO,$URL,$TVG_NAME,$TVG_ID,$PROVIDER,$N_NAME,$N_GROUP,$N_CATEGORY,$N_PROVIDER,$N_TVG,$CATEGORY) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")
            channels.forEach { ch ->
                val category = categoryFor(ch)
                statement.clearBindings()
                statement.bindString(1, "$sourceKey|${ch.id}|${ch.url}".hashCode().toString())
                statement.bindString(2, sourceKey); statement.bindString(3, ch.id); statement.bindString(4, ch.name)
                statement.bindString(5, ch.group); statement.bindString(6, ch.logo.orEmpty()); statement.bindString(7, ch.url)
                statement.bindString(8, ch.tvgName); statement.bindString(9, ch.tvgId); statement.bindString(10, ch.provider)
                statement.bindString(11, normalize(ch.name)); statement.bindString(12, normalize(ch.group))
                statement.bindString(13, normalize(category)); statement.bindString(14, normalize(ch.provider))
                statement.bindString(15, normalize("${ch.tvgName} ${ch.tvgId}")); statement.bindString(16, category)
                statement.executeInsert()
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    suspend fun count(sourceKey: String): Int = withContext(Dispatchers.IO) { queryInt("SELECT COUNT(*) FROM $TABLE WHERE $SOURCE = ?", arrayOf(sourceKey)) }

    suspend fun categories(sourceKey: String): List<String> = withContext(Dispatchers.IO) {
        val out = ArrayList<String>()
        helper.readableDatabase.rawQuery("SELECT $CATEGORY FROM $TABLE WHERE $SOURCE = ? AND $CATEGORY <> '' GROUP BY $CATEGORY ORDER BY $N_CATEGORY", arrayOf(sourceKey)).use { c -> while (c.moveToNext()) out += c.getString(0) }
        out
    }

    suspend fun channelsForCategory(sourceKey: String, category: String, limit: Int = 500): List<SportsChannel> = withContext(Dispatchers.IO) = queryChannels(
        "SELECT $ID,$NAME,$GROUP,$LOGO,$URL,$TVG_NAME,$TVG_ID,$CATEGORY,$PROVIDER FROM $TABLE WHERE $SOURCE = ? AND $CATEGORY = ? ORDER BY $N_NAME LIMIT ?",
        arrayOf(sourceKey, category, limit.toString())
    )

    suspend fun search(sourceKey: String, query: String, limit: Int = 100): List<SportsChannel> = withContext(Dispatchers.IO) {
        val q = "%${normalize(query).replace("%", "")}%"
        queryChannels(
            "SELECT $ID,$NAME,$GROUP,$LOGO,$URL,$TVG_NAME,$TVG_ID,$CATEGORY,$PROVIDER FROM $TABLE WHERE $SOURCE = ? AND ($N_NAME LIKE ? OR $N_GROUP LIKE ? OR $N_CATEGORY LIKE ? OR $N_PROVIDER LIKE ? OR $N_TVG LIKE ?) ORDER BY $N_NAME LIMIT ?",
            arrayOf(sourceKey, q, q, q, q, q, limit.toString())
        )
    }

    /** Bounded, indexed candidate lookup used by event-to-channel matching. */
    suspend fun channelsForEvent(sourceKey: String, event: SportsEvent, limit: Int = 250): List<SportsChannel> = withContext(Dispatchers.IO) {
        val terms = buildList {
            event.competitors.forEach { normalize(it).takeIf { n -> n.length >= 3 }?.let(::add) }
            normalize(event.broadcast).takeIf { it.isNotBlank() }?.let(::add)
            normalize(event.league).takeIf { it.isNotBlank() }?.let(::add)
        }.distinct().take(12)
        if (terms.isEmpty()) return@withContext emptyList()
        val where = terms.joinToString(" OR ") { "($N_NAME LIKE ? OR $N_GROUP LIKE ? OR $N_CATEGORY LIKE ? OR $N_PROVIDER LIKE ? OR $N_TVG LIKE ?)" }
        val args = ArrayList<String>(1 + terms.size * 5 + 1); args += sourceKey
        terms.forEach { term -> val like = "%${term.replace("%", "")}%"; repeat(5) { args += like } }
        args += limit.coerceIn(1, 600).toString()
        queryChannels("SELECT $ID,$NAME,$GROUP,$LOGO,$URL,$TVG_NAME,$TVG_ID,$CATEGORY,$PROVIDER FROM $TABLE WHERE $SOURCE = ? AND ($where) ORDER BY $N_NAME LIMIT ?", args.toTypedArray())
    }

    private fun queryChannels(sql: String, args: Array<String>): List<SportsChannel> {
        val out = ArrayList<SportsChannel>()
        helper.readableDatabase.rawQuery(sql, args).use { c -> while (c.moveToNext()) out += SportsChannel(c.getString(0), c.getString(1), c.getString(2), c.getString(3).ifBlank { null }, c.getString(4), c.getString(5), c.getString(6), c.getString(7), c.getString(8)) }
        return out
    }

    private fun queryInt(sql: String, args: Array<String>): Int = helper.readableDatabase.rawQuery(sql, args).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    private fun categoryFor(ch: SportsChannel): String {
        val group = ch.group.trim().removePrefix("##").removeSuffix("##").trim()
        if (group.isNotBlank() && !group.equals("live tv", true)) return group
        if (ch.category.isNotBlank()) return ch.category.trim()
        return "Uncategorized"
    }

    private fun normalize(value: String): String = value.lowercase().replace("&", " and ").replace(Regex("[^a-z0-9]+"), " ").trim()

    private class VaultDb(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, 2) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE $TABLE($ROW TEXT PRIMARY KEY,$SOURCE TEXT NOT NULL,$ID TEXT NOT NULL,$NAME TEXT NOT NULL,$GROUP TEXT NOT NULL,$LOGO TEXT,$URL TEXT NOT NULL,$TVG_NAME TEXT NOT NULL DEFAULT '',$TVG_ID TEXT NOT NULL DEFAULT '',$PROVIDER TEXT NOT NULL DEFAULT '',$N_NAME TEXT NOT NULL,$N_GROUP TEXT NOT NULL,$N_CATEGORY TEXT NOT NULL,$N_PROVIDER TEXT NOT NULL,$N_TVG TEXT NOT NULL,$CATEGORY TEXT NOT NULL)")
            createIndexes(db)
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                db.execSQL("ALTER TABLE $TABLE ADD COLUMN $TVG_NAME TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE $TABLE ADD COLUMN $TVG_ID TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE $TABLE ADD COLUMN $PROVIDER TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE $TABLE ADD COLUMN $N_GROUP TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE $TABLE ADD COLUMN $N_CATEGORY TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE $TABLE ADD COLUMN $N_PROVIDER TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE $TABLE ADD COLUMN $N_TVG TEXT NOT NULL DEFAULT ''")
                db.execSQL("UPDATE $TABLE SET $N_GROUP = lower($GROUP), $N_CATEGORY = lower($CATEGORY), $N_PROVIDER = lower($PROVIDER), $N_TVG = lower($TVG_NAME || ' ' || $TVG_ID)")
                createIndexes(db)
            }
        }
        private fun createIndexes(db: SQLiteDatabase) {
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_vault_source_name ON $TABLE($SOURCE,$N_NAME)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_vault_source_group ON $TABLE($SOURCE,$N_GROUP)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_vault_source_category ON $TABLE($SOURCE,$N_CATEGORY)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_vault_source_provider ON $TABLE($SOURCE,$N_PROVIDER)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_vault_source_tvg ON $TABLE($SOURCE,$N_TVG)")
        }
    }

    companion object {
        fun key(store: SourceStore): String {
            val raw = listOf(store.server.trim(), store.user, store.pass, store.playlist.trim()).joinToString("\u0000")
            return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
        }
        private const val DB_NAME = "usportz_channel_vault.db"
        private const val TABLE = "channels"
        private const val ROW = "row_key"; private const val SOURCE = "source_key"; private const val ID = "channel_id"
        private const val NAME = "name"; private const val GROUP = "group_name"; private const val LOGO = "logo"; private const val URL = "url"
        private const val TVG_NAME = "tvg_name"; private const val TVG_ID = "tvg_id"; private const val PROVIDER = "provider"
        private const val N_NAME = "normalized_name"; private const val N_GROUP = "normalized_group"; private const val N_CATEGORY = "normalized_category"
        private const val N_PROVIDER = "normalized_provider"; private const val N_TVG = "normalized_tvg"; private const val CATEGORY = "category"
    }
}
