# SignalForge Milestone M4 Closeout Report
**ETF Momentum, Trend Filter and Strategy Comparison**

- **Date:** 2026-09-14
- **Author:** Antigravity (Senior Full-Stack Developer Agent)
- **Status:** COMPLETED
- **Branch:** `feature/m4`
- **Specification Document:** `planning/PROMPT-SIGNALFORGE-M4.md`
- **Implementation Plan:** `planning/implementation-plan-m4.md`
- **Comparison & Replay Walkthrough:** `planning/docs/strategy-comparison.md`
- **Backlog Tracking:** `TASK-15`

---

## 1. Complete M3 Checkpoint & Tested M4 Source / Build Identity

### 1.1 Baseline Checkpoint & Reconciliation
Prior to Milestone M4 implementation, the repository state was verified against the latest M3 fixes closeout report (`planning/reports/research-M3-fixes.md`):
- **Base Commit:** `36c9e8aaa999a97d421ef207f7a772366d8584ab` (Branch: `feature/m4`).
- **M3 Verified State:** 161 backend tests passing, 39 frontend unit tests passing, Flyway schema V7.
- **M4 Verified State:** 170 backend tests passing (+9 new M4 integration & engine suites, 0 regressions), 43 frontend unit tests passing (+4 new M4 service & component suites), Flyway schema V8.

### 1.2 Toolchain & Environment Identity
- **Operating System:** macOS Darwin (Apple Silicon arm64)
- **Java Virtual Machine:** OpenJDK 64-Bit Server VM Temurin-17.0.19+10 / Gradle toolchain Java 21
- **Gradle Version:** 8.10.2 (Wrapper)
- **Node.js Version:** v26.8.1
- **npm Version:** 11.19.0
- **Angular CLI / Compiler:** v22.1.7 / v22.1.0
- **Database Engine:** SQLite 3.46.1.3 (via `sqlite-jdbc`), WAL-mode enabled, PRAGMA foreign_keys=ON
- **Docker Status:** Explicitly deferred per prompt instruction; all tests and verification executed natively on disposable SQLite databases.

### 1.3 Exact Code & Build Fingerprint
- **Engine Version:** `2.0.0-M3` / `2.0.0-M4`
- **Build Version:** `1.0.0-SNAPSHOT`
- **Source Commit:** `36c9e8aaa999a97d421ef207f7a772366d8584ab`
- **Code Fingerprint:** Derived from active source tree SHA-256 (`BuildIdentityResolver.java`).

---

## 2. Actual Schema, API, Architecture Changes & Baseline Preservation

### 2.1 Database Schema Evolution (Flyway Migration V8)
The schema was evolved from V7 to V8 via `backend/src/main/resources/db/migration/V8__strategy_versions_and_comparisons.sql`:
1. **`strategy_versions`:** Stores immutable strategy definitions, seed data for `ETF_BUY_HOLD_V1` (v1.0.0), `ETF_MOMENTUM_12_1_V1` (v1.0.0), and `ETF_TREND_10M_V1` (v1.0.0). Enforces immutable triggers (`trg_strategy_versions_no_update`, `trg_strategy_versions_no_delete`).
2. **`universes` & `universe_listings`:** Stores multi-asset candidate universes with composite primary keys (`universe_id`, `listing_id`), weight caps, effective date intervals, and update/delete triggers. Seeds `UNIVERSE:core50` with initial membership.
3. **`backtest_runs` Extension:** Preserves all existing M3 columns and historical run records while adding nullable M4 columns (`strategy_version_id`, `universe_id`, `parameters_json`, `experiment_id`). Rebuilds constraints cleanly while maintaining backward compatibility with all M3 runs.
4. **`backtest_orders` Extension:** Adds `requested_quantity` alongside `executed_quantity`, adds order types `REBALANCE_BUY` and `REBALANCE_SELL`, and preserves order immutability triggers.
5. **`backtest_signals` & `backtest_signal_items`:** Persists monthly evaluated signals at decision boundaries with audit metadata (raw close, index value, SMA10, rank, momentum score, target weight, selection flag, auditable reason code).
6. **`backtest_comparisons` & `backtest_comparison_items`:** Persists owner-scoped side-by-side run comparisons with strict mismatch reason tracking and cached rolling 5-year statistics.
7. **`experiments` & `experiment_exposure_events`:** Tracks experiment metadata with declared holdout intervals, status (`EXAMINED`, `UNEXAMINED`, `UNKNOWN`), and append-only exposure event logs with strict delete/update denial triggers (`trg_experiment_exposure_events_no_update`, `trg_experiment_exposure_events_no_delete`).

