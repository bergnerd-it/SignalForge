# Coding-agent prompt — SignalForge M1a

Place this file at `planning/prompts/PROMPT-SIGNALFORGE-M1A.md` if you keep prompts in the repository. The text below is the implementation instruction.

---

Implement **M1a — baseline stabilization only** in this existing SignalForge repository. SignalForge is the FinAlly fork. Keep the Spring Boot / Java / JDBC / Angular architecture.

The objective is a reproducibly runnable, locally bounded demo baseline on which the M1b accounting migration can safely be developed. Implement and verify the changes; do not stop after proposing a plan. End with a completion report. Do not implement M1b or M2.

## Read and establish context

Read, in this order:

1. Applicable `AGENTS.md` files and the repository's existing task-tracking instructions.
2. `planning/FINALLY-RESEARCH-SPEC-v0.1.md`.
3. `planning/reports/research-M0.md`.
4. `planning/SIGNALFORGE-SPEC-v0.2-M0-ADDENDUM.md`.
5. Relevant current architecture, deployment and test documentation, then the actual files being changed.

The addendum revises the proposed implementation scope; explicit user decisions remain authoritative. Follow the existing task-tracking workflow, including Backlog if applicable. Do not invent a second tracking system.

Record current HEAD and working-tree state. M0 reviewed `8d6597fca4d15e1c023a2a301494cb231de72797`; do not reset to it. Preserve unrelated local work. Verify each finding against current code before changing it, since it may already have been fixed.

Record the current spec SHA-256. The spec inspected for the addendum has hash `108e534cb87de13b1544207cffe4b090cdbca7e0dcb1563dd5d596bae148673b`; M0 recorded `b3ea37665b443b5419457b58548bb5665e00e95050d62d57a9f6d670e23de188`. Record any available diff or unresolved version mismatch and preserve newer decisions. Do not overwrite a local spec just to make the hash match. This discrepancy alone does not block the baseline repairs below.

## Scope and working rules

You may change application configuration, narrowly necessary startup/access code, tests, build/container inputs, safe sample configuration and corresponding documentation. Update the task record and write the report.

Use Java 21 and the actual locked frontend toolchain. Retain current declared application dependency versions. Use the existing Spring/JDBC and Angular/Vitest/Playwright tooling; do not introduce JPA, another frontend framework, a chart replacement, a database server or a new migration library. Deterministically pin/align existing E2E tooling and its browser image if required; record the exact rationale and versions. Respect any concrete repository dependency-approval rule, while continuing all already authorized work that is independent of it.

All runtime checks use uniquely named temporary databases and disposable test volumes. Never open, migrate, delete or attach the owner's existing database/volume. Do not copy private local configuration into an isolated test checkout or disclose credentials. Do not push, deploy remotely or create a release tag as part of this prompt. If gates pass, identify the tested commit/diff as a candidate pre-migration checkpoint.

## 1. Restore actual configuration loading

Investigate M0-01 in `backend/src/main/resources/application.yml` and current configuration tests.

- Consolidate duplicate `signalforge` mappings while preserving all intended nested settings.
- Verify the actual checked-in YAML through a real Spring Boot startup/context test. A test that only injects equivalent property strings does not prove YAML loading.
- Do not hide the failure through a test-only replacement YAML, exclusions, relaxed parsing, disabled tests or deleted assertions.
- Keep local override configuration optional and external where appropriate. Test startup with no private local file and with a safe temporary override. Use fake/empty provider credentials and explicit mock/simulator selection so verification does not call real LLM/market services.
- Resolve newly exposed baseline configuration failures within this scope. Report any substantive out-of-scope application defect with concrete evidence.

## 2. Make integration database isolation reliable

Address M0-14 in backend integration tests and the E2E configuration.

- Allocate a unique file-backed SQLite database per integration test context and register its datasource URL before context startup. The path must not resolve to an existing user database.
- Run the normal schema initialization against that database; avoid a reduced custom schema for integration evidence.
- Keep production-like datasource/transaction behavior and enforce foreign keys on each connection used by these integration checks.
- Demonstrate that independent pooled connections see the same schema/data.
- Close the application context/connections before deleting files. Check cleanup handles repeated runs without database locks or cross-test state leakage.
- Small genuinely single-connection unit fixtures may retain an in-memory database. Do not treat a pool of private `:memory:` databases as one shared database or solve the integration risk by globally reducing the production pool to one connection.
- Use a disposable file/volume for the E2E backend as well.

## 3. Enforce the intended local access boundary

Address M0-13 using existing facilities and a small, documented policy.

- Default native startup binds to `127.0.0.1`.
- Docker publishes host `127.0.0.1:8000:8000` (or the documented configured port). Keep the container's internal listener reachable by Docker forwarding; do not bind it only to container loopback.
- Replace wildcard CORS with same-origin behavior and an explicit allowlist for the actual local development origin(s).
- Enforce server-side checks for unsafe browser requests; merely omitting CORS response headers does not prove that a mutation was rejected. Use an exact-origin/host policy or an existing CSRF mechanism suitable for the current stack. Reject untrusted and `null` origins on mutations. Do not authorize arbitrary origins by substring or suffix matching.
- Define treatment of missing Origin for the application's native clients/tests, including the accepted Host and content types. Do not indiscriminately exempt all absent-Origin traffic. Preserve legitimate same-origin UI requests and required development preflight behavior. Keep test-only internal container access in the test configuration.
- Verify accepted local flows and denied requests actually have the expected database side effects, including a negative test proving a rejected cross-origin mutation writes nothing.
- No login system, remote-access feature or public deployment is part of M1a.

