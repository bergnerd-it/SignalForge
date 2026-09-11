# SignalForge Research — v0.2 M0 Addendum

Date: 2026-09-11  
Status: reviewable implementation handoff; product defaults remain proposals  
Base document: `planning/FINALLY-RESEARCH-SPEC-v0.1.md`  
Place this document at: `planning/SIGNALFORGE-SPEC-v0.2-M0-ADDENDUM.md`

## 1. Purpose, evidence and precedence

Continue the FinAlly fork, already named SignalForge in the audited code. Retain Spring Boot, Java, JDBC, Angular and the modular monolith. Establish a runnable demo baseline, then replace the accounting boundary before adding research execution.

This is a versioned supplement, not a replacement copy of the full specification. Read v0.1 and this addendum together. The corrections below supersede the corresponding v0.1 descriptions and split M1 into M1a and M1b. All other v0.1 requirements, especially strategy definitions, historical timing, costs, data provenance and exclusions, remain in force as proposed requirements. Explicit user decisions and applicable repository instructions retain their authority.

Evidence comes from the supplied `planning/reports/research-M0.md`, reviewed here as a document. Its source-code findings and runtime results have not been independently rerun in this session. The report identifies commit `8d6597fca4d15e1c023a2a301494cb231de72797`; line references are valid for that revision, not necessarily the current checkout.

There is a baseline identity discrepancy to resolve locally:

| Document | SHA-256 |
|---|---|
| v0.1 inspected in this session | `108e534cb87de13b1544207cffe4b090cdbca7e0dcb1563dd5d596bae148673b` |
| Repository v0.1 recorded by M0 | `b3ea37665b443b5419457b58548bb5665e00e95050d62d57a9f6d670e23de188` |
| Supplied M0 report inspected here | `c84af574bc242f9b00b24c0cab5bef10f1be3f9dd2229070c7d56ba8d426ac88` |

LF/CRLF conversion and removal of the final newline did not explain the spec mismatch in local checks. The reason is unknown; do not infer a semantic conflict or discard either version. M1a records the current checkout/spec identity and any available diff. This does not block verified startup/test repairs. Establish the effective specification before M1b schema work, preserving any newer user decisions.

## 2. Corrected implementation baseline

This replaces the documentation-only starting assumptions in v0.1 sections 1, 3.1, 5.1 and the snapshot statement in section 14.

| Area | M0 evidence | Consequence |
|---|---|---|
| Runtime | Java 21, Boot 3.3.4, Spring MVC and `JdbcTemplate` | Keep direct JDBC; no JPA conversion or version upgrade is needed for M1 |
| Frontend | Angular standalone/zoneless; service state in `BehaviorSubject`; empty routes | Reuse layout and patterns; introduce research context deliberately in M1b |
| Tests | Backend: 52 run, 36 passed, 16 failed from duplicate YAML keys; frontend: 19 passed | Restore real configuration loading; a packaged JAR is insufficient evidence of startup |
| Delivery | Frontend clean build and backend `bootJar` passed; Docker/E2E unavailable | Verify container startup separately when a daemon is available |
| Trade atomicity | Existing proxied single-trade rollback passed an injected failure probe | Preserve this guarantee while replacing accounting; do not describe the service as nontransactional |
| Decimal handling | `double`/`REAL`; quantities can be rounded to two decimals | Replace domain arithmetic and persistence together in M1b |
| Persistence | Startup schema creation and count-based seeding, no migration history | Add one migration mechanism and reconciliation in M1b |
| Identity | Owner/ticker addressing, no portfolio or listing identity | Add explicit modes, portfolio IDs and listing IDs in M1b |
| Current data | Fabricated `100.0` fallback and stale prices can execute | Block unavailable/unverified fills in M1b; current quotes are not historical data |
| Snapshots | Every 30 seconds and after trades | Research uses session/event valuations; no five-second baseline assumption |
| Simulator | Ten seeded symbols, 500 ms ticks, GBM plus jumps | Explicitly synthetic; no claimed mean reversion or historical validity |
| API/charts | `/api/portfolio/history`; SSE quotes; custom SVG charts; 60 samples | Correct endpoint docs; do not treat sample-index charts as dated research series |
| Frontend tooling | Partial strict checking, handwritten CSS, no lint target | No claim of full strictness, Tailwind usage or successful linting |
| Local exposure | Wildcard CORS, broad published port, no login | Restrict default local access during M1a |

