import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HeaderComponent } from './header/header.component';
import { WatchlistComponent } from './watchlist/watchlist.component';
import { MainChartComponent } from './main-chart/main-chart.component';
import { HeatmapComponent } from './heatmap/heatmap.component';
import { PnlChartComponent } from './pnl-chart/pnl-chart.component';
import { TradeBarComponent } from './trade-bar/trade-bar.component';
import { PositionsTableComponent } from './positions-table/positions-table.component';
import { ChatPanelComponent } from './chat-panel/chat-panel.component';

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
});
