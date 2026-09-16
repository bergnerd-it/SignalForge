import { Injectable } from '@angular/core';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Observable, BehaviorSubject, Subject, switchMap, tap, of, catchError, forkJoin } from 'rxjs';
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
  DatasetAdoptionResult,
  ModeChangeResponse,
  ProcessEventsResponse,
  PaperExecutionIntent,
  PaperReceivable,
  PaperProcessedAction,
  PaperDatasetAdoption,
  PaperPageMetadata,
  PaperPageName,
  PaperPageState,
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

  private readonly intentsSubject = new BehaviorSubject<PaperExecutionIntent[]>([]);
  public readonly intents$: Observable<PaperExecutionIntent[]> = this.intentsSubject.asObservable();

  private readonly receivablesSubject = new BehaviorSubject<PaperReceivable[]>([]);
  public readonly receivables$: Observable<PaperReceivable[]> = this.receivablesSubject.asObservable();

  private readonly actionsSubject = new BehaviorSubject<PaperProcessedAction[]>([]);
  public readonly actions$: Observable<PaperProcessedAction[]> = this.actionsSubject.asObservable();

  private readonly adoptionsSubject = new BehaviorSubject<PaperDatasetAdoption[]>([]);
  public readonly adoptions$: Observable<PaperDatasetAdoption[]> = this.adoptionsSubject.asObservable();

  private readonly initialPaperPages: PaperPageState = {
    proposals: { total: 0, limit: 50, offset: 0, isComplete: true },
    valuations: { total: 0, limit: 200, offset: 0, isComplete: true },
    intents: { total: 0, limit: 100, offset: 0, isComplete: true },
    receivables: { total: 0, limit: 100, offset: 0, isComplete: true },
    actions: { total: 0, limit: 100, offset: 0, isComplete: true },
    adoptions: { total: 0, limit: 100, offset: 0, isComplete: true },
  };
  private readonly paperPagesSubject = new BehaviorSubject<PaperPageState>(this.initialPaperPages);
  public readonly paperPages$: Observable<PaperPageState> = this.paperPagesSubject.asObservable();

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
        this.selectedPortfolioSubject.next(null);
        this.proposalsSubject.next([]);
        this.valuationsSubject.next([]);
        this.modeHistorySubject.next([]);
        this.intentsSubject.next([]);
        this.receivablesSubject.next([]);
        this.actionsSubject.next([]);
        this.adoptionsSubject.next([]);
        this.paperPagesSubject.next(this.initialPaperPages);
      }),
      switchMap((id) =>
        forkJoin({
          detail: this.http.get<ResearchPortfolioDetail>(`/api/research/portfolios/${id}`),
          proposals: this.http.get<PagedResponse<PaperProposal>>(`/api/research/portfolios/${id}/proposals?limit=50&offset=0`),
          valuations: this.http.get<PagedResponse<PaperValuation>>(`/api/research/portfolios/${id}/valuations?limit=200&offset=0`),
          modeHistory: this.http.get<PaperModeHistory[]>(`/api/research/portfolios/${id}/mode-history`),
          intents: this.http.get<PagedResponse<PaperExecutionIntent>>(`/api/research/portfolios/${id}/intents?limit=100&offset=0`),
          receivables: this.http.get<PagedResponse<PaperReceivable>>(`/api/research/portfolios/${id}/receivables?limit=100&offset=0`),
          actions: this.http.get<PagedResponse<PaperProcessedAction>>(`/api/research/portfolios/${id}/actions?limit=100&offset=0`),
          adoptions: this.http.get<PagedResponse<PaperDatasetAdoption>>(`/api/research/portfolios/${id}/adoptions?limit=100&offset=0`),
        }).pipe(
          catchError((error: unknown) => {
            console.error(`Failed to load portfolio detail ${id}:`, error);
            this.errorSubject.next('Failed to load portfolio detail');
            return of(null);
          })
        )
      )
    ).subscribe((state) => {
      if (state) {
        this.selectedPortfolioSubject.next(state.detail);
        this.proposalsSubject.next(state.proposals.items);
        this.valuationsSubject.next(state.valuations.items);
        this.modeHistorySubject.next(state.modeHistory);
        this.intentsSubject.next(state.intents.items);
        this.receivablesSubject.next(state.receivables.items);
        this.actionsSubject.next(state.actions.items);
        this.adoptionsSubject.next(state.adoptions.items);
        this.paperPagesSubject.next({
          proposals: this.pageMetadata(state.proposals),
          valuations: this.pageMetadata(state.valuations),
          intents: this.pageMetadata(state.intents),
          receivables: this.pageMetadata(state.receivables),
          actions: this.pageMetadata(state.actions),
          adoptions: this.pageMetadata(state.adoptions),
        });
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

  public adoptDataset(id: string, req: AdoptDatasetRequest): Observable<DatasetAdoptionResult> {
    const key = typeof crypto !== 'undefined' && crypto.randomUUID ? crypto.randomUUID() : 'adopt-' + Date.now();
    const headers = new HttpHeaders({
      'Content-Type': 'application/json',
      'Idempotency-Key': key,
    });

    return this.http.post<DatasetAdoptionResult>(`/api/research/portfolios/${id}/adopt-dataset`, req, { headers }).pipe(
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
        this.selectPortfolio(id);
      })
    );
  }

  public loadProposals(id: string, limit: number = 20, offset: number = 0): void {
    this.http.get<PagedResponse<PaperProposal>>(`/api/research/portfolios/${id}/proposals?limit=${limit}&offset=${offset}`).subscribe({
      next: (res) => {
        this.proposalsSubject.next(res.items || []);
        this.updatePage('proposals', res);
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
        this.selectPortfolio(id);
      })
    );
  }

  public changeApprovalMode(id: string, req: ChangeApprovalModeRequest): Observable<ModeChangeResponse> {
    const key = typeof crypto !== 'undefined' && crypto.randomUUID ? crypto.randomUUID() : 'mode-' + Date.now();
    const headers = new HttpHeaders({
      'Content-Type': 'application/json',
      'Idempotency-Key': key,
    });

    return this.http.post<ModeChangeResponse>(`/api/research/portfolios/${id}/mode`, req, { headers }).pipe(
      tap(() => {
        this.selectPortfolio(id);
      })
    );
  }

  public loadModeHistory(id: string): void {
    this.http.get<PaperModeHistory[]>(`/api/research/portfolios/${id}/mode-history`).subscribe({
      next: (res) => this.modeHistorySubject.next(res || []),
      error: (err) => console.error('Failed to load mode history', err),
    });
  }

  public processPortfolioEvents(id: string): Observable<ProcessEventsResponse> {
    const key = typeof crypto !== 'undefined' && crypto.randomUUID ? crypto.randomUUID() : 'proc-' + Date.now();
    const headers = new HttpHeaders({
      'Content-Type': 'application/json',
      'Idempotency-Key': key,
    });

    return this.http.post<ProcessEventsResponse>(`/api/research/portfolios/${id}/process`, {}, { headers }).pipe(
      tap(() => {
        this.selectPortfolio(id);
      })
    );
  }

  public loadValuations(id: string, limit: number = 100, offset: number = 0): void {
    this.http.get<PagedResponse<PaperValuation>>(`/api/research/portfolios/${id}/valuations?limit=${limit}&offset=${offset}`).subscribe({
      next: (res) => {
        this.valuationsSubject.next(res.items || []);
        this.updatePage('valuations', res);
      },
      error: (err) => console.error('Failed to load valuations', err),
    });
  }

  public loadIntents(id: string, limit = 100, offset = 0): void {
    this.loadPaperPage(id, 'intents', limit, offset, this.intentsSubject);
  }

  public loadReceivables(id: string, limit = 100, offset = 0): void {
    this.loadPaperPage(id, 'receivables', limit, offset, this.receivablesSubject);
  }

  public loadActions(id: string, limit = 100, offset = 0): void {
    this.loadPaperPage(id, 'actions', limit, offset, this.actionsSubject);
  }

  public loadAdoptions(id: string, limit = 100, offset = 0): void {
    this.loadPaperPage(id, 'adoptions', limit, offset, this.adoptionsSubject);
  }

  private loadPaperPage<T>(id: string, name: PaperPageName, limit: number, offset: number, subject: BehaviorSubject<T[]>): void {
    this.http.get<PagedResponse<T>>(`/api/research/portfolios/${id}/${name}?limit=${limit}&offset=${offset}`).subscribe({
      next: (response) => {
        subject.next(response.items || []);
        this.updatePage(name, response);
      },
      error: (error) => console.error(`Failed to load ${name}`, error),
    });
  }

  private updatePage(name: PaperPageName, response: PagedResponse<unknown>): void {
    this.paperPagesSubject.next({ ...this.paperPagesSubject.value, [name]: this.pageMetadata(response) });
  }

  private pageMetadata(response: PagedResponse<unknown>): PaperPageMetadata {
    return {
      total: response.total,
      limit: response.limit,
      offset: response.offset,
      isComplete: response.isComplete,
    };
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
