# SignalForge Research — Extension Specification

Version: 0.1 — reviewable draft, not yet an agreed product baseline  
Date: 2026-09-11  
Implementation: extend the existing Spring Boot / Angular application  
Language: English for implementation handoff

## 1. Purpose and status

Extend SignalForge from an intraday trading demonstration into a personal investment research application. It should compare transparent investment strategies, explain their behavior, generate reproducible signals, and track separate paper portfolios over time.

The question the product helps answer is: **Does a specified strategy improve investment outcomes compared with a simple global equity ETF, after modeled costs and with acceptable losses and recovery periods?**

There is no required positive return, outperformance target, or assumed probability of success. A correct result showing that a strategy underperforms is a successful product outcome.

This draft is based on the supplied `PLAN-java.md`, `backend-architecture.md`, and `frontend-architecture.md`, plus the discussion about global ETFs, momentum, trend filters, and later derivatives. The repository, dependency lockfiles, actual implementation, and current test results have not been inspected. Statements about existing behavior are therefore documentation-based.

**Scope convention:** “MUST” describes the proposed acceptance contract if this draft is adopted. It does not imply that the user has already approved every product choice. Section 3 distinguishes confirmed context from proposed defaults and unresolved choices. Work on the architecture audit and provider-independent core need not wait for a market-data subscription decision.

## 2. Product outcome and first vertical slice

The owner can:

1. Import a historical dataset and inspect its source, coverage, and limitations.
2. Select a versioned investment universe and a global equity ETF benchmark.
3. Compare buy-and-hold, ETF momentum, and an ETF trend filter using the same starting capital, period, execution assumptions, and reporting currency.
4. Inspect equity curves, drawdowns, costs, holdings, signals, and executed simulated trades.
5. Freeze a strategy configuration and follow it prospectively in its own paper portfolio.
6. Ask the existing AI assistant to explain computed results, with references to the relevant run and observations.

First complete vertical slice: **import a validated EUR ETF dataset → run buy-and-hold and monthly momentum → display the comparison and audit trail**. Add the trend filter and prospective paper tracking after this slice works.

The historical simulator evaluates investment rules. The existing random market simulator tests application behavior. Their outputs MUST remain distinguishable.

## 3. Context, proposed defaults, and open decisions

### 3.1 Established context

- A working SignalForge demo exists in Spring Boot and Angular, according to the user.
- Prefer incremental extension over a rewrite, subject to a focused code audit.
- The owner wants to investigate long-term returns and accepts substantial fluctuations.
- The application should support comparison and evidence-based evaluation; it cannot establish future returns with certainty.

### 3.2 Proposed defaults

| Topic | Draft default | Reason / consequence |
|---|---|---|
| Audience | One private user, local installation | Keeps the first version bounded |
| Backend / frontend | Existing Spring Boot and Angular stack | Reuses application infrastructure |
| Deployment | Existing application container and port, persistent local data volume | Preserves the familiar startup flow |
| Storage | SQLite for V1 with versioned migrations and exact decimal persistence | Avoids an immediate database-server dependency |
| First research instruments | Unleveraged ETFs, a small explicitly selected universe | Avoids historical individual-stock constituent requirements in the first release |
| Initial universe size | Approximately 5–20 ETFs | A scope target, not a requirement to buy that many assets |
| Reporting / trading currency | EUR; V1 research accepts EUR-quoted listings and EUR cash distributions | Avoids an incomplete FX accounting implementation |
| Sampling | Daily market data; monthly strategy decisions | Suitable for the initial strategies |
| Execution | Long-only, no borrowing, no shorts; paper trades only | A clear accounting and execution boundary |
| Data integration | File import first; provider adapter later | Development does not require a purchased API |
| Research capital | EUR 10,000, configurable | A simulation setting, not an allocation recommendation |
| Personal tax model | Excluded from V1; results explicitly before personal taxes | Prevents a misleading simplified German tax calculation |
| UI language | German; source identifiers and technical documentation in English | Appropriate for the owner; keep labels localizable |
| LLM | Existing configurable provider; optional for every core workflow | Analysis remains functional without AI access |

EUR trading does not mean currency hedging: a EUR-quoted world ETF can still have foreign-currency economic exposure. The interface MUST not describe it as hedged unless the instrument itself is hedged.

### 3.3 Decisions still needed

| ID | Decision | Can work proceed without it? | Point at which it matters |
|---|---|---|---|
| D1 | Confirm ETF-first V1 versus immediate individual-stock screening | Yes, using ETF-first as this draft's explicit scope | Before extending beyond the initial strategy implementations |
| D2 | Select actual ETFs, listings, benchmark, and rationale for the universe | Yes, using labeled technical fixtures | Before a real investment comparison |
| D3 | Choose historical data source and acceptable ongoing budget | Yes, using the documented file contract | Before a provider integration and current-data operation |
| D4 | Confirm local-only use versus access from phone / other computers | Yes, using loopback-only deployment | Before any network exposure |
| D5 | Decide whether SQLite simplicity or PostgreSQL is preferred | Yes, using the exact SQLite design in this draft | Before finalizing migrations in milestone M1 |
| D6 | Set intended holding horizon and acceptable loss / recovery duration | Yes, showing all comparison metrics | Before personalizing strategy selection or allocation |