## 4. Make build and E2E inputs reproducible

Address the relevant M0-16/M0-17 harness findings.

- Add an appropriate root `.dockerignore` for the actual build context. Exclude host dependency directories, build outputs/caches, local databases and journal files, private configuration, secrets and irrelevant VCS data. Retain required sources, wrapper files and lockfiles. `.dockerignore` controls what is sent in the Docker build context. [Docker build context documentation](https://docs.docker.com/build/concepts/context/).
- Use `npm ci` where a lockfile is present. Ensure broad later copies cannot overwrite installed container dependencies with host `node_modules`.
- Align the existing Playwright package/lockfile/browser image and add a deterministic install step. Fix stale E2E assertions to match the intended current UI, keeping meaningful assertions rather than deleting them to pass.
- Ensure any health probe uses a mechanism that exists in the built image. Verify HTTP/application startup without implying that an UP response proves market-data freshness.
- Correct runnable artifact names, explicit mock configuration and deployment commands in the documentation. Supply a safe credential-free example if existing instructions reference a missing example file.
- Do not add a new lint/format/CI ecosystem to this step. If the repository has no lint or Spotless task, record it as unavailable. Preserve existing gates that actually exist. Do not perform unrelated dependency upgrades or bulk reformatting.

## 5. Verify the result

Run the actual repository commands with supported installed tools; record working directory, command, exit code and actual outcome. Expected starting points, after checking repository configuration:

- Backend: `./gradlew clean test` and `./gradlew bootJar` from `backend`, with Java 21 and an isolated datasource for startup checks.
- Frontend: clean lockfile installation, `npm run test -- --watch=false` and `npm run build` from `frontend`.
- Explicit startup/context evidence loading the shipped YAML.
- Tests for database isolation and the local-origin/host policy.

M0 recorded 36/52 backend tests passing and all 19 frontend tests passing. Do not reuse these numbers as your result; report the new totals and explain intentional additions/changes. Passing packaging is not equivalent to a successful application startup.

When a Docker daemon is available:

1. Use a distinct compose project and disposable volume, with explicit mock/simulator settings and no live provider calls.
2. Build from clean inputs and verify that required assets are packaged and private/local files are excluded.
3. Start the app and exercise the real frontend/HTTP flow: load the terminal, inspect the demo portfolio, make a simple supported demo trade and a watchlist mutation, and check corresponding responses/state.
4. Stop/restart the same disposable stack and check seeded balances/holdings and the chosen mutation persist as expected. Avoid assuming that an intentionally empty watchlist stays empty; that known seeding defect belongs to M1b.
5. Run the existing E2E suite and record its actual result.
6. Clean up only resources created by this task.

When Docker is unavailable, complete all feasible fixes and native tests. Supply exact container/E2E rerun commands with safe disposable paths/project names, and mark that acceptance gate NOT VERIFIED. Do not claim full M1a validation or silently substitute a native run for container evidence. Respect tool/network permission boundaries; report a concrete blocked action instead of changing access controls.

Add only tests that establish these behaviors or reproduce an identified defect. Do not expand into optional stress, performance or broad coverage work once the applicable M1a risks are resolved.

## Explicitly deferred work

Do not implement:

- Schema migration, decimal TEXT columns, ledger, portfolio/listing identity or durable operation storage.
- A partial monetary patch that leaves the current persistence and service accounting inconsistent.
- Historical import, strategy rules, backtests, provider selection or paid data subscriptions.
- New research screens, chart overhaul, prospective strategy trades or research LLM actions.
- General fixes for every M0 finding.

Keep the existing single-trade rollback behavior intact. Document M0-02, M0-04, M0-05, M0-06 and M0-08/09 as remaining accounting/execution work for M1b, along with other deferred findings. This milestone restores a runnable demo; it does not make its balances suitable for investment research.

## Required report and stop condition

Write `planning/reports/research-M1a.md` with:

1. Starting HEAD, tested HEAD plus uncommitted-diff status, effective spec identity and any unresolved hash discrepancy.
2. Changes grouped by purpose, with relevant paths and current line references.
3. M0 finding IDs fully addressed, partially addressed and deferred.
4. Exact validation commands, new test counts, outcomes and concise failure evidence.
5. An acceptance table: YAML startup, database isolation, backend suite, frontend suite/build, local access policy, clean container startup/restart and E2E; mark PASS/FAIL/NOT VERIFIED honestly.
6. Test database/volume isolation and cleanup evidence, without private contents or secrets.
7. Remaining known defects and environmental blockers; runnable verification commands for every unverified gate.
8. A short recommendation for M1b readiness. Distinguish code ready for review from all baseline gates passed. Retain migration/backup testing as M1b work.

Finish all feasible M1a implementation, validation and documentation. Then stop; do not start M1b. In the final response, summarize the result, point to the report, give the actual test outcomes and state any remaining gate clearly.