M0 reports a concrete arithmetic regression: two buys of `0.004` units at `100` produce `0.01` units and equity `10000.20`, instead of `0.008` units and equity `10000.00`. This establishes a defect in that probe; it does not establish corruption of the owner's existing database.

Other reported concerns, including multi-connection write races and actual network exploitation, remain untested risks. Preserve the report's distinction between runtime evidence, static evidence and extension gaps.

## 3. Decisions and bounded defaults

Use SQLite, a single locally running application instance, private use, ETF-first research, EUR research accounts, file import first and optional LLM as implementation defaults unless the user has subsequently chosen otherwise. Record these in the local handoff; do not request every draft decision again to perform technical repairs.

D2/D3 (real listings, benchmark, data provider and budget) do not block M1. D6 (investment horizon and loss tolerance) does not affect these infrastructure fixes. SQLite is the working D5 choice for M1b; it is not evidence of a separately approved permanent database commitment. Remote access remains outside this handoff.

The demo's USD presentation is an inference about legacy currency, because the old database has no currency field. Record this inference during migration. Never relabel existing numerical amounts as EUR. Unresolved instrument identity must remain unresolved; no invented ISIN, venue or ETF classification.

## 4. Revised M1 delivery sequence

| Stage | Deliverable | Explicit boundary |
|---|---|---|
| **M1a — Baseline stabilization** | Real YAML startup, isolated integration databases, deterministic build inputs, local-access boundary, usable test/deployment instructions and report | Existing schema/accounting remains; no migrations or research functionality |
| **M1b — Identity and accounting foundation** | Versioned migration, exact ledger, durable idempotency, legacy adapters, portfolio/listing identity, minimal research API/UI and restore evidence | No historical import, strategy execution, backtest engine or research AI actions |
| M2–M6 | Original v0.1 milestones | Unchanged |

M1a is an independently reviewable prerequisite. Passing M1a does not certify financial correctness, quote quality or research readiness. M1 as a whole is complete only after M1b and its accounting/migration gates pass. The next coding instruction should implement **M1a only** and end with `planning/reports/research-M1a.md`.

### M1a acceptance

1. Checked-in `application.yml` loads in a real Spring Boot test/startup. Duplicate mappings are consolidated without discarding properties or replacing production YAML in the test.
2. Integration tests use a unique temporary file-backed SQLite database per context, close connections before cleanup, and load the actual schema/application configuration. Small single-connection unit fixtures may retain private in-memory databases. Tests never open the owner's database.
3. The full backend and frontend suites pass, with actual commands and counts recorded. Newly visible baseline failures are addressed within scope; tests are not skipped to preserve a nominal pass count.
4. Native startup binds to loopback by default. Docker publishes the application on host loopback while the process inside the container remains reachable by port forwarding. Browser mutations enforce an explicit local-origin policy; wildcard CORS is removed. Allowed frontend flows and denied cross-origin mutations are both tested.
5. Docker inputs exclude secrets, local databases, dependency/build directories and private local configuration. Node stages use lockfile installs. E2E package/browser versions are aligned using the existing test stack. A missing lint task is recorded, not reported as a successful lint run.
6. With Docker available: build from clean inputs; start a disposable stack, load the real frontend, exercise a simple demo flow, stop/restart using its disposable volume, and run the E2E suite. No existing user volume is attached or deleted. Do not promise preservation of an empty watchlist until M1b fixes legacy seeding.
7. Without Docker: finish all feasible implementation and native verification, provide exact rerun commands and mark the container/E2E gate **not verified**. Do not mark the entire gate passed or change runtime permissions to manufacture a pass.

