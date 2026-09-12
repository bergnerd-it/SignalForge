# SignalForge M1a completion report

Date: 2026-09-12 (Europe/Berlin)

## Identity and scope

- Starting and tested repository HEAD: `0ebd5f346f128f7275f5f296b4d82bbc2d7e4efc` (`prepare m0`). The implementation and this report are an uncommitted working-tree diff on that HEAD; no tag, push, deployment, schema migration, or M1b/M2 work was performed.
- M0 reviewed commit: `8d6597fca4d15e1c023a2a301494cb231de72797`. The current baseline had already advanced before M1a began and was not reset.
- Current `planning/FINALLY-RESEARCH-SPEC-v0.1.md` SHA-256: `b3ea37665b443b5419457b58548bb5665e00e95050d62d57a9f6d670e23de188`, equal to the hash recorded by M0.
- Current addendum SHA-256: `208970ccae5c9f6c429e692c40ab2136edb02573f7a0861e951d9a5346b52fe4`.
- Current M0 report SHA-256: `c84af574bc242f9b00b24c0cab5bef10f1be3f9dd2229070c7d56ba8d426ac88`.
- The addendum says its inspected v0.1 input had SHA-256 `108e534cb87de13b1544207cffe4b090cdbca7e0dcb1563dd5d596bae148673b`. That content is not present in Git or the working tree, so an actual content diff is unavailable. The mismatch remains unresolved; the current v0.1 text plus the newer addendum decisions were used.

The worktree contains the M1a files listed below and the Backlog TASK-9 record. `git status --short` at completion reports only these intended M1a modifications/untracked files. Application dependencies and database contents were not changed.

## Implemented baseline changes

### Configuration and SQLite isolation

- `backend/src/main/resources/application.yml:1-34` contains one `signalforge` mapping, defaults native HTTP to `127.0.0.1`, and applies `PRAGMA foreign_keys=ON` through Hikari for every opened connection.
- `backend/src/test/java/com/bergnerd/signalforge/app/ApplicationLocalConfigTest.java:22-60` starts the real application from shipped YAML twice: once with private imports disabled, and once with a generated credential-free YAML override. Both use disposable file-backed SQLite and mock provider settings.
- `backend/src/test/java/com/bergnerd/signalforge/app/TemporarySqliteInitializer.java:14-46` allocates a unique file before the integration context starts. It makes the datasource depend on a registered cleanup bean, causing Hikari to close before the database, WAL, and SHM files are deleted.
- `backend/src/test/java/com/bergnerd/signalforge/app/SignalForgeIntegrationTest.java:27-109` uses that initializer and the normal `DatabaseInitializer`. Lines 72-81 hold two independent Hikari connections concurrently and prove both see the seeded schema/data and both report `PRAGMA foreign_keys=1`.

### Local access boundary

- `backend/src/main/resources/application.yml:1-3` binds native startup to loopback by default. `Dockerfile:26-28` changes only the internal container listener to `0.0.0.0`; `docker-compose.yml:8-18` publishes it solely at host `127.0.0.1:8000`.
- `backend/src/main/java/com/bergnerd/signalforge/app/config/WebConfig.java:16-21` replaces wildcard CORS with exact Angular development origins.
- `backend/src/main/java/com/bergnerd/signalforge/app/config/LocalRequestPolicyFilter.java:15-89` rejects unsafe requests with untrusted, malformed, or `null` origins. Exact same-origin requests and the two development origins are accepted. A request with no Origin is accepted only for a loopback Host and, for POST/PUT/PATCH, a JSON-compatible content type; loopback DELETE remains usable without a request body.
- `backend/src/test/java/com/bergnerd/signalforge/app/WatchlistControllerTest.java:74-105` covers `null` Origin, untrusted Host, and non-JSON absent-Origin rejection before service invocation. `SignalForgeIntegrationTest.java:84-100` proves an allowed development request reaches the real database and a denied cross-origin request returns 403 without changing its row count.

### Deterministic build and E2E inputs

