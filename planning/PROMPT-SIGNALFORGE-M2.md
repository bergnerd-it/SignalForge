# Coding-agent prompt — SignalForge M2

Implement **M2 — historical import and data inspection only** in the existing SignalForge repository. Deliver a working file-import workflow, immutable historical datasets, validation/provenance, a usable data screen, meaningful tests and `planning/reports/research-M2.md`. Continue through implementation and verification; do not stop at a plan or start M3.

## 1. Baseline and checkpoint

Read applicable `AGENTS.md`, existing task-tracking instructions, and:

- `planning/FINALLY-RESEARCH-SPEC-v0.1.md`
- `planning/SIGNALFORGE-SPEC-v0.2-M0-ADDENDUM.md`
- `planning/PROMPT-SIGNALFORGE-M1B.md`
- `planning/reports/research-M1b-fixes.md`
- Relevant current architecture, schema/migration, API, frontend and test files.

The supplied M1b-fixes report documents native accounting, migration/recovery, API and browser success, with 104 backend and 23 frontend tests passing. It is the current reported readiness baseline; verify current code and do not treat historical counts as newly executed results.

That report describes 55 working-tree entries on `6d434c880bd9d316c3cb6fdb50031b7488ca2026`, including untracked required migrations/source/tests. Before M2 edits, identify and preserve the complete M1b state. Follow the repository workflow to create a local M1b checkpoint commit containing only the identified milestone files, or reuse its existing completed commit. Do not indiscriminately stage unrelated changes, reset to the old HEAD or omit untracked implementation files. Do not commit generated databases, checkpoint JARs or secrets. If repository instructions prevent a commit, retain a complete reproducible source manifest/checkpoint and record the reason while continuing authorized isolated work. Do not push or merge.

Use the canonical repository spec, addendum and later user decisions. Do not restart the unresolved search for an unavailable older chat-reference spec. This handoff supplies the bounded M2 contract. Document any actual conflict with an explicit later user decision and preserve that decision.

Docker/container deployment remains NOT VERIFIED under the previously agreed registry-access deferral. Continue native implementation and tests; attempt container checks only when the infrastructure is available. Do not claim a passed container gate based on native tests, and do not repeatedly retry the same network failure.

## 2. Scope and architecture

Keep Java 21, Spring MVC/JDBC, SQLite and Angular. Retain the pinned working Node/npm and Playwright versions from the current repository. Use the existing transaction, error, decimal and local-access policies. No framework/database upgrade, Python service or new infrastructure is required.

M2 includes:

- File upload and a bounded asynchronous import job.
- Historical instrument/listing metadata, sessions, raw daily bars and supported corporate-action records.
- Immutable dataset versions with retained input bytes or equivalent reconstructable source records, checksums and parser identity.
- Validation reports, coverage/provenance APIs and a data inspection screen.
- Explicitly synthetic sample bundles and an import guide.

M2 does not include provider subscriptions/connectors, live market downloads, strategy/universe optimization, momentum ranking, portfolio trading, action booking into the ledger, performance metrics or a backtest engine. M3 owns event-time execution and portfolio corporate-action accounting; M4 owns momentum/trend comparison. No profitability claims are part of acceptance.

Keep current quotes/SSE and historical data separate. Imports never register synthetic history with the demo quote simulator and never mutate portfolio cash, holdings, trade history or chat actions.

## 3. Versioned import contract

Support a browser-uploaded ZIP bundle with exactly these root files. Equivalent separate-file uploads are optional if straightforward; one complete ZIP workflow is sufficient for M2. Document exact column names/types in one versioned contract shared by parser, fixtures and guide.

