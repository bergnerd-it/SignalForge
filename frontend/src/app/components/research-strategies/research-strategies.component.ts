import { Component, OnInit, ChangeDetectorRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { BacktestService } from '../../services/backtest.service';
import {
  StrategyVersionDto,
  UniverseDto,
  CreateUniverseRequest,
  ExperimentDto,
  CreateExperimentRequest,
} from '../../models/backtest.model';

@Component({
  selector: 'app-research-strategies',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './research-strategies.component.html',
  styleUrl: './research-strategies.component.css',
})
export class ResearchStrategiesComponent implements OnInit {
  public activeSubTab: 'strategies' | 'universes' | 'experiments' = 'strategies';

  public strategies: StrategyVersionDto[] = [];
  public selectedStrategy: StrategyVersionDto | null = null;

  public universes: UniverseDto[] = [];
  public selectedUniverse: UniverseDto | null = null;
  public showCreateUniverseModal = false;
  public newUniverse: CreateUniverseRequest = {
    name: '',
    version: '1.0.0',
    description: '',
    datasetId: '',
    calendarId: 'XETR',
    currency: 'EUR',
    provenance: 'HISTORICAL_IMPORT',
    listingIds: [],
  };
  public newUniverseListingsInput = 'EXXT.DE, EUNL.DE, IS3N.DE';

  public experiments: ExperimentDto[] = [];
  public selectedExperiment: ExperimentDto | null = null;
  public showCreateExperimentModal = false;
  public newExperiment: CreateExperimentRequest = {
    name: '',
    version: 1,
    strategyId: 'ETF_MOMENTUM_12_1_V1',
    strategyVersion: '1.0.0',
    datasetId: '',
    benchmarkListingId: 'EUNL.DE',
    developmentStartDate: '2018-01-01',
    developmentEndDate: '2021-12-31',
    holdoutStartDate: '2022-01-01',
    holdoutEndDate: '2023-12-31',
    declaredHoldoutStatus: 'UNEXAMINED',
    parametersJson: '{"k": 2}',
  };

  public loading = false;
  public error: string | null = null;
  public successMsg: string | null = null;

  constructor(
    private readonly backtestService: BacktestService,
    private readonly cdr: ChangeDetectorRef
  ) {}

  ngOnInit(): void {
    this.loadAll();
  }

  public loadAll(): void {
    this.loading = true;
    this.error = null;

    this.backtestService.getStrategies().subscribe({
      next: (strats) => {
        this.strategies = strats;
        if (strats.length > 0 && !this.selectedStrategy) {
          this.selectedStrategy = strats[0];
        }
        this.loading = false;
        this.cdr.markForCheck();
      },
      error: (err) => {
        console.error('Failed to load strategies:', err);
        this.error = 'Failed to load strategies';
        this.loading = false;
        this.cdr.markForCheck();
      },
    });

    this.backtestService.getUniverses().subscribe({
      next: (unis) => {
        this.universes = unis;
        if (unis.length > 0 && !this.selectedUniverse) {
          this.selectedUniverse = unis[0];
        }
        this.cdr.markForCheck();
      },
      error: (err) => console.error('Failed to load universes:', err),
    });

    this.backtestService.getExperiments().subscribe({
      next: (exps) => {
        this.experiments = exps;
        if (exps.length > 0 && !this.selectedExperiment) {
          this.selectedExperiment = exps[0];
        }
        this.cdr.markForCheck();
      },
      error: (err) => console.error('Failed to load experiments:', err),
    });
  }

  public selectSubTab(tab: 'strategies' | 'universes' | 'experiments'): void {
    this.activeSubTab = tab;
    this.cdr.markForCheck();
  }

  public selectStrategy(strat: StrategyVersionDto): void {
    this.selectedStrategy = strat;
    this.cdr.markForCheck();
  }

  public selectUniverse(uni: UniverseDto): void {
    this.selectedUniverse = uni;
    this.cdr.markForCheck();
  }

  public selectExperiment(exp: ExperimentDto): void {
    this.selectedExperiment = exp;
    this.cdr.markForCheck();
  }

  public openCreateUniverse(): void {
    this.showCreateUniverseModal = true;
    this.cdr.markForCheck();
  }

  public closeCreateUniverse(): void {
    this.showCreateUniverseModal = false;
    this.cdr.markForCheck();
  }

  public submitCreateUniverse(): void {
    if (!this.newUniverse.name.trim() || !this.newUniverse.datasetId.trim()) {
      this.error = 'Universe name and Dataset ID are required';
      return;
    }
    const listings = this.newUniverseListingsInput
      .split(',')
      .map((s) => s.trim())
      .filter((s) => s.length > 0);

    if (listings.length === 0) {
      this.error = 'At least one listing ID is required';
      return;
    }

    const payload: CreateUniverseRequest = {
      ...this.newUniverse,
      listingIds: listings,
    };

    this.loading = true;
    this.backtestService.createUniverse(payload).subscribe({
      next: (created) => {
        this.universes.push(created);
        this.selectedUniverse = created;
        this.showCreateUniverseModal = false;
        this.loading = false;
        this.successMsg = `Universe "${created.name}" created successfully.`;
        setTimeout(() => {
          this.successMsg = null;
          this.cdr.markForCheck();
        }, 3000);
        this.cdr.markForCheck();
      },
      error: (err) => {
        console.error('Failed to create universe:', err);
        this.error = err?.error?.message || err?.message || 'Failed to create universe';
        this.loading = false;
        this.cdr.markForCheck();
      },
    });
  }

  public openCreateExperiment(): void {
    this.showCreateExperimentModal = true;
    this.cdr.markForCheck();
  }

  public closeCreateExperiment(): void {
    this.showCreateExperimentModal = false;
    this.cdr.markForCheck();
  }

  public submitCreateExperiment(): void {
    if (!this.newExperiment.name.trim() || !this.newExperiment.datasetId.trim()) {
      this.error = 'Experiment name and Dataset ID are required';
      return;
    }

    this.loading = true;
    this.backtestService.createExperiment(this.newExperiment).subscribe({
      next: (created) => {
        this.experiments.push(created);
        this.selectedExperiment = created;
        this.showCreateExperimentModal = false;
        this.loading = false;
        this.successMsg = `Experiment "${created.name}" registered successfully.`;
        setTimeout(() => {
          this.successMsg = null;
          this.cdr.markForCheck();
        }, 3000);
        this.cdr.markForCheck();
      },
      error: (err) => {
        console.error('Failed to create experiment:', err);
        this.error = err?.error?.message || err?.message || 'Failed to create experiment';
        this.loading = false;
        this.cdr.markForCheck();
      },
    });
  }
}
