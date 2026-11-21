# Project Rules: `android-tvheadend-tif`

## 1. Environment & Build Commands
- **Gradle Wrapper (OS-Aware):**
  - **Windows:** Always run `.\gradlew.bat assembleDebug testDebugUnitTest` (never invoke `gradlew.exe`—it does not exist—and do not use `./gradlew` in Windows PowerShell/CMD).
  - **Linux / macOS:** Run `./gradlew assembleDebug testDebugUnitTest` (e.g., in `~/build/android-tvheadend-tif`)[cite: 3].
- **Clean Build (if needed):** `.\gradlew.bat clean assembleDebug` (Windows) or `./gradlew clean assembleDebug` (Linux/macOS)[cite: 3].
- Do not modify `local.properties` (preserve the host environment's Android SDK path)[cite: 3].
- Only report builds or unit tests as passing after running the command and observing exit code `0`.

## 2. Architecture & Coding Standards
- **Language & Stack:** Kotlin, Android TV Input Framework (TIF), Leanback / Compose for TV, AndroidX Media3 (`ExoPlayer`), `kotlinx.serialization`, and Ktor Server CIO[cite: 3].
- **Strict Tvheadend vs. IPTV Input Isolation:**
  - `TvheadendInputService` and `TvhSyncManager` must have **zero** imports or dependencies on `com.nmaclean.tvheadend.iptv.*` and must target **only** the Tvheadend `TvInputService` `ComponentName`.
  - `IptvTvInputService`, `TifAllowlistSyncer`, `IptvEpgSyncer`, and `IptvStreamScanner` must target **only** `IptvTvInputService`'s `ComponentName` and never query, modify, or delete Tvheadend channels or EPG programs.
  - Any cleanup of stray `"iptv:"` rows under the Tvheadend input must never delete genuine Tvheadend channels.
- **On-Demand LAN Web Server & Zero-CDN Rule:**
  - `LanCurationServer` must bind only on demand while the TV curation/scanning screen is active and shut down cleanly on `Stop Server`, lifecycle `ON_STOP`, or `ViewModel.onCleared()`—never register a persistent 24/7 background `Service`.
  - `app/src/main/assets/curation/index.html` must be 100% self-contained (inline CSS and vanilla JS only) with zero external CDN URLs, remote fonts, or outbound telemetry, styled to match the app's dark theme.
- **Streaming Parsing:**
  - Parse `.m3u` / `.m3u8` playlists line-by-line via `BufferedReader` and XMLTV EPG feeds via streaming `XmlPullParser` (`InputStream`). Never load full playlists or EPG XML payloads into a single `String` or DOM in heap memory.
- **TV Lifecycle & Streaming:** Ensure `TvInputService.Session` lifecycles properly release `ExoPlayer` instances, audio focus, and HTSP/network streams on `onRelease()` to prevent background decoder leaks[cite: 3].
- **UI, Copy & D-Pad Navigation:**
  - All new TV UI components must handle D-pad focus states (`focusable`, visible focus rings) and avoid touch-only gestures[cite: 3].
  - Keep IPTV settings/controls in the web server and scanning screen (not cluttering main Settings), and use **Scan** terminology rather than legacy `"Sync Allowlist to Live Channels / TIF"` labels.

## 3. Git Checkpoint Workflow
- Before editing code, verify you are on a `feat/*` or `fix/*` branch (`git checkout -b feat/<short-name>`)[cite: 3].
- Break large features into logical milestones. After each milestone:
  1. Run the OS-appropriate build & test gate (`.\gradlew.bat assembleDebug testDebugUnitTest` on Windows, `./gradlew assembleDebug testDebugUnitTest` on Linux) and fix any compiler or test errors before continuing[cite: 3].
  2. Stage only modified project files (`git add <files>`) and commit locally using Conventional Commits (e.g., `feat(tif): isolate IPTV input service and stream scan`)[cite: 3].

## 4. Versioning & Release Rules
- **Monotonic Version Numbers Only:** Always bump release tags and `versionCode` strictly forward (e.g. `v1.2.x` -> `v1.3.0`), even after squashing history[cite: 3]. Never lower or reset version numbers below what is already installed on devices[cite: 3].
- **No Self-Uninstall / Downgrade Flows:** Android blocks in-place downgrades (`INSTALL_FAILED_VERSION_DOWNGRADE`) and uninstalling clears local app data[cite: 3]. Keep all app updates as single-step, in-place upgrades without downgrade/uninstall/settings-backup workarounds[cite: 3].