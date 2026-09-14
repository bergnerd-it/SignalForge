---
id: TASK-15
title: 'Implement M4 - ETF Momentum, Trend Filter and Strategy Comparison'
status: Done
assignee:
  - '@antigravity'
created_date: '2026-09-14 04:22'
updated_date: '2026-09-14 12:44'
labels: []
dependencies:
  - TASK-14
references:
  - planning/PROMPT-SIGNALFORGE-M4.md
  - planning/FINALLY-RESEARCH-SPEC-v0.1.md
  - planning/SIGNALFORGE-SPEC-v0.2-M0-ADDENDUM.md
  - planning/implementation-plan-m4.md
priority: high
type: feature
ordinal: 15000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Implement M4 in SignalForge: S2 (ETF_MOMENTUM_12_1_V1) and S3 (ETF_TREND_10M_V1) strategies alongside S1, auditable monthly signals, target-weight multi-asset execution, matched strategy comparisons, rolling 5-year windows, experiment/holdout metadata, and strategy/comparison UI and exports.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 Total-return signal index calculated correctly with corporate actions and split adjustments independent of portfolio cash
- [x] #2 S2 (12-1 momentum) ranks universe descending, handles ties, selects top K, equal target weights 1/K, cash distributions held until rebalance
- [x] #3 S3 (10-month SMA trend filter) targets 100% ETF when T(m) > SMA10(m) else 100% cash (equality selects cash), preserves holdings on unchanged target without churn, reinvests distributions while in ETF
- [x] #4 Multi-listing execution engine handles sell-before-buy, whole units, spread/slippage, proportional affordability scaling, and exact cost basis accounting
- [x] #5 Owner-scoped matched run comparison verifies identical funding, end date, initial cash, calendar/dataset, and benchmark, rejecting mismatches with field-by-field explanations
- [x] #6 Rolling 5-year historical compounded-return windows computed with exact boundary definitions and insufficient-history handling
- [x] #7 Immutable experiment definitions with development/holdout boundaries and append-only holdout exposure tracking
- [x] #8 Frontend routes /research/strategies and /research/compare implemented with pagination, chart caps, stale-response protection, and full export
- [x] #9 Comprehensive test suites pass, documentation and planning/reports/research-M4.md closeout report delivered
<!-- AC:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
1. Inspect the actual M3 checkout, tools, schema and latest closeout; preserve the complete tracked/untracked tree in a local checkpoint or exact manifest before M4 code changes. Use disposable databases only.
2. Freeze owner-scoped immutable universe/strategy/run identities and add the next verified follow-on migration without changing prior migrations or old results.
3. Implement portfolio-independent total-return index and exact month-end, availability and pending-decision timing with auditable signals.
4. Evaluate S1/S2/S3 using fixed V1 rules, full warm-up, stable ranking and rational top-K weights.
5. Generalize the shared exact accounting engine for multi-asset target weights, sales, costs, deterministic affordability and atomic publication while preserving S1.
6. Persist strict matched comparisons and five-calendar-year subperiod windows with common boundaries and insufficient-history reasons.
7. Add immutable experiments, declared holdout state and append-only exposure before any result retrieval/export.
8. Extend scoped APIs, typed Angular views, bounded complete exports and lifecycle protections.
9. Create independent hand calculations, reproducible M2-import fixtures and focused backend/frontend/migration tests.
10. Run full configured checks and a disposable native browser/export walkthrough; document actual evidence, deferred Docker and M4 closeout. Detailed reviewer comments: planning/implementation-plan-m4.md.
<!-- SECTION:PLAN:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Full verification passed: 170/170 backend tests passing, 43/43 frontend unit tests passing, production build succeeded. Comprehensive closeout report written to planning/reports/research-M4.md.
<!-- SECTION:NOTES:END -->

## Comments

<!-- COMMENTS:BEGIN -->
author: @codex
created: 2026-09-14 04:32
---
Implementation-plan review: planning/implementation-plan-m4.md now contains explicit implementation-model comments. Follow the M4 prompt for baseline checkpoint, timing, immutable run identity, allocator, exact comparison/rolling boundaries, exposure-before-retrieval, and native verification. The draft class/table list is provisional; inspect the current V7 schema and code before implementation.
---
<!-- COMMENTS:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
Implemented Milestone M4 (S2 ETF_MOMENTUM_12_1_V1, S3 ETF_TREND_10M_V1, multi-asset execution engine, auditable signals, matched comparisons, rolling 5-year windows, experiment/holdout tracking, UI at /research/strategies and /research/compare, and export endpoints). Verified with 170 backend tests, 43 frontend tests, and native interactive walkthrough.
<!-- SECTION:FINAL_SUMMARY:END -->
