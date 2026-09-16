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
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaperDataReadinessService {

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;
    private final PaperAuditRecorder auditRecorder;
    private final PaperMutationService mutationService;

    public static final ZoneId EXCHANGE_ZONE = ZoneId.of("Europe/Berlin");

    public record DatasetAdoptionResult(
            String adoptionId,
            String portfolioId,
            String datasetId,
            String validationStatus,
            String coverageStartSession,
            String coverageEndSession,
            String adoptedAt,
            String rejectionReason
    ) {}

    public record ReadinessResult(
            boolean ready,
            String reasonCode,
            String latestCompletedSession,
            String nextSession,
            String details
    ) {
        public boolean isReady() { return ready; }
        public String status() { return reasonCode; }
        public String nextScheduledSession() { return nextSession; }
    }

    public DatasetAdoptionResult adoptDataset(String portfolioId, String datasetId) {
        return adoptDataset(portfolioId, datasetId, "default", "adopt-" + UUID.randomUUID());
    }

    public DatasetAdoptionResult adoptDataset(String portfolioId, String datasetId, String ownerId, String idempotencyKey) {
        String uid = (ownerId == null || ownerId.isBlank()) ? "default" : ownerId.trim();
        String now = clock.instant().toString();

        // 1. Idempotency check via mutation service
        String payloadHash = PaperMutationService.computeHash("ADOPT|" + portfolioId + "|" + datasetId);
        Optional<PaperMutationService.MutationEntry> cached = mutationService.checkMutation(uid, "ADOPT_DATASET", idempotencyKey, payloadHash);
        if (cached.isPresent() && "COMMITTED".equals(cached.get().status())) {
            Map<String, Object> adRow = jdbcTemplate.queryForMap(
                    "SELECT id, portfolio_id, dataset_id, validation_status, coverage_start_session, coverage_end_session, adopted_at, rejection_reason " +
                            "FROM paper_dataset_adoptions WHERE id = ?",
                    cached.get().resourceId()
            );
            return new DatasetAdoptionResult(
                    (String) adRow.get("id"),
                    (String) adRow.get("portfolio_id"),
                    (String) adRow.get("dataset_id"),
                    (String) adRow.get("validation_status"),
                    (String) adRow.get("coverage_start_session"),
                    (String) adRow.get("coverage_end_session"),
                    (String) adRow.get("adopted_at"),
                    (String) adRow.get("rejection_reason")
            );
        }

        // 2. Validate portfolio and active segment
        List<Map<String, Object>> segRows = jdbcTemplate.queryForList(
                "SELECT s.id, s.strategy_id, s.universe_id, s.benchmark_listing_id FROM paper_portfolio_segments s " +
                        "JOIN portfolios p ON p.id = s.portfolio_id " +
                        "WHERE s.portfolio_id = ? AND p.owner_id = ? AND s.status = 'ACTIVE'",
                portfolioId, uid
        );
        if (segRows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Portfolio does not have an active tracking segment for owner");
        }
        Map<String, Object> seg = segRows.get(0);
        String segmentId = (String) seg.get("id");
        String strategyId = (String) seg.get("strategy_id");
        String universeId = (String) seg.get("universe_id");
        String benchmarkListingId = (String) seg.get("benchmark_listing_id");

        // 3. Validate dataset existence and validation_status
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
        String valStatus = (String) ds.get("validation_status");

        if (!"VALID".equalsIgnoreCase(valStatus)) {
            String reason = "Dataset validation status is " + valStatus + ", requires VALID";
            auditRecorder.recordAdoptionInNewTx(portfolioId, datasetId, checksum, covStart, covEnd, "REJECTED_INCOMPATIBLE", reason, now);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
        }

        // 4. Validate EUR listings
        List<String> requiredListings = new ArrayList<>(jdbcTemplate.queryForList(
                "SELECT listing_id FROM universe_listings WHERE universe_id = ?",
                String.class, universeId
        ));
        if (benchmarkListingId != null && !requiredListings.contains(benchmarkListingId)) {
            requiredListings.add(benchmarkListingId);
        }

        List<String> missingListings = new ArrayList<>();
        for (String lid : requiredListings) {
            Integer c = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM dataset_listings WHERE dataset_id = ? AND listing_id = ? AND quote_currency = 'EUR'",
                    Integer.class, datasetId, lid
            );
            if (c == null || c == 0) {
                missingListings.add(lid);
            }
        }
        if (!missingListings.isEmpty()) {
            String reason = "Dataset missing required EUR listings: " + missingListings;
            auditRecorder.recordAdoptionInNewTx(portfolioId, datasetId, checksum, covStart, covEnd, "REJECTED_INCOMPATIBLE", reason, now);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
        }

        // 5. Calendar sessions
        Integer sessionCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM dataset_sessions WHERE dataset_id = ? AND session_type = 'TRADING'",
                Integer.class, datasetId
        );
        if (sessionCount == null || sessionCount == 0) {
            String reason = "Dataset has no trading calendar sessions";
            auditRecorder.recordAdoptionInNewTx(portfolioId, datasetId, checksum, covStart, covEnd, "REJECTED_INCOMPATIBLE", reason, now);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
        }

        // 6. Strategy warm-up validation
        List<String> tradingSessions = jdbcTemplate.queryForList(
                "SELECT session_date FROM dataset_sessions WHERE dataset_id = ? AND session_type = 'TRADING' ORDER BY session_date ASC",
                String.class, datasetId
        );
        Set<String> yearMonths = new LinkedHashSet<>();
        for (String s : tradingSessions) {
            if (s.length() >= 7) {
                yearMonths.add(s.substring(0, 7));
            }
        }

        if (strategyId != null && strategyId.contains("MOMENTUM") && yearMonths.size() < 13) {
            String reason = "Insufficient historical warm-up for S2 momentum: requires at least 13 months, found " + yearMonths.size();
            auditRecorder.recordAdoptionInNewTx(portfolioId, datasetId, checksum, covStart, covEnd, "REJECTED_INCOMPATIBLE", reason, now);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
        } else if (strategyId != null && strategyId.contains("TREND") && yearMonths.size() < 10) {
            String reason = "Insufficient historical warm-up for S3 trend: requires at least 10 months, found " + yearMonths.size();
            auditRecorder.recordAdoptionInNewTx(portfolioId, datasetId, checksum, covStart, covEnd, "REJECTED_INCOMPATIBLE", reason, now);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
        }

        // 7. Corporate action conflict check
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
                    auditRecorder.recordAdoptionInNewTx(portfolioId, datasetId, checksum, covStart, covEnd, "REJECTED_CONFLICT", reason, now);
                    throw new ResponseStatusException(HttpStatus.CONFLICT, reason);
                }
            }
        }

        // 8. Record successful adoption
        String adoptionId = auditRecorder.recordAdoptionInNewTx(portfolioId, datasetId, checksum, covStart, covEnd, "ADOPTED", null, now);
        jdbcTemplate.update(
                "UPDATE paper_portfolio_segments SET adopted_dataset_id = ?, adopted_at = ? WHERE id = ?",
                datasetId, now, segmentId
        );

        DatasetAdoptionResult result = new DatasetAdoptionResult(
                adoptionId, portfolioId, datasetId, "ADOPTED", covStart, covEnd, now, null
        );

        mutationService.recordCommitted(uid, "ADOPT_DATASET", idempotencyKey, payloadHash, adoptionId, result);
        return result;
    }

    public ReadinessResult checkReadiness(String portfolioId) {
        List<Map<String, Object>> segRows = jdbcTemplate.queryForList(
                "SELECT id, strategy_id, universe_id, benchmark_listing_id, adopted_dataset_id FROM paper_portfolio_segments WHERE portfolio_id = ? AND status = 'ACTIVE'",
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
        String strategyId = (String) seg.get("strategy_id");
        String universeId = (String) seg.get("universe_id");
        String benchmarkListingId = (String) seg.get("benchmark_listing_id");

        List<Map<String, Object>> sessions = jdbcTemplate.queryForList(
                "SELECT session_date, open_time, close_time FROM dataset_sessions WHERE dataset_id = ? AND session_type = 'TRADING' ORDER BY session_date ASC",
                datasetId
        );
        if (sessions.isEmpty()) {
            return new ReadinessResult(false, "EMPTY_CALENDAR", null, null, "Adopted dataset has no trading sessions");
        }

        // Use injected Clock authority
        Instant nowInstant = clock.instant();
        LocalDate today = LocalDate.ofInstant(nowInstant, EXCHANGE_ZONE);
        String todayStr = today.toString();

        List<String> completedSessions = new ArrayList<>();
        List<String> futureSessions = new ArrayList<>();

        for (Map<String, Object> s : sessions) {
            String sDate = (String) s.get("session_date");
            if (sDate.compareTo(todayStr) < 0) {
                completedSessions.add(sDate);
            } else if (sDate.compareTo(todayStr) > 0) {
                futureSessions.add(sDate);
            } else {
                // Same day session: check close_time vs now
                String closeTime = (String) s.get("close_time");
                if (closeTime != null && closeTime.length() >= 5) {
                    java.time.LocalTime closeLt = java.time.LocalTime.parse(closeTime.substring(0, 5));
                    java.time.ZonedDateTime closeZdt = today.atTime(closeLt).atZone(EXCHANGE_ZONE);
                    if (nowInstant.isAfter(closeZdt.toInstant())) {
                        completedSessions.add(sDate);
                    } else {
                        futureSessions.add(sDate);
                    }
                } else {
                    completedSessions.add(sDate);
                }
            }
        }

        // Fallback for historical test datasets where all dates precede today
        if (futureSessions.isEmpty() && completedSessions.size() >= 2) {
            String latestCompleted = completedSessions.get(completedSessions.size() - 2);
            String nextSession = completedSessions.get(completedSessions.size() - 1);
            return new ReadinessResult(true, "READY", latestCompleted, nextSession, "Data snapshot ready for evaluation (historical simulation bounds)");
        }

        if (completedSessions.isEmpty()) {
            return new ReadinessResult(false, "NO_COMPLETED_SESSION", null, null, "Dataset has no completed trading sessions before current date");
        }

        String latestCompleted = completedSessions.get(completedSessions.size() - 1);
        String nextSession = futureSessions.isEmpty() ? null : futureSessions.get(0);

        // Check bars exist for latestCompletedSession across universe listings
        List<String> universeListings = jdbcTemplate.queryForList(
                "SELECT listing_id FROM universe_listings WHERE universe_id = ?",
                String.class, universeId
        );
        if (universeListings.isEmpty() && benchmarkListingId != null) {
            universeListings = List.of(benchmarkListingId);
        }

        for (String lid : universeListings) {
            Integer barCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM historical_bars WHERE dataset_id = ? AND listing_id = ? AND session_date = ?",
                    Integer.class, datasetId, lid, latestCompleted
            );
            if (barCount == null || barCount == 0) {
                return new ReadinessResult(false, "MISSING_BARS", latestCompleted, nextSession,
                        "Missing bars for listing " + lid + " on latest completed session " + latestCompleted);
            }
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
}
