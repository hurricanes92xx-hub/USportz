# USPortz

**Your sports, one place.** USPortz is an Android + Android TV sports discovery and live-TV companion built around a fast, local channel catalogue and a unified sports schedule.

## Product experience
- Live Now, Starting Soon, Today and Upcoming event discovery.
- Sport/league schedules, team follow foundation, event-to-channel matching and multiple ranked IPTV source options.
- Xtream Codes and generic M3U/M3U8 source support; credentials stay on device.
- Sports network browsing, favorites, source management and Media3 playback.
- Phone/tablet UI plus a dedicated Android TV / D-pad experience.

## Sports coverage
ESPN schedule layer, NCAA FBS and Division I men's/women's basketball, native MLB and NHL schedules, plus soccer, tennis, golf, MMA, boxing, racing, CFL/UFL and college sports. Optional adapters include BALLDONTLIE, PWHL/LeagueStat-compatible and SportsDataverse-compatible endpoints. All providers normalize into the same `SportsEvent` model; external sports data is metadata, while Xtream/M3U remains the playable source.

## Fast catalogue architecture
- Stream imports into SQLite in bounded batches; swap the active catalogue only after a complete generation is ready.
- FTS4 indexes channel names, groups and metadata; existing catalogues are upgraded/backfilled.
- Indexed event-to-channel candidates, substring fallback and bounded top-K ranking.
- Sports TV uses 40-item SQLite pages and indexed search instead of loading every channel into Compose.
- Cached last-good catalogue/schedule remains visible during refresh.
- Lazy artwork and background enrichment; source refresh does not gate schedule rendering.

The importer emits a credential-free `USPortzPerf` log line. Capture with `adb logcat -s USPortzPerf:I`. Benchmark cold start, first sports channel, full import, search latency and memory on the same device/source—especially with a 50k+ test catalogue.

## Interface direction
The design target is a premium, sports-first interface: cinematic live event hero, Live Now and Starting Soon rails, sport tiles, followed teams, global search, clean channel browsing, persistent favorites, and a remote-first TV layout. See [UI Design & Product Roadmap](docs/UI_DESIGN.md). This is a design target, not a claim that every listed screen is already implemented.

## Build
Open in Android Studio with a current Android SDK and run the `app` configuration. GitHub Actions runs unit tests and builds debug/release APKs.

## Security
Never commit provider URLs, playlists, Xtream credentials, keystores, or API keys. Configure optional provider keys through local Gradle properties or repository secrets.
