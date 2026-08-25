const express = require('express');
const axios = require('axios');

const app = express();
app.disable('x-powered-by');
app.use((req,res,next)=>{res.setHeader('Access-Control-Allow-Origin','*');res.setHeader('Access-Control-Allow-Headers','*');next();});

const PORT = Number(process.env.PORT || 10000);
const BASE = (process.env.PUBLIC_BASE_URL || '').replace(/\/$/,'');
const XTREAM_BASE_URL = (process.env.XTREAM_BASE_URL || '').replace(/\/$/,'');
const XTREAM_USERNAME = process.env.XTREAM_USERNAME || '';
const XTREAM_PASSWORD = process.env.XTREAM_PASSWORD || '';
const TIMEOUT = Number(process.env.REQUEST_TIMEOUT_MS || 8000);
const SCORE_TTL = Number(process.env.SCOREBOARD_TTL_SECONDS || 45) * 1000;
const XTREAM_TTL = Number(process.env.CACHE_TTL_SECONDS || 300) * 1000;
const scoreCache = new Map();
let xtreamCache = { expires: 0, rows: [] };
const eventCache = new Map();

// ESPN scoreboard feeds. This deliberately covers the major US sports plus
// international sports so the LIVE and UPCOMING views are not tied to one league.
const LEAGUES = {
  nfl:['NFL','football','nfl','🏈'], ncaaf:['NCAA Football','football','college-football','🏈'], cfl:['CFL','football','cfl','🏈'],
  nba:['NBA','basketball','nba','🏀'], wnba:['WNBA','basketball','wnba','🏀'], ncaab:['NCAA Men','basketball','mens-college-basketball','🏀'], ncaaw:['NCAA Women','basketball','womens-college-basketball','🏀'],
  mlb:['MLB','baseball','mlb','⚾'], nhl:['NHL','hockey','nhl','🏒'],
  mls:['MLS','soccer','usa.1','⚽'], epl:['Premier League','soccer','eng.1','⚽'], ucl:['Champions League','soccer','uefa.champions','⚽'], europa:['Europa League','soccer','uefa.europa','⚽'],
  laliga:['LaLiga','soccer','esp.1','⚽'], seriea:['Serie A','soccer','ita.1','⚽'], bundesliga:['Bundesliga','soccer','ger.1','⚽'], ligue1:['Ligue 1','soccer','fra.1','⚽'], liga_mx:['Liga MX','soccer','mex.1','⚽'], eredivisie:['Eredivisie','soccer','ned.1','⚽'], brasileirao:['Brasileirao','soccer','bra.1','⚽'],
  ufc:['UFC','mma','ufc','🥊'], boxing:['Boxing','boxing','boxing','🥊'],
  atp:['ATP Tennis','tennis','atp','🎾'], wta:['WTA Tennis','tennis','wta','🎾'],
  pga:['PGA Tour','golf','pga','⛳'], lpga:['LPGA','golf','lpga','⛳'],
  f1:['Formula 1','racing','f1','🏎️'], nascar:['NASCAR Cup','racing','nascar-premier','🏁'], indycar:['IndyCar','racing','irl','🏁'],
  rugby:['Rugby Union','rugby','rugby-union','🏉'], lacrosse:['PLL Lacrosse','lacrosse','pll','🥍'], cricket:['Cricket','cricket','icc.t20','🏏'], volleyball:['Volleyball','volleyball','fivb.m','🏐'],
  afl:['AFL','australian-football','afl','🏉']
};

const STOP = new Set(['the','and','at','vs','v','fc','cf','sc','club','team','live','tv','hd','fhd','uhd','4k','usa','us','network','sports','sport','channel','east','west','main','backup','feed','event','game']);
const norm = v => String(v ?? '').normalize('NFKD').replace(/[\u0300-\u036f]/g,'').toLowerCase().replace(/&/g,' and ').replace(/[^a-z0-9]+/g,' ').split(/\s+/).filter(x=>x && !STOP.has(x)).join(' ');
const toks = v => norm(v).split(' ').filter(Boolean);
function similarity(a,b){
  const aa=new Set(toks(a)), bb=new Set(toks(b));
  if(!aa.size || !bb.size) return 0;
  let hit=0; for(const x of aa) if(bb.has(x)) hit++;
  return Math.round((hit/Math.min(aa.size,bb.size)*70 + hit/new Set([...aa,...bb]).size*30));
}
function sleep(ms){return new Promise(r=>setTimeout(r,ms));}
function dateKey(d){return d.toISOString().slice(0,10).replace(/-/g,'');}
function scoreboardUrl(key, start, end){
  const l=LEAGUES[key];
  return `https://site.api.espn.com/apis/site/v2/sports/${l[1]}/${l[2]}/scoreboard?dates=${dateKey(start)}-${dateKey(end)}&limit=500`;
}

async function fetchLeague(key){
  const now=new Date();
  const start=new Date(now); start.setUTCDate(start.getUTCDate()-1);
  const end=new Date(now); end.setUTCDate(end.getUTCDate()+3);
  try {
    const r=await axios.get(scoreboardUrl(key,start,end),{timeout:TIMEOUT});
    return (r.data?.events || []).map(e=>toMeta(key,e)).filter(Boolean);
  } catch { return []; }
}

