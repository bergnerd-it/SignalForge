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
            if (splitNumerator == null || splitDenominator == null || splitDenominator <= 0 || splitNumerator <= 0) {
                throw new IllegalArgumentException("Invalid split ratio for action " + actionId + ": numerator and denominator must be positive integers");
            }
            try {
                BigDecimal ratio = BigDecimal.valueOf(splitNumerator).divide(BigDecimal.valueOf(splitDenominator));
                if (ratio.scale() > 8) {
                    throw new IllegalArgumentException("Unsupported split precision for action " + actionId + ": ratio " + splitNumerator + "/" + splitDenominator + " exceeds supported scale of 8");
                }
                return ratio;
            } catch (ArithmeticException e) {
                throw new IllegalArgumentException("Unsupported split precision for action " + actionId + ": ratio " + splitNumerator + "/" + splitDenominator + " cannot be represented exactly without rounding", e);
            }
        }
    }

    public record LoadedBacktestData(
            String datasetId,
            String calendarId,
            String datasetInputChecksum,
            String datasetContentChecksum,
            String parserVersion,
            String schemaVersion,
            String classification,
            String coverageStart,
            String coverageEnd,
            String selectedEvaluationSession,
            String selectedEndSession,
            String effectiveStartDate,
            String effectiveEndDate,
            SessionRecord evaluationSession,
            List<SessionRecord> tradingSessions,
            List<SessionRecord> calendarSessions,
            Map<String, Map<String, BarRecord>> barsByListingAndDate, // listingId -> date -> BarRecord
            Map<String, List<ActionRecord>> actionsByListing // listingId -> List<ActionRecord>
    ) {}

    public record PreflightValidationResult(
            String datasetId,
            String calendarId,
            String calendarTimezone,
            String inputChecksum,
            String contentChecksum,
            String parserVersion,
            String schemaVersion,
            String classification,
            String coverageStart,
            String coverageEnd,
            String requestedStartDate,
            String requestedEndDate,
            String effectiveStartDate,
            String effectiveEndDate,
            SessionRecord evaluationSession,
            SessionRecord endSession,
            List<SessionRecord> allSessions,
            List<SessionRecord> runTradingSessions
    ) {
        public String selectedEvaluationSession() {
            return evaluationSession != null ? evaluationSession.sessionDate() : requestedStartDate;
        }

        public String selectedEndSession() {
            return endSession != null ? endSession.sessionDate() : requestedEndDate;
        }
    }

    public PreflightValidationResult validatePreflight(
            String datasetId,
            String candidateListingId,
            String benchmarkListingId,
            String evaluationCutoff,
            String requestedStartDate,
            String requestedEndDate
    ) {
        // 1. Verify dataset exists and is validated
        List<Map<String, Object>> dsRows = jdbcTemplate.queryForList(
                "SELECT id, validation_status, quality_label, classification, schema_version, parser_version, " +
                        "input_checksum, content_checksum, coverage_start, coverage_end FROM datasets WHERE id = ?",
                datasetId
        );
        if (dsRows.isEmpty()) {
            throw new IllegalArgumentException("Dataset not found: " + datasetId);
        }
        Map<String, Object> dsRow = dsRows.get(0);
        String status = (String) dsRow.get("validation_status");
        if (!"VALID".equalsIgnoreCase(status) && !"VALIDATED".equalsIgnoreCase(status)) {
            throw new IllegalArgumentException("Dataset " + datasetId + " is not VALID (current status: " + status + ")");
        }

        String classification = (String) dsRow.get("classification");
        String schemaVersion = (String) dsRow.get("schema_version");
        String parserVersion = (String) dsRow.get("parser_version");
        String inputChecksum = (String) dsRow.get("input_checksum");
        String contentChecksum = (String) dsRow.get("content_checksum");
        String coverageStart = (String) dsRow.get("coverage_start");
        String coverageEnd = (String) dsRow.get("coverage_end");

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

        // 4. Strict date parsing and Month-end evaluation session validation
        java.time.LocalDate reqStart;
        java.time.LocalDate reqEnd;
        try {
            reqStart = java.time.LocalDate.parse(requestedStartDate.trim());
            reqEnd = java.time.LocalDate.parse(requestedEndDate.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid date format: " + e.getMessage(), e);
        }

        if (reqStart.isAfter(reqEnd)) {
            throw new IllegalArgumentException("Requested start date " + requestedStartDate + " is after requested end date " + requestedEndDate);
        }

        SessionRecord evalSession = null;
        for (SessionRecord s : allSessions) {
            if (s.sessionDate().equals(requestedStartDate.trim()) && s.isTrading()) {
                evalSession = s;
                break;
            }
        }
        if (evalSession == null) {
            throw new IllegalArgumentException("Requested start date " + requestedStartDate + " is not a trading session in dataset calendar " + candCal);
        }

        // Month-end requirement: ensure no later trading session exists in that same calendar year & month
        for (SessionRecord s : allSessions) {
            if (s.isTrading()) {
                java.time.LocalDate sDate = java.time.LocalDate.parse(s.sessionDate());
                if (sDate.getYear() == reqStart.getYear() && sDate.getMonth() == reqStart.getMonth() && sDate.isAfter(reqStart)) {
                    throw new IllegalArgumentException("Requested start date " + requestedStartDate +
                            " is not a completed month-end session (later trading session exists on " + s.sessionDate() + ")");
                }
            }
        }

        // 5. Cutoff validation against evaluation session close and required observation availability
        java.time.Instant cutoffInstant;
        try {
            cutoffInstant = java.time.Instant.parse(evaluationCutoff.trim());
        } catch (Exception e) {
            cutoffInstant = java.time.LocalDate.parse(evaluationCutoff.trim()).atTime(23, 59, 59).atZone(java.time.ZoneOffset.UTC).toInstant();
        }

        java.time.Instant evalSessionClose;
        String closeStr = evalSession.closeTime().trim();
        if (closeStr.contains("T")) {
            evalSessionClose = java.time.Instant.parse(closeStr);
        } else {
            evalSessionClose = java.time.Instant.parse(evalSession.sessionDate() + "T" + closeStr);
        }
        if (cutoffInstant.isBefore(evalSessionClose)) {
            throw new IllegalArgumentException("Evaluation cutoff " + evaluationCutoff +
                    " is before evaluation session close time " + evalSession.closeTime() + "; completed observations unavailable");
        }

        // Validate that evaluation session bars exist for both candidate and benchmark, and were available by cutoff
        List<String> evalListings = List.of(candidateListingId, benchmarkListingId);
        for (String lid : evalListings) {
            List<Map<String, Object>> evalBarRows = jdbcTemplate.queryForList(
                    "SELECT available_at FROM historical_bars WHERE dataset_id = ? AND listing_id = ? AND session_date = ?",
                    datasetId, lid, evalSession.sessionDate()
            );
            if (evalBarRows.isEmpty()) {
                throw new IllegalArgumentException(
                        "Missing evaluation bar on session " + evalSession.sessionDate() + " for listing " + lid
                );
            }
            String availAtStr = (String) evalBarRows.get(0).get("available_at");
            if (availAtStr == null || availAtStr.isBlank()) {
                throw new IllegalArgumentException(
                        "Evaluation bar for listing " + lid + " on " + evalSession.sessionDate() + " has null available_at"
                );
            }
            java.time.Instant availInstant = java.time.Instant.parse(availAtStr);
            if (availInstant.isAfter(cutoffInstant)) {
                throw new IllegalArgumentException(
                        "Evaluation bar for listing " + lid + " on " + evalSession.sessionDate() +
                                " was not available until " + availAtStr +
                                ", which is strictly after evaluation cutoff " + cutoffInstant + "; completed observations unavailable"
                );
            }
        }

        // 6. Find first trading session strictly after evaluationCutoff
        SessionRecord effectiveStartSession = null;
        for (SessionRecord session : allSessions) {
            if (!session.isTrading()) continue;
            java.time.Instant sessionOpen = java.time.Instant.parse(session.openTime());
            if (sessionOpen.isAfter(cutoffInstant)) {
                effectiveStartSession = session;
                break;
            }
        }

        if (effectiveStartSession == null) {
            throw new IllegalArgumentException("No eligible trading session found strictly after evaluation cutoff " + evaluationCutoff);
        }

        // 7. Validate requestedEndDate is an explicit trading session
        SessionRecord endSession = null;
        for (SessionRecord s : allSessions) {
            if (s.sessionDate().equals(requestedEndDate.trim()) && s.isTrading()) {
                endSession = s;
                break;
            }
        }
        if (endSession == null) {
            throw new IllegalArgumentException("Requested end date " + requestedEndDate + " is not an explicit trading session in dataset calendar " + candCal);
        }

        java.time.LocalDate effStartDate = java.time.LocalDate.parse(effectiveStartSession.sessionDate());
        if (effStartDate.isAfter(reqEnd)) {
            throw new IllegalArgumentException("Effective start session " + effectiveStartSession.sessionDate() + " is after requested end date " + requestedEndDate);
        }

        // 8. Select all trading sessions between effectiveStartDate and requestedEndDate
        List<SessionRecord> runTradingSessions = new ArrayList<>();
        for (SessionRecord session : allSessions) {
            if (session.isTrading()) {
                java.time.LocalDate sDate = java.time.LocalDate.parse(session.sessionDate());
                if (!sDate.isBefore(effStartDate) && !sDate.isAfter(reqEnd)) {
                    runTradingSessions.add(session);
                }
            }
        }

        if (runTradingSessions.isEmpty()) {
            throw new IllegalArgumentException("No trading sessions found in effective window [" + effectiveStartSession.sessionDate() + ", " + requestedEndDate + "]");
        }

        String effectiveStartDate = effectiveStartSession.sessionDate();
        String effectiveEndDate = endSession.sessionDate();

        return new PreflightValidationResult(
                datasetId,
                candCal,
                "UTC",
                inputChecksum,
                contentChecksum,
                parserVersion,
                schemaVersion,
                classification,
                coverageStart,
                coverageEnd,
                requestedStartDate.trim(),
                requestedEndDate.trim(),
                effectiveStartDate,
                effectiveEndDate,
                evalSession,
                endSession,
                allSessions,
                runTradingSessions
        );
    }

    public LoadedBacktestData loadAndValidateData(
            String datasetId,
            String candidateListingId,
            String benchmarkListingId,
            String evaluationCutoff,
            String requestedStartDate,
            String requestedEndDate
    ) {
        PreflightValidationResult preflight = validatePreflight(
                datasetId,
                candidateListingId,
                benchmarkListingId,
                evaluationCutoff,
                requestedStartDate,
                requestedEndDate
        );

        String candCal = preflight.calendarId();
        String inputChecksum = preflight.inputChecksum();
        String contentChecksum = preflight.contentChecksum();
        String parserVersion = preflight.parserVersion();
        String schemaVersion = preflight.schemaVersion();
        String classification = preflight.classification();
        String coverageStart = preflight.coverageStart();
        String coverageEnd = preflight.coverageEnd();
        String effectiveStartDate = preflight.effectiveStartDate();
        String effectiveEndDate = preflight.effectiveEndDate();
        SessionRecord evalSession = preflight.evaluationSession();
        List<SessionRecord> allSessions = preflight.allSessions();
        List<SessionRecord> runTradingSessions = preflight.runTradingSessions();

        // 9. Load all historical bars for candidate and benchmark with parameterized query
        Set<String> listingsToLoad = new HashSet<>(Arrays.asList(candidateListingId, benchmarkListingId));
        Map<String, Map<String, BarRecord>> barsByListingAndDate = new HashMap<>();
        for (String listingId : listingsToLoad) {
            barsByListingAndDate.put(listingId, new HashMap<>());
        }

        List<String> listingList = new ArrayList<>(listingsToLoad);
        String barPlaceholders = String.join(",", Collections.nCopies(listingList.size(), "?"));
        List<Object> barParams = new ArrayList<>();
        barParams.add(datasetId);
        barParams.addAll(listingList);
        barParams.add(evalSession.sessionDate());
        barParams.add(effectiveEndDate);

        jdbcTemplate.query(
                "SELECT listing_id, session_date, open, high, low, close, volume, available_at FROM historical_bars " +
                        "WHERE dataset_id = ? AND listing_id IN (" + barPlaceholders + ") " +
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
                barParams.toArray()
        );

        // Verify that evaluation session has a bar for each listing
        for (String listingId : listingsToLoad) {
            if (!barsByListingAndDate.get(listingId).containsKey(evalSession.sessionDate())) {
                throw new IllegalArgumentException(
                        "Missing evaluation bar for listing " + listingId + " on evaluation session " + evalSession.sessionDate()
                );
            }
        }

        // Verify that every trading session in runTradingSessions has a bar for each listing
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

        // 10. Load all corporate actions for candidate and benchmark with parameterized query
        Map<String, List<ActionRecord>> actionsByListing = new HashMap<>();
        for (String listingId : listingsToLoad) {
            actionsByListing.put(listingId, new ArrayList<>());
        }

        String actionPlaceholders = String.join(",", Collections.nCopies(listingList.size(), "?"));
        List<Object> actionParams = new ArrayList<>();
        actionParams.add(datasetId);
        actionParams.addAll(listingList);

        jdbcTemplate.query(
                "SELECT action_id, listing_id, action_type, effective_date, available_at, " +
                        "split_ratio_numerator, split_ratio_denominator, distribution_amount, distribution_currency, " +
                        "payment_date, payment_instant FROM historical_actions " +
                        "WHERE dataset_id = ? AND listing_id IN (" + actionPlaceholders + ") " +
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
                actionParams.toArray()
        );

        // Strict action validation
        for (String listingId : listingsToLoad) {
            for (ActionRecord action : actionsByListing.get(listingId)) {
                if (action.isSplit()) {
                    action.splitRatio(); // Will throw IllegalArgumentException if numerator or denominator are invalid or unrepresentable
                } else if (action.isDistribution()) {
                    if (action.distributionCurrency() == null || !"EUR".equalsIgnoreCase(action.distributionCurrency())) {
                        throw new IllegalArgumentException("Distribution action " + action.actionId() + " must have EUR currency, got: " + action.distributionCurrency());
                    }
                    if (action.distributionAmount() == null || action.distributionAmount().compareTo(BigDecimal.ZERO) <= 0) {
                        throw new IllegalArgumentException("Distribution action " + action.actionId() + " must have positive distribution amount");
                    }
                    if ((action.paymentDate() == null || action.paymentDate().isBlank()) &&
                            (action.paymentInstant() == null || action.paymentInstant().isBlank())) {
                        throw new IllegalArgumentException("Distribution action " + action.actionId() + " must have payment_date or payment_instant specified");
                    }
                }

                // Verify action availability constraints for actions within the run window:
                if (action.effectiveDate().compareTo(evalSession.sessionDate()) >= 0 &&
                        action.effectiveDate().compareTo(effectiveEndDate) <= 0) {
                    if (action.availableAt() == null || action.availableAt().isBlank()) {
                        throw new IllegalArgumentException("Corporate action " + action.actionId() + " effective on " + action.effectiveDate() + " has null available_at");
                    }
                    java.time.Instant actionAvail = java.time.Instant.parse(action.availableAt());
                    for (SessionRecord session : allSessions) {
                        if (session.sessionDate().equals(action.effectiveDate()) && session.isTrading()) {
                            java.time.Instant sessionOpen = java.time.Instant.parse(session.openTime());
                            if (actionAvail.isAfter(sessionOpen)) {
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
                inputChecksum,
                contentChecksum,
                parserVersion,
                schemaVersion,
                classification,
                coverageStart,
                coverageEnd,
                requestedStartDate.trim(),
                requestedEndDate.trim(),
                effectiveStartDate,
                effectiveEndDate,
                evalSession,
                runTradingSessions,
                allSessions,
                barsByListingAndDate,
                actionsByListing
        );
    }
}
