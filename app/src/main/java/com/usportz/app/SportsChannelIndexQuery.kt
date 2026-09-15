package com.usportz.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase

/** Disk-side access to the complete channel index without loading every row into Compose. */
object SportsChannelIndexQuery {
    fun eventCandidates(context: Context, sourceKey: String, terms: List<String>, limit: Int = 240): List<SportsChannel> {
        val max = limit.coerceIn(1, 600)
        val out = ArrayList<SportsChannel>(max)
        val seen = HashSet<String>()
        // Query the dedicated US/Canada index first. It is populated during the same streaming
        // import, so game matching does not have to wait for the full 57k+ catalogue.
        runCatching {
            NorthAmericaSportsFastLane(context).candidates(sourceKey, terms, max).forEach {
                if (seen.add("${it.id}|${it.url}")) out += it
            }
        }
        if (out.size >= max) return out.take(max)
        val dbFile = context.applicationContext.getDatabasePath("usportz_channels.db")
        if (!dbFile.exists()) return out
        val db = runCatching { SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY) }.getOrNull() ?: return out
        db.use {
            val safeTerms = terms.map(::normalize).filter { it.length >= 3 }.distinct().take(12)
            val base = "SELECT c.id,c.name,c.grp,c.logo,c.url,c.tvg_name,c.tvg_id,c.category,c.provider FROM channels c JOIN meta m ON m.source_key=c.source_key AND m.active_generation=c.generation AND m.complete=1 WHERE c.source_key=?"
            if (safeTerms.isNotEmpty()) {
                val clauses = safeTerms.joinToString(" OR ") { "(LOWER(c.name) LIKE ? OR LOWER(c.tvg_name) LIKE ? OR LOWER(c.tvg_id) LIKE ? OR LOWER(c.grp) LIKE ? OR LOWER(c.category) LIKE ? OR LOWER(c.provider) LIKE ?)" }
                val args = ArrayList<String>(1 + safeTerms.size * 6)
                args += sourceKey
                safeTerms.forEach { term -> val p = "%$term%"; repeat(6) { args += p } }
                db.rawQuery("$base AND ($clauses) ORDER BY c.is_sports DESC, c.name COLLATE NOCASE LIMIT ${max - out.size}", args.toTypedArray()).use { c -> while (c.moveToNext()) add(c, out, seen) }
            }
            if (out.size < max) {
                val remaining = max - out.size
                db.rawQuery("$base AND c.is_sports=1 ORDER BY c.name COLLATE NOCASE LIMIT $remaining", arrayOf(sourceKey)).use { c -> while (c.moveToNext()) add(c, out, seen) }
            }
        }
        return out
    }

    private fun add(c: android.database.Cursor, out: MutableList<SportsChannel>, seen: MutableSet<String>) {
        val id = c.getString(0)
        if (!seen.add(id)) return
        out += SportsChannel(c.getString(0), c.getString(1), c.getString(2), c.getString(3)?.ifBlank { null }, c.getString(4), c.getString(5), c.getString(6), c.getString(7), c.getString(8))
    }

    private fun normalize(value: String): String = value.lowercase().replace("&", " and ").replace("+", " plus ").replace(Regex("[^a-z0-9]+"), " ").trim().replace(Regex("\\s+"), " ")
}
