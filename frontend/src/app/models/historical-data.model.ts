export interface ManifestCoverage {
  start_date: string;
  end_date: string;
}

export interface Manifest {
  schema_version: string;
  source: string;
  retrieved_at: string;
  coverage: ManifestCoverage;
  license_note: string;
  classification: 'SYNTHETIC' | 'HISTORICAL';
  price_convention: 'RAW';
  calendar_completeness: string;
  action_completeness: string;
  known_limitations: string;
  availability_assumptions: string;
}

export interface ValidationFinding {
  code: string;
  severity: 'ERROR' | 'WARNING' | 'INFO';
  file: string;
  row: number | null;
  listingId: string | null;
  sessionDate: string | null;
  detail: string;
}

export interface ImportJobResponse {
  id: string;
  requestKey: string;
  inputChecksum: string;
  status: 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'INTERRUPTED';
  progressPct: number;
  datasetId: string | null;
  message: string | null;
  errorDetail: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface DatasetSummary {
  id: string;
  name: string;
  source: string;
  classification: 'SYNTHETIC' | 'HISTORICAL';
  qualityLabel: 'SYNTHETIC' | 'REVISED_HISTORY' | 'VERIFIED';
  coverageStart: string;
  coverageEnd: string;
  validationStatus: 'VALID' | 'WARNINGS' | 'REJECTED';
  importedAt: string;
  listingCount: number;
  barCount: number;
  actionCount: number;
}

export interface DatasetDetail {
  id: string;
  name: string;
  source: string;
  classification: 'SYNTHETIC' | 'HISTORICAL';
  schemaVersion: string;
  parserVersion: string;
  inputChecksum: string;
  contentChecksum: string;
  coverageStart: string;
  coverageEnd: string;
  validationStatus: 'VALID' | 'WARNINGS' | 'REJECTED';
  qualityLabel: 'SYNTHETIC' | 'REVISED_HISTORY' | 'VERIFIED';
  importedAt: string;
  manifest: Manifest | null;
  validationFindings: ValidationFinding[];
  listingCount: number;
  barCount: number;
  actionCount: number;
}

export interface DatasetListing {
  listingId: string;
  instrumentId: string;
  symbol: string;
  venue: string | null;
  quoteCurrency: string;
  calendarId: string;
  inceptionDate: string | null;
  terminationDate: string | null;
  isin: string | null;
  barCount: number;
  firstDate: string | null;
  lastDate: string | null;
}

export interface HistoricalBar {
  sessionDate: string;
  open: string;
  high: string;
  low: string;
  close: string;
  volume: string | null;
  availableAt: string;
}

export interface HistoricalAction {
  actionId: string;
  listingId: string;
  actionType: 'SPLIT' | 'CASH_DISTRIBUTION';
  effectiveDate: string;
  availableAt: string;
  splitRatio: string | null;
  distributionAmount: string | null;
  distributionCurrency: string | null;
  paymentDate: string | null;
  paymentInstant: string | null;
}

export interface PagedResponse<T> {
  items: T[];
  totalCount: number;
  limit: number;
  offset: number;
  hasMore: boolean;
}

export interface ListingHistoryResponse {
  datasetId: string;
  listingId: string;
  symbol: string;
  requestedStart: string | null;
  requestedEnd: string | null;
  availableStart: string | null;
  availableEnd: string | null;
  asOfCutoff: string | null;
  qualityLabel: string;
  bars: HistoricalBar[];
  actions: HistoricalAction[];
  coverageNotes: string | null;
  totalBars: number;
  returnedBars: number;
  limit: number;
  offset: number;
  isTruncated: boolean;
}