function toMeta(key,e){
  const l=LEAGUES[key];
  const c=e.competitions?.[0] || {};
  const competitors=c.competitors || [];
  const home=competitors.find(x=>x.homeAway==='home') || competitors[0] || {};
  const away=competitors.find(x=>x.homeAway==='away') || competitors[1] || {};
  const ht=home.team || {}, at=away.team || {};
  const status=c.status?.type || e.status?.type || {};
  const name = (ht.displayName && at.displayName) ? `${at.displayName} vs ${ht.displayName}` : (e.name || e.shortName || `${l[0]} event`);
  const start=e.date || c.date || '';
  const meta={
    id:`sport:${key}:${e.id}`, type:'channel', name, poster:ht.logo || at.logo || l[3], background:ht.logo || at.logo || '',
    description:`${l[0]} • ${status.shortDetail || status.detail || status.name || 'Scheduled'}`,
    releaseInfo:start, genres:['Sports',l[0]], sport:key, league:l[0], eventId:String(e.id),
    event:{id:String(e.id),league:key,start,state:status.state || (status.name==='STATUS_IN_PROGRESS'?'in':'pre'),
      home:{name:ht.displayName||home.athlete?.displayName||'',short:ht.abbreviation||'',logo:ht.logo||''},
      away:{name:at.displayName||away.athlete?.displayName||'',short:at.abbreviation||'',logo:at.logo||''},
      broadcast:(c.broadcasts||[]).flatMap(x=>x.names||[]), venue:c.venue?.fullName||''},
    score:{home:home.score ?? '',away:away.score ?? ''},
    sourceAvailable:false
  };
  eventCache.set(meta.id,meta);
  return meta;
}

async function allEvents(){
  const key='all'; const hit=scoreCache.get(key);
  if(hit && hit.expires>Date.now()) return hit.rows;
  const keys=Object.keys(LEAGUES), out=[];
  let cursor=0;
  const worker=async()=>{while(cursor<keys.length){const k=keys[cursor++];out.push(...await fetchLeague(k));await sleep(20);}};
  await Promise.all(Array.from({length:Math.min(8,keys.length)},worker));
  const unique=[...new Map(out.map(x=>[x.id,x])).values()].sort((a,b)=>new Date(a.releaseInfo)-new Date(b.releaseInfo));
  scoreCache.set(key,{expires:Date.now()+SCORE_TTL,rows:unique});
  return unique;
}

function xtreamApi(action,extra={}){
  const u=new URL(`${XTREAM_BASE_URL}/player_api.php`);
  u.searchParams.set('username',XTREAM_USERNAME); u.searchParams.set('password',XTREAM_PASSWORD);
  if(action) u.searchParams.set('action',action);
  for(const [k,v] of Object.entries(extra)) u.searchParams.set(k,String(v));
  return u.toString();
}
async function getXtream(){
  if(!XTREAM_BASE_URL || !XTREAM_USERNAME || !XTREAM_PASSWORD) return [];
  if(xtreamCache.expires>Date.now()) return xtreamCache.rows;
  try{
    const [cats,streams]=await Promise.all([
      axios.get(xtreamApi('get_live_categories'),{timeout:TIMEOUT}),
      axios.get(xtreamApi('get_live_streams'),{timeout:TIMEOUT})
    ]);
    const cm=new Map((Array.isArray(cats.data)?cats.data:[]).map(x=>[String(x.category_id),x.category_name||'Live TV']));
    xtreamCache.rows=(Array.isArray(streams.data)?streams.data:[]).map(s=>{
      const ext=String(s.container_extension||'ts').replace(/[^a-z0-9]/gi,'')||'ts';
      return {id:String(s.stream_id),name:s.name||`Channel ${s.stream_id}`,category:cm.get(String(s.category_id))||'Live TV',logo:s.stream_icon||'',url:`${XTREAM_BASE_URL}/live/${encodeURIComponent(XTREAM_USERNAME)}/${encodeURIComponent(XTREAM_PASSWORD)}/${encodeURIComponent(s.stream_id)}.${ext}`};
    });
    xtreamCache.expires=Date.now()+XTREAM_TTL;
  }catch{ if(!xtreamCache.rows) xtreamCache.rows=[]; }
  return xtreamCache.rows;
}

