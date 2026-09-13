---
id: TASK-14
title: Correct M3 after independent code review
status: Done
assignee:
  - '@codex'
created_date: '2026-09-12 15:48'
updated_date: '2026-09-13 22:32'
labels: []
dependencies:
  - TASK-13
documentation:
  - planning/PROMPT-SIGNALFORGE-M3-FIXES.md
priority: high
type: bug
ordinal: 14000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Code review of M3 revealed critical and high findings: missing owner scoping and SQL injection in series filters (CRITICAL-1), month-end and reporting interval enforcement gaps (HIGH-1), incomplete corporate action payment timing (HIGH-2), non-reproducible event IDs and incomplete frozen configuration metadata (HIGH-3), concurrency-unsafe idempotency and repeat cancellation failures (HIGH-4), missing benchmark loading and truncated pages in Angular (HIGH-5), analytics/schema/export defects (MEDIUM-1 to MEDIUM-4), and missing acceptance tests (MEDIUM-5).
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 CRITICAL-1: Owner scoping enforced on all run endpoints and SeriesType enum bound via JDBC parameters
- [x] #2 HIGH-3: Complete normalized immutable configuration snapshot persisted and deterministic business ordering/IDs established
- [x] #3 HIGH-1 & HIGH-2: Strict date/instant parsing, month-end validation, complete calendar payment settlement, and corporate action validation
- [x] #4 HIGH-4: Atomic duplicate resolution, repeat-safe terminal states, conditional durable transitions, and bounded queue capacity
- [x] #5 MEDIUM-1, MEDIUM-2, MEDIUM-3: Initial funded point in equity series, V5 migration for terminal insert guards & composite FKs, and streaming export without apostrophe prefixing
- [x] #6 HIGH-5 & MEDIUM-4: Strict TypeScript contracts, dual candidate/benchmark series loading, pagination, and stale response suppression in Angular
- [x] #7 MEDIUM-5: Comprehensive backend/frontend test gates, native browser verification, and research-M3-fixes.md completion report
<!-- AC:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
1. Derive truthful build identity and bind complete execution snapshot into idempotency canonical hash (HIGH-3).
2. Align initial funded equity boundary with first execution and reconcile CAGR/partial-year detection (MEDIUM-1).
3. Follow paged equity series up to 100k, expose incomplete series warnings, and suppress stale responses (HIGH-5).
4. Validate export bounds before HTTP 200 response and enforce streaming byte limits (MEDIUM-3).
5. Add deterministic temporary-SQLite tests for queued cancel, repeat cancel, cancel vs publication race, queue saturation, and rollback (HIGH-4).
6. Update research-M3-fixes.md with verified evidence and accurate versions (MEDIUM-5).

7. Reconcile the execution-boundary reference series without fabricating dates; test baseline and holiday gaps.

8. Prove export byte-limit HTTP behavior and lifecycle race/rollback gates on temporary SQLite, then run full native checks on final tree.
<!-- SECTION:PLAN:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
2026-09-14 M3 closeout: V7 same-date funded observation preserves existing rows; build source fingerprint is frozen while same-key replay uses stored client intent; calendar annual labels, bounded pre-response ZIP and omitted CAGR display corrected. 161 backend tests/25 suites, 39 frontend tests/3 files, Angular production build and git diff --check pass. Disposable native browser verified baseline creation, candidate/benchmark curves, orders/events, deep link, synthetic 5002-point-per-series long-run rendering, queued cancellation and cutoff error. Actual local ZIP download integrity/hash checked. The long table exceeded accessibility-frame capture; API pages and a Vitest 5000+2 boundary test verify continuation. Exact 50 MiB artifact and archived cross-binary replay were not executed; no known native M3 blocker. Docker deferred. See planning/reports/research-M3-fixes.md.
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
Corrected M3 reproducibility, first-execution funding, calendar analytics, export bounds and UI null handling; verified migration preservation, lifecycle races, 161 backend and 39 frontend tests, production build, disposable browser scenarios and ZIP integrity. Docker deferred.
<!-- SECTION:FINAL_SUMMARY:END -->