Do not silently turn an illustrative allocation such as 80/20 into an optimized portfolio or a user commitment. No ETF purchases, paid subscriptions, broker account connections, or public deployment are part of this specification.

## 4. Scope boundaries

### V1 includes

- Instrument catalog with stable IDs and listing details.
- Immutable historical dataset versions, CSV import, validation, and provenance.
- Versioned universes and deterministic strategy configurations.
- Three reference strategies specified in section 8.
- Historical backtests and equivalent benchmark runs after modeled trading costs.
- Separate prospective paper portfolios and a persistent signal / proposal history.
- Comparison charts and exports of results, trades, and run metadata.
- AI explanations grounded in stored analysis results.
- Migration of the existing demo to an explicitly labeled legacy portfolio context.

### Later extensions

- Individual-stock momentum, quality, value, and combined factor screens, with historical universe membership and publication-time fundamentals.
- Composite allocations such as a global ETF core plus a momentum sleeve.
- Multi-currency cash, historical FX conversion, and FX execution costs.
- Additional provider APIs, automated daily imports, and configurable notifications.
- German tax scenarios, broker imports, and reconciliation with real accounts.
- Optionscheine, exchange-traded options, and turbo certificates, each with its own instrument and pricing model.
- Moderately leveraged portfolio experiments with explicit financing and risk assumptions.
- Remote access, multi-user permissions, and optional broker execution.

V1 MUST NOT simulate a warrant or turbo by multiplying an ETF return. The current generic share model cannot represent expiration, volatility sensitivity, barrier monitoring, issuer events, or financing adjustments correctly.

## 5. Reuse and architecture

### 5.1 Existing assets to preserve

Reuse the Angular layout, watchlist, positions table, charts where suitable, SSE connection service, configurable LLM client, Spring MVC setup, Docker build, startup scripts, and useful tests. Reuse provider HTTP integration where it fits the new contracts.

Existing limitations documented in the source files:

- `MarketDataSource` and the cache primarily serve current prices.
- The frontend retains only a short rolling price buffer; it is not a historical database.
- Position uniqueness is currently based on user and ticker, not portfolio and instrument.
- Money and quantities are documented as SQLite `REAL` values.
- Demo market orders fill immediately at the current price without costs.
- Chat instructions can execute paper trades immediately.
- Portfolio snapshot cadence differs between the plan and backend documentation.

The audit must verify these points against code rather than treating the documentation as a passing implementation review.

### 5.2 Modular monolith

Keep one Spring Boot application with explicit package boundaries. No mandatory Python service, microservice split, message broker, or reactive-stack rewrite.

| Module | Responsibility |
|---|---|
| `market` | Existing current quotes and SSE; explicit provenance and freshness |
| `instruments` | Instruments, listings, trading calendars, provider identifiers |
| `history` | Imports, raw bars, corporate actions, datasets, quality checks |
| `strategy` | Immutable definitions, ranking, signals, target weights |
| `backtest` | Historical clock, execution simulation, runs, job lifecycle |
| `portfolio` | Portfolio identity, accounting ledger, positions, paper execution |
| `analytics` | Returns, costs, drawdowns, benchmarks, comparisons |
| `chat` | Existing LLM integration plus read-only research tools and proposal creation |
| `db` / `system` | Migrations, backups, health, background job supervision |

Dependencies flow from API/application orchestration to domain services and then to infrastructure interfaces. The strategy and accounting core MUST NOT fetch HTTP data, query wall-clock time, call an LLM, or depend on a Spring controller. Supply an explicit evaluation clock and an as-of data view.

Keep `MarketDataSource` for quotes. Introduce separate historical access and import contracts; do not overload the latest-price cache with backtesting responsibilities. Backtests and paper tracking reuse the same strategy implementation. Historical and prospective fill sources are separate adapters.

Preserve the actual working dependency versions initially. Inspect Gradle and frontend lockfiles in M0; evaluate supported-version updates separately. Version ranges in the supplied architecture documents are not an instruction to upgrade blindly.

## 6. Persistence and migration

### 6.1 Core records

Exact table names may follow repository conventions, but the following concepts and relationships are required:

