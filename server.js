const express = require('express');
const axios = require('axios');

const app = express();
app.disable('x-powered-by');
app.set('etag', false);

const PORT = Number(process.env.PORT || 10000);
const XTREAM_BASE_URL = (process.env.XTREAM_BASE_URL || '').replace(/\/$/, '');
const XTREAM_USERNAME = process.env.XTREAM_USERNAME || '';
const XTREAM_PASSWORD = process.env.XTREAM_PASSWORD || '';
const CACHE_TTL = Number(process.env.CACHE_TTL_SECONDS || 300) * 1000;
const SCOREBOARD_TTL = Number(process.env.SCOREBOARD_TTL_SECONDS || 60) * 1000;
const REQUEST_TIMEOUT = Number(process.env.REQUEST_TIMEOUT_MS || 7000);

const cache = new Map();
const inflight = new Map();

const LEAGUES = {
  nfl: { name: 'NFL', sport: 'football', league: 'nfl', icon: 'https://a.espncdn.com/i/teamlogos/leagues/500/nfl.png' },
  ncaaf: { name: 'NCAA Football', sport: 'football', league: 'college-football', icon: 'https://a.espncdn.com/i/teamlogos/ncaa/500/1.png' },
  nba: { name: 'NBA', sport: 'basketball', league: 'nba', icon: 'https://a.espncdn.com/i/teamlogos/leagues/500/nba.png' },
  wnba: { name: 'WNBA', sport: 'basketball', league: 'wnba', icon: 'https://a.espncdn.com/i/teamlogos/leagues/500/wnba.png' },
  ncaab: { name: 'NCAA Basketball', sport: 'basketball', league: 'mens-college-basketball', icon: 'https://a.espncdn.com/i/teamlogos/ncaa/500/1.png' },
  mlb: { name: 'MLB', sport: 'baseball', league: 'mlb', icon: 'https://a.espncdn.com/i/teamlogos/leagues/500/mlb.png' },
  nhl: { name: 'NHL', sport: 'hockey', league: 'nhl', icon: 'https://a.espncdn.com/i/teamlogos/leagues/500/nhl.png' },
  mls: { name: 'MLS', sport: 'soccer', league: 'usa.1', icon: 'https://a.espncdn.com/i/teamlogos/leagues/500/mls.png' },
  epl: { name: 'Premier League', sport: 'soccer', league: 'eng.1', icon: 'https://a.espncdn.com/i/teamlogos/leagues/500/eng.1.png' },
  ucl: { name: 'UEFA Champions League', sport: 'soccer', league: 'uefa.champions', icon: 'https://a.espncdn.com/i/teamlogos/leagues/500/uefa.champions.png' },
  laliga: { name: 'LaLiga', sport: 'soccer', league: 'esp.1', icon: 'https://a.espncdn.com/i/teamlogos/leagues/500/esp.1.png' },
  seriea: { name: 'Serie A', sport: 'soccer', league: 'ita.1', icon: 'https://a.espncdn.com/i/teamlogos/leagues/500/ita.1.png' },
  bundesliga: { name: 'Bundesliga', sport: 'soccer', league: 'ger.1', icon: 'https://a.espncdn.com/i/teamlogos/leagues/500/ger.1.png' },
  ligue1: { name: 'Ligue 1', sport: 'soccer', league: 'fra.1', icon: 'https://a.espncdn.com/i/teamlogos/leagues/500/fra.1.png' },
  ufc: { name: 'UFC', sport: 'mma', league: 'ufc', icon: 'https://a.espncdn.com/i/teamlogos/leagues/500/ufc.png' },
  boxing: { name: 'Boxing', sport: 'boxing', league: 'boxing', icon: 'https://a.espncdn.com/i/teamlogos/leagues/500/boxing.png' }
};

const STOP = new Set(['the','and','at','vs','v','fc','cf','sc','club','team','live','tv','hd','fhd','uhd','4k','usa','us','network','sports','sport','channel','east','west','main','backup','feed','event']);