Tagging a runnable pre-migration checkpoint should occur only after applicable baseline gates pass and according to the user's repository workflow. Such a tag identifies runnable demo behavior, not correct research accounting. Do not tag the audited red HEAD as a verified release.

## 5. M1b accounting and identity contract

Use the M0 table plan as a design starting point, with the refinements below. Implement only the M1 records: migrations, portfolios, instruments/listings/aliases, operations, ledger entries, execution records, cash/position projections and legacy metadata/valuations. Do not scaffold empty history, strategy, job or proposal subsystems.

### Decimal and ledger rules

- Use decimal strings at research API and JDBC boundaries, `BigDecimal` in authoritative calculations, and canonical decimal `TEXT` in SQLite. No `getDouble`, `setDouble` or SQL floating-point sums in new accounting, apart from the isolated legacy conversion routine. Chart coordinate conversion may be approximate.
- Quantities are not money. Document supported precision and bounds and reject unsupported input instead of rounding units to cents. Whole-unit order sizing in M3 does not restrict M1b accounting fixtures or split residual storage.
- Retain total acquisition cost as the position projection; derive average cost for display. Sales remove proportional basis. Full liquidation removes all remaining basis so repeated rounded partial disposals cannot leave a phantom cost balance. Buy fees increase basis; sell fees reduce proceeds.
- Cash uses the versioned two-decimal HALF_EVEN policy; prices support eight decimals; divisions use DECIMAL128. Explain any residual allocation. Never repeatedly round the stored quantity or reconstruct total basis from a two-decimal average price.
- Initial funding is a ledger operation. `initial_cash` is descriptive metadata, not another amount to add during replay. Rebuilding a projection from ledger entries reproduces the stored cash, units and total basis without double funding.
- Keep ledger entries immutable through application APIs and add database-level update/delete rejection for ledger rows once inserted. Corrections append new entries. This protects application invariants; it does not claim tamper resistance against the database owner.
- Every execution and ledger entry belongs to the same portfolio as its operation. Enforce corresponding non-null keys, composite foreign keys and referenced unique constraints, not just controller checks.

### Idempotency and transactions

For existing portfolios, a durable key scoped by `(portfolio_id, operation_kind, idempotency_key)` identifies a request. Canonicalize validated intent before hashing: include financially relevant inputs, mode and scope; exclude request arrival time, JSON property order and retry metadata. Resolve a successful retry before requesting a fresh market quote or invoking an LLM. The identical request returns the original operation/result and fill even if market prices have moved; a different request using the same scoped key returns a stable conflict.

**Portfolio creation needs a different scope:** there is no portfolio ID yet. Use a durable owner-scoped creation request key, for example `(owner_id, CREATE_PORTFOLIO, idempotency_key)`, which maps to the created portfolio/result. Insert the creation request, portfolio, funding operation, ledger and projections atomically. Retrying creation must not produce another funded account. A small separate request table is acceptable; document the chosen design.

Commit event batch, executions, ledger, projections and durable result together. A failed attempt leaves no committed success or partial funding. Define failed-request retry semantics explicitly. Publish notifications after commit and keep remote calls outside the financial transaction.

