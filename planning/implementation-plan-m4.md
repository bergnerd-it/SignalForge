# Implementation Plan — Milestone M4: ETF Momentum, Trend Filter and Strategy Comparison

Implement Milestone M4 in accordance with [the M4 prompt](PROMPT-SIGNALFORGE-M4.md), [research specification](FINALLY-RESEARCH-SPEC-v0.1.md), and [M0 addendum](SIGNALFORGE-SPEC-v0.2-M0-ADDENDUM.md). Where conventions differ, the M4 prompt controls. This plan is not evidence that M4 has been implemented or verified.

## Implementation-model review comments

These comments are instructions for the implementing model. Resolve each against the M4 prompt and current code before treating a proposed class or migration as a fixed design.

1. **Checkpoint before code.** Read the latest `reports/research-M3-fixes.md` and inspect the actual checkout. Its 161 backend/39 frontend counts, V7 and source identity describe a prior tested tree, not guaranteed current results. Preserve tracked and untracked M3 files in a local checkpoint or exact manifest before implementation. Use disposable SQLite databases only; leave old migrations, dataset rows and completed runs untouched. `planning/PLAN-java.md`, mentioned by `AGENTS.md`, is absent from this checkout.
2. **Migration is conditional, not a prescribed table rebuild.** V8 is the next version *if* V7 is still current. Inspect existing constraints, indexes, foreign keys and terminal triggers before rebuilding `backtest_runs` or `backtest_orders`. Ensure immutable universe/strategy versions, signals and comparisons have owner scoping, relational guards and insert/update/delete protections. Keep old runs readable with genuinely absent M4 metadata; do not synthesize it.
3. **Freeze complete run identity.** A strategy version needs identifier, supported parameters, fixed universe/reference listing, schedule, calculation-policy version and S2 distribution convention. A run also freezes M3 configuration, dataset checksums, cutoff/availability policy, execution/accounting/engine versions, assumptions and source/build fingerprint. Distinct K or cost settings create new versions or experiments; the 12–1 and SMA10 rules cannot silently change under V1 IDs.
4. **Correct timing and warm-up before engine work.** The first selected *completed* month-end is the evaluation boundary; candidate and benchmark fund just before the same first eligible later open, even if S3 selects cash. Validate every universe member and benchmark over full required intervals, including continuous daily observations and corporate-action obligations. Do not hard-code “13 months before start” or “10 months before start”: exact required endpoints depend on the selected evaluation month and prior index observation. Late input availability can delay execution past the immediate next open, never backdate it. Preserve earlier pending signals in order and persist a final unexecuted signal.
5. **Keep signal math separate from accounting.** Use raw close and supported split/distribution actions to build the total-return index; missing actions mean zero only when declared source completeness permits. Define same-date ordering, BigDecimal precision and availability. Rank on unrounded scores with stable listing-ID ties and retain rational `1/K` sizing. Theoretical ex-date index reinvestment is not portfolio cash: entitlements become receivables and only payable cash can fund a buy. Opening strategy/execution code must not read future high/low/close or actions.
6. **Specify the affordability allocator.** The draft names proportional scaling but no formula. Document exact per-order fee treatment, rounding, stable listing-ID unit removal and fee recalculation when an order becomes zero, then test the formula independently. Sell excess and split fractions first, remove proportional basis and clear it on full disposal. Receivables count in equity but are not spendable. Fail a costly sale that cannot be booked without negative cash; never waive costs. Persist target/requested/executed quantities, gains, fees, spread/slippage and cash shortfall. One same-open S3 allocation/reinvestment batch yields at most one net trade per listing.
7. **Match every financial assumption.** Comparison compatibility includes effective funding *instant*, reporting sessions and end, initial cash, currency/calendar, underlying dataset snapshot(s), benchmark listing/accounting assumptions, costs, execution/accounting model and compatible engine version. Strategy and intended universe may differ. Reject by field; do not shorten overlap or rescale. Re-run S1 in the current engine if an old M3 run is incompatible. Compare complete stored data, not frontend chart slices; distinguish money from percentage-point differences and do not invent gross performance by adding fees back.
8. **Define rolling and holdout boundaries exactly.** A five-year window starts from equity immediately before a month's first session (prior session close, or initial funded point if applicable) and ends at the last declared session on/before the fifth anniversary only when the calendar covers the anniversary. Use identical boundaries across runs, expose dates and observation count, omit insufficient history, and label overlap and positive-window share as descriptive rather than predictive. Holdout begins before a named session; development ends at the previous close. Record declared already-examined/not-yet-examined/unknown status and continuous-portfolio treatment. Append an exposure event *before* any result retrieval or export exposing holdout data, including summary and comparison endpoints; an old result is never retrospectively “unseen.”
9. **Complete API/UI/export and proof, not just class creation.** Keep durable same-key replay after software changes, bounded queue/cancellation/publication, owner checks, typed DTOs, SQL binding and local-origin policy. Page every series and table; expose loaded/total when a chart hits its 50,000-point cap; suppress stale responses and clean up polling. Export full exact metadata/signals/comparison compatibility with bounded preparation and safe CSV text. Add independent hand calculations before assertions, production M2-import synthetic fixtures, focused backend/Vitest tests, migration upgrades, and a native disposable-browser walkthrough. Report actual evidence and tool versions, with Docker deferred. Do not implement M5 or publish.

