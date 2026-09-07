import { Component, EventEmitter, Input, Output, OnChanges, SimpleChanges, ChangeDetectorRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { PriceTick, WatchlistEntry } from '../../models/market.model';

@Component({
  selector: 'app-watchlist',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <div class="watchlist-card">
      <div class="card-header">
        <div class="header-left">
          <span class="card-title">WATCHLIST</span>
          <span class="badge font-mono">{{ entries.length }}</span>
        </div>

        <form (ngSubmit)="onAddTicker()" class="add-form">
          <input
            type="text"
            [(ngModel)]="newTicker"
            name="newTicker"
            placeholder="+ TICKER"
            class="ticker-input font-mono"
            maxlength="6"
          />
          <button type="submit" class="btn-add font-mono" [disabled]="!newTicker.trim()">ADD</button>
        </form>
      </div>

      <div class="table-container">
        <table class="watchlist-table font-mono">
          <thead>
            <tr>
              <th class="text-left">SYMBOL</th>
              <th class="text-center">TREND</th>
              <th class="text-right">PRICE</th>
              <th class="text-right">CHG %</th>
              <th class="text-center">ACTION</th>
            </tr>
          </thead>
          <tbody>
            <tr
              *ngFor="let item of entries"
              [class.selected-row]="selectedTicker === item.ticker"
              (click)="onSelect(item.ticker)"
              class="ticker-row"
            >
              <td class="ticker-cell">
                <span class="symbol-text">{{ item.ticker }}</span>
              </td>

              <!-- Sparkline mini-chart -->
              <td class="sparkline-cell">
                <svg class="sparkline-svg" viewBox="0 0 70 20">
                  <polyline
                    fill="none"
                    [attr.stroke]="getSparklineColor(item.ticker)"
                    stroke-width="1.5"
                    stroke-linecap="round"
                    stroke-linejoin="round"
                    [attr.points]="getSparklinePoints(item.ticker)"
                  />
                </svg>
              </td>

              <td class="price-cell text-right" [ngClass]="flashClasses[item.ticker] || ''">
                {{ (livePrices[item.ticker]?.price ?? item.price) | currency:'USD':'symbol':'1.2-2' }}
              </td>

              <td class="change-cell text-right">
                <span
                  class="badge"
                  [ngClass]="(livePrices[item.ticker]?.changePercent ?? item.changePercent) >= 0 ? 'badge-green' : 'badge-red'"
                >
                  {{ (livePrices[item.ticker]?.changePercent ?? item.changePercent) >= 0 ? '+' : '' }}{{ (livePrices[item.ticker]?.changePercent ?? item.changePercent) | number:'1.2-2' }}%
                </span>
              </td>

              <td class="action-cell text-center" (click)="$event.stopPropagation()">
                <button
                  class="btn-remove"
                  title="Remove from watchlist"
                  (click)="onRemove(item.ticker)"
                >
                  ✕
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  `,
  styles: [`
    :host {
      display: flex;
      flex-direction: column;
      height: 100%;
      min-height: 0;
      flex: 1;
      overflow: hidden;
    }

    .watchlist-card {
      background-color: var(--bg-card);
      border: 1px solid var(--border-color);
      border-radius: 6px;
      display: flex;
      flex-direction: column;
      height: 100%;
      min-height: 0;
      flex: 1;
      overflow: hidden;
    }

    .card-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 12px 16px;
      border-bottom: 1px solid var(--border-color);
      background-color: rgba(255, 255, 255, 0.02);
    }

    .header-left {
      display: flex;
      align-items: center;
      gap: 8px;
    }

    .card-title {
      font-size: 12px;
      font-weight: 700;
      letter-spacing: 0.5px;
      color: var(--text-muted);
    }

    .badge {
      font-size: 10px;
      padding: 2px 6px;
      border-radius: 4px;
      font-weight: 600;
      background-color: var(--bg-main);
      color: var(--text-main);
    }

    .add-form {
      display: flex;
      align-items: center;
      gap: 6px;
    }

    .ticker-input {
      background-color: var(--bg-input);
      border: 1px solid var(--border-color);
      border-radius: 4px;
      padding: 4px 8px;
      color: var(--text-main);
      font-size: 11px;
      width: 80px;
      text-transform: uppercase;
      outline: none;
    }
    .ticker-input:focus {
      border-color: var(--color-primary);
    }

    .btn-add {
      background-color: var(--color-primary);
      color: white;
      border: none;
      border-radius: 4px;
      padding: 4px 8px;
      font-size: 10px;
      font-weight: 700;
      cursor: pointer;
    }
    .btn-add:disabled {
      opacity: 0.4;
      cursor: not-allowed;
    }

    .table-container {
      flex: 1;
      overflow-y: auto;
    }

    .watchlist-table {
      width: 100%;
      border-collapse: collapse;
      font-size: 12px;
    }

    th {
      position: sticky;
      top: 0;
      background-color: var(--bg-card);
      padding: 8px 12px;
      font-size: 10px;
      font-weight: 600;
      color: var(--text-dim);
      border-bottom: 1px solid var(--border-color);
      z-index: 2;
    }

    .ticker-row {
      cursor: pointer;
      border-bottom: 1px solid rgba(48, 54, 61, 0.4);
      transition: background-color 0.15s ease;
    }
    .ticker-row:hover {
      background-color: var(--bg-card-hover);
    }
    .selected-row {
      background-color: rgba(32, 157, 215, 0.12) !important;
      border-left: 3px solid var(--color-primary);
    }

    td {
      padding: 10px 12px;
      vertical-align: middle;
    }

    .symbol-text {
      font-weight: 700;
      color: var(--accent-yellow);
    }

    .sparkline-svg {
      width: 70px;
      height: 20px;
      display: block;
      margin: 0 auto;
    }

    .price-cell {
      font-weight: 600;
      transition: background-color 0.5s ease;
      border-radius: 3px;
    }

    .btn-remove {
      background: none;
      border: none;
      color: var(--text-dim);
      font-size: 12px;
      cursor: pointer;
      padding: 2px 6px;
      border-radius: 4px;
    }
    .btn-remove:hover {
      color: var(--color-red);
      background-color: rgba(239, 68, 68, 0.15);
    }

    .text-left { text-align: left; }
    .text-right { text-align: right; }
    .text-center { text-align: center; }
  `]
})
export class WatchlistComponent implements OnChanges {
  @Input() entries: WatchlistEntry[] = [];
  @Input() livePrices: Record<string, PriceTick> = {};
  @Input() priceHistory: Record<string, number[]> = {};
  @Input() selectedTicker: string = 'AAPL';

  @Output() readonly selectTicker = new EventEmitter<string>();
  @Output() readonly addTicker = new EventEmitter<string>();
  @Output() readonly removeTicker = new EventEmitter<string>();

  public newTicker: string = '';
  public flashClasses: Record<string, string> = {};
  private prevPrices: Record<string, number> = {};

  constructor(private readonly cdr: ChangeDetectorRef) {}

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['livePrices'] && this.livePrices) {
      for (const [ticker, tick] of Object.entries(this.livePrices)) {
        const prev = this.prevPrices[ticker];
        if (prev !== undefined && prev !== tick.price) {
          if (tick.price > prev) {
            this.flashClasses[ticker] = 'flash-up';
          } else if (tick.price < prev) {
            this.flashClasses[ticker] = 'flash-down';
          }
          setTimeout(() => {
            delete this.flashClasses[ticker];
            this.cdr.markForCheck();
          }, 600);
        }
        this.prevPrices[ticker] = tick.price;
      }
    }
  }

  public onSelect(ticker: string): void {
    this.selectTicker.emit(ticker);
  }

  public onAddTicker(): void {
    if (this.newTicker.trim()) {
      this.addTicker.emit(this.newTicker.trim().toUpperCase());
      this.newTicker = '';
    }
  }

  public onRemove(ticker: string): void {
    this.removeTicker.emit(ticker);
  }

  public getSparklinePoints(ticker: string): string {
    const history = this.priceHistory[ticker];
    if (!history || history.length < 2) {
      return '0,10 70,10';
    }

    const min = Math.min(...history);
    const max = Math.max(...history);
    const range = max - min || 1;
    const width = 70;
    const height = 18;
    const padding = 2;

    return history
      .map((val, idx) => {
        const x = (idx / (history.length - 1)) * width;
        const normalized = (val - min) / range;
        const y = height - normalized * (height - padding * 2) - padding;
        return `${x.toFixed(1)},${y.toFixed(1)}`;
      })
      .join(' ');
  }

  public getSparklineColor(ticker: string): string {
    const history = this.priceHistory[ticker];
    if (!history || history.length < 2) {
      return '#8b949e';
    }
    const first = history[0];
    const last = history.at(-1) ?? first;
    return last >= first ? '#10b981' : '#ef4444';
  }
}
