# SignalForge Historical Dataset Import Guide

This document describes the historical dataset bundle specification, RFC-4180 parsing rules, validation engine bounds, asynchronous import workflow, API usage, and the Angular Data Inspection interface introduced in Milestone 2 (`M2`).

---

## 1. Bundle Specification & Layout

A dataset bundle is a standard uncompressed or `DEFLATE`-compressed `.zip` archive containing exactly five required files at its root level (or under a single optional top-level directory):

```text
bundle.zip
├── manifest.json
├── listings.csv
├── sessions.csv
├── bars_daily.csv
└── actions.csv
```

Nested directory traversal (`../`) and zip bombs are strictly blocked by safety limits:
- Maximum uncompressed size: **250 MB**
- Maximum total entry count: **500 entries**
- Maximum compression ratio: **100:1**

### 1.1 `manifest.json`

The dataset descriptor defining identity, source provenance, quality rating, and coverage bounds.

```json
{
  "manifest_version": "1.0",
  "dataset_id": "ds-synthetic-2024",
  "name": "Synthetic EU Equities 2024",
  "description": "Deterministic synthetic daily OHLC bars and corporate actions",
  "provider": "SignalForge Synthetic Engine",
  "quality_label": "SYNTHETIC",
  "coverage": {
    "start_date": "2024-01-02",
    "end_date": "2024-01-10"
  },
  "created_at": "2026-09-12T12:00:00Z"
}
```

#### Fields:
- `manifest_version`: Required text (`1.0`).
- `dataset_id`: Unique alphanumeric ID (supports hyphens and underscores).
- `name`: Human-readable dataset name.
- `provider`: Data vendor or generator name.
- `quality_label`: Must be one of `RAW_VERIFIED`, `ADJUSTED`, `PROVISIONAL`, `SYNTHETIC`, `BENCHMARK`.
- `coverage.start_date` / `coverage.end_date`: ISO-8601 calendar dates (`YYYY-MM-DD`).
- `created_at`: ISO-8601 UTC timestamp (`YYYY-MM-DDTHH:MM:SSZ`).

---

### 1.2 `listings.csv`

Defines the tradable instruments and listings contained in the dataset.

| Column | Type | Format / Constraints | Example | Description |
|---|---|---|---|---|
| `listing_id` | String | Unique within dataset, non-blank | `listing-syn-a` | Unique listing key |
| `instrument_id` | String | Non-blank | `inst-syn-a` | Underlying financial instrument key |
| `symbol` | String | 1–16 chars, uppercase alphanumeric | `SYNA` | Ticker symbol |
| `venue` | String | ISO 10383 MIC or venue code | `XETRA` | Trading venue / market |
| `quote_currency` | String | ISO 4217 3-letter currency code | `EUR` | Currency of OHLC prices |
| `calendar_id` | String | Non-blank, matches sessions | `cal-xetra` | Associated trading session calendar |
| `inception_date` | Date | `YYYY-MM-DD` | `2020-01-01` | First active trading date |
| `termination_date` | Date | `YYYY-MM-DD` or empty | `2030-12-31` | Delisting date (nullable) |
| `isin` | String | ISO 6166 12-character ISIN or empty | `IE000SYN0001` | International Securities ID |

---

### 1.3 `sessions.csv`

The exchange calendar defining active trading days, market open/close times, and half-day flags.

| Column | Type | Format / Constraints | Example | Description |
|---|---|---|---|---|
| `calendar_id` | String | Non-blank | `cal-xetra` | Calendar identifier |
| `session_date` | Date | `YYYY-MM-DD`, unique with calendar | `2024-01-02` | Session date |
| `open_time` | Timestamp | ISO-8601 UTC timestamp | `2024-01-02T08:00:00Z` | Session market open time |
| `close_time` | Timestamp | ISO-8601 UTC timestamp > open_time | `2024-01-02T16:30:00Z` | Session market close time |
| `is_half_day` | Boolean | `true` or `false` | `false` | Early market close indicator |

---

### 1.4 `bars_daily.csv`

Unadjusted raw daily OHLCV bars.

