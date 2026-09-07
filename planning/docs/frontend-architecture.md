# FinAlly Frontend Architecture

## 1. Overview & Technology Stack

The FinAlly frontend is a single-page application (SPA) designed as an AI-assisted trading workstation inspired by professional financial terminals (e.g., Bloomberg, Refinitiv). It provides real-time market data visualization, simulated order execution, interactive portfolio analytics, and a conversational AI copilot.

### Core Technologies
- **Framework**: Angular 19+ (Standalone Components, Zoneless-ready architecture)
- **Language**: TypeScript 5+ (Strict type-checking enabled)
- **Reactive Programming**: RxJS 7+ (`BehaviorSubject`, `Observable`, `tap`, `switchMap`, `takeUntil`)
- **Styling**: Tailwind CSS & custom modern dark-theme styling (`#0d1117`, `#161b22`, `#21262d`)
- **Real-Time Data**: Server-Sent Events (SSE) via native `EventSource` and Angular `NgZone`
- **Testing**: Vitest + Angular Testing Utilities (`TestBed`, `HttpClientTestingModule`)
- **Build System**: Angular CLI (`@angular/build:application` with esbuild)

---

## 2. Architectural Design & Component Tree

The frontend follows a modular, feature-oriented structure with thin presentation components and centralized stateful services.

### Component Tree Hierarchy

```
App (Root Standalone Component)
├── HeaderComponent (Workstation Branding, Connection Status Indicator, Account Summary)
├── WatchlistComponent (Live Ticker Grid, Sparklines, Price Flashes, Add/Remove Controls)
├── MainChartComponent (Interactive Price Chart for Selected Ticker, Period Toggles)
├── HeatmapComponent (Portfolio Asset Allocation Treemap & P&L Sizing)
├── PnlChartComponent (Historical Portfolio Value & Equity Curve)
├── PositionsTableComponent (Open Holdings Table, Avg Cost, Unrealized P&L, Quick Sell)
├── TradeBarComponent (Instant Order Entry Form: Ticker, Buy/Sell, Quantity, Market Fill)
└── ChatPanelComponent (AI Trading Assistant, Natural Language Copilot, Action Execution Badges)
```

### Component Responsibilities

| Component | Responsibility | Key Inputs / Outputs |
| :--- | :--- | :--- |
| `App` (`app.ts`) | Root orchestrator. Subscribes to core services, holds top-level state, manages toast alerts. | Coordinates child components |
| `HeaderComponent` | Displays system status, SSE stream connection dot (`connected`, `reconnecting`, `disconnected`), cash balance, and total portfolio equity. | `portfolio`, `connectionStatus` |
| `WatchlistComponent` | Renders the tracked stock watchlist, real-time prices with green/red flash animations, mini sparkline charts, and allows manual ticker addition/removal. | `watchlist`, `livePrices`, `priceHistory`, `selectedTicker`, `(tickerSelected)`, `(removeTicker)`, `(addTicker)` |
| `MainChartComponent` | Renders a rich SVG/Canvas price chart for the actively selected stock ticker with recent tick history and price movement metrics. | `ticker`, `priceTick`, `history` |
| `HeatmapComponent` | Displays a visual treemap of open portfolio positions where box size represents position value and color indicates P&L percentage. | `positions`, `totalPortfolioValue` |
| `PnlChartComponent` | Visualizes historical portfolio equity snapshots over time to illustrate account performance. | `snapshots` |
| `PositionsTableComponent` | Tabular view of all held equities including quantity, average cost, current price, market value, unrealized P&L ($ / %), and quick sell trigger. | `positions`, `(sellPosition)` |
| `TradeBarComponent` | Fast trade submission interface with real-time total cost estimation, buy/sell toggling, and input validation against available funds. | `selectedTicker`, `currentPrice`, `cashBalance`, `(executeTrade)` |
| `ChatPanelComponent` | Conversational interface with the AI copilot. Displays message thread, AI thinking indicator, and visual action pills for executed trades and watchlist adjustments. | `messages`, `isThinking`, `(sendMessage)` |

---

## 3. Reactive State Management & Service Layer

Business logic, HTTP communication, SSE stream handling, and state caching are strictly separated from UI components into dedicated `@Injectable` singleton services.

```
┌─────────────────────────────────────────────────────────────┐
│                      Angular Components                     │
└──────────────▲──────────────────▲────────────────▲──────────┘
               │                  │                │
┌──────────────┴────────┐ ┌───────┴────────┐ ┌─────┴──────────┐
│  PriceStreamService   │ │PortfolioService│ │ WatchlistService│
│  - SSE /api/stream    │ │ - GET /api/... │ │ - GET /api/... │
│  - Tick buffer (60s)  │ │ - POST /trade  │ │ - POST/DELETE  │
└───────────────────────┘ └────────────────┘ └────────────────┘
                                  ▲
                                  │ (Triggers refresh on action)
                          ┌───────┴────────┐
                          │  ChatService   │
                          │  - /api/chat   │
                          │  - Chat History│
                          └────────────────┘
```

