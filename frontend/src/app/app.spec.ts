import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { of, throwError } from 'rxjs';

import { App } from './app';
import { PortfolioService } from './services/portfolio.service';
import { WatchlistService } from './services/watchlist.service';
import { ChatService } from './services/chat.service';
import { PriceStreamService } from './services/price-stream.service';
import { Portfolio, WatchlistEntry } from './models/market.model';

describe('App Component', () => {
  let mockPortfolioService: any;
  let mockWatchlistService: any;
  let mockChatService: any;
  let mockPriceStreamService: any;

  const samplePortfolio: Portfolio = {
    userId: 'default',
    cashBalance: 10000.0,
    totalPositionValue: 0.0,
    totalPortfolioValue: 10000.0,
    unrealizedPnl: 0.0,
    unrealizedPnlPercent: 0.0,
    positions: [],
  };

  const sampleWatchlist: WatchlistEntry[] = [
    {
      id: 'w1',
      ticker: 'AAPL',
      price: 190.0,
      previousPrice: 189.0,
      change: 1.0,
      changePercent: 0.53,
      direction: 'up',
      addedAt: new Date().toISOString(),
    },
  ];

  beforeEach(async () => {
    mockPortfolioService = {
      portfolio$: of(samplePortfolio),
      getPortfolio: () => of(samplePortfolio),
      getHistory: () => of([]),
      executeTrade: () => of({}),
      refreshPortfolio: () => {},
    };

    mockWatchlistService = {
      watchlist$: of(sampleWatchlist),
      getWatchlist: () => of(sampleWatchlist),
      addTicker: (ticker: string) => of({ ticker }),
      removeTicker: (ticker: string) => of({ ticker, removed: true }),
      refreshWatchlist: () => {},
    };

    mockChatService = {
      messages$: of([]),
      isThinking$: of(false),
      sendMessage: () => of({ message: 'OK', actions: [], createdAt: '' }),
      clearHistory: () => of(undefined),
      loadHistory: () => {},
    };

    mockPriceStreamService = {
      prices$: of({}),
      history$: of({}),
      status$: of('connected'),
    };

    await TestBed.configureTestingModule({
      imports: [App],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: PortfolioService, useValue: mockPortfolioService },
        { provide: WatchlistService, useValue: mockWatchlistService },
        { provide: ChatService, useValue: mockChatService },
        { provide: PriceStreamService, useValue: mockPriceStreamService },
      ],
    }).compileComponents();
  });

  it('should create the App component and initialize trading workstation', () => {
    const fixture = TestBed.createComponent(App);
    const app = fixture.componentInstance;
    expect(app).toBeTruthy();
    expect(app.selectedTicker).toBe('AAPL');
  });

  it('should render workstation header and watchlist', () => {
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('app-header')).toBeTruthy();
    expect(compiled.querySelector('app-watchlist')).toBeTruthy();
    expect(compiled.querySelector('app-main-chart')).toBeTruthy();
    expect(compiled.querySelector('app-chat-panel')).toBeTruthy();
  });

  it('should allow changing active selected ticker', () => {
    const fixture = TestBed.createComponent(App);
    const app = fixture.componentInstance;
    app.onSelectTicker('NVDA');
    expect(app.selectedTicker).toBe('NVDA');
  });

  it('should execute trade and update trading status and toast', () => {
    const fixture = TestBed.createComponent(App);
    const app = fixture.componentInstance;
    fixture.detectChanges();

    mockPortfolioService.executeTrade = () =>
      of({
        tradeId: 't1',
        ticker: 'NVDA',
        side: 'buy',
        quantity: 10,
        price: 120,
        totalCost: 1200,
        executedAt: new Date().toISOString(),
        updatedPortfolio: {
          ...samplePortfolio,
          cashBalance: 8800,
          totalPositionValue: 1200,
        },
      });

    app.onExecuteTrade({ ticker: 'NVDA', quantity: 10, side: 'buy' });
    expect(app.isTrading).toBe(false);
    expect(app.toastMessage).toContain('Executed BUY 10 NVDA');
  });

  it('should clear chat history and show toast', () => {
    const fixture = TestBed.createComponent(App);
    const app = fixture.componentInstance;
    fixture.detectChanges();

    app.onClearChatHistory();
    expect(app.toastMessage).toBe('Chat history cleared');
  });

  it('should display clear failure message on trade error from backend', () => {
    const fixture = TestBed.createComponent(App);
    const app = fixture.componentInstance;
    fixture.detectChanges();

    mockPortfolioService.executeTrade = () =>
      throwError(() => ({
        error: { message: 'Insufficient funds: required $6512.60, available $5292.85' },
        message: 'Http failure response for http://localhost:4200/api/portfolio/trade: 400 Bad Request',
        status: 400,
      }));

    app.onExecuteTrade({ ticker: 'NFLX', quantity: 10, side: 'buy' });
    expect(app.isTrading).toBe(false);
    expect(app.toastMessage).toBe('Insufficient funds: required $6512.60, available $5292.85');
    expect(app.toastType).toBe('error');
  });

  it('should fallback gracefully when trade error has no backend message', () => {
    const fixture = TestBed.createComponent(App);
    const app = fixture.componentInstance;
    fixture.detectChanges();

    mockPortfolioService.executeTrade = () =>
      throwError(() => ({
        error: {},
        message: 'Http failure response for http://localhost:4200/api/portfolio/trade: 500 Internal Server Error',
        status: 500,
      }));

    app.onExecuteTrade({ ticker: 'NFLX', quantity: 10, side: 'buy' });
    expect(app.isTrading).toBe(false);
    expect(app.toastMessage).toBe('Trade execution failed');
    expect(app.toastType).toBe('error');
  });
});
