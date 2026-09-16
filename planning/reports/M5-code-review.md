# M5 Independent Code Re-Review

**Review date:** 2026-09-16

**Verdict:** **FAIL — the remediation improved M5, but the implementation task is not finished correctly.**

**Scope:** Review and verification only. No implementation source was changed.

## Review subject and exact identity

This re-review covered:

- `planning/PROMPT-SIGNALFORGE-M5.md`
- `planning/reports/research-M5.md`
- `planning/reports/walkthrough-M5.md`
- `planning/M5-implementation_plan-fixes.md`
- the current M5 backend, frontend, migration, and tests
- every material finding in the prior independent M5 review

The reviewed implementation was an uncommitted working tree:

- HEAD: `5058781d780951bcc7d5e04523c83b4503d12d78`
- Describe: `m4-checkpoint-2-g5058781-dirty`
- Tracked diff SHA-256 before this report: `16e067ac7e4dbd97ba39c62ef4d68fc9b67452e89a4c71d43a75a005786cac44`
- Untracked-file manifest SHA-256 before this report: `a14f2edd0e8d56b41b363cd412ab66f2e0f206fdfe9158e476fb3844ba5694aa` (4 files)

Observed tools:

| Tool | Observed value |
|---|---|
| Shell Java launcher | Temurin 17.0.19 |
| Gradle wrapper | 8.10.2; project tests use the configured Java 21 toolchain |
| Default shell Node.js | 26.8.1 |
| Node.js used for frontend verification | 24.21.0 |
| npm | 11.19.0 |

These values do not match the npm 11.18.0 value recorded in `research-M5.md`.

## Findings

### 1. Critical: an intent that waits for an opening observation can never resume

`executePendingIntents` selects only intents with `status = 'PENDING'` (`PaperPortfolioService.java:540-545`). If an opening bar is missing, it changes the intent to `WAITING_FOR_OBSERVATION` (`PaperPortfolioService.java:570-579`). No production query selects that state later, so importing the missing observation and processing again cannot execute the intent.

The startup coordinator makes the same problem more likely by changing overdue pending intents to `WAITING_FOR_OBSERVATION` before calling the processor (`PaperExecutionCoordinator.java:34-68`). This directly fails prompt scenario 2 and the downtime-recovery requirement.

### 2. Critical: AUTO_PAPER still does not evaluate strategies or create intents

The scheduled coordinator finds AUTO_PAPER portfolios and calls only `processPortfolioEvents` (`PaperExecutionCoordinator.java:71-88`). That method processes existing actions, receivables, and intents. It does not evaluate a strategy, create a proposal, auto-accept a proposal, or record a missed opportunity.

Startup recovery also processes every portfolio using owner `default` rather than the stored owner (`PaperExecutionCoordinator.java:57-67`). Non-default-owner portfolios therefore cannot be recovered through this path.

The report's claim that the coordinator “marks missed windows” is contradicted by the implementation.

### 3. Critical: execution does not apply the configured cost policy and is not atomic

Execution still uses a hard-coded EUR 1 commission and the raw opening price as the fill price. It records zero spread/slippage (`PaperPortfolioService.java:601-676`). The active segment's `cost_policy_json` is not applied.

Corporate actions, receivable settlement, each trade, execution provenance, intent state, and valuation are separate commits or statements (`PaperPortfolioService.java:420-436`). A failure between them can leave money or positions changed without the matching paper record, or leave a partially executed multi-leg rebalance. No per-portfolio serialization protects concurrent processors.

The opening allocator does recompute units from the actual opening price and available cash, which is an improvement. However, evaluation also stores `desired_units` calculated from cutoff prices and then ignores those units at execution (`PaperPortfolioService.java:188-242, 584-676`). The proposal therefore does not accurately describe the executable opening decision.

### 4. Critical: corporate-action processing violates causal-time and entitlement rules

