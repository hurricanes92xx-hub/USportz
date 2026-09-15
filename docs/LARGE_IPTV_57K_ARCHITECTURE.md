# USPortz large-playlist architecture

## Target

USPortz is designed for Xtream providers with **57,000+ live channels**.
The full catalogue must remain available for IPTV browsing, while the sports screen must feel ready in seconds.

## What the reference apps teach us

- **ARVIO:** its large-IPTV work explicitly targets 50,000+ channels. Its device regression pass used a 54,502-channel provider and kept only 240 startup rows materialized in memory while the complete set remained persisted. Cached repository startup was about two seconds because it did not redownload the provider list.
- **TiviMate:** current user-facing reports and recent release notes emphasize improved processing of large playlists. The practical lesson is to keep playlist refresh separate from the interactive TV surface and avoid making every navigation action rebuild the whole list.
- **Tuvora:** its public project is a TV-first client built on Nuvio and focused on fast playback/navigation. It is useful as a UI/playback reference, but it is not the strongest source for large Xtream catalogue internals.

## USPortz implementation

### 1. Two-tier startup

1. Restore the complete last-good SQLite generation when it exists.
2. If no complete generation exists yet, restore a separate small sports preview cache.
3. Show sports from that preview immediately.
4. Continue importing the complete provider catalogue in the background.
5. Only publish the full catalogue after the staged generation is complete.

The preview cache is intentionally a separate SQLite database so a 48-channel sports first-paint can never replace a 57k-channel complete generation.

### 2. Bounded memory

- Xtream JSON is streamed with `JsonReader`.
- Gzip responses are decoded as streams.
- The full provider inventory stays in SQLite.
- Sports resolution combines the small startup window with indexed SQL candidates instead of requiring all 57k channels in a Compose list or resolver heap.

### 3. Atomic refresh

The active provider generation is never cleared at the start of a refresh. New rows are staged under a new generation and become active only at the publish step. A failed refresh therefore leaves the previous complete snapshot available.

### 4. Sports-first provider access

The cold-start accelerator only requests a small set of high-value sports categories. It does not scan every provider category before the sports UI can render.

### 5. Credentials

Xtream credentials remain on-device. USPortz does not embed credentials in the repository and does not proxy provider traffic through a server.

## Acceptance target

A 57k+ provider should behave like this:

`launch -> cached/preview sports -> resolve game -> play -> background full refresh -> atomic full catalogue publish`

The full network import itself is allowed to take materially longer than first paint. The UX requirement is that the user does not have to wait for all 57k rows before the sports experience becomes usable.
