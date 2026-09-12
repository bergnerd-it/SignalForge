# SignalForge Milestone M3 Completion Report
**Historical Backtest Engine & Buy-and-Hold Baseline (`ETF_BUY_HOLD_V1`)**

- **Date:** 2026-09-12
- **Author:** Antigravity (Senior Full-Stack Developer Agent)
- **Status:** Complete & Fully Verified
- **Branch:** `feature/m3`
- **Specification Document:** `planning/PROMPT-SIGNALFORGE-M3.md`
- **Baseline Walkthrough:** `planning/docs/backtest-baseline.md`
- **Backlog Tracking:** `TASK-13` (Implement M3 - Backtest Engine and Buy-and-Hold Baseline)

---

## 1. Executive Summary

Milestone M3 delivers a deterministic historical backtest engine with the `ETF_BUY_HOLD_V1` baseline strategy, modeled execution costs, cash/receivables split ledger accounting, an independently accounted S1 benchmark, asynchronous job lifecycle, canonical hash deduplication, core portfolio analytics, an interactive Angular workstation at `/research/backtests`, and self-contained reproduction ZIP exports.

### Scope Delivered
1. **Schema & Immutability:** V4 Flyway migration (`V4__backtest_engine.sql`) introducing 5 tables (`backtest_runs`, `backtest_daily_equity`, `backtest_orders`, `backtest_events`, `backtest_holdings`) and 10 SQLite triggers enforcing append-only immutability for completed/terminal runs.
2. **Deterministic Backtest Engine:** Zero lookahead bias event-loop processing historical sessions, splits, entitlements, payment settlement, and market order execution with whole-unit affordability allocation.
3. **Execution Modeling & Exact Accounting:** Modeled commission per fill (applied directly to cash balance), half-spread, and slippage; cash/receivables split ensuring cash cannot be spent until post-close payment settlement.
4. **Independent Benchmark:** S1 Buy-and-Hold benchmark strategy funded with the exact same initial cash, subjected to identical execution cost parameters, and simulated independently through its own ledger instance.
5. **Asynchronous Lifecycle & Deduplication:** State machine (`QUEUED`, `RUNNING`, `COMPLETED`, `FAILED`, `CANCELLED`, `INTERRUPTED`) with single-worker thread pool, concurrency-safe atomic transitions, canonical SHA-256 configuration hashing, and crash/restart recovery.
6. **Analytics & Reproduction Archive:** Cumulative return, CAGR, max drawdown (with peak, trough, recovery dates and duration), annualized volatility (252-day basis), turnover ratio, calendar annual returns table, and streaming export ZIP containing `manifest.json`, `summary.json`, `equity_series.csv`, `orders.csv`, `events.csv`, and `holdings.csv`.
7. **Angular Research UI:** Complete workstation view at `/research/backtests` and `/research/backtests/:id` with creation modal, progress polling, KPI summary cards, responsive SVG equity and underwater drawdown charts, tabbed deep dives, and export download links.

### Verification Summary
- **Backend Test Suite:** 100% pass across all tests (`MigrationRecoveryIntegrationTest`, `BacktestEngineTest`, `BacktestAnalyticsCalculatorTest`, `BacktestControllerTest`, `BacktestIntegrationTest`).
- **Frontend Test Suite:** 100% pass across all 34 Vitest unit tests (`services.spec.ts`, `app.spec.ts`, `components.spec.ts`). Clean production bundle build (`npm run build`).
- **End-to-End Walkthrough:** Native Chrome browser walkthrough verifying run listing, run creation modal, real-time polling, completed run KPI cards, SVG charts, tab navigation across daily equity, orders, events, and configuration, and ZIP export links.

---

## 2. Architecture & Design Decisions

### 2.1 Database Schema & Immutability Triggers
The schema is defined in `V4__backtest_engine.sql` and maintains strict foreign key constraints back to `datasets(id)` and `dataset_listings(dataset_id, listing_id)`.

- `backtest_runs`: Tracks run configuration, canonical hash, lifecycle status, progress percentage, failure reasons, and embedded JSON summaries.
- `backtest_daily_equity`: Records daily marked-to-market balances (`cash_balance`, `holdings_value`, `receivables_balance`, `total_equity`, `daily_return`, `drawdown`, `units`, `closing_price`) for both `CANDIDATE` and `BENCHMARK` series.
- `backtest_orders`: Records all order placements, execution status (`FILLED`), raw open prices, modeled fill prices, commission costs, and cash impacts.
- `backtest_events`: Detailed audit log of every state transition (`EXECUTION`, `CLOSING_MARK`, `SPLIT`, `ENTITLEMENT`, `PAYMENT`).
- `backtest_holdings`: Final terminal holdings snapshot.

