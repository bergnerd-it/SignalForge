export type BacktestStatus = 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED' | 'INTERRUPTED';
export type SeriesType = 'CANDIDATE' | 'BENCHMARK';
export type OrderStatus = 'FILLED' | 'SKIPPED';
export type OrderType = 'INITIAL_BUY' | 'REINVEST';
export type EventType = 'FUNDING' | 'SPLIT' | 'ENTITLEMENT' | 'PAYMENT' | 'EXECUTION' | 'CLOSING_MARK';

export interface PagedResponse<T> {
  items: T[];
  total: number;
  limit: number;
  offset: number;
  hasMore: boolean;
}

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
  universeId?: string;
  parametersJson?: string;
  experimentId?: string;
}

export interface BacktestAnnualReturn {
  year: number;
  candidateReturn: number | null;
  benchmarkReturn: number | null;
  startEquity: string;
  endEquity: string;
  isPartial: boolean;
}

export interface UnpaidReceivableDto {
  actionId: string;
  amount: string;
  entitlementDate: string;
  entitlementTime: string;
  paymentDate: string;
  paymentInstant: string;
}

export interface BacktestAnalyticsSummary {
  initialEquity: string;
  finalEquity: string;
  cumulativeReturn: number;
  cagr?: number | null;
  benchmarkReturn?: number | null;
  benchmarkDifference: number | null;
  maxDrawdown: number;
  peakDate: string;
  troughDate: string;
  recoveryDate: string | null;
  underwaterDurationDays: number | null;
  isRecovered: boolean;
  annualizedVolatility: number | null;
  turnover: number;
  fillCount: number;
  totalCommissions: string;
  totalSpreadSlippageEstimate?: string;
  realizedGain?: string;
  unrealizedGain?: string;
  endingCash: string;
  endingReceivables: string;
  endingHoldingsValue: string;
  endingCostBasis: string;
  endingUnits: string;
  exposureWeight?: number;
  cashWeight?: number;
  receivablesWeight?: number;
  turnoverFormula?: string;
  receivableTreatment?: string;
  annualReturns: BacktestAnnualReturn[];
  unpaidReceivables?: UnpaidReceivableDto[];
}

export interface BacktestNormalizedConfig {
  strategyId: string;
  strategyVersion: string;
  datasetId: string;
  datasetInputChecksum: string;
  datasetContentChecksum: string;
  parserVersion: string;
  schemaVersion: string;
  calendarId: string;
  calendarTimezone: string;
  coverageStart: string;
  coverageEnd: string;
  candidateListingId: string;
  benchmarkListingId: string;
  quoteCurrency: string;
  initialCash: string;
  evaluationCutoff: string;
  selectedEvaluationSession: string;
  selectedEndSession: string;
  requestedStartDate: string;
  requestedEndDate: string;
  effectiveStartDate: string;
  effectiveEndDate: string;
  commissionPerFill: string;
  spreadBps: string;
  slippageBps: string;
  costModelVersion: string;
  accountingVersion: string;
  executionModelVersion: string;
  engineVersion: string;
  sourceCommit: string;
  dirtyFlag: boolean;
  classification: string;
  availabilityAssumptions: string;
}

export interface BacktestSummaryResponse {
  id: string;
  ownerId: string;
  idempotencyKey: string;
  canonicalHash: string;
  status: BacktestStatus;
  progressPct: number;
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
  failureReason: string | null;
  candidateSummary: BacktestAnalyticsSummary | null;
  benchmarkSummary: BacktestAnalyticsSummary | null;
  normalizedConfig?: BacktestNormalizedConfig | null;
  universeId?: string | null;
  parametersJson?: string | null;
  experimentId?: string | null;
  rollingWindows?: RollingWindowSummaryDto | null;
  createdAt: string;
  updatedAt: string;
  completedAt: string | null;
}

export interface DailyEquityPoint {
  sessionDate: string;
  seriesType: SeriesType;
  cash: string;
  holdingsValue: string;
  receivables: string;
  totalEquity: string;
  dailyReturn: number | null;
  drawdown: number;
  peakEquity: string;
  units: string;
  costBasis: string;
  rawClose: string;
  pointKind?: 'INITIAL_FUNDED' | 'SESSION_CLOSE';
  observationTime?: string | null;
}

export interface BacktestOrderDto {
  id: string;
  runId: string;
  seriesType: SeriesType;
  orderType: OrderType;
  listingId: string;
  sessionDate: string;
  requestedQuantity: string;
  executedQuantity: string;
  rawOpen: string;
  fillPrice: string;
  commission: string;
  spreadCost: string;
  slippageCost: string;
  totalCashImpact: string;
  status: OrderStatus;
  skipReason: string | null;
  createdAt: string;
}

