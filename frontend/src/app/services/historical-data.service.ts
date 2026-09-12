import { Injectable, OnDestroy } from '@angular/core';
import { HttpClient, HttpHeaders, HttpParams } from '@angular/common/http';
import { BehaviorSubject, Observable, Subscription, of, timer } from 'rxjs';
import { catchError, switchMap, takeWhile, tap } from 'rxjs/operators';
import {
  DatasetDetail,
  DatasetListing,
  DatasetSummary,
  ImportJobResponse,
  ListingHistoryResponse,
} from '../models/historical-data.model';

@Injectable({
  providedIn: 'root',
})
export class HistoricalDataService implements OnDestroy {
  private readonly datasetsSubject = new BehaviorSubject<DatasetSummary[]>([]);
  public readonly datasets$: Observable<DatasetSummary[]> = this.datasetsSubject.asObservable();

  private readonly selectedDatasetSubject = new BehaviorSubject<DatasetDetail | null>(null);
  public readonly selectedDataset$: Observable<DatasetDetail | null> = this.selectedDatasetSubject.asObservable();

  private readonly listingsSubject = new BehaviorSubject<DatasetListing[]>([]);
  public readonly listings$: Observable<DatasetListing[]> = this.listingsSubject.asObservable();

  private readonly selectedHistorySubject = new BehaviorSubject<ListingHistoryResponse | null>(null);
  public readonly selectedHistory$: Observable<ListingHistoryResponse | null> = this.selectedHistorySubject.asObservable();

  private readonly activeJobSubject = new BehaviorSubject<ImportJobResponse | null>(null);
  public readonly activeJob$: Observable<ImportJobResponse | null> = this.activeJobSubject.asObservable();

  private readonly loadingSubject = new BehaviorSubject<boolean>(false);
  public readonly loading$: Observable<boolean> = this.loadingSubject.asObservable();

  private readonly errorSubject = new BehaviorSubject<string | null>(null);
  public readonly error$: Observable<string | null> = this.errorSubject.asObservable();

  private pollingSubscription?: Subscription;

  constructor(private readonly http: HttpClient) {
    this.refreshDatasets();
  }

  public refreshDatasets(): void {
    this.loadingSubject.next(true);
    this.http.get<DatasetSummary[]>('/api/research/datasets').pipe(
      catchError((err: unknown) => {
        console.error('Failed to load datasets:', err);
        this.errorSubject.next('Failed to load historical datasets');
        return of([]);
      })
    ).subscribe((list) => {
      this.datasetsSubject.next(list);
      this.loadingSubject.next(false);
    });
  }

  public selectDataset(datasetId: string): void {
    this.loadingSubject.next(true);
    this.selectedDatasetSubject.next(null);
    this.listingsSubject.next([]);
    this.selectedHistorySubject.next(null);

    this.http.get<DatasetDetail>(`/api/research/datasets/${datasetId}`).pipe(
      tap((detail) => this.selectedDatasetSubject.next(detail)),
      switchMap(() => this.http.get<DatasetListing[]>(`/api/research/datasets/${datasetId}/listings`)),
      catchError((err: unknown) => {
        console.error(`Failed to load dataset ${datasetId}:`, err);
        this.errorSubject.next('Failed to load dataset details');
        return of([] as DatasetListing[]);
      })
    ).subscribe((listings) => {
      this.listingsSubject.next(listings);
      this.loadingSubject.next(false);
    });
  }

  public loadListingHistory(
    datasetId: string,
    listingId: string,
    start?: string,
    end?: string,
    asOf?: string
  ): void {
    let params = new HttpParams();
    if (start) params = params.set('start', start);
    if (end) params = params.set('end', end);
    if (asOf) params = params.set('asOf', asOf);

    this.http.get<ListingHistoryResponse>(`/api/research/datasets/${datasetId}/history/${listingId}`, { params }).pipe(
      catchError((err: unknown) => {
        console.error(`Failed to load history for listing ${listingId}:`, err);
        this.errorSubject.next('Failed to load history for selected listing');
        return of(null);
      })
    ).subscribe((history) => {
      this.selectedHistorySubject.next(history);
    });
  }

  public uploadBundle(file: File, customKey?: string): Observable<ImportJobResponse> {
    const key = customKey && customKey.trim() ? customKey.trim() : 'import-' + (typeof crypto !== 'undefined' && crypto.randomUUID ? crypto.randomUUID() : Date.now());
    const formData = new FormData();
    formData.append('file', file, file.name);

    const headers = new HttpHeaders({
      'Idempotency-Key': key,
    });

    return this.http.post<ImportJobResponse>('/api/research/imports', formData, { headers }).pipe(
      tap((job) => {
        this.activeJobSubject.next(job);
      })
    );
  }

  public getJob(jobId: string): Observable<ImportJobResponse> {
    return this.http.get<ImportJobResponse>(`/api/research/jobs/${jobId}`);
  }

  public pollJob(jobId: string, intervalMs = 1000): void {
    this.stopPolling();
    this.pollingSubscription = timer(0, intervalMs).pipe(
      switchMap(() => this.getJob(jobId)),
      takeWhile((job) => job.status === 'QUEUED' || job.status === 'RUNNING', true),
      catchError((err: unknown) => {
        console.error(`Job polling failed for ${jobId}:`, err);
        this.errorSubject.next('Job status check failed');
        return of(null);
      })
    ).subscribe((job) => {
      if (job) {
        this.activeJobSubject.next(job);
        if (job.status === 'COMPLETED') {
          this.refreshDatasets();
          if (job.datasetId) {
            this.selectDataset(job.datasetId);
          }
          this.stopPolling();
        } else if (job.status === 'FAILED') {
          this.stopPolling();
        }
      }
    });
  }

  public stopPolling(): void {
    if (this.pollingSubscription) {
      this.pollingSubscription.unsubscribe();
      this.pollingSubscription = undefined;
    }
  }

  public clearActiveJob(): void {
    this.stopPolling();
    this.activeJobSubject.next(null);
  }

  ngOnDestroy(): void {
    this.stopPolling();
  }
}