| Column | Type | Format / Constraints | Example | Description |
|---|---|---|---|---|
| `listing_id` | String | Foreign key to `listings.csv` | `listing-syn-a` | Listing identifier |
| `bar_date` | Date | `YYYY-MM-DD` matching an open session | `2024-01-02` | Trading day |
| `open` | Decimal | Finite decimal > 0 | `100.00` | Session opening price |
| `high` | Decimal | Finite decimal >= max(open, close, low) | `102.50` | Highest traded price |
| `low` | Decimal | Finite decimal <= min(open, close, high), > 0 | `99.50` | Lowest traded price |
| `close` | Decimal | Finite decimal > 0 | `101.50` | Session closing price |
| `volume` | Decimal | Finite decimal >= 0 | `100000` | Trading volume in base units |
| `turnover` | Decimal | Finite decimal >= 0 or empty | `10100000.00` | Value traded in quote currency |
| `available_at` | Timestamp | ISO-8601 UTC timestamp >= session close | `2024-01-02T17:00:00Z` | Point-in-time publication timestamp |

---

### 1.5 `actions.csv`

Historical corporate actions (dividends, stock splits, spinoffs) stored in raw unadjusted form.

| Column | Type | Format / Constraints | Example | Description |
|---|---|---|---|---|
| `action_id` | String | Unique within dataset, non-blank | `act-div-001` | Corporate action identifier |
| `listing_id` | String | Foreign key to `listings.csv` | `listing-syn-a` | Listing identifier |
| `action_type` | String | `DIVIDEND_CASH`, `SPLIT`, `MERGER_SPINOFF` | `DIVIDEND_CASH` | Type of action |
| `ex_date` | Date | `YYYY-MM-DD` matching a session | `2024-01-05` | Ex-dividend or effective date |
| `record_date` | Date | `YYYY-MM-DD` >= ex_date or empty | `2024-01-06` | Record date for entitlement |
| `payment_date` | Date | `YYYY-MM-DD` >= ex_date or empty | `2024-01-15` | Cash distribution date |
| `cash_amount` | Decimal | Decimal >= 0 (required for DIVIDEND) | `0.50` | Gross cash dividend per share |
| `currency` | String | ISO 4217 3-letter code (for DIVIDEND) | `EUR` | Dividend currency |
| `split_ratio` | Decimal | Finite decimal > 0 (required for SPLIT) | `2.0` | Split factor (2.0 = 2-for-1) |
| `available_at` | Timestamp | ISO-8601 UTC timestamp | `2024-01-04T18:00:00Z` | Announcement publication timestamp |

---

## 2. Validation Engine Rules & Hard Bounds

The validator checks each incoming dataset bundle and rejects the import with a descriptive error code if any rule fails:

1. **Manifest Integrity (`M-001` .. `M-004`):**
   - Manifest file must exist, be valid JSON, and specify `manifest_version: "1.0"`.
   - `dataset_id`, `name`, `quality_label`, and `coverage` dates are mandatory.
   - `quality_label` must be one of the permitted enums.
2. **Calendar & Session Coverage (`S-001` .. `S-004`):**
   - Each calendar referenced by `listings.csv` must exist in `sessions.csv`.
   - Every calendar date in `coverage` that is a weekday must be declared or explicitly omitted as a non-trading session.
   - `open_time < close_time` for every session.
3. **OHLC Hard Bounds (`B-001` .. `B-006`):**
   - Prices must be strictly positive: `open > 0`, `high > 0`, `low > 0`, `close > 0`.
   - High price bound: `high >= open` and `high >= close` and `high >= low`.
   - Low price bound: `low <= open` and `low <= close` and `low <= high`.
   - Volume and turnover must be non-negative: `volume >= 0`, `turnover >= 0`.
   - No duplicate bars for `(listing_id, bar_date)`.
   - Unadjusted data constraint: raw bars must not have artificial retrospective split/dividend multipliers applied.
4. **Session Calendar Alignment (`B-007`):**
   - Every bar's `bar_date` must match an active trading session in that listing's calendar. Bars on weekends or non-trading days are rejected.
5. **Knowledge-Time Publication Availability (`B-008`, `A-004`):**
   - Point-in-time integrity requires that daily closing bars cannot be available before the session close: `available_at >= session.close_time`.
   - Future publication timestamps or missing `available_at` fail validation.
6. **Corporate Action Consistency (`A-001` .. `A-005`):**
   - `DIVIDEND_CASH` requires `cash_amount > 0` and matching `currency`.
   - `SPLIT` requires `split_ratio > 0` and `split_ratio != 1.0`.
   - `ex_date` must match an active session date.
   - If `record_date` or `payment_date` is provided, `ex_date <= record_date <= payment_date`.

---

## 3. Asynchronous Import Lifecycle & Idempotency

Import processing is bounded and asynchronous:

```text
               +-----------------------------------+
               |  POST /api/research/imports       |
               |  (Header: Idempotency-Key)        |
               +-----------------+-----------------+
                                 |
                     +-----------v-----------+
                     | Check Idempotency Key |
                     +-----+-----------+-----+
         Key exists        |           |  New Key
   +-----------------------+           +-----------------------+
   |                                                           |
   v                                                           v
Return Existing Job                                     Create `QUEUED` Job
                                                        Store uploaded zip to disk
                                                               |
                                                               v
                                                        Queue in Single Worker
                                                               |
                                                               v
                                                        Transition to `RUNNING`
                                                        Extract & Parse Bundle
                                                        Run Validation Engine
                                                               |
                                           +-------------------+-------------------+
                                           |                                       |
                                     All Valid                               Any Error
                                           v                                       v
                                Insert Dataset Records                   Transition to `FAILED`
                                (Atomically in DB tx)                    Record error code & detail
                                Transition to `COMPLETED`
```

### Crash Recovery & Restart Behavior
If the application server restarts while an import job is `RUNNING`, the `HistoricalImportJobService` detects the interrupted job upon startup, transitions it to `FAILED` with message `"Job interrupted by server restart"`, and cleans up staging files. The client can safely re-submit using the same idempotency key or a new key.

---

## 4. API Endpoints

### 4.1 Upload Dataset Bundle
`POST /api/research/imports`  
**Headers:**
- `Idempotency-Key: <unique-request-key>` (Required)
- `Content-Type: multipart/form-data`

**Form Body:**
- `file`: The `.zip` archive.

**Response (HTTP 202 Accepted):**
```json
{
  "id": "c1f76d90-3b47-4903-b09e-71f65d4918e7",
  "requestKey": "import-20260912-001",
  "inputChecksum": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
  "status": "QUEUED",
  "progressPct": 0,
  "datasetId": null,
  "message": "Import job queued",
  "errorDetail": null,
  "createdAt": "2026-09-12T12:00:00Z",
  "updatedAt": "2026-09-12T12:00:00Z"
}
```

### 4.2 Query Job Status
`GET /api/research/jobs/{jobId}`

**Response (HTTP 200 OK):**
```json
{
  "id": "c1f76d90-3b47-4903-b09e-71f65d4918e7",
  "requestKey": "import-20260912-001",
  "inputChecksum": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
  "status": "COMPLETED",
  "progressPct": 100,
  "datasetId": "ds-synthetic-2024",
  "message": "Dataset imported successfully: ds-synthetic-2024",
  "errorDetail": null,
  "createdAt": "2026-09-12T12:00:00Z",
  "updatedAt": "2026-09-12T12:00:02Z"
}
```

### 4.3 List Datasets
`GET /api/research/datasets`

**Response (HTTP 200 OK):**
```json
[
  {
    "id": "ds-synthetic-2024",
    "name": "Synthetic EU Equities 2024",
    "provider": "SignalForge Synthetic Engine",
    "qualityLabel": "SYNTHETIC",
    "startDate": "2024-01-02",
    "endDate": "2024-01-10",
    "listingCount": 2,
    "barCount": 14,
    "importedAt": "2026-09-12T12:00:02Z"
  }
]
```

### 4.4 Get Dataset Detail
`GET /api/research/datasets/{datasetId}`

### 4.5 Get Dataset Listings
`GET /api/research/datasets/{datasetId}/listings`

### 4.6 Inspect Listing Daily History with Point-in-Time Cutoff
`GET /api/research/datasets/{datasetId}/history/{listingId}?start=2024-01-01&end=2024-01-10&asOf=2024-01-04T18:00:00Z`

**Response (HTTP 200 OK):**
```json
{
  "datasetId": "ds-synthetic-2024",
  "listingId": "listing-syn-a",
  "symbol": "SYNA",
  "requestedStart": "2024-01-01",
  "requestedEnd": "2024-01-10",
  "availableStart": "2024-01-02",
  "availableEnd": "2024-01-04",
  "asOfCutoff": "2024-01-04T18:00:00Z",
  "qualityLabel": "SYNTHETIC",
  "bars": [
    {
      "barDate": "2024-01-02",
      "open": 100.0,
      "high": 102.5,
      "low": 99.5,
      "close": 101.5,
      "volume": 100000.0,
      "turnover": 10100000.0,
      "availableAt": "2024-01-02T17:00:00Z"
    },
    {
      "barDate": "2024-01-03",
      "open": 101.5,
      "high": 103.0,
      "low": 100.0,
      "close": 102.0,
      "volume": 120000.0,
      "turnover": 12240000.0,
      "availableAt": "2024-01-03T17:00:00Z"
    },
    {
      "barDate": "2024-01-04",
      "open": 102.0,
      "high": 104.0,
      "low": 101.5,
      "close": 103.5,
      "volume": 110000.0,
      "turnover": 11385000.0,
      "availableAt": "2024-01-04T17:00:00Z"
    }
  ]
}
```
*Notice that bars for dates after `2024-01-04T18:00:00Z` (e.g. `2024-01-05`) are excluded because their `available_at` exceeds the requested `asOf` cutoff timestamp.*

