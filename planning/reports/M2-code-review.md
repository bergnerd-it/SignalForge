# SignalForge M2 — Code Review: Implementation vs. Specification

**Reviewer:** Code Review Agent  
**Date:** 2026-09-12  
**Spec:** [PROMPT-SIGNALFORGE-M2.md](file:///Users/oliver/Projects/bergnerd/SignalForge/planning/PROMPT-SIGNALFORGE-M2.md)  
**Completion Report:** [M2-completion-report.md](file:///Users/oliver/Projects/bergnerd/SignalForge/planning/reports/M2-completion-report.md)

---

## Overall Assessment

The M2 implementation is **structurally solid** and covers the core import pipeline end-to-end: ZIP parsing → CSV validation → immutable dataset publication → history reader with as-of filtering → Angular UI. The architectural decisions are sound and the test suite covers the happy path well.

However, several spec requirements are **not fully met**, and the completion report **overstates coverage** in certain areas. Below is a detailed gap analysis.

---

## ✅ Spec Requirements Fully Met

| Spec Section | Requirement | Status |
|---|---|---|
| §2 | Keep Java 21, Spring MVC/JDBC, SQLite, Angular | ✅ |
| §2 | No framework/database upgrade | ✅ |
| §2 | Keep quotes/SSE and historical data separate | ✅ |
| §2 | Imports never mutate portfolio | ✅ Verified in integration test |
| §3 | ZIP bundle with 5 root files | ✅ |
| §3 | UTF-8, ISO dates/instants, decimal strings | ✅ |
| §3 | RFC-4180 CSV parsing (not naïve comma split) | ✅ Custom state-machine parser |
| §3 | Empty actions.csv only with completeness declaration | ✅ Validator checks `action_completeness` |
| §3 | Reject unsupported action types | ✅ Only SPLIT / CASH_DISTRIBUTION |
| §3 | RAW OHLC required, reject ADJUSTED | ✅ `price_convention` = RAW enforced |
| §3 | Split ratio as integer num/den | ✅ Stored as `split_ratio_numerator`/`denominator` |
| §4 | Follow-on V3 migration preserving V1/V2 | ✅ Migration file exists, checksum checked |
| §4 | Immutability triggers on datasets, bars, actions | ✅ 10 triggers in V3 SQL |
| §4 | Dataset identity/status/input checksum/validation | ✅ `datasets` table has all columns |
| §4 | Daily uniqueness scoped to dataset/listing/session | ✅ PK on `(dataset_id, listing_id, session_date)` |
| §4 | Action uniqueness scoped to dataset/action_id | ✅ PK on `(dataset_id, action_id)` |
| §5 | Validation findings with stable code, severity, file/row, detail | ✅ `ValidationFinding` record |
| §5 | OHLC bounds validation (positive, low ≤ open/close ≤ high) | ✅ |
| §5 | Calendar session alignment (bars only on TRADING days) | ✅ |
| §5 | Coverage check (missing trading-session bars) | ✅ |
| §5 | Knowledge-time: `available_at ≥ session.close_time` | ✅ |
| §5 | As-of history reader with cutoff filtering | ✅ SQL WHERE clause on `available_at ≤ ?` |
| §6 | POST upload returns job ID after accepting input | ✅ |
| §6 | Single active import + bounded queue (10) | ✅ `LinkedBlockingQueue(10)` |
| §6 | Job states: QUEUED, RUNNING, COMPLETED, FAILED, INTERRUPTED | ✅ |
| §6 | Stable request key with idempotent replay | ✅ Same key + same bytes = original result |
| §6 | Conflicting reuse → 409 | ✅ |
| §6 | Same bytes + different key → reuse dataset | ✅ Checksum dedup |
| §6 | Atomic publication via `TransactionTemplate` | ✅ |
| §6 | Restart recovery: RUNNING/QUEUED → INTERRUPTED | ✅ `@PostConstruct recoverInterruptedJobs()` |
| §6 | Archive size limits enforced during decompression | ✅ Streaming byte counter |
| §6 | Path traversal / duplicate filename / absolute path rejection | ✅ |
| §7 | API endpoints as specified | ✅ All 8 endpoints present |
| §7 | Every history query specifies a dataset | ✅ `{id}` is required path param |
| §7 | Coverage notes when requested vs available mismatch | ✅ |
| §8 | Deterministic SYNTHETIC fixtures from checked-in script | ✅ Python generator + 6 zips |
| §8 | Valid multi-listing dataset with closure, split, distribution | ✅ |
| §8 | Invalid variants for missing bars, adjusted, duplicates, bad actions, traversal | ✅ |
| §8 | `planning/docs/historical-import.md` user guide | ✅ 17KB guide present |

---

## ⚠️ Gaps, Deviations, and Issues

### CRITICAL — Spec Violations

#### 1. Missing `research-M2.md` Report (§9)

> [!CAUTION]
> The spec explicitly requires `planning/reports/research-M2.md`. Only `M2-completion-report.md` exists. The research report has a **different required structure** (9 numbered sections including M1b checkpoint identity, actual command/tool versions, PASS/FAIL gates, D2/D3 choices, and M3 readiness). The completion report is close but is **not the required deliverable name** and is missing several required sections (§9.1 checkpoint commit hash, §9.4 actual tool versions, §9.8 PASS/FAIL gate table).

#### 2. Completion Report Claims Inaccurate Trigger Count (§3–§4)

> [!WARNING]
> The completion report describes **10 database triggers** including named triggers like `trg_historical_bars_ohlc_check_insert`, `trg_historical_bars_positive_insert`, `trg_historical_bars_volume_check_insert`, `trg_historical_actions_dividend_check_insert`, and `trg_historical_actions_split_check_insert`. However, the actual [V3__historical_datasets_and_import.sql](file:///Users/oliver/Projects/bergnerd/SignalForge/backend/src/main/resources/db/migration/V3__historical_datasets_and_import.sql) contains only **11 triggers**: 5 pairs of immutability (UPDATE + DELETE) triggers + 1 format validation trigger `validate_historical_bars_insert`. The named OHLC/positive/volume/dividend/split triggers described in the report **do not exist** in the SQL — the report fabricates trigger names.

#### 3. Completion Report Incorrectly Describes `datasets` Table Columns (§3)

> [!WARNING]  
> The report says `datasets` has columns `(id, name, provider, quality_label, start_date, end_date, raw_manifest, created_at)`. The actual schema has `(id, name, source, classification, schema_version, parser_version, input_checksum, content_checksum, manifest_json, coverage_start, coverage_end, validation_status, validation_findings_json, quality_label, imported_at, created_at)`. There is no `provider` column; it's called `source`. Similarly for other table descriptions in the report — they are **inaccurate summaries**.

#### 4. Missing `session_type` for `is_half_day` Mismatch in Report vs Schema

> [!NOTE]
> The report says `dataset_sessions` has `is_half_day`. Actual schema has `session_type TEXT CHECK(session_type IN ('TRADING', 'CLOSED'))`. The report column description is wrong but the implementation is correct per spec.

---

### SIGNIFICANT — Functional Gaps

#### 5. No Compression Ratio Check (§6, Zip Bomb)

> [!IMPORTANT]
> Spec §6: _"Enforce archive/input limits before and during decompression: compressed and expanded byte caps, per-file/row/entry limits."_ The completion report claims a **100:1 compression ratio limit**. Examining [HistoricalBundleParser.java](file:///Users/oliver/Projects/bergnerd/SignalForge/backend/src/main/java/com/bergnerd/signalforge/app/research/historical/HistoricalBundleParser.java), the code checks `MAX_COMPRESSED_BYTES` (20MB) and `MAX_UNCOMPRESSED_BYTES` (100MB) but **never calculates or enforces a compression ratio**. This is a minor omission since the absolute size limits provide protection, but the claim in the report is false.

#### 6. Symlink Rejection Not Implemented (§6)

Spec §6: _"Reject …symlinks…"_ — The [extractZip](file:///Users/oliver/Projects/bergnerd/SignalForge/backend/src/main/java/com/bergnerd/signalforge/app/research/historical/HistoricalBundleParser.java#L107-L167) method checks for path traversal, directories, nested paths, and duplicate entries, but **does not check for symlink entries** in the ZIP. Java's `ZipEntry` doesn't natively expose symlink info via `ZipInputStream`, so this is hard to catch, but it should be documented as a limitation.

#### 7. History Response Returns Numeric, Not Decimal Strings (§4, §7)

> [!WARNING]
> Spec §4: _"All financial values stay exact at JDBC/API boundaries."_ The sample response in the completion report §5 shows `"open": 100.00` — a **JSON number**, not a decimal string. The backend correctly stores and reads OHLC as `TEXT` in SQLite and returns them as `String` in [HistoricalBarDto](file:///Users/oliver/Projects/bergnerd/SignalForge/backend/src/main/java/com/bergnerd/signalforge/app/research/historical/HistoricalDtos.java#L179-L187). So the **implementation is correct** (strings), but the **report's sample response is misleading** — it shows numbers when the actual API serves strings.

#### 8. No Pagination/Bounding on History/List Endpoints (§7)

Spec §7: _"Bound/paginate responses and index range access."_ None of the endpoints implement pagination parameters (`limit`, `offset`). The `GET /datasets`, `GET /datasets/{id}/listings`, `GET /datasets/{id}/sessions`, `GET /datasets/{id}/actions`, and `GET /datasets/{id}/history/{listingId}` all return unbounded result sets. For datasets with 500k bars, this could be problematic.

#### 9. `actions.csv` Column Names Deviate From Spec (§3)

Spec §3 defines `actions.csv` with: _"effective/ex-date"_ and _"distribution per post-split unit/currency; payment date and optional precise payment instant"_. The implementation uses `effective_date` for what the spec calls the "ex-date" — this is acceptable but should be documented. However, the fixture uses `effective_date` for the column name while the spec says the CSV should have the ex-date concept. The naming is slightly ambiguous. Minor issue.

#### 10. `ManifestDto` Missing `dataset_id` and `name` Fields per Report (§3)

The completion report §4 says validation rule M-002 checks `dataset_id` and `name` from the manifest. Looking at [ManifestDto](file:///Users/oliver/Projects/bergnerd/SignalForge/backend/src/main/java/com/bergnerd/signalforge/app/research/historical/HistoricalDtos.java#L11-L23), there is no `dataset_id` or `name` field — only `schema_version`, `source`, `coverage`, etc. The report's validation rule descriptions are **not aligned with the actual code**.

---

### MODERATE — Quality and Coverage Gaps

#### 11. Tests Are Fewer Than Claimed

The completion report claims:
- Backend: **119 tests** 
- Frontend: **29 tests**
- Specific M2 test suites: 5 + 4 + 7 + 4 + 12 = **32 new M2 tests**

But the **actual test files for M2** contain:
- [HistoricalImportIntegrationTest.java](file:///Users/oliver/Projects/bergnerd/SignalForge/backend/src/test/java/com/bergnerd/signalforge/app/research/historical/HistoricalImportIntegrationTest.java): **1 mega-test method** (not 5 separate tests)
- [HistoricalBundleParserTest.java](file:///Users/oliver/Projects/bergnerd/SignalForge/backend/src/test/java/com/bergnerd/signalforge/app/research/historical/HistoricalBundleParserTest.java): **4 tests** ✅
- [HistoricalDataValidatorTest.java](file:///Users/oliver/Projects/bergnerd/SignalForge/backend/src/test/java/com/bergnerd/signalforge/app/research/historical/HistoricalDataValidatorTest.java): **5 tests** (not 7)
- [Rfc4180CsvParserTest.java](file:///Users/oliver/Projects/bergnerd/SignalForge/backend/src/test/java/com/bergnerd/signalforge/app/research/historical/Rfc4180CsvParserTest.java): likely 4 tests ✅

The integration test is a single `@Test` method containing 10 numbered verification steps. While functionally comprehensive, this is a **test design concern** — a failure in step 3 masks whether steps 4–10 would pass.

#### 12. Spec-Required Test Scenarios Not Covered

| Spec §8 Required Test | Covered? |
|---|---|
| Real multipart/controller import on temp SQLite with FK | ✅ |
| Persisted rows/decimals/action/date precision | Partial — no scale/precision assertion |
| Missing trading-session bar vs declared closed | ✅ |
| Malformed/duplicate/unsupported inputs | ✅ |
| Same-key retries/conflicts, dedup | ✅ |
| Corrected bytes → new version, old unchanged | ❌ **Not tested** |
| As-of filtering | ✅ |
| Publication failure injection | ❌ **Not tested** |
| Simultaneous duplicate submissions | ❌ **Not tested** (only sequential) |
| Path traversal / expansion limits | ✅ (parser test) |
| Fresh schema + M1b upgrade without changing portfolio | ✅ |
| Frontend upload/status/quality/dataset selection | Partial (unit mocks only) |

#### 13. No Split/Price Discontinuity Diagnostic (§5)

Spec §5: _"Split/price discontinuities as quality diagnostics."_ The validator does **not** implement any diagnostic check comparing pre/post-split prices. The fixture shows a 2:1 split on 2024-01-05 with price going from ~107→54 — this is the expected behavior, but no validator rule flags it as a diagnostic `INFO` finding for user awareness.

#### 14. Missing `turnover` Column in `historical_bars` Table

The completion report describes a `turnover` column in `historical_bars`. The actual [V3 schema](file:///Users/oliver/Projects/bergnerd/SignalForge/backend/src/main/resources/db/migration/V3__historical_datasets_and_import.sql#L48-L60) does **not** have a `turnover` column — only `volume`. The report is inconsistent with the schema.

---

### MINOR — Code Quality Issues

#### 15. `HistoricalImportJobService.processJob` Updates Job Inside Transaction

In [HistoricalImportJobService.java:L239-L242](file:///Users/oliver/Projects/bergnerd/SignalForge/backend/src/main/java/com/bergnerd/signalforge/app/research/historical/HistoricalImportJobService.java#L239-L242), the import_jobs UPDATE is inside the same transaction as the dataset INSERT. If the transaction fails, the job status stays as RUNNING. The outer catch block (L248) handles this, but there's a window where the job could be left in RUNNING if the error occurs during transaction commit and the catch fails to execute. The startup recovery mitigates this, but it's worth noting.

#### 16. File Bytes Read Fully Into Memory

In [HistoricalDataController.java:L43](file:///Users/oliver/Projects/bergnerd/SignalForge/backend/src/main/java/com/bergnerd/signalforge/app/research/historical/HistoricalDataController.java#L43), `file.getBytes()` reads the entire upload into heap memory. Combined with the 20MB compressed limit, this is acceptable for M2, but the spec notes _"bounded resources"_ — worth adding a check before `getBytes()` using `file.getSize()`.

#### 17. `ObjectMapper` Instantiated Per-Service

Both `HistoricalImportJobService` and `HistoricalHistoryService` create their own `private final ObjectMapper objectMapper = new ObjectMapper()`. Spring Boot auto-configures a shared `ObjectMapper` — these should be injected via constructor for consistency and to share Jackson config.

#### 18. Route Configuration Doesn't Lazy-Load Component

[app.routes.ts](file:///Users/oliver/Projects/bergnerd/SignalForge/frontend/src/app/app.routes.ts) defines `{ path: 'research/data', children: [] }` — but `ResearchDataComponent` is not loaded by this route. The component appears to be conditionally rendered in the main `app.html` rather than routed. A deep-link to `/research/data` wouldn't actually render the component via the router — it relies on the main app template's `*ngIf` logic. This means **refresh/deep-link may not work correctly** depending on how the main app handles route matching.

---

## Completion Report Accuracy Summary

| Report Claim | Actual |
|---|---|
| 10 database triggers with specific names | **11 triggers**, different names than claimed |
| `datasets.provider` column | Column is called `source` |
| `dataset_sessions.is_half_day` column | Column is called `session_type` |
| `historical_bars.turnover` column | **Column does not exist** |
| 100:1 compression ratio enforcement | **Not implemented** (absolute limits only) |
| 119 backend tests, 29 frontend tests | May be accurate in count but M2 test structure is inflated |
| 5 integration tests in `HistoricalImportIntegrationTest` | **1 mega-test method** with 10 sections |
| 7 validator tests | **5 validator tests** |
| Sample response shows numeric OHLC | Actual API returns string OHLC (implementation correct, report wrong) |
| Validation rules M-002 checks `dataset_id`, `name` | Manifest DTO has no such fields |

---

## Recommended Actions

### Must Fix (Spec Violations)
1. **Create `planning/reports/research-M2.md`** with the 9 required sections from §9
2. **Correct the completion report** to accurately reflect actual schema column names, trigger names/count, and test structure

### Should Fix (Functional Gaps)
3. **Add pagination** to list endpoints (`limit`/`offset` query params)
4. **Split the integration mega-test** into separate `@Test` methods for isolation
5. **Add test for corrected-bytes-create-new-version** (versioned revision scenario)
6. **Add test for publication failure injection** (transaction rollback mid-import)
7. **Inject `ObjectMapper`** via Spring DI instead of creating new instances

### Nice to Have
8. Add split/price discontinuity diagnostic validation rule
9. Add `file.getSize()` pre-check before reading bytes
10. Document symlink limitation in the import guide
11. Consider proper Angular routing with `loadComponent` for the research-data path
