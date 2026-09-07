import { Component, Input, OnChanges, SimpleChanges } from '@angular/core';
import { CommonModule } from '@angular/common';
import { PriceTick } from '../../models/market.model';

@Component({
  selector: 'app-main-chart',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="chart-card">
      <div class="chart-header">
        <div class="ticker-info">
          <div class="symbol-badge font-mono">{{ ticker }}</div>
          <div class="price-block font-mono">
            <span class="current-price">{{ (currentTick?.price ?? 0) | currency:'USD':'symbol':'1.2-2' }}</span>
            <span
              class="change-badge"
              [ngClass]="(currentTick?.changePercent ?? 0) >= 0 ? 'badge-green' : 'badge-red'"
            >
              {{ (currentTick?.changePercent ?? 0) >= 0 ? '+' : '' }}{{ (currentTick?.change ?? 0) | currency:'USD':'symbol':'1.2-2' }}
              ({{ (currentTick?.changePercent ?? 0) >= 0 ? '+' : '' }}{{ (currentTick?.changePercent ?? 0) | number:'1.2-2' }}%)
            </span>
          </div>
        </div>

        <div class="stats-bar font-mono">
          <div class="stat-item">
            <span class="stat-label">HIGH</span>
            <span class="stat-value text-green">{{ highPrice | currency:'USD':'symbol':'1.2-2' }}</span>
          </div>
          <div class="stat-item">
            <span class="stat-label">LOW</span>
            <span class="stat-value text-red">{{ lowPrice | currency:'USD':'symbol':'1.2-2' }}</span>
          </div>
          <div class="stat-item">
            <span class="stat-label">TICKS</span>
            <span class="stat-value">{{ history.length }}</span>
          </div>
        </div>
      </div>

      <div class="svg-chart-container">
        <svg class="main-svg" viewBox="0 0 600 200" preserveAspectRatio="none">
          <defs>
            <linearGradient id="chartGradient" x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" [attr.stop-color]="isUp ? '#10b981' : '#ef4444'" stop-opacity="0.35"/>
              <stop offset="100%" [attr.stop-color]="isUp ? '#10b981' : '#ef4444'" stop-opacity="0.0"/>
            </linearGradient>
          </defs>

          <!-- Grid horizontal lines aligned with High, Mid, Low -->
          <line x1="0" y1="20" x2="540" y2="20" stroke="rgba(255,255,255,0.06)" stroke-dasharray="3,3" />
          <line x1="0" y1="90" x2="540" y2="90" stroke="rgba(255,255,255,0.06)" stroke-dasharray="3,3" />
          <line x1="0" y1="160" x2="540" y2="160" stroke="rgba(255,255,255,0.06)" stroke-dasharray="3,3" />

          <!-- Filled area under line -->
          <polygon
            *ngIf="areaPoints"
            [attr.points]="areaPoints"
            fill="url(#chartGradient)"
          />

          <!-- Main line -->
          <polyline
            *ngIf="polylinePoints"
            [attr.points]="polylinePoints"
            fill="none"
            [attr.stroke]="isUp ? '#10b981' : '#ef4444'"
            stroke-width="2"
            stroke-linecap="round"
            stroke-linejoin="round"
          />

          <!-- Last price dot -->
          <circle
            *ngIf="lastPointX !== null && lastPointY !== null"
            [attr.cx]="lastPointX"
            [attr.cy]="lastPointY"
            r="4"
            [attr.fill]="isUp ? '#10b981' : '#ef4444'"
          />

          <!-- Price axis labels on the right -->
          <text x="548" y="24" fill="#6e7681" font-size="10" font-family="monospace">{{ highPrice | currency:'USD':'symbol':'1.2-2' }}</text>
          <text x="548" y="94" fill="#6e7681" font-size="10" font-family="monospace">{{ midPrice | currency:'USD':'symbol':'1.2-2' }}</text>
          <text x="548" y="164" fill="#6e7681" font-size="10" font-family="monospace">{{ lowPrice | currency:'USD':'symbol':'1.2-2' }}</text>
        </svg>
      </div>
    </div>
  `,
  styles: [`
    .chart-card {
      background-color: var(--bg-card);
      border: 1px solid var(--border-color);
      border-radius: 6px;
      display: flex;
      flex-direction: column;
      height: 100%;
      overflow: hidden;
    }

    .chart-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 12px 16px;
      border-bottom: 1px solid var(--border-color);
      background-color: rgba(255, 255, 255, 0.02);
    }

    .ticker-info {
      display: flex;
      align-items: center;
      gap: 12px;
    }

    .symbol-badge {
      font-size: 16px;
      font-weight: 700;
      color: var(--accent-yellow);
      background-color: var(--bg-main);
      padding: 4px 10px;
      border: 1px solid var(--border-color);
      border-radius: 4px;
    }

    .price-block {
      display: flex;
      align-items: baseline;
      gap: 8px;
    }

    .current-price {
      font-size: 20px;
      font-weight: 700;
      color: var(--text-main);
    }

    .change-badge {
      font-size: 12px;
      font-weight: 600;
      padding: 2px 6px;
      border-radius: 4px;
    }

    .stats-bar {
      display: flex;
      align-items: center;
      gap: 16px;
    }

    .stat-item {
      display: flex;
      flex-direction: column;
      align-items: flex-end;
    }

    .stat-label {
      font-size: 9px;
      color: var(--text-dim);
      font-weight: 600;
    }

    .stat-value {
      font-size: 12px;
      font-weight: 700;
      color: var(--text-main);
    }

    .text-green { color: var(--color-green); }
    .text-red { color: var(--color-red); }

    .svg-chart-container {
      flex: 1;
      position: relative;
      padding: 10px 10px 0 10px;
      display: flex;
      align-items: center;
      justify-content: center;
      min-height: 180px;
    }

    .main-svg {
      width: 100%;
      height: 100%;
    }
  `]
})
export class MainChartComponent implements OnChanges {
  @Input() ticker: string = 'AAPL';
  @Input() currentTick: PriceTick | null = null;
  @Input() history: number[] = [];

  public highPrice: number = 0;
  public lowPrice: number = 0;
  public midPrice: number = 0;
  public isUp: boolean = true;
  public polylinePoints: string = '';
  public areaPoints: string = '';
  public lastPointX: number | null = null;
  public lastPointY: number | null = null;

  ngOnChanges(changes: SimpleChanges): void {
    this.calculateChart();
  }

  private calculateChart(): void {
    let data = this.history;
    if (!data || data.length === 0) {
      if (this.currentTick) {
        data = [this.currentTick.price];
      } else {
        data = [100];
      }
    }

    this.highPrice = Math.max(...data);
    this.lowPrice = Math.min(...data);

    if (this.highPrice === this.lowPrice) {
      this.highPrice += 1;
      this.lowPrice = Math.max(0, this.lowPrice - 1);
    }

    this.midPrice = (this.highPrice + this.lowPrice) / 2;
    this.isUp = data.length > 1 ? (data.at(-1) ?? 0) >= data[0] : true;

    const width = 540;
    const height = 180;
    const topPad = 20;
    const range = this.highPrice - this.lowPrice || 1;

    const coords = data.map((val, idx) => {
      const x = data.length > 1 ? (idx / (data.length - 1)) * width : width / 2;
      const normalized = (val - this.lowPrice) / range;
      const y = height - (normalized * (height - topPad * 2)) - topPad;
      return { x, y };
    });

    this.polylinePoints = coords.map(c => `${c.x.toFixed(1)},${c.y.toFixed(1)}`).join(' ');

    if (coords.length > 0) {
      const firstX = coords[0].x.toFixed(1);
      const lastX = coords.at(-1)!.x.toFixed(1);
      const bottomY = (height + 20).toString();
      this.areaPoints = `${firstX},${bottomY} ${this.polylinePoints} ${lastX},${bottomY}`;

      this.lastPointX = coords.at(-1)!.x;
      this.lastPointY = coords.at(-1)!.y;
    }
  }
}
