# SignalForge M1a closeout report

Date: 2026-09-12 (Europe/Berlin)

## Source and specification identity

- Closeout started while the M1a implementation was an uncommitted diff on `0ebd5f346f128f7275f5f296b4d82bbc2d7e4efc`. During closeout that preserved implementation became commit `2c781c4b0c756c37e222020017ff5f789548fc48` (`implement m1a`) on `feature/m0`; it is the final tested M1a implementation base.
- Closeout changes remain uncommitted on `2c781c4`: `Dockerfile`, `README.md`, TASK-9, `test/docker-compose.test.yml`, `test/e2e/workstation.spec.ts`, `test/package.json`, `test/package-lock.json`, this report, the supplied closeout prompt, and `test/verify-restart.py`.
- The deterministic closeout implementation source-manifest checksum is `adf3502cf8ac9543b9ada10595f51f3641b87ebf5cf739e8356bc83ee07e01d7`. It is SHA-256 over sorted `sha256 path` records for all non-planning/non-Backlog files changed from HEAD plus the untracked restart harness.
- Canonical `planning/FINALLY-RESEARCH-SPEC-v0.1.md`: SHA-256 `b3ea37665b443b5419457b58548bb5665e00e95050d62d57a9f6d670e23de188`.
- Addendum `planning/SIGNALFORGE-SPEC-v0.2-M0-ADDENDUM.md`: SHA-256 `208970ccae5c9f6c429e692c40ab2136edb02573f7a0861e951d9a5346b52fe4`.
- The requested `planning/reference/FINALLY-RESEARCH-SPEC-v0.1-chat-reference.md` was unavailable. A transient supplied file named `planning/PROMPT-SIGNALFORGE-M1A-reference.md` had SHA-256 `c13f6d38b4ce1d4686890f29c29f669b7c7bb9d548a946a564571313b453be6a`; inspection showed it was the 129-line M1a coding prompt, not the expected research-spec reference, and its hash did not equal `108e534cb87de13b1544207cffe4b090cdbca7e0dcb1563dd5d596bae148673b`. It therefore cannot resolve the specification discrepancy.

The effective M1b baseline remains the canonical v0.1 research specification, all later explicit user decisions, and the v0.2 M0 addendum. Neither observed document was overwritten to force a hash match.

## Narrow closeout changes

- `Dockerfile:2` pins the already successful and Angular-supported frontend build runtime to Node `24.21.0` instead of a floating Node 24 tag. `README.md:165` records that exact host toolchain.
- Installed `@angular/cli` and `@angular/build` are both `22.1.7`. Their package metadata accepts Node `^22.22.3 || ^24.15.0 || >=26.0.0` and npm `^6.11.0 || ^7.5.6 || >=8.0.0`. Thus both local Node `26.8.1` and selected Node `24.21.0` satisfy the engines. The earlier M1a report's statement that Node 26 was unsupported is corrected: the Node 26 build attempt aborted with exit 134, and its cause is unconfirmed. The passing Node 24.21.0 toolchain is selected for reproducibility; no further abort investigation was performed.
- `test/package.json:8-11`, `test/package-lock.json`, and `test/docker-compose.test.yml:21-31` align `@playwright/test`, `playwright`, and the Microsoft browser image at `1.63.0` (`v1.63.0-noble`). The image and package must match because the package expects its corresponding browser binaries.
- `test/docker-compose.test.yml:7-14` parameterizes the loopback host port and retains a project-scoped named database volume. This allows each closeout run to use a newly selected free port without changing the application-internal port.
- `test/e2e/workstation.spec.ts:45-58` adds a real browser/API assertion that an evil Origin receives 403 and does not add `ZZZZ` to the rendered watchlist. The existing UI watchlist test remains the allowed-mutation path.
- `test/verify-restart.py:1-94` makes persistence comparison executable. It adds a whole-unit AAPL trade and deliberately retains `ZZZZ`, records stable cash/quantity/average-cost/watchlist fields, copies the disposable database read-only to capture trade IDs and rows, restarts the same app, and asserts exact equality. It deliberately excludes simulator prices, market value, P&L, and snapshot counts.

