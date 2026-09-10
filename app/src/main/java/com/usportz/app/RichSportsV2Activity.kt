package com.usportz.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RichSportsV2Activity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { V2App() } }
    fun sources() = startActivity(Intent(this, SourceActivity::class.java))
    fun play(c: SportsChannel) = startActivity(Intent(this, RichPlayerActivity::class.java).putExtra(RichPlayerActivity.EXTRA_URL, c.url))
}

private val V2Bg = Color(0xFF070912)
private val V2Panel = Color(0xFF111625)
private val V2Panel2 = Color(0xFF171D2E)
private val V2Cyan = Color(0xFF35D8FF)
private val V2Purple = Color(0xFF9B63FF)
private val V2Pink = Color(0xFFFF3D91)

@Composable
private fun V2App() {
    val a = androidx.compose.ui.platform.LocalContext.current as RichSportsV2Activity
    val scope = rememberCoroutineScope()
    var channels by remember { mutableStateOf<List<SportsChannel>>(emptyList()) }
    var events by remember { mutableStateOf<List<SportsEvent>>(emptyList()) }
    var sport by remember { mutableStateOf("All") }
    var tab by remember { mutableIntStateOf(0) }
    var selectedEvent by remember { mutableStateOf<SportsEvent?>(null) }
    var matches by remember { mutableStateOf<List<SportsChannel>>(emptyList()) }
    var finding by remember { mutableStateOf(false) }
    var categories by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }

    fun reload(force: Boolean) {
        scope.launch {
            loading = true
            val ch = withContext(Dispatchers.IO) { SportsChannelBridge.load(a, force) }
            val ev = withContext(Dispatchers.IO) { runCatching { SportsSchedule.load(force, ch) }.getOrDefault(emptyList()) }
            val cats = withContext(Dispatchers.Default) {
                ch.asSequence().map(::v2Category).filter { it.isNotBlank() }.groupingBy { it }.eachCount()
                    .entries.sortedWith(compareByDescending<Map.Entry<String,Int>> { it.value }.thenBy { it.key.lowercase() }).take(250).map { it.key }
            }
            channels = ch; events = ev; categories = cats; loading = false
        }
    }
    LaunchedEffect(Unit) { reload(false) }

    fun openEvent(e: SportsEvent) {
        selectedEvent = e; finding = true; matches = emptyList()
        scope.launch { matches = withContext(Dispatchers.Default) { GameSourceMatcher.findMatches(e, channels, 16) }; finding = false }
    }

    val filtered = remember(events, sport) { SportsSchedule.forSport(events, sport) }
    val live = remember(filtered) { SportsSchedule.liveEvents(filtered) }
    val upcoming = remember(filtered) { SportsSchedule.upcomingEvents(filtered) }

    MaterialTheme(colorScheme = darkColorScheme(primary=V2Cyan, secondary=V2Purple, background=V2Bg, surface=V2Panel)) {
        Scaffold(containerColor=V2Bg, bottomBar={ V2Nav(tab) { tab=it } }) { pad ->
            when(tab) {
                0 -> V2Home(loading, channels.size, live, upcoming, sport, {sport=it}, ::openEvent, a::sources, ::reload)
                1 -> V2Sports(sport, {sport=it}, filtered, ::openEvent)
                2 -> V2LiveTv(categories, selectedCategory, {selectedCategory=it}, channels, a::play, loading)
                3 -> V2Favorites(events, channels, ::openEvent)
                else -> V2Sources(a::sources)
            }
        }
    }
    selectedEvent?.let { V2SourceDialog(it, matches, finding, a::play) { selectedEvent=null } }
}

@Composable private fun V2Home(loading:Boolean,count:Int,live:List<SportsEvent>,upcoming:List<SportsEvent>,sport:String,pick:(String)->Unit,open:(SportsEvent)->Unit,sources:()->Unit,reload:(Boolean)->Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(bottom=8.dp),contentPadding=PaddingValues(bottom=26.dp),verticalArrangement=Arrangement.spacedBy(13.dp)) {
        item { V2Header(sources,{reload(true)},loading) }
        item { V2Hero(count,live.size,upcoming.size) }
        item { V2SportRail(sport,pick) }
        item { V2Section("LIVE NOW","${live.size} events",V2Pink) }
        if(live.isEmpty()) item { V2Empty("No live events detected") } else items(live.take(10),key={"live-${it.id}"}) { V2EventCard(it,true,open) }
        item { V2Section("COMING UP","${upcoming.size} events",V2Cyan) }
        items(upcoming.take(18),key={"up-${it.id}"}) { V2EventCard(it,false,open) }
    }
}

