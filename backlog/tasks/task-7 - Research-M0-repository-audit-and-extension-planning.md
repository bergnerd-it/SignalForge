---
id: TASK-7
title: Research M0 repository audit and extension planning
status: Done
assignee:
  - '@codex'
created_date: '2026-09-11 13:56'
updated_date: '2026-09-11 14:13'
labels: []
dependencies: []
type: spike
ordinal: 7000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Audit the existing demo against planning/FINALLY-RESEARCH-SPEC-v0.1.md before research implementation. M0 only; preserve application code, dependencies and existing databases.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 Report cites actual code with severity, impact, remedies and evidence confidence for all requested audit areas.
- [x] #2 Report records reviewed commit, verification commands and actual results, plus a concrete M1 recommendation without implementation.
<!-- AC:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
Read architecture and specification; inspect implementation and tests; run isolated verification; write planning/reports/research-M0.md and verify scope.
<!-- SECTION:PLAN:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
M0 report written to planning/reports/research-M0.md. Reviewed commit 8d6597fca4d15e1c023a2a301494cb231de72797. Backend 52 tests: 36 pass, 16 fail due duplicate YAML key. Frontend 19/19 pass before and after isolated npm ci; production assets byte-identical. Backend bootJar passes. Disposable real-class probes verify fractional corruption, missing quote fallback, duplicate execution, chat audit divergence and working single-trade rollback. Docker unavailable. Application code, dependencies and existing databases unchanged.
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
Completed the audit report with severity, path/line evidence, practical remedies, validation results and a concrete M1 migration/accounting plan. Validated report references and clean repository scope. Existing backend failures are audit findings and remain unchanged under M0-only authorization; M1 was not implemented.
<!-- SECTION:FINAL_SUMMARY:END -->
