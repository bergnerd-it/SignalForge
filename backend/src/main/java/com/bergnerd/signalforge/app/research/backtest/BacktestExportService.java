package com.bergnerd.signalforge.app.research.backtest;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
    private final ExperimentService experimentService;

    public static final int MAX_SERIES_ROWS = 100000;
    public static final int MAX_HOLDINGS_ROWS = 10000;
    public static final int MAX_SIGNALS_ROWS = 50000;
    public static final long MAX_EXPORT_BYTES = 50 * 1024 * 1024L;

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

        // Bounded export preflight check: validate exact row limits before HTTP 200 response headers are committed
        int equityCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM backtest_daily_equity WHERE run_id = ?", Integer.class, runId);
        int eventsCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM backtest_events WHERE run_id = ?", Integer.class, runId);
        int ordersCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM backtest_orders WHERE run_id = ?", Integer.class, runId);
        int holdingsCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM backtest_holdings WHERE run_id = ?", Integer.class, runId);

        int signalsCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM backtest_signals WHERE run_id = ?", Integer.class, runId);

        if (equityCount > MAX_SERIES_ROWS) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "Export bounds exceeded: run has " + equityCount + " daily equity rows, exceeding maximum limit of " + MAX_SERIES_ROWS);
        }
        if (eventsCount > MAX_SERIES_ROWS) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "Export bounds exceeded: run has " + eventsCount + " event rows, exceeding maximum limit of " + MAX_SERIES_ROWS);
        }
        if (ordersCount > MAX_SERIES_ROWS) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "Export bounds exceeded: run has " + ordersCount + " order rows, exceeding maximum limit of " + MAX_SERIES_ROWS);
        }
        if (holdingsCount > MAX_HOLDINGS_ROWS) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "Export bounds exceeded: run has " + holdingsCount + " holdings rows, exceeding maximum limit of " + MAX_HOLDINGS_ROWS);
        }
        if (signalsCount > MAX_SIGNALS_ROWS) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "Export bounds exceeded: run has " + signalsCount + " signals rows, exceeding maximum limit of " + MAX_SIGNALS_ROWS);
        }
    }

    public void streamExportZip(String runId, String ownerId, OutputStream outputStream) throws IOException {
        streamExportZip(runId, ownerId, outputStream, MAX_EXPORT_BYTES);
    }

    public Path prepareExportZip(String runId, String ownerId) throws IOException {
        return prepareExportZip(runId, ownerId, MAX_EXPORT_BYTES);
    }

    Path prepareExportZip(String runId, String ownerId, long maxBytes) throws IOException {
        validateExportable(runId, ownerId);
        Path zip = Files.createTempFile("signalforge-backtest-export-", ".zip");
        try (OutputStream output = Files.newOutputStream(zip)) {
            streamExportZip(runId, ownerId, output, maxBytes);
            return zip;
        } catch (IOException | RuntimeException failure) {
            Files.deleteIfExists(zip);
            Throwable cause = failure;
            while (cause != null) {
                if (cause instanceof ExportTooLargeException) {
                    throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                            "Export exceeds maximum ZIP size of " + maxBytes + " bytes", failure);
                }
                cause = cause.getCause();
            }
            throw failure;
        }
    }

    private void streamExportZip(String runId, String ownerId, OutputStream outputStream, long maxBytes) throws IOException {
        validateExportable(runId, ownerId);

        String uid = (ownerId == null || ownerId.isBlank()) ? "default" : ownerId;
        List<Map<String, Object>> runRows = jdbcTemplate.queryForList(
                "SELECT * FROM backtest_runs WHERE id = ? AND owner_id = ?", runId, uid
        );
        if (runRows.isEmpty()) {
            throw new IllegalArgumentException("Backtest run not found: " + runId);
        }
        Map<String, Object> run = runRows.get(0);

        List<Map<String, Object>> runExp = jdbcTemplate.queryForList(
                "SELECT experiment_id FROM backtest_runs WHERE id = ?", runId
        );
        if (!runExp.isEmpty() && runExp.get(0).get("experiment_id") != null) {
            String expId = (String) runExp.get(0).get("experiment_id");
            experimentService.recordExposure(expId, runId, "EXPORT_DOWNLOAD", uid, "{\"runId\":\"" + runId + "\"}");
        }

        BoundedOutputStream bos = new BoundedOutputStream(outputStream, maxBytes);
        try (ZipOutputStream zos = new ZipOutputStream(bos, StandardCharsets.UTF_8)) {
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
                    manifest.put("codeFingerprint", cfg.codeFingerprint());
                    manifest.put("classification", cfg.classification());
                    manifest.put("availabilityAssumptions", cfg.availabilityAssumptions());
                    if (cfg.universeId() != null) manifest.put("universeId", cfg.universeId());
                    if (cfg.parametersJson() != null) manifest.put("parametersJson", cfg.parametersJson());
                    if (cfg.experimentId() != null) manifest.put("experimentId", cfg.experimentId());
                } catch (Exception e) {
                    log.warn("Failed to parse config_json for manifest, falling back to raw fields", e);
                }
            }

            manifest.putIfAbsent("strategyId", run.get("strategy_id"));
            manifest.putIfAbsent("strategyVersion", run.get("strategy_version"));
            manifest.putIfAbsent("datasetId", run.get("dataset_id"));
            manifest.putIfAbsent("candidateListingId", run.get("candidate_listing_id"));
            manifest.putIfAbsent("benchmarkListingId", run.get("benchmark_listing_id"));
            if (run.get("universe_id") != null) manifest.putIfAbsent("universeId", run.get("universe_id"));
            if (run.get("parameters_json") != null) manifest.putIfAbsent("parametersJson", run.get("parameters_json"));
            if (run.get("experiment_id") != null) manifest.putIfAbsent("experimentId", run.get("experiment_id"));
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
            writer.write("series_type,session_date,point_kind,observation_time,cash,holdings_value,receivables,total_equity,daily_return,drawdown,peak_equity,units,cost_basis,raw_close\n");
            jdbcTemplate.query(
                    "SELECT series_type, session_date, point_kind, observation_time, cash, holdings_value, receivables, total_equity, " +
                            "daily_return, drawdown, peak_equity, units, cost_basis, raw_close " +
                            "FROM backtest_daily_equity WHERE run_id = ? ORDER BY session_date ASC, point_kind ASC, series_type ASC",
                    rs -> {
                        try {
                            writer.write(escapeCsvText(rs.getString("series_type")) + ",");
                            writer.write(formatNumeric(rs.getString("session_date")) + ",");
                            writer.write(escapeCsvText(rs.getString("point_kind")) + ",");
                            writer.write(escapeCsvText(rs.getString("observation_time")) + ",");
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

            // 7. signals.csv (if any signals exist for this run)
            Integer sigCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM backtest_signals WHERE run_id = ?", Integer.class, runId
            );
            if (sigCount != null && sigCount > 0) {
                zos.putNextEntry(new ZipEntry("signals.csv"));
                writer.write("evaluation_date,evaluation_time,scheduled_execution_date,strategy_id,listing_id,score,index_value,sma_value,rank,eligible,selected,target_weight,status,reason_code\n");
                jdbcTemplate.query(
                        "SELECT s.evaluation_date, s.evaluation_time, s.scheduled_execution_date, s.strategy_id, " +
                                "si.listing_id, si.score, si.index_value, si.sma_value, si.rank, si.eligible, " +
                                "si.selected, si.target_weight, s.status, si.reason_code " +
                                "FROM backtest_signals s " +
                                "JOIN backtest_signal_items si ON s.id = si.signal_id " +
                                "WHERE s.run_id = ? ORDER BY s.evaluation_date ASC, si.rank ASC, si.listing_id ASC",
                        rs -> {
                            try {
                                writer.write(formatNumeric(rs.getString("evaluation_date")) + ",");
                                writer.write(escapeCsvText(rs.getString("evaluation_time")) + ",");
                                writer.write(formatNumeric(rs.getString("scheduled_execution_date")) + ",");
                                writer.write(escapeCsvText(rs.getString("strategy_id")) + ",");
                                writer.write(escapeCsvText(rs.getString("listing_id")) + ",");
                                writer.write(formatNumeric(rs.getString("score")) + ",");
                                writer.write(formatNumeric(rs.getString("index_value")) + ",");
                                writer.write(formatNumeric(rs.getString("sma_value")) + ",");
                                writer.write((rs.getObject("rank") != null ? rs.getInt("rank") : "") + ",");
                                writer.write((rs.getInt("eligible") == 1 ? "true" : "false") + ",");
                                writer.write((rs.getInt("selected") == 1 ? "true" : "false") + ",");
                                writer.write(formatNumeric(rs.getString("target_weight")) + ",");
                                writer.write(escapeCsvText(rs.getString("status")) + ",");
                                writer.write(escapeCsvText(rs.getString("reason_code")) + "\n");
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
    }

    public Path prepareComparisonZip(String comparisonId, String ownerId) throws IOException {
        String uid = (ownerId == null || ownerId.isBlank()) ? "default" : ownerId;
        List<Map<String, Object>> compRows = jdbcTemplate.queryForList(
                "SELECT * FROM backtest_comparisons WHERE id = ? AND (owner_id = ? OR owner_id = 'default')",
                comparisonId, uid
        );
        if (compRows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Comparison not found: " + comparisonId);
        }
        Map<String, Object> comp = compRows.get(0);

        List<Map<String, Object>> items = jdbcTemplate.queryForList(
                "SELECT comparison_id, run_id, role, ordinal FROM backtest_comparison_items WHERE comparison_id = ? ORDER BY ordinal ASC",
                comparisonId
        );

        // Check holdout exposures for all runs in comparison
        for (Map<String, Object> item : items) {
            String rid = (String) item.get("run_id");
            List<Map<String, Object>> runExp = jdbcTemplate.queryForList(
                    "SELECT experiment_id FROM backtest_runs WHERE id = ?", rid
            );
            if (!runExp.isEmpty() && runExp.get(0).get("experiment_id") != null) {
                String expId = (String) runExp.get(0).get("experiment_id");
                experimentService.recordExposure(expId, rid, "EXPORT_DOWNLOAD", uid,
                        "{\"comparisonId\":\"" + comparisonId + "\"}");
            }
        }

        Path zip = Files.createTempFile("signalforge-comparison-export-", ".zip");
        try (OutputStream output = Files.newOutputStream(zip);
             BoundedOutputStream bos = new BoundedOutputStream(output, MAX_EXPORT_BYTES);
             ZipOutputStream zos = new ZipOutputStream(bos, StandardCharsets.UTF_8)) {
            Writer writer = new OutputStreamWriter(zos, StandardCharsets.UTF_8);

            // 1. manifest.json
            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("comparisonId", comp.get("id"));
            manifest.put("ownerId", comp.get("owner_id"));
            manifest.put("name", comp.get("name"));
            manifest.put("status", comp.get("status"));
            manifest.put("benchmarkListingId", comp.get("benchmark_listing_id"));
            manifest.put("datasetId", comp.get("dataset_id"));
            manifest.put("effectiveStartDate", comp.get("effective_start_date"));
            manifest.put("effectiveEndDate", comp.get("effective_end_date"));
            manifest.put("initialCash", comp.get("initial_cash"));
            manifest.put("currency", comp.get("currency"));
            manifest.put("createdAt", comp.get("created_at"));
            manifest.put("runs", items);

            String mmJson = (String) comp.get("mismatch_reasons_json");
            if (mmJson != null && !mmJson.isBlank()) {
                manifest.put("mismatchReasons", objectMapper.readValue(mmJson, Object.class));
            }

            zos.putNextEntry(new ZipEntry("manifest.json"));
            writer.write(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(manifest));
            writer.flush();
            zos.closeEntry();

            // 2. summary.json
            String sumJson = (String) comp.get("summary_json");
            if (sumJson != null && !sumJson.isBlank()) {
                zos.putNextEntry(new ZipEntry("summary.json"));
                writer.write(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                        objectMapper.readValue(sumJson, Object.class)
                ));
                writer.flush();
                zos.closeEntry();
            }

            // 3. comparative_equity.csv
            zos.putNextEntry(new ZipEntry("comparative_equity.csv"));
            writer.write("comparison_id,run_id,series_type,session_date,cash,holdings_value,receivables,total_equity,daily_return,drawdown\n");
            for (Map<String, Object> item : items) {
                String rid = (String) item.get("run_id");
                jdbcTemplate.query(
                        "SELECT run_id, series_type, session_date, cash, holdings_value, receivables, total_equity, daily_return, drawdown " +
                                "FROM backtest_daily_equity WHERE run_id = ? AND series_type = 'CANDIDATE' ORDER BY session_date ASC",
                        rs -> {
                            try {
                                writer.write(escapeCsvText(comparisonId) + ",");
                                writer.write(escapeCsvText(rs.getString("run_id")) + ",");
                                writer.write(escapeCsvText(rs.getString("series_type")) + ",");
                                writer.write(formatNumeric(rs.getString("session_date")) + ",");
                                writer.write(formatNumeric(rs.getString("cash")) + ",");
                                writer.write(formatNumeric(rs.getString("holdings_value")) + ",");
                                writer.write(formatNumeric(rs.getString("receivables")) + ",");
                                writer.write(formatNumeric(rs.getString("total_equity")) + ",");
                                writer.write((rs.getObject("daily_return") != null ? formatNumeric(rs.getString("daily_return")) : "") + ",");
                                writer.write(formatNumeric(rs.getString("drawdown")) + "\n");
                            } catch (IOException e) {
                                throw new UncheckedIOException(e);
                            }
                        },
                        rid
                );
            }
            writer.flush();
            zos.closeEntry();

            return zip;
        } catch (Exception e) {
            Files.deleteIfExists(zip);
            throw e;
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

    private static class BoundedOutputStream extends FilterOutputStream {
        private final long maxBytes;
        private long bytesWritten = 0;

        public BoundedOutputStream(OutputStream out, long maxBytes) {
            super(out);
            this.maxBytes = maxBytes;
        }

        @Override
        public void write(int b) throws IOException {
            checkLimit(1);
            super.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            checkLimit(len);
            out.write(b, off, len);
        }

        private void checkLimit(int len) throws IOException {
            bytesWritten += len;
            if (bytesWritten > maxBytes) {
                throw new ExportTooLargeException();
            }
        }
    }

    private static class ExportTooLargeException extends IOException {}
}
