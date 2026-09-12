---
id: TASK-11
title: Correct M1b after independent review
status: Done
assignee:
  - '@codex'
created_date: '2026-09-12 08:22'
updated_date: '2026-09-12 09:51'
labels: []
dependencies:
  - TASK-10
references:
  - planning/reports/research-M1b-fixes.md
documentation:
  - planning/PROMPT-SIGNALFORGE-M1B-FIXES.md
  - planning/reports/codereview-M1b.md
modified_files:
  - backend/src/main/java/com/bergnerd/signalforge/app
  - backend/src/main/resources/db/migration
  - backend/src/test/java/com/bergnerd/signalforge/app
  - frontend/src/app
  - frontend/package.json
  - .nvmrc
  - .node-version
  - planning/reports/completion-report-M1b.md
  - planning/reports/research-M1b-fixes.md
priority: high
type: bug
ordinal: 11000
---

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 Production-configured research creation, scoped lookup, listing identity and manual trade flows satisfy the repaired contracts
- [x] #2 Trade and chat requests provide durable idempotent replay and restart recovery without duplicate effects
- [x] #3 SQLite concurrency and atomicity are verified through independent services, real transactions and injected operation failures
- [x] #4 Migration validation, conversion rollback, candidate compatibility and old-binary restore are demonstrated on disposable databases
- [x] #5 Decimal policy and reconciliation preserve exact auditable values and reject unsupported input
- [x] #6 Frontend selection, subscription cleanup and missing-price behavior are tested with the real request contract
- [x] #7 Pinned Java and Node builds and full suites pass and the M1b fixes report records actual evidence
<!-- AC:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
1. Capture the candidate state and add production-equivalent test infrastructure. 2. Repair schema, migrations, decimal validation, operation idempotency and concurrency. 3. Implement durable chat request/action recovery. 4. Align research/manual APIs and Angular clients/state. 5. Run focused and full native verification, including packaged API and restore flows. 6. Update superseded status and publish the fixes report.
<!-- SECTION:PLAN:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Corrected all 13 review findings. Final verification: Java 21.0.12.1 ./gradlew clean test PASS (104 tests, zero failures/errors/skips); backend build PASS; Node 24.21.0/npm 11.18.0 npm ci PASS, frontend tests PASS (23), production build PASS with one existing Research CSS budget warning. Packaged fresh and legacy-upgraded HTTP flows passed on disposable SQLite files. Native browser created an exact EUR PAPER portfolio and executed a manual trade; its loading-state and route-reload defects were fixed. Old checkpoint JAR restore startup, conversion rollback, candidate V1-to-V2 compatibility, chat recovery and real writer/busy races pass. Docker/container registry gate remains not verified as explicitly deferred.
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
Implemented the M1b corrective closeout across migrations, exact accounting, operation/chat idempotency, SQLite concurrency, scoped APIs and Angular state/contracts. Verified 104 backend tests, 23 frontend tests, both builds, packaged fresh/upgrade HTTP flows, native browser creation/trade, conversion rollback and old-binary restore. Evidence is in planning/reports/research-M1b-fixes.md; the prior completion report is marked superseded.
<!-- SECTION:FINAL_SUMMARY:END -->
