import { Component, EventEmitter, Input, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Position, PriceTick } from '../../models/market.model';

@Component({
  selector: 'app-positions-table',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="positions-card">
      <div class="card-header">
        <div class="header-left">
          <span class="card-title">OPEN POSITIONS</span>
          <span class="badge font-mono">{{ positions.length }} HOLDINGS</span>
        </div>
      </div>

      <div class="table-container">
        <table class="positions-table font-mono" *ngIf="positions.length > 0; else noPositions">
          <thead>
            <tr>
              <th class="text-left">TICKER</th>
              <th class="text-right">SHARES</th>
              <th class="text-right">AVG COST</th>
              <th class="text-right">PRICE</th>
              <th class="text-right">MARKET VALUE</th>
              <th class="text-right">UNREALIZED P&L</th>
              <th class="text-center">ACTION</th>
            </tr>
          </thead>
          <tbody>
            <tr
              *ngFor="let pos of positions"
              class="pos-row"
              (click)="onSelect(pos.ticker)"
            >
              <td class="ticker-cell">
                <span class="symbol-tag">{{ pos.ticker }}</span>
              </td>
              <td class="text-right font-bold">{{ pos.quantity | number:'1.2-4' }}</td>
              <td class="text-right text-muted">{{ pos.avgCost | currency:'USD':'symbol':'1.2-2' }}</td>
              <td class="text-right font-bold">{{ getPrice(pos) === null ? 'UNAVAILABLE' : (getPrice(pos) | currency:'USD':'symbol':'1.2-2') }}</td>
              <td class="text-right font-bold">{{ getTotalValue(pos) === null ? 'UNAVAILABLE' : (getTotalValue(pos) | currency:'USD':'symbol':'1.2-2') }}</td>
              <td class="text-right">
                <span
                  class="badge"
                  [ngClass]="(getUnrealizedPnl(pos) ?? 0) >= 0 ? 'badge-green' : 'badge-red'"
                >
                  <ng-container *ngIf="getUnrealizedPnl(pos) !== null; else unavailablePnl">
                    {{ (getUnrealizedPnl(pos) ?? 0) >= 0 ? '+' : '' }}{{ getUnrealizedPnl(pos) | currency:'USD':'symbol':'1.2-2' }}
                    ({{ (getUnrealizedPnlPercent(pos) ?? 0) >= 0 ? '+' : '' }}{{ getUnrealizedPnlPercent(pos) | number:'1.2-2' }}%)
                  </ng-container>
                  <ng-template #unavailablePnl>UNAVAILABLE</ng-template>
                </span>
              </td>
              <td class="text-center" (click)="$event.stopPropagation()">
                <button
                  class="btn-sell-quick"
                  (click)="onQuickSell(pos)"
                  title="Sell entire position"
                >
                  SELL ALL
                </button>
              </td>
            </tr>
          </tbody>
        </table>

        <ng-template #noPositions>
          <div class="empty-state">
            <span>No open positions. Use the Trade Bar or AI copilot to buy shares.</span>
          </div>
        </ng-template>
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

    .positions-card {
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

    .table-container {
      flex: 1;
      overflow-y: auto;
    }

    .positions-table {
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

    .pos-row {
      cursor: pointer;
      border-bottom: 1px solid rgba(48, 54, 61, 0.4);
      transition: background-color 0.15s ease;
    }
    .pos-row:hover {
      background-color: var(--bg-card-hover);
    }

    td {
      padding: 10px 12px;
      vertical-align: middle;
    }

    .symbol-tag {
      font-weight: 700;
      color: var(--accent-yellow);
    }

    .font-bold { font-weight: 700; }
    .text-muted { color: var(--text-muted); }

    .btn-sell-quick {
      background-color: rgba(239, 68, 68, 0.2);
      color: var(--color-red);
      border: 1px solid rgba(239, 68, 68, 0.4);
      border-radius: 4px;
      padding: 3px 8px;
      font-size: 10px;
      font-weight: 700;
      cursor: pointer;
      transition: all 0.15s ease;
    }
    .btn-sell-quick:hover {
      background-color: var(--color-red);
      color: #ffffff;
    }

    .empty-state {
      padding: 30px;
      text-align: center;
      color: var(--text-dim);
      font-size: 12px;
    }

    .text-left { text-align: left; }
    .text-right { text-align: right; }
    .text-center { text-align: center; }
  `]
})
export class PositionsTableComponent {
  @Input() positions: Position[] = [];
  @Input() livePrices: Record<string, PriceTick> = {};
  @Output() readonly selectTicker = new EventEmitter<string>();
  @Output() readonly quickSell = new EventEmitter<Position>();

  public getPrice(pos: Position): number | null {
    return this.livePrices[pos.ticker]?.price ?? pos.currentPrice;
  }

  public getTotalValue(pos: Position): number | null {
    const price = this.getPrice(pos);
    return price === null ? null : pos.quantity * price;
  }

  public getUnrealizedPnl(pos: Position): number | null {
    const totalValue = this.getTotalValue(pos);
    return totalValue === null ? null : totalValue - (pos.quantity * pos.avgCost);
  }

  public getUnrealizedPnlPercent(pos: Position): number | null {
    const cost = pos.quantity * pos.avgCost;
    const pnl = this.getUnrealizedPnl(pos);
    return pnl === null ? null : cost > 0 ? (pnl / cost) * 100 : 0;
  }

  public onSelect(ticker: string): void {
    this.selectTicker.emit(ticker);
  }

  public onQuickSell(position: Position): void {
    this.quickSell.emit(position);
  }
}
