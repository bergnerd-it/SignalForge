# SignalForge — M3 fixes and evidence-based closeout

Fix the existing M3 implementation and complete its original acceptance gates. Do not start M4. Read applicable AGENTS.md/task-tracker instructions, the canonical specification and addendum, planning/PROMPT-SIGNALFORGE-M3.md, planning/reports/research-M3.md and the uploaded M3-code-review.md (locate its repository equivalent).

Treat both reports as claims to verify against actual code. Review source identity was 855b9498bef8663a8223ad3e695d190f76a21281 plus tracked/untracked changes; preserve the complete current tree and establish a reproducible checkpoint under repository rules. Do not overwrite user changes, push or deploy. Tests use disposable databases only; never inspect or migrate the owner's working database. Keep Java/Spring/JdbcTemplate/SQLite and the actual pinned Angular stack. Docker remains explicitly deferred.

Use the review finding IDs below to track disposition, evidence and remaining limitations. Fix demonstrated defects and missing original requirements; do not add an authentication system, distributed workers, new strategies or unrelated refactoring.

## 1. SQL binding and owner/run isolation — CRITICAL-1

Remove concatenation of request-controlled series values from SQL. Parse an allowlisted SeriesType and bind every filter through JDBC parameters. Invalid series returns an actionable 400. Audit adjacent backtest queries for the same pattern.

Resolve owner context consistently with the existing local application convention on every endpoint: detail, cancel, equity, orders, events, holdings and export as well as creation/listing. Scope parent and child access accordingly; unknown and other-owner IDs should follow the existing non-disclosing 404 policy. Scope export before sending response bytes.

The current X-User-Id convention, if confirmed, is client-supplied local identity, not authenticated identity. Enforce consistent isolation without claiming protection against a client choosing a different header value. Preserve loopback and mutation-origin restrictions; remote authentication is outside this task.

Prove cross-owner read/export/cancel rejection and malicious series rejection through real controller/service integration tests on temporary SQLite, not just mocked services.

## 2. Immutable configuration and deterministic events — HIGH-3

Persist the complete normalized configuration required by the M3 prompt at creation: dataset input/content checksums, parser/schema identity, selected listing roles, calendar/timezone and coverage, cost/decimal/accounting/execution/engine versions, source build/commit/dirty identity, classification and availability assumptions. Record resolved defaults and supported precision explicitly. Reject unsupported precision rather than silently rounding request intent.

Keep canonical client-intent identity distinct from the frozen resolved execution snapshot where necessary. Same-key/same-client-intent replay returns the original run and its original metadata even after a software upgrade; it must not relabel it with the current engine. Conflicting intent returns 409. A new key can create a new reproducibility run using the current engine; do not reuse old completed results solely because an incomplete configuration hash matches.

Export stored metadata verbatim. Never invent historical build identity for old runs missing it. Preserve those records, label incomplete provenance explicitly, and require a newly created run for full reproducibility claims.

Use historical event timestamps and an explicit stable business sequence. Random run IDs and wall-clock job timestamps are allowed operational metadata; they are not themselves a defect. They must not determine financial event order or represent market time. Compare repeat runs after excluding operational identifiers/timestamps, preserving all financial inputs, market timestamps, reasons and business ordering.

## 3. Time, payments, eligibility and accounting — HIGH-1 / HIGH-2

Validate the requested evaluation session as the completed month-end session and cutoff against required observation availability. Parse dates and instants using their actual types, never substring/lexicographic comparisons. Enforce the selected end session and full reporting coverage. Reject absent/closed end sessions with a clear explanation instead of silently choosing an earlier end.

Use actual calendar open/close instants, not fixed example clock times. Preserve the narrow historical opening-price adapter and strict full-bar availability semantics from the original prompt. Future highs/lows/closes must not influence earlier opening fills.

Process payments on the complete event timeline: precise intraday payments affect the appropriate cash snapshot; date-only payments become spendable after the session close or at the documented end-of-day boundary on declared closed dates. Reinvestment uses the first strictly subsequent eligible open. An exact opening-time payment cannot fund that same opening execution under the M3 convention.

Persist each outstanding receivable with action identity, booked amount, entitlement time and known payment date/instant, including payments beyond run end. Preserve split-before-entitlement ordering and pre-trade eligibility. A subsequent split must not multiply an already booked cash receivable.

Reject missing/invalid ratios, missing required availability and non-EUR distributions. Do not default a bad split to 1. Retain the rational split representation and the existing quantity policy; reject unrepresentable outcomes rather than silently rounding away units. Preserve total basis, exact booked cash, rounded affordability and the independently calculated EUR 1018 reference result.

Verify S1 only creates reinvestment intent from paid distributions as specified, without introducing an undisclosed daily residual-cash buying strategy. Restore default spread/slippage to the specified nonzero scenario values if the zero-cost reference fixture values leaked into normal creation defaults.

## 4. Durable lifecycle and bounded work — HIGH-4

Make request reservation and duplicate/conflict resolution safe across independent concurrent requests. Use durable uniqueness, valid transaction boundaries and bounded whole-operation retries consistent with SQLite. Do not rely on an in-memory lock alone.

