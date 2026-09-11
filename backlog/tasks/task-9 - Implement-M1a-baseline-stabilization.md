---
id: TASK-9
title: Implement M1a baseline stabilization
status: Done
assignee:
  - '@codex'
created_date: '2026-09-11 21:48'
updated_date: '2026-09-11 22:04'
labels: []
dependencies: []
type: feature
ordinal: 9000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Implement the bounded M1a prerequisite defined by planning/SIGNALFORGE-SPEC-v0.2-M0-ADDENDUM.md: runnable configuration, isolated integration databases, local access enforcement, deterministic container/E2E inputs, documentation and completion evidence. Preserve unrelated planning moves and defer M1b accounting.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 Shipped YAML is exercised by real Spring startup and integration contexts use unique file-backed SQLite databases with foreign keys and cleanup.
- [x] #2 Native and Docker defaults enforce the specified loopback and browser-origin boundary with positive and negative side-effect tests.
- [x] #3 Container and E2E inputs are deterministic, exclude private/local artifacts, and use aligned lockfile tooling.
- [x] #4 Backend and frontend suites/builds pass, or unavailable Docker gates are explicitly recorded with safe rerun commands.
- [x] #5 planning/reports/research-M1a.md records identity, changes, finding disposition, exact validation, isolation evidence and M1b readiness.
<!-- AC:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
1. Stabilize shipped configuration and isolated integration database harness. 2. Add exact local host/origin enforcement and tests. 3. Harden Docker, npm and Playwright inputs plus sample configuration/docs. 4. Run native and Docker checks where available. 5. Write and validate the M1a report, then stop before M1b.
<!-- SECTION:PLAN:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Validated on Java 21: ./gradlew clean test bootJar passed with 59/59 tests; packaged loopback HTTP startup passed. Frontend npm ci and 19/19 tests passed; Node 24 production build passed. Compose syntax passed. Docker daemon unavailable, so clean container restart/persistence and Playwright remain NOT VERIFIED with rerun commands in planning/reports/research-M1a.md.
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
Implemented M1a baseline stabilization: real YAML startup tests, unique file-backed SQLite contexts with per-connection foreign keys and cleanup, loopback/origin enforcement with no-side-effect denial tests, deterministic Docker/E2E inputs, and completion report. Native gates pass; Docker runtime gates are documented as NOT VERIFIED because the daemon is unavailable.
<!-- SECTION:FINAL_SUMMARY:END -->
