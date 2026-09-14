package com.bergnerd.signalforge.app.research.backtest;

import com.bergnerd.signalforge.app.TemporarySqliteInitializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ContextConfiguration(initializers = TemporarySqliteInitializer.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BacktestM4IntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TotalReturnSignalIndexCalculator indexCalculator;

    @Autowired
    private StrategyEvaluator strategyEvaluator;

    @Autowired
    private BacktestEngine backtestEngine;

    @Autowired
    private BacktestAnalyticsCalculator analyticsCalculator;

    @Autowired
    private UniverseService universeService;

    @Autowired
    private ComparisonService comparisonService;

    @Autowired
    private ExperimentService experimentService;

    private TotalReturnSignalIndexCalculator.ListingSignalIndexSeries makeSeriesWithMonthEnds(
            String listingId, Map<YearMonth, BigDecimal> monthEnds
    ) {
        Map<YearMonth, TotalReturnSignalIndexCalculator.MonthObservation> map = new HashMap<>();
        List<TotalReturnSignalIndexCalculator.MonthObservation> list = new ArrayList<>();
        monthEnds.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> {
                    TotalReturnSignalIndexCalculator.MonthObservation obs = new TotalReturnSignalIndexCalculator.MonthObservation(
                            e.getKey(), e.getKey().atEndOfMonth().toString(), e.getValue(), e.getValue()
                    );
                    map.put(e.getKey(), obs);
                    list.add(obs);
                });
        return new TotalReturnSignalIndexCalculator.ListingSignalIndexSeries(listingId, Map.of(), List.of(), map, list);
    }

    // --- 1. Total-Return Signal Index & Corporate Action Invariance ---

    @Test
    @DisplayName("Total-return index is invariant to 2-for-1 forward stock split")
    void testTotalReturnIndexSplitInvariance() {
        // Day 0: close 100
        // Day 1: 2-for-1 split (splitMultiplier = 2), close 50
        List<BacktestDataReader.SessionRecord> sessions = List.of(
                new BacktestDataReader.SessionRecord("2025-01-02", "09:00:00", "17:30:00", "TRADING"),
                new BacktestDataReader.SessionRecord("2025-01-03", "09:00:00", "17:30:00", "TRADING")
        );
        Map<String, BacktestDataReader.BarRecord> bars = Map.of(
                "2025-01-02", new BacktestDataReader.BarRecord("ETF1", "2025-01-02", new BigDecimal("100.00"), new BigDecimal("101.00"), new BigDecimal("99.00"), new BigDecimal("100.00"), 1000L, "2025-01-02T16:30:00Z"),
                "2025-01-03", new BacktestDataReader.BarRecord("ETF1", "2025-01-03", new BigDecimal("50.00"), new BigDecimal("50.50"), new BigDecimal("49.50"), new BigDecimal("50.00"), 2000L, "2025-01-03T16:30:00Z")
        );
        List<BacktestDataReader.ActionRecord> actions = List.of(
                new BacktestDataReader.ActionRecord("act-1", "ETF1", "SPLIT", "2025-01-03", "2025-01-03T07:00:00Z", 2, 1, null, "EUR", "2025-01-03", "2025-01-03T07:00:00Z")
        );

        TotalReturnSignalIndexCalculator.ListingSignalIndexSeries series = indexCalculator.calculateSeries("ETF1", sessions, bars, actions);
        assertNotNull(series);
        assertEquals(new BigDecimal("100.00000000"), series.getPoint("2025-01-02").indexValue());
        // Day 1: 100.0 * 2.0 * (50.0 / 100.0) = 100.00000000
        assertEquals(new BigDecimal("100.00000000"), series.getPoint("2025-01-03").indexValue());
    }

    @Test
    @DisplayName("Total-return index correctly compounds cash distribution")
    void testTotalReturnIndexDistributionCompounding() {
        List<BacktestDataReader.SessionRecord> sessions = List.of(
                new BacktestDataReader.SessionRecord("2025-01-02", "09:00:00", "17:30:00", "TRADING"),
                new BacktestDataReader.SessionRecord("2025-01-03", "09:00:00", "17:30:00", "TRADING")
        );
        Map<String, BacktestDataReader.BarRecord> bars = Map.of(
                "2025-01-02", new BacktestDataReader.BarRecord("ETF1", "2025-01-02", new BigDecimal("100.00"), new BigDecimal("101.00"), new BigDecimal("99.00"), new BigDecimal("100.00"), 1000L, "2025-01-02T16:30:00Z"),
                "2025-01-03", new BacktestDataReader.BarRecord("ETF1", "2025-01-03", new BigDecimal("99.00"), new BigDecimal("100.00"), new BigDecimal("98.00"), new BigDecimal("99.00"), 1000L, "2025-01-03T16:30:00Z")
        );
        List<BacktestDataReader.ActionRecord> actions = List.of(
                new BacktestDataReader.ActionRecord("act-2", "ETF1", "DISTRIBUTION", "2025-01-03", "2025-01-03T07:00:00Z", null, null, new BigDecimal("2.00"), "EUR", "2025-01-03", "2025-01-03T07:00:00Z")
        );

        TotalReturnSignalIndexCalculator.ListingSignalIndexSeries series = indexCalculator.calculateSeries("ETF1", sessions, bars, actions);
        assertNotNull(series);
        assertEquals(new BigDecimal("100.00000000"), series.getPoint("2025-01-02").indexValue());
        // Day 1: 100.0 * 1.0 * (99.0 + 2.0) / 100.0 = 101.00000000 (+1.00%)
        assertEquals(new BigDecimal("101.00000000"), series.getPoint("2025-01-03").indexValue());
    }

    // --- 2. Strategy S2: Momentum 12-1 Ranking & Ties ---

    @Test
    @DisplayName("S2 ranks descending, resolves ties by listing ID ascending, and selects top K with 1/K weights")
    void testStrategyS2RankingAndTieBreaking() {
        // 4 listings, K = 2
        List<String> listings = List.of("ETF-A", "ETF-B", "ETF-C", "ETF-D");
        YearMonth evalMonth = YearMonth.of(2024, 12);
        YearMonth mMinus1 = YearMonth.of(2024, 11);
        YearMonth mMinus12 = YearMonth.of(2023, 12);

        Map<String, TotalReturnSignalIndexCalculator.ListingSignalIndexSeries> indexSeriesMap = new HashMap<>();

        // ETF-A: score = 112/100 - 1 = +0.12
        indexSeriesMap.put("ETF-A", makeSeriesWithMonthEnds("ETF-A", Map.of(
                mMinus12, new BigDecimal("100.00000000"),
                mMinus1, new BigDecimal("112.00000000")
        )));

        // ETF-B: score = 76/80 - 1 = -0.05
        indexSeriesMap.put("ETF-B", makeSeriesWithMonthEnds("ETF-B", Map.of(
                mMinus12, new BigDecimal("80.00000000"),
                mMinus1, new BigDecimal("76.00000000")
        )));

        // ETF-C: score = 114/120 - 1 = -0.05 (tied with ETF-B)
        indexSeriesMap.put("ETF-C", makeSeriesWithMonthEnds("ETF-C", Map.of(
                mMinus12, new BigDecimal("120.00000000"),
                mMinus1, new BigDecimal("114.00000000")
        )));

        // ETF-D: score = 45/50 - 1 = -0.10
        indexSeriesMap.put("ETF-D", makeSeriesWithMonthEnds("ETF-D", Map.of(
                mMinus12, new BigDecimal("50.00000000"),
                mMinus1, new BigDecimal("45.00000000")
        )));

        BacktestDataReader.SessionRecord evalSession = new BacktestDataReader.SessionRecord(
                "2024-12-30", "09:00:00", "17:30:00", "TRADING"
        );
        BacktestDataReader.SessionRecord nextSession = new BacktestDataReader.SessionRecord(
                "2025-01-02", "09:00:00", "17:30:00", "TRADING"
        );

        StrategyEvaluator.EvaluatedSignal result = strategyEvaluator.evaluateS2(
                "run-test-s2", 1, "uni-test", 2, evalMonth, evalSession, nextSession, listings, indexSeriesMap
        );

        assertNotNull(result);
        assertEquals(4, result.targetWeights().size());
        // ETF-A top rank: 1/2 = 0.5
        assertEquals(0, new BigDecimal("0.50000000").compareTo(result.targetWeights().get("ETF-A")));
        // ETF-B tied with ETF-C, wins by listing ID: 0.5
        assertEquals(0, new BigDecimal("0.50000000").compareTo(result.targetWeights().get("ETF-B")));
        assertEquals(BigDecimal.ZERO, result.targetWeights().get("ETF-C"));
        assertEquals(BigDecimal.ZERO, result.targetWeights().get("ETF-D"));

        // Check auditable items
        assertEquals(4, result.items().size());
        assertEquals(1, result.items().get(0).rank());
        assertEquals("ETF-A", result.items().get(0).listingId());
        assertTrue(result.items().get(0).selected());

        assertEquals(2, result.items().get(1).rank());
        assertEquals("ETF-B", result.items().get(1).listingId());
        assertTrue(result.items().get(1).selected());

        assertEquals(3, result.items().get(2).rank());
        assertEquals("ETF-C", result.items().get(2).listingId());
        assertFalse(result.items().get(2).selected());
    }

    // --- 3. Strategy S3: 10-Month SMA Trend Filter ---

    @Test
    @DisplayName("S3 selects 100% ETF when T(m) > SMA10, 100% cash when T(m) <= SMA10 (equality selects cash)")
    void testStrategyS3TrendFilter() {
        YearMonth evalMonth = YearMonth.of(2024, 10);
        BacktestDataReader.SessionRecord evalSession = new BacktestDataReader.SessionRecord(
                "2024-10-31", "09:00:00", "17:30:00", "TRADING"
        );
        BacktestDataReader.SessionRecord nextSession = new BacktestDataReader.SessionRecord(
                "2024-11-01", "09:00:00", "17:30:00", "TRADING"
        );

        // Bull case: 9 months of 100.0, 10th month (evalMonth) of 110.0 => SMA10 = 101.0, T(m)=110.0 > SMA10
        Map<YearMonth, BigDecimal> bullMonths = new HashMap<>();
        for (int i = 9; i >= 1; i--) {
            bullMonths.put(evalMonth.minusMonths(i), new BigDecimal("100.00000000"));
        }
        bullMonths.put(evalMonth, new BigDecimal("110.00000000"));

        TotalReturnSignalIndexCalculator.ListingSignalIndexSeries bullSeries = makeSeriesWithMonthEnds("ETF-WORLD", bullMonths);
        StrategyEvaluator.EvaluatedSignal resBull = strategyEvaluator.evaluateS3(
                "run-test-s3", 1, "ETF-WORLD", evalMonth, evalSession, nextSession, bullSeries
        );
        assertEquals(new BigDecimal("1"), resBull.targetWeights().get("ETF-WORLD"));
        assertEquals("TREND_ABOVE_SMA10_TARGET_ETF", resBull.reasonCode());

        // Equality case: 10 months of 100.0 => SMA10 = 100.0, T(m) = 100.0 => T(m) <= SMA10 => 100% cash (weight 0)
        Map<YearMonth, BigDecimal> eqMonths = new HashMap<>();
        for (int i = 9; i >= 0; i--) {
            eqMonths.put(evalMonth.minusMonths(i), new BigDecimal("100.00000000"));
        }
        TotalReturnSignalIndexCalculator.ListingSignalIndexSeries eqSeries = makeSeriesWithMonthEnds("ETF-WORLD", eqMonths);
        StrategyEvaluator.EvaluatedSignal resEq = strategyEvaluator.evaluateS3(
                "run-test-s3", 2, "ETF-WORLD", evalMonth, evalSession, nextSession, eqSeries
        );
        assertEquals(BigDecimal.ZERO, resEq.targetWeights().get("ETF-WORLD"));
        assertEquals("TREND_EQUAL_SMA10_TARGET_CASH", resEq.reasonCode());
    }

    // --- 4. Rolling 5-Year Windows & Positive Share ---

    @Test
    @DisplayName("Rolling 5-year windows calculate compounded return and positive-window share")
    void testRolling5YearWindows() {
        List<BacktestDtos.DailyEquityPoint> dailyEquity = new ArrayList<>();
        // Generate 6 years of monthly equity (2018 to 2024)
        java.time.LocalDate cur = java.time.LocalDate.of(2018, 1, 2);
        java.time.LocalDate end = java.time.LocalDate.of(2024, 1, 2);
        double eq = 1000.0;

        while (!cur.isAfter(end)) {
            dailyEquity.add(new BacktestDtos.DailyEquityPoint(
                    cur.toString(), "CANDIDATE", "100.00", String.format(Locale.US, "%.2f", eq - 100.0),
                    "0.00", String.format(Locale.US, "%.2f", eq), 0.0005, 0.0,
                    String.format(Locale.US, "%.2f", eq), "10", "900.00", "90.00", "CLOSE", cur + "T16:30:00Z"
            ));
            eq *= 1.0003; // upward growth
            cur = cur.plusDays(1);
        }

        List<BacktestDataReader.SessionRecord> allSessions = dailyEquity.stream()
                .map(p -> new BacktestDataReader.SessionRecord(p.sessionDate(), "09:00:00", "17:30:00", "TRADING"))
                .toList();

        BacktestDtos.RollingWindowSummaryDto summary = analyticsCalculator.calculateRolling5YearWindows(dailyEquity, allSessions);
        assertNotNull(summary);
        assertTrue(summary.totalWindows() > 0);
        assertTrue(summary.completeWindows() > 0);
        assertEquals(summary.completeWindows(), summary.positiveWindows());
        assertEquals(1.0, summary.positiveWindowShare());
        assertNotNull(summary.note());
        assertTrue(summary.note().contains("descriptive statistics and not predictive"), "Expected note to contain disclaimer, but was: " + summary.note());
    }

    // --- 5. Universe REST APIs ---

    @Test
    @DisplayName("Universe API supports creation, listing, and retrieval")
    void testUniverseLifecycle() throws Exception {
        // Insert a dummy valid dataset if needed for foreign key
        jdbcTemplate.update(
                "INSERT OR IGNORE INTO datasets (id, name, source, classification, schema_version, parser_version, " +
                        "input_checksum, content_checksum, manifest_json, coverage_start, coverage_end, " +
                        "validation_status, validation_findings_json, quality_label, imported_at, created_at) " +
                        "VALUES ('ds-m4-test', 'M4 Test Dataset', 'TEST_RUNNER', 'SYNTHETIC', '1.0.0', '1.0.0', 'cs-in', 'cs-out', '{}', " +
                        "'2020-01-01', '2025-12-31', 'VALID', '[]', 'SYNTHETIC', '2026-09-14T00:00:00Z', '2026-09-14T00:00:00Z')"
        );
        for (String lId : List.of("EXXT.DE", "EUNL.DE", "IS3N.DE")) {
            jdbcTemplate.update(
                    "INSERT OR IGNORE INTO dataset_listings (dataset_id, listing_id, instrument_id, symbol, quote_currency, calendar_id) " +
                            "VALUES ('ds-m4-test', ?, ?, ?, 'EUR', 'XETR')",
                    lId, "inst-" + lId, lId
            );
        }

        String universePayload = """
                {
                  "name": "European Tech & Core ETFs",
                  "version": "1.0.0",
                  "description": "Multi-asset ETF universe for momentum rotation",
                  "datasetId": "ds-m4-test",
                  "calendarId": "XETR",
                  "currency": "EUR",
                  "provenance": "TEST_SYNTHETIC",
                  "listingIds": ["EXXT.DE", "EUNL.DE", "IS3N.DE"]
                }
                """;

        MvcResult createResult = mockMvc.perform(post("/api/research/universes")
                        .header("X-User-Id", "tester-m4")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(universePayload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.name").value("European Tech & Core ETFs"))
                .andExpect(jsonPath("$.listingCount").value(3))
                .andReturn();

        BacktestDtos.UniverseDto created = objectMapper.readValue(
                createResult.getResponse().getContentAsString(), BacktestDtos.UniverseDto.class
        );

        mockMvc.perform(get("/api/research/universes/" + created.id())
                        .header("X-User-Id", "tester-m4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(created.id()))
                .andExpect(jsonPath("$.listings.length()").value(3));

        mockMvc.perform(get("/api/research/universes")
                        .header("X-User-Id", "tester-m4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    // --- 6. Strategies REST API ---

    @Test
    @DisplayName("Strategy versions endpoint returns seeded S1, S2, and S3 strategies")
    void testListStrategies() throws Exception {
        mockMvc.perform(get("/api/research/strategies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].strategyId").value("ETF_BUY_HOLD_V1"))
                .andExpect(jsonPath("$[1].strategyId").value("ETF_MOMENTUM_12_1_V1"))
                .andExpect(jsonPath("$[2].strategyId").value("ETF_TREND_10M_V1"));
    }

    // --- 7. Experiments & Holdout Tracking ---

    @Test
    @DisplayName("Experiments API creates immutable experiments and records append-only exposure events")
    void testExperimentLifecycleAndExposureTracking() throws Exception {
        jdbcTemplate.update(
                "INSERT OR IGNORE INTO datasets (id, name, source, classification, schema_version, parser_version, " +
                        "input_checksum, content_checksum, manifest_json, coverage_start, coverage_end, " +
                        "validation_status, validation_findings_json, quality_label, imported_at, created_at) " +
                        "VALUES ('ds-m4-test', 'M4 Test Dataset', 'TEST_RUNNER', 'SYNTHETIC', '1.0.0', '1.0.0', 'cs-in', 'cs-out', '{}', " +
                        "'2020-01-01', '2025-12-31', 'VALID', '[]', 'SYNTHETIC', '2026-09-14T00:00:00Z', '2026-09-14T00:00:00Z')"
        );
        jdbcTemplate.update(
                "INSERT OR IGNORE INTO dataset_listings (dataset_id, listing_id, instrument_id, symbol, quote_currency, calendar_id) " +
                        "VALUES ('ds-m4-test', 'EUNL.DE', 'inst-EUNL', 'EUNL', 'EUR', 'XETR')"
        );
        for (String date : List.of("2020-01-02", "2022-12-30", "2023-01-02", "2024-12-30")) {
            jdbcTemplate.update(
                    "INSERT OR IGNORE INTO dataset_sessions (dataset_id, calendar_id, session_date, open_time, close_time, session_type) " +
                            "VALUES ('ds-m4-test', 'XETR', ?, ?, ?, 'TRADING')",
                    date, date + "T08:00:00Z", date + "T16:30:00Z");
        }

        String expPayload = """
                {
                  "name": "Buy Hold Holdout Study",
                  "version": 1,
                  "strategyId": "ETF_BUY_HOLD_V1",
                  "strategyVersion": "1.0.0",
                  "datasetId": "ds-m4-test",
                  "candidateListingId": "EUNL.DE",
                  "benchmarkListingId": "EUNL.DE",
                  "developmentStartDate": "2020-01-02",
                  "developmentEndDate": "2022-12-30",
                  "holdoutStartDate": "2023-01-02",
                  "holdoutEndDate": "2024-12-30",
                  "declaredHoldoutStatus": "UNEXAMINED"
                }
                """;

        MvcResult expResult = mockMvc.perform(post("/api/research/experiments")
                        .header("X-User-Id", "exp-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(expPayload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.declaredHoldoutStatus").value("UNEXAMINED"))
                .andReturn();

        BacktestDtos.ExperimentDto exp = objectMapper.readValue(
                expResult.getResponse().getContentAsString(), BacktestDtos.ExperimentDto.class
        );

        // Record exposure directly
        experimentService.recordExposure(exp.id(), "run-dummy", "VIEW_DETAIL", "exp-tester", "{\"reason\":\"audit test\"}");

        mockMvc.perform(get("/api/research/experiments/" + exp.id())
                        .header("X-User-Id", "exp-tester"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalExposures").value(1))
                .andExpect(jsonPath("$.exposureEvents[0].accessType").value("VIEW_DETAIL"));

        String exposedRunId = "run-holdout-" + UUID.randomUUID();
        String now = java.time.Instant.now().toString();
        jdbcTemplate.update("INSERT INTO backtest_runs (id, owner_id, idempotency_key, canonical_hash, strategy_id, strategy_version, " +
                        "dataset_id, candidate_listing_id, benchmark_listing_id, initial_cash, currency, evaluation_cutoff, " +
                        "requested_start_date, requested_end_date, commission_per_fill, spread_bps, slippage_bps, " +
                        "experiment_id, status, progress_pct, config_json, created_at, updated_at) " +
                        "VALUES (?, 'exp-tester', ?, 'holdout-hash', 'ETF_BUY_HOLD_V1', '1.0.0', 'ds-m4-test', " +
                        "'EUNL.DE', 'EUNL.DE', '1000.00', 'EUR', '2020-01-02T17:00:00Z', " +
                        "'2020-01-02', '2024-12-30', '1.00', '10', '5', ?, 'COMPLETED', 100, '{}', ?, ?)",
                exposedRunId, UUID.randomUUID().toString(), exp.id(), now, now);
        mockMvc.perform(get("/api/research/backtests").header("X-User-Id", "exp-tester"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/research/backtests/" + exposedRunId + "/signals/export")
                        .header("X-User-Id", "exp-tester"))
                .andExpect(status().isOk());
        List<String> accessTypes = jdbcTemplate.queryForList(
                "SELECT access_type FROM experiment_exposure_events WHERE experiment_id = ? ORDER BY exposed_at",
                String.class, exp.id());
        assertTrue(accessTypes.contains("SUMMARY_VIEW"));
        assertTrue(accessTypes.contains("EXPORT_DOWNLOAD"));

        // Verify that exposure events are append-only (attempting to delete throws exception via trigger)
        assertThrows(Exception.class, () -> {
            jdbcTemplate.update("DELETE FROM experiment_exposure_events WHERE experiment_id = ?", exp.id());
        });
    }

    // --- 8. Comparisons & Field-by-Field Mismatch Rejection ---

    @Test
    @DisplayName("Comparison service rejects mismatched runs field-by-field")
    void testComparisonMismatchDetection() {
        jdbcTemplate.update(
                "INSERT OR IGNORE INTO datasets (id, name, source, classification, schema_version, parser_version, " +
                        "input_checksum, content_checksum, manifest_json, coverage_start, coverage_end, " +
                        "validation_status, validation_findings_json, quality_label, imported_at, created_at) " +
                        "VALUES ('ds-m4-test', 'M4 Test Dataset', 'TEST_RUNNER', 'SYNTHETIC', '1.0.0', '1.0.0', 'cs-in', 'cs-out', '{}', " +
                        "'2020-01-01', '2025-12-31', 'VALID', '[]', 'SYNTHETIC', '2026-09-14T00:00:00Z', '2026-09-14T00:00:00Z')"
        );
        jdbcTemplate.update(
                "INSERT OR IGNORE INTO dataset_listings (dataset_id, listing_id, instrument_id, symbol, quote_currency, calendar_id) " +
                        "VALUES ('ds-m4-test', 'EUNL.DE', 'inst-EUNL', 'EUNL', 'EUR', 'XETR')"
        );

        // Insert 2 completed runs with different initial cash
        String runA = "run-comp-a-" + UUID.randomUUID();
        String runB = "run-comp-b-" + UUID.randomUUID();
        String now = java.time.Instant.now().toString();

        String cfgJsonA = "{\"strategyId\":\"ETF_BUY_HOLD_V1\",\"datasetId\":\"ds-m4-test\",\"calendarId\":\"XETR\"," +
                "\"benchmarkListingId\":\"EUNL.DE\",\"quoteCurrency\":\"EUR\",\"initialCash\":\"1000.00\"," +
                "\"effectiveStartDate\":\"2020-01-02\",\"effectiveEndDate\":\"2021-12-30\"," +
                "\"commissionPerFill\":\"1.00\",\"spreadBps\":\"10\",\"slippageBps\":\"5\",\"engineVersion\":\"2.0.0-M4\"}";
        String cfgJsonB = "{\"strategyId\":\"ETF_TREND_10M_V1\",\"datasetId\":\"ds-m4-test\",\"calendarId\":\"XETR\"," +
                "\"benchmarkListingId\":\"EUNL.DE\",\"quoteCurrency\":\"EUR\",\"initialCash\":\"5000.00\"," + // Mismatched initial cash!
                "\"effectiveStartDate\":\"2020-01-02\",\"effectiveEndDate\":\"2021-12-30\"," +
                "\"commissionPerFill\":\"1.00\",\"spreadBps\":\"10\",\"slippageBps\":\"5\",\"engineVersion\":\"2.0.0-M4\"}";

        jdbcTemplate.update("INSERT INTO backtest_runs (id, owner_id, idempotency_key, canonical_hash, strategy_id, strategy_version, dataset_id, candidate_listing_id, benchmark_listing_id, initial_cash, currency, evaluation_cutoff, requested_start_date, requested_end_date, commission_per_fill, spread_bps, slippage_bps, status, progress_pct, config_json, created_at, updated_at) " +
                "VALUES (?, 'comp-user', ?, 'hashA', 'ETF_BUY_HOLD_V1', '1.0.0', 'ds-m4-test', 'EUNL.DE', 'EUNL.DE', '1000.00', 'EUR', '2020-01-01T22:00:00Z', '2020-01-02', '2021-12-30', '1.00', '10', '5', 'COMPLETED', 100, ?, ?, ?)",
                runA, UUID.randomUUID().toString(), cfgJsonA, now, now);

        jdbcTemplate.update("INSERT INTO backtest_runs (id, owner_id, idempotency_key, canonical_hash, strategy_id, strategy_version, dataset_id, candidate_listing_id, benchmark_listing_id, initial_cash, currency, evaluation_cutoff, requested_start_date, requested_end_date, commission_per_fill, spread_bps, slippage_bps, status, progress_pct, config_json, created_at, updated_at) " +
                "VALUES (?, 'comp-user', ?, 'hashB', 'ETF_TREND_10M_V1', '1.0.0', 'ds-m4-test', 'EUNL.DE', 'EUNL.DE', '5000.00', 'EUR', '2020-01-01T22:00:00Z', '2020-01-02', '2021-12-30', '1.00', '10', '5', 'COMPLETED', 100, ?, ?, ?)",
                runB, UUID.randomUUID().toString(), cfgJsonB, now, now);

        BacktestDtos.CreateComparisonRequest compReq = new BacktestDtos.CreateComparisonRequest(
                "S1 vs S3 Comparison", List.of(runA, runB)
        );

        BacktestDtos.BacktestComparisonDto comparison = comparisonService.createComparison(
                "comp-user", "idemp-comp-1", compReq
        );

        assertNotNull(comparison);
        assertEquals("MISMATCHED", comparison.status());
        assertFalse(comparison.mismatchReasons().isEmpty());
        // Verify mismatch is specifically identified as initialCash
        assertTrue(comparison.mismatchReasons().stream().anyMatch(m -> "initialCash".equals(m.field())));
    }

    // --- 9. Strategy Parameter & Version Validation Rejection ---

    @Test
    @DisplayName("Strategy validation rejects S1 with parameters, S2 with out-of-range K, S3 with invalid lookback, and invalid versions")
    void testStrategyParameterAndVersionValidation() throws Exception {
        // Ensure dataset and universe exist
        jdbcTemplate.update(
                "INSERT OR IGNORE INTO datasets (id, name, source, classification, schema_version, parser_version, " +
                        "input_checksum, content_checksum, manifest_json, coverage_start, coverage_end, " +
                        "validation_status, validation_findings_json, quality_label, imported_at, created_at) " +
                        "VALUES ('ds-val-test', 'Val Test Dataset', 'TEST_RUNNER', 'SYNTHETIC', '1.0.0', '1.0.0', 'cs-in', 'cs-out', '{}', " +
                        "'2020-01-01', '2025-12-31', 'VALID', '[]', 'SYNTHETIC', '2026-09-14T00:00:00Z', '2026-09-14T00:00:00Z')"
        );
        jdbcTemplate.update(
                "INSERT OR IGNORE INTO dataset_listings (dataset_id, listing_id, instrument_id, symbol, quote_currency, calendar_id) " +
                        "VALUES ('ds-val-test', 'EUNL.DE', 'inst-EUNL', 'EUNL', 'EUR', 'XETR')"
        );
        jdbcTemplate.update(
                "INSERT OR IGNORE INTO dataset_listings (dataset_id, listing_id, instrument_id, symbol, quote_currency, calendar_id) " +
                        "VALUES ('ds-val-test', 'EXXT.DE', 'inst-EXXT', 'EXXT', 'EUR', 'XETR')"
        );

        // Create universe with 2 listings
        String uniPayload = """
                {
                  "name": "Validation Test Universe",
                  "version": "1.0.0",
                  "description": "Validation testing",
                  "datasetId": "ds-val-test",
                  "calendarId": "XETR",
                  "currency": "EUR",
                  "provenance": "TEST",
                  "listingIds": ["EUNL.DE", "EXXT.DE"]
                }
                """;
        MvcResult uniRes = mockMvc.perform(post("/api/research/universes")
                        .header("X-User-Id", "val-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(uniPayload))
                .andExpect(status().isCreated())
                .andReturn();
        BacktestDtos.UniverseDto uni = objectMapper.readValue(uniRes.getResponse().getContentAsString(), BacktestDtos.UniverseDto.class);

        // 1. S1 with parameters => 400 Bad Request
        String s1WithParams = String.format("""
                {
                  "strategyId": "ETF_BUY_HOLD_V1",
                  "strategyVersion": "1.0.0",
                  "datasetId": "ds-val-test",
                  "benchmarkListingId": "EUNL.DE",
                  "candidateListingId": "EUNL.DE",
                  "initialCash": "1000.00",
                  "requestedStartDate": "2020-01-02",
                  "requestedEndDate": "2021-12-30",
                  "commissionPerFill": "1.00",
                  "spreadBps": "10",
                  "slippageBps": "5",
                  "parametersJson": "{\\"k\\":1}"
                }
                """);
        mockMvc.perform(post("/api/research/backtests")
                        .header("X-User-Id", "val-tester")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(s1WithParams))
                .andExpect(status().isBadRequest());

        // 2. S2 with k = 3 (universe has 2) => 400 Bad Request
        String s2KTooBig = String.format("""
                {
                  "strategyId": "ETF_MOMENTUM_12_1_V1",
                  "strategyVersion": "1.0.0",
                  "datasetId": "ds-val-test",
                  "universeId": "%s",
                  "benchmarkListingId": "EUNL.DE",
                  "initialCash": "1000.00",
                  "requestedStartDate": "2020-01-02",
                  "requestedEndDate": "2021-12-30",
                  "commissionPerFill": "1.00",
                  "spreadBps": "10",
                  "slippageBps": "5",
                  "parametersJson": "{\\"k\\":3}"
                }
                """, uni.id());
        mockMvc.perform(post("/api/research/backtests")
                        .header("X-User-Id", "val-tester")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(s2KTooBig))
                .andExpect(status().isBadRequest());

        // 3. S3 with lookbackMonths = 12 (must be 10) => 400 Bad Request
        String s3BadLookback = """
                {
                  "strategyId": "ETF_TREND_10M_V1",
                  "strategyVersion": "1.0.0",
                  "datasetId": "ds-val-test",
                  "benchmarkListingId": "EUNL.DE",
                  "candidateListingId": "EUNL.DE",
                  "initialCash": "1000.00",
                  "requestedStartDate": "2020-01-02",
                  "requestedEndDate": "2021-12-30",
                  "commissionPerFill": "1.00",
                  "spreadBps": "10",
                  "slippageBps": "5",
                  "parametersJson": "{\\"lookbackMonths\\":12}"
                }
                """;
        mockMvc.perform(post("/api/research/backtests")
                        .header("X-User-Id", "val-tester")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(s3BadLookback))
                .andExpect(status().isBadRequest());

        // 4. Invalid strategy version => 400 Bad Request
        String invalidVersion = """
                {
                  "strategyId": "ETF_BUY_HOLD_V1",
                  "strategyVersion": "9.9.9",
                  "datasetId": "ds-val-test",
                  "benchmarkListingId": "EUNL.DE",
                  "candidateListingId": "EUNL.DE",
                  "initialCash": "1000.00",
                  "requestedStartDate": "2020-01-02",
                  "requestedEndDate": "2021-12-30",
                  "commissionPerFill": "1.00",
                  "spreadBps": "10",
                  "slippageBps": "5"
                }
                """;
        mockMvc.perform(post("/api/research/backtests")
                        .header("X-User-Id", "val-tester")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidVersion))
                .andExpect(status().isBadRequest());
    }

    // --- 10. Strategy Detail & Signals CSV Export Endpoints ---

    @Test
    @DisplayName("Strategy detail endpoint returns single strategy metadata and parameter definitions")
    void testStrategyDetailEndpoint() throws Exception {
        mockMvc.perform(get("/api/research/strategies/ETF_TREND_10M_V1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.strategyId").value("ETF_TREND_10M_V1"))
                .andExpect(jsonPath("$.strategyVersion").value("1.0.1"))
                .andExpect(jsonPath("$.parameters.length()").value(0));
    }

    @Test
    @DisplayName("Signals CSV export produces valid RFC-4180 CSV with required columns")
    void testSignalCsvExportEndpoint() throws Exception {
        String runId = "run-csv-" + UUID.randomUUID();
        String now = java.time.Instant.now().toString();

        jdbcTemplate.update("INSERT INTO backtest_runs (id, owner_id, idempotency_key, canonical_hash, strategy_id, strategy_version, dataset_id, candidate_listing_id, benchmark_listing_id, initial_cash, currency, evaluation_cutoff, requested_start_date, requested_end_date, commission_per_fill, spread_bps, slippage_bps, status, progress_pct, config_json, created_at, updated_at) " +
                "VALUES (?, 'csv-user', ?, 'hashCsv', 'ETF_BUY_HOLD_V1', '1.0.0', 'ds-m4-test', 'EUNL.DE', 'EUNL.DE', '1000.00', 'EUR', '2020-01-01T22:00:00Z', '2020-01-02', '2020-01-05', '1.00', '10', '5', 'RUNNING', 50, '{}', ?, ?)",
                runId, UUID.randomUUID().toString(), now, now);

        jdbcTemplate.update("INSERT INTO backtest_signals (id, run_id, strategy_id, strategy_version, universe_id, evaluation_date, evaluation_time, decision_instant, scheduled_execution_date, target_allocation_summary, status, reason_code, details_json, created_at) " +
                "VALUES (?, ?, 'ETF_BUY_HOLD_V1', '1.0.0', NULL, '2020-01-02', '17:30:00', '2020-01-02T16:30:00Z', '2020-01-03', '100% EUNL.DE', 'EXECUTED', 'INITIAL_ALLOCATION', '{}', ?)",
                "sig-1", runId, now);

        jdbcTemplate.update("INSERT INTO backtest_signal_items (id, signal_id, listing_id, score, index_value, sma_value, rank, eligible, selected, target_weight, reason_code) " +
                "VALUES (?, ?, 'EUNL.DE', '1.00000000', '100.00000000', NULL, 1, 1, 1, '1.00000000', 'INITIAL_ALLOCATION')",
                "item-1", "sig-1");

        jdbcTemplate.update("UPDATE backtest_runs SET status = 'COMPLETED', progress_pct = 100 WHERE id = ?", runId);

        mockMvc.perform(get("/api/research/backtests/" + runId + "/signals/export")
                        .header("X-User-Id", "csv-user"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("evaluation_date,strategy_id,universe_id,listing_id,score,index_value,sma_value,rank,eligible,selected,target_weight,reason_code,scheduled_execution_date,status")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("2020-01-02,ETF_BUY_HOLD_V1,,EUNL.DE,1.00000000,100.00000000,,1,true,true,1.00000000,INITIAL_ALLOCATION,2020-01-03,EXECUTED")));
    }

    // --- 11. Point-in-Time Availability Filtering ---

    @Test
    @DisplayName("Total return index calculation strictly filters bars and actions by asOfInstant")
    void testTotalReturnIndexStrictAvailabilityFiltering() {
        List<BacktestDataReader.SessionRecord> sessions = List.of(
                new BacktestDataReader.SessionRecord("2025-01-02", "09:00:00", "17:30:00", "TRADING"),
                new BacktestDataReader.SessionRecord("2025-01-03", "09:00:00", "17:30:00", "TRADING")
        );
        Map<String, BacktestDataReader.BarRecord> bars = Map.of(
                "2025-01-02", new BacktestDataReader.BarRecord("ETF1", "2025-01-02", new BigDecimal("100.00"), new BigDecimal("101.00"), new BigDecimal("99.00"), new BigDecimal("100.00"), 1000L, "2025-01-02T16:30:00Z"),
                "2025-01-03", new BacktestDataReader.BarRecord("ETF1", "2025-01-03", new BigDecimal("105.00"), new BigDecimal("106.00"), new BigDecimal("104.00"), new BigDecimal("105.00"), 1000L, "2025-01-03T18:00:00Z") // Late-arriving
        );
        List<BacktestDataReader.ActionRecord> actions = List.of();

        // As of 2025-01-03T17:00:00Z (before bar on 2025-01-03 arrived at 18:00:00Z)
        java.time.Instant asOf = java.time.Instant.parse("2025-01-03T17:00:00Z");
        TotalReturnSignalIndexCalculator.ListingSignalIndexSeries series =
                indexCalculator.calculateSeries("ETF1", sessions, bars, actions, asOf);

        assertNotNull(series);
        assertNotNull(series.getPoint("2025-01-02"));
        // 2025-01-03 bar was available at 18:00, so as of 17:00 it must NOT be in the index series
        assertNull(series.getPoint("2025-01-03"));

        Map<String, BacktestDataReader.BarRecord> missingAvailability = Map.of(
                "2025-01-02", new BacktestDataReader.BarRecord("ETF1", "2025-01-02", new BigDecimal("100.00"), new BigDecimal("101.00"), new BigDecimal("99.00"), new BigDecimal("100.00"), 1000L, null));
        assertThrows(IllegalArgumentException.class, () -> indexCalculator.calculateSeries(
                "ETF1", sessions.subList(0, 1), missingAvailability, actions, asOf));
    }

    @Test
    @DisplayName("A valid S2 request reaches execution, while invalid K and foreign universes are rejected")
    void testValidS2RunAndOwnerScope() throws Exception {
        String datasetId = "ds-s2-" + UUID.randomUUID();
        String now = java.time.Instant.now().toString();
        jdbcTemplate.update(
                "INSERT INTO datasets (id, name, source, classification, schema_version, parser_version, " +
                        "input_checksum, content_checksum, manifest_json, coverage_start, coverage_end, " +
                        "validation_status, validation_findings_json, quality_label, imported_at, created_at) " +
                        "VALUES (?, 'S2 acceptance', 'TEST_RUNNER', 'SYNTHETIC', '1.0.0', '1.0.0', 's2-in', 's2-out', '{}', " +
                        "'2023-12-31', '2025-03-31', 'VALID', '[]', 'SYNTHETIC', ?, ?)", datasetId, now, now);
        for (String listingId : List.of("ETF-A", "ETF-B")) {
            jdbcTemplate.update("INSERT INTO dataset_listings (dataset_id, listing_id, instrument_id, symbol, quote_currency, calendar_id) " +
                    "VALUES (?, ?, ?, ?, 'EUR', 'XETR')", datasetId, listingId, "inst-" + listingId, listingId);
        }
        for (int month = 0; month < 15; month++) {
            String date = YearMonth.of(2023, 12).plusMonths(month).atEndOfMonth().toString();
            jdbcTemplate.update("INSERT INTO dataset_sessions (dataset_id, calendar_id, session_date, open_time, close_time, session_type) " +
                            "VALUES (?, 'XETR', ?, ?, ?, 'TRADING')",
                    datasetId, date, date + "T08:00:00Z", date + "T16:30:00Z");
            for (String listingId : List.of("ETF-A", "ETF-B")) {
                jdbcTemplate.update("INSERT INTO historical_bars (dataset_id, listing_id, session_date, open, high, low, close, volume, available_at) " +
                                "VALUES (?, ?, ?, '100.00', '100.00', '100.00', '100.00', 1000, ?)",
                        datasetId, listingId, date,
                        month == 14 && "ETF-B".equals(listingId) ? "2025-03-05T12:00:00Z" : date + "T16:35:00Z");
            }
        }
        for (String date : List.of("2025-03-03", "2025-03-31")) {
            jdbcTemplate.update("INSERT INTO dataset_sessions (dataset_id, calendar_id, session_date, open_time, close_time, session_type) " +
                            "VALUES (?, 'XETR', ?, ?, ?, 'TRADING')",
                    datasetId, date, date + "T08:00:00Z", date + "T16:30:00Z");
            for (String listingId : List.of("ETF-A", "ETF-B")) {
                jdbcTemplate.update("INSERT INTO historical_bars (dataset_id, listing_id, session_date, open, high, low, close, volume, available_at) " +
                                "VALUES (?, ?, ?, '100.00', '100.00', '100.00', '100.00', 1000, ?)",
                        datasetId, listingId, date, date + "T16:35:00Z");
            }
        }
        BacktestDtos.UniverseDto universe = universeService.createUniverse("s2-owner",
                new BacktestDtos.CreateUniverseRequest("S2 universe", "1.0.0", "Synthetic",
                        datasetId, "XETR", "EUR", "SYNTHETIC", List.of("ETF-A", "ETF-B")));

        BacktestDtos.CreateBacktestRequest valid = new BacktestDtos.CreateBacktestRequest(
                datasetId, "ETF-A", "ETF-A", "2025-01-31T17:00:00Z", "2025-01-31", "2025-02-28",
                "1000.00", "EUR", "1.00", "0", "0", "ETF_MOMENTUM_12_1_V1", "1.0.0",
                universe.id(), "{\"k\":2}", null);
        MvcResult created = mockMvc.perform(post("/api/research/backtests")
                        .header("X-User-Id", "s2-owner")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(valid)))
                .andExpect(status().isAccepted()).andReturn();
        String runId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();
        String runStatus = "QUEUED";
        for (int attempt = 0; attempt < 100; attempt++) {
            runStatus = jdbcTemplate.queryForObject("SELECT status FROM backtest_runs WHERE id = ?", String.class, runId);
            if ("COMPLETED".equals(runStatus) || "FAILED".equals(runStatus)) break;
            Thread.sleep(25);
        }
        assertEquals("COMPLETED", runStatus, () -> jdbcTemplate.queryForObject(
                "SELECT failure_reason FROM backtest_runs WHERE id = ?", String.class, runId));

        BacktestDtos.CreateBacktestRequest delayed = new BacktestDtos.CreateBacktestRequest(
                datasetId, "ETF-A", "ETF-A", "2025-01-31T17:00:00Z", "2025-01-31", "2025-03-31",
                "1000.00", "EUR", "1.00", "0", "0", "ETF_MOMENTUM_12_1_V1", "1.0.0",
                universe.id(), "{\"k\":2}", null);
        MvcResult delayedCreated = mockMvc.perform(post("/api/research/backtests")
                        .header("X-User-Id", "s2-owner")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(delayed)))
                .andExpect(status().isAccepted()).andReturn();
        String delayedRunId = objectMapper.readTree(delayedCreated.getResponse().getContentAsString()).get("id").asText();
        String delayedStatus = "QUEUED";
        for (int attempt = 0; attempt < 100; attempt++) {
            delayedStatus = jdbcTemplate.queryForObject("SELECT status FROM backtest_runs WHERE id = ?", String.class, delayedRunId);
            if ("COMPLETED".equals(delayedStatus) || "FAILED".equals(delayedStatus)) break;
            Thread.sleep(25);
        }
        assertEquals("COMPLETED", delayedStatus, () -> jdbcTemplate.queryForObject(
                "SELECT failure_reason FROM backtest_runs WHERE id = ?", String.class, delayedRunId));
        assertEquals("2025-03-31", jdbcTemplate.queryForObject(
                "SELECT scheduled_execution_date FROM backtest_signals WHERE run_id = ? AND evaluation_date = '2025-02-28'",
                String.class, delayedRunId));

        BacktestDtos.CreateBacktestRequest badK = new BacktestDtos.CreateBacktestRequest(
                datasetId, "ETF-A", "ETF-A", "2025-01-31T17:00:00Z", "2025-01-31", "2025-02-28",
                "1000.00", "EUR", "1.00", "0", "0", "ETF_MOMENTUM_12_1_V1", "1.0.0",
                universe.id(), "{\"k\":2.5}", null);
        mockMvc.perform(post("/api/research/backtests")
                        .header("X-User-Id", "s2-owner").header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(badK)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/research/backtests")
                        .header("X-User-Id", "other-owner").header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(valid)))
                .andExpect(status().isBadRequest());
    }
}
