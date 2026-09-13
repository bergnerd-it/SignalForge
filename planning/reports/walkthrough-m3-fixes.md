# Walkthrough: Milestone M3 Fixes & Verification

All defects from `planning/reports/M3-code-review.md` (CRITICAL-1, HIGH-1 through HIGH-5, and MEDIUM-1 through MEDIUM-5) have been remediated and verified under `planning/PROMPT-SIGNALFORGE-M3-FIXES.md`.

---

## 1. Summary of Changes

### Database Migration & Schema Hardening
- **Migration V5 (`V5__backtest_engine_hardening.sql`)**:
  - Implemented 4 child table triggers (`prevent_completed_daily_equity_insert`, `prevent_completed_orders_insert`, `prevent_completed_events_insert`, `prevent_completed_holdings_insert`) preventing any insertions into child tables for terminal runs (`COMPLETED`, `FAILED`, `CANCELLED`, `INTERRUPTED`).
  - Added composite listing foreign key triggers (`validate_backtest_run_listings_insert`) verifying candidate and benchmark listings exist in `dataset_listings` for the specified `dataset_id`.
- **`MigrationRunner.java` & Tests**: Updated current schema version to 5 and updated migration test assertions in `MigrationRecoveryIntegrationTest.java`.

### API Security & Parameter Protection
- **Series Parameter Validation (`HIGH-1`)**: Replaced string interpolation with strict `BacktestDtos.SeriesType` enum validation and parameterized JDBC query binding (`WHERE series_type = ?`), rejecting malicious inputs with HTTP 400 Bad Request.
- **Owner Scoping & Isolation (`HIGH-2`)**: Enforced `X-User-Id` header (defaulting to `"default"`) on all run endpoints. Detail, equity, orders, events, export, and cancel queries enforce `WHERE owner_id = ?`, returning non-disclosing HTTP 404 Not Found for cross-owner access.

### Reproducibility & Data Integrity
- **Frozen Configuration Snapshot (`HIGH-3`)**: Added `BacktestNormalizedConfig` persisted in `backtest_runs.config_json` during preflight and exported verbatim in `manifest.json`.
- **Deterministic Business IDs & Ordering (`HIGH-3`)**: Orders and events use deterministic business sequence IDs (`runId-series-ord-0001`, `runId-series-evt-0001`) and market event timestamps.
- **Strict Month-End & Cutoff Validation (`MEDIUM-1`)**: Enforced evaluation session as a completed month-end session, verified cutoff is on or after session close, and validated explicit end session presence.
- **Corporate Action Integrity (`MEDIUM-2`)**: Rejects non-EUR distributions, invalid split ratios, and corporate actions with `available_at` after session open (`lookahead unavailable`).
- **Full Timeline Cash Settlement (`MEDIUM-2`)**: Cash distributions settle according to payment timeline: pre-open, intraday, post-close, or closed-day boundaries.
- **Day 0 Initial Funded Point (`HIGH-5`)**: Added Day 0 funding point at evaluation date (`totalEquity = 1000.00 EUR`, `units = 0`). Annual returns calculated from Day 0, reconciling reference first-year return to exact `1.80%` (`0.0180`).
- **CSV Formula Escaping (`CRITICAL-1`)**: Fixed `escapeCsvText` in `BacktestExportService.java`. Escaping prefix (`'`) is only applied to text fields, never numeric values, formatting exact negative decimals (e.g., `-0.001`, `-0.001000`).

### Concurrency & Lifecycle
- **Concurrency-Safe Creation & Repeat Cancellation (`HIGH-4`)**: Switched from in-memory locks to database-level constraint conflict recovery on `DataIntegrityViolationException`. Cancellation is repeat-safe (including on `CANCELLED` status). State transitions conditionally check `WHERE status = 'RUNNING' AND cancel_requested = 0`.

### Angular Frontend Hardening
- **Strict TypeScript Models (`MEDIUM-4`)**: Added `PagedResponse<T>`, `SeriesType`, and `OrderStatus`, removing all `any`.
- **Dual-Series Equity Curve (`MEDIUM-4`)**: `BacktestService.loadDetails` fetches both `CANDIDATE` and `BENCHMARK` equity series via `forkJoin`. Chart renders both candidate (green `#10b981`) and benchmark (cyan `#06b6d4` dashed) polylines.
- **Stale Response Suppression (`MEDIUM-4`)**: Active request tracking discards out-of-order responses from previously selected runs.
- **Direct Deep-Link Resolution (`MEDIUM-4`)**: Deep links load runs directly by ID via `getRun(id)` regardless of list pagination.

---

## 2. Verification Results

### Automated Test Suites
- **Backend Tests (`./gradlew test`):**
  - **147 tests completed, 0 failed, 0 ignored (100% pass rate)**.
  - Duration: 4.615s.
- **Frontend Tests (`npm test -- --watch=false`):**
  - **34 tests completed, 0 failed (100% pass rate)** across 3 test suites (`services.spec.ts`, `app.spec.ts`, `components.spec.ts`).
  - Duration: 652ms.
- **Frontend Production Build (`npm run build`):**
  - Compiled cleanly in 1.762s with total initial bundle of 508.16 kB.

### Native Browser Walkthrough Evidence
- Performed end-to-end browser walkthrough using `browser_subagent` on disposable database:
  - Created backtest run `run-e55af907-6f05-4125-bf54-5f68ca649620`.
  - Observed completion to 100% with exact KPI reconciliation (`Cumulative Return: +1.80%`, `Final Equity: 1018.00 EUR`, `Commissions: 2.00 EUR`, `Turnover: 99.57%`).
  - Verified both Candidate (green) and Benchmark (cyan dashed) polylines render simultaneously on SVG chart.
  - Verified Orders, Audit Events, and Config tabs.
  - Verified direct URL deep link resolution (`/research/backtests/run-e55af907-6f05-4125-bf54-5f68ca649620`).
  - Recorded session: `m3_fixes_walkthrough_1789229931936.webp`.

### Downloaded Export ZIP Evidence
- **Archive:** `backtest-run-e55af907-6f05-4125-bf54-5f68ca649620-export.zip`
- **SHA-256:** `df3a04b15153de203235766b32a4f546d1a17d9be39b9d4129a1c85f0faf8c52`
- **Inspected Files:**
  - `manifest.json`: Verbatim snapshot config with all checksums and version metadata.
  - `summary.json`: Matches hand-calculated baseline.
  - `equity_series.csv`: Includes Day 0 (`2024-01-31,1000.00`), negative decimals formatted without `'` (`-0.001`).
  - `events.csv`, `orders.csv`, `holdings.csv`: Complete, formatted audit records.

---

## 3. Completion Artifacts & Backlog

- **Report:** [research-M3-fixes.md](file:///Users/oliver/Projects/bergnerd/SignalForge/planning/reports/research-M3-fixes.md)
- **Superseded:** [research-M3.md](file:///Users/oliver/Projects/bergnerd/SignalForge/planning/reports/research-M3.md)
- **Backlog:** `TASK-14` marked as **Done** with all 7 acceptance criteria verified.
