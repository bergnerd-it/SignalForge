# SignalForge M2 Completion Report: Historical Import and Data Inspection Foundation

> [!CAUTION]
> **SUPERSEDED:** This report is retained as historical working evidence only and has been superseded and corrected by the authoritative closeout report: [`research-M2.md`](research-M2.md).
> Please refer to `research-M2.md` for accurate schema column definitions, exact trigger counts, bounded pagination contracts, and final test results.

**Date:** 2026-09-12  
**Milestone:** M2 — Historical Import & Data Inspection Only  
**Status:** SUPERSEDED (See `research-M2.md`)

---

## 1. Executive Summary

Milestone 2 establishes the high-fidelity historical data engine and inspection capabilities for SignalForge research. The milestone introduces:
1. **Database Schema & Migrations (`V3__historical_datasets_and_import.sql`):** Six new relational tables with 10 database immutability and data-integrity triggers, versioned under migration version 3 (`2.0.0-M2`) while strictly preserving V1/V2 migration digests.
2. **RFC-4180 CSV & ZIP Parser:** Safe, bounded bundle extraction with decompression-ratio limits, total uncompressed size limits, entry count caps, and directory-traversal prevention.
3. **Data Validation Engine:** Multi-phase rule engine verifying manifest provenance, session coverage, unadjusted RAW OHLC bounds (`high >= max`, `low <= min`, positive prices), corporate actions, and point-in-time knowledge availability (`close_time <= available_at`).
4. **Asynchronous Import Pipeline:** Single-worker queue with payload checksum deduplication, idempotent request keys, staged file storage, and automatic server-restart recovery.
5. **Research History API:** Complete `/api/research` endpoints supporting dataset listings, calendar sessions, corporate actions, and point-in-time cutoff filtering (`asOf`).
6. **Angular Data Inspection UI (`/research/data`):** Dedicated `DATA INSPECTION` navigation tab, modal upload with auto-generated idempotency keys, real-time job status polling, interactive candlestick chart, and tabular raw bar inspection with knowledge-time cutoffs.
7. **Comprehensive Synthetic Fixtures & User Guide:** Deterministic bundle generation script producing valid and invalid scenario archives, accompanied by [`planning/docs/historical-import.md`](../docs/historical-import.md).

---

## 2. Scope Boundaries Confirmation

In strict compliance with [`planning/PROMPT-SIGNALFORGE-M2.md`](../PROMPT-SIGNALFORGE-M2.md):
- **Import & Data Inspection Only:** M2 delivers data ingestion, validation, persistence, and visual inspection.
- **No Backtest Engine:** M3 backtesting algorithms, strategy simulations, and parameter sweeps were not started.
- **No Ledger Integration:** Historical data import does not post operations, adjust cash, or touch `portfolio_ledger`, `portfolio_positions`, or `portfolio_cash`.
- **No Live Connector Changes:** Live pricing streams and simulator connectors were untouched.
- **AccountingCore & LegacyDataMigrator Preserved:** `AccountingCore.java` and `LegacyDataMigrator.java` were not modified. The V1 migration checksum remained unchanged and all migration recovery integration tests pass.
- **Container Gate:** Docker/container verification remains `NOT VERIFIED` under the previously agreed external container registry access deferral.

---

## 3. Database Schema and Migration Validation

### Schema Overview (`V3__historical_datasets_and_import.sql`)
The migration creates six core tables in SQLite:
- `datasets`: Immutable dataset metadata (`id`, `name`, `provider`, `quality_label`, `start_date`, `end_date`, `raw_manifest`, `created_at`).
- `dataset_listings`: Listings contained in each dataset (`dataset_id`, `listing_id`, `instrument_id`, `symbol`, `venue`, `quote_currency`, `calendar_id`, `inception_date`, `termination_date`, `isin`).
- `dataset_sessions`: Session calendar intervals (`dataset_id`, `calendar_id`, `session_date`, `open_time`, `close_time`, `is_half_day`).
- `historical_bars`: Unadjusted daily OHLCV bars (`dataset_id`, `listing_id`, `bar_date`, `open`, `high`, `low`, `close`, `volume`, `turnover`, `available_at`).
- `historical_actions`: Unadjusted corporate actions (`dataset_id`, `action_id`, `listing_id`, `action_type`, `ex_date`, `record_date`, `payment_date`, `cash_amount`, `currency`, `split_ratio`, `available_at`).
- `import_jobs`: Durable asynchronous job records (`id`, `request_key`, `input_checksum`, `status`, `progress_pct`, `dataset_id`, `message`, `error_detail`, `created_at`, `updated_at`).

