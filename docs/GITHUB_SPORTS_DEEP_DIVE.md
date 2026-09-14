# GitHub Sports Deep Dive — USportz Upgrade Targets

This note records sports-related GitHub projects and data patterns worth using as architectural inspiration or optional data sources. No third-party application code is copied into USportz.

## Highest-value findings

### 1. Rich game detail should be lazy, not part of startup
GitHub projects documenting ESPN's public feeds expose event summaries, box scores, plays, situation, broadcasts, period scores, standings, injuries, transactions, rankings, and news. USportz should request these only when a user opens a game or team so the live dashboard remains fast.

USPortz now has `SportsGameDetailService.kt` for bounded, on-demand event detail retrieval.

### 2. NCAA deserves its own fallback data lane
`henrygd/ncaa-api` exposes NCAA scores, stats, rankings, standings, schedules, brackets, logos, news, box scores, play-by-play, scoring summaries and team stats. This is especially valuable for FBS/FCS football and men's/women's basketball, where conference, ranking and tournament context matters.

Recommended next step: optional NCAA fallback/augmentation for NCAA event cards, rankings and tournament brackets. Keep it off the critical startup path and respect its documented rate limit.

### 3. EPG matching should be ID-first, fuzzy-name-second
IPTV Nexus and iptv-org/epg demonstrate the value of authoritative `tvg-id`/XMLTV IDs followed by constrained fuzzy matching. USPortz's EPG now normalizes channel identifiers consistently before comparison, preventing the earlier space-vs-hyphen mismatch.

Recommended next step: persist the last good EPG snapshot to disk so stale EPG survives process death, not just refresh failures during the same process.

### 4. Stream health should be historical
IPTV Nexus uses rolling 0–100 health scoring, exponentially weighted observations, time-to-first-byte, HTTP result, media metadata and conservative retirement. OTT Stream Score similarly emphasizes reliability history, duplicate-feed ranking and EPG/programming verification.

USPortz already has bounded playback health and best-source memory. Recommended next step: persist a small per-channel health summary (not raw URLs/credentials) so source ranking improves across app restarts.

### 5. Sport-specific presentation is worth doing
Sports score projects show useful differences by sport: basketball benefits from period markers, football benefits from down/distance and possession, soccer from halftime/status, hockey from periods, baseball from inning/half-inning, and tennis from set/game scores. Notifications should also be sport-aware rather than treating every score change equally.

Recommended next step: expand `SportsGameDetail` into sport-specific display models and use them in event cards/player overlays.

### 6. Team/league metadata is a force multiplier
TheSportsDB-style metadata models and open sports databases demonstrate that stable team IDs, aliases, logos, venues, leagues and season identifiers make matching substantially more reliable than raw text alone.

Recommended next step: create a compact local alias/identity table for major US leagues, NCAA conferences and high-value soccer competitions. Keep it bounded and updateable without storing a huge provider catalogue in RAM.

### 7. Soccer can use open historical data for identity, not live playback
OpenFootball and open-football datasets provide public-domain fixtures/results across many leagues and regions. These are useful for team/competition normalization and historical context, but should not be treated as a primary real-time source.

### 8. Avoid heavyweight always-on data pulls
The recurring pattern across the best projects is: cache-first, bounded payloads, visible-first EPG, shard/parallel background work, and lazy deep data. USPortz should continue to avoid loading full provider catalogues, full EPGs, or full play-by-play feeds into Compose memory.

## Priority order for future sprints

1. Persistent stream-health history + source quality score.
2. NCAA fallback/augmentation for scores, rankings, standings and brackets.
3. Sport-specific live detail presentation.
4. Local team/league identity + alias database.
5. Persistent stale EPG snapshot.
6. Team-following and score/period notification policy.
7. Soccer competition expansion and identity coverage.
8. Venue/time-zone enrichment where it improves scheduling.

## Sources

- `henrygd/ncaa-api` — NCAA scores, stats, rankings, standings, brackets, logos, news and game details.
- `sejaldua/espn-api` — ESPN endpoint catalog covering scoreboards, summaries, teams, athletes, standings, rankings, injuries, transactions, plays, situation, broadcasts and game packages.
- `dearbulut/iptv` — IPTV aggregation, health scoring, EPG normalization/matching and static API architecture.
- `iptv-org/epg` — XMLTV grabbing and channel/program normalization patterns.
- `withqwerty/open-football` / `openfootball/football.json` — public-domain soccer datasets.
- `TheSportsDB` API documentation — sports metadata model covering leagues, teams, players, events, venues and standings.
- `gi-os/BrightSports` — sport-specific score/period notification behavior and lightweight persistent caching patterns.

## Licensing / safety note

Use these projects for architecture, endpoint knowledge, schemas and ideas. Before incorporating code or bundled datasets, verify each repository's license and redistribution terms. Do not import private provider credentials, paid streams, or copyrighted media into USPortz.