function normalize(value) {
  return String(value || '')
    .normalize('NFKD').replace(/[\u0300-\u036f]/g, '')
    .toLowerCase().replace(/&/g, ' and ').replace(/[^a-z0-9]+/g, ' ')
    .split(/\s+/).filter(x => x && !STOP.has(x)).join(' ');
}

function tokens(value) { return normalize(value).split(' ').filter(Boolean); }
function similarity(a, b) {
  const aa = new Set(tokens(a)); const bb = new Set(tokens(b));
  if (!aa.size || !bb.size) return 0;
  let common = 0; for (const x of aa) if (bb.has(x)) common++;
  const containment = common / Math.min(aa.size, bb.size);
  const jaccard = common / new Set([...aa, ...bb]).size;
  return Math.round((containment * 0.7 + jaccard * 0.3) * 100);
}

function getCache(key) {
  const hit = cache.get(key);
  if (hit && hit.expires > Date.now()) return hit.value;
  return null;
}
function setCache(key, value, ttl) { cache.set(key, { value, expires: Date.now() + ttl }); return value; }
async function staleWhileRevalidate(key, loader, ttl) {
  const hit = cache.get(key);
  if (hit && hit.expires > Date.now()) return hit.value;
  if (inflight.has(key)) return inflight.get(key);
  const p = Promise.resolve().then(loader).then(value => setCache(key, value, ttl)).finally(() => inflight.delete(key));
  inflight.set(key, p);
  return p;
}

async function xtreamGet(action, params = {}) {
  if (!XTREAM_BASE_URL || !XTREAM_USERNAME || !XTREAM_PASSWORD) throw new Error('Xtream source is not configured');
  const response = await axios.get(`${XTREAM_BASE_URL}/player_api.php`, {
    params: { username: XTREAM_USERNAME, password: XTREAM_PASSWORD, action, ...params },
    timeout: REQUEST_TIMEOUT,
    responseType: 'json',
    validateStatus: s => s >= 200 && s < 300
  });
  return response.data;
}

async function loadXtreamIndex() {
  const categories = await xtreamGet('get_live_categories');
  const streams = await xtreamGet('get_live_streams');
  const categoryMap = new Map((Array.isArray(categories) ? categories : []).map(c => [String(c.category_id), c.category_name || 'Sports']));
  const sports = (Array.isArray(streams) ? streams : []).map(s => ({
    id: String(s.stream_id), name: s.name || '', categoryId: String(s.category_id || ''), category: categoryMap.get(String(s.category_id || '')) || '',
    url: s.stream_id ? `${XTREAM_BASE_URL}/live/${encodeURIComponent(XTREAM_USERNAME)}/${encodeURIComponent(XTREAM_PASSWORD)}/${s.stream_id}.m3u8` : '',
    logo: s.stream_icon || ''
  })).filter(s => /sport|nfl|nba|nhl|mlb|mls|soccer|football|basket|hockey|baseball|ufc|fight|boxing|espn|fox sports|bein|sky sport|tnt|cbs sports|nbc sports|racing|f1|formula|golf|tennis/i.test(`${s.name} ${s.category}`));
  return { categories: categoryMap.size, streams: sports, refreshedAt: new Date().toISOString() };
}

async function getXtreamIndex() {
  return staleWhileRevalidate('xtream:index', loadXtreamIndex, CACHE_TTL);
}

