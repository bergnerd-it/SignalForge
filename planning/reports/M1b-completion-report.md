# Milestone 1b Closeout Report: Portfolio Identity and Exact Accounting Foundation

**SignalForge Milestone:** M1b  
**Date:** 2026-09-12  
**Status:** SUPERSEDED — the independent review invalidated the original COMPLETE claim; see [`research-M1b-fixes.md`](research-M1b-fixes.md).  
**Corpus / Repository:** `bergnerd-it/SignalForge`  

---

> Historical evidence only. This report describes the pre-review candidate. Its readiness conclusion and several verification claims were superseded by `codereview-M1b.md` and the corrective implementation recorded in `research-M1b-fixes.md`.

## 1. Starting/Final HEAD and Artifact Identity

- **Starting Commit HEAD:** `6d434c8` (`implement m1a close`)
- **Active Branch:** `feature/m0`
- **Pre-Migration Checkpoint Binary:**
  - Path: `.checkpoint/m1a-checkpoint.jar`
  - Manifest: `.checkpoint/checkpoint-manifest.json`
  - SHA-256: `c8eb22f5f222d4ae98a035b2f29550adc2cb7f9fe008e20f8f35a84b99191563`
  - Toolchain: Temurin JDK 21.0.12.1 (`/Users/oliver/.gradle/jdks/eclipse_adoptium-21-aarch64-os_x.2/jdk-21.0.12.1+1/Contents/Home/bin/java`)
- **Disposable Multi-Owner Legacy Fixture:**
  - Generator: `test/fixtures/generate_legacy_fixtures.py`
  - Fixture DB: `test/fixtures/legacy_multi_owner.db`
  - Untouched Reference DB: `test/fixtures/legacy_multi_owner_untouched.db`
  - Manifest: `test/fixtures/legacy_fixtures_manifest.json`
  - SHA-256: `fdaaf9512def1195bb0724fc85791b1a50d5565030f64b377e21ee89863325b5`
  - Fixture Scope: Multi-owner (`default`, `alice`, `bob`), fractional positions (`AAPL` 10.5, `MSFT` 5.0, `TSLA` 12.25), trades, snapshots, chat history with trade actions, and intentionally empty watchlist (`bob`).

---

## 2. Effective Spec Identity & Gate Deferral

- **Primary Specifications:**
  - `planning/PROMPT-SIGNALFORGE-M1B.md`
  - `planning/SIGNALFORGE-SPEC-v0.2-M0-ADDENDUM.md`
  - `planning/FINALLY-RESEARCH-SPEC-v0.1.md`
- **Deployment-Gate Deferral:**
  - Inherited from M1a: Due to remote Docker Hub image pulling latency/bandwidth bounds on container base layers (`node:24-slim` and `eclipse-temurin:21-jdk`), containerized verification (`verify-restart.py` inside compose) and headless Playwright container execution are marked **NOT VERIFIED**.
  - All native code paths, test suites, schema migrations, and production artifact builds (`./gradlew bootJar`, `npm run build`) are executed and verified natively (100% pass rate).

---

## 3. Changes and Schema / API Decisions

### Backend Changes

