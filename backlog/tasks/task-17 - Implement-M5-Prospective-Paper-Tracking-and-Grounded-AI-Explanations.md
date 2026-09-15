---
id: TASK-17
title: Implement M5 - Prospective Paper Tracking and Grounded AI Explanations
status: Done
assignee:
  - '@antigravity'
created_date: '2026-09-15 07:34'
updated_date: '2026-09-15 14:14'
labels: []
dependencies:
  - TASK-16
references:
  - planning/PROMPT-SIGNALFORGE-M5.md
  - planning/SIGNALFORGE-SPEC-v1.0.md
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
1. Confirm M4 baseline and git tag m4-checkpoint (completed).
2. Author V12 migration for paper_portfolio_segments, paper_proposals, paper_proposal_items, paper_execution_intents, paper_execution_results, paper_receivables, paper_processed_corporate_actions, and paper_valuations with immutability triggers.
3. Add ClockConfig with injectable Clock bean; implement PaperDataReadinessService for calendar coverage and snapshot validation.
4. Implement PaperPortfolioService integrating with core OperationService for atomic executions, CAS proposal state machine, and corporate actions.
5. Implement PaperExecutionCoordinator for AUTO_PAPER mode, pre-open scheduling, and downtime recovery.
6. Implement ResearchAssistantService with discriminated typed tools, owner checks, holdout exposure tracking, and fact cards.
7. Extend ResearchPortfolioController with paged list, activation, adoption, evaluation, accept/reject, mode toggle, export, and assistant endpoints.
8. Extend frontend research.service.ts and build ResearchPaperComponent at /research/portfolios/:id with proposal review, mode controls, and audit export.
9. Implement unit tests (readiness, lifecycle, allocator), slice tests (WebMvcTest), integration tests (PaperPortfolioIntegrationTest with controlled clock, barriers, recovery, upgrade), and frontend Vitest specs.
10. Execute full verification suite, declared Node 24.21.0 production build, native browser walkthrough, and deliver paper-tracking.md and research-M5.md.
<!-- SECTION:PLAN:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
Implemented M5 prospective paper tracking in EUR, V12 schema migrations, CAS proposal lifecycle, corporate action receivables, AUTO_PAPER execution coordinator, grounded AI assistant with fact cards/evidence references, full frontend UI at /research/portfolios/:id, paper audit ZIP/CSV export, docs/paper-tracking.md, and planning/reports/research-M5.md. Verified with 187 backend tests (including 8-thread concurrency barrier and populated V12 migration recovery), 48 frontend Vitest tests, and Angular production build on declared Node 24.21.0.
<!-- SECTION:FINAL_SUMMARY:END -->