No accounting, migration, idempotency, quote-quality, portfolio-isolation, historical-data, or research feature was implemented.

## Dependency advisory disposition

The original Playwright 1.45.0 lockfile produced two npm vulnerability entries representing one transitive issue:

- Advisory: `GHSA-7mvr-c777-76hp`, npm source `1109208`.
- URL: `https://github.com/advisories/GHSA-7mvr-c777-76hp`.
- Title: Playwright browser downloads did not verify SSL certificate authenticity.
- Affected package/range: `playwright <1.55.1`; the direct `@playwright/test` entry was reported through its dependency on `playwright`.
- Fixed range used here: Playwright 1.63.0. Package metadata requires Node `>=20`.
- Exposure: these packages belong only to the `test/` Playwright runner and are not copied into the production runtime image. The issue concerned test-browser acquisition, so the original result did not establish a production application vulnerability.

After aligned update and clean install, `npm audit --json` reports zero vulnerabilities across five test-runner dependencies. No `npm audit fix --force`, application dependency, Angular, or broad framework update was used.

## Container isolation and attempted runtime verification

Docker Desktop was launched and the daemon became available. Docker client and server are both `29.7.2`.

The selected run identity was:

- Compose project: `signalforge-m1a-closeout-20260912-a7c3`
- Host port: `127.0.0.1:18137`
- Network resolved as `signalforge-m1a-closeout-20260912-a7c3_default`
- Database volume resolved from logical `e2e-data` under that project; it is not external, has no explicit shared name, and has no bind mount to the owner's database.
- The only bind mount is `test/` into the Playwright runner. The application database is `/app/test-db/signalforge-e2e.db` on the disposable named volume.
- Runtime settings explicitly select `LLM_MOCK=true`, empty credential-free configuration, internal `SERVER_ADDRESS=0.0.0.0`, and host loopback publication.

The daemon check and resolved Compose inspection passed. The clean image build was attempted twice. Both attempts failed before any Dockerfile stage ran because Docker Hub TLS metadata lookup timed out. A separate bounded host probe, `curl --head --connect-timeout 10 --max-time 20 https://registry-1.docker.io/v2/`, also failed with `curl: (28) SSL connection timeout`. No local base/application images existed to continue offline.

The failed build created no matching containers, networks, or volumes, verified with Docker label filters for the unique project. There was therefore nothing to delete and no pre-existing fixed project was touched.

## Commands and actual results

| Directory | Command | Exit | Result |
|---|---|---:|---|
| repository | `docker info --format '{{.ServerVersion}}'` | 0 | Docker server `29.7.2` available after Docker Desktop launch. |
| repository | `SIGNALFORGE_TEST_PORT=18137 docker compose -p signalforge-m1a-closeout-20260912-a7c3 -f test/docker-compose.test.yml config --format json` | 0 | Unique network, project volume, mock settings, internal listener, and loopback port resolved as intended. |
| repository | same environment/project, `docker compose ... build --no-cache app-test` | 1 twice | Docker Hub TLS handshake timeout while resolving Node/Temurin metadata; no Dockerfile stage executed. |
| repository | `curl --head --connect-timeout 10 --max-time 20 https://registry-1.docker.io/v2/` | 28 | Independent SSL connection timeout confirmed registry reachability blocker. |
| repository | Docker `ps`, `volume ls`, and `network ls` filtered by the unique project label | 0 | No resources created by failed builds. |
| `test` | `npm install --package-lock-only --ignore-scripts && npm ci --ignore-scripts && npm audit --json` | 0 | Playwright 1.63.0 lock generated, four packages installed, zero vulnerabilities. |
| `test` | Python AST parse; `npx playwright test --list`; `npm audit --json` | 0 | Restart harness syntax passed; five Chromium tests discovered; final audit zero. Browser execution was not possible without the image. |
| `backend` | `JAVA_HOME=<Temurin-21.0.12.1> ./gradlew clean test bootJar --no-daemon` | 0 | Fresh run: 59 tests, 0 failures/errors/skips; executable JAR built. Gradle 8.10.2 ran on Java 21.0.12.1. |
| `frontend` | `npx --yes --package node@24.21.0 -c 'node --version && npm --version && npm ci && npm run test -- --watch=false && npm run build'` | 0 | Node 24.21.0/npm 11.19.0; 375 packages installed; 3 files/19 tests passed; production build passed. |