@Composable private fun V2Header(sources:()->Unit,refresh:()->Unit,loading:Boolean) { Row(Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(Color(0xFF21183A),Color(0xFF111A30),V2Bg))).padding(18.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween){Column{Text("USPORTZ",color=Color.White,fontSize=32.sp,fontWeight=FontWeight.Black);Text("SPORTS COMMAND CENTER",color=V2Cyan,fontSize=11.sp,fontWeight=FontWeight.Bold,letterSpacing=1.6.sp)};Row{IconButton(onClick=refresh,enabled=!loading){Icon(Icons.Default.Refresh,"Refresh")};IconButton(onClick=sources){Icon(Icons.Default.Settings,"Sources")}}} }

@Composable private fun V2Hero(channels:Int,live:Int,upcoming:Int){Card(Modifier.fillMaxWidth().padding(horizontal=12.dp).height(180.dp),shape=RoundedCornerShape(26.dp),colors=CardDefaults.cardColors(containerColor=V2Panel2)){Row(Modifier.fillMaxSize().padding(22.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("LIVE SPORTS",color=V2Cyan,fontSize=13.sp,fontWeight=FontWeight.Black);Text("Everything worth watching.",color=Color.White,fontSize=27.sp,fontWeight=FontWeight.Black,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(top=6.dp));Text("$live live now  •  $upcoming coming up",color=Color.LightGray,fontSize=14.sp,modifier=Modifier.padding(top=8.dp));Text("$channels Xtream channels ready",color=Color.Gray,fontSize=11.sp,modifier=Modifier.padding(top=5.dp))};Box(Modifier.size(92.dp).clip(RoundedCornerShape(24.dp)).background(Brush.linearGradient(listOf(V2Purple,V2Cyan))),contentAlignment=Alignment.Center){Icon(Icons.Default.SportsScore,null,tint=Color.White,modifier=Modifier.size(50.dp))}}}}

@Composable private fun V2SportRail(selected:String,pick:(String)->Unit){LazyRow(contentPadding=PaddingValues(horizontal=12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){items(SportsCatalog.categories){s->FilterChip(selected==s,onClick={pick(s)},label={Text(s)},leadingIcon={Icon(Icons.Default.SportsScore,null,Modifier.size(16.dp))})}}}

@Composable private fun V2Section(title:String,count:String,accent:Color){Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=2.dp),verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(8.dp).background(accent,CircleShape));Spacer(Modifier.width(8.dp));Text(title,color=Color.White,fontSize=18.sp,fontWeight=FontWeight.Black);Spacer(Modifier.weight(1f));Text(count,color=Color.Gray,fontSize=11.sp,fontWeight=FontWeight.Bold)}}

@Composable private fun V2EventCard(e:SportsEvent,live:Boolean,open:(SportsEvent)->Unit){Card(Modifier.fillMaxWidth().padding(horizontal=12.dp).clickable{open(e)},colors=CardDefaults.cardColors(containerColor=V2Panel),shape=RoundedCornerShape(22.dp)){Column{Row(Modifier.fillMaxWidth().height(74.dp).background(Brush.horizontalGradient(listOf(Color(0xFF1D1730),Color(0xFF102333)))),verticalAlignment=Alignment.CenterVertically){EventLogo(e,0);Spacer(Modifier.width(10.dp));Text(if(live)"LIVE NOW" else "UPCOMING",color=if(live)V2Pink else V2Cyan,fontSize=10.sp,fontWeight=FontWeight.Black);Spacer(Modifier.width(8.dp));Text(e.league,color=Color.LightGray,fontSize=11.sp,maxLines=1,overflow=TextOverflow.Ellipsis)};Column(Modifier.padding(15.dp)){Text(SportsPresentation.matchup(e),color=Color.White,fontSize=19.sp,fontWeight=FontWeight.ExtraBold,maxLines=2,overflow=TextOverflow.Ellipsis);Text(e.detail.ifBlank{"Tap to see available sources"},color=Color(0xFF9DA5B7),fontSize=12.sp,modifier=Modifier.padding(top=5.dp));Row(Modifier.fillMaxWidth().padding(top=10.dp),horizontalArrangement=Arrangement.End){Text("VIEW SOURCES  ›",color=V2Cyan,fontSize=11.sp,fontWeight=FontWeight.Black)}}}}}

