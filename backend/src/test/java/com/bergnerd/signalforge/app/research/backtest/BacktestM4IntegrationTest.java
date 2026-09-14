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
                .andExpect(jsonPath("$.length()").value(3))
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

        String expPayload = """
                {
                  "name": "Momentum Lookback Study",
                  "version": 1,
                  "strategyId": "ETF_MOMENTUM_12_1_V1",
                  "strategyVersion": "1.0.0",
                  "datasetId": "ds-m4-test",
                  "benchmarkListingId": "EUNL.DE",
                  "developmentStartDate": "2020-01-01",
                  "developmentEndDate": "2022-12-31",
                  "holdoutStartDate": "2023-01-01",
                  "holdoutEndDate": "2024-12-31",
                  "declaredHoldoutStatus": "UNEXAMINED",
                  "parametersJson": "{\\"k\\":2}"
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
}
