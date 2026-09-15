# M5 Independent Code Review

**Review date:** 2026-09-15  
**Verdict:** **FAIL — M5 is not implemented correctly and TASK-17 should be reopened.**  
**Scope:** Review only. No M5 implementation files were changed.

## Review subject

The review covered `planning/PROMPT-SIGNALFORGE-M5.md`, `planning/reports/research-M5.md`, `planning/reports/walkthrough-M5.md`, `planning/SIGNALFORGE-SPEC-v1.0.md`, the M5 implementation, and its tests.

The reviewed tree was uncommitted:

- Base commit and `m4-checkpoint`: `9d5bc5d34f42e716fcf4d7c0a61ebd9146229965`
- `git describe --always --dirty --tags`: `m4-checkpoint-dirty`
- Tracked diff SHA-256: `747750dd9ddb5c1e1b40967cc4b88369e8ed1584335d0973cd3625f2cb728386`
- Untracked-file manifest SHA-256, before this report: `6834022240db7181af2830ce7cdbed93661386449feaec43d79d87666f78a271` (24 files)

Actual tools observed during this review:

| Tool | Observed value |
|---|---|
| macOS | 26.6.2, build 25G83, arm64 |
| Java launcher | Temurin 17.0.19 |
| Gradle | 8.10.2; project toolchain requests Java 21 |
| Declared Node | 24.21.0 in `.node-version` and `.nvmrc` |
| Node used for frontend verification | 24.21.0 |
| npm used | 11.19.0 (the package declares 11.18.x) |

## Findings

### 1. Critical: S2 and S3 proposals do not execute the M4 strategies

`PaperPortfolioService.evaluatePortfolio` injects `StrategyEvaluator` and `TotalReturnSignalIndexCalculator` but never uses either. Instead, S2 selects the first two listing IDs in database sort order and assigns constant scores; S3 always emits `TREND_ABOVE_SMA` for the first listing. No price, total-return index, momentum window, SMA10, or signal date is evaluated (`PaperPortfolioService.java:220-256`). The `observations` collection is created but never populated or inserted (`PaperPortfolioService.java:227-228`).

This can recommend assets that the M4 strategy would reject. It violates the required reuse of the M4 strategy core and makes proposal evidence non-auditable.

### 2. Critical: accepted proposals are not frozen executable decisions

Every proposal item is persisted with `desired_units = "0"` and a placeholder `raw_price_reference` (`PaperPortfolioService.java:230-255`). At execution, quantity is recomputed from the portfolio's then-current cash (`PaperPortfolioService.java:510-548`). A dividend, another trade, or any balance change after acceptance therefore changes the accepted decision.

The fill loop only buys positive target weights. It does not sell, reduce positions, calculate deltas from current holdings, or rebalance. It hard-codes a EUR 1 commission, ignores the segment's configured commission/spread/slippage, and records `fill_price = raw_open_price` with zero spread/slippage (`PaperPortfolioService.java:518-567`). This is not the required shared paper allocator/rebalance behavior.

### 3. Critical: valuations become wrong immediately after the first fill

The post-fill valuation writes `positions_market_value = 0`, `receivables_value = 0`, and sets total equity equal to remaining cash (`PaperPortfolioService.java:577-590`). In the supplied lifecycle fixture, the portfolio owns 100 units at EUR 100 but its stored total equity is EUR 9. Returns, high-water marks, and drawdowns are hard-coded to zero. The UI and assistant can consequently present materially false portfolio values.

### 4. Critical: corporate actions are not implemented

Production code never reads `historical_actions` to apply splits or create distribution receivables. `processPortfolioEvents` only settles receivables that already exist (`PaperPortfolioService.java:397-456`). The integration test inserts a receivable directly into the database, so it bypasses the missing behavior (`PaperPortfolioIntegrationTest.java:194-209`). No split, record-date entitlement, payment-date gate, withholding, reinvestment intent, or 1018 reference path is tested.

