import { Component, Input, OnChanges, SimpleChanges } from '@angular/core';
import { CommonModule } from '@angular/common';
import { PortfolioSnapshot } from '../../models/market.model';

@Component({
  selector: 'app-pnl-chart',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="pnl-card">
      <div class="card-header">
        <span class="card-title">PORTFOLIO PERFORMANCE (P&L)</span>
        <span class="stat-badge font-mono" [ngClass]="currentValue >= 10000 ? 'badge-green' : 'badge-red'">
          {{ currentValue >= 10000 ? '+' : '' }}{{ (currentValue - 10000) | currency:'USD':'symbol':'1.2-2' }}
        </span>
      </div>

      <div class="chart-body">
        <svg class="pnl-svg" viewBox="0 0 500 130" preserveAspectRatio="none">
          <defs>
            <linearGradient id="pnlGradient" x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" [attr.stop-color]="currentValue >= 10000 ? '#10b981' : '#ef4444'" stop-opacity="0.3"/>
              <stop offset="100%" [attr.stop-color]="currentValue >= 10000 ? '#10b981' : '#ef4444'" stop-opacity="0.0"/>
            </linearGradient>
          </defs>

          <!-- Baseline $10,000 guide line -->
          <line
            *ngIf="baselineY !== null"
            x1="0"
            [attr.y1]="baselineY"
            x2="430"
            [attr.y2]="baselineY"
            stroke="rgba(236, 173, 10, 0.4)"
            stroke-dasharray="4,4"
          />

          <!-- Area -->
          <polygon
            *ngIf="areaPoints"
            [attr.points]="areaPoints"
            fill="url(#pnlGradient)"
          />

          <!-- Line -->
          <polyline
            *ngIf="polylinePoints"
            [attr.points]="polylinePoints"
            fill="none"
            [attr.stroke]="currentValue >= 10000 ? '#10b981' : '#ef4444'"
            stroke-width="2"
            stroke-linecap="round"
            stroke-linejoin="round"
          />

          <!-- Last Point -->
          <circle
            *ngIf="lastPointX !== null && lastPointY !== null"
            [attr.cx]="lastPointX"
            [attr.cy]="lastPointY"
            r="3.5"
            [attr.fill]="currentValue >= 10000 ? '#10b981' : '#ef4444'"
          />

          <!-- Labels -->
          <text x="438" y="19" fill="#6e7681" font-size="9" font-family="monospace">{{ maxVal | currency:'USD':'symbol':'1.0-0' }}</text>
          <text *ngIf="baselineY !== null" x="438" [attr.y]="baselineY + 3" fill="rgba(236, 173, 10, 0.8)" font-size="9" font-family="monospace">$10,000</text>
          <text x="438" y="109" fill="#6e7681" font-size="9" font-family="monospace">{{ minVal | currency:'USD':'symbol':'1.0-0' }}</text>
        </svg>
      </div>
    </div>
  `,
  styles: [`
    .pnl-card {
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

    .stat-badge {
      font-size: 11px;
      font-weight: 700;
      padding: 2px 8px;
      border-radius: 4px;
    }

    .chart-body {
      flex: 1;
      padding: 10px 10px 0 10px;
      display: flex;
      align-items: center;
      justify-content: center;
      min-height: 140px;
    }

    .pnl-svg {
      width: 100%;
      height: 100%;
    }
  `]
})
export class PnlChartComponent implements OnChanges {
  @Input() snapshots: PortfolioSnapshot[] = [];
  @Input() currentValue: number = 10000;

  public minVal: number = 9500;
  public maxVal: number = 10500;
  public polylinePoints: string = '';
  public areaPoints: string = '';
  public lastPointX: number | null = null;
  public lastPointY: number | null = null;
  public baselineY: number | null = null;

  ngOnChanges(changes: SimpleChanges): void {
    this.calculateChart();
  }

  private calculateChart(): void {
    let vals: number[] = [];
    if (this.snapshots && this.snapshots.length > 0) {
      vals = this.snapshots.map(s => s.totalValue);
    }
    if (vals.length === 0) {
      vals = [10000, this.currentValue];
    } else {
      vals.push(this.currentValue);
    }

    this.minVal = Math.min(9800, ...vals);
    this.maxVal = Math.max(10200, ...vals);

    const width = 430;
    const height = 120;
    const topPad = 15;
    const range = this.maxVal - this.minVal || 1;

    // Baseline 10000 Y coord
    const baselineNorm = (10000 - this.minVal) / range;
    this.baselineY = height - (baselineNorm * (height - topPad * 2)) - topPad;

    const coords = vals.map((val, idx) => {
      const x = vals.length > 1 ? (idx / (vals.length - 1)) * width : width / 2;
      const normalized = (val - this.minVal) / range;
      const y = height - (normalized * (height - topPad * 2)) - topPad;
      return { x, y };
    });

    this.polylinePoints = coords.map(c => `${c.x.toFixed(1)},${c.y.toFixed(1)}`).join(' ');

    if (coords.length > 0) {
      const firstX = coords[0].x.toFixed(1);
      const lastX = coords.at(-1)!.x.toFixed(1);
      const bottomY = (height + 15).toString();
      this.areaPoints = `${firstX},${bottomY} ${this.polylinePoints} ${lastX},${bottomY}`;

      this.lastPointX = coords.at(-1)!.x;
      this.lastPointY = coords.at(-1)!.y;
    }
  }
}
