import { Injectable } from '@angular/core';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Observable, BehaviorSubject, Subject, switchMap, tap, of, catchError } from 'rxjs';
import {
  ResearchPortfolioSummary,
  ResearchPortfolioDetail,
  CreatePortfolioRequest,
  ActivatePortfolioRequest,
  AdoptDatasetRequest,
  ChangeApprovalModeRequest,
  AcceptProposalRequest,
  RejectProposalRequest,
  PaperSegment,
  PaperProposal,
  PaperValuation,
  PaperModeHistory,
  PagedResponse,
  AssistantChatRequest,
  AssistantChatResponse,
} from '../models/research.model';

@Injectable({
  providedIn: 'root',
})
export class ResearchService {
  private readonly portfoliosSubject = new BehaviorSubject<ResearchPortfolioSummary[]>([]);
  public readonly portfolios$: Observable<ResearchPortfolioSummary[]> = this.portfoliosSubject.asObservable();

  private readonly selectedPortfolioSubject = new BehaviorSubject<ResearchPortfolioDetail | null>(null);
  public readonly selectedPortfolio$: Observable<ResearchPortfolioDetail | null> = this.selectedPortfolioSubject.asObservable();

  private readonly proposalsSubject = new BehaviorSubject<PaperProposal[]>([]);
  public readonly proposals$: Observable<PaperProposal[]> = this.proposalsSubject.asObservable();

  private readonly valuationsSubject = new BehaviorSubject<PaperValuation[]>([]);
  public readonly valuations$: Observable<PaperValuation[]> = this.valuationsSubject.asObservable();

  private readonly modeHistorySubject = new BehaviorSubject<PaperModeHistory[]>([]);
  public readonly modeHistory$: Observable<PaperModeHistory[]> = this.modeHistorySubject.asObservable();

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

