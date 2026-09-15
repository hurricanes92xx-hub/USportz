package com.usportz.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Tiny cold-start cache for the sports experience. */
class SportsStartupPreviewStore(context: Context) : SQLiteOpenHelper(context.applicationContext, "usportz_sports_preview.db", null, 1) {
    override fun onConfigure(db: SQLiteDatabase) { super.onConfigure(db); runCatching { db.enableWriteAheadLogging() }; runCatching { db.execSQL("PRAGMA synchronous=NORMAL") } }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE preview (source_key TEXT NOT NULL, ord INTEGER NOT NULL, id TEXT NOT NULL, name TEXT NOT NULL, grp TEXT NOT NULL, logo TEXT, url TEXT NOT NULL, tvg_name TEXT NOT NULL DEFAULT '', tvg_id TEXT NOT NULL DEFAULT '', category TEXT NOT NULL DEFAULT '', provider TEXT NOT NULL DEFAULT '', PRIMARY KEY(source_key, ord))""".trimIndent())
        db.execSQL("CREATE INDEX idx_preview_source_id ON preview(source_key, id)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    fun replaceBatch(sourceKey: String, batch: List<SportsChannel>) { if (sourceKey.isNotBlank() && batch.isNotEmpty()) replaceAll(sourceKey, batch.take(240)) }
    fun replaceAll(sourceKey: String, channels: List<SportsChannel>) {
        if (sourceKey.isBlank()) return
        val db=writableDatabase; db.beginTransactionNonExclusive()
        try {
            db.delete("preview","source_key=?",arrayOf(sourceKey))
            if(channels.isNotEmpty()){
                val s=db.compileStatement("INSERT INTO preview(source_key,ord,id,name,grp,logo,url,tvg_name,tvg_id,category,provider) VALUES(?,?,?,?,?,?,?,?,?,?,?)")
                try { channels.take(240).forEachIndexed { i,c -> s.clearBindings();s.bindString(1,sourceKey);s.bindLong(2,i.toLong());s.bindString(3,c.id);s.bindString(4,c.name);s.bindString(5,c.group);if(c.logo.isNullOrBlank())s.bindNull(6)else s.bindString(6,c.logo);s.bindString(7,c.url);s.bindString(8,c.tvgName);s.bindString(9,c.tvgId);s.bindString(10,c.category);s.bindString(11,c.provider);s.executeInsert()} } finally { s.close() }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    /** Keep the best 240 startup channels and feed the US/Canada fast lane at the same time. */
    fun mergeRanked(sourceKey:String,candidates:List<SportsChannel>,limit:Int=240){
        if(sourceKey.isBlank()||candidates.isEmpty())return
        runCatching{NorthAmericaSportsFastLane(context.applicationContext).indexBatch(sourceKey,candidates)}
        val current=read(sourceKey,limit)
        val merged=(current+candidates).filter{it.id.isNotBlank()&&it.url.isNotBlank()}.distinctBy{"${it.id}|${it.url}"}.sortedWith(compareByDescending<SportsChannel>{NorthAmericaSportsStartupPolicy.priority(it)}.thenBy{it.name.lowercase()}).take(limit.coerceIn(32,500))
        replaceAll(sourceKey,merged)
    }
    fun append(sourceKey:String,startOrdinal:Int,channels:List<SportsChannel>){
        if(sourceKey.isBlank()||channels.isEmpty())return
        // Fast bootstrap writes here in small increments; mirror those rows into the fast lane
        // immediately instead of waiting for a 2,000-row full-catalogue batch.
        runCatching{NorthAmericaSportsFastLane(context.applicationContext).indexBatch(sourceKey,channels)}
        val db=writableDatabase;db.beginTransactionNonExclusive()
        try{
            val s=db.compileStatement("INSERT OR REPLACE INTO preview(source_key,ord,id,name,grp,logo,url,tvg_name,tvg_id,category,provider) VALUES(?,?,?,?,?,?,?,?,?,?,?)")
            try{channels.forEachIndexed{i,c->s.clearBindings();s.bindString(1,sourceKey);s.bindLong(2,(startOrdinal+i).toLong());s.bindString(3,c.id);s.bindString(4,c.name);s.bindString(5,c.group);if(c.logo.isNullOrBlank())s.bindNull(6)else s.bindString(6,c.logo);s.bindString(7,c.url);s.bindString(8,c.tvgName);s.bindString(9,c.tvgId);s.bindString(10,c.category);s.bindString(11,c.provider);s.executeInsert()}}finally{s.close()}
            db.setTransactionSuccessful()
        }finally{db.endTransaction()}
    }
    fun read(sourceKey:String,limit:Int=240):List<SportsChannel>{if(sourceKey.isBlank())return emptyList();val safe=limit.coerceIn(1,500);return readableDatabase.rawQuery("SELECT id,name,grp,logo,url,tvg_name,tvg_id,category,provider FROM preview WHERE source_key=? ORDER BY ord LIMIT $safe",arrayOf(sourceKey)).use{c->val out=ArrayList<SportsChannel>(safe);while(c.moveToNext())out+=SportsChannel(c.getString(0),c.getString(1),c.getString(2),c.getString(3)?.ifBlank{null},c.getString(4),c.getString(5),c.getString(6),c.getString(7),c.getString(8));out}}
    fun clear(sourceKey:String){if(sourceKey.isNotBlank())writableDatabase.delete("preview","source_key=?",arrayOf(sourceKey))}
}
