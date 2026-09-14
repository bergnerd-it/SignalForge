# SignalForge M4 — ETF Momentum, Trend Filter and Strategy Comparison

Implement M4 in the existing SignalForge repository. Complete implementation, focused acceptance tests, native browser verification and the report. Do not stop at a plan. Do not implement M5 prospective paper trading or AI explanations.

## 1. Read and preserve the accepted baseline

Read applicable AGENTS.md and repository tracker instructions, then:

- planning/FINALLY-RESEARCH-SPEC-v0.1.md
- planning/SIGNALFORGE-SPEC-v0.2-M0-ADDENDUM.md
- planning/PROMPT-SIGNALFORGE-M3.md
- planning/PROMPT-SIGNALFORGE-M3-FIXES.md
- The latest planning/reports/research-M3-fixes.md, dated 2026-09-14
- Relevant current strategy, accounting, backtest, history, migration, export and frontend code/tests.

The latest M3 closeout supersedes the earlier inaccurate completion claims. Its documented native baseline passed 161 backend and 39 frontend tests; it reports schema V7 and source a7acdcb37150aca3b5794d3038223c861ee47a14 plus uncommitted changes, including BuildIdentityResolver.java, V7 and the build-properties template. Confirm the actual checkout rather than assuming these are committed.

Preserve the entire M3 tree, including untracked files. Establish a complete local M3 checkpoint under repository rules before M4 changes. Do not push or publish. If repository rules prevent a checkpoint commit, preserve an exact manifest and state the limitation. Do not reopen resolved M3 findings without new evidence.

Keep Java 21, Spring MVC/JdbcTemplate, SQLite, Angular and the modular monolith. Verify tool versions from actual manifests/lockfiles/toolchains. Use the repository's pinned Node/npm; resolve the documented npm 11.19.0 versus requested 11.18.x discrepancy by using the existing declared toolchain, without an unrelated dependency upgrade. No JPA conversion, Python service or chart-library replacement.

All verification uses disposable databases; never inspect or migrate the owner's working database. Preserve prior migration definitions and source fingerprints. Add follow-on migrations after the actual current version when needed. Docker remains a separately deferred gate and does not block native M4 work.

## 2. Deliverable and explicit scope

Deliver three strategies on the same historical execution/accounting engine:

- Existing S1: ETF_BUY_HOLD_V1.
- New S2: ETF_MOMENTUM_12_1_V1.
- New S3: ETF_TREND_10M_V1.

Add immutable universe/strategy parameter versions, auditable monthly signals, multi-listing target-weight execution, matched-run comparisons, rolling historical five-year windows, experiment/holdout metadata, strategy/comparison UI and exports.

Keep S1 and its reference arithmetic working. Reuse the accepted job lifecycle, scoped APIs, metadata snapshots, cancellation/publication guarantees and exact ledger core. Generalize the existing engine only as necessary for multiple holdings and sales. Do not create a separate less accurate engine for S2/S3.

No live providers, broker orders, paper proposals, LLM numerical decisions, derivatives, taxes, currency conversion, optimization service or recommendation claims. Explicitly synthetic data is sufficient for technical acceptance. Real ETF/source/budget decisions remain prerequisites for financial interpretation, not for this implementation.

## 3. Immutable configurations and eligibility

Implement owner-scoped immutable universe versions containing stable listing IDs and selection provenance. V1 uses a fixed universe throughout a run; require a common calendar and EUR quote/distribution currency for universe and benchmark. No automatic universe shrinkage, ticker substitution, fabricated identities or history before inception. Preserve SYNTHETIC, REVISED_HISTORY and RETROSPECTIVE_UNIVERSE labels and any user-declared ETF classification.

A strategy version freezes its strategy identifier, supported parameters, universe/reference listing, decision schedule and calculation-policy version. Different parameters create a new version. S2 exposes K (default 3); reject K < 1 or K > universe size. Keep 12–1 momentum and the 10-month trend definition fixed in these V1 identifiers. Sensitivity experiments may use documented alternative K and cost configurations; do not silently change lookback definitions under the same identifier.

