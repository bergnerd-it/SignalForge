# Research M0 — Repository audit and extension baseline

Date: 2026-09-11. Scope: M0 only, against `planning/FINALLY-RESEARCH-SPEC-v0.1.md`.

Reviewed commit: **`8d6597fca4d15e1c023a2a301494cb231de72797`**. The application working tree was clean at entry. The research specification was already untracked; its SHA-256 was `b3ea37665b443b5419457b58548bb5665e00e95050d62d57a9f6d670e23de188`. This audit does not treat that draft as an approved product baseline. Tracking: Backlog TASK-7.

## Recommendation

**Extend the existing modular monolith, but establish a passing demo baseline and replace its accounting boundary before adding research behavior.** Spring MVC, JDBC, Angular, the presentation layout, SSE transport and configurable LLM transport are useful foundations. The existing portfolio service is not a research accounting engine: it combines mutable quotes, wall-clock time, floating-point accounting, persistence and immediate execution.

The baseline is currently red: **52 backend tests ran, 36 passed and 16 failed** because duplicate YAML keys prevent Spring context loading. **All 19 frontend tests passed**, including after a clean lockfile install, and the production frontend build passed outside the execution sandbox. Backend `bootJar` also passed; packaging success does not establish application startup. Docker/E2E execution was unavailable because the Docker daemon was not running.

M1 should deliver versioned migration, exact accounting, explicit portfolio/listing identity, an immutable ledger and durable idempotency, with legacy endpoint adapters confined to `LEGACY_DEMO`. Do not add history import, strategy evaluation, backtesting or research LLM actions in M1.

No application code, configuration, dependency declarations, lockfiles or existing databases were changed. No existing database was opened or migrated. Verification used an isolated checkout and disposable in-memory SQLite connections. Only this report and Backlog audit metadata are intended repository additions.

## Evidence conventions

- **Verified / runtime**: reproduced using the checked-in tests or a disposable probe against compiled repository classes.
- **Verified / static**: follows directly from inspected code; an end-to-end reproduction was not run.
- **Untested risk**: a plausible failure under an identified condition; not reported as observed corruption.
- **Extension gap**: absent research functionality required by the draft, not necessarily a defect in the original demo.

Severity is relative to the research foundation: **Blocker** prevents baseline startup/verification; **High** threatens accounting correctness, isolation or repeatability; **Medium** affects usability, operation or confidence; **Low** is limited maintenance friction. A static finding is not evidence that the owner's persisted data has already been damaged.

Paths below are repository-relative; line numbers refer to the reviewed files.

## Actual architecture and documentation discrepancies

The backend is a Spring Boot **3.3.4** / Java **21** application with Web MVC, JDBC, validation, Actuator and Lombok (`backend/build.gradle:1–42`). Persistence uses `JdbcTemplate`, not JPA. `SignalForgeApplication` enables scheduling (`backend/src/main/java/com/bergnerd/signalforge/app/SignalForgeApplication.java:7–12`). Existing feature packages are `market`, `portfolio`, `watchlist`, `chat`, `db`, `config` and `system`; there are no historical-data, strategy, backtest or ledger modules.

Angular uses standalone components, actual zoneless change detection and singleton services holding `BehaviorSubject` state (`frontend/src/app/app.config.ts:1–12`, `frontend/src/app/services/portfolio.service.ts:9–41`). The root coordinates eight components (`frontend/src/app/app.ts:29–69`). Routes are empty (`frontend/src/app/app.routes.ts:1–3`). The Dockerfile packages the frontend into the Spring Boot JAR (`Dockerfile:1–29`); a standalone backend `bootJar` does not itself build/copy the frontend.

| Documentation claim | Code evidence and conclusion | Severity / practical remedy |
|---|---|---|
| Backend architecture says Boot 3.4+ (`planning/docs/backend-architecture.md:5`) | `backend/build.gradle:3` pins 3.3.4. | Low: document the actual baseline; evaluate upgrades separately, without silently changing versions. |
| Backend documentation says snapshots every five seconds (`planning/docs/backend-architecture.md:152`); research draft repeats that premise (`planning/FINALLY-RESEARCH-SPEC-v0.1.md:425`) | `backend/src/main/java/com/bergnerd/signalforge/app/portfolio/PortfolioService.java:177–179,202–207` records every **30 seconds** and after trades, matching the original plan. | Medium: correct cadence assumptions and capacity estimates; research gets session/event valuations. |
| Backend documentation describes 20+ seeds, 200ms ticks and mean reversion (`planning/docs/backend-architecture.md:186–187`) | `backend/src/main/java/com/bergnerd/signalforge/app/market/MarketSimulator.java:52–64,91–129` seeds ten symbols, ticks every **500ms**, and implements GBM plus jumps; `initialPrice` is not used for mean reversion. | Medium: label synthetic behavior accurately; never use it as historical evidence. |
| Documented `/api/portfolio/snapshots` and `/api/market/prices[/{ticker}]` (`planning/docs/backend-architecture.md:273–280`) | Actual history is `/api/portfolio/history` (`backend/src/main/java/com/bergnerd/signalforge/app/portfolio/PortfolioController.java:27–29`, `frontend/src/app/services/portfolio.service.ts:40–41`). No market REST controller exists; quotes are SSE and watchlist joins (`backend/src/main/java/com/bergnerd/signalforge/app/market/PriceStreamController.java:10–19`). | Medium: publish the actual endpoint catalog before implementing new clients. Current frontend/backend history paths agree. |
| Strict typing, Tailwind, exponential SSE backoff, “60s” buffer (`planning/docs/frontend-architecture.md:9–11,64,82`) | `frontend/tsconfig.json:5–20` enables selected checks but omits `strict` and `strictTemplates`; explicit `any` remains. CSS is handwritten (`frontend/src/styles.css:1–18`); no Tailwind dependency. SSE retries after a fixed 3 seconds and retains **60 samples**, approximately 30 seconds at 500ms (`frontend/src/app/services/price-stream.service.ts:48–55,69–80`). | Medium: fix the document and add targeted typing/state work; no framework replacement needed. |
| README advertises Lightweight Charts and automatic migrations (`README.md:43–44`) | Dependency exists, but charts render custom SVG (`frontend/src/app/components/main-chart/main-chart.component.ts:42–87`, `frontend/src/app/components/pnl-chart/pnl-chart.component.ts:19–69`). Startup only executes `CREATE TABLE IF NOT EXISTS` SQL (`backend/src/main/java/com/bergnerd/signalforge/app/db/DatabaseInitializer.java:54–69`). | Medium: distinguish installed libraries and schema creation from implemented capabilities. |
| Documentation says integration tests cover market orders and watchlist modifications (`planning/docs/backend-architecture.md:299–300`) and SSE buffering tests (`planning/docs/frontend-architecture.md:198–200`) | `backend/src/test/java/com/bergnerd/signalforge/app/SignalForgeIntegrationTest.java:29–50` contains three GET smoke tests. `frontend/src/app/services/services.spec.ts:1–176` tests portfolio/watchlist/chat HTTP services; no real `PriceStreamService` test. | High confidence gap: replace coverage claims with actual test inventory. |
| Deployment guide uses `signalforge-backend-0.0.1-SNAPSHOT.jar` (`planning/docs/deployment-guide.md:98`) | Build sets `1.0.0-SNAPSHOT` (`backend/build.gradle:45–47`). Guide's `.env.example` instructions also reference an absent tracked file. | Low: repair startup examples and provide a safe configuration template. |

