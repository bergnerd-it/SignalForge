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
import java.util.*;

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
    private final ObjectMapper objectMapper = new ObjectMapper();

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
                            "cash_balance, positions_market_value, receivables_value, total_equity, cumulative_return, " +
                            "high_water_mark, drawdown, data_readiness_status, is_complete, adopted_dataset_checksum) " +
                            "VALUES (?, ?, ?, 'OPENING', ?, ?, '0.00', '0.00', ?, '0.000000', ?, '0.000000', 'COMPLETE', 1, 'none')",
                    "val-" + UUID.randomUUID(), portfolioId, now.substring(0, 10), now, cashAmount, cashAmount, cashAmount
            );
        });

        if (request.datasetId() != null && !request.datasetId().isBlank()) {
            dataReadinessService.adoptDataset(portfolioId, request.datasetId(), uid, idempotencyKey + "-adopt");
        }

        ResearchDtos.PaperSegmentDto segment = getActiveSegment(portfolioId, uid);
        mutationService.recordCommitted(uid, "ACTIVATE_PORTFOLIO", idempotencyKey, payloadHash, segmentId, segment);
        return segment;
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
        String scheduledOpenInstant = (nextSessionDate != null ? nextSessionDate : evalSessionDate) + "T09:00:00Z";
        String cycleId = "cycle-" + evalSessionDate;

        String payloadHash = PaperMutationService.computeHash("EVAL|" + portfolioId + "|" + cycleId);
        Optional<PaperMutationService.MutationEntry> cached = mutationService.checkMutation(uid, "EVALUATE_PORTFOLIO", idempotencyKey, payloadHash);
        if (cached.isPresent() && "COMMITTED".equals(cached.get().status())) {
            return getProposal(portfolioId, cached.get().resourceId(), uid);
        }

        Integer portRevision = jdbcTemplate.queryForObject(
                "SELECT revision FROM portfolio_state WHERE portfolio_id = ?", Integer.class, portfolioId
        );
        if (portRevision == null) portRevision = 1;

        // Execute Strategy via PaperStrategyAdapter (M4 strategy core reuse)
        Instant nowInstant = clock.instant();
        String now = nowInstant.toString();
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
                            "VALUES (?, ?, ?, ?, ?, ?, 'checksum', 'XETR', '1.0', ?, ?, ?, ?, ?, ?, ?, 'PROPOSED', ?)",
                    proposalId, portfolioId, cycleId, strategyId, strategyVersion, datasetId,
                    evalSessionDate, now, now, nextSessionDate != null ? nextSessionDate : evalSessionDate,
                    scheduledOpenInstant, outcome.evaluatedSignal().reasonCode(), finalPortRevision, now
            );

            // Insert proposal items with frozen desired_units
            for (var item : outcome.evaluatedSignal().items()) {
                BigDecimal targetWeight = item.targetWeight() != null ? item.targetWeight() : BigDecimal.ZERO;
                BigDecimal targetCash = finalEquity.multiply(targetWeight);
                BigDecimal priceRef = outcome.cutoffPrices().getOrDefault(item.listingId(), BigDecimal.ONE);
                BigDecimal desiredUnits = BigDecimal.ZERO;
                if (priceRef.compareTo(BigDecimal.ZERO) > 0) {
                    desiredUnits = targetCash.divide(priceRef, 0, RoundingMode.FLOOR);
                }

                jdbcTemplate.update(
                        "INSERT INTO paper_proposal_items (id, proposal_id, listing_id, rank, target_weight, desired_units, score, " +
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
        });

        ResearchDtos.PaperProposalDto proposal = getProposal(portfolioId, proposalId, uid);
        mutationService.recordCommitted(uid, "EVALUATE_PORTFOLIO", idempotencyKey, payloadHash, proposalId, proposal);
        return proposal;
    }

    // -------------------------------------------------------------
    // 3. Acceptance & Rejection
    // -------------------------------------------------------------

    public ResearchDtos.PaperProposalDto acceptProposal(
            String portfolioId, String proposalId, String ownerId, String idempotencyKey, ResearchDtos.AcceptProposalRequest request
    ) {
        String uid = normalizeUser(ownerId);
        validatePortfolioOwnership(portfolioId, uid);

        String payloadHash = PaperMutationService.computeHash("ACCEPT|" + portfolioId + "|" + proposalId);
        Optional<PaperMutationService.MutationEntry> cached = mutationService.checkMutation(uid, "ACCEPT_PROPOSAL", idempotencyKey, payloadHash);
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
                    "INSERT INTO paper_execution_intents (id, portfolio_id, proposal_id, order_type, scheduled_session_date, " +
                            "scheduled_open_instant, approval_mode, status, created_at) " +
                            "VALUES (?, ?, ?, 'REBALANCE', ?, ?, 'MANUAL', 'PENDING', ?)",
                    intentId, portfolioId, proposalId, scheduledSessionDate, scheduledOpenInstant, now
            );

            jdbcTemplate.update(
                    "INSERT INTO paper_intent_transitions (id, intent_id, from_status, to_status, trigger_type, transition_instant, notes) " +
                            "VALUES (?, ?, 'NONE', 'PENDING', 'USER_ACCEPT', ?, 'Manual proposal acceptance')",
                    "trans-" + UUID.randomUUID(), intentId, now
            );
        });

        ResearchDtos.PaperProposalDto accepted = getProposal(portfolioId, proposalId, uid);
        mutationService.recordCommitted(uid, "ACCEPT_PROPOSAL", idempotencyKey, payloadHash, proposalId, accepted);
        return accepted;
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
        int updated = jdbcTemplate.update(
                "UPDATE paper_proposals SET status = 'REJECTED', rejected_at = ?, rejection_reason = ? WHERE id = ? AND status = 'PROPOSED'",
                now, request != null ? request.rejectionReason() : "USER_REJECT", proposalId
        );
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Proposal was concurrently modified");
        }

        ResearchDtos.PaperProposalDto rejected = getProposal(portfolioId, proposalId, uid);
        mutationService.recordCommitted(uid, "REJECT_PROPOSAL", idempotencyKey, payloadHash, proposalId, rejected);
        return rejected;
    }

    // -------------------------------------------------------------
    // 4. Approval Mode & Coordinator
    // -------------------------------------------------------------

    public ResearchDtos.PaperSegmentDto changeApprovalMode(
            String portfolioId, String ownerId, String idempotencyKey, ResearchDtos.ChangeApprovalModeRequest request
    ) {
        String uid = normalizeUser(ownerId);
        validatePortfolioOwnership(portfolioId, uid);

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
                jdbcTemplate.update(
                        "UPDATE paper_portfolio_segments SET approval_mode = ? WHERE id = ?",
                        targetMode, seg.get("id")
                );
                jdbcTemplate.update(
                        "INSERT INTO paper_mode_history (id, portfolio_id, from_mode, to_mode, transition_instant, trigger_type, notes) " +
                                "VALUES (?, ?, ?, ?, ?, 'USER', ?)",
                        "mode-" + UUID.randomUUID(), portfolioId, currentMode, targetMode, now,
                        "Approval mode changed from " + currentMode + " to " + targetMode
                );
            });
        }

        ResearchDtos.PaperSegmentDto segment = getActiveSegment(portfolioId, uid);
        mutationService.recordCommitted(uid, "SET_APPROVAL_MODE", idempotencyKey, payloadHash, (String) seg.get("id"), segment);
        return segment;
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

        Map<String, Object> seg = getActiveSegmentRow(portfolioId);
        String datasetId = (String) seg.get("adopted_dataset_id");
        if (datasetId == null || datasetId.isBlank()) {
            return;
        }

        String now = clock.instant().toString();
        String currentSessionDate = now.substring(0, 10);

        // Step A: Corporate Actions Processing (Splits and Dividend Receivables)
        processCorporateActions(portfolioId, datasetId, currentSessionDate, now);

        // Step B: Settle Due Receivables
        settleDueReceivables(portfolioId, currentSessionDate, now);

        // Step C: Execute Pending Intents
        executePendingIntents(portfolioId, seg, datasetId, currentSessionDate, now);

        // Step D: Calculate Real Valuations
        calculateAndRecordValuation(portfolioId, datasetId, currentSessionDate, now);

        mutationService.recordCommitted(uid, "PROCESS_EVENTS", idempotencyKey, payloadHash, portfolioId, Map.of("processedAt", now));
    }

    private void processCorporateActions(String portfolioId, String datasetId, String currentSessionDate, String now) {
        List<Map<String, Object>> actions = jdbcTemplate.queryForList(
                "SELECT action_id, listing_id, action_type, effective_date, available_at, split_ratio_numerator, " +
                        "split_ratio_denominator, distribution_amount, distribution_currency, payment_date " +
                        "FROM historical_actions WHERE dataset_id = ? AND effective_date <= ? ORDER BY effective_date ASC",
                datasetId, currentSessionDate
        );

        for (Map<String, Object> a : actions) {
            String actionId = (String) a.get("action_id");
            String lid = (String) a.get("listing_id");
            String actionType = (String) a.get("action_type");
            String effDate = (String) a.get("effective_date");

            // Check if already processed
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM paper_processed_corporate_actions WHERE portfolio_id = ? AND listing_id = ? AND action_id = ?",
                    Integer.class, portfolioId, lid, actionId
            );
            if (count != null && count > 0) {
                continue;
            }

            String termsHash = PaperDataReadinessService.computeTermsHash(a);
            String payloadHash = PaperMutationService.computeHash("ACTION|" + actionId + "|" + termsHash);

            if ("SPLIT".equalsIgnoreCase(actionType)) {
                int num = ((Number) a.get("split_ratio_numerator")).intValue();
                int den = ((Number) a.get("split_ratio_denominator")).intValue();
                BigDecimal ratio = BigDecimal.valueOf(num).divide(BigDecimal.valueOf(den), 8, RoundingMode.HALF_EVEN);

                OperationService.SplitResult splitRes = operationService.applySplit(
                        portfolioId, lid, ratio, "idemp-split-" + actionId, now
                );

                jdbcTemplate.update(
                        "INSERT INTO paper_processed_corporate_actions (id, portfolio_id, source_namespace, listing_id, action_id, " +
                                "action_type, terms_hash, payload_hash, effective_date, availability_instant, processing_instant, " +
                                "dataset_id, dataset_checksum, status, linked_operation_id) " +
                                "VALUES (?, ?, 'SYNTHETIC', ?, ?, 'SPLIT', ?, ?, ?, ?, ?, ?, 'checksum', 'PROCESSED', ?)",
                        "pca-" + UUID.randomUUID(), portfolioId, lid, actionId, termsHash, payloadHash, effDate, now, now, datasetId, splitRes.operationId()
                );
            } else if ("CASH_DISTRIBUTION".equalsIgnoreCase(actionType) || "DISTRIBUTION".equalsIgnoreCase(actionType)) {
                BigDecimal distAmt = a.get("distribution_amount") != null ? new BigDecimal(String.valueOf(a.get("distribution_amount"))) : BigDecimal.ZERO;
                String payDate = a.get("payment_date") != null ? (String) a.get("payment_date") : effDate;

                // Entitlement from holdings
                List<Map<String, Object>> posList = jdbcTemplate.queryForList(
                        "SELECT quantity FROM positions WHERE portfolio_id = ? AND listing_id = ?",
                        portfolioId, lid
                );
                BigDecimal heldQty = posList.isEmpty() ? BigDecimal.ZERO : new BigDecimal((String) posList.get(0).get("quantity"));

                if (heldQty.compareTo(BigDecimal.ZERO) > 0 && distAmt.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal grossAmt = heldQty.multiply(distAmt).setScale(2, RoundingMode.HALF_EVEN);
                    BigDecimal netAmt = grossAmt;
                    String recId = "rec-" + UUID.randomUUID();

                    jdbcTemplate.update(
                            "INSERT INTO paper_receivables (id, portfolio_id, listing_id, action_id, action_type, record_instant, " +
                                    "ex_date, payment_date, gross_amount, withholding_tax, net_amount, status, created_at) " +
                                    "VALUES (?, ?, ?, ?, 'CASH_DISTRIBUTION', ?, ?, ?, ?, '0.00', ?, 'PENDING', ?)",
                            recId, portfolioId, lid, actionId, now, effDate, payDate, grossAmt.toPlainString(), netAmt.toPlainString(), now
                    );

                    jdbcTemplate.update(
                            "INSERT INTO paper_processed_corporate_actions (id, portfolio_id, source_namespace, listing_id, action_id, " +
                                    "action_type, terms_hash, payload_hash, effective_date, availability_instant, processing_instant, " +
                                    "dataset_id, dataset_checksum, status, linked_receivable_id) " +
                                    "VALUES (?, ?, 'SYNTHETIC', ?, ?, 'CASH_DISTRIBUTION', ?, ?, ?, ?, ?, ?, 'checksum', 'PROCESSED', ?)",
                            "pca-" + UUID.randomUUID(), portfolioId, lid, actionId, termsHash, payloadHash, effDate, now, now, datasetId, recId
                    );
                }
            }
        }
    }

    private void settleDueReceivables(String portfolioId, String currentSessionDate, String now) {
        List<Map<String, Object>> receivables = jdbcTemplate.queryForList(
                "SELECT id, listing_id, action_id, net_amount, payment_date FROM paper_receivables " +
                        "WHERE portfolio_id = ? AND status = 'PENDING' AND payment_date <= ?",
                portfolioId, currentSessionDate
        );

        for (Map<String, Object> r : receivables) {
            String recId = (String) r.get("id");
            String lid = (String) r.get("listing_id");
            String actionId = (String) r.get("action_id");
            BigDecimal netAmount = new BigDecimal((String) r.get("net_amount"));

            OperationService.CashDistributionResult res = operationService.creditCashDistribution(
                    portfolioId, lid, actionId, netAmount, "idemp-rec-pay-" + recId, now
            );

            jdbcTemplate.update(
                    "UPDATE paper_receivables SET status = 'PAID', paid_operation_id = ?, paid_at = ? WHERE id = ?",
                    res.operationId(), now, recId
            );
        }
    }

    private void executePendingIntents(String portfolioId, Map<String, Object> seg, String datasetId, String currentSessionDate, String now) {
        List<Map<String, Object>> intents = jdbcTemplate.queryForList(
                "SELECT id, proposal_id, scheduled_session_date, scheduled_open_instant FROM paper_execution_intents " +
                        "WHERE portfolio_id = ? AND status = 'PENDING' AND scheduled_open_instant <= ?",
                portfolioId, now
        );

        for (Map<String, Object> intent : intents) {
            String intentId = (String) intent.get("id");
            String proposalId = (String) intent.get("proposal_id");
            String schedDate = (String) intent.get("scheduled_session_date");

            // Check if open bars exist on scheduled date
            List<Map<String, Object>> propItems = jdbcTemplate.queryForList(
                    "SELECT listing_id, target_weight, desired_units FROM paper_proposal_items WHERE proposal_id = ? ORDER BY rank ASC",
                    proposalId
            );

            Map<String, BigDecimal> openPrices = new HashMap<>();
            for (Map<String, Object> item : propItems) {
                String lid = (String) item.get("listing_id");
                List<Map<String, Object>> barList = jdbcTemplate.queryForList(
                        "SELECT open FROM historical_bars WHERE dataset_id = ? AND listing_id = ? AND session_date = ?",
                        datasetId, lid, schedDate
                );
                if (!barList.isEmpty()) {
                    openPrices.put(lid, new BigDecimal(String.valueOf(barList.get(0).get("open"))));
                }
            }

            if (openPrices.size() < propItems.size()) {
                // Observations not yet available -> wait for observation
                jdbcTemplate.update(
                        "UPDATE paper_execution_intents SET status = 'WAITING_FOR_OBSERVATION' WHERE id = ?",
                        intentId
                );
                jdbcTemplate.update(
                        "INSERT INTO paper_intent_transitions (id, intent_id, from_status, to_status, trigger_type, transition_instant, notes) " +
                                "VALUES (?, ?, 'PENDING', 'WAITING_FOR_OBSERVATION', 'OBSERVATION_CHECK', ?, 'Market open bars unavailable')",
                        "trans-" + UUID.randomUUID(), intentId, now
                );
                continue;
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

            BigDecimal commission = new BigDecimal("1.00"); // Standard M4 commission

            // Determine target units per listing
            Map<String, BigDecimal> targetUnits = new HashMap<>();
            for (Map<String, Object> item : propItems) {
                String lid = (String) item.get("listing_id");
                BigDecimal weight = new BigDecimal((String) item.get("target_weight"));
                BigDecimal targetCash = totalEquity.multiply(weight);
                BigDecimal price = openPrices.get(lid);
                BigDecimal units = BigDecimal.ZERO;
                if (price != null && price.compareTo(BigDecimal.ZERO) > 0) {
                    units = targetCash.divide(price, 0, RoundingMode.FLOOR);
                }
                targetUnits.put(lid, units);
            }

            // Execute Sells first
            for (Map.Entry<String, BigDecimal> entry : currentHoldings.entrySet()) {
                String lid = entry.getKey();
                BigDecimal currentQty = entry.getValue();
                BigDecimal targetQty = targetUnits.getOrDefault(lid, BigDecimal.ZERO);
                if (currentQty.compareTo(targetQty) > 0) {
                    BigDecimal sellQty = currentQty.subtract(targetQty);
                    BigDecimal openPrice = openPrices.get(lid);
                    if (openPrice != null && openPrice.compareTo(BigDecimal.ZERO) > 0 && sellQty.compareTo(BigDecimal.ZERO) > 0) {
                        OperationService.TradeExecutionResult res = operationService.executeTrade(
                                portfolioId, lid, "SELL", sellQty, openPrice, commission,
                                "idemp-trade-" + intentId + "-sell-" + lid, "RAW_OPEN"
                        );
                        jdbcTemplate.update(
                                "INSERT INTO paper_execution_results (id, intent_id, proposal_id, operation_id, execution_id, listing_id, " +
                                        "side, requested_quantity, executed_quantity, shortfall_reason, raw_open_price, fill_price, commission, " +
                                        "spread_slippage_cost, cost_basis, realized_gain, dataset_id, dataset_checksum, market_effective_instant, " +
                                        "observed_instant, booked_instant) " +
                                        "VALUES (?, ?, ?, ?, ?, ?, 'SELL', ?, ?, NULL, ?, ?, ?, '0.00', ?, ?, ?, 'checksum', ?, ?, ?)",
                                "res-" + UUID.randomUUID(), intentId, proposalId, res.operationId(), res.executionId(), lid,
                                sellQty.toPlainString(), res.units(), openPrice.toPlainString(), res.fillPrice(), res.commission(),
                                res.basisDelta(), res.realizedGain(), datasetId, now, now, now
                        );
                    }
                }
            }

            // Execute Buys second with affordability checks
            rawCash = jdbcTemplate.queryForObject("SELECT cash_amount FROM portfolio_state WHERE portfolio_id = ?", String.class, portfolioId);
            BigDecimal availableCash = new BigDecimal(rawCash != null ? rawCash : "0.00");

            for (Map.Entry<String, BigDecimal> entry : targetUnits.entrySet()) {
                String lid = entry.getKey();
                BigDecimal targetQty = entry.getValue();
                BigDecimal currentQty = currentHoldings.getOrDefault(lid, BigDecimal.ZERO);
                if (targetQty.compareTo(currentQty) > 0) {
                    BigDecimal buyQty = targetQty.subtract(currentQty);
                    BigDecimal openPrice = openPrices.get(lid);
                    if (openPrice != null && openPrice.compareTo(BigDecimal.ZERO) > 0 && buyQty.compareTo(BigDecimal.ZERO) > 0) {
                        // Squeeze units for affordability
                        BigDecimal required = buyQty.multiply(openPrice).add(commission);
                        while (required.compareTo(availableCash) > 0 && buyQty.compareTo(BigDecimal.ZERO) > 0) {
                            buyQty = buyQty.subtract(BigDecimal.ONE);
                            required = buyQty.multiply(openPrice).add(commission);
                        }

                        if (buyQty.compareTo(BigDecimal.ZERO) > 0) {
                            OperationService.TradeExecutionResult res = operationService.executeTrade(
                                    portfolioId, lid, "BUY", buyQty, openPrice, commission,
                                    "idemp-trade-" + intentId + "-buy-" + lid, "RAW_OPEN"
                            );
                            jdbcTemplate.update(
                                    "INSERT INTO paper_execution_results (id, intent_id, proposal_id, operation_id, execution_id, listing_id, " +
                                            "side, requested_quantity, executed_quantity, shortfall_reason, raw_open_price, fill_price, commission, " +
                                            "spread_slippage_cost, cost_basis, realized_gain, dataset_id, dataset_checksum, market_effective_instant, " +
                                            "observed_instant, booked_instant) " +
                                            "VALUES (?, ?, ?, ?, ?, ?, 'BUY', ?, ?, NULL, ?, ?, ?, '0.00', ?, '0.00', ?, 'checksum', ?, ?, ?)",
                                    "res-" + UUID.randomUUID(), intentId, proposalId, res.operationId(), res.executionId(), lid,
                                    buyQty.toPlainString(), res.units(), openPrice.toPlainString(), res.fillPrice(), res.commission(),
                                    res.basisDelta(), datasetId, now, now, now
                            );
                            availableCash = new BigDecimal(res.remainingCash());
                        }
                    }
                }
            }

            // Mark Intent Executed
            jdbcTemplate.update("UPDATE paper_execution_intents SET status = 'EXECUTED' WHERE id = ?", intentId);
            jdbcTemplate.update(
                    "INSERT INTO paper_intent_transitions (id, intent_id, from_status, to_status, trigger_type, transition_instant, notes) " +
                            "VALUES (?, ?, 'PENDING', 'EXECUTED', 'MARKET_OPEN_FILL', ?, 'Fills completed successfully')",
                    "trans-" + UUID.randomUUID(), intentId, now
            );
        }
    }

    private void calculateAndRecordValuation(String portfolioId, String datasetId, String currentSessionDate, String now) {
        String rawCash = jdbcTemplate.queryForObject("SELECT cash_amount FROM portfolio_state WHERE portfolio_id = ?", String.class, portfolioId);
        BigDecimal cash = new BigDecimal(rawCash != null ? rawCash : "0.00");

        BigDecimal positionsMarketVal = BigDecimal.ZERO;
        List<Map<String, Object>> posRows = jdbcTemplate.queryForList(
                "SELECT listing_id, quantity FROM positions WHERE portfolio_id = ? AND quantity != '0'", portfolioId
        );
        for (Map<String, Object> pos : posRows) {
            String lid = (String) pos.get("listing_id");
            BigDecimal qty = new BigDecimal((String) pos.get("quantity"));
            List<Map<String, Object>> barList = jdbcTemplate.queryForList(
                    "SELECT close, open FROM historical_bars WHERE dataset_id = ? AND listing_id = ? AND session_date <= ? ORDER BY session_date DESC LIMIT 1",
                    datasetId, lid, currentSessionDate
            );
            BigDecimal price = BigDecimal.ZERO;
            if (!barList.isEmpty()) {
                String closeStr = (String) barList.get(0).get("close");
                price = new BigDecimal(closeStr != null ? closeStr : "0.00");
            }
            positionsMarketVal = positionsMarketVal.add(qty.multiply(price));
        }

        String rawRec = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(CAST(net_amount AS REAL)), 0.0) FROM paper_receivables WHERE portfolio_id = ? AND status = 'PENDING'",
                String.class, portfolioId
        );
        BigDecimal receivablesVal = new BigDecimal(rawRec != null ? rawRec : "0.00").setScale(2, RoundingMode.HALF_EVEN);

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

        String rawHwm = jdbcTemplate.queryForObject(
                "SELECT MAX(CAST(total_equity AS REAL)) FROM paper_valuations WHERE portfolio_id = ?",
                String.class, portfolioId
        );
        BigDecimal hwm = rawHwm != null ? new BigDecimal(rawHwm).setScale(2, RoundingMode.HALF_EVEN) : totalEquity;
        if (totalEquity.compareTo(hwm) > 0) {
            hwm = totalEquity;
        }

        BigDecimal drawdown = BigDecimal.ZERO;
        if (hwm.compareTo(BigDecimal.ZERO) > 0 && totalEquity.compareTo(hwm) < 0) {
            drawdown = totalEquity.subtract(hwm).divide(hwm, 6, RoundingMode.HALF_EVEN);
        }

        jdbcTemplate.update(
                "INSERT OR REPLACE INTO paper_valuations (id, portfolio_id, session_date, observation_kind, observation_instant, " +
                        "cash_balance, positions_market_value, receivables_value, total_equity, cumulative_return, " +
                        "high_water_mark, drawdown, data_readiness_status, is_complete, adopted_dataset_id, adopted_dataset_checksum) " +
                        "VALUES (?, ?, ?, 'SESSION_CLOSE', ?, ?, ?, ?, ?, ?, ?, ?, 'COMPLETE', 1, ?, 'checksum')",
                "val-" + UUID.randomUUID(), portfolioId, currentSessionDate, now,
                cash.toPlainString(), positionsMarketVal.toPlainString(), receivablesVal.toPlainString(),
                totalEquity.toPlainString(), cumulativeReturn.toPlainString(), hwm.toPlainString(), drawdown.toPlainString(),
                datasetId
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
                "SELECT id, proposal_id, listing_id, rank, target_weight, desired_units, score, reason_code, reason_description, raw_price_reference " +
                        "FROM paper_proposal_items WHERE proposal_id = ? ORDER BY rank ASC",
                proposalId
        );
        List<ResearchDtos.PaperProposalItemDto> items = new ArrayList<>();
        for (Map<String, Object> r : itemRows) {
            items.add(new ResearchDtos.PaperProposalItemDto(
                    (String) r.get("id"), (String) r.get("proposal_id"), (String) r.get("listing_id"),
                    ((Number) r.get("rank")).intValue(), (String) r.get("target_weight"), (String) r.get("desired_units"),
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
                (String) p.get("rejection_reason"), (String) p.get("superseding_proposal_id"), (String) p.get("created_at"),
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
                        "FROM paper_valuations WHERE portfolio_id = ? ORDER BY observation_instant DESC LIMIT ? OFFSET ?",
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
                        "status, accepted_at, rejected_at, rejection_reason, superseding_proposal_id, created_at " +
                        "FROM paper_proposals WHERE portfolio_id = ? AND id = ?",
                portfolioId, proposalId
        );
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Proposal not found: " + proposalId);
        }
        return rows.get(0);
    }

    private ResearchDtos.PaperSegmentDto mapSegment(Map<String, Object> r) {
        ResearchDtos.CostPolicyDto costPolicy = null;
        try {
            costPolicy = objectMapper.readValue((String) r.get("cost_policy_json"), ResearchDtos.CostPolicyDto.class);
        } catch (Exception e) {
            costPolicy = new ResearchDtos.CostPolicyDto("1.00", "0", "0");
        }

        return new ResearchDtos.PaperSegmentDto(
                (String) r.get("id"), (String) r.get("portfolio_id"), (String) r.get("strategy_id"),
                (String) r.get("strategy_version"), (String) r.get("universe_id"), (String) r.get("benchmark_listing_id"),
                costPolicy, (String) r.get("approval_mode"), (String) r.get("status"), (String) r.get("initial_equity"),
                (String) r.get("opening_observation_instant"), (String) r.get("adopted_dataset_id"), (String) r.get("adopted_at"),
                (String) r.get("created_at")
        );
    }

    private String normalizeUser(String ownerId) {
        return (ownerId == null || ownerId.isBlank()) ? "default" : ownerId.trim();
    }
}
