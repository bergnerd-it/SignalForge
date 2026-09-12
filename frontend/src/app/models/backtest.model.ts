export type BacktestStatus = 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED' | 'INTERRUPTED';

export interface CreateBacktestRequest {
  datasetId: string;
  candidateListingId: string;
  benchmarkListingId: string;
  evaluationCutoff: string;
  requestedStartDate: string;
  requestedEndDate: string;
  initialCash: string;
  currency: string;
  commissionPerFill: string;
  spreadBps: string;
  slippageBps: string;
  strategyId: string;
  strategyVersion: string;
}

export interface BacktestAnnualReturn {
  year: number;
  candidateReturn: number | null;
  benchmarkReturn: number | null;
  difference?: number | null;
  isPartial?: boolean;
  isPartialYear?: boolean;
  candidateStartEquity?: string;
  candidateEndEquity?: string;
  benchmarkStartEquity?: string;
  benchmarkEndEquity?: string;
}

export interface BacktestAnalyticsSummary {
  initialEquity: string;
  finalEquity: string;
  cumulativeReturn: number;
  cagr: number | null;
  maxDrawdown: number;
  peakDate: string;
  troughDate: string;
  recoveryDate: string | null;
  drawdownDurationDays?: number;
  underwaterDurationDays?: number;
  isRecovered: boolean;
  annualizedVolatility: number | null;
  turnoverRatio?: number;
  turnover?: number;
  fillCount: number;
  totalCommissions: string;
  endingCash: string;
  endingHoldingsValue: string;
  endingReceivables: string;
  endingUnits: string;
  endingCostBasis: string;
  annualReturns: BacktestAnnualReturn[];
  benchmarkDifference: number | null;
  benchmarkReturn?: number | null;
}

export interface BacktestSummaryResponse {
  id: string;
  ownerId: string;
  idempotencyKey: string;
  canonicalHash: string;
  strategyId: string;
  strategyVersion: string;
  datasetId: string;
  candidateListingId: string;
  benchmarkListingId: string;
  initialCash: string;
  currency: string;
  evaluationCutoff: string;
  requestedStartDate: string;
  requestedEndDate: string;
  effectiveStartDate?: string;
  effectiveEndDate?: string;
  commissionPerFill: string;
  spreadBps: string;
  slippageBps: string;
  status: BacktestStatus;
  progressPct: number;
  failureReason: string | null;
  completedAt: string | null;
  createdAt: string;
  updatedAt: string;
  candidateSummary: BacktestAnalyticsSummary | null;
  benchmarkSummary: BacktestAnalyticsSummary | null;
}

export interface DailyEquityPoint {
  runId: string;
  sessionDate: string;
  seriesType: 'CANDIDATE' | 'BENCHMARK';
  cashBalance: string;
  holdingsValue: string;
  receivablesBalance: string;
  totalEquity: string;
  dailyReturn: number | null;
  cumulativeReturn: number;
  drawdown: number;
  units: string;
  closingPrice: string;
}

export interface BacktestOrderDto {
  orderId: string;
  sessionDate: string;
  seriesType: 'CANDIDATE' | 'BENCHMARK';
  listingId: string;
  side: 'BUY' | 'SELL';
  requestedUnits: string;
  filledUnits: string;
  orderStatus: 'FILLED' | 'CANCELED' | 'EXPIRED';
  limitPrice: string | null;
  unadjustedFillPrice: string;
  modeledFillPrice: string;
  commission: string;
  totalCashImpact: string;
  executedAt: string;
}

export interface BacktestEventDto {
  eventId: number;
  sessionDate: string;
  seriesType: 'CANDIDATE' | 'BENCHMARK';
  eventType: string;
  eventPayloadJson: string;
  occurredAt: string;
}
