# Code Review: SignalForge M1b

**Review date:** 2026-09-12  
**Reviewed commit:** `6d434c880bd9d316c3cb6fdb50031b7488ca2026` (`implement m1a close`)  
**Reviewed state:** Uncommitted M1b changes in the working tree  
**Specification:** `planning/PROMPT-SIGNALFORGE-M1B.md`  
**Implementation report:** `planning/reports/research-M1b.md`  
**Conclusion:** M1b is not ready for merge. Core accounting tests pass, but production paths and required acceptance evidence remain incomplete.

## Findings

### 1. Blocker — Research portfolio creation fails with foreign keys enabled

**Classification:** Verified defect

`backend/src/main/resources/db/migration/V1__init_m1b_schema.sql:65-72` defines `portfolio_creation_requests.portfolio_id` as a foreign key to `portfolios(id)`. `backend/src/main/java/com/bergnerd/signalforge/app/operation/OperationService.java:195-207` inserts the creation request before inserting the referenced portfolio.

Starting the packaged application against a fresh disposable SQLite database and calling `POST /api/research/portfolios` returned HTTP 500 with a foreign-key constraint violation. The main M1b user flow described in the closeout report therefore does not work with the production datasource configuration.

The operation integration tests use a raw `SQLiteDataSource` at `backend/src/test/java/com/bergnerd/signalforge/app/operation/OperationServiceIntegrationTest.java:54-70` without enabling `PRAGMA foreign_keys=ON`. This differs from `backend/src/main/resources/application.yml:24` and explains why the test suite does not detect the defect.

**Impact:** No research portfolio can be created in a production-configured fresh installation.

**Remedy:** Insert the portfolio before the creation-request row within the same transaction, so a losing concurrent transaction still rolls back its portfolio if the owner/key constraint loses. Run the integration suite through the production datasource setup and assert that foreign keys are enabled on every connection. Add a controller-level test against a temporary file-backed database.

### 2. High — The built-in manual-trade client does not satisfy the new request contract

**Classification:** Verified defect

`backend/src/main/java/com/bergnerd/signalforge/app/portfolio/PortfolioService.java:90-95` requires an idempotency key for every legacy trade. `frontend/src/app/services/portfolio.service.ts:30-36` posts the request unchanged, while `frontend/src/app/models/market.model.ts:33-38` defines no idempotency-key field.

Posting the same payload emitted by the Angular client returned HTTP 400 with `Idempotency key is required for trade execution`.

**Impact:** Manual trades from the existing terminal fail after the M1b backend change.

**Remedy:** Generate one stable key when the user submits a trade, attach it through the documented header or body field, and reuse that key when retrying the same intent. Add an Angular HTTP test and a backend contract test that use the actual client request shape.

### 3. High — Required chat idempotency and recovery are not implemented

**Classification:** Verified defect and missing required behavior

`frontend/src/app/services/chat.service.ts:82-95` sends only `{ message }`. `backend/src/main/java/com/bergnerd/signalforge/app/chat/ChatController.java:17-27` accepts the missing key, and `backend/src/main/java/com/bergnerd/signalforge/app/chat/ChatService.java:57-59` replaces it with a random UUID. A transport retry is consequently treated as a new intent and can invoke the model and actions again.

When a stable key is supplied, `ChatService.java:79-83` attempts to insert the same user-message ID again before checking durable request state. There is no chat-request table or persisted inference state from which to return or resume the result. Trade action rows are inserted one at a time at `ChatService.java:125-130`; watchlist changes at `ChatService.java:169-205` are not persisted as durable actions and have no stable per-action keys. Financial execution, action status changes, and assistant-message persistence occur in separate transactions without restart recovery.

The reported recovery test at `backend/src/test/java/com/bergnerd/signalforge/app/operation/OperationServiceIntegrationTest.java:391-420` only deletes conversation text and confirms that manually inserted action evidence remains. It does not test model retry, assistant-text failure, partial completion, or recovery.

**Impact:** Retried chat requests can execute a second model plan, fail on duplicate message insertion, or leave partially completed action sets that cannot be resumed safely.