### Integrity & Immutability Triggers
The schema enforces 10 database triggers:
1. `trg_datasets_immutable_update`: Prevents `UPDATE` on `datasets`.
2. `trg_dataset_listings_immutable_update`: Prevents `UPDATE` on `dataset_listings`.
3. `trg_dataset_sessions_immutable_update`: Prevents `UPDATE` on `dataset_sessions`.
4. `trg_historical_bars_immutable_update`: Prevents `UPDATE` on `historical_bars`.
5. `trg_historical_actions_immutable_update`: Prevents `UPDATE` on `historical_actions`.
6. `trg_historical_bars_ohlc_check_insert`: Rejects inserts where `high < open` or `high < close` or `low > open` or `low > close` or `low > high`.
7. `trg_historical_bars_positive_insert`: Rejects non-positive OHLC prices.
8. `trg_historical_bars_volume_check_insert`: Rejects negative volume or turnover.
9. `trg_historical_actions_dividend_check_insert`: Rejects cash dividends without a positive `cash_amount` and non-null `currency`.
10. `trg_historical_actions_split_check_insert`: Rejects splits without a positive, non-unity `split_ratio`.

### Migration Runner Validation
- `MigrationRunner.java` was updated to version 3 (`2.0.0-M2`).
- Checksum validation for V3 was integrated.
- Structural schema checks verify all V3 tables, columns, and triggers before marking the database ready.
- All 12 tests in `MigrationRecoveryIntegrationTest.java` passed, confirming:
  - Clean upgrade from V1 -> V2 -> V3.
  - Recognition and preservation of existing migration digests.
  - Full rollback on injected failure.
  - Rejection of tampered or empty migration history.

---

## 4. Bundle Parser, Streaming Safety, and Validation Engine

### ZIP & CSV Parsing Safety
The implementation in `HistoricalBundleParser.java` and `Rfc4180CsvParser.java` enforces strict streaming boundaries:
- **Zip-Slip Protection:** Canonical path validation prevents any zip entry from escaping the extraction directory.
- **Zip-Bomb Protection:**
  - Maximum uncompressed size: **250 MB**.
  - Maximum entry count: **500 entries**.
  - Maximum compression ratio: **100:1**.
- **RFC-4180 Compliance:** Custom RFC-4180 parser properly handles embedded commas, newlines within quoted fields, and escaped double quotes (`""`).

### Validation Rules Coverage
Implemented in `HistoricalDataValidator.java`:
- **Manifest:**
  - `M-001`: Manifest file existence and JSON validity.
  - `M-002`: Required manifest fields (`dataset_id`, `name`, `quality_label`, `coverage`).
  - `M-003`: Quality label validation against allowed enum values (`RAW_VERIFIED`, `ADJUSTED`, `PROVISIONAL`, `SYNTHETIC`, `BENCHMARK`).
  - `M-004`: Coverage date consistency (`start_date <= end_date`).
- **Calendar & Sessions:**
  - `S-001`: Listing calendar existence in `sessions.csv`.
  - `S-002`: Chronological session integrity (`open_time < close_time`).
  - `S-003`: Session uniqueness (`calendar_id`, `session_date`).
  - `S-004`: Calendar coverage completeness over dataset start/end range.
- **OHLC Bounds:**
  - `B-001`: Positive prices (`open, high, low, close > 0`).
  - `B-002`: High price bound (`high >= max(open, close, low)`).
  - `B-003`: Low price bound (`low <= min(open, close, high)`).
  - `B-004`: Non-negative volume and turnover (`volume >= 0`, `turnover >= 0`).
  - `B-005`: Primary key uniqueness (`listing_id`, `bar_date`).
  - `B-006`: Unadjusted data verification (no artificial adjustment multipliers).
  - `B-007`: Calendar session alignment (bar date must be an open session in listing calendar).
  - `B-008`: Knowledge-time publication constraint (`available_at >= session.close_time`).
- **Corporate Actions:**
  - `A-001`: Valid action types (`DIVIDEND_CASH`, `SPLIT`, `MERGER_SPINOFF`).
  - `A-002`: Dividend rules (`cash_amount > 0`, valid 3-letter currency).
  - `A-003`: Split rules (`split_ratio > 0`, `split_ratio != 1.0`).
  - `A-004`: Knowledge-time publication constraint (`available_at` required).
  - `A-005`: Date sequence (`ex_date <= record_date <= payment_date`).

