# SignalForge M3 — Backtest Engine and Buy-and-Hold Baseline

Implement milestone M3 only in the existing SignalForge repository. Deliver a working, reproducible historical backtest with S1 buy-and-hold, a separately accounted benchmark, core analytics, a basic browser workflow, and result export. Continue through implementation and the required verification; do not stop at a plan. Do not implement M4 strategies or prospective paper trading.

## 1. Establish the source baseline

Read, in this order:

- Applicable `AGENTS.md` and the repository's task tracking instructions.
- `planning/FINALLY-RESEARCH-SPEC-v0.1.md` and `planning/SIGNALFORGE-SPEC-v0.2-M0-ADDENDUM.md`.
- `planning/PROMPT-SIGNALFORGE-M2.md` and `planning/reports/research-M2.md`.
- Relevant accounting, migration, historical import/history, API and frontend code and tests.

The repository specification plus addendum and subsequent explicit user decisions are authoritative. This prompt supplies the M3 scope and operational details. If an old reference is absent, locate its current equivalent; do not reopen the historical spec-hash investigation. Resolve ordinary implementation choices autonomously and document them. A substantive conflict that prevents a correct implementation must be reported explicitly.

The M2 report names `e072bdf3478d851f3d3eb8ce5bd152ad535b609b` plus uncommitted closeout changes; do not assume that commit contains all fixes. Inspect the actual working tree, preserve all tracked and untracked work, and establish a complete M2 checkpoint before M3 edits. Make a narrowly scoped local checkpoint commit if repository rules permit; otherwise retain an exact reproducible manifest and explain the restriction. Do not push or publish.

Resolve the M2 report's Angular version discrepancy against package manifests, lockfile and installed dependencies. Previous documentation described Angular 22, while the closeout says 18.2.14. Record actual versions and correct erroneous documentation; do not upgrade or downgrade frameworks to make the report true.

Keep Java 21, Spring MVC, JdbcTemplate, SQLite and the existing Angular stack. No JPA conversion, framework rewrite, Python service, provider purchase or broker connection. All verification must use disposable databases. Never open, copy, migrate or mutate the owner's working database for testing.

The Docker/container gate remains explicitly deferred. Finish feasible native implementation and verification without reopening unrelated M1/M2 work.

## 2. Scope and architecture

Implement:

1. Immutable run configuration and dataset references.
2. A deterministic historical clock and event engine.
3. `ETF_BUY_HOLD_V1` (S1), using exactly one explicitly selected listing.
4. An independently funded and accounted S1 benchmark using an explicitly selected listing.
5. Modeled execution costs, whole-unit buy sizing, split and distribution accounting.
6. Durable asynchronous run lifecycle, cancellation and read-only completed results.
7. Core analytics, basic equity/drawdown views, executions/events, and export.

Do not implement momentum, trend filters, rolling five-year experiments, holdout workflows, multi-run comparison screens, optimization, automated paper execution, taxes, FX conversion, derivatives or AI analysis. These remain later milestones. S1 does not require a momentum total-return signal index; do not build unused M4 machinery.

Reuse the pure exact accounting core where suitable, with a backtest-specific state/storage adapter. Keep strategy decisions, event scheduling, market observations, executions, accounting and analytics distinct enough to test their boundaries. Do not copy a second incompatible set of ledger arithmetic into the engine.

Historical calculations must not call live providers, quote caches, simulator streams or the LLM. Backtest state is isolated from PAPER and LEGACY_DEMO accounts. A BACKTEST account, if the existing model is reused, can only be created through the validated run lifecycle and must reject ordinary manual/chat mutations. Alternatively use run-scoped accounting tables with equivalent invariants; document the choice.

## 3. Frozen configuration and eligibility

Persist at least:

- Strategy identifier/version, candidate listing ID and benchmark listing ID.
- Explicit immutable dataset ID(s), checksums and parser/schema identity; never an implicit latest dataset.
- Initial evaluation cutoff, requested and effective reporting interval, calendar and timezone conventions.
- Initial EUR cash, cost parameters, decimal/accounting policy and execution-model version.
- Engine version, source commit/build identity and dirty-worktree indication when applicable.
- Source/availability assumptions and classifications such as SYNTHETIC, REVISED_HISTORY and RETROSPECTIVE_UNIVERSE.

