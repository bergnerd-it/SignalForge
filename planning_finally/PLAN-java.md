# SignalForge — AI Trading Workstation (Java + Angular Edition)

## Project Specification

> This document is the Java/Angular variant of the original `PLAN.md`. The **product vision, UX, visual design, data model, and feature set are unchanged**. Only the implementation stack changes: the backend is **Java (Spring Boot)** instead of FastAPI/Python, and the frontend is **Angular** instead of Next.js. Sections that are stack-independent are reproduced here for completeness so this file is self-contained.

## 1. Vision

SignalForge (Finance Ally) is a visually stunning AI-powered trading workstation that streams live market data, lets users trade a simulated portfolio, and integrates an LLM chat assistant that can analyze positions and execute trades on the user's behalf. It looks and feels like a modern Bloomberg terminal with an AI copilot.

This is the capstone project for an agentic AI coding course. It is built entirely by Coding Agents demonstrating how orchestrated AI agents can produce a production-quality full-stack application. Agents interact through files in `planning/`.

## 2. User Experience

### First Launch

The user runs a single Docker command (or a provided start script). A browser opens to `http://localhost:8000`. No login, no signup. They immediately see:

- A watchlist of 10 default tickers with live-updating prices in a grid
- $10,000 in virtual cash
- A dark, data-rich trading terminal aesthetic
- An AI chat panel ready to assist

### What the User Can Do

- **Watch prices stream** — prices flash green (uptick) or red (downtick) with subtle CSS animations that fade
- **View sparkline mini-charts** — price action beside each ticker in the watchlist, accumulated on the frontend from the SSE stream since page load (sparklines fill in progressively)
- **Click a ticker** to see a larger detailed chart in the main chart area
- **Buy and sell shares** — market orders only, instant fill at current price, no fees, no confirmation dialog
- **Monitor their portfolio** — a heatmap (treemap) showing positions sized by weight and colored by P&L, plus a P&L chart tracking total portfolio value over time
- **View a positions table** — ticker, quantity, average cost, current price, unrealized P&L, % change
- **Chat with the AI assistant** — ask about their portfolio, get analysis, and have the AI execute trades and manage the watchlist through natural language
- **Manage the watchlist** — add/remove tickers manually or via the AI chat

### Visual Design

- **Dark theme**: backgrounds around `#0d1117` or `#1a1a2e`, muted gray borders, no pure black
- **Price flash animations**: brief green/red background highlight on price change, fading over ~500ms via CSS transitions
- **Connection status indicator**: a small colored dot (green = connected, yellow = reconnecting, red = disconnected) visible in the header
- **Professional, data-dense layout**: inspired by Bloomberg/trading terminals — every pixel earns its place
- **Responsive but desktop-first**: optimized for wide screens, functional on tablet

### Color Scheme
- Accent Yellow: `#ecad0a`
- Blue Primary: `#209dd7`
- Purple Secondary: `#753991` (submit buttons)

## 3. Architecture Overview

### Single Container, Single Port

```
┌─────────────────────────────────────────────────┐
│  Docker Container (port 8000)                    │
│                                                  │
│  Spring Boot (Java 21, embedded Tomcat)          │
│  ├── /api/*          REST endpoints              │
│  ├── /api/stream/*   SSE streaming (SseEmitter)  │
│  └── /*              Static file serving          │
│                      (Angular build output)       │
│                                                  │
│  SQLite database (volume-mounted)                │
│  Background task: market data polling/sim         │
└─────────────────────────────────────────────────┘
```

- **Frontend**: Angular with TypeScript, built via `ng build` (production), whose static output is served by Spring Boot as static resources
- **Backend**: Spring Boot (Java 21), managed as a Gradle project (Gradle Wrapper committed)
- **Database**: SQLite, single file at `db/signalforge.db`, volume-mounted for persistence
- **Real-time data**: Server-Sent Events (SSE) — simpler than WebSockets, one-way server→client push, works everywhere
- **AI integration**: OpenAI-compatible REST API (OpenAI, Ollama, Groq, custom), called directly from Java via Spring's `RestClient`, using JSON-schema Structured Outputs for trade execution
- **Market data**: Environment-variable driven — simulator by default, real data via Massive API if key provided

### Why These Choices

