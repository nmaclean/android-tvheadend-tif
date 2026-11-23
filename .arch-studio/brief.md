# Gate 01 — Brainstorm & Framing: `android-tvheadend-tif`

## 1. Title & Metadata
- **Project / Repository Name:** `nmaclean/android-tvheadend-tif` (Android TVheadend TV Input Framework client)
- **Remote Git URL:** `https://github.com/nmaclean/android-tvheadend-tif.git`
- **Target Tech Stack:** Kotlin, Android / Android TV (TV Input Framework — TIF), Gradle (`build.gradle.kts`, `app/build.gradle.kts`), Jetpack Compose / Android UI & Leanback/TV components, Media3/ExoPlayer-style custom data sources & extractors (`HtspDataSource`, `HtspDvrDataSource`, `HtspExtractor`, `Mp2Decoder`), WorkManager (`TvhSyncWorker`, `IptvEpgSyncWorker`), JUnit unit tests (`app/src/test/...`), GitHub Actions (`.github/workflows/release.yml`). *(Exact library versions and UI framework details are not confirmed from file contents in the archive listing alone.)*
- **Mode:** Inspect Existing Code (grounded in the archive entry list and metadata; prior `.arch-studio/` and `docs/` artifacts exist in the archive listing, though their internal text was not inspected).

## 2. Problem Statement & Core Outcomes
- **Problem Being Solved:** Bringing live TV, electronic program guide (EPG), and DVR/recording capabilities from a Tvheadend server—as well as curated IPTV (M3U / XMLTV) streams—into the Android TV / TV Input Framework (TIF) ecosystem with low-latency playback, LAN curation ergonomics, and background synchronization.
- **Who Uses It:** Android TV / Google TV users running a Tvheadend backend and/or IPTV playlists/EPG feeds on a local network (LAN), and maintainers/developers evolving the TIF integration, HTSP/IPTV pipelines, and TV UI.
- **Measurable Outcomes (Baseline / Target Framing):**
  1. **Reliable TIF & Live Playback:** Live and DVR streams tune and play through `TvheadendInputService` / `IptvTvInputService` and custom HTSP playback components (`HtspDataSource`, `HtspDvrDataSource`, `HtspExtractor`, `Mp2Decoder`) with measurable tune telemetry (`TuneTelemetryTracker`).
  2. **Consistent Channel, EPG & DVR Sync:** Background and foreground synchronization (`TvhSyncManager`, `TvhSyncWorker`, `EpgRepository`, `DvrRepository`, `IptvEpgSyncer`, `IptvEpgSyncWorker`, `TifAllowlistSyncer`) keeps channels, program guides, recordings, and allowlists aligned without UI freezes.
  3. **Streamlined Setup & LAN Curation:** Users can configure Tvheadend (`TvhSetupFragment`, `SetupActivity`) and IPTV sources (`IptvSetupWizardScreen`, `M3uCurationScreen`, `LanCurationServer`) with minimal friction on TV remotes or via LAN web curation.
  4. **Unit Test Coverage & Release Automation:** Automated unit tests under `app/src/test/java/com/nmaclean/tvheadend/` and release workflow (`.github/workflows/release.yml`) support repeatable verification and packaging.

