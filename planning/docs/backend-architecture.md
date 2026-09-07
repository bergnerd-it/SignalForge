# FinAlly Backend Architecture & Database Specification

## 1. Overview & Technology Stack

The FinAlly backend is built on **Spring Boot 3.4+** running on **Java 21 (LTS)**. It functions as the central engine for real-time market data generation and ingestion, portfolio accounting, order execution, chat history persistence, and LLM copilot orchestration.

### Core Technologies
- **Runtime & Language**: Java 21 (LTS) with **Virtual Threads** (`spring.threads.virtual.enabled=true`) for lightweight concurrent I/O.
- **Framework**: Spring Boot (Web MVC, Scheduling, RestClient, JdbcTemplate, Actuator).
- **Database Engine**: **SQLite 3** via `org.xerial:sqlite-jdbc` with zero external database server requirements.
- **Boilerplate Reduction**: Lombok (`@RequiredArgsConstructor`, `@Getter`, `@Setter`, `@Slf4j`).
- **JSON Processing**: Jackson (`ObjectMapper`, `JsonNode`).
- **AI / LLM Integration**: Multi-provider OpenAI-compatible client (`OpenAiCompatibleLlmClient`, `MockLlmClient`) with strict JSON Schema structured outputs.
- **Testing**: JUnit 5, Mockito (`@ExtendWith(MockitoExtension.class)`), Spring MVC Test (`@WebMvcTest`), and Spring Boot Integration Tests (`@SpringBootTest`).

---

## 2. Layered Architecture & Module Breakdown

The backend follows a domain-driven, layered package design located in `com.financeally.app`:

```
com.financeally.app
├── FinAllyApplication.java          # Spring Boot Main Entrypoint
├── chat/                            # AI Assistant, LLM Clients, JSON Schema parsing
│   ├── ChatConfig.java              # Bean resolution (OpenAI/Ollama/Groq/Custom/Mock)
│   ├── ChatController.java          # REST endpoints for chat & history
│   ├── ChatService.java             # System prompt injection & auto-execution
│   ├── LlmClient.java               # Core LLM contract interface
│   ├── OpenAiCompatibleLlmClient.java # Universal OpenAI-compatible HTTP client
│   ├── MockLlmClient.java           # Deterministic offline mock client
│   └── ... (DTOs & Records)
├── market/                          # Market Data Simulation & Real-time Distribution
│   ├── MarketConfig.java            # Market data source bean wiring
│   ├── MarketDataSource.java        # Market data provider interface
│   ├── MarketSimulator.java         # Geometric random-walk price generator
│   ├── MassiveMarketClient.java     # Polygon.io/Massive API real market data client
│   ├── PriceBroadcaster.java        # SSE Emitter registry & scheduled broadcast
│   ├── PriceStreamController.java   # /api/stream/prices SSE endpoint
│   └── PriceTick.java               # Immutable market price record
├── portfolio/                       # Positions, Balances, Orders & Snapshots
│   ├── PortfolioController.java     # REST endpoints for portfolio & trades
│   ├── PortfolioService.java        # Trade execution math & concurrency locks
│   └── ... (DTOs & Exceptions)
├── watchlist/                       # User Watchlist Management
│   ├── WatchlistController.java     # REST endpoints for tracked tickers
│   └── WatchlistService.java        # Watchlist CRUD with live price joins
├── db/                              # Schema Execution & Default Data Seeding
│   └── DatabaseInitializer.java     # DDL execution & startup seed checks
├── config/                          # Web MVC, CORS, and Static Resource Forwarding
│   └── WebConfig.java               # SPA fallback routing to index.html
└── system/                          # Health & Monitoring Endpoints
    └── HealthController.java        # Health check REST endpoint
```

---

## 3. Database Architecture & Schema (SQLite)

Persistence is managed with an embedded SQLite database file located at `db/finally.db` (or configurable via `SPRING_DATASOURCE_URL`). Tables and initial data are seeded idempotently on startup by `DatabaseInitializer`.

```
┌──────────────────┐       1:N       ┌──────────────────┐
│  users_profile   │ ─────────────── │    watchlist     │
│  - id (PK)       │                 │  - id (PK)       │
│  - cash_balance  │                 │  - ticker        │
└────────┬─────────┘                 └──────────────────┘
         │
         │ 1:N
         ├────────────────────────── ┌──────────────────┐
         │                           │    positions     │
         │                           │  - id (PK)       │
         │                           │  - ticker        │
         │                           │  - quantity      │
         │                           │  - avg_cost      │
         │                           └──────────────────┘
         │ 1:N
         ├────────────────────────── ┌──────────────────┐
         │                           │      trades      │
         │                           │  - id (PK)       │
         │                           │  - ticker, side  │
         │                           │  - qty, price    │
         │                           └──────────────────┘
         │ 1:N
         ├────────────────────────── ┌──────────────────┐
         │                           │portfolio_snapshot│
         │                           │  - id (PK)       │
         │                           │  - total_value   │
         │                           └──────────────────┘
         │ 1:N
         └────────────────────────── ┌──────────────────┐
                                     │  chat_messages   │
                                     │  - id (PK)       │
                                     │  - role, content │
                                     │  - actions (JSON)│
                                     └──────────────────┘
```

