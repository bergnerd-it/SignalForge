export interface ResearchPortfolioSummary {
  id: string;
  ownerId: string;
  name: string;
  mode: 'PAPER';
  baseCurrency: 'EUR';
  cashBalance: string;
  revision: number;
  createdAt: string;
  paperStartedAt: string | null;
  strategyTracking: string;
  positionCount: number;
}

export interface ResearchPosition {
  listingId: string;
  ticker: string;
  quantity: string;
  totalAcquisitionCost: string;
  averageCost: string;
  updatedAt: string;
}

export interface ResearchPortfolioDetail {
  id: string;
  ownerId: string;
  name: string;
  mode: 'PAPER';
  baseCurrency: 'EUR';
  cashBalance: string;
  revision: number;
  createdAt: string;
  paperStartedAt: string | null;
  strategyTracking: string;
  valuationStatus: 'UNAVAILABLE' | 'AVAILABLE';
  marketValue: string | null;
  unrealizedPnl: string | null;
  positions: ResearchPosition[];
}

export interface CreatePortfolioRequest {
  name: string;
  mode: 'PAPER';
  baseCurrency: 'EUR';
  initialCash: string;
  idempotencyKey?: string;
}