## 3. Current State vs Target Scope
### Already Present in the Repository (Verified via Archive Entry List)
- **Existing Framing & Architecture Docs:** `.arch-studio/brief.md`, `.arch-studio/vision.html`, `.arch-studio/hld.html`, `.arch-studio/epic-breakdown.html`, `.arch-studio/mockup-concept.html`, `.arch-studio/epics/E1/` (`TASKS.md`, `lld.html`, `mockup-lld.html`), `README.md`, `AGENTS.md`, `tasks.md`, and `docs/` (`SPLIT-APPS-FEASIBILITY.md`, `TIF-CROSS-INPUT-AUDIT.md`, `boundary-contract-audit.md`, plus reference screenshots under `docs/reference/`).
- **Tvheadend & HTSP Stack:** `TvheadendInputService.kt`, `htsp/` (`Htsmsg.kt`, `HtspClient.kt`, `HtspConnectionPool.kt`, `HtspConnectionState.kt`, `ChannelRegistry.kt`), `model/` (`TvhChannel.kt`, `ChannelCapabilities.kt`, `TvhDvrModels.kt`, `TvhSettings.kt`, `TvhGeneralSettings.kt`, `TvhTifSettings.kt`, `TvhConstants.kt`), account/sync services (`TvhAccountService.kt`, `SyncService.kt`, `TvhSyncManager.kt`, `TvhSyncWorker.kt`, `BackgroundSyncStrategy.kt`, `DvrRepository.kt`, `EpgRepository.kt`, `InputChannelRepository.kt`, `TifChannelObserver.kt`).
- **Custom Player & AFR:** `player/` (`HtspDataSource.kt`, `HtspDvrDataSource.kt`, `HtspExtractor.kt`, `Mp2Decoder.kt`, `InputPlaybackStrategy.kt`) and `player/afr/` (`AfrMode.kt`, `AfrPlayerObserver.kt`, `AfrSettingsStore.kt`, `DisplayFrameRateController.kt`, `FrameRateMatcher.kt`, `TifAfrSessionHelper.kt`).
- **IPTV, M3U/XMLTV & LAN Curation:** `iptv/tif/` (`IptvTvInputService.kt`, `IptvStreamResolver.kt`, `TifAllowlistSyncer.kt`), `iptv/parser/StreamingM3uParser.kt`, `iptv/epg/` (`StreamingXmltvParser.kt`, `IptvEpgSyncer.kt`, `IptvEpgSyncWorker.kt`, `IptvEpgSettingsStore.kt`, `IptvEpgModels.kt`), `iptv/scan/` (`InputSourceHandler.kt`, `IptvStreamScanner.kt`, `SourceScanManager.kt`), `iptv/logo/IptvLogoManager.kt`, `iptv/storage/AllowlistRepository.kt`, `iptv/server/` (`LanCurationServer.kt`, `LanNetworkUtils.kt`), and asset `app/src/main/assets/curation/index.html`.
- **TV UI, Setup, Settings & Updater:** `MainActivity.kt`, `SetupActivity.kt`, `TvPlayerActivity.kt`, `DvrPlayerActivity.kt`, `ChannelGridActivity.kt`, `GeneralSettingsActivity.kt`, `InputManagerActivity.kt`, `ScanningInputsActivity.kt`, screens/fragments (`HomeFragment.kt`, `ChannelGuideFragment.kt`, `EpgTimelineOverlayFragment.kt`, `GuideScreen.kt`, `LiveTvScreen.kt`, `RecordingsScreen.kt`, `SearchScreen.kt`, `SettingsFragment.kt`, `TvhSetupFragment.kt`, `IptvSetupWizardScreen.kt`, `M3uCurationScreen.kt`, `M3uCurationViewModel.kt`), OSD/modals/navigation (`PlayerOsdOverlay.kt`, `ProgramDetailsModal.kt`, `TrackSelectionOverlay.kt`, `TvhActionModal.kt`, `TvhNavigationRail.kt`, `ChannelThumbnailCard.kt`), and `updater/` (`GitHubUpdateManager.kt`, `ApkInstaller.kt`, `SemVerComparator.kt`, `UpdateInstallReceiver.kt`, `UpdatePromptDialog.kt`).
- **Unit Tests:** 30 test files under `app/src/test/java/com/nmaclean/tvheadend/` covering HTSP, IPTV parsing/EPG/allowlist/LAN curation, AFR frame-rate matching, DVR/sync repositories, telemetry, and UI state/sync.

### Missing / Unverified / Target Gaps
- **Uninspected File Contents:** Because only the archive manifest and metadata were provided in this context, exact implementation completeness, edge-case coverage, or unimplemented items inside the source files and existing `.arch-studio/` / `docs/` files cannot be verified without reading those files.
- **Cross-Input & Split-App Boundaries:** `docs/TIF-CROSS-INPUT-AUDIT.md`, `docs/boundary-contract-audit.md`, and `docs/SPLIT-APPS-FEASIBILITY.md` exist in the repository, indicating active architectural scrutiny around cross-input TIF behavior, boundary contracts, and whether Tvheadend and IPTV should remain combined or split—decisions that must be reconciled before locking `vision.html` and `hld.html`.
- **Instrumented / On-Device TV Verification:** Unit tests are present in `app/src/test/`, while Android TV hardware/emulator TIF playback, AFR display switching, and LAN curation flows require device-level validation not evidenced in the test directory listing.

