# M4 research and implementation closeout

**Updated:** 2026-09-14
**Specification:** `planning/PROMPT-SIGNALFORGE-M4.md`
**Review history:** `planning/reports/M4-code-review.md`
**Implementation walkthrough:** `planning/reports/walkthrough-M4.md`

This report describes the current uncommitted M4 tree after the independent review and TASK-16 corrections. The earlier report described the initial V8 implementation and claimed more verification than its evidence supported. Those claims are superseded here; the original review remains in the code-review report.

## Source and migration state

M3's S1 buy-and-hold baseline remains covered by the backend regression suite. M4 adds total-return signals, S2 momentum, S3 trend, owner-scoped universes, comparisons, experiments, holdout exposure events, and research UI screens. The current migration target is **V10**, not V8. V8 introduced the M4 tables, V9 applied the first review corrections, and V10 restores the original S3 `1.0.0` parameter metadata, publishes corrected no-parameter S3 metadata as `1.0.1`, and allows a final `UNEXECUTED` signal to have no scheduled execution date. The final database therefore has four strategy-version rows across three strategy IDs. New S3 requests should use `1.0.1`; the UI selects that version. Existing `1.0.0` rows retain their original metadata after the complete migration chain.

`MigrationRunner` registers and verifies V10 for fresh, legacy, and versioned databases. A populated V9-to-V10 upgrade with existing signal-item rows has **not** been separately exercised in this verification run; production upgrade confidence is limited to the migration recovery suite's covered paths.

## Strategy and accounting conventions

The total-return signal index is separate from portfolio cash and accounts for splits and cash distributions on their effective dates. Its point-in-time calculation excludes bars and actions reported after the decision instant and rejects records without an availability timestamp. The engine computes a decision instant from required observations, delays monthly execution to the first eligible open when a later observation arrives late, and records a final signal as unexecuted if no open remains. Initial data that misses its cutoff is rejected before funding so the candidate and benchmark retain a common starting boundary. Required lookback sessions are restricted to the loaded interval, including one prior observation.

S2 ranks the universe by `T(m-1) / T(m-12) - 1`, breaks ties by listing ID, and allocates equally to the top K. K defaults to 3 and must be an integer between 1 and the universe size. S3 uses the fixed ten-month simple moving average: an index strictly above its SMA targets the ETF; equality or below targets cash. Its ETF target uses `floor(weight × pre-trade equity / raw open)`. Dividend payments while ETF allocation prevails schedule reinvestment at the next eligible open, including intraday and date-only settlements. The shared execution engine sells excess before buying, records requested and executed quantities, and prevents cash overdrafts.

The API rejects unknown strategy versions, unsupported or malformed parameter JSON, fractional/out-of-range K, incompatible listings, non-EUR or calendar-mismatched universes, and foreign-owned universes. Universe creation is transactional and returns metadata from the declared dataset instead of synthesizing listing attributes. S2 run creation and completion have an HTTP integration fixture with a valid 15-month, two-listing dataset.

## Comparison, experiments, and exports

Comparison creation uses an idempotency key. Match checks include configuration, dataset and cost assumptions, funding event instant, reporting-session sequence, and full benchmark equity/accounting series. The frontend reads paged comparison and signal responses and uses the actual export routes. Signals CSV export is capped at 10,000 rows and 5 million characters, uses CRLF records, and protects text cells beginning with spreadsheet formula characters.

Experiment creation parses ordered development/holdout boundaries, requires real trading-session dates and dataset coverage, validates strategy and listing identities, checks universe owner/dataset/calendar/EUR compatibility, and uses `UNKNOWN` when the holdout status is not declared. Result exposure paths append an allowed exposure event before returning a holdout result; failures fail closed. The tested result paths include the backtest summary list (`SUMMARY_VIEW`) and signals CSV (`EXPORT_DOWNLOAD`). Rolling five-year windows check calendar anniversary coverage and return an incomplete-window reason when an expected end-equity mark is missing. Exact-decimal compounded returns are present in the DTO.

## Verification performed on this tree

| Check | Result |
| --- | --- |
| `backend/./gradlew clean test` | 175 tests, 0 failures, 26 XML test suites |
| `frontend/npm test -- --watch=false` | 43 tests passed in 3 files |
| `frontend/npm run build` | Succeeded; existing research-backtests CSS budget warning (915 bytes over 10 kB) |
| Backend formatting | No `spotlessApply` task is configured |
| Frontend lint | No `lint` script is configured in `package.json` |

The backend suite includes S1 baseline regression, M4 strategy/index arithmetic, migration recovery, valid S2 execution and owner rejection, a later-month bar reported after the immediate next open that moves execution to a later open, endpoint exposure types, strategy catalog, and signals CSV. The frontend suite verifies services and component construction/contracts at the unit-test level. This correction run did **not** repeat a native browser walkthrough or a populated V9 upgrade. Accordingly, the prior report's named browser run, working-download, `UNIVERSE:core50`, exact browser bundle-size, and blanket nine-gate PASS claims are withdrawn; automated tests and production compilation are the evidence above.

## Scope limits

The fixtures use deterministic synthetic market data. Orders model session-open execution; taxes, FX, and live brokerage are outside M4. The UI and API expose historical research and comparison outputs, not predictive performance claims. Five-year overlapping return windows are descriptive and serially correlated.
