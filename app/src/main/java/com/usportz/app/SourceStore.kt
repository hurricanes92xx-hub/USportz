package com.usportz.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class Channel(val id:String,val name:String,val group:String,val logo:String?,val url:String)

class SourceStore(private val context:Context){
 private val p=context.getSharedPreferences("usportz",Context.MODE_PRIVATE);private val h=Handler(Looper.getMainLooper())
 var server:String get()=p.getString("server","")?:"";private set(v){p.edit().putString("server",v).apply()}
 var user:String get()=p.getString("user","")?:"";private set(v){p.edit().putString("user",v).apply()}
 var pass:String get()=p.getString("pass","")?:"";private set(v){p.edit().putString("pass",v).apply()}
 var playlist:String get()=p.getString("playlist","")?:"";private set(v){p.edit().putString("playlist",v).apply()}
 var channels:List<Channel> = emptyList();private set
 private fun setOf(k:String)=p.getStringSet(k,emptySet())?:emptySet();private fun toggle(k:String,v:String){val n=setOf(k).toMutableSet();if(!n.add(v))n.remove(v);p.edit().putStringSet(k,n).apply()}
 fun isChannelFavorite(v:String)=setOf("fav_channels").contains(v);fun isEventFavorite(v:String)=setOf("fav_events").contains(v);fun favoriteTeams()=setOf("fav_teams");fun favoriteLeagues()=setOf("fav_leagues")
 fun toggleChannelFavorite(v:String)=toggle("fav_channels",v);fun toggleEventFavorite(v:String)=toggle("fav_events",v);fun toggleTeamFavorite(v:String)=toggle("fav_teams",v);fun toggleLeagueFavorite(v:String)=toggle("fav_leagues",v)
 fun saveXtream(base:String,u:String,pw:String,done:(Boolean,String)->Unit){server=base.trimEnd('/');user=u;pass=pw;load("$server/get.php?username=${URLEncoder.encode(u,"UTF-8")}&password=${URLEncoder.encode(pw,"UTF-8")}&type=m3u_plus&output=ts",done)}
 fun saveM3u(v:String,done:(Boolean,String)->Unit){playlist=v;load(v,done)}
 private fun load(v:String,done:(Boolean,String)->Unit){Thread{try{val c=URL(v).openConnection() as HttpURLConnection;c.connectTimeout=10000;c.readTimeout=15000;c.instanceFollowRedirects=true;c.setRequestProperty("User-Agent","USportz/1.0");val t=c.inputStream.bufferedReader().use{it.readText()};c.disconnect();channels=parse(t);h.post{done(true,"Connected • ${channels.size} channels indexed")}}catch(e:Exception){h.post{done(false,"Source error: ${e.message?:"Unable to load source"}")}}}.start()}
 private fun parse(t:String):List<Channel>{val l=t.lineSequence().map{it.trim()}.filter{it.isNotEmpty()}.toList();val r=ArrayList<Channel>(minOf(3000,l.size/2));var a=emptyMap<String,String>();for(x in l){if(x.startsWith("#EXTINF",true))a=attrs(x)else if(!x.startsWith("#")){val n=a["name"]?:x.substringAfterLast('/').substringBefore('?').ifBlank{"Channel"};val g=a["group"]?:"Live TV";r+=Channel((n+"|"+x).hashCode().toString(),n,g,a["logo"],x);a=emptyMap();if(r.size>=3000)break}};return r.distinctBy{it.id}}
 private fun attrs(s:String):Map<String,String>{val m=mutableMapOf<String,String>();Regex("([\\w-]+)=\\\"([^\\\"]*)\\\"").findAll(s).forEach{m[it.groupValues[1].lowercase()]=it.groupValues[2]};val i=s.indexOf(',');if(i>=0)m["name"]=s.substring(i+1).trim();return m}
}
