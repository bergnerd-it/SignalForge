# SignalForge — Application Specification

**Specification version:** 1.0  
**Date:** 2026-09-14  
**Status:** Consolidated V1 product and engineering baseline  
**Canonical repository location:** `planning/SIGNALFORGE-SPEC-v1.0.md`  
**Implementation approach:** Incremental development of the FinAlly fork, named SignalForge  
**Document language:** English

## 1. Authority, version meaning and reading guide

This is the single specification for SignalForge V1. It consolidates and supersedes:

- `FINALLY-RESEARCH-SPEC-v0.1.md`.
- `SIGNALFORGE-SPEC-v0.2-M0-ADDENDUM.md`.

It also incorporates the relevant decisions and refinements from the M1–M3 implementation/fix handoffs, the latest M3 closeout report and the M4 implementation handoff. The two superseded specifications may remain archived for history, but an implementation agent does not need to read them to understand the current requirements.

**Specification v1.0 is not an application release or a completion certificate.** It defines what the agreed V1 application must do. Application/build versions, database migration versions, import schemas, algorithm versions and specification versions are independent identifiers. Open commercial/data choices below do not prevent versioning this baseline; they remain explicit prerequisites for particular uses.

MUST denotes a V1 requirement; SHOULD denotes an expected design practice whose deviation needs an explanation; MAY denotes an optional capability. Explicit subsequent user decisions take precedence and should be incorporated into a new specification revision. Milestone prompts select scope and give implementation instructions; they do not silently override this baseline. Existing reports describe evidence, not new requirements or authority to weaken acceptance criteria. Repository code determines what is implemented, not what the product contract ought to be.

This specification is written from the supplied source documents, milestone instructions and reports. The source repository and reported tests have not been independently rerun in the consolidation session. Normative requirements below are distinct from the implementation snapshot in section 3.

The owner can use sections 2–4 for scope, sections 5–21 for implementation and sections 22–25 for delivery, acceptance and remaining decisions.

## 2. Purpose and product outcome

SignalForge is a private investment research and paper-tracking application. It helps answer:

> Does a specified investment strategy improve outcomes compared with a simple global equity ETF, after modeled trading costs, while exposing losses, recovery periods and data limitations?

A correct result showing underperformance is a successful product outcome. There is no promised positive return, outperformance target, forecast win probability or optimized allocation.

The owner must be able to:

1. Import historical data and inspect coverage, provenance and validation findings.
2. Define a versioned ETF universe and a selected global equity ETF benchmark.
3. Run buy-and-hold, ETF momentum and a trend filter under reproducible assumptions.
4. Compare equity, drawdown, returns, costs, exposure, holdings, signals and fills.
5. Inspect the exact observations and rules behind a decision.
6. Freeze a strategy and track it prospectively in a separate paper portfolio.
7. Ask an optional AI assistant to explain stored results and proposals with references.
8. Export enough information to audit and reproduce a historical result.

Historical backtesting and the existing random-price demo are different functions. Both must have explicit mode and data labels. Synthetic simulations test software behavior and are not investment evidence.

## 3. Implementation status at consolidation

| Stage | Reported status as of 2026-09-14 | Meaning |
|---|---|---|
| M0 | Repository audit completed | FinAlly was retained as the foundation; concrete accounting and boundary work was identified |
| M1a | Native baseline stabilization completed | Configuration, startup and test baseline repaired |
| M1b | Accounting/identity foundation completed with fixes | Exact ledger, portfolio isolation, migration and durable actions established |
| M2 | Native import/data inspection accepted after fixes | Immutable dataset import, validation and data UI established |
| M3 | Accepted for progression based on latest supplied closeout | Buy-and-hold engine, benchmark, native tests/UI/export and targeted remediation documented |
| M4 | Implementation prompt issued; no completion report supplied | Momentum, trend and comparison are specified, not certified implemented |
| M5 | Planned | Prospective paper tracking and AI explanations |
| M6 | Planned | V1 usability, real-data/evidence review and deployment/recovery verification |
| Docker/container verification | Deferred | Native success is not container verification |

The latest M3 report identifies HEAD `a7acdcb37150aca3b5794d3038223c861ee47a14` plus uncommitted changes, schema through V7, 161 passing backend tests and 39 passing frontend tests. These are reported historical results, not tests executed for this document. The complete tree, including untracked build-identity code, migration and resource template, must be checkpointed before subsequent implementation changes.

Reported working tools are Java 21.0.12.1, Gradle 8.10.2, Node 24.21.0, Angular core 22.1.5, TypeScript 6.0.3 and Vitest 4.1.11. Earlier reports contained inaccurate versions. The latest report also records npm 11.19.0 while the manifest requests 11.18.x. Actual repository manifests, lockfiles, toolchains and build commands are authoritative for a new run; use and document the declared reproducible setup rather than treating this snapshot as permission to upgrade dependencies.

## 4. V1 scope and settled defaults

| Topic | V1 baseline |
|---|---|
| Audience | One private owner, local installation; multiple isolated portfolios/runs |
| Stack | Java 21, existing Spring Boot MVC, JdbcTemplate, Angular; modular monolith |
| Storage | SQLite with versioned migrations and exact decimal persistence |
| Instruments | Unleveraged ETFs; approximately 5–20 listings as an initial research scope target |
| Currency/calendar | EUR research cash, EUR-quoted listings and EUR distributions; common explicit trading calendar within a run/comparison |
| Sampling | Daily raw market data; monthly S2/S3 decisions |
| Execution | Long-only, no borrowing or shorts; modeled historical/paper trades only |
| Data | File import first; manual imports are sufficient for V1 paper tracking |
| Funding | One initial contribution per historical run; configurable amount, illustrative default EUR 10,000 |
| Costs | Stored scenario parameters; default EUR 1 commission, 10 bps full spread, 5 bps additional slippage |
| Cash interest | Zero, explicitly displayed |
| Taxes | Before personal taxes; no German tax-lot model |
| UI | German-facing V1 labels and explanations, localizable; identifiers and developer documentation in English. Existing English implementation screens may be localized during V1 usability closeout |
| AI | Existing configurable provider, optional; no core workflow depends on it |
| Network | Host loopback by default; remote access outside V1 |

