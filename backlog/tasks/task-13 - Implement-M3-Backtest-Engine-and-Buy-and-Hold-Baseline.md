---
id: TASK-13
title: Implement M3 - Backtest Engine and Buy-and-Hold Baseline
status: Done
assignee:
  - '@antigravity'
created_date: '2026-09-12 14:46'
updated_date: '2026-09-12 15:18'
labels: []
dependencies:
  - TASK-12
documentation:
  - planning/PROMPT-SIGNALFORGE-M3.md
priority: high
type: feature
ordinal: 13000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Deliver a working, reproducible historical backtest engine with S1 buy-and-hold, independently funded benchmark, modeled execution costs, exact ledger accounting, asynchronous lifecycle, core analytics, Angular browser UI at /research/backtests, result export ZIP, and verified acceptance fixtures as specified in planning/PROMPT-SIGNALFORGE-M3.md.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 V4 schema migration applied with immutable backtest runs, configurations, events, orders, fills, daily equity series, and isolation from live/demo accounts
- [x] #2 Deterministic event engine and S1 buy-and-hold implemented with independent S1 benchmark, whole-unit sizing, and no lookahead bias
- [x] #3 Modeled execution costs (commission, spread, slippage), cash/receivables split accounting, and exact BigDecimal ledger replay implemented
- [x] #4 Asynchronous backtest lifecycle (QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED, INTERRUPTED) with idempotency, concurrency safety, and restart recovery
- [x] #5 Core analytics (equity, drawdown, CAGR, volatility, turnover, partial years) and reproduction export ZIP generation implemented
- [x] #6 Research REST APIs (/api/research/backtests) and Angular UI (/research/backtests, /research/backtests/:id) implemented with zoneless rendering and lifecycle cleanup
- [x] #7 Independent hand calculation documented in planning/docs/backtest-baseline.md, comprehensive backend/frontend verification passing, and research-M3 report completed
<!-- AC:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
1. Create V4 database migration (V4__backtest_engine.sql) for backtest runs, daily equity series, events, orders, holdings, and immutability triggers; update MigrationRunner to version 4 preserving V1-V3 checksums.
2. Implement Backtest Models and DTOs (BacktestDtos.java) for run configuration, canonical hash computation, lifecycle states, order sizing, and analytics.
3. Implement BacktestDataReader to query sessions, bars, and corporate actions across required date ranges without API pagination limits.
4. Implement deterministic BacktestEngine with S1 buy-and-hold strategy, independent S1 benchmark, whole-unit affordability allocator, cash/receivables split accounting, and no lookahead.
5. Implement BacktestAnalyticsCalculator for equity series, drawdowns, underwater periods, partial calendar year returns, CAGR, 252-day annualized volatility, and turnover.
6. Implement BacktestExportService generating reproduction ZIP archives (manifest.json, summary.json, equity_series.csv, events.csv, orders.csv).
7. Implement asynchronous BacktestJobService with bounded worker queue, idempotency conflict detection, cancellation checks, and startup restart recovery.
8. Implement REST API controllers under /api/research/backtests (/backtests, /{id}, /{id}/cancel, /{id}/equity, /{id}/orders, /{id}/events, /{id}/export).
9. Create independent hand calculation in planning/docs/backtest-baseline.md and python fixture generator for acceptance scenarios.
10. Implement Angular UI (/research/backtests and /research/backtests/:id) with new run modal, progress polling, equity/drawdown charts, metrics, orders/events tables, and export download.
11. Run comprehensive test suite (unit, integration, slice, frontend vitest, and native browser walkthrough).
12. Generate planning/reports/research-M3.md completion report.
<!-- SECTION:PLAN:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Verification passed: All backend test suites (5 classes) pass. All frontend Vitest specs (34 tests) pass. Angular production build clean. Native browser walkthrough completed and verified. Math reconciled 1:1 against planning/docs/backtest-baseline.md. Completion report written to planning/reports/research-M3.md.
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
Implemented Milestone M3 historical backtest engine with S1 buy-and-hold baseline, modeled execution costs, cash/receivables split accounting, independent S1 benchmark, asynchronous job lifecycle, canonical hash deduplication, core portfolio analytics, Angular workstation at /research/backtests with SVG charts and tabbed details, and reproduction ZIP exports. All acceptance criteria verified.
<!-- SECTION:FINAL_SUMMARY:END -->
