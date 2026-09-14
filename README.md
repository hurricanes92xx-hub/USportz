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
- NCAA metadata is supplemental: Xtream remains the playable source and `GameSourceMatcher` remains responsible for finding the user's actual IPTV channels.

## NCAA data architecture
USPortz uses `henrygd/ncaa-api` as a bounded sports-data supplement. The public endpoint is the default, but the app accepts `-PNCAA_API_BASE_URL` and optional `-PNCAA_API_KEY` Gradle properties so production builds can point at a self-hosted instance.

The app caches NCAA scoreboard responses for 45 seconds, caps imported games, uses short network timeouts, and never blocks Xtream catalogue startup or playback on NCAA availability.

The upstream project documents Docker deployment and explicitly recommends hosting your own instance for long-term reliability; its public demo is rate-limited to 5 requests/second per IP. citeturn0search0turn3search0

Self-hosting assets are included under `deploy/ncaa-api/`:
- `docker-compose.yml` for local/Docker hosting.
- `render.yaml` for a Render Blueprint using the published Docker image.
- `README.md` for configuration and app build-property wiring.

## Security
Xtream credentials remain device-side. Do not commit real credentials, playlists, provider URLs, or NCAA API secrets.

## Build
Open in Android Studio with a current Android SDK and run the `app` configuration. GitHub Actions builds the debug APK on pushes and pull requests targeting `main`.