## Proposed Changes

### 1. Database Schema & Migration Runner

#### [NEW] [V8__strategy_versions_and_comparisons.sql](../backend/src/main/resources/db/migration/V8__strategy_versions_and_comparisons.sql)
- Create `universes` and `universe_listings` with composite keys and immutability triggers.
- Create `strategy_versions` (seeding `ETF_BUY_HOLD_V1`, `ETF_MOMENTUM_12_1_V1`, `ETF_TREND_10M_V1`).
- Extend `backtest_runs` with frozen version references and M4 configuration while preserving old rows and nullable M4 fields; rebuild only if existing SQLite constraints require it.
- Extend `backtest_orders` for sales and rebalances, including requested versus executed quantities, only after inspecting the existing check constraints and preservation triggers.
- Create `backtest_signals` and `backtest_signal_items` for persisting auditable monthly signals.
- Create `backtest_comparisons` and `backtest_comparison_items` for owner-scoped run comparisons.
- Create `experiments` and `experiment_exposure_events` for holdout tracking and exposure audit logs.
- Preserve and test terminal insert/update/delete guards on runs and every result table, plus immutability and owner-scoping guards on versions, comparisons and experiments. Exposure events are append-only.

#### [MODIFY] [MigrationRunner.java](../backend/src/main/java/com/bergnerd/signalforge/app/db/migration/MigrationRunner.java)
- Advance `CURRENT_SCHEMA_VERSION` to the next free version after checking the actual checkout; change `CODE_VERSION` only under the repository's versioning convention.
- Register the follow-on migration for fresh, legacy and versioned paths while preserving prior migration definitions and checksums.
- Update `validateCurrentSchema()` to verify new tables, columns, unique constraints, and immutability triggers.

---

### 2. Backend Strategy & Engine Core

#### [NEW] [TotalReturnSignalIndexCalculator.java](../backend/src/main/java/com/bergnerd/signalforge/app/research/backtest/TotalReturnSignalIndexCalculator.java)
- Independent calculation of daily total-return index series $T_i(d)$:
  $T(d) = T(d-1) \times \text{splitMultiplier}(d) \times (\text{rawClose}(d) + \text{distributionPerPostSplitUnit}(d)) / \text{rawClose}(d-1)$.
- Extracts month-end observation sessions using the calendar's final declared trading session of each calendar month.
- Validate continuous daily history and the needed prior observation, action completeness and availability at the decision instant without lookahead. Document same-date action order and authoritative BigDecimal policy; signal-index income never posts to portfolio cash.

#### [NEW] [StrategyEvaluator.java](../backend/src/main/java/com/bergnerd/signalforge/app/research/backtest/StrategyEvaluator.java)
- Evaluates strategy signals at month-end sessions:
  - **S1**: Initial 100% target, zero subsequent strategic allocation changes.
  - **S2**: 12–1 score = $T(m-1)/T(m-12)-1$, descending sort, listing ID ascending tie-breaking, top $K$ selection, equal $1/K$ target weights. Auditable exclusion reasons for below-K listings.
  - **S3**: $SMA10(m) = \frac{1}{10}\sum_{j=0}^{9} T(m-j)$. $T(m) > SMA10(m) \implies 100\%\text{ ETF}$, else $100\%\text{ Cash}$. Auditable reason code (`TREND_ABOVE_SMA` vs `TREND_BELOW_EQUAL_SMA`).
