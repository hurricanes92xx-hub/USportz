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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

/** Premium sports-first mobile dashboard. TV users have their own D-pad-first activity. */
class RichSportsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { PremiumSportsApp() } }
    fun openSourceApp() = startActivity(Intent(this, SourceActivity::class.java))
    fun playChannel(channel: SportsChannel) = startActivity(Intent(this, RichPlayerActivity::class.java).putExtra(RichPlayerActivity.EXTRA_URL, channel.url))
    fun playEvent(event: SportsEvent) { if (isMonsterJamEvent(event)) openMonsterJamYouTube(this) }
}

private val UBg = Color(0xFF070B12)
private val USurface = Color(0xFF101925)
private val USurface2 = Color(0xFF172333)
private val ULine = Color(0xFF26384A)
private val UBlue = Color(0xFF35D6FF)
private val ULive = Color(0xFFFF405B)
private val UAmber = Color(0xFFFFB547)

private enum class UTab(val label: String) { HOME("Home"), SPORTS("Sports"), CHANNELS("Sports TV"), SAVED("My Stuff"), SOURCES("Sources") }

@Composable private fun PremiumSportsApp() {
    val activity = androidx.compose.ui.platform.LocalContext.current as RichSportsActivity
    val prefs = remember { activity.getSharedPreferences("usportz_ui", 0) }
    val saved = remember { mutableStateMapOf<String, Boolean>().apply { prefs.all.filterValues { it == true }.keys.forEach { put(it, true) } } }
    var events by remember { mutableStateOf(emptyList<SportsEvent>()) }
    var channels by remember { mutableStateOf(emptyList<SportsChannel>()) }
    var tab by remember { mutableStateOf(UTab.HOME) }
    var sport by remember { mutableStateOf("All") }
    var search by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var refreshToken by remember { mutableIntStateOf(0) }
    var chosen by remember { mutableStateOf<SportsEvent?>(null) }

    suspend fun reload(force: Boolean) {
        refreshing = force; loading = events.isEmpty(); error = ""
        try {
            val pair = coroutineScope {
                val c = async(Dispatchers.IO) { SportsChannelBridge.load(activity, force) }
                val e = async(Dispatchers.IO) { (SportsSchedule.load(force) + MonsterJamSchedule.load()).distinctBy { it.id } }
                c.await() to e.await()
            }
            channels = pair.first; events = pair.second
        } catch (t: Throwable) { error = t.message?.take(160) ?: "Sports data is temporarily unavailable" }
        finally { loading = false; refreshing = false; now = System.currentTimeMillis() }
    }
    LaunchedEffect(Unit) {
        reload(false)
        while (true) { delay(45_000); reload(false) }
    }
    LaunchedEffect(refreshToken) { if (refreshToken > 0) reload(true) }

    val filtered = remember(events, sport, search) {
        events.filter { sport == "All" || SportsCatalog.classify(it.name, it.league).equals(sport, true) || (sport == "Motorsports" && SportsCatalog.classify(it.name, it.league) == "Racing") }.filter { search.isBlank() || listOf(it.name,it.league,it.detail,it.competitors.joinToString()).any { s -> s.contains(search, true) } }
    }
    val live = filtered.filter { it.state == "in" || (epoch(it.startTime)?.let { t -> t <= now && now-t < 6*3600000L } == true && it.state != "post") }
    val upcoming = filtered.filter { it.state != "in" && (epoch(it.startTime) ?: 0L) > now }.sortedBy { epoch(it.startTime) ?: Long.MAX_VALUE }
    val today = upcoming.filter { epoch(it.startTime)?.let { t -> Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()).toLocalDate() == Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate() } == true }

    MaterialTheme(colorScheme = darkColorScheme(primary=UBlue, secondary=UAmber, background=UBg, surface=USurface)) {
        Scaffold(containerColor=UBg, bottomBar={
            NavigationBar(containerColor=Color(0xFF0B111A), tonalElevation=0.dp) {
                UTab.values().forEach { t -> NavigationBarItem(selected=tab==t,onClick={tab=t;search=""},icon={Icon(when(t){UTab.HOME->Icons.Default.Home;UTab.SPORTS->Icons.Default.SportsScore;UTab.CHANNELS->Icons.Default.LiveTv;UTab.SAVED->Icons.Default.Bookmark;UTab.SOURCES->Icons.Default.SettingsInputAntenna},null)},label={Text(t.label,fontSize=10.sp)},colors=NavigationBarItemDefaults.colors(selectedIconColor=UBlue,selectedTextColor=UBlue,indicatorColor=USurface2)) }
            }
        }) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding),contentPadding=PaddingValues(bottom=26.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
                item { UHeader(tab,refreshing){refreshToken++} }
                if(tab!=UTab.SOURCES) item { USearch(search,{search=it}) }
                when(tab) {
                    UTab.HOME -> {
                        item { UHero(live.firstOrNull(),upcoming.firstOrNull(),channels.size,loading,activity::playChannel){ chosen=it } }
                        item { USection("LIVE RIGHT NOW",live.size,"LIVE",ULive) }
                        if(live.isEmpty()) item { UEmpty(if(loading)"Finding live games…" else "No live games at the moment","Your schedule updates automatically. Browse upcoming games below.") }
                        else items(live.take(12),key={"live-${it.id}"}) { UEventCard(it,true,channels,now,saved,{id,v->saved[id]=v;prefs.edit().putBoolean(id,v).apply()},activity::playChannel){chosen=it} }
                        item { USection("STARTING SOON",upcoming.size,"NEXT",UBlue) }
                        if(upcoming.isEmpty()) item { UEmpty("Nothing upcoming","Try another sport or refresh the schedule.") }
                        else items(upcoming.take(14),key={"next-${it.id}"}) { UEventCard(it,false,channels,now,saved,{id,v->saved[id]=v;prefs.edit().putBoolean(id,v).apply()},activity::playChannel){chosen=it} }
                        item { USection("BROWSE SPORTS",SportsCatalog.categories.size,"EXPLORE",UAmber) }
                        item { USportTiles(sport){sport=it;tab=UTab.SPORTS} }
                        item { USection("SPORTS NETWORKS",channels.size,"TV",UBlue) }
                        item { UChannelRail(channels,activity::playChannel) }
                    }
                    UTab.SPORTS -> {
                        item { USportRail(sport){sport=it} }
                        item { USection(if(sport=="All")"ALL EVENTS" else sport.uppercase(),filtered.size,"SCHEDULE",UBlue) }
                        if(filtered.isEmpty()) item { UEmpty("No events found","Try another sport or search.") }
                        else items(filtered.take(80),key={"schedule-${it.id}"}) { UEventCard(it,it.state=="in",channels,now,saved,{id,v->saved[id]=v;prefs.edit().putBoolean(id,v).apply()},activity::playChannel){chosen=it} }
                    }
                    UTab.CHANNELS -> {
                        item { USection("SPORTS TV",channels.size,"CHANNELS",UBlue) }
                        items(channels.filter { search.isBlank() || it.name.contains(search,true) }.take(160),key={"ch-${it.id}-${it.url}"}) { c -> UChannelRow(c,activity::playChannel) }
                    }
                    UTab.SAVED -> {
                        val favorites=filtered.filter{saved[it.id]==true}
                        item { USection("SAVED EVENTS",favorites.size,"BOOKMARKS",UAmber) }
                        if(favorites.isEmpty()) item { UEmpty("Your sports, saved","Tap the bookmark on an event to keep it here.") }
                        else items(favorites,key={"fav-${it.id}"}) { UEventCard(it,it.state=="in",channels,now,saved,{id,v->saved[id]=v;prefs.edit().putBoolean(id,v).apply()},activity::playChannel){chosen=it} }
                    }
                    UTab.SOURCES -> item { USourcePanel { activity.openSourceApp() } }
                }
                if(error.isNotBlank()) item { UEmpty("Schedule update paused",error) }
            }
        }
        chosen?.let { ev -> UEventDialog(ev,channels,activity::playChannel){chosen=null} }
    }
}