EUR quotation does not mean the ETF is currency hedged. The UI must not claim hedging unless supported by instrument metadata. An illustrative allocation such as 80/20 is not a user commitment or an optimized default.

V1 includes catalog/identity, historical datasets, three strategies, historical accounting, comparison/experiments, paper proposals/tracking, audit/export and AI explanations. It preserves the legacy demo in its own context.

Later extensions include individual-stock factors with historical constituents/fundamentals, composite portfolios, FX and multi-currency cash, provider automation/notifications, personal tax scenarios, broker imports/execution, warrants, listed options, turbos, explicit financing/leverage models and authenticated remote access. None is implicitly authorized by this specification. V1 must not represent a warrant or turbo by multiplying ETF returns.

## 5. Architecture and boundaries

Retain one Spring Boot modular monolith and the existing Angular application. Reuse suitable layout, watchlist, chart components, SSE plumbing, configurable LLM client, startup scripts, container files and meaningful tests. No mandatory Python service, microservices, message broker, JPA migration or reactive rewrite is required.

| Logical module | Responsibility |
|---|---|
| Market | Demo/current quote sources, provenance/freshness and SSE |
| Instruments | Stable instruments/listings, aliases, calendars and source identity |
| History | Import staging, immutable datasets, raw bars/actions and quality checks |
| Strategy | Versioned definitions, total-return inputs, signals/ranks and targets |
| Backtest | Historical clock, opening adapter, execution batches and run lifecycle |
| Portfolio/accounting | Exact ledger, projections, receivables and prospective paper execution |
| Analytics | Returns, costs, drawdowns, benchmarks, comparisons and rolling windows |
| Chat | Provider integration and narrowly scoped research tools |
| System/database | Migrations, backup/restore, build identity, jobs and health |

API/application orchestration invokes domain services and infrastructure interfaces. The authoritative strategy/accounting core must not depend on controllers, HTTP downloads, live quote caches, wall-clock time or LLM responses. Inject an explicit historical/evaluation clock and scoped data view.

Keep latest-quote contracts separate from history/import contracts. Demo SSE must never alter a completed run, historical valuation or research signal. Backtest and prospective tracking reuse strategy rules and exact accounting, with different execution adapters and authorization/timing rules.

## 6. Identity, modes and core records

Use stable internal instrument/listing IDs, not ticker alone. Namespace provider aliases and their effective intervals; reject ambiguous overlapping aliases. Preserve ticker reuse across history. Do not fabricate ISINs, venues, dates or ETF classifications from defaults. Unresolved legacy identities remain unresolved; synthetic identities remain explicitly synthetic.

Modes:

- `LEGACY_DEMO`: existing terminal behavior, server-resolved fixed target, synthetic quotes allowed under demo policy.
- `BACKTEST`: isolated historical run accounting, created only by validated run lifecycle.
- `PAPER`: explicitly EUR prospective portfolios, separate inception and record history.

Every financial mutation carries its run/portfolio scope and owner context. Ordinary manual/chat endpoints cannot write BACKTEST accounts. Legacy requests attempting to choose research targets are rejected, not silently ignored. Read-only requests must not create funding/accounts or seed instruments.

Required concepts may use repository-specific table names:

| Concept | Essential information |
|---|---|
| Instrument/listing | IDs, declared type/name, optional ISIN, venue/symbol, currency, calendar and lifetime |
| Dataset version | Source/classification, checksums, parser/schema versions, coverage, import time, assumptions and findings |
| Daily bar/session | Dataset/listing/calendar scope, raw OHLC, optional volume, session kind and effective/availability times |
| Corporate action | Stable action ID, listing, type, effective/ex-date, availability, exact ratio or cash terms/payment precision |
| Universe/strategy version | Immutable membership/rationale/selection provenance, algorithm ID, parameters and calendar |
| Run | Frozen resolved configuration, client intent identity, versions/build identity, state and results |
| Portfolio | Owner, mode, name, currency, initial-funding reference, strategy and prospective start |
| Operation/ledger/execution | Scoped causes, immutable movements, business times, quantities, exact costs and durable result |
| Position/receivable | Rebuildable units/total basis; entitlement amount, source action and expected payment timing |
| Signal/proposal | Cutoff, input references, ranks/targets/reasons and execution/approval lifecycle |
| Valuation | Time and observation kind, cash, market value, receivables, equity and quality |
| Comparison/experiment | Immutable run/configuration references, compatibility, period boundaries and exposure history |

Foreign keys and uniqueness constraints must enforce required scope, including run/dataset/listing relationships. Terminal results cannot be extended, updated or deleted through ordinary application operations. Use database protection against update/delete and insertion into terminal parents where required. This is application integrity, not resistance to a database administrator.

## 7. Exact accounting and durable mutations

Construct authoritative Java BigDecimal values from decimal strings. Use canonical decimal TEXT in SQLite and decimal strings in REST/export contracts; no financial SQL REAL casts, floating-point sums or getDouble/setDouble in new accounting. Chart coordinates may use approximate numbers without feeding them back into financial calculations.

Version the numeric policy:

- DECIMAL128 division/internal calculation convention.
- Modeled execution prices support eight decimals; original exact source values are retained separately.
- EUR cash bookings use two decimals with HALF_EVEN; round a booking once and disclose residuals where needed.
- Quantities are not cash. Persist the established supported quantity precision/bounds and reject unsupported values rather than silently rounding them to cents.
- Whole-unit buy sizing does not prevent exact fractional holdings from splits or accounting fixtures. Reject an unrepresentable split outcome rather than inventing cash-in-lieu.

Store total acquisition cost, derive average cost for display. Buy fees increase basis; sale proceeds are reduced by sell fees; realized gain equals net proceeds minus proportional disposed basis. A full sale removes all remaining basis. Splits change units while preserving total basis. Ordinary cash distributions are separate income. This is weighted-average research accounting, not statutory tax-lot accounting.

