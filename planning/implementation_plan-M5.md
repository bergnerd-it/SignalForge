# Implementation Plan — Milestone M5: Prospective Paper Tracking and Grounded AI Explanations

Implement Milestone M5 in accordance with [PROMPT-SIGNALFORGE-M5.md](file:///Users/oliver/Projects/bergnerd/SignalForge/planning/PROMPT-SIGNALFORGE-M5.md) and canonical specification [SIGNALFORGE-SPEC-v1.0.md](file:///Users/oliver/Projects/bergnerd/SignalForge/planning/SIGNALFORGE-SPEC-v1.0.md).

## Baseline Checkpoint & Current Tree Confirmation
- **M4 Baseline Status**: Commit `9d5bc5d34f42e716fcf4d7c0a61ebd9146229965` checkpointed with annotated git tag `m4-checkpoint`.
- **Verified Toolchains**:
  - Adoptium Java 21.0.12.1+1-LTS (compiler/runtime toolchain) & Gradle 8.10.2.
  - Node v24.21.0 & npm 11.18.0 (declared build toolchain).
  - Angular 22.1.5 & Vitest 4.1.11.
- **Existing Tests**: 176 backend tests passing, 44 frontend tests passing.
- **Database Status**: Migrations V1 through V11 intact with valid digests.

---

## Architecture and Proposed Changes

### 1. Database Schema & Migration (V12)
Preserve V1–V11 exactly. Add `V12__paper_tracking_and_proposals.sql` to introduce tables and integrity triggers for prospective paper portfolios:
- `paper_portfolios`: Links to `portfolios(id)` with mode `'PAPER'`, storing:
  - `strategy_id`, `strategy_version`, `universe_id`, `benchmark_listing_id`.
  - `data_source_namespace`, `adopted_dataset_id`, `adopted_at`.
  - `cost_policy_json` (commission, bid_ask_spread_bps, slippage_bps).
  - `approval_mode` (`MANUAL` or `AUTO_PAPER`), `status` (`CONFIGURED`, `ACTIVE`, `PAUSED`, `TERMINATED`).
  - `paper_started_at` (server timestamp of activation), `initial_equity`.
- `paper_mode_history`: Append-only audit of approval mode transitions (`MANUAL` <-> `AUTO_PAPER`), effective instant, triggering user/system, pending intents disposition.
- `paper_dataset_adoptions`: Append-only history of dataset snapshot adoptions for each paper portfolio, checksums, coverage bounds, and validation status.
- `paper_proposals`: Immutable records of strategy evaluations:
  - `id`, `portfolio_id`, `owner_id`, `cycle_id`, `strategy_id`, `strategy_version`, `dataset_id`.
  - `evaluation_session_date`, `input_cutoff_time`, `evaluation_time`.
  - `scheduled_open_session_date`, `scheduled_open_time`.
  - `target_weights_json`, `evaluation_inputs_json`, `reason_code`, `reason_description`.
  - `portfolio_state_version`, `status` (`PROPOSED`, `ACCEPTED`, `REJECTED`, `SUPERSEDED`, `WAITING_FOR_OBSERVATION`, `EXECUTED`, `BLOCKED`, `MISSED`).
  - `accepted_at`, `rejected_at`, `resolved_at`, `rejection_reason`.
- `paper_execution_intents`: Durable intent records scheduled for execution:
  - `id`, `portfolio_id`, `proposal_id`, `order_type` (`INITIAL_ALLOCATION`, `REBALANCE`, `REINVESTMENT`).
  - `scheduled_session_date`, `scheduled_open_time`, `created_at`, `approval_mode`.
  - `status` (`PENDING`, `WAITING_FOR_OBSERVATION`, `EXECUTED`, `CANCELLED`, `MISSED`, `FAILED`).
- `paper_orders`: Prospective executions resolving an intent upon observation of the scheduled open session:
  - Links to `paper_execution_intents`, storing raw open price, executed quantity, fill price, commission, spread/slippage, cost basis, realized gain, cash delta.
- `paper_valuations`: Prospective daily/session valuations and event observations:
  - `portfolio_id`, `session_date`, `observation_time`, `cash_balance`, `positions_value`, `receivables_value`, `total_equity`, `cumulative_return`, `high_water_mark`, `drawdown`.
- Integrity triggers: foreign keys enforced, immutable updates prevented on completed/executed/rejected proposals, runs, and mode history.

#### [NEW] [V12__paper_tracking_and_proposals.sql](file:///Users/oliver/Projects/bergnerd/SignalForge/backend/src/main/resources/db/migration/V12__paper_tracking_and_proposals.sql)
#### [MODIFY] [MigrationRunner.java](file:///Users/oliver/Projects/bergnerd/SignalForge/backend/src/main/java/com/bergnerd/signalforge/app/db/migration/MigrationRunner.java)
- Increment `CURRENT_SCHEMA_VERSION = 12` and `CODE_VERSION = "2.0.0-M5"`.
- Add V12 SQL loading, checksum verification, execution, and schema validation.

---

### 2. Time Authority & Injectable Clock
- Production uses `Clock.systemUTC()`.
- Introduce a server-managed injectable `AppClock` bean (or test-overridable clock) allowing integration tests to deterministically advance simulated time across decisions, intent creation, and later observation imports.
- Production REST endpoints reject client-provided timestamps for activation, acceptance, or evaluation now; the server clock is the sole authority.

#### [NEW] [AppClock.java](file:///Users/oliver/Projects/bergnerd/SignalForge/backend/src/main/java/com/bergnerd/signalforge/app/system/AppClock.java)

---

### 3. Data Readiness & Snapshot Adoption Policy
- Validate compatible dataset snapshots against paper portfolio requirements:
  - Matches source namespace, universe listings, quote currency (EUR), calendar ID.
  - Complete history for strategy warm-up (e.g. 13 months for S2, 10 months for S3) ending on a declared completed trading session.
  - Future schedule coverage: declared trading calendar must contain the intended execution session date.
  - Corporate action economic identity: map source/listing/action terms to prevent double-applying splits or dividends across snapshots.
  - Block adoption if conflicting observations with already booked events exist.

#### [NEW] [PaperDataReadinessService.java](file:///Users/oliver/Projects/bergnerd/SignalForge/backend/src/main/java/com/bergnerd/signalforge/app/research/paper/PaperDataReadinessService.java)

---

### 4. Paper Portfolio Service & Lifecycle
- **Creation & Funding**:
  - Only EUR PAPER portfolios. Single initial funding operation via `OperationService`.
  - Reusing empty portfolio does not double-fund. Reject attaching to manual portfolios with existing non-cash positions.
- **Activation**:
  - Freezes strategy association, universe, benchmark, and cost policy for the active segment.
  - Discloses which completed month-end provides the initial target; activation instant recorded from server clock.
  - Initial snapshot recorded; prospective metrics track only from this opening equity.
- **Evaluation**:
  - Evaluates S1, S2, or S3 using the shared `StrategyEvaluator` and `TotalReturnSignalIndexCalculator`.
  - Creates an immutable `paper_proposals` record.
  - If in `AUTO_PAPER` mode, automatically persists durable `paper_execution_intents` before the scheduled open.
- **Acceptance / Rejection**:
  - Manual flow: user accepts proposal before scheduled open -> creates `paper_execution_intents`.
  - Late acceptance (at or after suggested open): checks if open has passed; schedules strictly future eligible open with fresh sizing, or supersedes proposal if cycle no longer applicable.
  - Repeat acceptance is idempotent (returns existing accepted schedule). Conflicting concurrency protected by CAS row-count checks.
- **Resolution & Execution**:
  - When new dataset observation arrives covering the scheduled session open:
    - Verifies durable intent predates the open time.
    - Sizing and execution reuse the exact M4 allocator and accounting logic: whole units, sell-before-buy, spread/slippage, proportional affordability, fees, cost basis.
    - Records market-effective fill time and actual booking time separately.
- **Corporate Actions & Dividends**:
  - Mechanical split and dividend accounting from M3/M4. S2 holds cash; S1 and S3 reinvest under timely approval or pre-open auto intent.
  - Receivables tracked durably until payment date.
- **AUTO_PAPER Coordinator**:
  - Bounded in-process coordinator checking for scheduled evaluations and executions.
  - Downtime recovery: logs missed signals/opportunities, processes pending intents when data arrives, never fabricates past retro fills.

#### [NEW] [PaperDtos.java](file:///Users/oliver/Projects/bergnerd/SignalForge/backend/src/main/java/com/bergnerd/signalforge/app/research/paper/PaperDtos.java)
#### [NEW] [PaperPortfolioService.java](file:///Users/oliver/Projects/bergnerd/SignalForge/backend/src/main/java/com/bergnerd/signalforge/app/research/paper/PaperPortfolioService.java)
#### [NEW] [PaperExecutionCoordinator.java](file:///Users/oliver/Projects/bergnerd/SignalForge/backend/src/main/java/com/bergnerd/signalforge/app/research/paper/PaperExecutionCoordinator.java)
#### [NEW] [PaperExportService.java](file:///Users/oliver/Projects/bergnerd/SignalForge/backend/src/main/java/com/bergnerd/signalforge/app/research/paper/PaperExportService.java)
#### [NEW] [PaperPortfolioController.java](file:///Users/oliver/Projects/bergnerd/SignalForge/backend/src/main/java/com/bergnerd/signalforge/app/research/paper/PaperPortfolioController.java)

---

### 5. Grounded Research Assistant
- Extend research assistant service with selected context (run, comparison, paper portfolio, proposal).
- Implement typed read-only tools:
  - `get_run_summary(runId)`
  - `get_holdings_and_receivables(portfolioId)`
  - `get_signals_and_observations(runId or portfolioId)`
  - `get_drawdowns(runId or portfolioId)`
  - `get_comparison_results(comparisonId)`
  - `get_proposal_detail(proposalId)`
- Every tool validates caller owner scope server-side.
- If holdout data is accessed, records `experiment_exposure_events`.
- Grounded fact cards and evidence citations returned alongside responses (data IDs, observation times, exact calculated numbers).
- Assistant cannot execute trades or mutate state directly (may return non-executing configuration draft for user confirmation).
- Resilient: mock client for offline tests; full application works with LLM disabled.

#### [NEW] [ResearchAssistantDtos.java](file:///Users/oliver/Projects/bergnerd/SignalForge/backend/src/main/java/com/bergnerd/signalforge/app/research/assistant/ResearchAssistantDtos.java)
#### [NEW] [ResearchAssistantService.java](file:///Users/oliver/Projects/bergnerd/SignalForge/backend/src/main/java/com/bergnerd/signalforge/app/research/assistant/ResearchAssistantService.java)
#### [NEW] [ResearchAssistantController.java](file:///Users/oliver/Projects/bergnerd/SignalForge/backend/src/main/java/com/bergnerd/signalforge/app/research/assistant/ResearchAssistantController.java)

---

### 6. Frontend Angular Implementation
- Route and tab for paper tracking: `/research/portfolios/:id` and `/research/portfolios`.
- **Component**: `ResearchPaperComponent` (or integrated sub-route in research navigation):
  - Portfolio creation modal: strategy selection (S1, S2, S3), universe selection, initial funding amount (EUR), cost policy.
  - Detail view:
    - Status badges: Mode (`MANUAL` / `AUTO_PAPER`), Active/Paused, Tracking start time, Initial equity, Current equity, Cash, Receivables.
    - Strategy and Universe summary, Adopted dataset snapshot and latest completed session.
    - Data readiness card: indicates if data is current, stale, or awaiting next session.
    - AUTO_PAPER toggle switch with confirmation modal disclosing pending intents.
    - Proposal cards: Evaluation date, scheduled execution session, target weights table, ranking rationale, Accept / Reject buttons.
    - Scheduled / Observed executions table: distinct scheduled session vs observed booking time, raw open price, executed units, fees.
    - Prospective equity curve chart (isolated from historical backtest, prospective-only timeline).
    - Audit Export button downloading complete ZIP / CSV archive.
- Research Assistant drawer/panel integration:
  - Context selector (links to active paper portfolio or backtest run).
  - Fact cards displaying authoritative server numbers and evidence references.

#### [NEW] [research-paper.component.ts](file:///Users/oliver/Projects/bergnerd/SignalForge/frontend/src/app/components/research-paper/research-paper.component.ts)
#### [NEW] [research-paper.component.html](file:///Users/oliver/Projects/bergnerd/SignalForge/frontend/src/app/components/research-paper/research-paper.component.html)
#### [NEW] [research-paper.component.css](file:///Users/oliver/Projects/bergnerd/SignalForge/frontend/src/app/components/research-paper/research-paper.component.css)
#### [MODIFY] [paper.service.ts](file:///Users/oliver/Projects/bergnerd/SignalForge/frontend/src/app/services/paper.service.ts) (New service for paper APIs)
#### [MODIFY] [app.ts](file:///Users/oliver/Projects/bergnerd/SignalForge/frontend/src/app/app.ts), [app.routes.ts](file:///Users/oliver/Projects/bergnerd/SignalForge/frontend/src/app/app.routes.ts), [app.html](file:///Users/oliver/Projects/bergnerd/SignalForge/frontend/src/app/app.html)

---

### 7. Documentation & Closeout
- Create `planning/docs/paper-tracking.md`:
  - Lifecycle state machine diagram and transitions.
  - Route and API contracts.
  - Time, adoption, and freshness policy.
  - Approval behavior (Manual vs AUTO_PAPER).
  - Recovery instructions on restart/downtime.
  - Independent arithmetic walkthrough (EUR 1000, fee EUR 1, buy 9 at raw open 100, cash 99 / basis 901; split/distribution/payment).
- Create `planning/reports/research-M5.md`:
  - Complete M4 checkpoint identity.
  - Scope, schema, and API contracts.
  - Lifecycle, time, freshness, adoption conventions.
  - Independent arithmetic and causal timing verification.
  - Concurrency, idempotency, rollback, and upgrade verification.
  - AI tool boundary, evidence reference, and failure verification.
  - Test and build execution counts, browser walkthrough notes.
  - PASS / FAIL / NOT VERIFIED gates.

---

## Verification Plan

### Automated Tests
1. **Backend Integration & Unit Tests**:
   - `PaperPortfolioIntegrationTest.java`:
     - Test 1: Create & activate two same-owner PAPER portfolios; verify no duplicate funding, isolation from LEGACY_DEMO/BACKTEST.
     - Test 2: Timely acceptance before opening; unobserved open stays pending; later import resolves once at market open with separate booking time.
     - Test 3: Late acceptance (open passed); verify historical fill rejected, future session scheduled or superseded; repeat acceptance idempotency.
     - Test 4: Independent arithmetic: EUR 1000 initial cash, buy 9 @ 100 with EUR 1 fee -> cash 99, basis 901. Split / distribution / payment / reinvestment with prospective intent.
     - Test 5: Non-zero costs and shared M4 allocator rules (sell-before-buy, partial affordability, whole units).
     - Test 6: Stale/missing calendar/bar/action and incompatible snapshot blocking; revision terms immutability.
     - Test 7: Ex-date entitlement before trading, cash payment availability date, unpaid receivables.
     - Test 8: Concurrency barrier: 8 parallel duplicate acceptances/processing calls cannot double-fill; transaction rollback injection verification.
     - Test 9: Restart with accepted intent resolves once; restart without intent logs missed signal; AUTO_PAPER opt-in/disable transitions.
     - Test 10: Grounded AI assistant tools: owner scoping, holdout exposure recording, offline mock resilience, core functioning without LLM.
     - Test 11: Audit export: exact negative decimals, safe CSV formula escaping, ZIP integrity.
     - Test 12: Migration upgrade V11 -> V12 preserving all existing catalog, universes, runs, and demo records.
   - Run command: `./gradlew clean test` (targeting 190+ tests with 0 failures).

2. **Frontend Tests & Production Build**:
   - Unit tests for `PaperService`, `ResearchPaperComponent`, DTO contracts in `frontend/src/app/`.
   - Run command: `npm test -- --watch=false` (targeting 50+ tests passing).
   - Production bundle build with declared Node 24.21.0:
     `PATH=/Users/oliver/.npm/_npx/ce60003f8dc3f49f/node_modules/.bin:/Users/oliver/.npm/_npx/ce60003f8dc3f49f/node_modules/node/bin:$PATH npm run build`

### Native Walkthrough & Disposable Verification
- Run backend and frontend against a disposable SQLite database.
- Drive browser subagent or native automation to verify:
  - Navigation to `/research/portfolios` and creating a new paper portfolio.
  - Activating tracking and adopting a dataset.
  - Generating and reviewing an immutable proposal.
  - Accepting a proposal and inspecting scheduled state.
  - Enabling/disabling AUTO_PAPER mode.
  - Grounded AI assistant query displaying fact card and evidence citations.
  - Downloading and verifying the audit export ZIP/CSV.

---

## Plan review — 2026-09-15

**Assessment: revise before implementation.** The plan covers the M5 product scope, temporal tests, concurrency, recovery, assistant boundaries, exports, and closeout work. The following points need concrete decisions so implementation does not create competing sources of truth or an alternate accounting path.

### Blocking corrections

1. **Extend the existing paper portfolio API instead of adding a competing controller.** `ResearchPortfolioController` already owns `POST/GET /api/research/portfolios` and `ResearchService` already consumes those routes. A new `PaperPortfolioController` mapped to the same resource would create duplicate Spring mappings or a second contract. State that M5 will refactor/extend the existing controller and research component/service, add the `/:id` route, page the list, and replace the controller's current hard-coded `default` owner with the established scoped header convention.

2. **Choose one source of truth for activation.** `portfolios` already contains `paper_started_at`, initial cash, owner, mode, and currency. Do not duplicate those fields ambiguously in `paper_portfolios`. Keep the existing portfolio row authoritative for identity/funding/start time and use the extension table for the frozen active-segment configuration and tracked opening-equity observation. If multiple segments are intentionally supported, model a segment ID now; otherwise explicitly reject reactivation/configuration changes in M5.

3. **Do not create a parallel financial ledger.** `operations`, `ledger_entries`, `executions`, `portfolio_state`, `positions`, and `portfolio_snapshots` are already the authoritative accounting path. `paper_orders` must either be an execution-intent/result extension referencing the core `operation_id` and `execution_id`, or be removed. The plan must say that one transaction writes the core financial batch plus paper resolution state. A standalone paper order table with its own cash delta, basis, and realized gain would allow the two accounting records to diverge.

4. **Complete the durable corporate-action model.** V12 currently has no proposed row-level receivable, processed economic-action identity, or revision-conflict record even though the plan promises unpaid receivables and cross-snapshot de-duplication. Add normalized identities scoped by source namespace/listing/action, payload hash/terms, effective and availability times, processing state, linked operation/receivable, and a uniqueness rule that prevents the same economic action from booking twice across datasets. Aggregate `receivables_value` in a valuation is not enough.

5. **Normalize proposal evidence that needs integrity.** `target_weights_json` and `evaluation_inputs_json` may remain frozen export snapshots, but listing ranks/weights/reasons and data observation references need child tables with bounded cardinality, deterministic ordering, composite portfolio/dataset/listing scope, and terminal-parent immutability. Critical ownership and listing relationships cannot be enforced when they exist only in JSON.

6. **Define one state machine and its database transitions.** Calling proposals immutable while updating their status is ambiguous, and proposal `EXECUTED` overlaps intent `EXECUTED`. Specify legal proposal and intent transitions, terminal states, who owns the current execution state, retry behavior after `FAILED`, and the exact disposition when a new cycle arrives while an accepted intent is unresolved. Freeze proposal payload columns; guard lifecycle updates with compare-and-set and database constraints. Use append-only transition history where an audit of state changes is required.

7. **Add missing execution provenance and business identity.** An execution result needs listing, side, requested and executed quantity, shortfall/zero-order reason, raw-open observation reference, adopted dataset/checksum, market-effective time, observed/imported time, booked time, and links to core operation/execution rows. Reinvestment intents also need a source payment/action identity; requiring every intent to reference a strategy proposal does not describe payment-triggered S1/S3 reinvestment.

8. **Separate calendar authority from completed market observations.** The plan requires both current completed bars and a known future execution session, but V12 records only an adopted dataset. Define the immutable/versioned calendar source that can contain future sessions without pretending future bars exist, and persist its identity on proposals/intents. Use UTC instants for cutoff, evaluation, acceptance, scheduled open, observation, and booking; date or local-time columns alone are insufficient around calendar/time-zone boundaries.

### Required implementation clarifications

9. **Use an injectable `Clock` bean directly unless `AppClock` adds a demonstrated capability.** The prompt asks for a server-owned injectable clock, and the project favors the simplest implementation. No production endpoint may expose clock mutation. Coordinator scheduling should be disabled or controlled in tests, start only after migrations, use bounded work, and share the durable per-portfolio serialization used by manual processing.

10. **List concrete API routes and idempotency scopes before coding.** Define create, activate, adopt, evaluate, proposal list/detail, accept, reject, process, mode change, valuations/events, export, and research-assistant routes, including request/response status codes, pagination, owner checks, and which operations require `Idempotency-Key`. Include stable business-event uniqueness in addition to transport idempotency.

11. **Make data adoption transactional and explicit.** Clarify whether `adopted_dataset_id` is a current pointer updated only after an append-only adoption row succeeds, how compatibility is compared to the prior snapshot, and how an adoption conflict is represented without changing old proposals/fills/valuations. Persist source namespace and dataset/checksum on every decision and result that depends on it.

12. **Keep the research assistant separate from action-capable legacy chat.** Define discriminated tool inputs rather than “runId or portfolioId,” per-tool row/byte limits, evidence DTOs, stale-context cancellation, and the exact holdout exposure call. The research endpoint must not inherit legacy chat trade actions, executed-action refresh behavior, or unrelated conversation context. Add tests for prompt injection through dataset names/notes and for tool output truncation, as well as malformed model output and foreign IDs.

13. **Expand valuation schema and prospective metrics rules.** Add observation kind, data-quality/readiness status, last supported observation instant, missing-requirement details, adopted snapshot/calendar identity, and uniqueness rules preventing duplicate opening/session-close returns. State how positions are valued when one required listing is missing and ensure no “current” total or return is emitted from a partial mark.

14. **Use focused tests in addition to the end-to-end class.** Keep `PaperPortfolioIntegrationTest` for transaction, clock, restart, and concurrency evidence, but add pure unit tests for readiness/state transitions/allocator adapter, `@WebMvcTest` coverage for status codes and owner/idempotency contracts, and frontend service/component tests for pagination, stale response suppression, deep links, and exact strings. Avoid numeric targets such as “190+” or “50+”; report the actual final counts.

15. **Correct the frontend/file annotations and build command.** `paper.service.ts` is marked `[MODIFY]` although it does not exist. Prefer extending the existing `research.service.ts` unless a separate service has a clear bounded responsibility. The hard-coded `_npx` cache path is machine-specific; record it as the currently verified Node 24.21.0 binary for this checkout, but keep the plan's command portable and record the resolved executable/version in the closeout.

### Evidence and scope notes

- The M4 checkpoint statement is accurate: HEAD and annotated tag `m4-checkpoint` resolve to `9d5bc5d34f42e716fcf4d7c0a61ebd9146229965`.
- Node 24.21.0/npm 11.18.0 are present in the cited npm cache, while the default shell is Node 26.8.1/npm 11.19.0. Call the declared toolchain verified only after the M5 test and production-build commands actually pass with it.
- The native walkthrough should use native automation directly. Seeded future/pending/executed states are acceptable for display checks, while controlled-clock integration tests remain the causal timing evidence.
- Docker and a real-provider LLM call remain separate NOT VERIFIED checks unless they are actually run. No M6 provider, broker, deployment, or external messaging work belongs in this plan.