function eventId(leagueKey, event) { return `${leagueKey}:${event.id || Buffer.from(event.name).toString('hex').slice(0, 24)}`; }
function eventName(c) {
  const comps = c.competitions?.[0]; const competitors = comps?.competitors || [];
  const home = competitors.find(x => x.homeAway === 'home')?.team?.displayName || competitors[0]?.team?.displayName || 'Home';
  const away = competitors.find(x => x.homeAway === 'away')?.team?.displayName || competitors[1]?.team?.displayName || 'Away';
  return `${away} vs ${home}`;
}
function flattenScoreboard(data) {
  return (data?.events || []).map(e => {
    const c = e.competitions?.[0];
    const competitors = c?.competitors || [];
    const home = competitors.find(x => x.homeAway === 'home')?.team || competitors[0]?.team || {};
    const away = competitors.find(x => x.homeAway === 'away')?.team || competitors[1]?.team || {};
    return { id: String(e.id), name: eventName(e), date: e.date, state: e.status?.type?.description || e.status?.type?.name || '', stateShort: e.status?.type?.shortDetail || '', home: home.displayName || '', away: away.displayName || '', homeLogo: home.logo || '', awayLogo: away.logo || '', venue: c?.venue?.fullName || '', competitors, broadcasts: (c?.broadcasts || []).flatMap(x => x.names || []) };
  });
}

async function fetchLeagueEvents(leagueKey, days = 2) {
  const league = LEAGUES[leagueKey]; if (!league) return [];
  const dates = [];
  const now = new Date();
  for (let i = -1; i <= days; i++) { const d = new Date(now); d.setUTCDate(d.getUTCDate() + i); dates.push(d.toISOString().slice(0,10).replace(/-/g,'')); }
  const results = await Promise.all(dates.map(async date => {
    const key = `scoreboard:${leagueKey}:${date}`;
    return staleWhileRevalidate(key, async () => {
      const url = `https://site.api.espn.com/apis/site/v2/sports/${league.sport}/${league.league}/scoreboard?dates=${date}`;
      try { const r = await axios.get(url, { timeout: REQUEST_TIMEOUT }); return flattenScoreboard(r.data); } catch { return []; }
    }, SCOREBOARD_TTL);
  }));
  return results.flat();
}

async function allEvents() {
  const enabled = Object.keys(LEAGUES);
  const chunks = await Promise.all(enabled.map(k => fetchLeagueEvents(k, 2).then(events => events.map(e => ({ ...e, leagueKey: k, league: LEAGUES[k] })) )));
  return chunks.flat().sort((a,b) => new Date(a.date) - new Date(b.date));
}

function isLikelyMatch(stream, event) {
  const name = `${event.away} ${event.home}`;
  const s = similarity(stream.name, name);
  const cat = similarity(stream.category, event.league.name);
  const away = similarity(stream.name, event.away);
  const home = similarity(stream.name, event.home);
  return Math.max(s, Math.round((away + home) * 0.6), cat) >= 45;
}

function scoreStream(stream, event) {
  const away = similarity(stream.name, event.away);
  const home = similarity(stream.name, event.home);
  const pair = similarity(stream.name, `${event.away} ${event.home}`);
  const league = similarity(`${stream.name} ${stream.category}`, event.league.name);
  let score = pair * 0.45 + away * 0.25 + home * 0.25 + league * 0.05;
  if (/\b(4k|uhd)\b/i.test(stream.name)) score += 5;
  if (/\b(fhd|1080)\b/i.test(stream.name)) score += 3;
  if (/\b(hd|720)\b/i.test(stream.name)) score += 1;
  if (/backup|alt|test/i.test(stream.name)) score -= 4;
  return Math.round(score);
}

async function matchedStreams(event) {
  const index = await getXtreamIndex();
  return index.streams.map(s => ({ ...s, score: scoreStream(s, event) }))
    .filter(s => isLikelyMatch(s, event))
    .sort((a,b) => b.score - a.score)
    .slice(0, 8);
}

const manifest = {
  id: 'com.usportz.nuvio',
  version: '1.0.0',
  name: 'USportz',
  description: 'Fast live sports for Nuvio/Stremio powered by cached Xtream IPTV matching.',
  logo: 'https://cdn.jsdelivr.net/gh/hurricanes92xx-hub/USportz@main/assets/logo.svg',
  resources: ['catalog', 'meta', 'stream'],
  types: ['tv', 'movie'],
  catalogs: Object.entries(LEAGUES).map(([id,l]) => ({ type: 'tv', id, name: l.name, extraSupported: ['search'] })),
  idPrefixes: ['com.usportz.nuvio'],
  behaviorHints: { configurable: true, configurationRequired: false }
};

