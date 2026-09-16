# Implementation Plan: M5 Remediation and Verification

This plan remediates the findings in [M5-code-review.md](reports/M5-code-review.md) against [PROMPT-SIGNALFORGE-M5.md](PROMPT-SIGNALFORGE-M5.md) and [SIGNALFORGE-SPEC-v1.0.md](SIGNALFORGE-SPEC-v1.0.md). It completes M5 only. M6, brokerage integration, external data acquisition, and new dependencies remain out of scope.

## Preconditions and migration policy

1. Reopen `TASK-17` and leave its acceptance criteria unchecked until objective evidence proves them.
2. Record the exact pre-fix source identity: base commit/tag, dirty status, tracked patch hash, untracked-file manifest hash, OS, Java, Gradle, Node, npm, Angular, and Vitest versions.
3. Do not access or copy the owner's working database. Use only disposable, foreign-key-enabled SQLite databases.
4. Preserve V1 through V11 byte-for-byte. V12 is uncommitted and unreleased, so revise V12 in place and discard/recreate disposable databases that already contain its draft schema. If evidence shows that a non-disposable database has applied V12, stop and add V13 instead.
5. Add no dependency to `build.gradle` or `package.json`. Diagnose the production-build abort before changing build configuration.

## 1. Durable schema and state machines

Revise `V12__paper_tracking_and_proposals.sql` and `MigrationRunner` so the database enforces the M5 aggregate rather than relying on service convention.

### State models

- Proposal: `PROPOSED -> ACCEPTED | REJECTED | SUPERSEDED | BLOCKED`. Accepted, rejected, superseded, and blocked proposals are immutable. Execution outcome remains on the linked intent/result rather than rewriting an accepted proposal.
- Intent: `PENDING -> WAITING_FOR_OBSERVATION -> EXECUTED | FAILED | MISSED | CANCELLED`; allow `PENDING -> EXECUTED` when the required observation is already available. Terminal states are immutable.
- Receivable: `PENDING -> PAID | CANCELLED`. Paid/cancelled rows are immutable.
- Segment: `ACTIVE -> TERMINATED`; configuration is immutable after activation except approval mode and adopted-dataset pointer, whose changes have append-only history.
- Adoption: `ADOPTED | REJECTED_INCOMPATIBLE | REJECTED_CONFLICT`, immutable after insertion.

### Constraints and audit records

- Enforce one proposal per canonical portfolio/strategy cycle and state revision.
- Enforce one intent for a proposal/scheduled session and one execution result per intent/listing/order leg.
- Enforce stable corporate-action identity independently of dataset ID: `portfolio + source_namespace + listing + action_type + action_id`, with a stored payload hash. A changed payload for an already processed identity becomes a reconciliation conflict, not a second event.
- Guard update/delete on accepted or terminal proposals, proposal items and observations, intents and transitions, execution results, processed actions, receivables after terminal state, valuations, mode history, and adoption history.
- Prevent insertion of proposal children after the proposal becomes accepted or terminal.
- Enforce the single-active-segment invariant on INSERT and UPDATE.
- Add owner/portfolio/segment/proposal/intent consistency columns or composite foreign keys where SQLite supports them; retain service-level owner checks as defense in depth.
- Replace proposal `desired_units` with immutable target evidence. Persist target weights, ranks, reasons, cutoff observations, and optional cutoff price reference on the proposal. Persist requested/executed units and shortfall on execution results after opening observations resolve.
- Add `paper_mutation_requests` for activation, adoption, evaluation, acceptance/rejection, mode changes, and explicit processing. Store owner, action, idempotency key, canonical payload hash, status, resource/result reference, serialized response, and timestamps with `UNIQUE(owner_id, action, idempotency_key)`.

## 2. Shared strategy evaluation and opening allocator

Keep strategy formulas in the existing M4 components.

