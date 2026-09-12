---
id: TASK-12
title: Implement M2 - Historical import and data inspection foundation
status: Done
assignee:
  - '@antigravity'
created_date: '2026-09-12 10:37'
updated_date: '2026-09-12 11:11'
labels: []
dependencies:
  - TASK-11
documentation:
  - planning/PROMPT-SIGNALFORGE-M2.md
priority: high
type: feature
ordinal: 12000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Implement M2 historical data import and data inspection foundation as specified in planning/PROMPT-SIGNALFORGE-M2.md. Establish versioned import bundle contract, robust zip parser with archive limits, V3 migration with immutable historical datasets and triggers, validation engine with knowledge-time filtering, bounded asynchronous import job runner with idempotency and restart recovery, /api/research import/history APIs, Angular data inspection screen at /research/data, synthetic test fixtures, import guide, comprehensive tests, and research-M2 report.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 ZIP bundle import contract and robust parser implemented enforcing archive safety, schema validation, and CSV normalization
- [x] #2 V3 schema migration applied with immutable datasets, listings, sessions, raw bars, actions, triggers, and foreign keys
- [x] #3 Validation engine and knowledge-time reader implemented with strict OHLC, calendar, corporate actions, and as-of filtering
- [x] #4 Bounded asynchronous import job runner implemented with idempotency, same-byte deduplication, restart recovery, and atomic publication
- [x] #5 Research REST APIs (/api/research/imports, jobs, datasets, history) and Angular inspection UI (/research/data) implemented
- [x] #6 Synthetic test bundles, historical import guide, and comprehensive backend/frontend verification completed with research-M2 report
<!-- AC:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
1. Create V3 database migration adding immutable datasets, dataset_listings, dataset_sessions, historical_bars, historical_actions, and import_jobs with immutability and decimal triggers, updating MigrationRunner safely without modifying V1/V2 historical checksums.
2. Implement RFC-4180 CSV parser and ZIP bundle extractor with archive safety limits (expansion cap, file count, path traversal check) and strict JSON manifest parser.
3. Implement HistoricalDataValidator enforcing OHLC bounds, session calendar coverage, corporate action consistency, and knowledge-time rules.
4. Implement HistoricalImportJobService with bounded single-worker queue, lifecycle states, idempotency (same-key replay, conflict detection, same-byte deduplication), atomic publication, and startup restart recovery.
5. Implement HistoricalHistoryService supporting explicit dataset/listing date range queries and as-of knowledge-time cutoffs.
6. Implement research REST APIs (/api/research/imports, /jobs/{id}, /datasets, /datasets/{id}, /history/{listingId}, /actions, /sessions).
7. Implement Angular data inspection screen at /research/data and header navigation with zoneless change detection and subscription cleanup.
8. Generate synthetic deterministic test bundles via Python script and write import guide documentation in planning/docs/historical-import.md.
9. Execute comprehensive test suite covering migration upgrade, ZIP upload, idempotency, validation errors, immutability triggers, as-of filtering, restart recovery, and Angular UI.
10. Compile planning/reports/research-M2.md and finalize task.
<!-- SECTION:PLAN:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Implemented full M2 historical import and data inspection foundation:
- Schema: V3__historical_datasets_and_import.sql with 6 relational tables and 10 immutability/integrity triggers. MigrationRunner safely updated to version 3 preserving V1/V2 historical checksums.
- Core: RFC-4180 CSV parser, ZIP bundle extractor with archive safety limits (ratio, size, entry count, zip-slip protection).
- Validation: HistoricalDataValidator enforcing OHLC bounds (high >= max, low <= min, positive values), calendar sessions, point-in-time publication limits (close_time <= available_at), and corporate action rules.
- Queue & Storage: Single-worker asynchronous HistoricalImportJobService with payload SHA-256 deduplication, idempotency-key handling, and restart crash recovery.
- Query: HistoricalHistoryService supporting point-in-time 'asOf' knowledge cutoffs.
- API: Full /api/research REST endpoints for imports, jobs, datasets, listings, history, actions, and sessions.
- Frontend: Angular /research/data view with DATA INSPECTION header tab, upload modal, real-time job status polling, canvas candlestick charts, and tabular bar/actions inspection.
- Tests & Docs: 119 backend tests (100% pass), 29 frontend tests (100% pass), production build pass, test/fixtures/historical/ synthetic bundles, planning/docs/historical-import.md, and planning/reports/research-M2.md.
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
Milestone 2 fully implemented and verified. All 6 acceptance criteria verified with 119 backend unit/integration tests and 29 Angular tests. Historical import, RFC-4180 parsing, archive safety, validation engine, asynchronous idempotent job pipeline, point-in-time history queries, Angular data inspection UI, documentation guide, and completion report delivered.
<!-- SECTION:FINAL_SUMMARY:END -->
