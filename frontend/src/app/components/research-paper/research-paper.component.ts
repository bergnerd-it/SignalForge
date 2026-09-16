import { Component, OnInit, OnDestroy, ChangeDetectorRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Subscription } from 'rxjs';
import { ActivatedRoute, NavigationEnd, Router } from '@angular/router';
import { Location } from '@angular/common';
import { filter } from 'rxjs/operators';
import { ResearchService } from '../../services/research.service';
import {
  ResearchPortfolioSummary,
  ResearchPortfolioDetail,
  PaperProposal,
  PaperValuation,
  PaperModeHistory,
  AssistantChatRequest,
  AssistantChatResponse,
  PaperExecutionIntent,
  PaperReceivable,
  PaperProcessedAction,
  PaperDatasetAdoption,
  PaperPageMetadata,
  PaperPageName,
  PaperPageState,
} from '../../models/research.model';

@Component({
  selector: 'app-research-paper',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './research-paper.component.html',
  styleUrl: './research-paper.component.css',
})
export class ResearchPaperComponent implements OnInit, OnDestroy {
  public portfolios: ResearchPortfolioSummary[] = [];
  public selectedPortfolio: ResearchPortfolioDetail | null = null;
  public proposals: PaperProposal[] = [];
  public valuations: PaperValuation[] = [];
  public modeHistory: PaperModeHistory[] = [];
  public intents: PaperExecutionIntent[] = [];
  public receivables: PaperReceivable[] = [];
  public processedActions: PaperProcessedAction[] = [];
  public adoptions: PaperDatasetAdoption[] = [];
  public paperPages: PaperPageState = {
    proposals: { total: 0, limit: 50, offset: 0, isComplete: true },
    valuations: { total: 0, limit: 200, offset: 0, isComplete: true },
    intents: { total: 0, limit: 100, offset: 0, isComplete: true },
    receivables: { total: 0, limit: 100, offset: 0, isComplete: true },
    actions: { total: 0, limit: 100, offset: 0, isComplete: true },
    adoptions: { total: 0, limit: 100, offset: 0, isComplete: true },
  };

  public activeTab: 'proposals' | 'valuations' | 'positions' | 'intents' | 'events' | 'modeHistory' | 'assistant' = 'proposals';
  public isLoading: boolean = false;
  public errorMessage: string | null = null;
  public modeNotice: string | null = null;

  // New portfolio modal
  public showCreateModal: boolean = false;
  public newPortName: string = 'EUR Paper Growth';
  public newPortCash: string = '10000.00';

  // Activation modal
  public showActivateModal: boolean = false;
  public actStrategyId: string = 'ETF_BUY_HOLD_V1';
  public actStrategyVersion: string = '1.0.0';
  public actUniverseId: string = 'uni-default';
  public actBenchmarkId: string = 'listing-eur-syn-1';
  public actApprovalMode: 'MANUAL' | 'AUTO_PAPER' = 'MANUAL';

  // Adopt modal
  public showAdoptModal: boolean = false;
  public adoptDatasetId: string = '';

  // Assistant state
  public assistantMessage: string = '';
  public assistantResponse: AssistantChatResponse | null = null;
  public isAssistantThinking: boolean = false;

  private subscriptions: Subscription = new Subscription();

  public get valuationChartPoints(): string {
    const values = [...this.valuations]
      .reverse()
      .filter((valuation) => valuation.totalEquity !== null)
      .map((valuation) => Number(valuation.totalEquity))
      .filter((value) => Number.isFinite(value));
    if (values.length === 0) return '';
    const minimum = Math.min(...values);
    const maximum = Math.max(...values);
    const range = maximum - minimum || 1;
    return values.map((value, index) => {
      const x = values.length === 1 ? 50 : (index * 100) / (values.length - 1);
      const y = 38 - ((value - minimum) / range) * 34;
      return `${x},${y}`;
    }).join(' ');
  }

  constructor(
    public readonly researchService: ResearchService,
    private readonly route: ActivatedRoute,
    private readonly router: Router,
    private readonly location: Location,
    private readonly cdr: ChangeDetectorRef
  ) {}

