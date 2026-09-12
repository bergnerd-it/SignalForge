# SignalForge M2 — Historical Market Data and Research Foundation Report

**Document:** `planning/reports/research-M2.md`  
**Date:** 2026-09-12  
**Specification:** [PROMPT-SIGNALFORGE-M2.md](file:///Users/oliver/Projects/bergnerd/SignalForge/planning/PROMPT-SIGNALFORGE-M2.md)  
**Status:** M2 Core, Ingestion, Quality, History, and UI Gates **PASS**; Docker/Container Verification **NOT VERIFIED** (deferred)  
**Superseded Reports:** [`M2-completion-report.md`](file:///Users/oliver/Projects/bergnerd/SignalForge/planning/reports/M2-completion-report.md) is hereby **SUPERSEDED** and corrected by this report.

---

## 1. M1b Checkpoint and Source Identity

- **M1b Base Commit:** `4df08744a68c9ab25e5181a8d13317ce2bfd62b3` (`implement m1b`) / `e072bdf3478d851f3d3eb8ce5bd152ad535b609b` (`implement m2`)
- **Active Working Branch:** `feature/m2`
- **Reviewed Commit Hash:** `e072bdf3478d851f3d3eb8ce5bd152ad535b609b`
- **Working Tree State:** The reviewed commit plus the M2 closeout corrections documented herein.
- **Tracked Modified Files:**
  - `backend/src/main/java/com/bergnerd/signalforge/app/research/historical/HistoricalBundleParser.java`
  - `backend/src/main/java/com/bergnerd/signalforge/app/research/historical/HistoricalDataController.java`
  - `backend/src/main/java/com/bergnerd/signalforge/app/research/historical/HistoricalDataValidator.java`
  - `backend/src/main/java/com/bergnerd/signalforge/app/research/historical/HistoricalDtos.java`
  - `backend/src/main/java/com/bergnerd/signalforge/app/research/historical/HistoricalHistoryService.java`
  - `backend/src/main/java/com/bergnerd/signalforge/app/research/historical/HistoricalImportJobService.java`
  - `backend/src/test/java/com/bergnerd/signalforge/app/research/historical/HistoricalDataValidatorTest.java`
  - `backend/src/test/java/com/bergnerd/signalforge/app/research/historical/HistoricalImportIntegrationTest.java`
  - `frontend/src/app/app.ts`
  - `frontend/src/app/components/research-data/research-data.component.css`
  - `frontend/src/app/components/research-data/research-data.component.html`
  - `frontend/src/app/components/research-data/research-data.component.ts`
  - `frontend/src/app/models/historical-data.model.ts`
  - `frontend/src/app/services/historical-data.service.ts`
  - `frontend/src/app/services/services.spec.ts`
- **Database Safety Isolation:** All testing and local verification used strictly ephemeral SQLite instances (`/tmp/signalforge-disposable-browser-test.db` and JUnit temporary folders via `TemporarySqliteInitializer`). The user's working database was never opened, copied, migrated, or mutated.

---

## 2. Implemented Schema, File/API Contract, and Important Decisions

### 2.1 Database Schema (`V3__historical_datasets_and_import.sql`)

The database schema strictly adheres to SQLite with foreign keys enabled. It consists of 5 data tables and 1 job queue table:

1. **`datasets`**:
   - Columns: `id` (TEXT PK), `name` (TEXT NOT NULL), `source` (TEXT NOT NULL), `classification` (TEXT NOT NULL), `schema_version` (TEXT NOT NULL), `parser_version` (TEXT NOT NULL), `input_checksum` (TEXT NOT NULL), `content_checksum` (TEXT NOT NULL), `manifest_json` (TEXT NOT NULL), `coverage_start` (TEXT NOT NULL), `coverage_end` (TEXT NOT NULL), `validation_status` (TEXT NOT NULL), `validation_findings_json` (TEXT NOT NULL), `quality_label` (TEXT NOT NULL), `imported_at` (TEXT NOT NULL), `created_at` (TEXT NOT NULL).
   - *(Correction to prior report: The provider column is named `source`, not `provider`).*
2. **`dataset_listings`**:
   - Columns: `dataset_id` (FK to `datasets(id)`), `listing_id` (TEXT NOT NULL), `instrument_id` (TEXT NOT NULL), `symbol` (TEXT NOT NULL), `venue` (TEXT NOT NULL), `quote_currency` (TEXT NOT NULL), `calendar_id` (TEXT NOT NULL), `inception_date` (TEXT), `termination_date` (TEXT), `isin` (TEXT).
   - Primary Key: `(dataset_id, listing_id)`.
3. **`dataset_sessions`**:
   - Columns: `dataset_id` (FK to `datasets(id)`), `calendar_id` (TEXT NOT NULL), `session_date` (TEXT NOT NULL), `open_time` (TEXT NOT NULL), `close_time` (TEXT NOT NULL), `session_type` (TEXT NOT NULL CHECK(`session_type` IN ('TRADING', 'CLOSED'))).
   - Primary Key: `(dataset_id, calendar_id, session_date)`.
   - *(Correction to prior report: Column is `session_type`, not `is_half_day`).*
4. **`historical_bars`**:
   - Columns: `dataset_id` (FK to `datasets(id)`), `listing_id` (TEXT NOT NULL), `session_date` (TEXT NOT NULL), `open` (TEXT NOT NULL), `high` (TEXT NOT NULL), `low` (TEXT NOT NULL), `close` (TEXT NOT NULL), `volume` (BIGINT), `available_at` (TEXT NOT NULL).
   - Primary Key: `(dataset_id, listing_id, session_date)`.
   - *(Correction to prior report: There is NO `turnover` column in `historical_bars`; OHLC values are exact TEXT decimal strings).*
5. **`historical_actions`**:
   - Columns: `dataset_id` (FK to `datasets(id)`), `action_id` (TEXT NOT NULL), `listing_id` (TEXT NOT NULL), `action_type` (TEXT NOT NULL CHECK(`action_type` IN ('SPLIT', 'CASH_DISTRIBUTION'))), `effective_date` (TEXT NOT NULL), `available_at` (TEXT NOT NULL), `split_ratio_numerator` (INTEGER), `split_ratio_denominator` (INTEGER), `distribution_amount` (TEXT), `distribution_currency` (TEXT), `payment_date` (TEXT), `payment_instant` (TEXT).
   - Primary Key: `(dataset_id, action_id)`.
6. **`import_jobs`**:
   - Columns: `id` (TEXT PK), `request_key` (TEXT UNIQUE NOT NULL), `input_checksum` (TEXT NOT NULL), `status` (TEXT NOT NULL), `dataset_id` (TEXT), `progress_pct` (INTEGER NOT NULL), `message` (TEXT), `error_detail` (TEXT), `created_at` (TEXT NOT NULL), `updated_at` (TEXT NOT NULL).

### 2.2 Database Triggers (Exact Count: 11)

There are exactly **11 SQL triggers** in `V3__historical_datasets_and_import.sql`:
- **5 Immutability UPDATE triggers**: `prevent_datasets_update`, `prevent_dataset_listings_update`, `prevent_dataset_sessions_update`, `prevent_historical_bars_update`, `prevent_historical_actions_update` (raise SQLite FAIL on any UPDATE).
- **5 Immutability DELETE triggers**: `prevent_datasets_delete`, `prevent_dataset_listings_delete`, `prevent_dataset_sessions_delete`, `prevent_historical_bars_delete`, `prevent_historical_actions_delete` (raise SQLite FAIL on any DELETE).
- **1 Bar Validation trigger**: `validate_historical_bars_insert` (validates date format, available_at timestamp format, and non-negative volume).
*(Correction: Earlier claims of separate OHLC/positive/dividend/split triggers in SQL were inaccurate; domain validations are strictly performed in `HistoricalDataValidator.java` outside database write transactions).*

### 2.3 API Contracts and Pagination

All endpoints reside under `/api/research`:

| Endpoint | Method | Parameters | Response / Behavior |
|---|---|---|---|
| `/api/research/imports` | POST | `Idempotency-Key` header, multipart `file` | Returns HTTP 202 ACCEPTED with `ImportJobResponse` for new queueing, HTTP 200 OK for completed replay/deduplication, HTTP 409 CONFLICT for key collisions with changed bytes. |
| `/api/research/jobs/{id}` | GET | `id` (path) | Returns `ImportJobResponse` (status: QUEUED, RUNNING, COMPLETED, FAILED, INTERRUPTED). |
| `/api/research/datasets` | GET | `limit` (default 50, max 200), `offset` | Returns `PagedResponse<DatasetSummary>`. |
| `/api/research/datasets/{id}` | GET | `id` (path) | Returns `DatasetDetail` with manifest and validation findings. |
| `/api/research/datasets/{id}/listings` | GET | `limit` (default 50, max 200), `offset` | Returns `PagedResponse<DatasetListing>`. |
| `/api/research/datasets/{id}/sessions` | GET | `limit` (default 100, max 1000), `offset` | Returns `PagedResponse<DatasetSession>`. |
| `/api/research/datasets/{id}/actions` | GET | `limit` (default 50, max 1000), `offset` | Returns `PagedResponse<DatasetAction>`. |
| `/api/research/datasets/{id}/history/{listingId}` | GET | `start`, `end`, `asOf`, `limit` (default 1000, max 5000), `offset` | Returns `ListingHistoryResponse` including `totalBars`, `returnedBars`, `limit`, `offset`, `isTruncated`, and ordered daily bars/actions. |

### 2.4 Important Implementation Decisions

1. **Atomic Publication & Success:** Dataset rows, listings, sessions, bars, actions, and job status `COMPLETED` are committed in a single atomic transaction via `transactionTemplate.executeWithoutResult`. A failure mid-import rolls back the entire dataset; no partially visible dataset is ever exposed.
2. **Deterministic Bounded Pagination:** All list and history endpoints enforce deterministic sorting (`ORDER BY ... LIMIT ? OFFSET ?`) and return explicit pagination continuation metadata. The UI renders a warning alert banner and navigation buttons whenever `isTruncated` is true.
3. **Upload Caps Before In-Memory Read:** `HistoricalDataController` inspects `file.getSize()` against `MAX_COMPRESSED_BYTES` (20 MB) *before* calling `file.getBytes()`, preventing memory exhaustion.
4. **Archive Protections:** `HistoricalBundleParser` enforces:
   - Compressed size cap: 20 MB.
   - Expanded size cap: 100 MB.
   - Max file count: 10 files.
   - Max rows per file: 500,000 rows.
   - Symlink rejection: Rejects `isSymbolicLink` and zip unix mode `0120000`.
   - Path traversal rejection: Entries with `..`, leading slashes, or nested directories are rejected.
   - Duplicate entry rejection: Rejects duplicate filenames inside the archive.
   *(Note: No compression-ratio requirement was added, as instructed).*
5. **Quality Discontinuity Diagnostic:** `HistoricalDataValidator` implements `checkSplitPriceDiscontinuities`, which flags pre/post split price shifts as `SPLIT_PRICE_DISCONTINUITY_DIAGNOSTIC` findings with severity `INFO` or `WARNING` without requiring prices to follow mathematical split ratios exactly.
6. **Frontend Routing & Lifecycle:** `ResearchDataComponent` uses Angular zoneless/ChangeDetectorRef triggers, `takeUntilDestroyed` subscription teardown, and `window.addEventListener('popstate')` in `App` so `/research/data` deep links, page reloads, and browser Back/Forward navigation maintain flawless state.

---

## 3. Fixture/Source Classification, Validation Examples, and Limitations

### 3.1 Fixture Inventory

Generated via `test/fixtures/historical/generate_fixtures.py`:

| Fixture File | Classification | Primary Purpose | Validation Outcome |
|---|---|---|---|
| `valid-sample-bundle.zip` | `SYNTHETIC` | Multi-listing valid reference (closures, 2:1 split, cash dividend) | `VALIDATED` (Status: VALID, Quality: SYNTHETIC) |
| `invalid-missing-bar.zip` | `SYNTHETIC` | Omitted trading-session bar on active exchange day | `REJECTED` (`MISSING_TRADING_BAR`) |
| `invalid-adjusted-data.zip` | `SYNTHETIC` | Manifest declares `ADJUSTED` prices | `REJECTED` (`UNSUPPORTED_PRICE_CONVENTION`) |
| `invalid-duplicate-listing.zip` | `SYNTHETIC` | Duplicate listing identifier across instruments | `REJECTED` (`DUPLICATE_LISTING_ID`) |
| `invalid-bad-action-date.zip` | `SYNTHETIC` | Action effective date outside manifest coverage | `REJECTED` (`ACTION_OUTSIDE_COVERAGE`) |
| `invalid-traversal-bundle.zip` | `SYNTHETIC` | ZIP contains traversal paths (`../`) | `REJECTED` (`PATH_TRAVERSAL_DETECTED`) |

### 3.2 As-Of Availability & Knowledge-Time Semantics

- Every bar record specifies `available_at`. The validator enforces `available_at >= session.close_time` to prevent lookahead where future closing prices are recorded as known before market close.
- As-of filtering queries with `asOf = T` filter `WHERE available_at <= T`.
- **CRITICAL DISTINCTION:** As-of filtering strictly excludes data records timestamped after the query cutoff time. **It DOES NOT and CANNOT guarantee the complete absence of lookahead bias in real-world trading.** Upstream market data vendors may restate financial figures, provide survivorship-biased universes, or apply retroactive corporate action adjustments. `asOf` filtering proves software point-in-time querying behavior only.

---

## 4. Actual Commands, Tool Versions, Test Counts, and Browser Results

### 4.1 Toolchain Versions

- **OS:** macOS (Darwin 24.6.0)
- **Java:** Eclipse Adoptium OpenJDK `21.0.12.1+1`
- **Gradle:** `8.10.2`
- **Spring Boot:** `3.3.4`
- **Node:** `24.21.0` (pinned via `.nvmrc` and `.node-version`)
- **npm:** `11.18.0`
- **Angular CLI / Core:** `22`

### 4.2 Test Suite Execution

```bash
# 1. Backend Clean Test Suite
./gradlew clean test
# Result: 126 tests executed, 0 failures, 0 errors, 0 skipped. Duration: 8s.

# 2. Frontend Vitest Suite
npm test -- --watch=false
# Result: 3 test files, 29 tests passed, 0 failed. Duration: 826ms.

# 3. Frontend Production Build
npm run build
# Result: Application bundle generation complete. Output in frontend/dist/frontend.
```

### 4.3 Native Browser Walkthrough

Verified using `browser_subagent` recording on live instances (`http://localhost:4200` connected to backend port `8000` with disposable SQLite database):
1. **Initial Empty State:** Navigated to `/research/data`. Verified header title, empty state banner, and active "IMPORT BUNDLE" action.
2. **Native Upload & Modal:** Uploaded `valid-sample-bundle.zip`. Verified upload progress transition from QUEUED to COMPLETED.
3. **Dataset Presentation:**
   - Provenance badge displayed `SYNTHETIC`, `RAW`, `VALIDATED`.
   - Coverage displayed `2024-01-01` to `2024-01-10`.
   - Quality findings rendered in `VALIDATION FINDINGS` section.
   - Listings sidebar rendered `listing-eur-syn-1` and `listing-eur-syn-2`.
   - Raw daily OHLCV bars table rendered all columns with exact decimal strings.
   - Corporate actions table rendered `SPLIT 2:1` on `listing-eur-syn-1` and `CASH_DISTRIBUTION 1.25 EUR` on `listing-eur-syn-2`.
4. **Idempotent Replay:** Re-submitted identical bundle and idempotency key. Returned HTTP 200 with existing dataset reference without duplicate rows.
5. **Rejected Invalid Upload:** Submitted `invalid-missing-bar.zip`. Job banner displayed red `FAILED` with `MISSING_TRADING_BAR` error detail. Verified earlier published dataset remained active and untouched.
6. **As-Of Filter Interaction:** Set cutoff to `2024-01-04T23:59:59Z`. Bars table updated to show exactly 3 bars (Jan 2, 3, 4), excluding subsequent dates.
7. **Listing Switching:** Clicked between `listing-eur-syn-1` and `listing-eur-syn-2`. Both bars and corporate action tables refreshed immediately.
8. **Direct Refresh & History Navigation:** Direct browser reload at `/research/data` restored state seamlessly. Navigating to `/demo` and clicking browser Back restored `/research/data` via popstate listener.

---

## 5. Evidence for Retry, Deduplication, Revisions, Rollback, and Restart

All scenarios are verified by dedicated `@Test` methods in `HistoricalImportIntegrationTest.java`:

### 5.1 Simultaneous Duplicate Submissions & Conflicting Keys
- Method: `testSimultaneousDuplicateSubmissionsAndConflictingRequestKeys()`
- Concurrent HTTP POST submissions with the same idempotency key and same bytes return HTTP 202 ACCEPTED and resolve to the same job ID without crashing on SQLite unique constraint violations.
- Reusing the same key with different payload bytes returns HTTP 409 CONFLICT.

### 5.2 Corrected Bytes Create New Immutable Dataset Version
- Method: `testCorrectedBytesCreatingNewImmutableDatasetVersionAndPreservingOldDataset()`
- Uploading modified bundle bytes creates a new dataset (`dataset-B`) with distinct ID.
- Re-querying original `dataset-A` confirms all historical bars, prices (`104.00`), and as-of queries remain strictly identical.
- SQLite immutability triggers block any in-place UPDATE or DELETE on either dataset.

### 5.3 Injected Publication Failure Rollback
- Method: `testInjectedPublicationFailureLeavesNoPartiallyVisibleDataset()`
- A database trigger failure injected during the publication transaction causes Spring's `TransactionTemplate` to roll back the entire transaction.
- Verification queries prove exactly 0 datasets and 0 bars were committed. The import job is marked `FAILED` with descriptive error details.

### 5.4 Restart Recovery & Documented Retry Behavior
- Method: `testRestartRecoveryAndDocumentedRetryBehavior()`
- Import jobs left in `QUEUED` or `RUNNING` status during unexpected process shutdown are detected on startup by `@PostConstruct recoverInterruptedJobs()` and transitioned to `INTERRUPTED`.
- Re-submitting a retry with a new intent key processes cleanly and completes without orphaned locks.

### 5.5 Exact Decimal & Corporate Action Precision
- Method: `testExactDecimalStringsAndCorporateActionDateRatioPrecision()`
- Validates OHLC values (`100.00`, `105.00`, `98.50`, `104.00`), volume (`15000`), split ratio (`2:1`), distribution amount (`1.25`), and ISO timestamps (`2024-01-02T18:00:00Z`) are returned without float rounding distortion.
- Validates pagination parameters (`limit`, `offset`, `totalBars`, `returnedBars`, `isTruncated`).

---

## 6. Migration/Checksum Compatibility & M1b Baseline Preservation

- **Migration Pipeline:** `MigrationRunner` executes versions 1 through 3 sequentially.
- **Checksum Verification:** V1 (`LegacyDataMigrator`) and V2 (`V2__m1b_review_fixes.sql`) checksums are preserved and validated.
- **Financial Baseline Intact:** Verified in integration tests that pre-existing portfolios (e.g. `portfolio-legacy-demo-default`), cash balances (`10000.00`), position records, and ledger history are completely unaffected by M2 migrations and historical bundle imports.

---

## 7. Local Walkthrough Guide

### 7.1 Example Bundle Location
- Valid bundle: `test/fixtures/historical/valid-sample-bundle.zip`
- Generator script: `test/fixtures/historical/generate_fixtures.py`

### 7.2 Generation Command
```bash
python3 test/fixtures/historical/generate_fixtures.py
```

### 7.3 Upload via cURL
```bash
curl -X POST http://localhost:8000/api/research/imports \
  -H "Idempotency-Key: import-sample-manual-1" \
  -F "file=@test/fixtures/historical/valid-sample-bundle.zip;type=application/zip"
```

### 7.4 Query via cURL
```bash
# Query job status:
curl -s http://localhost:8000/api/research/jobs/<job-id>

# Query listing history with as-of cutoff:
curl -s "http://localhost:8000/api/research/datasets/<dataset-id>/history/listing-eur-syn-1?start=2024-01-01&end=2024-01-10&asOf=2024-01-04T23:59:59Z&limit=100&offset=0"
```

---

## 8. Gate Table (PASS / FAIL / NOT VERIFIED)

| Gate ID | Gate Description | Status | Evidence / Notes |
|---|---|---|---|
| **G-01** | ZIP Archive Ingestion & Limits | **PASS** | 20MB compressed / 100MB uncompressed caps, entry & row limits, symlink rejection, path traversal rejection verified. |
| **G-02** | RFC-4180 CSV Parsing & Types | **PASS** | Custom state-machine parser handling quotes, escaped commas, decimals, and timestamps. |
| **G-03** | Dataset Validation & Findings | **PASS** | OHLC ordering, positive bounds, trading session coverage, and split discontinuity diagnostic verified. |
| **G-04** | Immutability & SQLite Triggers | **PASS** | 10 immutability triggers prevent all UPDATE and DELETE operations on published datasets. |
| **G-05** | Atomic Publication & Rollback | **PASS** | TransactionTemplate ensures publication and job completion are atomic; injected failures leave 0 orphan rows. |
| **G-06** | Idempotency & Deduplication | **PASS** | Same-key replay, conflicting-key 409 rejection, and content-checksum reuse verified. |
| **G-07** | As-Of Knowledge-Time Filtering | **PASS** | Cutoff queries filter records with `available_at > asOf`. No future data leakage across query boundary. |
| **G-08** | Restart Recovery Protocol | **PASS** | Unfinished jobs marked INTERRUPTED on startup; documented retry via new intent key verified. |
| **G-09** | Web Data Screen (`/research/data`) | **PASS** | Upload modal, job polling, dataset switching, pagination, truncation alerts, and history navigation verified. |
| **G-10** | Docker / Container Packaging | **NOT VERIFIED** | Explicitly deferred per project specification; host environment and toolchain pinned locally. |

---

## 9. Remaining D2/D3 Choices and M3 Readiness

1. **Synthetic Data Boundaries:** All fixtures and verified data in M2 are strictly `SYNTHETIC`. Synthetic data proves parser robustness, validation invariants, query filtering, and software architecture. It does NOT provide statistical evidence for financial alpha, market liquidity, or real-world execution feasibility.
2. **Decisions D2 & D3 (Real Providers & Budget):** The choice of live commercial market data vendor (e.g., Massive Market, Polygon, Tiingo, IEX) and associated operational budget remains deferred to production deployment phases.
3. **M3 Readiness:** The historical data ingestion, immutable dataset storage, validation engine, and bounded query APIs are stable, robustly tested, and fully ready for M3 Strategy Specification and Backtesting.
