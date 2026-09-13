package com.usportz.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Disk-side access to the complete channel index without loading every row into Compose. */
object SportsChannelIndexQuery {
    fun eventCandidates(context: Context, sourceKey: String, terms: List<String>, limit: Int = 1200): List<SportsChannel> {
        val max = limit.coerceIn(1, 2000)
        val out = ArrayList<SportsChannel>(max)
        val seen = HashSet<String>()
        val db = Helper(context).readableDatabase
        val safeTerms = terms.map { normalize(it).replace("%", " ").replace("_", " ") }.filter { it.length >= 3 }.distinct().take(18)
        val base = "SELECT c.id,c.name,c.grp,c.logo,c.url,c.tvg_name,c.tvg_id,c.category,c.provider FROM channels c JOIN meta m ON m.source_key=c.source_key AND m.active_generation=c.generation WHERE c.source_key=?"
        if (safeTerms.isNotEmpty()) {
            val clauses = safeTerms.joinToString(" OR ") { "(LOWER(c.name) LIKE ? OR LOWER(c.tvg_name) LIKE ? OR LOWER(c.tvg_id) LIKE ? OR LOWER(c.grp) LIKE ? OR LOWER(c.category) LIKE ? OR LOWER(c.provider) LIKE ?)" }
            val args = ArrayList<String>(1 + safeTerms.size * 6)
            args += sourceKey
            safeTerms.forEach { term -> val p = "%$term%"; repeat(6) { args += p } }
            db.rawQuery("$base AND ($clauses) ORDER BY c.is_sports DESC, c.name COLLATE NOCASE LIMIT $max", args.toTypedArray()).use { c -> while (c.moveToNext()) add(c, out, seen) }
        }
        if (out.size < max) {
            val remaining = max - out.size
            db.rawQuery("$base AND c.is_sports=1 ORDER BY c.name COLLATE NOCASE LIMIT $remaining", arrayOf(sourceKey)).use { c -> while (c.moveToNext()) add(c, out, seen) }
        }
        return out
    }

    private fun add(c: android.database.Cursor, out: MutableList<SportsChannel>, seen: MutableSet<String>) {
        val id = c.getString(0)
        if (!seen.add(id)) return
        out += SportsChannel(c.getString(0), c.getString(1), c.getString(2), c.getString(3)?.ifBlank { null }, c.getString(4), c.getString(5), c.getString(6), c.getString(7), c.getString(8))
    }

    private fun normalize(value: String): String = value.lowercase().replace("&", " and ").replace("+", " plus ").replace(Regex("[^a-z0-9]+"), " ").trim().replace(Regex("\\s+"), " ")

    private class Helper(context: Context) : SQLiteOpenHelper(context.applicationContext, "usportz_channels.db", null, 2) {
        override fun onCreate(db: SQLiteDatabase) = Unit
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
}