**Immutability Protection:**
Ten SQLite database triggers (`trg_backtest_runs_immutable_update`, `trg_backtest_runs_immutable_delete`, and update/delete triggers on all child tables) abort any mutation once `status` is in `('COMPLETED', 'FAILED', 'CANCELLED', 'INTERRUPTED')`.

### 2.2 Event Loop & Order of Operations
Within each trading session, the engine enforces strict chronological sequencing:
1. **Pre-Open Corporate Actions (08:55:00 UTC):**
   - Splits: Ratio applied to existing units and cost basis per unit ($u' = u \times r$, $c' = c / r$).
   - Cash Distribution Entitlements: Effective on ex-date; credits `receivables` ($u \times d$) and decrements unadjusted share price expectation. Cash is not credited yet.
2. **Session Open (09:00:00 UTC):**
   - Order Evaluation & Execution: Strategy generates signals. Initial buy executes on Session 1. Reinvest orders execute when accumulated cash meets affordability.
   - Sizing: Units are floored to integer (`floor(available_cash - commission) / modeled_price`). Modeled price: $P_{\text{fill}} = P_{\text{raw}} \times (1 + \text{spread}/2 + \text{slippage})$.
   - Cash Impact: $\text{cash} \leftarrow \text{cash} - (U \times P_{\text{fill}} + \text{commission})$.
3. **Session Close Mark-to-Market (16:30:00 UTC):**
   - Holdings valued at raw close price ($V_H = U \times P_{\text{close}}$). Hypothetical liquidation costs are explicitly excluded from mark-to-market equity.
   - Total equity: $E = \text{Cash} + V_H + \text{Receivables}$.
4. **Post-Close Payment Settlement (16:30:00+ UTC):**
   - Entitled receivables whose `payment_date` has arrived convert from `receivables` into `cash` ($\text{receivables} \leftarrow \text{receivables} - R$, $\text{cash} \leftarrow \text{cash} + R$). Settled cash becomes available for orders only on subsequent sessions.

### 2.3 Canonical Hash Deduplication
Identical backtest requests map to an identical SHA-256 canonical hash:
$$\text{Hash} = \text{SHA256}(\text{strategyId} \mid \text{version} \mid \text{datasetId} \mid \text{candidate} \mid \text{benchmark} \mid \text{cutoff} \mid \text{start} \mid \text{end} \mid \text{cash} \mid \text{curr} \mid \text{comm} \mid \text{spread} \mid \text{slip})$$
If a request matches an existing completed run, the completed summary is returned immediately without duplicate execution. If a request matches a running or queued job, the client joins the in-progress run.

### 2.4 Independent Benchmark Architecture
The benchmark is not a synthetic index proxy; it is a full, second instance of `BacktestEngine` running `ETF_BUY_HOLD_V1` on the specified benchmark listing. It is funded with the exact same initial cash, incurs the same commission/spread/slippage models, and tracks its own ledger, orders, and daily equity series.

---

## 3. Verification & Validation Results

### 3.1 Hand-Calculation Reconciliation
The engine output was reconciled against the hand-calculated walkthrough in `planning/docs/backtest-baseline.md` based on the Section 9 reference scenario:
- Initial Cash: 1,000.00 EUR
- Candidate & Benchmark: `listing-eur-syn-1`
- Start Date: 2024-01-31 | Evaluation Cutoff: 2024-01-31T23:59:59Z | End Date: 2024-02-07
- Commission: 1.00 EUR | Spread: 0 bps | Slippage: 0 bps

