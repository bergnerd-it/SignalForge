package com.bergnerd.signalforge.app.research.backtest;

import com.bergnerd.signalforge.app.accounting.AccountingCore;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;

public final class BacktestDtos {

    private BacktestDtos() {}

    public enum Status {
        QUEUED,
        RUNNING,
        COMPLETED,
        FAILED,
        CANCELLED,
        INTERRUPTED
    }

    public enum SeriesType {
        CANDIDATE,
        BENCHMARK
    }

    public enum OrderType {
        INITIAL_BUY,
        REINVEST,
        REBALANCE_BUY,
        REBALANCE_SELL
    }

    public enum OrderStatus {
        FILLED,
        SKIPPED
    }

    public enum EventType {
        FUNDING,
        SPLIT,
        ENTITLEMENT,
        PAYMENT,
        EXECUTION,
        CLOSING_MARK
    }

    public record CreateBacktestRequest(
            String datasetId,
            String candidateListingId,
            String benchmarkListingId,
            String evaluationCutoff,
            String requestedStartDate,
            String requestedEndDate,
            String initialCash,
            String currency,
            String commissionPerFill,
            String spreadBps,
            String slippageBps,
            String strategyId,
            String strategyVersion,
            String universeId,
            String parametersJson,
            String experimentId
    ) {
        public CreateBacktestRequest(
                String datasetId,
                String candidateListingId,
                String benchmarkListingId,
                String evaluationCutoff,
                String requestedStartDate,
                String requestedEndDate,
                String initialCash,
                String currency,
                String commissionPerFill,
                String spreadBps,
                String slippageBps,
                String strategyId,
                String strategyVersion
        ) {
            this(datasetId, candidateListingId, benchmarkListingId, evaluationCutoff,
                    requestedStartDate, requestedEndDate, initialCash, currency,
                    commissionPerFill, spreadBps, slippageBps, strategyId, strategyVersion,
                    null, null, null);
        }

        public CreateBacktestRequest {
            if (strategyId == null || strategyId.isBlank()) {
                strategyId = "ETF_BUY_HOLD_V1";
            }
            if (strategyVersion == null || strategyVersion.isBlank()) {
                strategyVersion = "1.0.0";
            }
            if (currency == null || currency.isBlank()) {
                currency = "EUR";
            }
            if (initialCash == null || initialCash.isBlank()) {
                initialCash = "1000.00";
            }
            if (commissionPerFill == null || commissionPerFill.isBlank()) {
                commissionPerFill = "1.00";
            }
            if (spreadBps == null || spreadBps.isBlank()) {
                spreadBps = "10";
            }
            if (slippageBps == null || slippageBps.isBlank()) {
                slippageBps = "5";
            }
        }

        public String canonicalHash() {
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                BigDecimal normCash = AccountingCore.normalizeStartingCash(new BigDecimal(initialCash));
                BigDecimal normCommission = AccountingCore.normalizeCash(new BigDecimal(commissionPerFill), "commission");
                BigDecimal rawSpread = new BigDecimal(spreadBps);
                if (rawSpread.scale() > 4) {
                    throw new IllegalArgumentException("Spread precision exceeds maximum supported scale of 4: " + spreadBps);
                }
                BigDecimal rawSlippage = new BigDecimal(slippageBps);
                if (rawSlippage.scale() > 4) {
                    throw new IllegalArgumentException("Slippage precision exceeds maximum supported scale of 4: " + slippageBps);
                }
                BigDecimal normSpread = rawSpread.stripTrailingZeros();
                BigDecimal normSlippage = rawSlippage.stripTrailingZeros();

                String canonical = String.join("|",
                        strategyId.trim(),
                        strategyVersion.trim(),
                        datasetId.trim(),
                        candidateListingId != null ? candidateListingId.trim() : "",
                        benchmarkListingId.trim(),
                        universeId != null ? universeId.trim() : "",
                        parametersJson != null ? parametersJson.trim() : "",
                        evaluationCutoff.trim(),
                        requestedStartDate.trim(),
                        requestedEndDate.trim(),
                        normCash.toPlainString(),
                        currency.trim().toUpperCase(),
                        normCommission.toPlainString(),
                        normSpread.toPlainString(),
                        normSlippage.toPlainString()
                );
                byte[] digest = md.digest(canonical.getBytes(StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                for (byte b : digest) {
                    sb.append(String.format("%02x", b));
                }
                return sb.toString();
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 not available", e);
            }
        }
    }

    public record BacktestNormalizedConfig(
            String strategyId,
            String strategyVersion,
            String datasetId,
            String datasetInputChecksum,
            String datasetContentChecksum,
            String parserVersion,
            String schemaVersion,
            String calendarId,
            String calendarTimezone,
            String coverageStart,
            String coverageEnd,
            String candidateListingId,
            String benchmarkListingId,
            String quoteCurrency,
            String initialCash,
            String evaluationCutoff,
            String selectedEvaluationSession,
            String selectedEndSession,
            String requestedStartDate,
            String requestedEndDate,
            String effectiveStartDate,
            String effectiveEndDate,
            String commissionPerFill,
            String spreadBps,
            String slippageBps,
            String costModelVersion,
            String accountingVersion,
            String executionModelVersion,
            String engineVersion,
            String sourceCommit,
            boolean dirtyFlag,
            String codeFingerprint,
            String classification,
            String availabilityAssumptions,
            String universeId,
            String parametersJson,
            String experimentId
    ) {
        public BacktestNormalizedConfig(
                String strategyId,
                String strategyVersion,
                String datasetId,
                String datasetInputChecksum,
                String datasetContentChecksum,
                String parserVersion,
                String schemaVersion,
                String calendarId,
                String calendarTimezone,
                String coverageStart,
                String coverageEnd,
                String candidateListingId,
                String benchmarkListingId,
                String quoteCurrency,
                String initialCash,
                String evaluationCutoff,
                String selectedEvaluationSession,
                String selectedEndSession,
                String requestedStartDate,
                String requestedEndDate,
                String effectiveStartDate,
                String effectiveEndDate,
                String commissionPerFill,
                String spreadBps,
                String slippageBps,
                String costModelVersion,
                String accountingVersion,
                String executionModelVersion,
                String engineVersion,
                String sourceCommit,
                boolean dirtyFlag,
                String codeFingerprint,
                String classification,
                String availabilityAssumptions
        ) {
            this(strategyId, strategyVersion, datasetId, datasetInputChecksum, datasetContentChecksum,
                    parserVersion, schemaVersion, calendarId, calendarTimezone, coverageStart, coverageEnd,
                    candidateListingId, benchmarkListingId, quoteCurrency, initialCash, evaluationCutoff,
                    selectedEvaluationSession, selectedEndSession, requestedStartDate, requestedEndDate,
                    effectiveStartDate, effectiveEndDate, commissionPerFill, spreadBps, slippageBps,
                    costModelVersion, accountingVersion, executionModelVersion, engineVersion, sourceCommit,
                    dirtyFlag, codeFingerprint, classification, availabilityAssumptions, null, null, null);
        }

        public String canonicalHash() {
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                String canonical = String.join("|",
                        strategyId != null ? strategyId.trim() : "",
                        strategyVersion != null ? strategyVersion.trim() : "",
                        datasetId != null ? datasetId.trim() : "",
                        datasetInputChecksum != null ? datasetInputChecksum.trim() : "",
                        datasetContentChecksum != null ? datasetContentChecksum.trim() : "",
                        parserVersion != null ? parserVersion.trim() : "",
                        schemaVersion != null ? schemaVersion.trim() : "",
                        calendarId != null ? calendarId.trim() : "",
                        calendarTimezone != null ? calendarTimezone.trim() : "",
                        candidateListingId != null ? candidateListingId.trim() : "",
                        benchmarkListingId != null ? benchmarkListingId.trim() : "",
                        universeId != null ? universeId.trim() : "",
                        parametersJson != null ? parametersJson.trim() : "",
                        quoteCurrency != null ? quoteCurrency.trim().toUpperCase() : "",
                        initialCash != null ? initialCash.trim() : "",
                        evaluationCutoff != null ? evaluationCutoff.trim() : "",
                        selectedEvaluationSession != null ? selectedEvaluationSession.trim() : "",
                        selectedEndSession != null ? selectedEndSession.trim() : "",
                        requestedStartDate != null ? requestedStartDate.trim() : "",
                        requestedEndDate != null ? requestedEndDate.trim() : "",
                        effectiveStartDate != null ? effectiveStartDate.trim() : "",
                        effectiveEndDate != null ? effectiveEndDate.trim() : "",
                        commissionPerFill != null ? commissionPerFill.trim() : "",
                        spreadBps != null ? spreadBps.trim() : "",
                        slippageBps != null ? slippageBps.trim() : "",
                        costModelVersion != null ? costModelVersion.trim() : "",
                        accountingVersion != null ? accountingVersion.trim() : "",
                        executionModelVersion != null ? executionModelVersion.trim() : "",
                        engineVersion != null ? engineVersion.trim() : "",
                        sourceCommit != null ? sourceCommit.trim() : "",
                        String.valueOf(dirtyFlag),
                        codeFingerprint != null ? codeFingerprint.trim() : ""
                );
                byte[] digest = md.digest(canonical.getBytes(StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                for (byte b : digest) {
                    sb.append(String.format("%02x", b));
                }
                return sb.toString();
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 not available", e);
            }
        }
    }

    public record UnpaidReceivableDto(
            String actionId,
            String amount,
            String entitlementDate,
            String entitlementTime,
            String paymentDate,
            String paymentInstant
    ) {}

    public record AnnualReturn(
            int year,
            Double candidateReturn,
            Double benchmarkReturn,
            String startEquity,
            String endEquity,
            boolean isPartial
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BacktestAnalyticsSummary(
            String initialEquity,
            String finalEquity,
            Double cumulativeReturn,
            Double cagr,
            Double benchmarkReturn,
            Double benchmarkDifference,
            Double maxDrawdown,
            String peakDate,
            String troughDate,
            String recoveryDate,
            Integer underwaterDurationDays,
            boolean isRecovered,
            Double annualizedVolatility,
            Double turnover,
            int fillCount,
            String totalCommissions,
            String totalSpreadSlippageEstimate,
            String realizedGain,
            String unrealizedGain,
            String endingCash,
            String endingReceivables,
            String endingHoldingsValue,
            String endingCostBasis,
            String endingUnits,
            Double exposureWeight,
            Double cashWeight,
            Double receivablesWeight,
            String turnoverFormula,
            String receivableTreatment,
            List<AnnualReturn> annualReturns,
            List<UnpaidReceivableDto> unpaidReceivables
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BacktestSummaryResponse(
            String id,
            String ownerId,
            String idempotencyKey,
            String canonicalHash,
            String status,
            int progressPct,
            String strategyId,
            String strategyVersion,
            String datasetId,
            String candidateListingId,
            String benchmarkListingId,
            String universeId,
            String parametersJson,
            String experimentId,
            String initialCash,
            String currency,
            String evaluationCutoff,
            String requestedStartDate,
            String requestedEndDate,
            String effectiveStartDate,
            String effectiveEndDate,
            String commissionPerFill,
            String spreadBps,
            String slippageBps,
            String failureReason,
            BacktestAnalyticsSummary candidateSummary,
            BacktestAnalyticsSummary benchmarkSummary,
            BacktestNormalizedConfig normalizedConfig,
            List<BacktestHoldingsDto> candidateHoldings,
            String createdAt,
            String updatedAt,
            String completedAt
    ) {
        public BacktestSummaryResponse(
                String id, String ownerId, String idempotencyKey, String canonicalHash, String status, int progressPct,
                String strategyId, String strategyVersion, String datasetId, String candidateListingId, String benchmarkListingId,
                String initialCash, String currency, String evaluationCutoff, String requestedStartDate, String requestedEndDate,
                String effectiveStartDate, String effectiveEndDate, String commissionPerFill, String spreadBps, String slippageBps,
                String failureReason, BacktestAnalyticsSummary candidateSummary, BacktestAnalyticsSummary benchmarkSummary,
                BacktestNormalizedConfig normalizedConfig, String createdAt, String updatedAt, String completedAt
        ) {
            this(id, ownerId, idempotencyKey, canonicalHash, status, progressPct, strategyId, strategyVersion, datasetId,
                    candidateListingId, benchmarkListingId, null, null, null, initialCash, currency, evaluationCutoff,
                    requestedStartDate, requestedEndDate, effectiveStartDate, effectiveEndDate, commissionPerFill,
                    spreadBps, slippageBps, failureReason, candidateSummary, benchmarkSummary, normalizedConfig,
                    List.of(), createdAt, updatedAt, completedAt);
        }
    }

    public record DailyEquityPoint(
            String sessionDate,
            String seriesType,
            String cash,
            String holdingsValue,
            String receivables,
            String totalEquity,
            Double dailyReturn,
            Double drawdown,
            String peakEquity,
            String units,
            String costBasis,
            String rawClose,
            String pointKind,
            String observationTime
    ) {
        public DailyEquityPoint(String sessionDate, String seriesType, String cash, String holdingsValue,
                                String receivables, String totalEquity, Double dailyReturn, Double drawdown,
                                String peakEquity, String units, String costBasis, String rawClose) {
            this(sessionDate, seriesType, cash, holdingsValue, receivables, totalEquity, dailyReturn,
                    drawdown, peakEquity, units, costBasis, rawClose, "SESSION_CLOSE", null);
        }
    }

    public record BacktestOrderDto(
            String id,
            String runId,
            String seriesType,
            String orderType,
            String listingId,
            String sessionDate,
            String requestedQuantity,
            String executedQuantity,
            String rawOpen,
            String fillPrice,
            String commission,
            String spreadCost,
            String slippageCost,
            String totalCashImpact,
            String status,
            String skipReason,
            String createdAt
    ) {}

    public record BacktestEventDto(
            String id,
            String runId,
            String seriesType,
            int eventSeq,
            String eventType,
            String eventDate,
            String eventTime,
            String description,
            String detailsJson,
            String cashDelta,
            String unitsDelta,
            String basisDelta,
            String receivableDelta,
            String createdAt
    ) {}

    public record BacktestHoldingsDto(
            String seriesType,
            String listingId,
            String units,
            String totalCostBasis,
            String averageCost,
            String currentPrice,
            String marketValue,
            String unrealizedGain,
            String updatedAt
    ) {}

    public record PagedResponse<T>(
            List<T> items,
            int total,
            int limit,
            int offset,
            boolean hasMore
    ) {}

    // --- M4 DTOs ---

    public record UniverseListingDto(
            String listingId,
            int ordinal,
            String symbol,
            String venue,
            String quoteCurrency
    ) {}

    public record UniverseDto(
            String id,
            String ownerId,
            String name,
            String version,
            String description,
            String datasetId,
            String calendarId,
            String currency,
            String provenance,
            int listingCount,
            List<UniverseListingDto> listings,
            String createdAt
    ) {
        public UniverseDto(
                String id, String ownerId, String name, String version, String description,
                String datasetId, String calendarId, String currency, String provenance,
                List<UniverseListingDto> listings, String createdAt
        ) {
            this(id, ownerId, name, version, description, datasetId, calendarId, currency, provenance,
                 listings != null ? listings.size() : 0, listings, createdAt);
        }
    }

    public record CreateUniverseRequest(
            String name,
            String version,
            String description,
            String datasetId,
            String calendarId,
            String currency,
            String provenance,
            List<String> listingIds
    ) {}

    public record StrategyParameterDefinitionDto(
            String name,
            String type,
            Object defaultValue,
            boolean required,
            String description
    ) {}

    public record StrategyVersionDto(
            String strategyId,
            String strategyVersion,
            String name,
            String strategyFamily,
            String status,
            String description,
            String rebalanceFrequency,
            String executionModel,
            String parametersSchemaJson,
            List<StrategyParameterDefinitionDto> parameters,
            List<String> supportedCalendars,
            List<String> supportedCurrencies,
            String calculationPolicyVersion,
            String decisionSchedule,
            String createdAt
    ) {
        public StrategyVersionDto(
                String strategyId,
                String strategyVersion,
                String name,
                String description,
                String parametersSchemaJson,
                String calculationPolicyVersion,
                String decisionSchedule,
                String createdAt
        ) {
            this(strategyId, strategyVersion, name,
                    strategyId != null && strategyId.contains("MOMENTUM") ? "CROSS_SECTIONAL_MOMENTUM" :
                            (strategyId != null && strategyId.contains("TREND") ? "TIME_SERIES_TREND" : "BUY_AND_HOLD"),
                    "ACTIVE", description,
                    strategyId != null && (strategyId.contains("MOMENTUM") || strategyId.contains("TREND")) ? "MONTHLY" : "BUY_AND_HOLD",
                    strategyId != null && strategyId.contains("MOMENTUM") ? "TARGET_WEIGHT_REBALANCE" :
                            (strategyId != null && strategyId.contains("TREND") ? "ALLOCATION_SWITCH" : "OPEN_AUCTION_REINVEST"),
                    parametersSchemaJson,
                    List.of(), List.of("XETR"), List.of("EUR"),
                    calculationPolicyVersion, decisionSchedule, createdAt);
        }
    }

    public record BacktestSignalItemDto(
            String id,
            String signalId,
            String listingId,
            String score,
            String indexValue,
            String smaValue,
            Integer rank,
            boolean eligible,
            boolean selected,
            String targetWeight,
            String reasonCode
    ) {}

    public record BacktestSignalDto(
            String id,
            String runId,
            String strategyId,
            String strategyVersion,
            String universeId,
            String evaluationDate,
            String evaluationTime,
            String decisionInstant,
            String scheduledExecutionDate,
            String targetAllocationSummary,
            String status,
            String reasonCode,
            String detailsJson,
            List<BacktestSignalItemDto> items,
            String createdAt
    ) {}

    public record RollingWindowDto(
            int windowIndex,
            String startDate,
            String targetEndDate,
            String actualEndDate,
            String startingEquity,
            String endingEquity,
            Double compoundedReturn,
            String compoundedReturnExact,
            int observationCount,
            boolean isComplete,
            String incompleteReason
    ) {
        public RollingWindowDto(
                int windowIndex,
                String startDate,
                String targetEndDate,
                String actualEndDate,
                String startingEquity,
                String endingEquity,
                Double compoundedReturn,
                int observationCount,
                boolean isComplete,
                String incompleteReason
        ) {
            this(windowIndex, startDate, targetEndDate, actualEndDate, startingEquity, endingEquity,
                    compoundedReturn,
                    compoundedReturn != null ? BigDecimal.valueOf(compoundedReturn).setScale(8, RoundingMode.HALF_EVEN).toPlainString() : null,
                    observationCount, isComplete, incompleteReason);
        }
    }

    public record RollingWindowSummaryDto(
            int totalWindows,
            int completeWindows,
            int positiveWindows,
            Double positiveWindowShare,
            List<RollingWindowDto> windows,
            String note
    ) {}

    public record ComparisonMismatch(
            String field,
            String expected,
            String actual,
            String runId,
            String message
    ) {}

    public record ComparisonMetricRow(
            String metricKey,
            String metricLabel,
            String benchmarkValue,
            Map<String, String> valuesByRunId,
            Map<String, String> diffAgainstBenchmarkByRunId
    ) {
        public ComparisonMetricRow(String metricKey, String metricLabel, Map<String, String> valuesByRunId, Map<String, String> diffAgainstBenchmarkByRunId, String unit) {
            this(metricKey, metricLabel, unit, valuesByRunId, diffAgainstBenchmarkByRunId);
        }
    }

    public record ComparisonItemDto(
            String runId,
            String role,
            int ordinal,
            BacktestSummaryResponse runSummary
    ) {}

    public record BacktestComparisonItemDto(
            String comparisonId,
            String runId,
            String role,
            int ordinal
    ) {}

    public record ComparisonMismatchReason(
            String field,
            String expected,
            String actual,
            String message
    ) {}

    public record ComparisonSummaryDto(
            List<ComparisonMetricRow> metricRows,
            Map<String, RollingWindowSummaryDto> rollingWindowsByRunId,
            String note
    ) {}

    public record BacktestComparisonDto(
            String id,
            String ownerId,
            String idempotencyKey,
            String name,
            String benchmarkListingId,
            String datasetId,
            String effectiveStartDate,
            String effectiveEndDate,
            String initialCash,
            String currency,
            String status,
            List<String> runIds,
            List<BacktestSummaryResponse> runs,
            List<ComparisonMismatchReason> mismatchReasons,
            List<BacktestComparisonItemDto> items,
            ComparisonSummaryDto summary,
            Map<String, RollingWindowSummaryDto> rollingWindowsByRun,
            String createdAt,
            String updatedAt
    ) {
        public BacktestComparisonDto(
                String id,
                String ownerId,
                String idempotencyKey,
                String name,
                String benchmarkListingId,
                String datasetId,
                String effectiveStartDate,
                String effectiveEndDate,
                String initialCash,
                String currency,
                String status,
                List<ComparisonMismatchReason> mismatchReasons,
                List<BacktestComparisonItemDto> items,
                ComparisonSummaryDto summary,
                String createdAt,
                String updatedAt
        ) {
            this(id, ownerId, idempotencyKey, name, benchmarkListingId, datasetId,
                    effectiveStartDate, effectiveEndDate, initialCash, currency, status,
                    items != null ? items.stream().map(BacktestComparisonItemDto::runId).toList() : List.of(),
                    List.of(),
                    mismatchReasons, items, summary,
                    summary != null && summary.rollingWindowsByRunId() != null ? summary.rollingWindowsByRunId() : Map.of(),
                    createdAt, updatedAt);
        }
    }

    public record CreateComparisonRequest(
            String name,
            List<String> runIds
    ) {}

    public record CreateExperimentRequest(
            String name,
            int version,
            String strategyId,
            String strategyVersion,
            String datasetId,
            String universeId,
            String candidateListingId,
            String benchmarkListingId,
            String developmentStartDate,
            String developmentEndDate,
            String holdoutStartDate,
            String holdoutEndDate,
            String declaredHoldoutStatus,
            String parametersJson
    ) {}

    public record ExperimentExposureEventDto(
            String id,
            String experimentId,
            String runId,
            String accessType,
            String exposedBy,
            String exposedAt,
            String detailsJson
    ) {}

    public record ExperimentDto(
            String id,
            String ownerId,
            String name,
            int version,
            String strategyId,
            String strategyVersion,
            String datasetId,
            String universeId,
            String candidateListingId,
            String benchmarkListingId,
            String developmentStartDate,
            String developmentEndDate,
            String holdoutStartDate,
            String holdoutEndDate,
            String declaredHoldoutStatus,
            String parametersJson,
            int totalExposures,
            List<ExperimentExposureEventDto> exposureEvents,
            String createdAt
    ) {
        public ExperimentDto(
                String id, String ownerId, String name, int version, String strategyId, String strategyVersion,
                String datasetId, String universeId, String candidateListingId, String benchmarkListingId,
                String developmentStartDate, String developmentEndDate, String holdoutStartDate, String holdoutEndDate,
                String declaredHoldoutStatus, String parametersJson,
                List<ExperimentExposureEventDto> exposureEvents, String createdAt
        ) {
            this(id, ownerId, name, version, strategyId, strategyVersion, datasetId, universeId,
                 candidateListingId, benchmarkListingId, developmentStartDate, developmentEndDate,
                 holdoutStartDate, holdoutEndDate, declaredHoldoutStatus, parametersJson,
                 exposureEvents != null ? exposureEvents.size() : 0, exposureEvents, createdAt);
        }
    }
}
