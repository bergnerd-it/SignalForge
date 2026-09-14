import { Component, OnInit, OnDestroy, ChangeDetectorRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Subscription } from 'rxjs';

import { HeaderComponent } from './components/header/header.component';
import { WatchlistComponent } from './components/watchlist/watchlist.component';
import { MainChartComponent } from './components/main-chart/main-chart.component';
import { HeatmapComponent } from './components/heatmap/heatmap.component';
import { PnlChartComponent } from './components/pnl-chart/pnl-chart.component';
import { PositionsTableComponent } from './components/positions-table/positions-table.component';
import { TradeBarComponent } from './components/trade-bar/trade-bar.component';
import { ChatPanelComponent } from './components/chat-panel/chat-panel.component';

import { PriceStreamService, ConnectionStatus } from './services/price-stream.service';
import { PortfolioService } from './services/portfolio.service';
import { WatchlistService } from './services/watchlist.service';
import { ChatService } from './services/chat.service';

import {
  Portfolio,
  PortfolioSnapshot,
  Position,
  PriceTick,
  TradeRequest,
  WatchlistEntry,
  ChatMessage,
} from './models/market.model';

import { ResearchComponent } from './components/research/research.component';
import { ResearchDataComponent } from './components/research-data/research-data.component';
import { ResearchBacktestsComponent } from './components/research-backtests/research-backtests.component';
import { ResearchStrategiesComponent } from './components/research-strategies/research-strategies.component';
import { ResearchCompareComponent } from './components/research-compare/research-compare.component';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [
    CommonModule,
    HeaderComponent,
    WatchlistComponent,
    MainChartComponent,
    HeatmapComponent,
    PnlChartComponent,
    PositionsTableComponent,
    TradeBarComponent,
    ChatPanelComponent,
    ResearchComponent,
    ResearchDataComponent,
    ResearchBacktestsComponent,
    ResearchStrategiesComponent,
    ResearchCompareComponent,
  ],
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App implements OnInit, OnDestroy {
  public currentView: 'demo' | 'research' | 'research-data' | 'research-backtests' | 'research-strategies' | 'research-compare' = 'demo';
  public portfolio: Portfolio | null = null;
  public snapshots: PortfolioSnapshot[] = [];
  public watchlist: WatchlistEntry[] = [];
  public livePrices: Record<string, PriceTick> = {};
  public priceHistory: Record<string, number[]> = {};
  public connectionStatus: ConnectionStatus = 'disconnected';
  public chatMessages: ChatMessage[] = [];
  public isThinking: boolean = false;
  public isTrading: boolean = false;

  public selectedTicker: string = 'AAPL';
  public toastMessage: string | null = null;
  public toastType: 'success' | 'error' = 'success';
  private toastTimeout: any = null;

  private subscriptions: Subscription = new Subscription();

  constructor(
    private readonly priceStreamService: PriceStreamService,
    private readonly portfolioService: PortfolioService,
    private readonly watchlistService: WatchlistService,
    private readonly chatService: ChatService,
    private readonly cdr: ChangeDetectorRef
  ) {}

  ngOnInit(): void {
    if (typeof window !== 'undefined') {
      if (window.location.pathname.startsWith('/research/strategies')) {
        this.currentView = 'research-strategies';
      } else if (window.location.pathname.startsWith('/research/compare')) {
        this.currentView = 'research-compare';
      } else if (window.location.pathname.startsWith('/research/backtests')) {
        this.currentView = 'research-backtests';
      } else if (window.location.pathname.startsWith('/research/data')) {
        this.currentView = 'research-data';
      } else if (window.location.pathname.startsWith('/research')) {
        this.currentView = 'research';
      } else {
        this.currentView = 'demo';
      }
      window.addEventListener('popstate', this.handlePopState);
    }

    // 1. Subscribe to Live Price Stream
    this.subscriptions.add(
      this.priceStreamService.prices$.subscribe((prices) => {
        this.livePrices = prices;
        this.cdr.markForCheck();
      })
    );

    this.subscriptions.add(
      this.priceStreamService.history$.subscribe((history) => {
        this.priceHistory = history;
        this.cdr.markForCheck();
      })
    );

    this.subscriptions.add(
      this.priceStreamService.status$.subscribe((status) => {
        this.connectionStatus = status;
        this.cdr.markForCheck();
      })
    );

    // 2. Subscribe to Portfolio State
    this.subscriptions.add(
      this.portfolioService.portfolio$.subscribe((portfolio) => {
        this.portfolio = portfolio;
        this.cdr.markForCheck();
      })
    );

    // Load P&L snapshots
    this.loadHistorySnapshots();

    // 3. Subscribe to Watchlist State
    this.subscriptions.add(
      this.watchlistService.watchlist$.subscribe((watchlist) => {
        this.watchlist = watchlist;
        if (watchlist.length > 0 && !this.selectedTicker) {
          this.selectedTicker = watchlist[0].ticker;
        }
        this.cdr.markForCheck();
      })
    );

    // 4. Subscribe to Chat State
    this.subscriptions.add(
      this.chatService.messages$.subscribe((messages) => {
        this.chatMessages = messages;
        this.cdr.markForCheck();
      })
    );

    this.subscriptions.add(
      this.chatService.isThinking$.subscribe((thinking) => {
        this.isThinking = thinking;
        this.cdr.markForCheck();
      })
    );
  }

  private loadHistorySnapshots(): void {
    this.portfolioService.getHistory().subscribe({
      next: (snaps) => {
        this.snapshots = snaps;
        this.cdr.markForCheck();
      },
      error: (err) => console.error('Failed to load snapshots:', err),
    });
  }

  public get currentSelectedTick(): PriceTick | null {
    if (!this.selectedTicker) return null;
    return this.livePrices[this.selectedTicker] ?? null;
  }

  public get currentSelectedHistory(): number[] {
    return this.priceHistory[this.selectedTicker] || [];
  }

  public onSelectTicker(ticker: string): void {
    this.selectedTicker = ticker.toUpperCase();
    this.cdr.markForCheck();
  }

  public onAddWatchlistTicker(ticker: string): void {
    this.watchlistService.addTicker(ticker).subscribe({
      next: (entry) => {
        this.showToast(`Added ${entry.ticker} to watchlist`, 'success');
        this.selectedTicker = entry.ticker;
        this.cdr.markForCheck();
      },
      error: (err) => {
        const errorDetail = this.extractErrorMessage(err, 'Failed to add ticker');
        this.showToast(errorDetail.startsWith('Failed') ? errorDetail : `Failed to add ticker: ${errorDetail}`, 'error');
        this.cdr.markForCheck();
      },
    });
  }

  public onRemoveWatchlistTicker(ticker: string): void {
    this.watchlistService.removeTicker(ticker).subscribe({
      next: () => {
        this.showToast(`Removed ${ticker} from watchlist`, 'success');
        this.cdr.markForCheck();
      },
      error: (err) => {
        const errorDetail = this.extractErrorMessage(err, 'Failed to remove ticker');
        this.showToast(errorDetail.startsWith('Failed') ? errorDetail : `Failed to remove ticker: ${errorDetail}`, 'error');
        this.cdr.markForCheck();
      },
    });
  }

  public onExecuteTrade(trade: TradeRequest): void {
    this.isTrading = true;
    this.cdr.markForCheck();
    this.portfolioService.executeTrade(trade).subscribe({
      next: (res) => {
        this.isTrading = false;
        this.showToast(
          `Executed ${res.side.toUpperCase()} ${res.quantity} ${res.ticker} @ $${res.price.toFixed(2)}`,
          'success'
        );
        this.loadHistorySnapshots();
        this.cdr.markForCheck();
      },
      error: (err) => {
        this.isTrading = false;
        const msg = this.extractErrorMessage(err, 'Trade execution failed');
        this.showToast(msg, 'error');
        this.cdr.markForCheck();
      },
    });
  }

  public onQuickSell(position: Position): void {
    this.onExecuteTrade({
      ticker: position.ticker,
      quantity: position.quantity,
      side: 'sell',
    });
  }

  public onSendMessage(message: string): void {
    this.chatService.sendMessage(message).subscribe({
      next: (res) => {
        if (res.actions && res.actions.length > 0) {
          this.loadHistorySnapshots();
        }
        this.cdr.markForCheck();
      },
    });
  }

  public onClearChatHistory(): void {
    this.chatService.clearHistory().subscribe({
      next: () => {
        this.showToast('Chat history cleared', 'success');
        this.cdr.markForCheck();
      },
      error: (err) => {
        const errorDetail = this.extractErrorMessage(err, 'Failed to clear chat history');
        this.showToast(errorDetail.startsWith('Failed') ? errorDetail : `Failed to clear chat history: ${errorDetail}`, 'error');
        this.cdr.markForCheck();
      },
    });
  }

  private extractErrorMessage(err: unknown, fallback: string): string {
    const errorObj = err as { error?: { message?: string; error?: string } | string; message?: string };
    if (typeof errorObj?.error === 'string' && errorObj.error.trim()) {
      return errorObj.error;
    }
    if (errorObj?.error && typeof errorObj.error === 'object') {
      if (typeof errorObj.error.message === 'string' && errorObj.error.message.trim()) {
        return errorObj.error.message;
      }
      if (typeof errorObj.error.error === 'string' && errorObj.error.error.trim()) {
        return errorObj.error.error;
      }
    }
    if (errorObj?.message && typeof errorObj.message === 'string' && !errorObj.message.startsWith('Http failure response')) {
      return errorObj.message;
    }
    return fallback;
  }

  private showToast(message: string, type: 'success' | 'error'): void {
    this.toastMessage = message;
    this.toastType = type;
    if (this.toastTimeout) {
      clearTimeout(this.toastTimeout);
    }
    this.cdr.markForCheck();
    this.toastTimeout = setTimeout(() => {
      this.toastMessage = null;
      this.cdr.markForCheck();
    }, 4000);
  }

  public onViewChange(view: 'demo' | 'research' | 'research-data' | 'research-backtests' | 'research-strategies' | 'research-compare'): void {
    this.currentView = view;
    if (typeof window !== 'undefined' && window.history) {
      const targetPath =
        view === 'research-strategies'
          ? '/research/strategies'
          : view === 'research-compare'
          ? '/research/compare'
          : view === 'research-backtests'
          ? '/research/backtests'
          : view === 'research-data'
          ? '/research/data'
          : view === 'research'
          ? '/research'
          : '/demo';
      window.history.pushState({}, '', targetPath);
    }
    this.cdr.markForCheck();
  }

  private handlePopState = (): void => {
    if (typeof window !== 'undefined') {
      if (window.location.pathname.startsWith('/research/strategies')) {
        this.currentView = 'research-strategies';
      } else if (window.location.pathname.startsWith('/research/compare')) {
        this.currentView = 'research-compare';
      } else if (window.location.pathname.startsWith('/research/backtests')) {
        this.currentView = 'research-backtests';
      } else if (window.location.pathname.startsWith('/research/data')) {
        this.currentView = 'research-data';
      } else if (window.location.pathname.startsWith('/research')) {
        this.currentView = 'research';
      } else {
        this.currentView = 'demo';
      }
      this.cdr.markForCheck();
    }
  };

  ngOnDestroy(): void {
    if (typeof window !== 'undefined') {
      window.removeEventListener('popstate', this.handlePopState);
    }
    this.subscriptions.unsubscribe();
    if (this.toastTimeout) {
      clearTimeout(this.toastTimeout);
    }
  }
}