| File | Required meaning |
|---|---|
| `manifest.json` | Import schema version; source identifier/name; retrieval timestamp; declared coverage; source/use/license note; SYNTHETIC or HISTORICAL classification; RAW price convention; calendar/action completeness declarations; known limitations and availability assumptions |
| `instruments.csv` | Stable instrument and listing IDs; declared type/name; optional ISIN; venue/symbol; quote currency; calendar ID; inception and optional termination date |
| `sessions.csv` | Calendar ID; session date; UTC opening/closing instants; trading/closed-session classification as documented |
| `prices.csv` | Listing ID; session date; raw open/high/low/close; optional volume; `available_at` |
| `actions.csv` | Stable action ID; listing ID; SPLIT or CASH_DISTRIBUTION; effective/ex-date; availability time; exact split ratio or distribution per post-split unit/currency; payment date and optional precise payment instant |

Use UTF-8, unambiguous ISO dates/instants and decimal strings. Support normal CSV quoting/newlines through an appropriate parser. Reuse a suitable dependency if present; if one is absent, make the smallest documented parser/dependency decision consistent with repository instructions. Do not write a naïve comma splitter.

An empty `actions.csv` is accepted only with an explicit completeness declaration for the relevant listings/period. A missing file is not proof of no corporate actions. Completeness and source rights are supplied assertions, not facts independently verified by the importer; display them as such.

Reject unsupported action types and adjustment conventions with a stable error. RAW OHLC is required. Do not silently substitute adjusted closes or mix dividend-adjusted bars with separately applied distributions. A separate vendor-adjusted diagnostic series may be deferred entirely.

For split ratios, preserve an exact representation, preferably positive integer new-unit/old-unit terms; avoid lossy conversion of a rational ratio to a rounded decimal. Cash distribution fields must state that the amount is per post-split unit. Date-only payments retain DATE precision; do not invent a midnight/before-open payment instant. Corporate-action execution is not implemented here.

## 4. Identity and immutable persistence

Add the necessary follow-on schema migration using the existing runner. Preserve applied V1/V2 SQL and metadata; do not rewrite historical checksums or reset a user's database. Test both fresh startup and upgrade from a disposable valid M1b database.

The reported V1 digest includes compiled conversion/accounting dependencies. Inspect this boundary before touching related code: unrelated application changes must not accidentally redefine an applied migration. Keep historical conversion artifacts/dependencies immutable or isolate new logic outside them. Never fix a checksum mismatch by accepting arbitrary old hashes.

Persist dataset identity/status, source/classification, parser/schema versions, input checksum, normalized-content checksum if useful, import time, coverage and validation findings. Bars/actions and the relevant listing/calendar metadata must refer to the immutable dataset version. Later catalog edits must not silently change how an old dataset is interpreted.

Use existing stable listing IDs. Resolve by explicit identity, not ticker alone. Reject collisions or conflicting instrument/currency/venue identity. Do not create invented ISINs, venues or calendars from defaults. Synthetic listings are clearly source-classified, have no invented real-world identifiers and remain in synthetic dataset context. No silent promotion of quarantined/unresolved M1b listings into trusted historical data.

All financial values stay exact at JDBC/API boundaries. Validate input precision against documented supported limits and reject unsupported values rather than round away source data. Preserve raw input for audit. Scope daily uniqueness to dataset/listing/session and corporate-action uniqueness to dataset/action identity; support multiple legitimate actions on one date.

Successful datasets are immutable. A corrected input creates a new version without overwriting the prior bars/actions/metadata. No edit/delete UI for completed datasets is required. Test application-level immutability and appropriate schema protection; do not advertise protection against a database administrator.

## 5. Validation and knowledge time

Validation precedes publication. Findings contain stable code, severity, file/row, listing/session where relevant, and actionable detail. Do not swallow parsing failures or skip invalid rows silently.

Required checks:

- Required files/columns, valid manifest/version/enums, duplicate keys and references.
- Valid exact positive OHLC; low <= open/close <= high; nonnegative optional volume; decimal bounds and precision.
- Valid listing interval, quote/distribution currencies and supported action fields. V1 research is EUR; reject unsupported FX requirements clearly.
- Valid per-listing calendar references and session intervals. Reject bars for closed/nonexistent sessions and duplicate sessions.
- Coverage against supplied expected trading sessions within the declared coverage/listing lifetime. Distinguish declared closures from missing expected-session bars; do not assume every weekday trades or every market uses one calendar.
- Corporate-action identity, positive ratios, consistent ex/payment dates and precision, currency, source completeness and availability metadata.
- Split/price discontinuities as quality diagnostics. Actual prices can move on a split date: do not require a real next price to equal the mathematical split adjustment exactly.

