package com.bergnerd.signalforge.app.research.paper;

import com.bergnerd.signalforge.app.research.backtest.BacktestDataReader;
import com.bergnerd.signalforge.app.research.backtest.StrategyEvaluator;
import com.bergnerd.signalforge.app.research.backtest.TotalReturnSignalIndexCalculator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaperStrategyAdapter {

    private final JdbcTemplate jdbcTemplate;
    private final StrategyEvaluator strategyEvaluator;
    private final TotalReturnSignalIndexCalculator indexCalculator;

    public record StrategyProposalOutcome(
            StrategyEvaluator.EvaluatedSignal evaluatedSignal,
            List<PaperProposalObservation> observations,
            Map<String, BigDecimal> targetWeights,
            BacktestDataReader.SessionRecord evalSession,
            BacktestDataReader.SessionRecord nextSession,
            Map<String, BigDecimal> cutoffPrices
    ) {}

    public record PaperProposalObservation(
            String listingId,
            String observationSessionDate,
            String observationType,
            String observationValue
    ) {}

    public StrategyProposalOutcome evaluateStrategy(
            String portfolioId,
            String datasetId,
            String universeId,
            String benchmarkListingId,
            String strategyId,
            String strategyVersion,
            String evalSessionDate,
            Instant cutoffInstant
    ) {
        // 1. Resolve listings in universe
        List<String> universeListings = jdbcTemplate.queryForList(
                "SELECT listing_id FROM universe_listings WHERE universe_id = ? ORDER BY listing_id ASC",
                String.class, universeId
        );
        if (universeListings.isEmpty()) {
            universeListings = List.of(benchmarkListingId);
        }

        Set<String> allListingsToLoad = new LinkedHashSet<>(universeListings);
        allListingsToLoad.add(benchmarkListingId);

        // 2. Resolve calendar
        String calendarId = jdbcTemplate.queryForObject(
                "SELECT calendar_id FROM dataset_listings WHERE dataset_id = ? AND listing_id = ? LIMIT 1",
                String.class, datasetId, benchmarkListingId
        );
        if (calendarId == null) {
            calendarId = "XETR";
        }

        // 3. Load trading sessions up to and including evalSessionDate
        List<BacktestDataReader.SessionRecord> priorTradingSessions = jdbcTemplate.query(
                "SELECT session_date, open_time, close_time, session_type FROM dataset_sessions " +
                        "WHERE dataset_id = ? AND calendar_id = ? AND session_type = 'TRADING' AND session_date <= ? " +
                        "ORDER BY session_date ASC",
                (rs, rowNum) -> new BacktestDataReader.SessionRecord(
                        rs.getString("session_date"),
                        rs.getString("open_time"),
                        rs.getString("close_time"),
                        rs.getString("session_type")
                ),
                datasetId, calendarId, evalSessionDate
        );

        if (priorTradingSessions.isEmpty()) {
            throw new IllegalStateException("No trading sessions found up to evaluation date " + evalSessionDate);
        }

        BacktestDataReader.SessionRecord evalSession = priorTradingSessions.get(priorTradingSessions.size() - 1);

        // 4. Find next session
        List<BacktestDataReader.SessionRecord> nextSessions = jdbcTemplate.query(
                "SELECT session_date, open_time, close_time, session_type FROM dataset_sessions " +
                        "WHERE dataset_id = ? AND calendar_id = ? AND session_type = 'TRADING' AND session_date > ? " +
                        "ORDER BY session_date ASC LIMIT 1",
                (rs, rowNum) -> new BacktestDataReader.SessionRecord(
                        rs.getString("session_date"),
                        rs.getString("open_time"),
                        rs.getString("close_time"),
                        rs.getString("session_type")
                ),
                datasetId, calendarId, evalSessionDate
        );
        BacktestDataReader.SessionRecord nextSession = nextSessions.isEmpty() ? null : nextSessions.get(0);

        // 5. Load historical bars for all listings
        Map<String, Map<String, BacktestDataReader.BarRecord>> barsByListing = new HashMap<>();
        Map<String, BigDecimal> cutoffPrices = new HashMap<>();

        for (String listingId : allListingsToLoad) {
            Map<String, BacktestDataReader.BarRecord> bars = new HashMap<>();
            jdbcTemplate.query(
                    "SELECT listing_id, session_date, open, high, low, close, volume, available_at FROM historical_bars " +
                            "WHERE dataset_id = ? AND listing_id = ? AND session_date <= ? ORDER BY session_date ASC",
                    rs -> {
                        String sDate = rs.getString("session_date");
                        BigDecimal close = new BigDecimal(rs.getString("close"));
                        bars.put(sDate, new BacktestDataReader.BarRecord(
                                rs.getString("listing_id"),
                                sDate,
                                new BigDecimal(rs.getString("open")),
                                new BigDecimal(rs.getString("high")),
                                new BigDecimal(rs.getString("low")),
                                close,
                                rs.getLong("volume"),
                                rs.getString("available_at")
                        ));
                        if (sDate.equals(evalSessionDate)) {
                            cutoffPrices.put(listingId, close);
                        }
                    },
                    datasetId, listingId, evalSessionDate
            );
            barsByListing.put(listingId, bars);
        }

        // 6. Load corporate actions
        Map<String, List<BacktestDataReader.ActionRecord>> actionsByListing = new HashMap<>();
        for (String listingId : allListingsToLoad) {
            List<BacktestDataReader.ActionRecord> actions = jdbcTemplate.query(
                    "SELECT action_id, listing_id, action_type, effective_date, available_at, split_ratio_numerator, " +
                            "split_ratio_denominator, distribution_amount, distribution_currency, payment_date, payment_instant " +
                            "FROM historical_actions WHERE dataset_id = ? AND listing_id = ? AND effective_date <= ? ORDER BY effective_date ASC",
                    (rs, rowNum) -> new BacktestDataReader.ActionRecord(
                            rs.getString("action_id"),
                            rs.getString("listing_id"),
                            rs.getString("action_type"),
                            rs.getString("effective_date"),
                            rs.getString("available_at"),
                            rs.getObject("split_ratio_numerator") != null ? rs.getInt("split_ratio_numerator") : null,
                            rs.getObject("split_ratio_denominator") != null ? rs.getInt("split_ratio_denominator") : null,
                            rs.getString("distribution_amount") != null ? new BigDecimal(rs.getString("distribution_amount")) : null,
                            rs.getString("distribution_currency"),
                            rs.getString("payment_date"),
                            rs.getString("payment_instant")
                    ),
                    datasetId, listingId, evalSessionDate
            );
            actionsByListing.put(listingId, actions);
        }

        // 7. Calculate index series for all listings
        Map<String, TotalReturnSignalIndexCalculator.ListingSignalIndexSeries> indexSeriesMap = new HashMap<>();
        for (String listingId : allListingsToLoad) {
            TotalReturnSignalIndexCalculator.ListingSignalIndexSeries series = indexCalculator.calculateSeries(
                    listingId,
                    priorTradingSessions,
                    barsByListing.get(listingId),
                    actionsByListing.get(listingId),
                    cutoffInstant
            );
            indexSeriesMap.put(listingId, series);
        }

        // 8. Execute StrategyEvaluator
        YearMonth evalMonth = YearMonth.parse(evalSessionDate.substring(0, 7));
        String pseudoRunId = "paper-" + portfolioId;
        StrategyEvaluator.EvaluatedSignal signal;
        List<PaperProposalObservation> observations = new ArrayList<>();

        if ("ETF_BUY_HOLD_V1".equals(strategyId)) {
            String candidate = universeListings.get(0);
            BigDecimal price = cutoffPrices.getOrDefault(candidate, BigDecimal.ZERO);
            signal = strategyEvaluator.evaluateS1(pseudoRunId, 1, candidate, evalSession, nextSession, price);

            observations.add(new PaperProposalObservation(candidate, evalSessionDate, "CLOSE", price.toPlainString()));
        } else if ("ETF_MOMENTUM_12_1_V1".equals(strategyId)) {
            int k = Math.min(2, universeListings.size());
            signal = strategyEvaluator.evaluateS2(
                    pseudoRunId, 1, universeId, k, evalMonth, evalSession, nextSession, universeListings, indexSeriesMap
            );

            for (StrategyEvaluator.EvaluatedSignalItem item : signal.items()) {
                if (item.score() != null) {
                    observations.add(new PaperProposalObservation(item.listingId(), evalSessionDate, "MOMENTUM_SCORE", item.score().toPlainString()));
                }
                if (item.indexValue() != null) {
                    observations.add(new PaperProposalObservation(item.listingId(), evalSessionDate, "TOTAL_RETURN_INDEX", item.indexValue().toPlainString()));
                }
                BigDecimal price = cutoffPrices.get(item.listingId());
                if (price != null) {
                    observations.add(new PaperProposalObservation(item.listingId(), evalSessionDate, "CLOSE", price.toPlainString()));
                }
            }
        } else if ("ETF_TREND_10M_V1".equals(strategyId)) {
            String candidate = universeListings.get(0);
            TotalReturnSignalIndexCalculator.ListingSignalIndexSeries series = indexSeriesMap.get(candidate);
            signal = strategyEvaluator.evaluateS3(
                    pseudoRunId, 1, candidate, evalMonth, evalSession, nextSession, series
            );

            for (StrategyEvaluator.EvaluatedSignalItem item : signal.items()) {
                if (item.indexValue() != null) {
                    observations.add(new PaperProposalObservation(item.listingId(), evalSessionDate, "TOTAL_RETURN_INDEX", item.indexValue().toPlainString()));
                }
                if (item.smaValue() != null) {
                    observations.add(new PaperProposalObservation(item.listingId(), evalSessionDate, "SMA10", item.smaValue().toPlainString()));
                }
                BigDecimal price = cutoffPrices.get(item.listingId());
                if (price != null) {
                    observations.add(new PaperProposalObservation(item.listingId(), evalSessionDate, "CLOSE", price.toPlainString()));
                }
            }
        } else {
            throw new IllegalArgumentException("Unsupported strategy: " + strategyId);
        }

        return new StrategyProposalOutcome(signal, observations, signal.targetWeights(), evalSession, nextSession, cutoffPrices);
    }
}
