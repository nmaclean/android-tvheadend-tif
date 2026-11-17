# Project Rules: `android-tvheadend-tif`

## 1. Environment & Build Commands
- **Working Directory:** `~/build/android-tvheadend-tif`
- **Build & Test Gate:** `./gradlew assembleDebug testDebugUnitTest`
- **Clean Build (if needed):** `./gradlew clean assembleDebug`
- Do not modify `local.properties` (keep the Ubuntu server's Linux Android SDK path intact).

## 2. Architecture & Coding Standards
- **Language & Stack:** Kotlin, Android TV Input Framework (TIF), Leanback / Compose for TV, and AndroidX Media3 (`ExoPlayer`).
- **TV Lifecycle & Streaming:** Ensure `TvInputService.Session` lifecycles properly release `ExoPlayer` instances, audio focus, and HTSP/network streams on `onRelease()` to prevent background decoder leaks.
- **UI & D-Pad Navigation:** All new TV UI components must handle D-pad focus states (`focusable`, visible focus rings) and avoid touch-only gestures.

## 3. Git Checkpoint Workflow
- Before editing code, verify you are on a `feat/*` or `fix/*` branch (`git checkout -b feat/<short-name>`).
- Break large features into logical milestones. After each milestone: 
  1. Run `./gradlew assembleDebug testDebugUnitTest` and fix any compiler or test errors before continuing.
  2. Stage only modified project files (`git add <files>`) and commit locally using Conventional Commits (e.g., `feat(tif): add EPG channel sync retry`).
