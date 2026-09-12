import { Injectable } from '@angular/core';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { BehaviorSubject, Observable, tap } from 'rxjs';
import { Portfolio, PortfolioSnapshot, TradeRequest, TradeResponse } from '../models/market.model';

@Injectable({
  providedIn: 'root',
})
export class PortfolioService {
  private readonly portfolioSubject = new BehaviorSubject<Portfolio | null>(null);
  public readonly portfolio$: Observable<Portfolio | null> = this.portfolioSubject.asObservable();

  constructor(private readonly http: HttpClient) {
    this.refreshPortfolio();
  }

  public refreshPortfolio(): void {
    this.getPortfolio().subscribe({
      next: (data) => this.portfolioSubject.next(data),
      error: (err) => console.error('Failed to load portfolio:', err),
    });
  }

  public getPortfolio(): Observable<Portfolio> {
    return this.http.get<Portfolio>('/api/portfolio').pipe(
      tap((data) => this.portfolioSubject.next(data))
    );
  }

  public executeTrade(request: TradeRequest): Observable<TradeResponse> {
    const key = createIdempotencyKey();
    const headers = new HttpHeaders({ 'Idempotency-Key': key });
    return this.http.post<TradeResponse>('/api/portfolio/trade', request, { headers }).pipe(
      tap((res) => {
        if (res.updatedPortfolio) {
          this.portfolioSubject.next(res.updatedPortfolio);
        }
      })
    );
  }

  public getHistory(): Observable<PortfolioSnapshot[]> {
    return this.http.get<PortfolioSnapshot[]>('/api/portfolio/history');
  }
}

function createIdempotencyKey(): string {
  const unique = globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random().toString(16).slice(2)}`;
  return `trade-${unique}`;
}