Funding is an operation booked once; descriptive initial_cash metadata is not additional cash on replay. Ordered event/ledger replay must reproduce cash, units, basis, receivables and equity. No unauthorized negative cash or short positions.

A financial batch commits operations, executions, ledger, projections and durable success together. Rollback cannot leave a partially funded/traded account. Remote calls are outside financial transactions and notifications follow commit. SQLite coordination holds the writer guard through commit/rollback; acquire write capability before dependent balance reads and use bounded whole-operation retries. Do not nest manual BEGIN inside Spring transactions. Foreign keys are enabled on every connection, and multi-query portfolio reads use a consistent snapshot.

Idempotency:

- Existing account mutations use a durable scope such as `(portfolio, operation kind, key)`.
- Creation uses owner-scoped intent because the portfolio does not yet exist.
- Canonicalize validated financial intent, excluding JSON order, arrival time and retry metadata.
- Same key/same client intent returns the original stored result before fetching new quotes, invoking a model or rerunning preflight against a new engine.
- Changed intent with the same scoped key returns stable 409.
- Freeze resolved engine/data/configuration metadata separately from client intent; a software upgrade must not relabel an old successful replay.
- Resolve concurrent uniqueness conflicts durably; in-memory locks are insufficient.

Legacy chat action plans/results are durable outside deletable conversation text. Persist the plan before effects and use stable per-action identities. Retry after message persistence failure must not call the model for a new plan and duplicate trades. Clearing chat does not delete financial evidence. Missing/fabricated/stale quotes block real-provider demo fills under the documented freshness policy; simulator fills remain explicitly demo-only.

## 8. Migrations, compatibility and recovery

Use the existing ordered custom JDBC migration runner and schema_migrations registry, not an assumed Flyway implementation. Startup migrations finish before schedulers/request writers. Store migration version, description, checksum, application version and application time atomically. Recognize supported complete schemas and reject changed, unknown/newer or partial histories.

Never rewrite an applied migration or accept arbitrary checksum changes to make startup pass. Historical Java conversion/accounting dependencies were included in migration fingerprints: isolate new logic so an unrelated change does not redefine an old digest. Source/build fingerprints and migration compatibility have distinct roles. Parse compound SQL/triggers correctly, not by naive semicolon splitting.

Before intentional migration of a real installation, use a tested SQLite-consistent backup with writers stopped and required journal state retained. A copy of an active main file alone is insufficient. Tests and agent walkthroughs use only disposable databases/volumes, not the owner's working data.

Legacy conversion preserves IDs/timestamps, balances, trades, snapshots, watchlists and chat history. Convert finite REAL values deterministically, recording original values, converted values and reconciliation differences; lost precision cannot be recovered. If past trades cannot explain opening balances, use explicit migration openings/adjustments, not invented trades or double replay. Record the inferred legacy USD currency without relabeling amounts as EUR. Reject unconvertible rows rather than silently dropping them. Repeat startup must not duplicate funding or reseed an intentionally empty watchlist.

Test populated upgrades, repeat startup and rollback on injected failure. Restore testing opens a separate consistent backup with the pre-migration binary; a downgrade is restoration, not an untested reverse migration. Preserve previously demonstrated restore evidence and rerun it when changes create a concrete recovery risk.

## 9. Historical import and provenance

The primary V1 input is a browser-uploaded ZIP with exactly five allowlisted root files. Field spelling and required/optional status are controlled by the versioned parser contract; existing schema-compatible names should be preserved. These are required meanings:

| File | Contents |
|---|---|
| manifest.json | Schema version, source/retrieval time, coverage, use/license note, SYNTHETIC/HISTORICAL classification, RAW convention, calendar/action completeness, limitations and availability assumptions |
| instruments.csv | Stable instrument/listing IDs, declared type/name, optional ISIN, venue/symbol, EUR currency, calendar and inception/termination |
| sessions.csv | Calendar, session date, UTC open/close instants and TRADING/CLOSED classification |
| prices.csv | Listing/session, raw OHLC, optional nonnegative volume, available_at |
| actions.csv | Action ID/listing, SPLIT or CASH_DISTRIBUTION, effective/ex-date, available_at, exact split numerator/denominator or per-post-split-unit amount/currency, payment date and optional precise instant |

Use UTF-8, proper CSV quoting/multiline handling, unambiguous ISO dates/instants and exact decimal text. Empty actions.csv requires a completeness declaration; a missing file does not mean no actions. Preserve DATE versus INSTANT precision; do not invent midnight payment times. Unsupported actions/adjustments are rejected with stable errors. Adjusted vendor data may only be a separately identified diagnostic series; never substitute it for raw execution bars.

Persist immutable dataset versions containing relevant listing/calendar metadata, source/input checksum, parser/schema identity, normalized-content identity where used, coverage and validation findings. Keep original bytes or reconstructable source records. Catalog changes must not reinterpret an old dataset. Same bytes under the same parser/schema/options reuse the successful dataset; corrected bytes create a distinct version. Reprocessing with changed parser policy must have distinct identity.

Validation checks files/columns, enums, unique keys/references, precision bounds, positive OHLC and their ordering, volume, listing lifetime, expected trading-session coverage, calendars, currency, action ratios/dates/availability and split discontinuities. Actual prices need not equal a pure mathematical split adjustment. Findings include stable code/severity, file/row and listing/session where relevant. Invalid rows are not silently skipped or repaired.

Completeness, calendar authority and use rights are supplied assertions, not independently certified by import validation. A valid import is not proof of unbiased data or permission to redistribute it.

## 10. Knowledge time, coverage and historical views

Distinguish observation effective time, source availability and application import time. Importing today neither makes all historical data unavailable until today nor proves historic vintage correctness.

Completed daily-bar availability must not precede its session close. Actions may be announced before their effective date. If original vintages are unavailable, store the assumption and label results REVISED_HISTORY. A fixed universe selected today is RETROSPECTIVE_UNIVERSE unless historical selection evidence supports otherwise. SYNTHETIC remains synthetic and is excluded from investment-evidence conclusions.

