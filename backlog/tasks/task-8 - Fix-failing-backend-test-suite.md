---
id: TASK-8
title: Fix failing backend test suite
status: Done
assignee:
  - '@codex'
created_date: '2026-09-11 21:29'
updated_date: '2026-09-11 21:31'
labels: []
dependencies: []
type: bug
ordinal: 8000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
The backend test suite cannot load Spring application contexts because application.yml contains a duplicate top-level signalforge key. Restore a passing backend suite without changing unrelated planning work.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 Spring configuration loads without duplicate YAML keys.
- [x] #2 The complete backend test suite passes with Java 21 and an isolated SQLite test database.
<!-- AC:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
Remove the duplicated configuration mapping, run the focused context tests, then run the complete backend suite and address only failures caused by this defect.
<!-- SECTION:PLAN:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Removed the duplicate top-level signalforge mapping from backend/src/main/resources/application.yml. Focused ApplicationLocalConfigTest and HealthControllerTest passed. Full Java 21 verification with an isolated SQLite file passed: 13 suites, 52 tests, 0 failures/errors/skips. No Spotless plugin/task is configured.
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
Restored Spring configuration loading by keeping a single signalforge configuration block. The complete backend suite now passes under Java 21 with isolated database configuration.
<!-- SECTION:FINAL_SUMMARY:END -->
