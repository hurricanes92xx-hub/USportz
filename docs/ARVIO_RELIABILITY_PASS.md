# ARVIO reliability pass

This pass applies the useful ARVIO patterns to USportz without replacing its disk-backed sports architecture.

## Goals
- retain yesterday's events when they are still live;
- keep the last-good SQLite channel generation visible during refresh;
- resolve events from a bounded indexed candidate set;
- retry playback with alternate provider URLs/containers before failing;
- publish channels before EPG work completes.

## Guardrails
USportz does not embed provider credentials, proxy Xtream traffic through a server, or require a cloud backend. The complete provider catalogue remains disk-backed.
