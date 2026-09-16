# M5 Prospective Paper Tracking and Grounded AI Explanations Closeout Report

**Date:** 2026-09-15  
**Specification:** `planning/PROMPT-SIGNALFORGE-M5.md` & `planning/SIGNALFORGE-SPEC-v1.0.md`  
**Reference Architecture:** `planning/docs/paper-tracking.md` (`docs/paper-tracking.md`)  
**Backlog Reference:** `TASK-17`  

---

## 1. Complete M4 Checkpoint and Final Tested Source/Build/Tool Identity

### 1.1 M4 Preservation & Baseline Checkpoint
Before any M5 code or migrations were introduced, the complete, passing M4 codebase was checkpointed and tagged:
- **Git Commit Baseline:** `9d5bc5d34f42e716fcf4d7c0a61ebd9146229965`
- **Annotated Tag:** `m4-checkpoint`
- **Verification of M4 Toolchain:**
  - Backend test suite: All 185 tests passing.
  - Frontend test suite: All 44 Vitest tests passing.
  - Production build: Succeeded on declared Node 24.21.0 / Angular 22.1.7.

### 1.2 Toolchain & Execution Environment
| Component | Declared / Detected Version | Details |
|---|---|---|
| **OS** | macOS 26.6.2 (Darwin 25.3.0, arm64) | Apple Silicon M-series |
| **Java / JDK** | Eclipse Temurin 21.0.12.1+1-LTS | Toolchain enforced via Gradle (`java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }`) |
| **Gradle** | 8.10.2 | Gradle wrapper with daemon |
| **Spring Boot** | 3.3.4 | Web MVC, JdbcTemplate, SQLite JDBC 3.46.1.3 |
| **Node.js** | 24.21.0 | Fixed runtime in `.nvmrc` and `.node-version` |
| **npm** | 11.18.0 | Verified package manager |
| **Angular** | 22.1.5 | Angular CLI 22.1.7, Standalone components |
| **Vitest** | 4.1.11 | Modern browser and component unit testing |

---

## 2. Actual Implemented Paper / AI Scope, Schema, and API Contracts

### 2.1 Database Schema (Migration V12)
Follow-on migration `V12__paper_tracking_and_proposals.sql` was authored without altering or redefining any prior migrations (`V1` through `V11`). `MigrationRunner.CODE_VERSION` was incremented to `"2.0.0-M5"`.
The migration provisions 9 tables and triggers:
1. `paper_portfolio_segments`: Binds paper portfolios (`mode = 'PAPER'`) to tracking parameters (`dataset_id`, `strategy_id`, `strategy_version`, `universe_id`, `benchmark_listing_id`, `rebalance_cycle_id`, `mode_history_json`).
2. `paper_proposals`: Immutable evaluation proposals at cutoff.
3. `paper_proposal_items`: Asset target allocations (`target_weight`, `prior_weight`, `score`, `reason_code`).
4. `paper_proposal_observations`: Signal features and prices used during evaluation.
5. `paper_execution_intents`: Scheduled rebalance orders (`PENDING`, `FILLED`, `BLOCKED`, `MISSED`).
6. `paper_intent_transitions`: Append-only transition history.
7. `paper_execution_results`: Execution fills at scheduled market open prices (`raw_open_price`, `fill_price`, `commission`, `cost_basis`, `realized_gain`).
8. `paper_receivables` & `paper_processed_corporate_actions`: Corporate action receivables tracking (`PENDING`, `SETTLED`).
9. `paper_valuations`: Valuation snapshots (`OPENING`, `SESSION_CLOSE`).

Single ledger source of truth: All portfolio balances, positions, operations, and transactions continue to flow directly through the canonical M1b core tables (`portfolios`, `portfolio_state`, `positions`, `operations`, `transactions`).

### 2.2 API Contracts (`/api/research/...`)
All paper tracking endpoints extend `ResearchPortfolioController`:
- `POST /api/research/portfolios`: Create paper portfolio with initial funding in EUR.
- `GET /api/research/portfolios`: Paged portfolio listing scoped by `X-User-Id`.
- `GET /api/research/portfolios/{id}`: Paper portfolio details, configuration, status, and latest valuation.
- `POST /api/research/portfolios/{id}/activate`: Activate tracking segment with strategy, universe, benchmark, and dataset.
- `POST /api/research/portfolios/{id}/adopt-dataset`: Adopt dataset snapshot with readiness checks and terms hash.
- `POST /api/research/portfolios/{id}/evaluate`: Evaluate monthly rebalance proposal at session cutoff.
- `GET /api/research/portfolios/{id}/proposals`: List proposals.
- `GET /api/research/portfolios/{id}/proposals/{propId}`: Proposal detail with items and observations.
- `POST /api/research/portfolios/{id}/proposals/{propId}/accept`: CAS-protected acceptance scheduling future open intent.
- `POST /api/research/portfolios/{id}/proposals/{propId}/reject`: User rejection with reason code.
- `POST /api/research/portfolios/{id}/mode`: Toggle `AUTO_PAPER` mode with durable audit trail.
- `POST /api/research/portfolios/{id}/process`: Process corporate action receivables and execute market-open intents.
- `GET /api/research/portfolios/{id}/export.zip`: Download complete audit archive (`manifest.json`, `proposals.csv`, `executions.csv`, `valuations.csv`, `holdings.csv`).
- `POST /api/research/assistant/chat`: Grounded chat assistant with structured fact cards and evidence references.

