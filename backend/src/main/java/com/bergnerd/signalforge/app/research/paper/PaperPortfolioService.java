package com.bergnerd.signalforge.app.research.paper;

import com.bergnerd.signalforge.app.accounting.AccountingCore;
import com.bergnerd.signalforge.app.operation.OperationService;
import com.bergnerd.signalforge.app.research.ResearchDtos;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaperPortfolioService {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final OperationService operationService;
    private final PaperDataReadinessService dataReadinessService;
    private final PaperStrategyAdapter strategyAdapter;
    private final PaperMutationService mutationService;
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final Map<String, ReentrantLock> portfolioLocks = new ConcurrentHashMap<>();

    // -------------------------------------------------------------
    // 1. Activation
    // -------------------------------------------------------------

    public ResearchDtos.PaperSegmentDto activatePortfolio(
            String portfolioId,
            String ownerId,
            String idempotencyKey,
            ResearchDtos.ActivatePortfolioRequest request
    ) {
        String uid = normalizeUser(ownerId);
        validatePortfolioOwnership(portfolioId, uid);

        // Idempotency check
        String payloadHash = PaperMutationService.computeHash(
                "ACTIVATE|" + portfolioId + "|" + request.strategyId() + "|" + request.universeId() + "|" + request.approvalMode()
        );
        Optional<PaperMutationService.MutationEntry> cached = mutationService.checkMutation(uid, "ACTIVATE_PORTFOLIO", idempotencyKey, payloadHash);
        if (cached.isPresent() && "COMMITTED".equals(cached.get().status())) {
            return getActiveSegment(portfolioId, uid);
        }

        // Verify single active segment constraint
        Integer activeCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM paper_portfolio_segments WHERE portfolio_id = ? AND status = 'ACTIVE'",
                Integer.class, portfolioId
        );
        if (activeCount != null && activeCount > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Portfolio already has an active tracking segment");
        }

        // Verify no existing non-cash positions
        Integer nonCashPositions = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM positions WHERE portfolio_id = ? AND quantity != '0' AND quantity != '0.00'",
                Integer.class, portfolioId
        );
        if (nonCashPositions != null && nonCashPositions > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Existing non-cash holdings unsupported for paper tracking activation");
        }

        Integer universeExists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM universes WHERE id = ?", Integer.class, request.universeId());
        Integer benchmarkExists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM listings WHERE id = ? AND quote_currency = 'EUR'",
                Integer.class, request.benchmarkListingId());
        if (universeExists == null || universeExists == 0 || benchmarkExists == null || benchmarkExists == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Activation requires an existing universe and EUR benchmark listing");
        }

        String now = clock.instant().toString();
        String rawCash = jdbcTemplate.queryForObject(
                "SELECT cash_amount FROM portfolio_state WHERE portfolio_id = ?",
                String.class, portfolioId
        );
        final String cashAmount = rawCash != null ? rawCash : "0.00";

        String segmentId = "seg-" + UUID.randomUUID();
        String costPolicyJson;
        try {
            costPolicyJson = objectMapper.writeValueAsString(request.costPolicy());
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid cost policy format");
        }

        String approvalMode = request.approvalMode().trim().toUpperCase();
        if (!"MANUAL".equals(approvalMode) && !"AUTO_PAPER".equals(approvalMode)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid approval mode: " + approvalMode);
        }

        transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.update(
                    "UPDATE portfolios SET paper_started_at = ? WHERE id = ?",
                    now, portfolioId
            );

            jdbcTemplate.update(
                    "INSERT INTO paper_portfolio_segments (id, portfolio_id, strategy_id, strategy_version, universe_id, " +
                            "benchmark_listing_id, cost_policy_json, approval_mode, status, initial_equity, " +
                            "opening_observation_instant, created_at) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?)",
                    segmentId, portfolioId, request.strategyId(), request.strategyVersion(),
                    request.universeId(), request.benchmarkListingId(), costPolicyJson, approvalMode,
                    cashAmount, now, now
            );

            jdbcTemplate.update(
                    "INSERT INTO paper_mode_history (id, portfolio_id, from_mode, to_mode, transition_instant, trigger_type, notes) " +
                            "VALUES (?, ?, 'MANUAL', ?, ?, 'USER', 'Initial segment activation')",
                    "mode-" + UUID.randomUUID(), portfolioId, approvalMode, now
            );

            // Record initial opening valuation
            jdbcTemplate.update(
                    "INSERT OR IGNORE INTO paper_valuations (id, portfolio_id, session_date, observation_kind, observation_instant, " +
                            "portfolio_state_revision, cash_balance, positions_market_value, receivables_value, total_equity, cumulative_return, " +
                            "high_water_mark, drawdown, data_readiness_status, is_complete, adopted_dataset_checksum) " +
                            "VALUES (?, ?, ?, 'OPENING', ?, 1, ?, '0.00', '0.00', ?, '0.000000', ?, '0.000000', 'COMPLETE', 1, 'none')",
                    "val-" + UUID.randomUUID(), portfolioId, now.substring(0, 10), now, cashAmount, cashAmount, cashAmount
            );
            mutationService.recordCommitted(uid, "ACTIVATE_PORTFOLIO", idempotencyKey, payloadHash,
                    segmentId, Map.of("segmentId", segmentId));
        });

        if (request.datasetId() != null && !request.datasetId().isBlank()) {
            dataReadinessService.adoptDataset(portfolioId, request.datasetId(), uid, idempotencyKey + "-adopt");
        }

        return getActiveSegment(portfolioId, uid);
    }

    // -------------------------------------------------------------
    // 2. Evaluation & Proposals
    // -------------------------------------------------------------

    public ResearchDtos.PaperProposalDto evaluatePortfolio(String portfolioId, String ownerId, String idempotencyKey) {
        String uid = normalizeUser(ownerId);
        validatePortfolioOwnership(portfolioId, uid);

        Map<String, Object> seg = getActiveSegmentRow(portfolioId);
        String datasetId = (String) seg.get("adopted_dataset_id");
        if (datasetId == null || datasetId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot evaluate portfolio: No dataset adopted");
        }

        PaperDataReadinessService.ReadinessResult readiness = dataReadinessService.checkReadiness(portfolioId);
        if (!readiness.ready()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Data snapshot not ready for evaluation: " + readiness.reasonCode() + " - " + readiness.details());
        }

        String strategyId = (String) seg.get("strategy_id");
        String strategyVersion = (String) seg.get("strategy_version");
        String universeId = (String) seg.get("universe_id");
        String benchmarkListingId = (String) seg.get("benchmark_listing_id");

        String evalSessionDate = readiness.latestCompletedSession();
        String nextSessionDate = readiness.nextSession();
        Map<String, Object> datasetIdentity = jdbcTemplate.queryForMap(
                "SELECT content_checksum, schema_version FROM datasets WHERE id = ?", datasetId);
        String datasetChecksum = (String) datasetIdentity.get("content_checksum");
        String calendarId = jdbcTemplate.queryForObject(
                "SELECT calendar_id FROM dataset_listings WHERE dataset_id = ? AND listing_id = ?",
                String.class, datasetId, benchmarkListingId);
        Map<String, Object> nextSession = jdbcTemplate.queryForMap(
                "SELECT open_time FROM dataset_sessions WHERE dataset_id = ? AND calendar_id = ? AND session_date = ?",
                datasetId, calendarId, nextSessionDate);
        String scheduledOpenInstant = PaperDataReadinessService.sessionInstant(
                nextSessionDate, (String) nextSession.get("open_time"), exchangeZone(calendarId)).toString();
        String cycleId = "cycle-" + evalSessionDate + "-open-" + nextSessionDate;

        String payloadHash = PaperMutationService.computeHash(
                "EVAL|" + portfolioId + "|cycle-" + evalSessionDate);
        Optional<PaperMutationService.MutationEntry> cached = mutationService.checkMutation(uid, "EVALUATE_PORTFOLIO", idempotencyKey, payloadHash);
        if (cached.isPresent() && "COMMITTED".equals(cached.get().status())) {
            return getProposal(portfolioId, cached.get().resourceId(), uid);
        }

        Integer portRevision = jdbcTemplate.queryForObject(
                "SELECT revision FROM portfolio_state WHERE portfolio_id = ?", Integer.class, portfolioId
        );
        if (portRevision == null) portRevision = 1;

        List<Map<String, Object>> existingCycle = jdbcTemplate.queryForList(
                "SELECT id, dataset_id, dataset_checksum FROM paper_proposals " +
                "WHERE portfolio_id = ? AND evaluation_session_date = ? AND scheduled_open_session_date = ? " +
                        "AND portfolio_state_version = ? ORDER BY created_at DESC LIMIT 1",
                portfolioId, evalSessionDate, nextSessionDate, portRevision);
        if (!existingCycle.isEmpty()) {
            Map<String, Object> existing = existingCycle.get(0);
            if (!datasetId.equals(existing.get("dataset_id")) ||
                    !datasetChecksum.equals(existing.get("dataset_checksum"))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "This cycle and portfolio revision already have a proposal from another dataset");
            }
            String existingId = (String) existing.get("id");
            transactionTemplate.executeWithoutResult(status -> mutationService.recordCommitted(
                    uid, "EVALUATE_PORTFOLIO", idempotencyKey, payloadHash,
                    existingId, Map.of("proposalId", existingId)));
            return getProposal(portfolioId, existingId, uid);
        }

        // Execute Strategy via PaperStrategyAdapter (M4 strategy core reuse)
        Instant nowInstant = clock.instant();
        String now = nowInstant.toString();
        String resolvedInputCutoff = jdbcTemplate.queryForObject(
                "SELECT MAX(available_at) FROM historical_bars WHERE dataset_id = ? AND session_date <= ? AND available_at <= ?",
                String.class, datasetId, evalSessionDate, now);
        if (resolvedInputCutoff == null) {
            resolvedInputCutoff = now;
        }
        final String inputCutoff = resolvedInputCutoff;
        PaperStrategyAdapter.StrategyProposalOutcome outcome = strategyAdapter.evaluateStrategy(
                portfolioId, datasetId, universeId, benchmarkListingId, strategyId, strategyVersion, evalSessionDate, nowInstant
        );

        String proposalId = "prop-" + UUID.randomUUID();

        // Calculate current equity for frozen desired units sizing
        String cashStr = jdbcTemplate.queryForObject("SELECT cash_amount FROM portfolio_state WHERE portfolio_id = ?", String.class, portfolioId);
        BigDecimal currentCash = new BigDecimal(cashStr != null ? cashStr : "0.00");
        BigDecimal currentEquity = currentCash;

        List<Map<String, Object>> posRows = jdbcTemplate.queryForList(
                "SELECT listing_id, quantity FROM positions WHERE portfolio_id = ? AND quantity != '0'", portfolioId
        );
        for (Map<String, Object> pos : posRows) {
            String lid = (String) pos.get("listing_id");
            BigDecimal qty = new BigDecimal((String) pos.get("quantity"));
            BigDecimal price = outcome.cutoffPrices().getOrDefault(lid, BigDecimal.ZERO);
            currentEquity = currentEquity.add(qty.multiply(price));
        }

        // Atomic evaluation write
        final int finalPortRevision = portRevision;
        final BigDecimal finalEquity = currentEquity;
        transactionTemplate.executeWithoutResult(status -> {
            // Supersede previous unaccepted proposals for this portfolio
            jdbcTemplate.update(
                    "UPDATE paper_proposals SET status = 'SUPERSEDED' WHERE portfolio_id = ? AND status = 'PROPOSED'",
                    portfolioId
            );

            // Insert proposal header
            jdbcTemplate.update(
                    "INSERT INTO paper_proposals (id, portfolio_id, cycle_id, strategy_id, strategy_version, dataset_id, dataset_checksum, " +
                            "calendar_id, calendar_version, evaluation_session_date, input_cutoff_instant, evaluation_instant, " +
                            "scheduled_open_session_date, scheduled_open_instant, reason_code, portfolio_state_version, status, created_at) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PROPOSED', ?)",
                    proposalId, portfolioId, cycleId, strategyId, strategyVersion, datasetId,
                    datasetChecksum, calendarId, (String) datasetIdentity.get("schema_version"), evalSessionDate,
                    inputCutoff, now, nextSessionDate,
                    scheduledOpenInstant, outcome.evaluatedSignal().reasonCode(), finalPortRevision, now
            );

            // The target is frozen as weight/rank/reason evidence. Units are resolved at the observed open.
            for (var item : outcome.evaluatedSignal().items()) {
                BigDecimal targetWeight = item.targetWeight() != null ? item.targetWeight() : BigDecimal.ZERO;
                BigDecimal targetCash = finalEquity.multiply(targetWeight);
                BigDecimal priceRef = outcome.cutoffPrices().getOrDefault(item.listingId(), BigDecimal.ONE);
                BigDecimal desiredUnits = BigDecimal.ZERO;
                if (priceRef.compareTo(BigDecimal.ZERO) > 0) {
                    desiredUnits = targetCash.divide(priceRef, 0, RoundingMode.FLOOR);
                }

                jdbcTemplate.update(
                        "INSERT INTO paper_proposal_items (id, proposal_id, listing_id, rank, target_weight, cutoff_estimated_units, score, " +
                                "reason_code, reason_description, raw_price_reference, observation_kind) " +
                                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'CLOSE')",
                        "item-" + UUID.randomUUID(), proposalId, item.listingId(), item.rank(),
                        targetWeight.toPlainString(), desiredUnits.toPlainString(),
                        item.score() != null ? item.score().toPlainString() : null,
                        item.reasonCode(), item.reasonCode(), priceRef.toPlainString()
                );
            }

            // Insert observations
            for (var obs : outcome.observations()) {
                jdbcTemplate.update(
                        "INSERT INTO paper_proposal_observations (id, proposal_id, listing_id, observation_session_date, observation_type, observation_value, observed_at) " +
                                "VALUES (?, ?, ?, ?, ?, ?, ?)",
                        "obs-" + UUID.randomUUID(), proposalId, obs.listingId(), obs.observationSessionDate(), obs.observationType(), obs.observationValue(), now
                );
            }
            mutationService.recordCommitted(uid, "EVALUATE_PORTFOLIO", idempotencyKey, payloadHash,
                    proposalId, Map.of("proposalId", proposalId));
        });

        return getProposal(portfolioId, proposalId, uid);
    }

    // -------------------------------------------------------------
    // 3. Acceptance & Rejection
    // -------------------------------------------------------------

    public ResearchDtos.PaperProposalDto acceptProposal(
            String portfolioId, String proposalId, String ownerId, String idempotencyKey, ResearchDtos.AcceptProposalRequest request
    ) {
        return acceptProposal(portfolioId, proposalId, ownerId, idempotencyKey, request, "MANUAL", "USER_ACCEPT");
    }

    private ResearchDtos.PaperProposalDto acceptProposal(
            String portfolioId,
            String proposalId,
            String ownerId,
            String idempotencyKey,
            ResearchDtos.AcceptProposalRequest request,
            String approvalMode,
            String triggerType
    ) {
        String uid = normalizeUser(ownerId);
        validatePortfolioOwnership(portfolioId, uid);

        String payloadHash = PaperMutationService.computeHash("ACCEPT|" + portfolioId + "|" + proposalId);
        String mutationAction = "AUTO_PAPER".equals(approvalMode) ? "AUTO_ACCEPT_PROPOSAL" : "ACCEPT_PROPOSAL";
        Optional<PaperMutationService.MutationEntry> cached = mutationService.checkMutation(uid, mutationAction, idempotencyKey, payloadHash);
        if (cached.isPresent() && "COMMITTED".equals(cached.get().status())) {
            return getProposal(portfolioId, proposalId, uid);
        }

        Map<String, Object> prop = getProposalRow(portfolioId, proposalId);
        String currentStatus = (String) prop.get("status");
        if (!"PROPOSED".equals(currentStatus)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cannot accept proposal with status: " + currentStatus);
        }

        String now = clock.instant().toString();
        String scheduledOpenInstant = (String) prop.get("scheduled_open_instant");
        String scheduledSessionDate = (String) prop.get("scheduled_open_session_date");
        String reinvestmentReceivableId = (String) prop.get("reinvestment_receivable_id");
        String orderType = reinvestmentReceivableId == null ? "REBALANCE" : "REINVESTMENT";

        // Late acceptance check: cannot execute at past open!
        if (now.compareTo(scheduledOpenInstant) >= 0) {
            jdbcTemplate.update(
                    "UPDATE paper_proposals SET status = 'SUPERSEDED', rejection_reason = 'Scheduled open passed before acceptance' WHERE id = ? AND status = 'PROPOSED'",
                    proposalId
            );
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Scheduled open session has passed; proposal superseded. Re-evaluation required.");
        }

        // Atomic CAS update and Intent creation
        transactionTemplate.executeWithoutResult(status -> {
            int updated = jdbcTemplate.update(
                    "UPDATE paper_proposals SET status = 'ACCEPTED', accepted_at = ? WHERE id = ? AND status = 'PROPOSED'",
                    now, proposalId
            );
            if (updated == 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Proposal was concurrently modified or accepted");
            }

            String intentId = "intent-" + UUID.randomUUID();
            jdbcTemplate.update(
                    "INSERT INTO paper_execution_intents (id, portfolio_id, proposal_id, reinvestment_receivable_id, order_type, scheduled_session_date, " +
                            "scheduled_open_instant, approval_mode, status, created_at) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?)",
                    intentId, portfolioId, proposalId, reinvestmentReceivableId, orderType,
                    scheduledSessionDate, scheduledOpenInstant, approvalMode, now
            );

            jdbcTemplate.update(
                    "INSERT INTO paper_intent_transitions (id, intent_id, from_status, to_status, trigger_type, transition_instant, notes) " +
                            "VALUES (?, ?, 'NONE', 'PENDING', ?, ?, ?)",
                    "trans-" + UUID.randomUUID(), intentId, triggerType, now,
                    "AUTO_PAPER".equals(approvalMode) ? "Automatic proposal acceptance" : "Manual proposal acceptance"
            );
            mutationService.recordCommitted(uid, mutationAction, idempotencyKey, payloadHash,
                    proposalId, Map.of("proposalId", proposalId, "intentId", intentId));
        });

        return getProposal(portfolioId, proposalId, uid);
    }

    public ResearchDtos.PaperProposalDto rejectProposal(
            String portfolioId, String proposalId, String ownerId, String idempotencyKey, ResearchDtos.RejectProposalRequest request
    ) {
        String uid = normalizeUser(ownerId);
        validatePortfolioOwnership(portfolioId, uid);

        String payloadHash = PaperMutationService.computeHash("REJECT|" + portfolioId + "|" + proposalId);
        Optional<PaperMutationService.MutationEntry> cached = mutationService.checkMutation(uid, "REJECT_PROPOSAL", idempotencyKey, payloadHash);
        if (cached.isPresent() && "COMMITTED".equals(cached.get().status())) {
            return getProposal(portfolioId, proposalId, uid);
        }

        Map<String, Object> prop = getProposalRow(portfolioId, proposalId);
        String currentStatus = (String) prop.get("status");
        if (!"PROPOSED".equals(currentStatus)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cannot reject proposal with status: " + currentStatus);
        }

        String now = clock.instant().toString();
        transactionTemplate.executeWithoutResult(status -> {
            int updated = jdbcTemplate.update(
                    "UPDATE paper_proposals SET status = 'REJECTED', rejected_at = ?, rejection_reason = ? WHERE id = ? AND status = 'PROPOSED'",
                    now, request != null ? request.rejectionReason() : "USER_REJECT", proposalId
            );
            if (updated == 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Proposal was concurrently modified");
            }
            mutationService.recordCommitted(uid, "REJECT_PROPOSAL", idempotencyKey, payloadHash,
                    proposalId, Map.of("proposalId", proposalId));
        });
        return getProposal(portfolioId, proposalId, uid);
    }

    // -------------------------------------------------------------
    // 4. Approval Mode & Coordinator
    // -------------------------------------------------------------

    public ResearchDtos.PaperSegmentDto changeApprovalMode(
            String portfolioId, String ownerId, String idempotencyKey, ResearchDtos.ChangeApprovalModeRequest request
    ) {
        String uid = normalizeUser(ownerId);
        validatePortfolioOwnership(portfolioId, uid);

        ReentrantLock lock = portfolioLocks.computeIfAbsent(portfolioId, ignored -> new ReentrantLock(true));
        lock.lock();
        try {
        Map<String, Object> seg = getActiveSegmentRow(portfolioId);
        String currentMode = (String) seg.get("approval_mode");
        String targetMode = request.approvalMode().trim().toUpperCase();
        if (!"MANUAL".equals(targetMode) && !"AUTO_PAPER".equals(targetMode)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid approval mode: " + targetMode);
        }

        String payloadHash = PaperMutationService.computeHash("MODE|" + portfolioId + "|" + targetMode);
        Optional<PaperMutationService.MutationEntry> cached = mutationService.checkMutation(uid, "SET_APPROVAL_MODE", idempotencyKey, payloadHash);
        if (cached.isPresent() && "COMMITTED".equals(cached.get().status())) {
            return getActiveSegment(portfolioId, uid);
        }

        if (!currentMode.equals(targetMode)) {
            String now = clock.instant().toString();
            transactionTemplate.executeWithoutResult(status -> {
                int updated = jdbcTemplate.update(
                        "UPDATE paper_portfolio_segments SET approval_mode = ? " +
                                "WHERE id = ? AND status = 'ACTIVE' AND approval_mode = ?",
                        targetMode, seg.get("id"), currentMode
                );
                if (updated != 1) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Approval mode changed concurrently");
                }
                jdbcTemplate.update(
                        "INSERT INTO paper_mode_history (id, portfolio_id, from_mode, to_mode, transition_instant, trigger_type, notes) " +
                                "VALUES (?, ?, ?, ?, ?, 'USER', ?)",
                        "mode-" + UUID.randomUUID(), portfolioId, currentMode, targetMode, now,
                        "Approval mode changed from " + currentMode + " to " + targetMode
                );
                mutationService.recordCommitted(uid, "SET_APPROVAL_MODE", idempotencyKey, payloadHash,
                        (String) seg.get("id"), Map.of("approvalMode", targetMode));
            });
        } else {
            mutationService.recordCommitted(uid, "SET_APPROVAL_MODE", idempotencyKey, payloadHash,
                    (String) seg.get("id"), Map.of("approvalMode", targetMode));
        }

        return getActiveSegment(portfolioId, uid);
        } finally {
            lock.unlock();
        }
    }

    public void runAutomaticCycle(String portfolioId, String ownerId) {
        String uid = normalizeUser(ownerId);
        validatePortfolioOwnership(portfolioId, uid);
        ReentrantLock lock = portfolioLocks.computeIfAbsent(portfolioId, ignored -> new ReentrantLock(true));
        lock.lock();
        try {
            Map<String, Object> segment = getActiveSegmentRow(portfolioId);
            if (!"AUTO_PAPER".equals(segment.get("approval_mode"))) {
                return;
            }

            String now = clock.instant().toString();
            recordMissedAutomaticProposals(portfolioId, now);
            processPortfolioEvents(portfolioId, uid, "auto-process-" + portfolioId + "-" + now);

            Integer openIntents = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM paper_execution_intents WHERE portfolio_id = ? AND status IN ('PENDING', 'WAITING_FOR_OBSERVATION')",
                    Integer.class, portfolioId);
            if (openIntents != null && openIntents > 0) {
                return;
            }

            PaperDataReadinessService.ReadinessResult readiness = dataReadinessService.checkReadiness(portfolioId);
            if (!readiness.ready()) {
                return;
            }
            String cycleId = "cycle-" + readiness.latestCompletedSession() + "-open-" + readiness.nextSession();
            List<Map<String, Object>> existing = jdbcTemplate.queryForList(
                    "SELECT id, status FROM paper_proposals WHERE portfolio_id = ? AND evaluation_session_date = ? " +
                            "AND scheduled_open_session_date = ? ORDER BY created_at DESC LIMIT 1",
                    portfolioId, readiness.latestCompletedSession(), readiness.nextSession());
            if (!existing.isEmpty() && !"PROPOSED".equals(existing.get(0).get("status"))) {
                return;
            }

            ResearchDtos.PaperProposalDto proposal = existing.isEmpty()
                    ? evaluatePortfolio(portfolioId, uid, "auto-evaluate-" + portfolioId + "-" + cycleId)
                    : getProposal(portfolioId, (String) existing.get(0).get("id"), uid);
            acceptProposal(portfolioId, proposal.id(), uid, "auto-accept-" + proposal.id(),
                    new ResearchDtos.AcceptProposalRequest("AUTO_PAPER scheduled acceptance"),
                    "AUTO_PAPER", "AUTO_SCHEDULE");
        } finally {
            lock.unlock();
        }
    }

    private void recordMissedAutomaticProposals(String portfolioId, String now) {
        List<Map<String, Object>> expired = jdbcTemplate.queryForList(
                "SELECT id, scheduled_open_session_date, scheduled_open_instant FROM paper_proposals " +
                        "WHERE portfolio_id = ? AND status = 'PROPOSED' AND scheduled_open_instant <= ?",
                portfolioId, now);
        for (Map<String, Object> proposal : expired) {
            String proposalId = (String) proposal.get("id");
            transactionTemplate.executeWithoutResult(status -> {
                int changed = jdbcTemplate.update(
                        "UPDATE paper_proposals SET status = 'BLOCKED', rejection_reason = 'AUTO_PAPER intent was not durable before scheduled open' " +
                                "WHERE id = ? AND status = 'PROPOSED'",
                        proposalId);
                if (changed != 1) {
                    return;
                }
                String intentId = "intent-" + UUID.randomUUID();
                jdbcTemplate.update(
                        "INSERT INTO paper_execution_intents (id, portfolio_id, proposal_id, order_type, scheduled_session_date, " +
                                "scheduled_open_instant, approval_mode, status, created_at) VALUES (?, ?, ?, 'REBALANCE', ?, ?, 'AUTO_PAPER', 'MISSED', ?)",
                        intentId, portfolioId, proposalId, proposal.get("scheduled_open_session_date"),
                        proposal.get("scheduled_open_instant"), now);
                jdbcTemplate.update(
                        "INSERT INTO paper_intent_transitions (id, intent_id, from_status, to_status, trigger_type, transition_instant, notes) " +
                                "VALUES (?, ?, 'NONE', 'MISSED', 'SYSTEM_DOWNTIME_RECOVERY', ?, 'No durable pre-open intent existed')",
                        "trans-" + UUID.randomUUID(), intentId, now);
            });
        }
    }

    // -------------------------------------------------------------
    // 5. Events, Corporate Actions, and Execution
    // -------------------------------------------------------------

    public void processPortfolioEvents(String portfolioId, String ownerId, String idempotencyKey) {
        String uid = normalizeUser(ownerId);
        validatePortfolioOwnership(portfolioId, uid);

        String payloadHash = PaperMutationService.computeHash("PROCESS|" + portfolioId);
        Optional<PaperMutationService.MutationEntry> cached = mutationService.checkMutation(uid, "PROCESS_EVENTS", idempotencyKey, payloadHash);
        if (cached.isPresent() && "COMMITTED".equals(cached.get().status())) {
            return;
        }

        ReentrantLock lock = portfolioLocks.computeIfAbsent(portfolioId, ignored -> new ReentrantLock(true));
        lock.lock();
        try {
            operationService.executeCoordinatedBatch(() -> {
                Optional<PaperMutationService.MutationEntry> replay =
                        mutationService.checkMutation(uid, "PROCESS_EVENTS", idempotencyKey, payloadHash);
                if (replay.isPresent() && "COMMITTED".equals(replay.get().status())) {
                    return null;
                }

                Map<String, Object> seg = getActiveSegmentRow(portfolioId);
                String datasetId = (String) seg.get("adopted_dataset_id");
                if (datasetId == null || datasetId.isBlank()) {
                    mutationService.recordCommitted(uid, "PROCESS_EVENTS", idempotencyKey, payloadHash, portfolioId,
                            Map.of("status", "NO_ADOPTED_DATASET"));
                    return null;
                }

                String now = clock.instant().toString();
                String currentSessionDate = LocalDate.ofInstant(clock.instant(), PaperDataReadinessService.EXCHANGE_ZONE).toString();
                List<Map<String, Object>> dueOpens = jdbcTemplate.queryForList(
                        "SELECT DISTINCT scheduled_session_date, scheduled_open_instant FROM paper_execution_intents " +
                                "WHERE portfolio_id = ? AND status IN ('PENDING', 'WAITING_FOR_OBSERVATION') " +
                                "AND scheduled_open_instant <= ? ORDER BY scheduled_open_instant",
                        portfolioId, now);
                boolean waitingForEarlierOpen = false;
                for (Map<String, Object> dueOpen : dueOpens) {
                    String sessionDate = (String) dueOpen.get("scheduled_session_date");
                    String openInstant = (String) dueOpen.get("scheduled_open_instant");
                    // Ex-date actions take effect before that session's opening trades.
                    processCorporateActions(portfolioId, datasetId, sessionDate, now);
                    if (!executePendingIntents(portfolioId, seg, datasetId, openInstant, now)) {
                        waitingForEarlierOpen = true;
                        break;
                    }
                }
                if (!waitingForEarlierOpen) {
                    processCorporateActions(portfolioId, datasetId, currentSessionDate, now);
                    // Cash first observed during this processing pass cannot fund an earlier opening fill.
                    settleDueReceivables(portfolioId, uid, seg, datasetId, currentSessionDate, now);
                }
                calculateAndRecordValuation(portfolioId, datasetId, currentSessionDate, now);
                mutationService.recordCommitted(uid, "PROCESS_EVENTS", idempotencyKey, payloadHash, portfolioId,
                        Map.of("processedAt", now));
                return null;
            });
        } finally {
            lock.unlock();
        }
    }

    private void processCorporateActions(String portfolioId, String datasetId, String currentSessionDate, String now) {
        Map<String, Object> dataset = jdbcTemplate.queryForMap(
                "SELECT source, content_checksum FROM datasets WHERE id = ?", datasetId);
        String sourceNamespace = (String) dataset.get("source");
        String datasetChecksum = (String) dataset.get("content_checksum");
        List<Map<String, Object>> actions = jdbcTemplate.queryForList(
                "SELECT action_id, listing_id, action_type, effective_date, available_at, split_ratio_numerator, " +
                        "split_ratio_denominator, distribution_amount, distribution_currency, payment_date, payment_instant " +
                        "FROM historical_actions WHERE dataset_id = ? AND effective_date <= ? AND available_at <= ? " +
                        "ORDER BY effective_date ASC, CASE action_type WHEN 'SPLIT' THEN 0 ELSE 1 END, action_id ASC",
                datasetId, currentSessionDate, now
        );

        for (Map<String, Object> a : actions) {
            String actionId = (String) a.get("action_id");
            String lid = (String) a.get("listing_id");
            String actionType = (String) a.get("action_type");
            String effDate = (String) a.get("effective_date");

            String termsHash = PaperDataReadinessService.computeTermsHash(a);
            String payloadHash = PaperMutationService.computeHash("ACTION|" + actionId + "|" + termsHash);
            List<Map<String, Object>> prior = jdbcTemplate.queryForList(
                    "SELECT terms_hash FROM paper_processed_corporate_actions WHERE portfolio_id = ? AND source_namespace = ? " +
                            "AND listing_id = ? AND action_type = ? AND action_id = ?",
                    portfolioId, sourceNamespace, lid, actionType, actionId);
            if (!prior.isEmpty()) {
                if (!termsHash.equals(prior.get(0).get("terms_hash"))) {
                    throw new IllegalStateException("Conflicting terms for previously processed action " + actionId);
                }
                continue;
            }

            if ("SPLIT".equalsIgnoreCase(actionType)) {
                int num = ((Number) a.get("split_ratio_numerator")).intValue();
                int den = ((Number) a.get("split_ratio_denominator")).intValue();
                BigDecimal ratio = BigDecimal.valueOf(num).divide(BigDecimal.valueOf(den), 8, RoundingMode.HALF_EVEN);
                BigDecimal entitledQuantity = quantityAtEvent(portfolioId, lid, effDate);
                String operationId = null;
                if (entitledQuantity.compareTo(BigDecimal.ZERO) > 0) {
                    OperationService.SplitResult splitRes = operationService.applySplit(
                            portfolioId, lid, ratio, "idemp-split-" + sourceNamespace + "-" + actionId,
                            effDate + "T00:00:00Z"
                    );
                    operationId = splitRes.operationId();
                }

                jdbcTemplate.update(
                        "INSERT INTO paper_processed_corporate_actions (id, portfolio_id, source_namespace, listing_id, action_id, " +
                                "action_type, terms_hash, payload_hash, effective_date, availability_instant, processing_instant, " +
                                "dataset_id, dataset_checksum, status, linked_operation_id) " +
                                "VALUES (?, ?, ?, ?, ?, 'SPLIT', ?, ?, ?, ?, ?, ?, ?, 'PROCESSED', ?)",
                        "pca-" + UUID.randomUUID(), portfolioId, sourceNamespace, lid, actionId, termsHash, payloadHash,
                        effDate, a.get("available_at"), now, datasetId, datasetChecksum, operationId
                );
            } else if ("CASH_DISTRIBUTION".equalsIgnoreCase(actionType) || "DISTRIBUTION".equalsIgnoreCase(actionType)) {
                BigDecimal distAmt = a.get("distribution_amount") != null ? new BigDecimal(String.valueOf(a.get("distribution_amount"))) : BigDecimal.ZERO;
                String payDate = a.get("payment_date") != null ? (String) a.get("payment_date") : effDate;
                BigDecimal heldQty = quantityAtEvent(portfolioId, lid, effDate);
                String recId = null;

                if (heldQty.compareTo(BigDecimal.ZERO) > 0 && distAmt.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal grossAmt = heldQty.multiply(distAmt).setScale(2, RoundingMode.HALF_EVEN);
                    BigDecimal netAmt = grossAmt;
                    recId = "rec-" + UUID.randomUUID();

                    jdbcTemplate.update(
                            "INSERT INTO paper_receivables (id, portfolio_id, listing_id, source_namespace, action_id, action_type, record_instant, " +
                                    "ex_date, payment_date, payment_instant, availability_instant, gross_amount, withholding_tax, net_amount, " +
                                    "status, created_at, dataset_id, dataset_checksum, terms_hash) " +
                                    "VALUES (?, ?, ?, ?, ?, 'CASH_DISTRIBUTION', ?, ?, ?, ?, ?, ?, '0.00', ?, 'PENDING', ?, ?, ?, ?)",
                            recId, portfolioId, lid, sourceNamespace, actionId, effDate + "T00:00:00Z", effDate, payDate,
                            a.get("payment_instant"), a.get("available_at"), grossAmt.toPlainString(), netAmt.toPlainString(), now,
                            datasetId, datasetChecksum, termsHash
                    );
                }
                jdbcTemplate.update(
                        "INSERT INTO paper_processed_corporate_actions (id, portfolio_id, source_namespace, listing_id, action_id, " +
                                "action_type, terms_hash, payload_hash, effective_date, availability_instant, processing_instant, " +
                                "dataset_id, dataset_checksum, status, linked_receivable_id) " +
                                "VALUES (?, ?, ?, ?, ?, 'CASH_DISTRIBUTION', ?, ?, ?, ?, ?, ?, ?, 'PROCESSED', ?)",
                        "pca-" + UUID.randomUUID(), portfolioId, sourceNamespace, lid, actionId, termsHash, payloadHash,
                        effDate, a.get("available_at"), now, datasetId, datasetChecksum, recId
                );
            }
        }
    }

    private void settleDueReceivables(
            String portfolioId,
            String ownerId,
            Map<String, Object> segment,
            String datasetId,
            String currentSessionDate,
            String now
    ) {
        List<Map<String, Object>> receivables = jdbcTemplate.queryForList(
                "SELECT id, listing_id, action_id, net_amount, payment_date, payment_instant FROM paper_receivables " +
                        "WHERE portfolio_id = ? AND status = 'PENDING'",
                portfolioId
        );

        for (Map<String, Object> r : receivables) {
            String recId = (String) r.get("id");
            String lid = (String) r.get("listing_id");
            String actionId = (String) r.get("action_id");
            BigDecimal netAmount = new BigDecimal((String) r.get("net_amount"));
            String paymentInstant = (String) r.get("payment_instant");
            boolean payable = paymentInstant != null && !paymentInstant.isBlank()
                    ? paymentInstant.compareTo(now) <= 0
                    : ((String) r.get("payment_date")).compareTo(currentSessionDate) <= 0;
            if (!payable) {
                continue;
            }

            OperationService.CashDistributionResult res = operationService.creditCashDistribution(
                    portfolioId, lid, actionId, netAmount, "idemp-rec-pay-" + recId, now
            );

            jdbcTemplate.update(
                    "UPDATE paper_receivables SET status = 'PAID', paid_operation_id = ?, paid_at = ? WHERE id = ?",
                    res.operationId(), now, recId
            );
            createReinvestmentProposal(portfolioId, ownerId, segment, datasetId, recId, lid, netAmount, now);
        }
    }

    private void createReinvestmentProposal(
            String portfolioId,
            String ownerId,
            Map<String, Object> segment,
            String datasetId,
            String receivableId,
            String listingId,
            BigDecimal paidAmount,
            String now
    ) {
        String strategyId = (String) segment.get("strategy_id");
        if (strategyId.contains("MOMENTUM")) {
            return;
        }
        if (strategyId.contains("TREND")) {
            Integer invested = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM positions WHERE portfolio_id = ? AND listing_id = ? AND quantity != '0'",
                    Integer.class, portfolioId, listingId);
            if (invested == null || invested == 0) {
                return;
            }
        }
        Integer existing = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM paper_proposals WHERE reinvestment_receivable_id = ?",
                Integer.class, receivableId);
        if (existing != null && existing > 0) {
            return;
        }

        PaperDataReadinessService.ReadinessResult readiness = dataReadinessService.checkReadiness(portfolioId);
        if (!readiness.ready()) {
            return;
        }
        String benchmarkListingId = (String) segment.get("benchmark_listing_id");
        Map<String, Object> dataset = jdbcTemplate.queryForMap(
                "SELECT content_checksum, schema_version FROM datasets WHERE id = ?", datasetId);
        String calendarId = jdbcTemplate.queryForObject(
                "SELECT calendar_id FROM dataset_listings WHERE dataset_id = ? AND listing_id = ?",
                String.class, datasetId, benchmarkListingId);
        String openTime = jdbcTemplate.queryForObject(
                "SELECT open_time FROM dataset_sessions WHERE dataset_id = ? AND calendar_id = ? AND session_date = ?",
                String.class, datasetId, calendarId, readiness.nextSession());
        String scheduledOpen = PaperDataReadinessService.sessionInstant(
                readiness.nextSession(), openTime, exchangeZone(calendarId)).toString();
        int revision = Optional.ofNullable(jdbcTemplate.queryForObject(
                "SELECT revision FROM portfolio_state WHERE portfolio_id = ?", Integer.class, portfolioId)).orElse(0);
        String proposalId = "prop-" + UUID.randomUUID();
        String close = jdbcTemplate.queryForObject(
                "SELECT close FROM historical_bars WHERE dataset_id = ? AND listing_id = ? AND session_date = ?",
                String.class, datasetId, listingId, readiness.latestCompletedSession());
        BigDecimal estimatedUnits = paidAmount.divide(new BigDecimal(close), 0, RoundingMode.FLOOR);
        jdbcTemplate.update(
                "INSERT INTO paper_proposals (id, portfolio_id, cycle_id, strategy_id, strategy_version, dataset_id, dataset_checksum, " +
                        "calendar_id, calendar_version, evaluation_session_date, input_cutoff_instant, evaluation_instant, " +
                        "scheduled_open_session_date, scheduled_open_instant, reason_code, portfolio_state_version, status, " +
                        "reinvestment_receivable_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, " +
                        "'DISTRIBUTION_REINVESTMENT', ?, 'PROPOSED', ?, ?)",
                proposalId, portfolioId, "reinvest-" + receivableId, strategyId, segment.get("strategy_version"), datasetId,
                dataset.get("content_checksum"), calendarId, dataset.get("schema_version"), readiness.latestCompletedSession(),
                now, now, readiness.nextSession(), scheduledOpen, revision, receivableId, now);
        jdbcTemplate.update(
                "INSERT INTO paper_proposal_items (id, proposal_id, listing_id, rank, target_weight, cutoff_estimated_units, " +
                        "score, reason_code, reason_description, raw_price_reference, observation_kind) " +
                        "VALUES (?, ?, ?, 1, '1', ?, NULL, 'DISTRIBUTION_REINVESTMENT', " +
                        "'Reinvest an observed cash distribution at a future open', ?, 'CLOSE')",
                "item-" + UUID.randomUUID(), proposalId, listingId, estimatedUnits.toPlainString(), close);
        jdbcTemplate.update(
                "INSERT INTO paper_proposal_observations (id, proposal_id, listing_id, observation_session_date, " +
                        "observation_type, observation_value, observed_at) VALUES (?, ?, ?, ?, 'DISTRIBUTION', ?, ?)",
                "obs-" + UUID.randomUUID(), proposalId, listingId, currentSessionDate(now), paidAmount.toPlainString(), now);
        if ("AUTO_PAPER".equals(segment.get("approval_mode"))) {
            acceptProposal(portfolioId, proposalId, ownerId, "auto-reinvest-" + receivableId,
                    new ResearchDtos.AcceptProposalRequest("Automatic distribution reinvestment"),
                    "AUTO_PAPER", "AUTO_REINVESTMENT");
        }
    }

    private String currentSessionDate(String instant) {
        return LocalDate.ofInstant(Instant.parse(instant), PaperDataReadinessService.EXCHANGE_ZONE).toString();
    }

    private BigDecimal quantityAtEvent(String portfolioId, String listingId, String effectiveDate) {
        BigDecimal quantity = BigDecimal.ZERO;
        // Entitlement is established before this session's opening trades.
        // A split booked at midnight remains visible to a same-day distribution.
        String boundary = effectiveDate + "T00:00:00Z";
        for (String delta : jdbcTemplate.queryForList(
                "SELECT signed_quantity_delta FROM ledger_entries WHERE portfolio_id = ? AND listing_id = ? AND business_at <= ? ORDER BY business_at, sequence",
                String.class, portfolioId, listingId, boundary)) {
            quantity = quantity.add(new BigDecimal(delta));
        }
        return quantity;
    }

    private boolean executePendingIntents(String portfolioId, Map<String, Object> seg, String datasetId, String dueThroughInstant, String now) {
        List<Map<String, Object>> intents = jdbcTemplate.queryForList(
                "SELECT id, proposal_id, reinvestment_receivable_id, scheduled_session_date, scheduled_open_instant, status FROM paper_execution_intents " +
                        "WHERE portfolio_id = ? AND status IN ('PENDING', 'WAITING_FOR_OBSERVATION') AND scheduled_open_instant <= ? " +
                        "ORDER BY scheduled_open_instant, id",
                portfolioId, dueThroughInstant
        );

        ResearchDtos.CostPolicyDto costPolicy = parseCostPolicy((String) seg.get("cost_policy_json"));
        BigDecimal commission = new BigDecimal(costPolicy.commissionPerFill());
        BigDecimal halfSpread = new BigDecimal(costPolicy.bidAskSpreadBps())
                .divide(new BigDecimal("20000"), 12, RoundingMode.HALF_EVEN);
        BigDecimal slippage = new BigDecimal(costPolicy.slippageBps())
                .divide(new BigDecimal("10000"), 12, RoundingMode.HALF_EVEN);
        BigDecimal costFraction = halfSpread.add(slippage);
        String datasetChecksum = jdbcTemplate.queryForObject(
                "SELECT content_checksum FROM datasets WHERE id = ?", String.class, datasetId);

        for (Map<String, Object> intent : intents) {
            String intentId = (String) intent.get("id");
            String proposalId = (String) intent.get("proposal_id");
            String reinvestmentReceivableId = (String) intent.get("reinvestment_receivable_id");
            String schedDate = (String) intent.get("scheduled_session_date");
            String currentStatus = (String) intent.get("status");
            String marketEffectiveInstant = (String) intent.get("scheduled_open_instant");

            // Check if open bars exist on scheduled date
            List<Map<String, Object>> propItems = jdbcTemplate.queryForList(
                    "SELECT listing_id, target_weight FROM paper_proposal_items WHERE proposal_id = ? ORDER BY rank ASC",
                    proposalId
            );

            Map<String, BigDecimal> openPrices = new HashMap<>();
            Set<String> requiredListings = new LinkedHashSet<>();
            for (Map<String, Object> item : propItems) {
                requiredListings.add((String) item.get("listing_id"));
            }
            requiredListings.addAll(jdbcTemplate.queryForList(
                    "SELECT listing_id FROM positions WHERE portfolio_id = ? AND quantity != '0'", String.class, portfolioId));
            for (String lid : requiredListings) {
                List<Map<String, Object>> barList = jdbcTemplate.queryForList(
                        "SELECT open FROM historical_bars WHERE dataset_id = ? AND listing_id = ? AND session_date = ? AND available_at <= ?",
                        datasetId, lid, schedDate, now
                );
                if (!barList.isEmpty()) {
                    openPrices.put(lid, new BigDecimal(String.valueOf(barList.get(0).get("open"))));
                }
            }

            if (openPrices.size() < requiredListings.size()) {
                if ("PENDING".equals(currentStatus)) {
                    int changed = jdbcTemplate.update(
                            "UPDATE paper_execution_intents SET status = 'WAITING_FOR_OBSERVATION' WHERE id = ? AND status = 'PENDING'",
                            intentId
                    );
                    if (changed == 1) {
                        jdbcTemplate.update(
                                "INSERT INTO paper_intent_transitions (id, intent_id, from_status, to_status, trigger_type, transition_instant, notes) " +
                                        "VALUES (?, ?, 'PENDING', 'WAITING_FOR_OBSERVATION', 'OBSERVATION_CHECK', ?, 'Market open bars unavailable')",
                                "trans-" + UUID.randomUUID(), intentId, now
                        );
                    }
                }
                // Later batches must not size against holdings that an earlier intent has not booked.
                return false;
            }

            // Sizing against current holdings: Sells first, then Buys
            String rawCash = jdbcTemplate.queryForObject("SELECT cash_amount FROM portfolio_state WHERE portfolio_id = ?", String.class, portfolioId);
            BigDecimal currentCash = new BigDecimal(rawCash != null ? rawCash : "0.00");
            BigDecimal totalEquity = currentCash;

            Map<String, BigDecimal> currentHoldings = new HashMap<>();
            List<Map<String, Object>> holdingsRows = jdbcTemplate.queryForList(
                    "SELECT listing_id, quantity FROM positions WHERE portfolio_id = ? AND quantity != '0'", portfolioId
            );
            for (Map<String, Object> h : holdingsRows) {
                String lid = (String) h.get("listing_id");
                BigDecimal qty = new BigDecimal((String) h.get("quantity"));
                currentHoldings.put(lid, qty);
                BigDecimal openPrice = openPrices.getOrDefault(lid, BigDecimal.ZERO);
                totalEquity = totalEquity.add(qty.multiply(openPrice));
            }

            // Determine target units per listing
            Map<String, BigDecimal> targetUnits = new HashMap<>();
            BigDecimal reinvestmentAmount = null;
            if (reinvestmentReceivableId != null) {
                String amount = jdbcTemplate.queryForObject(
                        "SELECT net_amount FROM paper_receivables WHERE id = ? AND portfolio_id = ? AND status = 'PAID'",
                        String.class, reinvestmentReceivableId, portfolioId);
                reinvestmentAmount = new BigDecimal(amount);
            }
            for (Map<String, Object> item : propItems) {
                String lid = (String) item.get("listing_id");
                BigDecimal weight = new BigDecimal((String) item.get("target_weight"));
                BigDecimal targetCash = (reinvestmentAmount == null ? totalEquity : reinvestmentAmount).multiply(weight);
                BigDecimal price = openPrices.get(lid);
                BigDecimal units = BigDecimal.ZERO;
                if (price != null && price.compareTo(BigDecimal.ZERO) > 0) {
                    units = targetCash.divide(price, 0, RoundingMode.FLOOR);
                }
                targetUnits.put(lid, reinvestmentAmount == null
                        ? units : currentHoldings.getOrDefault(lid, BigDecimal.ZERO).add(units));
            }
            if (reinvestmentAmount != null) {
                for (Map.Entry<String, BigDecimal> holding : currentHoldings.entrySet()) {
                    targetUnits.putIfAbsent(holding.getKey(), holding.getValue());
                }
            }

            // Execute Sells first
            for (Map.Entry<String, BigDecimal> entry : currentHoldings.entrySet()) {
                String lid = entry.getKey();
                BigDecimal currentQty = entry.getValue();
                BigDecimal targetQty = targetUnits.getOrDefault(lid, BigDecimal.ZERO);
                if (currentQty.compareTo(targetQty) > 0) {
                    BigDecimal sellQty = currentQty.subtract(targetQty);
                    BigDecimal rawOpen = openPrices.get(lid);
                    if (rawOpen != null && rawOpen.compareTo(BigDecimal.ZERO) > 0 && sellQty.compareTo(BigDecimal.ZERO) > 0) {
                        BigDecimal fillPrice = AccountingCore.normalizePrice(
                                rawOpen.multiply(BigDecimal.ONE.subtract(costFraction), AccountingCore.MATH_CONTEXT)
                                        .stripTrailingZeros());
                        BigDecimal modeledCost = AccountingCore.roundCash(rawOpen.subtract(fillPrice).multiply(sellQty));
                        OperationService.TradeExecutionResult res = operationService.executePaperTrade(
                                portfolioId, lid, "SELL", sellQty, rawOpen, fillPrice, commission, modeledCost,
                                "idemp-trade-" + intentId + "-sell-" + lid, "PAPER_RAW_OPEN_WITH_COSTS",
                                marketEffectiveInstant
                        );
                        jdbcTemplate.update(
                                "INSERT INTO paper_execution_results (id, intent_id, proposal_id, reinvestment_receivable_id, operation_id, execution_id, listing_id, " +
                                        "side, requested_quantity, executed_quantity, shortfall_reason, raw_open_price, fill_price, commission, " +
                                        "spread_slippage_cost, cost_basis, realized_gain, dataset_id, dataset_checksum, market_effective_instant, " +
                                        "observed_instant, booked_instant) " +
                                        "VALUES (?, ?, ?, ?, ?, ?, ?, 'SELL', ?, ?, NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                                "res-" + UUID.randomUUID(), intentId, proposalId, reinvestmentReceivableId,
                                res.operationId(), res.executionId(), lid,
                                sellQty.toPlainString(), res.units(), rawOpen.toPlainString(), res.fillPrice(), res.commission(),
                                modeledCost.toPlainString(), res.basisDelta(), res.realizedGain(), datasetId, datasetChecksum,
                                marketEffectiveInstant, now, now
                        );
                    }
                }
            }

            // Execute Buys second with affordability checks
            rawCash = jdbcTemplate.queryForObject("SELECT cash_amount FROM portfolio_state WHERE portfolio_id = ?", String.class, portfolioId);
            BigDecimal availableCash = new BigDecimal(rawCash != null ? rawCash : "0.00");
            BigDecimal reinvestmentBudget = reinvestmentAmount;

            for (Map.Entry<String, BigDecimal> entry : targetUnits.entrySet()) {
                String lid = entry.getKey();
                BigDecimal targetQty = entry.getValue();
                BigDecimal currentQty = currentHoldings.getOrDefault(lid, BigDecimal.ZERO);
                if (targetQty.compareTo(currentQty) > 0) {
                    BigDecimal buyQty = targetQty.subtract(currentQty);
                    BigDecimal rawOpen = openPrices.get(lid);
                    if (rawOpen != null && rawOpen.compareTo(BigDecimal.ZERO) > 0 && buyQty.compareTo(BigDecimal.ZERO) > 0) {
                        BigDecimal requestedQty = buyQty;
                        BigDecimal fillPrice = AccountingCore.normalizePrice(
                                rawOpen.multiply(BigDecimal.ONE.add(costFraction), AccountingCore.MATH_CONTEXT)
                                        .stripTrailingZeros());
                        // Squeeze units for affordability
                        BigDecimal required = buyQty.multiply(fillPrice).add(commission);
                        BigDecimal spendableCash = reinvestmentBudget == null
                                ? availableCash : availableCash.min(reinvestmentBudget);
                        while (required.compareTo(spendableCash) > 0 && buyQty.compareTo(BigDecimal.ZERO) > 0) {
                            buyQty = buyQty.subtract(BigDecimal.ONE);
                            required = buyQty.multiply(fillPrice).add(commission);
                        }

                        if (buyQty.compareTo(BigDecimal.ZERO) > 0) {
                            BigDecimal modeledCost = AccountingCore.roundCash(fillPrice.subtract(rawOpen).multiply(buyQty));
                            OperationService.TradeExecutionResult res = operationService.executePaperTrade(
                                    portfolioId, lid, "BUY", buyQty, rawOpen, fillPrice, commission, modeledCost,
                                    "idemp-trade-" + intentId + "-buy-" + lid, "PAPER_RAW_OPEN_WITH_COSTS",
                                    marketEffectiveInstant
                            );
                            jdbcTemplate.update(
                                    "INSERT INTO paper_execution_results (id, intent_id, proposal_id, reinvestment_receivable_id, operation_id, execution_id, listing_id, " +
                                            "side, requested_quantity, executed_quantity, shortfall_reason, raw_open_price, fill_price, commission, " +
                                            "spread_slippage_cost, cost_basis, realized_gain, dataset_id, dataset_checksum, market_effective_instant, " +
                                            "observed_instant, booked_instant) " +
                                            "VALUES (?, ?, ?, ?, ?, ?, ?, 'BUY', ?, ?, ?, ?, ?, ?, ?, ?, '0.00', ?, ?, ?, ?, ?)",
                                    "res-" + UUID.randomUUID(), intentId, proposalId, reinvestmentReceivableId,
                                    res.operationId(), res.executionId(), lid,
                                    requestedQty.toPlainString(), res.units(),
                                    buyQty.compareTo(requestedQty) < 0 ? "INSUFFICIENT_CASH" : null,
                                    rawOpen.toPlainString(), res.fillPrice(), res.commission(), modeledCost.toPlainString(),
                                    res.basisDelta(), datasetId, datasetChecksum, marketEffectiveInstant, now, now
                            );
                            availableCash = new BigDecimal(res.remainingCash());
                            if (reinvestmentBudget != null) {
                                reinvestmentBudget = reinvestmentBudget.subtract(required);
                            }
                        }
                    }
                }
            }

            // Mark Intent Executed
            int executed = jdbcTemplate.update(
                    "UPDATE paper_execution_intents SET status = 'EXECUTED' WHERE id = ? AND status IN ('PENDING', 'WAITING_FOR_OBSERVATION')",
                    intentId);
            if (executed != 1) {
                throw new IllegalStateException("Intent state changed concurrently: " + intentId);
            }
            jdbcTemplate.update(
                    "INSERT INTO paper_intent_transitions (id, intent_id, from_status, to_status, trigger_type, transition_instant, notes) " +
                            "VALUES (?, ?, ?, 'EXECUTED', 'MARKET_OPEN_FILL', ?, 'Fills completed successfully')",
                    "trans-" + UUID.randomUUID(), intentId, currentStatus, now
            );
        }
        return true;
    }

    private void calculateAndRecordValuation(String portfolioId, String datasetId, String currentSessionDate, String now) {
        String rawCash = jdbcTemplate.queryForObject("SELECT cash_amount FROM portfolio_state WHERE portfolio_id = ?", String.class, portfolioId);
        BigDecimal cash = new BigDecimal(rawCash != null ? rawCash : "0.00");

        BigDecimal positionsMarketVal = BigDecimal.ZERO;
        List<String> missingPrices = new ArrayList<>();
        String lastSupportedObservation = null;
        List<Map<String, Object>> posRows = jdbcTemplate.queryForList(
                "SELECT listing_id, quantity FROM positions WHERE portfolio_id = ? AND quantity != '0'", portfolioId
        );
        for (Map<String, Object> pos : posRows) {
            String lid = (String) pos.get("listing_id");
            BigDecimal qty = new BigDecimal((String) pos.get("quantity"));
            List<Map<String, Object>> barList = jdbcTemplate.queryForList(
                    "SELECT close, available_at FROM historical_bars WHERE dataset_id = ? AND listing_id = ? AND session_date <= ? AND available_at <= ? ORDER BY session_date DESC LIMIT 1",
                    datasetId, lid, currentSessionDate, now
            );
            if (barList.isEmpty()) {
                missingPrices.add(lid);
                continue;
            }
            String closeStr = (String) barList.get(0).get("close");
            BigDecimal price = new BigDecimal(closeStr != null ? closeStr : "0.00");
            String availableAt = (String) barList.get(0).get("available_at");
            if (availableAt != null && (lastSupportedObservation == null || availableAt.compareTo(lastSupportedObservation) > 0)) {
                lastSupportedObservation = availableAt;
            }
            positionsMarketVal = positionsMarketVal.add(qty.multiply(price));
        }

        BigDecimal receivablesVal = BigDecimal.ZERO;
        for (String amount : jdbcTemplate.queryForList(
                "SELECT net_amount FROM paper_receivables WHERE portfolio_id = ? AND status = 'PENDING'",
                String.class, portfolioId)) {
            receivablesVal = receivablesVal.add(new BigDecimal(amount));
        }
        receivablesVal = AccountingCore.roundCash(receivablesVal);

        BigDecimal totalEquity = cash.add(positionsMarketVal).add(receivablesVal).setScale(2, RoundingMode.HALF_EVEN);

        String rawInitial = jdbcTemplate.queryForObject(
                "SELECT initial_equity FROM paper_portfolio_segments WHERE portfolio_id = ? AND status = 'ACTIVE'",
                String.class, portfolioId
        );
        BigDecimal initialEquity = new BigDecimal(rawInitial != null ? rawInitial : "10000.00");

        BigDecimal cumulativeReturn = BigDecimal.ZERO;
        if (initialEquity.compareTo(BigDecimal.ZERO) > 0) {
            cumulativeReturn = totalEquity.subtract(initialEquity).divide(initialEquity, 6, RoundingMode.HALF_EVEN);
        }

        BigDecimal hwm = totalEquity;
        for (String priorEquity : jdbcTemplate.queryForList(
                "SELECT total_equity FROM paper_valuations WHERE portfolio_id = ? AND total_equity IS NOT NULL",
                String.class, portfolioId)) {
            hwm = hwm.max(new BigDecimal(priorEquity));
        }
        if (totalEquity.compareTo(hwm) > 0) {
            hwm = totalEquity;
        }

        BigDecimal drawdown = BigDecimal.ZERO;
        if (hwm.compareTo(BigDecimal.ZERO) > 0 && totalEquity.compareTo(hwm) < 0) {
            drawdown = totalEquity.subtract(hwm).divide(hwm, 6, RoundingMode.HALF_EVEN);
        }

        String readinessStatus = missingPrices.isEmpty() ? "COMPLETE" : "PARTIAL_STALE";
        int complete = missingPrices.isEmpty() ? 1 : 0;
        String missingDetail = missingPrices.isEmpty() ? null : "Missing eligible prices for " + missingPrices;
        String datasetChecksum = jdbcTemplate.queryForObject(
                "SELECT content_checksum FROM datasets WHERE id = ?", String.class, datasetId);
        int portfolioRevision = jdbcTemplate.queryForObject(
                "SELECT revision FROM portfolio_state WHERE portfolio_id = ?", Integer.class, portfolioId);

        List<Map<String, Object>> previousMarks = jdbcTemplate.queryForList(
                "SELECT cash_balance, positions_market_value, receivables_value, total_equity, cumulative_return, " +
                        "high_water_mark, drawdown, data_readiness_status, is_complete, last_supported_observation_instant, " +
                        "missing_requirements_detail, adopted_dataset_checksum FROM paper_valuations " +
                        "WHERE portfolio_id = ? AND session_date = ? AND observation_kind = 'SESSION_CLOSE' " +
                        "AND portfolio_state_revision = ? AND adopted_dataset_id = ? " +
                        "ORDER BY observation_instant DESC LIMIT 1",
                portfolioId, currentSessionDate, portfolioRevision, datasetId);
        if (!previousMarks.isEmpty()) {
            Map<String, Object> prior = previousMarks.get(0);
            if (Objects.equals(prior.get("cash_balance"), cash.toPlainString())
                    && Objects.equals(prior.get("positions_market_value"), positionsMarketVal.toPlainString())
                    && Objects.equals(prior.get("receivables_value"), receivablesVal.toPlainString())
                    && Objects.equals(prior.get("total_equity"), totalEquity.toPlainString())
                    && Objects.equals(prior.get("cumulative_return"), cumulativeReturn.toPlainString())
                    && Objects.equals(prior.get("high_water_mark"), hwm.toPlainString())
                    && Objects.equals(prior.get("drawdown"), drawdown.toPlainString())
                    && Objects.equals(prior.get("data_readiness_status"), readinessStatus)
                    && ((Number) prior.get("is_complete")).intValue() == complete
                    && Objects.equals(prior.get("last_supported_observation_instant"), lastSupportedObservation)
                    && Objects.equals(prior.get("missing_requirements_detail"), missingDetail)
                    && Objects.equals(prior.get("adopted_dataset_checksum"), datasetChecksum)) {
                return;
            }
        }

        jdbcTemplate.update(
                "INSERT OR IGNORE INTO paper_valuations (id, portfolio_id, session_date, observation_kind, observation_instant, " +
                        "portfolio_state_revision, cash_balance, positions_market_value, receivables_value, total_equity, cumulative_return, " +
                        "high_water_mark, drawdown, data_readiness_status, is_complete, last_supported_observation_instant, " +
                        "missing_requirements_detail, adopted_dataset_id, adopted_dataset_checksum) " +
                        "VALUES (?, ?, ?, 'SESSION_CLOSE', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                "val-" + UUID.randomUUID(), portfolioId, currentSessionDate, now, portfolioRevision,
                cash.toPlainString(), positionsMarketVal.toPlainString(), receivablesVal.toPlainString(),
                totalEquity.toPlainString(), cumulativeReturn.toPlainString(), hwm.toPlainString(), drawdown.toPlainString(),
                readinessStatus, complete, lastSupportedObservation, missingDetail, datasetId, datasetChecksum
        );
    }

    // -------------------------------------------------------------
    // 6. Queries & Mappings
    // -------------------------------------------------------------

    public ResearchDtos.PaperSegmentDto getActiveSegment(String portfolioId, String ownerId) {
        String uid = normalizeUser(ownerId);
        validatePortfolioOwnership(portfolioId, uid);

        Map<String, Object> seg = getActiveSegmentRow(portfolioId);
        return mapSegment(seg);
    }

    public ResearchDtos.PaperProposalDto getProposal(String portfolioId, String proposalId, String ownerId) {
        String uid = normalizeUser(ownerId);
        validatePortfolioOwnership(portfolioId, uid);

        Map<String, Object> p = getProposalRow(portfolioId, proposalId);
        List<Map<String, Object>> itemRows = jdbcTemplate.queryForList(
                "SELECT id, proposal_id, listing_id, rank, target_weight, cutoff_estimated_units, score, reason_code, reason_description, raw_price_reference " +
                        "FROM paper_proposal_items WHERE proposal_id = ? ORDER BY rank ASC",
                proposalId
        );
        List<ResearchDtos.PaperProposalItemDto> items = new ArrayList<>();
        for (Map<String, Object> r : itemRows) {
            items.add(new ResearchDtos.PaperProposalItemDto(
                    (String) r.get("id"), (String) r.get("proposal_id"), (String) r.get("listing_id"),
                    ((Number) r.get("rank")).intValue(), (String) r.get("target_weight"), (String) r.get("cutoff_estimated_units"),
                    (String) r.get("score"), (String) r.get("reason_code"), (String) r.get("reason_description"), (String) r.get("raw_price_reference")
            ));
        }

        List<Map<String, Object>> obsRows = jdbcTemplate.queryForList(
                "SELECT id, proposal_id, listing_id, observation_session_date, observation_type, observation_value, observed_at " +
                        "FROM paper_proposal_observations WHERE proposal_id = ? ORDER BY observation_session_date ASC",
                proposalId
        );
        List<ResearchDtos.PaperProposalObservationDto> observations = new ArrayList<>();
        for (Map<String, Object> r : obsRows) {
            observations.add(new ResearchDtos.PaperProposalObservationDto(
                    (String) r.get("id"), (String) r.get("proposal_id"), (String) r.get("listing_id"),
                    (String) r.get("observation_session_date"), (String) r.get("observation_type"),
                    (String) r.get("observation_value"), (String) r.get("observed_at")
            ));
        }

        return new ResearchDtos.PaperProposalDto(
                (String) p.get("id"), (String) p.get("portfolio_id"), (String) p.get("cycle_id"),
                (String) p.get("strategy_id"), (String) p.get("strategy_version"), (String) p.get("dataset_id"),
                (String) p.get("dataset_checksum"), (String) p.get("calendar_id"), (String) p.get("calendar_version"),
                (String) p.get("evaluation_session_date"), (String) p.get("input_cutoff_instant"), (String) p.get("evaluation_instant"),
                (String) p.get("scheduled_open_session_date"), (String) p.get("scheduled_open_instant"),
                (String) p.get("reason_code"), ((Number) p.get("portfolio_state_version")).intValue(),
                (String) p.get("status"), (String) p.get("accepted_at"), (String) p.get("rejected_at"),
                (String) p.get("rejection_reason"), (String) p.get("superseding_proposal_id"),
                (String) p.get("reinvestment_receivable_id"), (String) p.get("created_at"),
                items, observations
        );
    }

    public List<ResearchDtos.PaperProposalDto> listProposals(String portfolioId, String ownerId) {
        String uid = normalizeUser(ownerId);
        validatePortfolioOwnership(portfolioId, uid);

        List<String> propIds = jdbcTemplate.queryForList(
                "SELECT id FROM paper_proposals WHERE portfolio_id = ? ORDER BY created_at DESC",
                String.class, portfolioId
        );
        List<ResearchDtos.PaperProposalDto> proposals = new ArrayList<>();
        for (String id : propIds) {
            proposals.add(getProposal(portfolioId, id, uid));
        }
        return proposals;
    }

    public ResearchDtos.PagedResponse<ResearchDtos.PaperProposalDto> listProposals(
            String portfolioId, String ownerId, int limit, int offset
    ) {
        String uid = normalizeUser(ownerId);
        validatePortfolioOwnership(portfolioId, uid);

        Integer total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM paper_proposals WHERE portfolio_id = ?",
                Integer.class,
                portfolioId
        );
        int totalCount = total != null ? total : 0;

        List<String> propIds = jdbcTemplate.queryForList(
                "SELECT id FROM paper_proposals WHERE portfolio_id = ? ORDER BY created_at DESC LIMIT ? OFFSET ?",
                String.class, portfolioId, limit, offset
        );
        List<ResearchDtos.PaperProposalDto> proposals = new ArrayList<>();
        for (String id : propIds) {
            proposals.add(getProposal(portfolioId, id, uid));
        }
        return new ResearchDtos.PagedResponse<>(proposals, totalCount, limit, offset, (offset + proposals.size()) >= totalCount);
    }

    public List<ResearchDtos.PaperModeHistoryDto> getModeHistory(String portfolioId, String ownerId) {
        String uid = normalizeUser(ownerId);
        validatePortfolioOwnership(portfolioId, uid);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, portfolio_id, from_mode, to_mode, transition_instant, trigger_type, notes " +
                        "FROM paper_mode_history WHERE portfolio_id = ? ORDER BY transition_instant DESC",
                portfolioId
        );
        List<ResearchDtos.PaperModeHistoryDto> result = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            result.add(new ResearchDtos.PaperModeHistoryDto(
                    (String) r.get("id"),
                    (String) r.get("portfolio_id"),
                    (String) r.get("from_mode"),
                    (String) r.get("to_mode"),
                    (String) r.get("transition_instant"),
                    (String) r.get("trigger_type"),
                    (String) r.get("notes")
            ));
        }
        return result;
    }

    public ResearchDtos.PagedResponse<ResearchDtos.PaperValuationDto> listValuations(
            String portfolioId, String ownerId, int limit, int offset
    ) {
        String uid = normalizeUser(ownerId);
        validatePortfolioOwnership(portfolioId, uid);

        Integer total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM paper_valuations WHERE portfolio_id = ?",
                Integer.class,
                portfolioId
        );
        int totalCount = total != null ? total : 0;

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, portfolio_id, session_date, observation_kind, observation_instant, cash_balance, " +
                        "positions_market_value, receivables_value, total_equity, cumulative_return, high_water_mark, " +
                        "drawdown, data_readiness_status, is_complete, last_supported_observation_instant, " +
                        "missing_requirements_detail, adopted_dataset_id, adopted_dataset_checksum " +
                        "FROM paper_valuations WHERE portfolio_id = ? " +
                        "ORDER BY observation_instant DESC, portfolio_state_revision DESC, id DESC LIMIT ? OFFSET ?",
                portfolioId, limit, offset
        );

        List<ResearchDtos.PaperValuationDto> dtos = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            dtos.add(new ResearchDtos.PaperValuationDto(
                    (String) r.get("id"),
                    (String) r.get("portfolio_id"),
                    (String) r.get("session_date"),
                    (String) r.get("observation_kind"),
                    (String) r.get("observation_instant"),
                    (String) r.get("cash_balance"),
                    (String) r.get("positions_market_value"),
                    (String) r.get("receivables_value"),
                    (String) r.get("total_equity"),
                    (String) r.get("cumulative_return"),
                    (String) r.get("high_water_mark"),
                    (String) r.get("drawdown"),
                    (String) r.get("data_readiness_status"),
                    ((Number) r.get("is_complete")).intValue() == 1,
                    (String) r.get("last_supported_observation_instant"),
                    (String) r.get("missing_requirements_detail"),
                    (String) r.get("adopted_dataset_id"),
                    (String) r.get("adopted_dataset_checksum")
            ));
        }

        return new ResearchDtos.PagedResponse<>(dtos, totalCount, limit, offset, (offset + dtos.size()) >= totalCount);
    }

    public ResearchDtos.PagedResponse<ResearchDtos.PaperExecutionIntentDto> listIntents(
            String portfolioId, String ownerId, int requestedLimit, int offset
    ) {
        validatePortfolioOwnership(portfolioId, normalizeUser(ownerId));
        int limit = Math.max(1, Math.min(requestedLimit, 200));
        int safeOffset = Math.max(0, offset);
        int total = Optional.ofNullable(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM paper_execution_intents WHERE portfolio_id = ?", Integer.class, portfolioId)).orElse(0);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, portfolio_id, proposal_id, reinvestment_receivable_id, order_type, scheduled_session_date, " +
                        "scheduled_open_instant, approval_mode, status, created_at FROM paper_execution_intents " +
                        "WHERE portfolio_id = ? ORDER BY created_at DESC LIMIT ? OFFSET ?",
                portfolioId, limit, safeOffset);
        List<ResearchDtos.PaperExecutionIntentDto> items = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String intentId = (String) row.get("id");
            List<ResearchDtos.PaperIntentTransitionDto> transitions = jdbcTemplate.query(
                    "SELECT id, intent_id, from_status, to_status, trigger_type, transition_instant, notes " +
                            "FROM paper_intent_transitions WHERE intent_id = ? ORDER BY transition_instant, id",
                    (rs, index) -> new ResearchDtos.PaperIntentTransitionDto(
                            rs.getString("id"), rs.getString("intent_id"), rs.getString("from_status"),
                            rs.getString("to_status"), rs.getString("trigger_type"),
                            rs.getString("transition_instant"), rs.getString("notes")),
                    intentId);
            List<ResearchDtos.PaperExecutionResultDto> results = jdbcTemplate.query(
                    "SELECT id, intent_id, proposal_id, reinvestment_receivable_id, operation_id, execution_id, listing_id, side, " +
                            "requested_quantity, executed_quantity, shortfall_reason, raw_open_price, fill_price, commission, " +
                            "spread_slippage_cost, cost_basis, realized_gain, dataset_id, dataset_checksum, market_effective_instant, " +
                            "observed_instant, booked_instant FROM paper_execution_results WHERE intent_id = ? ORDER BY side DESC, listing_id",
                    (rs, index) -> new ResearchDtos.PaperExecutionResultDto(
                            rs.getString("id"), rs.getString("intent_id"), rs.getString("proposal_id"),
                            rs.getString("reinvestment_receivable_id"), rs.getString("operation_id"), rs.getString("execution_id"),
                            rs.getString("listing_id"), rs.getString("side"), rs.getString("requested_quantity"),
                            rs.getString("executed_quantity"), rs.getString("shortfall_reason"), rs.getString("raw_open_price"),
                            rs.getString("fill_price"), rs.getString("commission"), rs.getString("spread_slippage_cost"),
                            rs.getString("cost_basis"), rs.getString("realized_gain"), rs.getString("dataset_id"),
                            rs.getString("dataset_checksum"), rs.getString("market_effective_instant"),
                            rs.getString("observed_instant"), rs.getString("booked_instant")),
                    intentId);
            items.add(new ResearchDtos.PaperExecutionIntentDto(
                    intentId, (String) row.get("portfolio_id"), (String) row.get("proposal_id"),
                    (String) row.get("reinvestment_receivable_id"), (String) row.get("order_type"),
                    (String) row.get("scheduled_session_date"), (String) row.get("scheduled_open_instant"),
                    (String) row.get("approval_mode"), (String) row.get("status"), (String) row.get("created_at"),
                    transitions, results));
        }
        return new ResearchDtos.PagedResponse<>(items, total, limit, safeOffset, safeOffset + items.size() >= total);
    }

    public ResearchDtos.PagedResponse<ResearchDtos.PaperReceivableDto> listReceivables(
            String portfolioId, String ownerId, int requestedLimit, int offset
    ) {
        validatePortfolioOwnership(portfolioId, normalizeUser(ownerId));
        int limit = Math.max(1, Math.min(requestedLimit, 200));
        int safeOffset = Math.max(0, offset);
        int total = Optional.ofNullable(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM paper_receivables WHERE portfolio_id = ?", Integer.class, portfolioId)).orElse(0);
        List<ResearchDtos.PaperReceivableDto> items = jdbcTemplate.query(
                "SELECT id, portfolio_id, listing_id, source_namespace, action_id, action_type, record_instant, ex_date, " +
                        "payment_date, payment_instant, availability_instant, gross_amount, withholding_tax, net_amount, status, " +
                        "paid_operation_id, paid_at, created_at, dataset_id, dataset_checksum, terms_hash FROM paper_receivables " +
                        "WHERE portfolio_id = ? ORDER BY created_at DESC LIMIT ? OFFSET ?",
                (rs, index) -> new ResearchDtos.PaperReceivableDto(
                        rs.getString("id"), rs.getString("portfolio_id"), rs.getString("listing_id"),
                        rs.getString("source_namespace"), rs.getString("action_id"), rs.getString("action_type"),
                        rs.getString("record_instant"), rs.getString("ex_date"), rs.getString("payment_date"),
                        rs.getString("payment_instant"), rs.getString("availability_instant"), rs.getString("gross_amount"),
                        rs.getString("withholding_tax"), rs.getString("net_amount"), rs.getString("status"),
                        rs.getString("paid_operation_id"), rs.getString("paid_at"), rs.getString("created_at"),
                        rs.getString("dataset_id"), rs.getString("dataset_checksum"), rs.getString("terms_hash")),
                portfolioId, limit, safeOffset);
        return new ResearchDtos.PagedResponse<>(items, total, limit, safeOffset, safeOffset + items.size() >= total);
    }

    public ResearchDtos.PagedResponse<ResearchDtos.PaperProcessedActionDto> listProcessedActions(
            String portfolioId, String ownerId, int requestedLimit, int offset
    ) {
        validatePortfolioOwnership(portfolioId, normalizeUser(ownerId));
        int limit = Math.max(1, Math.min(requestedLimit, 200));
        int safeOffset = Math.max(0, offset);
        int total = Optional.ofNullable(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM paper_processed_corporate_actions WHERE portfolio_id = ?", Integer.class, portfolioId)).orElse(0);
        List<ResearchDtos.PaperProcessedActionDto> items = jdbcTemplate.query(
                "SELECT id, portfolio_id, source_namespace, listing_id, action_id, action_type, terms_hash, effective_date, " +
                        "availability_instant, processing_instant, dataset_id, dataset_checksum, status, linked_operation_id, linked_receivable_id " +
                        "FROM paper_processed_corporate_actions WHERE portfolio_id = ? ORDER BY processing_instant DESC LIMIT ? OFFSET ?",
                (rs, index) -> new ResearchDtos.PaperProcessedActionDto(
                        rs.getString("id"), rs.getString("portfolio_id"), rs.getString("source_namespace"),
                        rs.getString("listing_id"), rs.getString("action_id"), rs.getString("action_type"),
                        rs.getString("terms_hash"), rs.getString("effective_date"), rs.getString("availability_instant"),
                        rs.getString("processing_instant"), rs.getString("dataset_id"), rs.getString("dataset_checksum"),
                        rs.getString("status"), rs.getString("linked_operation_id"), rs.getString("linked_receivable_id")),
                portfolioId, limit, safeOffset);
        return new ResearchDtos.PagedResponse<>(items, total, limit, safeOffset, safeOffset + items.size() >= total);
    }

    public ResearchDtos.PagedResponse<ResearchDtos.PaperDatasetAdoptionDto> listAdoptions(
            String portfolioId, String ownerId, int requestedLimit, int offset
    ) {
        validatePortfolioOwnership(portfolioId, normalizeUser(ownerId));
        int limit = Math.max(1, Math.min(requestedLimit, 200));
        int safeOffset = Math.max(0, offset);
        int total = Optional.ofNullable(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM paper_dataset_adoptions WHERE portfolio_id = ?", Integer.class, portfolioId)).orElse(0);
        List<ResearchDtos.PaperDatasetAdoptionDto> items = jdbcTemplate.query(
                "SELECT id, portfolio_id, dataset_id, dataset_checksum, coverage_start_session, coverage_end_session, " +
                        "validation_status, rejection_reason, adopted_at FROM paper_dataset_adoptions " +
                        "WHERE portfolio_id = ? ORDER BY adopted_at DESC LIMIT ? OFFSET ?",
                (rs, index) -> new ResearchDtos.PaperDatasetAdoptionDto(
                        rs.getString("id"), rs.getString("portfolio_id"), rs.getString("dataset_id"),
                        rs.getString("dataset_checksum"), rs.getString("coverage_start_session"),
                        rs.getString("coverage_end_session"), rs.getString("validation_status"),
                        rs.getString("rejection_reason"), rs.getString("adopted_at")),
                portfolioId, limit, safeOffset);
        return new ResearchDtos.PagedResponse<>(items, total, limit, safeOffset, safeOffset + items.size() >= total);
    }

    private void validatePortfolioOwnership(String portfolioId, String ownerId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id FROM portfolios WHERE id = ? AND owner_id = ?",
                portfolioId, ownerId
        );
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Portfolio not found: " + portfolioId);
        }
    }

    private Map<String, Object> getActiveSegmentRow(String portfolioId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, portfolio_id, strategy_id, strategy_version, universe_id, benchmark_listing_id, " +
                        "cost_policy_json, approval_mode, status, initial_equity, opening_observation_instant, " +
                        "adopted_dataset_id, adopted_at, created_at FROM paper_portfolio_segments " +
                        "WHERE portfolio_id = ? AND status = 'ACTIVE'",
                portfolioId
        );
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Active tracking segment not found for portfolio: " + portfolioId);
        }
        return rows.get(0);
    }

    private Map<String, Object> getProposalRow(String portfolioId, String proposalId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, portfolio_id, cycle_id, strategy_id, strategy_version, dataset_id, dataset_checksum, " +
                        "calendar_id, calendar_version, evaluation_session_date, input_cutoff_instant, evaluation_instant, " +
                        "scheduled_open_session_date, scheduled_open_instant, reason_code, portfolio_state_version, " +
                        "status, accepted_at, rejected_at, rejection_reason, superseding_proposal_id, reinvestment_receivable_id, created_at " +
                        "FROM paper_proposals WHERE portfolio_id = ? AND id = ?",
                portfolioId, proposalId
        );
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Proposal not found: " + proposalId);
        }
        return rows.get(0);
    }

    private ResearchDtos.PaperSegmentDto mapSegment(Map<String, Object> r) {
        ResearchDtos.CostPolicyDto costPolicy = parseCostPolicy((String) r.get("cost_policy_json"));

        return new ResearchDtos.PaperSegmentDto(
                (String) r.get("id"), (String) r.get("portfolio_id"), (String) r.get("strategy_id"),
                (String) r.get("strategy_version"), (String) r.get("universe_id"), (String) r.get("benchmark_listing_id"),
                costPolicy, (String) r.get("approval_mode"), (String) r.get("status"), (String) r.get("initial_equity"),
                (String) r.get("opening_observation_instant"), (String) r.get("adopted_dataset_id"), (String) r.get("adopted_at"),
                (String) r.get("created_at")
        );
    }

    private ResearchDtos.CostPolicyDto parseCostPolicy(String json) {
        try {
            ResearchDtos.CostPolicyDto policy = objectMapper.readValue(json, ResearchDtos.CostPolicyDto.class);
            new BigDecimal(policy.commissionPerFill());
            new BigDecimal(policy.bidAskSpreadBps());
            new BigDecimal(policy.slippageBps());
            return policy;
        } catch (Exception e) {
            throw new IllegalStateException("Invalid stored paper cost policy", e);
        }
    }

    private String normalizeUser(String ownerId) {
        return (ownerId == null || ownerId.isBlank()) ? "default" : ownerId.trim();
    }

    private ZoneId exchangeZone(String calendarId) {
        if (calendarId != null && calendarId.startsWith("XAMS")) {
            return ZoneId.of("Europe/Amsterdam");
        }
        return PaperDataReadinessService.EXCHANGE_ZONE;
    }
}