| Decision | Rationale |
|---|---|
| SSE over WebSockets | One-way push is all we need; simpler, no bidirectional complexity, universal browser support. Spring MVC exposes SSE via `SseEmitter` (or `Flux<ServerSentEvent>` in WebFlux) |
| Angular static build served by Spring Boot | Single origin, no CORS issues, one port, one container, simple deployment |
| SQLite over Postgres | No auth = no multi-user = no need for a database server; self-contained, zero config. Accessed via `sqlite-jdbc` |
| Single Docker container | Students run one command; no docker-compose for production, no service orchestration |
| Gradle for Java | Modern, fast, reproducible builds via the Gradle Wrapper; Spring Boot plugin produces a runnable fat JAR |
| Spring Boot | Batteries-included web framework: REST, SSE, DI, scheduling, JDBC, testing — the industry standard Java stack |
| Java 21 (LTS) + virtual threads | Current previous LTS; virtual threads make the blocking SSE/HTTP model cheap and scalable |
| Market orders only | Eliminates order book, limit order logic, partial fills — dramatically simpler portfolio math |

> **Framework note**: This plan assumes **Spring Boot Web MVC** (servlet stack) with **virtual threads enabled** (`spring.threads.virtual.enabled=true`), which keeps the SSE and blocking-I/O code straightforward while remaining scalable. Spring WebFlux (reactive) is a valid alternative but is not required; do not mix the two.

---

## 4. Directory Structure

```
signalforge/
├── frontend/                 # Angular TypeScript project (production build)
│   ├── src/
│   ├── angular.json
│   ├── package.json
│   └── ...
├── backend/                  # Spring Boot Gradle project (Java)
│   ├── src/main/java/...     # Application code
│   ├── src/main/resources/   # application.yml, schema.sql, seed data
│   │   └── db/               # Schema definitions, seed data, migration logic
│   ├── src/test/java/...     # JUnit tests
│   ├── build.gradle(.kts)
│   ├── settings.gradle(.kts)
│   └── gradlew, gradlew.bat, gradle/wrapper/
├── planning/                 # Project-wide documentation for agents
│   ├── PLAN.md               # Original (Python/Next.js) spec
│   ├── PLAN-java.md          # This document (Java/Angular spec)
│   └── ...                   # Additional agent reference docs
├── scripts/
│   ├── start_mac.sh          # Launch Docker container (macOS/Linux)
│   ├── stop_mac.sh           # Stop Docker container (macOS/Linux)
│   ├── start_windows.ps1     # Launch Docker container (Windows PowerShell)
│   └── stop_windows.ps1      # Stop Docker container (Windows PowerShell)
├── test/                     # Playwright E2E tests + docker-compose.test.yml
├── db/                       # Volume mount target (SQLite file lives here at runtime)
│   └── .gitkeep              # Directory exists in repo; signalforge.db is gitignored
├── Dockerfile                # Multi-stage build (Node → JDK build → JRE runtime)
├── docker-compose.yml        # Optional convenience wrapper
├── .env                      # Environment variables (gitignored, .env.example committed)
└── .gitignore
```

### Key Boundaries

- **`frontend/`** is a self-contained Angular project. It knows nothing about Java. It talks to the backend via `/api/*` endpoints and `/api/stream/*` SSE endpoints. Internal structure (modules, standalone components, services) is up to the Frontend Engineer agent.
- **`backend/`** is a self-contained Gradle project with its own `build.gradle(.kts)`. It owns all server logic including database initialization, schema, seed data, API routes, SSE streaming, market data, and LLM integration. Internal package structure (controllers, services, repositories, config) is up to the Backend/Market Data agents.
- **`backend/src/main/resources/db/`** contains schema SQL definitions and seed logic. The backend lazily initializes the database on first request/startup — creating tables and seeding default data if the SQLite file doesn't exist or is empty.
- **`db/`** at the top level is the runtime volume mount point. The SQLite file (`db/signalforge.db`) is created here by the backend and persists across container restarts via Docker volume.
- **`planning/`** contains project-wide documentation, including this plan. All agents reference files here as the shared contract.
- **`test/`** contains Playwright E2E tests and supporting infrastructure (e.g., `docker-compose.test.yml`). Unit tests live within `frontend/` and `backend/` respectively, following each framework's conventions.
- **`scripts/`** contains start/stop scripts that wrap Docker commands.

---

## 5. Environment Variables