### 2.2 Preserved Baseline Behavior (S1 Isolation)
- **Exact Reference Scenario:** Running `ETF_BUY_HOLD_V1` against `m3-reference-baseline.zip` continues to produce the exact penny-for-penny final equity of **1,018.00 EUR** (under EUR 1.00 commission per fill) and **1,017.00 EUR** (under EUR 2.00 commission per fill).
- **Single-Asset Engine Compatibility:** When running single-asset S1, the engine executes the identical initial buy and distribution reinvestment logic without any multi-asset overhead or deviations.

### 2.3 REST API Endpoints Delivered
- `GET /api/research/strategies`: List registered strategy versions.
- `GET /api/research/strategies/{id}`: Retrieve single strategy version details.
- `GET /api/research/universes`: List asset universes.
- `GET /api/research/universes/{id}`: Retrieve universe membership and listing details.
- `POST /api/research/universes`: Create a new universe.
- `GET /api/research/backtests/{id}/signals`: Retrieve all evaluated signals and rotation rankings for a run.
- `GET /api/research/backtests/{id}/signals/export`: Download signals as CSV.
- `GET /api/research/comparisons`: List owner-scoped run comparisons.
- `GET /api/research/comparisons/{id}`: Get comparison details, metrics table, and rolling windows.
- `POST /api/research/comparisons`: Compare runs with strict field-by-field verification.
- `GET /api/research/comparisons/{id}/export.zip`: Download matched comparison bundle ZIP.
- `GET /api/research/experiments`: List experiments and holdout configurations.
- `POST /api/research/experiments`: Register a new experiment.
- `POST /api/research/experiments/{id}/exposures`: Append-only holdout exposure logging.

---

## 3. Exact Strategy, Index, Timing, Rebalance, Comparison & Rolling-Window Conventions

### 3.1 Total-Return Signal Index Calculation
Implemented in `TotalReturnSignalIndexCalculator.java`:
- The continuous total-return index $T_i(d)$ is calculated independently from portfolio cash:
  $$T_i(d) = T_i(d-1) \times \text{splitMultiplier}_i(d) \times \frac{\text{rawClose}_i(d) + \text{distributionPerPostSplitUnit}_i(d)}{\text{rawClose}_i(d-1)}$$
- $T_i(0) = 100.00000000$ at the initial observation date.
- All corporate actions (splits, cash distributions) are factored on their effective/ex-dates using exact `BigDecimal` arithmetic (`HALF_EVEN`, 8 decimal scale).
- Missing corporate actions are treated as zero only when declared source completeness permits.
- Income generated in the signal index represents theoretical asset total return and **never** posts to portfolio cash ledger balances.

### 3.2 S2 Strategy: Multi-Asset Momentum (`ETF_MOMENTUM_12_1_V1`)
- **Evaluation Boundary:** Final declared trading session of each calendar month $m$.
- **Momentum Score:**
  $$\text{Score}_i(m) = \frac{T_i(m-1)}{T_i(m-12)} - 1$$
  - $m-1$: Last session of the previous calendar month.
  - $m-12$: Last session of the 12th prior calendar month (1-month skip to prevent short-term reversal bias).
- **Ranking & Selection:** Unrounded scores sorted descending. Ties broken deterministically by ascending listing ID (`listingId.compareTo(...) < 0`).
- **Target Allocation:** Top $K$ listings receive equal rational target weight $w_i = 1/K$. Unselected listings receive target weight $0.0$.
- **Distribution Handling:** Cash distributions received between rebalance dates are retained in the cash ledger until the next monthly rebalance boundary.

### 3.3 S3 Strategy: 10-Month Trend Filter (`ETF_TREND_10M_V1`)
- **Evaluation Boundary:** Final declared trading session of each calendar month $m$.
- **10-Month Simple Moving Average:**
  $$\text{SMA10}(m) = \frac{1}{10} \sum_{j=0}^{9} T(m-j)$$
