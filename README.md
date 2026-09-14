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

## Security
Xtream credentials remain device-side. Do not commit real credentials, playlists, or provider URLs.

## Build
Open in Android Studio with a current Android SDK and run the `app` configuration. GitHub Actions builds the debug APK on pushes and pull requests targeting `main`.
