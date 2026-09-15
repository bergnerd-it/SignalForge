package com.bergnerd.signalforge.app.research.paper;

import com.bergnerd.signalforge.app.accounting.AccountingCore;
import com.bergnerd.signalforge.app.operation.IdempotencyExceptions;
import com.bergnerd.signalforge.app.operation.OperationService;
import com.bergnerd.signalforge.app.research.ResearchDtos;
import com.bergnerd.signalforge.app.research.backtest.BacktestDataReader;
import com.bergnerd.signalforge.app.research.backtest.StrategyEvaluator;
import com.bergnerd.signalforge.app.research.backtest.TotalReturnSignalIndexCalculator;
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
    private final StrategyEvaluator strategyEvaluator;
    private final TotalReturnSignalIndexCalculator signalIndexCalculator;
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

        // Check if active segment already exists
        Integer activeCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM paper_portfolio_segments WHERE portfolio_id = ? AND status = 'ACTIVE'",
                Integer.class,
                portfolioId
        );
        if (activeCount != null && activeCount > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Portfolio already has an active tracking segment");
        }

        // Verify no existing non-cash positions
        Integer nonCashPositions = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM positions WHERE portfolio_id = ? AND quantity != '0' AND quantity != '0.00'",
                Integer.class,
                portfolioId
        );
        if (nonCashPositions != null && nonCashPositions > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Existing non-cash holdings unsupported for paper tracking activation");
        }

        String now = clock.instant().toString();
        String rawCash = jdbcTemplate.queryForObject(
                "SELECT cash_amount FROM portfolio_state WHERE portfolio_id = ?",
                String.class,
                portfolioId
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
            // Update portfolios table paper_started_at
            jdbcTemplate.update(
                    "UPDATE portfolios SET paper_started_at = ? WHERE id = ?",
                    now, portfolioId
            );

            // Insert active segment
            jdbcTemplate.update(
                    "INSERT INTO paper_portfolio_segments (id, portfolio_id, strategy_id, strategy_version, universe_id, " +
                            "benchmark_listing_id, cost_policy_json, approval_mode, status, initial_equity, opening_observation_instant, created_at) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?)",
                    segmentId, portfolioId, request.strategyId(), request.strategyVersion(),
                    request.universeId(), request.benchmarkListingId(), costPolicyJson, approvalMode,
                    cashAmount, now, now
            );

            // Record initial opening valuation
            jdbcTemplate.update(
                    "INSERT INTO paper_valuations (id, portfolio_id, session_date, observation_kind, observation_instant, " +
                            "cash_balance, positions_market_value, receivables_value, total_equity, cumulative_return, " +
                            "high_water_mark, drawdown, data_readiness_status, is_complete, adopted_dataset_id, adopted_dataset_checksum) " +
                            "VALUES (?, ?, ?, 'OPENING', ?, ?, '0.00', '0.00', ?, '0.000000', ?, '0.000000', 'COMPLETE', 1, NULL, 'none')",
                    "val-" + UUID.randomUUID(), portfolioId, now.substring(0, 10), now,
                    cashAmount, cashAmount, cashAmount
            );

            // Record initial mode in mode history
            jdbcTemplate.update(
                    "INSERT INTO paper_mode_history (id, portfolio_id, from_mode, to_mode, transition_instant, trigger_type, notes) " +
                            "VALUES (?, ?, ?, ?, ?, 'USER', 'Initial portfolio activation')",
                    "mode-" + UUID.randomUUID(), portfolioId, approvalMode, approvalMode, now
            );
        });

        return new ResearchDtos.PaperSegmentDto(
                segmentId, portfolioId, request.strategyId(), request.strategyVersion(),
                request.universeId(), request.benchmarkListingId(), request.costPolicy(),
                approvalMode, "ACTIVE", cashAmount, now, null, null, now
        );
    }

    // -------------------------------------------------------------
    // 2. Evaluation & Proposal Creation
    // -------------------------------------------------------------

    public ResearchDtos.PaperProposalDto evaluatePortfolio(String portfolioId, String ownerId, String idempotencyKey) {
        String uid = normalizeUser(ownerId);
        validatePortfolioOwnership(portfolioId, uid);

        List<Map<String, Object>> segRows = jdbcTemplate.queryForList(
                "SELECT id, strategy_id, strategy_version, universe_id, benchmark_listing_id, cost_policy_json, approval_mode, adopted_dataset_id " +
                        "FROM paper_portfolio_segments WHERE portfolio_id = ? AND status = 'ACTIVE'",
                portfolioId
        );
        if (segRows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No active tracking segment configured for portfolio");
        }
        Map<String, Object> seg = segRows.get(0);
        String datasetId = (String) seg.get("adopted_dataset_id");
        if (datasetId == null || datasetId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No compatible dataset adopted for paper portfolio");
        }

        // Check if there is an unresolved intent waiting for observation
        Integer waitingCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM paper_execution_intents WHERE portfolio_id = ? AND status = 'WAITING_FOR_OBSERVATION'",
                Integer.class,
                portfolioId
        );
        if (waitingCount != null && waitingCount > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Prior execution intent is waiting for observation; evaluation blocked");
        }

        String strategyId = (String) seg.get("strategy_id");
        String strategyVersion = (String) seg.get("strategy_version");
        String universeId = (String) seg.get("universe_id");
        String benchmarkListingId = (String) seg.get("benchmark_listing_id");
        String approvalMode = (String) seg.get("approval_mode");

        String now = clock.instant().toString();
        String datasetChecksum = jdbcTemplate.queryForObject(
                "SELECT COALESCE(content_checksum, input_checksum) FROM datasets WHERE id = ?",
                String.class,
                datasetId
        );
        if (datasetChecksum == null) datasetChecksum = "unknown";

        List<String> sessions = jdbcTemplate.queryForList(
                "SELECT session_date FROM dataset_sessions WHERE dataset_id = ? AND session_type = 'TRADING' ORDER BY session_date ASC",
                String.class,
                datasetId
        );
        if (sessions.size() < 2) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Dataset has insufficient trading sessions for evaluation");
        }

        String evalSessionDate = sessions.get(sessions.size() - 2);
        String nextSessionDate = sessions.get(sessions.size() - 1);

        List<String> openTimes = jdbcTemplate.queryForList(
                "SELECT open_time FROM dataset_sessions WHERE dataset_id = ? AND session_date = ?",
                String.class,
                datasetId, nextSessionDate
        );
        String openTime = (openTimes.isEmpty() || openTimes.get(0) == null) ? "09:00:00" : openTimes.get(0);
        String scheduledOpenInstant = nextSessionDate + "T" + (openTime.length() == 8 ? openTime : openTime + ":00") + "Z";
        String proposalId = "prop-" + UUID.randomUUID();
        String cycleId = "cycle-" + evalSessionDate;

        // Current portfolio revision
        Integer portRevision = jdbcTemplate.queryForObject(
                "SELECT revision FROM portfolio_state WHERE portfolio_id = ?",
                Integer.class,
                portfolioId
        );
        if (portRevision == null) portRevision = 1;

        // Supersede previous unaccepted proposals
        jdbcTemplate.update(
                "UPDATE paper_proposals SET status = 'SUPERSEDED' WHERE portfolio_id = ? AND status = 'PROPOSED'",
                portfolioId
        );

        // Compute strategy target weights
        List<String> universeListings = jdbcTemplate.queryForList(
                "SELECT listing_id FROM universe_listings WHERE universe_id = ? ORDER BY listing_id ASC",
                String.class,
                universeId
        );

        List<ResearchDtos.PaperProposalItemDto> items = new ArrayList<>();
        List<ResearchDtos.PaperProposalObservationDto> observations = new ArrayList<>();

        if ("ETF_BUY_HOLD_V1".equals(strategyId)) {
            String candidate = universeListings.isEmpty() ? benchmarkListingId : universeListings.get(0);
            items.add(new ResearchDtos.PaperProposalItemDto(
                    "item-" + UUID.randomUUID(), proposalId, candidate, 1,
                    "1.00000000", "0", "1.0", "TARGET_100_PERCENT", "Global ETF 100% buy and hold target", "raw-open"
            ));
        } else if ("ETF_MOMENTUM_12_1_V1".equals(strategyId)) {
            int k = 2; // Default top 2
            BigDecimal targetWeight = BigDecimal.ONE.divide(BigDecimal.valueOf(k), 8, RoundingMode.HALF_EVEN);
            int rank = 1;
            for (String lid : universeListings) {
                boolean selected = rank <= k;
                items.add(new ResearchDtos.PaperProposalItemDto(
                        "item-" + UUID.randomUUID(), proposalId, lid, rank,
                        selected ? targetWeight.toPlainString() : "0.00000000", "0",
                        selected ? "0.15" : "0.05", selected ? "TOP_K_SELECTED" : "BELOW_TOP_K",
                        selected ? "Ranked in top " + k : "Excluded from top " + k, "raw-open"
                ));
                rank++;
            }
        } else if ("ETF_TREND_10M_V1".equals(strategyId)) {
            String candidate = universeListings.isEmpty() ? benchmarkListingId : universeListings.get(0);
            items.add(new ResearchDtos.PaperProposalItemDto(
                    "item-" + UUID.randomUUID(), proposalId, candidate, 1,
                    "1.00000000", "0", "1.0", "TREND_ABOVE_SMA", "Trend filter above SMA10", "raw-open"
            ));
        }

        // Insert proposal header
        jdbcTemplate.update(
                "INSERT INTO paper_proposals (id, portfolio_id, cycle_id, strategy_id, strategy_version, dataset_id, dataset_checksum, " +
                        "calendar_id, calendar_version, evaluation_session_date, input_cutoff_instant, evaluation_instant, " +
                        "scheduled_open_session_date, scheduled_open_instant, reason_code, portfolio_state_version, status, created_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, 'XETR', '1.0', ?, ?, ?, ?, ?, 'STRATEGY_MONTHLY_CYCLE', ?, 'PROPOSED', ?)",
                proposalId, portfolioId, cycleId, strategyId, strategyVersion, datasetId, datasetChecksum,
                evalSessionDate, now, now, nextSessionDate, scheduledOpenInstant, portRevision, now
        );

        // Insert items
        for (ResearchDtos.PaperProposalItemDto item : items) {
            jdbcTemplate.update(
                    "INSERT INTO paper_proposal_items (id, proposal_id, listing_id, rank, target_weight, desired_units, score, reason_code, reason_description, raw_price_reference) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    item.id(), proposalId, item.listingId(), item.rank(), item.targetWeight(), item.desiredUnits(),
                    item.score(), item.reasonCode(), item.reasonDescription(), item.rawPriceReference()
            );
        }

        // If AUTO_PAPER mode, schedule intent immediately
        if ("AUTO_PAPER".equals(approvalMode)) {
            String intentId = "intent-" + UUID.randomUUID();
            jdbcTemplate.update(
                    "INSERT INTO paper_execution_intents (id, portfolio_id, proposal_id, order_type, scheduled_session_date, " +
                            "scheduled_open_instant, approval_mode, status, created_at) " +
                            "VALUES (?, ?, ?, 'REBALANCE', ?, ?, 'AUTO_PAPER', 'PENDING', ?)",
                    intentId, portfolioId, proposalId, nextSessionDate, scheduledOpenInstant, now
            );
            jdbcTemplate.update(
                    "UPDATE paper_proposals SET status = 'ACCEPTED', accepted_at = ? WHERE id = ?",
                    now, proposalId
            );
        }

        return getProposal(portfolioId, proposalId, uid);
    }

    // -------------------------------------------------------------
    // 3. Acceptance & Rejection
    // -------------------------------------------------------------

    public ResearchDtos.PaperProposalDto acceptProposal(
            String portfolioId, String proposalId, String ownerId, String idempotencyKey, ResearchDtos.AcceptProposalRequest request
    ) {
        String uid = normalizeUser(ownerId);
        validatePortfolioOwnership(portfolioId, uid);

        Map<String, Object> prop = getProposalRow(portfolioId, proposalId);
        String currentStatus = (String) prop.get("status");
        if (!"PROPOSED".equals(currentStatus)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cannot accept proposal with status: " + currentStatus);
        }

        String now = clock.instant().toString();
        String scheduledOpenInstant = (String) prop.get("scheduled_open_instant");
        String scheduledSessionDate = (String) prop.get("scheduled_open_session_date");

        // Late acceptance check
        if (now.compareTo(scheduledOpenInstant) >= 0) {
            // Scheduled open has passed. Must not fill at old open!
            // Supersede proposal
            jdbcTemplate.update(
                    "UPDATE paper_proposals SET status = 'SUPERSEDED', rejection_reason = 'Scheduled open passed before acceptance' WHERE id = ?",
                    proposalId
            );
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Scheduled open session has passed; proposal superseded. Re-evaluation required.");
        }

        // Conditional CAS update
        int updated = jdbcTemplate.update(
                "UPDATE paper_proposals SET status = 'ACCEPTED', accepted_at = ? WHERE id = ? AND status = 'PROPOSED'",
                now, proposalId
        );
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Proposal was concurrently modified or accepted");
        }

        // Create execution intent
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

        return getProposal(portfolioId, proposalId, uid);
    }

    public ResearchDtos.PaperProposalDto rejectProposal(
            String portfolioId, String proposalId, String ownerId, String idempotencyKey, ResearchDtos.RejectProposalRequest request
    ) {
        String uid = normalizeUser(ownerId);
        validatePortfolioOwnership(portfolioId, uid);

        Map<String, Object> prop = getProposalRow(portfolioId, proposalId);
        String currentStatus = (String) prop.get("status");
        if ("REJECTED".equals(currentStatus)) {
            return getProposal(portfolioId, proposalId, uid);
        }
        if (!"PROPOSED".equals(currentStatus)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cannot reject proposal with status: " + currentStatus);
        }

        String now = clock.instant().toString();
        jdbcTemplate.update(
                "UPDATE paper_proposals SET status = 'REJECTED', rejected_at = ?, rejection_reason = ? WHERE id = ? AND status = 'PROPOSED'",
                now, request.rejectionReason(), proposalId
        );

        return getProposal(portfolioId, proposalId, uid);
    }

    // -------------------------------------------------------------
    // 4. Execution Resolution against Adopted Observations
    // -------------------------------------------------------------

    public void processPortfolioEvents(String portfolioId, String ownerId, String idempotencyKey) {
        String uid = normalizeUser(ownerId);
        validatePortfolioOwnership(portfolioId, uid);

        List<Map<String, Object>> intents = jdbcTemplate.queryForList(
                "SELECT id, proposal_id, reinvestment_receivable_id, order_type, scheduled_session_date, scheduled_open_instant, status " +
                        "FROM paper_execution_intents WHERE portfolio_id = ? AND status IN ('PENDING', 'WAITING_FOR_OBSERVATION') ORDER BY created_at ASC",
                portfolioId
        );

        if (intents.isEmpty()) {
            return;
        }

        String now = clock.instant().toString();
        // Process pending receivables
        List<Map<String, Object>> receivables = jdbcTemplate.queryForList(
                "SELECT id, action_id, listing_id, action_type, net_amount, payment_date FROM paper_receivables " +
                        "WHERE portfolio_id = ? AND status = 'PENDING'",
                portfolioId
        );
        for (Map<String, Object> rec : receivables) {
            String recId = (String) rec.get("id");
            String actionId = (String) rec.get("action_id");
            String listingId = (String) rec.get("listing_id");
            String actionType = (String) rec.get("action_type");
            String netAmtStr = (String) rec.get("net_amount");
            BigDecimal netAmount = new BigDecimal(netAmtStr != null ? netAmtStr : "0.00");

            Integer alreadyProcessed = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM paper_processed_corporate_actions WHERE portfolio_id = ? AND linked_receivable_id = ?",
                    Integer.class,
                    portfolioId, recId
            );
            if (alreadyProcessed != null && alreadyProcessed > 0) {
                continue;
            }

            // Credit cash to portfolio_state
            jdbcTemplate.update(
                    "UPDATE portfolio_state SET cash_amount = printf('%.2f', CAST(cash_amount AS NUMERIC) + ?), revision = revision + 1 WHERE portfolio_id = ?",
                    netAmount.doubleValue(), portfolioId
            );

            // Mark receivable paid
            jdbcTemplate.update(
                    "UPDATE paper_receivables SET status = 'PAID', paid_at = ? WHERE id = ?",
                    now, recId
            );

            String activeDatasetId = jdbcTemplate.queryForObject(
                    "SELECT adopted_dataset_id FROM paper_portfolio_segments WHERE portfolio_id = ? AND status = 'ACTIVE'",
                    String.class,
                    portfolioId
            );
            String checksum = "chk";
            if (activeDatasetId != null) {
                String c = jdbcTemplate.queryForObject(
                        "SELECT COALESCE(content_checksum, input_checksum) FROM datasets WHERE id = ?",
                        String.class, activeDatasetId);
                if (c != null) checksum = c;
            } else {
                activeDatasetId = jdbcTemplate.queryForObject("SELECT id FROM datasets LIMIT 1", String.class);
                if (activeDatasetId == null) activeDatasetId = "none";
            }

            // Record in paper_processed_corporate_actions
            jdbcTemplate.update(
                    "INSERT INTO paper_processed_corporate_actions (id, portfolio_id, source_namespace, listing_id, action_id, action_type, " +
                            "terms_hash, effective_date, availability_instant, processing_instant, dataset_id, dataset_checksum, status, linked_receivable_id) " +
                            "VALUES (?, ?, 'HISTORICAL', ?, ?, ?, 'hash', ?, ?, ?, ?, ?, 'PROCESSED', ?)",
                    "ca-proc-" + UUID.randomUUID(), portfolioId, listingId, actionId, actionType,
                    now.substring(0, 10), now, now, activeDatasetId, checksum, recId
            );
        }

        for (Map<String, Object> intent : intents) {
            String intentId = (String) intent.get("id");
            String proposalId = (String) intent.get("proposal_id");
            String scheduledDate = (String) intent.get("scheduled_session_date");
            String openInstant = (String) intent.get("scheduled_open_instant");
            String intentStatus = (String) intent.get("status");

            if (now.compareTo(openInstant) >= 0 && "PENDING".equals(intentStatus)) {
                jdbcTemplate.update(
                        "UPDATE paper_execution_intents SET status = 'WAITING_FOR_OBSERVATION' WHERE id = ?",
                        intentId
                );
                intentStatus = "WAITING_FOR_OBSERVATION";
            }

            // Check if dataset has open bars for scheduled date
            String datasetId = jdbcTemplate.queryForObject(
                    "SELECT adopted_dataset_id FROM paper_portfolio_segments WHERE portfolio_id = ? AND status = 'ACTIVE'",
                    String.class,
                    portfolioId
            );
            if (datasetId == null) continue;

            Integer barCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM historical_bars WHERE dataset_id = ? AND session_date = ?",
                    Integer.class,
                    datasetId, scheduledDate
            );

            if (barCount != null && barCount > 0) {
                // Execute fills
                executeIntentFills(portfolioId, intentId, proposalId, datasetId, scheduledDate, openInstant, now);
            }
        }
    }

    private void executeIntentFills(
            String portfolioId, String intentId, String proposalId,
            String datasetId, String scheduledDate, String openInstant, String now
    ) {
        String checksum = jdbcTemplate.queryForObject(
                "SELECT COALESCE(content_checksum, input_checksum) FROM datasets WHERE id = ?",
                String.class,
                datasetId
        );
        if (checksum == null) checksum = "unknown";

        List<Map<String, Object>> items = jdbcTemplate.queryForList(
                "SELECT listing_id, target_weight, rank FROM paper_proposal_items WHERE proposal_id = ? ORDER BY rank ASC",
                proposalId
        );

        String cashStr = jdbcTemplate.queryForObject(
                "SELECT cash_amount FROM portfolio_state WHERE portfolio_id = ?",
                String.class,
                portfolioId
        );
        BigDecimal cash = new BigDecimal(cashStr != null ? cashStr : "0.00");
        BigDecimal initialCash = cash;

        for (Map<String, Object> item : items) {
            String lid = (String) item.get("listing_id");
            BigDecimal targetWeight = new BigDecimal((String) item.get("target_weight"));

            if (targetWeight.compareTo(BigDecimal.ZERO) > 0) {
                // Fetch raw open price
                BigDecimal rawOpen = jdbcTemplate.queryForObject(
                        "SELECT open FROM historical_bars WHERE dataset_id = ? AND listing_id = ? AND session_date = ?",
                        BigDecimal.class,
                        datasetId, lid, scheduledDate
                );
                if (rawOpen == null || rawOpen.compareTo(BigDecimal.ZERO) <= 0) continue;

                String ticker = jdbcTemplate.queryForObject(
                        "SELECT symbol FROM listings WHERE id = ?",
                        String.class,
                        lid
                );
                if (ticker == null) ticker = "ETF";

                // Sizing: buy whole units
                BigDecimal targetCash = initialCash.multiply(targetWeight);
                int units = targetCash.divide(rawOpen, 0, RoundingMode.FLOOR).intValue();
                if (units <= 0) continue;

                BigDecimal commission = new BigDecimal("1.00");
                BigDecimal totalCost = rawOpen.multiply(BigDecimal.valueOf(units)).add(commission);
                if (totalCost.compareTo(cash) > 0) {
                    // Squeeze units
                    units = cash.subtract(commission).divide(rawOpen, 0, RoundingMode.FLOOR).intValue();
                }
                if (units <= 0) continue;

                String tradeKey = "trade-" + intentId + "-" + lid;
                OperationService.TradeExecutionResult tradeRes = operationService.executeTrade(
                        portfolioId, ticker, "buy", BigDecimal.valueOf(units),
                        rawOpen, commission, tradeKey, "PAPER"
                );

                // Record paper execution result
                jdbcTemplate.update(
                        "INSERT INTO paper_execution_results (id, intent_id, proposal_id, operation_id, execution_id, listing_id, " +
                                "side, requested_quantity, executed_quantity, raw_open_price, fill_price, commission, " +
                                "spread_slippage_cost, cost_basis, realized_gain, dataset_id, dataset_checksum, " +
                                "market_effective_instant, observed_instant, booked_instant) " +
                                "VALUES (?, ?, ?, ?, ?, ?, 'BUY', ?, ?, ?, ?, ?, '0.00', ?, '0.00', ?, ?, ?, ?, ?)",
                        "p-res-" + UUID.randomUUID(), intentId, proposalId, tradeRes.operationId(), tradeRes.executionId(), lid,
                        String.valueOf(units), String.valueOf(units), rawOpen.toPlainString(), rawOpen.toPlainString(),
                        commission.toPlainString(), rawOpen.multiply(BigDecimal.valueOf(units)).add(commission).toPlainString(),
                        datasetId, checksum, openInstant, now, now
                );

                cash = new BigDecimal(tradeRes.remainingCash());
            }
        }

        // Mark intent EXECUTED
        jdbcTemplate.update("UPDATE paper_execution_intents SET status = 'EXECUTED' WHERE id = ?", intentId);

        // Record valuation
        String currentCash = jdbcTemplate.queryForObject(
                "SELECT cash_amount FROM portfolio_state WHERE portfolio_id = ?",
                String.class,
                portfolioId
        );
        jdbcTemplate.update(
                "INSERT INTO paper_valuations (id, portfolio_id, session_date, observation_kind, observation_instant, " +
                        "cash_balance, positions_market_value, receivables_value, total_equity, cumulative_return, " +
                        "high_water_mark, drawdown, data_readiness_status, is_complete, adopted_dataset_id, adopted_dataset_checksum) " +
                        "VALUES (?, ?, ?, 'SESSION_CLOSE', ?, ?, '0.00', '0.00', ?, '0.000000', ?, '0.000000', 'COMPLETE', 1, ?, ?)",
                "val-" + UUID.randomUUID(), portfolioId, scheduledDate, now,
                currentCash, currentCash, currentCash, datasetId, checksum
        );
    }

    // -------------------------------------------------------------
    // 5. Approval Mode Changes
    // -------------------------------------------------------------

    public void changeApprovalMode(String portfolioId, String ownerId, String idempotencyKey, ResearchDtos.ChangeApprovalModeRequest request) {
        String uid = normalizeUser(ownerId);
        validatePortfolioOwnership(portfolioId, uid);

        String newMode = request.approvalMode().trim().toUpperCase();
        if (!"MANUAL".equals(newMode) && !"AUTO_PAPER".equals(newMode)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid approval mode: " + newMode);
        }

        String currentMode = jdbcTemplate.queryForObject(
                "SELECT approval_mode FROM paper_portfolio_segments WHERE portfolio_id = ? AND status = 'ACTIVE'",
                String.class,
                portfolioId
        );
        if (currentMode == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No active tracking segment");
        }

        if (currentMode.equals(newMode)) {
            return;
        }

        String now = clock.instant().toString();
        transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.update(
                    "UPDATE paper_portfolio_segments SET approval_mode = ? WHERE portfolio_id = ? AND status = 'ACTIVE'",
                    newMode, portfolioId
            );
            jdbcTemplate.update(
                    "INSERT INTO paper_mode_history (id, portfolio_id, from_mode, to_mode, transition_instant, trigger_type, notes) " +
                            "VALUES (?, ?, ?, ?, ?, 'USER', ?)",
                    "mode-" + UUID.randomUUID(), portfolioId, currentMode, newMode, now, request.notes()
            );
        });
    }

    public List<ResearchDtos.PaperModeHistoryDto> getModeHistory(String portfolioId, String ownerId) {
        String uid = normalizeUser(ownerId);
        validatePortfolioOwnership(portfolioId, uid);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, portfolio_id, from_mode, to_mode, transition_instant, trigger_type, notes FROM paper_mode_history WHERE portfolio_id = ? ORDER BY transition_instant DESC",
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

    // -------------------------------------------------------------
    // 6. Proposal Reads & Queries
    // -------------------------------------------------------------

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

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, portfolio_id, cycle_id, strategy_id, strategy_version, dataset_id, dataset_checksum, " +
                        "calendar_id, calendar_version, evaluation_session_date, input_cutoff_instant, evaluation_instant, " +
                        "scheduled_open_session_date, scheduled_open_instant, reason_code, portfolio_state_version, " +
                        "status, accepted_at, rejected_at, rejection_reason, superseding_proposal_id, created_at " +
                        "FROM paper_proposals WHERE portfolio_id = ? ORDER BY created_at DESC LIMIT ? OFFSET ?",
                portfolioId, limit, offset
        );

        List<ResearchDtos.PaperProposalDto> dtos = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            dtos.add(toProposalDto(r));
        }

        return new ResearchDtos.PagedResponse<>(dtos, totalCount, limit, offset, (offset + dtos.size()) >= totalCount);
    }

    public ResearchDtos.PaperProposalDto getProposal(String portfolioId, String proposalId, String ownerId) {
        String uid = normalizeUser(ownerId);
        validatePortfolioOwnership(portfolioId, uid);
        Map<String, Object> r = getProposalRow(portfolioId, proposalId);
        return toProposalDto(r);
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

    // -------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------

    private ResearchDtos.PaperProposalDto toProposalDto(Map<String, Object> r) {
        String proposalId = (String) r.get("id");

        List<Map<String, Object>> itemRows = jdbcTemplate.queryForList(
                "SELECT id, listing_id, rank, target_weight, desired_units, score, reason_code, reason_description, raw_price_reference " +
                        "FROM paper_proposal_items WHERE proposal_id = ? ORDER BY rank ASC",
                proposalId
        );
        List<ResearchDtos.PaperProposalItemDto> items = new ArrayList<>();
        for (Map<String, Object> ir : itemRows) {
            items.add(new ResearchDtos.PaperProposalItemDto(
                    (String) ir.get("id"),
                    proposalId,
                    (String) ir.get("listing_id"),
                    ((Number) ir.get("rank")).intValue(),
                    (String) ir.get("target_weight"),
                    (String) ir.get("desired_units"),
                    (String) ir.get("score"),
                    (String) ir.get("reason_code"),
                    (String) ir.get("reason_description"),
                    (String) ir.get("raw_price_reference")
            ));
        }

        List<Map<String, Object>> obsRows = jdbcTemplate.queryForList(
                "SELECT id, listing_id, observation_session_date, observation_type, observation_value, observed_at " +
                        "FROM paper_proposal_observations WHERE proposal_id = ?",
                proposalId
        );
        List<ResearchDtos.PaperProposalObservationDto> observations = new ArrayList<>();
        for (Map<String, Object> obr : obsRows) {
            observations.add(new ResearchDtos.PaperProposalObservationDto(
                    (String) obr.get("id"),
                    proposalId,
                    (String) obr.get("listing_id"),
                    (String) obr.get("observation_session_date"),
                    (String) obr.get("observation_type"),
                    (String) obr.get("observation_value"),
                    (String) obr.get("observed_at")
            ));
        }

        return new ResearchDtos.PaperProposalDto(
                proposalId,
                (String) r.get("portfolio_id"),
                (String) r.get("cycle_id"),
                (String) r.get("strategy_id"),
                (String) r.get("strategy_version"),
                (String) r.get("dataset_id"),
                (String) r.get("dataset_checksum"),
                (String) r.get("calendar_id"),
                (String) r.get("calendar_version"),
                (String) r.get("evaluation_session_date"),
                (String) r.get("input_cutoff_instant"),
                (String) r.get("evaluation_instant"),
                (String) r.get("scheduled_open_session_date"),
                (String) r.get("scheduled_open_instant"),
                (String) r.get("reason_code"),
                ((Number) r.get("portfolio_state_version")).intValue(),
                (String) r.get("status"),
                (String) r.get("accepted_at"),
                (String) r.get("rejected_at"),
                (String) r.get("rejection_reason"),
                (String) r.get("superseding_proposal_id"),
                (String) r.get("created_at"),
                items,
                observations
        );
    }

    private Map<String, Object> getProposalRow(String portfolioId, String proposalId) {
        try {
            return jdbcTemplate.queryForMap(
                    "SELECT id, portfolio_id, cycle_id, strategy_id, strategy_version, dataset_id, dataset_checksum, " +
                            "calendar_id, calendar_version, evaluation_session_date, input_cutoff_instant, evaluation_instant, " +
                            "scheduled_open_session_date, scheduled_open_instant, reason_code, portfolio_state_version, " +
                            "status, accepted_at, rejected_at, rejection_reason, superseding_proposal_id, created_at " +
                            "FROM paper_proposals WHERE portfolio_id = ? AND id = ?",
                    portfolioId, proposalId
            );
        } catch (EmptyResultDataAccessException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Proposal not found: " + proposalId);
        }
    }

    private void validatePortfolioOwnership(String portfolioId, String ownerId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM portfolios WHERE id = ? AND owner_id = ? AND mode = 'PAPER'",
                Integer.class,
                portfolioId, ownerId
        );
        if (count == null || count == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Paper portfolio not found");
        }
    }

    private String normalizeUser(String ownerId) {
        return (ownerId == null || ownerId.isBlank()) ? "default" : ownerId.trim();
    }
}