  ngOnInit(): void {
    const initialRouteId = this.routePortfolioId();
    if (initialRouteId) {
      this.researchService.selectPortfolio(initialRouteId);
    }

    this.subscriptions.add(
      this.router.events.pipe(filter((event): event is NavigationEnd => event instanceof NavigationEnd)).subscribe(() => {
        const id = this.routePortfolioId();
        if (id) this.researchService.selectPortfolio(id);
      })
    );

    this.subscriptions.add(
      this.researchService.portfolios$.subscribe((list) => {
        this.portfolios = list;
        this.cdr.markForCheck();
        const currentRouteId = this.routePortfolioId();
        if (list.length > 0 && !this.selectedPortfolio && !currentRouteId) {
          this.researchService.selectPortfolio(list[0].id);
        }
      })
    );

    this.subscriptions.add(
      this.researchService.selectedPortfolio$.subscribe((detail) => {
        this.selectedPortfolio = detail;
        this.assistantResponse = null; // Clear context-specific evidence on portfolio switch
        this.cdr.markForCheck();
      })
    );

    this.subscriptions.add(
      this.researchService.proposals$.subscribe((props) => {
        this.proposals = props;
        this.cdr.markForCheck();
      })
    );

    this.subscriptions.add(
      this.researchService.valuations$.subscribe((vals) => {
        this.valuations = vals;
        this.cdr.markForCheck();
      })
    );

    this.subscriptions.add(
      this.researchService.modeHistory$.subscribe((hist) => {
        this.modeHistory = hist;
        this.cdr.markForCheck();
      })
    );
    this.subscriptions.add(this.researchService.intents$.subscribe((items) => {
      this.intents = items;
      this.cdr.markForCheck();
    }));
    this.subscriptions.add(this.researchService.receivables$.subscribe((items) => {
      this.receivables = items;
      this.cdr.markForCheck();
    }));
    this.subscriptions.add(this.researchService.actions$.subscribe((items) => {
      this.processedActions = items;
      this.cdr.markForCheck();
    }));
    this.subscriptions.add(this.researchService.adoptions$.subscribe((items) => {
      this.adoptions = items;
      this.cdr.markForCheck();
    }));
    this.subscriptions.add(this.researchService.paperPages$.subscribe((pages) => {
      this.paperPages = pages;
      this.cdr.markForCheck();
    }));

    this.subscriptions.add(
      this.researchService.loading$.subscribe((loading) => {
        this.isLoading = loading;
        this.cdr.markForCheck();
      })
    );

    this.subscriptions.add(
      this.researchService.error$.subscribe((err) => {
        this.errorMessage = err;
        this.cdr.markForCheck();
      })
    );
  }

  ngOnDestroy(): void {
    this.subscriptions.unsubscribe();
  }