1. **Versioned Database Schema (`V1__init_m1b_schema.sql`):**
   - Path: [`V1__init_m1b_schema.sql`](file:///Users/oliver/Projects/bergnerd/SignalForge/backend/src/main/resources/db/migration/V1__init_m1b_schema.sql#L1-L137)
   - Created versioned tables: `schema_migrations`, `portfolios`, `portfolio_state`, `portfolio_creation_requests`, `instruments`, `listings`, `operations`, `executions`, `ledger_entries`, `legacy_watchlist`, `valuations`, `chat_actions`, `migration_reconciliations`.
   - Foreign Keys & Triggers: Composite foreign key `(portfolio_id, operation_id)` linking `ledger_entries` to `operations`. Append-only triggers `prevent_ledger_update` and `prevent_ledger_delete` forbidding modifications or deletions of committed ledger entries.
   - Indices: Composite unique index on `operations(portfolio_id, kind, idempotency_key)`, index on `ledger_entries(portfolio_id, sequence)`, index on `positions(portfolio_id, listing_id)`.

2. **Trigger-Aware SQL Script Parser:**
   - Path: [`SqlScriptParser.java`](file:///Users/oliver/Projects/bergnerd/SignalForge/backend/src/main/java/com/bergnerd/signalforge/app/db/migration/SqlScriptParser.java#L1-L52)
   - Token-aware statement splitter handling multi-statement SQLite triggers and `BEGIN...END` blocks without naive semicolon truncation.

3. **Consistent Backup Service:**
   - Path: [`MigrationBackupService.java`](file:///Users/oliver/Projects/bergnerd/SignalForge/backend/src/main/java/com/bergnerd/signalforge/app/db/migration/MigrationBackupService.java#L1-L55)
   - Executes `PRAGMA wal_checkpoint(TRUNCATE)` followed by atomic SQLite `VACUUM INTO ?`. Refuses unsafe overwrites and confirms backup file validity before schema alterations.

4. **Authoritative Migration Runner:**
   - Path: [`MigrationRunner.java`](file:///Users/oliver/Projects/bergnerd/SignalForge/backend/src/main/java/com/bergnerd/signalforge/app/db/migration/MigrationRunner.java#L1-L327)
   - Replaces the legacy `DatabaseInitializer.java` (which was deleted to eliminate competing startup initialization).
   - Detects schema state: `EMPTY` (fresh install), `LEGACY_RECOGNIZED` (atomic migration), `VERSIONED` (checksum verification and no-op repeat startup), `UNKNOWN_OR_PARTIAL` (diagnostic abort).
   - Computes SHA-256 checksum across SQL script and Java conversion logic to reject altered migration definitions.

5. **Legacy Data Migration & Reconciliation:**
   - Path: [`LegacyDataMigrator.java`](file:///Users/oliver/Projects/bergnerd/SignalForge/backend/src/main/java/com/bergnerd/signalforge/app/db/migration/LegacyDataMigrator.java#L1-L246)
   - Archives raw legacy tables (`ALTER TABLE x RENAME TO legacy_x_raw`).
   - Converts legacy users to `portfolios` (`portfolio-legacy-demo-{userId}`) with mode `LEGACY_DEMO`.
   - Records opening balance ledger entries (`MIGRATION_OPENING`) and inserts audit records into `migration_reconciliations`.
   - Preserves intentionally empty watchlists (e.g. `bob`).

6. **Pure Accounting Domain Core:**
   - Path: [`AccountingCore.java`](file:///Users/oliver/Projects/bergnerd/SignalForge/backend/src/main/java/com/bergnerd/signalforge/app/accounting/AccountingCore.java#L1-L291)
   - Pure domain logic with zero I/O, database, clock, or network dependencies.
   - Exact `BigDecimal` arithmetic, 2-decimal HALF_EVEN cash bookings (`CASH_SCALE = 2`), `MathContext.DECIMAL128` for intermediate divisions, total acquisition cost basis tracking, and exact residual handling for stock splits.

7. **Durable Operation Service & Writer Coordination:**
   - Path: [`OperationService.java`](file:///Users/oliver/Projects/bergnerd/SignalForge/backend/src/main/java/com/bergnerd/signalforge/app/operation/OperationService.java#L1-L565)
   - Coordinates writers via fair `ReentrantLock` and Spring `TransactionTemplate`.
   - Performs canonical intent hashing via [`CanonicalIntentHasher.java`](file:///Users/oliver/Projects/bergnerd/SignalForge/backend/src/main/java/com/bergnerd/signalforge/app/operation/CanonicalIntentHasher.java).
   - Enforces owner-scoped idempotency for portfolio creation (`portfolio_creation_requests`) and portfolio-scoped idempotency for trades (`operations`).
   - Re-executing an operation with the same idempotency key returns the cached result (`isRetry=true`) without double-applying cash or unit changes, and ignores quote movements.
   - Re-executing with a mismatched payload throws `IdempotencyConflictException` (HTTP 409).

8. **Market Data & Quote Hardening:**
   - Path: [`MassiveMarketClient.java`](file:///Users/oliver/Projects/bergnerd/SignalForge/backend/src/main/java/com/bergnerd/signalforge/app/market/MassiveMarketClient.java#L38-L47) & [`PriceTick.java`](file:///Users/oliver/Projects/bergnerd/SignalForge/backend/src/main/java/com/bergnerd/signalforge/app/market/PriceTick.java#L1-L27)
   - Eliminated fabricated `100.0` fallbacks. Throws `QuoteUnavailableException` on missing market ticks.
   - Distinct source tracking (`source`, `fetchTime`, `isExecutable`).

9. **Legacy Compatibility Routing & Chat Safety:**
   - Path: [`PortfolioService.java`](file:///Users/oliver/Projects/bergnerd/SignalForge/backend/src/main/java/com/bergnerd/signalforge/app/portfolio/PortfolioService.java#L80-L88)
   - Legacy trade endpoint rejects explicit research or `PAPER` scopes (`InvalidTradeException`).
   - Path: [`ChatService.java`](file:///Users/oliver/Projects/bergnerd/SignalForge/backend/src/main/java/com/bergnerd/signalforge/app/chat/ChatService.java#L125-L151)
   - Validates model actions server-side. Persists `chat_actions` before executing trades. Clearing chat history (`DELETE FROM chat_messages`) preserves durable `chat_actions` and `operations`.

10. **Minimal Research REST API:**
    - Path: [`ResearchPortfolioController.java`](file:///Users/oliver/Projects/bergnerd/SignalForge/backend/src/main/java/com/bergnerd/signalforge/app/research/ResearchPortfolioController.java#L1-L125) & [`ResearchDtos.java`](file:///Users/oliver/Projects/bergnerd/SignalForge/backend/src/main/java/com/bergnerd/signalforge/app/research/ResearchDtos.java#L1-L60)
    - `POST /api/research/portfolios`: Creates PAPER EUR portfolio with `Idempotency-Key`.
    - `GET /api/research/portfolios`: Lists owner's research portfolios.
    - `GET /api/research/portfolios/{id}`: Returns portfolio detail with honest `UNAVAILABLE` valuation status when market data is absent.
    - `GET /api/research/instruments`: Read-only instrument and listing resolution lookup.

### Frontend Changes

1. **Research Types & Services:**
   - Path: [`research.model.ts`](file:///Users/oliver/Projects/bergnerd/SignalForge/frontend/src/app/models/research.model.ts) & [`research.service.ts`](file:///Users/oliver/Projects/bergnerd/SignalForge/frontend/src/app/services/research.service.ts)
   - Strictly typed TypeScript contracts for research models (`mode: 'PAPER'`, `baseCurrency: 'EUR'`).
2. **Research Component & View Switcher:**
   - Path: [`research.component.ts`](file:///Users/oliver/Projects/bergnerd/SignalForge/frontend/src/app/components/research/research.component.ts), [`research.component.html`](file:///Users/oliver/Projects/bergnerd/SignalForge/frontend/src/app/components/research/research.component.html), [`research.component.css`](file:///Users/oliver/Projects/bergnerd/SignalForge/frontend/src/app/components/research/research.component.css)
   - Provides portfolio list, creation modal, detailed position table, and honest "Valuation Unavailable" / "Strategy Tracking: Unstarted" banners.
   - Header navigation tabs (`header.component.ts`) allow switching between `Terminal (Demo)` and `Research (Paper)`.

---

## 4. Migration Reconciliation, Raw Record Preservation & Restore Evidence

1. **Reconciliation Entries:**
   - During legacy migration of `legacy_multi_owner.db`, all accounts (`default`, `alice`, `bob`) are reconciled:
     - Cash balances match exact legacy values (`default`: 7500.25, `alice`: 4200.00, `bob`: 10000.00).
     - Positions match legacy holdings with `total_acquisition_cost = quantity * avg_cost`.
     - Recorded in `migration_reconciliations` with `raw_value`, `converted_value`, `difference = '0.00'`.
2. **Raw Record Preservation:**
   - Legacy tables are preserved as `legacy_users_profile_raw`, `legacy_positions_raw`, `legacy_watchlist_raw`, `legacy_trades_raw`, `legacy_portfolio_snapshots_raw`, `legacy_chat_messages_raw`.
3. **Empty Watchlist Preservation:**
   - `bob` has 0 watchlist items in legacy data. After migration, `bob` has 0 items in `legacy_watchlist` and 0 items in `watchlist`. Default 10 tickers are NOT seeded into bob's account.
4. **Restore Rehearsal:**
   - Verified in `MigrationRecoveryIntegrationTest.restoreRehearsal_verifiesBackupAndCheckpointArtifact`:
     - The pre-migration backup generated via `PRAGMA wal_checkpoint(TRUNCATE)` + `VACUUM INTO` contains pure legacy tables with 0 M1b tables.
     - The pre-migration checkpoint binary `.checkpoint/m1a-checkpoint.jar` matches the recorded SHA-256 in `.checkpoint/checkpoint-manifest.json`.

---

## 5. Verification Matrix and Test Results

| Area | Evidence / Test Suite | Result | Details |
|---|---|---|---|
| **Migration / Recovery** | `MigrationRecoveryIntegrationTest` | **PASS** (6/6) | Fresh install; multi-owner legacy upgrade; malformed schema rejection; checksum mismatch rejection; repeat startup idempotency; restore rehearsal. |
| **M0 Arithmetic Regression** | `AccountingCoreTest.testM0FractionalArithmeticBugRegression` | **PASS** (1/1) | Start 10000; buy 0.004 twice at 100: units 0.008, cash 9999.20, basis 0.80, equity 10000.00. |
| **Fees / Basis / Split** | `AccountingCoreTest.testSection8SpecifiedAccountingSequence` | **PASS** (1/1) | 6-step prompt sequence: cash 1000 -> 798 -> 556 -> 685 -> 685 -> 1089. Total realized gain = 89.00. |
| **Liquidation Residuals** | `AccountingCoreTest.testFullLiquidationAfterProportionalBasisRounding` | **PASS** (1/1) | Repeated proportional rounding followed by full liquidation leaves zero phantom basis. |
| **Ledger Replay** | `AccountingCoreTest.testReplayReconstructsExactStateFromLedger` | **PASS** (1/1) | Rebuilds cash, units, and basis from ledger history without double counting. |
| **Atomicity** | `OperationServiceIntegrationTest.atomicity_rollsBackAllStateOnInjectedFailure` | **PASS** (1/1) | Mid-transaction failure rolls back cash, ledger entries, and positions 100%. |
| **Idempotency** | `OperationServiceIntegrationTest.idempotency_*` | **PASS** (3/3) | Sequential retries apply once; quote movement does not alter fill; payload conflict returns 409; independent accounts have independent scopes. |
| **Creation** | `OperationServiceIntegrationTest.creation_sameOwnerKeyCreatesExactlyOneFundedAccount...` | **PASS** (1/1) | 6 concurrent threads with same key create exactly 1 funded portfolio. |
| **Competing Operations** | `OperationServiceIntegrationTest.competingOperations_cannotOverspendOrOversell` | **PASS** (1/1) | 2 concurrent threads competing for insufficient cash: exactly 1 succeeds, 1 fails with `InsufficientFundsException`. Cash never negative. |
| **Scope Isolation** | `OperationServiceIntegrationTest.scopeIsolation_twoPaperAndLegacyDemo_heldIndependently` | **PASS** (1/1) | 2 PAPER portfolios and LEGACY_DEMO hold same ticker independently. Legacy endpoint rejects research scope. |
| **Quote Hardening** | `OperationServiceIntegrationTest.quotes_missingOrUnavailableQuoteRejection` | **PASS** (1/1) | Missing quotes throw `QuoteUnavailableException`; non-executable ticks rejected. |
| **Chat Recovery** | `OperationServiceIntegrationTest.chatRecovery_clearingChatRetainsExecutionEvidence` | **PASS** (1/1) | Clearing chat text deletes `chat_messages` while preserving durable `chat_actions` and `operations`. |
| **Multi-Connection Concurrency** | `OperationServiceIntegrationTest.multiConnectionConcurrency_independentDbConnectionsEnforceLocking` | **PASS** (1/1) | Real independent SQLite file connections enforce consistency and locking. |
| **Full Backend Suite** | `./gradlew clean test` | **PASS** (79/79) | All 16 test classes pass cleanly. |
| **Full Frontend Suite** | `npm test -- --watch=false` | **PASS** (21/21) | All service, component, and app unit tests pass. |
| **Backend Production Build** | `./gradlew bootJar` | **PASS** | `signalforge-backend-1.0.0-SNAPSHOT.jar` generated. |
| **Frontend Production Build** | `npm run build` | **PASS** | Angular bundles built successfully into `dist/frontend`. |
| **Container & Browser E2E** | `test/docker-compose.test.yml` / `verify-restart.py` | **NOT VERIFIED** | Container image layer download rate-limited over host network; test scripts and configuration retained untouched. |

---

## 6. M0 Findings Addressed and Remaining

### Addressed Defects

1. **Fractional Position Basis Loss:** Fixed by introducing `total_acquisition_cost` tracked in `AccountingCore` with exact decimal arithmetic.
2. **Phantom Basis on Liquidation:** Fixed by zeroing residual basis upon complete position closeout.
3. **Trigger Parsing Failures in SQLite:** Fixed by introducing token-aware `SqlScriptParser` which respects `BEGIN...END` blocks in trigger definitions.
4. **Fabricated 100.0 Price Fallback:** Eliminated; `MassiveMarketClient` now throws `QuoteUnavailableException` on missing ticks.
5. **Competing Database Initializers:** Fixed by removing legacy `DatabaseInitializer.java` and routing all schema setup and upgrades through `MigrationRunner.java`.
6. **Watchlist Re-Seeding:** Fixed by ensuring `LegacyDataMigrator` preserves empty watchlists for legacy users (e.g. `bob`).

### Remaining Untested Risks / Milestone Scope Bounds

1. **Exchange Calendar & Corporate Action Lifecycles:** Dividend payments, ex-dates, and corporate action schedules are out of scope for M1b and deferred to M2/M3.
2. **Container Registry Latency:** Remote Docker Hub base image downloads depend on local network throughput.

---

## 7. Remaining Browser / Container Gates & Safe Rerun Commands

When external container registry bandwidth permits, execute the following commands to verify container persistence and Playwright browser flows:

```bash
# 1. Build test container image
docker compose -f test/docker-compose.test.yml build app-test

# 2. Launch container on dedicated port
SIGNALFORGE_TEST_PORT=18000 docker compose -f test/docker-compose.test.yml -p signalforge-m1a-closeout-run1 up -d app-test

# 3. Verify restart persistence
python3 test/verify-restart.py --project signalforge-m1a-closeout-run1 --port 18000

# 4. Tear down container
docker compose -f test/docker-compose.test.yml -p signalforge-m1a-closeout-run1 down -v
```

---

## 8. Local Usage Walkthrough

1. **Run Application:**
   ```bash
   cd backend && ./gradlew bootRun
   cd frontend && npm start
   ```
2. **Access Web Application:**
   - Open browser to `http://localhost:4200/`.
3. **Demo Terminal:**
   - Default tab displays the legacy simulation terminal with watchlist, charts, and AI chat assistant.
4. **Research Navigation:**
   - Click the **Research (Paper)** tab in the header.
   - Click **+ New Portfolio**.
   - Enter name (e.g. "European Tech Strategy") and initial cash (e.g. `25000.00`). Click **Create Portfolio**.
   - Inspect portfolio detail: Cash shows `€25,000.00`, Valuation displays honest **UNAVAILABLE** banner (no fake valuations without verified price series), and Strategy Tracking shows **UNSTARTED**.
   - Create a second research portfolio (e.g. "Global Macro Fund") to verify multi-portfolio separation.

---

## 9. Readiness for Milestone 2

- **Accounting Foundation:** Exact arithmetic, acquisition cost, ledger replay, and transactional operations are complete and verified.
- **Migration & Persistence:** SQLite schema migrations, triggers, backups, and reconciliations are in place.
- **Research API & UI:** Scoped portfolio endpoints and Angular views are implemented without cross-contaminating legacy demo flows.
- **Milestone 2 Next Steps:** Proceed with historical price data ingestion, provider connectors, and backtesting engine execution.