app.get('/', (req,res) => res.json({ name: 'USportz', status: 'ok', manifest: '/manifest.json', health: '/health', configured: Boolean(XTREAM_BASE_URL && XTREAM_USERNAME && XTREAM_PASSWORD) }));
app.get('/health', (req,res) => res.json({ ok: true, xtreamConfigured: Boolean(XTREAM_BASE_URL && XTREAM_USERNAME && XTREAM_PASSWORD), cacheEntries: cache.size, uptime: process.uptime() }));
app.get('/manifest.json', (req,res) => res.json(manifest));

app.get('/catalog/tv/:id.json', async (req,res) => {
  try {
    const league = LEAGUES[req.params.id];
    if (!league) return res.json({ metas: [] });
    const events = await fetchLeagueEvents(req.params.id, 2);
    const metas = events.map(e => ({ id: eventId(req.params.id,e), type:'tv', name:e.name, poster:e.homeLogo || e.awayLogo || league.icon, logo:e.homeLogo || e.awayLogo || league.icon, description:`${league.name} • ${e.stateShort || e.state || 'Scheduled'}${e.venue ? ` • ${e.venue}` : ''}`, releaseInfo:e.date, behaviorHints:{ defaultVideoId:eventId(req.params.id,e) } }));
    res.set('Cache-Control','public, max-age=30, stale-while-revalidate=120');
    res.json({ metas });
  } catch (err) { res.status(200).json({ metas: [], error: 'metadata temporarily unavailable' }); }
});

app.get('/meta/tv/:id.json', async (req,res) => {
  try {
    const [leagueKey, eventKey] = req.params.id.split(':');
    const events = await fetchLeagueEvents(leagueKey, 2);
    const e = events.find(x => String(x.id) === eventKey);
    if (!e) return res.json({ meta: null });
    res.json({ meta:{ id:req.params.id, type:'tv', name:e.name, poster:e.homeLogo || e.awayLogo || LEAGUES[leagueKey].icon, description:`${LEAGUES[leagueKey].name}\n${e.stateShort || e.state}\n${e.venue || ''}`.trim(), releaseInfo:e.date, videos:[{ id:req.params.id, title:e.name, released:e.date }] } });
  } catch { res.json({ meta:null }); }
});

app.get('/stream/tv/:id.json', async (req,res) => {
  try {
    const [leagueKey, eventKey] = req.params.id.split(':');
    const events = await fetchLeagueEvents(leagueKey, 2);
    const e = events.find(x => String(x.id) === eventKey);
    if (!e) return res.json({ streams: [] });
    const streams = await matchedStreams({ ...e, league: LEAGUES[leagueKey] });
    res.set('Cache-Control','private, max-age=10, stale-while-revalidate=30');
    res.json({ streams: streams.map(s => ({ name:`USportz • ${s.name}`, title:`${s.name} • Match ${s.score}`, url:s.url, behaviorHints:{ bingeGroup:`usportz-${s.id}` }, externalUrl:s.url })) });
  } catch (err) { res.status(200).json({ streams: [] }); }
});

app.get('/api/xtream/status', async (req,res) => {
  try { const index = await getXtreamIndex(); res.json({ ok:true, streams:index.streams.length, categories:index.categories, refreshedAt:index.refreshedAt }); }
  catch (e) { res.status(503).json({ ok:false, error:e.message }); }
});

app.get('/api/cache/refresh', async (req,res) => {
  try { cache.delete('xtream:index'); const index = await getXtreamIndex(); res.json({ ok:true, streams:index.streams.length, refreshedAt:index.refreshedAt }); }
  catch (e) { res.status(503).json({ ok:false, error:e.message }); }
});

app.listen(PORT, '0.0.0.0', () => console.log(`USportz listening on 0.0.0.0:${PORT}`));
