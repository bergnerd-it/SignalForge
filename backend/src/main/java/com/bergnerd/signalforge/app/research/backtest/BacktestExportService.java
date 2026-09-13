package com.bergnerd.signalforge.app.research.backtest;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Slf4j
@Service
@RequiredArgsConstructor
public class BacktestExportService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public void validateExportable(String runId, String ownerId) {
        String uid = (ownerId == null || ownerId.isBlank()) ? "default" : ownerId;
        List<Map<String, Object>> runRows = jdbcTemplate.queryForList(
                "SELECT id, status FROM backtest_runs WHERE id = ? AND owner_id = ?", runId, uid
        );
        if (runRows.isEmpty()) {
            throw new IllegalArgumentException("Backtest run not found: " + runId);
        }
        String status = (String) runRows.get(0).get("status");
        if (!"COMPLETED".equalsIgnoreCase(status)) {
            throw new IllegalStateException("Cannot export incomplete backtest run (current status: " + status + ")");
        }
    }

    public void streamExportZip(String runId, String ownerId, OutputStream outputStream) throws IOException {
        String uid = (ownerId == null || ownerId.isBlank()) ? "default" : ownerId;
        List<Map<String, Object>> runRows = jdbcTemplate.queryForList(
                "SELECT * FROM backtest_runs WHERE id = ? AND owner_id = ?", runId, uid
        );
        if (runRows.isEmpty()) {
            throw new IllegalArgumentException("Backtest run not found: " + runId);
        }
        Map<String, Object> run = runRows.get(0);
        String status = (String) run.get("status");
        if (!"COMPLETED".equalsIgnoreCase(status)) {
            throw new IllegalStateException("Cannot export incomplete backtest run (current status: " + status + ")");
        }

        // Bounded export preflight check: ensure row counts do not exceed bounds before streaming response bytes
        int equityCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM backtest_daily_equity WHERE run_id = ?", Integer.class, runId);
        int eventsCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM backtest_events WHERE run_id = ?", Integer.class, runId);
        int ordersCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM backtest_orders WHERE run_id = ?", Integer.class, runId);
        int holdingsCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM backtest_holdings WHERE run_id = ?", Integer.class, runId);

        final int MAX_SERIES_ROWS = 100000;
        final int MAX_HOLDINGS_ROWS = 10000;
        if (equityCount > MAX_SERIES_ROWS || eventsCount > MAX_SERIES_ROWS || ordersCount > MAX_SERIES_ROWS || holdingsCount > MAX_HOLDINGS_ROWS) {
            throw new IllegalArgumentException("Export bounds exceeded: run has " +
                    Math.max(Math.max(equityCount, eventsCount), Math.max(ordersCount, holdingsCount)) +
                    " rows, exceeding maximum export limit of " + MAX_SERIES_ROWS);
        }

        try (ZipOutputStream zos = new ZipOutputStream(outputStream, StandardCharsets.UTF_8)) {
            Writer writer = new OutputStreamWriter(zos, StandardCharsets.UTF_8);

            // 1. manifest.json: built verbatim from frozen run metadata and config snapshot
            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("runId", run.get("id"));
            manifest.put("ownerId", run.get("owner_id"));
            manifest.put("idempotencyKey", run.get("idempotency_key"));
            manifest.put("canonicalHash", run.get("canonical_hash"));

            String configJson = (String) run.get("config_json");
            if (configJson != null && !configJson.isBlank()) {
                try {
                    BacktestDtos.BacktestNormalizedConfig cfg = objectMapper.readValue(
                            configJson, BacktestDtos.BacktestNormalizedConfig.class
                    );
                    manifest.put("strategyId", cfg.strategyId());
                    manifest.put("strategyVersion", cfg.strategyVersion());
                    manifest.put("datasetId", cfg.datasetId());
                    manifest.put("datasetInputChecksum", cfg.datasetInputChecksum());
                    manifest.put("datasetContentChecksum", cfg.datasetContentChecksum());
                    manifest.put("parserVersion", cfg.parserVersion());
                    manifest.put("schemaVersion", cfg.schemaVersion());
                    manifest.put("calendarId", cfg.calendarId());
                    manifest.put("calendarTimezone", cfg.calendarTimezone());
                    manifest.put("coverageStart", cfg.coverageStart());
                    manifest.put("coverageEnd", cfg.coverageEnd());
                    manifest.put("candidateListingId", cfg.candidateListingId());
                    manifest.put("benchmarkListingId", cfg.benchmarkListingId());
                    manifest.put("quoteCurrency", cfg.quoteCurrency());
                    manifest.put("initialCash", cfg.initialCash());
                    manifest.put("evaluationCutoff", cfg.evaluationCutoff());
                    manifest.put("selectedEvaluationSession", cfg.selectedEvaluationSession());
                    manifest.put("selectedEndSession", cfg.selectedEndSession());
                    manifest.put("requestedStartDate", cfg.requestedStartDate());
                    manifest.put("requestedEndDate", cfg.requestedEndDate());
                    manifest.put("effectiveStartDate", cfg.effectiveStartDate());
                    manifest.put("effectiveEndDate", cfg.effectiveEndDate());
                    manifest.put("commissionPerFill", cfg.commissionPerFill());
                    manifest.put("spreadBps", cfg.spreadBps());
                    manifest.put("slippageBps", cfg.slippageBps());
                    manifest.put("costModelVersion", cfg.costModelVersion());
                    manifest.put("accountingPolicy", cfg.accountingVersion());
                    manifest.put("executionModelVersion", cfg.executionModelVersion());
                    manifest.put("engineVersion", cfg.engineVersion());
                    manifest.put("sourceCommit", cfg.sourceCommit());
                    manifest.put("dirtyFlag", cfg.dirtyFlag());
                    manifest.put("classification", cfg.classification());
                    manifest.put("availabilityAssumptions", cfg.availabilityAssumptions());
                } catch (Exception e) {
                    log.warn("Failed to parse config_json for manifest, falling back to raw fields", e);
                }
            }

            manifest.putIfAbsent("strategyId", run.get("strategy_id"));
            manifest.putIfAbsent("strategyVersion", run.get("strategy_version"));
            manifest.putIfAbsent("datasetId", run.get("dataset_id"));
            manifest.putIfAbsent("candidateListingId", run.get("candidate_listing_id"));
            manifest.putIfAbsent("benchmarkListingId", run.get("benchmark_listing_id"));
            manifest.putIfAbsent("initialCash", run.get("initial_cash"));
            manifest.putIfAbsent("currency", run.get("currency"));
            manifest.putIfAbsent("createdAt", run.get("created_at"));
            manifest.putIfAbsent("completedAt", run.get("completed_at"));
            manifest.put("endLiquidationConvention", "MARK_TO_MARKET_EXCLUDES_HYPOTHETICAL_LIQUIDATION_COSTS");
            manifest.put("turnoverFormula", "TOTAL_PURCHASES_DIVIDED_BY_AVERAGE_EQUITY");
            manifest.put("receivableTreatment", "CONTRIBUTES_TO_EQUITY_UNSPENDABLE_UNTIL_PAYMENT");
            manifest.put("dataWarning", "SYNTHETIC_DATA_TESTS_SOFTWARE_BEHAVIOR_ONLY_NOT_REAL_WORLD_PERFORMANCE");

            zos.putNextEntry(new ZipEntry("manifest.json"));
            writer.write(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(manifest));
            writer.flush();
            zos.closeEntry();

            // 2. summary.json
            String summaryJson = (String) run.get("summary_json");
            if (summaryJson != null && !summaryJson.isBlank()) {
                zos.putNextEntry(new ZipEntry("summary.json"));
                Object summaryObj = objectMapper.readValue(summaryJson, Object.class);
                writer.write(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(summaryObj));
                writer.flush();
                zos.closeEntry();
            }

            // 3. equity_series.csv (streamed directly)
            zos.putNextEntry(new ZipEntry("equity_series.csv"));
            writer.write("series_type,session_date,cash,holdings_value,receivables,total_equity,daily_return,drawdown,peak_equity,units,cost_basis,raw_close\n");
            jdbcTemplate.query(
                    "SELECT series_type, session_date, cash, holdings_value, receivables, total_equity, " +
                            "daily_return, drawdown, peak_equity, units, cost_basis, raw_close " +
                            "FROM backtest_daily_equity WHERE run_id = ? ORDER BY session_date ASC, series_type ASC",
                    rs -> {
                        try {
                            writer.write(escapeCsvText(rs.getString("series_type")) + ",");
                            writer.write(formatNumeric(rs.getString("session_date")) + ",");
                            writer.write(formatNumeric(rs.getString("cash")) + ",");
                            writer.write(formatNumeric(rs.getString("holdings_value")) + ",");
                            writer.write(formatNumeric(rs.getString("receivables")) + ",");
                            writer.write(formatNumeric(rs.getString("total_equity")) + ",");
                            writer.write((rs.getObject("daily_return") != null ? formatNumeric(rs.getString("daily_return")) : "") + ",");
                            writer.write(formatNumeric(rs.getString("drawdown")) + ",");
                            writer.write(formatNumeric(rs.getString("peak_equity")) + ",");
                            writer.write(formatNumeric(rs.getString("units")) + ",");
                            writer.write(formatNumeric(rs.getString("cost_basis")) + ",");
                            writer.write(formatNumeric(rs.getString("raw_close")) + "\n");
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    },
                    runId
            );
            writer.flush();
            zos.closeEntry();

            // 4. events.csv (streamed directly)
            zos.putNextEntry(new ZipEntry("events.csv"));
            writer.write("series_type,event_seq,event_type,event_date,event_time,description,cash_delta,units_delta,basis_delta,receivable_delta\n");
            jdbcTemplate.query(
                    "SELECT series_type, event_seq, event_type, event_date, event_time, description, " +
                            "cash_delta, units_delta, basis_delta, receivable_delta " +
                            "FROM backtest_events WHERE run_id = ? ORDER BY event_seq ASC, series_type ASC",
                    rs -> {
                        try {
                            writer.write(escapeCsvText(rs.getString("series_type")) + ",");
                            writer.write(rs.getInt("event_seq") + ",");
                            writer.write(escapeCsvText(rs.getString("event_type")) + ",");
                            writer.write(formatNumeric(rs.getString("event_date")) + ",");
                            writer.write(escapeCsvText(rs.getString("event_time")) + ",");
                            writer.write(escapeCsvText(rs.getString("description")) + ",");
                            writer.write(formatNumeric(rs.getString("cash_delta")) + ",");
                            writer.write(formatNumeric(rs.getString("units_delta")) + ",");
                            writer.write(formatNumeric(rs.getString("basis_delta")) + ",");
                            writer.write(formatNumeric(rs.getString("receivable_delta")) + "\n");
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    },
                    runId
            );
            writer.flush();
            zos.closeEntry();

            // 5. orders.csv (streamed directly)
            zos.putNextEntry(new ZipEntry("orders.csv"));
            writer.write("series_type,order_type,listing_id,session_date,requested_quantity,executed_quantity,raw_open,fill_price,commission,spread_cost,slippage_cost,status,skip_reason\n");
            jdbcTemplate.query(
                    "SELECT series_type, order_type, listing_id, session_date, requested_quantity, executed_quantity, " +
                            "raw_open, fill_price, commission, spread_cost, slippage_cost, status, skip_reason " +
                            "FROM backtest_orders WHERE run_id = ? ORDER BY session_date ASC, series_type ASC",
                    rs -> {
                        try {
                            writer.write(escapeCsvText(rs.getString("series_type")) + ",");
                            writer.write(escapeCsvText(rs.getString("order_type")) + ",");
                            writer.write(escapeCsvText(rs.getString("listing_id")) + ",");
                            writer.write(formatNumeric(rs.getString("session_date")) + ",");
                            writer.write(formatNumeric(rs.getString("requested_quantity")) + ",");
                            writer.write(formatNumeric(rs.getString("executed_quantity")) + ",");
                            writer.write(formatNumeric(rs.getString("raw_open")) + ",");
                            writer.write(formatNumeric(rs.getString("fill_price")) + ",");
                            writer.write(formatNumeric(rs.getString("commission")) + ",");
                            writer.write(formatNumeric(rs.getString("spread_cost")) + ",");
                            writer.write(formatNumeric(rs.getString("slippage_cost")) + ",");
                            writer.write(escapeCsvText(rs.getString("status")) + ",");
                            writer.write(escapeCsvText(rs.getString("skip_reason")) + "\n");
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    },
                    runId
            );
            writer.flush();
            zos.closeEntry();

            // 6. holdings.csv (streamed directly)
            zos.putNextEntry(new ZipEntry("holdings.csv"));
            writer.write("series_type,listing_id,units,total_cost_basis,average_cost,current_price,market_value,unrealized_gain\n");
            jdbcTemplate.query(
                    "SELECT series_type, listing_id, units, total_cost_basis, average_cost, current_price, market_value, unrealized_gain " +
                            "FROM backtest_holdings WHERE run_id = ? ORDER BY series_type ASC",
                    rs -> {
                        try {
                            writer.write(escapeCsvText(rs.getString("series_type")) + ",");
                            writer.write(escapeCsvText(rs.getString("listing_id")) + ",");
                            writer.write(formatNumeric(rs.getString("units")) + ",");
                            writer.write(formatNumeric(rs.getString("total_cost_basis")) + ",");
                            writer.write(formatNumeric(rs.getString("average_cost")) + ",");
                            writer.write(formatNumeric(rs.getString("current_price")) + ",");
                            writer.write(formatNumeric(rs.getString("market_value")) + ",");
                            writer.write(formatNumeric(rs.getString("unrealized_gain")) + "\n");
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    },
                    runId
            );
            writer.flush();
            zos.closeEntry();
        }
    }

    public byte[] generateExportZip(String runId, String ownerId) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        streamExportZip(runId, ownerId, baos);
        return baos.toByteArray();
    }

    private String formatNumeric(String val) {
        if (val == null || val.isBlank()) return "";
        return val.trim();
    }

    private String escapeCsvText(String val) {
        if (val == null) return "";
        String clean = val;
        // Formula injection protection for spreadsheet software:
        // Only escape if starting with =, +, -, @ and NOT a valid numeric decimal!
        if ((clean.startsWith("=") || clean.startsWith("+") || clean.startsWith("-") || clean.startsWith("@"))
                && !isNumeric(clean)) {
            clean = "'" + clean;
        }
        if (clean.contains(",") || clean.contains("\"") || clean.contains("\n") || clean.contains("\r")) {
            return "\"" + clean.replace("\"", "\"\"") + "\"";
        }
        return clean;
    }

    private boolean isNumeric(String str) {
        if (str == null || str.isBlank()) return false;
        try {
            new BigDecimal(str.trim());
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
