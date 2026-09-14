# Reliability implementation status

The reliability pass is being implemented incrementally. The source of truth remains the SQLite active generation for channels; refreshes must not publish an empty transient provider response over a known-good snapshot. Event ingestion must retain currently-live events across midnight. Event-to-channel resolution must query a bounded SQLite candidate set before detailed scoring. Playback should try normalized/alternate stream variants before surfacing failure. EPG must be best-effort and must never block the initial channel snapshot.

## Coverage wave

Wave 1-5 foundations now include persistent sports identity/EPG graph storage, multi-provider schedule federation, adaptive stream-variant models, live delta/game-center state, My Teams primitives, multiview/timeshift/mini-player state contracts, deeper league adapter surfaces, and explicit WWE Raw/SmackDown schedule coverage. The provider registry now exposes the federation and WWE sources alongside ESPN/NCAA/MLB/NHL.
