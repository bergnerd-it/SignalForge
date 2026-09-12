import { Component, EventEmitter, Input, Output, OnChanges, SimpleChanges, ChangeDetectorRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Position, PriceTick } from '../../models/market.model';

interface TreemapItem {
  ticker: string;
  weight: number;
  unrealizedPnl: number;
  unrealizedPnlPercent: number;
  totalValue: number;
  color: string;
}

@Component({
  selector: 'app-heatmap',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="heatmap-card">
      <div class="card-header">
        <span class="card-title">PORTFOLIO HEATMAP</span>
        <span class="badge font-mono" *ngIf="items.length > 0">{{ items.length }} POSITIONS</span>
      </div>

      <div class="heatmap-body">
        <div class="treemap-grid" *ngIf="items.length > 0; else emptyState">
          <div
            *ngFor="let item of items"
            class="treemap-tile font-mono"
            [style.flex-grow]="item.weight"
            [style.background-color]="item.color"
            (click)="onSelect(item.ticker)"
            title="Click to select {{ item.ticker }}"
          >
            <div class="tile-ticker">{{ item.ticker }}</div>
            <div class="tile-weight">{{ item.weight | number:'1.1-1' }}%</div>
            <div class="tile-pnl">
              {{ item.unrealizedPnlPercent >= 0 ? '+' : '' }}{{ item.unrealizedPnlPercent | number:'1.2-2' }}%
            </div>
            <div class="tile-val">{{ item.totalValue | currency:'USD':'symbol':'1.0-0' }}</div>
          </div>
        </div>

        <ng-template #emptyState>
          <div class="empty-state">
            <span class="empty-icon">📊</span>
            <span class="empty-text">No active positions in your portfolio</span>
            <span class="empty-sub">Execute a trade or ask the AI assistant to buy shares</span>
          </div>
        </ng-template>
      </div>
    </div>
  `,
  styles: [`
    .heatmap-card {
      background-color: var(--bg-card);
      border: 1px solid var(--border-color);
      border-radius: 6px;
      display: flex;
      flex-direction: column;
      height: 100%;
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

    .heatmap-body {
      flex: 1;
      padding: 12px;
      display: flex;
      min-height: 160px;
    }

    .treemap-grid {
      display: flex;
      flex-wrap: wrap;
      gap: 6px;
      width: 100%;
      height: 100%;
    }

    .treemap-tile {
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      padding: 8px;
      border-radius: 4px;
      min-width: 90px;
      min-height: 80px;
      border: 1px solid rgba(255, 255, 255, 0.1);
      transition: transform 0.15s ease, filter 0.15s ease;
      cursor: pointer;
    }
    .treemap-tile:hover {
      transform: scale(1.02);
      filter: brightness(1.15);
      z-index: 2;
    }

    .tile-ticker {
      font-size: 14px;
      font-weight: 700;
      color: #ffffff;
    }

    .tile-weight {
      font-size: 10px;
      color: rgba(255, 255, 255, 0.7);
    }

    .tile-pnl {
      font-size: 12px;
      font-weight: 700;
      color: #ffffff;
      margin-top: 2px;
    }

    .tile-val {
      font-size: 10px;
      color: rgba(255, 255, 255, 0.8);
      margin-top: 2px;
    }

    .empty-state {
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      width: 100%;
      height: 100%;
      color: var(--text-dim);
      gap: 6px;
      padding: 20px;
      text-align: center;
    }

    .empty-icon {
      font-size: 28px;
      opacity: 0.6;
    }

    .empty-text {
      font-size: 13px;
      font-weight: 600;
      color: var(--text-muted);
    }

    .empty-sub {
      font-size: 11px;
    }
  `]
})
export class HeatmapComponent implements OnChanges {
  @Input() positions: Position[] = [];
  @Input() totalPositionValue: number | null = null;
  @Input() livePrices: Record<string, PriceTick> = {};

  @Output() readonly selectTicker = new EventEmitter<string>();

  public items: TreemapItem[] = [];

  constructor(private readonly cdr: ChangeDetectorRef) {}

  ngOnChanges(changes: SimpleChanges): void {
    this.calculateItems();
  }

  public onSelect(ticker: string): void {
    this.selectTicker.emit(ticker);
  }

  private calculateItems(): void {
    if (!this.positions || this.positions.length === 0) {
      this.items = [];
      this.cdr.markForCheck();
      return;
    }

    const enriched = this.positions.flatMap((pos) => {
      const price = this.livePrices[pos.ticker]?.price ?? pos.currentPrice;
      if (price === null) {
        return [];
      }
      const totalValue = pos.quantity * price;
      const costBasis = pos.quantity * pos.avgCost;
      const unrealizedPnl = totalValue - costBasis;
      const unrealizedPnlPercent = costBasis > 0 ? (unrealizedPnl / costBasis) * 100 : 0;
      return [{
        ticker: pos.ticker,
        quantity: pos.quantity,
        totalValue,
        unrealizedPnl,
        unrealizedPnlPercent,
      }];
    });

    const calculatedTotal = enriched.reduce((acc, p) => acc + p.totalValue, 0);
    const effectiveTotal = calculatedTotal > 0 ? calculatedTotal : (this.totalPositionValue ?? 0);

    if (effectiveTotal <= 0) {
      this.items = [];
      this.cdr.markForCheck();
      return;
    }

    this.items = enriched.map((pos) => {
      const weight = (pos.totalValue / effectiveTotal) * 100;
      const pnlPct = pos.unrealizedPnlPercent;
      let color = '#21262d';

      if (pnlPct > 5) {
        color = '#059669'; // Dark emerald
      } else if (pnlPct > 0) {
        color = '#10b981'; // Green
      } else if (pnlPct < -5) {
        color = '#dc2626'; // Deep red
      } else if (pnlPct < 0) {
        color = '#ef4444'; // Red
      }

      return {
        ticker: pos.ticker,
        weight: Math.max(1, weight),
        unrealizedPnl: pos.unrealizedPnl,
        unrealizedPnlPercent: pos.unrealizedPnlPercent,
        totalValue: pos.totalValue,
        color,
      };
    });
    this.cdr.markForCheck();
  }
}