## 4. Key Screens, Surfaces & User Flows
- **Primary TV & Playback Surfaces:**
  - `MainActivity` & `TvhNavigationRail` hosting navigation across home (`HomeFragment`), live TV (`LiveTvScreen`), program guide (`GuideScreen`, `ChannelGuideFragment`, `EpgTimelineOverlayFragment`), recordings (`RecordingsScreen`), and search (`SearchScreen`).
  - `TvPlayerActivity` and `DvrPlayerActivity` with `PlayerOsdOverlay`, `ProgramDetailsModal`, `TrackSelectionOverlay`, and `TvhActionModal`.
  - `ChannelGridActivity` and `ChannelThumbnailCard` / card presenters (`ChannelCardPresenter`, `HomeActionPresenter`, `InputCardPresenter`).
- **Setup, Curation & Settings Surfaces:**
  - `SetupActivity` & `TvhSetupFragment` (with `TvhSetupInputValidator`) for Tvheadend connection setup.
  - `IptvSetupWizardActivity` / `IptvSetupWizardScreen`, `IptvSetupFragment`, `InputManagerActivity`, and `ScanningInputsActivity` for IPTV source setup and stream scanning.
  - `M3uCurationActivity` / `M3uCurationScreen` / `M3uCurationViewModel` paired with `LanCurationServer` and `app/src/main/assets/curation/index.html` for browser-based LAN curation.
  - `GeneralSettingsActivity` and `SettingsFragment` (backed by `res/xml/preferences.xml`).
- **End-to-End Flows:**
  1. **Tvheadend Live & DVR Flow:** Configure server -> authenticate/sync via HTSP (`HtspClient`, `HtspConnectionPool`, `TvhSyncManager`, `DvrRepository`, `EpgRepository`) -> publish/observe channels via Android TIF (`TvheadendInputService`, `TifChannelObserver`) -> tune & decode via `HtspDataSource` / `HtspDvrDataSource` / `HtspExtractor` / `Mp2Decoder` and `DisplayFrameRateController` (`TvPlayerActivity` / `DvrPlayerActivity`).
  2. **IPTV & LAN Curation Flow:** Add/scan M3U/XMLTV sources (`StreamingM3uParser`, `StreamingXmltvParser`, `SourceScanManager`, `IptvStreamScanner`) -> optionally curate via `LanCurationServer` on the LAN -> persist allowlist/logos (`AllowlistRepository`, `IptvLogoManager`) -> sync to TIF (`TifAllowlistSyncer`, `IptvTvInputService`, `IptvStreamResolver`).
  3. **App Update Flow:** Check releases via `GitHubUpdateManager` & `SemVerComparator` -> prompt via `UpdatePromptDialog` -> download/install via `ApkInstaller` & `UpdateInstallReceiver` (using `res/xml/file_paths.xml`).

## 5. System & Integration Boundaries
- **Local Persistence & Android System Integration:**
  - Android TV Input Framework (`android.media.tv.TvInputService`) declarations in `res/xml/tv_input.xml` and `res/xml/iptv_tv_input.xml`.
  - Android Account & Sync framework (`TvhAccountService`, `SyncService`, `res/xml/authenticator.xml`, `res/xml/syncadapter.xml`) alongside WorkManager (`TvhSyncWorker`, `IptvEpgSyncWorker`) and settings/repository stores (`IptvEpgSettingsStore`, `AfrSettingsStore`, `AllowlistRepository`, `IptvLogoManager`, `TvhUiRepository`).
- **LAN & External Protocols:**
  - **HTSP (Tvheadend Streaming Protocol):** Custom message framing (`Htsmsg`), connection pooling/state (`HtspConnectionPool`, `HtspConnectionState`, `HtspClient`), and stream/DVR extraction (`HtspDataSource`, `HtspDvrDataSource`, `HtspExtractor`).
  - **IPTV & EPG Feeds:** M3U playlist and XMLTV program guide streams (`StreamingM3uParser`, `StreamingXmltvParser`) and resolved stream URLs (`IptvStreamResolver`).
  - **LAN HTTP Curation:** Embedded server (`LanCurationServer`, `LanNetworkUtils`) serving `assets/curation/index.html` over the local network.
  - **External Release Check:** GitHub release API/downloads (`GitHubUpdateManager`).