const isSportsChannel=s=>/\b(espn|fox sports|fs1|fs2|tnt|tbs|truTV|nba|nfl|nhl|mlb|sec|acc|big ten|cbs sports|nbc sports|msg|bally|sports|fight|ufc|boxing|tennis|golf|racing|f1|nascar|soccer|football|basketball|hockey|baseball)\b/i.test(`${s.name} ${s.category}`);
function channelScore(s,e){
  const text=`${s.name} ${s.category}`;
  const names=`${e.away.name} ${e.home.name}`;
  let score=similarity(text,names)*0.75;
  for(const b of e.broadcast||[]) if(norm(text).includes(norm(b)) && norm(b)) score=Math.max(score,94);
  if(/\b(espn|fs1|fs2|tnt|tbs|truTV|cbs sports|nbc sports|sec|acc|big ten|fox sports)\b/i.test(text)) score+=8;
  if(/\b(4k|uhd)\b/i.test(text)) score+=4;
  if(/\b(backup|alt|test)\b/i.test(text)) score-=8;
  return Math.round(score);
}
async function resolveStreams(meta){
  if(!meta?.event) return [];
  const rows=await getXtream();
  const pool=rows.filter(isSportsChannel);
  return pool.map(s=>({...s,score:channelScore(s,meta.event)})).filter(s=>s.score>=35).sort((a,b)=>b.score-a.score).slice(0,12);
}

const CATALOGS=[
  {type:'channel',id:'live-now',name:'🔴 LIVE NOW'},
  {type:'channel',id:'upcoming',name:'⏱ UPCOMING'},
  ...Object.entries(LEAGUES).map(([id,l])=>({type:'channel',id,name:`${l[3]} ${l[0]}`}))
];
const manifest={id:'com.usportz.nuvio',version:'2.1.0',name:'USportz',description:'Live and upcoming sports with authorized Xtream/M3U stream matching.',resources:[{name:'catalog',types:['channel']},{name:'meta',types:['channel'],idPrefixes:['sport:','xtream:']},{name:'stream',types:['channel'],idPrefixes:['sport:','xtream:']}],types:['channel'],catalogs:CATALOGS,behaviorHints:{configurable:false,configurationRequired:false}};

app.get('/',(req,res)=>res.json({name:'USportz',version:'2.1.0',status:'ok',manifest:'/manifest.json',health:'/health'}));
app.get('/health',(req,res)=>res.json({ok:true,eventsCached:scoreCache.has('all'),xtreamConfigured:Boolean(XTREAM_BASE_URL&&XTREAM_USERNAME&&XTREAM_PASSWORD),xtreamStreams:xtreamCache.rows.length,uptime:process.uptime()}));
app.get('/manifest.json',(req,res)=>res.json(manifest));
app.get('/catalog/channel/:id.json',async(req,res)=>{
  try{
    const id=req.params.id;
    const events=await allEvents();
    let rows;
    if(id==='live-now') rows=events.filter(x=>x.event?.state==='in');
    else if(id==='upcoming') rows=events.filter(x=>x.event?.state==='pre' && new Date(x.releaseInfo).getTime()<=Date.now()+7*86400000);
    else if(LEAGUES[id]) rows=events.filter(x=>x.sport===id);
    else if(id==='sports-command-center') rows=events;
    else if(id==='iptv-live'){
      const xs=await getXtream();
      return res.json({metas:xs.map(s=>({id:`xtream:${s.id}`,type:'channel',name:s.name,poster:s.logo,background:s.logo,description:s.category,genres:['Live TV',s.category],behaviorHints:{isLive:true}})).slice(0,500)});
    } else rows=[];
    res.json({metas:rows.slice(0,200)});
  }catch(e){res.status(200).json({metas:[],error:e.message});}
});
app.get('/meta/channel/:id.json',async(req,res)=>{
  try{
    const id=decodeURIComponent(req.params.id);
    if(id.startsWith('sport:')) return res.json({meta:eventCache.get(id) || (await allEvents()).find(x=>x.id===id) || null});
    if(id.startsWith('xtream:')){const s=(await getXtream()).find(x=>`xtream:${x.id}`===id);return res.json({meta:s?{id,type:'channel',name:s.name,poster:s.logo,background:s.logo,description:s.category,genres:['Live TV',s.category],behaviorHints:{isLive:true}}:null});}
    res.json({meta:null});
  }catch{res.json({meta:null});}
});
app.get('/stream/channel/:id.json',async(req,res)=>{
  try{
    const id=decodeURIComponent(req.params.id);
    if(id.startsWith('xtream:')){const s=(await getXtream()).find(x=>`xtream:${x.id}`===id);return res.json({streams:s?[{name:`USportz • ${s.name}`,title:s.category,url:s.url,behaviorHints:{isLive:true}}]:[]});}
    const meta=eventCache.get(id) || (await allEvents()).find(x=>x.id===id);
    const matches=await resolveStreams(meta);
    res.json({streams:matches.map(s=>({name:`USportz • ${s.name}`,title:`${s.category} • source match ${s.score}%`,url:s.url,behaviorHints:{isLive:true,bingeGroup:`usportz-${s.id}`}}))});
  }catch{res.status(200).json({streams:[]});}
});
app.get('/api/xtream/status',async(req,res)=>{const rows=await getXtream();res.json({ok:Boolean(rows.length),streams:rows.length,sportsStreams:rows.filter(isSportsChannel).length});});
app.get('/api/cache/refresh',async(req,res)=>{scoreCache.clear();xtreamCache.expires=0;const events=await allEvents();const rows=await getXtream();res.json({ok:true,events:events.length,streams:rows.length});});
app.listen(PORT,'0.0.0.0',()=>console.log(`USportz live sports server listening on ${PORT}`));