### Reuse and refactoring decisions

| Area | Preserve | Necessary change / stage |
|---|---|---|
| Runtime and delivery | Java 21, Boot MVC, JDBC, one container/port, Gradle wrapper and Angular lockfile | Fix startup and isolation configuration in M1; retain current declared versions initially. |
| Portfolio | Controller/DTO patterns and tested buy/sell scenarios | Replace financial math with a small pure `BigDecimal` accounting class and a transactional application service. Existing service becomes a legacy adapter, not a shared historical execution source. M1. |
| Market | Quote interface and SSE emitter cleanup/heartbeats (`backend/src/main/java/com/bergnerd/signalforge/app/market/PriceBroadcaster.java:22–103`) | Add provenance/freshness to demo quote handling as needed. Introduce separate immutable history/as-of contracts in M2; do not enlarge the quote cache into a time-series store. |
| Chat | `LlmClient`, provider configuration and injectable HTTP client (`backend/src/main/java/com/bergnerd/signalforge/app/chat/OpenAiCompatibleLlmClient.java:42–72`) | Explicit legacy boundary in M1; read-only selected research context and proposal tools in M5. No LLM inside accounting transactions. |
| Frontend | Layout, watchlist, positions presentation, chart cards, chat panel, service patterns | Move the terminal into a legacy route; add explicit context/decimal-string models and portfolio state for M1. Later research services must never subscribe valuations to demo SSE. |
| Charts | Existing small SVG sparklines | Research charts need dated multi-series data, tooltips, currency and quality labels. Evaluate the already-declared Lightweight Charts package in M3; no chart dependency or redesign in M0/M1. |
| Tests | Mockito/WebMvcTest structure, HTTP mocks, Angular TestBed/Vitest | Keep them; add real file-backed SQLite transactional integration tests, independent accounting fixtures and explicit context tests. No JPA test slice is needed for a JDBC application. |

## Findings

### M0-01 — Duplicate YAML key blocks startup (Blocker; verified / runtime)

**Evidence:** `backend/src/main/resources/application.yml:23–41` declares `signalforge` twice. All 16 context-based backend test failures stem from `org.yaml.snakeyaml.constructor.DuplicateKeyException` or the resulting context failure threshold. Pure/service tests still run.

**Impact:** The current default application configuration cannot load, including MVC slices and full integration startup. A successful JAR build hides this failure.

**Remedy:** In M1's baseline prerequisite, consolidate that mapping and test actual YAML loading. `backend/src/test/java/com/bergnerd/signalforge/app/ApplicationLocalConfigTest.java:13–46` injects property strings into a narrow context; it does not load the YAML despite its test names. Do not bypass this failure with a test-only replacement configuration and call the baseline green.

### M0-02 — Quantities and cost basis are rounded as money (High; verified / runtime)

**Evidence:** `backend/src/main/java/com/bergnerd/signalforge/app/portfolio/PortfolioService.java:85,101–122,148–166,239–240`; `backend/src/main/resources/db/schema.sql:3,19–20,30–31,38`. Values use `double`/`REAL`; `round` converts an already-computed binary floating-point value through `BigDecimal`, then back to `double`, at two decimals with HALF_UP.

**Reproduction:** Two buys of `0.004` shares at a fixed price of `100` should yield `0.008` shares. The actual proxied service yields **0.01**, cash **9999.20**, average cost **80**, and equity **10000.20** from initial cash 10000, with unchanged prices and no external return. The first insertion stores the unrounded quantity; subsequent updates round it. Small residual sales can likewise delete holdings prematurely.

**Remedy:** Exact decimal persistence and domain arithmetic; separate price, cash and quantity policies. Adopt the draft DECIMAL128 / price-eight-decimals / cash-two-decimals HALF_EVEN policy as a versioned research default. Preserve legacy raw values and disclose conversion losses. Do not merely replace DTO types while retaining `getDouble`, `setDouble` or SQL `REAL` aggregation.

### M0-03 — No portfolio or listing isolation (High; extension gap verified / static)

**Evidence:** `backend/src/main/resources/db/schema.sql:1–49` has six user/ticker tables, no portfolio, listing, currency or mode. Position uniqueness is `(user_id,ticker)`. Controllers hardcode `default` (`backend/src/main/java/com/bergnerd/signalforge/app/portfolio/PortfolioController.java:17–29`, `backend/src/main/java/com/bergnerd/signalforge/app/chat/ChatController.java:17–29`). Reads can implicitly create a funded user (`backend/src/main/java/com/bergnerd/signalforge/app/portfolio/PortfolioService.java:221–235`).

**Impact:** Two strategies for the same owner cannot have independent holdings/cash. A ticker cannot distinguish venues, currencies or reused symbols. Adding an optional portfolio field to the existing request would be particularly unsafe: `TradeRequest` ignores unknown JSON properties (`backend/src/main/java/com/bergnerd/signalforge/app/portfolio/TradeRequest.java:7–16`), so the legacy endpoint would still trade `default`.

**Remedy:** Explicit portfolio IDs, mode and currency; stable instrument/listing IDs; uniqueness per portfolio/listing. Research APIs reject absent/unknown scope. Keep legacy routes permanently bound to a designated legacy ID, with an explicit guard against research targets. Never infer EUR from the dollar demo.

### M0-04 — Trade transaction exists, but durable idempotency does not (High; verified / runtime plus untested concurrency risks)

**Evidence:** `backend/src/main/java/com/bergnerd/signalforge/app/portfolio/PortfolioService.java:79–82,97–98,110–179` wraps each trade in `@Transactional`, generates a fresh UUID and uses a JVM per-user monitor. Schema `trades` has no request/event uniqueness (`backend/src/main/resources/db/schema.sql:25–33`).

**Verified positive:** A disposable Spring proxy using the repository method and `JdbcTransactionManager` correctly rolled back cash, position, trade and snapshot writes when a trigger rejected the snapshot insert. It is incorrect to describe this service as nontransactional.

