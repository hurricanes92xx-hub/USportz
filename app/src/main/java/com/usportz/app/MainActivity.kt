package com.usportz.app

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

private data class Channel(val id:String,val name:String,val group:String,val logo:String?,val url:String)
private data class SportEvent(val title:String,val league:String,val time:String,val sport:String,val live:Boolean=false)

class MainActivity : ComponentActivity() {
    private val store by lazy { SourceStore(this) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { USportzApp(store) }
    }
}

@Composable
private fun USportzApp(store: SourceStore) {
    var tab by remember { mutableIntStateOf(0) }
    var settings by remember { mutableStateOf(false) }
    var playerUrl by remember { mutableStateOf<String?>(null) }
    var channels by remember { mutableStateOf(store.channels) }
    var favorites by remember { mutableStateOf(store.favorites) }
    val events = remember { sampleEvents() }

    MaterialTheme(colorScheme = darkColorScheme(primary=Color(0xFF48B9FF), background=Color(0xFF070B10), surface=Color(0xFF101820))) {
        when {
            playerUrl != null -> PlayerScreen(playerUrl!!) { playerUrl=null }
            settings -> SettingsScreen(store, { channels=store.channels; settings=false }, { settings=false })
            else -> Scaffold(bottomBar={
                NavigationBar { listOf("Home","Sports","Live TV","Favorites","Search").forEachIndexed { i,label ->
                    NavigationBarItem(selected=tab==i,onClick={tab=i},icon={Icon(if(i==0)Icons.Default.Home else if(i==1)Icons.Default.SportsFootball else if(i==2)Icons.Default.LiveTv else if(i==3)Icons.Default.Star else Icons.Default.Search,label)},label={Text(label)})
                }}
            }) { pad ->
                LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal=16.dp)) {
                    item { Header { settings=true } }
                    when(tab) {
                        0 -> { item { HeroCard(events.first()) { } }; item { Section("Live & Upcoming") }; items(events.drop(1)) { EventCard(it, false) {} }; item { Section("Your Channels") }; if(channels.isEmpty()) item { EmptyCard("No source loaded","Open Settings to connect Xtream Codes or an M3U/M3U8 playlist.") } else items(channels.take(8)) { c -> ChannelCard(c){playerUrl=c.url} } }
                        1 -> { item { SportFilters() }; items(events) { EventCard(it,false){} } }
                        2 -> { item { Section("Live TV") }; if(channels.isEmpty()) item { EmptyCard("No channels yet","Connect a source in Settings.") } else items(channels) { c -> ChannelCard(c){playerUrl=c.url} } }
                        3 -> { item { Section("Favorites") }; val fs=channels.filter{favorites.contains(it.id)}; if(fs.isEmpty()) item{EmptyCard("No favorites","Star a channel to keep it here.")} else items(fs){c->ChannelCard(c){playerUrl=c.url}} }
                        4 -> { item { SearchBox(channels){ } }; item{Text("Search your loaded channels",color=Color.Gray,modifier=Modifier.padding(top=12.dp))} }
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}

@Composable private fun Header(openSettings:()->Unit){ Row(Modifier.fillMaxWidth().padding(top=18.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween){ Column{Text("USportz",fontSize=30.sp,fontWeight=FontWeight.ExtraBold);Text("Sports command center",color=Color.Gray)};IconButton(openSettings){Icon(Icons.Default.Settings,"Settings")} } }
@Composable private fun Section(s:String){Text(s,fontSize=21.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=22.dp,bottom=10.dp))}
@Composable private fun HeroCard(e:SportEvent,onClick:()->Unit){Card(Modifier.fillMaxWidth().padding(top=16.dp).clickable{onClick()},shape=RoundedCornerShape(20.dp)){Column(Modifier.padding(20.dp)){Text(if(e.live)"LIVE NOW" else "FEATURED",color=Color(0xFF65C9FF),fontWeight=FontWeight.Bold);Text(e.title,fontSize=25.sp,fontWeight=FontWeight.ExtraBold,modifier=Modifier.padding(top=5.dp));Text(e.league+" • "+e.time,color=Color.Gray);Button(onClick=onClick,modifier=Modifier.padding(top=14.dp)){Text("Open")}}}}
@Composable private fun EventCard(e:SportEvent,favorite:Boolean,onClick:()->Unit){Card(Modifier.fillMaxWidth().padding(vertical=5.dp).clickable{onClick()},shape=RoundedCornerShape(14.dp)){Row(Modifier.padding(14.dp),verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(46.dp).background(Color(0xFF183344),RoundedCornerShape(12.dp)),contentAlignment=Alignment.Center){Icon(Icons.Default.Sports,"")};Spacer(Modifier.width(12.dp));Column(Modifier.weight(1f)){Text(e.title,fontWeight=FontWeight.Bold);Text(e.league+" • "+e.sport,color=Color.Gray,fontSize=13.sp)};Text(e.time,fontSize=12.sp);Icon(if(favorite)Icons.Default.Star else Icons.Default.StarBorder,"Favorite")}}}
@Composable private fun ChannelCard(c:Channel,onClick:()->Unit){Card(Modifier.fillMaxWidth().padding(vertical=4.dp).clickable{onClick()},shape=RoundedCornerShape(12.dp)){Row(Modifier.padding(14.dp),verticalAlignment=Alignment.CenterVertically){Icon(Icons.Default.LiveTv,"",Modifier.size(32.dp));Spacer(Modifier.width(12.dp));Column(Modifier.weight(1f)){Text(c.name,fontWeight=FontWeight.SemiBold);Text(c.group,color=Color.Gray,fontSize=12.sp)};Icon(Icons.Default.PlayArrow,"Play")}}}
@Composable private fun EmptyCard(a:String,b:String){Card(Modifier.fillMaxWidth().padding(vertical=8.dp)){Column(Modifier.padding(20.dp)){Text(a,fontWeight=FontWeight.Bold);Text(b,color=Color.Gray,modifier=Modifier.padding(top=5.dp))}}}
@Composable private fun SportFilters(){LazyRow{items(listOf("All","Football","Basketball","Baseball","Hockey","Soccer","MMA","Wrestling")){Text(it,modifier=Modifier.padding(end=8.dp).background(Color(0xFF18232D),RoundedCornerShape(30.dp)).padding(horizontal=16.dp,vertical=10.dp),color=Color.White)}}}
@Composable private fun SearchBox(channels:List<Channel>,onQuery:(String)->Unit){var q by remember{mutableStateOf("")};OutlinedTextField(q,{q=it;onQuery(it)},label={Text("Search channels")},singleLine=true,modifier=Modifier.fillMaxWidth().padding(top=16.dp))}

@Composable private fun SettingsScreen(store:SourceStore,onDone:()->Unit,onBack:()->Unit){
    var server by remember{mutableStateOf(store.server)};var user by remember{mutableStateOf(store.user)};var pass by remember{mutableStateOf(store.pass)};var playlist by remember{mutableStateOf(store.playlist)};var status by remember{mutableStateOf("")};var loading by remember{mutableStateOf(false)}
    Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState())){
        Row(verticalAlignment=Alignment.CenterVertically){IconButton(onBack){Icon(Icons.Default.ArrowBack,"Back")};Text("Sources",fontSize=27.sp,fontWeight=FontWeight.Bold)}
        Text("Xtream Codes",fontSize=20.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=18.dp));
        OutlinedTextField(server,{server=it},label={Text("Server URL")},modifier=Modifier.fillMaxWidth());OutlinedTextField(user,{user=it},label={Text("Username")},modifier=Modifier.fillMaxWidth().padding(top=8.dp));OutlinedTextField(pass,{pass=it},label={Text("Password")},modifier=Modifier.fillMaxWidth().padding(top=8.dp));
        Button(enabled=!loading,onClick={loading=true;status="Connecting…";store.saveXtream(server,user,pass){ok,msg->loading=false;status=msg;if(ok)onDone()}},modifier=Modifier.padding(top=10.dp)){Text(if(loading)"Loading…" else "Connect Xtream")}
        HorizontalDivider(Modifier.padding(vertical=22.dp));Text("M3U / M3U8",fontSize=20.sp,fontWeight=FontWeight.Bold);OutlinedTextField(playlist,{playlist=it},label={Text("Playlist URL")},modifier=Modifier.fillMaxWidth());Button(enabled=!loading,onClick={loading=true;status="Loading playlist…";store.saveM3u(playlist){ok,msg->loading=false;status=msg;if(ok)onDone()}},modifier=Modifier.padding(top=10.dp)){Text("Load playlist")}
        if(status.isNotBlank())Text(status,color=Color.Gray,modifier=Modifier.padding(top=12.dp));Text("Sources and credentials are stored locally on this device.",color=Color.Gray,fontSize=12.sp,modifier=Modifier.padding(top=18.dp))
    }
}