- Add a small paper adapter that reads the adopted snapshot through `BacktestDataReader`, builds continuous total-return inputs through `TotalReturnSignalIndexCalculator`, and calls `StrategyEvaluator.evaluateS1/evaluateS2/evaluateS3`.
- Map `EvaluatedSignal` and its normalized items into immutable proposal rows. Preserve strategy/version, universe, dataset/checksum, calendar/version, evaluation session, cutoff and observation times, ranks, exact scores/SMA/index values, target weights, reason codes, and portfolio revision.
- Use the same S2 warm-up, 12-1 definition, top-K ordering, listing-ID tie break, S3 SMA10/equality behavior, and churn suppression as M4. Do not duplicate these formulas in `PaperPortfolioService`.
- Evaluation has no financial effects. It supersedes only an unresolved proposal for the same canonical cycle/state and does so atomically.
- Acceptance freezes the proposal target and creates a durable intent for a strictly future eligible open. A repeated successful acceptance returns the original accepted response and schedule. Conflicting reuse returns 409.
- At or after an unaccepted proposal's suggested open, revalidate readiness and either schedule a later eligible open under the documented rule or supersede the proposal for a new explicit evaluation. Never fill at the expired open.
- When the scheduled raw-open observations are available, process earlier splits, entitlements, payments, and fills first. Then invoke one shared deterministic allocator against the actual opening portfolio.
- Execute sells before buys, net to one order per listing/batch, apply the segment's commission/spread/slippage policy, enforce whole-unit and partial-affordability rules, and persist zero-order/shortfall reasons.
- Store resolved requested units once before financial posting. Replay reads the stored resolution and cannot resize from changed cash or data.

## 3. Exact accounting, corporate actions, and valuations

Extend `AccountingCore` and `OperationService` with two narrow operations instead of a generic write API:

- `applySplit`: exact ratio quantity adjustment with unchanged total basis.
- `creditCashDistribution`: exact EUR cash increase linked to one receivable/action identity.

Each operation uses the existing operation/idempotency, ledger-entry, projection, and retry conventions. One transaction must atomically commit the operation, ledger entries, cash/position projections, processed-action identity, receivable state, execution provenance, intent transition, and valuation affected by that financial batch. An injected failure leaves none of those effects committed.

Corporate-action processing must:

- Discover eligible actions across adopted snapshots by stable source/listing/action identity, payload hash, economic date, and `available_at`, including delayed imports.
- Apply same-day splits before distribution entitlement.
- Determine entitlement from holdings established before the relevant event/trade boundary.
- Create one pending receivable on ex-date, preserving gross amount, withholding, net amount, record/effective/availability timestamps, dataset identity, and source identity.
- Pay only when the precise timestamp is available or the documented date-only/closed-date rule is satisfied. A sale does not remove an earned receivable.
- Create reinvestment intent only after the cash is observed and booked, for a future open after intent creation. S3 cash mode does not trigger reinvestment re-entry.
- Block a corrected snapshot whose payload conflicts with an applied action; a harmless correction may support future evaluations without changing prior proposals, fills, entitlements, or valuations.

Valuation processing must:

- Mark positions using the adopted snapshot's eligible observation, add pending receivables, and compute exact total equity.
- Compute cumulative return, high-water mark, and drawdown from the prospective segment only.
- Persist opening, event, and session-close observations without duplicate daily returns.
- Persist `COMPLETE`, `PARTIAL_STALE`, or `INSUFFICIENT_HISTORY`, last supported observation time, and missing requirements. Suppress metrics such as CAGR when history is insufficient.

## 4. Readiness, adoption, calendars, and time

Use the injected server `Clock` for every boundary decision.

- Require a `VALID` immutable dataset snapshot with checksum and owner-compatible universe/listing identities.
- Validate universe and benchmark coverage, EUR quote currency, source namespace, calendar ID/version, and consistent exchange zone.
- Determine completed and future sessions from the versioned calendar and exchange zone. Convert local opens with `ZoneId`/`ZonedDateTime`; never append `Z` to a local time.
- Require bars and action coverage for every needed listing/session, strategy-specific S1/S2/S3 warm-up, the latest expected completed session under an explicit availability/grace policy, and at least one eligible future execution session.
- Return explicit blocked reasons for invalid, stale, incomplete, incompatible, conflicting, or future-calendar-deficient data. Do not silently shorten the universe or guess weekdays/prices.
- Persist every accepted adoption and keep its checksum/history immutable. Persist rejected adoption audit in a separate `REQUIRES_NEW` component so the record survives rollback; test its owner scope and foreign-key validity.

## 5. Idempotency, serialization, and recovery