### 4.7 Get Corporate Actions
`GET /api/research/datasets/{datasetId}/actions?listingId=listing-syn-a`

### 4.8 Get Sessions Calendar
`GET /api/research/datasets/{datasetId}/sessions?calendarId=cal-xetra`

---

## 5. Command-Line (cURL) Walkthrough

### 5.1 Uploading a Dataset Bundle
```bash
curl -i -X POST http://localhost:8080/api/research/imports \
  -H "Idempotency-Key: import-cli-$(date +%s)" \
  -F "file=@test/fixtures/historical/valid-sample-bundle.zip"
```

### 5.2 Checking Job Status
```bash
curl -s http://localhost:8080/api/research/jobs/<JOB_ID> | jq .
```

### 5.3 Fetching Dataset Bars with `asOf` Filter
```bash
curl -s "http://localhost:8080/api/research/datasets/ds-synthetic-2024/history/listing-syn-a?asOf=2024-01-03T23:59:59Z" | jq .
```

---

## 6. Web UI Walkthrough: Data Inspection (`/research/data`)

The SignalForge web application provides a specialized data inspection view:

1. **Accessing Data Inspection:**
   - Click the **DATA INSPECTION** tab in the top navigation header or navigate directly to `http://localhost:4200/research/data`.
2. **Uploading a Dataset:**
   - Click the **+ IMPORT DATASET** button in the header bar.
   - An upload modal opens showing an auto-generated idempotency key.
   - Choose a `.zip` bundle file (e.g. `valid-sample-bundle.zip`).
   - Click **UPLOAD & IMPORT**.
   - An active job notification banner appears with real-time status and progress updates (`QUEUED` -> `RUNNING` -> `COMPLETED`).
3. **Inspecting Dataset Details:**
   - The left sidebar lists all available datasets with their quality badge, date coverage, listing count, and bar count.
   - Select any dataset to view its metadata, provider, and raw manifest descriptor.
4. **Filtering and Charting Listings:**
   - Choose a listing from the dataset's listing selector.
   - Filter the date range by adjusting **Start Date** and **End Date**.
   - Set a point-in-time cutoff using **As Of (Knowledge Cutoff)**.
   - The interactive chart renders the unadjusted daily OHLC candlesticks.
   - The historical data table below displays exact `Open`, `High`, `Low`, `Close`, `Volume`, `Turnover`, and `Available At` timestamps.
5. **Corporate Actions Table:**
   - Scroll to the Corporate Actions section to inspect ex-dates, cash dividends, split ratios, and publication timestamps.

---

## 7. Troubleshooting & Common Error Codes

| Error Code | Meaning | Common Cause & Resolution |
|---|---|---|
| `VALIDATION_FAILED (M-001)` | Missing `manifest.json` | Ensure `manifest.json` is located at the archive root or single root folder. |
| `VALIDATION_FAILED (M-003)` | Invalid `quality_label` | Quality label must be one of `RAW_VERIFIED`, `ADJUSTED`, `PROVISIONAL`, `SYNTHETIC`, `BENCHMARK`. |
| `VALIDATION_FAILED (B-002)` | High price violation | Verify `high >= max(open, close, low)`. Negative or corrupted prices present in raw bar CSV. |
| `VALIDATION_FAILED (B-008)` | Bar available before session close | Ensure `available_at >= session.close_time` for the trading day. No lookahead knowledge allowed. |
| `VALIDATION_FAILED (A-002)` | Invalid split ratio | `split_ratio` must be positive and not equal to `1.0`. |
| `CSV_PARSE_ERROR` | Malformed CSV data | Ensure values with commas or quotes are properly escaped with double quotes per RFC-4180. |
| `BUNDLE_UNPACK_ERROR` | Corrupted ZIP or path traversal | Ensure the archive is a valid `.zip` file without directory traversal (`../`) entries. |
| `HTTP 400 Bad Request` | Missing `Idempotency-Key` header | Include the `Idempotency-Key` header on all `POST /api/research/imports` requests. |
| `HTTP 409 Conflict` | Conflicting idempotency key | A previous import used the same idempotency key with different file contents. Use a new key. |
