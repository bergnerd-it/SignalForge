import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { PortfolioService } from './portfolio.service';
import { WatchlistService } from './watchlist.service';
import { ChatService } from './chat.service';
import { Portfolio, WatchlistEntry, ChatResponse } from '../models/market.model';

describe('PortfolioService', () => {
  let service: PortfolioService;
  let httpTesting: HttpTestingController;

  beforeEach(() => {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        PortfolioService,
      ],
    });

    service = TestBed.inject(PortfolioService);
    httpTesting = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpTesting.verify();
  });

  it('should fetch portfolio data on getPortfolio()', () => {
    const mockPortfolio: Portfolio = {
      userId: 'default',
      cashBalance: 10000,
      totalPositionValue: 0,
      totalPortfolioValue: 10000,
      unrealizedPnl: 0,
      unrealizedPnlPercent: 0,
      positions: [],
    };

    // Constructor refreshPortfolio request
    httpTesting.expectOne('/api/portfolio').flush(mockPortfolio);

    service.getPortfolio().subscribe((data) => {
      expect(data.cashBalance).toBe(10000);
      expect(data.totalPortfolioValue).toBe(10000);
    });

    httpTesting.expectOne('/api/portfolio').flush(mockPortfolio);
  });

  it('sends the manual trade contract with one stable idempotency key', () => {
    httpTesting.expectOne('/api/portfolio').flush({});
    const trade = { ticker: 'AAPL', quantity: 1, side: 'buy' as const };

    service.executeTrade(trade).subscribe();

    const request = httpTesting.expectOne('/api/portfolio/trade');
    expect(request.request.body).toEqual(trade);
    expect(request.request.headers.get('Idempotency-Key')).toMatch(/^trade-/);
    request.flush({
      tradeId: 'trade-1',
      ticker: 'AAPL',
      side: 'buy',
      quantity: 1,
      price: 100,
      totalCost: 100,
      executedAt: '2026-09-12T00:00:00Z',
      updatedPortfolio: null,
    });
  });
});

describe('WatchlistService', () => {
  let service: WatchlistService;
  let httpTesting: HttpTestingController;

  beforeEach(() => {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        WatchlistService,
      ],
    });

    service = TestBed.inject(WatchlistService);
    httpTesting = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpTesting.verify();
  });

  it('should add a ticker and refresh watchlist', () => {
    const newEntry: WatchlistEntry = {
      id: 'w-1',
      ticker: 'TSLA',
      price: 215,
      previousPrice: 215,
      change: 0,
      changePercent: 0,
      direction: 'flat',
      addedAt: new Date().toISOString(),
    };

    // Constructor request
    httpTesting.expectOne('/api/watchlist').flush([]);

    service.addTicker('TSLA').subscribe((res) => {
      expect(res.ticker).toBe('TSLA');
    });

    const addReq = httpTesting.expectOne('/api/watchlist');
    expect(addReq.request.method).toBe('POST');
    expect(addReq.request.body).toEqual({ ticker: 'TSLA' });
    addReq.flush(newEntry);

    // Follow-up refresh
    httpTesting.expectOne('/api/watchlist').flush([newEntry]);
  });
});