```bash
# Provider options: openai (default), ollama, groq, custom, mock
LLM_PROVIDER=openai

# Required for cloud providers: API key (e.g. OpenAI, Groq)
LLM_API_KEY=your-api-key-here

# Optional: Model name (defaults depend on provider: gpt-4o-mini for openai, llama3.1 for ollama, llama-3.3-70b-versatile for groq)
LLM_MODEL=gpt-4o-mini

# Optional: Custom OpenAI-compatible base URL (defaults: OpenAI, Ollama, Groq endpoints)
LLM_BASE_URL=

# Optional: Massive (Polygon.io) API key for real market data
# If not set, the built-in market simulator is used (recommended for most users)
MASSIVE_API_KEY=

# Optional: Set to "true" for deterministic mock LLM responses (testing)
LLM_MOCK=false
```

### Behavior

- If `MASSIVE_API_KEY` is set and non-empty → backend uses Massive REST API for market data
- If `MASSIVE_API_KEY` is absent or empty → backend uses the built-in market simulator
- If `LLM_MOCK=true` → backend returns deterministic mock LLM responses (for E2E tests)
- The backend reads these values via Spring's environment/`application.yml` property binding. Environment variables are provided to the container via docker `--env-file .env`. (For local `./gradlew bootRun`, an env-loading approach such as `spring-dotenv` or exporting the variables may be used; do not commit real secrets.)

---

## 6. Market Data

### Two Implementations, One Interface

Both the simulator and the Massive client implement the same Java interface (e.g. `MarketDataSource`). The backend selects which bean to use based on the environment variable (a `@Configuration` factory or `@ConditionalOnProperty`). All downstream code (SSE streaming, price cache, frontend) is agnostic to the source.

### Simulator (Default)

- Generates prices using geometric Brownian motion (GBM) with configurable drift and volatility per ticker
- Updates at ~500ms intervals
- Correlated moves across tickers (e.g., tech stocks move together)
- Occasional random "events" — sudden 2-5% moves on a ticker for drama
- Starts from realistic seed prices (e.g., AAPL ~$190, GOOGL ~$175, etc.)
- Runs as an in-process background task — no external dependencies. Implement with Spring's `@Scheduled` task or a dedicated scheduled `ExecutorService` (virtual-thread friendly). Use a fixed random seed when `LLM_MOCK`/test mode requires determinism.

### Massive API (Optional)

- REST API polling (not WebSocket) — simpler, works on all tiers. Use Spring's `RestClient`/`WebClient`.
- Polls for the union of all watched tickers on a configurable interval
- Free tier (5 calls/min): poll every 15 seconds
- Paid tiers: poll every 2-15 seconds depending on tier
- Parses REST response (Jackson) into the same domain model as the simulator

### Shared Price Cache

- A single background task (simulator or Massive poller) writes to an in-memory price cache — a thread-safe structure such as `ConcurrentHashMap<String, PriceTick>`
- The cache holds the latest price, previous price, and timestamp for each ticker
- SSE streams read from this cache and push updates to connected clients
- This architecture supports future multi-user scenarios without changes to the data layer

### SSE Streaming

- Endpoint: `GET /api/stream/prices`
- Long-lived SSE connection implemented with Spring MVC `SseEmitter` (or `Flux<ServerSentEvent<…>>` under WebFlux). The controller registers each emitter and a scheduled broadcaster pushes updates.
- Client uses the native `EventSource` API
- Server pushes price updates for all tickers known to the system at a regular cadence (~500ms) — in the single-user model this is equivalent to the user's watchlist
- Each SSE event contains ticker, price, previous price, timestamp, and change direction (serialized to JSON via Jackson)
- Client handles reconnection automatically (EventSource has built-in retry). The server should send periodic keep-alive comments/heartbeats and clean up emitters on completion/timeout/error.

---

## 7. Database

### SQLite with Lazy Initialization

The backend checks for the SQLite database on startup (or first request). If the file doesn't exist or tables are missing, it creates the schema and seeds default data. Options in the Spring ecosystem:

- **Recommended**: Spring's `schema.sql` / `data.sql` initialization (or a `CommandLineRunner`) executing idempotent `CREATE TABLE IF NOT EXISTS` and seed statements against the SQLite `DataSource` (via `sqlite-jdbc`).
- Data access via **`JdbcTemplate`** (recommended for this small, well-defined schema) or **Spring Data JPA** with a SQLite Hibernate dialect (`org.hibernate.community.dialect.SQLiteDialect` from `hibernate-community-dialects`).

