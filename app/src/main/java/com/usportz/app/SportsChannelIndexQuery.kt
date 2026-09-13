package com.usportz.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase

/**
 * Read-side queries over the complete disk-backed channel catalogue.
 * Only a bounded candidate slice is materialized into memory for a resolver/display operation.
 */
object SportsChannelIndexQuery {
    fun eventCandidates(context: Context, sourceKey: String, terms: List<String>, limit: Int = 1200): List<SportsChannel> {
        if (sourceKey.isBlank()) return emptyList()
        val safeLimit = limit.coerceIn(1, 2000)
        val normalizedTerms = terms.map { it.trim() }.filter { it.length >= 3 }.distinct().take(18)
        val store = SportsChannelDiskStore(context)
        return runCatching {
            val db = store.readableDatabase
            val args = ArrayList<String>()
            val where = StringBuilder("c.source_key=? AND c.generation=m.active_generation")
            args += sourceKey
            if (normalizedTerms.isNotEmpty()) {
                where.append(" AND (")
                normalizedTerms.forEachIndexed { index, term ->
                    if (index > 0) where.append(" OR ")
                    where.append("(c.name LIKE ? OR c.grp LIKE ? OR c.tvg_name LIKE ? OR c.tvg_id LIKE ? OR c.category LIKE ? OR c.provider LIKE ?)")
                    val like = "%${escapeLike(term)}%"
                    repeat(6) { args += like }
                }
                where.append(")")
            }
            val sql = "SELECT c.id,c.name,c.grp,c.logo,c.url,c.tvg_name,c.tvg_id,c.category,c.provider FROM channels c JOIN meta m ON m.source_key=c.source_key AND m.active_generation=c.generation WHERE $where ORDER BY c.is_sports DESC, c.name COLLATE NOCASE LIMIT $safeLimit"
            db.rawQuery(sql, args.toTypedArray()).use { cursor ->
                val out = ArrayList<SportsChannel>(minOf(safeLimit, 512))
                while (cursor.moveToNext()) {
                    out += SportsChannel(
                        cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getString(3)?.ifBlank { null },
                        cursor.getString(4), cursor.getString(5), cursor.getString(6), cursor.getString(7), cursor.getString(8)
                    )
                }
                out
            }
        }.getOrDefault(emptyList())
    }

    private fun escapeLike(value: String): String = value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
}
