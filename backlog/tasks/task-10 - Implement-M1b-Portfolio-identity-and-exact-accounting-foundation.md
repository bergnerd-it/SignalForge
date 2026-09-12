---
id: TASK-10
title: Implement M1b - Portfolio identity and exact accounting foundation
status: Done
assignee:
  - '@antigravity'
created_date: '2026-09-11 22:44'
updated_date: '2026-09-12 07:19'
labels: []
dependencies:
  - TASK-9
documentation:
  - planning/PROMPT-SIGNALFORGE-M1B.md
  - planning/SIGNALFORGE-SPEC-v0.2-M0-ADDENDUM.md
  - planning/FINALLY-RESEARCH-SPEC-v0.1.md
priority: high
type: feature
ordinal: 10000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Implement M1b portfolio identity and exact accounting foundation as specified in planning/PROMPT-SIGNALFORGE-M1B.md and planning/SIGNALFORGE-SPEC-v0.2-M0-ADDENDUM.md. Establish reproducible pre-migration checkpoint and disposable legacy fixtures, JDBC migration runner with schema_migrations, decimal domain types, pure accounting core, durable transactional operations, legacy state migration with reconciliation, minimal research APIs/UI, and thorough durability/verification tests and report.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 Native pre-migration checkpoint preserved and disposable multi-owner legacy fixtures generated with manifests
- [x] #2 Robust SQLite JDBC migration runner implemented with schema_migrations, trigger support, and verified backup/restore procedure
- [x] #3 Pure accounting core and durable operation service implemented with exact decimal arithmetic, acquisition cost, and idempotency
- [x] #4 Legacy state migrated with reconciliation, legacy demo routed through new operations, and quote/chat reliability improved
- [x] #5 Minimal research APIs and UI implemented under /api/research and Angular frontend with isolated PAPER portfolios
- [x] #6 Comprehensive verification passes covering migration, accounting, atomicity, idempotency, scope isolation, and planning/reports/research-M1b.md generated
<!-- AC:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
1. Preserve runnable native pre-migration checkpoint JAR, configuration manifest, and generate synthetic legacy SQLite fixtures. 2. Implement versioned schema_migrations, V1 schema, robust statement parser, and SQLite VACUUM backup/restore runner. 3. Implement pure AccountingCore (exact decimals, acquisition cost, half-even rounding, splits) and durable OperationService with TransactionTemplate writer coordination. 4. Implement legacy migration reconciliation, route legacy demo trading/chat through operations, and update quote freshness/unavailable handling. 5. Implement minimal research REST API (/api/research/portfolios) and Angular views with /demo and /research navigation. 6. Run full verification suite (accounting regression, durability, concurrency, migration, restore) and compile planning/reports/research-M1b.md.
<!-- SECTION:PLAN:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Implemented pure AccountingCore with exact BigDecimal arithmetic, two-decimal HALF_EVEN cash bookings, total acquisition cost basis, and split residual handling. Implemented durable OperationService with ReentrantLock top-level coordination, canonical intent hashing, and atomic transaction template execution. Implemented robust SQLite JDBC migration runner with V1 schema, trigger parsing, WAL-checkpoint backup, and legacy reconciliation. Implemented minimal research API and Angular research views. Verified with 79 backend tests (100% passing) and 21 frontend tests (100% passing). Published planning/reports/research-M1b.md.
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
Successfully completed Milestone 1b (M1b). Established reproducible pre-migration checkpoint (.checkpoint/m1a-checkpoint.jar) and disposable legacy multi-owner fixtures. Implemented authoritative SQLite JDBC MigrationRunner with V1 schema, trigger parser, WAL-checkpoint backup, and legacy reconciliation. Implemented pure AccountingCore (exact BigDecimal, HALF_EVEN cash, total acquisition cost basis, split residual handling) and durable OperationService with top-level writer coordination and idempotency. Implemented minimal research REST API and Angular frontend. Verified 100% test pass across 79 backend tests and 21 frontend tests, plus production builds. Published planning/reports/research-M1b.md.
<!-- SECTION:FINAL_SUMMARY:END -->
