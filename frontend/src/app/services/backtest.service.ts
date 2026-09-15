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
  StrategyVersionDto,
  UniverseDto,
  CreateUniverseRequest,
  ExperimentDto,
  CreateExperimentRequest,
  BacktestComparisonDto,
  CreateComparisonRequest,
  SignalDto,
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

  private readonly signalsSubject = new BehaviorSubject<SignalDto[]>([]);
  public readonly signals$: Observable<SignalDto[]> = this.signalsSubject.asObservable();

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
          this.signalsSubject.next([]);
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
    this.signalsSubject.next([]);
    this.equityStatusSubject.next({
      isComplete: true,
      candidateLoaded: 0,
      candidateTotal: 0,
      benchmarkLoaded: 0,
      benchmarkTotal: 0,
    });
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

  private readonly equityStatusSubject = new BehaviorSubject<{
    isComplete: boolean;
    candidateLoaded: number;
    candidateTotal: number;
    benchmarkLoaded: number;
    benchmarkTotal: number;
  }>({
    isComplete: true,
    candidateLoaded: 0,
    candidateTotal: 0,
    benchmarkLoaded: 0,
    benchmarkTotal: 0,
  });
  public readonly equityStatus$: Observable<{
    isComplete: boolean;
    candidateLoaded: number;
    candidateTotal: number;
    benchmarkLoaded: number;
    benchmarkTotal: number;
  }> = this.equityStatusSubject.asObservable();

  public fetchAllEquity(
    runId: string,
    series: SeriesType,
    maxPoints = 50000
  ): Observable<{ points: DailyEquityPoint[]; isComplete: boolean; total: number }> {
    const pageSize = 5000;
    const fetchPage = (
      offset: number,
      acc: DailyEquityPoint[]
    ): Observable<{ points: DailyEquityPoint[]; isComplete: boolean; total: number }> => {
      return this.http.get<PagedResponse<DailyEquityPoint>>(
        `/api/research/backtests/${runId}/equity?series=${series}&limit=${pageSize}&offset=${offset}`
      ).pipe(
        switchMap((res) => {
          const items = res?.items || [];
          const total = res?.total ?? (acc.length + items.length);
          const nextAcc = [...acc, ...items];
          if (res?.hasMore && items.length > 0) {
            if (nextAcc.length >= maxPoints) {
              return of({ points: nextAcc, isComplete: false, total });
            }
            return fetchPage(offset + items.length, nextAcc);
          }
          return of({ points: nextAcc, isComplete: true, total });
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

        const isComplete = candidateEquity.isComplete && benchmarkEquity.isComplete;
        this.equityStatusSubject.next({
          isComplete,
          candidateLoaded: candidateEquity.points.length,
          candidateTotal: candidateEquity.total,
          benchmarkLoaded: benchmarkEquity.points.length,
          benchmarkTotal: benchmarkEquity.total,
        });

        this.dailyEquitySubject.next([...candidateEquity.points, ...benchmarkEquity.points]);
        this.ordersSubject.next(ordersPage?.items || []);
        this.ordersTotalSubject.next(ordersPage?.total || 0);
        this.eventsSubject.next(eventsPage?.items || []);
        this.eventsTotalSubject.next(eventsPage?.total || 0);

        this.getSignals(id).subscribe({
          next: (sigs) => {
            if (this.activeLoadingRunId === id) {
              this.signalsSubject.next(sigs || []);
            }
          },
          error: () => {
            if (this.activeLoadingRunId === id) {
              this.signalsSubject.next([]);
            }
          }
        });
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

  public getSignals(runId: string): Observable<SignalDto[]> {
    const readPage = (offset: number): Observable<SignalDto[]> =>
      this.http.get<PagedResponse<SignalDto>>(`/api/research/backtests/${runId}/signals`, {
        params: { limit: '500', offset: String(offset) },
      }).pipe(switchMap((page) => page.hasMore
        ? readPage(offset + page.items.length).pipe(switchMap((rest) => of([...page.items, ...rest])))
        : of(page.items)));
    return readPage(0);
  }

  public getSignalsExportUrl(id: string): string {
    return `/api/research/backtests/${id}/signals/export`;
  }

  public getStrategies(): Observable<StrategyVersionDto[]> {
    return this.http.get<StrategyVersionDto[]>('/api/research/strategies');
  }

  public getStrategy(id: string): Observable<StrategyVersionDto> {
    return this.http.get<StrategyVersionDto>(`/api/research/strategies/${id}`);
  }

  public getUniverses(): Observable<UniverseDto[]> {
    return this.http.get<UniverseDto[]>('/api/research/universes');
  }

  public getUniverse(id: string): Observable<UniverseDto> {
    return this.http.get<UniverseDto>(`/api/research/universes/${id}`);
  }

  public createUniverse(req: CreateUniverseRequest): Observable<UniverseDto> {
    return this.http.post<UniverseDto>('/api/research/universes', req);
  }

  public getExperiments(): Observable<ExperimentDto[]> {
    const readPage = (offset: number): Observable<ExperimentDto[]> =>
      this.http.get<PagedResponse<ExperimentDto>>('/api/research/experiments', {
        params: { limit: '100', offset: String(offset) },
      }).pipe(switchMap((page) => page.hasMore
        ? readPage(offset + page.items.length).pipe(switchMap((rest) => of([...page.items, ...rest])))
        : of(page.items)));
    return readPage(0);
  }

  public getExperiment(id: string): Observable<ExperimentDto> {
    return this.http.get<ExperimentDto>(`/api/research/experiments/${id}`);
  }

  public createExperiment(req: CreateExperimentRequest): Observable<ExperimentDto> {
    return this.http.post<ExperimentDto>('/api/research/experiments', req);
  }

  public getComparisons(): Observable<BacktestComparisonDto[]> {
    const readPage = (offset: number): Observable<BacktestComparisonDto[]> =>
      this.http.get<PagedResponse<BacktestComparisonDto>>('/api/research/comparisons', {
        params: { limit: '100', offset: String(offset) },
      }).pipe(switchMap((page) => page.hasMore
        ? readPage(offset + page.items.length).pipe(switchMap((rest) => of([...page.items, ...rest])))
        : of(page.items)));
    return readPage(0);
  }

  public getComparison(id: string): Observable<BacktestComparisonDto> {
    return this.http.get<BacktestComparisonDto>(`/api/research/comparisons/${id}`);
  }

  public createComparison(req: CreateComparisonRequest, idempotencyKey?: string): Observable<BacktestComparisonDto> {
    const key = idempotencyKey || (typeof crypto !== 'undefined' && crypto.randomUUID ? crypto.randomUUID() : 'comp-' + Date.now());
    const headers = new HttpHeaders({
      'Content-Type': 'application/json',
      'Idempotency-Key': key,
    });
    return this.http.post<BacktestComparisonDto>('/api/research/comparisons', req, { headers });
  }

  public getComparisonExportUrl(id: string): string {
    return `/api/research/comparisons/${id}/export`;
  }
}