| Record | Required information |
|---|---|
| `Instrument` / `Listing` | Stable internal IDs, instrument type, name, ISIN where available, venue, ticker aliases, currency, calendar, inception / termination dates |
| `DatasetVersion` | Source, imported-at timestamp, checksum, parser version, coverage, license / usage notes, quality findings, data classification |
| `DailyBar` | Dataset and listing IDs, session date, UTC open / close times, raw OHLC, optional volume, availability timestamp and adjustment convention |
| `CorporateAction` | Dataset and instrument/listing IDs, action type, effective / ex-date, pay date when applicable, split ratio or distribution amount and currency, source / availability |
| `UniverseVersion` | Immutable membership, effective dates, rationale, selection date, provenance and hindsight limitations |
| `StrategyVersion` | Strategy identifier, algorithm version, validated parameters, universe reference, benchmark reference, decision calendar |
| `BacktestRun` | Frozen configuration and datasets, requested/effective dates, engine version and code commit, cost model, status, quality labels, results |
| `Portfolio` | Owner, name, mode, base currency, initial cash, associated strategy version and paper start date |
| `LedgerEntry` | Immutable cash / security movement, portfolio, instrument when relevant, business time, quantity/amount, cause and idempotency key |
| `Position` | Rebuildable projection of units and average acquisition cost per portfolio/listing |
| `Signal` / `Proposal` | Evaluation time, data cutoff, inputs/ranks, target weights, reason, strategy version, lifecycle status |
| `Execution` | Proposal/order reference, model, fill time, reference price, fill price, units, spread/slippage, fee |
| `Valuation` | Portfolio/run, timestamp, cash, positions value, receivables, equity, data-quality state |

Backtest positions, paper portfolios, and the legacy demo MUST be isolated. Every write carries the relevant run or portfolio identity. A live SSE quote must never alter a completed backtest or historical equity curve.

### 6.2 Numeric and transactional rules

- Use Java `BigDecimal` for amounts, prices, fees, quantities, and ledger calculations; construct from decimal strings.
- SQLite financial fields use canonical decimal `TEXT`, with arithmetic in the domain layer. Do not cast to `REAL` for financial aggregation. A future PostgreSQL adapter can use `NUMERIC`.
- Research REST contracts encode financial decimals as strings. Angular may convert to `number` for chart coordinates and display approximations; authoritative accounting stays server-side.
- Version the rounding policy: internal arithmetic uses DECIMAL128; executable prices support 8 decimals; EUR cash bookings use 2 decimals with HALF_EVEN. Fees are rounded once per fill. Record any rounding residual explicitly when needed for reconciliation.
- V1 uses whole-share execution. Splits may create fractional holdings; retain them exactly and permit liquidation of the full residual under a labeled simulated fractional-fill assumption.
- Use weighted average acquisition cost including buy commissions. A sale realizes net sale proceeds after its commission minus the disposed units' acquisition cost. A split changes units and per-unit cost inversely; ordinary distributions are separate income. This is research accounting, not a German tax-lot calculation.
- Execute an order batch, its ledger entries, cash update, position update, and status changes in one database transaction. No negative cash or unauthorized short positions.
- Add idempotency keys and uniqueness constraints. In-memory locks alone are insufficient for durable consistency.
- Jobs use a bounded queue; serialize database mutation where necessary for SQLite. No unbounded parallel imports or backtest writers.

### 6.3 Migration approach

1. Record the current repository commit and preserve a functioning demo release tag when implementing.
2. Back up the existing SQLite file before the first schema migration.
3. Add versioned migrations using one migration mechanism; choose and record the tool in M0.
4. Map existing balances, trades, positions, snapshots, and chat actions to a `LEGACY_DEMO` portfolio. Preserve existing records and timestamps.
5. Convert floating-point values deterministically using a documented rounding policy. Conversion cannot recover precision already lost. Produce a migration reconciliation report.
6. If old history cannot reconstruct the current state, preserve it and record an explicit opening balance / migration adjustment; do not silently fabricate missing trades.
7. Create new research portfolios separately. Do not treat the old USD demo portfolio as EUR by changing its label.
8. Verify fresh initialization, upgrade, repeat startup without duplicate seeding, and restoration from the backup.

The old endpoints may remain as adapters for the legacy demo. New research APIs must not implicitly select the default portfolio.

## 7. Historical data contract and quality

### 7.1 Import bundle

V1 supports a local ZIP bundle containing only the following allowlisted files, or the equivalent individual uploads. The importer validates paths and sizes before extraction.

| File | Minimum fields |
|---|---|
| `manifest.json` | Schema version, source, retrieval date, license/use note, `SYNTHETIC` or `HISTORICAL`, adjustment convention, coverage and known limitations |
| `instruments.csv` | Instrument/listing IDs, name, ISIN if known, ticker, venue, currency, calendar ID, inception date, termination date if known |
| `sessions.csv` | Calendar ID, session date, UTC open and close times, session kind |
| `prices.csv` | Listing ID, session date, raw open/high/low/close, optional volume, `available_at` |
| `actions.csv` | Listing ID, action type, effective/ex-date, pay date, split ratio or distribution per post-split unit, currency, `available_at` |

