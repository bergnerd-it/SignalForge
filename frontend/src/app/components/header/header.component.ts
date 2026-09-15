import { Component, Input, Output, EventEmitter } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Portfolio } from '../../models/market.model';
import { ConnectionStatus } from '../../services/price-stream.service';

@Component({
  selector: 'app-header',
  standalone: true,
  imports: [CommonModule],
  template: `
    <header class="header-container">
      <div class="brand-section">
        <div class="logo-box">
          <span class="logo-icon">⚡</span>
          <div class="logo-text">
            <span class="title">SignalForge</span>
            <span class="subtitle">AI Trading Workstation</span>
          </div>
        </div>

        <nav class="nav-tabs font-mono" aria-label="Workstation Navigation">
          <button
            class="nav-tab-btn"
            [class.active]="currentView === 'demo'"
            (click)="selectView('demo')"
            id="tab-demo"
          >
            DEMO WORKSTATION
          </button>
          <button
            class="nav-tab-btn"
            [class.active]="currentView === 'research'"
            (click)="selectView('research')"
            id="tab-research"
          >
            RESEARCH PORTFOLIOS
          </button>
          <button
            class="nav-tab-btn"
            [class.active]="currentView === 'research-data'"
            (click)="selectView('research-data')"
            id="tab-research-data"
          >
            DATA INSPECTION
          </button>
          <button
            class="nav-tab-btn"
            [class.active]="currentView === 'research-backtests'"
            (click)="selectView('research-backtests')"
            id="tab-research-backtests"
          >
            BACKTESTS
          </button>
          <button
            class="nav-tab-btn"
            [class.active]="currentView === 'research-strategies'"
            (click)="selectView('research-strategies')"
            id="tab-research-strategies"
          >
            STRATEGIES
          </button>
          <button
            class="nav-tab-btn"
            [class.active]="currentView === 'research-compare'"
            (click)="selectView('research-compare')"
            id="tab-research-compare"
          >
            COMPARE
          </button>
          <button
            class="nav-tab-btn"
            [class.active]="currentView === 'research-portfolios'"
            (click)="selectView('research-portfolios')"
            id="tab-research-portfolios"
          >
            PAPER TRACKING
          </button>
        </nav>
      </div>

      <div class="metrics-section" *ngIf="portfolio && currentView === 'demo'">
        <div class="metric-card">
          <span class="metric-label">PORTFOLIO VALUE</span>
          <span class="metric-value font-mono">{{ portfolio.totalPortfolioValue === null ? 'UNAVAILABLE' : (portfolio.totalPortfolioValue | currency:'USD':'symbol':'1.2-2') }}</span>
        </div>

        <div class="metric-card">
          <span class="metric-label">CASH BALANCE</span>
          <span class="metric-value font-mono text-muted">{{ portfolio.cashBalance | currency:'USD':'symbol':'1.2-2' }}</span>
        </div>

        <div class="metric-card">
          <span class="metric-label">UNREALIZED P&L</span>
          <span class="metric-value font-mono" [ngClass]="(portfolio.unrealizedPnl ?? 0) >= 0 ? 'text-green' : 'text-red'">
            <ng-container *ngIf="portfolio.unrealizedPnl !== null; else unavailablePnl">
              {{ (portfolio.unrealizedPnl ?? 0) >= 0 ? '+' : '' }}{{ portfolio.unrealizedPnl | currency:'USD':'symbol':'1.2-2' }}
              <span class="pnl-percent">({{ (portfolio.unrealizedPnlPercent ?? 0) >= 0 ? '+' : '' }}{{ portfolio.unrealizedPnlPercent | number:'1.2-2' }}%)</span>
            </ng-container>
            <ng-template #unavailablePnl>UNAVAILABLE</ng-template>
          </span>
        </div>
      </div>

      <div class="status-section">
        <div class="status-indicator">
          <span class="pulse-dot" [ngClass]="{
            'pulse-green': status === 'connected',
            'pulse-yellow': status === 'reconnecting',
            'pulse-red': status === 'disconnected'
          }"></span>
          <span class="status-label font-mono">{{ status | uppercase }}</span>
        </div>
      </div>
    </header>
  `,
  styles: [`
    .header-container {
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 10px 20px;
      background-color: var(--bg-card);
      border-bottom: 1px solid var(--border-color);
      height: 64px;
    }

    .brand-section {
      display: flex;
      align-items: center;
      gap: 32px;
    }

    .logo-box {
      display: flex;
      align-items: center;
      gap: 10px;
    }

    .logo-icon {
      font-size: 24px;
      color: var(--accent-yellow);
    }

    .logo-text {
      display: flex;
      flex-direction: column;
    }

    .title {
      font-weight: 700;
      font-size: 18px;
      letter-spacing: 0.5px;
      color: var(--text-main);
    }

    .subtitle {
      font-size: 11px;
      color: var(--color-primary);
      text-transform: uppercase;
      font-weight: 600;
      letter-spacing: 0.5px;
    }

    .nav-tabs {
      display: flex;
      gap: 8px;
      background: var(--bg-main);
      padding: 3px;
      border-radius: 6px;
      border: 1px solid var(--border-color);
    }

    .nav-tab-btn {
      background: none;
      border: none;
      color: var(--text-muted);
      font-size: 11px;
      font-weight: 700;
      padding: 6px 14px;
      border-radius: 4px;
      cursor: pointer;
      letter-spacing: 0.5px;
      transition: all 0.2s;
    }

    .nav-tab-btn:hover {
      color: var(--text-main);
    }

    .nav-tab-btn.active {
      background: #0284c7;
      color: #ffffff;
    }

    .metrics-section {
      display: flex;
      align-items: center;
      gap: 24px;
    }

    .metric-card {
      display: flex;
      flex-direction: column;
    }

    .metric-label {
      font-size: 10px;
      color: var(--text-muted);
      font-weight: 600;
      letter-spacing: 0.5px;
      margin-bottom: 2px;
    }

    .metric-value {
      font-size: 16px;
      font-weight: 700;
      color: var(--text-main);
    }

    .pnl-percent {
      font-size: 13px;
      margin-left: 4px;
      font-weight: 600;
    }

    .text-green { color: var(--color-green); }
    .text-red { color: var(--color-red); }
    .text-muted { color: var(--text-muted); }

    .status-section {
      display: flex;
      align-items: center;
    }

    .status-indicator {
      display: flex;
      align-items: center;
      gap: 8px;
      padding: 6px 12px;
      background-color: var(--bg-main);
      border: 1px solid var(--border-color);
      border-radius: 20px;
    }

    .status-label {
      font-size: 11px;
      font-weight: 600;
      color: var(--text-muted);
    }
  `]
})
export class HeaderComponent {
  @Input() portfolio: Portfolio | null = null;
  @Input() status: ConnectionStatus = 'disconnected';
  @Input() currentView: 'demo' | 'research' | 'research-data' | 'research-backtests' | 'research-strategies' | 'research-compare' | 'research-portfolios' = 'demo';
  @Output() viewChange = new EventEmitter<'demo' | 'research' | 'research-data' | 'research-backtests' | 'research-strategies' | 'research-compare' | 'research-portfolios'>();

  selectView(view: 'demo' | 'research' | 'research-data' | 'research-backtests' | 'research-strategies' | 'research-compare' | 'research-portfolios'): void {
    this.viewChange.emit(view);
  }
}