### 1. `PriceStreamService` (`price-stream.service.ts`)
- Manages the single persistent Server-Sent Events (SSE) connection to `/api/stream/prices`.
- Uses `NgZone.run()` inside `EventSource` event listeners to bring asynchronous browser push events back into Angular change detection.
- Exposes three reactive observables:
  - `prices$: Observable<Record<string, PriceTick>>`: Latest price tick indexed by ticker symbol.
  - `history$: Observable<Record<string, number[]>>`: Sliding window of the last 60 price points per ticker for sparkline and chart rendering.
  - `status$: Observable<ConnectionStatus>`: Connection lifecycle status (`'connected' | 'reconnecting' | 'disconnected'`).
- Implements automatic reconnection with exponential backoff on stream disconnection.

### 2. `PortfolioService` (`portfolio.service.ts`)
- Manages portfolio valuation state and order execution via `/api/portfolio` and `/api/portfolio/trade`.
- Holds `portfolioSubject: BehaviorSubject<Portfolio | null>`.
- Provides `executeTrade(request: TradeRequest)`: Dispatches market orders and immediately updates local portfolio state upon successful API execution.
- Provides `loadHistorySnapshots()`: Fetches periodic equity curve data from `/api/portfolio/snapshots`.

### 3. `WatchlistService` (`watchlist.service.ts`)
- Handles user watchlist persistence and mutations via `/api/watchlist`.
- Exposes `watchlist$: Observable<WatchlistEntry[]>`.
- Supports optimistic and synchronized operations for adding (`addTicker`) and deleting (`removeTicker`) stocks.

### 4. `ChatService` (`chat.service.ts`)
- Bridges the UI with the backend LLM copilot endpoint `/api/chat` and `/api/chat/history`.
- Maintains the conversational thread in `messagesSubject: BehaviorSubject<ChatMessage[]>`.
- Controls `isThinking$: Observable<boolean>` to drive the UI loading spinner.
- **Autonomous Feedback Loop**: When the assistant's response includes executed actions (e.g., automated market buys/sells or watchlist changes), `ChatService` automatically triggers `portfolioService.refreshPortfolio()` and `watchlistService.refreshWatchlist()`, keeping the entire UI in sync without requiring a page reload.

---

## 4. TypeScript Data Models & Contracts

All contracts matching backend DTOs are strongly typed in `src/app/models/market.model.ts`:

```typescript
export interface PriceTick {
  ticker: string;
  price: number;
  previousPrice: number;
  change: number;
  changePercent: number;
  timestamp: string;
  direction: 'up' | 'down' | 'flat';
}

export interface Position {
  id: string;
  ticker: string;
  quantity: number;
  avgCost: number;
  currentPrice: number;
  totalValue: number;
  unrealizedPnl: number;
  unrealizedPnlPercent: number;
  updatedAt: string;
}

export interface Portfolio {
  userId: string;
  cashBalance: number;
  totalPositionValue: number;
  totalPortfolioValue: number;
  unrealizedPnl: number;
  unrealizedPnlPercent: number;
  positions: Position[];
}

export interface TradeRequest {
  ticker: string;
  quantity: number;
  side: 'buy' | 'sell';
}

export interface TradeResponse {
  tradeId: string;
  ticker: string;
  side: 'buy' | 'sell';
  quantity: number;
  price: number;
  totalCost: number;
  executedAt: string;
  updatedPortfolio: Portfolio;
}

export interface ChatAction {
  type: string;
  ticker: string;
  details: string;
  success: boolean;
  error?: string;
}

export interface ChatMessage {
  id?: string;
  role: 'user' | 'assistant';
  content: string;
  actions?: ChatAction[];
  createdAt: string;
}
```

---

## 5. UI/UX & Visual Design Principles

### Bloomberg Terminal Aesthetic
- **Backgrounds**: Dark slate canvas (`#0d1117`), secondary card containers (`#161b22`), and subtle borders (`#30363d`).
- **Brand Accents**:
  - Yellow/Gold Accent (`#ecad0a`): Primary branding, active highlights.
  - Cyan Primary (`#209dd7`): Headers, charts, and metrics.
  - Purple Action (`#753991`): Buy/sell and execution triggers.
  - Gain Green (`#22c55e` / `#2ea043`): Positive P&L, upticks.
  - Loss Red (`#ef4444` / `#da3633`): Negative P&L, downticks.

### Real-Time Animations & Micro-Interactions
- **Price Flashes**: Watchlist prices apply a temporary CSS keyframe flash (`flash-green` / `flash-red`) that fades over 500ms when a tick arrives.
- **Sparklines**: Lightweight SVG vector paths rendered client-side from the buffered history array.
- **Connection Indicator**: Real-time pulsating status badge in the header displaying live health of the SSE data feed.

---

## 6. Testing Strategy

Frontend tests are configured using **Vitest** for ultra-fast unit testing without headless browser overhead:

- **Service Tests (`services.spec.ts`)**:
  - Tests HTTP requests using `HttpClientTestingModule` and `HttpTestingController`.
  - Verifies reactive streams, SSE data buffering, and auto-refresh triggers.
- **Component Tests (`components.spec.ts` & `app.spec.ts`)**:
  - Tests DOM rendering, user event emissions (buy, sell, ticker click), and input binding.
- **Execution Command**:
  ```bash
  cd frontend && npm test
  ```