History reads require explicit dataset/listing/range, with as-of filtering where applicable. Frozen earlier results are not rewritten by later corrections. As-of filtering only enforces recorded availability, not guaranteed absence of real-world lookahead/survivorship bias.

A declared holiday differs from a missing expected trading-session bar. Missing necessary observations block execution; no simulator fallback, forward-filled execution, shortened lookback, invented delisting price or dropped held listing. Out-of-coverage queries show requested/available ranges and reasons. Runs require explicit supported start/end boundaries and must not silently choose earlier valid dates.

The historical opening adapter may expose a raw auction/open reference under a disclosed model assumption. Full-day available_at typically describes the completed bar, not availability of all its fields at the open. Keep this opening adapter narrow; do not weaken full-bar as-of filtering to expose high/low/close early. Close-based decisions wait for required completed observations.

## 11. Import jobs and resource safety

Persist QUEUED, RUNNING, COMPLETED, FAILED and INTERRUPTED states. Use one active import and bounded queuing with explicit overload behavior. Cancellation is optional for imports. Stable request keys map identical upload intent to its original job; conflicting reuse returns 409. Deliberate retries after failure/interruption follow a documented new-intent protocol.

Check compressed size before reading the whole upload. Bound compressed/expanded bytes, files, rows, staging and retained work. Reject unexpected/nested paths, traversal/absolute paths, duplicate names, symlinks and excessive expansion. Accept uploads, not arbitrary server filesystem paths. Parse outside long write transactions, then publish complete normalized data and success atomically. Failure may leave a diagnostic job but not visible partial datasets or changed portfolios. Recover unfinished jobs honestly and clean only task-owned staging.

The established import envelope is 20 MiB compressed, 100 MiB expanded, at most 10 archive entries with only the five root files accepted, and 500,000 rows per file. Preserve documented per-file/resource bounds. A compression-ratio threshold is not independently required if absolute bounds are enforced. Changes to limits must be explicit and tested.

Source/error text is escaped, never executed/rendered as trusted HTML. Do not put credentials in manifests, fixtures, logs or exports.

## 12. Strategy definitions and total-return index

Strategies have immutable algorithm identifiers and validated parameter versions. Every run retains universe/version, exact parameters and calculation policy. Inputs are an explicit cutoff/as-of view plus portfolio context where needed; outputs are immutable signals with references, ranks/targets/reasons and execution state.

For raw close C, split multiplier s (new units per old unit), and cash distribution D per post-split unit, construct a total-return signal index:

`T(d) = T(previous session) × s(d) × (C(d) + D(d)) / C(previous session)`.

Initialize T to an arbitrary positive base such as 100. Missing s=1/D=0 is permissible only under the supported completeness declaration. Use a documented deterministic same-date action order. Require the prior point and continuous daily history needed by the recurrence.

This index assumes theoretical ex-date reinvestment for signal measurement. Portfolio distributions become spendable only on payment. Never post the index return as cash or count distributions both through adjusted prices and cash flows. Use authoritative unrounded calculation values for ranking; displayed rounded scores are not ranking inputs.

### S1 — ETF_BUY_HOLD_V1

Exactly one selected unleveraged global equity ETF/listing. Initial target is 100% ETF; execute at the next eligible open after initial evaluation. No scheduled rebalancing or strategic sale. Reinvest paid distributions at the first eligible subsequent open when a whole unit plus costs is affordable; otherwise retain cash. Do not introduce daily residual-cash buying unrelated to a payment trigger. Benchmark uses the same strategy/execution model.

### S2 — ETF_MOMENTUM_12_1_V1

Use one immutable fixed ETF universe. At month-end m:

`score(i,m) = T_i(last trading session of m−1) / T_i(last trading session of m−12) − 1`.

This excludes the latest month and measures approximately eleven months, not twelve daily-month approximations. Every configured listing must have sufficient continuous history; no opportunistic universe shrinkage. Rank descending; exact ties use stable listing ID ascending. Select top K, default K=3, with 1 <= K <= universe size. Target equal weights 1/K and zero in others. Preserve rational equal-weight meaning for sizing. Negative scores do not trigger cash.

Rebalance monthly at the next eligible open. Paid distributions remain cash until the next scheduled monthly rebalance. Persist this convention; no unscheduled reinvestment/rebalancing is added. This is ETF rotation, not replication of the MSCI World Momentum stock index.

### S3 — ETF_TREND_10M_V1

Use the same selected global ETF as the S1 reference. At month-end m compute the arithmetic mean of ten month-end index values including m:

`SMA10(m) = mean(T(m−9), …, T(m))`.

If T(m) > SMA10(m), target 100% ETF; otherwise target 100% cash. Equality selects cash. Cash interest is zero; cash must not be labeled a money-market ETF.

Execute allocation changes at the next eligible open. Unchanged targets do not create artificial monthly sell/rebuy activity. Reinvest paid distributions only while the applicable target remains ETF. An allocation-change batch takes precedence over a same-open reinvestment attempt; merge compatible intent to avoid duplicate trades. Receivables survive sale and do not force re-entry while target is cash.

The named V1 lookbacks are fixed definitions. Alternative lookback algorithms need distinct identifiers; K and cost sensitivities can create new validated parameter/run versions without rewriting previous results.

## 13. Evaluation schedule and event clock

Initial evaluation is at a selected completed month-end after warm-up. Validate the evaluation date against the declared calendar and cutoff against required bar availability, not only close time. The candidate and benchmark receive identical funding immediately before their common first subsequent eligible opening event. Warm-up is excluded from performance. S3 initially selecting cash does not shift funding or reporting boundaries.

S2/S3 evaluate at each month-end using a frozen, documented availability policy. A delayed required observation cannot produce a backdated decision or fill. Use the first eligible open strictly after the decision instant or explicitly reject an unsupported timing configuration. Pending decisions must not be silently overwritten/reordered by later months. End dates must be supported explicit trading sessions; missing/closed end choices produce an actionable error rather than silent truncation.

Order historical events by effective instant and documented tie-breaks:

1. Session-start splits and ex-date entitlements using eligible pre-trade holdings.
2. Payments as their actual supported availability boundaries occur.
3. Opening executions whose intent and spendable cash already exist.
4. Closing marks/valuations and completed-data decisions at their availability times.