- `.dockerignore:1-26` excludes VCS/editor state, private local configuration, host dependencies, caches/build output, reports, planning data, and SQLite databases/journals from the root build context.
- `Dockerfile:1-33` uses the frontend lockfile through `npm ci`, retains Java 21, installs the `curl` binary required by the existing healthcheck, uses the correct wildcard JAR artifact, and relies on environment properties without an unexpanded exec-form `${...}` JVM argument.
- `test/package.json:1-12` and `test/package-lock.json` pin Playwright 1.45.0 to the existing `mcr.microsoft.com/playwright:v1.45.0-jammy` image and TypeScript 5.4.5. `test/docker-compose.test.yml:1-34` performs `npm ci`, uses a disposable named SQLite volume, binds the test port to host loopback, and retains internal service reachability. Compose parsing passed.
- `test/e2e/workstation.spec.ts:3-24` corrects the stale subtitle text while retaining the behavior assertions.
- `application-local.yml.example:1-8` is credential-free and selects the mock LLM explicitly. `README.md:20`, `README.md:149-193` documents local binding, locked frontend installation, actual test command, and disposable compose project commands.

## M0 finding disposition

| Finding | Disposition | Evidence / remaining impact |
|---|---|---|
| M0-01 duplicate YAML | Fully addressed | One mapping loads through real Spring Boot startup tests and the packaged application. |
| M0-13 network exposure | Code and native tests addressed; container runtime gate not verified | Exact CORS/request policy and loopback defaults are tested. Docker daemon was unavailable, so actual port publication remains unexecuted. |
| M0-14 pooled in-memory SQLite | Fully addressed for integration/E2E configuration | Unique files/volume replace `:memory:`; two concurrent connections and foreign keys are tested. |
| M0-16 reproducibility | Partially addressed | Native backend/frontend gates, lockfiles, `npm ci`, build context, Compose syntax and packaged startup pass. Image build and Playwright browser execution remain unverified. |
| M0-17 readiness evidence | Partially addressed | Safe commands and concrete native evidence now exist. Container restart/persistence evidence remains unavailable. |
| M0-02, M0-04, M0-05, M0-06, M0-08, M0-09 | Deferred to M1b as required | Monetary representation, accounting/idempotency, migrations/reconstruction, quote executability, and LLM authorization/audit boundaries remain unsuitable for investment research. |
| M0-03, M0-07, M0-10, M0-11, M0-12, M0-15 | Deferred | Portfolio/listing isolation, historical data, valuation consistency, research charts, strict frontend state/contracts, and accounting/concurrency coverage were outside M1a. |

## Validation record

Commands were run from the directory shown. Exit codes and results are actual results from this working tree.

| Directory | Command | Exit | Result |
|---|---|---:|---|
| `backend` | `JAVA_HOME=/Users/oliver/.gradle/jdks/eclipse_adoptium-21-aarch64-os_x.2/jdk-21.0.12.1+1/Contents/Home ./gradlew tasks --all --no-daemon` | 0 | Task list succeeded; no Spotless/format task exists. |
| `backend` | `JAVA_HOME=... ./gradlew clean test bootJar --no-daemon` | 0 | Final run: 59 tests, 0 failures/errors/skips; `signalforge-backend-1.0.0-SNAPSHOT.jar` built. |
| repository root | temp-file search after the final backend run | 0 | No `signalforge-integration-*`, `signalforge-yaml-*`, or override DB/journal files remained. |
| `frontend` | `node --version; npm --version; npm ci` | 0 | Node 26.8.1/npm 11.19.0; clean install of 375 packages succeeded. |
| `frontend` | `npm run test -- --watch=false` | 0 | 3 files, 19 tests passed. |
| `frontend` | `npm run build` under installed Node 26.8.1 | 134 | Angular builder aborted without diagnostic after `Building...`; this unsupported local runtime was not accepted as build evidence. |
| `frontend` | `npx --yes --package node@24 -c 'npm run build'` | 0 | Node 24-compatible production build succeeded; output `frontend/dist/frontend`. |
| `test` | `npm install --package-lock-only --ignore-scripts` | 0 | Generated aligned deterministic lockfile; npm reported two high-severity advisories in this old pinned E2E tool tree. |
| `test` | `npm ci --ignore-scripts` | 0 | Clean install of 5 packages from the new lockfile succeeded. |
| repository root | `docker compose config --quiet && docker compose -f test/docker-compose.test.yml config --quiet` | 0 | Both Compose configurations parsed successfully. |
| repository root | `docker info` | 1 | Client available, daemon unavailable: `/Users/oliver/.docker/run/docker.sock` does not exist. |
| `backend` | packaged Java 21 JAR on `127.0.0.1:18080` with `/tmp/signalforge-m1a-startup-20260912.db`, mock LLM, empty market key | 0 | Tomcat started; normal schema and seed initialized. `curl --fail --silent --show-error http://127.0.0.1:18080/api/health` returned HTTP success with `status: UP`. Hikari shut down before the disposable DB was removed. This proves process/HTTP startup, not market-data freshness. |
| repository root | `git diff --check` | 0 | No whitespace errors. |