@Composable private fun PlayerScreen(url:String,onBack:()->Unit){val ctx=androidx.compose.ui.platform.LocalContext.current;val player=remember(url){ExoPlayer.Builder(ctx).build().apply{setMediaItem(MediaItem.fromUri(url));prepare();playWhenReady=true}};DisposableEffect(player){onDispose{player.release()}};Box(Modifier.fillMaxSize().background(Color.Black)){AndroidView({PlayerView(it).apply{this.player=player}},Modifier.fillMaxSize());IconButton(onBack,Modifier.align(Alignment.TopStart).padding(12.dp)){Icon(Icons.Default.ArrowBack,"Back",tint=Color.White)}}}

private class SourceStore(private val context:Context){
 private val main=Handler(Looper.getMainLooper())
 private val p=context.getSharedPreferences("usportz",Context.MODE_PRIVATE);var server:String get()=p.getString("server","")?:"";private set(v){p.edit().putString("server",v).apply()};var user:String get()=p.getString("user","")?:"";private set(v){p.edit().putString("user",v).apply()};var pass:String get()=p.getString("pass","")?:"";private set(v){p.edit().putString("pass",v).apply()};var playlist:String get()=p.getString("playlist","")?:"";private set(v){p.edit().putString("playlist",v).apply()};var channels:List<Channel> = emptyList();val favorites:Set<String> get()=p.getStringSet("favorites",emptySet())?:emptySet()
 fun saveXtream(base:String,u:String,pw:String,done:(Boolean,String)->Unit){server=base.trimEnd('/');user=u;pass=pw;val url=server+"/get.php?username="+URLEncoder.encode(u,"UTF-8")+"&password="+URLEncoder.encode(pw,"UTF-8")+"&type=m3u_plus&output=ts";load(url,done)}
 fun saveM3u(url:String,done:(Boolean,String)->Unit){playlist=url;load(url,done)}
 private fun load(url:String,done:(Boolean,String)->Unit){Thread{try{val text=download(url);val parsed=parseM3u(text);if(parsed.isEmpty()) main.post{done(false,"No playable channels found")} else {channels=parsed;main.post{done(true,"Loaded ${parsed.size} channels")}}}catch(e:Exception){main.post{done(false,"Source error: ${e.message?:"unknown"}")}}}.start()}
 private fun download(u:String):String{val c=URL(u).openConnection() as HttpURLConnection;c.connectTimeout=10000;c.readTimeout=20000;c.requestMethod="GET";c.instanceFollowRedirects=true;return c.inputStream.bufferedReader().use{it.readText()}.also{c.disconnect()}}
}

