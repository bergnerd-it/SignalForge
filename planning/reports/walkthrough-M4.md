# Walkthrough — Milestone M4: ETF Momentum, Trend Filter and Strategy Comparison

Milestone M4 has been fully implemented, verified, and closed out.

## Summary of Changes Delivered

### 1. Database Schema Evolution (Flyway Migration V8)
- [V8__strategy_versions_and_comparisons.sql](file:///Users/oliver/Projects/bergnerd/SignalForge/backend/src/main/resources/db/migration/V8__strategy_versions_and_comparisons.sql):
  - Created `strategy_versions` table with immutability triggers, seeded with:
    - `ETF_BUY_HOLD_V1` (v1.0.0)
    - `ETF_MOMENTUM_12_1_V1` (v1.0.0)
    - `ETF_TREND_10M_V1` (v1.0.0)
  - Created `universes` and `universe_listings` tables with composite keys, weight caps, and date validity.
  - Extended `backtest_runs` with `strategy_version_id`, `universe_id`, `parameters_json`, and `experiment_id`.
  - Extended `backtest_orders` with `requested_quantity` alongside `executed_quantity` and new types `REBALANCE_BUY` and `REBALANCE_SELL`.
  - Created `backtest_signals` and `backtest_signal_items` for monthly decision audits.
  - Created `backtest_comparisons` and `backtest_comparison_items` for owner-scoped run comparisons.
  - Created `experiments` and `experiment_exposure_events` with append-only triggers.

### 2. Backend Engine & Strategy Core
- [TotalReturnSignalIndexCalculator.java](file:///Users/oliver/Projects/bergnerd/SignalForge/backend/src/main/java/com/bergnerd/signalforge/app/research/backtest/TotalReturnSignalIndexCalculator.java):
  - Daily continuous total-return index $T(d)$ with corporate action adjustments independent of portfolio cash.
- [StrategyEvaluator.java](file:///Users/oliver/Projects/bergnerd/SignalForge/backend/src/main/java/com/bergnerd/signalforge/app/research/backtest/StrategyEvaluator.java):
  - S1: Single initial 100% allocation.
  - S2: 12-1 momentum score $T(m-1)/T(m-12)-1$, unrounded score descending sort, ascending listing ID tie-breaking, top-$K$ selection with $1/K$ target weights.
  - S3: 10-month SMA trend filter with strict inequality ($T(m) > \text{SMA10}(m) \implies 100\%$ ETF, else $100\%$ cash), and churn suppression on unchanged targets.
- [BacktestEngine.java](file:///Users/oliver/Projects/bergnerd/SignalForge/backend/src/main/java/com/bergnerd/signalforge/app/research/backtest/BacktestEngine.java):
  - Multi-asset execution engine: pre-trade equity sizing, sell-before-buy, whole unit increments, fractional basis clearance, half-spread/slippage penalty, and deterministic proportional affordability allocation.
- [BacktestAnalyticsCalculator.java](file:///Users/oliver/Projects/bergnerd/SignalForge/backend/src/main/java/com/bergnerd/signalforge/app/research/backtest/BacktestAnalyticsCalculator.java):
  - Multi-asset turnover, cash/exposure weights, and rolling 5-year compounded-return windows with positive-window share.
- [ComparisonService.java](file:///Users/oliver/Projects/bergnerd/SignalForge/backend/src/main/java/com/bergnerd/signalforge/app/research/backtest/ComparisonService.java):
  - Field-by-field verification of funding instant, reporting sessions, initial cash, calendar, dataset, costs, and engine version.
- [ExperimentService.java](file:///Users/oliver/Projects/bergnerd/SignalForge/backend/src/main/java/com/bergnerd/signalforge/app/research/backtest/ExperimentService.java):
  - Immutable experiment definitions and append-before-read holdout exposure tracking.
- REST Controllers:
  - [StrategyController.java](file:///Users/oliver/Projects/bergnerd/SignalForge/backend/src/main/java/com/bergnerd/signalforge/app/research/backtest/StrategyController.java) (`/api/research/strategies`)
  - [UniverseController.java](file:///Users/oliver/Projects/bergnerd/SignalForge/backend/src/main/java/com/bergnerd/signalforge/app/research/backtest/UniverseController.java) (`/api/research/universes`)
  - [ComparisonController.java](file:///Users/oliver/Projects/bergnerd/SignalForge/backend/src/main/java/com/bergnerd/signalforge/app/research/backtest/ComparisonController.java) (`/api/research/comparisons`)
  - [ExperimentController.java](file:///Users/oliver/Projects/bergnerd/SignalForge/backend/src/main/java/com/bergnerd/signalforge/app/research/backtest/ExperimentController.java) (`/api/research/experiments`)

### 3. Frontend Research Workstation
- [ResearchStrategiesComponent](file:///Users/oliver/Projects/bergnerd/SignalForge/frontend/src/app/components/research-strategies/research-strategies.component.ts) (`/research/strategies`):
  - Strategy catalog, parameters inspector, asset universes viewer, and experiments registry.
- [ResearchCompareComponent](file:///Users/oliver/Projects/bergnerd/SignalForge/frontend/src/app/components/research-compare/research-compare.component.ts) (`/research/compare`):
  - Run selector, side-by-side performance matrix, rolling 5-year window statistics cards, mandatory disclaimer notice, and export ZIP link.
- [ResearchBacktestsComponent](file:///Users/oliver/Projects/bergnerd/SignalForge/frontend/src/app/components/research-backtests/research-backtests.component.ts) (`/research/backtests`):
  - Extended with strategy dropdown, universe selector, $K$ parameter input, and "Signals & Rotation" tab with CSV export.
- [HeaderComponent](file:///Users/oliver/Projects/bergnerd/SignalForge/frontend/src/app/components/header/header.component.ts) & [App Navigation](file:///Users/oliver/Projects/bergnerd/SignalForge/frontend/src/app/app.ts):
  - Added `STRATEGIES` and `COMPARE` tabs and synchronized history routing.

---

## Verification Results

1. **Backend Tests:**
   - `./backend/gradlew -p backend test --rerun`: **170/170 passed** (0 failures).
   - Preserves exact S1 baseline reference results (EUR 1,018.00 and EUR 1,017.00 tests pass without deviation).
2. **Frontend Tests:**
   - `npm test -- --watch=false`: **43/43 passed** (0 failures).
   - `npm run build`: Production bundle builds successfully (707.08 kB).
3. **Closeout Documentation:**
   - [strategy-comparison.md](file:///Users/oliver/Projects/bergnerd/SignalForge/planning/docs/strategy-comparison.md): Independent hand calculations, formulas, and walkthrough.
   - [research-M4.md](file:///Users/oliver/Projects/bergnerd/SignalForge/planning/reports/research-M4.md): Exhaustive 9-section closeout report.
   - [TASK-15](file:///Users/oliver/Projects/bergnerd/SignalForge/backlog/tasks/task-15%20-%20Implement-M4-ETF-Momentum-Trend-Filter-and-Strategy-Comparison.md): All 9 Acceptance Criteria checked and marked **Done**.