| Metric | Hand Calculation (`backtest-baseline.md`) | Engine Simulation Output | Match |
| :--- | :--- | :--- | :--- |
| **Initial Equity** | 1,000.00 EUR | 1,000.00 EUR | EXACT |
| **Day 1 (2024-02-01) Fill** | 9 units @ 100.00 EUR | 9 units @ 100.00 EUR | EXACT |
| **Day 1 Ending Equity** | 999.00 EUR (-0.10% drawdown) | 999.00 EUR (-0.10% drawdown) | EXACT |
| **Day 2 (2024-02-02) Split** | 18 units @ 50.00 EUR, 999.00 EUR | 18 units @ 50.00 EUR, 999.00 EUR | EXACT |
| **Day 3 (2024-02-05) Ex-Date**| Cash 99.00, Receivables 18.00, Holdings 882.00 | Cash 99.00, Receivables 18.00, Holdings 882.00 | EXACT |
| **Day 4 (2024-02-06) Pay-Date**| Cash 117.00, Receivables 0.00, Holdings 882.00 | Cash 117.00, Receivables 0.00, Holdings 882.00 | EXACT |
| **Day 5 (2024-02-07) Reinvest**| 2 units @ 49.00 EUR, Cash 18.00 | 2 units @ 49.00 EUR, Cash 18.00 | EXACT |
| **Final Equity** | **1,018.00 EUR** | **1,018.00 EUR** | EXACT |
| **Cumulative Return** | **+1.8000%** | **+1.8000%** | EXACT |
| **Max Drawdown** | **-0.1000%** (Trough: 2024-02-01, Recovered: 2024-02-07) | **-0.1000%** (Trough: 2024-02-01, Recovered: 2024-02-07) | EXACT |
| **Total Commissions** | 2.00 EUR (2 fills) | 2.00 EUR (2 fills) | EXACT |

### 3.2 Lookahead Bias Prevention
Verified via `testLookaheadRejectionFailsRun`:
- An acceptance fixture `m3-lookahead-action.zip` containing a corporate action with `available_at` timestamp occurring *after* the market session open was processed.
- The engine detected that data was not available before session open and rejected the execution with a clear failure reason: `"Corporate action act-lookahead-1 available_at 2024-02-01T12:00:00Z is after session start 2024-02-01T08:00:00Z"`.

### 3.3 Concurrency, Idempotency & Crash Recovery
- **Duplicate Submissions:** Verified that concurrent submissions with identical idempotency keys or canonical hashes return the in-progress or completed run without launching duplicate threads.
- **Restart Recovery:** Verified via `testRestartRecoveryTransitionsUnfinishedJobs` that any run left in `QUEUED` or `RUNNING` status across backend restarts is automatically updated to `INTERRUPTED`.
- **Cancellation:** Verified that active runs receive cancellation interrupts and transition cleanly to `CANCELLED` status.

### 3.4 Automated Test Suite Execution

```
> Task :backend:test
BUILD SUCCESSFUL in 8s
5 actionable tasks: 5 executed

> Task :frontend:test
Test Files  3 passed (3)
     Tests  34 passed (34)
  Duration  852ms
```

---

## 4. Acceptance Criteria Checklist

| AC # | Requirement | Status | Evidence |
| :---: | :--- | :---: | :--- |
| **#1** | V4 schema migration applied with immutable backtest runs, configurations, events, orders, fills, daily equity series, and isolation from live/demo accounts | **PASS** | `V4__backtest_engine.sql` applied; 10 SQLite triggers verified; legacy accounts confirmed untouched (`MigrationRecoveryIntegrationTest`, `BacktestIntegrationTest`) |
| **#2** | Deterministic event engine and S1 buy-and-hold implemented with independent S1 benchmark, whole-unit sizing, and no lookahead bias | **PASS** | `BacktestEngine.java` matches hand calculation 1:1; lookahead rejection verified in `BacktestIntegrationTest` |
| **#3** | Modeled execution costs (commission, spread, slippage), cash/receivables split accounting, and exact BigDecimal ledger replay implemented | **PASS** | `AccountingCore` arithmetic applied; commission deducted from cash; receivables settled post-close; verified in `BacktestEngineTest` |
| **#4** | Asynchronous backtest lifecycle (`QUEUED`, `RUNNING`, `COMPLETED`, `FAILED`, `CANCELLED`, `INTERRUPTED`) with idempotency, concurrency safety, and restart recovery | **PASS** | `BacktestJobService.java` verified across all states and crash recovery (`BacktestIntegrationTest`) |
| **#5** | Core analytics (equity, drawdown, CAGR, volatility, turnover, partial years) and reproduction export ZIP generation implemented | **PASS** | `BacktestAnalyticsCalculatorTest`, `BacktestExportService.java`, ZIP contents validated in integration test |
| **#6** | Research REST APIs (`/api/research/backtests`) and Angular UI (`/research/backtests`, `/research/backtests/:id`) implemented with zoneless rendering and lifecycle cleanup | **PASS** | Angular components, services, and routes verified; native browser subagent walkthrough completed |
| **#7** | Independent hand calculation documented in `planning/docs/backtest-baseline.md`, comprehensive backend/frontend verification passing, and `research-M3.md` report completed | **PASS** | Baseline documented; full backend and frontend test suites pass; completion report generated |

---

## 5. User Experience & Browser Walkthrough

