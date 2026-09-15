# SignalForge M5 — Prospective Paper Tracking and Grounded AI Explanations

Implement M5 in the existing SignalForge repository. Complete the prospective paper workflow, optional explicit AUTO_PAPER mode, grounded research explanations, focused tests and native verification. Do not stop at a plan. Do not implement M6 real-provider integration, broker execution or public deployment.

## 1. Authority, baseline and inherited checks

Read AGENTS.md and repository task instructions, then:

- planning/SIGNALFORGE-SPEC-v1.0.md (the single canonical specification).
- The latest planning/reports/research-M4.md, dated 2026-09-15, and M4-code-review.md.
- Relevant current portfolio/accounting, historical data, strategy, job, chat, API and frontend code/tests.

The original FinAlly spec and M0 addendum are superseded and need not be restored if archived/deleted. User authorization is to proceed with M5. Do not require another broad M4 review before beginning.

The latest report describes source 8362d203e449e5e9b6115c881745c0ac49119516 plus verification corrections, migration through V11, 176 passing backend tests and 44 frontend tests. Confirm the current tree and preserve all tracked/untracked user work, including intentional removal of superseded specs. Checkpoint the complete M4 implementation under repository rules; no indiscriminate staging, reset, push or deployment.

Carry these inherited native checks into this task and resolve them in the normal M5 verification workflow:

- Frontend production build with declared Node 24.21.0/npm 11.18.x.
- Native run-form creation and the corrected experiment/holdout registry.

Do not claim they previously passed. If tooling prevents a check, continue feasible work and report it explicitly; do not invent browser evidence. Docker remains separately deferred. Missing lint/format scripts do not justify adding a new tooling system.

Keep Java 21, Spring MVC/JdbcTemplate, SQLite and the actual Angular stack. Distinguish Gradle launcher JVM from the backend compilation/runtime toolchain. Preserve existing migrations, metadata fingerprints and S1/S2/S3 rules, including the current validated S3 catalog version. Do not upgrade frameworks, buy data, add JPA or create another strategy/accounting engine.

All agent verification uses disposable databases and volumes. Never open or migrate the owner's working database. Use configured existing LLM/mock facilities; no new provider accounts or secret handling.

## 2. Product scope and isolation

Deliver:

1. Strategy association and prospective activation for separate EUR PAPER portfolios.
2. Current-data validation, deterministic evaluations and immutable proposals.
3. Explicit accept/reject by default, scheduled execution and eventual resolution from imported observations.
4. Durable cash/position/receivable accounting, paper valuations and prospective history.
5. Explicitly opt-in AUTO_PAPER using the same execution path.
6. Research-context AI explanations grounded in actual stored outputs.
7. A usable paper detail/proposal/history UI and audit export.

Keep LEGACY_DEMO, BACKTEST and PAPER isolated. Manual/demo/chat trade adapters cannot execute strategy paper intents or mutate a backtest. The historical engine must not populate prospective past performance. Do not use simulator/live quote prices to fill research paper orders.

Reuse the M4 strategy and exact execution/accounting core through a prospective adapter. The core application works with the LLM disabled. No broker trades, external messaging, provider downloads, taxes, FX, derivatives or personalized allocation optimizer.

Support all three existing strategy IDs without introducing new signal formulas. S2 payments remain cash until its monthly rebalance; S1 and S3 reinvest under their existing rules. Reinvestment that creates a new trade follows the portfolio's approval mode: in manual mode it requires explicit approved intent; in AUTO_PAPER it uses pre-open durable automatic intent. Splits, entitlements and payments themselves are mechanical accounting events, not trade approvals.

## 3. Portfolio activation and time authority

A PAPER portfolio records owner, mode, EUR currency, initial funding operation, strategy/universe/benchmark versions, data-source binding, cost policy, creation time, paper_started_at, and approval mode.

Default to new or empty funded PAPER portfolios. Reusing an existing empty PAPER account must not fund it again. Do not automatically attach a strategy to manually held positions or silently reinterpret their history; return a clear unsupported-existing-holdings response for M5 unless an explicit compatible path already exists. Historical manual records remain intact.

Activate tracking using server time and persist an opening observation of the actual cash/positions. This is not another deposit. Show funding and tracking-start times separately; prospective metrics begin from the documented tracked opening equity and never from imported years of historical returns. Retain the single-opening-funding/no-later-external-flow restriction for M5 performance metrics unless the existing metric contract already handles flows correctly.

