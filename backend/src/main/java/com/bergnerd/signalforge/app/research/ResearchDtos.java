package com.bergnerd.signalforge.app.research;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public class ResearchDtos {

    public record PagedResponse<T>(
            List<T> items,
            int total,
            int limit,
            int offset,
            boolean isComplete
    ) {}

    public record CreatePortfolioRequest(
            @NotBlank(message = "Name is required")
            String name,

            @NotBlank(message = "Mode is required (must be PAPER)")
            String mode,

            @NotBlank(message = "Base currency is required (must be EUR)")
            String baseCurrency,

            @NotBlank(message = "Initial cash is required")
            String initialCash,

            String idempotencyKey
    ) {}

    public record CostPolicyDto(
            String commissionPerFill,
            String bidAskSpreadBps,
            String slippageBps
    ) {}

    public record ActivatePortfolioRequest(
            @NotBlank(message = "Strategy ID is required")
            String strategyId,

            @NotBlank(message = "Strategy version is required")
            String strategyVersion,

            @NotBlank(message = "Universe ID is required")
            String universeId,

            @NotBlank(message = "Benchmark listing ID is required")
            String benchmarkListingId,

            @NotNull(message = "Cost policy is required")
            CostPolicyDto costPolicy,

            @NotBlank(message = "Approval mode is required (MANUAL or AUTO_PAPER)")
            String approvalMode,

            String datasetId
    ) {
        public ActivatePortfolioRequest(
                String strategyId, String strategyVersion, String universeId,
                String benchmarkListingId, CostPolicyDto costPolicy, String approvalMode
        ) {
            this(strategyId, strategyVersion, universeId, benchmarkListingId, costPolicy, approvalMode, null);
        }
    }

    public record AdoptDatasetRequest(
            @NotBlank(message = "Dataset ID is required")
            String datasetId
    ) {}

    public record ChangeApprovalModeRequest(
            @NotBlank(message = "Approval mode is required (MANUAL or AUTO_PAPER)")
            String approvalMode,

            String notes
    ) {}

    public record AcceptProposalRequest(
            String reason
    ) {}

    public record RejectProposalRequest(
            @NotBlank(message = "Rejection reason is required")
            String rejectionReason
    ) {}

    public record PaperSegmentDto(
            String id,
            String portfolioId,
            String strategyId,
            String strategyVersion,
            String universeId,
            String benchmarkListingId,
            CostPolicyDto costPolicy,
            String approvalMode,
            String status,
            String initialEquity,
            String openingObservationInstant,
            String adoptedDatasetId,
            String adoptedAt,
            String createdAt
    ) {}

    public record PaperProposalItemDto(
            String id,
            String proposalId,
            String listingId,
            int rank,
            String targetWeight,
            String cutoffEstimatedUnits,
            String score,
            String reasonCode,
            String reasonDescription,
            String rawPriceReference
    ) {}

    public record PaperProposalObservationDto(
            String id,
            String proposalId,
            String listingId,
            String observationSessionDate,
            String observationType,
            String observationValue,
            String observedAt
    ) {}

    public record PaperProposalDto(
            String id,
            String portfolioId,
            String cycleId,
            String strategyId,
            String strategyVersion,
            String datasetId,
            String datasetChecksum,
            String calendarId,
            String calendarVersion,
            String evaluationSessionDate,
            String inputCutoffInstant,
            String evaluationInstant,
            String scheduledOpenSessionDate,
            String scheduledOpenInstant,
            String reasonCode,
            int portfolioStateVersion,
            String status,
            String acceptedAt,
            String rejectedAt,
            String rejectionReason,
            String supersedingProposalId,
            String reinvestmentReceivableId,
            String createdAt,
            List<PaperProposalItemDto> items,
            List<PaperProposalObservationDto> observations
    ) {}

    public record PaperModeHistoryDto(
            String id,
            String portfolioId,
            String fromMode,
            String toMode,
            String transitionInstant,
            String triggerType,
            String notes
    ) {}

    public record PaperReceivableDto(
            String id,
            String portfolioId,
            String listingId,
            String sourceNamespace,
            String actionId,
            String actionType,
            String recordInstant,
            String exDate,
            String paymentDate,
            String paymentInstant,
            String availabilityInstant,
            String grossAmount,
            String withholdingTax,
            String netAmount,
            String status,
            String paidOperationId,
            String paidAt,
            String createdAt,
            String datasetId,
            String datasetChecksum,
            String termsHash
    ) {}

    public record PaperIntentTransitionDto(
            String id,
            String intentId,
            String fromStatus,
            String toStatus,
            String triggerType,
            String transitionInstant,
            String notes
    ) {}

    public record PaperExecutionIntentDto(
            String id,
            String portfolioId,
            String proposalId,
            String reinvestmentReceivableId,
            String orderType,
            String scheduledSessionDate,
            String scheduledOpenInstant,
            String approvalMode,
            String status,
            String createdAt,
            List<PaperIntentTransitionDto> transitions,
            List<PaperExecutionResultDto> results
    ) {}

    public record PaperProcessedActionDto(
            String id,
            String portfolioId,
            String sourceNamespace,
            String listingId,
            String actionId,
            String actionType,
            String termsHash,
            String effectiveDate,
            String availabilityInstant,
            String processingInstant,
            String datasetId,
            String datasetChecksum,
            String status,
            String linkedOperationId,
            String linkedReceivableId
    ) {}

    public record PaperDatasetAdoptionDto(
            String id,
            String portfolioId,
            String datasetId,
            String datasetChecksum,
            String coverageStartSession,
            String coverageEndSession,
            String validationStatus,
            String rejectionReason,
            String adoptedAt
    ) {}

    public record PaperExecutionResultDto(
            String id,
            String intentId,
            String proposalId,
            String reinvestmentReceivableId,
            String operationId,
            String executionId,
            String listingId,
            String side,
            String requestedQuantity,
            String executedQuantity,
            String shortfallReason,
            String rawOpenPrice,
            String fillPrice,
            String commission,
            String spreadSlippageCost,
            String costBasis,
            String realizedGain,
            String datasetId,
            String datasetChecksum,
            String marketEffectiveInstant,
            String observedInstant,
            String bookedInstant
    ) {}

    public record PaperValuationDto(
            String id,
            String portfolioId,
            String sessionDate,
            String observationKind,
            String observationInstant,
            String cashBalance,
            String positionsMarketValue,
            String receivablesValue,
            String totalEquity,
            String cumulativeReturn,
            String highWaterMark,
            String drawdown,
            String dataReadinessStatus,
            boolean isComplete,
            String lastSupportedObservationInstant,
            String missingRequirementsDetail,
            String adoptedDatasetId,
            String adoptedDatasetChecksum
    ) {}

    public record ResearchPortfolioSummary(
            String id,
            String ownerId,
            String name,
            String mode,
            String baseCurrency,
            String cashBalance,
            int revision,
            String createdAt,
            String paperStartedAt,
            String strategyTracking,
            int positionCount,
            String approvalMode,
            String segmentStatus,
            String strategyId,
            String universeId,
            String adoptedDatasetId
    ) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ResearchPortfolioDetail(
            String id,
            String ownerId,
            String name,
            String mode,
            String baseCurrency,
            String cashBalance,
            int revision,
            String createdAt,
            String paperStartedAt,
            String strategyTracking,
            String valuationStatus,
            String marketValue,
            String unrealizedPnl,
            List<ResearchPositionItem> positions,
            PaperSegmentDto activeSegment,
            String receivablesValue,
            String totalEquity,
            String dataReadinessStatus,
            int pendingProposalCount,
            int pendingIntentCount
    ) {}

    public record ResearchPositionItem(
            String listingId,
            String ticker,
            String quantity,
            String totalAcquisitionCost,
            String averageCost,
            String updatedAt
    ) {}

    public record ListingDto(
            String id,
            String instrumentId,
            String venue,
            String symbol,
            String quoteCurrency,
            String identityStatus
    ) {}

    public record InstrumentDto(
            String id,
            String type,
            String name,
            String isin,
            String provenance,
            List<ListingDto> listings
    ) {}
}
