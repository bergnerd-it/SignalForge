---
id: TASK-17
title: Implement M5 - Prospective Paper Tracking and Grounded AI Explanations
status: Done
assignee:
  - '@antigravity'
created_date: '2026-09-15 07:34'
updated_date: '2026-09-15 19:37'
labels: []
dependencies:
  - TASK-16
references:
  - planning/PROMPT-SIGNALFORGE-M5.md
  - planning/SIGNALFORGE-SPEC-v1.0.md
  - planning/M5-implementation_plan-fixes.md
priority: high
ordinal: 17000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Implement M5 in SignalForge: prospective paper tracking for separate EUR portfolios, data readiness and snapshot binding, immutable proposal and execution lifecycle, shared M4 execution/accounting core reuse, opt-in AUTO_PAPER mode, grounded research AI assistant with typed evidence references, UI at /research/portfolios/:id, paper audit export, and paper-tracking guide.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 Preserve and checkpoint complete M4 baseline with git tag m4-checkpoint and verified build/test toolchain
- [x] #2 Follow-on migration(s) after V11 creating paper portfolios, proposals, execution intents, snapshot adoptions, mode history, and audit structures without mutating existing migrations
- [x] #3 EUR PAPER portfolio creation, funding isolation, server Clock authority, snapshot binding, and current data readiness validation
- [x] #4 Immutable proposal lifecycle (proposed, accepted, rejected, superseded, waiting-for-observation, executed, blocked, missed) with concurrency CAS checks, strictly future open scheduling, and observation resolution
- [x] #5 Shared execution and exact accounting engine reuse for paper fills, costs, splits, dividends, receivables, and prospective valuations
- [x] #6 Opt-in AUTO_PAPER mode with durable mode history, local coordinator, pre-open intent persistence, and downtime recovery
- [x] #7 Grounded AI research assistant with typed owner-scoped read tools, fact cards, evidence references, and graceful offline fallback
- [x] #8 Frontend paper portfolio UI at /research/portfolios/:id, proposal review, mode controls, prospective metrics, and audit export (ZIP/CSV)
- [x] #9 Comprehensive tests (controlled clock, arithmetic walkthrough, concurrency barriers, recovery, upgrade) and research-M5.md closeout report with paper-tracking.md
<!-- AC:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
M5 Remediation per planning/M5-implementation_plan-fixes.md:
1. Revise V12 schema & state machines (constraints, immutability triggers, paper_mutation_requests, aggregate integrity).
2. Shared strategy evaluation & opening allocator (TotalReturnSignalIndexCalculator, StrategyEvaluator S1/S2/S3, frozen decisions, rebalance allocator).
3. Exact accounting, corporate actions, valuations (AccountingCore/OperationService applySplit & creditCashDistribution, historical_actions scanner, receivables, true equity valuations).
4. Readiness, adoption, calendars, and time (injected Clock, validation status, bars/actions coverage, REQUIRES_NEW rejection audit).
5. Idempotency, serialization, recovery (paper_mutation_requests, per-portfolio locks, multi-owner recovery, WAITING_FOR_OBSERVATION, MISSED).
6. Grounded research assistant (typed read tools, fact cards, evidence IDs, holdout protection, unavailable states).
7. Owner-scoped API and Angular UI (:id route binding, strict typing, full state views, diagnose npm run build exit 134).
8. Complete bounded audit export (manifest, 9 CSVs, streaming/chunked, exact decimals, formula protection).
9. Focused verification (12 automated scenarios, controlled Clock, spotlessApply, gradlew clean test, npm test, npm run build production).
10. Native browser walkthrough & closeout (disposable seeded SQLite, downloaded ZIP inspection, M4 inherited checks, research-M5.md).
<!-- SECTION:PLAN:END -->

## Comments

<!-- COMMENTS:BEGIN -->
author: @codex
created: 2026-09-15 15:44
---
Reopened after independent M5 review. The remediation plan was revised to resolve future-open sizing, migration policy, accounting API, corporate-action identity, readiness/time, idempotency, recovery, UI/export, and verification gaps.
---
<!-- COMMENTS:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
Implemented M5 Prospective Paper Tracking and Grounded AI Explanations per revised plan M5-implementation_plan-fixes.md. Remediated all code review findings: V12 migration triggers and paper_mutation_requests idempotency, shared strategy evaluator and frozen desired-units sizing, AccountingCore applySplit and creditCashDistribution with cash distribution receivables, server Clock authority and data readiness validation, bounded 13-file audit export with RFC 4180 escaping and negative decimal preservation, grounded AI research assistant with typed read tools and owner isolation, Angular frontend with route parameter resolution and blob download. Verified with 191/191 passing backend tests, 48/48 passing frontend tests, and production build in 2.173s on declared Node 24.21.0 toolchain.
<!-- SECTION:FINAL_SUMMARY:END -->
