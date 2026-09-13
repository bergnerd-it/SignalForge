import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HeaderComponent } from './header/header.component';
import { WatchlistComponent } from './watchlist/watchlist.component';
import { MainChartComponent } from './main-chart/main-chart.component';
import { HeatmapComponent } from './heatmap/heatmap.component';
import { PnlChartComponent } from './pnl-chart/pnl-chart.component';
import { TradeBarComponent } from './trade-bar/trade-bar.component';
import { PositionsTableComponent } from './positions-table/positions-table.component';
import { ChatPanelComponent } from './chat-panel/chat-panel.component';
import { ResearchComponent } from './research/research.component';
import { ResearchDataComponent } from './research-data/research-data.component';
import { ResearchBacktestsComponent } from './research-backtests/research-backtests.component';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { BehaviorSubject, of } from 'rxjs';
import { ResearchService } from '../services/research.service';
import { HistoricalDataService } from '../services/historical-data.service';
import { BacktestService } from '../services/backtest.service';

describe('Component Unit Tests', () => {
  it('HeaderComponent should display portfolio value and cash', () => {
    const fixture: ComponentFixture<HeaderComponent> = TestBed.createComponent(HeaderComponent);
    const comp = fixture.componentInstance;
    comp.portfolio = {
      userId: 'default',
      cashBalance: 10000,
      totalPositionValue: 2500,
      totalPortfolioValue: 12500,
      unrealizedPnl: 500,
      unrealizedPnlPercent: 25,
      positions: [],
    };
    comp.status = 'connected';
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.textContent).toContain('SignalForge');
    expect(compiled.textContent).toContain('$12,500.00');
    expect(compiled.textContent).toContain('$10,000.00');
    expect(compiled.textContent).toContain('CONNECTED');
  });

  it('WatchlistComponent should emit onSelect and onRemove', () => {
    const fixture: ComponentFixture<WatchlistComponent> = TestBed.createComponent(WatchlistComponent);
    const comp = fixture.componentInstance;
    comp.entries = [
      {
        id: '1',
        ticker: 'AAPL',
        price: 190,
        previousPrice: 189,
        change: 1,
        changePercent: 0.53,
        direction: 'up',
        addedAt: new Date().toISOString(),
      },
    ];
    fixture.detectChanges();

    let selected = '';
    comp.selectTicker.subscribe((t) => (selected = t));
    comp.onSelect('AAPL');
    expect(selected).toBe('AAPL');

    let removed = '';
    comp.removeTicker.subscribe((t) => (removed = t));
    comp.onRemove('AAPL');
    expect(removed).toBe('AAPL');
  });

  it('TradeBarComponent should validate and emit trade request and ticker change', () => {
    const fixture: ComponentFixture<TradeBarComponent> = TestBed.createComponent(TradeBarComponent);
    const comp = fixture.componentInstance;
    comp.ticker = 'MSFT';
    comp.currentPrice = 420;
    comp.quantity = 5;

    let tradeReq: any = null;
    comp.executeTrade.subscribe((req) => (tradeReq = req));

    comp.onTrade('buy');
    expect(tradeReq).toEqual({ ticker: 'MSFT', quantity: 5, side: 'buy', price: 420 });

    let selectedTicker = '';
    comp.selectTicker.subscribe((t) => (selectedTicker = t));
    comp.onTickerInputChange('tsla');
    expect(selectedTicker).toBe('TSLA');
    expect(comp.ticker).toBe('TSLA');
  });

  it('MainChartComponent should calculate polyline and price range', () => {
    const fixture: ComponentFixture<MainChartComponent> = TestBed.createComponent(MainChartComponent);
    const comp = fixture.componentInstance;
    comp.ticker = 'NVDA';
    comp.history = [120, 122, 121, 125];
    comp.ngOnChanges({});
    fixture.detectChanges();

    expect(comp.highPrice).toBe(125);
    expect(comp.lowPrice).toBe(120);
    expect(comp.polylinePoints).toBeTruthy();
  });

  it('HeatmapComponent should calculate weights and emit selectTicker', () => {
    const fixture: ComponentFixture<HeatmapComponent> = TestBed.createComponent(HeatmapComponent);
    const comp = fixture.componentInstance;
    comp.positions = [
      {
        id: 'p1',
        ticker: 'AAPL',
        quantity: 10,
        avgCost: 150,
        currentPrice: 180,
        totalValue: 1800,
        unrealizedPnl: 300,
        unrealizedPnlPercent: 20,
        updatedAt: new Date().toISOString(),
      },
    ];
    comp.totalPositionValue = 1800;
    comp.ngOnChanges({});
    fixture.detectChanges();

    expect(comp.items.length).toBe(1);
    expect(comp.items[0].ticker).toBe('AAPL');
    expect(comp.items[0].weight).toBe(100);

    let selected = '';
    comp.selectTicker.subscribe((t) => (selected = t));
    comp.onSelect('AAPL');
    expect(selected).toBe('AAPL');
  });

  it('PositionsTableComponent should calculate live pricing and emit quickSell and select', () => {
    const fixture: ComponentFixture<PositionsTableComponent> = TestBed.createComponent(PositionsTableComponent);
    const comp = fixture.componentInstance;
    const pos = {
      id: 'p1',
      ticker: 'AAPL',
      quantity: 10,
      avgCost: 150,
      currentPrice: 180,
      totalValue: 1800,
      unrealizedPnl: 300,
      unrealizedPnlPercent: 20,
      updatedAt: new Date().toISOString(),
    };
    comp.positions = [pos];
    comp.livePrices = {
      AAPL: {
        ticker: 'AAPL',
        price: 200,
        previousPrice: 180,
        change: 20,
        changePercent: 11.11,
        timestamp: new Date().toISOString(),
        direction: 'up',
      },
    };
    fixture.detectChanges();

    expect(comp.getPrice(pos)).toBe(200);
    expect(comp.getTotalValue(pos)).toBe(2000);
    expect(comp.getUnrealizedPnl(pos)).toBe(500);

    let selected = '';
    comp.selectTicker.subscribe((t) => (selected = t));
    comp.onSelect('AAPL');
    expect(selected).toBe('AAPL');

    let soldPos: any = null;
    comp.quickSell.subscribe((p) => (soldPos = p));
    comp.onQuickSell(pos);
    expect(soldPos).toEqual(pos);
  });

  it('PnlChartComponent should calculate baseline and bounds correctly', () => {
    const fixture: ComponentFixture<PnlChartComponent> = TestBed.createComponent(PnlChartComponent);
    const comp = fixture.componentInstance;
    comp.currentValue = 11000;
    comp.snapshots = [
      { id: '1', totalValue: 10000, recordedAt: '2026-09-01T00:00:00Z' },
      { id: '2', totalValue: 10500, recordedAt: '2026-09-01T00:01:00Z' },
    ];
    comp.ngOnChanges({});
    fixture.detectChanges();

    expect(comp.maxVal).toBeGreaterThanOrEqual(11000);
    expect(comp.baselineY).not.toBeNull();
    expect(comp.polylinePoints).toBeTruthy();
  });

  it('ChatPanelComponent should emit messages, manage autoscroll, and emit clearChat', () => {
    const fixture: ComponentFixture<ChatPanelComponent> = TestBed.createComponent(ChatPanelComponent);
    const comp = fixture.componentInstance;
    fixture.detectChanges();

    let sentMessage = '';
    comp.sendMessage.subscribe((msg) => (sentMessage = msg));

    let cleared = false;
    comp.clearChat.subscribe(() => (cleared = true));

    comp.userInput = 'Buy 10 AAPL';
    comp.onSend();
    expect(sentMessage).toBe('Buy 10 AAPL');
    expect(comp.userInput).toBe('');

    comp.sendQuickPrompt('Analyze portfolio');
    expect(sentMessage).toBe('Analyze portfolio');

    comp.onClear();
    expect(cleared).toBe(true);
  });

  it('ResearchComponent should render portfolio details and honest unavailable valuation', () => {
    const mockPortfolios = [
      {
        id: 'port-123',
        ownerId: 'default',
        name: 'EUR Tech Fund',
        mode: 'PAPER' as const,
        baseCurrency: 'EUR',
        cashBalance: '10000.00',
        revision: 1,
        createdAt: '2026-09-12T00:00:00Z',
        paperStartedAt: null,
        strategyTracking: 'UNSTARTED' as const,
        positionCount: 1,
      },
    ];
    const mockDetail = {
      id: 'port-123',
      ownerId: 'default',
      name: 'EUR Tech Fund',
      mode: 'PAPER' as const,
      baseCurrency: 'EUR',
      cashBalance: '10000.00',
      revision: 1,
      createdAt: '2026-09-12T00:00:00Z',
      paperStartedAt: null,
      strategyTracking: 'UNSTARTED' as const,
      valuationStatus: 'UNAVAILABLE' as const,
      marketValue: null,
      unrealizedPnl: null,
      positions: [
        {
          listingId: 'listing-1',
          ticker: 'VWCE',
          quantity: '10',
          totalAcquisitionCost: '1000.00',
          averageCost: '100.00000000',
          updatedAt: '2026-09-12T00:00:00Z',
        },
      ],
    };

    const mockResearchService = {
      portfolios$: of(mockPortfolios),
      selectedPortfolio$: of(mockDetail),
      loading$: of(false),
      error$: of(null),
      refreshPortfolios: () => {},
      selectPortfolio: () => {},
      createPortfolio: () => of(mockDetail),
    };

    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        { provide: ResearchService, useValue: mockResearchService },
      ],
    });
    const fixture: ComponentFixture<ResearchComponent> = TestBed.createComponent(ResearchComponent);
    fixture.detectChanges();

    const el = fixture.nativeElement as HTMLElement;
    expect(el.textContent).toContain('RESEARCH PORTFOLIOS');
    expect(el.textContent).toContain('EUR Tech Fund');
    expect(el.textContent).toContain('€10000.00');
    expect(el.textContent).toContain('UNAVAILABLE');
    expect(el.textContent).toContain('UNSTARTED');
    expect(el.textContent).toContain('VWCE');
  });

  it('ResearchDataComponent should render historical datasets, provenance and bars table', () => {
    const mockDatasets = [
      {
        id: 'ds-1',
        name: 'Synthetic Alpha Dataset',
        source: 'synthetic-v1',
        classification: 'SYNTHETIC' as const,
        qualityLabel: 'SYNTHETIC' as const,
        coverageStart: '2024-01-01',
        coverageEnd: '2024-01-10',
        validationStatus: 'VALID' as const,
        importedAt: '2026-09-12T12:00:00Z',
        listingCount: 1,
        barCount: 7,
        actionCount: 1,
      },
    ];

    const mockDetail = {
      ...mockDatasets[0],
      schemaVersion: '1.0',
      parserVersion: '2.0.0-rfc4180',
      inputChecksum: 'sha-input',
      contentChecksum: 'sha-content',
      manifest: {
        schema_version: '1.0',
        source: 'synthetic-v1',
        retrieved_at: '2026-09-12T10:00:00Z',
        coverage: { start_date: '2024-01-01', end_date: '2024-01-10' },
        license_note: 'Synthetic license',
        classification: 'SYNTHETIC' as const,
        price_convention: 'RAW' as const,
        calendar_completeness: 'Full',
        action_completeness: 'Full',
        known_limitations: 'Test fixture only',
        availability_assumptions: 'End of day',
      },
      validationFindings: [],
    };

    const mockListings = [
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

    const mockHistory = {
      datasetId: 'ds-1',
      listingId: 'listing-1',
      symbol: 'SYNA',
      requestedStart: '2024-01-01',
      requestedEnd: '2024-01-10',
      availableStart: '2024-01-02',
      availableEnd: '2024-01-10',
      asOfCutoff: null,
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
      actions: [
        {
          actionId: 'act-1',
          listingId: 'listing-1',
          actionType: 'SPLIT' as const,
          effectiveDate: '2024-01-05',
          availableAt: '2024-01-04T18:00:00Z',
          splitRatio: '2:1',
          distributionAmount: null,
          distributionCurrency: null,
          paymentDate: null,
          paymentInstant: null,
        },
      ],
      coverageNotes: null,
    };

    const mockDataService = {
      datasets$: of(mockDatasets),
      selectedDataset$: of(mockDetail),
      listings$: of(mockListings),
      selectedHistory$: of(mockHistory),
      activeJob$: of(null),
      loading$: of(false),
      error$: of(null),
      selectDataset: () => {},
      loadListingHistory: () => {},
      uploadBundle: () => of({}),
      clearActiveJob: () => {},
    };

    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        { provide: HistoricalDataService, useValue: mockDataService },
      ],
    });

    const fixture: ComponentFixture<ResearchDataComponent> = TestBed.createComponent(ResearchDataComponent);
    fixture.detectChanges();

    const el = fixture.nativeElement as HTMLElement;
    expect(el.textContent).toContain('HISTORICAL DATA INSPECTION');
    expect(el.textContent).toContain('Synthetic Alpha Dataset');
    expect(el.textContent).toContain('SYNTHETIC');
    expect(el.textContent).toContain('SYNA');
    expect(el.textContent).toContain('104.00');
    expect(el.textContent).toContain('act-1');
    expect(el.textContent).toContain('2:1');
  });

  it('ResearchBacktestsComponent should render run history and summary', () => {
    const selectedRun = new BehaviorSubject<unknown>(null);
    const mockRun = {
      id: 'run-test-123',
      ownerId: 'default',
      idempotencyKey: 'k-1',
      canonicalHash: 'h-1',
      strategyId: 'ETF_BUY_HOLD_V1',
      strategyVersion: '1.0.0',
      datasetId: 'dataset-alpha',
      candidateListingId: 'listing-alpha',
      benchmarkListingId: 'listing-alpha',
      initialCash: '1000.00',
      currency: 'EUR',
      evaluationCutoff: '2024-02-07T23:59:59Z',
      requestedStartDate: '2024-01-31',
      requestedEndDate: '2024-02-07',
      commissionPerFill: '1.00',
      spreadBps: '0',
      slippageBps: '0',
      status: 'COMPLETED' as const,
      progressPct: 100,
      failureReason: null,
      completedAt: '2026-09-12T00:00:00Z',
      createdAt: '2026-09-12T00:00:00Z',
      updatedAt: '2026-09-12T00:00:00Z',
      candidateSummary: {
        initialEquity: '1000.00',
        finalEquity: '1018.00',
        cumulativeReturn: 0.018,
        cagr: null,
        maxDrawdown: -0.001,
        peakDate: '2024-02-01',
        troughDate: '2024-02-01',
        recoveryDate: '2024-02-07',
        drawdownDurationDays: 6,
        isRecovered: true,
        annualizedVolatility: 0.12,
        turnoverRatio: 0.998,
        fillCount: 2,
        totalCommissions: '2.00',
        endingCash: '18.00',
        endingHoldingsValue: '1000.00',
        endingReceivables: '0.00',
        endingUnits: '20.00000000',
        endingCostBasis: '1000.00',
        annualReturns: [],
        benchmarkDifference: 0.0,
      },
      benchmarkSummary: null,
    };

    const mockBacktestService = {
      runs$: of([mockRun]),
      selectedRun$: selectedRun.asObservable(),
      dailyEquity$: of([]),
      equityStatus$: of({ isComplete: true, candidateLoaded: 0, candidateTotal: 0, benchmarkLoaded: 0, benchmarkTotal: 0 }),
      orders$: of([]),
      ordersTotal$: of(0),
      events$: of([]),
      eventsTotal$: of(0),
      refreshRuns: () => {},
      selectRun: () => {},
      clearSelection: () => {},
      pollRun: () => of(mockRun),
      createRun: () => of(mockRun),
      cancelRun: () => of(mockRun),
      getExportUrl: (id: string) => `/api/research/backtests/${id}/export`,
    };

    const mockDataService = {
      datasets$: of([]),
      listings$: of([]),
      refreshDatasets: () => {},
      selectDataset: () => {},
    };

    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        { provide: BacktestService, useValue: mockBacktestService },
        { provide: HistoricalDataService, useValue: mockDataService },
      ],
    });

    const fixture: ComponentFixture<ResearchBacktestsComponent> = TestBed.createComponent(ResearchBacktestsComponent);
    fixture.detectChanges();

    const el = fixture.nativeElement as HTMLElement;
    expect(el.textContent).toContain('Strategy Backtesting');
    expect(el.textContent).toContain('run-test-123');
    expect(el.textContent).toContain('ETF_BUY_HOLD_V1');
    expect(el.textContent).toContain('COMPLETED');
    expect(el.textContent).toContain('+1.80%');

    const { cagr: ignoredCagr, ...summaryWithoutCagr } = mockRun.candidateSummary;
    selectedRun.next({ ...mockRun, candidateSummary: summaryWithoutCagr });
    fixture.detectChanges();
    expect(el.textContent).toContain('N/A (< 1 yr)');
  });
});