Default to one dataset containing both listings; supporting cross-dataset combinations is unnecessary in M3. Require a common specified trading calendar and EUR quote/distribution currency. Reject unsupported configurations rather than silently converting or substituting listings. Synthetic ETF fixtures are allowed as explicitly labeled software tests; do not invent real ISINs or certify global/unleveraged ETF eligibility from a ticker alone. Record any user-declared classification when the dataset lacks sufficient instrument metadata.

Follow the spec's initial month-end evaluation convention: the user selects a completed month-end session and an explicit cutoff at or after the observations needed for evaluation become available. Initial allocation is executed at the first eligible later session open strictly after that cutoff. Fund candidate and benchmark immediately before this same first execution event. Warm-up/evaluation history is excluded from reported returns. Do not silently shorten requested data coverage. Show requested versus effective dates and explain closed-day boundaries.

Require the configured end session and all necessary bars/actions/calendar coverage for both listings. Do not backfill before inception or invent a delisting exit. Additional contributions/withdrawals during a historical run are unsupported. Cash interest is zero. No forced final liquidation; final equity is marked to market and excludes hypothetical liquidation costs.

Validate structural eligibility and coverage before expensive work, while retaining durable actionable failure results. A preflight check may reject a run based on missing data, but it must not expose future prices/actions to strategy or order-sizing code.

## 4. Historical time and information boundaries

Use explicit ordered events with deterministic tie-breaking. Separate effective market time, source availability, dataset import time and job wall-clock time.

At minimum distinguish:

- Session-start splits and ex-date entitlement events.
- Distribution payments at a precise supported payment instant or the documented date-only boundary.
- Scheduled opening executions.
- Closing marks, equity snapshots and completed-data availability for decisions.

At an opening fill, the execution adapter may read the raw opening price only. It must not expose that day's high, low or close to the strategy or sizing code. A daily row's `available_at` describes availability of the complete bar, usually after close; it is not evidence that all its fields were known at the open. Use a narrow opening-observation adapter with an explicit historical auction/open-price assumption. Record that assumption, and do not weaken M2's full-bar as-of filter to obtain an opening price. Close-based decisions must wait for the complete required observations. Retrospective valuation timestamps and observation-availability timestamps must remain distinguishable.

For splits/distributions, use the action information required at the effective event only when it is available. If required terms are first available after the entitlement/split event, fail M3's strict model with an actionable unsupported-availability error rather than reading ahead or inventing an earlier announcement. Do not silently discard an action because it lies outside a reporting API page or cutoff.

On an ex-date, eligibility comes from holdings before that session's trading, adjusted for same-day splits. Apply same-day split ratios before interpreting the supplied per-post-split-unit distribution amount. Multiple compatible events on one date must have a documented stable order.

Create a receivable at entitlement time. It contributes to equity but cannot finance purchases. On payment, move the same booked amount from receivables to cash without recognizing income twice. A payment date with no time becomes spendable conservatively after that date's session close; on a declared closed date use a documented end-of-calendar-day boundary in the selected calendar timezone. Never invent an opening-time payment. A precise timestamp is respected; if cash arrives exactly at an opening boundary, use the next strictly later open for reinvestment. Payments before the close should affect that session's cash snapshot; date-only payment after the close may have a separate event snapshot with unchanged equity.

Unpaid entitlements at run end remain receivables, with known payment dates beyond the reporting window retained. If an action lacks timing necessary for M3 accounting, reject explicitly; do not infer payment from ex-date. A later split must not multiply a previously established cash entitlement.

All business ordering and financial results must depend on frozen inputs, not hash-map iteration, thread scheduling, wall-clock timestamps or random IDs. Cancellation checks occur at defined event/session boundaries.

## 5. S1, execution costs and exact accounting