**Verified limitation:** Sending the same request twice created two trade rows. A retry after an ambiguous response has no durable way to return the original result.

**Untested concurrency risks:** The method's monitor ends before the surrounding Spring proxy commits. It also does not cover another JVM or nontrade writers. Portfolio reads fetch cash and positions in separate statements without a consistent read transaction (`:31–71`); periodic snapshots use that view (`:202–217`). Busy errors, commit-window contention and mixed valuations were not stress-tested; no lost-update incident is claimed. The “concurrent” test actually issues sequential calls (`backend/src/test/java/com/bergnerd/signalforge/app/PortfolioServiceTest.java:119–133`).

**Remedy:** Commit financial event batch, ledger, projections and operation result together. Enforce a unique scoped idempotency key with payload hash; return the stored result for a matching retry and conflict for reuse with different input. Serialize SQLite writers through commit, use bounded busy handling and test actual simultaneous connections and restart/replay. Spring proxy semantics and SQLite single-writer behavior are documented by [Spring](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html) and [SQLite](https://www.sqlite.org/lang_transaction.html).

### M0-05 — Initialization is not migration or reconstruction (High; extension gap / static)

**Evidence:** `backend/src/main/java/com/bergnerd/signalforge/app/db/DatabaseInitializer.java:33–37,54–69,73–114` executes semicolon-split schema statements and count-based seeding. No version/checksum/history/backup mechanism exists. An intentionally empty watchlist is reseeded on restart (`:89–101`). `trades` stores only side, quantity, price and time; no funding, fees, splits, receivables or cash movements (`backend/src/main/resources/db/schema.sql:25–33`). “Append-only” is a calling convention, not a database restriction.

**Impact:** New table definitions cannot upgrade existing columns/constraints. Partial initialization can persist. Historical trade rows may not reconstruct current holdings because of prior rounding and missing accounting events. Simply replaying them risks inventing a false history.

**Remedy:** Use the migration mechanism and reconciliation process below; preserve original records, apply explicit migration adjustments, and seed only a genuinely new installation. Preserve empty watchlists. Inspect a backed-up legacy database during M1, not during this audit.

### M0-06 — Fabricated and stale quotes are executable (High; verified / runtime and static)

**Evidence:** `backend/src/main/java/com/bergnerd/signalforge/app/market/MassiveMarketClient.java:38–47` creates a timestamped `100.0` quote when absent; `:100–115` retains last values on invalid/missing responses or exceptions. `backend/src/main/java/com/bergnerd/signalforge/app/portfolio/PortfolioService.java:94–103` executes directly against the returned price with no source/session/freshness gate. The existing test explicitly expects the placeholder (`backend/src/test/java/com/bergnerd/signalforge/app/MassiveMarketClientTest.java:13–24`). The probe obtained `100.0` for an unknown listing without any HTTP call.

**Impact:** Missing API data looks tradable. Poll-time timestamps (`backend/src/main/java/com/bergnerd/signalforge/app/market/MassiveMarketClient.java:74,107`) do not prove the observation's market time. The transport connection indicator does not establish data freshness.

**Remedy:** Represent unavailable/stale quotes explicitly and reject their execution. Supply immutable quote metadata to a demo execution adapter; historical/paper fills must come from validated historical observations with availability and execution times. No simulator fallback for research.

### M0-07 — Latest-price interface cannot support historical research (High; extension gap)

**Evidence:** `backend/src/main/java/com/bergnerd/signalforge/app/market/MarketDataSource.java:5–8` exposes current quotes and ticker registration only. `backend/src/main/java/com/bergnerd/signalforge/app/market/MassiveMarketClient.java:63–107` requests a US-stock snapshot and reads selected close/open fields; it has no date range, calendar, immutable dataset, revision, adjustment, corporate action or listing contract. `backend/src/main/java/com/bergnerd/signalforge/app/market/MarketSimulator.java:19,79–87` constrains symbols to 1–5 letters and invents prices for new ones.

**Impact:** European ETF coverage and raw/adjusted conventions are unverified. The same `previousPrice/change` fields mean previous simulator tick (`backend/src/main/java/com/bergnerd/signalforge/app/market/MarketSimulator.java:153–159`) versus prior day in the provider (`backend/src/main/java/com/bergnerd/signalforge/app/market/MassiveMarketClient.java:94–107`), making cross-source comparison ambiguous. A seeded random generator still uses scheduling and wall-clock timestamps; it is not a reproducible historical engine.

**Remedy:** Keep quotes separate. M1 adds identity only. M2 adds the draft file contract, immutable dataset checksums, raw bars, calendars, effective/available/imported times, corporate actions and strict missing-data validation. Provider selection remains D3; no subscription or European coverage is assumed.

### M0-08 — Chat execution and its audit result can diverge (High; verified / runtime)

**Evidence:** `backend/src/main/java/com/bergnerd/signalforge/app/chat/ChatService.java:68–82,80–135,137–153` saves a user message, calls an LLM, commits individual trades and later saves assistant actions. Each action catches failures and continues. The entire chat is not atomic and should not hold a database transaction over remote inference.

**Reproduction:** Rejecting only the assistant-message insert left **one committed trade and zero assistant messages**. Retrying the chat can execute another trade. Action metadata does not include the returned trade ID (`:82–89`). Clearing history deletes these action descriptions (`:46–48`), although trade rows remain.

**Remedy:** Persist execution intent and durable operation/result references independently of conversational text. Keep legacy autonomous execution scoped to the legacy portfolio. Research chat initially reads selected results or creates validated proposals; acceptance and deterministic execution are a separate boundary. LLM failure must not affect completed research records.

### M0-09 — Schema-shaped LLM text is not an authorization boundary (High; research extension gap, verified / static)

**Evidence:** The system prompt grants autonomous powers (`backend/src/main/java/com/bergnerd/signalforge/app/chat/ChatService.java:158–186`); every returned trade is executed. `backend/src/main/java/com/bergnerd/signalforge/app/chat/OpenAiCompatibleLlmClient.java:147–162` accepts embedded JSON after a failed direct parse; there is no local schema validation/authorization step. REST `@Valid` is not applied to instructions passed directly to the service. Nullable malformed instructions can fail again inside the error handler (`backend/src/main/java/com/bergnerd/signalforge/app/chat/ChatService.java:80–98`). Missing cloud credentials silently select a mock (`backend/src/main/java/com/bergnerd/signalforge/app/chat/ChatConfig.java:30–33`), whose regex can interpret a quoted or negated “buy 5 AAPL” as a trade (`backend/src/main/java/com/bergnerd/signalforge/app/chat/MockLlmClient.java:13,25–30`).

**Impact:** A useful demo behavior would be an unacceptable execution policy for research portfolios. Schema-valid output alone cannot establish user intent, portfolio ownership, data cutoff or acceptance time. There is no broker connection in this code; these are paper/demo mutations.

**Remedy:** Server-enforced mode/context checks and typed validation independent of the model; separate research tools from legacy trade instructions. Do not forward unrelated portfolios/history. Add execution IDs to auditable actions. Add timeouts and sanitized errors: current HTTP clients set no explicit timeout policy (`backend/src/main/java/com/bergnerd/signalforge/app/chat/OpenAiCompatibleLlmClient.java:63–71`, `backend/src/main/java/com/bergnerd/signalforge/app/market/MassiveMarketClient.java:23–28`), and parsing logs raw model content (`backend/src/main/java/com/bergnerd/signalforge/app/chat/OpenAiCompatibleLlmClient.java:151`). Provider latency and actual sensitive-log exposure were not tested.

### M0-10 — Live component values disagree with header and history (Medium; verified / static)

**Evidence:** `frontend/src/app/app.ts:73–101` stores SSE quotes separately from portfolio responses. `frontend/src/app/components/positions-table/positions-table.component.ts:206–220` and `frontend/src/app/components/heatmap/heatmap.component.ts:203–219` recalculate from live quotes; the header displays the last REST portfolio (`frontend/src/app/components/header/header.component.ts:22–37`). No periodic portfolio refresh or SSE revaluation updates the header. History loads at initialization and after manual/chat actions (`frontend/src/app/app.ts:103–104,133–140,206,229–230`), not at each backend snapshot.

**Impact:** After prices move without a trade, the positions table can show a new total while the header/P&L remain stale. Research must not inherit this split valuation authority.

**Remedy:** For the demo, use a single clearly approximate live display projection or scheduled refresh. Research state uses authoritative, context-specific valuations with as-of/loading/error/stale states; completed backtests are immutable and unaffected by SSE.

### M0-11 — Charts are demo samples, not historical evidence (Medium; extension gap / static)

**Evidence:** `frontend/src/app/services/price-stream.service.ts:44–57` retains price-only arrays, discarding sample times. `frontend/src/app/components/main-chart/main-chart.component.ts:207–240` plots sample index and supplies a fallback 100. `frontend/src/app/components/pnl-chart/pnl-chart.component.ts:138–168` ignores `recordedAt`, appends the current value to stored snapshots and assumes a 10000 USD baseline (`:13–14,66–68`). There are no date tooltips, benchmarks, drawdowns, dataset/quality labels or history range requests.

**Impact:** Unevenly timed observations appear evenly spaced. A historical result could be contaminated by a current value if this chart were reused unchanged. `PortfolioService.getHistory` returns all rows without date bounds/index support (`backend/src/main/java/com/bergnerd/signalforge/app/portfolio/PortfolioService.java:189–199`, `backend/src/main/resources/db/schema.sql:35–40`); continuous 30-second recording means 2,880 rows/day, about 1.05 million/year per continuously running demo.

**Remedy:** Dated, bounded series APIs and explicit chart inputs; add index/range access and retention/aggregation for demo snapshots. Use one research valuation per session/accounting event, with configurable starting capital/currency and visible quality assumptions.

### M0-12 — Strict API contracts and state isolation need work (Medium; verified / static)

**Evidence:** `frontend/tsconfig.json:5–20` lacks full strict settings; `frontend/src/app/services/chat.service.ts:25–47` uses `any[]` and parses a JSON-string `actions` field, while POST returns structured actions. Timers use `any` (`frontend/src/app/app.ts:60`, `frontend/src/app/services/price-stream.service.ts:15`). Monetary fields are numbers throughout `frontend/src/app/models/market.model.ts:11–54`. Errors have only a message (`backend/src/main/java/com/bergnerd/signalforge/app/config/GlobalExceptionHandler.java:17–45`); services mostly log load errors (`frontend/src/app/services/portfolio.service.ts:17–21`).

**Impact:** Adding several portfolios/runs to singleton state without an explicit selected ID risks stale responses overwriting another context. This race is a future extension risk, not a reproduced current multi-portfolio defect. Existing grouped long-lived root subscriptions are correctly unsubscribed (`frontend/src/app/app.ts:283–287`); the SSE service closes connections/timers (`frontend/src/app/services/price-stream.service.ts:89–96`).

**Remedy:** Dedicated research feature services with explicit request IDs/context, cancellation of obsolete loads, typed state unions and stable error codes. Decimal strings on research APIs, number conversion only at rendering boundaries. Incrementally enable strict checking and remove `any`; maintain legacy response compatibility. Backend ignores client-supplied `price`, so frontend trade estimates are not authoritative fills (`backend/src/main/java/com/bergnerd/signalforge/app/portfolio/TradeRequest.java:7–16`, `backend/src/main/java/com/bergnerd/signalforge/app/portfolio/PortfolioService.java:94–95`).

### M0-13 — Network exposure exceeds the proposed local-only boundary (High; verified / static, external exploitation untested)

**Evidence:** `docker-compose.yml:8–9` publishes `8000:8000`; scripts use that compose configuration (`scripts/start_mac.sh:18–19`, `scripts/start_windows.ps1:15–20`). `backend/src/main/java/com/bergnerd/signalforge/app/config/WebConfig.java:17–20` permits any origin for mutations. No application security/owner authentication layer is present; controllers operate on `default`. `backend/src/main/resources/application.yml:1–2` does not limit native host binding.

**Impact:** Deployment exposes a no-login mutation API beyond the intended local surface, subject to host/firewall/browser restrictions. This is a code/configuration finding, not a claim the owner's machine is publicly reachable.

**Remedy:** M1 default host publication `127.0.0.1:8000:8000`, loopback binding for native local runs, explicit same-origin mutation protection and development-origin allowlist. Remote access remains a separate D4 design. LLM and market keys stay server-side.

### M0-14 — In-memory integration configuration is unsafe with multiple connections (High test-confidence risk; primitive reproduced)

**Evidence:** `backend/src/test/java/com/bergnerd/signalforge/app/SignalForgeIntegrationTest.java:16–20` and `test/docker-compose.test.yml:9–11` use `jdbc:sqlite::memory:` without constraining the pooled datasource. SQLite creates a separate private database for each such connection. The probe confirmed a second connection cannot see the first connection's schema. The full app consequence is currently masked by M0-01; pooled request failures were not reproduced.

**Remedy:** Use a unique temporary **file-backed SQLite database** per integration test context, with production-like connections and foreign keys, and delete it only after closing the context. Reserve `SingleConnectionDataSource(:memory:)` for small isolated tests. This distinction is documented in [SQLite's in-memory database reference](https://www.sqlite.org/inmemorydb.html).

### M0-15 — Existing test count is not accounting/concurrency coverage (High; verified / static)

**Evidence:** `backend/src/test/java/com/bergnerd/signalforge/app/PortfolioServiceTest.java:33–48` creates a custom reduced schema and directly instantiates the service, bypassing `@Transactional`. Its six tests cover basic empty/buy/sell/rejection and sequential different-user operations. There are no rollback, duplicate delivery, fees, splits, migration, recovery or same-owner portfolio-isolation tests. `backend/src/test/java/com/bergnerd/signalforge/app/MassiveMarketClientTest.java:13–31` tests fallback and empty-key polling, not provider response parsing. There are no `PriceBroadcaster` or real frontend EventSource tests. LLM client HTTP/parse tests are useful (`backend/src/test/java/com/bergnerd/signalforge/app/OpenAiCompatibleLlmClientTest.java:57–114,125–175`).

**Impact:** Passing tests can endorse fabricated quote behavior and omit durability errors. No coverage percentage was measured; no percentage is inferred from test count.

**Remedy:** M1 exit requires independent A01/A02/A13/A14 evidence, real transactional proxies, injected write failures, process/context restart and two simultaneous writers. Preserve existing tests, correct overstated names/documentation and add fixtures for actual monetary invariants.

### M0-16 — Builds are partly reproducible; deployment/E2E remain unverified (Medium; verified static and actual command results)

**Evidence:** Gradle wrapper pins 8.10.2 (`backend/gradle/wrapper/gradle-wrapper.properties:3`) and build pins Boot/SQLite, but no dependency lock/verification metadata or wrapper SHA-256 is committed. Frontend lockfile pins Angular core 22.1.5, CLI/build 22.1.7, TypeScript 6.0.3, Vitest 4.1.11 and Lightweight Charts 5.2.1. `Dockerfile:2–15` uses mutable image tags, `npm install`, then copies the entire frontend. No `.dockerignore` exists, so local `node_modules` can enter the build and overwrite the container installation; local backend resources/build caches can also be copied. This contamination was not tested in Docker.

`frontend/package.json:4–9` and `frontend/angular.json:15–77` contain no lint task/target; no Spotless task/plugin is present. No CI workflow is committed. E2E uses a floating `@playwright/test` range with no lockfile (`test/package.json:8–11`), an old fixed browser image and `npx playwright test` without a clean install step (`test/docker-compose.test.yml:18–28`). The exact subtitle assertion is uppercase (`test/e2e/workstation.spec.ts:9`), while template text is title case (`frontend/src/app/components/header/header.component.ts:17`); this assertion mismatch is statically verified, not an executed E2E result.

**Remedy:** Add `.dockerignore`, use `npm ci`, align/pin E2E package/browser versions, establish clean CI and explicit formatting/lint gates with dependency approval if new tooling is needed. Confirm a clean container build and fresh/restarted-volume smoke test. Do not upgrade dependencies merely to match architecture prose.

### M0-17 — Backup/readiness guidance needs executable evidence (Medium; untested deployment risk)

**Evidence:** `backend/src/main/java/com/bergnerd/signalforge/app/system/HealthController.java:15–21` always returns UP, not data freshness. Production compose has no healthcheck (`docker-compose.yml:1–23`). The test image healthcheck expects `curl`, while the Dockerfile installs no utilities (`test/docker-compose.test.yml:12–16`, `Dockerfile:18–29`). The deployment guide's backup command assumes a `sqlite3` CLI in that image (`planning/docs/deployment-guide.md:327–331`); its presence was not verified. No restore test exists. External local configuration is imported unconditionally, including a classpath location (`backend/src/main/resources/application.yml:11–15`); broad Docker copies could package ignored local resource configuration if present.

**Remedy:** A tested backup/restore procedure with the application stopped for M1; explicit excluded secrets/build inputs; separate application/database readiness from market-data freshness. Use an actually available probe mechanism and verify graceful shutdown/restart. Do not claim runtime utility absence solely from the base-image name.

### Checked and not reported as defects

- **Docker datasource interpolation:** Exec-form `CMD` does pass `${SPRING_DATASOURCE_URL}` literally (`Dockerfile:29`), as described by [Docker](https://docs.docker.com/reference/dockerfile/#variable-substitution). However, the resolved Spring environment **expands that property value**. A probe against this repository's Spring dependencies returned `jdbc:sqlite:/app/db/signalforge.db`. Therefore this is **not a verified startup defect**; removing the redundant `-D` argument would simplify configuration, but Docker runtime verification is still outstanding.
- **Wrapper line endings:** An initial `git archive` inherited local `core.autocrlf=true`, producing an unusable CRLF shebang. The HEAD blob and existing working wrapper are LF. Extraction with `git -c core.autocrlf=false archive` avoids it. This was an audit-environment issue, not corrupt committed wrapper code.
- **Financial rollback:** A proxied single trade did roll back on persistence failure. Lack of sufficient test coverage is distinct from absence of transactional implementation.

## Concrete M1 extension plan (proposal; not implemented)

### Decisions used for this recommendation

Use the draft defaults of a local single user, SQLite, ETF-first research, EUR research accounts, file import first, paper-only execution, English identifiers/German-localizable labels and optional LLM. These are **planning assumptions**, not newly approved user decisions. Preserve actual dependency versions. Retain the existing demo as `LEGACY_DEMO`; its USD presentation is documented evidence, but the old database itself has no currency field. Record that currency inference explicitly during migration. Do not convert its amounts to EUR by relabeling them.

D5 (SQLite versus PostgreSQL) must be settled before finalizing M1 migrations; the recommended implementation below uses the draft SQLite default. D2/D3 (actual listings/benchmark/provider), D6 (personal horizon/loss criteria), remote access and strategy choices do not block technical fixtures. No real listing metadata, allocation or subscription is inferred.

### One migration mechanism

**Select a small ordered JDBC migration runner using existing Spring JDBC/`TransactionTemplate`, with a `schema_migrations` table.** This adds no external dependency and fits the existing direct SQL design. Use a fixed ordered list of versioned SQL resources plus a concrete Java conversion step for legacy decimal conversion; no generic migration framework, plugin discovery or competing startup initializer. Record version, description, migration checksum, code version and applied timestamp; refuse changed checksums, an unknown newer schema and partially recognized legacy schemas. Migration entries commit with their schema/data changes. Make initialization finish before scheduled work or requests can mutate the database.

If the owner prefers Flyway/Liquibase, that is a separate explicit dependency decision before implementation. Do not install both or silently introduce one while following this recommendation.

### Exact M1 schema boundary

All IDs are non-null text primary keys unless a composite key is specified. Financial fields are canonical decimal **TEXT**, written with `setString` and read with `getString`/`new BigDecimal`; business timestamps are UTC strings. Quantize at the policy boundary, serialize with `toPlainString` and a documented canonical representation (including one zero representation). No SQL floating-point financial arithmetic. Enforce foreign keys on every connection; add not-null, enum and uniqueness constraints. Validate decimal grammar, bounds and nonnegative invariants in the domain layer without a `CAST(... AS REAL)` check.

| Table | Required M1 columns and constraints |
|---|---|
| `schema_migrations` | `version INTEGER PRIMARY KEY`, description, checksum, applied_at, code_version. |
| `portfolios` | id, owner_id, name, mode (`LEGACY_DEMO`, `PAPER`, reserved `BACKTEST`), base_currency, initial_cash, created_at, nullable paper_started_at, rounding_policy_version. No implicit funded account creation on GET. Creating `BACKTEST` accounts remains unavailable until runs exist. |
| `instruments` | id, type, name, nullable ISIN, provenance/classification. Legacy symbol placeholders are explicitly unresolved demo instruments; do not invent ISIN/type evidence. |
| `listings` | id, instrument_id FK, venue, symbol, quote_currency, nullable calendar_id/inception/termination dates, identity_status. Unique validated venue+symbol+currency identity; stable surrogate ID handles aliases and changes. Legacy entries can have unresolved venue/calendar and are forbidden in research execution. |
| `listing_aliases` | listing_id FK, source/namespace, alias, effective_from/to; unique source+alias+effective_from. Resolve ambiguities explicitly. M1 technical fixtures may supply known test identities; calendars/providers are M2. |
| `portfolio_state` | portfolio_id PK/FK, cash_amount, revision INTEGER. Rebuildable cash projection, updated only with ledger writes. |
| `positions` | portfolio_id FK, listing_id FK, quantity, total_acquisition_cost, updated_at; PK `(portfolio_id,listing_id)`. Derive average unit cost with versioned arithmetic; retain total basis to avoid repeated rounding loss. |
| `operations` | id, portfolio_id FK, kind, idempotency_key, payload_hash, result_json, business_at, created_at; UNIQUE `(portfolio_id,kind,idempotency_key)`. Financial result and key commit together. Persist rejected requests separately only if their retry semantics are explicitly defined. |
| `ledger_entries` | id, portfolio_id FK, operation_id FK, sequence, entry_type, nullable listing_id FK, signed quantity_delta, signed cash_delta, acquisition_cost_delta, currency, business_at, recorded_at, nullable legacy_record_id; UNIQUE `(operation_id,sequence)`. A composite operation/portfolio foreign key ensures ledger entries and their operations share the same portfolio. No application update/delete path. Corrections are new entries. |
| `executions` | id, portfolio_id FK, operation_id FK, listing_id FK, side, units, reference_price, fill_price, commission, modeled_spread/slippage, executed_at, execution_model; scoped sequence/key uniqueness. M1 supplies deterministic test fills and legacy quote fills only, not a historical engine. |
| `valuations` | id, portfolio_id FK, business_at, valuation_sequence, cash, positions_value, receivables_value, equity, source/quality; unique portfolio+business_at+sequence. Preserve legacy snapshot IDs/times or an exact mapping. Research calculations arrive later. |
| Legacy metadata | Preserve watchlist and chat IDs/times/actions with explicit legacy portfolio scope; action-to-execution links when demonstrable, unresolved mapping otherwise. Keep original six tables in a read-only legacy archive or a preserved backup with a manifest; do not discard original REAL values after conversion. |

Do not create empty implementations for every later module. Dataset/bar/action/universe/strategy/run/proposal job tables belong to M2–M5. M1 can reserve modes and persist generic operation causes without creating a strategy lifecycle prematurely.

### Transaction and accounting contract

Use a thin application service and one small pure accounting class taking explicit decimal inputs and business time. No HTTP, `Instant.now()`, LLM calls or quote-cache access in that class. Resolve a legacy quote before opening the financial transaction; pass the immutable fill input into it. Historical and prospective adapters are later implementations.

For initial SQLite simplicity, configure a deliberately serialized writer path that holds its execution guard **through `TransactionTemplate.execute` completion**, not an inner `@Transactional` monitor. Keep one application instance as the supported deployment. Configure foreign keys and a bounded busy timeout on connections. Make the idempotency-row insertion the first database mutation before reading cash/positions; this obtains the database write lock before financial read-modify-write. Database uniqueness remains the correctness mechanism across retries and competing connections. Roll back on every accounting failure; retry only a whole transaction at a bounded boundary, reusing the same key. Do not nest a manual `BEGIN IMMEDIATE` inside a Spring-managed transaction. Read multi-query portfolios in a consistent transaction; keep remote I/O and background calculations outside it.

Within one transaction: validate scope/mode and request hash; acquire or resolve the operation key; load cash and positions; compute exact deltas; reject negative cash/short holdings; append ledger and execution rows; update projections and revision; persist the stable response; commit. A duplicate with the same payload returns that result; a reused key with different payload returns HTTP 409 with a stable error code. Publish notifications after commit. Later order batches must encompass all fills/fees/status changes in the same boundary.

Cash is booked to two decimals HALF_EVEN, executable prices support eight decimals, and internal divisions use DECIMAL128. Quantities are not rounded to cents. Whole-unit buy sizing belongs to the later execution model; the foundation retains exact split residuals. Acquisition cost includes buy fees; sales remove proportional basis and record net proceeds/realized gains; splits alter units and per-unit basis inversely while preserving total basis. Record rounding residuals as explicit adjustments when needed. M1 tests these accounting primitives; it does not implement corporate-action import or scheduled strategy execution.

### Legacy upgrade and rollback procedure

1. First correct M0-01 and establish a tested demo build; only then tag a functioning pre-migration release. The audited HEAD is not a functioning-release candidate on present evidence.
2. Stop application writers. Back up the database consistently, including any required journal/WAL state by checkpoint/SQLite backup rather than copying only an active main file. Record checksums, size, schema, row counts and application version. Keep the pre-migration image/JAR.
3. Identify an empty database versus the exact legacy schema versus an unsupported schema. New installation seeds once through migrations. Existing intentional emptiness is not treated as a seed request.
4. Assign one legacy portfolio per existing owner (at least `default`), preserve source IDs/timestamps and map tickers to explicit unresolved legacy listing IDs. Research PAPER portfolios are newly funded separately; do not merge balances or trade histories.
5. Convert existing REAL values with a fixed Java rule: use the finite value's deterministic decimal representation (`BigDecimal.valueOf`), apply the documented field scale/rounding policy once, and record before/after values and deltas. Preserve raw source records; lost precision cannot be recovered. Reject nonfinite/invalid records into a migration diagnostic that blocks success.
6. Reconcile current cash, quantity and acquisition cost against preserved trade history. Where replay cannot explain state, retain the history and create explicit `MIGRATION_OPENING`/`MIGRATION_ADJUSTMENT` entries. Do not replay old trades on top of an opening balance and double count them. Report cash/units/basis differences per portfolio/listing, row mappings and unresolved history/currency assumptions. Old snapshots remain legacy observations, not recalculated research performance.
7. Commit migration with its version/checksum; verify foreign keys and reconciliation before serving requests. Legacy API adapters write only the migrated legacy portfolio through the new accounting boundary. Research routes require an explicit ID and reject legacy trade instructions targeting PAPER.
8. Prove repeated startup does not repeat funding, holdings, adjustments or watchlist seeds. Perform a real restore rehearsal into a **separate disposable location**, open it with the old application and compare checksum/rows. Rollback means restoring the backup and prior binary, not trying to reverse rounded data with ad hoc SQL.

### Ordered M1 work and exit evidence

1. **Baseline prerequisite:** fix YAML startup, isolate integration databases, correct test/build harness issues and restrict default local exposure. Preserve versions. Run current tests and container smoke tests before migrations.
2. **Migration and identity:** implement the one runner, tables, stable IDs, backup/reconciliation output and legacy adapters. Add portfolio create/list/detail APIs under `/api/research/portfolios`, plus read-only instrument/listing identity APIs. Creation requires mode/currency/funding and an idempotency key; no public arbitrary ledger-write endpoint.
3. **Accounting boundary:** exact domain calculations and transactional ledger/projection updates. Legacy manual/chat requests go through this boundary with legacy mode checks. Research PAPER creation and inspection work independently of LLM availability; strategy trading is not enabled yet.
4. **Minimal frontend foundation:** display explicit selected portfolio/mode/currency, distinguish legacy demo, use decimal-string research contracts and typed loading/error/empty state. Reject stale responses after context switches. Preserve existing demo layout; no research chart overhaul yet.
5. **Exit gate:** A01 fresh/legacy upgrade, repeat startup and backup restoration; A02 exact buys/fees/sells/split residual accounting and projection rebuild; A13 same-key duplicate/concurrent/restart replay with conflict on mismatched payload; A14 injected failures at ledger, cash, positions, executions and result writes with complete rollback. Include two PAPER portfolios belonging to the same owner holding the same listing, plus a LEGACY_DEMO account, and prove cross-scope manual/chat writes fail. Verify the legacy demo remains usable through actual HTTP/frontend flows.

Use independently computed expected values, not expectations generated by the same implementation. M1 is complete only when the full backend/frontend suites and applicable isolated deployment checks pass with migration reconciliation/restore evidence. A02 here covers accounting primitives; M2/M3 remain responsible for historical action timing and execution simulation. No M1 work was performed by this audit.

## Verification record

### Environment and isolation

Host: macOS arm64. Default Java was Temurin 17.0.19; a pre-existing Gradle-managed Temurin **21.0.12.1** was selected explicitly for backend verification. Node **26.8.1**, npm **11.19.0**, Gradle wrapper **8.10.2**; package metadata requests npm 11.18.0. The locked Angular packages' Node engine range accepts Node 26 (`frontend/package-lock.json:304–309`). Docker CLI **29.7.2** was installed; its daemon socket was absent.

Temporary audit root: `/private/tmp/signalforge-M0`. Backend source came from `git archive HEAD backend`, with the working-tree LF wrapper copied after the local archive line-ending issue. That wrapper matches the HEAD LF content. No private `application-local.yml` or existing database was copied. The Gradle cache was copied to the audit root; unavailable declared dependencies were then downloaded there. All runtime probes used fresh in-memory databases and fake/empty provider keys, with no market or LLM network calls. Frontend first used the existing installed dependencies, then an independent clean `npm ci` installation in the temporary checkout. Both produced the same JavaScript/CSS output bytes and passed all 19 tests. This verifies reproducibility on this host, not on the untested Linux container.

### Commands and actual outcomes

Read-only inspection used `cat`, `nl -ba`, `sed`, `rg --files`, and targeted `rg -n` across `AGENTS.md`, the research specification, `PLAN-java.md`, `planning/docs/{backend-architecture,frontend-architecture,deployment-guide}.md`, README, all backend feature packages, test sources, frontend components/services/models/configuration, Docker/compose and scripts. `git status --short`, `git rev-parse HEAD`, `git ls-files`, `git diff --stat`, `git ls-files --eol`, `git check-attr --all backend/gradlew`, `git show HEAD:backend/gradlew`, `git config --get core.autocrlf`, and SHA-256 checks established the revision/scope. Dependency versions/engines were read directly from `frontend/package-lock.json` with Node. Python parsed JUnit XML, validated report references, compared build bytes/lockfiles, and compiled/executed the disposable Java probes. Read-only `ls`, `du`, `file` and `shasum` checked tool caches and artifacts; `ps` was sandbox-blocked. No repository secrets were printed.

| Command / check | Actual outcome |
|---|---|
| `backlog instructions overview`; `backlog search "M0" --plain` | Overview read; no existing M0 task. Read creation/execution/finalization guides and command help; created TASK-7 through CLI. Initial metadata-lock write was sandbox-blocked; authorized retry succeeded. |
| `java -version`; `/usr/libexec/java_home -V`; `node --version`; `npm --version`; `docker --version` | Versions as above. Additional read-only search found the cached Java 21 toolchain. |
| `npm run lint` in `frontend` | **Failed exit 1: missing script `lint`**. No Angular lint target either. No source linting completed. |
| Gradle `tasks --all` / build-file inspection | No Spotless plugin/task. `spotlessApply` was therefore not run; no formatting changes made. |
| `./gradlew clean test --offline --no-daemon` in original backend | Could not start: external Gradle cache lock blocked by filesystem sandbox. No tests ran in that attempt. |
| Same command in isolated archive, Java 21/private cache | Initial CRLF archive shebang failed (127); after LF wrapper copy, daemon socket sandbox blocked startup. Authorized retry reached dependency resolution but offline cache lacked Foojay 0.8.0. These are setup failures, not test results. |
| `./gradlew clean test --no-daemon` in isolated backend, environment below, outside sandbox | **Exit 1; 52 tests, 36 passed, 16 failed, 0 skipped.** Failure cause: duplicate YAML key. No code/configuration was altered to suppress failures. |
| `npm run test -- --watch=false` in original frontend | **Exit 0; 3 files, 19 tests passed**; Vitest 4.1.11. |
| `npm run build`, then `NG_BUILD_MAX_WORKERS=2 npm run build` in sandbox | Both aborted with exit 134 shortly after “Building”; no compiler diagnostic. |
| `NG_BUILD_MAX_WORKERS=2 npm run build` outside sandbox | **Exit 0**; production bundle 383.68 kB initial raw / 92.73 kB estimated transfer; build reported 1.841 seconds. This makes an environment-related abort likely, not a verified source build defect. |
| `npm ci --cache /private/tmp/signalforge-M0/npm-cache --no-audit --no-fund` in clean temporary frontend | **Exit 0; 375 packages installed in approximately five minutes.** npm warned that five packages had install scripts not covered by its allowScripts policy; no scripts were separately approved. Temporary lockfile matches HEAD bytes; the original working-tree lockfile hash is unchanged (its CRLF checkout differs only in line endings). |
| `NG_BUILD_MAX_WORKERS=2 npm run build` and `npm run test -- --watch=false` in that clean frontend | **Both exit 0; same 383.68 kB production output, 3 test files / 19 tests passed.** Main JS and CSS were byte-identical to the original-checkout build. Clean build reported 2.706 seconds. |
| `./gradlew tasks --all bootJar auditClasspath -I /private/tmp/signalforge-M0/classpath.init.gradle --offline --no-daemon` in isolated backend | **Exit 0**; JAR packaged. Temporary init script only registered an audit task writing the resolved runtime classpath; it changed no dependencies or repository files. JAR is backend-only in this test. |
| `javac` / `java AuditProbe` against compiled repository classes and resolved runtime classpath | **Exit 0**; reproduced fallback quote, fractional corruption, duplicate execution, chat/audit divergence, verified single-trade rollback and independent memory connections. Results below. |
| `javac` / `java PropertyProbe` with `SPRING_DATASOURCE_URL=jdbc:sqlite:/app/db/signalforge.db` | **Exit 0**; raw literal placeholder resolved correctly through Spring's environment. No database connection made. |
| `docker info --format '{{.ServerVersion}}'` | Daemon unavailable: `/Users/oliver/.docker/run/docker.sock` absent. **No Docker image build, deployment, E2E suite or restore test was run.** Existing application/volumes were not started. |

Backend verification environment:

```sh
JAVA_HOME=/Users/oliver/gradle/jdks/eclipse_adoptium-21-aarch64-os_x.2/jdk-21.0.12.1+1/Contents/Home
GRADLE_USER_HOME=/private/tmp/signalforge-M0/gradle
LLM_MOCK=true
MASSIVE_API_KEY=
SPRING_DATASOURCE_URL=jdbc:sqlite::memory:
# From /private/tmp/signalforge-M0/checkout/backend, with these variables exported:
./gradlew clean test --no-daemon
```

For a fresh reproduction, extract with `git -c core.autocrlf=false archive 8d6597fca4d15e1c023a2a301494cb231de72797 backend | tar -x -C <empty-audit-checkout>` and select an installed Java 21 toolchain. Keep that checkout outside directories containing private application configuration. Use a private Gradle cache and allow dependency downloads; offline mode only works after the artifacts are cached.

### Backend test distribution (JUnit XML, actual run)

| Test class | Passed | Failed |
|---|---:|---:|
| ApplicationLocalConfigTest | 4 | 0 |
| ChatConfigTest | 7 | 0 |
| ChatControllerTest | 0 | 3 |
| ChatServiceTest | 3 | 0 |
| HealthControllerTest | 0 | 1 |
| MarketSimulatorTest | 4 | 0 |
| MassiveMarketClientTest | 2 | 0 |
| OpenAiCompatibleLlmClientTest | 8 | 0 |
| PortfolioControllerTest | 0 | 6 |
| PortfolioServiceTest | 6 | 0 |
| SignalForgeIntegrationTest | 0 | 3 |
| WatchlistControllerTest | 0 | 3 |
| WatchlistServiceTest | 2 | 0 |
| **Total** | **36** | **16** |

### Disposable probe evidence and reproduction recipe

The probe compiled a temporary Java class against `sourceSets.main.runtimeClasspath`, instantiated the real `PortfolioService`, `MassiveMarketClient`, `ChatService` and `WatchlistService`, and loaded the actual `db/schema.sql` into `SingleConnectionDataSource("jdbc:sqlite::memory:", true)`. A Spring `ProxyFactory` attached `TransactionInterceptor(new JdbcTransactionManager(ds), new AnnotationTransactionAttributeSource())` to the real portfolio target. No application source was patched.

Reproduce in order: seed `default` cash 10000; use `MassiveMarketClient("")`; request an unknown quote; submit two identical `TradeRequest("TEST", .004, "buy")`; inspect cash/position/trades. Reset only this disposable DB. Add `CREATE TRIGGER fail_snapshot BEFORE INSERT ON portfolio_snapshots BEGIN SELECT RAISE(ABORT,'audit failure'); END`; buy one unit and catch the database exception, then assert all financial state rolled back. Drop that trigger; add `CREATE TRIGGER fail_assistant BEFORE INSERT ON chat_messages WHEN NEW.role='assistant' BEGIN SELECT RAISE(ABORT,'audit failure'); END`; supply a lambda `LlmClient` returning one TEST buy and call the actual chat service. Finally open a second `jdbc:sqlite::memory:` connection and query the first database's schema.

```text
CACHE_MISS_PRICE=100.0
FRACTIONAL expectedQty=0.008 actualQty=0.01 equity=10000.2
IDENTICAL_REQUESTS tradeCount=2
PROXIED_TRADE_ROLLBACK=PASS cash=10000 positions=0 trades=0 snapshots=0
CHAT_SAVE_FAILURE trades=1 assistantMessages=0
SECOND_MEMORY_CONNECTION=[SQLITE_ERROR] SQL error or missing database (no such table: users_profile)
RAW=${SPRING_DATASOURCE_URL}
SPRING_RESOLVED=jdbc:sqlite:/app/db/signalforge.db
```

Temporary raw evidence: `backend-test-online.log`, `backend-build.log`, `frontend-build-escalated.log`, `frontend-clean-build.log`, `frontend-clean-test.log`, `npm-ci.log`, `probe.log`, `property-probe.log`, `AuditProbe.java`, `PropertyProbe.java`, `runtime-classpath.txt`, and JUnit XML/HTML under `/private/tmp/signalforge-M0/checkout/backend/build/`. These are local audit artifacts, not persistent project dependencies. The essential outcomes are retained above.

### Remaining verification limits

No live provider coverage/latency, licensing, market-data correctness, long-history performance, browser rendering, actual pooled concurrency, authentication attack, owner-database reconciliation or backup restoration was tested. The existing 20-ETF/20-year/60-second target has no implementation or measurement yet. Financial probes isolate specific defects; they do not establish the correctness of every current trade. The report intentionally leaves the failing baseline unchanged because this task authorizes audit and planning only.
