package com.bergnerd.signalforge.app.research.assistant;

import com.bergnerd.signalforge.app.chat.LlmClient;
import com.bergnerd.signalforge.app.research.backtest.ExperimentService;
import com.bergnerd.signalforge.app.research.assistant.ResearchAssistantDtos.ChatRequest;
import com.bergnerd.signalforge.app.research.assistant.ResearchAssistantDtos.ResearchContextDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ResearchAssistantServiceTest {

    @Mock JdbcTemplate jdbcTemplate;
    @Mock ExperimentService experimentService;
    @Mock LlmClient llmClient;

    @Test
    void holdoutExposureFailureStopsAssistantBeforeModelCall() {
        when(jdbcTemplate.queryForList(contains("FROM chat_requests"), eq("owner"), eq("chat-key")))
                .thenReturn(List.of());
        when(jdbcTemplate.queryForList(contains("FROM backtest_runs"), eq("run-1"), eq("owner")))
                .thenReturn(List.of(Map.of(
                        "id", "run-1", "owner_id", "owner", "strategy_id", "ETF_BUY_HOLD_V1",
                        "strategy_version", "1.0.0", "experiment_id", "experiment-1",
                        "status", "COMPLETED", "created_at", "2026-09-16T00:00:00Z")));
        doThrow(new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Exposure write failed"))
                .when(experimentService).recordExposure(
                        eq("experiment-1"), eq("run-1"), eq("ASSISTANT_TOOL_READ"), eq("owner"), anyString());
        ResearchAssistantService service = new ResearchAssistantService(
                jdbcTemplate, experimentService, llmClient,
                Clock.fixed(Instant.parse("2026-09-16T10:00:00Z"), ZoneOffset.UTC), new ObjectMapper());

        assertThrows(ResponseStatusException.class, () -> service.processQuery(
                "owner", "chat-key", new ChatRequest("Summarize this run",
                        new ResearchContextDto("RUN", "run-1"))));
        verifyNoInteractions(llmClient);
        verify(jdbcTemplate).update(contains("status = 'FAILED_VALIDATION'"), anyString(), anyString());
    }

    @Test
    void modelFailureFallsBackToGroundedPortfolioResponseWithoutFinancialMutation() {
        when(jdbcTemplate.queryForList(contains("FROM chat_requests"), eq("owner"), eq("chat-key")))
                .thenReturn(List.of());
        when(jdbcTemplate.queryForList(contains("FROM portfolios"), eq("portfolio-1"), eq("owner")))
                .thenReturn(List.of(Map.of(
                        "id", "portfolio-1", "owner_id", "owner", "name", "Paper",
                        "mode", "PAPER", "base_currency", "EUR", "initial_cash", "1000.00",
                        "created_at", "2026-09-16T00:00:00Z", "paper_started_at", "2026-09-16T00:00:00Z")));
        when(jdbcTemplate.queryForObject(contains("FROM portfolio_state"), eq(String.class), eq("portfolio-1")))
                .thenReturn("1000.00");
        when(jdbcTemplate.queryForList(contains("paper_portfolio_segments"), eq("portfolio-1")))
                .thenReturn(List.of(Map.of(
                        "strategy_id", "ETF_BUY_HOLD_V1", "approval_mode", "MANUAL",
                        "status", "ACTIVE", "adopted_dataset_id", "dataset-1")));
        when(jdbcTemplate.queryForList(contains("FROM positions"), eq("portfolio-1"))).thenReturn(List.of());
        when(jdbcTemplate.queryForList(contains("FROM paper_receivables"), eq("portfolio-1"))).thenReturn(List.of());
        when(jdbcTemplate.queryForList(contains("FROM paper_execution_intents"), eq("portfolio-1"))).thenReturn(List.of());
        when(jdbcTemplate.queryForList(contains("FROM paper_valuations"), eq("portfolio-1"))).thenReturn(List.of());
        when(llmClient.generateResponse(anyString(), anyList(), anyString()))
                .thenThrow(new IllegalStateException("provider unavailable"));
        ResearchAssistantService service = new ResearchAssistantService(
                jdbcTemplate, experimentService, llmClient,
                Clock.fixed(Instant.parse("2026-09-16T10:00:00Z"), ZoneOffset.UTC), new ObjectMapper());

        var response = service.processQuery("owner", "chat-key", new ChatRequest(
                "Invent a trade", new ResearchContextDto("PORTFOLIO", "portfolio-1")));

        assertTrue(response.message().contains("EUR 1000.00 cash"));
        verify(jdbcTemplate, never()).update(contains("positions"), any(Object[].class));
        verify(jdbcTemplate, never()).update(contains("paper_proposals"), any(Object[].class));
        verify(jdbcTemplate, never()).update(contains("paper_execution_intents"), any(Object[].class));
        verify(jdbcTemplate).update(contains("status = 'COMPLETED'"), any(), any(), any(), any());
    }
}