Use the actual calendar, not hard-coded example opening/closing hours. Date/instant comparisons use parsed types, not substrings/lexicographic assumptions. Strategy handlers receive only permissible information. Missing required action terms at their effective event produce a strict unsupported-availability failure, not an invented earlier announcement.

Business timestamps and event ordering are deterministic. Operational run IDs and job wall-clock timestamps may differ; they must not determine financial order or replace market time. A final signal with no in-window next open is retained as unexecuted. No forced sale at run end.

## 14. Splits, distributions and valuations

Apply a same-day split before interpreting distributions per post-split unit. Ex-date entitlement belongs to holdings from before that session's trading. Create an exact booked receivable with source action, amount, entitlement and known payment timing. It contributes to equity but not spendable cash. A later split must not multiply a previously established cash receivable.

At payment, replace the same receivable with cash without recognizing income twice:

- A supplied precise payment instant is respected, including intraday settlement in that session's cash snapshot.
- Date-only payment becomes spendable after the session close.
- On a declared closed date, date-only payment uses a documented end-of-calendar-day boundary in the selected calendar timezone.
- Reinvestment uses a strictly subsequent eligible open; cash arriving exactly at an opening boundary cannot finance that same opening fill.

Outstanding payments beyond run end remain receivables with source/timing metadata. Missing required payment information blocks unsupported accounting; ex-date is not a substitute payment date. Multiple payments available before one reinvestment open are pooled into compatible intent, not duplicate orders.

Equity = cash + raw-price market value + outstanding receivables. Keep acquisition basis separate from market value. Observe pre-open initial funding and session-close equity separately, including both on the same date. An after-close payment may add an event snapshot but must not create an extra trading-day return observation. Final valuation reconciles to ending cash, holdings and receivables. Hypothetical liquidation costs are not deducted; disclose this.

## 15. Execution costs and multi-asset sizing

Default scenario inputs are EUR 1 fixed commission per nonzero fill, 10 bps full bid/ask spread and 5 bps additional adverse slippage per side. They are illustrative research costs, not verified broker prices.

For raw opening reference P, full spread fraction s and slippage l:

- Buy fill = P × (1 + s/2 + l).
- Sell fill = P × (1 − s/2 − l).

Commission is separately booked; embedded spread/slippage must not be deducted again. Do not deduct ETF TER again from observed fund prices/returns. Model no personal taxes, liquidity/market impact or settlement delay. Trade-date sale proceeds are immediately reusable under the disclosed model.

Signals fix target weights, not exact units. Sizing at the opening reference is a simulation convention, not a claim that a real pre-submitted order knows its auction price.

For a target batch:

1. Value pre-trade equity at raw opens, including receivables but excluding them from spendable cash.
2. Target whole units = floor(weight × pre-trade equity / raw open).
3. Sell excess first, including a split-created fractional residual when needed to reach the target; preserve proportional/full-disposal basis rules.
4. Compute desired whole-unit buy increments and costs. If unaffordable, scale desired buys proportionally under a documented deterministic allocator, floor them, then remove further units in listing-ID order until affordable.
5. Recompute fees when an order becomes zero. Do not redistribute unused cash during that batch.
6. Use the exact rounded amounts that will be booked when checking affordability. Skip zero orders without fees; never borrow or silently waive unaffordable costs.

Persist target versus actual weights, requested/executed quantities, raw/reference/fill values, commissions, cost estimates, realized gains and shortfall/skipped reasons. Ordinary buying stays in whole units, while disposal can clear split fractions under the labeled simulation assumption. A batch is all-or-nothing within the run's publication/accounting design.

## 16. Backtest configuration, lifecycle and reproducibility

A normalized frozen configuration contains at least strategy/universe/benchmark identities, dataset IDs/checksums/parser/schema, requested/effective intervals, calendar/timezone, funding, costs, calculation/execution/accounting versions, engine/build/commit/dirty/source fingerprint and all data/selection assumptions.

No downloads, live caches, random simulator or LLM inside calculation. A new key may create a separately executed identical-input reproducibility run; do not silently deduplicate across incompatible engines. Same-key replay returns the original result/snapshot even after a software change. Legacy runs lacking metadata retain an explicit incomplete-provenance label; do not invent historic build identity.

States: QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED, INTERRUPTED. Use one calculation worker and a bounded durable queue; current M3 uses 50 queued slots. Saturation has a stable overload response and cannot leave permanently queued work. Calculate outside long database write transactions with complete bounded data access; M2 default pages are not complete input datasets.

Publish results and COMPLETED atomically. Staged rows are inaccessible as successful results. Cancellation uses durable conditional state transitions and defined event/session boundaries; all terminal retries are safe, including cancelled queued tasks. A cancellation/publication race cannot expose partial success or overwrite a terminal state. Startup marks unfinished jobs INTERRUPTED, never fabricates completion or duplicate funding. Retry is deliberate and documented.

Repeat frozen inputs under the same engine produce identical financial outputs and ordered business events, excluding operational IDs/timestamps. Completed data/configuration/results are read-only and unaffected by later imports, strategy edits or live price updates.

## 17. Analytics, comparisons and five-year windows

Historical V1 uses one initial contribution and no later external flows. Required output:

- Initial/final equity; ending cash, positions, basis and receivables.
- Cumulative return; accounted benchmark return and difference.
- CAGR = `(ending/starting)^(365.25/elapsed calendar days) − 1`, using effective funding/reporting dates. Suppress for periods shorter than a year.
- Daily equity/returns/drawdown; maximum drawdown and peak/trough/recovery dates, peak-to-trough, trough-to-recovery and total underwater duration. Unrecovered remains ongoing.
- Calendar annual return table; first period starts at funded equity and partial years use actual calendar/reporting coverage, not date heuristics.
- Sample daily-return volatility with disclosed default 252-session annualization; unavailable when insufficient observations.
- Exposure/cash weights with receivable treatment, turnover with explicit formula, fills, commissions and separate spread/slippage estimates.
- Realized/unrealized gains and all assumptions/exclusions.