S1 targets 100% in its one listing at initial evaluation, buys at the next eligible open, and has no later strategic sale or scheduled rebalance. Paid distributions trigger a reinvestment attempt at the first eligible subsequent open. If cash cannot afford one whole unit plus costs, keep it as cash without a fee. Pool cash made available before a scheduled reinvestment open into one order per listing/open; do not duplicate fills when several payments trigger the same event. Retain pending attempts outside the run window as unexecuted intent.

Use the specification's cost and sizing semantics:

- Default fixed commission: EUR 1.00 per nonzero fill.
- Default full spread: 10 basis points; half is applied per side.
- Default additional adverse slippage: 5 basis points per side.
- Buy price = raw open × (1 + fullSpread/2 + slippage).
- Sell price = raw open × (1 - fullSpread/2 - slippage), where relevant to the reusable adapter; S1 itself does not sell.
- Fees are separate booked costs; spread/slippage are embedded in the fill and are not deducted again.
- Target weights become whole-unit buy quantities at the opening event using available opening references and the spec's deterministic affordability allocator. Receivables are excluded from spendable cash. Never borrow or round an unaffordable order up.
- Preserve requested/executed quantities, raw open, effective fill, commission, spread/slippage estimates, and skipped-order reasons.

Implement the sizing behavior required for S1 without inventing a portfolio rebalancing product. Use the same model for candidate and benchmark. Explain the approximation of sizing at the opening reference: it is a historical simulation convention, not a claim that a real submitted order knows its auction fill in advance.

Use BigDecimal and the existing versioned precision policy for authoritative arithmetic; decimal strings at JDBC/REST/export boundaries; no SQL floating-point sums. Calculate affordability using the same rounded amounts that will actually be booked. Keep original exact source prices separate from modeled/rounded fill amounts. Cash uses the established two-decimal HALF_EVEN policy. Preserve split-created fractional units and total cost basis. Reject unsupported split precision explicitly instead of silently losing units; do not invent cash-in-lieu.

Record funding exactly once, operations, fills, distributions/receivables, cash, units, total acquisition cost, realized and unrealized gains. Replaying ordered run events must reproduce stored balances and final equity. Commission treatment must agree with the existing accounting policy and must not be charged twice through cash and basis adjustments.

## 6. Storage, jobs and durability

Add follow-on migrations after the actual current version (expected V3), using the existing migration mechanism. Preserve all historical migration files/checksums and M1b balances. The project previously bound migration digests to compiled accounting/converter dependencies: do not change a dependency in a way that retroactively redefines an applied digest. Use an isolated new adapter/version where needed; never accept arbitrary checksum changes to get tests green.

Use run-scoped immutable configurations/results and correct foreign keys. The same owner may have multiple independent historical runs. Do not mutate imported datasets, PAPER balances or demo history.

POST creation requires a durable owner-scoped idempotency key with a canonical hash of financially relevant configuration. Same key/same intent returns the original run; conflicting reuse returns 409. A new key with identical configuration may create a separate reproducibility run. Do not silently reuse an obsolete engine version. Persist normalized configuration before work starts.

States: QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED, INTERRUPTED. Use one active calculation worker and a bounded queue, with explicit overload behavior. Keep queued requests durable and recoverable. Reuse suitable M2 infrastructure only where its semantics fit; avoid collision with the existing import job API or IDs.

Calculate outside long database write transactions using bounded data access. M2 history APIs are paginated: the engine must consume complete required ranges through a dedicated repository/reader or iterate all pages; the first default page is never a complete dataset by assumption.

Publish complete results and COMPLETED status atomically. If staging result rows is necessary, keep them inaccessible as successful results until publication. A failure or cancellation leaves no visible completed financial result or partially updated account. Preserve status and diagnostics. Resolve cancellation versus completion with a durable state transition so neither can overwrite the other after the fact. Startup marks unfinished jobs INTERRUPTED; a deliberate retry uses documented semantics and cannot double-fund the previous run.

Completed runs are read-only. Ordinary API mutations cannot rewrite run configuration, events, valuations or results. Define practical input/range/result/export limits and reject unsupported workloads clearly. Do not build a general distributed job framework.

## 7. Core analytics and benchmark

Calculate candidate and benchmark independently with matching funding, effective dates, calendar and cost assumptions. If both use the same listing, their deterministic financial results should match. Do not substitute a normalized price line for an accounted benchmark with fees and distributions.