`actions.csv` can be empty only with an explicit completeness assertion for the period, for example an accumulating ETF with no split in that window. A missing file is not evidence of no actions.

Prices used for execution MUST be unadjusted tradable price series. Vendor-adjusted closes may be retained as a separately identified diagnostic series, never substituted silently for execution prices. Portfolio cash flows and a vendor total-return series must not both count the same dividend.

The importer validates unique keys, positive prices, OHLC consistency, currency, dates, calendar coverage, action ratios, pay dates, availability metadata, and discontinuities around splits. Re-importing identical bytes is idempotent; corrected bytes create a new dataset version. Previous runs retain their original data references.

### 7.2 Knowledge-time and source limitations

Distinguish a market observation's effective date, when it was available, and when this application imported it. Importing data today does not make every historical observation unavailable until today; equally, a historical session timestamp does not prove the absence of later revisions.

If original publication/vintage data is unavailable, record the documented availability assumption and label results `REVISED_HISTORY`. Do not describe these runs as a fully reconstructed point-in-time experiment. Artificial fixtures are always `SYNTHETIC` and are excluded from investment evidence summaries.

For a fixed ETF universe selected today, historical results are exploratory unless its past membership and selection policy are supported independently. Display `RETROSPECTIVE_UNIVERSE` as appropriate. Do not backfill ETF returns before inception or substitute an index without identifying a separate proxy analysis.

### 7.3 Missing data and scope

- V1 requires the research universe and benchmark to use the same specified trading calendar and EUR quote/distribution currency. Other configurations return a clear unsupported-configuration error.
- A holiday is not a missing quote. A missing price on an expected session is a quality error.
- Require enough history for strategy warm-up. Never shorten the lookback silently.
- On a missing required open, close, or corporate action, the strict V1 backtest fails with affected instruments and dates. Do not fabricate fills or omit a held delisted instrument.
- Forward filling may support a visibly stale display; it cannot produce a new trading signal or simulated execution.
- Requesting more history than exists returns the valid period and the reason. The user can select a shorter period; the application does not silently change it.
- Total fund returns already reflect expenses embedded in NAV/market prices. Do not deduct the stated TER again from observed ETF returns. Model additional brokerage and execution costs separately.

Provider selection is a separate implementation decision. Check venue/ETF coverage, raw/adjusted conventions, distributions, calendars, data revisions, storage/use rights, request limits, and budget. An existing Massive adapter does not demonstrate sufficient coverage for European ETF research.

## 8. Reference strategy definitions

All defaults below are research hypotheses, not claims of optimal investment parameters. No parameter is to be optimized and then silently presented as an independently validated choice.

Let `T_i(d)` be the cumulative total-return signal index for listing `i` at session close, derived consistently from raw closes and corporate actions up to that cutoff. Initialize it arbitrarily to 100. For an ordinary cash distribution per post-split unit `D_d` and split multiplier `s_d` (new units per old unit):

`T_i(d) = T_i(d-1) × s_d × (close_i(d) + D_d) / close_i(d-1)`.

Use `s_d = 1` and `D_d = 0` when there is no action. This index assumes theoretical reinvestment at ex-date close for signal measurement. It is distinct from portfolio accounting, where distribution cash becomes spendable on the payment date. Unsupported special actions fail validation.

### S1 — Global ETF buy-and-hold (`ETF_BUY_HOLD_V1`)

- Exactly one selected unleveraged global equity ETF/listing.
- At the initial evaluation cutoff, target weight is 100% in that ETF.
- Execute at the next eligible session open using section 9.
- No later strategic selling or scheduled rebalancing.
- Reinvest paid distribution cash at the next eligible open if at least one unit and its modeled costs are affordable. Small residual cash remains cash.
- No shorting or borrowing; the same execution model applies to the benchmark portfolio.

### S2 — Monthly ETF momentum (`ETF_MOMENTUM_12_1_V1`)

- Use an immutable, explicitly defined ETF universe.
- Evaluate after the final completed session of each calendar month.
- For month-end `m`, calculate `score_i(m) = T_i(m-1) / T_i(m-12) - 1`, using the last trading session of the respective months. This is a 12–1 signal: it excludes the most recent month and measures approximately 11 months of returns.
- Require both observations and a valid continuous history between them. This version requires the entire configured universe to satisfy eligibility; it must not shrink opportunistically around missing data.
- Rank descending by score; break exact ties by stable listing ID ascending.
- Select the top `K`, default `K = 3`; the universe must have at least `K` eligible listings.
- Target equal weights `1/K` across selected listings, zero across other holdings. Rebalance at the next eligible open.
- Negative scores do not automatically trigger cash. Absolute trend filtering is a separate strategy; do not introduce it implicitly.
- Fees, integer units, and cash constraints create actual weights different from targets; report both.

This strategy is **ETF rotation**, not a replication of the MSCI World Momentum stock index. The application must not label its results as that index's results.

