import { Component, EventEmitter, Input, Output, OnChanges, SimpleChanges } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TradeRequest } from '../../models/market.model';

@Component({
  selector: 'app-trade-bar',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <div class="trade-bar-card font-mono">
      <div class="trade-bar-content">
        <div class="selected-ticker-display">
          <span class="label">TICKER</span>
          <input
            type="text"
            [(ngModel)]="ticker"
            (ngModelChange)="onTickerInputChange($event)"
            class="ticker-input"
            maxlength="6"
            placeholder="TICKER"
          />
        </div>

        <div class="price-display">
          <span class="label">PRICE</span>
          <span class="price-val">{{ currentPrice === null ? 'UNAVAILABLE' : (currentPrice | currency:'USD':'symbol':'1.2-2') }}</span>
        </div>

        <div class="qty-section">
          <span class="label">SHARES</span>
          <div class="qty-input-group">
            <input
              type="number"
              [(ngModel)]="quantity"
              min="0.1"
              step="1"
              class="qty-input"
            />
            <div class="preset-buttons">
              <button type="button" class="btn-preset" (click)="setQty(1)">1</button>
              <button type="button" class="btn-preset" (click)="setQty(5)">5</button>
              <button type="button" class="btn-preset" (click)="setQty(10)">10</button>
              <button type="button" class="btn-preset" (click)="setQty(25)">25</button>
            </div>
          </div>
        </div>

        <div class="total-cost-display">
          <span class="label">EST. TOTAL</span>
          <span class="cost-val">{{ currentPrice === null ? 'UNAVAILABLE' : (quantity * currentPrice | currency:'USD':'symbol':'1.2-2') }}</span>
        </div>

        <div class="action-buttons">
          <button
            type="button"
            class="btn-trade btn-buy font-mono"
            [disabled]="!isValidTrade() || isSubmitting"
            (click)="onTrade('buy')"
          >
            BUY (MKT)
          </button>
          <button
            type="button"
            class="btn-trade btn-sell font-mono"
            [disabled]="!isValidTrade() || isSubmitting"
            (click)="onTrade('sell')"
          >
            SELL (MKT)
          </button>
        </div>
      </div>
    </div>
  `,
  styles: [`
    .trade-bar-card {
      background-color: var(--bg-card);
      border: 1px solid var(--border-color);
      border-radius: 6px;
      padding: 12px 16px;
    }

    .trade-bar-content {
      display: flex;
      align-items: center;
      justify-content: space-between;
      flex-wrap: wrap;
      gap: 16px;
    }

    .selected-ticker-display, .price-display, .qty-section, .total-cost-display {
      display: flex;
      flex-direction: column;
      gap: 4px;
    }

    .label {
      font-size: 10px;
      font-weight: 600;
      color: var(--text-dim);
    }

    .ticker-input {
      background-color: var(--bg-input);
      border: 1px solid var(--border-color);
      border-radius: 4px;
      padding: 6px 10px;
      color: var(--accent-yellow);
      font-weight: 700;
      font-size: 14px;
      width: 90px;
      text-transform: uppercase;
      outline: none;
    }
    .ticker-input:focus {
      border-color: var(--color-primary);
    }

    .price-val {
      font-size: 15px;
      font-weight: 700;
      color: var(--text-main);
      padding: 4px 0;
    }

    .qty-input-group {
      display: flex;
      align-items: center;
      gap: 6px;
    }

    .qty-input {
      background-color: var(--bg-input);
      border: 1px solid var(--border-color);
      border-radius: 4px;
      padding: 6px 10px;
      color: var(--text-main);
      font-weight: 700;
      font-size: 14px;
      width: 80px;
      outline: none;
    }
    .qty-input:focus {
      border-color: var(--color-primary);
    }

    .preset-buttons {
      display: flex;
      gap: 3px;
    }

    .btn-preset {
      background-color: var(--bg-main);
      border: 1px solid var(--border-color);
      color: var(--text-muted);
      padding: 4px 6px;
      font-size: 10px;
      font-weight: 600;
      border-radius: 3px;
      cursor: pointer;
    }
    .btn-preset:hover {
      border-color: var(--color-primary);
      color: var(--text-main);
    }

    .cost-val {
      font-size: 15px;
      font-weight: 700;
      color: var(--color-primary);
      padding: 4px 0;
    }

    .action-buttons {
      display: flex;
      align-items: center;
      gap: 10px;
    }

    .btn-trade {
      padding: 8px 18px;
      font-size: 12px;
      font-weight: 700;
      border-radius: 4px;
      border: none;
      cursor: pointer;
      transition: all 0.15s ease;
    }
    .btn-trade:disabled {
      opacity: 0.4;
      cursor: not-allowed;
    }

    .btn-buy {
      background-color: var(--color-green);
      color: #ffffff;
    }
    .btn-buy:hover:not(:disabled) {
      background-color: #059669;
    }

    .btn-sell {
      background-color: var(--color-red);
      color: #ffffff;
    }
    .btn-sell:hover:not(:disabled) {
      background-color: #dc2626;
    }
  `]
})
export class TradeBarComponent implements OnChanges {
  @Input() ticker: string = 'AAPL';
  @Input() currentPrice: number | null = null;
  @Input() isSubmitting: boolean = false;

  @Output() readonly tickerChange = new EventEmitter<string>();
  @Output() readonly selectTicker = new EventEmitter<string>();
  @Output() readonly executeTrade = new EventEmitter<TradeRequest>();

  public quantity: number = 10;

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['ticker'] && this.ticker) {
      this.ticker = this.ticker.toUpperCase();
    }
  }

  public onTickerInputChange(val: string): void {
    if (val) {
      const upper = val.trim().toUpperCase();
      this.ticker = upper;
      this.tickerChange.emit(upper);
      this.selectTicker.emit(upper);
    }
  }

  public setQty(val: number): void {
    this.quantity = val;
  }

  public isValidTrade(): boolean {
    const qty = Number(this.quantity);
    return !!this.ticker && !!this.ticker.trim() && !isNaN(qty) && qty > 0
      && this.currentPrice !== null && this.currentPrice > 0;
  }

  public onTrade(side: 'buy' | 'sell'): void {
    if (this.isValidTrade()) {
      this.executeTrade.emit({
        ticker: this.ticker.trim().toUpperCase(),
        quantity: Number(this.quantity),
        side,
        price: this.currentPrice ?? undefined,
      });
    }
  }
}