> **Java note**: If Spring Data JPA is chosen, entities must be plain mutable classes — **JPA entities cannot be Java `record`s** (records are final and lack a no-arg constructor). Use `record`s only for DTOs / API payloads / internal value objects, not for persisted entities.

This means:

- No separate migration step
- No manual database setup
- Fresh Docker volumes start with a clean, seeded database automatically

### Schema

All tables include a `user_id` column defaulting to `"default"`. This is hardcoded for now (single-user) but enables future multi-user support without schema migration.

**users_profile** — User state (cash balance)
- `id` TEXT PRIMARY KEY (default: `"default"`)
- `cash_balance` REAL (default: `10000.0`)
- `created_at` TEXT (ISO timestamp)

**watchlist** — Tickers the user is watching
- `id` TEXT PRIMARY KEY (UUID)
- `user_id` TEXT (default: `"default"`)
- `ticker` TEXT
- `added_at` TEXT (ISO timestamp)
- UNIQUE constraint on `(user_id, ticker)`

**positions** — Current holdings (one row per ticker per user)
- `id` TEXT PRIMARY KEY (UUID)
- `user_id` TEXT (default: `"default"`)
- `ticker` TEXT
- `quantity` REAL (fractional shares supported)
- `avg_cost` REAL
- `updated_at` TEXT (ISO timestamp)
- UNIQUE constraint on `(user_id, ticker)`

**trades** — Trade history (append-only log)
- `id` TEXT PRIMARY KEY (UUID)
- `user_id` TEXT (default: `"default"`)
- `ticker` TEXT
- `side` TEXT (`"buy"` or `"sell"`)
- `quantity` REAL (fractional shares supported)
- `price` REAL
- `executed_at` TEXT (ISO timestamp)

**portfolio_snapshots** — Portfolio value over time (for P&L chart). Recorded every 30 seconds by a background task (Spring `@Scheduled`), and immediately after each trade execution.
- `id` TEXT PRIMARY KEY (UUID)
- `user_id` TEXT (default: `"default"`)
- `total_value` REAL
- `recorded_at` TEXT (ISO timestamp)

**chat_messages** — Conversation history with LLM
- `id` TEXT PRIMARY KEY (UUID)
- `user_id` TEXT (default: `"default"`)
- `role` TEXT (`"user"` or `"assistant"`)
- `content` TEXT
- `actions` TEXT (JSON — trades executed, watchlist changes made; null for user messages)
- `created_at` TEXT (ISO timestamp)

### Default Seed Data

- One user profile: `id="default"`, `cash_balance=10000.0`
- Ten watchlist entries: AAPL, GOOGL, MSFT, AMZN, TSLA, NVDA, META, JPM, V, NFLX

---

## 8. API Endpoints

> Endpoint paths, methods, and JSON contracts are **identical to the original plan** — the frontend contract does not change with the backend language.

### Market Data
| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/stream/prices` | SSE stream of live price updates |

### Portfolio
| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/portfolio` | Current positions, cash balance, total value, unrealized P&L |
| POST | `/api/portfolio/trade` | Execute a trade: `{ticker, quantity, side}` |
| GET | `/api/portfolio/history` | Portfolio value snapshots over time (for P&L chart) |

### Watchlist
| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/watchlist` | Current watchlist tickers with latest prices |
| POST | `/api/watchlist` | Add a ticker: `{ticker}` |
| DELETE | `/api/watchlist/{ticker}` | Remove a ticker |

### Chat
| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/chat` | Send a message, receive complete JSON response (message + executed actions) |

### System
| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/health` | Health check (for Docker/deployment). May be backed by Spring Boot Actuator (`/actuator/health`) exposed/aliased at `/api/health`. |

---

## 9. LLM Integration

Because **LiteLLM is a Python library, it is not used in the Java build**. Instead, call OpenAI-compatible **Chat Completions REST APIs** (OpenAI, Ollama, Groq, custom) directly from Java using Spring's `RestClient`.

- Base URL: Configurable (`https://api.openai.com/v1`, `http://localhost:11434/v1`, `https://api.groq.com/openai/v1`, etc.)
- Model: Configurable (`gpt-4o-mini`, `llama3.1`, `llama-3.3-70b-versatile`, etc.)
- Auth: `Authorization: Bearer ${LLM_API_KEY}` (where required)
- **Structured Outputs**: use the `response_format` field with a JSON schema (`{"type": "json_schema", "json_schema": {...}}`) so the model returns the exact trade/watchlist schema below. Parse the response with Jackson into typed Java records.