Include:

- Initial and final equity; ending cash, receivables, holdings and cost basis.
- Cumulative net return and benchmark return/difference.
- Daily equity and drawdown; maximum drawdown with peak/trough dates, recovery date or ongoing status, and durations.
- Annual/yearly return table with partial calendar years visibly labeled.
- CAGR per the spec using elapsed calendar days; suppress for periods shorter than a year.
- Sample daily-return volatility with disclosed 252-session annualization; return unavailable when observations are insufficient.
- Fill count, commissions, separately estimated spread/slippage costs, exposure/cash weights, and realized/unrealized gains.
- Turnover with an explicit formula and reporting-period convention.

Include initial funded equity in the series so initial transaction costs are visible in return and drawdown. Equity = cash + market value + outstanding receivables; do not count initial funding or distributions twice. Define the valuation grid and day-count convention. An event after the close must not create an extra trading-day return observation. End-period valuation must reconcile to final holdings, cash and receivables.

Authoritative cash/units/returns should retain suitable decimal precision. Transcendental/statistical presentation calculations may use a documented numerical method; they must not feed back into accounting. Avoid NaN/Infinity and fabricated zero metrics. Synthetic labels and revised-history/universe assumptions remain visible in summaries, charts and exports. Do not add personal taxes, deduct ETF TER again from observed prices, or imply measured future profitability.

## 8. APIs, browser flow and export

Implement within `/api/research`, adapting to current conventions:

- POST/GET `/backtests`.
- GET `/backtests/{id}` for status, frozen configuration and completed summary.
- POST `/backtests/{id}/cancel` with repeat-safe semantics.
- Bounded, deterministically ordered resources under the run for equity, events/signals, executions and holdings as needed.
- GET `/backtests/{id}/export` for a completed result ZIP.

Keep existing import/job routes compatible. Enforce owner/run scope, stable errors and existing mutation-origin protections. Pending, failed, cancelled and interrupted runs must not return misleading completed summaries or exports. Never accept a public arbitrary ledger or synthetic fill request.

Provide `/research/backtests` and `/research/backtests/:id` using existing UI conventions. Users can select the dataset, candidate/benchmark listings, month-end evaluation/end dates, initial capital and cost assumptions; inspect validation; start a run; observe progress; cancel; and open completed results.

Show candidate/benchmark equity and drawdown with dated tooltips, assumptions, data classification, holdings/receivables, fills and skipped/pending reasons. Use actual chronological data, not the demo's rolling sample buffer. Approximate chart coordinates are acceptable; authoritative values stay exact. When APIs are paginated, loading/continuation must be visible and charts must not silently misrepresent a partial series. Test deep links/refresh, route navigation, stale response suppression, zoneless rendering and polling cleanup. No LLM availability is required.

The ZIP contains a manifest, summary, equity series, events/ledger and executions/holdings sufficient to reconcile and reproduce the run. Include both candidate and benchmark with explicit identities. Manifest includes input/configuration hashes, source/engine/accounting versions, cost assumptions, effective times, data warnings and end-liquidation convention. Export exact decimal strings and safe CSV text cells, with bounded/streamed generation and server-generated safe filenames.

## 9. Acceptance fixtures and focused verification

Before coding financial expectations, document a small independent hand calculation in `planning/docs/backtest-baseline.md`. Use real production import/validation paths for synthetic bundles and exercise the actual accounting core. Do not compute expected values by calling the production algorithm under test.

Required reference scenario, with a complete declared synthetic calendar and sufficient January month-end evaluation data:

- Evaluation after the 2024-01-31 close with data already available before the first subsequent open.
- Fund EUR 1000 immediately before the 2024-02-01 open.
- For this arithmetic fixture only: zero spread/slippage, EUR 1 commission per fill.
- Feb 1 open and close 100: buy 9 units, commission 1, cash 99, cost basis 901, closing equity 999.
- Feb 2: 2:1 split before trading; open/close 50. Units 18, cost basis 901, cash 99, equity 999.
- Feb 5 ex-date: distribution EUR 1 per post-split unit, announced before the entitlement event; open/close 49. Receivable 18, cash 99, units 18, equity 999.
- Feb 6 date-only payment; open/close 49. No purchase at that day's open. After close, receivable 0 and cash 117; equity remains 999.
- Feb 7 open 49, close 50: reinvest into 2 units, commission 1, cash 18, units 20, total basis 1000, final equity 1018.
- Total commissions 2; total distributions recognized 18; cumulative return 1.8%; no final sale. Candidate and same-listing benchmark agree.

