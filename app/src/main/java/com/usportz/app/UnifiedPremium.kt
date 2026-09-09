package com.usportz.app

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

private enum class Nav(val title:String){HOME("Home"),SPORTS("Sports"),LIVE("Live TV"),FAV("Favorites"),SEARCH("Search"),SOURCES("Sources")}

@Composable
internal fun USportzApp(store:SourceStore){
    var nav by remember{mutableStateOf(Nav.HOME)}
    var sport by remember{mutableStateOf("All")}
    var query by remember{mutableStateOf("")}
    var events by remember{mutableStateOf<List<SportsEvent>>(emptyList())}
    var channels by remember{mutableStateOf<List<SportsChannel>>(emptyList())}
    var loading by remember{mutableStateOf(true)}
    var refresh by remember{mutableIntStateOf(0)}
    val ctx=LocalContext.current
    val fav=remember{Favs(ctx)}
    LaunchedEffect(refresh){
        loading=true
        runCatching{SportsSchedule.load(refresh>0)}.getOrNull()?.let{events=it}
        runCatching{SportsChannelBridge.load(ctx,refresh>0)}.getOrNull()?.let{channels=it}
        loading=false
    }
    fun play(url:String){ctx.startActivity(Intent(ctx,RichPlayerActivity::class.java).putExtra(RichPlayerActivity.EXTRA_URL,url))}
    MaterialTheme(colorScheme=darkColorScheme(primary=Color(0xFFBFA6FF),secondary=Color(0xFF63D7FF),tertiary=Color(0xFFFF5FAF),background=Color(0xFF070912),surface=Color(0xFF111624))){
        Scaffold(containerColor=Color(0xFF070912),bottomBar={
            NavigationBar(containerColor=Color(0xFF0B0F18)){
                Nav.entries.forEach{n->NavigationBarItem(selected=nav==n,onClick={nav=n},icon={Icon(iconFor(n),n.title)},label={Text(n.title)})}
            }
        }){pad->
            LazyColumn(Modifier.fillMaxSize().padding(pad),contentPadding=PaddingValues(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
                item{Header(nav.title,loading,{refresh++},{nav=Nav.SOURCES})}
                when(nav){
                    Nav.HOME->home(events,channels,fav,::play)
                    Nav.SPORTS->sports(events,channels,sport,{sport=it},fav,::play)
                    Nav.LIVE->live(channels,fav,::play)
                    Nav.FAV->favorites(events,channels,fav,::play)
                    Nav.SEARCH->search(query,{query=it},events,channels,fav,::play)
                    Nav.SOURCES->sources(store){refresh++}
                }
            }
        }
    }
}

private fun iconFor(n:Nav)=when(n){Nav.HOME->Icons.Default.Home;Nav.SPORTS->Icons.Default.SportsScore;Nav.LIVE->Icons.Default.LiveTv;Nav.FAV->Icons.Default.Star;Nav.SEARCH->Icons.Default.Search;Nav.SOURCES->Icons.Default.SettingsInputAntenna}
private fun bestChannel(e:SportsEvent,c:List<SportsChannel>):SportsChannel? = c.maxByOrNull { ch -> SportsSchedule.matchChannel(e,ch.name,ch.group) }
private fun LazyListScope.home(e:List<SportsEvent>,c:List<SportsChannel>,f:Favs,play:(String)->Unit){item{Hero(e.firstOrNull{it.state=="in"}?:e.firstOrNull(),c,play)};val live=SportsSchedule.liveEvents(e);if(live.isNotEmpty()){item{Title("LIVE NOW","${live.size} on air")};item{EventRail(live.take(10),c,f,play)}};item{Title("TODAY'S EVENTS","Live schedule")};item{EventRail(SportsSchedule.upcomingEvents(e).take(16),c,f,play)};item{Title("SPORTS","Browse all")};item{SportRail()};item{Title("WRESTLING HUB","WWE • AEW • TNA • ROH")};item{WrestlingRail()};item{Title("LIVE TV","Indexed channels")};item{ChannelRail(c.take(12),f,play)}}
private fun LazyListScope.sports(e:List<SportsEvent>,c:List<SportsChannel>,sel:String,set:(String)->Unit,f:Favs,play:(String)->Unit){item{SportRail(sel,set)};if(sel=="Wrestling")item{WrestlingRail()};val x=SportsSchedule.forSport(e,sel);val live=SportsSchedule.liveEvents(x);if(live.isNotEmpty()){item{Title("LIVE","$sel now")};item{EventRail(live,c,f,play)}};item{Title("UPCOMING","$sel schedule")};item{EventRail(SportsSchedule.upcomingEvents(x),c,f,play)}}
private fun LazyListScope.live(c:List<SportsChannel>,f:Favs,play:(String)->Unit){item{Title("LIVE TV","${c.size} channels")};if(c.isEmpty())item{Empty("No channels loaded","Connect a source in Sources.")}else items(c,key={it.id}){ChannelRow(it,f.isChannelFav(it.id),{play(it.url)},{f.toggleChannel(it.id)})}}
private fun LazyListScope.favorites(e:List<SportsEvent>,c:List<SportsChannel>,f:Favs,play:(String)->Unit){item{Title("FAVORITES","Saved on this device")};val fe=e.filter{f.isEventFav(it.id)};val fc=c.filter{f.isChannelFav(it.id)};if(fe.isNotEmpty()){item{Title("EVENTS","Saved games")};item{EventRail(fe,c,f,play)}};if(fc.isNotEmpty()){item{Title("CHANNELS","Saved channels")};item{ChannelRail(fc,f,play)}};if(fe.isEmpty()&&fc.isEmpty())item{Empty("Nothing saved yet","Star an event or channel to build Favorites.")}}
private fun LazyListScope.search(q:String,set:(String)->Unit,e:List<SportsEvent>,c:List<SportsChannel>,f:Favs,play:(String)->Unit){item{SearchBox(q,set)};if(q.isBlank())item{Text("Search teams • events • leagues • channels • sports",color=Color.Gray,modifier=Modifier.padding(horizontal=16.dp))}else{val ev=e.filter{it.name.contains(q,true)||it.shortName.contains(q,true)}.take(20);val teams=e.flatMap{it.competitors}.distinct().filter{it.contains(q,true)}.take(12);val leagues=e.map{it.league}.distinct().filter{it.contains(q,true)}.take(12);val ch=c.filter{it.name.contains(q,true)||it.group.contains(q,true)}.take(40);item{Results("TEAMS",teams)};item{Results("EVENTS",ev.map{SportsPresentation.matchup(it)})};item{Results("LEAGUES",leagues)};item{Title("CHANNELS","${ch.size} matches")};items(ch,key={"search-${it.id}"}){ChannelRow(it,f.isChannelFav(it.id),{play(it.url)},{f.toggleChannel(it.id)})}}}
private fun LazyListScope.sources(s:SourceStore,done:()->Unit){item{Title("SOURCES","Xtream Codes + M3U/M3U8")};item{Editor(s,done)};item{Text("Passwords remain local and are never bundled.",color=Color.Gray,fontSize=11.sp,modifier=Modifier.padding(horizontal=16.dp))}}
@Composable private fun Header(t:String,l:Boolean,r:()->Unit,src:()->Unit){Row(Modifier.fillMaxWidth().padding(15.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("USPORTZ",fontSize=29.sp,fontWeight=FontWeight.Black,letterSpacing=2.sp);Text(t.uppercase(),fontSize=11.sp,color=Color.Gray)};IconButton(r,enabled=!l){Icon(Icons.Default.Refresh,"Refresh")};IconButton(src){Icon(Icons.Default.SettingsInputAntenna,"Sources")}}}
@Composable private fun Title(a:String,b:String){Text("$a  $b",fontSize=17.sp,fontWeight=FontWeight.Black,modifier=Modifier.padding(horizontal=14.dp))}
@Composable private fun Hero(e:SportsEvent?,c:List<SportsChannel>,play:(String)->Unit){Box(Modifier.fillMaxWidth().padding(horizontal=12.dp).height(210.dp).clip(RoundedCornerShape(24.dp)).background(Brush.linearGradient(listOf(Color(0xFF281744),Color(0xFF0B2638))))){Column(Modifier.align(Alignment.BottomStart).padding(20.dp)){Text(if(e?.state=="in")"LIVE NOW"else"FEATURED",color=Color(0xFFFF5FAF),fontWeight=FontWeight.Black);Text(e?.let{SportsPresentation.matchup(it)}?:"Your sports command center",fontSize=24.sp,fontWeight=FontWeight.Black,maxLines=2,overflow=TextOverflow.Ellipsis);Text(e?.league?:"Connect a source",color=Color.LightGray);val b=e?.let{bestChannel(it,c)};val score=if(e!=null&&b!=null)SportsSchedule.matchChannel(e,b.name,b.group)else 0;if(e!=null&&b!=null&&score>0)Text("WATCH LIVE  ›",color=Color(0xFF63D7FF),fontWeight=FontWeight.Black,modifier=Modifier.padding(top=8.dp).clickable{play(b.url)})}}}
@Composable private fun EventRail(e:List<SportsEvent>,c:List<SportsChannel>,f:Favs,play:(String)->Unit){LazyRow(contentPadding=PaddingValues(horizontal=12.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)){items(e,key={it.id}){EventCard(it,c,f,play)}}}
@Composable private fun EventCard(e:SportsEvent,c:List<SportsChannel>,f:Favs,play:(String)->Unit){val b=bestChannel(e,c);val score=b?.let{SportsSchedule.matchChannel(e,it.name,it.group)}?:0;Card(Modifier.width(280.dp),shape=RoundedCornerShape(17.dp),colors=CardDefaults.cardColors(containerColor=Color(0xFF121522))){Column(Modifier.padding(13.dp)){Row(verticalAlignment=Alignment.CenterVertically){AsyncImage(e.leagueLogo,e.league,Modifier.size(38.dp),contentScale=ContentScale.Fit);Column(Modifier.weight(1f).padding(horizontal=8.dp)){Text(e.league,fontSize=10.sp,color=Color.Gray);Text(e.shortName,fontWeight=FontWeight.Bold,maxLines=1,overflow=TextOverflow.Ellipsis)};IconButton({f.toggleEvent(e.id)},Modifier.size(34.dp)){Icon(if(f.isEventFav(e.id))Icons.Default.Star else Icons.Default.StarBorder,"Favorite")}};Text(e.competitors.joinToString("  •  "),fontWeight=FontWeight.Bold,maxLines=2,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(top=9.dp));Text(if(e.state=="in")"● LIVE • ${e.detail}"else e.detail.ifBlank{"Scheduled"},color=if(e.state=="in")Color(0xFFFF5FAF)else Color.Gray,fontSize=10.sp,modifier=Modifier.padding(top=7.dp));if(b!=null&&score>0)Button(onClick={play(b.url)},modifier=Modifier.fillMaxWidth().padding(top=7.dp)){Icon(Icons.Default.PlayArrow,null);Text(if(e.state=="in")"WATCH LIVE"else"WATCH")}}}}
@Composable private fun SportRail(sel:String="All",set:(String)->Unit={}){LazyRow(contentPadding=PaddingValues(horizontal=12.dp),horizontalArrangement=Arrangement.spacedBy(7.dp)){items(SportsCatalog.categories){FilterChip(selected=sel==it,onClick={set(it)},label={Text(it)})}}}
@Composable private fun WrestlingRail(){LazyRow(contentPadding=PaddingValues(horizontal=12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf("WWE","AEW","TNA","ROH").forEach{Card(Modifier.width(150.dp),shape=RoundedCornerShape(15.dp)){Column(Modifier.padding(18.dp)){Text(it,fontSize=20.sp,fontWeight=FontWeight.Black);Text("OPEN HUB",color=Color(0xFFBFA6FF),fontSize=10.sp,modifier=Modifier.padding(top=7.dp))}}}}}
@Composable private fun ChannelRail(c:List<SportsChannel>,f:Favs,play:(String)->Unit){LazyRow(contentPadding=PaddingValues(horizontal=12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){items(c,key={it.id}){x->ChannelRow(x,f.isChannelFav(x.id),{play(x.url)},{f.toggleChannel(x.id)})}}}
@Composable private fun ChannelRow(c:SportsChannel,f:Boolean,play:()->Unit,fav:()->Unit){Card(Modifier.fillMaxWidth().padding(horizontal=12.dp),shape=RoundedCornerShape(14.dp),colors=CardDefaults.cardColors(containerColor=Color(0xFF111624))){Row(Modifier.padding(10.dp),verticalAlignment=Alignment.CenterVertically){AsyncImage(c.logo,c.name,Modifier.size(48.dp),contentScale=ContentScale.Fit);Column(Modifier.weight(1f).padding(horizontal=9.dp)){Text(c.name,fontWeight=FontWeight.Bold,maxLines=1,overflow=TextOverflow.Ellipsis);Text(c.group,color=Color.Gray,fontSize=10.sp)};IconButton(fav){Icon(if(f)Icons.Default.Star else Icons.Default.StarBorder,"Favorite")};FilledTonalButton(onClick=play){Icon(Icons.Default.PlayArrow,null)}}}}
@Composable private fun SearchBox(v:String,set:(String)->Unit){OutlinedTextField(value=v,onValueChange=set,modifier=Modifier.fillMaxWidth().padding(horizontal=12.dp),label={Text("Search everything")},leadingIcon={Icon(Icons.Default.Search,null)},singleLine=true,shape=RoundedCornerShape(15.dp))}
@Composable private fun Results(t:String,v:List<String>){if(v.isNotEmpty())Column{Title(t,"${v.size} matches");v.forEach{x->Card(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=2.dp)){Text(x,Modifier.padding(13.dp))}}}}
@Composable private fun Empty(a:String,b:String){Card(Modifier.fillMaxWidth().padding(12.dp)){Column(Modifier.padding(18.dp)){Text(a,fontWeight=FontWeight.Bold);Text(b,color=Color.Gray,fontSize=12.sp,modifier=Modifier.padding(top=4.dp))}}}
@Composable private fun Editor(s:SourceStore,done:()->Unit){var server by remember{mutableStateOf(s.server)};var user by remember{mutableStateOf(s.user)};var pass by remember{mutableStateOf(s.pass)};var m3u by remember{mutableStateOf(s.playlist)};var busy by remember{mutableStateOf(false)};var status by remember{mutableStateOf("")};Card(Modifier.fillMaxWidth().padding(12.dp),shape=RoundedCornerShape(18.dp)){Column(Modifier.padding(15.dp)){Text("XTREAM CODES",fontWeight=FontWeight.Black);OutlinedTextField(server,{server=it},Modifier.fillMaxWidth(),label={Text("Server")},singleLine=true);OutlinedTextField(user,{user=it},Modifier.fillMaxWidth().padding(top=6.dp),label={Text("Username")},singleLine=true);OutlinedTextField(pass,{pass=it},Modifier.fillMaxWidth().padding(top=6.dp),label={Text("Password")},singleLine=true);Button(enabled=!busy&&server.isNotBlank()&&user.isNotBlank()&&pass.isNotBlank(),onClick={busy=true;s.saveXtream(server,user,pass){ok,msg->busy=false;status=msg;if(ok)done()}},modifier=Modifier.fillMaxWidth().padding(top=8.dp)){Text(if(busy)"CONNECTING…"else"CONNECT XTREAM")};HorizontalDivider(Modifier.padding(vertical=13.dp));Text("M3U / M3U8",fontWeight=FontWeight.Black);OutlinedTextField(m3u,{m3u=it},Modifier.fillMaxWidth().padding(top=6.dp),label={Text("Playlist URL")},singleLine=true);Button(enabled=!busy&&m3u.isNotBlank(),onClick={busy=true;s.saveM3u(m3u){ok,msg->busy=false;status=msg;if(ok)done()}},modifier=Modifier.fillMaxWidth().padding(top=8.dp)){Text(if(busy)"LOADING…"else"LOAD PLAYLIST")};if(status.isNotBlank())Text(status,color=Color.Gray,fontSize=12.sp,modifier=Modifier.padding(top=8.dp))}}}
private class Favs(c:Context){private val p=c.getSharedPreferences("usportz_favs",Context.MODE_PRIVATE);private fun set(k:String)=p.getStringSet(k,emptySet())?:emptySet();private fun t(k:String,v:String){val n=set(k).toMutableSet();if(!n.add(v))n.remove(v);p.edit().putStringSet(k,n).apply()};fun isChannelFav(v:String)=set("channels").contains(v);fun isEventFav(v:String)=set("events").contains(v);fun toggleChannel(v:String)=t("channels",v);fun toggleEvent(v:String)=t("events",v);fun toggleTeam(v:String)=t("teams",v);fun toggleLeague(v:String)=t("leagues",v)}