describe('ChatService', () => {
  let service: ChatService;
  let httpTesting: HttpTestingController;

  beforeEach(() => {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        PortfolioService,
        WatchlistService,
        ChatService,
      ],
    });

    service = TestBed.inject(ChatService);
    httpTesting = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpTesting.verify();
  });

  it('should post message and receive assistant response', () => {
    const mockResponse: ChatResponse = {
      message: 'Bought 5 shares of AAPL',
      actions: [{ type: 'trade', ticker: 'AAPL', details: 'BUY 5', success: true }],
      createdAt: new Date().toISOString(),
    };

    // Constructor requests for injected services
    httpTesting.expectOne('/api/chat/history').flush([]);
    httpTesting.expectOne('/api/portfolio').flush({});
    httpTesting.expectOne('/api/watchlist').flush([]);

    service.sendMessage('Buy 5 AAPL').subscribe((res) => {
      expect(res.message).toBe('Bought 5 shares of AAPL');
      expect(res.actions.length).toBe(1);
    });

    const chatReq = httpTesting.expectOne('/api/chat');
    expect(chatReq.request.method).toBe('POST');
    expect(chatReq.request.body).toEqual({ message: 'Buy 5 AAPL' });
    expect(chatReq.request.headers.get('Idempotency-Key')).toMatch(/^chat-/);
    chatReq.flush(mockResponse);

    // Actions executed -> refreshes portfolio & watchlist
    httpTesting.expectOne('/api/portfolio').flush({});
    httpTesting.expectOne('/api/watchlist').flush([]);
  });

  it('should clear chat history and reset default greeting', () => {
    // Constructor requests for injected services
    httpTesting.expectOne('/api/chat/history').flush([]);
    httpTesting.expectOne('/api/portfolio').flush({});
    httpTesting.expectOne('/api/watchlist').flush([]);

    let messages: any[] = [];
    service.messages$.subscribe((msgs) => (messages = msgs));

    service.clearHistory().subscribe();

    const deleteReq = httpTesting.expectOne('/api/chat/history');
    expect(deleteReq.request.method).toBe('DELETE');
    deleteReq.flush(null);

    expect(messages.length).toBe(1);
    expect(messages[0].role).toBe('assistant');
    expect(messages[0].content).toContain('SignalForge');
  });
});

import { ResearchService } from './research.service';
import { ResearchPortfolioSummary, ResearchPortfolioDetail } from '../models/research.model';

describe('ResearchService', () => {
  let service: ResearchService;
  let httpTesting: HttpTestingController;

  beforeEach(() => {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        ResearchService,
      ],
    });

    service = TestBed.inject(ResearchService);
    httpTesting = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpTesting.verify();
  });

  it('should list research portfolios and select detail', () => {
    const mockList: ResearchPortfolioSummary[] = [
      {
        id: 'port-1',
        ownerId: 'default',
        name: 'EUR Paper Alpha',
        mode: 'PAPER',
        baseCurrency: 'EUR',
        cashBalance: '10000.00',
        revision: 1,
        createdAt: '2026-09-12T00:00:00Z',
        paperStartedAt: null,
        strategyTracking: 'UNSTARTED',
        positionCount: 0,
      },
    ];

    // Constructor refresh
    httpTesting.expectOne('/api/research/portfolios').flush(mockList);

    let currentList: ResearchPortfolioSummary[] = [];
    service.portfolios$.subscribe((list) => (currentList = list));
    expect(currentList.length).toBe(1);
    expect(currentList[0].baseCurrency).toBe('EUR');

    // Select portfolio
    const mockDetail: ResearchPortfolioDetail = {
      ...mockList[0],
      valuationStatus: 'UNAVAILABLE',
      marketValue: null,
      unrealizedPnl: null,
      positions: [],
    };

    service.selectPortfolio('port-1');
    const req = httpTesting.expectOne('/api/research/portfolios/port-1');
    expect(req.request.method).toBe('GET');
    req.flush(mockDetail);

    const receivedDetails: ResearchPortfolioDetail[] = [];
    service.selectedPortfolio$.subscribe((detail) => {
      if (detail) {
        receivedDetails.push(detail);
      }
    });
    expect(receivedDetails.at(-1)?.name).toBe('EUR Paper Alpha');
    expect(receivedDetails.at(-1)?.valuationStatus).toBe('UNAVAILABLE');
  });

  it('cancels an obsolete portfolio selection', () => {
    httpTesting.expectOne('/api/research/portfolios').flush([]);
    service.selectPortfolio('port-a');
    const requestA = httpTesting.expectOne('/api/research/portfolios/port-a');

    service.selectPortfolio('port-b');
    const requestB = httpTesting.expectOne('/api/research/portfolios/port-b');
    expect(requestA.cancelled).toBe(true);

    requestB.flush({
      id: 'port-b',
      ownerId: 'default',
      name: 'Portfolio B',
      mode: 'PAPER',
      baseCurrency: 'EUR',
      cashBalance: '100.00',
      revision: 1,
      createdAt: '2026-09-12T00:00:00Z',
      paperStartedAt: null,
      strategyTracking: 'UNSTARTED',
      valuationStatus: 'UNAVAILABLE',
      marketValue: null,
      unrealizedPnl: null,
      positions: [],
    } satisfies ResearchPortfolioDetail);
  });
});