Initial trading costs must appear in first-year and cumulative returns and the chart's initial drawdown. No phantom extra return from pre-open/event snapshots. Missing metrics are unavailable, not zero/NaN/Infinity. Statistical/transcendental calculations may use a documented numerical method that does not feed back into accounting.

The benchmark is independently funded and accounted using S1 with matching assumptions, not a normalized close-price line. Candidate and benchmark using the same listing/configuration must agree.

A persisted comparison references completed owner-scoped runs. Require matching funding instant, reporting sessions/end, initial cash, calendar/currency, dataset snapshots, benchmark and costs/execution/accounting/compatible engine. Strategy and intended universe may differ. Reject mismatches with field-specific reasons, not an automatically shortened overlap. Re-run S1 under the new engine when needed instead of rewriting old M3 runs. Distinguish percentage-point and monetary differences. Do not claim gross performance by merely adding costs back to net wealth; gross counterfactual performance needs a separate simulation.

Rolling five-year windows are subperiod returns of the existing simulated portfolio, not newly funded strategy reruns:

- Start monthly at equity immediately before the first trading session, using prior close or a qualifying first-session initial funded point.
- End at the last declared session on/before the five-calendar-year anniversary, with calendar coverage through the anniversary boundary.
- Return = end equity/start equity − 1. Use matching boundaries across comparisons.
- Omit incomplete windows with an insufficient-history reason; never pad or annualize short windows.
- Expose exact dates, return, observation/window counts, overlapping nature and positive share (strictly return > 0).

Carried holdings and pre-window costs/exposure remain part of these subperiods. The positive share is not a forecast probability or independent-trial success statistic.

## 18. Experiments, sensitivity and holdout exposure

Persist immutable experiment definitions with development/holdout boundaries and frozen strategy/configuration references. The holdout boundary is before a selected trading session; development ends at the previous close. Disclose whether evaluation is a segment of a continuous portfolio, not independently refunded. Earlier available data may supply warm-up. Reject overlapping/unsupported intervals.

Exposure status can be declared already examined, not yet examined or unknown. Previously viewed runs cannot be relabeled unseen. Append a durable exposure event before first result retrieval/export that exposes holdout metrics, including summary/comparison endpoints. Hiding a chart while returning its results does not preserve unexamined status. Conservatively marking exposure upon any result view is acceptable. The application cannot know what the owner has examined elsewhere and must say so.

Parameter/configuration changes create a new experiment/version retaining previous results and exposure history. Support small explicit sensitivity sets, such as default/higher costs and alternative K, retaining every attempted configuration/status. No automated search for the most impressive backtest or unexplained selection of the highest-return default. Do not infer an edge from correlated trades or independent resampling of dependent outcomes.

## 19. Prospective paper tracking

Each PAPER portfolio has its own creation time, associated immutable strategy, initial funding and paper_started_at. Historical runs do not populate an earlier prospective track record. Multiple portfolios for one owner can hold the same listing independently. Do not label an empty account's creation as completed strategy tracking.

Generate a proposal only when required completed sessions are available and valid. A stale/failed import yields a visible blocked evaluation; no random quote fallback. Freeze data references, inputs/targets, proposal creation/cutoff and intended execution. Imported corrections do not rewrite prior decisions.

Default mode is explicit proposal acceptance:

- Acceptance before a future execution open records intent; a later imported raw open can resolve the modeled fill.
- Acceptance after that open cannot award its historical price. Schedule the next eligible future session with fresh sizing context.
- Record decision, acceptance, scheduled execution, market-effective fill and observation/import times separately.
- Reject or supersede stale/incompatible proposals explicitly; never silently reinterpret an accepted target.

Optional AUTO_PAPER requires an explicitly enabled deterministic strategy. Persist intent before its execution session. Following downtime, do not invent missed past intent; record a missed signal and evaluate/schedule a future opportunity. Duplicate imports, retries and restart cannot double-fill or double-book cash.

Use the same signal and accounting rules as research, with a prospective timing/authorization adapter. Manual daily file imports are sufficient; unattended tracking requires separately configured data automation. Current demo/chat endpoints remain confined to the legacy target.

## 20. AI assistant

Reuse the existing provider abstraction and chat UI with explicit research context. The assistant explains computed data; it is not the authority for prices, arithmetic, rankings, signals or execution.

Initial tools retrieve run summaries, holdings, drawdowns, signal inputs/reasons, comparisons and proposals. Numerical statements cite returned run/portfolio/data references. Missing data produces an explicit limitation rather than fabricated prices, identities, news or returns.

The assistant may propose a new research configuration or paper proposal, subject to typed server validation and the paper acceptance/execution path. Model-generated JSON is not itself execution authorization. It cannot overwrite strategy versions, old runs or unrelated portfolios. AI failures cannot roll back completed financial work or block core workflows.

Only selected necessary research context is sent to the configured provider; secrets remain server-side and unrelated chat/portfolio data is not automatically forwarded. No provider change or new external transmission scope is silently introduced. Existing legacy chat execution retains durable plan/idempotency boundaries described in section 7.

## 21. User interface, API and exports

| Route | User task |
|---|---|
| `/demo` or existing explicit demo route | Labeled legacy terminal |
| `/research` | Data/strategy/run/paper overview |
| `/research/data` | Import, versions, coverage, raw bars/actions and validation |
| `/research/strategies` | Rules, immutable parameters/universe and signal inspection |
| `/research/backtests` | Configure/start runs, progress/cancellation/failure |
| `/research/backtests/:id` | Results, benchmark, events/fills, assumptions and export |
| `/research/compare` | Matched runs, drawdown/returns, windows and experiment context |
| `/research/portfolios/:id` | Paper balances/receivables, proposals and prospective history |

Reuse existing components. Support loading/empty/error/stale/insufficient-history states, deep links/refresh, browser navigation, lifecycle cleanup and zoneless rendering. Cancel/suppress outdated responses so context A cannot overwrite B. Use typed DTOs without permissive missing-value defaults that display fabricated cash impact or metrics. Financial values remain exact strings; chart tooltips show dates, currency and source labels.