Calendar completeness can only be assessed against its supplied declaration/authority. Do not claim independently verified exchange-calendar correctness from an internally consistent CSV alone. Preserve inception/termination gaps rather than backfilling prices or inventing delisting exits.

Distinguish market-effective time, source-availability time and application-import time. Do not replace historical availability with today's import timestamp. Completed daily-bar availability cannot be asserted before the session is complete under this full-day schema. Corporate actions may be announced before their effective date, so such ordering is not automatically invalid.

If source vintages are unavailable, retain the explicit availability assumption and REVISED_HISTORY quality label. SYNTHETIC fixtures remain SYNTHETIC. A valid import means the data passed its documented contract, not that it is confirmed unbiased investment evidence. Preserve selection/provenance limitations; future universe configuration belongs to later milestones.

Implement an explicit dataset/listing/range history reader, with an optional as-of cutoff that excludes observations unavailable at that cutoff. Test that future observations/corrections cannot change an earlier as-of result for a frozen version. Do not implement next-open fills, total-return signals or portfolio dividend accounting in this reader.

No forward fill, simulator fallback, silent shortened coverage or dropping failed listings. For range requests outside coverage, report requested versus available dates and the reason explicitly.

## 6. Safe jobs, idempotency and publication

POST upload returns an import job ID after accepting bounded input. Use one active import and a bounded queue with documented overload behavior. Poll through a scoped job API; persist lifecycle/progress/error results sufficiently for restart handling.

States include QUEUED, RUNNING, COMPLETED, FAILED and INTERRUPTED. An interrupted job is not a successful dataset; do not automatically resume partial work without a defined recovery protocol. Cancellation may be deferred if not already part of the repository contract.

Require a stable request key for a user upload intent. Same key/same bytes and parser/options returns the original job/result; conflicting reuse returns 409. Successful identical-byte imports under the same parser/schema/options reuse the existing dataset even with a new request key. Changed bytes create a new version. A deliberate reprocessing after failure uses a documented retry/new-intent path; no double publication or stuck permanent reservations.

Retain staged input privately, parse/validate with bounded resources outside long financial transactions, then atomically publish the normalized rows and successful result. Queries must never expose half a completed dataset. Import errors may leave a diagnostic job record and retained input under the documented policy, but not visible partial history or changed financial state. Coordinate database writes with existing SQLite rules.

On application restart, detect unfinished work, expose an honest interrupted status and clean/recover task-owned staging under a documented rule. Never delete files based on client-controlled paths.

Enforce archive/input limits before and during decompression: compressed and expanded byte caps, per-file/row/entry limits and bounded queue retention. Reject duplicate archive filenames, unexpected/nested paths, traversal/absolute paths, symlinks and excessive expansion. Use only allowed root filenames and controlled task directories. Surface limits in the UI. Do not accept an arbitrary server filesystem path through the upload API.

Serve source/error content as escaped text, never HTML. Keep source credentials out of logs, metadata and examples. Existing local-origin/host protections must continue to cover upload mutations.

## 7. API and data screen

Implement or extend the actual `/api/research` contracts:

| Endpoint | Purpose |
|---|---|
| POST `/imports` | Upload accepted bundle; return job ID and status |
| GET `/jobs/{id}` | Import progress, completion/failure/interruption and dataset reference |
| GET `/datasets`, `/datasets/{id}` | Published versions, provenance, coverage and validation summary |
| GET `/history/{listingId}` | Explicit dataset and bounded date range; decimal strings, timestamps and quality metadata |
| Dataset-scoped action/session/quality resources as needed | Inspect exact imported records and diagnostics without global ambiguous defaults |

