# Milestone M5: Prospective Paper Tracking & Grounded AI Explanations Walkthrough

## Overview
Milestone M5 delivers prospective paper portfolio tracking (`mode = 'PAPER'`) in EUR, maintaining the single ledger source of truth established in M1b (`portfolios`, `portfolio_state`, `positions`, `operations`, `transactions`). It adds server `Clock` authority, immutable strategy proposals, CAS-protected acceptance with late-acceptance guards, corporate action receivables, automated execution with downtime recovery (`AUTO_PAPER`), a grounded AI research assistant with typed read tools, full frontend management at `/research/portfolios/:id`, and audit archive export (`ZIP`/`CSV`).

---

## Key Deliverables

### 1. Database Schema & Migrations
- Added `backend/src/main/resources/db/migration/V12__paper_tracking_and_proposals.sql` (schema version 12):
  - `paper_portfolio_segments`
  - `paper_proposals`, `paper_proposal_items`, `paper_proposal_observations`
  - `paper_execution_intents`, `paper_intent_transitions`, `paper_execution_results`
  - `paper_receivables`, `paper_processed_corporate_actions`
  - `paper_valuations`
  - Database triggers enforcing immutability on accepted/rejected proposals and terminal intent transitions.
- Updated `MigrationRunner.java` to `CODE_VERSION = "2.0.0-M5"`.

### 2. Backend Services & Controllers
- **`ClockConfig.java`**: Configurable injectable `Clock` bean for temporal testing and server-side authority.
- **`PaperDataReadinessService.java`**: Validates exchange calendar coverage, listing prices, corporate action alignment, and dataset snapshot terms hash.
- **`PaperPortfolioService.java`**: Orchestrates portfolio creation, activation, cutoff evaluation, CAS proposal acceptance, intent sizing against raw open prices, corporate actions (splits and dividend receivables), and valuation snapshots.
- **`PaperExecutionCoordinator.java`**: In-process scheduler for `AUTO_PAPER` mode, pre-open scheduling, and downtime catch-up recovery.
- **`PaperExportService.java`**: Generates RFC 4180-compliant audit archive ZIP containing `manifest.json` plus 12 CSV files (`adoptions.csv`, `proposals.csv`, `proposal_items.csv`, `proposal_observations.csv`, `intents.csv`, `intent_transitions.csv`, `execution_results.csv`, `receivables.csv`, `corporate_actions.csv`, `valuations.csv`, `holdings.csv`, `mode_history.csv`).
- **`ResearchAssistantService.java`**: Discriminated read-only tools (`PORTFOLIO_STATE`, `POSITION_DETAILS`, `PROPOSAL_INSPECTOR`, `VALUATION_HISTORY`, `STRATEGY_DETAILS`) scoped by owner, emitting structured fact cards and evidence references.
- **`ResearchPortfolioController.java` & `ResearchAssistantController.java`**: REST API endpoints for paper portfolio management, proposal review, export, and chat assistance.

### 3. Frontend UI & Services
- **`research.model.ts`**: TypeScript interfaces for paper portfolios, proposals, execution intents, corporate actions, valuations, and assistant chat.
- **`research.service.ts`**: Angular HTTP service methods for all paper operations and audit export blob streaming.
- **`ResearchPaperComponent`** (`research-paper.component.ts`, `.html`, `.css`): Complete UI with KPI cards, mode toggle, data readiness card, proposal action controls, holdings table, pending receivables, prospective chart, and audit ZIP download.
- **Routing & Navigation**: Added `/research/portfolios` and `/research/portfolios/:id` in `app.routes.ts` and `header.component.ts`, resolving portfolio ID from `ActivatedRoute` and handling browser popstate navigation.

### 4. Documentation & Reports
- **`docs/paper-tracking.md`** & **`planning/docs/paper-tracking.md`**: Complete architecture guide, schema definition, lifecycle state machines, corporate actions lifecycle, downtime recovery, and assistant integration.
- **`planning/reports/research-M5.md`**: Comprehensive M5 closeout report meeting all prompt specifications.

---

## Verification Results

### Backend Automated Verification
```bash
cd backend
./gradlew clean test
```
- **Result:** `BUILD SUCCESSFUL in 10s`
- **Total Tests:** 191 tests completed, 0 failed.
- **Highlights:**
  - `multiPortfolioIsolationAndLegacyDemoIntegrity_scenario1`: Verified multi-portfolio funding isolation and demonstrated that existing `LEGACY_DEMO` portfolios remain completely unmodified.
  - `exactArithmeticAffordability_scenario4`: Verified EUR 1,000.00 funding with EUR 1.00 fee and raw open 100.00 EUR buys exactly 9 units, leaves exactly EUR 99.00 cash, books EUR 901.00 acquisition cost, and repeat event execution is idempotent.
  - `assistantSecurityAndHoldoutIntegrity_scenario10`: Verified assistant read tools strictly enforce owner isolation and reject foreign contexts.
  - `boundedAuditZipComprehensiveVerification_scenario11`: Verified the audit ZIP archive produces exactly 13 non-empty files with RFC 4180 escaping and negative numeric decimal preservation.
  - `PaperPortfolioIntegrationTest.completePaperPortfolioLifecycleWorkflow`: Verified schema, full prospective lifecycle, corporate action dividend receivable, and an 8-thread concurrent acceptance barrier where exactly 1 thread succeeds and 7 receive `409 Conflict`.
  - `MigrationRecoveryIntegrationTest`: Verified clean upgrade of populated legacy databases to V12 with foreign keys enforced.
  - `PaperDataReadinessServiceTest`: Verified readiness checks, calendar boundary alignment, and terms hash validation.

### Frontend Automated Verification
```bash
cd frontend
npm test -- --watch=false
```
- **Result:** 4 test files passed, 48 passed tests (100% pass rate in 978ms).
- **Production Build:** `npm run build -- --configuration production` completed with exit code 0 in 2.173s on declared Node 24.21.0 toolchain.

### Browser Walkthrough Status
- Background execution of backend (`bootRun` on port 8000) and frontend (`ng serve` on port 4200) was verified with HTTP 200 responses.
- Native browser walkthrough using Chrome DevTools Protocol subagent on port 9222 encountered protocol limitation (`Browser.setDownloadBehavior: Browser context management is not supported`). In accordance with prompt guidelines, Docker and unconfigured real LLM are kept explicitly **NOT VERIFIED**, and browser walkthrough status is recorded with exact diagnostic.

---

## Checkpoint & Next Steps
- Baseline M4 preserved under git tag `m4-checkpoint`.
- Backlog task `TASK-17` updated with verified evidence.
- As instructed, M6 has NOT been started.
