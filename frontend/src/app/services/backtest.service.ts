import { Injectable } from '@angular/core';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Observable, BehaviorSubject, forkJoin, of, switchMap, tap } from 'rxjs';
import {
  BacktestSummaryResponse,
  CreateBacktestRequest,
  DailyEquityPoint,
  BacktestOrderDto,
  BacktestEventDto,
  PagedResponse,
  SeriesType,
} from '../models/backtest.model';

@Injectable({
  providedIn: 'root',
})
export class BacktestService {
  private readonly runsSubject = new BehaviorSubject<BacktestSummaryResponse[]>([]);
  public readonly runs$: Observable<BacktestSummaryResponse[]> = this.runsSubject.asObservable();

  private readonly selectedRunSubject = new BehaviorSubject<BacktestSummaryResponse | null>(null);
  public readonly selectedRun$: Observable<BacktestSummaryResponse | null> = this.selectedRunSubject.asObservable();

  private readonly dailyEquitySubject = new BehaviorSubject<DailyEquityPoint[]>([]);
  public readonly dailyEquity$: Observable<DailyEquityPoint[]> = this.dailyEquitySubject.asObservable();

  private readonly ordersSubject = new BehaviorSubject<BacktestOrderDto[]>([]);
  public readonly orders$: Observable<BacktestOrderDto[]> = this.ordersSubject.asObservable();

  private readonly ordersTotalSubject = new BehaviorSubject<number>(0);
  public readonly ordersTotal$: Observable<number> = this.ordersTotalSubject.asObservable();

  private readonly eventsSubject = new BehaviorSubject<BacktestEventDto[]>([]);
  public readonly events$: Observable<BacktestEventDto[]> = this.eventsSubject.asObservable();

  private readonly eventsTotalSubject = new BehaviorSubject<number>(0);
  public readonly eventsTotal$: Observable<number> = this.eventsTotalSubject.asObservable();

  private readonly loadingSubject = new BehaviorSubject<boolean>(false);
  public readonly loading$: Observable<boolean> = this.loadingSubject.asObservable();

  private readonly errorSubject = new BehaviorSubject<string | null>(null);
  public readonly error$: Observable<string | null> = this.errorSubject.asObservable();

  private activeLoadingRunId: string | null = null;

  constructor(private readonly http: HttpClient) {
    this.refreshRuns();
  }

  public refreshRuns(): void {
    this.http.get<PagedResponse<BacktestSummaryResponse>>('/api/research/backtests').subscribe({
      next: (res) => {
        const list: BacktestSummaryResponse[] = Array.isArray(res) ? res : (res?.items || []);
        this.runsSubject.next(list);
      },
      error: (err) => {
        console.error('Failed to load backtest runs:', err);
        this.errorSubject.next('Failed to load backtest runs');
      },
    });
  }

  public getRun(id: string): Observable<BacktestSummaryResponse> {
    return this.http.get<BacktestSummaryResponse>(`/api/research/backtests/${id}`);
  }

  public selectRun(id: string): void {
    this.activeLoadingRunId = id;
    this.loadingSubject.next(true);
    this.errorSubject.next(null);

    this.getRun(id).subscribe({
      next: (run) => {
        // Suppress stale response if a different run was selected while in-flight
        if (this.activeLoadingRunId !== id) {
          return;
        }
        this.selectedRunSubject.next(run);
        this.loadingSubject.next(false);
        if (run.status === 'COMPLETED') {
          this.loadDetails(id);
        } else {
          this.dailyEquitySubject.next([]);
          this.ordersSubject.next([]);
          this.ordersTotalSubject.next(0);
          this.eventsSubject.next([]);
          this.eventsTotalSubject.next(0);
        }
      },
      error: (err) => {
        if (this.activeLoadingRunId === id) {
          console.error(`Failed to load backtest run ${id}:`, err);
          this.errorSubject.next(`Failed to load backtest run ${id}`);
          this.loadingSubject.next(false);
        }
      },
    });
  }

  public clearSelection(): void {
    this.activeLoadingRunId = null;
    this.selectedRunSubject.next(null);
    this.dailyEquitySubject.next([]);
    this.ordersSubject.next([]);
    this.ordersTotalSubject.next(0);
    this.eventsSubject.next([]);
    this.eventsTotalSubject.next(0);
    this.loadingSubject.next(false);
    this.errorSubject.next(null);
  }

  public pollRun(id: string): Observable<BacktestSummaryResponse> {
    return this.getRun(id).pipe(
      tap((run) => {
        const currentSelected = this.selectedRunSubject.value;
        if (currentSelected && currentSelected.id === id) {
          this.selectedRunSubject.next(run);
          if (run.status === 'COMPLETED' && currentSelected.status !== 'COMPLETED') {
            this.loadDetails(id);
          }
        }
        const currentList = this.runsSubject.value;
        const index = currentList.findIndex((r) => r.id === id);
        if (index >= 0) {
          const updated = [...currentList];
          updated[index] = run;
          this.runsSubject.next(updated);
        }
      })
    );
  }

