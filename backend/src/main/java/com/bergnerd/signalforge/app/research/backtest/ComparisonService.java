package com.bergnerd.signalforge.app.research.backtest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ComparisonService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final BacktestAnalyticsCalculator analyticsCalculator;
    private final ExperimentService experimentService;

    public BacktestDtos.BacktestComparisonDto createComparison(
            String ownerId,
            String idempotencyKey,
            BacktestDtos.CreateComparisonRequest request
    ) {
        String uid = (ownerId == null || ownerId.isBlank()) ? "default" : ownerId;
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key header is required");
        }
        if (request.runIds() == null || request.runIds().size() < 2 || request.runIds().size() > 3) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Comparisons require 2 or 3 completed runs, got: " + (request.runIds() != null ? request.runIds().size() : 0));
        }

        // Check existing idempotency
        List<Map<String, Object>> existing = jdbcTemplate.queryForList(
                "SELECT * FROM backtest_comparisons WHERE owner_id = ? AND idempotency_key = ?",
                uid, idempotencyKey
        );
        if (!existing.isEmpty()) {
            return getComparison((String) existing.get(0).get("id"), uid);
        }

        // Load runs and their configs
        List<Map<String, Object>> runRows = new ArrayList<>();
        List<BacktestDtos.BacktestNormalizedConfig> configs = new ArrayList<>();
        for (String runId : request.runIds()) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT * FROM backtest_runs WHERE id = ?", runId
            );
            if (rows.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Run not found: " + runId);
            }
            Map<String, Object> r = rows.get(0);
            String runOwner = (String) r.get("owner_id");
            if (!uid.equals(runOwner) && !"default".equals(runOwner)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied to run: " + runId);
            }
            String status = (String) r.get("status");
            if (!"COMPLETED".equalsIgnoreCase(status)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot compare uncompleted run " + runId + " (current status: " + status + ")");
            }
            runRows.add(r);

            String configJson = (String) r.get("config_json");
            try {
                configs.add(objectMapper.readValue(configJson, BacktestDtos.BacktestNormalizedConfig.class));
            } catch (Exception e) {
                throw new IllegalStateException("Failed to parse config for run " + runId, e);
            }
        }

        // Field-by-field matched comparison
        List<BacktestDtos.ComparisonMismatchReason> mismatches = new ArrayList<>();
        BacktestDtos.BacktestNormalizedConfig baseConfig = configs.get(0);
        Map<String, Object> baseRun = runRows.get(0);

        for (int i = 1; i < configs.size(); i++) {
            BacktestDtos.BacktestNormalizedConfig c = configs.get(i);
            Map<String, Object> r = runRows.get(i);
            String runIdA = (String) baseRun.get("id");
            String runIdB = (String) r.get("id");

            checkMatch(mismatches, "datasetId", baseConfig.datasetId(), c.datasetId(), runIdA, runIdB);
            checkMatch(mismatches, "calendarId", baseConfig.calendarId(), c.calendarId(), runIdA, runIdB);
            checkMatch(mismatches, "datasetInputChecksum", baseConfig.datasetInputChecksum(), c.datasetInputChecksum(), runIdA, runIdB);
            checkMatch(mismatches, "datasetContentChecksum", baseConfig.datasetContentChecksum(), c.datasetContentChecksum(), runIdA, runIdB);
            checkMatch(mismatches, "benchmarkListingId", baseConfig.benchmarkListingId(), c.benchmarkListingId(), runIdA, runIdB);
            checkMatch(mismatches, "quoteCurrency", baseConfig.quoteCurrency(), c.quoteCurrency(), runIdA, runIdB);
            checkMatch(mismatches, "initialCash", baseConfig.initialCash(), c.initialCash(), runIdA, runIdB);
            checkMatch(mismatches, "effectiveStartDate", baseConfig.effectiveStartDate(), c.effectiveStartDate(), runIdA, runIdB);
            checkMatch(mismatches, "effectiveEndDate", baseConfig.effectiveEndDate(), c.effectiveEndDate(), runIdA, runIdB);
            checkMatch(mismatches, "commissionPerFill", baseConfig.commissionPerFill(), c.commissionPerFill(), runIdA, runIdB);
            checkMatch(mismatches, "spreadBps", baseConfig.spreadBps(), c.spreadBps(), runIdA, runIdB);
            checkMatch(mismatches, "slippageBps", baseConfig.slippageBps(), c.slippageBps(), runIdA, runIdB);
            checkMatch(mismatches, "engineVersion", baseConfig.engineVersion(), c.engineVersion(), runIdA, runIdB);
        }

        String compStatus = mismatches.isEmpty() ? "MATCHED" : "MISMATCHED";
        String mismatchReasonsJson;
        try {
            mismatchReasonsJson = objectMapper.writeValueAsString(mismatches);
        } catch (JsonProcessingException e) {
            mismatchReasonsJson = "[]";
        }

        // If MATCHED, generate comparative metrics, rolling 5-year windows, and comparative charts
        String summaryJson = null;
        if ("MATCHED".equals(compStatus)) {
            summaryJson = buildComparisonSummaryJson(request.runIds(), runRows, baseConfig);
        }

        String compId = "cmp-" + UUID.randomUUID();
        String now = Instant.now().toString();

        jdbcTemplate.update(
                "INSERT INTO backtest_comparisons (" +
                        "id, owner_id, idempotency_key, name, benchmark_listing_id, dataset_id, " +
                        "effective_start_date, effective_end_date, initial_cash, currency, status, " +
                        "mismatch_reasons_json, summary_json, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                compId, uid, idempotencyKey, request.name().trim(),
                baseConfig.benchmarkListingId(), baseConfig.datasetId(),
                baseConfig.effectiveStartDate(), baseConfig.effectiveEndDate(),
                baseConfig.initialCash(), baseConfig.quoteCurrency(),
                compStatus, mismatchReasonsJson, summaryJson, now, now
        );

        for (int idx = 0; idx < request.runIds().size(); idx++) {
            String rid = request.runIds().get(idx);
            String role = (String) runRows.get(idx).get("strategy_id");
            jdbcTemplate.update(
                    "INSERT INTO backtest_comparison_items (comparison_id, run_id, role, ordinal) VALUES (?, ?, ?, ?)",
                    compId, rid, role != null ? role : "CANDIDATE", idx
            );
        }

        return getComparison(compId, uid);
    }

    public BacktestDtos.BacktestComparisonDto getComparison(String id, String ownerId) {
        String uid = (ownerId == null || ownerId.isBlank()) ? "default" : ownerId;
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM backtest_comparisons WHERE id = ? AND (owner_id = ? OR owner_id = 'default')",
                id, uid
        );
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Comparison not found: " + id);
        }
        Map<String, Object> r = rows.get(0);

        List<Map<String, Object>> itemRows = jdbcTemplate.queryForList(
                "SELECT comparison_id, run_id, role, ordinal FROM backtest_comparison_items WHERE comparison_id = ? ORDER BY ordinal ASC",
                id
        );

        List<BacktestDtos.BacktestComparisonItemDto> items = new ArrayList<>();
        List<String> runIds = new ArrayList<>();
        for (Map<String, Object> ir : itemRows) {
            String runId = (String) ir.get("run_id");
            runIds.add(runId);
            items.add(new BacktestDtos.BacktestComparisonItemDto(
                    id, runId, (String) ir.get("role"), ((Number) ir.get("ordinal")).intValue()
            ));

            // Check if run belongs to an experiment and record holdout exposure
            List<Map<String, Object>> runExperiment = jdbcTemplate.queryForList(
                    "SELECT experiment_id FROM backtest_runs WHERE id = ?", runId
            );
            if (!runExperiment.isEmpty() && runExperiment.get(0).get("experiment_id") != null) {
                String expId = (String) runExperiment.get(0).get("experiment_id");
                experimentService.recordExposure(expId, runId, "COMPARISON_VIEW", uid,
                        "{\"comparisonId\":\"" + id + "\"}");
            }
        }

        List<BacktestDtos.ComparisonMismatchReason> mismatches = List.of();
        String mmJson = (String) r.get("mismatch_reasons_json");
        if (mmJson != null && !mmJson.isBlank()) {
            try {
                mismatches = objectMapper.readValue(mmJson, objectMapper.getTypeFactory().constructCollectionType(List.class, BacktestDtos.ComparisonMismatchReason.class));
            } catch (Exception e) {
                log.warn("Failed to parse mismatch_reasons_json for comparison {}: {}", id, e.getMessage());
            }
        }

        BacktestDtos.ComparisonSummaryDto summary = null;
        String sJson = (String) r.get("summary_json");
        if (sJson != null && !sJson.isBlank()) {
            try {
                summary = objectMapper.readValue(sJson, BacktestDtos.ComparisonSummaryDto.class);
            } catch (Exception e) {
                log.warn("Failed to parse summary_json for comparison {}: {}", id, e.getMessage());
            }
        }

        return new BacktestDtos.BacktestComparisonDto(
                (String) r.get("id"),
                (String) r.get("owner_id"),
                (String) r.get("idempotency_key"),
                (String) r.get("name"),
                (String) r.get("benchmark_listing_id"),
                (String) r.get("dataset_id"),
                (String) r.get("effective_start_date"),
                (String) r.get("effective_end_date"),
                (String) r.get("initial_cash"),
                (String) r.get("currency"),
                (String) r.get("status"),
                mismatches,
                items,
                summary,
                (String) r.get("created_at"),
                (String) r.get("updated_at")
        );
    }

    public BacktestDtos.PagedResponse<BacktestDtos.BacktestComparisonDto> listComparisons(String ownerId, int limit, int offset) {
        String uid = (ownerId == null || ownerId.isBlank()) ? "default" : ownerId;
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM backtest_comparisons WHERE owner_id = ? OR owner_id = 'default'",
                Integer.class, uid
        );
        int total = count != null ? count : 0;

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id FROM backtest_comparisons WHERE owner_id = ? OR owner_id = 'default' " +
                        "ORDER BY created_at DESC LIMIT ? OFFSET ?",
                uid, limit, offset
        );

        List<BacktestDtos.BacktestComparisonDto> list = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            list.add(getComparison((String) r.get("id"), uid));
        }

        return new BacktestDtos.PagedResponse<>(list, total, limit, offset, offset + list.size() < total);
    }

    private void checkMatch(List<BacktestDtos.ComparisonMismatchReason> list, String field, String valA, String valB, String runA, String runB) {
        String sA = valA != null ? valA.trim() : "";
        String sB = valB != null ? valB.trim() : "";
        if (!sA.equalsIgnoreCase(sB)) {
            list.add(new BacktestDtos.ComparisonMismatchReason(
                    field, sA, sB, "Field '" + field + "' mismatch between " + runA + " (" + sA + ") and " + runB + " (" + sB + ")"
            ));
        }
    }

    private String buildComparisonSummaryJson(
            List<String> runIds,
            List<Map<String, Object>> runRows,
            BacktestDtos.BacktestNormalizedConfig config
    ) {
        try {
            // Load calendar sessions for the shared calendar
            List<BacktestDataReader.SessionRecord> allSessions = jdbcTemplate.query(
                    "SELECT session_date, open_time, close_time, session_type FROM dataset_sessions " +
                            "WHERE dataset_id = ? AND calendar_id = ? ORDER BY session_date ASC",
                    (rs, rowNum) -> new BacktestDataReader.SessionRecord(
                            rs.getString("session_date"),
                            rs.getString("open_time"),
                            rs.getString("close_time"),
                            rs.getString("session_type")
                    ),
                    config.datasetId(), config.calendarId()
            );

            // Load daily equity series for each run
            Map<String, List<BacktestDtos.DailyEquityPoint>> candidateSeriesByRun = new LinkedHashMap<>();
            List<BacktestDtos.DailyEquityPoint> benchmarkSeries = null;

            for (String rid : runIds) {
                List<BacktestDtos.DailyEquityPoint> candPoints = jdbcTemplate.query(
                        "SELECT session_date, series_type, cash, holdings_value, receivables, total_equity, " +
                                "daily_return, drawdown, peak_equity, units, cost_basis, raw_close, point_kind, observation_time " +
                                "FROM backtest_daily_equity WHERE run_id = ? AND series_type = 'CANDIDATE' ORDER BY session_date ASC",
                        (rs, rowNum) -> new BacktestDtos.DailyEquityPoint(
                                rs.getString("session_date"), rs.getString("series_type"),
                                rs.getString("cash"), rs.getString("holdings_value"), rs.getString("receivables"),
                                rs.getString("total_equity"), rs.getObject("daily_return") != null ? rs.getDouble("daily_return") : null,
                                rs.getDouble("drawdown"), rs.getString("peak_equity"), rs.getString("units"),
                                rs.getString("cost_basis"), rs.getString("raw_close"), rs.getString("point_kind"), rs.getString("observation_time")
                        ),
                        rid
                );
                candidateSeriesByRun.put(rid, candPoints);

                if (benchmarkSeries == null) {
                    benchmarkSeries = jdbcTemplate.query(
                            "SELECT session_date, series_type, cash, holdings_value, receivables, total_equity, " +
                                    "daily_return, drawdown, peak_equity, units, cost_basis, raw_close, point_kind, observation_time " +
                                    "FROM backtest_daily_equity WHERE run_id = ? AND series_type = 'BENCHMARK' ORDER BY session_date ASC",
                            (rs, rowNum) -> new BacktestDtos.DailyEquityPoint(
                                    rs.getString("session_date"), rs.getString("series_type"),
                                    rs.getString("cash"), rs.getString("holdings_value"), rs.getString("receivables"),
                                    rs.getString("total_equity"), rs.getObject("daily_return") != null ? rs.getDouble("daily_return") : null,
                                    rs.getDouble("drawdown"), rs.getString("peak_equity"), rs.getString("units"),
                                    rs.getString("cost_basis"), rs.getString("raw_close"), rs.getString("point_kind"), rs.getString("observation_time")
                            ),
                            rid
                    );
                }
            }

            // Parse summaries from each run
            Map<String, BacktestDtos.BacktestAnalyticsSummary> candSummaries = new LinkedHashMap<>();
            BacktestDtos.BacktestAnalyticsSummary benchSummary = null;

            for (Map<String, Object> r : runRows) {
                String rid = (String) r.get("id");
                String sumJson = (String) r.get("summary_json");
                if (sumJson != null && !sumJson.isBlank()) {
                    Map<String, Object> m = objectMapper.readValue(sumJson, Map.class);
                    if (m.containsKey("candidate")) {
                        candSummaries.put(rid, objectMapper.convertValue(m.get("candidate"), BacktestDtos.BacktestAnalyticsSummary.class));
                    }
                    if (benchSummary == null && m.containsKey("benchmark")) {
                        benchSummary = objectMapper.convertValue(m.get("benchmark"), BacktestDtos.BacktestAnalyticsSummary.class);
                    }
                }
            }

            // Calculate rolling 5-year windows for each run and for benchmark
            Map<String, BacktestDtos.RollingWindowSummaryDto> rollingWindowsByRun = new LinkedHashMap<>();
            for (String rid : runIds) {
                rollingWindowsByRun.put(rid, analyticsCalculator.calculateRolling5YearWindows(candidateSeriesByRun.get(rid), allSessions));
            }
            if (benchmarkSeries != null) {
                rollingWindowsByRun.put("BENCHMARK", analyticsCalculator.calculateRolling5YearWindows(benchmarkSeries, allSessions));
            }

            // Build metric rows
            List<BacktestDtos.ComparisonMetricRow> metricRows = new ArrayList<>();
            addMetricRow(metricRows, "Initial Cash", "initialCash", runIds, candSummaries, benchSummary, s -> s != null ? s.endingCash() : null, false);
            addMetricRow(metricRows, "Final Equity", "finalEquity", runIds, candSummaries, benchSummary, s -> s != null ? s.endingHoldingsValue() : null, false);
            addMetricRow(metricRows, "Net Cumulative Return", "cumulativeReturn", runIds, candSummaries, benchSummary, s -> s != null ? String.format("%.2f%%", s.cumulativeReturn() * 100) : "0.00%", true);
            addMetricRow(metricRows, "CAGR", "cagr", runIds, candSummaries, benchSummary, s -> s != null && s.cagr() != null ? String.format("%.2f%%", s.cagr() * 100) : "N/A", true);
            addMetricRow(metricRows, "Max Drawdown", "maxDrawdown", runIds, candSummaries, benchSummary, s -> s != null ? String.format("%.2f%%", s.maxDrawdown() * 100) : "0.00%", true);
            addMetricRow(metricRows, "Annualized Volatility", "volatility", runIds, candSummaries, benchSummary, s -> s != null && s.annualizedVolatility() != null ? String.format("%.2f%%", s.annualizedVolatility() * 100) : "N/A", true);
            addMetricRow(metricRows, "Total Commissions", "commissions", runIds, candSummaries, benchSummary, s -> s != null ? s.totalCommissions() + " EUR" : "0.00 EUR", false);
            addMetricRow(metricRows, "Total Spread & Slippage", "spreadSlippage", runIds, candSummaries, benchSummary, s -> s != null ? s.totalSpreadSlippageEstimate() + " EUR" : "0.00 EUR", false);
            addMetricRow(metricRows, "Turnover", "turnover", runIds, candSummaries, benchSummary, s -> s != null && s.turnover() != null ? String.format("%.4f", s.turnover()) : "0.0000", false);

            BacktestDtos.ComparisonSummaryDto summaryDto = new BacktestDtos.ComparisonSummaryDto(
                    metricRows,
                    rollingWindowsByRun,
                    "Comparison evaluated on matched historical parameters. Past performance and rolling 5-year windows are descriptive and not predictive."
            );

            return objectMapper.writeValueAsString(summaryDto);
        } catch (Exception e) {
            log.error("Failed to build comparison summary: {}", e.getMessage(), e);
            return null;
        }
    }

    private void addMetricRow(
            List<BacktestDtos.ComparisonMetricRow> rows,
            String label,
            String metricKey,
            List<String> runIds,
            Map<String, BacktestDtos.BacktestAnalyticsSummary> candSummaries,
            BacktestDtos.BacktestAnalyticsSummary benchSummary,
            java.util.function.Function<BacktestDtos.BacktestAnalyticsSummary, String> extractor,
            boolean isPercentage
    ) {
        String benchmarkVal = extractor.apply(benchSummary);
        Map<String, String> valuesByRunId = new LinkedHashMap<>();
        Map<String, String> diffAgainstBench = new LinkedHashMap<>();

        for (String rid : runIds) {
            BacktestDtos.BacktestAnalyticsSummary s = candSummaries.get(rid);
            String val = extractor.apply(s);
            valuesByRunId.put(rid, val != null ? val : "N/A");
            diffAgainstBench.put(rid, "N/A");
        }

        rows.add(new BacktestDtos.ComparisonMetricRow(
                metricKey, label, benchmarkVal, valuesByRunId, diffAgainstBench
        ));
    }
}
