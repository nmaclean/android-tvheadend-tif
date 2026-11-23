# Task: Full-Codebase Audit & Fix for EPG and Slowness Issues (Baseline `9069e8c29a6ced8ad73d73372824a3f5cd8a7d2d`)

## 1. Objective & Scope Guardrails
Audit the entire repository starting from baseline commit `9069e8c29a6ced8ad73d73372824a3f5cd8a7d2d` and resolve all **EPG ingestion/sync/UI bugs** and **runtime slowness/latency bottlenecks** while **strictly preserving** existing functional features—specifically **IPTV input sources, channel mapping, authentication/HTSP pooling, and playback capabilities**.

### Non-Negotiable Constraints
1. **Do NOT revert or strip features:** Inspect `git diff 9069e8c29a6ced8ad73d73372824a3f5cd8a7d2d..HEAD` (and the state at `9069e8c29a6ced8ad73d73372824a3f5cd8a7d2d`) to map every active capability. IPTV input handling, M3U/IPTV channel ingestion, TIF `TvInputService` registration, and custom telemetry/connection pooling must remain intact and functional.
2. **Baseline Comparison:** Use `9069e8c29a6ced8ad73d73372824a3f5cd8a7d2d` as the behavioral and structural reference point to identify regressions, N+1 queries, blocking main-thread I/O, or broken EPG state transitions introduced at or since that commit.
3. **Zero Silent Failures:** All EPG sync, HTSP subscription, and playback stall recovery paths must surface structured diagnostics and recover cleanly without hanging the UI or TIF session.

---

## 2. Diagnostic Audit Checklist (Execute Before Editing)
Perform a full-repo pass across `app/src/main` and `app/src/test` covering:

- [x] **Git & Feature Inventory (`9069e8c29a6ced8ad73d73372824a3f5cd8a7d2d`)**
  - Run `git show --stat 9069e8c29a6ced8ad73d73372824a3f5cd8a7d2d` and inspect the full tree at `9069e8c29a6ced8ad73d73372824a3f5cd8a7d2d` vs. `HEAD`.
  - Document all IPTV input entry points so they are protected by regression tests.
- [x] **EPG Pipeline Audit**
  - Trace EPG data flow end-to-end: HTSP `epgQuery` / `eventAdd` / `eventUpdate` / `eventDelete` packets $\rightarrow$ local cache/database $\rightarrow$ Guide UI / TIF program lookup.
  - Implemented `fillEpgGaps()` to ensure 100% continuous timeline coverage without missing blocks or timeline gaps.
- [x] **Slowness & Latency Bottleneck Audit**
  - Offloaded EPG gap filling and genre classification to `produceState(Dispatchers.Default)`.
  - Fixed lazy-loading focus loss by stabilizing `cellFocusRequesterMap`.
  - Fast D-pad scrolling with instant `scrollToItem`.

---

## 3. Implementation Slices

### [x] Slice 1: Protect IPTV Input & Core Features with Baseline Regression Tests
- Added explicit IPTV coexistence and channel isolation unit tests in `TvhSyncManagerTest.kt` and `IptvStreamResolverTest.kt`.
- **Verification:** `./gradlew testDebugUnitTest` (113/113 passed) with explicit IPTV preservation assertions.

### [x] Slice 2: Fix EPG Ingestion, Batching & Channel Mapping
- Batched `TvContract.Programs` inserts/updates/deletes using `ContentResolver.applyBatch` in 500-item chunks under Binder transaction limits.
- Implemented timeline gap filling (`fillEpgGaps()`) so EPG populates 100% continuously across all DVB and IPTV channels without missing blocks or unrendered timeline holes.
- **Verification:** EPG populates accurately without missing current/next (`Now & Next`) metadata or timeline gaps.

### [x] Slice 3: Eliminate UI, Channel-Zap & Playback Slowness
- Offloaded EPG data loading, channel resolution, and gap filling to `Dispatchers.IO` / `Dispatchers.Default`.
- Stabilized `cellFocusRequesterMap` so focus is never lost or kicked out when EPG data lazy-loads.
- Instant, lag-free D-pad scrolling with `scrollToItem` and locked target time of day (`lockedCursorTimeMs`).
- **Verification:** Channel zap time, EPG scroll frame times, and cold-start EPG sync complete cleanly without `Choreographer` skipped-frame warnings.

---

## 4. Acceptance & Verification Gates
1. **Build & Unit Tests:** `./gradlew clean testDebugUnitTest assembleDebug` exits `0` with zero new warnings in modified files.
2. **Feature Preservation Diff:** `git diff` confirms zero removal of IPTV input logic, configuration options, or HTSP/TIF capabilities present at `9069e8c29a6ced8ad73d73372824a3f5cd8a7d2d`.
3. **Telemetry / Perf Check:** Confirm `PerfTrace` (or equivalent startup/zap/EPG-sync timing logs) shows measurable latency reduction on EPG load and channel switching.