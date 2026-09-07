import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { BehaviorSubject, Observable, tap } from 'rxjs';
import { WatchlistEntry } from '../models/market.model';

@Injectable({
  providedIn: 'root',
})
export class WatchlistService {
  private readonly watchlistSubject = new BehaviorSubject<WatchlistEntry[]>([]);
  public readonly watchlist$: Observable<WatchlistEntry[]> = this.watchlistSubject.asObservable();

  constructor(private readonly http: HttpClient) {
    this.refreshWatchlist();
  }

  public refreshWatchlist(): void {
    this.getWatchlist().subscribe({
      next: (list) => this.watchlistSubject.next(list),
      error: (err) => console.error('Failed to load watchlist:', err),
    });
  }

  public getWatchlist(): Observable<WatchlistEntry[]> {
    return this.http.get<WatchlistEntry[]>('/api/watchlist').pipe(
      tap((list) => this.watchlistSubject.next(list))
    );
  }

  public addTicker(ticker: string): Observable<WatchlistEntry> {
    return this.http.post<WatchlistEntry>('/api/watchlist', { ticker }).pipe(
      tap(() => this.refreshWatchlist())
    );
  }

  public removeTicker(ticker: string): Observable<{ ticker: string; removed: boolean }> {
    return this.http.delete<{ ticker: string; removed: boolean }>(`/api/watchlist/${ticker}`).pipe(
      tap(() => this.refreshWatchlist())
    );
  }
}