import { HistoricalDataService } from './historical-data.service';
import { DatasetDetail, DatasetListing, DatasetSummary, ImportJobResponse, ListingHistoryResponse } from '../models/historical-data.model';

describe('HistoricalDataService', () => {
  let service: HistoricalDataService;
  let httpTesting: HttpTestingController;

  beforeEach(() => {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        HistoricalDataService,
      ],
    });

    service = TestBed.inject(HistoricalDataService);
    httpTesting = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpTesting.verify();
  });

  it('fetches datasets on initialization', () => {
    const mockDatasets: DatasetSummary[] = [
      {
        id: 'dataset-1',
        name: 'Synthetic Alpha Dataset',
        source: 'test-source',
        classification: 'SYNTHETIC',
        qualityLabel: 'SYNTHETIC',
        coverageStart: '2024-01-01',
        coverageEnd: '2024-01-10',
        validationStatus: 'VALID',
        importedAt: '2026-09-12T12:00:00Z',
        listingCount: 2,
        barCount: 14,
        actionCount: 2,
      },
    ];

    const req = httpTesting.expectOne('/api/research/datasets');
    expect(req.request.method).toBe('GET');
    req.flush(mockDatasets);

    service.datasets$.subscribe((list) => {
      expect(list.length).toBe(1);
      expect(list[0].id).toBe('dataset-1');
      expect(list[0].classification).toBe('SYNTHETIC');
    });
  });

  it('selects dataset and loads its listings', () => {
    httpTesting.expectOne('/api/research/datasets').flush([]);

    const mockDetail: DatasetDetail = {
      id: 'dataset-1',
      name: 'Synthetic Alpha',
      source: 'test-source',
      classification: 'SYNTHETIC',
      schemaVersion: '1.0',
      parserVersion: '2.0.0-rfc4180',
      inputChecksum: 'sha-input',
      contentChecksum: 'sha-content',
      coverageStart: '2024-01-01',
      coverageEnd: '2024-01-10',
      validationStatus: 'VALID',
      qualityLabel: 'SYNTHETIC',
      importedAt: '2026-09-12T12:00:00Z',
      manifest: null,
      validationFindings: [],
      listingCount: 1,
      barCount: 7,
      actionCount: 1,
    };

    const mockListings: DatasetListing[] = [
      {
        listingId: 'listing-1',
        instrumentId: 'inst-1',
        symbol: 'SYNA',
        venue: 'XETRA',
        quoteCurrency: 'EUR',
        calendarId: 'cal-1',
        inceptionDate: '2023-01-01',
        terminationDate: null,
        isin: 'IE000SYN0001',
        barCount: 7,
        firstDate: '2024-01-02',
        lastDate: '2024-01-10',
      },
    ];

    service.selectDataset('dataset-1');

    const detailReq = httpTesting.expectOne('/api/research/datasets/dataset-1');
    expect(detailReq.request.method).toBe('GET');
    detailReq.flush(mockDetail);

    const listingsReq = httpTesting.expectOne('/api/research/datasets/dataset-1/listings');
    expect(listingsReq.request.method).toBe('GET');
    listingsReq.flush(mockListings);

    service.selectedDataset$.subscribe((d) => {
      if (d) {
        expect(d.id).toBe('dataset-1');
      }
    });

    service.listings$.subscribe((listings) => {
      if (listings.length > 0) {
        expect(listings[0].symbol).toBe('SYNA');
      }
    });
  });

  it('uploads bundle with Idempotency-Key header', () => {
    httpTesting.expectOne('/api/research/datasets').flush([]);

    const file = new File(['dummy-content'], 'bundle.zip', { type: 'application/zip' });
    const mockJob: ImportJobResponse = {
      id: 'job-123',
      requestKey: 'custom-key-1',
      inputChecksum: 'sha-123',
      status: 'QUEUED',
      progressPct: 0,
      datasetId: null,
      message: 'Import queued',
      errorDetail: null,
      createdAt: '2026-09-12T12:00:00Z',
      updatedAt: '2026-09-12T12:00:00Z',
    };

    service.uploadBundle(file, 'custom-key-1').subscribe((job) => {
      expect(job.id).toBe('job-123');
      expect(job.status).toBe('QUEUED');
    });

    const uploadReq = httpTesting.expectOne('/api/research/imports');
    expect(uploadReq.request.method).toBe('POST');
    expect(uploadReq.request.headers.get('Idempotency-Key')).toBe('custom-key-1');
    uploadReq.flush(mockJob);

    service.activeJob$.subscribe((job) => {
      expect(job?.id).toBe('job-123');
    });
  });

  it('retrieves job status via getJob', () => {
    httpTesting.expectOne('/api/research/datasets').flush([]);

    const mockJob: ImportJobResponse = {
      id: 'job-123',
      requestKey: 'custom-key-1',
      inputChecksum: 'sha-123',
      status: 'COMPLETED',
      progressPct: 100,
      datasetId: 'dataset-1',
      message: 'Import completed',
      errorDetail: null,
      createdAt: '2026-09-12T12:00:00Z',
      updatedAt: '2026-09-12T12:00:05Z',
    };

    service.getJob('job-123').subscribe((job) => {
      expect(job.status).toBe('COMPLETED');
      expect(job.datasetId).toBe('dataset-1');
    });

    const jobReq = httpTesting.expectOne('/api/research/jobs/job-123');
    expect(jobReq.request.method).toBe('GET');
    jobReq.flush(mockJob);
  });

  it('loads listing history with asOf parameter', () => {
    httpTesting.expectOne('/api/research/datasets').flush([]);

    const mockHistory: ListingHistoryResponse = {
      datasetId: 'dataset-1',
      listingId: 'listing-1',
      symbol: 'SYNA',
      requestedStart: '2024-01-01',
      requestedEnd: '2024-01-10',
      availableStart: '2024-01-02',
      availableEnd: '2024-01-10',
      asOfCutoff: '2024-01-04T18:00:00Z',
      qualityLabel: 'SYNTHETIC',
      bars: [
        {
          sessionDate: '2024-01-02',
          open: '100.00',
          high: '105.00',
          low: '98.50',
          close: '104.00',
          volume: '15000',
          availableAt: '2024-01-02T18:00:00Z',
        },
      ],
      actions: [],
      coverageNotes: null,
      totalBars: 1,
      returnedBars: 1,
      limit: 1000,
      offset: 0,
      isTruncated: false,
    };

    service.loadListingHistory('dataset-1', 'listing-1', '2024-01-01', '2024-01-10', '2024-01-04T18:00:00Z');

    const req = httpTesting.expectOne((r) => r.url === '/api/research/datasets/dataset-1/history/listing-1');
    expect(req.request.params.get('start')).toBe('2024-01-01');
    expect(req.request.params.get('end')).toBe('2024-01-10');
    expect(req.request.params.get('asOf')).toBe('2024-01-04T18:00:00Z');
    req.flush(mockHistory);

    service.selectedHistory$.subscribe((h) => {
      if (h) {
        expect(h.symbol).toBe('SYNA');
        expect(h.bars.length).toBe(1);
      }
    });
  });
});
