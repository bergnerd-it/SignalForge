---
id: TASK-17
title: Implement M5 - Prospective Paper Tracking and Grounded AI Explanations
status: Done
assignee:
  - '@codex'
created_date: '2026-09-15 07:34'
updated_date: '2026-09-16 21:23'
labels: []
dependencies:
  - TASK-16
references:
  - planning/PROMPT-SIGNALFORGE-M5.md
  - planning/SIGNALFORGE-SPEC-v1.0.md
  - planning/M5-implementation_plan-fixes.md
modified_files:
  - >-
    backend/src/main/java/com/bergnerd/signalforge/app/research/backtest/ExperimentService.java
  - >-
    backend/src/main/java/com/bergnerd/signalforge/app/research/paper/PaperDataReadinessService.java
  - >-
    backend/src/main/java/com/bergnerd/signalforge/app/research/paper/PaperPortfolioService.java
  - >-
    backend/src/test/java/com/bergnerd/signalforge/app/research/backtest/BacktestM4IntegrationTest.java
  - >-
    backend/src/test/java/com/bergnerd/signalforge/app/research/paper/PaperDataReadinessServiceTest.java
  - >-
    backend/src/test/java/com/bergnerd/signalforge/app/research/paper/PaperPortfolioIntegrationTest.java
  - frontend/src/app/components/components.spec.ts
  - >-
    frontend/src/app/components/research-strategies/research-strategies.component.html
  - >-
    frontend/src/app/components/research-strategies/research-strategies.component.ts
  - frontend/src/app/models/backtest.model.ts
  - planning/reports/research-M5.md
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
1. Freeze M5 scope and review the existing dirty diff only. 2. Keep demonstrated production fixes and focused regressions; add no speculative features or exhaustive scenario matrix. 3. Run affected focused tests, then one final backend suite, frontend tests/build, and diff check. 4. Update verification documentation with explicitly deferred browser, Docker, and exhaustive timing checks. 5. Check acceptance criteria supported by accumulated objective evidence and close TASK-17.
<!-- SECTION:PLAN:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Corrective pass on dirty HEAD a614547: 195 backend tests and 49 frontend tests pass; declared Node 24 production build passes. Native disposable SQLite/Chrome walkthrough verified create, activate, adopt, evaluate, accept/reject, pending intent, deep-link refresh, assistant evidence, and actual ZIP. AUTO_PAPER native toggle was blocked by auto-review. Full twelve-scenario matrix, populated M4 upgrade, S2/S3 and recovery timing, and pagination remain open; see planning/reports/research-M5.md. Task stays In Progress with AC unchecked.

2026-09-16 corrective follow-up: user approved disposable AUTO_PAPER toggle and configured OpenAI API check. Native enable/history and real-provider grounded answer passed; one synthetic prompt-injection check rejected invented cash/trade. Populated archived V12 to V13 upgrade preserved original paper rows, V12 checksum, foreign keys and immutability; repeat startup passed. Fixed LF/CRLF V12 checksum compatibility, duplicate cycle evaluation, late-acceptance re-evaluation, duplicate same-state valuations, and incomplete valuation UI. Required backend clean test passes 199/199; frontend tests 50/50 and declared Node 24 production build pass. TASK-17 remains open: full S2/S3 timing/readiness/corporate-action matrix, native disable/executed/blocked states, pagination, provider failure coverage and inherited browser checks remain incomplete. See planning/reports/research-M5.md.

2026-09-16 final continuation: fixed the demonstrated audit-pagination defect with typed previous/next state for all six paper collections; fixed S2/S3 readiness to use the latest eligible completed month end and require observed warm-up for every required listing; added provider-failure grounded fallback/no-financial-mutation coverage. Final checks: backend ./gradlew clean test 202/202, frontend npm test 52/52, Node 24.21.0 production build PASS at 632.75 kB with the existing CSS budget warning, git diff --check PASS. M5 remains PARTIAL/In Progress because the complete twelve-scenario S2/S3/corporate-action/AUTO_PAPER matrix, inherited M4 native form/holdout checks, and Docker remain NOT VERIFIED; see planning/reports/research-M5.md.

Resumed on 2026-09-16 after independent review. Chronological intent processing was missing; code change is in progress.

Fixed due-intent scan order and paused later due batches behind an earlier missing opening bar; added a regression test. Corrected ex-date entitlement to use pre-open holdings, and updated delayed-entitlement fixture plus same-day purchase test. Focused PaperPortfolioIntegrationTest passes 14/14.

2026-09-16 scope decision approved by user: finish cost-effectively without further browser/Docker runs or exhaustive scenario expansion. Existing native evidence remains valid; untested exhaustive variants will be documented as limitations rather than blockers.

Lean finalization completed without further browser or Docker runs. Final objective checks: affected backend tests PASS; ./gradlew clean test PASS with 213/213; frontend npm test PASS with 52/52; declared Node 24.21.0 production build PASS at 634.61 kB with the existing CSS budget warning; git diff --check PASS. Spotless and frontend lint tasks are not configured. Remaining exhaustive native/timing variants are accepted documented limitations, not hidden PASS claims.
<!-- SECTION:NOTES:END -->

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
Completed M5 prospective paper tracking and grounded explanations, including the final chronological-intent, ex-date entitlement, dataset/session validation, mode-transition concurrency, and experiment-form corrections. Verified focused affected paths, backend 213/213, frontend 52/52, the Node 24 production build, accumulated disposable-browser/upgrade/export evidence, and a clean diff. Closed with exhaustive browser, Docker, and full timing-matrix variants explicitly deferred by user scope decision.
<!-- SECTION:FINAL_SUMMARY:END -->