Make all terminal states repeat-safe, including CANCELLED. A queued cancelled task must be skipped without attempting a prohibited terminal update. Resolve cancellation versus result publication with conditional durable state transitions and checked affected-row counts. Dataset-independent run results and COMPLETED status publish atomically; cancellation or injected failure must not expose a partial completed result.

Verify the actual executor queue capacity. The report names a single-thread executor; single active worker is correct, but an unbounded task queue does not meet M3. Configure bounded queuing/overload behavior and ensure rejected work does not leave a permanently QUEUED record. Test startup recovery for both QUEUED and RUNNING and document deliberate retry behavior.

## 5. Analytics, schema and export — MEDIUM-1 / MEDIUM-2 / MEDIUM-3

Include the initial funded point so first-fill costs appear in the displayed equity curve and first-year return. Derive annual boundaries and partial-year labels from the actual calendar/reporting interval. The reference first-year return must be 1.8%, matching cumulative return, rather than using 999 as starting equity. Do not count an extra event snapshot as an extra trading-day return. Add required exposure/cash weights and disclose receivable treatment and turnover formula.

Use follow-on migrations after the actual current version; do not edit applied V4 or redefine earlier checksums. Add terminal-parent insert guards for result tables and composite dataset/listing foreign keys for candidate and benchmark. Publication must insert results before marking the parent complete in the same transaction. Check existing rows before stricter migration; never silently drop invalid rows or fabricate provenance. Preserve all M1b accounts and M2 datasets in upgrade tests.

Bound and stream exports using the existing stack, including bounded reads rather than merely streaming an already fully buffered archive. Document row/byte limits and error behavior. Export exact negative decimal fields unchanged. Apply spreadsheet formula protection by column/type to untrusted text; it must not prefix valid numeric drawdowns or cash deltas with apostrophes. Verify downloaded contents against stored metadata and API/accounting totals.

## 6. Correct frontend contracts and complete results — HIGH-5 / MEDIUM-4

Use exact typed PagedResponse<T> and backend-aligned DTOs/status enums, including SKIPPED. Remove permissive any/fallback adapters that mask a wrong contract. Return authoritative cash impact or define its exact calculation under one contract; never display a fabricated 0.00 for a missing fill field.

Load candidate and benchmark explicitly. Consume all required chart pages or use an explicitly bounded complete-series endpoint; show limitations rather than drawing an apparently complete truncated curve. Add real pagination/continuation to tables. Dataset/listing selectors must also expose further pages when applicable.

Use selected-run streams with cancellation/stale-response suppression, lifecycle teardown and correct zoneless updates. Direct links load the requested run by ID, regardless of whether it is in the first list page. Verify slow run-A responses cannot overwrite selected run B. Keep source labels and assumptions visible. Do not raise build budgets merely to mask a new failure without measuring and explaining the change.

## 7. Focused evidence and truthful report — MEDIUM-5

Cover every still-missing original M3 acceptance gate with independent expected outcomes. Existing meaningful tests may be reused; do not multiply equivalent tests. At minimum establish:

- SQL/owner scoping and typed filters.
- Valid and invalid month-end/cutoff/end boundaries and holidays.
- Precise, date-only, closed-day and beyond-end payments; split precision/currency/availability; ex-date entitlement.
- Future-data mutation isolation, default execution costs, affordability and zero-order behavior.
- Input history longer than a default/max M2 page and complete candidate/benchmark UI data.
- Two independently executed identical runs and immutable old results after dataset correction.
- Concurrent creation, repeat/queued cancellation, cancellation-publication race, injected rollback and both startup interruption states.
- Actual M2-to-current upgrade preservation, terminal child-insert guards and composite listing FKs.
- Initial funding/annual returns, known drawdown recovery, insufficient sample metrics and exact export values.
- Typed UI contracts, stale responses and deep-link loading beyond the first list page.

Use actual production configuration/foreign-key-enabled temporary SQLite for integration evidence. Coordinate concurrency tests with deterministic barriers; sequential replay is not concurrency evidence. Do not change old migration digests or disable guards to make tests pass.

Run required backend/frontend suites and production builds with exact tool versions recorded. Perform a native browser walkthrough on a disposable database: creation, both chart series, a long paginated result, run switching/deep link, cancellation/error, and actual ZIP download. Record reproducible commands/steps and the downloaded file's checksum and checked contents. If a browser capability is unavailable, say NOT VERIFIED instead of inventing a walkthrough. Docker remains a separate deferred gate.

Write planning/reports/research-M3-fixes.md with reviewed/tested source identity, changes, a finding-by-finding disposition table, actual commands/test counts, independent reconciliation, browser/download evidence, limitations and gate status. Correct research-M3.md or clearly mark it superseded. Distinguish runtime reproduction, static evidence and untested risk; remove unsupported claims of zero lookahead bias or full verification. Record the actual migration runner rather than calling it Flyway without evidence.

Update the task tracker. Recommend M4 only when functional M3 requirements and applicable native gates are met; unresolved Docker alone is not a blocker. Stop after M3 closeout and return report paths and concrete remaining blockers, if any.