### S3 — Global ETF trend filter (`ETF_TREND_10M_V1`)

- Apply to the same global ETF as S1.
- At month-end `m`, calculate the arithmetic mean of the 10 monthly total-return index values ending at `m`.
- If `T(m) > SMA10(m)`, target 100% ETF. If `T(m) <= SMA10(m)`, target 100% cash.
- Execute at the next eligible open. Stay in the current allocation between monthly decisions, except for reinvestment of paid distributions while the target remains ETF.
- Default cash interest is zero, displayed in results. A later cash-return series is a separate versioned assumption; zero-interest cash must not be called a money-market ETF.

### Shared strategy contract

Input: strategy version, universe version, explicit evaluation timestamp, as-of data view, and portfolio context where needed. Output: immutable signal with input references, computed values, eligibility/ranks, target weights, and machine-readable reason codes.

Evaluate the initial allocation at the chosen start month-end after warm-up, then follow the strategy schedule. The run's initial funding point is immediately before the first subsequent execution session. Warm-up history is excluded from reported returns. Benchmark and candidate receive identical funding timing and reporting windows.

## 9. Backtest and execution semantics

### 9.1 Historical engine

- Runs operate on frozen datasets and configuration. No downloads, LLM responses, or current quote cache values inside a run.
- At decision time, only fully completed and available observations can enter strategy evaluation. Full-day OHLC records may exist in the dataset, but an open-execution event may access the open only; high, low, and close become visible at their designated event times.
- Signals computed from a month-end close execute no earlier than the next session open. Same-close fills are prohibited for these strategies.
- Daily order: apply splits and establish ex-date entitlements; pay due distributions; execute scheduled orders; value holdings/receivables at close; evaluate any month-end strategy signal.
- Apply each event only when its required information is available to that event handler. If a distribution has only a payment date and no payment time, conservatively make cash available after that session's close; otherwise respect the supplied payment timestamp. Reinvestment uses the first open after cash availability. Do not assume date-only payments are available before the opening auction.
- On ex-date, entitlement belongs to holdings from before that session's trading, adjusted for same-day splits. A distribution creates a receivable included in equity but unavailable for buying until payment. On payment, replace the receivable with cash without recognizing the distribution twice.
- Trades are modeled on a trade-date basis with immediately reusable sale proceeds. Settlement delays, market impact, liquidity constraints, and actual broker fractional-share support are excluded and disclosed.
- No forced sale at the end of a run. Show mark-to-market ending equity, and identify that final liquidation costs have not been charged.
- A pending final signal with no next session in the test window is recorded as unexecuted.

### 9.2 Cost model and order sizing

Default illustrative settings, configurable and stored per run:

- Fixed commission: EUR 1.00 per nonzero fill.
- Assumed full bid/ask spread: 10 basis points; one side incurs 5 basis points.
- Additional adverse slippage: 5 basis points per side.
- Cash interest: zero.
- No personal tax deductions.

These are scenario inputs, not verified prices for a particular broker or ETF. Display sensitivity comparisons with higher trading costs before interpreting an apparent advantage.

For raw opening reference price `P`, full spread fraction `s`, and slippage fraction `l`:

- Buy fill price: `P × (1 + s/2 + l)`.
- Sell fill price: `P × (1 - s/2 - l)`.
- Commission is a separate cash ledger entry; spread/slippage is already embedded in fill price and must not be deducted twice.

The signal fixes target weights, not exact units. At the next open, the execution adapter sizes a hypothetical target-weight batch using available opening prices and pre-trade equity. This is a disclosed sizing approximation, not a claim that a real pre-submitted market order can know its opening fill price.

Compute target whole units as `floor(weight × pre-trade equity / reference open)`. Sell excess holdings first, including a split-created fractional residual when needed to reach that target. Buy increments are always floored to whole units. For buys, use one deterministic cash allocator: calculate desired whole-unit increments, include per-order fees, and, if unaffordable, proportionally scale buy quantities down and floor them; then remove further units in listing-ID order until the batch is affordable. Do not redistribute unused cash during that batch. Skip zero-unit orders and do not charge a fee for them. Persist requested versus executed quantities and any cash shortfall.

### 9.3 Jobs and repeatability

Backtest states: `QUEUED`, `RUNNING`, `COMPLETED`, `FAILED`, `CANCELLED`, `INTERRUPTED`. Return a job ID immediately; poll or push progress through a separate research channel. Limit the initial implementation to one active calculation job and a bounded queue.

Cancellation must stop at a defined processing boundary. Partial output is not a completed result. On restart, identify interrupted jobs; never mark them successful. Repeating the same dataset/configuration/engine must produce identical financial results and business event order, apart from operational IDs and timestamps.

## 10. Analytics and interpretation

V1 historical runs use a single starting contribution and no external cash flows. Record contributions/withdrawals in the ledger model, but reject them for V1 historical comparisons; cash-flow-aware performance metrics are a later extension.

