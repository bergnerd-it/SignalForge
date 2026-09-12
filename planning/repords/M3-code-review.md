# M3 Code Review

Date: 2026-09-12  
Reviewed commit: `855b9498bef8663a8223ad3e695d190f76a21281` (`feature/m3`) plus all tracked and untracked working-tree changes present at review time  
Specification: `planning/PROMPT-SIGNALFORGE-M3.md`  
Implementation report checked: `planning/reports/research-M3.md`

## Verdict

M3 is **not implemented correctly enough to close or to start M4**. The reference arithmetic passes, and the build is green, but mandatory owner isolation, deterministic output, frozen reproducibility metadata, interval validation, corporate-action timing, cancellation/idempotency behavior, complete frontend series loading, and several acceptance tests are missing or defective. The statement in `research-M3.md:230-232` that M3 is “100% complete and fully verified” is not supported by the code or tests.

## Findings

### CRITICAL-1 — Run resources are not owner-scoped, and `series` permits SQL injection

**Classification:** Verified defect; reproduced with an isolated in-memory SQLite database.

`BacktestController` accepts an owner only for create and list. Detail, cancel, equity, orders, events, and export accept only a run ID (`backend/src/main/java/com/bergnerd/signalforge/app/research/backtest/BacktestController.java:47-104`). Their service queries likewise use only `run_id` (`BacktestJobService.java:380-415`, `437-555`; `BacktestExportService.java:24-35`). Anyone who knows another run ID can read its configuration/results, download it, or request cancellation.

In addition, orders and events concatenate the request-controlled `series` value into SQL (`BacktestJobService.java:480-493`, `522-535`). The review reproduction used `CANDIDATE' OR run_id <> '` and returned an order belonging to another run.

**Impact:** Cross-owner disclosure and mutation; SQL injection can bypass run filtering.

**Remedy:** Obtain the authenticated owner on every endpoint; constrain every run and child query by owner through a join or prior owner-scoped lookup. Parse `series` into `SeriesType`, reject invalid values with 400, and bind it as a JDBC parameter. Add two-owner controller/integration tests, including malicious `series` values.

### HIGH-1 — The requested reporting interval and month-end rule are not enforced

**Classification:** Verified defect by code inspection.

`requestedStartDate` is accepted but never used in window selection (`BacktestDataReader.java:79-85`, `144-182`). There is no check that the selected evaluation date is a completed month-end session or that the cutoff is after the required close/bar availability. If the requested end is closed or absent, the code silently uses the last earlier trading session (`BacktestDataReader.java:167-182`) instead of requiring the configured end session and complete coverage as required by the prompt (`PROMPT-SIGNALFORGE-M3.md:55-57`). Timestamp eligibility is decided by substring and lexicographic string comparison (`BacktestDataReader.java:144-158`) rather than parsed instants/calendar rules.

**Impact:** A request can run a materially different period from the one the user selected while appearing successful.

**Remedy:** Parse dates/instants strictly; validate requested start as the selected completed month-end session, cutoff against required observation availability, and end as an explicit trading session. Reject missing/closed end coverage with an actionable 4xx/failure reason. Persist and display the reason for any supported requested/effective boundary difference.

### HIGH-2 — Corporate-action timing is incomplete and sometimes financially wrong

**Classification:** Verified defect by code inspection; edge cases are untested.

Precise payments are processed only when already earlier than a session open (`BacktestEngine.java:178-208`). A payment arriving between open and close is therefore omitted from that session's cash snapshot, contrary to `PROMPT-SIGNALFORGE-M3.md:78`. Date-only payments are handled only when their date equals a trading session (`BacktestEngine.java:322-349`), so a payment on a declared closed day never settles at the calendar-day boundary. A future known payment remains only in an in-memory `PendingDistribution`; its date/instant is not stored in holdings, events, or final results (`BacktestEngine.java:31-36`, `414-449`).

Input validation also silently converts a missing/invalid split ratio to `1` and rounds ratios to eight decimals (`BacktestDataReader.java:61-66`), permits a null `available_at` (`258-279`), and never enforces EUR distribution currency. This conflicts with strict rejection and precision requirements (`PROMPT-SIGNALFORGE-M3.md:74-80`, `101`).

