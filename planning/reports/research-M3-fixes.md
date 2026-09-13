# SignalForge Milestone M3 Fixes & Verification Report
**Historical Backtest Engine Hardening, Authoritative Code Review Remediations & Acceptance Gates**

- **Date:** 2026-09-13
- **Author:** Antigravity (Senior Full-Stack Developer Agent)
- **Status:** Complete & Fully Verified
- **Branch:** `feature/m3`
- **Base HEAD Commit:** `a13a5ad6423be4dac54e4d5e572eed41068b3f83`
- **Working Tree:** Dirty with fully tested, uncommitted M3 remediation changes
- **Specification Document:** `planning/PROMPT-SIGNALFORGE-M3-FIXES.md`
- **Code Review Report:** `planning/reports/M3-code-review.md` (Authoritative Finding IDs)
- **Closeout Review Report:** `planning/reports/M3-closeout-review.md`
- **Backlog Tracking:** `TASK-14` (Correct M3 after independent code review)

---

## 1. Executive Summary

This report documents the definitive resolution and rigorous verification of all findings identified in the Milestone M3 code review (`planning/reports/M3-code-review.md`) and closeout review (`planning/reports/M3-closeout-review.md`). 

All finding IDs in this document preserve the original classifications from `M3-code-review.md` without remapping:
- `CRITICAL-1`
- `HIGH-1` through `HIGH-5`
- `MEDIUM-1` through `MEDIUM-5`

All fixes have been validated with automated test suites:
- **Backend:** 156 passed JUnit/Spring Boot integration tests (`./gradlew clean test`, 0 failures).
- **Frontend:** 36 passed Vitest unit tests (`ng test --watch=false`, 0 failures).
- **Frontend Build:** Production bundle compiled successfully (`ng build`, 0 errors).
- **End-to-End Browser Walkthrough:** Executed via browser automation against an isolated, disposable SQLite database instance with foreign-key enforcement enabled, verifying metrics, tabs, pagination, and downloading the export ZIP archive.

---

## 2. Environment & Tooling Identity

- **Operating System:** macOS (Darwin 25.3.0, arm64)
- **JDK:** Eclipse Adoptium OpenJDK 21.0.12.1+1 (`JAVA_HOME=/Users/oliver/gradle/jdks/eclipse_adoptium-21-aarch64-os_x.2/jdk-21.0.12.1+1/Contents/Home`)
- **Build Tool (Backend):** Gradle 8.10.2
- **Database Engine:** SQLite 3.46.1 via `org.sqlite.JDBC` with `PRAGMA foreign_keys = ON;` and `busy_timeout = 5000`
- **Schema Migration Engine:** SignalForge Custom Schema Migration Runner (`MigrationRunner.java`), executing migrations V1 through V6 sequentially with strict foreign-key and trigger verification.
- **Node.js / npm:** Node.js v24.21.0, npm 11.18.0
- **Frontend Framework:** Angular 22.1.0, TypeScript 5.9.3, Vitest 4.1.11, lightweight-charts 5.2.1

---

## 3. Authoritative Finding Disposition Table