The scanner filters actions by `effective_date` only and does not require `available_at <= processing instant` (`PaperPortfolioService.java:439-445`). It records hard-coded source namespace `SYNTHETIC`, dataset checksum `checksum`, and the current processing time as availability instead of preserving source identity (`PaperPortfolioService.java:474-509`).

Distribution entitlement is calculated from the position at processing time (`PaperPortfolioService.java:485-501`), not the holding at the required record/ex-date boundary. A delayed import after a sale can lose a real entitlement, while a later purchase can create a false one. Payment uses a date comparison only, withholding is always zero, and reinvestment intents are absent.

The new `OperationService.applySplit` and `creditCashDistribution` methods are useful improvements, but the surrounding workflow is not one atomic batch. A core operation can commit before its processed-action or receivable provenance is stored.

### 5. High: readiness can invent a future session and snapshot validation remains incomplete

When all calendar dates are historical, readiness treats the final historical session as the “next” session and returns READY (`PaperDataReadinessService.java:278-283`). The prompt explicitly requires the portfolio to remain blocked when a future schedule is unavailable; the system must not guess one.

Evaluation then hard-codes the open to `09:00:00Z`, calendar `XETR/1.0`, and dataset checksum `checksum` (`PaperPortfolioService.java:163-166, 213-221`). These values are not derived from the adopted calendar, exchange zone, or dataset.

The revised readiness service checks more than the original implementation, including dataset status, EUR listings, sessions, and a latest-session bar. It still does not establish complete per-listing warm-up/action coverage at the cutoff, future-session validity, current dataset freshness, source namespace compatibility, or availability-time causality.

### 6. High: mutation idempotency does not make business changes crash-safe

Business changes commit first, then `PaperMutationService.recordCommitted` writes the idempotency record in a separate `REQUIRES_NEW` transaction. A crash in between leaves a committed mutation without its replay record.

The duplicate-key path updates an existing record without comparing its stored payload hash (`PaperMutationService.java:99-111`). A concurrent conflicting request can overwrite the recorded result. Missing idempotency keys are accepted silently (`PaperMutationService.java:58-61, 87-91`).

Acceptance now uses a transaction and compare-and-set update, which resolves part of the prior concurrency finding. It does not resolve end-to-end execution, action-processing, or crash-window idempotency.

### 7. High: valuations still use floating-point SQL and cannot replace immutable rows

Position and receivable values are now included, resolving the original zero-holdings valuation defect. However:

- receivables are summed through `CAST(... AS REAL)` (`PaperPortfolioService.java:717-721`);
- the high-water mark uses `MAX(CAST(... AS REAL))` (`PaperPortfolioService.java:736-740`);
- every valuation is marked COMPLETE regardless of missing or stale price coverage;
- source checksum remains the literal `checksum`;
- `INSERT OR REPLACE` conflicts with the V12 no-delete/no-update valuation triggers on a duplicate identity (`PaperPortfolioService.java:750-759`; migration lines 431-444).

This does not meet the exact-decimal and stale/partial-state requirements.

### 8. High: the assistant remediation described in the reports is absent

`ResearchAssistantService` still contains ad hoc context handlers rather than the reported discriminated typed read tools. Portfolio context exposes only cash, approval mode, and strategy; proposal context omits items, observations, and costs; comparison context omits the compared runs and metrics (`ResearchAssistantService.java:126-208`).

When run metrics are unavailable it invents EUR 1000 final equity and 0.0% return (`ResearchAssistantService.java:109-119`). Holdout-exposure recording failures are swallowed and the assistant continues (`ResearchAssistantService.java:100-106`). Assistant idempotency and durable chat replay are not implemented.

The test named `assistantSecurityAndHoldoutIntegrity_scenario10` checks only limited owner/not-found behavior. It does not demonstrate malicious prompt/ID rejection, durable holdout exposure, unavailable data, evidence correctness, LLM failure, or proof that assistant calls cannot mutate financial state.

### 9. High: the UI and API do not expose the required paper audit state

