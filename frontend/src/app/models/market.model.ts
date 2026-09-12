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
  currentPrice: number | null;
  totalValue: number | null;
  unrealizedPnl: number | null;
  unrealizedPnlPercent: number | null;
  updatedAt: string;
}

export interface Portfolio {
  userId: string;
  cashBalance: number;
  totalPositionValue: number | null;
  totalPortfolioValue: number | null;
  unrealizedPnl: number | null;
  unrealizedPnlPercent: number | null;
  positions: Position[];
}

export interface TradeRequest {
  ticker: string;
  quantity: number;
  side: 'buy' | 'sell';
  price?: number;
}

export interface TradeResponse {
  tradeId: string;
  ticker: string;
  side: 'buy' | 'sell';
  quantity: number;
  price: number;
  totalCost: number;
  executedAt: string;
  updatedPortfolio: Portfolio | null;
}

export interface PortfolioSnapshot {
  id: string;
  totalValue: number;
  recordedAt: string;
}

export interface WatchlistEntry {
  id: string;
  ticker: string;
  price: number;
  previousPrice: number;
  change: number;
  changePercent: number;
  direction: 'up' | 'down' | 'flat';
  addedAt: string;
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

export interface ChatResponse {
  message: string;
  actions: ChatAction[];
  createdAt: string;
}
