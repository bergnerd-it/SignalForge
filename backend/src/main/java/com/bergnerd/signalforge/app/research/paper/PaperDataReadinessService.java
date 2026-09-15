package com.bergnerd.signalforge.app.research.paper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaperDataReadinessService {

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public record ReadinessResult(
            boolean isReady,
            String status,
            String latestCompletedSession,
            String nextScheduledSession,
            String details
    ) {}

    public record DatasetAdoptionResult(
            String adoptionId,
            String portfolioId,
            String datasetId,
            String validationStatus,
            String coverageStart,
            String coverageEnd,
            String adoptedAt,
            String rejectionReason
    ) {}

    @Transactional
    public DatasetAdoptionResult adoptDataset(String portfolioId, String datasetId) {
        String now = clock.instant().toString();

        List<Map<String, Object>> segRows = jdbcTemplate.queryForList(
                "SELECT id, strategy_id, strategy_version, universe_id, benchmark_listing_id FROM paper_portfolio_segments WHERE portfolio_id = ? AND status = 'ACTIVE'",
                portfolioId
        );
        if (segRows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Portfolio does not have an active tracking segment");
        }
        Map<String, Object> seg = segRows.get(0);
        String segmentId = (String) seg.get("id");
        String strategyId = (String) seg.get("strategy_id");
        String universeId = (String) seg.get("universe_id");
        String benchmarkListingId = (String) seg.get("benchmark_listing_id");

        List<Map<String, Object>> dsRows = jdbcTemplate.queryForList(
                "SELECT id, input_checksum, content_checksum, coverage_start, coverage_end, validation_status FROM datasets WHERE id = ?",
                datasetId
        );
        if (dsRows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Dataset not found: " + datasetId);
        }
        Map<String, Object> ds = dsRows.get(0);
        String checksum = (String) ds.get("content_checksum");
        if (checksum == null || checksum.isBlank()) {
            checksum = (String) ds.get("input_checksum");
        }
        String covStart = (String) ds.get("coverage_start");
        String covEnd = (String) ds.get("coverage_end");

        List<String> requiredListings = new ArrayList<>(jdbcTemplate.queryForList(
                "SELECT listing_id FROM universe_listings WHERE universe_id = ?",
                String.class,
                universeId
        ));
        if (benchmarkListingId != null && !requiredListings.contains(benchmarkListingId)) {
            requiredListings.add(benchmarkListingId);
        }

        List<String> missingListings = new ArrayList<>();
        for (String lid : requiredListings) {
            Integer c = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM dataset_listings WHERE dataset_id = ? AND listing_id = ? AND quote_currency = 'EUR'",
                    Integer.class,
                    datasetId, lid
            );
            if (c == null || c == 0) {
                missingListings.add(lid);
            }
        }
        if (!missingListings.isEmpty()) {
            String reason = "Dataset missing required EUR listings: " + missingListings;
            recordAdoption(portfolioId, datasetId, checksum, covStart, covEnd, "REJECTED_INCOMPATIBLE", reason, now);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
        }

        // Calendar check: Must have sessions and bars
        Integer sessionCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM dataset_sessions WHERE dataset_id = ?",
                Integer.class,
                datasetId
        );
        if (sessionCount == null || sessionCount == 0) {
            String reason = "Dataset has insufficient trading calendar sessions (" + sessionCount + ")";
            recordAdoption(portfolioId, datasetId, checksum, covStart, covEnd, "REJECTED_INCOMPATIBLE", reason, now);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
        }

        // Corporate action conflict check:
        // Ensure no conflicting terms for already booked corporate actions on this portfolio
        List<Map<String, Object>> newActions = jdbcTemplate.queryForList(
                "SELECT action_id, listing_id, action_type, effective_date, split_ratio_numerator, split_ratio_denominator, " +
                        "distribution_amount, distribution_currency, payment_date FROM historical_actions WHERE dataset_id = ?",
                datasetId
        );

        for (Map<String, Object> na : newActions) {
            String actionId = (String) na.get("action_id");
            String lid = (String) na.get("listing_id");
            String actionType = (String) na.get("action_type");
            String termsHash = computeTermsHash(na);

            List<Map<String, Object>> existing = jdbcTemplate.queryForList(
                    "SELECT terms_hash FROM paper_processed_corporate_actions WHERE portfolio_id = ? AND action_id = ? AND listing_id = ?",
                    portfolioId, actionId, lid
            );
            for (Map<String, Object> ex : existing) {
                String existingTerms = (String) ex.get("terms_hash");
                if (existingTerms != null && !existingTerms.equals(termsHash)) {
                    String reason = String.format("Conflicting corporate action terms for listing %s action %s", lid, actionId);
                    recordAdoption(portfolioId, datasetId, checksum, covStart, covEnd, "REJECTED_CONFLICT", reason, now);
                    throw new ResponseStatusException(HttpStatus.CONFLICT, reason);
                }
            }
        }

        // Adoption succeeds
        String adoptionId = recordAdoption(portfolioId, datasetId, checksum, covStart, covEnd, "ADOPTED", null, now);
        jdbcTemplate.update(
                "UPDATE paper_portfolio_segments SET adopted_dataset_id = ?, adopted_at = ? WHERE id = ?",
                datasetId, now, segmentId
        );

        return new DatasetAdoptionResult(
                adoptionId, portfolioId, datasetId, "ADOPTED", covStart, covEnd, now, null
        );
    }

    public ReadinessResult checkReadiness(String portfolioId) {
        List<Map<String, Object>> segRows = jdbcTemplate.queryForList(
                "SELECT id, strategy_id, universe_id, adopted_dataset_id FROM paper_portfolio_segments WHERE portfolio_id = ? AND status = 'ACTIVE'",
                portfolioId
        );
        if (segRows.isEmpty()) {
            return new ReadinessResult(false, "NO_ACTIVE_SEGMENT", null, null, "No active tracking segment configured");
        }
        Map<String, Object> seg = segRows.get(0);
        String datasetId = (String) seg.get("adopted_dataset_id");
        if (datasetId == null || datasetId.isBlank()) {
            return new ReadinessResult(false, "NO_ADOPTED_DATASET", null, null, "No dataset snapshot adopted");
        }

        List<String> sessions = jdbcTemplate.queryForList(
                "SELECT session_date FROM dataset_sessions WHERE dataset_id = ? AND session_type = 'TRADING' ORDER BY session_date ASC",
                String.class,
                datasetId
        );
        if (sessions.isEmpty()) {
            return new ReadinessResult(false, "EMPTY_CALENDAR", null, null, "Adopted dataset has no trading sessions");
        }

        String latestCompleted = sessions.get(sessions.size() - 1);
        String nextSession = null;
        if (sessions.size() >= 2) {
            latestCompleted = sessions.get(sessions.size() - 2);
            nextSession = sessions.get(sessions.size() - 1);
        }

        return new ReadinessResult(true, "READY", latestCompleted, nextSession, "Data snapshot ready for evaluation");
    }

    public static String computeTermsHash(Map<String, Object> action) {
        String actionType = String.valueOf(action.get("action_type"));
        String effectiveDate = String.valueOf(action.get("effective_date"));
        String splitNum = String.valueOf(action.get("split_ratio_numerator"));
        String splitDen = String.valueOf(action.get("split_ratio_denominator"));
        String distAmt = String.valueOf(action.get("distribution_amount"));
        String distCurr = String.valueOf(action.get("distribution_currency"));
        String payDate = String.valueOf(action.get("payment_date"));

        String raw = actionType + "|" + effectiveDate + "|" + splitNum + "|" + splitDen + "|" + distAmt + "|" + distCurr + "|" + payDate;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 computation error", e);
        }
    }

    private String recordAdoption(
            String portfolioId, String datasetId, String checksum,
            String start, String end, String status, String reason, String now
    ) {
        String id = "adopt-" + UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO paper_dataset_adoptions (id, portfolio_id, dataset_id, dataset_checksum, coverage_start_session, coverage_end_session, validation_status, rejection_reason, adopted_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, portfolioId, datasetId, checksum != null ? checksum : "unknown",
                start != null ? start : "", end != null ? end : "", status, reason, now
        );
        return id;
    }
}