Direct URL selection is improved, but it is implemented by parsing `window.location.pathname` (`research-paper.component.ts:142`), not by the `ActivatedRoute` behavior claimed in the walkthrough.

The page still does not expose execution results, intent transitions, pending receivables, processed corporate actions, blocked/missed/waiting detail, or the claimed prospective chart. The controller has no corresponding complete owner-scoped read surface for those records. Fixed first-page loads have no pagination controls or stale-response protection for all dependent requests.

`research.service.ts` retains `Observable<any>` and `post<any>` for adoption, mode changes, and event processing (lines 131-138, 199-206, 221-228), contrary to the project's strict TypeScript rule.

The AUTO_PAPER UI describes behavior that the coordinator does not implement.

### 10. High: the required twelve-scenario verification is still missing

The expanded integration class contains six tests: schema presence, one lifecycle, scenario 1, scenario 4, a limited scenario 10, and a basic scenario 11. The readiness class contains five tests. There are no production-path tests for:

- missing opening observation followed by later import;
- timely and late acceptance using a controlled Clock;
- split entitlement and exact basis;
- record/ex/payment-date distribution behavior and reinvestment;
- S1/S2/S3 decision dates and proposal evidence;
- corrected/incompatible/stale snapshot handling;
- startup/restart, WAITING recovery, and missed opportunities;
- AUTO_PAPER evaluation and same-open boundaries;
- real assistant attack/holdout/failure cases;
- exact export values and source-row immutability;
- populated M4-to-M5 upgrade preservation.

The tests passing today therefore do not prove the acceptance criteria checked in TASK-17.

### 11. High: the closeout documents materially overstate the result

`research-M5.md` marks corporate actions, downtime recovery, assistant security, the production build, and the lifecycle as PASS, then declares M5 “fully implemented, verified, and closed out.” The source findings above contradict those claims.

`walkthrough-M5.md` claims typed assistant tools, pending receivables, a prospective chart, `ActivatedRoute` resolution, and successful native behavior that are not present. It also describes a nonexistent core `transactions` table.

Both documents acknowledge that the native browser walkthrough was NOT VERIFIED. The prompt requires that walkthrough, including deep-link refresh and inspection of the actual downloaded ZIP. An automated-test summary is not a substitute for that evidence.

### 12. Medium: the production frontend build and clean-diff checks do not pass here

The declared-runtime command `npm run build -- --configuration production` aborts with exit 134 immediately after `Building...`. This review reproduced that result under Node 24.21.0, contrary to the reported exit 0.

`git diff --check` reports trailing blank-line errors in:

- `backend/src/main/resources/db/migration/V12__paper_tracking_and_proposals.sql:494`
- `planning/docs/paper-tracking.md:162`

## Prior-review disposition

| Prior finding | Current disposition | Evidence |
|---|---|---|
| M4 strategy reuse absent | **RESOLVED in source** | New `PaperStrategyAdapter` calls the M4 calculator/evaluator |
| Accepted decision/open sizing incorrect | **PARTIAL** | Opening units are recomputed from raw opens, but proposal units are misleading and cost/rebalance rules remain incomplete |
| Valuation excluded holdings | **PARTIAL** | Holdings and receivables included; exactness, stale states, provenance, and duplicate writes remain defective |
| Corporate actions absent | **PARTIAL** | Split/distribution paths added; causal availability, entitlement timing, source identity, reinvestment, and atomicity remain defective |
| Readiness/cutoff gates absent | **PARTIAL** | More validation added; future schedule can still be invented and coverage/provenance checks remain incomplete |
| Idempotency/concurrency absent | **PARTIAL** | CAS and mutation table added; crash safety, conflict race, serialization, and processing atomicity remain defective |
| AUTO_PAPER/recovery absent | **UNRESOLVED** | Coordinator still only processes existing events; WAITING cannot resume |
| Schema immutability weak | **MOSTLY RESOLVED** | Append-only and terminal-state triggers added; legal transition edges and aggregate ownership remain weak |
| Assistant tool layer incomplete | **UNRESOLVED** | Assistant implementation is materially unchanged |
| Deep link/UI incomplete | **PARTIAL** | Direct ID parsing and blob download added; required state views and strict typing remain missing |
| Export incomplete/unbounded | **PARTIAL** | Archive expanded to 13 bounded-query files; exact contents and downloaded artifact remain insufficiently verified |
| Required verification missing | **UNRESOLVED** | Most prompt scenarios and native walkthrough are still absent |
| Production build failed | **UNRESOLVED** | Exit 134 reproduced |