Add focused tests for the concrete remaining risks:

1. Same-close execution prohibited; weekend/holiday transitions; initial funding and first cost included correctly.
2. Future high/low/close changes cannot alter an earlier opening fill; later unrelated action changes cannot alter earlier events. Keep mutated fixtures otherwise valid and distinguish preflight rejection from information leakage.
3. Default nonzero spread/slippage, separate commission, affordability boundary, insufficient cash and zero-order fee suppression against independent arithmetic.
4. Split precision/basis preservation; ex-date eligibility before versus after purchase; no receivable multiplication by later splits; no double distribution; date-only versus precise payment timing; receivable still unpaid at end.
5. Missing required bar/action timing blocks the run; declared closed day does not. Unsupported calendar/currency and incomplete coverage produce actionable errors.
6. Engine processes more than the M2 default/max history page size without silent truncation.
7. Same input/configuration/engine produces identical ordered business events and financial output, excluding operational IDs/timestamps. Corrected imported datasets do not rewrite prior results.
8. Concurrent same-key creation/conflicts, publication failure rollback, cancellation/completion race, and startup interruption cannot create completed partial runs or duplicate funding.
9. Fresh schema and M2 upgrade preserve existing M1b accounts and M2 datasets. Legacy/chat mutation paths cannot modify a backtest.
10. Metrics reconcile to independent fixtures, including initial fees, flat values, known drawdown/recovery, unrecovered drawdown, partial years and short-sample CAGR suppression.
11. Export agrees with API/accounting totals and includes assumptions; incomplete jobs cannot export completed results.
12. Native browser creation, completion, cancellation/error states, result chart, deep-link refresh and downloaded export on a disposable database.

Run the repository's required full backend/frontend suites and production builds using the actual pinned toolchain. Report real commands/counts/results; do not invent test methods, schema names, recordings or timings. Add only tests that establish distinct behavior or satisfy these gates. Once sufficiently verified, stop optional testing.

Measure one disclosed representative daily-data workload if feasible, documenting actual listings, bars, sessions, runtime and machine. The original 20-ETF/20-year target is not an M3 claim: M3 supports a single-listing S1 and benchmark, and does not need an M4 universe engine to satisfy a benchmark number. No profitability gate.

## 10. Deliverables and closeout

Deliver functioning code/migrations/tests plus:

- `planning/docs/backtest-baseline.md`: event/timing/rounding conventions, independent example and local walkthrough.
- Synthetic fixtures or a deterministic generator for the new cases.
- `planning/reports/research-M3.md` with:
  1. Complete M2 checkpoint, tested M3 source identity and actual tool versions, including resolution of the Angular discrepancy.
  2. Implemented scope, architecture and exact changed schema/API contracts.
  3. S1/benchmark rules, time/availability assumptions, costs and decimal policy.
  4. Hand-calculated reconciliation versus observed run results.
  5. Actual commands, test results and native browser/export evidence.
  6. Durability, idempotency, cancellation, isolation and migration verification.
  7. A PASS / FAIL / NOT VERIFIED gate table, with Docker explicitly deferred.
  8. Known limitations and remaining real ETF/provider/budget choices.
  9. M4 readiness and any concrete blockers.

Keep report claims proportional to evidence. Source availability filtering does not prove a bias-free real-world experiment. SYNTHETIC fixtures establish software behavior, not investment performance. Real data/provider choices are required before meaningful strategy conclusions, even though they do not block this technical milestone.

Update the repository tracker and relevant architecture/user documentation. Preserve unrelated user changes; do not publish remotely or deploy. Stop after M3 and return a concise summary with the report path and actual verification status.