@Composable private fun EventLogo(e:SportsEvent,index:Int){val url=e.competitorLogos.getOrNull(index)?.takeIf{it.isNotBlank()}?:e.leagueLogo;Box(Modifier.size(48.dp).clip(CircleShape).background(Color(0xFF22283A)),contentAlignment=Alignment.Center){if(!url.isNullOrBlank())AsyncImage(url,e.league,Modifier.size(40.dp),contentScale=ContentScale.Fit)else Icon(Icons.Default.SportsScore,null,tint=V2Cyan)}}

@Composable private fun V2Sports(selected:String,pick:(String)->Unit,events:List<SportsEvent>,open:(SportsEvent)->Unit){LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(top=16.dp,bottom=28.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){item{Text("SPORTS",color=Color.White,fontSize=28.sp,fontWeight=FontWeight.Black,modifier=Modifier.padding(horizontal=16.dp))};item{V2SportRail(selected,pick)};item{V2Section(selected.uppercase(),"${events.size} events",V2Cyan)};items(events.take(40),key={"sport-${it.id}"}){V2EventCard(it,it.state=="in",open)}}}

@Composable private fun V2LiveTv(categories:List<String>,selected:String?,pick:(String?)->Unit,channels:List<SportsChannel>,play:(SportsChannel)->Unit,loading:Boolean){val visible=remember(selected,channels){if(selected==null)emptyList()else channels.asSequence().filter{v2Category(it)==selected}.take(500).toList()};LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp,bottom=28.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){item{Text("LIVE TV",color=Color.White,fontSize=28.sp,fontWeight=FontWeight.Black)};item{Text(if(loading)"Loading Xtream source…" else "Choose a category to open its channels.",color=Color.Gray)};if(selected!=null)item{TextButton(onClick={pick(null)}){Text("← ALL CATEGORIES")}};if(selected==null)items(categories,key={"cat-$it"}){cat->Card(Modifier.fillMaxWidth().clickable{pick(cat)},colors=CardDefaults.cardColors(containerColor=V2Panel),shape=RoundedCornerShape(18.dp)){Row(Modifier.padding(18.dp),verticalAlignment=Alignment.CenterVertically){Icon(Icons.Default.Folder,null,tint=V2Cyan);Spacer(Modifier.width(12.dp));Text(cat,color=Color.White,fontWeight=FontWeight.Bold);Spacer(Modifier.weight(1f));Icon(Icons.Default.ChevronRight,null,tint=Color.Gray)}}}else{item{V2Section(selected.uppercase(),"${visible.size}+ channels",V2Cyan)};items(visible,key={"tv-${it.id}"}){c->Card(Modifier.fillMaxWidth().clickable{play(c)},colors=CardDefaults.cardColors(containerColor=V2Panel),shape=RoundedCornerShape(16.dp)){Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically){if(!c.logo.isNullOrBlank())AsyncImage(c.logo,c.name,Modifier.size(46.dp),contentScale=ContentScale.Fit)else Icon(Icons.Default.LiveTv,null,tint=V2Cyan,modifier=Modifier.size(34.dp));Spacer(Modifier.width(12.dp));Column(Modifier.weight(1f)){Text(c.name,color=Color.White,fontWeight=FontWeight.Bold,maxLines=1,overflow=TextOverflow.Ellipsis);Text(c.group,color=Color.Gray,fontSize=10.sp,maxLines=1,overflow=TextOverflow.Ellipsis)};Icon(Icons.Default.PlayArrow,null,tint=V2Cyan)}}}}}