  private routePortfolioId(): string | null {
    const activatedId = this.route.snapshot.paramMap.get('id');
    if (activatedId) return activatedId;
    let current = this.router.routerState.snapshot.root;
    while (current) {
      const id = current.paramMap.get('id');
      if (id) return id;
      if (!current.firstChild) break;
      current = current.firstChild;
    }
    const routePattern = /^\/research\/portfolios\/([^/?#]+)\/?$/;
    const match = routePattern.exec(this.location.path()) ||
      (typeof window !== 'undefined' ? routePattern.exec(window.location.pathname) : null);
    return match ? decodeURIComponent(match[1]) : null;
  }

  public onSelectPortfolio(id: string): void {
    void this.router.navigate(['/research/portfolios', id]);
  }

  public changePage(name: PaperPageName, direction: -1 | 1): void {
    if (!this.selectedPortfolio) return;
    const page = this.paperPages[name];
    const offset = Math.max(0, page.offset + direction * page.limit);
    if (offset === page.offset || (direction === 1 && page.isComplete)) return;
    const loaders: Record<PaperPageName, (id: string, limit: number, pageOffset: number) => void> = {
      proposals: (id, limit, pageOffset) => this.researchService.loadProposals(id, limit, pageOffset),
      valuations: (id, limit, pageOffset) => this.researchService.loadValuations(id, limit, pageOffset),
      intents: (id, limit, pageOffset) => this.researchService.loadIntents(id, limit, pageOffset),
      receivables: (id, limit, pageOffset) => this.researchService.loadReceivables(id, limit, pageOffset),
      actions: (id, limit, pageOffset) => this.researchService.loadActions(id, limit, pageOffset),
      adoptions: (id, limit, pageOffset) => this.researchService.loadAdoptions(id, limit, pageOffset),
    };
    loaders[name](this.selectedPortfolio.id, page.limit, offset);
  }

  public pageStart(page: PaperPageMetadata): number {
    return page.total === 0 ? 0 : page.offset + 1;
  }

  public pageEnd(page: PaperPageMetadata): number {
    return Math.min(page.total, page.offset + page.limit);
  }

  public createPortfolio(): void {
    if (!this.newPortName || !this.newPortCash) return;
    this.researchService.createPortfolio({
      name: this.newPortName.trim(),
      mode: 'PAPER',
      baseCurrency: 'EUR',
      initialCash: this.newPortCash.trim(),
    }).subscribe({
      next: (created) => {
        this.showCreateModal = false;
        this.cdr.markForCheck();
        if (created?.id) {
          this.onSelectPortfolio(created.id);
        }
      },
      error: (err) => {
        this.errorMessage = 'Failed to create paper portfolio: ' + (err.error?.message || err.message);
        this.cdr.markForCheck();
      },
    });
  }

  public activatePortfolio(): void {
    if (!this.selectedPortfolio) return;
    this.researchService.activatePortfolio(this.selectedPortfolio.id, {
      strategyId: this.actStrategyId,
      strategyVersion: this.actStrategyVersion,
      universeId: this.actUniverseId,
      benchmarkListingId: this.actBenchmarkId,
      costPolicy: {
        commissionPerFill: '1.00',
        bidAskSpreadBps: '10',
        slippageBps: '5',
      },
      approvalMode: this.actApprovalMode,
    }).subscribe({
      next: () => {
        this.showActivateModal = false;
        this.cdr.markForCheck();
      },
      error: (err) => {
        this.errorMessage = 'Activation error: ' + (err.error?.message || err.message);
        this.cdr.markForCheck();
      },
    });
  }

  public adoptDataset(): void {
    if (!this.selectedPortfolio || !this.adoptDatasetId) return;
    this.researchService.adoptDataset(this.selectedPortfolio.id, {
      datasetId: this.adoptDatasetId.trim(),
    }).subscribe({
      next: () => {
        this.showAdoptModal = false;
        this.cdr.markForCheck();
      },
      error: (err) => {
        this.errorMessage = 'Adoption error: ' + (err.error?.message || err.message);
        this.cdr.markForCheck();
      },
    });
  }

  public evaluateStrategy(): void {
    if (!this.selectedPortfolio) return;
    this.researchService.evaluatePortfolio(this.selectedPortfolio.id).subscribe({
      next: () => {
        this.activeTab = 'proposals';
        this.cdr.markForCheck();
      },
      error: (err) => {
        this.errorMessage = 'Evaluation error: ' + (err.error?.message || err.message);
        this.cdr.markForCheck();
      },
    });
  }

  public acceptProposal(proposalId: string): void {
    if (!this.selectedPortfolio) return;
    this.researchService.acceptProposal(this.selectedPortfolio.id, proposalId).subscribe({
      next: () => {},
      error: (err) => {
        this.errorMessage = 'Acceptance error: ' + (err.error?.message || err.message);
        this.cdr.markForCheck();
      },
    });
  }

  public rejectProposal(proposalId: string): void {
    if (!this.selectedPortfolio) return;
    this.researchService.rejectProposal(this.selectedPortfolio.id, proposalId, {
      rejectionReason: 'Declined by user',
    }).subscribe({
      next: () => {},
      error: (err) => {
        this.errorMessage = 'Rejection error: ' + (err.error?.message || err.message);
        this.cdr.markForCheck();
      },
    });
  }

  public toggleMode(): void {
    if (!this.selectedPortfolio?.activeSegment) return;
    const currentMode = this.selectedPortfolio.activeSegment.approvalMode;
    const newMode = currentMode === 'MANUAL' ? 'AUTO_PAPER' : 'MANUAL';
    const notes = newMode === 'AUTO_PAPER' ? 'Enabled automatic paper execution' : 'Disabled automatic paper execution';

    this.researchService.changeApprovalMode(this.selectedPortfolio.id, {
      approvalMode: newMode,
      notes,
    }).subscribe({
      next: () => {
        if (newMode === 'MANUAL') {
          this.modeNotice = 'AUTO_PAPER disabled: pending previously accepted execution intents remain intact, but no new proposals will be automatically generated.';
        } else {
          this.modeNotice = 'AUTO_PAPER enabled: future proposals will be automatically scheduled and executed at eligible market opens.';
        }
        this.cdr.markForCheck();
      },
      error: (err) => {
        this.errorMessage = 'Mode toggle error: ' + (err.error?.message || err.message);
        this.cdr.markForCheck();
      },
    });
  }

  public processEvents(): void {
    if (!this.selectedPortfolio) return;
    this.researchService.processPortfolioEvents(this.selectedPortfolio.id).subscribe({
      next: () => {},
      error: (err) => {
        this.errorMessage = 'Process events error: ' + (err.error?.message || err.message);
        this.cdr.markForCheck();
      },
    });
  }

  public exportAuditZip(): void {
    if (!this.selectedPortfolio) return;
    this.researchService.exportAuditZip(this.selectedPortfolio.id).subscribe({
      error: (err) => {
        this.errorMessage = 'Failed to download audit archive: ' + (err.error?.message || err.message);
        this.cdr.markForCheck();
      },
    });
  }

  public sendAssistantQuery(): void {
    if (!this.assistantMessage.trim()) return;
    const msg = this.assistantMessage.trim();
    this.isAssistantThinking = true;
    this.assistantResponse = null;

    const req: AssistantChatRequest = {
      message: msg,
    };
    if (this.selectedPortfolio) {
      req.context = {
        contextType: 'PORTFOLIO',
        contextId: this.selectedPortfolio.id,
      };
    }

    this.researchService.sendAssistantChat(req).subscribe({
      next: (res) => {
        this.assistantResponse = res;
        this.isAssistantThinking = false;
        this.assistantMessage = '';
        this.cdr.markForCheck();
      },
      error: (err) => {
        this.errorMessage = 'Assistant error: ' + (err.error?.message || err.message);
        this.isAssistantThinking = false;
        this.cdr.markForCheck();
      },
    });
  }
}