### Table Definitions (`src/main/resources/db/schema.sql`)

#### 1. `users_profile`
Holds account-level financial state and available cash balance.
```sql
CREATE TABLE IF NOT EXISTS users_profile (
    id TEXT PRIMARY KEY,
    cash_balance REAL NOT NULL DEFAULT 10000.0,
    created_at TEXT NOT NULL
);
```

#### 2. `watchlist`
Stores the tickers actively watched by the user.
```sql
CREATE TABLE IF NOT EXISTS watchlist (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL DEFAULT 'default',
    ticker TEXT NOT NULL,
    added_at TEXT NOT NULL,
    UNIQUE(user_id, ticker)
);
```

#### 3. `positions`
Maintains current holdings and weighted average cost basis per ticker.
```sql
CREATE TABLE IF NOT EXISTS positions (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL DEFAULT 'default',
    ticker TEXT NOT NULL,
    quantity REAL NOT NULL DEFAULT 0.0,
    avg_cost REAL NOT NULL DEFAULT 0.0,
    updated_at TEXT NOT NULL,
    UNIQUE(user_id, ticker)
);
```

#### 4. `trades`
Immutable audit log of all completed market orders.
```sql
CREATE TABLE IF NOT EXISTS trades (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL DEFAULT 'default',
    ticker TEXT NOT NULL,
    side TEXT NOT NULL,
    quantity REAL NOT NULL,
    price REAL NOT NULL,
    executed_at TEXT NOT NULL
);
```

#### 5. `portfolio_snapshots`
Time-series entries recorded periodically (every 5 seconds) to construct the historical equity curve.
```sql
CREATE TABLE IF NOT EXISTS portfolio_snapshots (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL DEFAULT 'default',
    total_value REAL NOT NULL,
    recorded_at TEXT NOT NULL
);
```

#### 6. `chat_messages`
Stores conversation history between user and AI assistant, including structured action execution metadata.
```sql
CREATE TABLE IF NOT EXISTS chat_messages (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL DEFAULT 'default',
    role TEXT NOT NULL,
    content TEXT NOT NULL,
    actions TEXT,
    created_at TEXT NOT NULL
);
```

### Initial Data Seeding
Upon first startup, `DatabaseInitializer` seeds:
- User profile `'default'` with `$10,000.00` cash balance.
- Default watchlist containing 10 major equities: `AAPL`, `GOOGL`, `MSFT`, `AMZN`, `TSLA`, `NVDA`, `META`, `JPM`, `V`, `NFLX`.
- Initial equity snapshot of `$10,000.00`.

---

## 4. Market Data Engine & Real-Time SSE Broadcasting

### 1. Market Data Generation (`MarketSimulator`)
- Generates continuous realistic intraday market price movements for 20+ equities using a geometric random walk with mean reversion toward baseline prices.
- Updates internal state on every tick interval (200ms) with configurable volatility and drift.
- If `MASSIVE_API_KEY` is provided, `MassiveMarketClient` polls real stock data from Polygon.io.

### 2. Real-Time Price Broadcaster (`PriceBroadcaster`)
- **Server-Sent Events (SSE)**: Clients establish a long-lived HTTP connection to `GET /api/stream/prices`.
- Broadcasts current prices to all active `SseEmitter` instances every 500ms (`@Scheduled(fixedRate = 500)`).
- Sends periodic SSE heartbeat comments every 15s (`@Scheduled(fixedRate = 15000)`) to keep proxies and firewalls from dropping idle connections.
- Automatically cleans up dead emitters on client disconnects or timeout errors.

---

## 5. Portfolio Accounting & Order Execution

### Order Execution Flow (`PortfolioService.executeTrade`)
1. **Concurrency Lock**: Employs per-user locking (`synchronized (userLocks.computeIfAbsent(uid, ...))`) to eliminate race conditions on balances and positions during concurrent trade requests.
2. **Validation**:
   - Rejects negative or zero quantities.
   - For `BUY` orders: Verifies available cash: $\text{cashBalance} \ge \text{quantity} \times \text{currentPrice}$.
   - For `SELL` orders: Verifies existing holdings: $\text{holdingQuantity} \ge \text{quantity}$.
