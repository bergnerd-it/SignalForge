# Coding-agent prompt — SignalForge M1b

Implement **M1b — portfolio identity and exact accounting foundation** in the existing SignalForge repository. Continue the FinAlly fork using Spring Boot, Java, JDBC, SQLite and Angular. Deliver implemented code, meaningful verification and `planning/reports/research-M1b.md`. Do not stop at a design proposal and do not start M2.

## 1. Read the baseline and preserve current work

Read applicable `AGENTS.md` and task-tracking instructions, then:

- `planning/FINALLY-RESEARCH-SPEC-v0.1.md`
- `planning/SIGNALFORGE-SPEC-v0.2-M0-ADDENDUM.md`
- `planning/reports/research-M0.md`
- `planning/reports/research-M1a.md`
- `planning/reports/research-M1a-closeout.md`
- Relevant architecture/deployment documentation and the actual code affected by this milestone.

Follow the existing task tracker. Preserve unrelated work and all M1a/closeout changes. Do not reset to an earlier audited commit. The closeout reports M1a commit `2c781c4b0c756c37e222020017ff5f789548fc48` plus an uncommitted closeout implementation manifest hash `adf3502cf8ac9543b9ada10595f51f3641b87ebf5cf739e8356bc83ee07e01d7`. Verify current state; those identifiers do not prove that the present checkout is unchanged.

Use the current canonical v0.1, the v0.2 addendum and later explicit user decisions as the effective baseline. The addendum hash reported by closeout is `208970ccae5c9f6c429e692c40ab2136edb02573f7a0861e951d9a5346b52fe4`. The unavailable chat-reference spec is not a prerequisite for this bounded milestone. Record the remaining provenance discrepancy without replacing the canonical spec or fetching the same missing reference repeatedly.

### Sequencing adjustment for this handoff

M1a native checks passed, while Docker Hub TLS timeouts prevented image construction. **This task permits M1b development and native verification before those deployment gates are closed.** This updates the earlier proposed sequence; it does not mark M1a container/E2E checks passed or waive their eventual acceptance.

Carry forward the unresolved clean image build, packaged UI, runtime port/origin behavior and container restart tests. Attempt them when infrastructure is available; one bounded availability check is enough when the same registry failure persists. Do not spend this milestone repeatedly retrying image downloads, disabling TLS verification or changing host/network security settings.

M1b financial correctness, migration and native restore checks are required. They cannot be deferred merely because Docker is unavailable. Keep deployment verification status separate from implementation and financial test status.

## 2. Scope and implementation order

Working defaults: local single user, one running application instance, SQLite, EUR PAPER accounts, optional LLM, no real broker. Preserve actual Java 21, Node 24.21.0 and aligned Playwright 1.63.0 tooling from closeout unless current repository evidence says otherwise. Do not upgrade application frameworks or introduce a database server, JPA, a second migration engine or new infrastructure.

Implement these steps in order within M1b:

1. Establish a reproducible native pre-migration checkpoint and disposable legacy fixtures.
2. Implement instrument/listing and portfolio identity, one migration mechanism, decimal domain types and ledger/projection schema.
3. Implement the pure accounting core and durable transactional operation service.
4. Migrate legacy state with reconciliation; route legacy trading through the new boundary and repair executable quote/chat behavior.
5. Add the minimal research APIs/UI and regression/durability tests.
6. Verify recovery and report actual results and remaining deployment gates.

Intermediate steps are implementation checkpoints, not separate completed milestones. Continue through the full scoped result. Do not scaffold empty history, strategy, backtest or proposal subsystems.

## 3. Disposable migration development and recovery

Before application edits, preserve a runnable native pre-migration JAR, required safe configuration and source identity in a task-owned location. Record artifact checksums and Java version. Account for the closeout diff, rather than identifying the old binary solely by a HEAD that excludes it. Do not commit generated binaries or secrets.

Generate representative legacy databases from the actual legacy schema and synthetic records, including multiple owners, trades, fractional positions, snapshots, chat, a non-empty watchlist and an intentionally empty watchlist. Keep an untouched fixture copy and manifest. Do not open, migrate, overwrite, seed or delete the owner's actual database/volume during this coding session.

Implement one fixed ordered JDBC migration runner and `schema_migrations`, using existing Spring JDBC/transaction facilities. Retire competing startup initializer writes. Requirements:

