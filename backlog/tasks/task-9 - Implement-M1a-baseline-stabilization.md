---
id: TASK-9
title: Implement M1a baseline stabilization
status: Done
assignee:
  - '@codex'
created_date: '2026-09-11 21:48'
updated_date: '2026-09-11 22:31'
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
1. Resolve source/spec identity and tool compatibility evidence. 2. Inspect E2E advisories and align Playwright/image only if evidence requires it. 3. Run a uniquely named disposable Docker stack, browser/origin checks, and executable restart-persistence assertions. 4. Re-run native regression gates, calculate final diff checksum, and write the closeout report.
<!-- SECTION:PLAN:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Validated on Java 21: ./gradlew clean test bootJar passed with 59/59 tests; packaged loopback HTTP startup passed. Frontend npm ci and 19/19 tests passed; Node 24 production build passed. Compose syntax passed. Docker daemon unavailable, so clean container restart/persistence and Playwright remain NOT VERIFIED with rerun commands in planning/reports/research-M1a.md.

Closeout: M1a is now commit 2c781c4 plus closeout manifest adf3502c. Angular 22.1.7 engines confirm Node 26 was supported; prior abort cause remains unconfirmed, and Node 24.21.0 is pinned/verified. Updated E2E-only Playwright/image from 1.45.0 to 1.63.0 for GHSA-7mvr-c777-76hp; clean audit is zero. Added browser denial and executable restart-persistence checks. Docker daemon 29.7.2 ran and isolation resolved, but two clean builds failed before stages due Docker Hub TLS timeouts; no project resources were created. Final native gates: backend 59/59 and bootJar, frontend 19/19 and production build.
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
Completed all feasible M1a closeout work and documented it in planning/reports/research-M1a-closeout.md. Native gates and dependency audit pass; container/browser/restart gates remain blocked by Docker Hub TLS reachability and are not claimed as verified.
<!-- SECTION:FINAL_SUMMARY:END -->