- Produce immutable signal records including input references/values, eligibility, ranking, selection/exclusion reasons, rational target weights and scheduled/actual/blocked/unexecuted execution status. Rank unrounded scores.

#### [MODIFY] [BacktestDtos.java](../backend/src/main/java/com/bergnerd/signalforge/app/research/backtest/BacktestDtos.java)
- Add DTOs for `UniverseDto`, `UniverseListingDto`, `CreateUniverseRequest`.
- Add DTOs for `StrategyVersionDto`, `BacktestSignalDto`, `BacktestSignalItemDto`.
- Add DTOs for `BacktestComparisonDto`, `CreateComparisonRequest`, `ComparisonSummaryDto`, `RollingWindowDto`.
- Add DTOs for `ExperimentDto`, `CreateExperimentRequest`, `ExperimentExposureEventDto`.
- Extend `CreateBacktestRequest` and `BacktestNormalizedConfig` with `universeId`, `parameters` (e.g. `k`), `experimentId`.
- Update `OrderType` enum to include `REBALANCE_BUY` and `REBALANCE_SELL`.
- Update `HoldingsDto` to support multi-asset holding lists and cash/receivable breakdowns.

#### [MODIFY] [BacktestDataReader.java](../backend/src/main/java/com/bergnerd/signalforge/app/research/backtest/BacktestDataReader.java)
- Support loading universe listings, calendar sessions, daily bars, and corporate actions across all universe listings and benchmark.
- Preflight S2's exact `m-12`/`m-1` endpoints and S3's `m-9` through `m` endpoints relative to the selected evaluation month, including the prior daily index point and full action obligations; reject incomplete member or benchmark coverage.
- Require fixed membership, common declared calendar and EUR quote/distribution currency. Preflight may check future coverage but must not pass future financial values into strategy decisions or opening fills.

#### [MODIFY] [BacktestEngine.java](../backend/src/main/java/com/bergnerd/signalforge/app/research/backtest/BacktestEngine.java)
- Generalize simulation execution to support multi-asset target weights:
  1. Corporate actions (splits then entitlements) applied to each held position.
  2. Settle each payment only at its M3-defined availability instant; an intraday payment cannot fund an earlier opening trade.
  3. Pre-trade equity calculated from cash, receivables, and all held positions valued at raw opens.
  4. Sizing:
     - Target units = $\lfloor weight_i \times \text{preTradeEquity} / \text{rawOpen}_i \rfloor$.
     - Sell excess first: execute `REBALANCE_SELL` (or `ALLOCATION_SELL`) for listings where $current > target$, selling down whole and fractional units to target. Fill price with adverse spread/slippage, commissions deducted, proportional cost basis removed (100% basis cleared on full exit). Proceeds immediately spendable.
     - Buy desired whole increments: execute `REBALANCE_BUY` (or `INITIAL_BUY` / `REINVEST`). Document the proportional affordability formula including per-positive-order fees; scale quantities, remove units in stable listing-ID order until affordable, and recalculate fees when an order becomes zero. Never redistribute leftover cash.
  5. S3 churn suppression: if target remains ETF, preserve holdings without sell/rebuy churn. Reinvest distributions only when target is ETF. Merge allocation change and reinvestment scheduled for same open.
  6. Closing valuation across all held positions, calculating exact mark-to-market holdings value, total equity, drawdowns, and daily returns.
  7. Return complete `SimulationResult` containing multi-asset holdings, orders, events, daily equity and signals; publish the batch atomically. Persist requested/executed quantities, realized gain, costs, weights and shortfall. Receivables contribute to equity but are not spendable.