Settlement mutates `portfolio_state.cash_amount` directly with SQLite `printf` and `doubleValue` rather than using the accounting core, does not create the promised operation/ledger entry, never sets `paid_operation_id`, and does not check `payment_date` before paying (`PaperPortfolioService.java:420-455`). The report's claim that splits and distributions are implemented is unsupported and, for splits, contradicted by the code.

### 5. Critical: data readiness and causal-time gates accept invalid snapshots

Dataset adoption ignores `datasets.validation_status`, bar completeness, action completeness, source namespace, calendar compatibility, dataset/universe identity, strategy warm-up, current completed-session coverage, and future-session coverage. It checks only that required EUR listing rows and at least one calendar row exist (`PaperDataReadinessService.java:61-112`).

`checkReadiness` does not use its injected `Clock`. It simply calls the penultimate dataset session “latest completed” and the last session “next,” regardless of whether either is in the past or future, and returns `READY` without checking bars or actions (`PaperDataReadinessService.java:154-184`). Evaluation repeats this last-two-rows logic, hard-codes calendar `XETR`/version `1.0`, and constructs a UTC instant by appending `Z` to a local exchange time (`PaperPortfolioService.java:184-203,258-266`).

Rejected adoption history is inserted and then an exception is thrown inside the same `@Transactional` method, so the rejection row is normally rolled back (`PaperDataReadinessService.java:96-111,128-138`). The claimed durable rejection audit is therefore absent.

### 6. High: idempotency, transaction, and concurrency guarantees are incomplete

The idempotency key parameters on activation, adoption, evaluation, acceptance, rejection, mode changes, processing, and assistant chat are ignored. Evaluation can create repeated proposals for the same cycle. Intent and execution-result tables have no durable uniqueness constraint for their logical identities (`V12__paper_tracking_and_proposals.sql:155-211`).

Acceptance changes the proposal and creates the intent in separate statements without one transaction (`PaperPortfolioService.java:327-349`). Processing selects pending intents, executes a core trade, inserts paper provenance, updates intent status, and writes a valuation without one encompassing transaction (`PaperPortfolioService.java:382-591`). Concurrent processors can both observe the same pending intent; the core trade key may prevent duplicate money movement, but both can insert distinct paper execution-result rows because result IDs are random and no unique constraint prevents it.

The supplied eight-thread test covers only the proposal status CAS. It does not exercise real transaction boundaries around duplicate intents, fills, recovery, action processing, or valuations.

### 7. High: AUTO_PAPER and downtime recovery do not provide the required behavior

The scheduler only calls `processPortfolioEvents` for already-existing intents. It never performs a scheduled strategy evaluation or creates an AUTO_PAPER proposal/intent, and it never records a missed opportunity when no intent existed (`PaperExecutionCoordinator.java:71-88`).

Startup recovery changes intent state and inserts its transition in separate, unconditional statements. It then processes every active portfolio using owner `default`, so recovery fails for other owners (`PaperExecutionCoordinator.java:34-68`). There is no per-portfolio serialization, compare-and-set transition, restart integration test, same-open boundary test, or missed-window implementation.

### 8. High: the schema does not enforce the documented immutable state machines

The proposal trigger protects rejected, superseded, and missed rows, but leaves accepted proposals mutable (`V12__paper_tracking_and_proposals.sql:239-246`). Child rows may still be inserted after acceptance or terminal state. Intent transitions are unconstrained and mutable; mode history and adoption history have no update/delete guards; execution intents can be changed or deleted; the single-active-segment trigger covers inserts but not updates (`V12__paper_tracking_and_proposals.sql:239-296`).

The corporate-action unique key includes `terms_hash`, allowing the same action identity with changed terms to coexist. Foreign keys point at global listing IDs and do not enforce that strategy, universe, dataset, proposal, intent, and portfolio records belong to one compatible owner-scoped aggregate.

### 9. High: the research assistant is a small set of queries, not the required bounded tool layer

