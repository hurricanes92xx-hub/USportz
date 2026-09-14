# Reliability implementation

This branch establishes the production contracts for the five reliability upgrades.

1. Live event rollover: yesterday is queried for critical feeds and active events are retained across midnight.
2. Cache-first UI: the existing generation-backed SQLite snapshot remains the last-good source while refresh runs.
3. Indexed resolution: event matching first asks SQLite for a bounded candidate set, then applies detailed scoring.
4. Playback recovery: retry candidates are bounded to three attempts and include protocol/container alternates when applicable.
5. Visible-first EPG: channel publication is independent of guide enrichment.

The existing application wiring remains the source of truth for provider credentials and playback. No credentials or provider URLs are stored in source control.