#### [MODIFY] [BacktestAnalyticsCalculator.java](../backend/src/main/java/com/bergnerd/signalforge/app/research/backtest/BacktestAnalyticsCalculator.java)
- Calculate multi-asset turnover, portfolio exposure weights, cash weights, and receivables weights.
- Implement rolling 5-year compounded-return windows:
  - Monthly start boundary using equity immediately prior to month's first session (or initial funded point).
  - End at the last declared session on or before the fifth anniversary of the starting session date, requiring calendar coverage through the anniversary; use identical boundaries across compared runs.
  - Return = $\text{endingEquity} / \text{startingEquity} - 1$.
  - Insufficient history handled explicitly with no padding or short annualization.
  - Positive-window share = count($\text{return} > 0$) / total complete windows; expose dates, observation counts, overlap and the non-predictive limitation.

#### [NEW] [ComparisonService.java](../backend/src/main/java/com/bergnerd/signalforge/app/research/backtest/ComparisonService.java)
- Create and persist owner-scoped comparisons.
- Perform strict field-by-field verification of funding instant, reporting sessions/end, initial cash, currency/calendar, underlying dataset snapshot(s), benchmark listing/accounting assumptions, costs and compatible execution/accounting/engine versions. Strategy and intended universe may differ.
- Generate comparison metrics: equity curves, drawdowns, annual returns, CAGRs, volatilities, turnover, costs, money vs percentage-point return differences, and rolling 5-year window comparisons.

#### [NEW] [ExperimentService.java](../backend/src/main/java/com/bergnerd/signalforge/app/research/backtest/ExperimentService.java)
- Manage immutable experiment configurations with the holdout boundary before a trading session, frozen identities, declared examined/not-yet-examined/unknown state and continuous-portfolio disclosure.
- Append holdout exposure *before* first result retrieval/export through this application, including summary and comparison endpoints. Preserve prior attempts and exposure history when parameters change.

#### [MODIFY] [BacktestJobService.java](../backend/src/main/java/com/bergnerd/signalforge/app/research/backtest/BacktestJobService.java)
- Support running S1, S2, and S3 strategy runs.
- Persist signals and multi-asset holdings in database transaction.
- Preserve idempotency, cancellation, bounded queue, and publication guarantees.

#### [MODIFY] [BacktestExportService.java](../backend/src/main/java/com/bergnerd/signalforge/app/research/backtest/BacktestExportService.java)
- Include signals CSV (`signals.csv`), multi-asset holdings CSV (`holdings.csv`), frozen strategy/universe metadata and exact decimal values in the bounded exported ZIP; protect untrusted text cells without corrupting negative numbers.
- Provide comparison export endpoint `/api/research/comparisons/{id}/export` streaming comparison manifest and comparative equity/metrics.

#### [MODIFY] [BacktestController.java](../backend/src/main/java/com/bergnerd/signalforge/app/research/backtest/BacktestController.java)
- Add endpoints:
  - `GET /api/research/strategies`: list strategy definitions and versions.
  - `GET /api/research/universes`, `POST /api/research/universes`, `GET /api/research/universes/{id}`: universe management.
  - `GET /api/research/backtests/{id}/signals`: paged signals and audit items.
  - `POST /api/research/comparisons`, `GET /api/research/comparisons`, `GET /api/research/comparisons/{id}`, `GET /api/research/comparisons/{id}/export`.
  - `POST /api/research/experiments`, `GET /api/research/experiments`, `GET /api/research/experiments/{id}`.

---

### 3. Frontend Strategies & Comparison UI

#### [MODIFY] [backtest.service.ts](../frontend/src/app/services/backtest.service.ts)
- Add client methods and strict TypeScript models for universes, strategies, signals, comparisons, and experiments.
- Support complete paged reads, disclosed 50,000-observation chart cap with loaded/total indication, stale-response protection, direct-link loading and polling cleanup.

#### [NEW] [research-strategies.component.ts](../frontend/src/app/components/research-strategies/research-strategies.component.ts) & HTML/CSS
- Route `/research/strategies`: View strategy specifications (S1, S2, S3), rules, parameters, universe definitions, and historical signal inspection.

#### [MODIFY] [research-backtests.component.ts](../frontend/src/app/components/research-backtests/research-backtests.component.ts) & HTML
- Extended configuration form supporting strategy selector (`S1`, `S2`, `S3`), universe selection for S2, $K$ parameter input, and warm-up validation messages.
- Signal audit tab showing evaluation session, SMA/scores, target weights, and execution status.
- Multi-asset holdings display.