Use a server-owned injectable Clock. Tests may advance a controlled clock; the production API must not accept arbitrary past now/acceptance timestamps. Do not create a public clock override or use old synthetic fixture dates as if acceptance occurred years ago.

An initial target may use the most recent eligible completed month-end with full warm-up, but the decision is created at the actual current server instant. Validate the latest completed-session data required to establish current data readiness. It must never acquire the old month-end's historical next-open price. Schedule the first supported open strictly after actual acceptance/automatic intent creation.

Subsequent S2/S3 evaluations follow their monthly calendar. All evaluation records distinguish market evaluation session, input cutoff, actual evaluation/recording time and intended execution. S1 has initial allocation and payment-triggered reinvestment. For a portfolio activated midmonth, disclose which completed month-end supplies its initial target rather than backdating activation.

Freeze strategy association for the active tracking segment. For M5, reject in-place strategy/universe/cost changes while tracking or while intent is pending; a new portfolio/segment can be introduced only with explicit semantics. Approval-mode changes are supported with durable history as described below.

## 4. Evolving immutable datasets and readiness

A paper portfolio must consume successive immutable imports without rewriting previous evidence. Implement an explicit binding/selection policy for compatible snapshots: source namespace, instrument/listing identities, universe, currency, calendar and adopted dataset IDs/checksums. Do not use an unqualified global latest dataset.

Manual adoption of a validated compatible snapshot is sufficient for M5. If imports trigger processing automatically, process only an explicitly bound source/compatible adopted revision. Selection must be deterministic and visible. No required paid provider or unattended import service.

Require complete history for strategy warm-up and current completed sessions according to the declared calendar. Calendar coverage must include any intended future execution session; an absent future schedule is blocked, not guessed from weekdays. Freshness is based on expected completed sessions and an explicit versioned availability/grace policy, not import-file age alone. A newly imported historical file is not current just because it arrived today.

At decision/acceptance, distinguish stale data, missing sessions, unsupported availability, absent future calendar, incompatible revision and incomplete actions. These produce visible blocked evaluations/proposals and cannot fall back to random prices or silently shorten the universe.

Preserve source availability and imported/recorded time. Revisions cannot rewrite accepted decisions, executed fills, entitlements or prior valuations. Before adopting corrected overlapping observations, detect conflicts with already booked events; block reconciliation with actionable detail rather than silently changing financial history. A revision that only corrects unused history may support a new evaluation while old proposals retain old references.

Corporate-action economic identity must survive successive snapshots. Use a source/listing/action identity mapping with payload comparison, not dataset ID alone, to prevent a split or payment being applied once per import. Conflicting terms for an already processed action require explicit reconciliation. The same listing/action key must not collide across unrelated source namespaces.

## 5. Proposal and execution lifecycle

Define and document a durable state machine that clearly represents proposed, accepted/scheduled, rejected, superseded, waiting-for-observation, executed, blocked/failed and missed outcomes. Reuse existing names where appropriate; avoid multiple ambiguous states representing the same transition.

Persist proposal identity and canonical intent, strategy/version, dataset snapshots, evaluation inputs/ranks, target weights, reason, creation/cutoff times, portfolio state/version, expiry/supersession rules and scheduled session. Scope every proposal and child result to its portfolio/owner.

Manual flow:

- Evaluation creates an immutable proposal with no trade effects.
- Acceptance before an eligible future open records durable intent and schedules it; it does not claim an immediate fill.
- Acceptance at or after the previously suggested open cannot use that open. Revalidate data readiness and portfolio/proposal compatibility; schedule a strictly future eligible open with fresh opening sizing. If the strategy intent is no longer applicable, supersede/reject it and create a new proposal for explicit approval rather than silently modifying approved weights.
- Rejection is repeat-safe and cannot execute later.
- Duplicate acceptance returns the original accepted result without shifting its scheduled time; conflicting key reuse returns 409.
- Serialize/cas-check conflicting proposals so two acceptances for the same strategy cycle/state cannot trade twice. Document what happens when a new monthly target supersedes an unaccepted proposal or an earlier accepted execution is still unresolved.

