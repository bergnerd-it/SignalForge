package com.bergnerd.signalforge.app.research.backtest;

import com.bergnerd.signalforge.app.TemporarySqliteInitializer;
import com.bergnerd.signalforge.app.research.historical.HistoricalDtos;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
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
    private BuildIdentityResolver buildIdentityResolver;

    @Autowired
    private BacktestExportService exportService;

    @SpyBean
    private BacktestAnalyticsCalculator analyticsSpy;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private BacktestJobService jobService;

    @Autowired
    private BacktestDataReader dataReader;

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
                .andReturn();

        int uploadStatus = uploadRes.getResponse().getStatus();
        assertTrue(uploadStatus == 200 || uploadStatus == 202,
                "Expected 200 or 202 from upload but got: " + uploadStatus);

        HistoricalDtos.ImportJobResponse jobResp = objectMapper.readValue(
                uploadRes.getResponse().getContentAsString(), HistoricalDtos.ImportJobResponse.class
        );

        if ("COMPLETED".equals(jobResp.status())) {
            return jobResp.datasetId();
        }

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

        // 5. Query daily equity timeseries endpoint (verify Day 0 initial funding point is present)
        MvcResult eqRes = mockMvc.perform(get("/api/research/backtests/" + runId + "/equity?series=CANDIDATE&limit=10"))
                .andExpect(status().isOk())
                .andReturn();
        BacktestDtos.PagedResponse<BacktestDtos.DailyEquityPoint> eqPaged = objectMapper.readValue(
                eqRes.getResponse().getContentAsString(),
                objectMapper.getTypeFactory().constructParametricType(BacktestDtos.PagedResponse.class, BacktestDtos.DailyEquityPoint.class)
        );
        assertEquals(6, eqPaged.total());
        assertEquals("2024-02-01", eqPaged.items().get(0).sessionDate());
        assertEquals("INITIAL_FUNDED", eqPaged.items().get(0).pointKind());
        assertEquals("SESSION_CLOSE", eqPaged.items().get(1).pointKind());
        assertEquals("1000.00", eqPaged.items().get(0).totalEquity());
        assertEquals("0.00", eqPaged.items().get(0).holdingsValue());
        assertEquals("1018.00", eqPaged.items().get(5).totalEquity());

        // 6. Test Export ZIP endpoint (streaming response)
        MvcResult exportAsync = mockMvc.perform(get("/api/research/backtests/" + runId + "/export"))
                .andExpect(request().asyncStarted())
                .andReturn();

        MvcResult exportRes = mockMvc.perform(asyncDispatch(exportAsync))
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

        // Manifest must be verbatim from stored configuration snapshot
        assertTrue(zipContents.get("manifest.json").contains("MARK_TO_MARKET_EXCLUDES_HYPOTHETICAL_LIQUIDATION_COSTS"));
        assertTrue(zipContents.get("manifest.json").contains("datasetInputChecksum"));
        assertTrue(zipContents.get("summary.json").contains("1018.00"));
        assertTrue(zipContents.get("equity_series.csv").contains("2024-02-07,SESSION_CLOSE,"));
        assertTrue(zipContents.get("equity_series.csv").contains(",18.00,1000.00,0.00,1018.00"));

        // Verify exact negative decimals: no formula-escaping apostrophe on numbers (HIGH-5 / CRITICAL-1)
        assertTrue(zipContents.get("equity_series.csv").contains("-0.001"));
        assertFalse(zipContents.get("equity_series.csv").contains("'-0.001"));

        org.springframework.web.server.ResponseStatusException byteBound = assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> exportService.prepareExportZip(runId, "default", 100));
        assertEquals(413, byteBound.getStatusCode().value());

        // 7. Test Cross-Owner Non-Disclosing Isolation (HIGH-2)
        mockMvc.perform(get("/api/research/backtests/" + runId).header("X-User-Id", "other-user"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/research/backtests/" + runId + "/equity").header("X-User-Id", "other-user"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/research/backtests/" + runId + "/orders").header("X-User-Id", "other-user"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/research/backtests/" + runId + "/events").header("X-User-Id", "other-user"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/research/backtests/" + runId + "/export").header("X-User-Id", "other-user"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/research/backtests/" + runId + "/cancel")
                        .header("Origin", "http://localhost:4200")
                        .header("X-User-Id", "other-user"))
                .andExpect(status().isNotFound());

        // 8. Test Series SQL Injection Prevention (HIGH-1)
        mockMvc.perform(get("/api/research/backtests/" + runId + "/equity?series=CANDIDATE' OR '1'='1"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/research/backtests/" + runId + "/orders?series=INVALID_SERIES"))
                .andExpect(status().isBadRequest());

        // 9. Verify SQLite Immutability Triggers block any update/delete on COMPLETED run
        assertThrows(Exception.class, () ->
                jdbcTemplate.update("UPDATE backtest_runs SET initial_cash = '9999.00' WHERE id = ?", runId)
        );
        assertThrows(Exception.class, () ->
                jdbcTemplate.update("DELETE FROM backtest_daily_equity WHERE run_id = ?", runId)
        );

        // 10. Verify M1b legacy portfolio accounts remain 100% untouched
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
        jdbcTemplate.update(
                "INSERT INTO dataset_listings (dataset_id, listing_id, instrument_id, symbol, quote_currency, calendar_id) " +
                        "VALUES (?, 'l-1', 'inst-1', 'SYM', 'EUR', 'CAL')",
                dummyDsId
        );

        String testRunId = "run-interrupted-test-" + UUID.randomUUID();
        String queuedRunId = "run-queued-test-" + UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO backtest_runs (id, owner_id, idempotency_key, canonical_hash, strategy_id, strategy_version, " +
                        "dataset_id, candidate_listing_id, benchmark_listing_id, initial_cash, currency, evaluation_cutoff, " +
                        "requested_start_date, requested_end_date, commission_per_fill, spread_bps, slippage_bps, status, " +
                        "progress_pct, config_json, created_at, updated_at) " +
                        "VALUES (?, 'default', 'interrupted-key-1', 'hash1', 'ETF_BUY_HOLD_V1', '1.0.0', ?, 'l-1', 'l-1', " +
                        "'1000.00', 'EUR', '2024-01-31T23:59:59Z', '2024-01-31', '2024-02-07', '1.00', '0', '0', 'RUNNING', 50, '{}', " +
                        "'2026-09-12T00:00:00Z', '2026-09-12T00:00:00Z')",
                testRunId, dummyDsId
        );
        jdbcTemplate.update(
                "INSERT INTO backtest_runs (id, owner_id, idempotency_key, canonical_hash, strategy_id, strategy_version, " +
                        "dataset_id, candidate_listing_id, benchmark_listing_id, initial_cash, currency, evaluation_cutoff, " +
                        "requested_start_date, requested_end_date, commission_per_fill, spread_bps, slippage_bps, status, " +
                        "progress_pct, config_json, created_at, updated_at) " +
                        "VALUES (?, 'default', 'interrupted-key-2', 'hash2', 'ETF_BUY_HOLD_V1', '1.0.0', ?, 'l-1', 'l-1', " +
                        "'1000.00', 'EUR', '2024-01-31T23:59:59Z', '2024-01-31', '2024-02-07', '1.00', '0', '0', 'QUEUED', 0, '{}', " +
                        "'2026-09-12T00:00:00Z', '2026-09-12T00:00:00Z')",
                queuedRunId, dummyDsId
        );

        jobService.recoverInterruptedJobs();

        String statusRunning = jdbcTemplate.queryForObject(
                "SELECT status FROM backtest_runs WHERE id = ?", String.class, testRunId
        );
        String statusQueued = jdbcTemplate.queryForObject(
                "SELECT status FROM backtest_runs WHERE id = ?", String.class, queuedRunId
        );
        assertEquals("INTERRUPTED", statusRunning);
        assertEquals("INTERRUPTED", statusQueued);
    }

    @Test
    @DisplayName("CRITICAL-1: Malicious imported listing IDs executed safely via parameterized SQL")
    void testMaliciousImportedListingIdHandledSafely() {
        String dummyDsId = "ds-injection-" + UUID.randomUUID();
        String maliciousListingId = "'; DROP TABLE backtest_runs; --";

        jdbcTemplate.update(
                "INSERT INTO datasets (id, name, source, classification, schema_version, parser_version, " +
                        "input_checksum, content_checksum, manifest_json, coverage_start, coverage_end, " +
                        "validation_status, validation_findings_json, quality_label, imported_at, created_at) " +
                        "VALUES (?, 'Injection DS', 'TEST', 'SYNTHETIC', '1.0.0', '1.0.0', 'chk1', 'chk2', '{}', " +
                        "'2024-01-01', '2024-02-01', 'VALID', '[]', 'SYNTHETIC', '2026-09-12T00:00:00Z', '2026-09-12T00:00:00Z')",
                dummyDsId
        );
        jdbcTemplate.update(
                "INSERT INTO dataset_listings (dataset_id, listing_id, instrument_id, symbol, quote_currency, calendar_id) " +
                        "VALUES (?, ?, 'inst-1', 'SYM', 'EUR', 'CAL')",
                dummyDsId, maliciousListingId
        );
        jdbcTemplate.update(
                "INSERT INTO dataset_sessions (dataset_id, calendar_id, session_date, open_time, close_time, session_type) " +
                        "VALUES (?, 'CAL', '2024-01-31', '2024-01-31T08:00:00Z', '2024-01-31T16:30:00Z', 'TRADING')",
                dummyDsId
        );
        jdbcTemplate.update(
                "INSERT INTO dataset_sessions (dataset_id, calendar_id, session_date, open_time, close_time, session_type) " +
                        "VALUES (?, 'CAL', '2024-02-01', '2024-02-01T08:00:00Z', '2024-02-01T16:30:00Z', 'TRADING')",
                dummyDsId
        );
        jdbcTemplate.update(
                "INSERT INTO historical_bars (dataset_id, listing_id, session_date, open, high, low, close, volume, available_at) " +
                        "VALUES (?, ?, '2024-01-31', '100.00', '105.00', '95.00', '102.00', '1000', '2024-01-31T16:35:00Z')",
                dummyDsId, maliciousListingId
        );
        jdbcTemplate.update(
                "INSERT INTO historical_bars (dataset_id, listing_id, session_date, open, high, low, close, volume, available_at) " +
                        "VALUES (?, ?, '2024-02-01', '102.00', '106.00', '101.00', '105.00', '1000', '2024-02-01T16:35:00Z')",
                dummyDsId, maliciousListingId
        );

        // Preflight validation with malicious listing ID should execute parameterized query safely without syntax error or drop
        assertDoesNotThrow(() ->
                dataReader.validatePreflight(dummyDsId, maliciousListingId, maliciousListingId,
                        "2024-01-31T20:00:00Z", "2024-01-31", "2024-02-01")
        );

        // Verify backtest_runs table was NOT dropped by the injection payload!
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'backtest_runs'", Integer.class);
        assertEquals(1, count, "Table backtest_runs must remain intact after malicious listing ID query");
    }

    @Test
    @DisplayName("HIGH-1: Evaluation bar availability after cutoff fails preflight validation")
    void testEvaluationBarAvailabilityAfterCutoffFailsPreflight() {
        String dummyDsId = "ds-eval-avail-" + UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO datasets (id, name, source, classification, schema_version, parser_version, " +
                        "input_checksum, content_checksum, manifest_json, coverage_start, coverage_end, " +
                        "validation_status, validation_findings_json, quality_label, imported_at, created_at) " +
                        "VALUES (?, 'Eval Avail DS', 'TEST', 'SYNTHETIC', '1.0.0', '1.0.0', 'chk1', 'chk2', '{}', " +
                        "'2024-01-01', '2024-02-01', 'VALID', '[]', 'SYNTHETIC', '2026-09-12T00:00:00Z', '2026-09-12T00:00:00Z')",
                dummyDsId
        );
        jdbcTemplate.update(
                "INSERT INTO dataset_listings (dataset_id, listing_id, instrument_id, symbol, quote_currency, calendar_id) " +
                        "VALUES (?, 'l-cand', 'inst-1', 'CAND', 'EUR', 'CAL')", dummyDsId
        );
        jdbcTemplate.update(
                "INSERT INTO dataset_listings (dataset_id, listing_id, instrument_id, symbol, quote_currency, calendar_id) " +
                        "VALUES (?, 'l-bm', 'inst-2', 'BM', 'EUR', 'CAL')", dummyDsId
        );
        jdbcTemplate.update(
                "INSERT INTO dataset_sessions (dataset_id, calendar_id, session_date, open_time, close_time, session_type) " +
                        "VALUES (?, 'CAL', '2024-01-31', '2024-01-31T08:00:00Z', '2024-01-31T16:30:00Z', 'TRADING')", dummyDsId
        );
        jdbcTemplate.update(
                "INSERT INTO dataset_sessions (dataset_id, calendar_id, session_date, open_time, close_time, session_type) " +
                        "VALUES (?, 'CAL', '2024-02-01', '2024-02-01T08:00:00Z', '2024-02-01T16:30:00Z', 'TRADING')", dummyDsId
        );
        // Bar published at 20:00Z, but cutoff is set to 18:00Z (before available_at)
        jdbcTemplate.update(
                "INSERT INTO historical_bars (dataset_id, listing_id, session_date, open, high, low, close, volume, available_at) " +
                        "VALUES (?, 'l-cand', '2024-01-31', '100.00', '105.00', '95.00', '100.00', '1000', '2024-01-31T20:00:00Z')", dummyDsId
        );
        jdbcTemplate.update(
                "INSERT INTO historical_bars (dataset_id, listing_id, session_date, open, high, low, close, volume, available_at) " +
                        "VALUES (?, 'l-bm', '2024-01-31', '100.00', '105.00', '95.00', '100.00', '1000', '2024-01-31T20:00:00Z')", dummyDsId
        );

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                dataReader.validatePreflight(dummyDsId, "l-cand", "l-bm", "2024-01-31T18:00:00Z", "2024-01-31", "2024-02-01")
        );
        assertTrue(ex.getMessage().contains("strictly after evaluation cutoff") || ex.getMessage().contains("was not available until"),
                "Expected cutoff rejection message but got: " + ex.getMessage());
    }

    @Test
    @DisplayName("HIGH-2: Split ratio precision and distribution payment timing validation")
    void testSplitPrecisionAndMissingPaymentTimingValidation() {
        // 1. Repeating decimal split ratio (1/3)
        String dsSplit = "ds-split-" + UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO datasets (id, name, source, classification, schema_version, parser_version, " +
                        "input_checksum, content_checksum, manifest_json, coverage_start, coverage_end, " +
                        "validation_status, validation_findings_json, quality_label, imported_at, created_at) " +
                        "VALUES (?, 'Split DS', 'TEST', 'SYNTHETIC', '1.0.0', '1.0.0', 'chk1', 'chk2', '{}', " +
                        "'2024-01-01', '2024-02-01', 'VALID', '[]', 'SYNTHETIC', '2026-09-12T00:00:00Z', '2026-09-12T00:00:00Z')", dsSplit
        );
        jdbcTemplate.update(
                "INSERT INTO dataset_listings (dataset_id, listing_id, instrument_id, symbol, quote_currency, calendar_id) " +
                        "VALUES (?, 'l-split', 'inst-1', 'SPL', 'EUR', 'CAL')", dsSplit
        );
        jdbcTemplate.update(
                "INSERT INTO dataset_sessions (dataset_id, calendar_id, session_date, open_time, close_time, session_type) " +
                        "VALUES (?, 'CAL', '2024-01-31', '2024-01-31T08:00:00Z', '2024-01-31T16:30:00Z', 'TRADING')", dsSplit
        );
        jdbcTemplate.update(
                "INSERT INTO dataset_sessions (dataset_id, calendar_id, session_date, open_time, close_time, session_type) " +
                        "VALUES (?, 'CAL', '2024-02-01', '2024-02-01T08:00:00Z', '2024-02-01T16:30:00Z', 'TRADING')", dsSplit
        );
        jdbcTemplate.update(
                "INSERT INTO historical_bars (dataset_id, listing_id, session_date, open, high, low, close, volume, available_at) " +
                        "VALUES (?, 'l-split', '2024-01-31', '100.00', '105.00', '95.00', '100.00', '1000', '2024-01-31T16:35:00Z')", dsSplit
        );
        jdbcTemplate.update(
                "INSERT INTO historical_bars (dataset_id, listing_id, session_date, open, high, low, close, volume, available_at) " +
                        "VALUES (?, 'l-split', '2024-02-01', '100.00', '105.00', '95.00', '100.00', '1000', '2024-02-01T16:35:00Z')", dsSplit
        );
        jdbcTemplate.update(
                "INSERT INTO historical_actions (dataset_id, action_id, listing_id, action_type, effective_date, available_at, " +
                        "split_ratio_numerator, split_ratio_denominator) " +
                        "VALUES (?, 'act-rep-split', 'l-split', 'SPLIT', '2024-02-01', '2024-02-01T07:00:00Z', '1', '3')", dsSplit
        );

        IllegalArgumentException exSplit = assertThrows(IllegalArgumentException.class, () ->
                dataReader.loadAndValidateData(dsSplit, "l-split", "l-split", "2024-01-31T23:59:59Z", "2024-01-31", "2024-02-01")
        );
        assertTrue(exSplit.getMessage().contains("Unsupported split precision for action") || exSplit.getMessage().contains("cannot be represented exactly"),
                "Expected split precision rejection but got: " + exSplit.getMessage());

        // 2. Distribution without paymentDate or paymentInstant
        String dsDist = "ds-dist-" + UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO datasets (id, name, source, classification, schema_version, parser_version, " +
                        "input_checksum, content_checksum, manifest_json, coverage_start, coverage_end, " +
                        "validation_status, validation_findings_json, quality_label, imported_at, created_at) " +
                        "VALUES (?, 'Dist DS', 'TEST', 'SYNTHETIC', '1.0.0', '1.0.0', 'chk1', 'chk2', '{}', " +
                        "'2024-01-01', '2024-02-01', 'VALID', '[]', 'SYNTHETIC', '2026-09-12T00:00:00Z', '2026-09-12T00:00:00Z')", dsDist
        );
        jdbcTemplate.update(
                "INSERT INTO dataset_listings (dataset_id, listing_id, instrument_id, symbol, quote_currency, calendar_id) " +
                        "VALUES (?, 'l-dist', 'inst-1', 'DST', 'EUR', 'CAL')", dsDist
        );
        jdbcTemplate.update(
                "INSERT INTO dataset_sessions (dataset_id, calendar_id, session_date, open_time, close_time, session_type) " +
                        "VALUES (?, 'CAL', '2024-01-31', '2024-01-31T08:00:00Z', '2024-01-31T16:30:00Z', 'TRADING')", dsDist
        );
        jdbcTemplate.update(
                "INSERT INTO dataset_sessions (dataset_id, calendar_id, session_date, open_time, close_time, session_type) " +
                        "VALUES (?, 'CAL', '2024-02-01', '2024-02-01T08:00:00Z', '2024-02-01T16:30:00Z', 'TRADING')", dsDist
        );
        jdbcTemplate.update(
                "INSERT INTO historical_bars (dataset_id, listing_id, session_date, open, high, low, close, volume, available_at) " +
                        "VALUES (?, 'l-dist', '2024-01-31', '100.00', '105.00', '95.00', '100.00', '1000', '2024-01-31T16:35:00Z')", dsDist
        );
        jdbcTemplate.update(
                "INSERT INTO historical_bars (dataset_id, listing_id, session_date, open, high, low, close, volume, available_at) " +
                        "VALUES (?, 'l-dist', '2024-02-01', '100.00', '105.00', '95.00', '100.00', '1000', '2024-02-01T16:35:00Z')", dsDist
        );
        jdbcTemplate.update(
                "INSERT INTO historical_actions (dataset_id, action_id, listing_id, action_type, effective_date, available_at, " +
                        "distribution_amount, distribution_currency, payment_date, payment_instant) " +
                        "VALUES (?, 'act-no-pay', 'l-dist', 'CASH_DISTRIBUTION', '2024-02-01', '2024-02-01T07:00:00Z', '10.00', 'EUR', NULL, NULL)", dsDist
        );

        IllegalArgumentException exDist = assertThrows(IllegalArgumentException.class, () ->
                dataReader.loadAndValidateData(dsDist, "l-dist", "l-dist", "2024-01-31T23:59:59Z", "2024-01-31", "2024-02-01")
        );
        assertTrue(exDist.getMessage().contains("must have payment_date or payment_instant specified") || exDist.getMessage().contains("paymentDate"),
                "Expected distribution payment timing rejection but got: " + exDist.getMessage());
    }

    @Test
    @DisplayName("HIGH-4: Concurrent same-key creation with CountDownLatch preserves idempotency")
    void testConcurrentSameKeyCreationPreservesIdempotency() throws Exception {
        String datasetId = importBundle("m3-reference-baseline.zip");

        BacktestDtos.CreateBacktestRequest request = new BacktestDtos.CreateBacktestRequest(
                datasetId, "listing-eur-syn-1", "listing-eur-syn-1", "2024-01-31T23:59:59Z",
                "2024-01-31", "2024-02-07", "1000.00", "EUR", "1.00", "10", "5",
                "ETF_BUY_HOLD_V1", "1.0.0"
        );

        String sameKey = "concurrent-idemp-key-" + UUID.randomUUID();
        int concurrency = 8;
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        CountDownLatch readyLatch = new CountDownLatch(concurrency);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(concurrency);

        List<String> returnedRunIds = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger successCount = new AtomicInteger();

        for (int i = 0; i < concurrency; i++) {
            pool.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    MvcResult res = mockMvc.perform(post("/api/research/backtests")
                                    .header("Idempotency-Key", sameKey)
                                    .header("Origin", "http://localhost:4200")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(request)))
                            .andReturn();

                    int status = res.getResponse().getStatus();
                    if (status == 202 || status == 200) {
                        BacktestDtos.BacktestSummaryResponse resp = objectMapper.readValue(
                                res.getResponse().getContentAsString(), BacktestDtos.BacktestSummaryResponse.class
                        );
                        returnedRunIds.add(resp.id());
                        successCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    // Log failure
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown(); // Launch all concurrent requests simultaneously
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS), "All concurrent requests must finish in time");
        pool.shutdown();

        assertEquals(concurrency, successCount.get(), "All concurrent threads must receive HTTP 200 or 202");
        Set<String> uniqueRunIds = new HashSet<>(returnedRunIds);
        assertEquals(1, uniqueRunIds.size(), "All concurrent requests with the same idempotency key must return the exact same run ID");
    }

    @Test
    @DisplayName("HIGH-4: Repeat cancel on terminal states (COMPLETED/CANCELLED) is a safe no-op")
    void testRepeatCancellationOnTerminalStatesIsNoOp() throws Exception {
        String dummyDsId = "ds-cancel-term-" + UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO datasets (id, name, source, classification, schema_version, parser_version, " +
                        "input_checksum, content_checksum, manifest_json, coverage_start, coverage_end, " +
                        "validation_status, validation_findings_json, quality_label, imported_at, created_at) " +
                        "VALUES (?, 'Cancel Term DS', 'TEST', 'SYNTHETIC', '1.0.0', '1.0.0', 'chk1', 'chk2', '{}', " +
                        "'2024-01-01', '2024-02-01', 'VALID', '[]', 'SYNTHETIC', '2026-09-12T00:00:00Z', '2026-09-12T00:00:00Z')", dummyDsId
        );
        jdbcTemplate.update(
                "INSERT INTO dataset_listings (dataset_id, listing_id, instrument_id, symbol, quote_currency, calendar_id) " +
                        "VALUES (?, 'l-1', 'inst-1', 'SYM', 'EUR', 'CAL')", dummyDsId
        );

        String runId = "run-term-cancel-" + UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO backtest_runs (id, owner_id, idempotency_key, canonical_hash, strategy_id, strategy_version, " +
                        "dataset_id, candidate_listing_id, benchmark_listing_id, initial_cash, currency, evaluation_cutoff, " +
                        "requested_start_date, requested_end_date, commission_per_fill, spread_bps, slippage_bps, status, " +
                        "progress_pct, config_json, created_at, updated_at) " +
                        "VALUES (?, 'default', 'idemp-term-cancel', 'hash', 'ETF_BUY_HOLD_V1', '1.0.0', ?, 'l-1', 'l-1', " +
                        "'1000.00', 'EUR', '2024-01-31T23:59:59Z', '2024-01-31', '2024-02-07', '1.00', '0', '0', 'COMPLETED', 100, '{}', " +
                        "'2026-09-12T00:00:00Z', '2026-09-12T00:00:00Z')", runId, dummyDsId
        );

        // Cancel on COMPLETED should return 200 and remain COMPLETED
        mockMvc.perform(post("/api/research/backtests/" + runId + "/cancel")
                        .header("Origin", "http://localhost:4200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        // Second cancel call must also be a safe 200 no-op
        mockMvc.perform(post("/api/research/backtests/" + runId + "/cancel")
                        .header("Origin", "http://localhost:4200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    @DisplayName("HIGH-4: Queued cancellation immediately marks run as CANCELLED")
    void testQueuedCancellationTransitionsToCancelled() throws Exception {
        String dummyDsId = "ds-cancel-q-" + UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO datasets (id, name, source, classification, schema_version, parser_version, " +
                        "input_checksum, content_checksum, manifest_json, coverage_start, coverage_end, " +
                        "validation_status, validation_findings_json, quality_label, imported_at, created_at) " +
                        "VALUES (?, 'Cancel Q DS', 'TEST', 'SYNTHETIC', '1.0.0', '1.0.0', 'chk1', 'chk2', '{}', " +
                        "'2024-01-01', '2024-02-01', 'VALID', '[]', 'SYNTHETIC', '2026-09-12T00:00:00Z', '2026-09-12T00:00:00Z')", dummyDsId
        );
        jdbcTemplate.update(
                "INSERT INTO dataset_listings (dataset_id, listing_id, instrument_id, symbol, quote_currency, calendar_id) " +
                        "VALUES (?, 'l-1', 'inst-1', 'SYM', 'EUR', 'CAL')", dummyDsId
        );

        String runId = "run-q-cancel-" + UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO backtest_runs (id, owner_id, idempotency_key, canonical_hash, strategy_id, strategy_version, " +
                        "dataset_id, candidate_listing_id, benchmark_listing_id, initial_cash, currency, evaluation_cutoff, " +
                        "requested_start_date, requested_end_date, commission_per_fill, spread_bps, slippage_bps, status, " +
                        "progress_pct, config_json, created_at, updated_at) " +
                        "VALUES (?, 'default', 'idemp-q-cancel', 'hash', 'ETF_BUY_HOLD_V1', '1.0.0', ?, 'l-1', 'l-1', " +
                        "'1000.00', 'EUR', '2024-01-31T23:59:59Z', '2024-01-31', '2024-02-07', '1.00', '0', '0', 'QUEUED', 0, '{}', " +
                        "'2026-09-12T00:00:00Z', '2026-09-12T00:00:00Z')", runId, dummyDsId
        );

        mockMvc.perform(post("/api/research/backtests/" + runId + "/cancel")
                        .header("Origin", "http://localhost:4200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        String status = jdbcTemplate.queryForObject("SELECT status FROM backtest_runs WHERE id = ?", String.class, runId);
        assertEquals("CANCELLED", status);
    }

    @Test
    @DisplayName("HIGH-3: Deterministic replay yields identical financial metrics and ordered events")
    void testDeterministicReplayIdenticalFinancialMetrics() throws Exception {
        String datasetId = importBundle("m3-reference-baseline.zip");

        BacktestDtos.CreateBacktestRequest request = new BacktestDtos.CreateBacktestRequest(
                datasetId, "listing-eur-syn-1", "listing-eur-syn-1", "2024-01-31T23:59:59Z",
                "2024-01-31", "2024-02-07", "1000.00", "EUR", "1.00", "10", "5",
                "ETF_BUY_HOLD_V1", "1.0.0"
        );

        // Run 1
        String key1 = "replay-run-1-" + UUID.randomUUID();
        MvcResult res1 = mockMvc.perform(post("/api/research/backtests")
                        .header("Idempotency-Key", key1)
                        .header("Origin", "http://localhost:4200")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andReturn();
        String runId1 = objectMapper.readValue(res1.getResponse().getContentAsString(), BacktestDtos.BacktestSummaryResponse.class).id();

        // Run 2 (different key, identical parameters)
        String key2 = "replay-run-2-" + UUID.randomUUID();
        MvcResult res2 = mockMvc.perform(post("/api/research/backtests")
                        .header("Idempotency-Key", key2)
                        .header("Origin", "http://localhost:4200")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andReturn();
        String runId2 = objectMapper.readValue(res2.getResponse().getContentAsString(), BacktestDtos.BacktestSummaryResponse.class).id();

        // Wait for both to complete
        BacktestDtos.BacktestSummaryResponse summary1 = null;
        BacktestDtos.BacktestSummaryResponse summary2 = null;
        for (int i = 0; i < 100; i++) {
            Thread.sleep(100);
            if (summary1 == null) {
                var s = objectMapper.readValue(mockMvc.perform(get("/api/research/backtests/" + runId1)).andReturn().getResponse().getContentAsString(), BacktestDtos.BacktestSummaryResponse.class);
                if ("COMPLETED".equals(s.status())) summary1 = s;
            }
            if (summary2 == null) {
                var s = objectMapper.readValue(mockMvc.perform(get("/api/research/backtests/" + runId2)).andReturn().getResponse().getContentAsString(), BacktestDtos.BacktestSummaryResponse.class);
                if ("COMPLETED".equals(s.status())) summary2 = s;
            }
            if (summary1 != null && summary2 != null) break;
        }

        assertNotNull(summary1);
        assertNotNull(summary2);

        // Verify identical financial analytics
        assertEquals(summary1.candidateSummary().finalEquity(), summary2.candidateSummary().finalEquity());
        assertEquals(summary1.candidateSummary().cumulativeReturn(), summary2.candidateSummary().cumulativeReturn(), 0.00001);
        assertEquals(summary1.candidateSummary().maxDrawdown(), summary2.candidateSummary().maxDrawdown(), 0.00001);
        assertEquals(summary1.candidateSummary().fillCount(), summary2.candidateSummary().fillCount());
        assertEquals(summary1.candidateSummary().totalCommissions(), summary2.candidateSummary().totalCommissions());

        // Verify identical canonical hash
        assertEquals(summary1.canonicalHash(), summary2.canonicalHash());

        // Verify identical event sequences
        List<Map<String, Object>> events1 = jdbcTemplate.queryForList("SELECT event_type, event_date, event_time, cash_delta, units_delta FROM backtest_events WHERE run_id = ? ORDER BY event_seq", runId1);
        List<Map<String, Object>> events2 = jdbcTemplate.queryForList("SELECT event_type, event_date, event_time, cash_delta, units_delta FROM backtest_events WHERE run_id = ? ORDER BY event_seq", runId2);
        assertEquals(events1.size(), events2.size());
        for (int i = 0; i < events1.size(); i++) {
            assertEquals(events1.get(i).get("event_type"), events2.get(i).get("event_type"));
            assertEquals(events1.get(i).get("event_date"), events2.get(i).get("event_date"));
            assertEquals(events1.get(i).get("event_time"), events2.get(i).get("event_time"));
            assertEquals(events1.get(i).get("cash_delta"), events2.get(i).get("cash_delta"));
            assertEquals(events1.get(i).get("units_delta"), events2.get(i).get("units_delta"));
        }

        String storedFingerprint = summary1.normalizedConfig().codeFingerprint();
        assertTrue(storedFingerprint.matches("[a-f0-9]{64}"));
        buildIdentityResolver.setTestCodeFingerprint("f".repeat(64));
        try {
            MvcResult replay = mockMvc.perform(post("/api/research/backtests")
                            .header("Idempotency-Key", key1)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andReturn();
            BacktestDtos.BacktestSummaryResponse replayed = objectMapper.readValue(
                    replay.getResponse().getContentAsString(), BacktestDtos.BacktestSummaryResponse.class);
            assertEquals(runId1, replayed.id());
            assertEquals(storedFingerprint, replayed.normalizedConfig().codeFingerprint());

            BacktestDtos.CreateBacktestRequest changedIntent = new BacktestDtos.CreateBacktestRequest(
                    datasetId, "listing-eur-syn-1", "listing-eur-syn-1", "2024-01-31T23:59:59Z",
                    "2024-01-31", "2024-02-07", "2000.00", "EUR", "1.00", "10", "5",
                    "ETF_BUY_HOLD_V1", "1.0.0");
            mockMvc.perform(post("/api/research/backtests")
                            .header("Idempotency-Key", key1)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(changedIntent)))
                    .andExpect(status().isConflict());
        } finally {
            buildIdentityResolver.clearTestOverride();
        }
    }

    @Test
    void realQueuedCancellationAndQueueSaturationLeaveNoPublishedRows() throws Exception {
        String datasetId = importBundle("m3-reference-baseline.zip");
        BacktestDtos.CreateBacktestRequest request = new BacktestDtos.CreateBacktestRequest(
                datasetId, "listing-eur-syn-1", "listing-eur-syn-1", "2024-01-31T23:59:59Z",
                "2024-01-31", "2024-02-07", "1000.00", "EUR", "1.00", "10", "5",
                "ETF_BUY_HOLD_V1", "1.0.0");
        ExecutorService worker = (ExecutorService) org.springframework.test.util.ReflectionTestUtils
                .getField(jobService, "calculationExecutor");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> blocker = worker.submit(() -> {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            String key = "queued-cancel-" + UUID.randomUUID();
            String runId = jobService.createBacktest("default", key, request).response().id();
            assertEquals("QUEUED", jdbcTemplate.queryForObject(
                    "SELECT status FROM backtest_runs WHERE id = ?", String.class, runId));
            assertEquals("CANCELLED", jobService.cancelBacktest(runId, "default").status());
            assertEquals("CANCELLED", jobService.cancelBacktest(runId, "default").status());

            for (int i = 0; i < 49; i++) {
                worker.submit(() -> {});
            }
            String rejectedKey = "queue-full-" + UUID.randomUUID();
            org.springframework.web.server.ResponseStatusException rejected = assertThrows(
                    org.springframework.web.server.ResponseStatusException.class,
                    () -> jobService.createBacktest("default", rejectedKey, request));
            assertEquals(503, rejected.getStatusCode().value());
            assertEquals("FAILED", jdbcTemplate.queryForObject(
                    "SELECT status FROM backtest_runs WHERE idempotency_key = ?", String.class, rejectedKey));
            assertEquals(0, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM backtest_daily_equity WHERE run_id = ?", Integer.class, runId));
        } finally {
            release.countDown();
            blocker.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void publicationFailureRollsBackAllResults() throws Exception {
        String datasetId = importBundle("m3-reference-baseline.zip");
        BacktestDtos.CreateBacktestRequest request = new BacktestDtos.CreateBacktestRequest(
                datasetId, "listing-eur-syn-1", "listing-eur-syn-1", "2024-01-31T23:59:59Z",
                "2024-01-31", "2024-02-07", "1000.00", "EUR", "1.00", "10", "5",
                "ETF_BUY_HOLD_V1", "1.0.0");
        jdbcTemplate.execute("CREATE TRIGGER fail_test_publication BEFORE INSERT ON backtest_orders " +
                "BEGIN SELECT RAISE(FAIL, 'injected publication failure'); END");
        try {
            String runId = jobService.createBacktest("default", "publication-failure-" + UUID.randomUUID(), request)
                    .response().id();
            String status = null;
            for (int i = 0; i < 100; i++) {
                status = jdbcTemplate.queryForObject("SELECT status FROM backtest_runs WHERE id = ?", String.class, runId);
                if ("FAILED".equals(status)) break;
                Thread.sleep(50);
            }
            assertEquals("FAILED", status);
            assertEquals(0, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM backtest_daily_equity WHERE run_id = ?", Integer.class, runId));
            assertEquals(0, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM backtest_orders WHERE run_id = ?", Integer.class, runId));
            assertEquals(0, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM backtest_events WHERE run_id = ?", Integer.class, runId));
            assertEquals(0, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM backtest_holdings WHERE run_id = ?", Integer.class, runId));
        } finally {
            jdbcTemplate.execute("DROP TRIGGER fail_test_publication");
        }
    }

    @Test
    void cancellationAtPublicationBoundaryWinsWithoutPartialResults() throws Exception {
        String datasetId = importBundle("m3-reference-baseline.zip");
        BacktestDtos.CreateBacktestRequest request = new BacktestDtos.CreateBacktestRequest(
                datasetId, "listing-eur-syn-1", "listing-eur-syn-1", "2024-01-31T23:59:59Z",
                "2024-01-31", "2024-02-07", "1000.00", "EUR", "1.00", "10", "5",
                "ETF_BUY_HOLD_V1", "1.0.0");
        CountDownLatch atSummary = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        doAnswer(invocation -> {
            atSummary.countDown();
            if (!resume.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting at publication boundary");
            }
            return invocation.callRealMethod();
        }).when(analyticsSpy).calculateSummary(any(BacktestEngine.SimulationResult.class),
                any(), anyList());
        String runId = null;
        try {
            runId = jobService.createBacktest("default", "cancel-publish-" + UUID.randomUUID(), request)
                    .response().id();
            assertTrue(atSummary.await(5, TimeUnit.SECONDS));
            assertEquals("RUNNING", jdbcTemplate.queryForObject(
                    "SELECT status FROM backtest_runs WHERE id = ?", String.class, runId));
            jobService.cancelBacktest(runId, "default");
        } finally {
            resume.countDown();
        }
        String status = null;
        for (int i = 0; i < 100; i++) {
            status = jdbcTemplate.queryForObject("SELECT status FROM backtest_runs WHERE id = ?", String.class, runId);
            if ("CANCELLED".equals(status)) break;
            Thread.sleep(50);
        }
        assertEquals("CANCELLED", status);
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM backtest_daily_equity WHERE run_id = ?", Integer.class, runId));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM backtest_events WHERE run_id = ?", Integer.class, runId));
    }
}
