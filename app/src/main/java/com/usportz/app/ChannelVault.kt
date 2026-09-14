package com.usportz.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/** Persistent channel inventory plus stable Channel DNA and bounded stream reliability memory. */
class ChannelVault(context: Context) {
    private val helper = VaultDb(context.applicationContext)

    data class Dna(
        val key: String,
        val canonical: String,
        val family: String,
        val lastUrl: String,
        val successes: Int,
        val failures: Int,
        val score: Int
    )

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
                observeDna(db, ch)
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

    suspend fun channelsForCategory(sourceKey: String, category: String, limit: Int = 500): List<SportsChannel> = withContext(Dispatchers.IO) {
        queryChannels("SELECT $ID,$NAME,$GROUP,$LOGO,$URL,$TVG_NAME,$TVG_ID,$CATEGORY,$PROVIDER FROM $TABLE WHERE $SOURCE = ? AND $CATEGORY = ? ORDER BY $N_NAME LIMIT ?", arrayOf(sourceKey, category, limit.toString()))
    }

    suspend fun search(sourceKey: String, query: String, limit: Int = 100): List<SportsChannel> = withContext(Dispatchers.IO) {
        val q = "%${normalize(query).replace("%", "")}%"
        queryChannels("SELECT $ID,$NAME,$GROUP,$LOGO,$URL,$TVG_NAME,$TVG_ID,$CATEGORY,$PROVIDER FROM $TABLE WHERE $SOURCE = ? AND ($N_NAME LIKE ? OR $N_GROUP LIKE ? OR $N_CATEGORY LIKE ? OR $N_PROVIDER LIKE ? OR $N_TVG LIKE ?) ORDER BY $N_NAME LIMIT ?", arrayOf(sourceKey, q, q, q, q, q, limit.toString()))
    }

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

    fun dna(channel: SportsChannel): Dna? {
        val key = dnaKey(channel)
        return helper.readableDatabase.query(DNA_TABLE, null, "$DNA_KEY=?", arrayOf(key), null, null, null, "1").use { c ->
            if (!c.moveToFirst()) return null
            Dna(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getInt(4), c.getInt(5), c.getInt(6))
        }
    }

    fun observe(channel: SportsChannel) { observeDna(helper.writableDatabase, channel) }
    fun recordSuccess(channel: SportsChannel) = adjustDna(channel, true)
    fun recordFailure(channel: SportsChannel) = adjustDna(channel, false)

    private fun adjustDna(channel: SportsChannel, success: Boolean) {
        observe(channel)
        val old = dna(channel) ?: return
        val values = android.content.ContentValues().apply {
            put(DNA_URL, channel.url)
            put(DNA_SUCCESSES, if (success) old.successes + 1 else old.successes)
            put(DNA_FAILURES, if (!success) old.failures + 1 else old.failures)
            put(DNA_SCORE, if (success) (old.score + 7).coerceAtMost(100) else (old.score - 5).coerceAtLeast(0))
        }
        helper.writableDatabase.update(DNA_TABLE, values, "$DNA_KEY=?", arrayOf(old.key))
    }