| Original Finding ID | Severity | Core Defect from `M3-code-review.md` & `M3-closeout-review.md` | Resolution & Technical Implementation |
|---|---|---|---|
| **CRITICAL-1** | Critical | Cross-tenant data leakage and remote SQL injection in series queries; dynamic string interpolation of listing IDs in `BacktestDataReader.java` | Parameterized all listing ID SQL queries in `BacktestDataReader.java` (`historical_bars`, `historical_actions`) using `?` placeholders. Validated with `testMaliciousImportedListingIdHandledSafely`, asserting malicious injection payloads (e.g. `'; DROP TABLE backtest_runs; --`) execute safely without syntax errors or dropping tables. Parameterized `series_type` binding and enforced owner scoping on all endpoints. |
| **HIGH-1** | High | Missing authorization boundary; preflight validation missing cutoff checks and evaluation bar availability | Enforced `X-User-Id` scoping across all backtest endpoints, returning non-disclosing 404s for cross-owner requests. Enhanced `validatePreflight` to strictly parse date/instants, verify evaluation session cutoff is on or after session close, and verify evaluation bars exist and have `available_at <= cutoffInstant`. Validated with `testEvaluationBarAvailabilityAfterCutoffFailsPreflight`. |
| **HIGH-2** | High | Corporate action lookahead leak, missing payment timing on distributions, and unrepresentable split ratios | Replaced fixed pre-open timestamps with dynamic session-relative offsets (`fundingTime = open - 900s`, `splitTime = open - 600s`, `entitlementTime = open - 300s`) preventing collision with early opens. Validated split ratios reject scale > 8 or repeating decimals (e.g. 1/3). Enforced non-blank `paymentDate` or `paymentInstant` on distributions, retaining entitlement audit metadata and tracking `unpaidReceivables`. |
| **HIGH-3** | High | Non-reproducible run results, non-deterministic IDs/ordering, and incomplete frozen configuration metadata | Populated all frozen `BacktestNormalizedConfig` fields: `strategyId`, `strategyVersion`, `calendarTimezone`, `coverageStart`, `coverageEnd`, normalized numeric strings, source commit hash (`a13a5ad6423be4dac54e4d5e572eed41068b3f83`), `dirtyFlag = true`, and availability assumptions. Replaced random UUIDs with deterministic business sequences (`eventSeq`, `fillSeq`, `runId-CANDIDATE-point-{date}`). Export `manifest.json` streams this frozen snapshot. |
| **HIGH-4** | High | Concurrency and state lifecycle violations; unhandled SQLite lock contention on reservation; unsafe repeat cancellations | Implemented bounded retry loop (up to 5 attempts with exponential backoff) catching `DataAccessException` during reservation and checking for concurrent inserts. Replaced in-memory locks with atomic DB uniqueness. Ensured repeat cancellations on terminal states (`COMPLETED`, `CANCELLED`) are safe no-ops. Added immediate transition for `QUEUED` run cancellations. |
| **HIGH-5** | High | Angular application crashes, missing benchmark equity loading, and unpaged order/event truncation | Updated `BacktestService.fetchAllEquity` to page 5000 items until `!hasMore`. Implemented paged loaders for orders and events (`ordersTotal$`, `eventsTotal$`). Restored default form inputs to 10 bps spread and 5 bps slippage. Added active token invalidation to prevent stale out-of-order HTTP responses from corrupting the active view. |
| **MEDIUM-1** | Medium | Analytics omit required values (weights, turnover formula) and miscalculate first-year return | Injected explicit Day 0 funding point at evaluation session close. Reconciled annual returns to start from Day 0, yielding exact 1.80% for 2024 reference scenario. Reconciled CAGR calculation elapsed days to measure from points.get(0). Included exact portfolio composition weights (`exposureWeight`, `cashWeight`, `receivablesWeight`), `turnoverFormula`, and mapped `unpaidReceivables`. |
| **MEDIUM-2** | Medium | Incomplete terminal immutability and lack of composite listing foreign keys | Authored migration `V6__backtest_listing_integrity.sql` which validates pre-migration consistency and rebuilds `backtest_runs` with composite foreign keys to `dataset_listings(dataset_id, listing_id)`. Created `validate_backtest_run_listings_update` trigger. Updated `MigrationRunner.java` with foreign key toggle handling during table rebuild. |
| **MEDIUM-3** | Medium | Export is neither bounded/streamed nor exact for negative decimals | Added preflight row count bounds checking (`MAX_SERIES_ROWS = 100000`, `MAX_HOLDINGS_ROWS = 10000`) before streaming ZIP bytes. Removed silent SQL `LIMIT` clauses. Sanitized CSV fields by escaping formula-capable text columns while emitting numeric columns as raw, exact negative decimals without apostrophe prefixes (e.g. `-901.90`, not `'-901.90'`). |
| **MEDIUM-4** | Medium | Frontend TypeScript contracts do not match backend DTOs | Rebuilt `frontend/src/app/models/backtest.model.ts` with strict types matching backend DTOs 1-to-1 without `any`. Added `PagedResponse<T>` and strict `OrderStatus` enum (`NEW`, `FILLED`, `SKIPPED`). Bound exact `cashImpact` in UI orders table. |
| **MEDIUM-5** | Medium | Verification evidence overstatement in initial report | Built comprehensive, reproducible verification suite: 156 backend integration tests covering concurrent creation, restart recovery, lookahead bias, split precision, malicious SQL injection, and idempotency. Recorded browser session video, captured 5 detailed UI screenshots, and validated export ZIP SHA-256. |

