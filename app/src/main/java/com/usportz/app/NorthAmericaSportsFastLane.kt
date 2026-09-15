package com.usportz.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Fast metadata-only US/Canada sports lookup built during the full Xtream stream import. */
class NorthAmericaSportsFastLane(context: Context) : SQLiteOpenHelper(
    context.applicationContext, "usportz_usca_fast_lane.db", null, 1
) {
    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        runCatching { db.enableWriteAheadLogging() }
        runCatching { db.execSQL("PRAGMA synchronous=NORMAL") }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE fast_lane (source_key TEXT NOT NULL, id TEXT NOT NULL, url TEXT NOT NULL, name TEXT NOT NULL, grp TEXT NOT NULL, logo TEXT, tvg_name TEXT NOT NULL, tvg_id TEXT NOT NULL, category TEXT NOT NULL, provider TEXT NOT NULL, region TEXT NOT NULL, bucket TEXT NOT NULL, priority INTEGER NOT NULL, search_text TEXT NOT NULL, PRIMARY KEY(source_key,id,url))")
        db.execSQL("CREATE INDEX idx_fast_priority ON fast_lane(source_key,priority DESC)")
        db.execSQL("CREATE INDEX idx_fast_region ON fast_lane(source_key,region,priority DESC)")
        db.execSQL("CREATE INDEX idx_fast_bucket ON fast_lane(source_key,bucket,priority DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun indexBatch(sourceKey: String, channels: List<SportsChannel>) {
        if (sourceKey.isBlank() || channels.isEmpty()) return
        val items = channels.mapNotNull(::classify)
        if (items.isEmpty()) return
        val db = writableDatabase
        db.beginTransactionNonExclusive()
        val s = db.compileStatement("INSERT OR REPLACE INTO fast_lane(source_key,id,url,name,grp,logo,tvg_name,tvg_id,category,provider,region,bucket,priority,search_text) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)")
        try {
            items.forEach { x ->
                val c = x.channel
                s.clearBindings(); s.bindString(1, sourceKey); s.bindString(2, c.id); s.bindString(3, c.url); s.bindString(4, c.name); s.bindString(5, c.group)
                if (c.logo.isNullOrBlank()) s.bindNull(6) else s.bindString(6, c.logo)
                s.bindString(7, c.tvgName); s.bindString(8, c.tvgId); s.bindString(9, c.category); s.bindString(10, c.provider)
                s.bindString(11, x.region); s.bindString(12, x.bucket); s.bindLong(13, x.priority.toLong()); s.bindString(14, x.searchText); s.executeInsert()
            }
            db.setTransactionSuccessful()
        } finally { s.close(); db.endTransaction() }
    }

    fun candidates(sourceKey: String, terms: List<String>, limit: Int = 720): List<SportsChannel> {
        if (sourceKey.isBlank()) return emptyList()
        val max = limit.coerceIn(1, 1200)
        val safe = terms.map(::normalize).filter { it.length >= 3 }.distinct().take(24)
        val sql = if (safe.isEmpty()) {
            "SELECT id,name,grp,logo,url,tvg_name,tvg_id,category,provider FROM fast_lane WHERE source_key=? ORDER BY priority DESC,name COLLATE NOCASE LIMIT $max"
        } else {
            val clauses = safe.joinToString(" OR ") { "search_text LIKE ?" }
            "SELECT id,name,grp,logo,url,tvg_name,tvg_id,category,provider FROM fast_lane WHERE source_key=? AND ($clauses) ORDER BY priority DESC,name COLLATE NOCASE LIMIT $max"
        }
        return readableDatabase.rawQuery(sql, arrayOf(sourceKey, *safe.map { "%$it%" }.toTypedArray())).use { c ->
            val out = ArrayList<SportsChannel>(max); val seen = HashSet<String>()
            while (c.moveToNext()) {
                val id = c.getString(0); val url = c.getString(4)
                if (seen.add("$id|$url")) out += SportsChannel(id,c.getString(1),c.getString(2),c.getString(3)?.ifBlank { null },url,c.getString(5),c.getString(6),c.getString(7),c.getString(8))
            }
            out
        }
    }

    fun clear(sourceKey: String) { if (sourceKey.isNotBlank()) writableDatabase.delete("fast_lane","source_key=?",arrayOf(sourceKey)) }

    private data class Classified(val channel: SportsChannel,val region:String,val bucket:String,val priority:Int,val searchText:String)

    private fun classify(c: SportsChannel): Classified? {
        val text = normalize(listOf(c.name,c.tvgName,c.tvgId,c.group,c.category,c.provider).joinToString(" "))
        if (text.isBlank()) return null
        val policy = NorthAmericaSportsStartupPolicy.priority(c)
        val sports = SportsNetworkCatalog.isSportsChannel(c)
        if (!sports && policy == 0) return null
        val region = when {
            any(text,"tsn","sportsnet","rds","tva sports","cbc","ctv","dazn canada","one soccer","canada","canadian") -> "CA"
            any(text,"usa","us sports","united states","american") -> "US"
            else -> "NA"
        }
        val bucket = when {
            any(text,"espn","espn2","espnu","espn news","espn+","espn plus") -> "US_ESPN"
            any(text,"fs1","fs2","fox sports","fox deportes") -> "US_FOX"
            any(text,"cbs sports","cbssn","cbs sports network") -> "US_CBS_SPORTS"
            any(text,"nbc sports","usa network","peacock") -> "US_NBC_USA"
            any(text,"tnt sports","tnt","tbs","trutv","tru tv") -> "US_TNT_TBS"
            exact(text,"abc") || exact(text,"cbs") || exact(text,"nbc") || exact(text,"fox") || exact(text,"cw") || exact(text,"ion") -> "US_BROADCAST"
            any(text,"nfl network","nfl redzone","nfl+") -> "US_NFL"
            any(text,"nba tv","nba league pass") -> "US_NBA"
            any(text,"mlb network","mlb.tv") -> "US_MLB"
            any(text,"nhl network","nhl.tv") -> "US_NHL"
            any(text,"golf channel","tennis channel") -> "US_GOLF_TENNIS"
            any(text,"yes network","msg network","msg","sny","masn","bally sports","fanduel sports network","root sports") -> "US_REGIONAL"
            any(text,"acc network","accn","sec network","secn","big ten network","btn","b1g","big 12","longhorn") -> "US_COLLEGE"
            any(text,"tsn","tsn1","tsn2","tsn3","tsn4","tsn5","tsn+","tsn plus") -> "CA_TSN"
            any(text,"sportsnet","sportsnet one","sportsnet 360","sportsnet east","sportsnet ontario","sportsnet west","sportsnet pacific") -> "CA_SPORTSNET"
            any(text,"rds","rds2","rds info") -> "CA_RDS"
            any(text,"tva sports","tva sport") -> "CA_TVA"
            any(text,"cbc","cbc sports","ctv","ctv2") -> "CA_BROADCAST"
            any(text,"dazn canada","one soccer","onesoccer") -> "CA_SERVICES"
            any(text,"ppv","event","events","event 01","event 02","event 03","event 04","event 05") -> "EVENT_FEED"
            any(text,"dazn","fight network","flosports","flo sports","willow","mls season pass","paramount+","prime video","apple tv","max") -> "SPORTS_SERVICE"
            else -> if (region == "CA") "CA_GENERIC_SPORTS" else "SPORTS_GENERIC"
        }
        val bonus = when (bucket) {
            "US_ESPN","US_FOX","US_CBS_SPORTS","US_NBC_USA","US_TNT_TBS","US_NFL","US_NBA","US_MLB","US_NHL","US_BROADCAST","CA_TSN","CA_SPORTSNET","CA_RDS","CA_TVA","CA_BROADCAST" -> 120
            "US_REGIONAL","US_COLLEGE","CA_SERVICES","SPORTS_SERVICE" -> 90
            else -> 70
        }
        return Classified(c,region,bucket,policy+bonus,text)
    }
    private fun exact(text:String,v:String)=text==v||text.startsWith("$v ")||text.endsWith(" $v")||text.contains(" $v ")
    private fun any(text:String,vararg values:String)=values.any{exact(text,normalize(it))}
    private fun normalize(v:String)=v.lowercase().replace("&"," and ").replace("+"," plus ").replace("fox sports 1","fs1").replace("fox sports 2","fs2").replace("big ten network","btn").replace("sec network","secn").replace("acc network","accn").replace("usa network","usa").replace(Regex("[^a-z0-9]+")," ").trim().replace(Regex("\\s+")," ")
}