For SQLite, implement one writer coordination path held through the completion of a top-level `TransactionTemplate` transaction, including commit/rollback. Prevent an accidental surrounding transaction from deferring commit beyond that guard. Acquire database write capability before reading balances to modify them; a pre-transaction retry lookup may be read-only but does not replace the in-transaction uniqueness check. Bounded retries restart the entire operation with the same key. Do not nest a manual `BEGIN` inside an existing Spring transaction. This design follows SQLite's single-writer and transaction-upgrade behavior, and Spring's programmatic transaction boundary. [SQLite transactions](https://www.sqlite.org/lang_transaction.html), [Spring programmatic transactions](https://docs.spring.io/spring-framework/reference/data-access/transaction/programmatic.html).

Enable and verify foreign keys separately on every connection, before transactions start. Composite child keys must reference a matching parent primary/unique key. Read a multi-query portfolio in a consistent read transaction. [SQLite foreign keys](https://www.sqlite.org/foreignkeys.html).

Test independent concurrent connections as well as the application's writer coordination. An in-process lock is not a substitute for durable uniqueness, and a sequential test is not concurrency evidence.

### Listing identity and mode boundaries

Use immutable listing IDs. Resolve provider/symbol aliases within a namespace and effective interval, rejecting overlapping ambiguous mappings. A perpetual unique venue/symbol/currency tuple alone must not make historical ticker reuse impossible. M1b may support active fixtures and unresolved legacy identities; richer provider/calendar resolution remains M2.

New research accounts are `PAPER`, explicitly EUR and long-only. `BACKTEST` is reserved and not creatable until the run lifecycle exists. GET requests never create funded users or portfolios. Two PAPER portfolios for the same owner can hold the same listing independently.

Legacy REST and chat adapters always resolve their fixed `LEGACY_DEMO` target server-side. Explicit research target fields on legacy trade requests are rejected; ignoring them silently would make a user believe the wrong portfolio was addressed. No public arbitrary-ledger or synthetic-fill endpoint is added. M1b research creation/inspection works with the LLM unavailable.

Missing quotes are unavailable, not `100.0`. Distinguish source observation time from fetch time and reject stale or unverified provider fills under a documented demo policy. Synthetic simulator fills remain allowed only in the explicit demo mode. History and prospective executions will use separate validated adapters later.

Persist legacy chat action intent/results outside deletable conversation text and link them to durable execution IDs. A retry after assistant-message persistence fails must reuse the original action set/results rather than request new model instructions and duplicate completed trades. Clearing chat does not remove execution evidence. Malformed model instructions fail typed server validation before any mutation.

## 6. Migration and recovery requirements for M1b

The proposed migration mechanism is the M0 recommendation: a small ordered JDBC runner with a fixed migration registry and `schema_migrations`. This is a local implementation choice, not a general recommendation to build a migration framework.

- Use one authoritative startup mechanism; eliminate competing initializer writes. Migration finishes before schedulers or request handlers can mutate the database.
- Record version, description, checksum, application version and applied time atomically with migration changes. Checksum the full migration definition, including any Java conversion implementation through a reproducible build-generated digest; a fixed label alone does not detect conversion-code changes.
- Fail on an unknown/newer/partial schema or a changed historical checksum. Do not split SQL naively at every semicolon if resources contain triggers or other compound statements; use an existing appropriate parser or explicit statement boundaries.
- Back up with writers stopped and a tested SQLite-consistent procedure. Preserve original bytes/records and a manifest, including necessary journal state. A copy of an actively written main file alone is insufficient.
- Use a deterministic conversion rule for legacy finite REAL values; capture raw values, converted values and differences. This boundary is the sole permitted binary-floating-point read into the new accounting pipeline. Lost precision cannot be recovered.
- Preserve original trade IDs/times, snapshots, watchlists and chat history. If trade replay cannot explain current cash/units/basis, create explicit migration openings/adjustments and retain the original historical observations. Do not replay old trades on top of an opening that already includes them.
- Produce reconciliation per owner/portfolio/listing, including currency assumptions, unresolved identities and raw-to-new mappings. Detect invalid records and fail with actionable diagnostics instead of silently dropping them.
- Repeated startup must not create additional initial funding, holdings, adjustments or watchlist seeds. Intentional empty watchlists remain empty.
- Demonstrate restore into a separate disposable location using the pre-migration binary; compare the restored schema/data and reopen successfully. A downgrade is restoration, not an untested reverse conversion.

Implement and rehearse against representative legacy fixtures and disposable database copies. Do not automatically migrate or replace the owner's working database as part of a coding-session test run.

## 7. M1b minimal API/UI and acceptance

Add research portfolio create/list/detail and read-only instrument/listing identity APIs. Creation requires explicit mode/currency/funding and a durable creation key. Return decimal strings and stable validation/conflict codes. Strategy association may be absent until M5; `paper_started_at` refers to prospective strategy tracking, not merely an empty account's creation.

Move/preserve the terminal under an explicit demo route and add a minimal research portfolio selection/detail view. Show selected portfolio, mode, currency, loading/error/empty state and data origin. Obsolete requests must not overwrite the newly selected context. Demo SSE never becomes an authority for research valuation. No chart library replacement is required in M1.

M1b exit evidence extends A01/A02/A13/A14 and the applicable scope-isolation part of A16:

| Gate | Minimum evidence |
|---|---|
| Migration/recovery | Fresh database, representative legacy upgrade, conversion reconciliation, repeat startup, preserved empty watchlist and restored old binary/data |
| Exact accounting | The M0 `0.004 + 0.004` case yields `0.008`, cash `9999.20`, cost basis `0.80`, equity `10000.00`; repeated fees/buys/sells and fractional split residuals reconcile |
| Projection rebuild | Cash, quantity and total basis match ledger replay; full liquidation leaves no residual basis |
| Atomicity | Fail independently at operation result, execution, ledger, cash and position writes; no financial fragment commits |
| Duplicate delivery | Same intent/key returns the same result after restart and after quote movement; changed intent conflicts; concurrent same-key calls execute once |
| Concurrent different operations | Two real connections cannot overspend the same cash or sell the same holdings twice; bounded failures remain atomic and retryable |
| Creation | Same owner/creation key creates one funded account across retries/concurrency/restart |
| Scope | Two PAPER accounts plus one LEGACY_DEMO; wrong-mode, missing-scope and explicit research targets on legacy manual/chat paths are rejected |
| Chat recovery | Execution succeeds but assistant-text storage fails; retry creates no additional execution; clearing text preserves execution links |
| UI/API | Actual creation, selection and inspection flows; obsolete-response rejection; research still works without LLM and is unaffected by demo SSE |

Use independently calculated expected values. Passing these gates does not yet prove corporate-action availability timing or historical execution correctness; M2/M3 own those tests.

## 8. Finding disposition

| M0 finding | Planned stage |
|---|---|
| 01 YAML | M1a |
| 02 financial precision | M1b |
| 03 identity/isolation | M1b |
| 04 durable idempotency/concurrency | M1b |
| 05 migrations/seeding | M1b |
| 06 fabricated/stale executable quotes | M1b demo gate; M2/M3 history adapters |
| 07 historical-data gap | M1b identity; M2 data contract |
| 08 chat/result divergence | M1b legacy execution recovery |
| 09 model output boundaries | M1b validation and legacy scope; M5 research proposals/tools |
| 10 valuation disagreement | M1b context/authority; M3 research series |
| 11 demo charts/snapshot growth | M1b legacy range/index and retention decision; M3 dated research charts |
| 12 API typing/state | M1b incremental contracts/context; later research screens as built |
| 13 local exposure | M1a local boundary; remote design remains separate |
| 14 SQLite test isolation | M1a |
| 15 missing durability tests | M1a runnable harness; M1b meaningful financial gates |
| 16 build/E2E confidence | M1a deterministic inputs and existing suites; new lint/CI tooling separately scoped |
| 17 startup/backup/readiness | M1a startup/probe/config packaging; M1b backup/restore; M2 data freshness |

M1a reports every deferred High finding explicitly. Its report supplies the evidence needed to write the next M1b implementation prompt without guessing whether the baseline actually runs.