- Distinguish a genuinely empty database, the recognized legacy schema and supported versioned schemas. Fail with actionable diagnostics on partial, unknown or newer schemas.
- Record version, description, checksum, application version and applied time atomically with schema/data changes. Include Java conversion logic in a reproducible migration checksum. Detect changed historical migrations.
- Support SQL resources containing triggers through correct statement handling; do not split blindly at every semicolon.
- Gate scheduled/background mutation and application readiness until migration/reconciliation finishes. A failure must not expose a partially migrated application.
- Implement a tested consistent backup procedure before any existing-schema conversion. Stop writers and preserve required SQLite journal state, using a supported backup/checkpoint procedure. Do not copy just an actively changing main database file. Refuse unsafe overwrite of an existing backup.
- Prove failed conversion rolls back, subsequent retry behaves deterministically, and repeated startup does not duplicate funding, holdings, migration entries or watchlist seeds.
- Restore into a separate disposable location and verify schema/data equivalence before opening it with the preserved old binary. Then prove old-binary startup and compare stable fields, documenting its original snapshot/empty-watchlist seeding behavior. The old initializer's known effects must not be confused with backup corruption or repaired retrospectively in the checkpoint binary. Verify preservation of intentionally empty watchlists separately under the new migrated application.

Document native backup/migration/restore commands for later operator use. Running the new code against the owner's existing database is outside this task's test scope.

### Legacy conversion and reconciliation

Preserve raw original records, IDs and timestamps in an immutable archive or backup plus mappings. Isolate the one-time REAL conversion; use a documented finite-value decimal conversion and field policy. Report original/converted values and differences. Reject invalid/nonfinite data with diagnostics. Lost precision cannot be recovered.

Create one LEGACY_DEMO portfolio per existing owner. Record the inferred legacy USD currency and unresolved listing identities. Do not relabel old balances as EUR or invent venues/ISINs/instrument types.

Reconcile current cash, units and total acquisition cost against preserved history. When history is incomplete or inconsistent, preserve it and use explicit migration opening/adjustment entries to reproduce the converted current state. Do not replay historical trades on top of an opening already incorporating them. Do not silently “repair” the M0 fractional bug by guessing what an owner intended to trade. Old snapshots/history remain legacy observations, not validated research returns.

## 4. Identity, persistence and exact accounting

Use the M0 table plan with the v0.2 refinements. Include portfolios, instruments/listings/aliases, durable operations, ledger entries, execution records, cash and position projections, legacy history/valuations and scoped creation-request records as needed.

- Every financial record carries explicit portfolio identity; executions and ledger entries reference an operation belonging to that same portfolio through database-enforced constraints.
- Enforce non-null keys, enums, foreign keys on every connection, and required uniqueness. Use stable listing IDs and unambiguous namespaced aliases/effective intervals. Unresolved legacy listings are forbidden in research execution.
- PAPER is EUR, long-only and separately funded. BACKTEST remains reserved and cannot be created through public APIs yet. No GET creates a funded account.
- Research decimals travel as strings; SQLite stores canonical decimal TEXT with string JDBC reads/writes. Authoritative calculations use `BigDecimal`, not floating-point SQL or `double` intermediates. Preserve legitimate display-only approximate numbers separately.
- Version decimal grammar, bounds, normalization, cash rounding and price/quantity precision. EUR/USD cash bookings use two-decimal HALF_EVEN, execution prices support eight decimals, and nonterminating division uses DECIMAL128. Do not round quantities to cents. Reject unsupported quantity precision rather than silently losing units.
- Store total acquisition cost, deriving average cost for display. Buy fees increase basis; sale fees reduce proceeds. Partial sales remove proportional basis; full liquidation removes all remaining basis. Explain and reconcile rounding residuals.
- Splits alter quantity/per-unit basis while preserving total basis. Support fractional residuals in the core. Whole-unit strategy order sizing is future M3 work.
- Funding is an operation/ledger movement; `initial_cash` metadata is not added again during replay. Rebuilding cash, units and total basis from the ledger must reproduce projections.
- Ledger entries have no update/delete application path and database-level rejection of UPDATE/DELETE. Corrections append entries. This is an application invariant, not protection against an administrator editing the database file.

Keep the core a small pure domain component taking explicit decimal inputs and business time. It must not read the wall clock, HTTP, current-quote cache, Spring controller or LLM. Adapters resolve inputs before transactional execution.

## 5. Atomic operations and durable idempotency

For an existing account, enforce `(portfolio_id, operation_kind, idempotency_key)` uniqueness with a canonical validated-intent hash. For portfolio creation, use an owner-scoped creation key because the target portfolio does not exist yet. Create the request/result, account, initial funding ledger and projections in one transaction.