3. **Position Cost Basis Math**:
   - **Buy Order**: Recalculates weighted average cost basis:
     $$\text{newAvgCost} = \frac{(\text{oldQty} \times \text{oldAvgCost}) + (\text{buyQty} \times \text{fillPrice})}{\text{oldQty} + \text{buyQty}}$$
   - **Sell Order**: Reduces quantity; average cost basis remains unchanged. If remaining quantity reaches 0, the position row is removed.
4. **Audit Trail**: Writes an immutable transaction record to `trades` and updates `users_profile.cash_balance`.

---

## 6. AI Copilot Integration Architecture

The AI trading assistant integrates an OpenAI-compatible interface supporting **OpenAI**, **Ollama**, **Groq**, or any standard local/remote endpoint.

### System Prompt & Context Injection (`ChatService.java`)
On every user message:
1. Gathers current cash balance, open positions, unrealized P&L, and current prices of all watchlist tickers.
2. Injects this dynamic context alongside conversation history (last 10 messages) into the system prompt.
3. Invokes `LlmClient.generateResponse(...)` with JSON Schema structured output formatting (`strict: true`).

### JSON Schema Output Specification
```json
{
  "type": "object",
  "properties": {
    "message": { "type": "string" },
    "trades": {
      "type": "array",
      "items": {
        "type": "object",
        "properties": {
          "ticker": { "type": "string" },
          "side": { "type": "string", "enum": ["buy", "sell"] },
          "quantity": { "type": "number" }
        },
        "required": ["ticker", "side", "quantity"],
        "additionalProperties": false
      }
    },
    "watchlist_changes": {
      "type": "array",
      "items": {
        "type": "object",
        "properties": {
          "ticker": { "type": "string" },
          "action": { "type": "string", "enum": ["add", "remove"] }
        },
        "required": ["ticker", "action"],
        "additionalProperties": false
      }
    }
  },
  "required": ["message"],
  "additionalProperties": false
}
```

### Autonomous Action Execution
- Any instructions in `trades` are automatically executed via `PortfolioService.executeTrade()`.
- Any entries in `watchlist_changes` are executed via `WatchlistService`.
- Execution results (success, failure reason, quantity executed) are packaged into `ChatActionExecution` metadata and persisted with the assistant message.

---

## 7. REST & SSE Endpoint Catalog

| HTTP Method | Path | Description | Sample Request / Response |
| :--- | :--- | :--- | :--- |
| `GET` | `/api/stream/prices` | SSE live price stream | `text/event-stream` returning `PriceTick[]` |
| `GET` | `/api/market/prices` | Get current prices for all tickers | Returns `PriceTick[]` |
| `GET` | `/api/market/prices/{ticker}` | Get price tick for single ticker | Returns `PriceTick` |
| `GET` | `/api/watchlist` | Get user watchlist with live prices | Returns `WatchlistEntryDto[]` |
| `POST` | `/api/watchlist` | Add ticker to watchlist | `{"ticker": "NVDA"}` |
| `DELETE` | `/api/watchlist/{ticker}` | Remove ticker from watchlist | 200 OK |
| `GET` | `/api/portfolio` | Get account balance & open positions | Returns `PortfolioResponse` |
| `POST` | `/api/portfolio/trade` | Execute a market buy/sell order | `{"ticker": "AAPL", "quantity": 10, "side": "buy"}` |
| `GET` | `/api/portfolio/snapshots` | Get historical portfolio equity curve | Returns `PortfolioSnapshotDto[]` |
| `GET` | `/api/chat/history` | Retrieve past conversation history | Returns `ChatMessageRecord[]` |
| `POST` | `/api/chat` | Send message to AI trading copilot | `{"message": "Buy 5 shares of MSFT"}` |
| `GET` | `/api/health` | Application health check | `{"status": "UP", "timestamp": "..."}` |
| `GET` | `/actuator/health` | Spring Boot actuator health probe | `{"status": "UP"}` |

---

## 8. Testing Strategy

The backend follows Spring test slicing best practices:

- **Unit Tests (`MockitoExtension.class`)**:
  - `ChatServiceTest`: Validates system prompt assembly, structured response parsing, and trade auto-execution.
  - `OpenAiCompatibleLlmClientTest`: Validates multi-provider endpoint routing, HTTP JSON schema payload structuring, and error fallbacks.
  - `ChatConfigTest`: Validates configuration property resolution across generic namespace and legacy fallbacks.
- **Web Layer Slice Tests (`@WebMvcTest`)**:
  - `ChatControllerTest`: Tests chat HTTP controller serialization, request validation, and error responses.
  - `HealthControllerTest`: Verifies health check endpoints.
- **Full Integration Tests (`@SpringBootTest`)**:
  - `FinAllyIntegrationTest`: End-to-end testing with an in-memory SQLite database verifying database initialization, market orders, balance updates, and watchlist modifications.
- **Execution Command**:
  ```bash
  cd backend && ./gradlew clean test
  ```