A run freezes these identities plus all M3 configuration, dataset checksums, engine/accounting/execution versions, cutoff rules, assumptions and source/build identity. Completed results and old configuration remain unchanged after a new universe, strategy, dataset or binary appears. Existing old runs stay readable; do not fabricate missing M4 metadata for them.

Initial evaluation is the selected completed month-end after sufficient warm-up. Candidate and benchmark are funded immediately before the same first subsequent eligible opening execution. Even if S3 initially selects cash, retain the same funding/reporting boundary. Warm-up is excluded from reported returns. Validate the entire configured universe and benchmark over all required intervals and retained corporate-action obligations.

## 4. Total-return signal observations and information timing

Build a total-return signal index from raw closes and supported corporate actions, independently of portfolio holdings:

T(d) = T(previous session) × splitMultiplier(d)
       × (rawClose(d) + distributionPerPostSplitUnit(d))
       / rawClose(previous session).

Initialize the first supported index point to an arbitrary positive value such as 100. For missing actions use multiplier 1 and distribution 0 only when the source completeness declaration supports it. Aggregate compatible same-date actions in a deterministic documented order. Preserve M3's strict availability/currency/precision validation.

The signal index uses theoretical ex-date reinvestment. Portfolio distributions create receivables on ex-date and spendable cash only at payment. Never credit the signal-index return as portfolio income or combine adjusted prices with another cash distribution. Execution prices remain raw opens.

Compute monthly endpoints using each month's final declared trading session, not a fixed count of days. Require continuous valid daily data between necessary endpoints and the extra prior observation needed for the recurrence where applicable. Missing history blocks eligibility; no silent shorter lookback or dropped instrument.

Use the M3 event clock. Monthly decisions see only completed observations available at their decision instant. Freeze a deterministic cutoff/availability policy for all required universe observations. If required observations arrive after the immediate next open, execution must wait for the first eligible open strictly after the actual decision instant, or reject an unsupported timing configuration explicitly; never backdate the signal. Do not let a later monthly evaluation overwrite or reorder a still-pending earlier one silently.

Opening execution reads only the raw open through the accepted narrow adapter. Future high/low/close values or future actions must not influence earlier rankings, signals or fills. Preflight may detect missing future coverage but must not provide future financial values to strategy logic. A final signal without an eligible in-window execution remains recorded as unexecuted.

Use authoritative BigDecimal arithmetic with a documented calculation policy. Rank using unrounded scores rather than display values; exact ties use stable listing ID ascending. Equal target weights should retain their rational 1/K meaning for sizing, avoiding arbitrary last-asset bias from display rounding.

## 5. Exact S2 and S3 rules

### S2 — ETF_MOMENTUM_12_1_V1

At month-end m:

score(i,m) = T_i(last session of month m-1)
             / T_i(last session of month m-12) - 1.

This excludes the most recent month and spans approximately eleven months of returns. It is not a trailing twelve-month daily approximation.

Require every configured listing to be eligible. Rank descending, ties by listing ID ascending. Select top K; target 1/K each and zero for other holdings. Negative scores do not trigger cash. Rebalance at the next eligible open following the decision.

This is ETF rotation, not the MSCI World Momentum stock index. Label it accordingly. For M4's explicit S2 convention, paid distributions remain cash until the next scheduled monthly rebalance; do not add an unscheduled daily rebalancing/reinvestment strategy. Persist this convention in strategy metadata.

### S3 — ETF_TREND_10M_V1

Use the same selected global ETF as the S1 reference comparison. At month-end m, compute the arithmetic mean of ten monthly index values including m:

SMA10(m) = mean(T(m-9), ..., T(m)).

If T(m) > SMA10(m), target 100% ETF; otherwise target 100% cash. Equality selects cash. No shorting, leverage or substitute money-market instrument. Cash interest is zero and shown explicitly.

