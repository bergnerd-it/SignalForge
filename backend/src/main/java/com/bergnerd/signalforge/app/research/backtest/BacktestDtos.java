package com.bergnerd.signalforge.app.research.backtest;

import com.bergnerd.signalforge.app.accounting.AccountingCore;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

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
        REINVEST
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
            String strategyVersion
    ) {
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
                        candidateListingId.trim(),
                        benchmarkListingId.trim(),
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
            String availabilityAssumptions
    ) {
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
            String createdAt,
            String updatedAt,
            String completedAt
    ) {}

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
}