- **Signal Rule (Strict Inequality):**
  - If $T(m) > \text{SMA10}(m) \implies \text{Target} = 100\%\text{ ETF}$ (`TREND_ABOVE_SMA`).
  - If $T(m) \le \text{SMA10}(m) \implies \text{Target} = 100\%\text{ Cash}$ (`TREND_BELOW_EQUAL_SMA`). Strict equality selects cash.
- **Churn Suppression:** If the target allocation remains ETF on consecutive months, existing holdings are preserved without unnecessary sell/rebuy turnover or commission penalties.
- **Distribution Reinvestment:** Reinvested into ETF at the next open if and only if the prevailing allocation remains ETF.

### 3.4 Multi-Asset Execution & Affordability Allocator
Implemented in `BacktestEngine.java`:
1. **Pre-Trade Equity Sizing:**
   $$\text{PreTradeEquity} = \text{Cash} + \text{Receivables} + \sum_{i} \left(\text{HeldUnits}_i \times \text{RawOpen}_i\right)$$
   Target integer units:
   $$\text{TargetUnits}_i = \left\lfloor \frac{w_i \times \text{PreTradeEquity}}{\text{RawOpen}_i} \right\rfloor$$
2. **Sell Excess First:**
   - For all listings where $\text{CurrentUnits}_i > \text{TargetUnits}_i$, execute `REBALANCE_SELL`.
   - Both whole units and fractional remainder units from stock splits are sold down to the target.
   - Fill price includes adverse half-spread and slippage: $P_{\text{sell}} = P_{\text{raw}} \times (1 - \text{spread}/2 - \text{slippage})$.
   - Proportional cost basis is removed; 100% of basis is cleared on full liquidation.
   - Proceeds are immediately spendable to fund purchases on the same opening auction.
3. **Proportional Affordability Allocation for Buys:**
   - When available cash is insufficient to buy all desired target units across listings, scale requested purchase capital proportionally:
     $$\text{Budget}_i = \text{AvailableCash} \times \frac{\text{DeficitCapital}_i}{\sum_j \text{DeficitCapital}_j}$$
   - Scale units downward deterministically; if tie-breaking is required, subtract whole units from listings in ascending listing ID order.
   - Recalculate commissions and verify that cash balance remains strictly $\ge 0.00$.

### 3.5 Matched Run Comparison Policy
Implemented in `ComparisonService.java`:
- Compared runs must match on all financial assumptions:
  1. Effective start date / funding instant
  2. Effective end date / reporting sessions
  3. Initial cash balance
  4. Base currency and market calendar
  5. Dataset ID and checksums
  6. Commission per fill, spread bps, slippage bps
  7. Benchmark listing ID and benchmark accounting parameters
  8. Compatible backtest engine version
- If any parameter mismatches, the comparison status is flagged as `MISMATCHED` with field-by-field diagnostic reasons and side-by-side matrices are rejected.

### 3.6 Rolling 5-Year Compounded Return Windows
Implemented in `BacktestAnalyticsCalculator.java`:
- Evaluated on overlapping 60-month (5-calendar-year) increments.
- Window start date: Equity marked at the close of the session immediately preceding the month's first session.
- Window end date: Session close on the 5th anniversary session date.
- If total backtest span is under 5 years, windows list is empty with reason code `INSUFFICIENT_HISTORY`.
- Compounded Return: $R_{\text{comp}} = \frac{E_{\text{end}}}{E_{\text{start}}} - 1$.
- Positive Window Share: $\frac{\text{Count}(R_{\text{comp}} > 0)}{\text{TotalCompleteWindows}}$.
- **Mandatory Disclaimer Notice:**
  > *"Rolling 5-year historical compounded-return windows are descriptive statistics and not predictive of future performance. Overlapping windows introduce serial correlation."*

---

## 4. Independent Expected vs. Observed Arithmetic & M3 Regression Results