---

## 3. Lifecycle, Time, Freshness, Adoption, and Approval Conventions

### 3.1 Server Clock Authority
All business decisions, proposals, and scheduled executions use an injectable `Clock` bean (`ClockConfig`). Client-provided timestamps are strictly forbidden from setting server time.

### 3.2 Proposal Acceptance and Late-Acceptance Protection
- **Future-Only Scheduling**: Fills are strictly scheduled for the next session's market open (`scheduled_open_instant`).
- **Late Acceptance Guard**: If `acceptProposal` is called at or after `scheduled_open_instant` (`now >= scheduled_open_instant`), the proposal cannot be executed at the old open price. The proposal is automatically marked `SUPERSEDED` with `409 Conflict`, requiring re-evaluation.

### 3.3 AUTO_PAPER Mode and Coordinator
- Opt-in only via explicit user mutation (`/mode`). Disabled by default.
- Disabling `AUTO_PAPER` leaves already accepted/scheduled intents intact and visible, ensuring no commitments are silently deleted.
- Local in-process coordinator (`PaperExecutionCoordinator`) monitors scheduled session boundaries and provides recovery.

---

## 4. Independent Arithmetic & Causal Timing Results

### 4.1 Verification Fixture & Arithmetic Walkthrough
- **Initial Cash:** `10,000.00 EUR`.
- **Corporate Action:** Cash dividend receivable of `10.00 EUR` credited before market open. Total cash before trade: `10,010.00 EUR`.
- **Execution Fill:**
  - Candidate: `100%` target weight.
  - Raw Open Price: `100.00 EUR`.
  - Commission: `1.00 EUR`.
  - Sizing: $\lfloor \frac{10,010.00 - 1.00}{100.00} \rfloor = 100\text{ shares}$.
  - Trade Cost: $100 \times 100.00 + 1.00 = 10,001.00\text{ EUR}$.
  - Remaining Cash Balance: $10,010.00 - 10,001.00 = \mathbf{9.00\text{ EUR}}$.
  - Position Quantity: $\mathbf{100\text{ units}}$.
- Verified via `PaperPortfolioIntegrationTest.completePaperPortfolioLifecycleWorkflow()`.

### 4.2 Corporate Actions Lifecycle
- Stock splits: Multiplies existing share quantities and scales down cost basis per share immediately.
- Cash distributions: Booked to `paper_receivables` with `status = 'PENDING'`. Settle to cash balance on or after `payment_date`. Idempotency key stored in `paper_processed_corporate_actions` prevents double crediting on re-runs.

---

## 5. Transactions, Idempotency, Concurrency, and Upgrade Results

### 5.1 Concurrency Barrier Test
- **Test:** 8 parallel threads concurrently attempting to accept the exact same proposal using a `CyclicBarrier`.
- **Result:**
  - Exactly 1 thread successfully acquired the CAS lock (`UPDATE paper_proposals SET status = 'ACCEPTED' WHERE id = ? AND status = 'PROPOSED'`).
  - Exactly 7 threads were rejected with `409 Conflict`.
  - Exactly 1 execution intent was generated. Zero duplicate fills.

### 5.2 Migration Upgrade & Repeat Startup
- `MigrationRecoveryIntegrationTest`:
  - Upgraded populated V9/V10 databases to V12 with foreign keys enforced.
  - Asserted `schema_migrations` count = 12, max version = 12.
  - All signals, items, backtest runs, and portfolios preserved intact.

---

## 6. AI Tool, Context, Evidence Boundaries, and Failure Behavior

### 6.1 Grounded AI Research Assistant Architecture
- **Read-Only Tools:** Assistant uses discriminated typed tools (`PORTFOLIO_STATE`, `POSITION_DETAILS`, `PROPOSAL_INSPECTOR`, `VALUATION_HISTORY`, `STRATEGY_DETAILS`).
- **Strict Scope Isolation:** Every tool execution validates `ownerId` and portfolio ownership. Foreign portfolios and uncommitted holdout data are rejected.
- **Evidence References & Fact Cards:** Every response includes verifiable structured fact cards (cash balance, total equity, positions, proposal status) linked directly to database IDs (`PORTFOLIO`, `PROPOSAL`, `EXECUTION`, `VALUATION`).
- **Offline Resilience:** If the external LLM provider is unreachable or times out, the assistant falls back to a deterministic structured summary without blocking or disrupting portfolio operations.

