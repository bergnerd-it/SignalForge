package com.bergnerd.signalforge.app.research.backtest;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(BacktestController.class)
class BacktestControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private BacktestJobService jobService;

    @MockBean
    private BacktestExportService exportService;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("POST /api/research/backtests requires Idempotency-Key header")
    void testCreateBacktestRequiresIdempotencyKey() throws Exception {
        BacktestDtos.CreateBacktestRequest req = new BacktestDtos.CreateBacktestRequest(
                "ds-1", "listing-1", "listing-1", "2024-01-31T23:59:59Z",
                "2024-01-31", "2024-02-07", "1000.00", "EUR", "1.00", "10", "5",
                "ETF_BUY_HOLD_V1", "1.0.0"
        );

        mockMvc.perform(post("/api/research/backtests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/research/backtests returns 202 Accepted on new run submission")
    void testCreateBacktestAccepted() throws Exception {
        BacktestDtos.CreateBacktestRequest req = new BacktestDtos.CreateBacktestRequest(
                "ds-1", "listing-1", "listing-1", "2024-01-31T23:59:59Z",
                "2024-01-31", "2024-02-07", "1000.00", "EUR", "1.00", "10", "5",
                "ETF_BUY_HOLD_V1", "1.0.0"
        );

        BacktestDtos.BacktestSummaryResponse responseDto = new BacktestDtos.BacktestSummaryResponse(
                "run-123", "default", "key-123", "hash-123", "QUEUED", 0,
                "ETF_BUY_HOLD_V1", "1.0.0", "ds-1", "listing-1", "listing-1",
                "1000.00", "EUR", "2024-01-31T23:59:59Z", "2024-01-31", "2024-02-07",
                null, null, "1.00", "10", "5", null, null, null,
                "2026-09-12T10:00:00Z", "2026-09-12T10:00:00Z", null
        );

        when(jobService.createBacktest(eq("default"), eq("key-123"), any()))
                .thenReturn(new BacktestJobService.CreationResult(responseDto, true));

        mockMvc.perform(post("/api/research/backtests")
                        .header("Idempotency-Key", "key-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value("run-123"))
                .andExpect(jsonPath("$.status").value("QUEUED"));
    }

    @Test
    @DisplayName("POST /api/research/backtests returns 200 OK on same-intent replay")
    void testCreateBacktestReplay() throws Exception {
        BacktestDtos.CreateBacktestRequest req = new BacktestDtos.CreateBacktestRequest(
                "ds-1", "listing-1", "listing-1", "2024-01-31T23:59:59Z",
                "2024-01-31", "2024-02-07", "1000.00", "EUR", "1.00", "10", "5",
                "ETF_BUY_HOLD_V1", "1.0.0"
        );

        BacktestDtos.BacktestSummaryResponse responseDto = new BacktestDtos.BacktestSummaryResponse(
                "run-123", "default", "key-123", "hash-123", "COMPLETED", 100,
                "ETF_BUY_HOLD_V1", "1.0.0", "ds-1", "listing-1", "listing-1",
                "1000.00", "EUR", "2024-01-31T23:59:59Z", "2024-01-31", "2024-02-07",
                "2024-02-01", "2024-02-07", "1.00", "10", "5", null, null, null,
                "2026-09-12T10:00:00Z", "2026-09-12T10:01:00Z", "2026-09-12T10:01:00Z"
        );

        when(jobService.createBacktest(eq("default"), eq("key-123"), any()))
                .thenReturn(new BacktestJobService.CreationResult(responseDto, false));

        mockMvc.perform(post("/api/research/backtests")
                        .header("Idempotency-Key", "key-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("run-123"))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    @DisplayName("POST /api/research/backtests returns 409 Conflict when key has conflicting config")
    void testCreateBacktestConflict() throws Exception {
        BacktestDtos.CreateBacktestRequest req = new BacktestDtos.CreateBacktestRequest(
                "ds-1", "listing-1", "listing-1", "2024-01-31T23:59:59Z",
                "2024-01-31", "2024-02-07", "1000.00", "EUR", "1.00", "10", "5",
                "ETF_BUY_HOLD_V1", "1.0.0"
        );

        when(jobService.createBacktest(eq("default"), eq("key-123"), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "Conflicting key"));

        mockMvc.perform(post("/api/research/backtests")
                        .header("Idempotency-Key", "key-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("GET /api/research/backtests returns paged runs")
    void testListBacktests() throws Exception {
        BacktestDtos.PagedResponse<BacktestDtos.BacktestSummaryResponse> paged = new BacktestDtos.PagedResponse<>(
                List.of(), 0, 50, 0, false
        );
        when(jobService.listBacktests(eq("default"), eq(50), eq(0))).thenReturn(paged);

        mockMvc.perform(get("/api/research/backtests"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.limit").value(50));
    }

    @Test
    @DisplayName("GET /api/research/backtests/{id} returns detail")
    void testGetBacktest() throws Exception {
        BacktestDtos.BacktestSummaryResponse responseDto = new BacktestDtos.BacktestSummaryResponse(
                "run-123", "default", "key-123", "hash-123", "COMPLETED", 100,
                "ETF_BUY_HOLD_V1", "1.0.0", "ds-1", "listing-1", "listing-1",
                "1000.00", "EUR", "2024-01-31T23:59:59Z", "2024-01-31", "2024-02-07",
                "2024-02-01", "2024-02-07", "1.00", "10", "5", null, null, null,
                "2026-09-12T10:00:00Z", "2026-09-12T10:01:00Z", "2026-09-12T10:01:00Z"
        );
        when(jobService.getBacktestDetail("run-123")).thenReturn(responseDto);

        mockMvc.perform(get("/api/research/backtests/run-123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("run-123"))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    @DisplayName("POST /api/research/backtests/{id}/cancel returns cancelled run")
    void testCancelBacktest() throws Exception {
        BacktestDtos.BacktestSummaryResponse responseDto = new BacktestDtos.BacktestSummaryResponse(
                "run-123", "default", "key-123", "hash-123", "CANCELLED", 0,
                "ETF_BUY_HOLD_V1", "1.0.0", "ds-1", "listing-1", "listing-1",
                "1000.00", "EUR", "2024-01-31T23:59:59Z", "2024-01-31", "2024-02-07",
                null, null, "1.00", "10", "5", null, null, null,
                "2026-09-12T10:00:00Z", "2026-09-12T10:01:00Z", null
        );
        when(jobService.cancelBacktest("run-123")).thenReturn(responseDto);

        mockMvc.perform(post("/api/research/backtests/run-123/cancel")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    @DisplayName("GET /api/research/backtests/{id}/export streams zip archive")
    void testExportBacktest() throws Exception {
        byte[] dummyZip = new byte[]{0x50, 0x4b, 0x03, 0x04}; // PK zip magic
        when(exportService.generateExportZip("run-123")).thenReturn(dummyZip);

        mockMvc.perform(get("/api/research/backtests/run-123/export"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"backtest-run-123-export.zip\""))
                .andExpect(content().contentType("application/zip"));
    }
}