> The original plan referenced a Python-only `cerebras-inference` skill. In this Java edition, that skill does **not** apply; implement the equivalent behavior with a plain HTTP client as described above.

Configure `LLM_API_KEY` and `LLM_PROVIDER` in the `.env` file in the project root.

### How It Works

When the user sends a chat message, the backend:

1. Loads the user's current portfolio context (cash, positions with P&L, watchlist with live prices, total portfolio value)
2. Loads recent conversation history from the `chat_messages` table
3. Constructs a prompt with a system message, portfolio context, conversation history, and the user's new message
4. Calls the LLM via OpenAI-compatible REST API (`RestClient`), requesting structured output
5. Parses the complete structured JSON response (Jackson → typed record)
6. Auto-executes any trades or watchlist changes specified in the response
7. Stores the message and executed actions in `chat_messages`
8. Returns the complete JSON response to the frontend (no token-by-token streaming — modern LLM inference is fast enough that a loading indicator is sufficient)

### Structured Output Schema

The LLM is instructed to respond with JSON matching this schema:

```json
{
  "message": "Your conversational response to the user",
  "trades": [
    {"ticker": "AAPL", "side": "buy", "quantity": 10}
  ],
  "watchlist_changes": [
    {"ticker": "PYPL", "action": "add"}
  ]
}
```

- `message` (required): The conversational text shown to the user
- `trades` (optional): Array of trades to auto-execute. Each trade goes through the same validation as manual trades (sufficient cash for buys, sufficient shares for sells)
- `watchlist_changes` (optional): Array of watchlist modifications

Model these as Java `record`s (e.g. `ChatResponse`, `TradeInstruction`, `WatchlistChange`) for safe deserialization.

### Auto-Execution

Trades specified by the LLM execute automatically — no confirmation dialog. This is a deliberate design choice:
- It's a simulated environment with fake money, so the stakes are zero
- It creates an impressive, fluid demo experience
- It demonstrates agentic AI capabilities — the core theme of the course

If a trade fails validation (e.g., insufficient cash), the error is included in the chat response so the LLM can inform the user.

### System Prompt Guidance

The LLM should be prompted as "SignalForge, an AI trading assistant" with instructions to:
- Analyze portfolio composition, risk concentration, and P&L
- Suggest trades with reasoning
- Execute trades when the user asks or agrees
- Manage the watchlist proactively
- Be concise and data-driven in responses
- Always respond with valid structured JSON

### LLM Mock Mode

When `LLM_MOCK=true`, the backend returns deterministic mock responses instead of calling external LLM providers (implement as an alternate `@ConditionalOnProperty` bean of the LLM client interface). This enables:
- Fast, free, reproducible E2E tests
- Development without an API key
- CI/CD pipelines

---

## 10. Frontend Design

### Layout

The frontend is a single-page Angular application with a dense, terminal-inspired layout. The specific component architecture and layout system is up to the Frontend Engineer, but the UI should include these elements:

- **Watchlist panel** — grid/table of watched tickers with: ticker symbol, current price (flashing green/red on change), daily change %, and a sparkline mini-chart (accumulated from SSE since page load)
- **Main chart area** — larger chart for the currently selected ticker, with at minimum price over time. Clicking a ticker in the watchlist selects it here.
- **Portfolio heatmap** — treemap visualization where each rectangle is a position, sized by portfolio weight, colored by P&L (green = profit, red = loss)
- **P&L chart** — line chart showing total portfolio value over time, using data from `portfolio_snapshots`
- **Positions table** — tabular view of all positions: ticker, quantity, avg cost, current price, unrealized P&L, % change
- **Trade bar** — simple input area: ticker field, quantity field, buy button, sell button. Market orders, instant fill.
- **AI chat panel** — docked/collapsible sidebar. Message input, scrolling conversation history, loading indicator while waiting for LLM response. Trade executions and watchlist changes shown inline as confirmations.
- **Header** — portfolio total value (updating live), connection status indicator, cash balance

### Technical Notes

