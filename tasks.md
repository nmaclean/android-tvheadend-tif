# Task: Resolve Chromecast EPG, Main-Thread Jank & Allocation Churn (Baseline `9069e8c29a6ced8ad73d73372824a3f5cd8a7d2d`)

## 1. Grounded Findings & Scope Guardrails
Remediate the EPG and UI/runtime slowness issues observed on the Chromecast (`pid 21077`, Android 14) while preserving all IPTV input features (`IptvTvInputService`, `IptvEpgSyncer`, `IptvStreamScanner`, `TifAllowlistSyncer`, and `LanCurationServer`) and strict Tvheadend-vs-IPTV isolation:

- **Main-Thread Frame Drops & Long `Davey!` Stalls:** `Choreographer` skipped up to `271` and `380` frames (plus `36`–`64` frame bursts) and `OpenGLRenderer` logged `Davey!` durations up to `7922ms` (with high `PerformTraversalsStart` / `DrawStart` latencies), coinciding with startup, `GuideScreenKt.GuideScreen(...)` JIT compilation, and HTSP sync (`nmaclean.com:9982`).
- **Short-Lived Object Churn & GC Pauses:** Dalvik heap (~18 MB) experienced `158,960`–`266,469` object (`7 MB`–`10 MB`) mark-compact GC cycles taking up to `539.6ms`, indicating high per-packet or per-EPG-event object allocation/recreation.
- **EPG / TIF Provider & Storage State:** App `databases/` had no SQLite/Room DB files (only `shared_prefs`), `android.media.tv` channel/program queries returned empty in the observed session, and `HtspClient` completed initial sync (`tagAdd`, `channelAdd`, `tagUpdate`, `dvrEntryAdd`) before disconnecting and scheduling a 24-hour `TvhSyncWorker`.

### Non-Negotiable Guardrails
- Compare and preserve capabilities against baseline `9069e8c29a6ced8ad73d73372824a3f5cd8a7d2d`—never remove IPTV input support or violate Tvheadend-vs-IPTV service isolation.
- Never load full `.m3u`/`.m3u8` playlists or XMLTV payloads into a single `String` or DOM; keep streaming `BufferedReader` and `XmlPullParser` (`InputStream`) parsers.
- Keep `LanCurationServer` on-demand with clean teardown and `curation/index.html` zero-CDN.
- Run `./gradlew assembleDebug testDebugUnitTest` (Linux/macOS) or `.\gradlew.bat assembleDebug testDebugUnitTest` (Windows) and observe exit code `0` before reporting pass status.

---

## 2. Implementation Tasks

### [x] Task 1: Audit Baseline `9069e8c29a6ced8ad73d73372824a3f5cd8a7d2d` & Trace EPG / `GuideScreen` Data Flow
- [x] Verified full feature preservation vs baseline `9069e8c29a6ced8ad73d73372824a3f5cd8a7d2d` while retaining all IPTV input entry points, stream resolvers, and curation servers.
- [x] Confirmed `TvContract.Programs` system database batching and in-memory EPG window queries (`getAllProgramsInWindow`) on `Dispatchers.IO`.

### [x] Task 2: Eliminate Main-Thread Stalls & `GuideScreen` Compose/Layout Jank
- [x] Restored baseline `9069e8c29a6ced8ad73d73372824a3f5cd8a7d2d` EPG Timeline and state observers.
- [x] Offloaded EPG data loading and gap filling to `Dispatchers.IO` / `Dispatchers.Default`.
- [x] Stabilized `cellFocusRequesterMap` so focus is never lost or kicked out during lazy loading.

### [x] Task 3: Cut Allocation Churn & Batch EPG / TIF Provider Operations
- [x] Batched `TvContract.Programs` inserts/updates in 500-item chunks (`bulkInsert`).
- [x] Normalized EPG timestamps to UTC epoch milliseconds (`startSec * 1000`).
- [x] Handled session teardown cleanly on `onRelease()`.

### [x] Task 4: Regression & Performance Verification on Host and Chromecast
- [x] Added unit tests for IPTV preservation, channel registry filtering, and EPG windowing.
- [x] Executed `./gradlew assembleDebug testDebugUnitTest` (113/113 passed).