Spotless and frontend lint remain unavailable because the repository defines neither task. This closeout did not add a new formatting/lint ecosystem.

## Gate status

| Gate | Status | Evidence |
|---|---|---|
| Reproducible source identity | PASS | M1a commit `2c781c4`; closeout manifest checksum above. |
| Effective spec/addendum identity | PASS with limitation | Canonical/addendum hashes verified; requested `108e…` reference unavailable and the observed substitute was the wrong document. |
| Node compatibility and pinned build runtime | PASS | Angular 22.1.7 engine metadata inspected; clean Node 24.21.0 build passed. |
| E2E dependency advisory | PASS | GHSA inspected; aligned 1.63.0 package/image; clean audit zero. |
| Backend clean suite/JAR | PASS | 59/59 tests and `bootJar`. |
| Frontend clean suite/build | PASS | 19/19 tests and production build under Node 24.21.0. |
| Compose resource isolation | PASS (resolved configuration) | Unique project/port; project-owned non-external DB volume; no owner DB bind. |
| Clean image build | FAIL (environment) | Docker Hub TLS timeout before stage execution. |
| Packaged UI and image contents | NOT VERIFIED | Application image could not be built. |
| Runtime loopback/internal listener | NOT VERIFIED | Static resolution passed; no container could start. |
| Actual browser Origin/Host behavior | NOT VERIFIED | Five tests discovered, but browser image unavailable. |
| Allowed/rejected browser mutations | NOT VERIFIED in container | Native real-database tests from M1a pass; new Playwright denial test was not executed. |
| Asserted restart persistence | NOT VERIFIED | Executable comparison exists, but no application image could start. |
| Resource cleanup | PASS | Label-filtered inventory showed no resources after the failed builds. |

## Exact remaining verification

The missing prerequisite is outbound TLS access from Docker Desktop/host to `registry-1.docker.io` and Microsoft Container Registry. Once available, select a new project suffix and unused loopback port; do not reuse or pre-delete the project recorded above.

```bash
export SIGNALFORGE_TEST_PORT=18138
export SIGNALFORGE_TEST_PROJECT=signalforge-m1a-closeout-20260912-new1

docker compose -p "$SIGNALFORGE_TEST_PROJECT" -f test/docker-compose.test.yml config --format json
docker compose -p "$SIGNALFORGE_TEST_PROJECT" -f test/docker-compose.test.yml build --no-cache app-test
docker compose -p "$SIGNALFORGE_TEST_PROJECT" -f test/docker-compose.test.yml up -d app-test

python3 test/verify-restart.py \
  --project "$SIGNALFORGE_TEST_PROJECT" \
  --port "$SIGNALFORGE_TEST_PORT" \
  --compose-file test/docker-compose.test.yml

docker compose -p "$SIGNALFORGE_TEST_PROJECT" -f test/docker-compose.test.yml run --rm playwright
docker compose -p "$SIGNALFORGE_TEST_PROJECT" -f test/docker-compose.test.yml down --volumes --remove-orphans
```

The resolved configuration must be inspected before `up`; after completion, label-filtered `docker ps -a`, `docker volume ls`, and `docker network ls` should return no resources for the new project.

## M1b recommendation

M1a closeout code and native regression gates are ready for review, and the prior M1a implementation now has an actual commit identity. M1b should **not** start from a claim of fully closed M1a yet: clean image construction, packaged UI, real browser Origin/Host behavior, and asserted restart persistence remain unverified because registry access prevented image acquisition. Once the exact disposable commands above pass and the resulting closeout diff is committed, that commit is the reproducible M1b starting point.
