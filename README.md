# FinAlly — The AI-Powered Trading Workstation

FinAlly (**Finance Ally**) is a modern, data-dense trading workstation that pairs real-time market streaming and portfolio simulation with an intelligent AI copilot. Designed with a dark terminal aesthetic inspired by professional financial software, FinAlly allows users to monitor real-time stock prices, analyze positions, execute simulated trades, and converse with an LLM assistant capable of performing automated portfolio actions.

---

## Key Features

- **Live Market Data Streaming**: Real-time ticker streaming via Server-Sent Events (SSE) with price flash animations and dynamic sparkline mini-charts.
- **Built-in Market Simulator & Real Feeds**: Ships out of the box with an in-process Geometric Brownian Motion (GBM) market simulator (zero configuration required) and supports live market data via the Massive (Polygon.io) REST API.
- **Simulated Trading & Analytics**:
  - Instant market order execution (Buy/Sell) with virtual cash ($10,000 default balance).
  - Portfolio Heatmap (treemap visualization sized by weight and colored by P&L).
  - Portfolio value snapshot history & interactive P&L line chart.
  - Detailed real-time positions table with unrealized P&L and cost basis tracking.
- **AI Trading Copilot**:
  - Natural language conversational assistant for portfolio analysis and strategy discussions.
  - **Autonomous Action Execution**: Leverages JSON Schema Structured Outputs to automatically execute trades and modify watchlists upon user request.
  - Supports multiple LLM providers: **OpenAI**, **Groq**, **Ollama** (local models), custom OpenAI-compatible endpoints, and a deterministic **Mock** mode for testing.
- **Single Container, Zero-Config Deployment**: Bundled as a single self-contained Docker container serving both the Angular SPA and Spring Boot REST/SSE endpoints on port `8000` with an embedded SQLite database.

---

## Architecture & Tech Stack

```
┌─────────────────────────────────────────────────────────┐
│              Docker Container (Port 8000)               │
│                                                         │
│  Spring Boot Backend (Java 21, Embedded Tomcat)         │
│  ├── /api/*          REST API (Portfolio, Watchlist,    │
│  │                   Chat, Health)                      │
│  ├── /api/stream/*   Server-Sent Events (SSE)           │
│  └── /*              Static Resource Serving            │
│                      (Angular Production SPA)           │
│                                                         │
│  SQLite Database (/app/db/finally.db)                   │
│  Background Scheduler: Market Simulator & Snapshots     │
└─────────────────────────────────────────────────────────┘
```

- **Backend**: Java 21, Spring Boot 3, Spring Web MVC (with Virtual Threads enabled), Spring JDBC (`JdbcTemplate`), SQLite (`sqlite-jdbc`), Lombok, Jackson.
- **Frontend**: Angular 22+, TypeScript, Tailwind CSS, Lightweight Charts, RxJS.
- **Database**: SQLite with automatic schema migration and seed data initialization.
- **Containerization**: Multi-stage Docker build (Node.js builder → Eclipse Temurin JDK builder → JRE runtime).

---

## Quick Start (Docker)

### Prerequisites

- [Docker](https://docs.docker.com/get-docker/) and [Docker Compose](https://docs.docker.com/compose/)

### 1. Configure Environment (Optional)

Copy the configuration template and set your LLM credentials:

```bash
cp application-local.yml.example application-local.yml
```

Edit `application-local.yml` to specify your provider and API key (e.g. OpenAI or Groq). If no API key is provided, FinAlly can run using Ollama locally, mock LLM mode, or standard trading terminal mode.

### 2. Launch FinAlly

**macOS / Linux:**
```bash
./scripts/start_mac.sh
```

**Windows (PowerShell):**
```powershell
.\scripts\start_windows.ps1
```

**Or directly via Docker Compose:**
```bash
docker compose up -d --build
```

### 3. Open the Workstation

Navigate to **`http://localhost:8000`** in your browser.

To stop the workstation:
```bash
# macOS / Linux
./scripts/stop_mac.sh

# Windows (PowerShell)
.\scripts\stop_windows.ps1

# Docker Compose
docker compose down
```

---

## Configuration

FinAlly can be configured either via `application-local.yml` or standard environment variables.

### Configuration Properties

| Property / Env Variable | Description | Default |
|---|---|---|
| `finally.llm.provider` / `LLM_PROVIDER` | LLM provider: `openai`, `groq`, `ollama`, `custom`, `mock` | `openai` |
| `finally.llm.api-key` / `LLM_API_KEY` | API Key for LLM provider (OpenAI / Groq) | `""` |
| `finally.llm.model` / `LLM_MODEL` | Model identifier | `gpt-4o-mini` (OpenAI), `llama-3.3-70b-versatile` (Groq), `llama3.1` (Ollama) |
| `finally.llm.base-url` / `LLM_BASE_URL` | Custom base URL for OpenAI-compatible endpoint | Provider default |
| `finally.llm.mock` / `LLM_MOCK` | Enable deterministic mock responses for testing | `false` |
| `finally.massive.api-key` / `MASSIVE_API_KEY` | Polygon.io / Massive API Key for live stock data | `""` (uses built-in GBM simulator) |
| `SPRING_DATASOURCE_URL` | SQLite database JDBC connection URL | `jdbc:sqlite:../db/finally.db` (local) / `/app/db/finally.db` (Docker) |

### Provider Examples in `application-local.yml`

#### OpenAI
```yaml
finally:
  llm:
    provider: openai
    api-key: sk-proj-...
    model: gpt-4o-mini
```

#### Groq
```yaml
finally:
  llm:
    provider: groq
    api-key: gsk_...
    model: llama-3.3-70b-versatile
```

#### Ollama (Local LLM)
```yaml
finally:
  llm:
    provider: ollama
    base-url: http://localhost:11434/v1
    model: llama3.1
```

---

## Local Development

If you prefer to run the backend and frontend independently during development:

### 1. Backend (Spring Boot)

**Requirements**: Java 21+ JDK

```bash
cd backend
./gradlew bootRun
```
The backend starts on `http://localhost:8000`. Database schema and seed data are automatically initialized on startup.

### 2. Frontend (Angular)

**Requirements**: Node.js 20+, npm

```bash
cd frontend
npm install
npm start
```
The Angular development server starts on `http://localhost:4200` and automatically proxies `/api` calls to the Spring Boot backend on port `8000`.

---

## Testing & Verification

### Run Backend Unit & Integration Tests
```bash
cd backend
./gradlew clean test
```

### Run Frontend Unit Tests
```bash
cd frontend
npm run test
```

### Run Playwright End-to-End Tests
```bash
docker compose -f test/docker-compose.test.yml up --build --abort-on-container-exit
```

---

## Project Structure

```
finally/
├── backend/                  # Spring Boot application (Java 21)
│   ├── src/main/java/        # Domain services, controllers, LLM client, repositories
│   ├── src/main/resources/   # Application configuration & database scripts
│   └── build.gradle          # Gradle build configuration
├── frontend/                 # Angular SPA (TypeScript)
│   ├── src/app/              # Standalone components, services, models
│   ├── src/styles.css        # Tailwind CSS and theme definitions
│   └── package.json          # Frontend dependencies & build scripts
├── db/                       # Volume mount directory for persistent SQLite database
├── planning/                 # Architecture specifications and system design documents
├── scripts/                  # Container management start/stop helper scripts
├── test/                     # Playwright E2E testing suite
├── Dockerfile                # Multi-stage production container definition
├── docker-compose.yml        # Docker Compose service definition
└── application-local.yml.example  # Configuration template
```

---

## License

TBD