- Use standalone Angular components (Angular 17+), the `HttpClient` for REST calls, and RxJS `Observable`s for state. An Angular `service` (e.g. `PriceStreamService`) wraps the native `EventSource` and exposes price ticks as an RxJS stream; run `EventSource` callbacks back inside Angular's zone (or via signals) so change detection fires.
- Use `EventSource` for the SSE connection to `/api/stream/prices`
- Charting: prefer a canvas-based library for performance — **Lightweight Charts** (framework-agnostic, works well from an Angular component) for price/P&L charts; **ngx-charts** or **ng2-charts (Chart.js)** are acceptable alternatives, including for the treemap.
- Price flash effect: on receiving a new price, briefly apply a CSS class with a background-color transition, then remove it (via Angular class binding / `Renderer2`)
- All API calls go to the same origin (`/api/*`) — no CORS configuration needed in production. During `ng serve` development, use Angular's dev-server `proxy.conf.json` to forward `/api` to the Spring Boot backend.
- **Tailwind CSS** for styling with a custom dark theme (configured via `tailwind.config.js` in the Angular project)

---

## 11. Docker & Deployment

### Multi-Stage Dockerfile

```
Stage 1: Node 20 slim  (frontend build)
  - Copy frontend/
  - npm install && npm run build   (ng build --configuration production → static output)

Stage 2: Eclipse Temurin JDK 21   (backend build)
  - Copy backend/
  - Copy the Angular build output into backend static resources
    (e.g. backend/src/main/resources/static/)
  - ./gradlew bootJar --no-daemon  (produces the runnable fat JAR)

Stage 3: Eclipse Temurin JRE 21    (runtime)
  - Copy the built JAR from stage 2
  - Expose port 8000
  - CMD: java -jar app.jar   (Spring Boot serves API + static frontend on port 8000)
```

Spring Boot serves the static frontend files (from the classpath `static/` directory) and all API routes on port 8000 (`server.port=8000`). Configure SPA fallback routing so deep links resolve to `index.html`.

### Docker Volume

The SQLite database persists via a named Docker volume:

```bash
docker run -v signalforge-data:/app/db -p 8000:8000 --env-file .env signalforge
```

The `db/` directory in the project root maps to `/app/db` in the container. The backend writes `signalforge.db` to this path (configure the SQLite JDBC URL, e.g. `jdbc:sqlite:/app/db/signalforge.db`).

### Start/Stop Scripts

**`scripts/start_mac.sh`** (macOS/Linux):
- Builds the Docker image if not already built (or if `--build` flag passed)
- Runs the container with the volume mount, port mapping, and `.env` file
- Prints the URL to access the app
- Optionally opens the browser

**`scripts/stop_mac.sh`** (macOS/Linux):
- Stops and removes the running container
- Does NOT remove the volume (data persists)

**`scripts/start_windows.ps1`** / **`scripts/stop_windows.ps1`**: PowerShell equivalents for Windows.

All scripts should be idempotent — safe to run multiple times. The Docker image name, ports, volume name, and `.env` handling are unchanged from the original plan, so the scripts themselves need little or no modification.

### Optional Cloud Deployment

The container is designed to deploy to AWS App Runner, Render, or any container platform. A Terraform configuration for App Runner may be provided in a `deploy/` directory as a stretch goal, but is not part of the core build.

---

## 12. Testing Strategy

### Unit Tests (within `frontend/` and `backend/`)

**Backend (JUnit 5 + Spring Boot Test + Mockito)**:
- Market data: simulator generates valid prices, GBM math is correct, Massive API response parsing works, both implementations conform to the `MarketDataSource` interface
- Portfolio: trade execution logic, P&L calculations, edge cases (selling more than owned, buying with insufficient cash, selling at a loss)
- LLM: structured-output parsing handles all valid schemas, graceful handling of malformed responses, trade validation within chat flow (mock the HTTP client / LLM API call)
- API routes: correct status codes, response shapes, error handling (`@WebMvcTest` / `MockMvc`, or `@SpringBootTest` with `TestRestTemplate`)

**Frontend (Jasmine + Karma, or Jest via `@angular-builders/jest`)**:
- Component rendering with mock data (Angular `TestBed`)
- Price flash animation triggers correctly on price changes
- Watchlist CRUD operations
- Portfolio display calculations
- Chat message rendering and loading state
- Service tests for the SSE/`EventSource` wrapper and `HttpClient` calls (`HttpTestingController`)

### E2E Tests (in `test/`)

