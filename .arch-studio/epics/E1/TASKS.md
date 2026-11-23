# Gate 08 — Build Tasks (`TASKS.md`) — Epic `E1`

## 1. Title & Scope Metadata
- **Repository:** `nmaclean/android-tvheadend-tif` (`https://github.com/nmaclean/android-tvheadend-tif.git`)[cite: 1]
- **Archive Source:** `android-tvheadend-tif-clean.zip` (197 entries; grounded in archive manifest and prior gates, with file bodies uninspected in this pass)[cite: 1]
- **Gate & Deliverable:** Gate 08 — Build Tasks (`TASKS.md`)[cite: 1]
- **Active Epic:** `E1` (HTSP Core, Channel Registry, Repositories, Sync & TIF Input)[cite: 1]
- **Summary of LLD & Prior Design Inputs:** Builds on Gate 01 `brief.md`, Gate 02 `vision.html`, Gate 03 `mockup.html`, Gate 04 `hld.html`, Gate 05 `epic.html`, and Gate 06 `mockup-lld.html` / Gate 07 `lld.html` for `E1`, while noting that existing `.arch-studio/epics/E1/TASKS.md`, `.arch-studio/epics/E1/lld.html`, `.arch-studio/epics/E1/mockup-lld.html`, `docs/TIF-CROSS-INPUT-AUDIT.md`, `docs/boundary-contract-audit.md`, and `docs/SPLIT-APPS-FEASIBILITY.md` exist in the archive manifest[cite: 1] and require direct inspection before or alongside implementation.
- **Implementation Goal:** Establish a clean, well-tested, and boundary-safe `E1` foundation covering HTSP protocol/connection management, channel registry and repository/worker sync, and `TvheadendInputService` / TIF channel observation while keeping gaps and unverified details explicit.

## 2. Execution Strategy & Ordering
1. **Phase 1 — Audit & Baseline Reconciliation (Task 1):** Inspect existing E1 and audit/design artifacts in the archive (`docs/TIF-CROSS-INPUT-AUDIT.md`, `docs/boundary-contract-audit.md`, `docs/SPLIT-APPS-FEASIBILITY.md`, `.arch-studio/epics/E1/TASKS.md`, `.arch-studio/epics/E1/lld.html`)[cite: 1] so task changes align with established contracts and avoid cross-input TIF rework.
2. **Phase 2 — HTSP Wire, State & Connection Pool (Tasks 2–3):** Harden HTSP message handling, connection state transitions, and connection pooling before touching higher-level sync or TIF writers, preventing cascading reconnect/socket bugs.
3. **Phase 3 — Channel Registry, Repositories & Sync Workers (Tasks 4–5):** Align channel registry lookups, EPG/DVR/input channel repositories, account/sync services, and background sync orchestration on top of the stabilized HTSP layer.
4. **Phase 4 — TIF Input Service & Observer Hardening (Task 6):** Update `TvheadendInputService` and `TifChannelObserver` once registry and repository state transitions are consistent, ensuring clean TIF updates without cross-input collisions.
5. **Phase 5 — Unit Test Expansion & Gradle Verification (Task 7):** Expand JVM unit tests under `app/src/test/java/com/nmaclean/tvheadend/` and run Gradle checks so every task group has verifiable pass/fail evidence.

## 3. Ordered Build Tasks

### Task 1: Reconcile E1 & Cross-Input Audit Documentation
- **Scope:** Review the uninspected repository documentation and prior E1 files in the archive to confirm boundary rules, split-app/cross-input findings, and any pre-existing E1 tasks before editing code.
- **Target Files / Modules:** `docs/TIF-CROSS-INPUT-AUDIT.md`, `docs/boundary-contract-audit.md`, `docs/SPLIT-APPS-FEASIBILITY.md`, `.arch-studio/epics/E1/TASKS.md`, `.arch-studio/epics/E1/lld.html`[cite: 1].
- **Inputs / Outputs:** Inputs are the archive documents; output is a reconciled checklist of `E1` boundary constraints and unresolved gaps.
- **Acceptance Criteria:** Cross-input TIF rules, boundary contracts, and any `E1` tasks in `.arch-studio/epics/E1/TASKS.md`[cite: 1] are accounted for without fabricating unverified requirements.