- **Hardware & Service Dependencies:**
  - Tvheadend server on the network, IPTV/XMLTV stream hosts, Android TV/Google TV display & refresh-rate APIs (`DisplayFrameRateController`, `FrameRateMatcher`), and audio/video decoders (including custom `Mp2Decoder` alongside platform/Media3 codecs).

## 6. Non-Functional Guardrails
- **Latency & Tune Budgets:** Live channel tuning and frame rate adaptation must stay responsive for TV remote navigation; `TuneTelemetryTracker` exists to measure tuning performance, and `DisplayFrameRateController` / `FrameRateMatcher` manage display refresh-rate matching. *(Specific numeric latency/boot budgets are not stated in the archive manifest.)*
- **Offline & LAN Resilience:** Must tolerate transient LAN/server disconnects, reconnect pooled HTSP connections (`HtspConnectionPool`, `HtspConnectionState`), and run background sync/EPG updates resiliently (`TvhSyncWorker`, `IptvEpgSyncWorker`, `BackgroundSyncStrategy`).
- **Security & Privacy Constraints:** `LanCurationServer` exposes a LAN HTTP interface—network binding/exposure (`LanNetworkUtils`), credentials/tokens in account/settings stores, and APK installation permissions/file providers (`ApkInstaller`, `res/xml/file_paths.xml`) require careful scoping to local/trusted contexts. *(Exact security rules are not verified from file contents in this pass.)*
- **Operational Simplicity:** TV-first D-pad navigation, guided setup wizards (`TvhSetupFragment`, `IptvSetupWizardScreen`), browser-based LAN curation to avoid tedious remote typing, and in-app GitHub update checks (`GitHubUpdateManager`).

## 7. Assumptions, Risks & Open Questions
- **Assumptions:**
  - The archive entry list accurately reflects the current repository layout on `nmaclean/android-tvheadend-tif`.
  - Existing `.arch-studio/` (`vision.html`, `hld.html`, `epic-breakdown.html`, `epics/E1/...`) and `docs/` files represent prior architectural work that should be aligned with rather than blindly contradicted.
- **Technical Risks & Failure Modes:**
  - **HTSP / Stream Edge Cases:** Complex MPEG-TS/HTSP muxes, audio formats (e.g., MP2), or server state transitions could cause stalls, A/V drift, or reconnect loops if `HtspExtractor`, `HtspClient`, or `Mp2Decoder` encounter malformed/unusual streams.
  - **TIF & Multi-Input Conflicts:** Sharing Android TV's TIF channel database across Tvheadend and IPTV inputs (`TvheadendInputService` vs `IptvTvInputService`, `TifChannelObserver`, `TifAllowlistSyncer`) risks duplicate channels, stale TIF entries, or cross-input regressions (`docs/TIF-CROSS-INPUT-AUDIT.md`).
  - **Large M3U / XMLTV & Background Sync Load:** Large IPTV playlists or XMLTV guides can spike memory or I/O if streaming parsers (`StreamingM3uParser`, `StreamingXmltvParser`) or sync workers (`IptvEpgSyncWorker`, `TvhSyncWorker`) run without bounded batching/throttling.
  - **LAN Curation Exposure:** Running `LanCurationServer` on a LAN could allow unintended local clients to modify curation state if unauthenticated or left active unnecessarily.
- **Open Questions to Resolve Before Locking `vision.html` and `hld.html`:**
  1. What are the exact conclusions and recommendations in `docs/SPLIT-APPS-FEASIBILITY.md`, `docs/TIF-CROSS-INPUT-AUDIT.md`, and `docs/boundary-contract-audit.md`, and how do they align with `.arch-studio/vision.html` and `.arch-studio/hld.html`?
  2. Which parts of the Tvheadend and IPTV pipelines are already production-complete versus partial or experimental in the current source code?
  3. What are the target numeric SLAs/budgets (e.g., channel tune time, cold start time, EPG sync intervals, max playlist/XMLTV sizes) and supported Android / TV API levels in `app/build.gradle.kts`?
  4. How should `LanCurationServer` lifecycle and access controls behave by default (e.g., auto-stop, session/PIN guard, or LAN-only assumption)?