Required output:

- Starting and ending equity, cumulative return, annualized return, and yearly return table.
- CAGR: `(ending equity / starting equity)^(365.25 / elapsed calendar days) - 1`. For periods under a year, display cumulative return and suppress CAGR; do not annualize a few weeks as if it were a stable expected return.
- Daily equity and drawdown series; maximum drawdown measured against the running peak.
- Time from peak to trough, trough to recovery, and total underwater duration. If unrecovered, label it ongoing instead of treating the end date as recovery.
- Daily return volatility using sample standard deviation and a disclosed annualization convention (default 252).
- Turnover, number of fills, modeled commissions, and separately estimated spread/slippage costs.
- Benchmark return and return difference, exposure / cash weight, holdings, and realized/unrealized gains.
- For at least five years of valid history: monthly-start rolling five-year compounded returns and their historical positive-window share. Show window count and overlap; this is not a forecast probability or a set of independent trials.

The dashboard MUST separate gross signal behavior from the net simulated portfolio. Its primary comparison is after modeled trading costs and before personal taxes. Short samples, synthetic fixtures, retrospective universes, revised-history assumptions, and incomplete periods remain visible in charts and exports.

Use development and holdout periods with parameters frozen before reviewing the holdout. Store the split and whether the holdout has been examined. Changes after examination create a new experiment and retain the old record. A chronologically later period already inspected by the researcher is not genuinely unseen.

No automated search for the most impressive backtest in V1. Scenario comparison records every tried configuration. Do not infer future success from 100–200 correlated trades or from resampling trades as if they were independent.

## 11. Prospective paper portfolios

Each paper portfolio records its creation time, strategy version, initial cash, and current dataset references. Historical backtests do not populate a paper portfolio's earlier track record.

Generate a paper proposal only when the latest required completed session is available and valid. A stale or failed import produces a visible blocked signal; it must not fall back to random simulator prices. Imported corrections do not rewrite completed paper decisions.

Initial mode is explicit proposal acceptance. Before a future execution session, acceptance records intent; a later imported actual open can resolve the modeled fill. If the user accepts after that open, the application must not award that historical price: schedule the next eligible future session instead, with a fresh sizing context. Record decision, acceptance, scheduled execution, and fill observation times separately.

Optional `AUTO_PAPER` mode can execute a fixed, explicitly enabled deterministic strategy. Persist intent before the execution session; after downtime do not fabricate past intent. Missed signals are marked missed and can schedule a future session. A restart or duplicate import cannot execute an order twice.

The old chat-driven demo remains confined to the legacy demo portfolio. Research paper accounts cannot be changed by an unscoped legacy endpoint or a free-text LLM instruction.

## 12. AI assistant

Reuse `LlmClient` and the chat UI, with research-specific tool contracts. Initial tools can retrieve a run summary, holdings, drawdown events, signal inputs, and comparison results.

- Numerical statements must refer to returned results; the assistant does not calculate authoritative NAV, rankings, or performance independently.
- Explanations identify the strategy/run and data cutoff in user-facing form.
- Missing data yields an explicit limitation, not invented prices, ISINs, news, or results.
- The assistant may propose a new research configuration. Backend validation determines whether it is executable; it does not overwrite a strategy version.
- Paper trade proposals require the execution path in section 11. Schema-valid JSON is not sufficient authorization to change a research portfolio.
- The core application works when the LLM is unavailable. AI errors must not roll back completed financial calculations or mutate their records.
- Send only the selected research context needed for the request; API keys remain server-side. Do not automatically forward unrelated portfolio or chat data.

## 13. Frontend and API

### 13.1 Screens

| Route | User task |
|---|---|
| `/research` | Overview of dataset status, latest signals, selected comparison, and paper portfolios |
| `/research/data` | Import, coverage matrix, provenance, validation findings, dataset versions |
| `/research/strategies` | Read rules, inspect parameter versions, view ranks and signal reasons |
| `/research/backtests` | Configure and start a run; view progress, cancellation and failures |
| `/research/backtests/:id` | Equity/benchmark chart, drawdowns, yearly returns, trades and assumptions |
| `/research/compare` | Compare completed runs with matching periods and assumptions |
| `/research/portfolios/:id` | Positions, cash, receivables, proposal acceptance and paper history |
| Existing demo route | Existing terminal, visibly labeled as demo |

Reuse presentational components, but introduce history APIs instead of extending the 60-point client buffer. Chart tooltips must show dates, currency, values, and relevant source labels. All screens support loading, empty, failed, stale, and insufficient-history states. Preserve desktop usability and basic tablet layout.

Separate feature services for research state. `PriceStreamService` must not refresh backtest valuations. The chat panel receives an explicit selected portfolio/run context.

### 13.2 Proposed API surface

Use `/api/research` to avoid changing existing demo contracts implicitly.