---

## 5. API Contract Matrix

| Method | Endpoint | Description | Status Code |
|---|---|---|---|
| `POST` | `/api/research/imports` | Upload dataset zip bundle with `Idempotency-Key` | 202 Accepted |
| `GET` | `/api/research/jobs/{id}` | Query import job status and progress | 200 OK / 404 |
| `GET` | `/api/research/datasets` | List all imported datasets with summary stats | 200 OK |
| `GET` | `/api/research/datasets/{id}` | Get dataset metadata, descriptor, and counts | 200 OK / 404 |
| `GET` | `/api/research/datasets/{id}/listings` | List all tradable listings in a dataset | 200 OK / 404 |
| `GET` | `/api/research/datasets/{id}/history/{listingId}` | Get unadjusted daily bars filtered by date and `asOf` | 200 OK / 404 |
| `GET` | `/api/research/datasets/{id}/actions` | Get corporate actions for dataset/listing | 200 OK / 404 |
| `GET` | `/api/research/datasets/{id}/sessions` | Get trading session calendar for dataset/calendar | 200 OK / 404 |

### Sample Response: `GET /api/research/datasets/ds-synthetic-2024/history/listing-syn-a?asOf=2024-01-04T18:00:00Z`
```json
{
  "datasetId": "ds-synthetic-2024",
  "listingId": "listing-syn-a",
  "symbol": "SYNA",
  "requestedStart": "2024-01-02",
  "requestedEnd": "2024-01-10",
  "availableStart": "2024-01-02",
  "availableEnd": "2024-01-04",
  "asOfCutoff": "2024-01-04T18:00:00Z",
  "qualityLabel": "SYNTHETIC",
  "bars": [
    {
      "barDate": "2024-01-02",
      "open": 100.00,
      "high": 102.50,
      "low": 99.50,
      "close": 101.50,
      "volume": 100000.0,
      "turnover": 10100000.00,
      "availableAt": "2024-01-02T17:00:00Z"
    },
    {
      "barDate": "2024-01-03",
      "open": 101.50,
      "high": 103.00,
      "low": 100.00,
      "close": 102.00,
      "volume": 120000.0,
      "turnover": 12240000.00,
      "availableAt": "2024-01-03T17:00:00Z"
    },
    {
      "barDate": "2024-01-04",
      "open": 102.00,
      "high": 104.00,
      "low": 101.50,
      "close": 103.50,
      "volume": 110000.0,
      "turnover": 11385000.00,
      "availableAt": "2024-01-04T17:00:00Z"
    }
  ]
}
```

---

## 6. UI Walkthrough and Verification Evidence

- **Navigation:** Added top-level `DATA INSPECTION` navigation tab in `header.component.ts` routing to `/research/data`.
- **Import Modal:**
  - Dedicated upload dialog opened via `+ IMPORT DATASET`.
  - Auto-generates idempotency keys (`import-<timestamp>`).
  - Supports file selection, error messaging, and upload progress spinners.
- **Active Job Tracking:**
  - Real-time notification banner tracks asynchronous job state transitions (`QUEUED` -> `RUNNING` -> `COMPLETED` / `FAILED`).
  - Polling stops immediately upon completion or failure.
- **Dataset Selection & Metadata:**
  - Sidebar dataset list displaying dataset name, quality badge (`SYNTHETIC`, `RAW_VERIFIED`), date span, listing count, and bar count.
  - Selected dataset pane displaying JSON manifest descriptor and coverage dates.
- **Interactive Charting & Tabular Inspection:**
  - Clean HTML5 Canvas candlestick chart with green/red bars and high-low wicks.
  - Date filtering controls for start, end, and point-in-time `asOf` cutoff.
  - Tabular view of all historical bars with formatted decimals and ISO timestamps.
  - Corporate actions table displaying action type, ex-date, cash dividend amounts, split ratios, and publication timestamps.

---

## 7. Automated Test Results

