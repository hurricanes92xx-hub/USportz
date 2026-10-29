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
                // FTS narrows the search first; the LIKE path below remains a compatibility
                // fallback for unusual punctuation, abbreviations, and partial substrings.
                val ftsExpression = safeTerms.joinToString(" OR ") { term ->
                    "\"" + term.replace("\"", "\"\"") + "\""
                }
                runCatching {
                    val remaining = max - out.size
                    if (remaining > 0) {
                        val sql = "SELECT c.id,c.name,c.grp,c.logo,c.url,c.tvg_name,c.tvg_id,c.category,c.provider FROM channels_fts f JOIN meta m ON m.source_key=f.source_key AND m.active_generation=CAST(f.generation AS INTEGER) AND m.complete=1 JOIN channels c ON c.source_key=f.source_key AND c.generation=CAST(f.generation AS INTEGER) AND c.id=f.id WHERE f.source_key=? AND channels_fts MATCH ? ORDER BY c.is_sports DESC,c.name COLLATE NOCASE LIMIT $remaining"
                        db.rawQuery(sql, arrayOf(sourceKey, ftsExpression)).use { c -> while (c.moveToNext()) add(c, out, seen) }
                    }
                }
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

    /** Returns one bounded page from the active catalogue; suitable for lazy channel browsing. */
    fun pageSportsChannels(
        context: Context,
        sourceKey: String,
        query: String = "",
        offset: Int = 0,
        limit: Int = 80
    ): List<SportsChannel> {
        if (sourceKey.isBlank()) return emptyList()
        val safeLimit = limit.coerceIn(1, 200)
        val safeOffset = offset.coerceAtLeast(0)
        val dbFile = context.applicationContext.getDatabasePath("usportz_channels.db")
        if (!dbFile.exists()) return emptyList()
        val db = runCatching { SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY) }.getOrNull()
            ?: return emptyList()
        db.use {
            val normalized = normalize(query)
            val tokens = normalized.split(' ').filter { it.length >= 2 }.distinct().take(8)
            val projection = "SELECT c.id,c.name,c.grp,c.logo,c.url,c.tvg_name,c.tvg_id,c.category,c.provider FROM channels c JOIN meta m ON m.source_key=c.source_key AND m.active_generation=c.generation AND m.complete=1"
            if (tokens.isEmpty()) {
                val sql = "$projection WHERE c.source_key=? AND c.is_sports=1 ORDER BY c.name COLLATE NOCASE LIMIT $safeLimit OFFSET $safeOffset"
                return db.rawQuery(sql, arrayOf(sourceKey)).use { cursor ->
                    buildList { while (cursor.moveToNext()) addChannel(cursor) }
                }
            }

            val ftsExpression = tokens.joinToString(" AND ") { "$it*" }
            val ftsSql = "SELECT c.id,c.name,c.grp,c.logo,c.url,c.tvg_name,c.tvg_id,c.category,c.provider FROM channels_fts f JOIN meta m ON m.source_key=f.source_key AND m.active_generation=CAST(f.generation AS INTEGER) AND m.complete=1 JOIN channels c ON c.source_key=f.source_key AND c.generation=CAST(f.generation AS INTEGER) AND c.id=f.id WHERE f.source_key=? AND c.is_sports=1 AND channels_fts MATCH ? ORDER BY c.name COLLATE NOCASE LIMIT $safeLimit OFFSET $safeOffset"
            val indexed = runCatching {
                db.rawQuery(ftsSql, arrayOf(sourceKey, ftsExpression)).use { cursor ->
                    buildList { while (cursor.moveToNext()) addChannel(cursor) }
                }
            }.getOrNull()
            if (!indexed.isNullOrEmpty()) return indexed

            val clauses = tokens.joinToString(" AND ") { "(LOWER(c.name) LIKE ? OR LOWER(c.tvg_name) LIKE ? OR LOWER(c.tvg_id) LIKE ? OR LOWER(c.grp) LIKE ? OR LOWER(c.category) LIKE ? OR LOWER(c.provider) LIKE ?)" }
            val args = ArrayList<String>(1 + tokens.size * 6)
            args += sourceKey
            tokens.forEach { token -> val pattern = "%$token%"; repeat(6) { args += pattern } }
            val sql = "$projection WHERE c.source_key=? AND c.is_sports=1 AND ($clauses) ORDER BY c.name COLLATE NOCASE LIMIT $safeLimit OFFSET $safeOffset"
            return db.rawQuery(sql, args.toTypedArray()).use { cursor ->
                buildList { while (cursor.moveToNext()) addChannel(cursor) }
            }
        }
    }

    private fun addChannel(cursor: android.database.Cursor): SportsChannel =
        SportsChannel(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getString(3)?.ifBlank { null }, cursor.getString(4), cursor.getString(5), cursor.getString(6), cursor.getString(7), cursor.getString(8))

    private fun add(c: android.database.Cursor, out: MutableList<SportsChannel>, seen: MutableSet<String>) {
        val id = c.getString(0)
        if (!seen.add(id)) return
        out += SportsChannel(c.getString(0), c.getString(1), c.getString(2), c.getString(3)?.ifBlank { null }, c.getString(4), c.getString(5), c.getString(6), c.getString(7), c.getString(8))
    }

    private fun normalize(value: String): String = value.lowercase().replace("&", " and ").replace("+", " plus ").replace(Regex("[^a-z0-9]+"), " ").trim().replace(Regex("\\s+"), " ")
}