- Canonicalize validated payloads before side effects. Same-key/same-payload replay returns the stored result before reading changed data; same-key/different-payload returns 409.
- Keep portfolio creation on its existing durable request table and trades/corporate operations on `operations`. Reuse existing `chat_requests` for assistant requests. Use `paper_mutation_requests` for the remaining paper mutations.
- Protect business events with logical unique keys independently of transport idempotency keys.
- Serialize evaluation, acceptance, event processing, and AUTO_PAPER work per portfolio with a bounded local lock plus database CAS/unique constraints. Check affected-row counts.
- Make acceptance plus intent insertion atomic. Make each financial batch atomic. Multi-query detail/export reads use one consistent read transaction.
- `PENDING` intents whose open has passed remain `WAITING_FOR_OBSERVATION` when a durable pre-open intent exists but data arrived late. Later compatible observations may resolve exactly once at that market-effective open with separate observed/booked times.
- Mark `MISSED` only when no valid pre-open intent existed or a documented terminal incompatibility makes execution impossible. Do not use an arbitrary elapsed-time grace window to invalidate a valid durable intent.
- AUTO_PAPER is off by default. Enabling it records effective time and confirmed strategy/cost/data context. It creates future proposals/intents at eligible boundaries. Disabling prevents new automatic intents while preserving and disclosing already accepted intents. Recovery uses each portfolio's real owner and never invents a historical order.

## 6. Grounded research assistant

Implement bounded, typed, read-only tools for:

- Run summary, holdings, signals/input observations, executions, returns, and drawdowns.
- Comparison compatibility, members, and result metrics.
- Paper portfolio state, holdings, receivables, readiness, valuations, and pending intents.
- Proposal configuration, target items, observations, costs, reasons, schedule, and status.

For every tool:

- Resolve owner and parent/child scope server-side with bound SQL and bounded pagination.
- Return structured fact cards plus evidence IDs and observation times. Missing data returns `UNAVAILABLE`; never invent a value.
- Record holdout exposure before returning protected run/comparison evidence. If exposure persistence fails, do not reveal the evidence.
- Send only selected necessary facts/evidence to the configured LLM. Treat user text, dataset names, and notes as untrusted data, not authorization or tool instructions.
- Reuse durable chat request handling so retries do not duplicate work. LLM timeout, malformed output, or prompt injection cannot mutate money, accept proposals, evaluate, or change AUTO_PAPER.
- Preserve deterministic structured facts separately from prose and identify mocked-provider evidence accurately.

## 7. Owner-scoped API and Angular UI

Add typed, paged API contracts for segments/adoptions, proposals/items/observations, intents/transitions/executions, events/processed actions, receivables, valuations, holdings, mode history, assistant evidence, and bounded export. Validate enums, decimal strings, limits/offsets, owner scope, parent-child relationships, idempotency keys, and local-origin policy.

Update the Angular research feature to:

- Resolve `/research/portfolios/:id` through `ActivatedRoute` and load that exact portfolio even when it is outside the first list page. Preserve direct-link refresh and browser navigation.
- Use strict interfaces for every request/response; introduce no `any`.
- Cancel or suppress stale portfolio/proposal/valuation/assistant responses with `switchMap` or equivalent lifecycle-safe streams. Clear assistant evidence when context changes.
- Show mode/tracking start, strategy/universe/costs, adopted snapshot/checksum, latest supported session, readiness details, exact balances, receivables, holdings, pending intents, proposals and reasons, scheduled/market-effective/observed/booked times, execution/shortfall states, events, mode history, and prospective-only equity/drawdown chart.
- Render blocked, stale, missed, waiting-for-observation, incomplete, and unavailable states explicitly. Explain pending-intent disposition when disabling AUTO_PAPER.
- Provide bounded pagination and context-specific assistant selection for run, comparison, portfolio, and proposal. Keep paper performance separate from historical backtests.
- Download the audit archive through `HttpClient` as a blob so owner/request policy is preserved.

Investigate the production build exit 134 using the declared Node 24.21.0 toolchain and actual npm version. Fix demonstrated source/configuration issues without adding dependencies or merely raising budgets. Record the diagnostic and result.

## 8. Complete bounded audit export

Generate an owner-scoped consistent archive with explicit row limits or a streamed/chunked implementation. Include:

- `manifest.json`: source/build identity, export time, portfolio and segment configuration, costs, strategy/universe/calendar versions, and archive limits.
- Dataset adoption history.
- Proposals, items, observations, decisions, and acceptance/rejection/supersession metadata.
- Intents, transitions, execution results, and linked core operation/execution IDs.
- Receivables and processed corporate actions with source, effective, available, observed, and booked times.
- Prospective valuations, current cash, holdings, and receivable state.
- Mode history.

