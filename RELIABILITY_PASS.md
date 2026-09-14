# USportz reliability pass

The sports command center follows a cache-first, bounded-work model:

- retain the last good provider snapshot during refresh failures;
- query yesterday as well as today so overnight live events survive midnight;
- query a bounded SQLite candidate set before detailed event-to-channel scoring;
- use bounded playback retry candidates rather than unbounded reconnect loops;
- publish channel data independently from optional EPG enrichment.

The provider credentials and stream URLs remain device-side and are never committed to the repository.
