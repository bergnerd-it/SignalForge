import { ChangeDetectorRef, Component, DestroyRef, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { HistoricalDataService } from '../../services/historical-data.service';
import {
  DatasetDetail,
  DatasetListing,
  DatasetSummary,
  ImportJobResponse,
  ListingHistoryResponse,
} from '../../models/historical-data.model';

@Component({
  selector: 'app-research-data',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './research-data.component.html',
  styleUrl: './research-data.component.css',
})
export class ResearchDataComponent implements OnInit {
  private readonly destroyRef = inject(DestroyRef);

  public datasets: DatasetSummary[] = [];
  public selectedDataset: DatasetDetail | null = null;
  public listings: DatasetListing[] = [];
  public selectedListingId: string | null = null;
  public selectedHistory: ListingHistoryResponse | null = null;
  public activeJob: ImportJobResponse | null = null;

  public isLoading: boolean = false;
  public errorMessage: string | null = null;

  // Filter & Pagination params
  public filterStart: string = '';
  public filterEnd: string = '';
  public filterAsOf: string = '';
  public barsLimit: number = 1000;
  public barsOffset: number = 0;

  // Upload modal/state
  public showUploadModal: boolean = false;
  public selectedFile: File | null = null;
  public uploadKey: string = '';
  public isUploading: boolean = false;
  public uploadError: string | null = null;

  constructor(
    private readonly dataService: HistoricalDataService,
    private readonly changeDetector: ChangeDetectorRef
  ) {}

  ngOnInit(): void {
    this.dataService.datasets$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((list) => {
      this.datasets = list;
      if (!this.selectedDataset && list.length > 0) {
        this.selectDataset(list[0].id);
      }
      this.changeDetector.markForCheck();
    });

    this.dataService.selectedDataset$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((detail) => {
      this.selectedDataset = detail;
      if (detail && detail.manifest && detail.manifest.coverage) {
        this.filterStart = detail.manifest.coverage.start_date;
        this.filterEnd = detail.manifest.coverage.end_date;
      }
      this.changeDetector.markForCheck();
    });

    this.dataService.listings$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((list) => {
      this.listings = list;
      if (list.length > 0) {
        this.selectListing(list[0].listingId);
      } else {
        this.selectedListingId = null;
      }
      this.changeDetector.markForCheck();
    });

    this.dataService.selectedHistory$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((history) => {
      this.selectedHistory = history;
      if (history) {
        this.barsOffset = history.offset;
        this.barsLimit = history.limit;
      }
      this.changeDetector.markForCheck();
    });

    this.dataService.activeJob$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((job) => {
      this.activeJob = job;
      this.changeDetector.markForCheck();
    });

    this.dataService.loading$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((loading) => {
      this.isLoading = loading;
      this.changeDetector.markForCheck();
    });

    this.dataService.error$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((err) => {
      this.errorMessage = err;
      this.changeDetector.markForCheck();
    });
  }

  public selectDataset(id: string): void {
    this.barsOffset = 0;
    this.dataService.selectDataset(id);
  }

  public selectListing(listingId: string): void {
    this.selectedListingId = listingId;
    this.barsOffset = 0;
    this.applyHistoryFilter();
  }

  public applyHistoryFilter(resetOffset: boolean = true): void {
    if (!this.selectedDataset || !this.selectedListingId) return;
    if (resetOffset) {
      this.barsOffset = 0;
    }
    this.dataService.loadListingHistory(
      this.selectedDataset.id,
      this.selectedListingId,
      this.filterStart || undefined,
      this.filterEnd || undefined,
      this.filterAsOf || undefined,
      this.barsLimit,
      this.barsOffset
    );
  }

  public nextBarsPage(): void {
    if (this.selectedHistory && this.selectedHistory.isTruncated) {
      this.barsOffset += this.barsLimit;
      this.applyHistoryFilter(false);
    }
  }

  public prevBarsPage(): void {
    if (this.barsOffset >= this.barsLimit) {
      this.barsOffset = Math.max(0, this.barsOffset - this.barsLimit);
      this.applyHistoryFilter(false);
    }
  }

  public get currentPage(): number {
    return Math.floor(this.barsOffset / this.barsLimit) + 1;
  }

  public get totalPages(): number {
    if (!this.selectedHistory || this.selectedHistory.totalBars === 0) return 1;
    return Math.ceil(this.selectedHistory.totalBars / this.barsLimit);
  }

  public openUploadModal(): void {
    this.showUploadModal = true;
    this.uploadError = null;
    this.selectedFile = null;
    this.uploadKey = 'import-' + Date.now();
  }

  public closeUploadModal(): void {
    this.showUploadModal = false;
    this.uploadError = null;
    this.selectedFile = null;
  }

  public onFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    if (input.files && input.files.length > 0) {
      this.selectedFile = input.files[0];
    }
  }

  public submitUpload(): void {
    if (!this.selectedFile) {
      this.uploadError = 'Please select a ZIP bundle file to upload';
      return;
    }

    this.isUploading = true;
    this.uploadError = null;

    this.dataService.uploadBundle(this.selectedFile, this.uploadKey).subscribe({
      next: (job) => {
        this.isUploading = false;
        this.showUploadModal = false;
        this.dataService.pollJob(job.id);
      },
      error: (err) => {
        this.isUploading = false;
        this.uploadError = err.error?.message || err.message || 'Upload failed';
      },
    });
  }

  public dismissJobBanner(): void {
    this.dataService.clearActiveJob();
  }
}