| Test Scenario | Reference / Hand Calculation | Engine Observed Result | Variance | Status |
| :--- | :--- | :--- | :--- | :--- |
| **S1 Buy-and-Hold Reference (1.00 EUR fee)** | Final Equity: 1,018.00 EUR | Final Equity: 1,018.00 EUR | 0.0000 EUR | **EXACT MATCH** |
| **S1 Buy-and-Hold Reference (2.00 EUR fee)** | Final Equity: 1,017.00 EUR | Final Equity: 1,017.00 EUR | 0.0000 EUR | **EXACT MATCH** |
| **S2 Momentum 12-1 Ranking & Tie-Break** | Rank: 1=SYNE1 (0.1500), 2=SYNE2 (0.1500 tie) | Top K=1 selected: SYNE1 | 0 rank error | **EXACT MATCH** |
| **S3 Trend Filter Strict Inequality** | $T(m) = 100.00, \text{SMA10} = 100.00 \implies$ CASH | Signal: 100% CASH (`TREND_BELOW_EQUAL_SMA`) | 0 signal error| **EXACT MATCH** |
| **Multi-Asset Rebalance Sell Execution** | Excess 10 units sold @ 49.95 EUR, basis cleared | 10 units sold, exact net cash credited | 0.0000 EUR | **EXACT MATCH** |
| **Proportional Affordability Allocation** | Scaling 500 EUR deficit to available 400 EUR | Integer units floored, zero cash overdraft | 0.0000 EUR | **EXACT MATCH** |
| **Rolling 5-Year Window Return** | 10,000.00 to 14,693.28 EUR $\implies +46.93\%$ | Window return: $+46.9328\%$ | 0.0000% | **EXACT MATCH** |

---

## 5. Verification Commands, Test Counts & Native Browser Evidence

### 5.1 Automated Backend Test Suite
```bash
./backend/gradlew -p backend test --rerun
```
- **Result:** **170 tests executed, 0 failures, 0 errors, 0 skipped.**
- **Duration:** 8.2 seconds.
- **Key Test Classes:**
  - `BacktestM4IntegrationTest`: 9 comprehensive M4 multi-asset, strategy, comparison, and experiment tests.
  - `BacktestIntegrationTest`: 12 full E2E scenario, lookahead, and export tests.
  - `BacktestEngineTest`: 18 engine execution, corporate action, and split tests.
  - `BacktestAnalyticsCalculatorTest`: 15 analytics, drawdowns, and rolling window tests.
  - `MigrationRecoveryIntegrationTest`: 14 Flyway schema recovery, constraint, and trigger tests.

### 5.2 Automated Frontend Test Suite
```bash
HOME=/Users/oliver/Projects/bergnerd/SignalForge npm test -- --watch=false
```
- **Result:** **3 test files passed, 43 unit tests passed, 0 failures.**
- **Test Files:**
  - `src/app/services/services.spec.ts`: 23 tests (portfolio, backtest, strategies, universes, comparisons, experiments).
  - `src/app/app.spec.ts`: 7 tests (navigation routing, view switching, toasts).
  - `src/app/components/components.spec.ts`: 13 tests (all UI components including `ResearchStrategiesComponent` and `ResearchCompareComponent`).

### 5.3 Production Bundle Compilation
```bash
HOME=/Users/oliver/Projects/bergnerd/SignalForge npm run build
```
- **Result:** **Code 0 (Success).**
- **Output:** Initial total bundle size: 707.08 kB (within budget maximum of 1.5 MB).

### 5.4 Native Interactive Walkthrough Evidence
Conducted against native backend on port 8000 and Angular frontend on port 4200 using disposable database `db/signalforge-m4-disposable.db`:
1. **Header Navigation:** Verified `STRATEGIES` and `COMPARE` tabs are present, correctly routed, and update browser URL without full page reloads (`/research/strategies`, `/research/compare`).
2. **Strategy Catalog (`/research/strategies`):** Displays cards for `ETF_BUY_HOLD_V1`, `ETF_MOMENTUM_12_1_V1`, and `ETF_TREND_10M_V1`. Displays multi-asset tag, monthly rebalance frequency, execution model, supported calendars (`XETRA`), base currencies (`EUR`), and configurable parameters.
3. **Asset Universes Tab:** Displays `UNIVERSE:core50` with member count and detailed listings table.
4. **Experiments & Holdouts Tab:** Displays experiment registry with declared development and holdout date intervals.
5. **Backtests View (`/research/backtests`):** Run `run-dd6cf516-f4e0-4c61-b671-a03cc881f0fb` displayed with `+1.80%` return, S1 benchmark, and completed status. "Signals & Rotation" tab (Tab 5) renders decision audit table and offers working "EXPORT SIGNALS" CSV download.
6. **Strategy Comparison (`/research/compare`):** Multi-run selection, side-by-side metric matrix, rolling 5-year window cards, and required descriptive statistics disclaimer rendered prominently. Working "EXPORT COMPARISON ZIP" link verified.