private fun parseM3u(text:String):List<Channel>{val lines=text.lines();val out=mutableListOf<Channel>();var name="";var group="";var logo:String?=null;for(line0 in lines){val line=line0.trim();if(line.startsWith("#EXTINF",true)){name=line.substringAfter(",","Channel").trim();group=Regex("group-title=\"([^\"]*)\"").find(line)?.groupValues?.get(1).orEmpty();logo=Regex("tvg-logo=\"([^\"]*)\"").find(line)?.groupValues?.get(1)}else if(line.isNotBlank()&&!line.startsWith("#")&&(line.startsWith("http://")||line.startsWith("https://"))){out+=Channel((name+"|"+line).hashCode().toString(),name.ifBlank{"Channel"},group.ifBlank{"Uncategorized"},logo,line);name="";group="";logo=null;if(out.size>=3000)break}};return out}
private fun sampleEvents()=listOf(SportEvent("Live Sports Center","Featured","NOW","All",true),SportEvent("College Football","NCAA","Tonight","Football"),SportEvent("NBA","Basketball","Tonight","Basketball"),SportEvent("NHL","Hockey","Tonight","Hockey"),SportEvent("UFC Fight Night","UFC","Sat 8:00 PM","MMA"),SportEvent("WWE Raw","WWE","Mon 8:00 PM","Wrestling"))
