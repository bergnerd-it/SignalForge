---
id: TASK-14
title: Correct M3 after independent code review
status: Done
assignee:
  - '@antigravity'
created_date: '2026-09-12 15:48'
updated_date: '2026-09-13 14:30'
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
1. Audit and fix SQL parameter binding and owner isolation across all backtest endpoints (CRITICAL-1).
2. Establish complete normalized immutable configuration and deterministic business sequence/IDs (HIGH-3).
3. Enforce month-end evaluation session, strict calendar dates/instants, full timeline payment settlement, and corporate action validation (HIGH-1 / HIGH-2).
4. Implement concurrency-safe creation, repeat-safe terminal states, atomic publication, and bounded queue capacity (HIGH-4).
5. Add initial funded equity point, calendar-accurate annual returns, V5 migration for terminal insert guards, and streaming export without apostrophe corruption on negative numbers (MEDIUM-1 / MEDIUM-2 / MEDIUM-3).
6. Update Angular UI with strict types, dual candidate/benchmark series loading, pagination, and stale-response cancellation (HIGH-5 / MEDIUM-4).
7. Execute focused test suites, run native browser walkthrough on disposable database, and write research-M3-fixes.md (MEDIUM-5).
<!-- SECTION:PLAN:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Validated all 11 original findings (CRITICAL-1, HIGH-1..5, MEDIUM-1..5) from M3-code-review.md and M3-closeout-review.md. Backend suite: 156 tests passed (0 failures). Frontend suite: 36 tests passed (0 failures). Production build: 0 errors. E2E browser walkthrough recorded and verified on disposable SQLite DB. Export ZIP SHA-256: d95dac323de9bbb8a84dc9de9f657ac648f950e479ecb5ccaa8ba650f189eb15.
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
Completed all native M3 remediations preserving original finding IDs: parameterized listing queries against SQL injection (CRITICAL-1), cutoff availability validation (HIGH-1), session-relative action timing and split/payment validation (HIGH-2), frozen config snapshot with source commit hash and deterministic sequences (HIGH-3), bounded SQLite lock retry and repeat cancellation (HIGH-4), Angular strict DTOs and dual-series/events pagination (HIGH-5 & MEDIUM-4), Day 0 equity boundary and CAGR formula reconciliation (MEDIUM-1), V6 migration with composite foreign keys and pre-migration scan (MEDIUM-2), bounded export streaming with exact negative decimal formatting (MEDIUM-3), and comprehensive verification with 156 backend tests, 36 frontend tests, and browser walkthrough (MEDIUM-5).
<!-- SECTION:FINAL_SUMMARY:END -->