| Method | Path suffix | Purpose |
|---|---|---|
| POST | `/imports` | Upload/import a dataset bundle; return job ID |
| GET | `/datasets` and `/datasets/{id}` | Inspect immutable versions and quality reports |
| GET | `/instruments` | Search listings by name, ISIN, symbol and venue |
| GET | `/history/{listingId}` | Date-ranged series with dataset and adjustment identity |
| POST / GET | `/universes` | Create and list immutable universe versions |
| POST / GET | `/strategies` | Create and list strategy versions |
| POST / GET | `/backtests` | Start or list runs |
| GET | `/backtests/{id}` | State, metadata and summary |
| POST | `/backtests/{id}/cancel` | Request cancellation |
| GET | `/backtests/{id}/equity`, `/signals`, `/executions` | Paginated / range-filtered result resources |
| POST | `/comparisons` | Validate and create a comparison from run IDs |
| POST / GET | `/portfolios` | Create and list paper portfolios |
| GET | `/portfolios/{id}` | Balances, positions and strategy status |
| POST | `/portfolios/{id}/evaluations` | Evaluate latest eligible cutoff; idempotent |
| GET | `/portfolios/{id}/proposals` | Proposal lifecycle and reasons |
| POST | `/proposals/{id}/accept` or `/reject` | Record the user's paper decision |
| GET | `/jobs/{id}` | Import/evaluation job progress and errors |
| GET | `/backtests/{id}/export` | ZIP of result CSVs and manifest |

In the result-resources row, `/signals` and `/executions` are siblings under `/backtests/{id}`. Do not interpret them as global unscoped endpoints.

Requests reference immutable IDs rather than unnamed mutable defaults. Validation errors include a stable code and actionable detail. Mutating operations accept idempotency keys. Completed runs are read-only; deletion is not required for V1.

Export manifests contain configuration, dataset checksums, calendar, assumptions, strategy/engine versions, commit ID, classification labels, and effective dates. Export CSVs use consistent UTF-8/date/decimal formats and safe text-cell handling for spreadsheet users.

## 14. Operation and access

- Default startup exposes the application on host loopback only, for example Docker port binding `127.0.0.1:8000:8000`. Do not carry a publicly reachable no-login demo into the research product.
- Validate write-request origin, disable wildcard CORS, and protect browser mutations appropriately even in local mode. Remote access/authentication requires its own design before exposure.
- Keep secrets in existing external configuration; no credentials in datasets, exports, logs, prompts, or source control.
- Store imports and results in the persistent data volume, with size limits and a documented backup/restore procedure. Dataset raw bytes or canonical normalized rows must remain available for repeatability.
- On startup distinguish the configured source and mode. A missing market API key may select the existing simulator for the demo only; research remains unconfigured/blocked until real data or explicit fixtures are selected.
- Retain live demo snapshots if desired, but persist research valuations once per session and after relevant accounting events. Do not extend five-second snapshots across decades.
- Record import/job progress, last successful data session, and actionable errors. App health and data freshness are separate indicators.
- Initial performance goal: a 20-ETF, 20-year daily-data run completes within 60 seconds on the owner's development machine. This is a target to measure and document, not a verified current capability; optimize only after a representative measurement.

## 15. Meaningful acceptance tests

Tests use small independently calculated fixtures with transparent expected values, plus integration tests for durability. Profitability is never a test gate.

| ID | Required evidence |
|---|---|
| A01 | Existing demo data survives upgrade; repeat startup does not seed duplicate cash or holdings |
| A02 | Decimal ledger reconciliation remains exact through repeated purchases, fees, sales and splits |
| A03 | A 2-for-1 split halves a constant-value raw price, doubles units and leaves portfolio equity and signal return unchanged |
| A04 | Ex-date distribution creates the correct receivable; payment converts it to cash; total return is not counted twice |
| A05 | Month-end close signal executes at next open, including a weekend/holiday transition |
| A06 | Changing future closes/highs/lows or future corporate actions cannot change an earlier signal or opening fill |
| A07 | Momentum uses the exact 12–1 months, deterministic ties, top-K and equal targets; insufficient history is rejected |
| A08 | Trend equality selects cash; an ordinary positive comparison selects the ETF |
| A09 | Costs, integer sizing, zero-quantity suppression and insufficient cash match independently computed examples |
| A10 | Missing expected-session prices block the run; a declared holiday does not; no fabricated delisting exit |
| A11 | Identical completed run inputs yield identical financial results; corrected import does not rewrite old results |
| A12 | Benchmark and candidate use identical effective dates and initial funding; comparisons reject mismatches |
| A13 | Repeated requests/imports/restarts cannot double-apply paper orders or cash movements |
| A14 | An exception during execution rolls back all ledger, cash, position and status writes |
| A15 | Late paper acceptance cannot obtain a historical fill; no prior intent is invented after downtime |
| A16 | Research data survives LLM failure, and a chat trade instruction cannot modify an unintended portfolio |
| A17 | Equity/drawdown charts, stale/error states and export assumptions are verified through frontend/E2E flows |
| A18 | Failed/cancelled/interrupted jobs cannot appear as completed; imported dataset versions remain immutable |

