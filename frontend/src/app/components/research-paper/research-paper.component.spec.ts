import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ResearchPaperComponent } from './research-paper.component';
import { ResearchService } from '../../services/research.service';
import { BehaviorSubject, Subject, of } from 'rxjs';
import { provideRouter } from '@angular/router';
import {
  ResearchPortfolioSummary,
  ResearchPortfolioDetail,
  PaperProposal,
  PaperValuation,
  PaperModeHistory,
  AssistantChatResponse,
  PaperPageState,
} from '../../models/research.model';

describe('ResearchPaperComponent', () => {
  let fixture: ComponentFixture<ResearchPaperComponent>;
  let comp: ResearchPaperComponent;

  const mockPortfolios: ResearchPortfolioSummary[] = [
    {
      id: 'port-paper-1',
      ownerId: 'default',
      name: 'Alpha EUR Paper',
      mode: 'PAPER',
      baseCurrency: 'EUR',
      cashBalance: '10000.00',
      revision: 1,
      createdAt: '2026-09-15T08:00:00Z',
      paperStartedAt: '2026-09-15T08:00:00Z',
      strategyTracking: 'ACTIVE',
      positionCount: 1,
    },
  ];

  const mockDetail: ResearchPortfolioDetail = {
    ...mockPortfolios[0],
    valuationStatus: 'AVAILABLE',
    marketValue: '990.00',
    unrealizedPnl: '-10.00',
    positions: [
      {
        listingId: 'listing-iwda',
        ticker: 'IWDA.AS',
        quantity: '10',
        totalAcquisitionCost: '1000.00',
        averageCost: '100.00',
        updatedAt: '2026-09-15T08:00:00Z',
      },
    ],
    activeSegment: {
      id: 'seg-1',
      portfolioId: 'port-paper-1',
      strategyId: 'ETF_BUY_HOLD_V1',
      strategyVersion: '1.0.0',
      universeId: 'uni-1',
      benchmarkListingId: 'listing-1',
      costPolicy: {
        commissionPerFill: '1.00',
        bidAskSpreadBps: '5',
        slippageBps: '2',
      },
      approvalMode: 'MANUAL',
      status: 'ACTIVE',
      initialEquity: '10000.00',
      openingObservationInstant: '2026-09-15T08:00:00Z',
      adoptedDatasetId: null,
      adoptedAt: null,
      createdAt: '2026-09-15T08:00:00Z',
    },
  };

  const mockProposals: PaperProposal[] = [
    {
      id: 'prop-1',
      portfolioId: 'port-paper-1',
      cycleId: 'cycle-1',
      strategyId: 'ETF_BUY_HOLD_V1',
      strategyVersion: '1.0.0',
      datasetId: 'ds-1',
      datasetChecksum: 'chk-1',
      calendarId: 'XAMS',
      calendarVersion: '1.0',
      evaluationSessionDate: '2026-09-15',
      inputCutoffInstant: '2026-09-15T08:00:00Z',
      evaluationInstant: '2026-09-15T08:30:00Z',
      scheduledOpenSessionDate: '2026-09-15',
      scheduledOpenInstant: '2026-09-15T09:00:00Z',
      reasonCode: 'DAILY_REBALANCE',
      portfolioStateVersion: 1,
      status: 'PROPOSED',
      acceptedAt: null,
      rejectedAt: null,
      rejectionReason: null,
      supersedingProposalId: null,
      reinvestmentReceivableId: null,
      createdAt: '2026-09-15T09:00:00Z',
      items: [
        {
          id: 'item-1',
          proposalId: 'prop-1',
          listingId: 'listing-iwda',
          rank: 1,
          targetWeight: '0.10',
          cutoffEstimatedUnits: '10',
          score: '1.0',
          reasonCode: 'TARGET_ALLOCATION',
          reasonDescription: 'Initial allocation',
          rawPriceReference: '100.00',
        },
      ],
      observations: [],
    },
  ];

  let portfoliosSubject: BehaviorSubject<ResearchPortfolioSummary[]>;
  let selectedPortfolioSubject: BehaviorSubject<ResearchPortfolioDetail | null>;
  let proposalsSubject: BehaviorSubject<PaperProposal[]>;
  let valuationsSubject: BehaviorSubject<PaperValuation[]>;
  let modeHistorySubject: BehaviorSubject<PaperModeHistory[]>;
  let loadingSubject: BehaviorSubject<boolean>;
  let errorSubject: BehaviorSubject<string | null>;
  let paperPagesSubject: BehaviorSubject<PaperPageState>;

  let mockResearchService: Partial<ResearchService>;

  beforeEach(() => {
    portfoliosSubject = new BehaviorSubject<ResearchPortfolioSummary[]>(mockPortfolios);
    selectedPortfolioSubject = new BehaviorSubject<ResearchPortfolioDetail | null>(mockDetail);
    proposalsSubject = new BehaviorSubject<PaperProposal[]>(mockProposals);
    valuationsSubject = new BehaviorSubject<PaperValuation[]>([]);
    modeHistorySubject = new BehaviorSubject<PaperModeHistory[]>([]);
    loadingSubject = new BehaviorSubject<boolean>(false);
    errorSubject = new BehaviorSubject<string | null>(null);
    paperPagesSubject = new BehaviorSubject<PaperPageState>({
      proposals: { total: 0, limit: 50, offset: 0, isComplete: true },
      valuations: { total: 0, limit: 200, offset: 0, isComplete: true },
      intents: { total: 0, limit: 100, offset: 0, isComplete: true },
      receivables: { total: 0, limit: 100, offset: 0, isComplete: true },
      actions: { total: 0, limit: 100, offset: 0, isComplete: true },
      adoptions: { total: 0, limit: 100, offset: 0, isComplete: true },
    });

    mockResearchService = {
      portfolios$: portfoliosSubject.asObservable(),
      selectedPortfolio$: selectedPortfolioSubject.asObservable(),
      proposals$: proposalsSubject.asObservable(),
      valuations$: valuationsSubject.asObservable(),
      modeHistory$: modeHistorySubject.asObservable(),
      intents$: of([]),
      receivables$: of([]),
      actions$: of([]),
      adoptions$: of([]),
      paperPages$: paperPagesSubject.asObservable(),
      loading$: loadingSubject.asObservable(),
      error$: errorSubject.asObservable(),
      selectPortfolio: vi.fn(),
      refreshPortfolios: vi.fn(),
      loadProposals: vi.fn(),
      loadValuations: vi.fn(),
      loadIntents: vi.fn(),
      loadReceivables: vi.fn(),
      loadActions: vi.fn(),
      loadAdoptions: vi.fn(),
      loadModeHistory: vi.fn(),
      acceptProposal: vi.fn().mockReturnValue(of({ message: 'Proposal accepted' })),
      rejectProposal: vi.fn().mockReturnValue(of({ message: 'Proposal rejected' })),
      changeApprovalMode: vi.fn().mockReturnValue(of(mockDetail)),
      sendAssistantChat: vi.fn(),
    };

    TestBed.configureTestingModule({
      imports: [ResearchPaperComponent],
      providers: [{ provide: ResearchService, useValue: mockResearchService }, provideRouter([])],
    });

    fixture = TestBed.createComponent(ResearchPaperComponent);
    comp = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should render paper portfolio details and active segment info', () => {
    const el = fixture.nativeElement as HTMLElement;
    expect(el.textContent).toContain('Alpha EUR Paper');
    expect(el.textContent).toContain('ETF_BUY_HOLD_V1');
    expect(el.textContent).toContain('MANUAL');
    expect(el.textContent).toContain('10000.00');
  });

  it('renders asynchronous portfolio and assistant emissions without another user event', async () => {
    selectedPortfolioSubject.next(null);
    await fixture.whenStable();
    selectedPortfolioSubject.next(mockDetail);
    await fixture.whenStable();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Alpha EUR Paper');

    comp.activeTab = 'assistant';
    fixture.detectChanges();
    const response = new Subject<AssistantChatResponse>();
    vi.mocked(mockResearchService.sendAssistantChat!).mockReturnValue(response.asObservable());
    comp.assistantMessage = 'Show evidence';
    comp.sendAssistantQuery();
    response.next({ message: 'Grounded response', factCards: [], evidenceReferences: [] });
    response.complete();
    await fixture.whenStable();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Grounded response');
    expect((fixture.nativeElement as HTMLElement).textContent).not.toContain('Analyzing...');
  });

  it('keeps incomplete valuation equity and chart points unavailable', async () => {
    const incomplete: PaperValuation = {
      id: 'val-missing', portfolioId: 'port-paper-1', sessionDate: '2026-09-15',
      observationKind: 'SESSION_CLOSE', observationInstant: '2026-09-15T17:30:00Z',
      cashBalance: '321.00', positionsMarketValue: '0.00', receivablesValue: '0.00',
      totalEquity: null, cumulativeReturn: null, highWaterMark: null, drawdown: null,
      dataReadinessStatus: 'PARTIAL_STALE', isComplete: false,
      lastSupportedObservationInstant: null, missingRequirementsDetail: 'Missing close for listing-iwda',
      adoptedDatasetId: 'ds-1', adoptedDatasetChecksum: 'chk-1',
    };
    comp.activeTab = 'valuations';
    valuationsSubject.next([incomplete]);
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    expect(comp.valuationChartPoints).toBe('');
    expect(el.querySelector('.valuations-table tbody tr td:nth-child(4)')?.textContent?.trim()).toBe('UNAVAILABLE');
    expect(el.textContent).toContain('Missing close for listing-iwda');
  });

  it('should display proposals and handle proposal acceptance', () => {
    comp.activeTab = 'proposals';
    fixture.detectChanges();

    const el = fixture.nativeElement as HTMLElement;
    expect(el.textContent).toContain('PROPOSED');
    expect(el.textContent).toContain('listing-iwda');

    comp.acceptProposal('prop-1');
    expect(mockResearchService.acceptProposal).toHaveBeenCalledWith('port-paper-1', 'prop-1');
  });

  it('should allow toggling between tabs', () => {
    const positionsTabBtn = fixture.nativeElement.querySelector('#tab-positions') as HTMLButtonElement;
    positionsTabBtn.click();
    fixture.detectChanges();

    let el = fixture.nativeElement as HTMLElement;
    expect(el.textContent).toContain('IWDA.AS');
    expect(el.textContent).toContain('10');

    const assistantTabBtn = fixture.nativeElement.querySelector('#tab-assistant') as HTMLButtonElement;
    assistantTabBtn.click();
    fixture.detectChanges();

    el = fixture.nativeElement as HTMLElement;
    expect(comp.activeTab).toBe('assistant');
    expect(el.textContent).toContain('Grounded Research Assistant');
    expect(el.textContent).toContain('Ask Assistant');
  });

  it('loads the next bounded audit page and shows its range', async () => {
    paperPagesSubject.next({
      ...paperPagesSubject.value,
      proposals: { total: 75, limit: 50, offset: 0, isComplete: false },
    });
    comp.activeTab = 'proposals';
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.textContent).toContain('1–50 of 75');

    comp.changePage('proposals', 1);
    expect(mockResearchService.loadProposals).toHaveBeenCalledWith('port-paper-1', 50, 50);
  });

  it('should handle grounded assistant interaction', async () => {
    comp.activeTab = 'assistant';
    fixture.detectChanges();

    const mockAssistantResponse: AssistantChatResponse = {
      message: 'Portfolio holds 10 shares of IWDA.AS valued at EUR 990.00.',
      factCards: [
        {
          title: 'Current Position',
          value: '10 IWDA.AS',
          description: 'Holding 10 shares IWDA.AS with unrealized PnL of -10.00 EUR.',
          category: 'POSITION',
        },
      ],
      evidenceReferences: [
        {
          type: 'POSITION',
          id: 'IWDA.AS',
          observationInstant: '2026-09-15T09:00:00Z',
          description: 'Position snapshot',
        },
      ],
    };

    vi.mocked(mockResearchService.sendAssistantChat!).mockReturnValue(of(mockAssistantResponse));

    comp.assistantMessage = 'What are my positions?';
    comp.sendAssistantQuery();

    expect(mockResearchService.sendAssistantChat).toHaveBeenCalledWith(
      expect.objectContaining({
        message: 'What are my positions?',
        context: {
          contextType: 'PORTFOLIO',
          contextId: 'port-paper-1',
        },
      })
    );
    expect(comp.assistantResponse).toEqual(mockAssistantResponse);

    await fixture.whenStable();
    fixture.detectChanges();

    const el = fixture.nativeElement as HTMLElement;
    expect(el.textContent).toContain('Portfolio holds 10 shares of IWDA.AS');
    expect(el.textContent).toContain('Current Position');
    expect(el.textContent).toContain('Holding 10 shares IWDA.AS');
  });
});