### Task 2: Harden HTSP Message Framing & Connection State Transitions
- **Scope:** Review and refine HTSP message parsing/serialization and connection state handling so malformed messages, disconnects, and retries transition deterministically.
- **Target Files / Modules:** `app/src/main/java/com/nmaclean/tvheadend/htsp/Htsmsg.kt`, `app/src/main/java/com/nmaclean/tvheadend/htsp/HtspConnectionState.kt`, `app/src/main/java/com/nmaclean/tvheadend/model/TvhConstants.kt`[cite: 1].
- **Inputs / Outputs:** Inputs are HTSP message payloads and socket/session events; outputs are typed messages and valid `HtspConnectionState` transitions.
- **Acceptance Criteria:** Invalid/truncated messages and state transitions fail safely without unhandled exceptions, and unit tests in `HtsmsgTest.kt` and `HtspConnectionStateTest.kt`[cite: 1] pass.

### Task 3: Stabilize HTSP Client & Connection Pooling
- **Scope:** Ensure `HtspClient` and `HtspConnectionPool` manage pooled connections, request correlation, and reconnect/teardown lifecycles cleanly under transient network faults.
- **Target Files / Modules:** `app/src/main/java/com/nmaclean/tvheadend/htsp/HtspClient.kt`, `app/src/main/java/com/nmaclean/tvheadend/htsp/HtspConnectionPool.kt`, `app/src/main/java/com/nmaclean/tvheadend/model/TvhSettings.kt`, `app/src/main/java/com/nmaclean/tvheadend/model/TvhGeneralSettings.kt`[cite: 1].
- **Inputs / Outputs:** Inputs are server connection settings and HTSP requests; outputs are pooled active connections, dispatched responses, or bounded error/retry states.
- **Acceptance Criteria:** Reconnect and pool release paths do not leak connections or block callers indefinitely; baseline tests compile and pass.

### Task 4: Align Channel Registry & Domain / Repository Models
- **Scope:** Verify and harden `ChannelRegistry` along with channel, capability, DVR, and TIF settings models so channel metadata and capabilities remain consistent across sync and playback consumers.
- **Target Files / Modules:** `app/src/main/java/com/nmaclean/tvheadend/htsp/ChannelRegistry.kt`, `app/src/main/java/com/nmaclean/tvheadend/model/TvhChannel.kt`, `app/src/main/java/com/nmaclean/tvheadend/model/ChannelCapabilities.kt`, `app/src/main/java/com/nmaclean/tvheadend/model/TvhDvrModels.kt`, `app/src/main/java/com/nmaclean/tvheadend/model/TvhTifSettings.kt`[cite: 1].
- **Inputs / Outputs:** Inputs are HTSP channel/capability/DVR payloads; outputs are consistent registry entries and repository models.
- **Acceptance Criteria:** Channel lookups, capability checks, and model mappings are deterministic, and `ChannelRegistryTest.kt` and `ChannelCapabilitiesTest.kt`[cite: 1] pass.

### Task 5: Harden EPG, DVR & Input Channel Sync Orchestration
- **Scope:** Review and refine `EpgRepository`, `DvrRepository`, `InputChannelRepository`, `TvhSyncManager`, `TvhSyncWorker`, `BackgroundSyncStrategy`, `TvhAccountService`, and `SyncService` for idempotent sync and background/foreground coordination.
- **Target Files / Modules:** `app/src/main/java/com/nmaclean/tvheadend/sync/EpgRepository.kt`, `app/src/main/java/com/nmaclean/tvheadend/sync/DvrRepository.kt`, `app/src/main/java/com/nmaclean/tvheadend/sync/InputChannelRepository.kt`, `app/src/main/java/com/nmaclean/tvheadend/sync/TvhSyncManager.kt`, `app/src/main/java/com/nmaclean/tvheadend/sync/TvhSyncWorker.kt`, `app/src/main/java/com/nmaclean/tvheadend/sync/BackgroundSyncStrategy.kt`, `app/src/main/java/com/nmaclean/tvheadend/TvhAccountService.kt`, `app/src/main/java/com/nmaclean/tvheadend/SyncService.kt`[cite: 1].
- **Inputs / Outputs:** Inputs are HTSP sync batches, account/sync triggers, and worker schedules; outputs are updated EPG/DVR/input channel state and worker completion/retry results.
- **Acceptance Criteria:** Repeated or interrupted sync runs do not corrupt repository state, and `DvrRepositoryTest.kt` and `TvhSyncManagerTest.kt`[cite: 1] pass.

