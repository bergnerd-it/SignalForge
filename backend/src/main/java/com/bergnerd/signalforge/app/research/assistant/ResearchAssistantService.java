package com.bergnerd.signalforge.app.research.assistant;

import com.bergnerd.signalforge.app.chat.ChatMessageRecord;
import com.bergnerd.signalforge.app.chat.LlmClient;
import com.bergnerd.signalforge.app.chat.LlmStructuredResponse;
import com.bergnerd.signalforge.app.operation.IdempotencyExceptions;
import com.bergnerd.signalforge.app.research.backtest.ExperimentService;
import com.bergnerd.signalforge.app.research.paper.PaperMutationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ResearchAssistantService {

    private final JdbcTemplate jdbcTemplate;
    private final ExperimentService experimentService;
    private final LlmClient llmClient;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public ResearchAssistantDtos.ChatResponse processQuery(
            String ownerId,
            String idempotencyKey,
            ResearchAssistantDtos.ChatRequest request
    ) {
        String uid = (ownerId == null || ownerId.isBlank()) ? "default" : ownerId.trim();
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IdempotencyExceptions.MissingIdempotencyKeyException(
                    "Idempotency key is required for research assistant requests");
        }
        String key = idempotencyKey.trim();
        String contextType = request.context() == null ? "NONE" : String.valueOf(request.context().contextType()).trim().toUpperCase();
        String contextId = request.context() == null ? "NONE" : String.valueOf(request.context().contextId()).trim();
        String payloadHash = PaperMutationService.computeHash(
                uid + "\n" + contextType + "\n" + contextId + "\n" + request.message().trim());

        List<Map<String, Object>> stored = jdbcTemplate.queryForList(
                "SELECT id, payload_hash, status, response_json FROM chat_requests WHERE user_id = ? AND idempotency_key = ?",
                uid, key);
        if (!stored.isEmpty()) {
            Map<String, Object> row = stored.get(0);
            if (!payloadHash.equals(row.get("payload_hash"))) {
                throw new IdempotencyExceptions.IdempotencyConflictException(
                        "Conflicting reuse of research assistant idempotency key: " + key);
            }
            if ("COMPLETED".equals(row.get("status"))) {
                return deserializeResponse((String) row.get("response_json"));
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Research assistant request is already in progress");
        }

        String requestId = "research-chat-" + UUID.randomUUID();
        String now = clock.instant().toString();
        jdbcTemplate.update(
                "INSERT INTO chat_requests (id, user_id, idempotency_key, payload_hash, user_message, status, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, 'INFERENCE_PENDING', ?, ?)",
                requestId, uid, key, payloadHash, request.message().trim(), now, now);
        try {
            ResearchAssistantDtos.ChatResponse response = buildResponse(uid, request);
            String json = objectMapper.writeValueAsString(response);
            jdbcTemplate.update(
                    "UPDATE chat_requests SET assistant_message = ?, response_json = ?, status = 'COMPLETED', updated_at = ? WHERE id = ?",
                    response.message(), json, clock.instant().toString(), requestId);
            return response;
        } catch (RuntimeException exception) {
            jdbcTemplate.update(
                    "UPDATE chat_requests SET status = 'FAILED_VALIDATION', updated_at = ? WHERE id = ?",
                    clock.instant().toString(), requestId);
            throw exception;
        } catch (Exception exception) {
            jdbcTemplate.update(
                    "UPDATE chat_requests SET status = 'FAILED_VALIDATION', updated_at = ? WHERE id = ?",
                    clock.instant().toString(), requestId);
            throw new IllegalStateException("Could not persist research assistant response", exception);
        }
    }

    private ResearchAssistantDtos.ChatResponse buildResponse(
            String uid,
            ResearchAssistantDtos.ChatRequest request
    ) {
        ResearchAssistantDtos.ResearchContextDto context = request.context();

        List<ResearchAssistantDtos.FactCardDto> factCards = new ArrayList<>();
        List<EvidenceReferenceDtoBuilder> evidenceBuilders = new ArrayList<>();
        StringBuilder explanation = new StringBuilder();

        if (context == null || context.contextType() == null || context.contextId() == null) {
            explanation.append("SignalForge Research Assistant is ready. Please select a research context (Backtest Run, Paper Portfolio, Proposal, or Comparison) to inspect grounded metrics and analysis.");
            return new ResearchAssistantDtos.ChatResponse(explanation.toString(), factCards, List.of());
        }

        String type = context.contextType().trim().toUpperCase();
        String contextId = context.contextId().trim();

        switch (type) {
            case "RUN" -> handleRunContext(contextId, uid, factCards, evidenceBuilders, explanation);
            case "PORTFOLIO" -> handlePortfolioContext(contextId, uid, factCards, evidenceBuilders, explanation);
            case "PROPOSAL" -> handleProposalContext(contextId, uid, factCards, evidenceBuilders, explanation);
            case "COMPARISON" -> handleComparisonContext(contextId, uid, factCards, evidenceBuilders, explanation);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported context type: " + type);
        }

        List<ResearchAssistantDtos.EvidenceReferenceDto> evidenceReferences = new ArrayList<>();
        for (EvidenceReferenceDtoBuilder eb : evidenceBuilders) {
            evidenceReferences.add(new ResearchAssistantDtos.EvidenceReferenceDto(
                    eb.type, eb.id, eb.observationInstant, eb.description
            ));
        }

        // Incorporate LLM if available, otherwise return grounded fact-based explanation
        String finalMessage = explanation.toString();
        try {
            if (llmClient != null) {
                String systemPrompt = "You are the SignalForge Grounded Research Assistant. Answer user questions strictly using the provided authoritative fact cards and evidence references. Never invent prices, metrics, trades, or execution results.";
                String userPrompt = "Context: " + type + " ID: " + contextId + "\n" +
                        "Authoritative facts: " + factCards + "\n" +
                        "User query: " + request.message();
                LlmStructuredResponse llmRes = llmClient.generateResponse(systemPrompt, List.of(), userPrompt);
                if (llmRes != null && llmRes.message() != null && !llmRes.message().isBlank()) {
                    finalMessage = llmRes.message() + "\n\n" + finalMessage;
                }
            }
        } catch (Exception e) {
            log.debug("LLM query fallback to deterministic response: {}", e.getMessage());
        }

        return new ResearchAssistantDtos.ChatResponse(finalMessage, factCards, evidenceReferences);
    }

    private void handleRunContext(
            String runId, String uid,
            List<ResearchAssistantDtos.FactCardDto> factCards,
            List<EvidenceReferenceDtoBuilder> evidence,
            StringBuilder explanation
    ) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, owner_id, strategy_id, strategy_version, experiment_id, status, created_at, finished_at FROM backtest_runs WHERE id = ? AND owner_id = ?",
                runId, uid
        );
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Backtest run not found or unauthorized: " + runId);
        }
        Map<String, Object> r = rows.get(0);
        String strategyId = (String) r.get("strategy_id");
        String experimentId = (String) r.get("experiment_id");
        String status = (String) r.get("status");

        if (experimentId != null && !experimentId.isBlank()) {
            experimentService.recordExposure(
                    experimentId, runId, "ASSISTANT_TOOL_READ", uid, "{\"source\":\"research_assistant\"}");
        }

        // Fetch metrics from backtest_runs or daily equity
        List<Map<String, Object>> metrics = jdbcTemplate.queryForList(
                "SELECT total_equity, cumulative_return, drawdown FROM backtest_daily_equity WHERE run_id = ? ORDER BY session_date DESC LIMIT 1",
                runId
        );
        String finalEquity = metrics.isEmpty() ? "UNAVAILABLE" : String.valueOf(metrics.get(0).get("total_equity"));
        String returnPct = metrics.isEmpty() ? "UNAVAILABLE" : String.valueOf(metrics.get(0).get("cumulative_return"));
        String drawdown = metrics.isEmpty() ? "UNAVAILABLE" : String.valueOf(metrics.get(0).get("drawdown"));

        factCards.add(new ResearchAssistantDtos.FactCardDto("Strategy", strategyId, "Evaluated strategy", "METRIC"));
        factCards.add(new ResearchAssistantDtos.FactCardDto("Final Equity",
                "UNAVAILABLE".equals(finalEquity) ? finalEquity : "EUR " + finalEquity,
                "Ending portfolio equity", "FINANCIAL"));
        factCards.add(new ResearchAssistantDtos.FactCardDto("Cumulative Return", returnPct, "Prospective run return", "METRIC"));
        factCards.add(new ResearchAssistantDtos.FactCardDto("Drawdown", drawdown, "Latest run drawdown", "METRIC"));
        factCards.add(new ResearchAssistantDtos.FactCardDto("Run Status", status, "Execution state", "STATUS"));

        evidence.add(new EvidenceReferenceDtoBuilder("BACKTEST_RUN", runId, (String) r.get("created_at"), "Authoritative backtest execution record"));

        explanation.append(String.format("Analysis for Backtest Run `%s`: Strategy `%s` reached status `%s`; final equity is %s.",
                runId, strategyId, status, "UNAVAILABLE".equals(finalEquity) ? "UNAVAILABLE" : "EUR " + finalEquity));
    }

    private void handlePortfolioContext(
            String portfolioId, String uid,
            List<ResearchAssistantDtos.FactCardDto> factCards,
            List<EvidenceReferenceDtoBuilder> evidence,
            StringBuilder explanation
    ) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, owner_id, name, mode, base_currency, initial_cash, created_at, paper_started_at FROM portfolios WHERE id = ? AND owner_id = ? AND mode = 'PAPER'",
                portfolioId, uid
        );
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Paper portfolio not found or unauthorized: " + portfolioId);
        }
        Map<String, Object> p = rows.get(0);
        String cash = jdbcTemplate.queryForObject("SELECT cash_amount FROM portfolio_state WHERE portfolio_id = ?", String.class, portfolioId);

        List<Map<String, Object>> segRows = jdbcTemplate.queryForList(
                "SELECT strategy_id, approval_mode, status, adopted_dataset_id FROM paper_portfolio_segments WHERE portfolio_id = ? AND status = 'ACTIVE'",
                portfolioId
        );
        String strategy = segRows.isEmpty() ? "NONE" : (String) segRows.get(0).get("strategy_id");
        String approvalMode = segRows.isEmpty() ? "MANUAL" : (String) segRows.get(0).get("approval_mode");

        List<Map<String, Object>> holdings = jdbcTemplate.queryForList(
                "SELECT listing_id, quantity, total_acquisition_cost, updated_at FROM positions " +
                        "WHERE portfolio_id = ? AND quantity != '0' ORDER BY listing_id LIMIT 100",
                portfolioId);
        List<Map<String, Object>> receivables = jdbcTemplate.queryForList(
                "SELECT id, listing_id, net_amount, payment_date, status, created_at FROM paper_receivables " +
                        "WHERE portfolio_id = ? ORDER BY created_at DESC LIMIT 100",
                portfolioId);
        List<Map<String, Object>> intents = jdbcTemplate.queryForList(
                "SELECT id, status, scheduled_open_instant, created_at FROM paper_execution_intents " +
                        "WHERE portfolio_id = ? AND status IN ('PENDING', 'WAITING_FOR_OBSERVATION') ORDER BY created_at LIMIT 100",
                portfolioId);
        List<Map<String, Object>> valuations = jdbcTemplate.queryForList(
                "SELECT id, total_equity, drawdown, data_readiness_status, observation_instant FROM paper_valuations " +
                        "WHERE portfolio_id = ? ORDER BY observation_instant DESC LIMIT 1",
                portfolioId);

        factCards.add(new ResearchAssistantDtos.FactCardDto("Cash Balance", "EUR " + cash, "Current available cash balance", "FINANCIAL"));
        factCards.add(new ResearchAssistantDtos.FactCardDto("Approval Mode", approvalMode, "Active order approval mode", "CONFIGURATION"));
        factCards.add(new ResearchAssistantDtos.FactCardDto("Active Strategy", strategy, "Associated strategy", "METRIC"));
        factCards.add(new ResearchAssistantDtos.FactCardDto("Holdings", String.valueOf(holdings.size()), "Current non-zero positions", "STATUS"));
        factCards.add(new ResearchAssistantDtos.FactCardDto("Pending Receivables", String.valueOf(
                receivables.stream().filter(row -> "PENDING".equals(row.get("status"))).count()),
                "Unpaid distribution entitlements", "STATUS"));
        factCards.add(new ResearchAssistantDtos.FactCardDto("Pending Intents", String.valueOf(intents.size()),
                "Accepted orders awaiting resolution", "STATUS"));
        if (valuations.isEmpty()) {
            factCards.add(new ResearchAssistantDtos.FactCardDto("Total Equity", "UNAVAILABLE", "No valuation exists", "FINANCIAL"));
        } else {
            Map<String, Object> valuation = valuations.get(0);
            factCards.add(new ResearchAssistantDtos.FactCardDto("Total Equity", String.valueOf(valuation.get("total_equity")),
                    "Latest prospective valuation", "FINANCIAL"));
            factCards.add(new ResearchAssistantDtos.FactCardDto("Readiness", String.valueOf(valuation.get("data_readiness_status")),
                    "Latest valuation data state", "STATUS"));
            evidence.add(new EvidenceReferenceDtoBuilder("PAPER_VALUATION", (String) valuation.get("id"),
                    (String) valuation.get("observation_instant"), "Latest prospective valuation"));
        }
        for (Map<String, Object> holding : holdings) {
            evidence.add(new EvidenceReferenceDtoBuilder("PAPER_HOLDING", (String) holding.get("listing_id"),
                    (String) holding.get("updated_at"), "Quantity " + holding.get("quantity") + ", basis " + holding.get("total_acquisition_cost")));
        }
        for (Map<String, Object> receivable : receivables) {
            evidence.add(new EvidenceReferenceDtoBuilder("PAPER_RECEIVABLE", (String) receivable.get("id"),
                    (String) receivable.get("created_at"), "Net " + receivable.get("net_amount") + ", status " + receivable.get("status")));
        }
        for (Map<String, Object> intent : intents) {
            evidence.add(new EvidenceReferenceDtoBuilder("PAPER_INTENT", (String) intent.get("id"),
                    (String) intent.get("created_at"), "Status " + intent.get("status") + ", open " + intent.get("scheduled_open_instant")));
        }

        evidence.add(new EvidenceReferenceDtoBuilder("PAPER_PORTFOLIO", portfolioId, (String) p.get("created_at"), "Prospective paper portfolio root"));

        explanation.append(String.format("Paper Portfolio `%s` (%s): Operating in `%s` mode under strategy `%s` with EUR %s cash.",
                portfolioId, p.get("name"), approvalMode, strategy, cash));
    }

    private void handleProposalContext(
            String proposalId, String uid,
            List<ResearchAssistantDtos.FactCardDto> factCards,
            List<EvidenceReferenceDtoBuilder> evidence,
            StringBuilder explanation
    ) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT p.id, p.portfolio_id, p.strategy_id, p.status, p.evaluation_session_date, p.scheduled_open_session_date, " +
                        "p.scheduled_open_instant, p.reason_code, p.dataset_id, p.dataset_checksum, p.created_at, s.cost_policy_json " +
                        "FROM paper_proposals p JOIN portfolios port ON p.portfolio_id = port.id " +
                        "LEFT JOIN paper_portfolio_segments s ON s.portfolio_id = p.portfolio_id AND s.status = 'ACTIVE' " +
                        "WHERE p.id = ? AND port.owner_id = ?",
                proposalId, uid
        );
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Proposal not found or unauthorized: " + proposalId);
        }
        Map<String, Object> prop = rows.get(0);
        String status = (String) prop.get("status");
        String strategyId = (String) prop.get("strategy_id");
        String schedDate = (String) prop.get("scheduled_open_session_date");
        List<Map<String, Object>> items = jdbcTemplate.queryForList(
                "SELECT listing_id, rank, target_weight, score, reason_code, raw_price_reference " +
                        "FROM paper_proposal_items WHERE proposal_id = ? ORDER BY rank LIMIT 100",
                proposalId);
        List<Map<String, Object>> observations = jdbcTemplate.queryForList(
                "SELECT id, listing_id, observation_session_date, observation_type, observation_value, observed_at " +
                        "FROM paper_proposal_observations WHERE proposal_id = ? ORDER BY observation_session_date, listing_id LIMIT 500",
                proposalId);

        factCards.add(new ResearchAssistantDtos.FactCardDto("Proposal Status", status, "Current lifecycle state", "STATUS"));
        factCards.add(new ResearchAssistantDtos.FactCardDto("Strategy", strategyId, "Evaluating strategy", "METRIC"));
        factCards.add(new ResearchAssistantDtos.FactCardDto("Scheduled Execution", schedDate, "Target market open date", "SCHEDULE"));
        factCards.add(new ResearchAssistantDtos.FactCardDto("Targets", String.valueOf(items.size()), "Immutable proposal target rows", "STATUS"));
        factCards.add(new ResearchAssistantDtos.FactCardDto("Input Observations", String.valueOf(observations.size()), "Stored cutoff evidence rows", "STATUS"));
        factCards.add(new ResearchAssistantDtos.FactCardDto("Cost Policy", String.valueOf(prop.get("cost_policy_json")),
                "Commission, spread, and slippage configuration", "CONFIGURATION"));

        evidence.add(new EvidenceReferenceDtoBuilder("PAPER_PROPOSAL", proposalId, (String) prop.get("created_at"), "Immutable strategy evaluation proposal"));
        for (Map<String, Object> item : items) {
            evidence.add(new EvidenceReferenceDtoBuilder("PAPER_PROPOSAL_ITEM", proposalId + ":" + item.get("listing_id"),
                    (String) prop.get("created_at"), "Weight " + item.get("target_weight") + ", reason " + item.get("reason_code")));
        }
        for (Map<String, Object> observation : observations) {
            evidence.add(new EvidenceReferenceDtoBuilder("PAPER_PROPOSAL_OBSERVATION", (String) observation.get("id"),
                    (String) observation.get("observed_at"), observation.get("observation_type") + "=" + observation.get("observation_value")));
        }

        explanation.append(String.format("Proposal `%s`: Generated by `%s` in status `%s`, scheduled for execution session `%s`.",
                proposalId, strategyId, status, schedDate));
    }

    private void handleComparisonContext(
            String comparisonId, String uid,
            List<ResearchAssistantDtos.FactCardDto> factCards,
            List<EvidenceReferenceDtoBuilder> evidence,
            StringBuilder explanation
    ) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, name, compatibility_status, created_at FROM backtest_comparisons WHERE id = ? AND owner_id = ?",
                comparisonId, uid
        );
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Comparison not found or unauthorized: " + comparisonId);
        }
        Map<String, Object> c = rows.get(0);
        String compat = (String) c.get("compatibility_status");
        List<Map<String, Object>> members = jdbcTemplate.queryForList(
                "SELECT i.run_id, i.role, r.strategy_id, r.status, d.total_equity, d.cumulative_return, d.drawdown, d.session_date " +
                        "FROM backtest_comparison_items i JOIN backtest_runs r ON r.id = i.run_id " +
                        "LEFT JOIN backtest_daily_equity d ON d.run_id = r.id AND d.session_date = " +
                        "(SELECT MAX(d2.session_date) FROM backtest_daily_equity d2 WHERE d2.run_id = r.id) " +
                        "WHERE i.comparison_id = ? AND r.owner_id = ? ORDER BY i.role, i.run_id LIMIT 100",
                comparisonId, uid);

        factCards.add(new ResearchAssistantDtos.FactCardDto("Comparison Status", compat, "Run comparability status", "STATUS"));
        factCards.add(new ResearchAssistantDtos.FactCardDto("Comparison Members", String.valueOf(members.size()),
                "Owner-scoped compared runs", "STATUS"));
        evidence.add(new EvidenceReferenceDtoBuilder("COMPARISON", comparisonId, (String) c.get("created_at"), "Owner-scoped strategy comparison record"));
        for (Map<String, Object> member : members) {
            evidence.add(new EvidenceReferenceDtoBuilder("COMPARISON_RUN", (String) member.get("run_id"),
                    (String) member.get("session_date"), "Role " + member.get("role") + ", return " +
                    Objects.toString(member.get("cumulative_return"), "UNAVAILABLE") + ", drawdown " +
                    Objects.toString(member.get("drawdown"), "UNAVAILABLE")));
        }

        explanation.append(String.format("Comparison `%s`: Assessed compatibility status `%s`.", comparisonId, compat));
    }

    private static class EvidenceReferenceDtoBuilder {
        final String type;
        final String id;
        final String observationInstant;
        final String description;

        EvidenceReferenceDtoBuilder(String type, String id, String observationInstant, String description) {
            this.type = type;
            this.id = id;
            this.observationInstant = observationInstant;
            this.description = description;
        }
    }

    private ResearchAssistantDtos.ChatResponse deserializeResponse(String json) {
        try {
            return objectMapper.readValue(json, ResearchAssistantDtos.ChatResponse.class);
        } catch (Exception exception) {
            throw new IllegalStateException("Stored research assistant response is invalid", exception);
        }
    }
}