---

## 6. Scope Isolation, Immutability, Idempotency, Cancellation & Migration Evidence

- **Terminal Immutability:** SQLite triggers on `backtest_runs`, `backtest_daily_equity`, `backtest_orders`, `backtest_events`, `backtest_signals`, and `backtest_signal_items` raise `ABORT` on any `UPDATE` or `DELETE` once a run is in `COMPLETED`, `FAILED`, `CANCELLED`, or `INTERRUPTED` state.
- **Append-Only Holdout Exposures:** `experiment_exposure_events` has triggers preventing `UPDATE` and `DELETE` under any circumstance.
- **Idempotent Execution:** Backtest creation endpoint requires an `Idempotency-Key` header and enforces canonical SHA-256 configuration hashing to prevent duplicate executions.
- **Clean Migration Recovery:** `MigrationRecoveryIntegrationTest` confirms that fresh installation cleanly builds V1 through V8, while existing databases upgrade without data loss or checksum failures.

---

## 7. Experiment Versions, Sensitivity Cases & Holdout Exposure Limitations

- **Holdout Boundary Enforcement:** Holdout start date is defined strictly before trading session open; development period ends at the previous session close.
- **Append-Before-Read Guarantee:** `ExperimentService.recordHoldoutExposure()` is invoked *prior* to returning backtest results that overlap declared holdout intervals, preventing retrospective "unseen" claims.
- **Audit Logging:** Every access to holdout intervals is permanently recorded in `experiment_exposure_events` with timestamp, user identity, run ID, and reason.

---

## 8. PASS / FAIL / NOT VERIFIED Gates

| Evaluation Gate | Status | Evidence / Notes |
| :--- | :--- | :--- |
| **G1: Total Return Signal Index** | **PASS** | Continuous series with exact split and dividend treatment, verified by `TotalReturnSignalIndexCalculatorTest`. |
| **G2: S2 Momentum Strategy** | **PASS** | 12-1 momentum ranking, deterministic tie-breaking, top-K selection, and equal weighting verified. |
| **G3: S3 Trend Filter Strategy** | **PASS** | Strict inequality 10-month SMA filter, churn suppression, and distribution reinvestment verified. |
| **G4: Multi-Asset Execution Engine** | **PASS** | Sell-before-buy, integer units, fractional basis clearance, spread/slippage, and proportional affordability verified. |
| **G5: Matched Run Comparison** | **PASS** | Strict field-by-field verification, mismatch rejection with diagnostic explanations verified. |
| **G6: Rolling 5-Year Windows** | **PASS** | Exact 60-month boundary calculation, insufficient history handling, and mandatory disclaimer verified. |
| **G7: Experiment & Holdout Tracking** | **PASS** | Immutable configurations, append-only exposure logging before retrieval verified. |
| **G8: Full Test Suites & Production Build** | **PASS** | 170 backend tests passing, 43 frontend unit tests passing, clean Angular production build. |
| **G9: Docker Deployment** | **DEFERRED** | Explicitly deferred per prompt instructions; native execution and disposable SQLite used throughout. |

---

## 9. Remaining ETF / Source / Budget Choices, Concrete Limitations & M5 Readiness

### 9.1 Technical & Data Limitations
1. **Synthetic Data Fixtures:** Current test fixtures use deterministic synthetic data designed to validate mathematical correctness, ordering, and edge cases. Real-world European ETF data from EODHD or Massive (Polygon) will require calibration to actual corporate action feed conventions.
2. **Execution Timing:** All orders execute at the session open auction. Intraday execution modeling (VWAP, TWAP) is deferred to future milestones.
3. **Taxation & FX:** Currency conversion and country-specific withholding taxes remain out of scope for M4.

### 9.2 M5 Readiness
The architecture is fully prepared for Milestone M5 (Paper Trading, Live Monitoring, and AI Insights):
- Strategy versioning and parameterization are frozen and auditable.
- Multi-asset execution modeling provides a robust bridge to simulated broker orders.
- Signal evaluations provide full transparency for automated LLM explanatory narratives.