#### [NEW] [research-compare.component.ts](../frontend/src/app/components/research-compare/research-compare.component.ts) & HTML/CSS
- Route `/research/compare`: Select 2 or 3 completed runs (e.g. S1 vs S2 vs S3).
- Display match validation check results or field-by-field mismatch errors.
- Comparative equity and drawdown charts rendering distinct lines for all runs and benchmark.
- Side-by-side metric comparison table (CAGR, drawdown, turnover, costs, return differences in money and percentage points).
- Rolling 5-year historical compounded-return table and positive-window share statistics.
- Holdout exposure status and export download button.

#### [MODIFY] [app.routes.ts](../frontend/src/app/app.routes.ts) & [app.component.html](../frontend/src/app/app.component.html)
- Add navigation links and routes for `/research/strategies` and `/research/compare`.

---

### 4. Independent Fixtures, Documentation & Verification

#### [NEW] [strategy-comparison.md](docs/strategy-comparison.md)
- Complete independent hand calculations:
  1. Signal-index invariance (split 2:1 and cash distribution).
  2. S2 12–1 momentum calculation, top $K$ ranking, tie-breaking by listing ID, and all-negative score behavior.
  3. S3 10-month SMA equality (cash), trend above SMA (ETF), and trend below SMA (cash).
  4. Multi-asset rotation accounting with sell-before-buy, adverse costs, and proportional cash scaling.
  5. Preservation of M3 EUR 1018 zero-cost reference and EUR 1017 cost case.

#### [NEW] [MultiAssetTestFixtureGenerator.java](../backend/src/test/java/com/bergnerd/signalforge/app/research/backtest/MultiAssetTestFixtureGenerator.java)
- Deterministic synthetic M2-importable dataset with multiple ETF listings and benchmark covering enough daily data for complete five-year windows, splits, distributions, distinct strategy paths and rebalances. Keep independent unit fixtures separate from end-to-end imports.

#### [NEW] [BacktestM4IntegrationTest.java](../backend/src/test/java/com/bergnerd/signalforge/app/research/backtest/BacktestM4IntegrationTest.java)
- Acceptance tests verifying all M4 criteria:
  - Exact total return signal index calculation.
  - S2 momentum ranking, tie breaking, $K$ validation, cash distribution preservation until rebalance.
  - S3 SMA10 trend calculation, cash/ETF allocation, no-churn on unchanged targets, distribution reinvestment.
  - Multi-asset execution, sell-before-buy, proportional affordability scaling, fee computation.
  - Matched run comparison and field-specific mismatch rejection.
  - Rolling 5-year windows, positive share, and insufficient history handling.
  - Experiment versions, holdout boundaries, and append-only exposure tracking.
  - Fresh, legacy and populated V7 upgrade verification to the actual follow-on version, without modifying prior migration files or old completed results.

#### [NEW] [research-M4.md](reports/research-M4.md)
- Formal closeout report covering all 9 required sections in Section 11 of the prompt.

## Verification Plan

### Automated Tests
1. **Backend Tests**:
   - `./gradlew spotlessApply` if the task exists, then `./gradlew clean test` with Java 21. Record actual counts; 161 was the documented M3 baseline, not a guaranteed current count.
2. **Frontend Tests & Build**:
   - Use the declared Node/npm toolchain; run `npm run lint` if configured, then `npm run test -- --watch=false` (or the configured equivalent). Record actual counts.
   - `npm run build` (production compilation).
3. **Migration Verification**:
   - Test fresh installation, legacy migration and populated V7 upgrade to the actual follow-on version on disposable SQLite files; verify old data and terminal guards survive.

### Manual / Browser Verification
1. Start backend and frontend on a disposable SQLite database.
2. Import multi-asset dataset.
3. Create S1, S2, and S3 runs; verify completed states.
4. Verify `/research/strategies`: view strategy parameters, universes, and generated signals.
5. Verify `/research/compare`: matched comparison between S1, S2, and S3, inspecting comparative charts, drawdown, rolling 5-year windows, and mismatch detection.
6. Verify holdout exposure is recorded before detail/summary/comparison/export retrieval, refresh a direct link, and download/check the bounded comparison export ZIP. Record browser, API and automated evidence separately.
7. If a suitable deterministic generator exists, measure the 20-ETF/20-year workload and record machine and runtime; do not claim the 60-second target without measurement. Docker is deferred.