**Remedy:** Require a client-provided stable request key. Persist a canonical intent hash, request/inference state, the complete validated action set, stable per-action identities, and result references outside deletable conversation text before executing any action. Resume incomplete requests without another model call and return completed results on matching retries.

### 4. High — Completed trade retries perform market I/O before the idempotency lookup

**Classification:** Verified defect from code inspection

`backend/src/main/java/com/bergnerd/signalforge/app/portfolio/PortfolioService.java:108-129` resolves a fresh executable quote before calling `OperationService.executeTrade`. The cached-operation lookup begins at `backend/src/main/java/com/bergnerd/signalforge/app/operation/OperationService.java:261-280`.

**Impact:** A matching retry can fail because the quote provider is unavailable or stale even though the original trade already committed. It also performs unnecessary provider calls and does not meet the explicit requirement to check completed operations before fetching another quote.

**Remedy:** Resolve the server-owned legacy portfolio first and query the completed operation using the canonical client intent before market I/O. Resolve a quote only when no completed operation exists, with the durable uniqueness check repeated inside the transaction. Add a provider invocation-count test and an unavailable-provider retry test.

### 5. High — Research execution invents instrument and listing identity

**Classification:** Verified defect from code inspection

`backend/src/main/java/com/bergnerd/signalforge/app/operation/OperationService.java:538-562` creates a supposedly resolved research listing whenever none exists. It assigns every ticker an ETF type, the hard-coded ISIN `IE00B4L5Y983`, XETRA venue, XETR calendar, and a fixed inception date.

**Impact:** Arbitrary symbols can be recorded under fabricated identities, corrupting research portfolio identity and provenance. Multiple unrelated symbols may receive the same ISIN.

**Remedy:** Reject research execution when no trusted resolved listing exists. Seed deterministic resolved listings only in internal test fixtures. Keep unresolved legacy listings isolated from research execution.

### 6. High — Versioned-schema detection accepts incomplete databases

**Classification:** Verified defect from code inspection

`backend/src/main/java/com/bergnerd/signalforge/app/db/migration/MigrationRunner.java:112-120` classifies any database containing `schema_migrations` as versioned when its maximum version is not greater than one. This includes an empty migration table. `MigrationRunner.java:195-219` then iterates zero rows and declares the database current without checking required tables, columns, constraints, or the presence of version 1.

The malformed-schema test at `backend/src/test/java/com/bergnerd/signalforge/app/db/MigrationRecoveryIntegrationTest.java:198-206` creates an unrelated table without `schema_migrations`; it does not exercise this case.

**Impact:** A partial or corrupted database can pass startup migration checks and fail later during normal requests or background work.

**Remedy:** Require the exact supported migration-version set, reject an empty or gapped migration history, and verify the expected schema structure before marking readiness.

### 7. High — Required migration rollback and old-binary restore evidence is missing

**Classification:** Untested required behavior

The restore test at `backend/src/test/java/com/bergnerd/signalforge/app/db/MigrationRecoveryIntegrationTest.java:257-302` verifies the checkpoint JAR hash and reads tables from a backup. It does not restore into a separate location, start the preserved old binary, or compare stable fields before and after old-binary startup. There is also no injected conversion-failure test proving complete rollback and deterministic subsequent retry.

The migration checksum does not actually cover the Java conversion implementation. `backend/src/main/java/com/bergnerd/signalforge/app/db/migration/LegacyDataMigrator.java:26-36` hashes a manually maintained version string, which `backend/src/main/java/com/bergnerd/signalforge/app/db/migration/MigrationRunner.java:286-291` combines with the SQL checksum. Conversion code can change without changing the recorded checksum.

**Impact:** The recovery and changed-historical-migration guarantees required for M1b have not been demonstrated.

**Remedy:** Add an injectable failure during legacy conversion and verify that schema renames, converted data, ledger entries, and migration metadata all roll back before a successful retry. Restore the backup to another file, compare schema/data, start the checkpoint JAR against it, and compare documented stable fields. Derive the conversion checksum from an immutable migration artifact that changes with the implementation.

### 8. High — Concurrency and atomicity tests do not prove the stated guarantees

**Classification:** Untested risk and overstated evidence