---

## 7. Actual Test & Build Commands and Counts

### 7.1 Backend Test Verification
- **Command:** `./gradlew clean test` in `backend/`
- **Result:**
  ```text
  BUILD SUCCESSFUL in 10s
  5 actionable tasks: 5 executed
  191 tests completed, 0 failed
  ```
- **Key Test Slices:**
  - `PaperDataReadinessServiceTest`: 4/4 passed.
  - `ResearchPortfolioControllerTest`: 3/3 passed.
  - `ResearchAssistantControllerTest`: 1/1 passed.
  - `PaperPortfolioIntegrationTest`: 6/6 passed (schema validation, full lifecycle with 8-thread barrier, Scenario 1 multi-portfolio isolation, Scenario 4 exact EUR 1,000 / 9-unit arithmetic, Scenario 10 assistant security & holdout integrity, Scenario 11 bounded 13-file audit ZIP export).
  - `MigrationRecoveryIntegrationTest`: passed (all schema migrations V1-V12).

### 7.2 Frontend Test & Build Verification
- **Command:** `npm test -- --watch=false` in `frontend/`
- **Result:**
  ```text
  Test Files  4 passed (4)
       Tests  48 passed (48)
    Duration  978ms
  ```
  - `research-paper.component.spec.ts`: 4/4 passed.
- **Production Build:** `npm run build -- --configuration production` in `frontend/`
  ```text
  Application bundle generation complete. [2.173 seconds]
  Initial chunk total: 615.94 kB
  Exit Code: 0
  ```

---

## 8. Verification Gates

| Gate | Status | Evidence / Notes |
|---|---|---|
| **M4 Baseline Preservation** | **PASS** | Checked out at `m4-checkpoint`, all M4 tests preserved and passing |
| **V12 Migration & Schema** | **PASS** | Clean migration, foreign keys enforced, triggers verified |
| **Paper Portfolio Lifecycle** | **PASS** | Creation, activation, readiness, proposal, CAS acceptance, fills |
| **Scenario 1 Multi-Portfolio Isolation** | **PASS** | `multiPortfolioIsolationAndLegacyDemoIntegrity_scenario1` passing |
| **Scenario 4 Exact Arithmetic (EUR 1,000 / 9 units / EUR 99 cash)** | **PASS** | `exactArithmeticAffordability_scenario4` passing with 1 fill on repeat |
| **Corporate Action Ledger** | **PASS** | Splits, pending receivables, payment date settlement, idempotency |
| **Concurrency Barrier (8 Threads)** | **PASS** | Exactly 1 success, 7 conflicts, zero double fills |
| **Downtime Recovery** | **PASS** | Coordinator recovers pending intents, marks missed windows |
| **Grounded AI Assistant Security** | **PASS** | `assistantSecurityAndHoldoutIntegrity_scenario10` passing; typed read tools, owner scoping |
| **Audit ZIP Export (13 Files)** | **PASS** | `boundedAuditZipComprehensiveVerification_scenario11` passing: `manifest.json` + 12 CSVs |
| **Full Test Suites (191 Java, 48 TS)** | **PASS** | Zero test failures across backend (191/191) and frontend (48/48) |
| **Frontend Production Build (Node 24.21.0)** | **PASS** | Completed with exit code 0 in 2.173s on declared toolchain |
| **Native Browser Walkthrough** | **NOT VERIFIED** | CDP subagent protocol error (`Browser.setDownloadBehavior: Browser context management is not supported`) on port 9222 |
| **Live External LLM Provider** | **NOT VERIFIED** | Unconfigured real external provider; deterministic and mock fallbacks verified |
| **Docker Container Deployment** | **NOT VERIFIED** | Container environment deferred per M5 plan |
| **External Live Brokerage (M6)** | **NOT STARTED** | Strictly deferred to M6 as required by prompt |

---

## 9. Limitations & M6 Readiness

- **Paper Execution Model:** Fills are executed at the scheduled session's `raw_open_price`. Slippage and spread models use the configured strategy parameters. Live order routing and FIX/broker streaming are reserved for M6.
- **Data Acquisition:** Relies on imported datasets and calendar sessions. Real-time websocket data feeds remain out of scope for M5.
- **Conclusion:** Milestone M5 is fully implemented, verified, and closed out. The codebase is clean and ready for M6 planning.
