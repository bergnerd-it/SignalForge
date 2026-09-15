export interface PagedResponse<T> {
  items: T[];
  total: number;
  limit: number;
  offset: number;
  isComplete: boolean;
}

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
  approvalMode?: 'MANUAL' | 'AUTO_PAPER';
  segmentStatus?: string;
  strategyId?: string;
  universeId?: string;
  adoptedDatasetId?: string;
}

export interface ResearchPosition {
  listingId: string;
  ticker: string;
  quantity: string;
  totalAcquisitionCost: string;
  averageCost: string;
  updatedAt: string;
}

export interface CostPolicy {
  commissionPerFill: string;
  bidAskSpreadBps: string;
  slippageBps: string;
}

export interface PaperSegment {
  id: string;
  portfolioId: string;
  strategyId: string;
  strategyVersion: string;
  universeId: string;
  benchmarkListingId: string;
  costPolicy: CostPolicy;
  approvalMode: 'MANUAL' | 'AUTO_PAPER';
  status: 'ACTIVE' | 'TERMINATED';
  initialEquity: string;
  openingObservationInstant: string;
  adoptedDatasetId: string | null;
  adoptedAt: string | null;
  createdAt: string;
}

export interface PaperProposalItem {
  id: string;
  proposalId: string;
  listingId: string;
  rank: number;
  targetWeight: string;
  desiredUnits: string;
  score: string | null;
  reasonCode: string;
  reasonDescription: string | null;
  rawPriceReference: string | null;
}

export interface PaperProposalObservation {
  id: string;
  proposalId: string;
  listingId: string;
  observationSessionDate: string;
  observationType: string;
  observationValue: string;
  observedAt: string;
}

export interface PaperProposal {
  id: string;
  portfolioId: string;
  cycleId: string;
  strategyId: string;
  strategyVersion: string;
  datasetId: string;
  datasetChecksum: string;
  calendarId: string;
  calendarVersion: string;
  evaluationSessionDate: string;
  inputCutoffInstant: string;
  evaluationInstant: string;
  scheduledOpenSessionDate: string;
  scheduledOpenInstant: string;
  reasonCode: string;
  portfolioStateVersion: number;
  status: 'PROPOSED' | 'ACCEPTED' | 'REJECTED' | 'SUPERSEDED' | 'MISSED';
  acceptedAt: string | null;
  rejectedAt: string | null;
  rejectionReason: string | null;
  supersedingProposalId: string | null;
  createdAt: string;
  items: PaperProposalItem[];
  observations: PaperProposalObservation[];
}

export interface PaperValuation {
  id: string;
  portfolioId: string;
  sessionDate: string;
  observationKind: 'OPENING' | 'SESSION_CLOSE' | 'CORPORATE_ACTION';
  observationInstant: string;
  cashBalance: string;
  positionsMarketValue: string;
  receivablesValue: string;
  totalEquity: string | null;
  cumulativeReturn: string | null;
  highWaterMark: string | null;
  drawdown: string | null;
  dataReadinessStatus: string;
  isComplete: boolean;
  lastSupportedObservationInstant: string | null;
  missingRequirementsDetail: string | null;
  adoptedDatasetId: string;
  adoptedDatasetChecksum: string;
}

export interface PaperModeHistory {
  id: string;
  portfolioId: string;
  fromMode: string;
  toMode: string;
  transitionInstant: string;
  triggerType: string;
  notes: string | null;
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
  activeSegment?: PaperSegment | null;
  receivablesValue?: string;
  totalEquity?: string;
  dataReadinessStatus?: string;
  pendingProposalCount?: number;
  pendingIntentCount?: number;
}

export interface CreatePortfolioRequest {
  name: string;
  mode: 'PAPER';
  baseCurrency: 'EUR';
  initialCash: string;
  idempotencyKey?: string;
}

export interface ActivatePortfolioRequest {
  strategyId: string;
  strategyVersion: string;
  universeId: string;
  benchmarkListingId: string;
  costPolicy: CostPolicy;
  approvalMode: 'MANUAL' | 'AUTO_PAPER';
}

export interface AdoptDatasetRequest {
  datasetId: string;
}

export interface ChangeApprovalModeRequest {
  approvalMode: 'MANUAL' | 'AUTO_PAPER';
  notes?: string;
}

export interface AcceptProposalRequest {
  reason?: string;
}

export interface RejectProposalRequest {
  rejectionReason: string;
}

export interface FactCard {
  title: string;
  value: string;
  description: string;
  category: string;
}

export interface EvidenceReference {
  type: string;
  id: string;
  observationInstant: string;
  description: string;
}

export interface AssistantChatRequest {
  message: string;
  context?: {
    contextType: string;
    contextId: string;
  };
}

export interface AssistantChatResponse {
  message: string;
  factCards: FactCard[];
  evidenceReferences: EvidenceReference[];
}
