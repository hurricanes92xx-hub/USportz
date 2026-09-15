package com.usportz.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase

/**
 * Bounded reader for the complete provider catalogue. It never materializes the
 * provider inventory in Compose/RAM; callers request small pages from SQLite.
 */
data class CataloguePage(
    val items: List<SportsChannel>,
    val offset: Int,
    val limit: Int,
    val total: Int
) {
    val hasNext: Boolean get() = offset + items.size < total
}

object FullCataloguePager {
    fun page(context: Context, sourceKey: String, offset: Int = 0, limit: Int = 100, query: String = ""): CataloguePage {
        val safeOffset = offset.coerceAtLeast(0)
        val safeLimit = limit.coerceIn(1, 200)
        val dbFile = context.applicationContext.getDatabasePath("usportz_channels.db")
        if (!dbFile.exists()) return CataloguePage(emptyList(), safeOffset, safeLimit, 0)
        val db = runCatching { SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY) }.getOrNull()
            ?: return CataloguePage(emptyList(), safeOffset, safeLimit, 0)
        db.use {
            val where = "m.source_key=? AND m.complete=1"
            val normalized = query.trim().lowercase()
            val countArgs = arrayListOf(sourceKey)
            val pageArgs = arrayListOf(sourceKey)
            val extra = if (normalized.isBlank()) "" else " AND (LOWER(c.name) LIKE ? OR LOWER(c.grp) LIKE ? OR LOWER(c.tvg_name) LIKE ? OR LOWER(c.category) LIKE ?)"
            if (normalized.isNotBlank()) {
                val p = "%$normalized%"
                repeat(4) { countArgs += p; pageArgs += p }
            }
            val total = db.rawQuery("SELECT COUNT(*) FROM channels c JOIN meta m ON m.source_key=c.source_key AND m.active_generation=c.generation WHERE $where$extra", countArgs.toTypedArray()).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
            val items = ArrayList<SportsChannel>(safeLimit)
            db.rawQuery("SELECT c.id,c.name,c.grp,c.logo,c.url,c.tvg_name,c.tvg_id,c.category,c.provider FROM channels c JOIN meta m ON m.source_key=c.source_key AND m.active_generation=c.generation WHERE $where$extra ORDER BY c.name COLLATE NOCASE LIMIT $safeLimit OFFSET $safeOffset", pageArgs.toTypedArray()).use { c ->
                while (c.moveToNext()) items += SportsChannel(c.getString(0), c.getString(1), c.getString(2), c.getString(3)?.ifBlank { null }, c.getString(4), c.getString(5), c.getString(6), c.getString(7), c.getString(8))
            }
            return CataloguePage(items, safeOffset, safeLimit, total)
        }
    }
}
