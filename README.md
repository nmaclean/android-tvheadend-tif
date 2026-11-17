# Android TVheadend TIF

An Android TV Input Framework (TIF) client for **Tvheadend**, built in Kotlin with Leanback, AndroidX Media3 (`ExoPlayer`), and native HTSP streaming.

## Features

- **Android TV Input Framework (TIF) Integration:** Registers as a live TV input (`TvheadendInputService`) for Android TV / Google TV Live Channels, with a built-in Leanback channel guide and player.
- **Native HTSP Streaming & Demuxing:** Persistent authenticated HTSP socket pool (`HtspConnectionPool`), custom Media3 `HtspDataSource` and `HtspExtractor` (H.264, HEVC, MPEG-2, AAC, AC-3, E-AC-3, DVB subtitles), plus a pure-Kotlin MPEG-1/2 Layer II (`Mp2Decoder`) audio decoder.
- **Leanback TIF Setup & Live Logo Preview:** Guided setup flow with inline credential editing, **Test Server Connection**, real-time channel/logo (`picon`) import progress, and a D-pad-scrollable channel preview list.
- **EPG & Background Sync:** Syncs channels, picons, and EPG events into `TvContract` with periodic `WorkManager` updates (`TvhSyncWorker`).
- **Diagnostics (`TVH_STREAM_DEBUG`):** Optional Streaming Debug Mode in Settings for HTSP connection state (`[HTSP_STATE]`), tune-to-first-frame latency (`[TUNE_TELEMETRY]`), and demuxer diagnostics.
- **In-App Updater & GitHub Releases CI:** Checks GitHub Releases (`nmaclean/android-tvheadend-tif`) via **Settings → App Updates → Check for Updates**, downloads the `.apk` with progress, and launches the Android package installer via `FileProvider`.

## Requirements

- Android TV / Google TV device running Android 8.0+ (`minSdk = 26`, `targetSdk = 34`)
- Tvheadend server reachable over HTSP (default port `9982`) and HTTP (default port `9981`)
- JDK 17 & Android SDK (`compileSdk = 34`) for local builds

## Building & Testing

```bash
# Run unit tests and build the debug APK
./gradlew testDebugUnitTest assembleDebug

# Build the release APK
./gradlew assembleRelease
```

## Installing via ADB

```bash
adb connect <DEVICE_IP>:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Releases & CI Signing

Pushing a tag matching `v*` (e.g., `v1.0.0`) triggers [`.github/workflows/release.yml`](.github/workflows/release.yml), which runs unit tests, builds `assembleRelease`, and publishes the APK to GitHub Releases.

To sign CI release APKs with a consistent keystore (required for in-place updates), configure these repository secrets in **Settings → Secrets and variables → Actions**:

- `KEYSTORE_BASE64`: Base64-encoded `.jks` keystore (`base64 -w 0 release.jks`)
- `KEYSTORE_PASSWORD`: Keystore password
- `KEY_ALIAS`: Key alias
- `KEY_PASSWORD`: Key password