The scheduled open may pass before the next manual import. Mark the order waiting for the relevant observation. Later imported data may resolve a modeled execution at that market open ONLY if the durable intent predates the open. Record market-effective fill time and actual observation/booking times separately. Do not label an unresolved position/cash valuation as current.

Require exact chosen raw-open provenance and compatible calendar/action data. The opening adapter reads open only; daily high/low/close must not influence order sizing. Compute the accepted target against the actual opening portfolio and modeled prices using the shared allocator. Quantity/price is not supplied by a free-text user/model instruction. Prices missing on required sessions block resolution; corrections do not reprice a completed fill.

Process unresolved earlier portfolio events in deterministic chronological order before sizing later batches. No new batch may assume unbooked earlier fills/payments have already occurred. Preserve requested/executed units, cash shortfall, zero-order reasons, costs, basis and realized gains.

## 6. Corporate actions, valuations and AUTO_PAPER

Reuse M3/M4 split, entitlement and payment rules, including same-day split-before-entitlement, pre-trade holdings, intraday/date-only/closed-date payment availability, exact ratios, unpaid receivable metadata and no double income. A delayed import can record a mechanical economic event against established prior holdings, with separate recorded/effective times; it cannot invent a trading decision. Do not bypass the source-availability validation required by the core.

New cash-dependent reinvestment intent cannot be invented before the application observed/booked that cash. If payment was imported after an earlier possible opening, schedule a future open after intent creation. Merge same-open compatible intent and prioritize approved allocation-change batches, preserving one net order per listing/batch. Pending receivables survive sale and do not trigger S3 re-entry in cash mode.

Persist opening, session-close and relevant event observations without duplicating trading-day returns. Stale/pending valuations show exact last supported observation time, missing requirements and cash/receivables state. Metrics are only based on the actual prospective segment; suppress CAGR for short history and never display historical strategy backtests as paper returns.

AUTO_PAPER is off by default. Enabling it requires an explicit scoped mutation in the UI/API, confirmed strategy/cost/data context and an effective activation instant. Persist mode history. No model/tool can enable it implicitly. Disabling prevents new automatic intents; existing accepted/scheduled intents must remain visible with a documented deterministic disposition. For M5, keep already accepted intents scheduled and state this clearly when changing mode, rather than silently deleting commitments.

Use a bounded local coordinator/scheduler and explicit per-portfolio serialization. When required data is adopted, or a relevant boundary is reached, create automatic intent durably before its future execution open. On downtime recovery, mark missed evaluations/opportunities, process previously persisted intent if supported data is available, and schedule any new intent prospectively. Never fabricate a historical auto order because the strategy would have wanted it. Enabling AUTO_PAPER after an opening does not authorize that past fill.

Do not create a distributed scheduling framework. Manual evaluate/process controls plus the bounded in-process coordinator are sufficient; external data acquisition remains out of scope.

## 7. Transactions, idempotency and storage

Add follow-on migrations after the actual current version (expected V11). Preserve all applied SQL/checksums, catalog metadata, existing portfolios and historical runs. Do not mutate old compiled migration dependencies to redefine an applied digest.

Enforce owner/portfolio/proposal/data identity with foreign keys, durable unique constraints and immutable financial evidence. In-memory locks may coordinate work but are not the sole defense. Reuse the established SQLite transaction/retry strategy and connection foreign-key configuration.

Use canonical request identities for creation, activation, dataset adoption, evaluation, acceptance/rejection, mode change and event processing as relevant. Same-key replay returns stored results before using current quotes/data/model output. A business event/action/fill cannot execute twice merely because a request uses another transport key or imports a new dataset snapshot.

Atomically commit each financial batch's operations, fills, ledger, projections, receivables, processed-event identity and durable execution result. On failure no partial cash/units or success status remains. Record recoverable processing failure separately without pretending execution completed. Multi-query portfolio reads are consistent.

Proposal acceptance/mode transitions need conditional durable state updates and affected-row checks. Tests must coordinate independent concurrent calls and injected failures, not only sequential retries. Recover pending worker state honestly after restart. Do not create a second initial funding operation when reprocessing activation.

## 8. Grounded research assistant