@Composable private fun V2Favorites(events:List<SportsEvent>,channels:List<SportsChannel>,open:(SportsEvent)->Unit){val ctx=androidx.compose.ui.platform.LocalContext.current;val favs=remember{Favs(ctx)};val favEvents=remember(events){events.filter{favs.isEventFav(it.id)}.take(50)};val favChannels=remember(channels){channels.filter{favs.isChannelFav(it.id)}.take(100)};LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp,bottom=28.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){item{Text("FAVORITES",color=Color.White,fontSize=28.sp,fontWeight=FontWeight.Black)};if(favEvents.isEmpty()&&favChannels.isEmpty())item{V2Empty("Nothing saved yet")};items(favEvents,key={"fav-${it.id}"}){V2EventCard(it,it.state=="in",open)}}}

@Composable private fun V2Sources(open:()->Unit){Column(Modifier.fillMaxSize().padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){Text("SOURCES",color=Color.White,fontSize=28.sp,fontWeight=FontWeight.Black);Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=V2Panel),shape=RoundedCornerShape(22.dp)){Column(Modifier.padding(20.dp)){Text("XTREAM / M3U",color=V2Cyan,fontWeight=FontWeight.Black);Text("Connect your source to unlock live channels and game sources.",color=Color.Gray,modifier=Modifier.padding(top=7.dp));Button(onClick=open,modifier=Modifier.fillMaxWidth().padding(top=16.dp)){Icon(Icons.Default.Login,null);Spacer(Modifier.width(7.dp));Text("SIGN IN / MANAGE SOURCE")}}}}}

@Composable private fun V2SourceDialog(e:SportsEvent,matches:List<SportsChannel>,finding:Boolean,play:(SportsChannel)->Unit,close:()->Unit){AlertDialog(onDismissRequest=close,confirmButton={},title={Text(SportsPresentation.matchup(e),fontWeight=FontWeight.Black,maxLines=2,overflow=TextOverflow.Ellipsis)},text={Column(Modifier.fillMaxWidth()){Text("AVAILABLE SOURCES",color=V2Cyan,fontSize=11.sp,fontWeight=FontWeight.Black);Spacer(Modifier.height(8.dp));when{finding->Text("Searching your Xtream catalog…",color=Color.Gray);matches.isEmpty()->Text("No matching Xtream channel found.",color=Color.Gray);else->matches.take(12).forEach{c->Row(Modifier.fillMaxWidth().clickable{play(c)}.padding(vertical=9.dp),verticalAlignment=Alignment.CenterVertically){if(!c.logo.isNullOrBlank())AsyncImage(c.logo,c.name,Modifier.size(34.dp),contentScale=ContentScale.Fit)else Icon(Icons.Default.LiveTv,null,tint=V2Cyan);Spacer(Modifier.width(10.dp));Column(Modifier.weight(1f)){Text(c.name,color=Color.White,maxLines=1,overflow=TextOverflow.Ellipsis);Text(c.group,color=Color.Gray,fontSize=9.sp,maxLines=1,overflow=TextOverflow.Ellipsis)};Text("PLAY",color=V2Cyan,fontSize=10.sp,fontWeight=FontWeight.Black)}}}}})}

@Composable private fun V2Nav(selected:Int,onSelect:(Int)->Unit){NavigationBar(containerColor=Color(0xFF10131D)){val nav=listOf(Icons.Default.Home to "Home",Icons.Default.SportsScore to "Sports",Icons.Default.LiveTv to "Live TV",Icons.Default.Star to "Favorites",Icons.Default.Settings to "Sources");nav.forEachIndexed{i,(icon,label)->NavigationBarItem(selected==i,onClick={onSelect(i)},icon={Icon(icon,label)},label={Text(label)})}}}
@Composable private fun V2Empty(text:String){Card(Modifier.fillMaxWidth().padding(12.dp),colors=CardDefaults.cardColors(containerColor=V2Panel),shape=RoundedCornerShape(18.dp)){Text(text,color=Color.Gray,modifier=Modifier.padding(22.dp))}}
private fun v2Category(c:SportsChannel):String{val g=c.group.trim();val n=c.name.trim();val marker=Regex("^##\\s*(.+?)\\s*##$").find(n)?.groupValues?.getOrNull(1);return when{marker!=null->marker.trim();g.isNotBlank()&&!g.equals("live tv",true)->g.removePrefix("##").removeSuffix("##").trim();else->"Uncategorized"}.ifBlank{"Uncategorized"}}