Execute allocation changes at the next eligible open. When the target is unchanged, preserve the holding rather than creating artificial monthly sell/rebuy activity. Reinvest paid distributions only while the applicable target remains ETF. An allocation-change batch takes precedence over a reinvestment attempt scheduled for the same open; merge compatible intent so there is at most one net trade per listing in that batch. Existing receivables survive a sale and settle normally; payment does not force re-entry while target is cash.

## 6. Auditable signals and target-weight execution

Persist each signal with run/strategy/universe version, evaluation session and instant, exact input references/index values, eligibility, scores/SMA, ranking, selected listings, target weights, reason codes and scheduled/actual execution status. Show why an asset was selected or excluded and why S3 chose ETF/cash. Include unexecuted and blocked reasons, not only successful fills.

Implement the specification's shared execution model:

1. At the execution open, apply effective splits/entitlements and eligible payments in M3's temporal order.
2. Compute pre-trade equity from cash, receivables and all held positions at raw opens. Receivables contribute to equity but are never spendable cash.
3. Compute target whole units as floor(weight × pre-trade equity / raw open).
4. Sell excess first, including split-created fractional residuals when needed to reach the target. Use adverse sell spread/slippage and commission; remove proportional basis, clearing all basis on full disposal.
5. Compute desired whole-unit buy increments. Apply the deterministic proportional cash allocator including per-order fees. Document the scaling formula, then remove further units in stable listing-ID order if needed to make the batch affordable. Recompute applicable fees when an order becomes zero. Do not redistribute leftover cash in that batch.
6. Buy only affordable positive whole quantities, using the same rounding and exact amounts as ledger posting. No negative cash or position, zero-quantity fees or duplicate spread deductions.

Preserve requested versus executed quantities, realized gains, fees, spread/slippage estimates, actual versus target weights and cash shortfalls. Trade-date sale proceeds are immediately reusable as the existing disclosed simulation assumption; liquidity/market-impact/broker settlement models remain excluded. If a valid sale with unusually large costs cannot be booked without negative cash, fail explicitly under the existing long-only/cash constraints rather than waiving costs.

A batch and its accounting effects must be all-or-nothing within the existing result-publication design. No partial completed portfolio after an injected failure. Keep stable business order across runs independently of UUIDs, maps and thread scheduling. Runtime operations may have distinct IDs/timestamps; compare deterministic business payloads after excluding those fields.

## 7. Matched comparisons and rolling windows

Provide an owner-scoped persisted comparison referencing completed immutable runs. The primary comparison is after modeled trading costs and before personal taxes. S1/S2/S3 can differ in strategy and intended universe, but require matching:

- Effective funding instant, reporting sessions/end and initial cash.
- Currency/calendar and underlying dataset snapshot(s).
- Benchmark listing and benchmark accounting assumptions.
- Costs, execution/accounting model, and compatible engine version.

Reject mismatches with a field-by-field explanation, not a silently shortened overlap or rescaled initial portfolio. Old M3 runs remain readable; if their engine/configuration cannot satisfy matching rules, create new S1 runs with the current engine rather than rewriting them. Same benchmark and matched inputs must produce matching benchmark outputs.

Display equity, drawdown, annual returns, final wealth, CAGR where eligible, volatility, recovery durations, exposure/cash/receivables, turnover, fills and costs. Distinguish percentage-point return differences from money differences. Do not calculate 'gross performance' merely by adding fees back to net wealth; a gross counterfactual would require its own simulation and is not needed for M4.

Add rolling five-year historical compounded-return windows for sufficiently long completed series. Define these explicitly as subperiod returns of the existing simulated portfolio, not freshly funded strategy reruns:

- Monthly start boundary: equity immediately before the first trading session of a month, using the prior session close; the initial funded point may serve when funding begins at that month's first trading session.
- Target end: five calendar years after that starting session date; use the last declared session on or before that anniversary, with the dataset calendar covering the full anniversary boundary.
- Return = ending equity / starting equity - 1, using the same boundaries across comparison runs.
- Omit unsupported/incomplete windows with a visible insufficient-history reason. No padding or annualization of shorter windows.

