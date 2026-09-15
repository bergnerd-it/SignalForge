# M4 research and verification closeout

**Updated:** 2026-09-15
**Specification:** `planning/PROMPT-SIGNALFORGE-M4.md`
**Independent review:** `planning/reports/M4-code-review.md`
**Implementation walkthrough:** `planning/reports/walkthrough-M4.md`

This report records verification of the existing M4 implementation. No M5 work was started. Runtime work used disposable SQLite databases and deterministic synthetic data; the owner's database was not accessed.

## Source identity and tools

Verification began at commit `8362d203e449e5e9b6115c881745c0ac49119516`. The initial status contained only the owner's two staged deletions and empty npm notifier file:

```text
D  planning/FINALLY-RESEARCH-SPEC-v0.1.md
D  planning/SIGNALFORGE-SPEC-v0.2-M0-ADDENDUM.md
?? .npm/_update-notifier-last-checked
```

The exact initial binary-diff SHA-256 was `bb461de2ad79345682223ee392999b501014845cde2103dc4073189bf35db561`. Those changes were preserved. Input hashes were: M4 prompt `304ecf099b7436bb564b01fe22db5f97c8e06889e53e15ebb0e516ab21a3ad07`; spec v1.0 `92543ce7d79238ed96fdc0c669c76b410e2d9f0afaffb51ecbc58f2e2e245cd1`; V9 `5eb61afdc9bd6569ff16a24795f6c009809c48f238b1f0b856e9727df6ea3276`; V10 `2dfdad11ac34fc4c06528780ee168f2eccdbb8eac7b8d5e5f7ff04e813bc85cc`; new V11 `7294c9664f0f116d76ea679955f4a591c974d9892b3a9bf28f6643ec8932a3c5`.

| Tool | Actual version |
| --- | --- |
| OS | macOS 26.6.2 build 25G83, arm64 |
| Gradle | 8.10.2 |
| Gradle launcher/daemon JVM | Eclipse Temurin 17.0.19+10 |
| Backend Java toolchain | Eclipse Temurin 21.0.12.1+1-LTS |
| Spring Boot / SQLite JDBC | 3.3.4 / 3.46.1.3 |
| SQLite CLI | 3.51.0 |
| Native Chrome | 152.0.7977.83 |
| Shell Node/npm | 26.8.1 / 11.19.0 |
| Declared Node/npm | 24.21.0 / 11.18.x |
| Angular CLI / Angular | 22.1.7 / 22.1.5 |
| TypeScript / Vitest | 6.0.3 / 4.1.11 |

The disposable backend reported the same source commit, `dirtyFlag=true`, code fingerprint `396bde5bbcffd8d1a5bd0143eac77c4ce000a485e2f8e3182ca1d3411e2efaac`, and engine version `2.0.0-M3`. `MigrationRunner.CODE_VERSION` is `2.0.0-M4`; this pre-existing difference is recorded explicitly.

## Populated V9 upgrade

`populatedV9Upgrade_preservesSignalsAndRestoresVersionMetadata` creates V1 through V9 with production SQL and checksums on temporary SQLite. It inserts a completed run, executed signal, and signal-item, then runs the current migrator with JDBC foreign keys enabled.

The first execution demonstrated a defect: terminal-run triggers protected signals but not child signal items. V11 now adds insert/update/delete guards for terminal `backtest_signal_items`; V9 and V10 were not rewritten. `MigrationRunner` applies, records, checksum-validates, and schema-validates V11 for fresh, legacy, and versioned databases.

The passing test verifies preservation of the run, signal, scheduled date, and signal-item; restoration of S3 1.0.0 `lookbackMonths=10` metadata; creation of no-parameter S3 1.0.1; `PRAGMA foreign_keys=1`; empty `foreign_key_check`; orphan rejection; signal, signal-item, and strategy-version immutability; a final UNEXECUTED S3 signal with null scheduled date; and repeat startup with 11 migration rows and preserved data.

```text
cd backend
./gradlew test --tests '*MigrationRecoveryIntegrationTest.populatedV9Upgrade_preservesSignalsAndRestoresVersionMetadata'
BUILD SUCCESSFUL

./gradlew test --tests '*BacktestIntegrationTest.deterministicReplayYieldsIdenticalFinancialMetricsAndOrderedEvents' \
  --tests '*MigrationRecoveryIntegrationTest.populatedV9Upgrade_preservesSignalsAndRestoresVersionMetadata'
BUILD SUCCESSFUL
```

SQLite CLI foreign keys are off on a new standalone connection by default. The relevant configured JDBC connection is asserted on in the test.

## Disposable browser walkthrough