`backend/src/main/java/com/bergnerd/signalforge/app/operation/OperationService.java:34` uses one process-local `ReentrantLock`. The concurrent creation and competing-operation tests at `backend/src/test/java/com/bergnerd/signalforge/app/operation/OperationServiceIntegrationTest.java:222-325` call the same service instance, so the Java lock serializes the operations before SQLite concurrency is exercised.

The independent-connection test at `OperationServiceIntegrationTest.java:423-446` opens two connections and performs two reads; it executes no competing operation. No bounded SQLite busy handling or whole-operation retry was found.

The atomicity test at `OperationServiceIntegrationTest.java:98-132` manually updates state and throws inside a generic `TransactionTemplate`. It does not call `OperationService` or inject failures at operation-result, execution, ledger, cash, and position persistence stages as required.

**Impact:** Multi-connection correctness, same-key races, overspend protection, busy failures, and rollback of each real operation stage remain unproven.

**Remedy:** Use two independently constructed operation services and production-configured pooled connections with barriers. Exercise same-key and distinct-key writes through the service. Configure bounded busy handling and retry the complete operation with the same key. Add controlled persistence failure points for every required atomicity stage.

### 9. High — Decimal grammar, precision and bounds are not enforced

**Classification:** Verified defect from code inspection

`backend/src/main/java/com/bergnerd/signalforge/app/accounting/AccountingCore.java:78-83` silently rounds prices beyond eight decimals. Validation at `AccountingCore.java:263-277` checks only signs and nulls; it does not reject unsupported quantity precision or enforce documented numeric bounds. Portfolio starting cash is rounded at `OperationService.java:193` after the unrounded value was included in the canonical hash at `OperationService.java:109-111`.

The TEXT decimal columns in `backend/src/main/resources/db/migration/V1__init_m1b_schema.sql:13-23`, `:75-89`, and `:128-159` have no checks for canonical decimal grammar or supported bounds. Several enum-like fields, including instrument type, listing identity status, operation kind, ledger entry type, execution side, and execution model, also accept arbitrary values.

`backend/src/main/java/com/bergnerd/signalforge/app/db/migration/LegacyDataMigrator.java:119-124` and `:182-190` record `difference = '0.00'` unconditionally, even when legacy cash or average-cost conversion was rounded.

**Impact:** Unsupported precision can be silently changed, financially equivalent creation requests can hash differently, invalid decimal text can enter the database, and migration reconciliation can conceal lost precision.

**Remedy:** Introduce versioned parsing and normalization rules at the domain boundary, reject unsupported scales, hash the validated normalized intent, add database constraints where SQLite can enforce them, and calculate reconciliation differences from the original and converted values.

### 10. Medium — Research API mode and owner scoping are incomplete

**Classification:** Verified defect from code inspection

`backend/src/main/java/com/bergnerd/signalforge/app/research/ResearchPortfolioController.java:49-55` passes caller-controlled mode and currency into `OperationService`, which accepts `LEGACY_DEMO` at `OperationService.java:146-155`. The research creation endpoint is therefore not restricted to explicit PAPER/EUR requests.

The detail endpoint at `ResearchPortfolioController.java:101-109` retrieves a portfolio solely by ID. It does not constrain the lookup by the server owner or PAPER mode, although list retrieval does.

**Impact:** The public research API can create the wrong account mode and return out-of-scope legacy or other-owner portfolio details when an ID is known.

**Remedy:** Validate or hard-code PAPER and EUR at the research boundary. Load detail using portfolio ID, server owner, and PAPER mode together, returning 404 for out-of-scope IDs.

### 11. Medium — Research selection can publish stale responses and leaks subscriptions

**Classification:** Verified defect from code inspection

`frontend/src/app/services/research.service.ts:45-58` subscribes independently for every selection. A slow response for an earlier selection can overwrite a later selection. The imported `switchMap` is unused. `frontend/src/app/components/research/research.component.ts:33-51` creates four long-lived subscriptions but implements no destruction cleanup.

The existing research service test at `frontend/src/app/services/services.spec.ts:228-245` covers only one selection and uses `any` for the observed detail, so it does not verify strict typing or the required context-switch race.