There are no discriminated typed tool contracts matching the closeout report. Portfolio context returns only cash, approval mode, and strategy; proposal context omits items, observations, and costs; comparison context omits compared runs and metrics; run context omits holdings, signals, executions, and drawdown (`ResearchAssistantService.java:82-208`). When a run has no daily equity it invents EUR 1000 final equity instead of returning an unavailable state (`ResearchAssistantService.java:109-119`).

Holdout exposure errors are swallowed, allowing the read to continue without the required durable exposure (`ResearchAssistantService.java:100-106`). The idempotency key is ignored and existing durable chat request handling is not reused. Tests mock the controller service and perform one broad integration query; they do not verify foreign-resource rejection, malicious instructions/IDs, prompt boundaries, evidence correctness, holdout persistence, malformed LLM output, or financial non-mutation.

### 10. High: `/research/portfolios/:id` does not select the requested portfolio and the UI omits required states

The root app recognizes that the path starts with `/research/portfolios`, but `ResearchPaperComponent` never reads the route ID. `ResearchService` loads the first list page and the component selects its first row (`research-paper.component.ts:61-79`). A refresh of `/research/portfolios/{id}` can therefore display a different portfolio.

The page does not present execution results, intent transitions, pending receivables, corporate-action events, missed/blocked/awaiting-data details, scheduled-versus-observed fill data, or a prospective chart. It only sends assistant queries in portfolio context. There is no stale-response suppression when the selected portfolio changes, and there are no pagination controls beyond fixed first-page loads.

M5 frontend code also introduces `any` for the assistant request and three service responses (`research-paper.component.ts:247`; `research.service.ts:131-138,199-206,221-228`), contrary to the project's strict typing rule.

### 11. High: the audit ZIP is incomplete and unbounded

The export contains only a small manifest plus proposals, execution results, valuations, and current holdings. It omits segment/cost configuration, mode history, dataset adoption identities, proposal items and observations, intents and transitions, receivables, processed actions, and effective/recorded event chronology (`PaperExportService.java:32-154`). It builds every table and the whole ZIP in memory without a bound.

The test verifies only that five entry names exist and are non-empty. It does not inspect exact decimals, owner isolation, formula-injection handling, manifest identity, or source-row immutability after export (`PaperPortfolioIntegrationTest.java:267-286`).

### 12. High: required verification is missing and the closeout reports overstate the evidence

The prompt requires twelve concrete integration scenarios. The M5 suite adds four readiness unit tests, three controller slice tests, one assistant controller test, two component tests plus two simple interaction tests, and one broad lifecycle integration test. It does not cover two-portfolio isolation, controlled-clock timely/late acceptance, missing-open then later import, EUR 1000/9-unit arithmetic, split/distribution/reinvestment, all S1/S2/S3 decision dates, stale/incompatible/corrected snapshots, restart/missed behavior, AUTO_PAPER mode boundaries, assistant attacks/holdout behavior, exact export, or a populated M4-to-current upgrade.

The integration fixture uses `LocalDate.now`, mutates `scheduled_open_instant` directly, and inserts its receivable directly; it does not use a test-controlled Clock with the production workflow (`PaperPortfolioIntegrationTest.java:55-56,194-209`). The migration test changes expected version/count values from 11 to 12 but adds no populated M5 upgrade assertions.

Neither supplied report records a native browser walkthrough. `walkthrough-M5.md` is an implementation summary containing automated command claims, not browser actions, screenshots/DOM evidence, deep-link refresh evidence, or an inspected downloaded ZIP. Inherited M4 form and holdout-view checks are also absent. Nevertheless, `research-M5.md` marks all gates PASS and declares M5 fully closed, contrary to the prompt's explicit reporting rule.

The reports contain additional factual mismatches: they name a nonexistent core `transactions` table instead of the actual ledger structures; describe proposal/intent states that differ from V12; claim implemented split processing, typed assistant tools, slippage, missed-window recovery, and complete exports that the code does not contain; and report JDK/npm/Darwin versions different from those observed here.

### 13. High: the production frontend build does not pass in the reviewed environment