**Impact:** Cash availability, reinvestment dates, snapshots, units, and exported reproduction data can be wrong.

**Remedy:** Model payment events on the complete calendar timeline, including intraday and closed-date boundaries; persist outstanding receivables with source action and payment timing. Strictly validate availability, currency, action fields, and representable split precision before execution.

### HIGH-3 — Business output and frozen configuration are not reproducible

**Classification:** Verified defect by code inspection.

Orders/events use random UUIDs and wall-clock `Instant.now()` values throughout the simulation (`BacktestEngine.java:85-103`, `118-135`, `157-173`, `246-278`, `281-317`, `331-347`, `377-432`). The prompt requires business ordering/results to depend only on frozen inputs (`PROMPT-SIGNALFORGE-M3.md:82`).

The stored configuration is merely the raw request (`BacktestJobService.java:124-145`). The schema/response omit dataset input/content checksums, parser/schema identity, calendar/timezone, execution/accounting versions, engine/source commit/build/dirty identity, and classification/assumptions (`V4__backtest_engine.sql:4-33`; `BacktestDtos.java:161-190`). The canonical hash omits those fields and silently rounds spread/slippage to two decimals (`BacktestDtos.java:89-111`). The export adds a hard-coded engine version at generation time (`BacktestExportService.java:41-67`), so a future binary can describe an old run with new metadata and same-key replay can silently reuse an obsolete engine.

**Impact:** Identical runs do not produce identical audit artifacts, and an exported run cannot be reliably attributed or reproduced.

**Remedy:** Build a normalized immutable config before insert, including every identity required at `PROMPT-SIGNALFORGE-M3.md:44-51`; include it in the canonical intent hash. Generate stable event/order IDs from run/series/sequence and separate deterministic market timestamps from operational job timestamps. Export the stored snapshot verbatim.

### HIGH-4 — Idempotency and cancellation are not concurrency-safe

**Classification:** Verified defect by inspection; repeat-cancel trigger failure reproduced in memory. The concurrent insert race was not executed under load.

Creation performs `SELECT` followed by `INSERT` without a transaction or unique-conflict recovery (`BacktestJobService.java:76-98`, `124-155`). Two simultaneous same-key calls can both see no row; one then fails the unique constraint instead of returning the original run or a stable 409.

`cancelBacktest` omits `CANCELLED` from its terminal no-op states (`BacktestJobService.java:388-403`). Repeating cancellation updates a terminal row and is rejected by `prevent_completed_backtest_run_update` (`V4__backtest_engine.sql:113-120`), violating repeat-safe cancellation. A queued cancellation leaves its task in the executor; when it starts, `markCancelled` tries another update on the already terminal row (`BacktestJobService.java:161-166`, `311-315`). Completion uses an unconditional update after a separate cancellation read (`260-290`), so cancellation and completion are not resolved by one conditional durable transition.

**Impact:** Duplicate submissions and cancellation races can return 5xx responses or inconsistent terminal behavior.

**Remedy:** Insert-first and resolve the unique conflict by loading and comparing the existing row. Use conditional state transitions (`WHERE status IN (...) AND cancel_requested = ...`) and check affected-row counts. Make every terminal status, including `CANCELLED`, repeat-safe and remove/correctly ignore cancelled queued tasks.

### HIGH-5 — The Angular result view silently omits the benchmark and truncates data

**Classification:** Verified defect by API/client contract inspection.

The equity endpoint defaults to `CANDIDATE` (`BacktestController.java:57-64`; `BacktestJobService.java:441-470`). The client calls it once without a series and then expects both candidate and benchmark rows (`frontend/src/app/services/backtest.service.ts:95-115`; `research-backtests.component.ts:113-120`). Consequently, the benchmark polyline is empty. The client also takes only the first default page: 1,000 candidate equity rows and 100 orders/events, with no continuation or partial-data warning (`backtest.service.ts:95-153`; `research-backtests.component.html:261-395`). This violates `PROMPT-SIGNALFORGE-M3.md:154`.

Outstanding requests are independent subscriptions and are not cancelled or checked against the current run (`backtest.service.ts:54-70`, `95-153`), so a slower response for run A can overwrite run B. Deep-link loading only selects an ID found in the first run-list page (`research-backtests.component.ts:86-101`).

