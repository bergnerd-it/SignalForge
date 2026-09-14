package com.bergnerd.signalforge.app.research.backtest;

import com.bergnerd.signalforge.app.accounting.AccountingCore;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class StrategyEvaluator {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public record EvaluatedSignal(
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
            List<EvaluatedSignalItem> items,
            Map<String, BigDecimal> targetWeights // listingId -> target weight (e.g. 1/K or 1.0)
    ) {}

    public record EvaluatedSignalItem(
            String listingId,
            BigDecimal score,
            BigDecimal indexValue,
            BigDecimal smaValue,
            Integer rank,
            boolean eligible,
            boolean selected,
            BigDecimal targetWeight,
            String reasonCode
    ) {}

    /**
     * Evaluate S1 signal (Global ETF Buy and Hold).
     * Single initial allocation target 100% in candidate listing.
     */
    public EvaluatedSignal evaluateS1(
            String runId,
            int signalSeq,
            String candidateListingId,
            BacktestDataReader.SessionRecord evalSession,
            BacktestDataReader.SessionRecord nextTradingSession,
            BigDecimal currentPrice
    ) {
        String signalId = runId + "-sig-" + String.format("%04d", signalSeq);
        String scheduledExecutionDate = nextTradingSession != null ? nextTradingSession.sessionDate() : null;
        String status = nextTradingSession != null ? "SCHEDULED" : "UNEXECUTED";
        String reasonCode = nextTradingSession != null ? "BUY_AND_HOLD_INITIAL_TARGET" : "UNEXECUTED_FINAL_SESSION";

        EvaluatedSignalItem item = new EvaluatedSignalItem(
                candidateListingId,
                null,
                currentPrice,
                null,
                1,
                true,
                true,
                BigDecimal.ONE.setScale(8, RoundingMode.HALF_EVEN),
                "TARGET_100_PERCENT"
        );

        Map<String, Object> details = Map.of(
                "strategy", "ETF_BUY_HOLD_V1",
                "candidateListingId", candidateListingId,
                "targetWeight", "1.00000000",
                "evalSessionDate", evalSession.sessionDate()
        );
        String detailsJson;
        try {
            detailsJson = objectMapper.writeValueAsString(details);
        } catch (Exception e) {
            detailsJson = "{}";
        }

        return new EvaluatedSignal(
                signalId,
                runId,
                "ETF_BUY_HOLD_V1",
                "1.0.0",
                null,
                evalSession.sessionDate(),
                evalSession.closeTime(),
                evalSession.closeTime(),
                scheduledExecutionDate,
                "100% " + candidateListingId,
                status,
                reasonCode,
                detailsJson,
                List.of(item),
                Map.of(candidateListingId, BigDecimal.ONE)
        );
    }

    /**
     * Evaluate S2 signal (ETF_MOMENTUM_12_1_V1).
     * At month-end m:
     * score(i, m) = T_i(last trading session of m-1) / T_i(last trading session of m-12) - 1.
     * Excludes month m. Spans ~11 months.
     * Select top K. Equal weight 1/K each.
     */
    public EvaluatedSignal evaluateS2(
            String runId,
            int signalSeq,
            String universeId,
            int k,
            YearMonth evalMonth,
            BacktestDataReader.SessionRecord evalSession,
            BacktestDataReader.SessionRecord nextTradingSession,
            List<String> universeListings,
            Map<String, TotalReturnSignalIndexCalculator.ListingSignalIndexSeries> indexSeriesMap
    ) {
        return evaluateS2(runId, signalSeq, universeId, k, evalMonth, evalSession, nextTradingSession, universeListings, indexSeriesMap, null, null);
    }

    public EvaluatedSignal evaluateS2(
            String runId,
            int signalSeq,
            String universeId,
            int k,
            YearMonth evalMonth,
            BacktestDataReader.SessionRecord evalSession,
            BacktestDataReader.SessionRecord nextTradingSession,
            List<String> universeListings,
            Map<String, TotalReturnSignalIndexCalculator.ListingSignalIndexSeries> indexSeriesMap,
            String decisionInstant,
            String reasonCodeOverride
    ) {
        if (k < 1 || k > universeListings.size()) {
            throw new IllegalArgumentException("K must satisfy 1 <= K <= universe size (" + universeListings.size() + "), was: " + k);
        }

        String signalId = runId + "-sig-" + String.format("%04d", signalSeq);
        String scheduledExecutionDate = nextTradingSession != null ? nextTradingSession.sessionDate() : null;
        String status = nextTradingSession != null ? "SCHEDULED" : "UNEXECUTED";
        String reasonCode = reasonCodeOverride != null ? reasonCodeOverride :
                (nextTradingSession != null ? "MOMENTUM_12_1_MONTHLY_REBALANCE" : "UNEXECUTED_FINAL_SESSION");

        YearMonth mMinus1 = evalMonth.minusMonths(1);
        YearMonth mMinus12 = evalMonth.minusMonths(12);

        record ScoredListing(String listingId, BigDecimal score, BigDecimal indexMMinus1, BigDecimal indexMMinus12) {}
        List<ScoredListing> scoredListings = new ArrayList<>();

        for (String listingId : universeListings) {
            TotalReturnSignalIndexCalculator.ListingSignalIndexSeries series = indexSeriesMap.get(listingId);
            if (series == null) {
                throw new IllegalStateException("Missing index series for universe listing: " + listingId);
            }
            TotalReturnSignalIndexCalculator.MonthObservation obsMMinus1 = series.getMonthEnd(mMinus1);
            TotalReturnSignalIndexCalculator.MonthObservation obsMMinus12 = series.getMonthEnd(mMinus12);

            if (obsMMinus1 == null || obsMMinus12 == null) {
                throw new IllegalStateException("Insufficient historical warm-up for listing " + listingId +
                        " at evaluation month " + evalMonth + " (needed " + mMinus12 + " and " + mMinus1 + ")");
            }

            BigDecimal tMMinus1 = obsMMinus1.indexValue();
            BigDecimal tMMinus12 = obsMMinus12.indexValue();

            if (tMMinus12.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalStateException("Non-positive base index value for listing " + listingId + " at " + mMinus12);
            }

            // score = T(m-1) / T(m-12) - 1
            BigDecimal score = tMMinus1.divide(tMMinus12, AccountingCore.MATH_CONTEXT).subtract(BigDecimal.ONE);
            scoredListings.add(new ScoredListing(listingId, score, tMMinus1, tMMinus12));
        }

        // Rank descending by unrounded score, break exact ties by listing ID ascending
        scoredListings.sort((a, b) -> {
            int cmp = b.score().compareTo(a.score());
            if (cmp != 0) {
                return cmp;
            }
            return a.listingId().compareTo(b.listingId());
        });

        // Target weight 1/K
        BigDecimal targetWeightFraction = BigDecimal.ONE.divide(BigDecimal.valueOf(k), AccountingCore.MATH_CONTEXT);

        List<EvaluatedSignalItem> items = new ArrayList<>();
        Map<String, BigDecimal> targetWeights = new HashMap<>();
        List<String> selectedListingIds = new ArrayList<>();

        for (int rankIdx = 0; rankIdx < scoredListings.size(); rankIdx++) {
            ScoredListing sc = scoredListings.get(rankIdx);
            int rank = rankIdx + 1;
            boolean selected = (rank <= k);

            BigDecimal weight = selected ? targetWeightFraction : BigDecimal.ZERO;
            String itemReason = selected ? "TOP_K_MOMENTUM_RANK_" + rank : "RANK_BELOW_K_" + rank;

            items.add(new EvaluatedSignalItem(
                    sc.listingId(),
                    sc.score(),
                    sc.indexMMinus1(),
                    null,
                    rank,
                    true,
                    selected,
                    weight.setScale(8, RoundingMode.HALF_EVEN),
                    itemReason
            ));

            if (selected) {
                targetWeights.put(sc.listingId(), targetWeightFraction);
                selectedListingIds.add(sc.listingId());
            } else {
                targetWeights.put(sc.listingId(), BigDecimal.ZERO);
            }
        }

        Map<String, Object> details = Map.of(
                "strategy", "ETF_MOMENTUM_12_1_V1",
                "k", k,
                "evalMonth", evalMonth.toString(),
                "mMinus1", mMinus1.toString(),
                "mMinus12", mMinus12.toString(),
                "selectedListings", selectedListingIds,
                "targetWeightPerAsset", targetWeightFraction.toPlainString()
        );
        String detailsJson;
        try {
            detailsJson = objectMapper.writeValueAsString(details);
        } catch (Exception e) {
            detailsJson = "{}";
        }

        return new EvaluatedSignal(
                signalId,
                runId,
                "ETF_MOMENTUM_12_1_V1",
                "1.0.0",
                universeId,
                evalSession.sessionDate(),
                evalSession.closeTime(),
                decisionInstant != null ? decisionInstant : evalSession.closeTime(),
                scheduledExecutionDate,
                "TOP_" + k + " (" + String.join(", ", selectedListingIds) + ")",
                status,
                reasonCode,
                detailsJson,
                items,
                targetWeights
        );
    }

    /**
     * Evaluate S3 signal (ETF_TREND_10M_V1).
     * At month-end m, arithmetic mean of ten monthly index values including m:
     * SMA10(m) = mean(T(m-9), ..., T(m)).
     * If T(m) > SMA10(m), target 100% ETF; else target 100% cash. Equality selects cash.
     */
    public EvaluatedSignal evaluateS3(
            String runId,
            int signalSeq,
            String candidateListingId,
            YearMonth evalMonth,
            BacktestDataReader.SessionRecord evalSession,
            BacktestDataReader.SessionRecord nextTradingSession,
            TotalReturnSignalIndexCalculator.ListingSignalIndexSeries indexSeries
    ) {
        return evaluateS3(runId, signalSeq, candidateListingId, evalMonth, evalSession, nextTradingSession, indexSeries, null, null);
    }

    public EvaluatedSignal evaluateS3(
            String runId,
            int signalSeq,
            String candidateListingId,
            YearMonth evalMonth,
            BacktestDataReader.SessionRecord evalSession,
            BacktestDataReader.SessionRecord nextTradingSession,
            TotalReturnSignalIndexCalculator.ListingSignalIndexSeries indexSeries,
            String decisionInstant,
            String reasonCodeOverride
    ) {
        String signalId = runId + "-sig-" + String.format("%04d", signalSeq);
        String scheduledExecutionDate = nextTradingSession != null ? nextTradingSession.sessionDate() : null;
        String status = nextTradingSession != null ? "SCHEDULED" : "UNEXECUTED";

        // Collect 10 monthly observations ending at evalMonth: m-9 ... m
        List<BigDecimal> monthlyValues = new ArrayList<>();
        BigDecimal sum = BigDecimal.ZERO;

        for (int offset = 9; offset >= 0; offset--) {
            YearMonth ym = evalMonth.minusMonths(offset);
            TotalReturnSignalIndexCalculator.MonthObservation obs = indexSeries.getMonthEnd(ym);
            if (obs == null) {
                throw new IllegalStateException("Insufficient historical warm-up for S3 trend filter: missing month-end observation for " + ym);
            }
            monthlyValues.add(obs.indexValue());
            sum = sum.add(obs.indexValue());
        }

        BigDecimal sma10 = sum.divide(BigDecimal.valueOf(10), AccountingCore.MATH_CONTEXT);
        BigDecimal currentT = monthlyValues.get(monthlyValues.size() - 1); // T(m)

        boolean isEtfSelected = currentT.compareTo(sma10) > 0;
        String targetAllocationSummary = isEtfSelected ? "100% ETF" : "100% CASH";
        String reasonCode;
        if (reasonCodeOverride != null) {
            reasonCode = reasonCodeOverride;
        } else if (nextTradingSession == null) {
            reasonCode = "UNEXECUTED_FINAL_SESSION";
        } else if (isEtfSelected) {
            reasonCode = "TREND_ABOVE_SMA10_TARGET_ETF";
        } else if (currentT.compareTo(sma10) == 0) {
            reasonCode = "TREND_EQUAL_SMA10_TARGET_CASH";
        } else {
            reasonCode = "TREND_BELOW_SMA10_TARGET_CASH";
        }

        BigDecimal targetWeight = isEtfSelected ? BigDecimal.ONE : BigDecimal.ZERO;

        EvaluatedSignalItem item = new EvaluatedSignalItem(
                candidateListingId,
                null,
                currentT,
                sma10,
                1,
                true,
                isEtfSelected,
                targetWeight.setScale(8, RoundingMode.HALF_EVEN),
                isEtfSelected ? "T_GREATER_THAN_SMA10" : (currentT.compareTo(sma10) == 0 ? "T_EQUALS_SMA10" : "T_LESS_THAN_SMA10")
        );

        Map<String, Object> details = Map.of(
                "strategy", "ETF_TREND_10M_V1",
                "candidateListingId", candidateListingId,
                "evalMonth", evalMonth.toString(),
                "tM", currentT.toPlainString(),
                "sma10", sma10.toPlainString(),
                "selectedTarget", isEtfSelected ? "ETF" : "CASH",
                "isEtfSelected", isEtfSelected
        );
        String detailsJson;
        try {
            detailsJson = objectMapper.writeValueAsString(details);
        } catch (Exception e) {
            detailsJson = "{}";
        }

        Map<String, BigDecimal> targetWeights = Map.of(candidateListingId, targetWeight);

        return new EvaluatedSignal(
                signalId,
                runId,
                "ETF_TREND_10M_V1",
                "1.0.0",
                null,
                evalSession.sessionDate(),
                evalSession.closeTime(),
                decisionInstant != null ? decisionInstant : evalSession.closeTime(),
                scheduledExecutionDate,
                targetAllocationSummary,
                status,
                reasonCode,
                detailsJson,
                List.of(item),
                targetWeights
        );
    }
}