An earlier focused run exposed two harness defects: Java's `TestRestTemplate` did not transmit the test Origin header as expected, and the first cleanup singleton lacked a registered destruction callback. The final tests use MockMvc against the real application/database for Origin evidence and register the cleanup callback explicitly. Both were rerun successfully; only disposable test files from failed runs were removed.

## Acceptance status

| Gate | Status | Evidence |
|---|---|---|
| Shipped YAML startup, no private file | PASS | Two real Spring starts plus packaged JAR startup. |
| Safe temporary YAML override | PASS | Generated YAML changes the real environment; mock mode prevents provider calls. |
| File-backed database isolation / foreign keys / cleanup | PASS | Unique pre-start file, two simultaneous pooled connections, `foreign_keys=1`, no residual files. |
| Backend clean suite | PASS | 59/59 tests. |
| Backend bootJar and real HTTP startup | PASS | Java 21 JAR built and returned `/api/health` over loopback. |
| Frontend clean install and unit suite | PASS | `npm ci`; 19/19 tests. |
| Frontend production build | PASS | Node 24 build passed. Node 26 local attempt failed with exit 134. |
| Local host/origin policy | PASS (native/test) | Positive/negative controller and real-DB side-effect tests. |
| Compose syntax | PASS | Production and test files parse. |
| Clean container build/start/restart/persistence | NOT VERIFIED | Docker daemon unavailable. |
| Playwright E2E browser flow | NOT VERIFIED | Docker daemon unavailable; dependency install and static alignment pass only. |

To execute every unverified container gate without touching the owner's normal volume:

```bash
docker compose -p signalforge-m1a-e2e -f test/docker-compose.test.yml down --volumes --remove-orphans
docker compose -p signalforge-m1a-e2e -f test/docker-compose.test.yml build --no-cache
docker compose -p signalforge-m1a-e2e -f test/docker-compose.test.yml up -d app-test
curl --fail http://127.0.0.1:18000/api/health
docker compose -p signalforge-m1a-e2e -f test/docker-compose.test.yml run --rm playwright
docker compose -p signalforge-m1a-e2e -f test/docker-compose.test.yml restart app-test
curl --fail http://127.0.0.1:18000/api/portfolio
curl --fail http://127.0.0.1:18000/api/watchlist
docker compose -p signalforge-m1a-e2e -f test/docker-compose.test.yml down --volumes --remove-orphans
```

The Playwright flow performs a watchlist add/remove and demo trades; after restart, the portfolio/watchlist calls provide persistence evidence. The final `down --volumes` removes only this distinct Compose project's disposable volume.

## Known limits and M1b recommendation

The code is ready for review as an M1a candidate pre-migration checkpoint, but all baseline gates have not passed until the container build, restart persistence, and Playwright flow run with a Docker daemon. The E2E lockfile deliberately retains Playwright 1.45.0 to match the existing browser image; npm currently reports two high-severity advisories, so a separately reviewed aligned Playwright/image upgrade should follow rather than silently widening M1a.

M1b can begin after review of this diff and, preferably, the disposable Docker commands above passing. M1b should first implement a tested backup/restore and one migration mechanism, then introduce portfolio/listing identity, exact decimal persistence, ledger-based accounting, transactional/idempotent operations, and reconstruction tests together. It must keep M0-02, M0-04, M0-05, M0-06, M0-08, and M0-09 visible until those invariants and execution boundaries are proven.
