import { Injectable } from '@angular/core';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Observable, BehaviorSubject, Subject, switchMap, tap, of, catchError } from 'rxjs';
import {
  ResearchPortfolioSummary,
  ResearchPortfolioDetail,
  CreatePortfolioRequest,
} from '../models/research.model';

@Injectable({
  providedIn: 'root',
})
export class ResearchService {
  private readonly portfoliosSubject = new BehaviorSubject<ResearchPortfolioSummary[]>([]);
  public readonly portfolios$: Observable<ResearchPortfolioSummary[]> = this.portfoliosSubject.asObservable();

  private readonly selectedPortfolioSubject = new BehaviorSubject<ResearchPortfolioDetail | null>(null);
  public readonly selectedPortfolio$: Observable<ResearchPortfolioDetail | null> = this.selectedPortfolioSubject.asObservable();

  private readonly loadingSubject = new BehaviorSubject<boolean>(false);
  public readonly loading$: Observable<boolean> = this.loadingSubject.asObservable();

  private readonly errorSubject = new BehaviorSubject<string | null>(null);
  public readonly error$: Observable<string | null> = this.errorSubject.asObservable();
  private readonly selectedIdSubject = new Subject<string>();

  constructor(private readonly http: HttpClient) {
    this.selectedIdSubject.pipe(
      tap(() => {
        this.loadingSubject.next(true);
        this.errorSubject.next(null);
      }),
      switchMap((id) =>
        this.http.get<ResearchPortfolioDetail>(`/api/research/portfolios/${id}`).pipe(
          catchError((error: unknown) => {
            console.error(`Failed to load portfolio detail ${id}:`, error);
            this.errorSubject.next('Failed to load portfolio detail');
            return of(null);
          })
        )
      )
    ).subscribe((detail) => {
      if (detail) {
        this.selectedPortfolioSubject.next(detail);
      }
      this.loadingSubject.next(false);
    });
    this.refreshPortfolios();
  }

  public refreshPortfolios(): void {
    this.loadingSubject.next(true);
    this.http.get<ResearchPortfolioSummary[]>('/api/research/portfolios').subscribe({
      next: (list) => {
        this.portfoliosSubject.next(list);
        this.loadingSubject.next(false);
      },
      error: (err) => {
        console.error('Failed to load research portfolios:', err);
        this.errorSubject.next('Failed to load research portfolios');
        this.loadingSubject.next(false);
      },
    });
  }

  public selectPortfolio(id: string): void {
    this.selectedIdSubject.next(id);
  }

  public createPortfolio(req: CreatePortfolioRequest): Observable<ResearchPortfolioDetail> {
    const key = req.idempotencyKey || (typeof crypto !== 'undefined' && crypto.randomUUID ? crypto.randomUUID() : 'key-' + Date.now());
    const headers = new HttpHeaders({
      'Content-Type': 'application/json',
      'Idempotency-Key': key,
    });

    return this.http.post<ResearchPortfolioDetail>('/api/research/portfolios', req, { headers }).pipe(
      tap((newPort) => {
        this.refreshPortfolios();
        this.selectedPortfolioSubject.next(newPort);
      })
    );
  }
}