`npm run build -- --configuration production` aborted consistently with exit code 134 immediately after `Building...` under Node 24.21.0. It also aborted under Node 26.8.1 and with `NG_BUILD_MAX_WORKERS=1`. The closeout report's production-build PASS could not be reproduced.

## Commands and results

| Command | Result |
|---|---|
| `cd backend && ./gradlew clean test` | **PASS** — 187 tests, 0 failures; `BUILD SUCCESSFUL in 11s` |
| XML result aggregation over `backend/build/test-results/test/TEST-*.xml` | 30 suites, 187 tests, 0 failures/errors |
| `cd frontend && PATH=<Node-24.21.0>:$PATH npm test -- --watch=false` | **PASS** — 4 files, 48 tests |
| `cd frontend && PATH=<Node-24.21.0>:$PATH npm run build -- --configuration production` | **FAIL** — exit 134 |
| Same production build with default Node 26.8.1 | **FAIL** — exit 134 |
| Same production build with `NG_BUILD_MAX_WORKERS=1` | **FAIL** — exit 134 |
| `git diff --check` | **PASS** (line-ending conversion warnings were emitted separately) |
| Native browser walkthrough on disposable SQLite | **NOT VERIFIED** — no such evidence in the supplied walkthrough |
| Docker | **NOT VERIFIED / deferred**, as directed |

Green unit suites establish that the current assertions pass. They do not override the demonstrated behavioral defects above.

## Gate disposition

| Gate | Status | Basis |
|---|---|---|
| M4 base commit/tag identity | PASS | Commit and tag both resolve to `9d5bc5d...` |
| Follow-on V12 migration present; V1-V11 unchanged | PASS | V12 is wired into `MigrationRunner` |
| V12 aggregate integrity and immutability | FAIL | Mutable accepted/intents/histories; incomplete scope and uniqueness constraints |
| Separate EUR PAPER creation | PARTIAL | Creation reuses `OperationService`; required isolation scenarios are untested |
| Current data readiness and snapshot adoption | FAIL | Required time, bar, action, warm-up, validation, and compatibility checks are absent |
| M4 strategy-core reuse for S1/S2/S3 | FAIL | S2/S3 decisions are hard-coded |
| Immutable proposal and future intent lifecycle | FAIL | Units are not frozen; idempotency and transaction boundaries are missing |
| Exact accounting, costs, valuations | FAIL | Cost policy ignored; valuation excludes holdings; direct floating-point cash mutation exists |
| Splits, distributions, receivables, reinvestment | FAIL | Only manually seeded receivable settlement exists |
| AUTO_PAPER, restart, downtime and missed opportunities | FAIL | Coordinator cannot evaluate/create/miss; recovery owner is hard-coded |
| Grounded assistant and owner/holdout boundaries | FAIL | Required tool coverage and failure/security tests are absent; invented fallback value exists |
| Paper UI and deep links | FAIL | Route ID ignored; required lifecycle states/views are absent |
| Complete bounded audit export | FAIL | Required audit records omitted; unbounded in-memory generation |
| Backend automated suite | PASS | 187/187 passed |
| Frontend automated suite | PASS | 48/48 passed |
| Declared-runtime production build | FAIL | Reproducible exit 134 in this review |
| Native M5 walkthrough and downloaded artifact inspection | NOT VERIFIED | No browser walkthrough evidence supplied |
| Inherited M4 native form/holdout checks | NOT VERIFIED | Not recorded in M5 reports |
| Optional real LLM call | NOT VERIFIED | Optional and correctly not required for core operation |
| Docker | NOT VERIFIED | Explicitly deferred |

## Required disposition

TASK-17 is currently marked **Done** with every acceptance criterion checked, but the objective evidence does not support that state. Reopen it and address findings 1-11 before repeating verification. Replace the closeout reports' blanket PASS claims with actual PASS/FAIL/NOT VERIFIED results. A new verification run should use a disposable foreign-key-enabled SQLite database, a controlled Clock through real services, all twelve prompt scenarios, a native deep-link/browser walkthrough, and inspection of the downloaded archive bytes. Do not start M6 while these M5 gates remain failed.