All list/history/result endpoints are bounded and deterministically ordered. Both candidate and benchmark must load; API default pages cannot masquerade as complete charts/tables. The current chart envelope is 50,000 points with loaded/total and isComplete=false when capped; incomplete views must visibly warn. Server-side comparisons use complete stored metrics/series rather than capped client data. Long UI-only seeded fixtures must not be presented as financial backtests.

Use `/api/research` and preserve existing contracts unless deliberately versioned:

| Resources | Required operations/behavior |
|---|---|
| Imports/jobs | POST upload, GET scoped status/result |
| Datasets | GET versions/detail/listings/sessions/actions/history with explicit dataset |
| Instruments | Read/search stable identity and metadata |
| Universes/strategies | Create/list/read immutable versions |
| Backtests | POST/GET; detail; cancel; scoped equity/signals/events/orders/holdings; export |
| Comparisons/experiments | Create/read immutable references, compatibility, windows and exposure history |
| Paper portfolios | Create/list/detail, evaluate, proposal list and accept/reject |

Exact endpoint suffixes follow the existing code contract; do not rename working paths solely to match a schematic table. Every run/child/export mutation or read resolves owner/scope consistently. Bind parameters and parse enums; no request-string SQL interpolation. Return stable actionable validation/conflict/overload errors. Failed/incomplete jobs cannot expose completed summaries or exports. No public arbitrary-ledger/synthetic-fill write API.

Exports contain frozen manifest/configuration, dataset/code/engine identities, assumptions/classification, effective times, summary, equity, holdings/receivables, events/ledger, executions and relevant signals/ranks/targets/comparison metadata. CSV is UTF-8 with exact decimals/date formats and type-aware text formula protection; valid negative numeric fields remain numeric text. Pending events and end-liquidation assumptions remain visible.

Bound export preparation and reads. Current M3 prepares at most a 50 MiB ZIP in a task-owned temporary file before response headers, then streams it; oversized preparation returns 413. Current row envelopes are 100,000 per series/event/order resource and 10,000 holdings. Extended M4 resources need equally explicit bounds. Ownership checks precede preparation/delivery. A download/checksum/content check is evidence distinct from a browser link merely being visible.

## 22. Operations, access and quality discipline

Host loopback is the default. Docker may bind internally as required, but publishes on host loopback, for example `127.0.0.1:8000:8000`. Disable wildcard CORS and enforce explicit mutation-origin policy. The existing local X-User-Id convention is client-supplied scope, not authenticated multi-user access. Do not claim remote security without a separate authentication/authorization design.

Store durable inputs/results in the configured persistent data location; document backup/restore and resource retention. Exclude secrets, local databases and build/dependency trees from container inputs. Use lockfile installs and compatible test/browser versions. App health, data freshness and source mode are separate indicators. A missing quote-provider key may select demo simulation only; research remains blocked or explicitly fixture-based.

Research valuations are session/event based, not millions of periodic live snapshots. Historical demo behavior and its documented cadence are not authority for research clocks.

Record meaningful progress, failure reasons, source fingerprints and actual tested identity including dirty/untracked files. Do not claim a green build establishes untested concurrency, recovery or UI behavior. Tests use actual production configuration and foreign-key-enabled isolated databases where integration matters. Independent arithmetic and injected failures establish concrete invariants. Do not invent tests, screenshots, tool versions, schema names or production-readiness claims.

The initial performance target is a 20-ETF/20-year daily run within 60 seconds on the owner's development machine. It is a measurement target, not a currently certified result or reason to build speculative infrastructure. Record workload/machine/runtime and optimize demonstrated bottlenecks. Profitability is never a performance or acceptance gate.

## 23. Delivery milestones and release criteria

| Milestone | Scope and exit |
|---|---|
| M0 | Audit actual architecture, boundaries, precision, migrations, tests and deployment; report concrete extension plan |
| M1a | Restore native startup/test baseline, local-access boundary and reproducible build inputs; report unavailable container gate honestly |
| M1b | Exact ledger, stable identity, modes, durable actions, migration/reconciliation/restore and minimal paper account UI |
| M2 | Complete immutable file import, validation/provenance/history APIs and data inspection with synthetic fixtures |
| M3 | S1, historical event clock, accounting/benchmark/jobs, analytics, basic UI/export and hand-calculated reconciliation |
| M4 | S2/S3, multi-asset execution, immutable parameters/signals, matched comparisons, rolling windows and experiment/holdout metadata |
| M5 | Prospective proposal/acceptance/AUTO_PAPER lifecycle, missed-signal handling and grounded AI explanations |
| M6 | End-to-end V1 usability/localization, real-data/source decisions, evidence review, measured performance and deployment/restore completion |

A milestone report records changed contracts, tested commit/tree, commands/counts/results, native/API/browser evidence, limitations, PASS/FAIL/NOT VERIFIED gates and next-stage readiness. A prior pass count is not a new test run. Implement only the assigned milestone; acceptance of M3 is not authorization to mark M4–M6 complete.

**Technically complete V1** requires applicable functional and operation gates, usable documented workflows and honest data labeling. **A real-data research release** additionally requires selected genuine listings/benchmark, suitable data/use rights and assessed coverage/vintage limitations. If real data remains unresolved, deliver a technical demo explicitly distinguished from validated investment research. Container delivery cannot be called verified until its deferred gate is run or its release scope is explicitly changed.

## 24. Acceptance invariants and independent examples

Keep original A identifiers for traceability and add requirements without renumbering old findings:

| ID | Required evidence |
|---|---|
| A01 | Legacy data survives upgrades; repeat startup preserves empty watchlists and does not duplicate funding |
| A02 | Exact repeated purchases/sales/fees/splits reconcile cash, units and total basis |
| A03 | Constant-value 2:1 split doubles units, halves raw price and preserves equity/signal return |
| A04 | Distribution entitlement, receivable, payment and signal return are not double-counted |
| A05 | Month-end decisions execute only at an eligible later open, including holidays and delayed availability |
| A06 | Future high/low/close/actions cannot alter earlier permitted signals/fills |
| A07 | S2 exact 12–1 endpoints, full eligibility, deterministic ties/top-K/equal targets |
| A08 | S3 includes current month; equality selects cash; unchanged target avoids churn |
| A09 | Costs, whole-unit allocation, fractional disposal, zero suppression and cash constraints match independent arithmetic |
| A10 | Missing expected sessions/actions block; closures do not; no fabricated delisting exit |
| A11 | Repeated frozen inputs yield identical business results; revisions preserve old runs |
| A12 | Benchmark/comparison funding, dates and assumptions match; mismatches are rejected explicitly |
| A13 | Concurrent/repeated creation, orders, imports and restarts cannot duplicate financial effects |
| A14 | Injected failures roll back full financial/publication batches; no visible partial completion |
| A15 | Late acceptance/missed automation cannot obtain a fabricated historical fill |
| A16 | LLM failure leaves research intact; legacy/chat paths cannot mutate unintended accounts |
| A17 | Typed UI, both series, complete/capped pagination, stale-response suppression, deep links and exact exports |
| A18 | Failed/cancelled/interrupted jobs never appear completed; immutable configurations/datasets/results |
| A19 | SQL filters are bound and all run/child/export resources enforce the existing owner scope |
| A20 | Payment precision, intraday/closed-date timing, unpaid obligations and split precision are respected |
| A21 | Initial-funded observations, annual returns/CAGR/drawdown and unavailable metrics are correct |
| A22 | Real concurrent reservation, repeat/queued cancellation, saturation and cancellation/publication races are exercised |
| A23 | Rolling windows have exact matched boundaries, overlap/count labels and no forecast-probability claim |
| A24 | Experiment versions and exposure history persist; prior/unknown inspection is not labeled unseen |
| A25 | Archive/export bounds, traversal/symlink rejection, exact negative decimals and populated migration guards hold |

Reference arithmetic (not investment scenarios):

- Precision regression: two purchases of 0.004 units retain 0.008 units, not 0.01.
- M3: EUR 1000 funded immediately before Feb 1, 2024 open; commission EUR 1, zero spread/slippage. Buy 9 at 100 → cash 99/basis 901/equity 999. Feb 2 split 2:1 at price 50 → 18 units/equity 999. Feb 5 distribution EUR 1, close 49 → receivable 18/cash 99/equity 999. Feb 6 date-only payment after close → cash 117/receivable 0. Feb 7 buy 2 at 49 plus fee 1, close 50 → cash 18/20 units/basis 1000/equity 1018; return 1.8%, commissions 2. The same fixture with default 10/5-bps costs is reported to end at EUR 1017; retain independent assertions for both.
- Signal index: prior close 100, 2:1 split/close 50 → zero growth; next close 49 plus distribution 1 → zero growth.
- S2: base endpoint 100 for A/B/C; later endpoint 120/110/110; K=2 selects A and the earlier stable ID among B/C. Changing excluded month m must not change that score. All-negative scores still select top K.
- S3: ten 100s select cash; nine 100s then 110 give mean 101 and ETF; nine 100s then 90 give mean 99 and cash.
- Rotation: cash 100, 9 A units at open 100/basis 901; rotate to B open 50 with zero spread/slippage and fee 1 per fill. Sell A net 899 → cash 999/realized gain −2. Buy 19 B plus fee → cash 48/basis 951. B close 50 → equity 998, total fees 2, no A basis.

Integration fixtures must pass the real importer and contain full declared calendar/warm-up coverage. UI-only seeded pagination/cancel fixtures must be disclosed separately. Test expected values must not be calculated by calling the production algorithm under test. Native browser checks and downloaded bytes are separate evidence from unit mocks.

## 25. Open decisions and specification maintenance

Additional broad architecture planning is not required to continue the agreed milestones. These remaining choices have explicit boundaries:

| ID | Status/decision | Required before |
|---|---|---|
| D1 | Settled: ETF-first V1; individual-stock screening later | A future scope revision to add stocks |
| D2 | Open: actual ETFs/listings/benchmark and universe-selection rationale | Meaningful real-data strategy comparisons |
| D3 | Open: historical source, coverage/vintage/use rights and acceptable budget | Real-data ingestion/provider integration and operational evidence |
| D4 | Settled: local-only V1; remote/phone access is separate scope | Any non-loopback exposure |
| D5 | Settled: SQLite/JDBC for V1; PostgreSQL/JPA are not current tasks | A separately justified architecture revision |
| D6 | Open: personal horizon, acceptable loss/recovery and allocation preferences | Personalized interpretation or allocation advice, not technical engine development |

Choosing a data source may reveal gaps in corporate actions, availability, history or calendar coverage. Document them rather than silently relaxing the model. Narrow implementation details for M5 can be specified in its handoff provided they preserve this baseline; a substantive change to execution, identity or scope updates the specification.

For repository adoption:

1. Add this file at its canonical location and update README/planning references to it.
2. Retain the two old specs as superseded historical records, optionally under an archive directory; they are not active prerequisites.
3. Point new milestone prompts to this file plus the relevant current report and task scope. Old prompts remain historical unless explicitly reissued.
4. Keep the latest accepted reports and exact source checkpoints; do not rewrite history to suggest V1 was already released.
5. Record later clarifications as a new document revision with a concise change log. An intentional behavioral change gets a new relevant strategy/engine/schema version as well.

### Consolidation change log — v1.0

- Renamed the application baseline from FinAlly Research to SignalForge and replaced two active specs with one.
- Incorporated audited reuse decisions, M1a/M1b separation, exact ledger/identity/durable-action and migration safeguards.
- Incorporated M2 immutable import/availability/resource/API contracts and M3 timing, jobs, frozen metadata, funded observations and export corrections.
- Incorporated M4 exact strategy, distribution, rebalance, comparison, rolling-window and holdout conventions.
- Preserved V1 prospective paper and optional AI requirements, later-feature boundaries and acceptance traceability.
- Separated specification version from implementation/release status, and settled architecture choices from outstanding real-data/personal decisions.