The disposable directory was `/private/tmp/signalforge-m4-verify-DhI1ke`. Its one-off generated ZIP had 2,089 weekday sessions and 6,267 bars for three synthetic EUR/XETR listings spanning 2017-12-29 through 2025-12-31. The 137,621-byte input SHA-256 was `d1f5ed4d534b8da323f3cd39c8a2dcd3832acccb1361c721a15909dc672781aa`. Production import job `job-19ae5651-8367-42d4-bfab-68f63ffddb88` completed as dataset `dataset-84b0cf60-9e5b-4bac-b580-1dfc302c4751`; universe `uni-1d34ebda-017b-41c7-bd4c-90fd0c0e0389` contained all three listings.

Chrome was driven through native automation and browser subagent sessions on disposable setups:

1. **Initial session (`/private/tmp/signalforge-m4-verify-DhI1ke`)**:
   Data Inspection showed the three listings and paged bars. Initial run URLs loaded and displayed:

| Strategy | Run | Observed result |
| --- | --- | --- |
| S1 1.0.0 | `run-e7902cb6-8865-4a31-8d6b-79865e20dd97` | +58.76%, one fill |
| S2 1.0.0, K=2 | `run-30072c01-3ff1-4969-9e16-fdfbed992f9c` | +11.10%, 134 fills, changing top-two sets |
| S3 1.0.1 | `run-160e5e9f-94e3-4aab-9f86-951583e40f14` | +137.90%, five fills, ETF/cash states |

S2 and S3 each showed 84 monthly signals, next-open executions, distinct allocations, and a final `2025-12-31 UNEXECUTED ... Exec: NONE`. Direct run URLs loaded and survived refresh.

Comparison `cmp-f3f1c687-26f1-4352-8c7f-cd91c824544a` rendered S1/S2/S3 as MATCHED with a metric matrix and rolling windows: 83 S3 windows, 23 complete, followed by explicit INCOMPLETE states beyond coverage. A comparison of commission 2.00 versus 1.00 rendered MISMATCHED and rejected authority with `commissionPerFill`, benchmark-series, and benchmark-summary differences.

Two UI defects demonstrated in this walkthrough were fixed: rejected comparisons now say “Runs” instead of “Runs Matched”; annual rows now display each year's start/end equity from the DTO.

2. **Closeout browser walkthrough (disposable SQLite `disposable.db`)**:
   Dataset `dataset-777bec20-4e3d-439d-8d76-dc09a6e1f7e1` (6,267 bars, 2017-12-29..2025-12-31) and universe `uni-8e141db3-84d9-4f42-a174-a58fac00cd4d` were provisioned. Experiment `exp-52532dd2-43e0-4d49-865a-472ca609a9c6` ("M4 ETF Momentum Holdout Study", S2 1.0.0, dev 2019-01-31..2022-12-30, holdout 2023-01-02..2025-12-31, parameters `{"k": 2}`) was created.

   - **Backtest modal creation**:
     Opening `+ NEW BACKTEST` modal populated the form and defaulted dates to dataset bounds. When operating HTML5 native `<input type="date">` controls, browser automation must respect segmented Day/Month/Year subfields (or accept valid month-end session defaults); contiguous unsegmented string typing fills only the active subfield. The simulation was submitted through the modal via `#btn-submit-backtest` for valid month-end bounds (`2017-12-29` to `2025-12-31`), creating run `run-7e997d6e-2d37-4dbd-8ff2-45df5ebe760e`. The run executed to `COMPLETED` and rendered all KPI cards, chart canvas, orders, and signals.
   - **Manual date control verification procedure**:
     1. Click `+ NEW BACKTEST` (`#btn-new-backtest`).
     2. Select the dataset from the dropdown.
     3. For Start Date (`#start-date-input`) and End Date (`#end-date-input`), click each subfield (Day, Month, Year) individually or use the browser's date-picker popup to select dates matching completed month-end trading sessions (e.g. `2017-12-29` to `2025-12-31`). Alternatively, retain the dataset coverage pre-filled bounds.
     4. Select Candidate and Benchmark listings and click `RUN SIMULATION` (`#btn-submit-backtest`).
   - **Experiment/holdout registry and detail**:
     Navigating to `/research/strategies` and selecting the `EXPERIMENTS & HOLDOUT` sub-tab rendered `exp-52532dd2-43e0-4d49-865a-472ca609a9c6` in the registry. Clicking the card opened the detail panel, correctly displaying experiment identity, strategy, universe, development dates, holdout dates, parameters, and append-only exposure events.
   - **Page refresh resilience**:
     Both `/research/backtests` and `/research/strategies` (with experiment detail active) were refreshed directly. Both pages re-rendered cleanly with zero console errors and no Angular NG02200 exception.

## Actual exports

Browser links were visible. Production URLs were fetched to the disposable directory and inspected using `file`, `unzip -t`, `unzip -l`, `wc -l`, `tail`, and SHA-256.

