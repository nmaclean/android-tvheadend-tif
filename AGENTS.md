# AGENT.md — `android-tvheadend-tif`

## 1. Environment & Build Commands
- **Gradle Wrapper (OS-Aware):**
  - **Windows:** Always run `.\gradlew.bat assembleDebug testDebugUnitTest` (never invoke `gradlew.exe`—it does not exist—and do not use `./gradlew` in Windows PowerShell/CMD).
  - **Linux / macOS:** Run `./gradlew assembleDebug testDebugUnitTest` (e.g., in `/home/server/build/android-tvheadend-tif` or `~/build/android-tvheadend-tif`).
- **Clean Build (if needed):** `.\gradlew.bat clean assembleDebug` (Windows) or `./gradlew clean assembleDebug` (Linux/macOS).
- Do not modify `local.properties` (preserve the host environment's Android SDK path).
- Only report builds or unit tests as passing after running the command and observing exit code `0`.

## 2. Architecture & Coding Standards
- **Language & Stack:** Kotlin, Android TV Input Framework (TIF), Leanback / Compose for TV, AndroidX Media3 (`ExoPlayer`), `kotlinx.serialization`, and Ktor Server CIO.
- **Strict Tvheadend vs. IPTV Input Isolation:**
  - `TvheadendInputService` and `TvhSyncManager` must have **zero** imports or dependencies on `com.nmaclean.tvheadend.iptv.*` and must target **only** the Tvheadend `TvInputService` `ComponentName`.
  - `IptvTvInputService`, `TifAllowlistSyncer`, `IptvEpgSyncer`, and `IptvStreamScanner` must target **only** `IptvTvInputService`'s `ComponentName` and never query, modify, or delete Tvheadend channels or EPG programs.
  - Any cleanup of stray `"iptv:"` rows under the Tvheadend input must never delete genuine Tvheadend channels.
- **On-Demand LAN Web Server & Zero-CDN Rule:**
  - `LanCurationServer` must bind only on demand while the TV curation/scanning screen is active and shut down cleanly on `Stop Server`, lifecycle `ON_STOP`, or `ViewModel.onCleared()`—never register a persistent 24/7 background `Service`.
  - `app/src/main/assets/curation/index.html` must be 100% self-contained (inline CSS and vanilla JS only) with zero external CDN URLs, remote fonts, or outbound telemetry, styled to match the app's dark theme.
- **Streaming Parsing & Bounded Memory:**
  - Parse `.m3u` / `.m3u8` playlists line-by-line via `BufferedReader` and XMLTV EPG feeds via streaming `XmlPullParser` (`InputStream`). Never load full playlists or EPG XML payloads into a single `String` or DOM in heap memory.
  - Bound caches and queues explicitly to avoid OOM or GC pauses on Android TV hardware; release bitmaps/drawables/players cleanly on teardown.
- **EPG Sync & Data Integrity:**
  - Batch `TvContractCompat.Programs` inserts, updates, and deletes via `ContentResolver.applyBatch` in bounded chunks (e.g., 250–500 operations per batch) rather than issuing per-item IPC calls.
  - Normalize timestamps consistently (UTC epoch seconds vs. milliseconds), deduplicate/coalesce bursty HTSP event updates (`eventAdd` / `eventUpdate` / `eventDelete`), and clean up stale/overlapping program windows without dropping `Now & Next` entries or IPTV channel mappings.
- **Performance, Concurrency & Channel-Zap Latency:**
  - Never perform blocking network I/O, disk I/O, XML/JSON/HTSP parsing, or large list diffing on `Dispatchers.Main`; run work on `Dispatchers.IO` or `Dispatchers.Default` with structured cancellation.
  - When switching channels (`TvPlayerActivity`, `HtspDataSource`, `HtspConnectionPool`), tear down or hand off prior subscriptions cleanly without blocking the next channel's `subscribe` call or stalling playback recovery.
  - Window/page EPG guide queries to the active time range plus a small prefetch buffer instead of loading multi-day datasets upfront, and use stable item keys in TV lists/grids.
- **TV Lifecycle & Streaming:**
  - Ensure `TvInputService.Session` lifecycles properly release `ExoPlayer` instances, audio focus, and HTSP/network streams on `onRelease()` to prevent background decoder leaks.
- **UI, Copy & D-Pad Navigation:**
  - All new TV UI components must handle D-pad focus states (`focusable`, visible focus rings) and avoid touch-only gestures.
  - Keep IPTV settings/controls in the web server and scanning screen (not cluttering main Settings), and use **Scan** terminology rather than legacy `"Sync Allowlist to Live Channels / TIF"` labels.

## 3. Git Checkpoint, Baseline & Release Workflow
- **Feature Branches & Baseline Reference:**
  - Before editing code, verify you are on a `feat/*` or `fix/*` branch (`git checkout -b feat/<short-name>` or `fix/<short-name>`).
  - Treat `9069e8c29a6ced8ad73d73372824a3f5cd8a7d2d` as a behavioral reference baseline when auditing regressions: never strip IPTV input support or existing capabilities when fixing EPG or slowness issues.
- **Milestone Checkpoints:**
  - Break large features or fixes into logical milestones. After each milestone:
    1. Run the OS-appropriate build & test gate (`.\gradlew.bat assembleDebug testDebugUnitTest` on Windows, `./gradlew assembleDebug testDebugUnitTest` on Linux) and fix any compiler or test errors before continuing.
    2. Stage only modified/intended project files (`git add <files>`) and commit locally using Conventional Commits (e.g., `fix(epg): batch program provider updates and coalesce events`).
- **Push & Tag Discipline:**
  - Verify Git `user.name` and `user.email` are configured for the target user/environment before committing.
  - When pushing tags or creating releases, verify remote reachability and transport (SSH vs. HTTPS) and confirm the branch and tag exist on `origin`.

## 4. Versioning & Release Rules
- **Monotonic Version Numbers Only:** Always bump release tags and `versionCode` strictly forward (e.g. `v1.2.x` -> `v1.3.0`), even after squashing history. Never lower or reset version numbers below what is already installed on devices.
- **No Self-Uninstall / Downgrade Flows:** Android blocks in-place downgrades (`INSTALL_FAILED_VERSION_DOWNGRADE`) and uninstalling clears local app data. Keep all app updates as single-step, in-place upgrades without downgrade/uninstall/settings-backup workarounds.

## 5. Agent Execution & Staging Rules
- **Low-Loop, Action-First:** Do not stop for clarifying questions unless a target path or destructive action is ambiguous; state safe assumptions in 1–2 short bullets, apply changes directly, and run a concise verification check.
- **Safe Staging & Backups:** Stage temporary scripts/artifacts under `/tmp/admin-server-studio/runs/<runId>` (or OS temp dir), back up existing files before overwriting when modifying in place, use `set -euo pipefail` in shell scripts, and quote all paths.