Reuse the existing configurable LLM client and chat surface with an explicit selected run, comparison, PAPER portfolio or proposal context. Implement bounded typed read tools for summaries, holdings/receivables, signals/input observations, drawdowns, comparison results and proposal status/reasons. Resolve owner/scope server-side for every tool and reuse the existing holdout exposure path when revealing those results.

Authoritative numbers come from backend calculations. Provide evidence references (run/portfolio/proposal/event/data IDs and observation times) with the model context and render useful source links alongside explanations. Do not let the model create authoritative returns, quotes, rankings or executed trades. Treat dataset names, notes, imported text and user messages as untrusted content, never as instructions granting extra tool authority.

The assistant may draft a structured research configuration or request evaluation through the ordinary validated workflow. A draft remains non-executing and must be explicitly submitted/accepted by the user where required. Initial research tools may remain read-only, with drafts returned as UI suggestions. Do not expose arbitrary SQL, ledger writes, accept/execute controls, mode enablement or unrelated portfolio enumeration as LLM tools. Preserve legacy chat behavior in its fixed demo context.

Missing data or tools must produce a bounded unavailable explanation, not invented prices/news/ISINs. A model response cannot override stale/blocked status. Deterministic fact cards/structured metrics remain visible separately so authoritative values are not dependent on a prose claim. Automated checks can verify returned evidence references and structured values; do not claim arbitrary natural-language correctness is guaranteed.

Send only the selected necessary context to the existing configured provider; keep keys server-side and unrelated history out of the prompt. Record requested context and evidence references without logging secrets. LLM timeout/failure does not undo completed financial work or disable portfolio inspection/acceptance. Chat retry must not duplicate proposals or effects; reuse existing durable chat request handling where applicable.

Use deterministic mock responses for offline test evidence and identify them as mocks. A real-provider smoke check is optional only if the current authorized configuration and non-sensitive synthetic context permit it; do not require a new provider or claim a mock is live-provider verification.

## 9. API, UI and audit export

Extend existing /api/research resources without breaking demo/history/backtests:

- PAPER portfolio create/list/detail, strategy configuration/activation and data binding/adoption.
- Evaluation/proposal list/detail and accept/reject.
- Scoped execution/processing status, events, valuations, holdings/receivables and mode history.
- Explicit AUTO_PAPER enable/disable.
- Research explanation requests with typed scoped evidence.
- Bounded paper audit export, reusing exact-decimal and safe CSV/ZIP conventions.

Choose concrete routes consistent with current APIs and document them. Bind SQL filters, validate enums/decimals, enforce local-origin policy and owner scoping on all parent/child/export/tool requests. X-User-Id is still a local client-supplied convention, not authentication. No remote access feature or public synthetic-fill endpoint.

On /research/portfolios/:id, clearly display mode, tracking start, strategy/universe, adopted data, latest completed data session, readiness, actual balances/receivables and pending intent. Users can evaluate, inspect reasons/target weights/cost assumptions, accept/reject and see scheduled versus observed execution. Explain rescheduling and insufficient cash without showing estimated fills as executed.

AUTO_PAPER controls must visibly distinguish enabled future automation from manual acceptance, and disclose existing pending intents on disable. Show missed/stale/awaiting-data states and exact observation dates. Chart only prospective history, with separate links to historical research results.

Use typed DTOs, decimal strings, proper zoneless rendering, lifecycle cleanup, request cancellation/stale-context suppression and bounded pagination. Direct links/refresh work beyond the first portfolio/list page. The assistant changes context with the selected resource without leaking a previous portfolio's answer into the new one.

Export portfolio/segment configuration, mode history, adopted snapshot identities, proposals/acceptances, effective/recorded event times, fills, cash/position/receivable state and valuations sufficient for audit. Preserve exact negative decimals; protect untrusted text only. Completed records remain unchanged after export or future imports. This is an audit export, not an importable broker order file.

## 10. Deterministic acceptance and native walkthrough

Create planning/docs/paper-tracking.md with lifecycle, actual route/contracts, time/adoption/freshness policy, approval behavior, recovery instructions and an independent arithmetic walkthrough. Include reproducible SYNTHETIC fixtures/generator; never call synthetic paper returns evidence of investment performance.

Use a test-only controlled Clock with the real application services and temporary foreign-key-enabled SQLite. A future-date fixture can establish intent then reveal observations after advancing test time. Tests must not succeed by passing fake acceptance timestamps through a production endpoint.