### Task 6: Harden `TvheadendInputService` & `TifChannelObserver` Boundaries
- **Scope:** Ensure `TvheadendInputService` and `TifChannelObserver` synchronize Tvheadend TIF channels and observe changes cleanly while respecting cross-input TIF isolation rules.
- **Target Files / Modules:** `app/src/main/java/com/nmaclean/tvheadend/TvheadendInputService.kt`, `app/src/main/java/com/nmaclean/tvheadend/sync/TifChannelObserver.kt`, `app/src/main/res/xml/tv_input.xml`, `app/src/main/res/xml/authenticator.xml`, `app/src/main/res/xml/syncadapter.xml`, `app/src/main/AndroidManifest.xml`[cite: 1].
- **Inputs / Outputs:** Inputs are synced channel/registry state and TIF observer notifications; outputs are TIF service/channel updates aligned with `docs/TIF-CROSS-INPUT-AUDIT.md`[cite: 1].
- **Acceptance Criteria:** TIF channel observation and service callbacks avoid cross-input interference, and `TifChannelObserverTest.kt`[cite: 1] passes.

### Task 7: Expand `E1` Unit Tests & Run Gradle Verification
- **Scope:** Add or update unit tests covering `E1` state transitions, registry/repository behavior, and observer/sync edge cases, then execute Gradle build/test verification.
- **Target Files / Modules:** `app/src/test/java/com/nmaclean/tvheadend/ChannelRegistryTest.kt`, `app/src/test/java/com/nmaclean/tvheadend/HtsmsgTest.kt`, `app/src/test/java/com/nmaclean/tvheadend/htsp/HtspConnectionStateTest.kt`, `app/src/test/java/com/nmaclean/tvheadend/model/ChannelCapabilitiesTest.kt`, `app/src/test/java/com/nmaclean/tvheadend/sync/DvrRepositoryTest.kt`, `app/src/test/java/com/nmaclean/tvheadend/sync/TifChannelObserverTest.kt`, `app/src/test/java/com/nmaclean/tvheadend/sync/TvhSyncManagerTest.kt`, `build.gradle.kts`, `app/build.gradle.kts`[cite: 1].
- **Inputs / Outputs:** Inputs are `E1` modules and test fixtures; outputs are passing JVM unit test results and a verified build/test log.
- **Acceptance Criteria:** `./gradlew test` (or `gradlew test`[cite: 1]) passes with no regressions in `E1` or adjacent unit tests.

## 4. Data, API & State Touchpoints
- **Domain & Capability Models:** `TvhChannel`, `ChannelCapabilities`, `TvhDvrModels`, `TvhSettings`, `TvhGeneralSettings`, `TvhTifSettings`, `TvhConstants`[cite: 1].
- **Protocol & Connection State:** HTSP messages (`Htsmsg`), client sessions (`HtspClient`), connection pool (`HtspConnectionPool`), connection lifecycle state (`HtspConnectionState`), and channel registry (`ChannelRegistry`)[cite: 1].
- **Repositories & Background Sync:** `EpgRepository`, `DvrRepository`, `InputChannelRepository`, `TvhSyncManager`, `TvhSyncWorker`, `BackgroundSyncStrategy`, `TvhAccountService`, `SyncService`[cite: 1].
- **TIF & Manifest/XML Touchpoints:** `TvheadendInputService`, `TifChannelObserver`, `AndroidManifest.xml`, `res/xml/tv_input.xml`, `res/xml/authenticator.xml`, `res/xml/syncadapter.xml`[cite: 1]. *(Exact database/provider URIs, keys, and ports are not verified from file contents in this pass.)*