  public refreshPortfolios(limit?: number, offset?: number): void {
    this.loadingSubject.next(true);
    const url = (limit !== undefined && offset !== undefined)
      ? `/api/research/portfolios?limit=${limit}&offset=${offset}`
      : '/api/research/portfolios';
    this.http.get<PagedResponse<ResearchPortfolioSummary> | ResearchPortfolioSummary[]>(url).subscribe({
      next: (res) => {
        if (Array.isArray(res)) {
          this.portfoliosSubject.next(res);
        } else if (res && Array.isArray((res as PagedResponse<ResearchPortfolioSummary>).items)) {
          this.portfoliosSubject.next((res as PagedResponse<ResearchPortfolioSummary>).items);
        } else {
          this.portfoliosSubject.next([]);
        }
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

  public activatePortfolio(id: string, req: ActivatePortfolioRequest): Observable<PaperSegment> {
    const key = typeof crypto !== 'undefined' && crypto.randomUUID ? crypto.randomUUID() : 'act-' + Date.now();
    const headers = new HttpHeaders({
      'Content-Type': 'application/json',
      'Idempotency-Key': key,
    });

    return this.http.post<PaperSegment>(`/api/research/portfolios/${id}/activate`, req, { headers }).pipe(
      tap(() => {
        this.selectPortfolio(id);
      })
    );
  }

  public adoptDataset(id: string, req: AdoptDatasetRequest): Observable<any> {
    const key = typeof crypto !== 'undefined' && crypto.randomUUID ? crypto.randomUUID() : 'adopt-' + Date.now();
    const headers = new HttpHeaders({
      'Content-Type': 'application/json',
      'Idempotency-Key': key,
    });

    return this.http.post<any>(`/api/research/portfolios/${id}/adopt-dataset`, req, { headers }).pipe(
      tap(() => {
        this.selectPortfolio(id);
      })
    );
  }

  public evaluatePortfolio(id: string): Observable<PaperProposal> {
    const key = typeof crypto !== 'undefined' && crypto.randomUUID ? crypto.randomUUID() : 'eval-' + Date.now();
    const headers = new HttpHeaders({
      'Content-Type': 'application/json',
      'Idempotency-Key': key,
    });

    return this.http.post<PaperProposal>(`/api/research/portfolios/${id}/evaluate`, {}, { headers }).pipe(
      tap(() => {
        this.loadProposals(id);
        this.selectPortfolio(id);
      })
    );
  }

  public loadProposals(id: string, limit: number = 20, offset: number = 0): void {
    this.http.get<PagedResponse<PaperProposal>>(`/api/research/portfolios/${id}/proposals?limit=${limit}&offset=${offset}`).subscribe({
      next: (res) => {
        this.proposalsSubject.next(res.items || []);
      },
      error: (err) => console.error('Failed to load proposals', err),
    });
  }

  public acceptProposal(id: string, proposalId: string, req?: AcceptProposalRequest): Observable<PaperProposal> {
    const key = typeof crypto !== 'undefined' && crypto.randomUUID ? crypto.randomUUID() : 'acc-' + Date.now();
    const headers = new HttpHeaders({
      'Content-Type': 'application/json',
      'Idempotency-Key': key,
    });

    return this.http.post<PaperProposal>(`/api/research/portfolios/${id}/proposals/${proposalId}/accept`, req || {}, { headers }).pipe(
      tap(() => {
        this.loadProposals(id);
        this.selectPortfolio(id);
      })
    );
  }

  public rejectProposal(id: string, proposalId: string, req: RejectProposalRequest): Observable<PaperProposal> {
    const key = typeof crypto !== 'undefined' && crypto.randomUUID ? crypto.randomUUID() : 'rej-' + Date.now();
    const headers = new HttpHeaders({
      'Content-Type': 'application/json',
      'Idempotency-Key': key,
    });

    return this.http.post<PaperProposal>(`/api/research/portfolios/${id}/proposals/${proposalId}/reject`, req, { headers }).pipe(
      tap(() => {
        this.loadProposals(id);
        this.selectPortfolio(id);
      })
    );
  }

  public changeApprovalMode(id: string, req: ChangeApprovalModeRequest): Observable<any> {
    const key = typeof crypto !== 'undefined' && crypto.randomUUID ? crypto.randomUUID() : 'mode-' + Date.now();
    const headers = new HttpHeaders({
      'Content-Type': 'application/json',
      'Idempotency-Key': key,
    });

    return this.http.post<any>(`/api/research/portfolios/${id}/mode`, req, { headers }).pipe(
      tap(() => {
        this.selectPortfolio(id);
        this.loadModeHistory(id);
      })
    );
  }

  public loadModeHistory(id: string): void {
    this.http.get<PaperModeHistory[]>(`/api/research/portfolios/${id}/mode-history`).subscribe({
      next: (res) => this.modeHistorySubject.next(res || []),
      error: (err) => console.error('Failed to load mode history', err),
    });
  }

  public processPortfolioEvents(id: string): Observable<any> {
    const key = typeof crypto !== 'undefined' && crypto.randomUUID ? crypto.randomUUID() : 'proc-' + Date.now();
    const headers = new HttpHeaders({
      'Content-Type': 'application/json',
      'Idempotency-Key': key,
    });

    return this.http.post<any>(`/api/research/portfolios/${id}/process`, {}, { headers }).pipe(
      tap(() => {
        this.selectPortfolio(id);
        this.loadValuations(id);
      })
    );
  }

  public loadValuations(id: string, limit: number = 100, offset: number = 0): void {
    this.http.get<PagedResponse<PaperValuation>>(`/api/research/portfolios/${id}/valuations?limit=${limit}&offset=${offset}`).subscribe({
      next: (res) => this.valuationsSubject.next(res.items || []),
      error: (err) => console.error('Failed to load valuations', err),
    });
  }

  public exportAuditZip(id: string): Observable<Blob> {
    return this.http.get(`/api/research/portfolios/${id}/export`, { responseType: 'blob' }).pipe(
      tap((blob) => {
        if (typeof window !== 'undefined') {
          const url = window.URL.createObjectURL(blob);
          const a = document.createElement('a');
          a.href = url;
          a.download = `paper-portfolio-${id}-audit.zip`;
          document.body.appendChild(a);
          a.click();
          document.body.removeChild(a);
          window.URL.revokeObjectURL(url);
        }
      })
    );
  }

  public sendAssistantChat(req: AssistantChatRequest): Observable<AssistantChatResponse> {
    const key = typeof crypto !== 'undefined' && crypto.randomUUID ? crypto.randomUUID() : 'chat-' + Date.now();
    const headers = new HttpHeaders({
      'Content-Type': 'application/json',
      'Idempotency-Key': key,
    });

    return this.http.post<AssistantChatResponse>('/api/research/assistant/chat', req, { headers });
  }
}
