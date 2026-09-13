# USPortz

Fresh Android + Android TV sports command center rebuilt from the observed product capabilities of the supplied AK47Sports v1.6 and SportzX 3.2 APKs.

## Scope
- Original Kotlin/Jetpack Compose implementation; no proprietary APK code is copied.
- Xtream Codes M3U+ connection.
- Generic M3U/M3U8 URL loading and local parsing.
- Complete provider catalogue streamed into SQLite in bounded batches; Compose reads only bounded projections.
- Live TV browsing and Media3 playback.
- Sports/event home and category UI.
- Event-to-channel resolution against the disk-backed catalogue.
- Favorites persistence.
- Search foundation.
- Phone/tablet UI that can also be driven with TV directional input; TV-specific refinements are tracked in `TV_UI.md`.

## Security
Xtream credentials are stored only in Android private preferences. Do not commit real credentials, playlists, or provider URLs.

## Build
Open in Android Studio with a current Android SDK and run the `app` configuration. The repository intentionally contains no server-side proxy or Render service from the previous USPortz project.