  public fetchAllEquity(runId: string, series: SeriesType): Observable<DailyEquityPoint[]> {
    const pageSize = 5000;
    const fetchPage = (offset: number, acc: DailyEquityPoint[]): Observable<DailyEquityPoint[]> => {
      return this.http.get<PagedResponse<DailyEquityPoint>>(
        `/api/research/backtests/${runId}/equity?series=${series}&limit=${pageSize}&offset=${offset}`
      ).pipe(
        switchMap((res) => {
          const items = res?.items || [];
          const nextAcc = [...acc, ...items];
          if (res?.hasMore && items.length > 0 && nextAcc.length < 50000) {
            return fetchPage(offset + items.length, nextAcc);
          }
          return of(nextAcc);
        })
      );
    };
    return fetchPage(0, []);
  }

  public loadOrders(runId: string, limit = 200, offset = 0): Observable<PagedResponse<BacktestOrderDto>> {
    return this.http.get<PagedResponse<BacktestOrderDto>>(
      `/api/research/backtests/${runId}/orders?limit=${limit}&offset=${offset}`
    );
  }

  public loadEvents(runId: string, limit = 200, offset = 0): Observable<PagedResponse<BacktestEventDto>> {
    return this.http.get<PagedResponse<BacktestEventDto>>(
      `/api/research/backtests/${runId}/events?limit=${limit}&offset=${offset}`
    );
  }

  public loadDetails(id: string): void {
    this.activeLoadingRunId = id;

    forkJoin({
      candidateEquity: this.fetchAllEquity(id, 'CANDIDATE'),
      benchmarkEquity: this.fetchAllEquity(id, 'BENCHMARK'),
      ordersPage: this.loadOrders(id, 200, 0),
      eventsPage: this.loadEvents(id, 200, 0),
    }).subscribe({
      next: ({ candidateEquity, benchmarkEquity, ordersPage, eventsPage }) => {
        // Drop stale response if user selected another run while in flight
        if (this.activeLoadingRunId !== id) {
          return;
        }

        this.dailyEquitySubject.next([...candidateEquity, ...benchmarkEquity]);
        this.ordersSubject.next(ordersPage?.items || []);
        this.ordersTotalSubject.next(ordersPage?.total || 0);
        this.eventsSubject.next(eventsPage?.items || []);
        this.eventsTotalSubject.next(eventsPage?.total || 0);
      },
      error: (err) => {
        if (this.activeLoadingRunId === id) {
          console.error(`Failed to load details for run ${id}:`, err);
          this.errorSubject.next(`Failed to load details for backtest run ${id}`);
        }
      },
    });
  }

  public loadMoreOrders(runId: string, currentLength: number, limit = 200): void {
    if (this.activeLoadingRunId !== runId) {
      return;
    }
    this.loadOrders(runId, limit, currentLength).subscribe({
      next: (page) => {
        if (this.activeLoadingRunId === runId && page?.items) {
          this.ordersSubject.next([...this.ordersSubject.value, ...page.items]);
          this.ordersTotalSubject.next(page.total);
        }
      },
      error: (err) => {
        console.error('Failed to load more orders:', err);
        this.errorSubject.next('Failed to load more orders');
      },
    });
  }

  public loadMoreEvents(runId: string, currentLength: number, limit = 200): void {
    if (this.activeLoadingRunId !== runId) {
      return;
    }
    this.loadEvents(runId, limit, currentLength).subscribe({
      next: (page) => {
        if (this.activeLoadingRunId === runId && page?.items) {
          this.eventsSubject.next([...this.eventsSubject.value, ...page.items]);
          this.eventsTotalSubject.next(page.total);
        }
      },
      error: (err) => {
        console.error('Failed to load more events:', err);
        this.errorSubject.next('Failed to load more events');
      },
    });
  }

  public createRun(request: CreateBacktestRequest, idempotencyKey?: string): Observable<BacktestSummaryResponse> {
    const key = idempotencyKey || (typeof crypto !== 'undefined' && crypto.randomUUID ? crypto.randomUUID() : 'backtest-' + Date.now());
    const headers = new HttpHeaders({
      'Content-Type': 'application/json',
      'Idempotency-Key': key,
    });

    return this.http.post<BacktestSummaryResponse>('/api/research/backtests', request, { headers }).pipe(
      tap((newRun) => {
        this.activeLoadingRunId = newRun.id;
        this.refreshRuns();
        this.selectedRunSubject.next(newRun);
      })
    );
  }

  public cancelRun(id: string): Observable<BacktestSummaryResponse> {
    return this.http.post<BacktestSummaryResponse>(`/api/research/backtests/${id}/cancel`, {}).pipe(
      tap((canceledRun) => {
        this.selectedRunSubject.next(canceledRun);
        this.refreshRuns();
      })
    );
  }

  public getExportUrl(id: string): string {
    return `/api/research/backtests/${id}/export`;
  }
}
