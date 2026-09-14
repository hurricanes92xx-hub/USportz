# USPortz

Fresh Android + Android TV sports command center rebuilt from observed product capabilities. The reliability pass is cache-first and optimized for large IPTV catalogues.

## Scope
- Original Kotlin/Jetpack Compose implementation.
- Xtream Codes M3U+ connection plus generic M3U/M3U8 loading.
- Complete provider catalogue streamed into SQLite in bounded batches; Compose reads bounded projections.
- Last-good catalogue snapshot remains available while provider refresh runs.
- Live event rollover retention across midnight (yesterday + today + lookahead).
- Indexed event-to-channel candidate resolution followed by detailed scoring.
- Media3 live playback with bounded recovery attempts.
- Sports/event home, categories, favorites and search foundation.
- Channel data can render independently of EPG enrichment.
- Phone/tablet UI with Android TV directional-input support.
- NCAA Football FBS plus NCAA men's and women's Division I basketball intelligence merged into the same `SportsEvent` pipeline used by the rest of the sports system.
- MLB and NHL native schedule adapters.
- Soccer, tennis, golf, UFC, boxing, F1, NASCAR, CFL, UFL and college sports feeds through the existing ESPN schedule layer.
- Persistent Channel DNA and playback health/ranking remain independent of external sports-data availability.
- Lazy ESPN game detail enrichment on demand.
- XMLTV/EPG normalization with visible-first startup and stale-cache retention.
- Optional BALLDONTLIE, PWHL/LeagueStat-compatible and SportsDataverse-compatible adapters, all normalized into `SportsEvent`.
- BrightSports-style followed-team persistence for future alerts/rails without coupling it to playback.

## Sports provider architecture
All sports-data sources are supplemental metadata. They normalize into `SportsEvent`, are loaded concurrently by `SportsProviderEngine`, and are deduplicated before matching. Xtream remains the playable source; `GameSourceMatcher`, Channel DNA, EPG intelligence and IPTV health decide which actual channel/feed to open.

Current provider families:
- ESPN: major US sports, soccer, tennis, golf, MMA, boxing, racing and college coverage.
- NCAA API: FBS football and D-I men's/women's basketball.
- MLB Stats API: native MLB schedule/status.
- NHL API: native NHL schedule/status.
- BALLDONTLIE: optional NBA enrichment when an API key is configured.
- PWHL/LeagueStat-compatible JSON: optional endpoint when configured.
- SportsDataverse-compatible JSON: optional self-hosted snapshot/adapter endpoint when configured.

Optional Gradle properties:
- `NCAA_API_BASE_URL`, `NCAA_API_KEY`
- `BALLDONTLIE_API_KEY`
- `PWHL_LEAGUESTAT_URL`
- `SPORTSDATAVERSE_URL`

Providers with no configured endpoint/key simply return an empty list and never block the rest of the sports engine.

## NCAA data architecture
USPortz uses `henrygd/ncaa-api` as a bounded sports-data supplement. The public endpoint is the default, but the app accepts `-PNCAA_API_BASE_URL` and optional `-PNCAA_API_KEY` Gradle properties so production builds can point at a self-hosted instance.

The app caches NCAA scoreboard responses for 45 seconds, caps imported games, uses short network timeouts, and never blocks Xtream catalogue startup or playback on NCAA availability.

Self-hosting assets are included under `deploy/ncaa-api/`.

## Security
Xtream credentials remain device-side. Do not commit real credentials, playlists, provider URLs, or API secrets.

## Build
Open in Android Studio with a current Android SDK and run the `app` configuration. GitHub Actions builds the debug APK on pushes and pull requests targeting `main`.
