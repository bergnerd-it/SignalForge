import { Injectable } from '@angular/core';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Observable, BehaviorSubject, of, catchError, tap } from 'rxjs';
import {
  BacktestSummaryResponse,
  CreateBacktestRequest,
  DailyEquityPoint,
  BacktestOrderDto,
  BacktestEventDto,
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

  private readonly eventsSubject = new BehaviorSubject<BacktestEventDto[]>([]);
  public readonly events$: Observable<BacktestEventDto[]> = this.eventsSubject.asObservable();

  private readonly loadingSubject = new BehaviorSubject<boolean>(false);
  public readonly loading$: Observable<boolean> = this.loadingSubject.asObservable();

  private readonly errorSubject = new BehaviorSubject<string | null>(null);
  public readonly error$: Observable<string | null> = this.errorSubject.asObservable();

  constructor(private readonly http: HttpClient) {
    this.refreshRuns();
  }

  public refreshRuns(): void {
    this.http.get<any>('/api/research/backtests').subscribe({
      next: (res) => {
        const list: BacktestSummaryResponse[] = Array.isArray(res) ? res : (res?.items || res?.backtests || []);
        this.runsSubject.next(list);
      },
      error: (err) => {
        console.error('Failed to load backtest runs:', err);
        this.errorSubject.next('Failed to load backtest runs');
      },
    });
  }

  public selectRun(id: string): void {
    this.loadingSubject.next(true);
    this.errorSubject.next(null);
    this.http.get<BacktestSummaryResponse>(`/api/research/backtests/${id}`).subscribe({
      next: (run) => {
        this.selectedRunSubject.next(run);
        this.loadingSubject.next(false);
        if (run.status === 'COMPLETED') {
          this.loadDetails(id);
        }
      },
      error: (err) => {
        console.error(`Failed to load backtest run ${id}:`, err);
        this.errorSubject.next(`Failed to load backtest run ${id}`);
        this.loadingSubject.next(false);
      },
    });
  }

  public pollRun(id: string): Observable<BacktestSummaryResponse> {
    return this.http.get<BacktestSummaryResponse>(`/api/research/backtests/${id}`).pipe(
      tap((run) => {
        const currentSelected = this.selectedRunSubject.value;
        if (currentSelected && currentSelected.id === id) {
          this.selectedRunSubject.next(run);
          if (run.status === 'COMPLETED' && currentSelected.status !== 'COMPLETED') {
            this.loadDetails(id);
          }
        }
        // Also update run in list
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

  public loadDetails(id: string): void {
    this.http.get<any>(`/api/research/backtests/${id}/equity`).pipe(
      catchError(() => of({ items: [] }))
    ).subscribe((res) => {
      const items: any[] = Array.isArray(res) ? res : (res?.items || []);
      const mapped: DailyEquityPoint[] = items.map((p) => ({
        runId: p.runId || id,
        sessionDate: p.sessionDate,
        seriesType: p.seriesType,
        cashBalance: p.cashBalance ?? p.cash ?? '0.00',
        holdingsValue: p.holdingsValue ?? '0.00',
        receivablesBalance: p.receivablesBalance ?? p.receivables ?? '0.00',
        totalEquity: p.totalEquity ?? '0.00',
        dailyReturn: p.dailyReturn ?? null,
        cumulativeReturn: p.cumulativeReturn ?? 0,
        drawdown: p.drawdown ?? 0,
        units: p.units ?? '0',
        closingPrice: p.closingPrice ?? p.rawClose ?? '0.00',
      }));
      this.dailyEquitySubject.next(mapped);
    });

    this.http.get<any>(`/api/research/backtests/${id}/orders`).pipe(
      catchError(() => of({ items: [] }))
    ).subscribe((res) => {
      const items: any[] = Array.isArray(res) ? res : (res?.items || []);
      const mapped: BacktestOrderDto[] = items.map((o) => ({
        orderId: o.orderId || o.id,
        sessionDate: o.sessionDate,
        seriesType: o.seriesType,
        listingId: o.listingId,
        side: (o.side || (o.orderType?.includes('BUY') ? 'BUY' : 'BUY')) as 'BUY' | 'SELL',
        requestedUnits: o.requestedUnits ?? o.requestedQuantity ?? '0',
        filledUnits: o.filledUnits ?? o.executedQuantity ?? '0',
        orderStatus: o.orderStatus ?? o.status ?? 'FILLED',
        limitPrice: o.limitPrice ?? null,
        unadjustedFillPrice: o.unadjustedFillPrice ?? o.rawOpen ?? '0.00',
        modeledFillPrice: o.modeledFillPrice ?? o.fillPrice ?? '0.00',
        commission: o.commission ?? '0.00',
        totalCashImpact: o.totalCashImpact ?? (o.totalCost ? String(o.totalCost) : '0.00'),
        executedAt: o.executedAt ?? o.createdAt ?? '',
      }));
      this.ordersSubject.next(mapped);
    });

    this.http.get<any>(`/api/research/backtests/${id}/events`).pipe(
      catchError(() => of({ items: [] }))
    ).subscribe((res) => {
      const items: any[] = Array.isArray(res) ? res : (res?.items || []);
      const mapped: BacktestEventDto[] = items.map((e) => ({
        eventId: e.eventId ?? e.eventSeq ?? 0,
        sessionDate: e.sessionDate ?? e.eventDate ?? '',
        seriesType: e.seriesType,
        eventType: e.eventType,
        eventPayloadJson: e.eventPayloadJson ?? e.description ?? e.detailsJson ?? '',
        occurredAt: e.occurredAt ?? e.eventTime ?? e.createdAt ?? '',
      }));
      this.eventsSubject.next(mapped);
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