export interface BacktestEventDto {
  id: string;
  runId: string;
  seriesType: SeriesType;
  eventSeq: number;
  eventType: string;
  eventDate: string;
  eventTime: string;
  description: string;
  detailsJson: string | null;
  cashDelta: string;
  unitsDelta: string;
  basisDelta: string;
  receivableDelta: string;
  createdAt: string;
}

export interface StrategyParameterDefinitionDto {
  name: string;
  type: string;
  defaultValue: string | number | boolean | null;
  description: string;
  required: boolean;
}

export interface StrategyVersionDto {
  strategyId: string;
  strategyVersion: string;
  name: string;
  strategyFamily: string;
  status: string;
  description: string;
  rebalanceFrequency: string;
  executionModel: string;
  parameters: StrategyParameterDefinitionDto[];
  supportedCalendars: string[];
  supportedCurrencies: string[];
}

export interface UniverseDto {
  id: string;
  ownerId: string;
  name: string;
  version: string;
  description: string;
  datasetId: string;
  calendarId: string;
  currency: string;
  provenance: string;
  listingCount: number;
  listings: UniverseListingDto[];
  createdAt: string;
}

export interface UniverseListingDto {
  listingId: string;
  ordinal: number;
  symbol: string;
  venue: string;
  quoteCurrency: string;
}

export interface CreateUniverseRequest {
  name: string;
  version: string;
  description: string;
  datasetId: string;
  calendarId: string;
  currency: string;
  provenance: string;
  listingIds: string[];
}

export interface ExperimentExposureEventDto {
  id: string;
  experimentId: string;
  runId?: string | null;
  accessType: string;
  userId: string;
  reasonJson?: string | null;
  exposedAt: string;
}

export interface ExperimentDto {
  id: string;
  ownerId: string;
  name: string;
  version: number;
  strategyId: string;
  strategyVersion: string;
  datasetId: string;
  benchmarkListingId: string;
  developmentStartDate: string;
  developmentEndDate: string;
  holdoutStartDate: string;
  holdoutEndDate: string;
  declaredHoldoutStatus: string;
  parametersJson?: string | null;
  totalExposures: number;
  exposureEvents: ExperimentExposureEventDto[];
  createdAt: string;
  updatedAt: string;
}

export interface CreateExperimentRequest {
  name: string;
  version: number;
  strategyId: string;
  strategyVersion: string;
  datasetId: string;
  universeId?: string | null;
  candidateListingId?: string | null;
  benchmarkListingId: string;
  developmentStartDate: string;
  developmentEndDate: string;
  holdoutStartDate: string;
  holdoutEndDate: string;
  declaredHoldoutStatus: string;
  parametersJson?: string | null;
}

export interface ComparisonMismatchReason {
  field: string;
  expected: string;
  actual: string;
  message: string;
}

export interface RollingWindowDto {
  windowIndex: number;
  startDate: string;
  targetEndDate: string;
  actualEndDate?: string | null;
  startingEquity: string;
  endingEquity?: string | null;
  compoundedReturn?: number | null;
  observationCount: number;
  isComplete: boolean;
  incompleteReason?: string | null;
}

export interface RollingWindowSummaryDto {
  totalWindows: number;
  completeWindows: number;
  positiveWindows: number;
  positiveWindowShare?: number | null;
  windows: RollingWindowDto[];
  note: string;
}

export interface BacktestComparisonDto {
  id: string;
  ownerId: string;
  name: string;
  status: 'MATCHED' | 'MISMATCHED' | 'ERROR';
  runIds: string[];
  runs: BacktestSummaryResponse[];
  mismatchReasons: ComparisonMismatchReason[];
  rollingWindowsByRun: Record<string, RollingWindowSummaryDto>;
  createdAt: string;
}

export interface CreateComparisonRequest {
  name: string;
  runIds: string[];
}

export interface SignalItemDto {
  id: string;
  signalId: string;
  listingId: string;
  score?: number | null;
  indexValue?: string | null;
  smaValue?: string | null;
  rank?: number | null;
  eligible: boolean;
  selected: boolean;
  targetWeight: string;
  reasonCode: string;
}

export interface SignalDto {
  id: string;
  runId: string;
  strategyId: string;
  strategyVersion: string;
  universeId?: string | null;
  evaluationDate: string;
  evaluationTime: string;
  decisionInstant: string;
  scheduledExecutionDate?: string | null;
  targetAllocationSummary: string;
  status: string;
  reasonCode: string;
  detailsJson?: string | null;
  items: SignalItemDto[];
  createdAt: string;
}

export interface HoldingDto {
  listingId: string;
  units: string;
  averageCostBasis: string;
  totalCostBasis: string;
  currentPrice: string;
  marketValue: string;
  unrealizedGainLoss: string;
  weight: number;
}

