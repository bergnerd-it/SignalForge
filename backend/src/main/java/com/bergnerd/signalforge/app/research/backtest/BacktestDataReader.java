package com.bergnerd.signalforge.app.research.backtest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class BacktestDataReader {

    private final JdbcTemplate jdbcTemplate;

    public record SessionRecord(
            String sessionDate,
            String openTime,
            String closeTime,
            String sessionType
    ) {
        public boolean isTrading() {
            return "TRADING".equalsIgnoreCase(sessionType);
        }
    }

    public record BarRecord(
            String listingId,
            String sessionDate,
            BigDecimal open,
            BigDecimal high,
            BigDecimal low,
            BigDecimal close,
            Long volume,
            String availableAt
    ) {}

    public record ActionRecord(
            String actionId,
            String listingId,
            String actionType,
            String effectiveDate,
            String availableAt,
            Integer splitNumerator,
            Integer splitDenominator,
            BigDecimal distributionAmount,
            String distributionCurrency,
            String paymentDate,
            String paymentInstant
    ) {
        public boolean isSplit() {
            return "SPLIT".equalsIgnoreCase(actionType);
        }

        public boolean isDistribution() {
            return "CASH_DISTRIBUTION".equalsIgnoreCase(actionType);
        }

        public BigDecimal splitRatio() {
            if (splitNumerator == null || splitDenominator == null || splitDenominator <= 0) {
                return BigDecimal.ONE;
            }
            return BigDecimal.valueOf(splitNumerator).divide(BigDecimal.valueOf(splitDenominator), 8, java.math.RoundingMode.HALF_EVEN);
        }
    }

    public record LoadedBacktestData(
            String datasetId,
            String calendarId,
            String effectiveStartDate,
            String effectiveEndDate,
            List<SessionRecord> tradingSessions,
            Map<String, Map<String, BarRecord>> barsByListingAndDate, // listingId -> date -> BarRecord
            Map<String, List<ActionRecord>> actionsByListing // listingId -> List<ActionRecord>
    ) {}

    public LoadedBacktestData loadAndValidateData(
            String datasetId,
            String candidateListingId,
            String benchmarkListingId,
            String evaluationCutoff,
            String requestedStartDate,
            String requestedEndDate
    ) {
        // 1. Verify dataset exists and is validated
        List<Map<String, Object>> dsRows = jdbcTemplate.queryForList(
                "SELECT id, validation_status, quality_label, coverage_start, coverage_end FROM datasets WHERE id = ?",
                datasetId
        );
        if (dsRows.isEmpty()) {
            throw new IllegalArgumentException("Dataset not found: " + datasetId);
        }
        String status = (String) dsRows.get(0).get("validation_status");
        if (!"VALID".equalsIgnoreCase(status) && !"VALIDATED".equalsIgnoreCase(status)) {
            throw new IllegalArgumentException("Dataset " + datasetId + " is not VALID (current status: " + status + ")");
        }

        // 2. Verify candidate & benchmark listings exist in this dataset
        List<Map<String, Object>> candidateListings = jdbcTemplate.queryForList(
                "SELECT listing_id, calendar_id, quote_currency FROM dataset_listings WHERE dataset_id = ? AND listing_id = ?",
                datasetId, candidateListingId
        );
        if (candidateListings.isEmpty()) {
            throw new IllegalArgumentException("Candidate listing " + candidateListingId + " not found in dataset " + datasetId);
        }
        List<Map<String, Object>> benchmarkListings = jdbcTemplate.queryForList(
                "SELECT listing_id, calendar_id, quote_currency FROM dataset_listings WHERE dataset_id = ? AND listing_id = ?",
                datasetId, benchmarkListingId
        );
        if (benchmarkListings.isEmpty()) {
            throw new IllegalArgumentException("Benchmark listing " + benchmarkListingId + " not found in dataset " + datasetId);
        }

        String candCal = (String) candidateListings.get(0).get("calendar_id");
        String benchCal = (String) benchmarkListings.get(0).get("calendar_id");
        if (!candCal.equalsIgnoreCase(benchCal)) {
            throw new IllegalArgumentException("Candidate calendar (" + candCal + ") does not match benchmark calendar (" + benchCal + ")");
        }
        String candCurr = (String) candidateListings.get(0).get("quote_currency");
        String benchCurr = (String) benchmarkListings.get(0).get("quote_currency");
        if (!"EUR".equalsIgnoreCase(candCurr) || !"EUR".equalsIgnoreCase(benchCurr)) {
            throw new IllegalArgumentException("Unsupported currency: both listings must quote in EUR (candidate: " + candCurr + ", benchmark: " + benchCurr + ")");
        }

        // 3. Load all sessions for the shared calendar
        List<SessionRecord> allSessions = jdbcTemplate.query(
                "SELECT session_date, open_time, close_time, session_type FROM dataset_sessions " +
                        "WHERE dataset_id = ? AND calendar_id = ? ORDER BY session_date ASC",
                (rs, rowNum) -> new SessionRecord(
                        rs.getString("session_date"),
                        rs.getString("open_time"),
                        rs.getString("close_time"),
                        rs.getString("session_type")
                ),
                datasetId, candCal
        );

        if (allSessions.isEmpty()) {
            throw new IllegalArgumentException("No calendar sessions found for dataset " + datasetId + " and calendar " + candCal);
        }

        // 4. Find first trading session strictly after evaluationCutoff
        // Cutoff can be date or ISO instant. E.g. "2024-01-31T23:59:59Z" -> compare open_time > cutoff
        SessionRecord effectiveStartSession = null;
        for (SessionRecord session : allSessions) {
            if (!session.isTrading()) continue;
            // session is eligible if session.open_time is strictly after evaluationCutoff,
            // or if session_date > cutoff's date
            String cutoffDate = evaluationCutoff.length() >= 10 ? evaluationCutoff.substring(0, 10) : evaluationCutoff;
            if (session.sessionDate.compareTo(cutoffDate) > 0) {
                effectiveStartSession = session;
                break;
            } else if (session.sessionDate.equals(cutoffDate) && session.openTime != null && session.openTime.compareTo(evaluationCutoff) > 0) {
                effectiveStartSession = session;
                break;
            }
        }

        if (effectiveStartSession == null) {
            throw new IllegalArgumentException("No eligible trading session found strictly after evaluation cutoff " + evaluationCutoff);
        }

        String effectiveStartDate = effectiveStartSession.sessionDate();

        // 5. Select all trading sessions between effectiveStartDate and requestedEndDate
        List<SessionRecord> runTradingSessions = new ArrayList<>();
        for (SessionRecord session : allSessions) {
            if (session.sessionDate().compareTo(effectiveStartDate) >= 0 &&
                    session.sessionDate().compareTo(requestedEndDate) <= 0) {
                if (session.isTrading()) {
                    runTradingSessions.add(session);
                }
            }
        }

        if (runTradingSessions.isEmpty()) {
            throw new IllegalArgumentException("No trading sessions found in effective window [" + effectiveStartDate + ", " + requestedEndDate + "]");
        }

        String effectiveEndDate = runTradingSessions.get(runTradingSessions.size() - 1).sessionDate();

        // 6. Load all historical bars for candidate and benchmark
        Set<String> listingsToLoad = new HashSet<>(Arrays.asList(candidateListingId, benchmarkListingId));
        Map<String, Map<String, BarRecord>> barsByListingAndDate = new HashMap<>();
        for (String listingId : listingsToLoad) {
            barsByListingAndDate.put(listingId, new HashMap<>());
        }

        String inListings = String.join("','", listingsToLoad);
        jdbcTemplate.query(
                "SELECT listing_id, session_date, open, high, low, close, volume, available_at FROM historical_bars " +
                        "WHERE dataset_id = ? AND listing_id IN ('" + inListings + "') " +
                        "AND session_date >= ? AND session_date <= ? ORDER BY session_date ASC",
                rs -> {
                    String lid = rs.getString("listing_id");
                    String sDate = rs.getString("session_date");
                    BarRecord bar = new BarRecord(
                            lid,
                            sDate,
                            new BigDecimal(rs.getString("open")),
                            new BigDecimal(rs.getString("high")),
                            new BigDecimal(rs.getString("low")),
                            new BigDecimal(rs.getString("close")),
                            rs.getLong("volume"),
                            rs.getString("available_at")
                    );
                    barsByListingAndDate.get(lid).put(sDate, bar);
                },
                datasetId, effectiveStartDate, effectiveEndDate
        );

        // Verify that every trading session has a bar for each listing
        for (SessionRecord session : runTradingSessions) {
            String sDate = session.sessionDate();
            for (String listingId : listingsToLoad) {
                if (!barsByListingAndDate.get(listingId).containsKey(sDate)) {
                    throw new IllegalArgumentException(
                            "Missing trading bar for listing " + listingId + " on trading session " + sDate
                    );
                }
            }
        }

        // 7. Load all corporate actions for candidate and benchmark
        Map<String, List<ActionRecord>> actionsByListing = new HashMap<>();
        for (String listingId : listingsToLoad) {
            actionsByListing.put(listingId, new ArrayList<>());
        }

        jdbcTemplate.query(
                "SELECT action_id, listing_id, action_type, effective_date, available_at, " +
                        "split_ratio_numerator, split_ratio_denominator, distribution_amount, distribution_currency, " +
                        "payment_date, payment_instant FROM historical_actions " +
                        "WHERE dataset_id = ? AND listing_id IN ('" + inListings + "') " +
                        "ORDER BY effective_date ASC, action_id ASC",
                rs -> {
                    String lid = rs.getString("listing_id");
                    ActionRecord action = new ActionRecord(
                            rs.getString("action_id"),
                            lid,
                            rs.getString("action_type"),
                            rs.getString("effective_date"),
                            rs.getString("available_at"),
                            rs.getObject("split_ratio_numerator") != null ? rs.getInt("split_ratio_numerator") : null,
                            rs.getObject("split_ratio_denominator") != null ? rs.getInt("split_ratio_denominator") : null,
                            rs.getString("distribution_amount") != null ? new BigDecimal(rs.getString("distribution_amount")) : null,
                            rs.getString("distribution_currency"),
                            rs.getString("payment_date"),
                            rs.getString("payment_instant")
                    );
                    actionsByListing.get(lid).add(action);
                },
                datasetId
        );

        // Verify action availability constraints:
        // For any action effective on or after effectiveStartDate and <= effectiveEndDate:
        // available_at must be known before trading starts on effective_date
        for (String listingId : listingsToLoad) {
            for (ActionRecord action : actionsByListing.get(listingId)) {
                if (action.effectiveDate().compareTo(effectiveStartDate) >= 0 &&
                        action.effectiveDate().compareTo(effectiveEndDate) <= 0) {
                    // Find session open time
                    for (SessionRecord session : runTradingSessions) {
                        if (session.sessionDate().equals(action.effectiveDate())) {
                            if (action.availableAt() != null && action.availableAt().compareTo(session.openTime()) > 0) {
                                throw new IllegalArgumentException(
                                        "Corporate action " + action.actionId() + " effective on " + action.effectiveDate() +
                                                " was not available until " + action.availableAt() +
                                                " which is strictly after session open " + session.openTime() + "; lookahead unavailable"
                                );
                            }
                            break;
                        }
                    }
                }
            }
        }

        return new LoadedBacktestData(
                datasetId,
                candCal,
                effectiveStartDate,
                effectiveEndDate,
                runTradingSessions,
                barsByListingAndDate,
                actionsByListing
        );
    }
}