Reuse the established exact-decimal and RFC 4180 conventions. Preserve negative numeric decimals as numbers and protect only untrusted text from spreadsheet formulas. Export is an audit artifact, not a broker-order import.

## 9. Focused verification

Use a test-controlled `Clock`, real services, independent connections for race tests, and disposable foreign-key-enabled SQLite. Keep focused unit, controller, data, component, and integration tests rather than one monolithic lifecycle test.

### Required automated scenarios

1. Create/activate two same-owner PAPER portfolios; prove one funding each, isolation, and no mutation of LEGACY_DEMO/BACKTEST.
2. Timely acceptance before open; missing open waits; later import resolves once with distinct market-effective, observed, and booked times. Repeat acceptance returns the original schedule.
3. Late first acceptance cannot use the expired open; select a strictly future eligible session or supersede. Same-open boundary is deterministic.
4. EUR 1000, zero spread/slippage, EUR 1 fee: buy 9 at raw open 100, cash 99, basis 901. Re-import/restart leaves one fill. Separately retain the valid 1018 reference fixture.
5. Default nonzero costs, partial affordability, sell-before-buy, net orders, and S1/S2/S3 decisions match the shared M4 core for identical inputs, including S3 cash/churn behavior.
6. Invalid/stale/missing calendar, bar, action, warm-up, future-session, owner, and listing data block. Corrected snapshots cannot repeat an action or reprice completed evidence.
7. Split-before-entitlement, ex-date receivable, precise/date-only/closed-date payment, delayed observation, sale with unpaid receivable, and future reinvestment intent are exact and repeat-safe.
8. Eight coordinated duplicate acceptance and processing calls plus conflicting proposals produce one intent/fill/effect. Inject failures at ledger/projection/provenance/status steps and prove full rollback/retry.
9. Restart resolves a previously accepted intent once; restart without intent records a missed opportunity without a historical order. Test AUTO_PAPER opt-in, disable/re-enable, pending-intent disclosure, and same-open boundaries.
10. Typed assistant tools reject foreign resources and malicious IDs/instructions; holdout reads persist exposure; unavailable/LLM failure/malformed output cannot mutate financial or approval state.
11. Parse the actual export and verify all entries, exact positive/negative decimals, safe text escaping, owner scope, bounds, manifest identity, prospective-only metrics, and unchanged source rows. Test scoped pagination, missing UI values, stale-response suppression, and context changes.
12. Upgrade populated M4/V11 data to the final schema with foreign keys enabled; preserve catalog, universes, experiments/exposures, signals/items, historical results, and legacy/manual/demo records. Verify migration checksums and repeat startup.

### Required commands

Run and record actual versions, counts, duration, and failures:

```bash
cd backend
./gradlew spotlessApply
./gradlew clean test

cd ../frontend
npm test -- --watch=false
npm run build -- --configuration production
```

No frontend lint script currently exists. Do not claim lint verification unless a project command is present and executed.

## 10. Native browser walkthrough and closeout

Run the application against a disposable seeded database and perform a native browser walkthrough that covers:

- Create and activate a PAPER portfolio through the form.
- Adopt ready data and inspect invalid/stale/blocked data states.
- Evaluate S1/S2/S3, inspect proposal evidence/costs, accept and reject proposals.
- Inspect pending, waiting, executed, failed/blocked, and missed states with exact times.
- Enable/disable AUTO_PAPER and verify pending-intent disclosure.
- Inspect holdings, receivables, actions, valuations, prospective chart, and context-grounded explanations.
- Refresh a deep link to a portfolio outside the first list page and use back/forward navigation.
- Download the actual ZIP through the UI, record its checksum, and inspect every entry and representative exact values.
- Complete inherited M4 native run-form and holdout-registry checks.

Update `planning/docs/paper-tracking.md`, `planning/reports/walkthrough-M5.md`, and `planning/reports/research-M5.md` from observed behavior and commands. The research report must record final source/tool identity and a PASS/FAIL/NOT VERIFIED gate table. Keep Docker and an optional real-provider call explicitly NOT VERIFIED when unavailable. Do not use synthetic or mocked evidence for a blanket production-ready claim.

Read the Backlog finalization guide, update `TASK-17` through the CLI, and check only acceptance criteria supported by objective evidence. Stop after M5; do not start M6.