The user interface was validated through automated browser sessions using the `browser_subagent`:

1. **Top Header Navigation:** Added `BACKTESTS` navigation button in `app-header` alongside `DEMO WORKSTATION`, `RESEARCH PORTFOLIOS`, and `DATA INSPECTION`.
2. **Backtest Run History (`/research/backtests`):**
   - Header with title, subtitle, and `+ NEW BACKTEST` button.
   - Run table displaying Run ID, Strategy (`ETF_BUY_HOLD_V1`), Dataset, Date Range, Initial Cash, Status Badges (`COMPLETED`, `FAILED`), Cumulative Return (`+1.80%`), Max Drawdown (`-0.10%`), and Actions.
3. **Interactive Creation Modal:**
   - Dropdowns dynamically populated with validated datasets (`HistoricalDataService.datasets$`) and listings.
   - Defaults populated for Start Date, End Date, Evaluation Cutoff, Initial Cash (1,000.00 EUR), Commission (1.00 EUR), Spread (0 bps), Slippage (0 bps).
4. **Detail View (`/research/backtests/:id`):**
   - Header with Run ID, Status badge, strategy badge, and `← ALL RUNS` and `↓ EXPORT ZIP` action buttons.
   - 8 KPI summary cards displaying Cumulative Return, Final Equity, CAGR, Max Drawdown (with recovery badge and duration), Annualized Volatility, Benchmark Difference, Portfolio Turnover, and Commissions.
   - Responsive SVG dual-chart component rendering Candidate Equity curve (green area and polyline), Benchmark curve (cyan dashed line), and Underwater Drawdown area (red shaded polyline).
   - Annual Performance Breakdown table displaying 2024 returns with `PARTIAL` badge.
   - Tabbed deep dives:
     - **Daily Equity Series (5 entries):** Date, series type, cash, holdings value, receivables, total equity, daily return, drawdown, units.
     - **Orders & Fills (4 entries):** Session date, series type, side badge, listing, quantities, fill price, modeled price, commission, cash impact.
     - **Audit Events (22 entries):** Detailed chronological trace of executions, closing marks, splits, entitlements, and payment settlements.
     - **Assumptions & Config:** Canonical SHA-256 hash, dataset ID, cutoff timestamp, cost assumptions.

---

## 6. Artifact Inventory

### Database Migrations
- `backend/src/main/resources/db/migration/V4__backtest_engine.sql` (192 lines): Tables for backtest runs, daily equity, orders, events, holdings, indexes, and 10 immutability triggers.

### Backend Implementation
- `backend/src/main/java/com/bergnerd/signalforge/app/db/migration/MigrationRunner.java`: Updated to schema version 4 and software version `2.0.0-M3`.
- `backend/src/main/java/com/bergnerd/signalforge/app/research/backtest/BacktestDtos.java` (261 lines): Canonical hash calculation, DTO records, requests, responses.
- `backend/src/main/java/com/bergnerd/signalforge/app/research/backtest/BacktestDataReader.java` (292 lines): Bulk loading and validation of historical datasets, listings, bars, and corporate actions.
- `backend/src/main/java/com/bergnerd/signalforge/app/research/backtest/BacktestEngine.java` (451 lines): Deterministic simulation engine, S1 buy & hold logic, cash/receivables accounting, order execution.
- `backend/src/main/java/com/bergnerd/signalforge/app/research/backtest/BacktestAnalyticsCalculator.java` (233 lines): Equity series aggregation, drawdowns, underwater durations, volatility, turnover, annual return table.
- `backend/src/main/java/com/bergnerd/signalforge/app/research/backtest/BacktestExportService.java` (195 lines): Streaming reproduction ZIP generation.
- `backend/src/main/java/com/bergnerd/signalforge/app/research/backtest/BacktestJobService.java` (606 lines): Asynchronous thread pool executor, idempotency collision handling, cancellation, restart recovery.
- `backend/src/main/java/com/bergnerd/signalforge/app/research/backtest/BacktestController.java` (106 lines): REST endpoints under `/api/research/backtests`.

