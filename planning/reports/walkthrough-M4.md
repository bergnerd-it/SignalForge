# M4 implementation walkthrough

Updated 2026-09-15 after populated-upgrade and native-browser verification. See `planning/reports/research-M4.md` for commands, source identity, hashes, review dispositions, and gates.

## Data and strategy path

Run creation validates strategy/version, parameters, ownership, calendars, EUR listings, required observations, point-in-time availability, experiment identity, and a common funding boundary. Monthly signals use loaded total-return data; late observations delay execution; a final evaluation without another open persists as UNEXECUTED.

S1 retains buy-and-hold. S2 uses 12-1 momentum, listing-ID tie breaks, and equal top-K weights. S3 uses fixed ten-month trend version 1.0.1, pre-trade-equity sizing, and next-open eligible distribution reinvestment.

## Research API and UI path

The catalog has four immutable versions. Signal JSON is paged and CSV bounded. Comparisons use idempotency, strict financial matching, and explicit complete/incomplete rolling windows. The UI now labels comparison run counts neutrally, unwraps paged experiments, fetches selected exposure detail, and shows each annual row's own start/end equity.

Experiments require ordered trading-session development/holdout boundaries and compatible strategy, dataset, benchmark, candidate, and owner-scoped universe identities. Result reads append an allowed exposure event before returning output.

## Schema and verification

V8 created M4 tables; V9 applied initial corrections; V10 restores immutable S3 1.0.0 metadata, publishes 1.0.1, and permits final unscheduled UNEXECUTED signals. Verification demonstrated mutable terminal signal items, so forward-only V11 adds insert/update/delete guards. `MigrationRunner` now targets V11.

The backend suite passed 176 tests and frontend passed 44. Native Chrome verified disposable data, S1/S2/S3 detail and signals, deep links, matched/rejected comparisons, and rolling states. Actual ZIP/CSV bytes were inspected. Native modal creation, the corrected holdout registry, browser-managed saving, and production build on declared Node 24.21.0 remain NOT VERIFIED. Docker remains deferred.
