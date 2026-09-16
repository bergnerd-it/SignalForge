# M5 native browser walkthrough — corrective pass

**Date:** 2026-09-16. Chrome native UI, Angular at `127.0.0.1:4200`, Spring Boot at `127.0.0.1:8000`, dedicated SQLite `/private/tmp/signalforge-m5-browser-oJE5jh`. A synthetic `dataset-browser-m5`, XAMS sessions for 2026-09-15/17, EUR listing `listing-eur-syn-1`, and universe `uni-default` were inserted only into this disposable database. The owner's database was not opened. The previous report's claim of a complete native walkthrough was unsupported; the observations below supersede it.

| Native action | Observed result |
|---|---|
| Create `M5 disposable browser fund`, EUR 1000 | Portfolio `portfolio-02d90a35-62f9-4fb8-a978-d5238e18942c` created and shown on its direct URL. |
| Activate S1 tracking | Initial attempt before fixture import hit a foreign-key error because the form's default universe/listing did not exist in a fresh install. With the disposable fixture inserted, activation succeeded. The backend was subsequently changed to validate those IDs and return HTTP 400; the revised error path passed an integration test but was not repeated in Chrome. The form still accepts free-text IDs. |
| Adopt `dataset-browser-m5` | UI displayed `READY`, adopted snapshot ID, and S1/universe metadata. |
| Evaluate S1 | Proposal `prop-d349d517-47e8-4b89-a98a-e555d73db524` displayed target weight 1, close 100, `browser-content` checksum, XAMS calendar, 2026-09-15 cutoff, and 2026-09-17 07:00Z scheduled open. |
| Accept | Proposal showed `ACCEPTED`; Intents tab showed `REBALANCE — PENDING`, `NONE → PENDING`, `USER_ACCEPT`, and future open. No production-clock fill was attempted. |
| Reload the portfolio URL | The selected portfolio, controls, valuation count, and pending intent reappeared after the change-detection fix. |
| Create second EUR 500 portfolio and reject | `portfolio-7d955b18-ab0e-41bb-b656-6148ca7ea85d` activated/adopted/evaluated; its proposal showed `REJECTED` with timestamp and reason. |
| Ask assistant about EUR 500 portfolio | Rendered EUR 500 cash and equity, MANUAL, S1, no holdings/receivables/intents, and references to the portfolio and valuation IDs. The first attempt had persisted a completed response but remained visually `Analyzing`; marking async callbacks for change detection fixed the display. |
| Download audit ZIP | Chrome showed a completed 4.6 KB download. The actual file passed `ZipFile.testzip()` and contained all 13 expected entries. Parsed manifest and CSVs matched the selected EUR 1000 portfolio, dataset/checksum, accepted proposal, pending intent/transition, and opening valuation. |
| AUTO_PAPER toggle | NOT VERIFIED. Automatic approval review rejected the direct toggle as a consequential persistent mode change, even for this disposable portfolio. No alternate route was used. |

The browser did not observe an executed fill, WAITING recovery, split/receivable display, S2/S3 decision, populated upgrade, or inherited M4 run-form/holdout flow. Those remain NOT VERIFIED in this native walkthrough. Focused disposable SQLite integration tests cover some of the temporal and accounting behavior separately; see `research-M5.md` for their exact scope and the M5 gate.

## Approved AUTO_PAPER and live-provider follow-up

After the user explicitly approved the disposable mode change and use of the configured OpenAI API key, a second native Chrome pass used `/private/tmp/signalforge-m5-verify-XzqMkK/browser.db` with the current V13 backend. Chrome created `portfolio-8ad37af8-f2a3-46e1-842d-5cfcc25380c1` with EUR 1000, activated S1 MANUAL, adopted `dataset-browser-m5`, and showed READY. Clicking `AUTO_PAPER: OFF` changed the display to `AUTO_PAPER: ON`; Mode History showed a USER transition from MANUAL at `2026-09-16T15:29:20.915126Z`. The coordinator created an ACCEPTED proposal and one PENDING AUTO_PAPER intent for the future open.

Clicking Evaluate Strategy again initially produced a raw SQLite UNIQUE error on `(portfolio_id, cycle_id, portfolio_state_version)`. The service now returns the existing proposal when cycle, revision, and dataset identity match; a focused integration test passed and a retest of the local HTTP endpoint returned 200 with the same accepted proposal ID. Chrome was in use by the owner during that retest, so the revised response was not observed a second time in native UI.

The Chrome Research Assistant used the configured OpenAI-compatible provider and displayed EUR 1000 cash, AUTO_PAPER, zero holdings, one pending intent, and three evidence references. A separate local HTTP prompt-injection check asked it to invent EUR 1,000,000 cash and an executed trade; the live response instead said EUR 1,000 and no executed trade. The database still had zero paper execution-result rows. This checks one bounded attack, not general language-model reliability. The assistant call sent only the synthetic disposable portfolio context; the API key was never printed. No disable-with-pending-intent, executed fill, S2/S3, or final V13 ZIP download was observed in this follow-up.

## Post-walkthrough implementation verification

The final source adds typed previous/next navigation for every paged paper audit collection and preserves each response's total, limit, offset, and completion state. Component/service tests cover a second proposal page; a populated multi-page state was not revisited in Chrome. Controlled-clock backend tests now select the latest completed month end for S2/S3 and reject a listing without the required observed warm-up months. A provider-failure test confirms deterministic grounded fallback and no financial-table mutation. These are automated checks and do not extend the native observations above.