Reuse existing instrument/listing APIs where suitable. Every history query specifies a dataset; never silently select the latest revision for an already selected dataset. Bound/paginate responses and index range access. Separate failed import jobs from successful published datasets.

Add `/research/data` with upload, status, dataset selection, coverage per listing, sample dated bars/actions and validation/provenance display. Show SYNTHETIC/REVISED_HISTORY and declared-source limitations prominently enough to understand the selected data. Handle empty/loading/error/insufficient-coverage states. Do not invent charts or returns to fill empty space.

Reuse typed services, switchMap/cancellation, lifecycle teardown and zoneless rendering patterns corrected in M1b. Old upload/dataset responses must not overwrite the currently selected context. A refresh/deep link must load the data screen correctly. The entire workflow works without an LLM or current quote provider.

## 8. Fixtures, guide and meaningful verification

Create deterministic, clearly SYNTHETIC bundles generated from a checked-in script or transparent source CSVs. Include a small valid multi-listing dataset with a declared closure, a 2-for-1 split, and a distribution with separate ex/payment dates. Include invalid variants for missing bars, unsupported adjusted data, duplicate identity, bad action metadata and archive rejection. Do not use fake real-world investment evidence or require paid data.

Document exact field names, dates/precision, assumptions, limits, example invocation/upload flow and error interpretation in `planning/docs/historical-import.md` or the repository's established equivalent. Provide one ready-to-import valid bundle and repeatable generation command.

Tests must cover:

- Real multipart/controller import on the production-configured temporary SQLite database with foreign keys enabled.
- Correct persisted rows/decimals/action/date precision and dataset metadata for the valid fixture.
- Missing trading-session bar versus a declared closed session; no silent data repair.
- Malformed/duplicate/unsupported inputs with useful file/row errors and no published partial dataset.
- Same-key retries/conflicts, successful same-byte deduplication and simultaneous duplicate submissions.
- Corrected bytes create a new immutable version while the old history remains unchanged.
- As-of availability filtering without importing today's timestamp as historical knowledge.
- Whole-publication failure injection and process/context restart with interrupted job handling and no duplicated successful dataset.
- Path traversal, duplicate archive entries and expansion/size limits.
- Fresh schema and M1b-to-M2 upgrade without changing existing portfolio cash, holdings, ledger, operations or chat evidence.
- Frontend upload/status/quality states, obsolete-response rejection, dataset version selection and visible synthetic labels.

Keep M1b financial tests and the existing suites passing. Run clean backend/frontend tests and builds with the established toolchains. Execute a native API and, when available, browser walkthrough: upload valid bundle, inspect it, re-import it, submit an invalid variant and verify the unchanged earlier dataset. Test discovery/unit mocks are not a real browser run. Record unavailable browser/container gates explicitly.

Stop optional testing once these concrete risks are sufficiently covered. Do not add broad performance optimization, CI/lint ecosystems, actual provider integration or backtesting to satisfy a local test.

## 9. Completion report and stop condition

Write `planning/reports/research-M2.md` with:

1. M1b checkpoint and final tested source identity including any uncommitted/untracked implementation state.
2. Implemented schema, file/API contract and important decisions with paths.
3. Fixture/source classification, validation examples and identity/knowledge-time limitations.
4. Actual commands/tool versions/test counts, plus native API/browser results.
5. Evidence for retry/deduplication, immutable revisions, failed publication and restart handling.
6. Migration/checksum compatibility and proof that pre-existing financial state is unchanged in disposable upgrade tests.
7. Short local walkthrough with the actual included example bundle path.
8. PASS/FAIL/NOT VERIFIED by required gate, including inherited container limitations.
9. Remaining D2/D3 choices (real listings/data provider/budget) and M3 readiness. Synthetic data proves software behavior only.

Complete all feasible M2 implementation and validation, preserve the report and stop before M3. Do not migrate the owner's working database during tests, connect a broker, buy data or publish remotely.