Expose each window's exact dates, return, observation count and overall positive-window share (strictly return > 0). Label overlap and sample count; the share is not an independent-trial statistic or a forecast probability. Existing portfolio exposure, costs and pre-window holdings carry into these subperiods; do not claim these are fresh-investor entry results.

## 8. Experiment and holdout metadata

Add a small immutable experiment definition with development/holdout boundaries, frozen strategy/configuration identities and explicit exposure status. Define the boundary as the beginning of a trading session: development ends at the previous close and holdout begins before that session. Show whether a series is a continuous portfolio segment; do not pretend a continuous holdout segment is independently refunded.

Warm-up can use earlier available observations. Reject overlapping or unsupported periods. Record whether the user declares a holdout already examined, not yet examined or unknown. Existing historical runs must not be labeled unseen retrospectively.

Persist an append-only holdout-exposure event before first result retrieval/export through this application, including comparison/summary endpoints that expose holdout metrics. Hiding a chart while returning its results is not unexamined access. Marking exposure on any result view is an acceptable conservative implementation. Do not claim the application knows what the user has seen outside it.

Parameter/configuration changes create a new experiment/version and retain the prior record and exposure history. No automated 'best parameter' search. Support a small explicit sensitivity set (for example default versus higher costs and an alternative valid K), preserving every attempted configuration and status. Do not choose the highest-return variant as an unexplained default.

## 9. APIs, UI, storage and exports

Use existing owner-scoped /api/research conventions. Add only the necessary universe/version, strategy/version, signal, experiment and comparison resources. Mutations use durable idempotency; same-key replay returns the stored original result even after software changes. Bind SQL parameters, parse enum filters and retain local-origin protection. X-User-Id remains a local client-supplied convention, not authentication.

Extend frozen run metadata and result exports for the new strategies, signals, universes, comparisons and experiments. Use follow-on migrations, genuine relational constraints or the established equivalent guards, and terminal insert/update/delete protections. Never alter M1b balances, M2 dataset rows or old completed M3 results.

Reuse M3's bounded worker queue and cancellation/recovery/publication paths. Calculation must consume complete required history beyond any API page size. Explicit practical universe/session/result limits are acceptable; reject excess early rather than silently truncating analysis. Comparisons must use complete stored metrics/series or bounded readers, not frontend chart subsets.

Frontend routes should support:

- /research/strategies: immutable rules/parameters/universe and signal inspection.
- Existing /research/backtests: choose S1/S2/S3, validate warm-up and start runs.
- /research/compare: matched comparison with clear differences, costs and limits.
- Experiment/holdout and rolling-window views integrated where most useful.

Use exact typed DTOs and decimals, correct zoneless updates, selected-context stale-response suppression, direct-link loading by ID and polling cleanup. Paginate tables and both candidate/benchmark/chart series. Preserve the disclosed chart cap and loaded/total indication; a capped chart must not look complete. For visibly different synthetic strategy paths, verify all three lines and the benchmark, not only overlapping curves.

Exports must preserve the stored metadata snapshot and exact decimals, protecting untrusted text cells without corrupting negative numeric values. Maintain bounded preparation/streaming, owner checks and incomplete-job restrictions. Include signal inputs/ranks/targets/executions and comparison compatibility metadata sufficient to reconcile results. Clearly label synthetic data, retrospective universes, revised history, zero cash interest and excluded taxes/liquidity effects. No LLM dependency.

## 10. Independent fixtures and acceptance gates

Document independent expected values before encoding assertions in planning/docs/strategy-comparison.md. Use the production M2 import path for end-to-end fixtures. Unit fixtures may isolate arithmetic. Never derive expected output by invoking the implementation under test.

Minimum transparent examples:

1. Signal-index invariance: prior raw close 100, next split 2:1 and raw close 50 yields index growth 0%. Next raw close 49 with a EUR 1 post-split-unit distribution also yields growth 0%; portfolio entitlement/payment remains separate.
2. S2 at month m, K=2: T(m-12)=100 for A/B/C; T(m-1)=120/110/110. Scores are 20%/10%/10%; if B's stable ID precedes C, select A/B. Changing only month m's prices cannot alter this score. A separate all-negative case still selects top K. Supply full valid warm-up, not only isolated endpoint rows in integration data.
3. S3: ten monthly values all 100 selects cash (equality). Nine 100 values followed by 110 gives SMA 101 and selects ETF. Nine 100 followed by 90 gives SMA 99 and selects cash. Current month is included.
4. Independent rotation accounting with zero spread/slippage and EUR 1 commission: starting cash 100 and 9 A units at raw open 100, total basis 901, equity 1000. Rotate fully to B at raw open 50. Sell 9 A for net 899, cash becomes 999, realized gain -2; desired B=20 but affordable B=19, spend 951 including fee, cash 48, B basis 951. With B close 50, ending equity 998, commissions 2 and no A basis remains. This is an execution-unit fixture independent of the full initial-entry history.
5. Keep M3's EUR 1018 zero-spread/slippage reference and its nonzero-cost EUR 1017 case unchanged under the same execution rules.

Focused tests must establish:

- Exact month indexing, equality/tie rules, all-negative S2, K validation, continuous warm-up, and month-end holidays.
- Distribution/split signal correctness distinct from portfolio cash timing, including sold positions retaining receivables.
- Strict availability and future-data mutation isolation for rankings, trend decisions and earlier fills.
- Multi-asset sell-before-buy sizing, nonzero spread/slippage, fractional split residuals, fees, proportional affordability, no redistribution and complete basis removal.
- S3 no-churn unchanged targets, cash-state distribution payments, same-open intent merging, and unexecuted final signals.
- Independent matching benchmark, aligned comparisons, field-specific mismatch rejection and unchanged old results after configuration/data revisions.
- Five-year boundary/insufficient-history/overlap calculations from an independent long synthetic fixture; distinguish engine-generated financial runs from any seeded UI-only fixture.
- Immutable experiment versions and persistent exposure tracking through detail, comparison and export paths; no false unseen claim on prior results.
- Run replay and cancellation/publication guarantees on the generalized multi-asset path, upgrade preservation and no cross-owner resource access.
- API/frontend contract agreement, full pagination, stale-run suppression, unavailable states and exact bounded export.

Run required full backend/frontend suites and production builds using the pinned toolchain. Perform a native walkthrough on a disposable database: import a sufficiently long multi-listing synthetic bundle, create S1/S2/S3 runs, inspect distinguishing signals and fills, compare aligned results, reject a mismatch, inspect rolling windows/holdout status, refresh a direct link, and download/check an export. Record actual API/browser evidence separately; do not invent recordings or call seeded UI data a financial backtest.

Measure the original 20-ETF/20-year daily-data performance target if a suitable deterministic synthetic generator is available within scope. Record actual workload, machine and runtime; do not claim the 60-second target passed without measurement. Optimize only a demonstrated bottleneck. Profitability and outperformance are not acceptance gates.

## 11. Closeout

Deliver working implementation, migrations, meaningful tests, reproducible synthetic fixture generator/bundles and planning/docs/strategy-comparison.md with rules, hand calculations and walkthrough.

Write planning/reports/research-M4.md containing:

1. Complete M3 checkpoint and tested M4 source/build identity, including untracked changes and exact tool versions.
2. Actual schema/API/architecture changes and preserved baseline behavior.
3. Exact strategy, index, timing, rebalance, comparison and rolling-window conventions.
4. Independent expected versus observed arithmetic, including M3 regression results.
5. Commands, counts/results and honest native browser/download evidence.
6. Scope/isolation, immutability, idempotency, cancellation and migration evidence.
7. Experiment versions, sensitivity cases and holdout exposure limitations.
8. PASS / FAIL / NOT VERIFIED gates; Docker explicitly deferred.
9. Remaining real ETF/source/budget choices, concrete limitations and M5 readiness.

Update the tracker and relevant architecture/user documentation. Use current source evidence, not remembered schema names or fabricated test claims. State when evidence is static, automated integration, native browser or not verified. No claim of future investment returns, guaranteed absence of bias or production readiness based solely on synthetic tests. Stop after M4; do not implement M5 or publish remotely.
