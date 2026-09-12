import { ChangeDetectorRef, Component, DestroyRef, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ResearchService } from '../../services/research.service';
import {
  ResearchPortfolioSummary,
  ResearchPortfolioDetail,
  CreatePortfolioRequest,
} from '../../models/research.model';

@Component({
  selector: 'app-research',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './research.component.html',
  styleUrl: './research.component.css',
})
export class ResearchComponent implements OnInit {
  private readonly destroyRef = inject(DestroyRef);
  public portfolios: ResearchPortfolioSummary[] = [];
  public selectedPortfolio: ResearchPortfolioDetail | null = null;
  public isLoading: boolean = false;
  public errorMessage: string | null = null;

  // New portfolio form state
  public showCreateModal: boolean = false;
  public newPortfolioName: string = 'Primary EUR Paper Fund';
  public newInitialCash: string = '10000.00';
  public isCreating: boolean = false;
  public createError: string | null = null;

  constructor(
    private readonly researchService: ResearchService,
    private readonly changeDetector: ChangeDetectorRef,
  ) {}

  ngOnInit(): void {
    this.researchService.portfolios$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((list) => {
      this.portfolios = list;
      if (!this.selectedPortfolio && list.length > 0) {
        this.selectPortfolio(list[0].id);
      }
      this.changeDetector.markForCheck();
    });

    this.researchService.selectedPortfolio$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((detail) => {
      this.selectedPortfolio = detail;
      this.changeDetector.markForCheck();
    });

    this.researchService.loading$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((loading) => {
      this.isLoading = loading;
      this.changeDetector.markForCheck();
    });

    this.researchService.error$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((err) => {
      this.errorMessage = err;
      this.changeDetector.markForCheck();
    });
  }

  public selectPortfolio(id: string): void {
    this.researchService.selectPortfolio(id);
  }

  public openCreateModal(): void {
    this.showCreateModal = true;
    this.createError = null;
  }

  public closeCreateModal(): void {
    this.showCreateModal = false;
    this.createError = null;
  }

  public submitCreatePortfolio(): void {
    if (!this.newPortfolioName.trim()) {
      this.createError = 'Portfolio name is required';
      return;
    }

    if (!/^(?:0|[1-9]\d*)\.\d{2}$/.test(this.newInitialCash) || this.newInitialCash === '0.00') {
      this.createError = 'Initial cash must be a positive decimal with exactly two places';
      return;
    }

    this.isCreating = true;
    this.createError = null;

    const request: CreatePortfolioRequest = {
      name: this.newPortfolioName.trim(),
      mode: 'PAPER',
      baseCurrency: 'EUR',
      initialCash: this.newInitialCash.trim(),
    };

    this.researchService.createPortfolio(request).subscribe({
      next: (created) => {
        this.isCreating = false;
        this.showCreateModal = false;
        this.newPortfolioName = 'Research Portfolio ' + (this.portfolios.length + 1);
        this.selectPortfolio(created.id);
      },
      error: (err) => {
        this.isCreating = false;
        this.createError = err.error?.message || err.message || 'Failed to create portfolio';
      },
    });
  }
}