**Impact:** Charts and audit tables can be incomplete or belong to the previously selected run while looking authoritative.

**Remedy:** Load both series and all pages (or add an explicit combined bounded endpoint), expose pagination/loading state, and cancel/suppress stale requests with a selected-run stream plus `switchMap`. Resolve deep links directly by ID.

### MEDIUM-1 — Analytics omit required values and miscalculate first-year return

**Classification:** Verified defect by code inspection.

The first annual period starts from the first closing equity instead of initial funded equity (`BacktestAnalyticsCalculator.java:196-227`). In the reference run that omits the initial commission loss: yearly return is based on 999 to 1018 rather than 1000 to 1018. “Partial year” uses Jan 15/Dec 15 heuristics instead of the actual calendar/reporting boundaries (`204-208`). The returned summary has no exposure or cash weights despite the explicit requirement (`BacktestDtos.java:131-158`; `PROMPT-SIGNALFORGE-M3.md:125-136`). The daily-equity resource has no initial funded point; it starts after the first close (`BacktestEngine.java:396-409`).

**Impact:** The annual table disagrees with cumulative return and omits required portfolio composition metrics.

**Remedy:** Include an explicit initial funded observation/boundary, derive first-year return from it, label partial years from actual calendar coverage, and return exact exposure/cash weights with documented turnover convention.

### MEDIUM-2 — Terminal immutability and foreign keys are incomplete

**Classification:** Verified defect; post-terminal insert reproduced in memory.

V4 blocks update/delete but has no insert triggers on result tables (`V4__backtest_engine.sql:113-192`). A child event was successfully inserted after its run was terminal. Candidate and benchmark listing columns are plain text and do not reference `dataset_listings(dataset_id, listing_id)` (`V4__backtest_engine.sql:10-13`), contrary to the completion report's strict-FK claim at `research-M3.md:36-47`.

**Impact:** Completed results can be extended after publication, and a run can reference listings outside its dataset at the database boundary.

**Remedy:** Add follow-on insert guards for terminal parents and composite foreign keys for both listing roles. Do not edit the applied V4 migration once released; add a new migration.

### MEDIUM-3 — Export is neither bounded/streamed nor exact for negative decimals

**Classification:** Verified defect by code inspection.

The export loads every table into `StringBuilder`s and returns the entire ZIP as a `byte[]` (`BacktestExportService.java:24-173`; `BacktestController.java:87-96`), with no row or byte limit. Its CSV sanitizer prefixes every value beginning with `-` with an apostrophe (`BacktestExportService.java:183-193`), changing legitimate negative drawdowns and cash deltas rather than exporting exact decimal strings. The manifest lacks the frozen identities and assumptions described in HIGH-3.

**Impact:** Large exports can exhaust heap, and numeric CSV contents no longer exactly match API/accounting values.

**Remedy:** Enforce documented limits and stream the ZIP response. Escape formula-capable text by column/type; emit numeric columns as validated exact decimals without an apostrophe. Populate the manifest from frozen run metadata.

### MEDIUM-4 — Frontend types do not match the backend contract

**Classification:** Verified defect by code inspection.

The service uses `any` for paged responses and mapping (`frontend/src/app/services/backtest.service.ts:41-45`, `95-151`), and the component adds another `as any` (`research-backtests.component.ts:86-92`), contrary to the repository's strict typing rule. `BacktestOrderDto.orderStatus` excludes backend status `SKIPPED` and includes unsupported `CANCELED`/`EXPIRED` (`frontend/src/app/models/backtest.model.ts:104-119`; backend `BacktestDtos.java:36-39`). The backend does not return `totalCashImpact`, so the client displays `0.00` for fills (`backtest.service.ts:121-136`) even though the table labels it “CASH IMPACT” (`research-backtests.component.html:331-360`).

**Impact:** Contract changes and malformed responses evade compilation, and the UI presents false execution values.

**Remedy:** Define typed `PagedResponse<T>` and wire DTOs exactly to the backend. Add the required cash-impact field or calculate it from exact returned components under one documented contract.

### MEDIUM-5 — The completion report overstates verification

**Classification:** Verified documentation/test-evidence discrepancy.

