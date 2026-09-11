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
