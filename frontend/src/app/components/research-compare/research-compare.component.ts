import { Component, OnInit, ChangeDetectorRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { BacktestService } from '../../services/backtest.service';
import {
  BacktestSummaryResponse,
  BacktestComparisonDto,
  CreateComparisonRequest,
  RollingWindowSummaryDto,
} from '../../models/backtest.model';

@Component({
  selector: 'app-research-compare',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './research-compare.component.html',
  styleUrl: './research-compare.component.css',
})
export class ResearchCompareComponent implements OnInit {
  public completedRuns: BacktestSummaryResponse[] = [];
  public selectedRunIds: Set<string> = new Set<string>();
  public comparisonName: string = '';

  public comparisons: BacktestComparisonDto[] = [];
  public activeComparison: BacktestComparisonDto | null = null;

  public loading = false;
  public error: string | null = null;
  public successMsg: string | null = null;

  constructor(
    private readonly backtestService: BacktestService,
    private readonly cdr: ChangeDetectorRef
  ) {}

  ngOnInit(): void {
    this.loadData();
  }

  public loadData(): void {
    this.loading = true;
    this.error = null;

    this.backtestService.runs$.subscribe((runs) => {
      this.completedRuns = (runs || []).filter((r) => r.status === 'COMPLETED');
      this.cdr.markForCheck();
    });
    this.backtestService.refreshRuns();

    this.backtestService.getComparisons().subscribe({
      next: (comps) => {
        this.comparisons = comps;
        if (comps.length > 0 && !this.activeComparison) {
          this.activeComparison = comps[0];
        }
        this.loading = false;
        this.cdr.markForCheck();
      },
      error: (err) => {
        console.error('Failed to load comparisons:', err);
        this.error = 'Failed to load comparisons';
        this.loading = false;
        this.cdr.markForCheck();
      },
    });
  }

  public toggleRunSelection(runId: string): void {
    if (this.selectedRunIds.has(runId)) {
      this.selectedRunIds.delete(runId);
    } else {
      this.selectedRunIds.add(runId);
    }
    this.cdr.markForCheck();
  }

  public isRunSelected(runId: string): boolean {
    return this.selectedRunIds.has(runId);
  }

  public createComparison(): void {
    const runIds = Array.from(this.selectedRunIds);
    if (runIds.length < 2) {
      this.error = 'Select at least 2 completed backtests to compare';
      return;
    }

    const name = this.comparisonName.trim() || `Comparison (${runIds.length} runs)`;
    const req: CreateComparisonRequest = {
      name,
      runIds,
    };

    this.loading = true;
    this.error = null;

    this.backtestService.createComparison(req).subscribe({
      next: (comp) => {
        this.comparisons.unshift(comp);
        this.activeComparison = comp;
        this.selectedRunIds.clear();
        this.comparisonName = '';
        this.loading = false;
        this.successMsg = `Comparison "${comp.name}" generated: ${comp.status}`;
        setTimeout(() => {
          this.successMsg = null;
          this.cdr.markForCheck();
        }, 4000);
        this.cdr.markForCheck();
      },
      error: (err) => {
        console.error('Failed to create comparison:', err);
        this.error = err?.error?.message || err?.message || 'Failed to create comparison';
        this.loading = false;
        this.cdr.markForCheck();
      },
    });
  }

  public selectComparison(comp: BacktestComparisonDto): void {
    this.activeComparison = comp;
    this.cdr.markForCheck();
  }

  public getExportUrl(id: string): string {
    return this.backtestService.getComparisonExportUrl(id);
  }

  public getRollingWindowsForRun(runId: string): RollingWindowSummaryDto | null {
    if (!this.activeComparison?.rollingWindowsByRun) return null;
    return this.activeComparison.rollingWindowsByRun[runId] || null;
  }
}
