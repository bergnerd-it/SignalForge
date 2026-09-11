# SignalForge — finish M1a verification

Use this prompt in the existing local Codex session after making the local Docker daemon available. It is based on the supplied `research-M1a.md`, not an independent review of its implementation diff.

---

Complete the remaining **M1a verification and narrow harness fixes** in this SignalForge repository. Implement the required checks and fixes, write the report, then stop before M1b. Preserve the successful M1a implementation and unrelated working-tree changes.

Read applicable `AGENTS.md` and task-tracking instructions, the effective research spec/addendum, `planning/reports/research-M0.md`, and `planning/reports/research-M1a.md`. Inspect the current Dockerfile, Compose files, Playwright tests/configuration, local request filter, and relevant lockfiles before changing them. Follow the existing task tracker.

## 1. Preserve the reviewed state and resolve document identity

Record starting HEAD, current diff and actual tested source state. The prior report describes uncommitted changes on `0ebd5f346f128f7275f5f296b4d82bbc2d7e4efc`; that commit alone does not identify the M1a implementation. Do not reset or discard the diff. Report a final diff checksum or an actual implementation commit if one is created under the repository workflow. Do not push or create a release tag.

If supplied, compare `planning/reference/FINALLY-RESEARCH-SPEC-v0.1-chat-reference.md` with the canonical `planning/FINALLY-RESEARCH-SPEC-v0.1.md`. The reference should have SHA-256 `108e534cb87de13b1544207cffe4b090cdbca7e0dcb1563dd5d596bae148673b`; the repository copy previously had `b3ea37665b443b5419457b58548bb5665e00e95050d62d57a9f6d670e23de188`.

Summarize substantive differences and state the effective baseline for M1b, retaining subsequent explicit user decisions and the addendum. Do not overwrite either version merely to match checksums. If the comparison copy is unavailable, record that limitation and continue the container verification. The known addendum hash is `208970ccae5c9f6c429e692c40ab2136edb02573f7a0861e951d9a5346b52fe4`.

## 2. Correct the Node compatibility explanation

The report's Node 26 build abort does not establish that Node 26 is unsupported. M0 found an accepting engine range in the lockfile. Angular's official table also lists Node 26 for Angular 22.0.x: [Angular compatibility](https://angular.dev/reference/versions).

Inspect the exact installed/locked Angular CLI/build package engine requirements. Record the full Node/npm versions actually used, including the successful Node 24 patch version if recoverable. Choose and document a supported, reproducible Node version for the host and Docker build stage. Check the Docker stage against those requirements.

Retain the successful Node 24 build as evidence. Correct the prior explanation to “local Node 26 attempt aborted; cause unconfirmed” unless new evidence proves a specific cause. Do not spend this task investigating the abort once the selected supported toolchain passes the required build.

## 3. Inspect the two reported E2E dependency advisories

Inspect the actual lockfile and current audit details in `test/`. Record advisory IDs/URLs, affected packages, affected/fixed versions and whether the path belongs only to the test runner. Two reported high-severity advisories do not by themselves establish an exploitable production vulnerability.

If a small test-tool update is required to address a confirmed advisory or browser compatibility failure, update the existing Playwright package, lockfile and browser image together to a verified compatible version. This prompt scopes that targeted test-harness change; respect any still-applicable explicit dependency approval rule. No application framework upgrades or broad `npm audit fix --force`.

Pin the selected package/image versions and use `npm ci`. Matching the Playwright dependency to the image is necessary for its browser executables: [Playwright Docker documentation](https://playwright.dev/docs/docker). If a relevant issue cannot be resolved within this scope, report the concrete residual issue without claiming a clean dependency check.

## 4. Execute isolated container and browser checks

Check `docker info`. If the daemon remains unavailable, finish feasible checks/documentation and name the missing prerequisite in the report. Do not claim container success or start M1b.

Use a newly generated unique Compose project name, a disposable named volume, and an unused host loopback port. Inspect resolved volume mappings to ensure there are no external volumes, shared explicit volume names or bind mounts to the owner's database. A project-name prefix alone is not proof of isolation. Never begin by deleting resources from a pre-existing fixed project name.

Select mock LLM and synthetic demo data explicitly; do not use credentials or make real market/LLM calls.

1. Build the actual application image from clean inputs. Confirm its Node stage is compatible, the frontend is packaged, and private configuration/databases/host dependencies are excluded.
2. Start the disposable app and wait for readiness with a bounded retry/deadline. Verify the actual host port is bound to loopback and the internal listener permits container access. Do not treat Compose parsing as runtime evidence.
3. Run the real Playwright browser suite against the packaged UI. Inspect actual browser Origin/Host behavior and service DNS addressing. Fix only demonstrated harness or local-policy defects; do not disable the request filter or introduce wildcard CORS to make tests pass.
4. Add or run a real browser/API flow that confirms an allowed mutation works and a rejected cross-origin mutation changes no persisted state. Keep the local policy enforced in the same image being tested.
5. Demonstrate restart persistence with assertions as specified below.
6. Clean up only the uniquely identified containers/networks/volumes created by this task. Preserve concise logs and test results before cleanup.

### Required restart comparison

The earlier rerun commands merely print portfolio/watchlist responses after restart. Successful GET requests alone do not prove persistence.

In the disposable installation, execute a simple whole-unit demo trade and retain a deliberately non-empty modified watchlist. Capture cash, position quantities/acquisition costs, persisted trade count/IDs when accessible, and normalized watchlist membership. Use read-only database access if an HTTP endpoint does not expose the necessary persisted fields; avoid hardcoding unverified endpoint names.

Stop/start or restart the same app with the same disposable volume, wait for readiness, and assert that those persistent fields are unchanged. Do not compare simulator-dependent market value, P&L, quote prices or periodic snapshot counts as if they were stable. Verify cash was not reset to its seed amount and existing trades were not duplicated; do not assume a funding ledger already exists. Do not test the known empty-watchlist reseeding fix as if it belonged to this milestone; that remains M1b work.

Make the comparison executable and repeatable, not a manual inspection of two JSON dumps.

## 5. Regression checks and completion report

Run the final clean backend suite/JAR build and frontend suite/production build with the documented toolchains. Record new results; do not reuse the prior 59/59 and 19/19 counts without execution. Run clean installation for any changed test lockfile and rerun the relevant browser checks after fixes. Stop optional testing once these concrete gates are sufficiently verified.

Write `planning/reports/research-M1a-closeout.md`. Preserve the original M1a report as dated evidence; correct its unsupported Node inference by explicitly referencing it in the closeout.

Include:

- Starting and final source identity, with the implementation diff accounted for.
- Effective spec/addendum identity and the result or remaining limit of the spec comparison.
- Narrow changes made and their reasons.
- Full tool versions, exact commands, exits and actual test counts.
- PASS/FAIL/NOT VERIFIED for native gates, clean image build, packaged UI, local port/origin behavior, Playwright and asserted restart persistence.
- Before/after persistence assertions and resource-isolation/cleanup evidence.
- Specific E2E advisory disposition without confusing test dependencies with production exposure.
- Any failed or unexecuted gate and the exact remaining prerequisite.
- Recommendation on whether M1b can start from this reproducibly identified baseline.

The accounting, migrations, durable operation keys, quote-quality gates, portfolio isolation and research functionality remain M1b/later work. Do not implement partial versions of them during closeout. Finish all feasible work, report remaining blockers honestly, and stop before M1b.