The report claims concurrent duplicate submissions and cancellation were verified (`research-M3.md:101-104`), but `BacktestIntegrationTest` tests only sequential replay/conflict (`180-207`) and contains no cancellation race/repeat test. The restart test covers one manually inserted `RUNNING` row, despite its name mentioning queued jobs (`BacktestIntegrationTest.java:311-342`). Required focused cases from `PROMPT-SIGNALFORGE-M3.md:174-187` are largely absent: weekend/holiday transitions, precise/closed-date payment, unpaid receivable metadata, split precision, >page-size input, deterministic repeated output, publication rollback, concurrent creation, cancellation/completion race, full upgrade preservation, stale UI responses, and native download verification.

The report also calls the export “streaming” (`research-M3.md:24`) although it is fully buffered, and says V4 has composite listing foreign keys (`research-M3.md:37`) although it does not. The claimed browser walkthrough has no reproducible command, artifact, or result details beyond prose (`research-M3.md:27-30`, `135-155`).

**Impact:** Green suites create false confidence in the precise risks the M3 prompt required tests to establish.

**Remedy:** Correct the closeout report after implementation fixes. Add focused tests for every missing gate and record reproducible browser steps plus the downloaded archive's contents/hash.

## Assumptions and untested risks

- **Untested risk:** Export memory failure at realistic upper bounds. No workload limits exist, but heap exhaustion was not induced during this review.
- **Untested risk:** The select-then-insert idempotency race and cancellation/completion race are evident in control flow but were not stress-tested concurrently.
- **Untested risk:** No native browser session was rerun. Static inspection already proves that the benchmark series cannot be loaded by the current client call and that pagination/stale-response requirements are unmet.
- **Assumption:** The existing local `X-User-Id` convention remains the intended owner identity source. Even under that convention, all ID-based endpoints must enforce it.

## Commands and actual results

All database-related tests used the repository's `TemporarySqliteInitializer` or `:memory:` SQLite; the owner's working database was not accessed.

| Command | Result |
|---|---|
| `backlog instructions overview` | Completed earlier in this conversation as required by `AGENTS.md`. |
| `git status --short`, `git rev-parse HEAD`, `git branch --show-current` | Reviewed the uncommitted M3 tree on `feature/m3` at `855b9498...`. |
| `git diff --check` | Reported one trailing blank line in `frontend/src/app/services/services.spec.ts:663` and an LF/CRLF warning for `app.routes.ts`. |
| `JAVA_HOME=.../jdk-21.0.12.1+1/Contents/Home ./gradlew clean test` | **PASS**, 146 tests in 25 suites, 0 failures/errors/skips; Gradle reported unchecked operations in `BacktestJobService`. |
| `./gradlew spotlessCheck` | **NOT AVAILABLE**: task `spotlessCheck` does not exist. |
| `npx --yes --package=node@24.21.0 --package=npm@11.18.0 npm run test -- --watch=false` | **PASS**, 3 files / 34 tests. |
| `npx --yes --package=node@24.21.0 --package=npm@11.18.0 npm run build` | **PASS**, production bundle 507.87 kB raw / 116.28 kB estimated transfer. |
| In-memory Python/SQLite V4 probe | Confirmed repeat-cancel update raises `Cannot update a terminated backtest run`; post-terminal child insert succeeds; injected `series` predicate returns another run's order. |

There is no frontend lint script in `frontend/package.json`.

## Recommended implementation order before M4

1. Fix owner scoping and parameterized enum filters first; add cross-owner/security tests.
2. Define and persist the complete normalized frozen configuration, then make IDs/business timestamps deterministic.
3. Correct interval/month-end and corporate-action/payment validation and execution semantics.
4. Make create/cancel/complete transitions atomic and repeat-safe; add concurrency and rollback tests.
5. Correct analytics, schema immutability/FKs, and exact bounded export.
6. Replace the frontend's permissive adapters with exact typed contracts; load both full series with visible pagination and stale-response suppression.
7. Execute every focused gate in `PROMPT-SIGNALFORGE-M3.md:174-189`, rerun a documented native browser/download workflow on a disposable database, and rewrite `research-M3.md` from the resulting evidence.

M4 should remain blocked until at least all CRITICAL and HIGH findings are fixed and the corresponding tests pass.
