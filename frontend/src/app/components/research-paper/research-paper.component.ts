import { Component, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Subscription } from 'rxjs';
import { ResearchService } from '../../services/research.service';
import {
  ResearchPortfolioSummary,
  ResearchPortfolioDetail,
  PaperProposal,
  PaperValuation,
  PaperModeHistory,
  AssistantChatResponse,
  FactCard,
  EvidenceReference,
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

  public activeTab: 'proposals' | 'valuations' | 'positions' | 'modeHistory' | 'assistant' = 'proposals';
  public isLoading: boolean = false;
  public errorMessage: string | null = null;

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

  constructor(public readonly researchService: ResearchService) {}

  ngOnInit(): void {
    this.subscriptions.add(
      this.researchService.portfolios$.subscribe((list) => {
        this.portfolios = list;
        if (list.length > 0 && !this.selectedPortfolio) {
          this.researchService.selectPortfolio(list[0].id);
        }
      })
    );

    this.subscriptions.add(
      this.researchService.selectedPortfolio$.subscribe((detail) => {
        this.selectedPortfolio = detail;
        if (detail) {
          this.researchService.loadProposals(detail.id);
          this.researchService.loadValuations(detail.id);
          this.researchService.loadModeHistory(detail.id);
        }
      })
    );

    this.subscriptions.add(
      this.researchService.proposals$.subscribe((props) => {
        this.proposals = props;
      })
    );

    this.subscriptions.add(
      this.researchService.valuations$.subscribe((vals) => {
        this.valuations = vals;
      })
    );

    this.subscriptions.add(
      this.researchService.modeHistory$.subscribe((hist) => {
        this.modeHistory = hist;
      })
    );

    this.subscriptions.add(
      this.researchService.loading$.subscribe((loading) => {
        this.isLoading = loading;
      })
    );

    this.subscriptions.add(
      this.researchService.error$.subscribe((err) => {
        this.errorMessage = err;
      })
    );
  }

  ngOnDestroy(): void {
    this.subscriptions.unsubscribe();
  }

  public onSelectPortfolio(id: string): void {
    this.researchService.selectPortfolio(id);
  }

  public createPortfolio(): void {
    if (!this.newPortName || !this.newPortCash) return;
    this.researchService.createPortfolio({
      name: this.newPortName.trim(),
      mode: 'PAPER',
      baseCurrency: 'EUR',
      initialCash: this.newPortCash.trim(),
    }).subscribe({
      next: () => {
        this.showCreateModal = false;
      },
      error: (err) => {
        this.errorMessage = 'Failed to create paper portfolio: ' + (err.error?.message || err.message);
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
      },
      error: (err) => {
        this.errorMessage = 'Activation error: ' + (err.error?.message || err.message);
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
      },
      error: (err) => {
        this.errorMessage = 'Adoption error: ' + (err.error?.message || err.message);
      },
    });
  }

  public evaluateStrategy(): void {
    if (!this.selectedPortfolio) return;
    this.researchService.evaluatePortfolio(this.selectedPortfolio.id).subscribe({
      next: () => {
        this.activeTab = 'proposals';
      },
      error: (err) => {
        this.errorMessage = 'Evaluation error: ' + (err.error?.message || err.message);
      },
    });
  }

  public acceptProposal(proposalId: string): void {
    if (!this.selectedPortfolio) return;
    this.researchService.acceptProposal(this.selectedPortfolio.id, proposalId).subscribe({
      next: () => {},
      error: (err) => {
        this.errorMessage = 'Acceptance error: ' + (err.error?.message || err.message);
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
      next: () => {},
      error: (err) => {
        this.errorMessage = 'Mode toggle error: ' + (err.error?.message || err.message);
      },
    });
  }

  public processEvents(): void {
    if (!this.selectedPortfolio) return;
    this.researchService.processPortfolioEvents(this.selectedPortfolio.id).subscribe({
      next: () => {},
      error: (err) => {
        this.errorMessage = 'Process events error: ' + (err.error?.message || err.message);
      },
    });
  }

  public exportAuditZip(): void {
    if (!this.selectedPortfolio) return;
    this.researchService.exportAuditZip(this.selectedPortfolio.id);
  }

  public sendAssistantQuery(): void {
    if (!this.assistantMessage.trim()) return;
    const msg = this.assistantMessage.trim();
    this.isAssistantThinking = true;
    this.assistantResponse = null;

    const req: any = {
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
      },
      error: (err) => {
        this.errorMessage = 'Assistant error: ' + (err.error?.message || err.message);
        this.isAssistantThinking = false;
      },
    });
  }
}