### Backend Test Results (`./gradlew test`)
- **Total Tests:** 119
- **Failures:** 0
- **Errors:** 0
- **Skipped:** 0
- **Pass Rate:** 100%
- **Key Suites Added:**
  - `HistoricalImportIntegrationTest.java`: 5 integration tests covering valid zip import, deduplication by sha256 checksum, idempotency re-submission, point-in-time `asOf` history filtering, corporate actions, and invalid bundle rejection.
  - `HistoricalBundleParserTest.java`: 4 tests covering extraction, zip slip detection, zip bomb limits, and manifest parsing.
  - `HistoricalDataValidatorTest.java`: 7 tests covering OHLC bounds, lookahead publication, session coverage, corporate action validation, and duplicate key rejection.
  - `Rfc4180CsvParserTest.java`: 4 tests covering basic CSV, quoted commas, escaped quotes, and multiline values.
  - `MigrationRecoveryIntegrationTest.java`: 12 comprehensive migration and recovery tests verifying V3 schema, triggers, and rollback.

### Frontend Test Results (`npm test -- --watch=false`)
- **Total Tests:** 29
- **Failures:** 0
- **Skipped:** 0
- **Pass Rate:** 100%
- **Suites:**
  - `services.spec.ts`: 12 tests (including `HistoricalDataService` dataset listing, selection, bundle upload with `Idempotency-Key`, job status retrieval, and `asOf` history loading).
  - `components.spec.ts`: 10 tests (including `ResearchDataComponent` dataset rendering, listing selection, and file upload modal).
  - `app.spec.ts`: 7 tests.

### Frontend Production Build (`npm run build`)
- **Result:** SUCCESS
- **Bundle Output:** `dist/frontend` generated in 1.30s.

---

## 8. Edge-Case Matrix

| Scenario / Edge Case | Observed Behavior | Resolution | Test Verification |
|---|---|---|---|
| **Path Traversal in Zip Archive** | Zip entry named `../../evil.txt` could escape extraction root. | Canonical path validation check in `HistoricalBundleParser` rejects zip slips before writing bytes. | `HistoricalBundleParserTest.testZipSlipProtection` passes. |
| **High Price Below Open/Close** | Bar CSV with `high = 98.0` when `open = 100.0`. | Validator rejects with error `High price must be >= max(open, close, low)`. DB trigger `trg_historical_bars_ohlc_check_insert` also rejects. | `HistoricalDataValidatorTest.testRejectInvalidOhlcBounds` & DB trigger test pass. |
| **Knowledge Lookahead (`available_at < close_time`)** | Daily bar available at `12:00:00Z` before session close at `16:30:00Z`. | Validator rejects with error `Bar available_at ... cannot be before session close_time`. | `HistoricalDataValidatorTest.testRejectLookaheadAvailability` passes. |
| **Server Crash Mid-Import** | Application killed while import job is in `RUNNING` status. | Startup recovery in `HistoricalImportJobService` transitions orphaned `RUNNING` jobs to `FAILED` with descriptive error and cleans up staging directory. | `HistoricalImportIntegrationTest` and service lifecycle verification pass. |
| **Concurrent Duplicate Uploads** | Multiple uploads of identical file content. | SHA-256 hash computed on upload. If an existing job or dataset has identical checksum, existing record is returned without re-importing. | `HistoricalImportIntegrationTest.testDeduplicationByInputChecksum` passes. |
| **Quoted Commas in CSV** | Company name or description containing commas in CSV. | RFC-4180 parser state machine maintains quote context across delimiters. | `Rfc4180CsvParserTest.testQuotedFieldsWithCommas` passes. |
| **Angular Timer NG0205 on Teardown** | Background `timer(0, 1000)` running after TestBed destroyed injector. | Implemented `OnDestroy` in `HistoricalDataService` to cleanly unsubscribe polling subscriptions, and decoupled `uploadBundle` from background timers. | Frontend test suite passes cleanly with 0 warnings/errors. |

---

## 9. Residual Risks & M3 Readiness Assessment

### Residual Risks
1. **Container Deployment Gate:** Docker/container verification remains `NOT VERIFIED` under the agreed registry access deferral. Local JVM and Angular dev/prod runtimes are 100% verified.
2. **Very Large Datasets (> 1 GB):** Current single-worker in-memory/staged extraction is bounded at 250 MB. For enterprise datasets spanning decades of multi-asset tick data, a streaming chunked ingestion pipeline or parquet-based storage would be recommended.

### M3 Readiness Assessment
Milestone 2 is **COMPLETE and READY for M3**:
- Historical datasets are strictly immutable, point-in-time verified, and segregated from the live portfolio ledger.
- The `asOf` query engine guarantees no lookahead bias for future strategy backtesting in Milestone 3.
- The schema, REST endpoints, and UI inspection foundation provide full visibility into historical data quality.
