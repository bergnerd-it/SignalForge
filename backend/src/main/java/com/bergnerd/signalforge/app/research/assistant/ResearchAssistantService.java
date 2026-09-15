package com.bergnerd.signalforge.app.research.assistant;

import com.bergnerd.signalforge.app.chat.ChatMessageRecord;
import com.bergnerd.signalforge.app.chat.LlmClient;
import com.bergnerd.signalforge.app.chat.LlmStructuredResponse;
import com.bergnerd.signalforge.app.research.backtest.ExperimentService;
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

    public ResearchAssistantDtos.ChatResponse processQuery(
            String ownerId,
            String idempotencyKey,
            ResearchAssistantDtos.ChatRequest request
    ) {
        String uid = (ownerId == null || ownerId.isBlank()) ? "default" : ownerId.trim();
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

        // Record holdout exposure if run belongs to an experiment
        if (experimentId != null && !experimentId.isBlank()) {
            try {
                experimentService.recordExposure(experimentId, runId, "ASSISTANT_TOOL_READ", uid, "{\"source\":\"research_assistant\"}");
            } catch (Exception e) {
                log.debug("Exposure recording error: {}", e.getMessage());
            }
        }

        // Fetch metrics from backtest_runs or daily equity
        List<Map<String, Object>> metrics = jdbcTemplate.queryForList(
                "SELECT total_equity, cumulative_return, drawdown FROM backtest_daily_equity WHERE run_id = ? ORDER BY session_date DESC LIMIT 1",
                runId
        );
        String finalEquity = metrics.isEmpty() ? "1000.00" : String.valueOf(metrics.get(0).get("total_equity"));
        String returnPct = metrics.isEmpty() ? "0.0%" : String.valueOf(metrics.get(0).get("cumulative_return"));

        factCards.add(new ResearchAssistantDtos.FactCardDto("Strategy", strategyId, "Evaluated strategy", "METRIC"));
        factCards.add(new ResearchAssistantDtos.FactCardDto("Final Equity", "EUR " + finalEquity, "Ending portfolio equity", "FINANCIAL"));
        factCards.add(new ResearchAssistantDtos.FactCardDto("Run Status", status, "Execution state", "STATUS"));

        evidence.add(new EvidenceReferenceDtoBuilder("BACKTEST_RUN", runId, (String) r.get("created_at"), "Authoritative backtest execution record"));

        explanation.append(String.format("Analysis for Backtest Run `%s`: Strategy `%s` reached status `%s` with final equity EUR %s.", runId, strategyId, status, finalEquity));
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

        factCards.add(new ResearchAssistantDtos.FactCardDto("Cash Balance", "EUR " + cash, "Current available cash balance", "FINANCIAL"));
        factCards.add(new ResearchAssistantDtos.FactCardDto("Approval Mode", approvalMode, "Active order approval mode", "CONFIGURATION"));
        factCards.add(new ResearchAssistantDtos.FactCardDto("Active Strategy", strategy, "Associated strategy", "METRIC"));

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
                "SELECT p.id, p.portfolio_id, p.strategy_id, p.status, p.evaluation_session_date, p.scheduled_open_session_date, p.reason_code, p.created_at " +
                        "FROM paper_proposals p JOIN portfolios port ON p.portfolio_id = port.id WHERE p.id = ? AND port.owner_id = ?",
                proposalId, uid
        );
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Proposal not found or unauthorized: " + proposalId);
        }
        Map<String, Object> prop = rows.get(0);
        String status = (String) prop.get("status");
        String strategyId = (String) prop.get("strategy_id");
        String schedDate = (String) prop.get("scheduled_open_session_date");

        factCards.add(new ResearchAssistantDtos.FactCardDto("Proposal Status", status, "Current lifecycle state", "STATUS"));
        factCards.add(new ResearchAssistantDtos.FactCardDto("Strategy", strategyId, "Evaluating strategy", "METRIC"));
        factCards.add(new ResearchAssistantDtos.FactCardDto("Scheduled Execution", schedDate, "Target market open date", "SCHEDULE"));

        evidence.add(new EvidenceReferenceDtoBuilder("PAPER_PROPOSAL", proposalId, (String) prop.get("created_at"), "Immutable strategy evaluation proposal"));

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

        factCards.add(new ResearchAssistantDtos.FactCardDto("Comparison Status", compat, "Run comparability status", "STATUS"));
        evidence.add(new EvidenceReferenceDtoBuilder("COMPARISON", comparisonId, (String) c.get("created_at"), "Owner-scoped strategy comparison record"));

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
}
