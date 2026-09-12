package com.bergnerd.signalforge.app.research.backtest;

import com.bergnerd.signalforge.app.TemporarySqliteInitializer;
import com.bergnerd.signalforge.app.research.historical.HistoricalDtos;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ContextConfiguration(initializers = TemporarySqliteInitializer.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BacktestIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private BacktestJobService jobService;

    @Autowired
    private ObjectMapper objectMapper;

    private byte[] loadFixture(String name) throws IOException {
        Path path = Path.of("../test/fixtures/historical/" + name);
        if (!Files.exists(path)) {
            path = Path.of("test/fixtures/historical/" + name);
        }
        return Files.readAllBytes(path);
    }

    private String importBundle(String fixtureName) throws Exception {
        byte[] bytes = loadFixture(fixtureName);
        MockMultipartFile file = new MockMultipartFile("file", fixtureName, "application/zip", bytes);
        String importKey = "import-m3-" + UUID.randomUUID();

        MvcResult uploadRes = mockMvc.perform(multipart("/api/research/imports")
                        .file(file)
                        .header("Idempotency-Key", importKey)
                        .header("Origin", "http://localhost:4200"))
                .andExpect(status().isAccepted())
                .andReturn();

        HistoricalDtos.ImportJobResponse jobResp = objectMapper.readValue(
                uploadRes.getResponse().getContentAsString(), HistoricalDtos.ImportJobResponse.class
        );

        String jobId = jobResp.id();
        String datasetId = null;

        // Poll until completed
        for (int i = 0; i < 40; i++) {
            Thread.sleep(100);
            MvcResult pollRes = mockMvc.perform(get("/api/research/jobs/" + jobId)).andReturn();
            HistoricalDtos.ImportJobResponse polled = objectMapper.readValue(
                    pollRes.getResponse().getContentAsString(), HistoricalDtos.ImportJobResponse.class
            );
            if ("COMPLETED".equals(polled.status())) {
                datasetId = polled.datasetId();
                break;
            } else if ("FAILED".equals(polled.status())) {
                throw new IllegalStateException("Import job failed: " + polled.errorDetail());
            }
        }

        assertNotNull(datasetId, "Dataset import must complete within timeout");
        return datasetId;
    }

    @Test
    @DisplayName("End-to-End M3 Reference Scenario Execution, Hand-Calculation Reconciliation & Export")
    void testEndToEndM3ReferenceScenarioExecutionAndExport() throws Exception {
        String datasetId = importBundle("m3-reference-baseline.zip");

        // Prepare Backtest request matching reference scenario
        BacktestDtos.CreateBacktestRequest request = new BacktestDtos.CreateBacktestRequest(
                datasetId,
                "listing-eur-syn-1",
                "listing-eur-syn-1",
                "2024-01-31T23:59:59Z",
                "2024-01-31",
                "2024-02-07",
                "1000.00",
                "EUR",
                "1.00",
                "0",
                "0",
                "ETF_BUY_HOLD_V1",
                "1.0.0"
        );

        String runKey = "backtest-ref-" + UUID.randomUUID();

        MvcResult createResult = mockMvc.perform(post("/api/research/backtests")
                        .header("Idempotency-Key", runKey)
                        .header("Origin", "http://localhost:4200")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andReturn();

        BacktestDtos.BacktestSummaryResponse initialSummary = objectMapper.readValue(
                createResult.getResponse().getContentAsString(), BacktestDtos.BacktestSummaryResponse.class
        );
        String runId = initialSummary.id();
        assertNotNull(runId);

        // Poll for completion
        BacktestDtos.BacktestSummaryResponse finalSummary = null;
        for (int i = 0; i < 40; i++) {
            Thread.sleep(100);
            MvcResult pollRes = mockMvc.perform(get("/api/research/backtests/" + runId)).andReturn();
            BacktestDtos.BacktestSummaryResponse polled = objectMapper.readValue(
                    pollRes.getResponse().getContentAsString(), BacktestDtos.BacktestSummaryResponse.class
            );
            if ("COMPLETED".equals(polled.status())) {
                finalSummary = polled;
                break;
            } else if ("FAILED".equals(polled.status())) {
                fail("Backtest run failed: " + polled.failureReason());
            }
        }

        assertNotNull(finalSummary, "Backtest must complete within timeout");
        assertEquals("COMPLETED", finalSummary.status());
        assertEquals(100, finalSummary.progressPct());

        // 1. Reconcile against independent hand calculation in planning/docs/backtest-baseline.md
        BacktestDtos.BacktestAnalyticsSummary candidate = finalSummary.candidateSummary();
        assertNotNull(candidate, "Candidate summary must be present");
        assertEquals("1000.00", candidate.initialEquity());
        assertEquals("1018.00", candidate.finalEquity());
        assertEquals(0.018, candidate.cumulativeReturn(), 0.0001);
        assertEquals("18.00", candidate.endingCash());
        assertEquals("0.00", candidate.endingReceivables());
        assertEquals("1000.00", candidate.endingHoldingsValue());
        assertEquals("1000.00", candidate.endingCostBasis());
        assertEquals("20.00000000", candidate.endingUnits());
        assertEquals("2.00", candidate.totalCommissions());
        assertEquals(2, candidate.fillCount());
        assertEquals(-0.001, candidate.maxDrawdown(), 0.0001);
        assertEquals("2024-02-01", candidate.peakDate());
        assertEquals("2024-02-01", candidate.troughDate());
        assertEquals("2024-02-07", candidate.recoveryDate());
        assertTrue(candidate.isRecovered());

        // 2. Same-listing benchmark must match candidate 1:1
        BacktestDtos.BacktestAnalyticsSummary benchmark = finalSummary.benchmarkSummary();
        assertNotNull(benchmark, "Benchmark summary must be present");
        assertEquals(candidate.finalEquity(), benchmark.finalEquity());
        assertEquals(candidate.cumulativeReturn(), benchmark.cumulativeReturn());
        assertEquals(0.0, candidate.benchmarkDifference(), 0.00001);

        // 3. Test Idempotency Replay
        MvcResult replayResult = mockMvc.perform(post("/api/research/backtests")
                        .header("Idempotency-Key", runKey)
                        .header("Origin", "http://localhost:4200")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn();

        BacktestDtos.BacktestSummaryResponse replayed = objectMapper.readValue(
                replayResult.getResponse().getContentAsString(), BacktestDtos.BacktestSummaryResponse.class
        );
        assertEquals(runId, replayed.id());
        assertEquals("COMPLETED", replayed.status());

        // 4. Test Idempotency Conflict with changed parameters
        BacktestDtos.CreateBacktestRequest conflictingReq = new BacktestDtos.CreateBacktestRequest(
                datasetId, "listing-eur-syn-1", "listing-eur-syn-1", "2024-01-31T23:59:59Z",
                "2024-01-31", "2024-02-07", "5000.00", "EUR", "1.00", "0", "0",
                "ETF_BUY_HOLD_V1", "1.0.0"
        );

        mockMvc.perform(post("/api/research/backtests")
                        .header("Idempotency-Key", runKey)
                        .header("Origin", "http://localhost:4200")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(conflictingReq)))
                .andExpect(status().isConflict());

        // 5. Query daily equity timeseries endpoint
        MvcResult eqRes = mockMvc.perform(get("/api/research/backtests/" + runId + "/equity?series=CANDIDATE&limit=10"))
                .andExpect(status().isOk())
                .andReturn();
        BacktestDtos.PagedResponse<BacktestDtos.DailyEquityPoint> eqPaged = objectMapper.readValue(
                eqRes.getResponse().getContentAsString(),
                objectMapper.getTypeFactory().constructParametricType(BacktestDtos.PagedResponse.class, BacktestDtos.DailyEquityPoint.class)
        );
        assertEquals(5, eqPaged.total());
        assertEquals("1018.00", eqPaged.items().get(4).totalEquity());

        // 6. Test Export ZIP endpoint
        MvcResult exportRes = mockMvc.perform(get("/api/research/backtests/" + runId + "/export"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"backtest-" + runId + "-export.zip\""))
                .andExpect(content().contentType("application/zip"))
                .andReturn();

        byte[] zipBytes = exportRes.getResponse().getContentAsByteArray();
        assertNotNull(zipBytes);
        assertTrue(zipBytes.length > 0);

        Map<String, String> zipContents = new HashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                byte[] entryBytes = zis.readAllBytes();
                zipContents.put(entry.getName(), new String(entryBytes, StandardCharsets.UTF_8));
                zis.closeEntry();
            }
        }

        assertTrue(zipContents.containsKey("manifest.json"));
        assertTrue(zipContents.containsKey("summary.json"));
        assertTrue(zipContents.containsKey("equity_series.csv"));
        assertTrue(zipContents.containsKey("events.csv"));
        assertTrue(zipContents.containsKey("orders.csv"));
        assertTrue(zipContents.containsKey("holdings.csv"));

        assertTrue(zipContents.get("manifest.json").contains("MARK_TO_MARKET_EXCLUDES_HYPOTHETICAL_LIQUIDATION_COSTS"));
        assertTrue(zipContents.get("summary.json").contains("1018.00"));
        assertTrue(zipContents.get("equity_series.csv").contains("2024-02-07,18.00,1000.00,0.00,1018.00"));

        // 7. Verify SQLite Immutability Triggers block any update/delete on COMPLETED run
        assertThrows(Exception.class, () ->
                jdbcTemplate.update("UPDATE backtest_runs SET initial_cash = '9999.00' WHERE id = ?", runId)
        );
        assertThrows(Exception.class, () ->
                jdbcTemplate.update("DELETE FROM backtest_daily_equity WHERE run_id = ?", runId)
        );

        // 8. Verify M1b legacy portfolio accounts remain 100% untouched
        Double legacyCash = jdbcTemplate.queryForObject(
                "SELECT cash_balance FROM users_profile WHERE id = 'default'", Double.class
        );
        assertEquals(10000.0, legacyCash);
    }

    @Test
    @DisplayName("Lookahead rejection: Corporate action not available until after session open fails run")
    void testLookaheadRejectionFailsRun() throws Exception {
        String datasetId = importBundle("m3-lookahead-action.zip");

        BacktestDtos.CreateBacktestRequest request = new BacktestDtos.CreateBacktestRequest(
                datasetId, "listing-eur-syn-1", "listing-eur-syn-1", "2024-01-31T23:59:59Z",
                "2024-01-31", "2024-02-07", "1000.00", "EUR", "1.00", "0", "0",
                "ETF_BUY_HOLD_V1", "1.0.0"
        );

        String runKey = "backtest-lookahead-" + UUID.randomUUID();

        MvcResult createResult = mockMvc.perform(post("/api/research/backtests")
                        .header("Idempotency-Key", runKey)
                        .header("Origin", "http://localhost:4200")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andReturn();

        BacktestDtos.BacktestSummaryResponse initialSummary = objectMapper.readValue(
                createResult.getResponse().getContentAsString(), BacktestDtos.BacktestSummaryResponse.class
        );
        String runId = initialSummary.id();

        // Poll for FAILED status
        BacktestDtos.BacktestSummaryResponse finalSummary = null;
        for (int i = 0; i < 40; i++) {
            Thread.sleep(100);
            MvcResult pollRes = mockMvc.perform(get("/api/research/backtests/" + runId)).andReturn();
            BacktestDtos.BacktestSummaryResponse polled = objectMapper.readValue(
                    pollRes.getResponse().getContentAsString(), BacktestDtos.BacktestSummaryResponse.class
            );
            if ("FAILED".equals(polled.status())) {
                finalSummary = polled;
                break;
            }
        }

        assertNotNull(finalSummary, "Run must fail due to lookahead availability violation");
        assertTrue(finalSummary.failureReason().contains("lookahead unavailable"));
    }

    @Test
    @DisplayName("Restart recovery: Unfinished runs in QUEUED or RUNNING are marked INTERRUPTED")
    void testRestartRecoveryTransitionsUnfinishedJobs() {
        String dummyDsId = "ds-recovery-" + UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO datasets (id, name, source, classification, schema_version, parser_version, " +
                        "input_checksum, content_checksum, manifest_json, coverage_start, coverage_end, " +
                        "validation_status, validation_findings_json, quality_label, imported_at, created_at) " +
                        "VALUES (?, 'Recovery DS', 'TEST', 'SYNTHETIC', '1.0.0', '1.0.0', 'chk1', 'chk2', '{}', " +
                        "'2024-01-01', '2024-02-01', 'VALID', '[]', 'SYNTHETIC', '2026-09-12T00:00:00Z', '2026-09-12T00:00:00Z')",
                dummyDsId
        );

        String testRunId = "run-interrupted-test-" + UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO backtest_runs (id, owner_id, idempotency_key, canonical_hash, strategy_id, strategy_version, " +
                        "dataset_id, candidate_listing_id, benchmark_listing_id, initial_cash, currency, evaluation_cutoff, " +
                        "requested_start_date, requested_end_date, commission_per_fill, spread_bps, slippage_bps, status, " +
                        "progress_pct, config_json, created_at, updated_at) " +
                        "VALUES (?, 'default', 'interrupted-key', 'hash', 'ETF_BUY_HOLD_V1', '1.0.0', ?, 'l-1', 'l-1', " +
                        "'1000.00', 'EUR', '2024-01-31T23:59:59Z', '2024-01-31', '2024-02-07', '1.00', '0', '0', 'RUNNING', 50, '{}', " +
                        "'2026-09-12T00:00:00Z', '2026-09-12T00:00:00Z')",
                testRunId, dummyDsId
        );

        jobService.recoverInterruptedJobs();

        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM backtest_runs WHERE id = ?", String.class, testRunId
        );
        assertEquals("INTERRUPTED", status);
    }
}