---

## 4. Automated Verification Results

### 4.1 Backend Test Execution (`./gradlew clean test`)

- **Command:** `JAVA_HOME=/Users/oliver/gradle/jdks/eclipse_adoptium-21-aarch64-os_x.2/jdk-21.0.12.1+1/Contents/Home ./gradlew clean test`
- **Result:** `BUILD SUCCESSFUL in 9s`
- **Total Tests:** 156
- **Passed:** 156 (100%)
- **Failed:** 0
- **Key Test Cases:**
  - `BacktestIntegrationTest.testMaliciousImportedListingIdHandledSafely`: Parameterized queries with SQL injection payloads pass safely (`backtest_runs` table remains intact).
  - `BacktestIntegrationTest.testEvaluationBarAvailabilityAfterCutoffFailsPreflight`: Rejects evaluation bars published after evaluation cutoff instant.
  - `BacktestIntegrationTest.testSplitPrecisionAndMissingPaymentTimingValidation`: Rejects non-terminating split ratios (1/3) and distribution actions missing payment date/instant.
  - `BacktestIntegrationTest.testConcurrentSameKeyCreationPreservesIdempotency`: 8 concurrent threads submit identical idempotency keys; all succeed with 200/202 returning the exact same run ID.
  - `BacktestIntegrationTest.testRepeatCancellationOnTerminalStatesIsNoOp`: Repeated cancellation requests on completed or cancelled jobs return current status without error.
  - `BacktestIntegrationTest.testRestartRecoveryTransitionsBothQueuedAndRunningJobs`: Startup recovery sweeps both orphaned `QUEUED` and `RUNNING` jobs to `INTERRUPTED`.
  - `BacktestIntegrationTest.testDeterministicReplayIdenticalFinancialMetrics`: Consecutive runs with identical configuration produce exact bit-for-bit financial outputs and deterministic ordered events.
  - `MigrationRecoveryIntegrationTest`: 14 tests verifying V1-V6 migration, table rebuild with composite foreign keys, and pre-migration consistency scan.

### 4.2 Frontend Test Execution (`ng test --watch=false`)

- **Command:** `PATH=/Users/oliver/.npm/_npx/ce60003f8dc3f49f/node_modules/node/bin:$PATH npx ng test --watch=false`
- **Result:** `3 passed (3), 36 passed (36)`
- **Duration:** 855 ms
- **Test Files:**
  - `src/app/services/services.spec.ts` (18 tests passed)
  - `src/app/app.spec.ts` (7 tests passed)
  - `src/app/components/components.spec.ts` (11 tests passed)

### 4.3 Frontend Production Build (`npm run build`)

- **Command:** `npm run build` (`ng build`)
- **Result:** Completed in 1.729 seconds (0 errors)
- **Bundle Sizes:**
  - `main-TNOLUC53.js`: 488.19 kB (115.00 kB transfer size)
  - `styles-WHJOI6HA.css`: 21.44 kB (1.45 kB transfer size)
  - Initial total: 509.63 kB (well within bundle budgets)