**Infrastructure**: A separate `docker-compose.test.yml` in `test/` that spins up the app container plus a Playwright container. This keeps browser dependencies out of the production image. (E2E stack is language-agnostic and unchanged from the original plan.)

**Environment**: Tests run with `LLM_MOCK=true` by default for speed and determinism.

**Key Scenarios**:
- Fresh start: default watchlist appears, $10k balance shown, prices are streaming
- Add and remove a ticker from the watchlist
- Buy shares: cash decreases, position appears, portfolio updates
- Sell shares: cash increases, position updates or disappears
- Portfolio visualization: heatmap renders with correct colors, P&L chart has data points
- AI chat (mocked): send a message, receive a response, trade execution appears inline
- SSE resilience: disconnect and verify reconnection

---

## 13. Summary of Changes vs. the Original `PLAN.md`

| Area | Original (`PLAN.md`) | This edition (`PLAN-java.md`) |
|---|---|---|
| Backend language/framework | Python + FastAPI | Java 21 + Spring Boot (Web MVC, virtual threads) |
| Backend build/dependency tool | `uv` + `pyproject.toml` | Gradle + Gradle Wrapper (`build.gradle`) |
| Web server | Uvicorn (ASGI) | Embedded Tomcat |
| SSE implementation | FastAPI SSE | Spring MVC `SseEmitter` (or WebFlux `Flux`) |
| Frontend framework | Next.js (static export) | Angular (`ng build` static output) |
| Frontend served by | FastAPI static files | Spring Boot static resources (`static/`) |
| DB access | SQLite via Python | SQLite via `sqlite-jdbc` + `JdbcTemplate`/JPA |
| LLM client | LiteLLM (Python) + `cerebras-inference` skill | Direct OpenAI-compatible REST call via `RestClient` (OpenAI, Ollama, Groq, custom) |
| Background tasks | Python async tasks | Spring `@Scheduled` / virtual-thread executors |
| Backend unit tests | pytest | JUnit 5 + Spring Boot Test + Mockito |
| Frontend unit tests | React Testing Library | Angular TestBed (Jasmine/Karma or Jest) |
| Dockerfile stages | Node → Python | Node → JDK build → JRE runtime |
| E2E tests | Playwright | Playwright (unchanged) |
| Product spec, UX, API contract, DB schema, env vars | — | Unchanged |

### "Is there anything else that must be changed?"

Beyond swapping the two stacks, these ripple effects should be handled:

1. **`AGENTS.md`** currently points at `planning/PLAN.md` as the key document. Decide whether to update it to reference `PLAN-java.md` (or make `PLAN-java.md` the canonical plan). This is the single most important cross-reference to fix.
2. **Directory contents change shape** even though top-level folders stay: `backend/` becomes a Gradle/Spring project (with `gradlew`, `build.gradle`, `src/main/java`, `src/main/resources`) instead of a `uv` project; `frontend/` becomes an Angular workspace (`angular.json`, `src/app`) instead of Next.js.
3. **Dockerfile** gains a three-stage layout (Node build → JDK build → JRE runtime) and copies the Angular build output into the backend's static resources.
4. **LLM integration guidance and the `cerebras-inference` skill reference are Python-specific** and must be replaced by the direct-HTTP approach documented in §9. Any other docs in `planning/` that mention FastAPI, `uv`, LiteLLM, Next.js, or pytest should be updated for consistency.
5. **`.gitignore`** entries change: ignore Java/Gradle artifacts (`build/`, `.gradle/`) and Angular artifacts (`node_modules/`, `dist/`, `.angular/`) instead of Python artifacts (`.venv/`, `__pycache__/`, `*.egg-info`).
6. **Local dev workflow** adds an Angular dev proxy (`proxy.conf.json`) so `ng serve` can reach the Spring Boot backend during development; production remains single-origin.
7. **`.env.example`** stays the same (the three variables are unchanged), but the mechanism to load `.env` locally differs (Spring property binding / `spring-dotenv` vs. Python's loader).
8. **Health check** may be provided by Spring Boot Actuator; ensure the Docker/health path still resolves to `/api/health`.
9. **Start/stop scripts** are largely unaffected because the image name, port, volume, and `.env` handling are unchanged — verify the image build args only.

Everything not listed here (the product vision, UX, visual design, color scheme, REST/SSE contract, database schema and seed data, and E2E scenarios) is intentionally **identical** to the original plan.