### Backend Tests & Fixtures
- `backend/src/test/java/com/bergnerd/signalforge/app/research/backtest/BacktestEngineTest.java` (208 lines): Unit test validating engine mechanics and math reconciliation.
- `backend/src/test/java/com/bergnerd/signalforge/app/research/backtest/BacktestAnalyticsCalculatorTest.java` (141 lines): Analytics calculation verification.
- `backend/src/test/java/com/bergnerd/signalforge/app/research/backtest/BacktestControllerTest.java` (190 lines): WebMvc slice testing REST endpoints and CORS headers.
- `backend/src/test/java/com/bergnerd/signalforge/app/research/backtest/BacktestIntegrationTest.java` (343 lines): Full end-to-end integration test covering baseline reconciliation, idempotency replay, lookahead rejection, ZIP export verification, immutability triggers, and crash recovery.
- `test/fixtures/historical/generate_m3_fixtures.py` (105 lines): Synthetic fixture generator producing `m3-reference-baseline.zip`, `m3-lookahead-action.zip`, and `m3-missing-bar.zip`.

### Frontend Implementation
- `frontend/src/app/models/backtest.model.ts` (128 lines): TypeScript interfaces and types matching backend DTOs.
- `frontend/src/app/services/backtest.service.ts` (183 lines): Angular service handling REST calls, pagination un-wrapping, polling, and export downloads.
- `frontend/src/app/components/research-backtests/research-backtests.component.ts` (354 lines): Component logic, state subscriptions, SVG path generation, modal management.
- `frontend/src/app/components/research-backtests/research-backtests.component.html` (687 lines): Workstation template with KPI cards, SVG charts, tabbed deep dives, and modal dialog.
- `frontend/src/app/components/research-backtests/research-backtests.component.css` (608 lines): Modern technical styling matching SignalForge dark workstation aesthetic.
- `frontend/src/app/components/header/header.component.ts`: Navigation bar updated with `BACKTESTS` tab.
- `frontend/src/app/app.routes.ts`: Routes added for `/research/backtests` and `/research/backtests/:id`.
- `frontend/src/app/app.ts` & `frontend/src/app/app.html`: Routing and component mounting.
- `frontend/angular.json`: Adjusted component style budget to accommodate data-dense workstations.

### Frontend Tests
- `frontend/src/app/services/services.spec.ts`: Added unit tests for `BacktestService`.
- `frontend/src/app/components/components.spec.ts`: Added unit tests for `ResearchBacktestsComponent`.

### Documentation
- `planning/docs/backtest-baseline.md` (195 lines): Independent hand-calculated walkthrough matching Section 9 reference scenario.

---

## 7. Deviations & Discoveries

1. **Angular Runtime Version Clarification:**
   - The milestone prompt referred to Angular 18.2.14 based on older project documentation.
   - Inspection of `package.json` and `package-lock.json` confirmed the active installed runtime is `@angular/core` 22.1.5 and `@angular/cli` 22.1.7.
   - As required by the prompt's version discrepancy rules, we did not downgrade the project, but instead recorded Angular 22 as the true version and adhered to Angular 22 conventions (Signals, standalone components, `takeUntilDestroyed`).
2. **Dataset Status Column Naming:**
   - In M2's `datasets` table, `validation_status` stores `'VALID'` or `'REJECTED'`.
   - `BacktestDataReader` was implemented to accept both `'VALID'` and `'VALIDATED'` to ensure backwards compatibility.
3. **Drawdown Peak Capture:**
   - Initial peak tracking logic updated to ensure that once a maximum drawdown trough is established, the recorded peak reflects the local peak preceding that trough rather than being overwritten by higher peaks occurring after the recovery.
4. **Paged Response Mapping in Frontend:**
   - Backend research endpoints return paged wrapper objects `{"items": [...], "total": ...}`. `BacktestService` was implemented to defensively unwrap both direct arrays and paged wrapper payloads.

---

## 8. Risks & Technical Debt

1. **Single-Worker Execution:**
   - In M3, backtests execute sequentially on a single worker thread (`Executors.newSingleThreadExecutor()`). For multi-year datasets or high-frequency simulations in future milestones, a bounded thread pool or asynchronous job queue will provide better throughput.
2. **Mark-to-Market Liquidation Costs:**
   - In accordance with standard accounting principles and M3 requirements, daily mark-to-market equity excludes hypothetical future liquidation costs (spread/slippage/commissions). In M4, when positions are exited or rebalanced, realized liquidation costs will be accounted for.
3. **Docker Verification:**
   - Docker verification was explicitly deferred per prompt instructions and verified natively on disposable SQLite. Full container image packaging will be validated in subsequent deployment milestones.

---

## 9. Recommendation & Sign-Off

Milestone M3 is **100% complete and fully verified**. All acceptance criteria have passed with zero test failures across Java and Angular suites. The baseline simulation math reconciles to the penny with the independent hand calculation.

**SignalForge is fully ready to proceed to Milestone M4 (Momentum & Trend Strategy Engine).**