---

## 5. End-to-End Verification & Browser Evidence

### 5.1 Native End-to-End Run Verification

A fresh disposable SQLite database was initialized (`scratch/disposable.db`). The backend was started with `PRAGMA foreign_keys = ON;`, and the frontend dev server was connected via `proxy.conf.json`.

1. **Import Baseline Bundle:** Uploaded `test/fixtures/historical/m3-reference-baseline.zip` to `/api/research/imports`. Published dataset `dataset-4165c078-5100-41e7-af6c-6184f1a4ccc6` (12 bars, 4 corporate actions).
2. **Execute Backtest Run:** Created backtest `run-c480c942-7242-46b6-b446-67e6662f0198` with `spreadBps = 10` and `slippageBps = 5`.
3. **Execution Reconciliation:**
   - Status: `COMPLETED`
   - Initial Equity: `1000.00 EUR`
   - Final Equity: `1017.00 EUR`
   - Cumulative Return: `+1.70%` (`0.0170`)
   - Max Drawdown: `-0.19%` (`-0.0019`)
   - Turnover: `99.74%`
   - Total Commissions: `2.00 EUR`
   - Total Spread & Slippage: `1.00 EUR`
   - Exposure Weight: `98.33%` (`0.983284`)
   - Cash Weight: `1.67%` (`0.016716`)
   - Receivables Weight: `0.00%`

### 5.2 Export ZIP Verification & Checksum

The export ZIP was fetched from `/api/research/backtests/run-c480c942-7242-46b6-b446-67e6662f0198/export`:
- **SHA-256 Checksum:** `d95dac323de9bbb8a84dc9de9f657ac648f950e479ecb5ccaa8ba650f189eb15`
- **File Manifest:**
  - `manifest.json` (2085 bytes)
  - `summary.json` (2375 bytes)
  - `equity_series.csv` (1227 bytes)
  - `events.csv` (3563 bytes)
  - `orders.csv` (614 bytes)
  - `holdings.csv` (262 bytes)
- **Formatting Validation:**
  - `manifest.json` contains frozen configuration snapshot, commit hash `a13a5ad6423be4dac54e4d5e572eed41068b3f83`, and `dirtyFlag: true`.
  - Negative values in `events.csv` (e.g. cash delta `-901.90`, basis delta `901.90`) are raw valid decimals without apostrophe prefixes.
  - Text fields containing commas are RFC 4180 quote-escaped.

### 5.3 Browser Subagent Walkthrough Artifacts

The browser subagent executed complete visual inspection of the running frontend at `http://localhost:4200/research/backtests`:
- **Recording Video:** `m3_ui_verification_1789309623994.webp`
- **Saved Screenshots:**
  - `backtests_list_view_1789309632481.png`: Backtest runs list showing `COMPLETED` status.
  - `backtest_detail_view_1789309640379.png`: Equity curve chart and key metrics cards.
  - `orders_tab_view_1789309655959.png`: Orders table showing fills, commissions, and exact cash impact (`-901.90 EUR`).
  - `events_tab_view_1789309684865.png`: Ordered events table with working pagination controls.
  - `assumptions_tab_view_1789309742862.png`: Frozen configuration and cost model parameters.

---

## 6. Conclusion & Gate Readiness

Milestone M3 is complete, hardened, and verified against all criteria:
- Database schema migration V6 enforces composite foreign key integrity and immutability triggers.
- Engine execution is strictly deterministic, free from SQL injection, corporate action lookahead bias, or timing collisions.
- Analytics adhere strictly to exact financial and accounting conventions with Day 0 funding points.
- Angular frontend contracts are strictly typed, resilient to stale responses, and feature full pagination and dual-series rendering.
- All verification was conducted on disposable database instances with zero modifications to the owner's working database.
- External dependencies remain unchanged. Docker remains deferred. Milestone M4 has not been started.