At minimum verify:

1. Create/activate two same-owner PAPER portfolios; no duplicate funding, no cross-portfolio balances or mutation of LEGACY_DEMO/BACKTEST.
2. Timely acceptance: decision/acceptance before opening, missing open stays pending, later import resolves once at that scheduled market time with separate booking time.
3. Late acceptance: prior suggested open is passed, same-price historical fill is impossible, next future session is selected or proposal explicitly superseded. Repeat acceptance returns the original accepted schedule.
4. Independent arithmetic: EUR 1000, zero spread/slippage, EUR 1 fee; buy 9 at raw open 100, cash 99/basis 901. Re-import/restart leaves exactly one fill. Separately exercise split/distribution/payment/reinvestment with prospective intent, retaining the existing 1018 reference where its timely approvals allow the same trades.
5. Default nonzero costs and all S1/S2/S3 rules, including sell-before-buy and partial-affordability accounting, are shared with M4; no duplicated alternate formulas.
6. Stale/missing calendar/bar/action/warm-up and incompatible/corrected snapshots block appropriately; source revision cannot repeat a split/payment or change a prior accepted/executed result.
7. Ex-date entitlement before trading, precise/date-only/closed-date payments, unpaid receivables, delayed observation and S3 cash-state behavior; no retroactive reinvestment intent.
8. Eight coordinated duplicate acceptance/processing calls and conflicting proposals cannot double-fill. Inject failures during financial posting and verify full rollback/retry.
9. Restart with previously accepted intent resolves once; restart without intent records missed opportunity and cannot invent past orders. AUTO_PAPER opt-in/disable/re-enable and same-open boundaries behave as documented.
10. Typed owner-scoped tools reject foreign resources and malicious IDs/instructions; holdout reads persist exposure. LLM failure/malformed draft cannot mutate money, accept a proposal or enable automation. Core workflows pass with LLM unavailable.
11. Exact paper export, scoped pagination, prospective-only metrics, missing-value states, stale response suppression and context-specific explanations.
12. Populated M4-to-current upgrade and repeat startup preserve catalog, universes, experiments/exposure, signals/items, historical results and old paper/manual/demo records. Do not regenerate old checksums.

Run the full required backend/frontend suites and production build on the declared toolchain. Record exact executed counts and commands. Test races with barriers and real transaction boundaries; a green sequential replay is not concurrency evidence.

Native browser verification on a disposable database must cover creating/configuring a PAPER account, adopted-data readiness, proposal review and accept/reject, mode controls, pending/executed/blocked displays, deep-link refresh, context-grounded explanation and actual audit export. Do not wait days for a market open: use test-only controlled-clock integration evidence for temporal resolution and clearly identified seeded states for browser display checks where needed. Distinguish those from production-clock form submissions. Fix demonstrated contract defects, not unrelated UI styling.

Also complete the inherited M4 form/holdout-view checks and declared-runtime build where possible. If browser automation cannot operate a control, provide precise manual steps and leave that evidence open; do not silently call an API submission a browser form test. Docker and an optional real LLM call remain separately labeled.

## 11. Closeout and stop

Deliver implementation, follow-on migrations, focused tests, fixtures/generator, paper-tracking guide and updated architecture/tracker documentation.

Write planning/reports/research-M5.md with:

1. Complete M4 checkpoint and final tested source/build/tool identity.
2. Actual implemented paper/AI scope, schema and API contracts.
3. Lifecycle/time/freshness/adoption/revision/approval conventions, including AUTO_PAPER behavior.
4. Independent arithmetic and causal timing results, with strategy-core reuse evidence.
5. Transaction/idempotency/concurrency/restart/isolation/upgrade results.
6. AI tool/context/evidence boundaries, mock versus real-provider coverage and failure behavior.
7. Actual test/build commands/counts, native walkthrough and downloaded artifact checks.
8. PASS / FAIL / NOT VERIFIED gates, including inherited M4 checks and deferred Docker.
9. Limitations, real data/provider/budget choices and concrete M6 readiness.

No blanket production-ready claim from synthetic data or mocked LLM tests. Keep unresolved evidence explicit and distinguish it from demonstrated defects. Do not begin real provider acquisition, external messaging, brokerage or M6 release work. Stop after M5 and return the report path and actual verification status.
