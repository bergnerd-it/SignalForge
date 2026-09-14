# M4 implementation walkthrough

Updated 2026-09-14 after the independent M4 code review and TASK-16 corrections. Read `planning/reports/research-M4.md` for the current evidence and limitations. This document describes the implemented paths; no native browser session was repeated for this update.

## Data and strategy path

A research user selects a validated dataset, candidate or universe, benchmark, dates, and a registered strategy version. `BacktestJobService` checks parameter JSON, strategy version, K bounds, ownership, and experiment identity before queuing. `BacktestDataReader` validates the calendar, EUR listings, required bars and actions, point-in-time availability, and a common initial funding boundary. Monthly evaluations build the total-return index only from loaded sessions; late observations delay the scheduled open, and an evaluation with no later open persists as `UNEXECUTED`. A null availability timestamp cannot enter a point-in-time signal index.

S1 retains the M3 buy-and-hold behavior. S2 uses the 12-1 momentum score, stable listing-ID tie breaks, and top-K equal weights. S3 uses the fixed ten-month trend rule and `1.0.1` for new requests. Its target sizing uses pre-trade equity. While ETF allocation persists, distribution payments can trigger next-open reinvestment even between monthly evaluations. Executed orders store requested and executed quantities; a cash-limited buy is recorded as a filled partial quantity with a shortfall reason.

## Research API and UI path

The strategy catalog exposes four version rows across S1, S2, and S3. Universe responses contain listing objects with real dataset symbol, calendar, and currency. The Angular selectors extract `listingId` from those objects. The Signals tab reads paged JSON and downloads `GET /api/research/backtests/{id}/signals/export`; comparison exports use `GET /api/research/comparisons/{id}/export`. The comparison client sends an idempotency key, pages list results, and renders the backend's expected/actual mismatch fields.

Experiment creation requires ordered development and holdout trading-session boundaries and compatible dataset, strategy, benchmark, and owner-scoped universe metadata. Result list and export paths record permitted append-only exposure types before revealing holdout results. Comparison matching checks the actual funding event instant, reporting sessions, and benchmark accounting series. Rolling windows require calendar anniversary coverage and an end-equity mark, or report an incomplete reason.

## Schema and verification

V8 created M4's strategy versions, universes, signals, comparisons, and experiments. V9 contains the initial correction migration. V10 restores S3 `1.0.0` metadata after V9, adds corrected S3 `1.0.1`, and permits a final unexecuted signal without a scheduled date. `MigrationRunner` targets V10. The original review and follow-up findings remain in `planning/reports/M4-code-review.md` as historical evidence.

`./gradlew clean test` passed **175 backend tests** with no failures; `npm test -- --watch=false` passed **43 frontend tests**; `npm run build` succeeded with a research-backtests CSS budget warning. No backend Spotless task or frontend lint script is configured. The current correction run did not perform a native browser walkthrough or a populated V9-to-V10 signal-row upgrade, so those behaviors are not claimed as manually verified.