**Impact:** Rapid account changes can display the wrong portfolio, and repeatedly creating the component leaves stale subscriptions active.

**Remedy:** Drive detail loading from a selected-ID stream with `switchMap`, and use the async pipe or `takeUntilDestroyed`. Add an out-of-order HTTP-response test.

### 12. Medium — Legacy display still fabricates fallback prices

**Classification:** Verified defect from code inspection

`frontend/src/app/app.ts:151-163` constructs a synthetic tick at price `100` whenever live data is absent. `frontend/src/app/app.html:42` also falls back to `100` for the trade bar, and line 63 falls back to a portfolio value of `10000`.

**Impact:** The UI can present a fabricated current price and estimated trade value while the backend correctly treats a missing executable quote as unavailable. This contradicts the implementation report's claim that the `100.0` fallback was eliminated.

**Remedy:** Represent missing legacy market data explicitly, disable trade submission until an executable tick exists, and keep any simulator price clearly identified as synthetic legacy-demo data.

### 13. Medium — The closeout report overstates implementation and verification status

**Classification:** Documentation defect

`planning/reports/research-M1b.md:3-4` marks the milestone complete and states that all native tests and builds pass. Its verification table at `research-M1b.md:133-154` reports migration recovery, atomicity, chat recovery, and multi-connection concurrency as passed even though the cited tests do not exercise the required scenarios. The documented local walkthrough at `research-M1b.md:196-212` cannot currently create its first research portfolio because of finding 1.

**Impact:** The report gives reviewers and subsequent milestone work an incorrect readiness baseline.

**Remedy:** Mark M1b incomplete, replace claimed evidence with the actual test scope, record the reproduced API and build failures, and list every outstanding acceptance gate before beginning M2.

## Verification performed

The review inspected both tracked diffs and 25 untracked files. No application code, dependency, or database content in the repository was modified.

| Command or check | Result |
|---|---|
| `backlog instructions overview` | Completed before repository work. |
| `git rev-parse HEAD` | `6d434c880bd9d316c3cb6fdb50031b7488ca2026` |
| `JAVA_HOME=... ./gradlew clean test bootJar --no-daemon` | PASS. 79 tests, zero failures/errors/skips; boot JAR generated. |
| Packaged application start with a fresh disposable SQLite database | PASS. Application reached running state and applied the fresh schema. |
| `POST /api/research/portfolios` with PAPER/EUR and an idempotency key | FAIL. HTTP 500 caused by the creation-request foreign-key violation. |
| Legacy manual-trade payload emitted by Angular, without an idempotency key | FAIL as an application flow. Backend returned HTTP 400. |
| Legacy chat payload emitted by Angular, without an idempotency key | HTTP 200. Confirms that the server silently creates a non-reusable request identity. |
| `npm run test -- --watch=false` | PASS. 3 test files and 21 tests passed. |
| `npm run build` | FAIL with exit 134 on the current host using Node `26.8.1` and npm `11.19.0`. The Dockerfile pins Node 24.21.0, but no host `.nvmrc`, `.node-version`, `.tool-versions`, or package `engines` entry was found. |
| `npm run lint` | Not available; `package.json` has no lint script. |
| `git diff --check` | PASS, with warnings that Git will convert several LF files to CRLF. |
| `./gradlew spotlessCheck --no-daemon` | Not executed successfully because the configured Gradle distribution lock under `/Users/oliver/gradle` was outside the writable review sandbox. This is an environment limitation, not a formatting result. |

## Acceptance assessment

The exact `BigDecimal` accounting sequences, ledger replay unit tests, append-only ledger triggers, basic fresh/legacy migration paths, backend compilation, backend unit tests, and Angular unit tests are useful foundations. They do not compensate for the broken research creation path or the missing durability boundaries.

Before M1b can be closed, findings 1–9 should be resolved and tested through production-equivalent SQLite configuration. The recovery, concurrency, atomicity, chat-resume, client-idempotency, and context-switch tests required by `planning/PROMPT-SIGNALFORGE-M1B.md:123-142` should then be run and reported accurately. M2 should not begin from the current state.