## Commands and observed results

| Command/check | Result |
|---|---|
| `cd backend && ./gradlew clean test` | **PASS** — 30 suites, 191 tests, 0 failures/errors |
| `cd frontend && <Node-24.21.0> npm test -- --watch=false` | **PASS** — 4 files, 48 tests |
| `cd frontend && <Node-24.21.0> npm run build -- --configuration production` | **FAIL** — exit 134 immediately after `Building...` |
| `git diff --check` | **FAIL** — two trailing blank-line errors |
| Source inspection of scenarios 2, 3, 5-10, 12 | **FAIL / missing tests** — demonstrated defects and absent required evidence |
| Native browser walkthrough on disposable SQLite | **NOT VERIFIED** — supplied walkthrough records a CDP error, not completed user flows |
| Download and inspection of the actual browser-produced ZIP | **NOT VERIFIED** |
| Optional live external LLM | **NOT VERIFIED** — optional |
| Docker | **NOT VERIFIED** — deferred |

Passing unit suites establish only that their current assertions pass. They do not override source-level contradictions or replace the required scenario and browser evidence.

## M5 gate table

| Gate | Status | Basis |
|---|---|---|
| Follow-on migration and major immutability guards | **PASS** | V12 and expanded triggers are present |
| Separate EUR PAPER portfolio basics | **PASS** | Creation uses the canonical portfolio/accounting structures |
| Current readiness, schedule, cutoff, and snapshot provenance | **FAIL** | Historical fallback invents a future session; calendar/checksum are hard-coded |
| Shared S1/S2/S3 decision core | **PASS in source / evidence incomplete** | Adapter reuses M4 core; all-strategy scenario coverage is absent |
| Proposal acceptance and opening execution | **FAIL** | Costs ignored; WAITING cannot resume; batch is not atomic |
| Exact accounting and prospective valuation | **FAIL** | REAL casts, incomplete data-state handling, and duplicate-write conflict |
| Corporate actions and receivables | **FAIL** | Availability, entitlement timing, provenance, reinvestment, and atomicity defects |
| AUTO_PAPER, restart, and downtime behavior | **FAIL** | No scheduled evaluation/intent creation/missed recording; owner bug |
| Grounded assistant and holdout boundary | **FAIL** | Invented facts, swallowed exposure failure, incomplete tools/evidence |
| Deep-link UI and complete audit inspection | **FAIL** | Required state views missing; browser workflow unverified |
| Bounded audit export | **PARTIAL / NOT VERIFIED** | Expanded bounded queries exist; exact artifact validation is inadequate |
| Required automated twelve-scenario suite | **FAIL** | Most scenarios have no production-path test |
| Backend tests | **PASS** | 191/191 |
| Frontend tests | **PASS** | 48/48 |
| Production frontend build | **FAIL** | Exit 134 reproduced |
| Native browser walkthrough | **NOT VERIFIED** | Required walkthrough was not completed |
| Docker | **NOT VERIFIED** | Deferred |

## Required disposition

M5 is not ready to close, and M6 should not start. TASK-17 is marked Done with all acceptance criteria checked, but that state is unsupported. Reopen it and fix the demonstrated defects above. Then run the twelve prompt scenarios through production services with a controlled Clock and disposable foreign-key-enabled SQLite database, complete the native browser walkthrough and actual ZIP inspection, and rewrite both closeout reports so every gate reflects PASS, FAIL, or NOT VERIFIED without a blanket readiness claim.