## 5. Verification & Test Plan
- **Build & Unit Test Command:** Run `./gradlew test` (using `gradlew` and Gradle Kotlin DSL files `build.gradle.kts`, `app/build.gradle.kts`)[cite: 1] to execute unit tests under `app/src/test/java/com/nmaclean/tvheadend/`.
- **Task Group Pass/Fail Criteria:**
  - **Tasks 2–3 (HTSP & Pool):** Pass when `HtsmsgTest`, `HtspConnectionStateTest`, and any added HTSP pool/client unit tests pass with zero failures; fail if message parsing or reconnect/state logic throws unhandled errors or leaks state.
  - **Tasks 4–5 (Registry, Repos & Sync):** Pass when `ChannelRegistryTest`, `ChannelCapabilitiesTest`, `DvrRepositoryTest`, and `TvhSyncManagerTest` pass and idempotent sync behavior is verified; fail on duplicate/stale repository corruption or broken worker/sync orchestration.
  - **Task 6 (TIF Service & Observer):** Pass when `TifChannelObserverTest` passes, manifest/XML declarations (`tv_input.xml`, `authenticator.xml`, `syncadapter.xml`) remain valid, and cross-input audit constraints are respected; fail if cross-input channel collisions or observer loops occur.
  - **Manual / On-Device Verification (Explicitly Flagged as Separate from JVM Unit Tests):** Live Tvheadend HTSP server sync, Android TV TIF channel guide population, and `SyncService` / `TvhSyncWorker` background behavior on an Android TV emulator or physical device are not covered by JVM unit tests alone and should be checked manually on target hardware/emulator.

## 6. Risks, Rollback & Edge Cases
- **Server Disconnects & Reconnect Loops:** Transient network drops or Tvheadend restarts can trigger rapid socket retries; mitigate with clear `HtspConnectionState` transitions and bounded pool/worker retry behavior.
- **Cross-Input TIF Interference:** Concurrent updates across `TvheadendInputService`, `TifChannelObserver`, and IPTV TIF sync can cause duplicate or overwritten channels; mitigate by honoring `docs/TIF-CROSS-INPUT-AUDIT.md` and `docs/boundary-contract-audit.md`[cite: 1] boundaries.
- **Partial Syncs & Process/Service Teardown:** Interrupted EPG/DVR batches or worker/service restarts must remain idempotent so subsequent syncs reconcile state cleanly.
- **Rollback & Recovery Steps:** Isolate changes by task/commit in Git so any regressions in `htsp/`, `sync/`, or `TvheadendInputService` can be reverted cleanly to the last green `./gradlew test` baseline; if a bad sync state is observed during testing, re-run a clean full sync against the test Tvheadend instance or reset local app test data.

## 7. Open Questions & Handoff Notes
- **Uninspected Files to Read in Execution:** Inspect `.arch-studio/epics/E1/TASKS.md`, `.arch-studio/epics/E1/lld.html`, `docs/TIF-CROSS-INPUT-AUDIT.md`, `docs/boundary-contract-audit.md`, `docs/SPLIT-APPS-FEASIBILITY.md`, and the target `E1` Kotlin source files[cite: 1] to replace inferred details with exact implementation specifics.
- **Configuration & Version Unknowns:** Exact Android SDK/dependency versions in `app/build.gradle.kts`, Tvheadend/HTSP defaults, and `TvhSyncWorker` / `BackgroundSyncStrategy` scheduling intervals were not inspected in this pass and must be checked directly in the source/Gradle files.
- **Handoff to Implementation:** Proceed task-by-task in order (Tasks 1 through 7), keeping each change scoped to `E1` and running `./gradlew test` after each code-modifying task group.