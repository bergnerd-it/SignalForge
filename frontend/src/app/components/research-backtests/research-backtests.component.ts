import {
  ChangeDetectorRef,
  Component,
  DestroyRef,
  OnInit,
  inject,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { interval, switchMap, of, catchError } from 'rxjs';
import { BacktestService } from '../../services/backtest.service';
import { HistoricalDataService } from '../../services/historical-data.service';
import {
  BacktestSummaryResponse,
  CreateBacktestRequest,
  DailyEquityPoint,
  BacktestOrderDto,
  BacktestEventDto,
  UniverseDto,
  SignalDto,
} from '../../models/backtest.model';
import { DatasetListing, DatasetSummary } from '../../models/historical-data.model';

@Component({
  selector: 'app-research-backtests',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './research-backtests.component.html',
  styleUrl: './research-backtests.component.css',
})
export class ResearchBacktestsComponent implements OnInit {
  private readonly destroyRef = inject(DestroyRef);

  public runs: BacktestSummaryResponse[] = [];
  public selectedRun: BacktestSummaryResponse | null = null;
  public dailyEquity: DailyEquityPoint[] = [];
  public candidatePoints: DailyEquityPoint[] = [];
  public benchmarkPoints: DailyEquityPoint[] = [];
  public orders: BacktestOrderDto[] = [];
  public ordersTotal: number = 0;
  public events: BacktestEventDto[] = [];
  public eventsTotal: number = 0;
  public signals: SignalDto[] = [];
  public selectedSignal: SignalDto | null = null;

  // Datasets & Universes for Modal
  public availableDatasets: DatasetSummary[] = [];
  public availableListings: DatasetListing[] = [];
  public universes: UniverseDto[] = [];
  public momentumK: number = 2;

  // View state
  public activeTab: 'equity' | 'orders' | 'events' | 'signals' | 'config' = 'equity';
  public showCreateModal: boolean = false;
  public isSubmitting: boolean = false;
  public isCancelling: boolean = false;
  public createError: string | null = null;

  // New Run Form State
  public newRun: CreateBacktestRequest = {
    datasetId: '',
    candidateListingId: '',
    benchmarkListingId: '',
    evaluationCutoff: '',
    requestedStartDate: '',
    requestedEndDate: '',
    initialCash: '1000.00',
    currency: 'EUR',
    commissionPerFill: '1.00',
    spreadBps: '10',
    slippageBps: '5',
    strategyId: 'ETF_BUY_HOLD_V1',
    strategyVersion: '1.0.0',
    universeId: '',
    parametersJson: '',
  };

  // Chart computed points
  public equityCandidatePolyline: string = '';
  public equityBenchmarkPolyline: string = '';
  public candidateAreaPolygon: string = '';
  public drawdownPolygon: string = '';
  public drawdownPolyline: string = '';
  public minEquity: number = 0;
  public maxEquity: number = 0;
  public maxDrawdownDepth: number = 0;
  public chartDates: string[] = [];
  public equityStatus: {
    isComplete: boolean;
    candidateLoaded: number;
    candidateTotal: number;
    benchmarkLoaded: number;
    benchmarkTotal: number;
  } | null = null;

  constructor(
    private readonly backtestService: BacktestService,
    private readonly historicalDataService: HistoricalDataService,
    private readonly cdr: ChangeDetectorRef
  ) {}

  ngOnInit(): void {
    // Check URL for direct deep-link /research/backtests/:id immediately
    if (typeof window !== 'undefined' && window.location) {
      const match = window.location.pathname.match(/\/research\/backtests\/([a-zA-Z0-9_-]+)/);
      if (match && match[1]) {
        this.selectRun(match[1]);
      }
    }

    // 1. Subscribe to Backtest Runs
    this.backtestService.runs$
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((runs) => {
        this.runs = runs || [];
        this.cdr.markForCheck();
      });

    // 2. Subscribe to Selected Run
    this.backtestService.selectedRun$
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((run) => {
        this.selectedRun = run;
        this.cdr.markForCheck();
      });

    // 3. Subscribe to Details
    this.backtestService.equityStatus$
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((status) => {
        this.equityStatus = status;
        this.cdr.markForCheck();
      });

    this.backtestService.dailyEquity$
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((points) => {
        this.dailyEquity = points;
        this.candidatePoints = points.filter((p) => p.seriesType === 'CANDIDATE');
        this.benchmarkPoints = points.filter((p) => p.seriesType === 'BENCHMARK');
        this.calculateChartPaths();
        this.cdr.markForCheck();
      });

    this.backtestService.orders$
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((orders) => {
        this.orders = orders;
        this.cdr.markForCheck();
      });

    this.backtestService.ordersTotal$
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((total) => {
        this.ordersTotal = total;
        this.cdr.markForCheck();
      });

    this.backtestService.events$
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((events) => {
        this.events = events;
        this.cdr.markForCheck();
      });

    this.backtestService.eventsTotal$
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((total) => {
        this.eventsTotal = total;
        this.cdr.markForCheck();
      });

    // 4. Subscribe to Signals
    this.backtestService.signals$
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((signals) => {
        this.signals = signals || [];
        this.selectedSignal = this.signals.length > 0 ? this.signals[0] : null;
        this.cdr.markForCheck();
      });

    // 5. Load Universes
    this.backtestService.getUniverses()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((unis) => {
        this.universes = unis || [];
        this.cdr.markForCheck();
      });

    // 6. Subscribe to Available Datasets
    this.historicalDataService.datasets$
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((ds) => {
        this.availableDatasets = ds.filter((d) => d.validationStatus === 'VALID');
        if (!this.newRun.datasetId && this.availableDatasets.length > 0) {
          this.onDatasetSelect(this.availableDatasets[0].id);
        }
        this.cdr.markForCheck();
      });

    // 7. Polling for active runs
    interval(2500)
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        switchMap(() => {
          const hasActiveRuns = this.runs.some(
            (r) => r.status === 'QUEUED' || r.status === 'RUNNING'
          );
          if (hasActiveRuns) {
            this.backtestService.refreshRuns();
            if (
              this.selectedRun &&
              (this.selectedRun.status === 'QUEUED' || this.selectedRun.status === 'RUNNING')
            ) {
              return this.backtestService.pollRun(this.selectedRun.id).pipe(catchError(() => of(null)));
            }
          }
          return of(null);
        })
      )
      .subscribe();
  }

  public selectRun(id: string): void {
    this.backtestService.selectRun(id);
    if (typeof window !== 'undefined' && window.history) {
      window.history.pushState({}, '', `/research/backtests/${id}`);
    }
    this.cdr.markForCheck();
  }

  public clearSelection(): void {
    this.selectedRun = null;
    this.backtestService.clearSelection();
    if (typeof window !== 'undefined' && window.history) {
      window.history.pushState({}, '', '/research/backtests');
    }
    this.cdr.markForCheck();
  }

  public loadMoreOrders(): void {
    if (this.selectedRun) {
      this.backtestService.loadMoreOrders(this.selectedRun.id, this.orders.length);
    }
  }

  public loadMoreEvents(): void {
    if (this.selectedRun) {
      this.backtestService.loadMoreEvents(this.selectedRun.id, this.events.length);
    }
  }

  public openNewModal(): void {
    this.showCreateModal = true;
    this.createError = null;
    this.historicalDataService.refreshDatasets();
    if (this.availableDatasets.length > 0 && !this.newRun.datasetId) {
      this.onDatasetSelect(this.availableDatasets[0].id);
    }
    this.cdr.markForCheck();
  }

  public closeNewModal(): void {
    this.showCreateModal = false;
    this.createError = null;
    this.cdr.markForCheck();
  }

  public onDatasetSelect(datasetId: string): void {
    this.newRun.datasetId = datasetId;
    const ds = this.availableDatasets.find((d) => d.id === datasetId);
    if (ds) {
      this.newRun.requestedStartDate = ds.coverageStart;
      this.newRun.requestedEndDate = ds.coverageEnd;
      this.newRun.evaluationCutoff = ds.coverageStart ? `${ds.coverageStart}T23:59:59Z` : '';
    }

    // Fetch listings for selected dataset
    this.historicalDataService.selectDataset(datasetId);
    this.historicalDataService.listings$
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((listings) => {
        this.availableListings = listings;
        if (listings.length > 0) {
          this.newRun.candidateListingId = listings[0].listingId;
          this.newRun.benchmarkListingId = listings[0].listingId;
          this.newRun.currency = listings[0].quoteCurrency || 'EUR';
        }
        this.cdr.markForCheck();
      });
  }

  public selectSignal(sig: SignalDto): void {
    this.selectedSignal = sig;
    this.cdr.markForCheck();
  }

  public onStrategyChange(stratId: string): void {
    this.newRun.strategyId = stratId;
    this.newRun.strategyVersion = stratId === 'ETF_TREND_10M_V1' ? '1.0.1' : '1.0.0';
    if (stratId === 'ETF_MOMENTUM_12_1_V1') {
      if (this.universes.length > 0 && !this.newRun.universeId) {
        this.onUniverseSelect(this.universes[0].id);
      }
    }
    this.cdr.markForCheck();
  }

  public onUniverseSelect(uniId: string): void {
    this.newRun.universeId = uniId;
    const uni = this.universes.find((u) => u.id === uniId);
    if (uni && uni.listings?.length > 0) {
      this.newRun.candidateListingId = uni.listings[0].listingId;
      this.newRun.currency = uni.currency || 'EUR';
    }
    this.cdr.markForCheck();
  }

  public getSignalsExportUrl(id: string): string {
    return this.backtestService.getSignalsExportUrl(id);
  }

  public submitNewRun(): void {
    if (!this.newRun.datasetId) {
      this.createError = 'Dataset is required.';
      return;
    }

    if (this.newRun.strategyId === 'ETF_MOMENTUM_12_1_V1') {
      if (!this.newRun.universeId) {
        this.createError = 'An ETF Universe is required for ETF Momentum.';
        return;
      }
      this.newRun.parametersJson = JSON.stringify({ k: Number(this.momentumK) || 2 });
    } else {
      this.newRun.universeId = undefined;
      this.newRun.parametersJson = undefined;
      if (!this.newRun.candidateListingId) {
        this.createError = 'Candidate listing is required.';
        return;
      }
    }

    this.isSubmitting = true;
    this.createError = null;

    this.backtestService.createRun(this.newRun).subscribe({
      next: (created) => {
        this.isSubmitting = false;
        this.showCreateModal = false;
        this.selectRun(created.id);
        this.cdr.markForCheck();
      },
      error: (err) => {
        this.isSubmitting = false;
        this.createError =
          err?.error?.message || err?.message || 'Failed to submit backtest run.';
        this.cdr.markForCheck();
      },
    });
  }

  public cancelCurrentRun(): void {
    if (!this.selectedRun) return;
    this.isCancelling = true;
    this.backtestService.cancelRun(this.selectedRun.id).subscribe({
      next: (run) => {
        this.isCancelling = false;
        this.selectedRun = run;
        this.cdr.markForCheck();
      },
      error: (err) => {
        this.isCancelling = false;
        console.error('Failed to cancel run:', err);
        this.cdr.markForCheck();
      },
    });
  }

  public getExportUrl(id: string): string {
    return this.backtestService.getExportUrl(id);
  }

  public formatPct(val: number | null | undefined): string {
    if (val === null || val === undefined) return '0.00%';
    const pct = val * 100;
    return `${pct >= 0 ? '+' : ''}${pct.toFixed(2)}%`;
  }

  public formatNumber(val: number | null | undefined, decimals: number = 2): string {
    if (val === null || val === undefined) return '0.00';
    return val.toFixed(decimals);
  }

  private calculateChartPaths(): void {
    if (this.candidatePoints.length === 0) {
      this.equityCandidatePolyline = '';
      this.equityBenchmarkPolyline = '';
      this.candidateAreaPolygon = '';
      this.drawdownPolygon = '';
      this.drawdownPolyline = '';
      return;
    }

    const width = 600;
    const height = 180;
    const ddHeight = 70;

    const candValues = this.candidatePoints.map((p) => parseFloat(p.totalEquity));
    const benchValues = this.benchmarkPoints.map((p) => parseFloat(p.totalEquity));
    const allValues = [...candValues, ...(benchValues.length > 0 ? benchValues : candValues)];

    const minVal = Math.min(...allValues);
    const maxVal = Math.max(...allValues);
    this.minEquity = minVal;
    this.maxEquity = maxVal;

    const range = maxVal - minVal > 0 ? maxVal - minVal : 1;
    const n = this.candidatePoints.length;
    const stepX = n > 1 ? width / (n - 1) : width;

    // Equity Polylines
    const candCoords = this.candidatePoints.map((p, idx) => {
      const x = idx * stepX;
      const y = height - 10 - ((parseFloat(p.totalEquity) - minVal) / range) * (height - 20);
      return `${x.toFixed(1)},${y.toFixed(1)}`;
    });
    this.equityCandidatePolyline = candCoords.join(' ');
    this.candidateAreaPolygon = `0,${height} ${this.equityCandidatePolyline} ${width},${height}`;

    if (this.benchmarkPoints.length > 0) {
      const benchN = this.benchmarkPoints.length;
      const benchStepX = benchN > 1 ? width / (benchN - 1) : width;
      const benchCoords = this.benchmarkPoints.map((p, idx) => {
        const x = idx * benchStepX;
        const y = height - 10 - ((parseFloat(p.totalEquity) - minVal) / range) * (height - 20);
        return `${x.toFixed(1)},${y.toFixed(1)}`;
      });
      this.equityBenchmarkPolyline = benchCoords.join(' ');
    } else {
      this.equityBenchmarkPolyline = '';
    }

    // Drawdown Underwater Chart
    const ddValues = this.candidatePoints.map((p) => Math.abs(p.drawdown));
    const maxDd = Math.max(...ddValues, 0.001);
    this.maxDrawdownDepth = maxDd;

    const ddCoords = this.candidatePoints.map((p, idx) => {
      const x = idx * stepX;
      const ddFraction = Math.abs(p.drawdown) / maxDd;
      const y = ddFraction * (ddHeight - 10);
      return `${x.toFixed(1)},${y.toFixed(1)}`;
    });

    this.drawdownPolyline = ddCoords.join(' ');
    this.drawdownPolygon = `0,0 ${this.drawdownPolyline} ${width},0`;

    // Extract Date Labels
    this.chartDates = [
      this.candidatePoints[0].sessionDate,
      this.candidatePoints[Math.floor(n / 2)].sessionDate,
      this.candidatePoints[n - 1].sessionDate,
    ];
  }
}