@Composable private fun UHeader(tab:UTab,refreshing:Boolean,refresh:()->Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("USPORTZ",color=Color.White,fontSize=26.sp,fontWeight=FontWeight.Black,letterSpacing=(-1).sp)
            Text("YOUR SPORTS. ONE PLACE.",color=UBlue,fontSize=9.sp,fontWeight=FontWeight.Bold,letterSpacing=1.7.sp)
        }
        Box(Modifier.background(USurface2,RoundedCornerShape(50)).padding(horizontal=10.dp,vertical=6.dp)) { Row(verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(6.dp).background(if(refreshing)UAmber else UBlue,RoundedCornerShape(50)));Spacer(Modifier.width(6.dp));Text(if(refreshing)"SYNCING" else "LIVE DATA",color=Color.LightGray,fontSize=9.sp,fontWeight=FontWeight.Bold)} }
        IconButton(onClick=refresh,enabled=!refreshing){Icon(Icons.Default.Refresh,"Refresh",tint=UBlue)}
    }
}
@Composable private fun USearch(value:String,onChange:(String)->Unit) {
    OutlinedTextField(value,onChange,Modifier.fillMaxWidth().padding(horizontal=18.dp),singleLine=true,placeholder={Text("Search teams, leagues, events…",fontSize=13.sp)},leadingIcon={Icon(Icons.Default.Search,null,tint=UBlue)},shape=RoundedCornerShape(16.dp),colors=OutlinedTextFieldDefaults.colors(focusedBorderColor=UBlue,unfocusedBorderColor=ULine,focusedContainerColor=USurface,unfocusedContainerColor=USurface))
}
@Composable private fun UHero(event:SportsEvent?,next:SportsEvent?,count:Int,loading:Boolean,play:(SportsChannel)->Unit,open:(SportsEvent)->Unit) {
    val featured=event?:next
    Card(Modifier.fillMaxWidth().padding(horizontal=16.dp),shape=RoundedCornerShape(24.dp),colors=CardDefaults.cardColors(containerColor=USurface2)) {
        Box(Modifier.fillMaxWidth().height(205.dp).background(Brush.horizontalGradient(listOf(Color(0xFF11283B),Color(0xFF101724),Color(0xFF25152B)))).padding(20.dp)) {
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth()) {
                Row(verticalAlignment=Alignment.CenterVertically){Text(if(event!=null)"● LIVE NOW" else if(next!=null)"UP NEXT" else "SPORTS CENTRAL",color=if(event!=null)ULive else UBlue,fontSize=10.sp,fontWeight=FontWeight.Black);Spacer(Modifier.width(10.dp));Text("${count} SPORTS CHANNELS",color=Color.LightGray,fontSize=9.sp)}
                Text(if(featured!=null)SportsPresentation.matchup(featured) else "Every game. Every league.",color=Color.White,fontSize=25.sp,fontWeight=FontWeight.Black,maxLines=2,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(top=7.dp))
                Text(if(featured!=null)featured.league+"  •  "+(featured.detail.ifBlank{featured.startTime}) else "Find live games and your favorite teams.",color=Color(0xFFB4C4D3),fontSize=12.sp,modifier=Modifier.padding(top=5.dp))
                Row(Modifier.padding(top=12.dp),verticalAlignment=Alignment.CenterVertically) {
                    Button(onClick={featured?.let(open)},colors=ButtonDefaults.buttonColors(containerColor=UBlue,contentColor=Color(0xFF041018)),shape=RoundedCornerShape(10.dp),enabled=featured!=null){Icon(Icons.Default.PlayArrow,null);Text(if(event!=null)"FIND A STREAM" else "VIEW SCHEDULE",fontWeight=FontWeight.Black)}
                    Spacer(Modifier.width(9.dp));Text("FAST • PERSONAL • SPORTS-FIRST",color=Color.Gray,fontSize=9.sp)
                }
            }
        }
    }
}
@Composable private fun USection(title:String,count:Int,tag:String,accent:Color) {
    Row(Modifier.fillMaxWidth().padding(horizontal=20.dp),verticalAlignment=Alignment.CenterVertically) {
        Box(Modifier.width(3.dp).height(27.dp).background(accent,RoundedCornerShape(3.dp)));Spacer(Modifier.width(10.dp))
        Text(title,color=Color.White,fontSize=15.sp,fontWeight=FontWeight.Black,modifier=Modifier.weight(1f))
        Text("$count  •  $tag",color=Color.Gray,fontSize=10.sp,fontWeight=FontWeight.Bold)
    }
}
@Composable private fun UEventCard(event:SportsEvent,live:Boolean,channels:List<SportsChannel>,now:Long,saved:Map<String,Boolean>,toggle:(String,Boolean)->Unit,play:(SportsChannel)->Unit,open:(SportsEvent)->Unit) {
    var sources by remember(event.id,channels){mutableStateOf(emptyList<SportsResolver.WatchSource>())}
    LaunchedEffect(event.id,channels){sources=withContext(Dispatchers.Default){SportsResolver.resolve(event,channels,8)}}
    val logo=event.leagueLogo?.takeIf{it.isNotBlank()}?:BrandAssets.logoUrl(SportsBranding.find(event.name,event.league))
    Card(Modifier.fillMaxWidth().padding(horizontal=16.dp).clickable{if(sources.isNotEmpty())play(sources.first().channel)else open(event)},shape=RoundedCornerShape(18.dp),colors=CardDefaults.cardColors(containerColor=USurface)) {
        Row(Modifier.padding(14.dp),verticalAlignment=Alignment.CenterVertically) {
            Box(Modifier.size(54.dp).background(USurface2,RoundedCornerShape(14.dp)),contentAlignment=Alignment.Center){if(!logo.isNullOrBlank())AsyncImage(logo,event.league,Modifier.size(42.dp),contentScale=ContentScale.Fit)else Text(event.league.take(3).uppercase(),color=UBlue,fontWeight=FontWeight.Black)}
            Column(Modifier.weight(1f).padding(start=12.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically){Text(if(live)"LIVE" else "UPCOMING",color=if(live)ULive else UAmber,fontSize=9.sp,fontWeight=FontWeight.Black);Spacer(Modifier.width(7.dp));Text(event.league,color=Color.Gray,fontSize=10.sp,maxLines=1)}
                Text(SportsPresentation.matchup(event),color=Color.White,fontSize=16.sp,fontWeight=FontWeight.ExtraBold,maxLines=2,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(top=4.dp))
                Text(event.detail.ifBlank{event.startTime},color=Color(0xFF9EADBC),fontSize=11.sp,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(top=3.dp))
                Text(if(sources.isEmpty())"Tap for channel matches" else "${sources.size} MATCHED SOURCES  •  ${sources.first().channel.name}",color=UBlue,fontSize=9.sp,fontWeight=FontWeight.Bold,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(top=6.dp))
            }
            IconButton(onClick={toggle(event.id,saved[event.id]!=true)}){Icon(if(saved[event.id]==true)Icons.Default.Bookmark else Icons.Default.BookmarkBorder,"Save event",tint=if(saved[event.id]==true)UBlue else Color.Gray)}
        }
    }
}
@Composable private fun USportRail(selected: String, on: (String) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(SportsCatalog.categories) { category ->
            FilterChip(selected == category, { on(category) }, { Text(category) },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = UBlue, selectedLabelColor = Color.Black))
        }
    }
}
@Composable private fun USportTiles(selected: String, on: (String) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(SportsCatalog.categories.take(12)) { category ->
            Card(Modifier.width(112.dp).height(82.dp).clickable { on(category) }, shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = USurface)) {
                Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.SpaceBetween) {
                    Icon(Icons.Default.SportsScore, null, tint = UBlue)
                    Text(category, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1)
                }
            }
        }
    }
}
@Composable private fun UChannelRail(channels: List<SportsChannel>, play: (SportsChannel) -> Unit) {
    val sportChannels = remember(channels) { SportsNetworkCatalog.sportsChannels(channels).map { it.first }.take(18) }
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(sportChannels, key = { "rail-${it.id}-${it.url}" }) { channel ->
            Card(Modifier.width(180.dp).clickable { play(channel) }, shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = USurface)) {
                Column(Modifier.padding(13.dp)) {
                    Icon(Icons.Default.LiveTv, null, tint = UBlue)
                    Text(channel.name, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                    Text("WATCH CHANNEL  ›", color = UBlue, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
@Composable private fun UChannelRow(c:SportsChannel,play:(SportsChannel)->Unit){Card(Modifier.fillMaxWidth().padding(horizontal=16.dp).clickable{play(c)},shape=RoundedCornerShape(14.dp),colors=CardDefaults.cardColors(containerColor=USurface)){Row(Modifier.padding(14.dp),verticalAlignment=Alignment.CenterVertically){Icon(Icons.Default.LiveTv,null,tint=UBlue);Column(Modifier.weight(1f).padding(start=12.dp)){Text(c.name,color=Color.White,fontWeight=FontWeight.Bold,maxLines=1,overflow=TextOverflow.Ellipsis);Text("SPORTS CHANNEL",color=Color.Gray,fontSize=9.sp)};Icon(Icons.Default.PlayArrow,null,tint=UBlue)}}}
@Composable private fun USourcePanel(open:()->Unit){Card(Modifier.fillMaxWidth().padding(18.dp),shape=RoundedCornerShape(22.dp),colors=CardDefaults.cardColors(containerColor=USurface)){Column(Modifier.padding(22.dp)){Icon(Icons.Default.SettingsInputAntenna,null,tint=UBlue);Text("Your streaming sources",color=Color.White,fontSize=22.sp,fontWeight=FontWeight.Black,modifier=Modifier.padding(top=10.dp));Text("Connect Xtream Codes or an M3U playlist. Your provider credentials stay on this device.",color=Color.Gray,modifier=Modifier.padding(top=8.dp));Button(onClick=open,Modifier.fillMaxWidth().padding(top=16.dp),shape=RoundedCornerShape(12.dp)){Text("MANAGE SOURCES")}}}}
@Composable private fun UEmpty(title:String,detail:String){Card(Modifier.fillMaxWidth().padding(horizontal=16.dp),shape=RoundedCornerShape(16.dp),colors=CardDefaults.cardColors(containerColor=USurface)){Column(Modifier.padding(18.dp)){Text(title,color=Color.White,fontWeight=FontWeight.Bold);Text(detail,color=Color.Gray,fontSize=12.sp,modifier=Modifier.padding(top=5.dp))}}}
@Composable private fun UEventDialog(event:SportsEvent,channels:List<SportsChannel>,play:(SportsChannel)->Unit,close:()->Unit){val matches by produceState(emptyList<SportsResolver.WatchSource>(),event.id,channels){value=withContext(Dispatchers.Default){SportsResolver.resolve(event,channels,16)}};AlertDialog(onDismissRequest=close,containerColor=USurface2,title={Text(SportsPresentation.matchup(event),color=Color.White,fontWeight=FontWeight.Black)},text={Column{Text("${matches.size} channel matches",color=UBlue);matches.take(12).forEach{m->TextButton(onClick={close();play(m.channel)}){Text("${m.score}%  •  ${m.channel.name}",color=Color.White)}}}},confirmButton={TextButton(close){Text("CLOSE",color=UBlue)}})}
private fun epoch(value:String):Long?=runCatching{Instant.parse(value).toEpochMilli()}.getOrNull()?:value.toLongOrNull()?.let{if(it<10000000000L)it*1000 else it}