Canonical intent includes financially relevant request fields and scope. It excludes JSON key order, request-arrival timestamps and retry metadata. Normalize equivalent decimal representations. A matching retry returns the stored result/IDs; conflicting reuse returns HTTP 409 and a stable error code.

Check completed operations before fetching another quote or invoking the model. A trade retry after the quote changes returns the original fill. The stored fill is a result, not a new mutable field to hash into the same client's retry intent. Recheck durable uniqueness inside the transaction; a preliminary lookup is only an optimization.

Persist execution, ledger, cash/position projections, revision and success result in one transaction. Roll back every financial fragment on failure. Define failure/retry semantics explicitly; do not leave a durable “success” reservation without the corresponding committed accounting. Notify after commit.

For SQLite, coordinate writers through actual commit/rollback completion using a top-level `TransactionTemplate` boundary. Guard against an outer transaction delaying that commit. Acquire database write capability before reading balances to modify; do not nest manual BEGIN inside a managed transaction. Use bounded busy handling, and retry the whole operation with the same key. Read multi-query portfolio views within a consistent read transaction. No remote I/O inside financial transactions.

Require stable idempotency keys for covered mutations: research account creation, legacy manual trades and legacy chat requests. Update the built-in clients to generate a key once per user intent and reuse it for retries; a deliberate new action gets a new key. Document this legacy request-contract extension and use clear errors for missing keys. Preserve existing response fields where practical; never promise retry safety to callers who supply no request identity.

## 6. Legacy trading, quotes and chat

Route manual and chat-driven demo trades through the new operation service. Legacy adapters select their designated LEGACY_DEMO target server-side. Explicit PAPER/research scope on a legacy request must be rejected, not ignored or redirected. Server-side typed validation also applies to model-generated instructions, independent of controller annotations.

Missing provider data must be unavailable, not a fabricated `100.0`. Carry source observation time separately from fetch time, source identity and executable/freshness status. Define a conservative, testable demo policy; reject missing/stale/unverified provider fills. Synthetic simulator fills are allowed only for the explicit legacy demo. Do not implement exchange-calendar history or select a new provider in M1b.

Persist chat request identity, the validated action set and execution-result references outside deletable conversation text. Store the action set before any financial execution. Retry after assistant-text persistence failure must resume/return those same actions, not generate and execute a new model plan. Handle partial action completion with stable per-action keys/results. An in-progress request must not be executed concurrently twice; define restart recovery for inference/action states without holding a database transaction over model inference. Clearing chat retains execution evidence.

Bound provider/LLM waits, return sanitized errors, and validate malformed/null model actions without secondary exception-handler failures. Do not forward research accounts to the legacy assistant.

## 7. Minimal research API and frontend

Implement under `/api/research`:

- POST `/portfolios`: explicit PAPER mode, EUR currency, name, starting cash and owner-scoped idempotency key; the local owner comes from server context.
- GET `/portfolios` and `/portfolios/{id}`: scoped identity, cash/positions and typed state. Unstarted strategy tracking is explicit; no fabricated return series.
- Read-only instrument/listing identity lookup using stable IDs, provenance and unresolved status.

No public arbitrary ledger-write, synthetic-fill or PAPER market-order endpoint. Integration tests may use trusted internal deterministic fills/fixtures to verify accounting and two PAPER accounts holding the same listing. This is not a product trading feature.

Preserve the terminal under `/demo` and add minimal research account creation, selection and detail views. Show mode/currency/context, precise server values, loading/error/empty states, and an honest “valuation unavailable” state when market history is absent. Do not equate purchase cost with current market value or value PAPER accounts from demo SSE.

Use typed decimal-string research contracts. Cancel/discard obsolete responses on context switches. Use one consistent authority for the legacy live display so header/table values do not follow incompatible state paths; keep that approximation distinct from research. Add bounded legacy history access/indexes and document retention without automatically deleting the owner's old snapshots. No chart-library replacement or broad UI redesign.

## 8. Required independent fixtures and tests

Use real temporary file-backed SQLite, application transactions and production schema/migration paths for durability tests. Keep foreign-key and cleanup behavior established by M1a. Use synchronizing barriers/latches and genuinely independent connections for concurrency evidence, including a test that does not rely on a shared Java monitor alone. A sequential test is not concurrency proof.

At minimum, demonstrate:

| Area | Required evidence |
|---|---|
| Migration/recovery | Fresh install; multi-owner legacy upgrade; malformed/unknown schema rejection; changed checksum; injected migration failure; reconciliation; repeat startup; empty watchlist preserved; old-binary restore |
| M0 arithmetic regression | Start cash 10000; buy 0.004 twice at 100, zero fees: units 0.008, cash 9999.20, basis 0.80, equity 10000.00 under a fixed test valuation |
| Fees/basis/split | The independently specified sequence below, plus full liquidation after repeated proportional-basis rounding |
| Replay | Rebuild cash/units/basis exactly from ledger, including funding and migration openings without double counting |
| Atomicity | Inject failures at operation-result, execution, ledger, cash and position writes; all financial state rolls back |
| Idempotency | Same-key sequential/concurrent/restart retries apply once; quote movement does not change prior result; changed payload conflicts; independent accounts have independent scopes |
| Creation | Same owner/key creates exactly one funded account across retry, concurrency and restart |
| Competing operations | Distinct keys competing for insufficient cash/holdings cannot overspend/oversell; bounded busy failures leave consistent state |
| Scope | Two PAPER accounts and LEGACY_DEMO; same listing held independently; missing/wrong scope and legacy requests targeting research are rejected |
| Quotes | Missing, stale and unverified provider quotes cannot execute; explicitly synthetic legacy fills remain usable |
| Chat recovery | Financial success followed by assistant-text failure; retry creates no duplicate; malformed actions fail; history deletion preserves durable evidence |
| API/UI | Account creation/detail, decimal contract, selected-context race, no research SSE contamination, preserved legacy manual/chat flows and LLM-independent research |

Independent accounting sequence (all monetary values in one test currency):

| Step | Cash after step | Units | Total basis | Realized gain for step |
|---|---:|---:|---:|---:|
| Initial funding | 1000.00 | 0 | 0.00 | — |
| Buy 2 at 100, fee 2 | 798.00 | 2 | 202.00 | — |
| Buy 2 at 120, fee 2 | 556.00 | 4 | 444.00 | — |
| Sell 1 at 130, fee 1 | 685.00 | 3 | 333.00 | 18.00 |
| 3-for-2 split | 685.00 | 4.5 | 333.00 | 0.00 |
| Sell all 4.5 at 90, fee 1 | 1089.00 | 0 | 0.00 | 71.00 |

Total realized gain is 89.00, matching final minus initial cash. This is a supplied accounting fixture, not a historical market scenario. Corporate-action availability/ex-date/payment timing remains M2/M3 work.

## 9. Verification, documents and completion

Run the full backend and frontend suites and production builds using the selected toolchains. Verify native application startup against a disposable migrated database, then restart and inspect persistent state. Run real browser flows with the available supported harness; if browser/container acquisition is blocked, retain executable tests and label those executions NOT VERIFIED. Do not describe TestBed tests or Playwright discovery as an executed browser flow.

Retain the inherited closeout container/restart checks and adapt them to intentional API changes. Attempt container verification when registry access permits, with unique project/volume/port isolation. Do not remove local origin enforcement to pass tests. All required accounting, migration and native restore tests must actually run to claim that portion of M1b complete.

Update the relevant architecture/API/deployment documentation and add a concise accounting/migration design record with schema, numeric policy, transaction/retry semantics, recovery procedure and legacy assumptions. Keep the design small and implementation-specific; do not introduce a new framework to satisfy documentation.

Write `planning/reports/research-M1b.md` containing:

1. Starting/final HEAD and tested diff/artifact identity; preserve a reproducible pre-migration checkpoint.
2. Effective spec identity and the explicit M1a deployment-gate deferral used by this handoff.
3. Changes and schema/API decisions, with paths and current line references.
4. Migration reconciliation, raw-record preservation and disposable restore evidence.
5. Actual test commands/counts and PASS/FAIL/NOT VERIFIED for each required category; link concise evidence.
6. M0 findings addressed/remaining, separating observed defects from untested risks.
7. Remaining browser/container gates and exact safe rerun commands; no claim of a fully validated deployment while those remain open.
8. A short local usage walkthrough: create two research accounts, select them, inspect balances and use the isolated demo.
9. Readiness for M2 and any concrete remaining blocker. Profitability or investment conclusions are not part of this milestone.

Complete all feasible implementation and validation, preserve the reports, and stop before historical imports/backtests. Do not deploy remotely, connect a broker, buy data, or run migration tests on the owner's working database.