Use the repository's actual backend and frontend test runners; the supplied documents name different frontend runners. Record commands, tested commit, actual results, and any missing tests in each milestone report.

## 16. Incremental delivery milestones

### M0 — Repository audit and extension baseline

Inspect actual code, dependencies, schema initialization, transactions, money handling, endpoints, tests, and Docker binding. Reconcile architecture documents with the implementation, including snapshot intervals and LLM execution behavior.

Deliver `planning/reports/research-M0.md`: what can be reused, concrete defects/blockers, proposed migration mechanism, exact schema/transaction plan, and reproducible build/test results. Record draft decisions adopted for implementation. Do not rewrite the application during the audit.

### M1 — Portfolio identity and accounting foundation

Introduce versioned migrations, exact decimals, portfolio modes/IDs, ledger, transaction and idempotency boundaries. Migrate the legacy demo with reconciliation and backup restoration evidence. Add instrument/listing identity and research API foundations.

Exit: legacy demo still works; two paper portfolios are isolated; tests A01/A02/A13/A14 pass for this foundation. The new paper accounts need not yet trade strategies.

### M2 — Historical import and data inspection

Implement the file contract, immutable dataset versions, calendars, action handling, validations, provenance and the data screen. Supply explicitly synthetic developer fixtures and an example import guide. Actual provider purchase is not a prerequisite.

Exit: complete valid dataset imports reproducibly; bad/missing data is visible; corporate-action and history invariants are demonstrated. Real-data conclusions remain blocked until D2/D3 are resolved.

### M3 — Backtest engine and baseline

Implement the historical clock, next-open execution, cost model, job lifecycle, S1, benchmark accounting and core analytics. Add a basic run-detail chart and result export.

Exit: a hand-calculated baseline example reconciles from initial cash to final equity; lookahead, cost and missing-data tests pass. No AI dependency.

### M4 — Momentum, trend filter and comparison

Implement S2/S3 using the same core, immutable parameter versions, rankings/reasons, comparisons, rolling windows and holdout metadata. Add strategy configuration and comparative equity/drawdown views.

Exit: all three strategies can be compared under matched assumptions; differences are traceable to signals, executions and costs. Validation includes parameter and cost sensitivity without picking a favorable result as the default.

### M5 — Prospective paper tracking and AI explanations

Implement signal/proposal lifecycle, user acceptance, optional explicit `AUTO_PAPER`, interrupted-run behavior and AI read tools. Manual daily file import is sufficient for this milestone; unattended tracking requires a separately configured data source.

Exit: prospective decisions cannot be backdated, duplicate fills are prevented, and explanations link to actual outputs. Relevant paper/LLM tests pass.

### M6 — Usable V1 and evidence review

Resolve the real-data universe/source choices, load available history, run the three strategies, document coverage and holdout limitations, complete E2E flows, measure runtime, and verify restore/startup. Update architecture/user documentation to match the implementation.

Exit: all applicable acceptance tests pass; the owner can import, compare, inspect and track without developer intervention. Report actual findings even if momentum or trend filtering underperforms buy-and-hold. If data access is unresolved, deliver a technically complete demo explicitly distinguished from a validated research release.

Every milestone report states changes, commit, validation, known limitations, and remaining decisions. Implement one milestone at a time when given a milestone-scoped task. Do not expand into derivatives or a framework rewrite to satisfy a local acceptance criterion.

## 17. What to decide before calling this specification final

The highest-impact choices are:

1. **Product scope:** accept ETF-based comparison as V1, or include individual-stock selection immediately (with substantially stronger historical data requirements).
2. **Operating mode:** local-only private use, or remote access from the start.
3. **Data budget and universe:** desired markets/listings, source coverage, and acceptable recurring cost.

The technical defaults can support an initial audit and incremental implementation. Investment horizon, acceptable drawdown, provider choice, and portfolio allocation are not inferred from a generic willingness to tolerate fluctuations.

## 18. Source documents and authority

- `PLAN-java.md`: original Java/Angular demo product specification supplied by the user.
- `backend-architecture.md`: supplied description of current backend packages, schema, market simulation, portfolio execution, and LLM integration.
- `frontend-architecture.md`: supplied description of Angular components, services, contracts, and tests.
- Visible project discussion: extend the demo for long-term strategy research, with global ETF / momentum / trend comparisons before derivative extensions.

The sources describe the starting point; this draft proposes the extension. Once adopted, keep the original demo plan as historical context and record which requirements this document supersedes for research mode. Repository evidence determines what is actually implemented. User decisions override draft defaults and should be reflected in a new specification version.