    private fun observeDna(db: SQLiteDatabase, channel: SportsChannel) {
        val old = dna(channel)
        val values = android.content.ContentValues().apply {
            put(DNA_KEY, dnaKey(channel)); put(DNA_CANONICAL, canonical(channel)); put(DNA_FAMILY, StreamClassifier.family(channel)); put(DNA_URL, channel.url)
            put(DNA_SUCCESSES, old?.successes ?: 0); put(DNA_FAILURES, old?.failures ?: 0); put(DNA_SCORE, old?.score ?: 50)
        }
        db.insertWithOnConflict(DNA_TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun dnaKey(c: SportsChannel): String {
        val values = listOf(c.tvgId, c.tvgName, c.name, c.group, c.provider).map(::normalize).filter { it.isNotBlank() }
        return values.firstOrNull { it.length >= 4 && !it.all(Char::isDigit) } ?: values.joinToString("|").take(180)
    }
    private fun canonical(c: SportsChannel): String = listOf(c.tvgId, c.tvgName, c.name).firstOrNull { it.isNotBlank() }?.let(::normalize).orEmpty()

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

    private class VaultDb(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, 3) {
        override fun onCreate(db: SQLiteDatabase) { createMain(db); createDna(db) }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                db.execSQL("ALTER TABLE $TABLE ADD COLUMN $TVG_NAME TEXT NOT NULL DEFAULT ''"); db.execSQL("ALTER TABLE $TABLE ADD COLUMN $TVG_ID TEXT NOT NULL DEFAULT ''"); db.execSQL("ALTER TABLE $TABLE ADD COLUMN $PROVIDER TEXT NOT NULL DEFAULT ''"); db.execSQL("ALTER TABLE $TABLE ADD COLUMN $N_GROUP TEXT NOT NULL DEFAULT ''"); db.execSQL("ALTER TABLE $TABLE ADD COLUMN $N_CATEGORY TEXT NOT NULL DEFAULT ''"); db.execSQL("ALTER TABLE $TABLE ADD COLUMN $N_PROVIDER TEXT NOT NULL DEFAULT ''"); db.execSQL("ALTER TABLE $TABLE ADD COLUMN $N_TVG TEXT NOT NULL DEFAULT ''"); db.execSQL("UPDATE $TABLE SET $N_GROUP = lower($GROUP), $N_CATEGORY = lower($CATEGORY), $N_PROVIDER = lower($PROVIDER), $N_TVG = lower($TVG_NAME || ' ' || $TVG_ID)"); createIndexes(db)
            }
            if (oldVersion < 3) createDna(db)
        }
        private fun createMain(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE $TABLE($ROW TEXT PRIMARY KEY,$SOURCE TEXT NOT NULL,$ID TEXT NOT NULL,$NAME TEXT NOT NULL,$GROUP TEXT NOT NULL,$LOGO TEXT,$URL TEXT NOT NULL,$TVG_NAME TEXT NOT NULL DEFAULT '',$TVG_ID TEXT NOT NULL DEFAULT '',$PROVIDER TEXT NOT NULL DEFAULT '',$N_NAME TEXT NOT NULL,$N_GROUP TEXT NOT NULL,$N_CATEGORY TEXT NOT NULL,$N_PROVIDER TEXT NOT NULL,$N_TVG TEXT NOT NULL,$CATEGORY TEXT NOT NULL)"); createIndexes(db)
        }
        private fun createDna(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS $DNA_TABLE($DNA_KEY TEXT PRIMARY KEY,$DNA_CANONICAL TEXT NOT NULL,$DNA_FAMILY TEXT NOT NULL,$DNA_URL TEXT NOT NULL,$DNA_SUCCESSES INTEGER NOT NULL DEFAULT 0,$DNA_FAILURES INTEGER NOT NULL DEFAULT 0,$DNA_SCORE INTEGER NOT NULL DEFAULT 50)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_dna_family ON $DNA_TABLE($DNA_FAMILY)")
        }
        private fun createIndexes(db: SQLiteDatabase) {
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_vault_source_name ON $TABLE($SOURCE,$N_NAME)"); db.execSQL("CREATE INDEX IF NOT EXISTS idx_vault_source_group ON $TABLE($SOURCE,$N_GROUP)"); db.execSQL("CREATE INDEX IF NOT EXISTS idx_vault_source_category ON $TABLE($SOURCE,$N_CATEGORY)"); db.execSQL("CREATE INDEX IF NOT EXISTS idx_vault_source_provider ON $TABLE($SOURCE,$N_PROVIDER)"); db.execSQL("CREATE INDEX IF NOT EXISTS idx_vault_source_tvg ON $TABLE($SOURCE,$N_TVG)")
        }
    }

    companion object {
        fun key(store: SourceStore): String { val raw = listOf(store.server.trim(), store.user, store.pass, store.playlist.trim()).joinToString("\u0000"); return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) } }
        private const val DB_NAME = "usportz_channel_vault.db"; private const val TABLE = "channels"; private const val DNA_TABLE = "channel_dna"
        private const val ROW = "row_key"; private const val SOURCE = "source_key"; private const val ID = "channel_id"; private const val NAME = "name"; private const val GROUP = "group_name"; private const val LOGO = "logo"; private const val URL = "url"
        private const val TVG_NAME = "tvg_name"; private const val TVG_ID = "tvg_id"; private const val PROVIDER = "provider"; private const val N_NAME = "normalized_name"; private const val N_GROUP = "normalized_group"; private const val N_CATEGORY = "normalized_category"; private const val N_PROVIDER = "normalized_provider"; private const val N_TVG = "normalized_tvg"; private const val CATEGORY = "category"
        private const val DNA_KEY = "dna_key"; private const val DNA_CANONICAL = "canonical"; private const val DNA_FAMILY = "family"; private const val DNA_URL = "last_url"; private const val DNA_SUCCESSES = "successes"; private const val DNA_FAILURES = "failures"; private const val DNA_SCORE = "score"
    }
}