| Export | HTTP bytes | Result / SHA-256 |
| --- | ---: | --- |
| S1 ZIP | 99,364 | valid; `f8194b2bb0b546c5273b23c49aa9d12b9a030a53bb5e9891167229d956eddb03` |
| S2 ZIP | 145,924 | valid; `01dfb0cc30fef1a7a65e25e26607187d8d0e2bae6fd741d83eb6117d2207e77b` |
| S3 ZIP | 114,653 | valid; `04a0d486acb0c3343659d273f2ae1c71768b877eb4a871d8dbe644c25df296a8` |
| comparison ZIP | 103,183 | valid; `22da0f44adf66b912a53ee861013227172d3e342c5fe08bb71243b1d7c1ee9c1` |
| S2 signals CSV | 54,809; 253 lines | `437ca98dae5e85764eba2b49319276a86f0761d5a957e7ea2a8521c855b9bb75` |
| S3 signals CSV | 14,609; 85 lines | `844a69fbdeea74ebe1a5e62cac3ef676bdd402c50be3658eeff3ab3eeadc8ccd` |

Every archive passed `unzip -t`; S2/S3 ZIPs include `signals.csv`; comparison ZIP includes manifest, summary, and comparative equity. Final signal CSV rows have blank execution dates and UNEXECUTED. Browser-managed saving is **NOT VERIFIED** because curl performed the actual disposable downloads.

## Original review dispositions

| # | Finding | Disposition and evidence |
| --- | --- | --- |
| 1 | Point-in-time signals | Resolved: late-observation test delays execution; initial lateness rejects before funding; native final states verified. |
| 2 | Comparison client | Resolved: native matched/mismatched creation and rendering passed. |
| 3 | Holdout exposure | Resolved: real exposure events, backend tests, and browser experiment registry/detail verified. |
| 4 | S3 sizing/reinvestment | Resolved: focused tests pass; native ETF/cash decisions and executions observed. |
| 5 | Universe integrity | Resolved: real S2 completed; owner/metadata rejection tests pass. |
| 6 | Parameter/version validation | Resolved: K=2 completed, four catalog rows present, S3 uses 1.0.1. |
| 7 | Signals/catalog contracts | Resolved: native signal/catalog views and actual CSV bytes verified. |
| 8 | Comparison boundaries/DTO | Resolved: compatible trio matched; cost mismatch rejected with series differences. |
| 9 | Experiment boundaries | Resolved: trading boundaries, exposure detail, and browser experiment registry/detail verified. |
| 10 | Rolling windows | Resolved: native complete/incomplete states and disclaimer observed. |

No original item remains a demonstrated unresolved M4 defect.

## Commands, results, and gates

```text
cd backend && ./gradlew clean test
BUILD SUCCESSFUL; 176 tests, 0 failures/errors/skips, 26 XML suites

cd frontend && npm test -- --watch=false
3 files, 44 tests passed

PATH=/Users/oliver/.npm/_npx/ce60003f8dc3f49f/node_modules/.bin:/Users/oliver/.npm/_npx/ce60003f8dc3f49f/node_modules/node/bin:$PATH npm run build
Application bundle generation complete. [2.173 seconds]
Initial total: 578.76 kB (126.43 kB estimated transfer)
Budget warning: research-backtests.component.css exceeded 10.00 kB budget by 915 bytes. Exit 0.

git diff --check
exit 0
```

Tested source identity at closeout:
- Commit: `85cfca876803ca29e30130872c180fed52740b70`
- Toolchains: Adoptium Java `21.0.12.1`, Gradle `8.10.2`, Node `24.21.0`, npm `11.18.0`.

| Gate | Status | Basis |
| --- | --- | --- |
| Populated V9 upgrade / V10 metadata | PASS | Disposable production migration chain |
| Foreign keys / repeat startup | PASS | JDBC assertions and second migration run |
| Terminal immutability | PASS after V11 | Signal and item mutation guards exercised |
| S1/S2/S3 execution | PASS | Completed disposable runs and native detail |
| Native modal run creation | PASS | Modal creation verified in browser; manual date control procedure documented |
| Signals, executions, deep links | PASS | Native S2/S3 views and refreshed URLs |
| Compatible and mismatched comparisons | PASS | Native matched/rejected flows |
| Rolling windows | PASS | Native complete/incomplete states |
| Holdout API/exposure | PASS | Real experiment/run and exposure detail |
| Corrected native holdout registry | PASS | Browser verified registry and detail; survived refresh without NG02200 |
| Export routes and bytes | PASS | HTTP/MIME/archive/CSV inspection |
| Browser-managed file saving | NOT VERIFIED | curl downloaded actual bytes |
| Backend tests | PASS | 176/176 |
| Frontend tests | PASS | 44/44 |
| Frontend production build | PASS | Pinned Node 24.21.0/npm 11.18.0 build succeeded |
| Backend format | NOT VERIFIED | No Spotless/format task configured |
| Frontend lint | NOT VERIFIED | No lint script configured |
| Docker | DEFERRED / NOT VERIFIED | Explicitly deferred |

M4 verification is complete to these stated limits. NOT VERIFIED rows remain explicit and are not blanket PASS claims.
