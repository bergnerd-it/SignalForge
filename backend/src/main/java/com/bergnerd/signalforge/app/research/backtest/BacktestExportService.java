package com.bergnerd.signalforge.app.research.backtest;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Slf4j
@Service
@RequiredArgsConstructor
public class BacktestExportService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public byte[] generateExportZip(String runId) throws IOException {
        // 1. Fetch backtest run
        List<Map<String, Object>> runRows = jdbcTemplate.queryForList(
                "SELECT * FROM backtest_runs WHERE id = ?", runId
        );
        if (runRows.isEmpty()) {
            throw new IllegalArgumentException("Backtest run not found: " + runId);
        }
        Map<String, Object> run = runRows.get(0);
        String status = (String) run.get("status");
        if (!"COMPLETED".equalsIgnoreCase(status)) {
            throw new IllegalStateException("Cannot export incomplete backtest run (current status: " + status + ")");
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos, StandardCharsets.UTF_8)) {

            // 1. manifest.json
            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("runId", run.get("id"));
            manifest.put("ownerId", run.get("owner_id"));
            manifest.put("idempotencyKey", run.get("idempotency_key"));
            manifest.put("canonicalHash", run.get("canonical_hash"));
            manifest.put("engineVersion", "2.0.0-M3");
            manifest.put("accountingPolicy", "v2-decimal-grammar-half-even");
            manifest.put("strategyId", run.get("strategy_id"));
            manifest.put("strategyVersion", run.get("strategy_version"));
            manifest.put("datasetId", run.get("dataset_id"));
            manifest.put("candidateListingId", run.get("candidate_listing_id"));
            manifest.put("benchmarkListingId", run.get("benchmark_listing_id"));
            manifest.put("currency", run.get("currency"));
            manifest.put("initialCash", run.get("initial_cash"));
            manifest.put("evaluationCutoff", run.get("evaluation_cutoff"));
            manifest.put("requestedStartDate", run.get("requested_start_date"));
            manifest.put("requestedEndDate", run.get("requested_end_date"));
            manifest.put("effectiveStartDate", run.get("effective_start_date"));
            manifest.put("effectiveEndDate", run.get("effective_end_date"));
            manifest.put("commissionPerFill", run.get("commission_per_fill"));
            manifest.put("spreadBps", run.get("spread_bps"));
            manifest.put("slippageBps", run.get("slippage_bps"));
            manifest.put("createdAt", run.get("created_at"));
            manifest.put("completedAt", run.get("completed_at"));
            manifest.put("endLiquidationConvention", "MARK_TO_MARKET_EXCLUDES_HYPOTHETICAL_LIQUIDATION_COSTS");
            manifest.put("dataWarning", "SYNTHETIC_DATA_TESTS_SOFTWARE_BEHAVIOR_ONLY_NOT_REAL_WORLD_PERFORMANCE");

            addZipEntry(zos, "manifest.json", objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(manifest));

            // 2. summary.json
            String summaryJson = (String) run.get("summary_json");
            if (summaryJson != null) {
                Object summaryObj = objectMapper.readValue(summaryJson, Object.class);
                addZipEntry(zos, "summary.json", objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(summaryObj));
            }

            // 3. equity_series.csv
            StringBuilder eqCsv = new StringBuilder();
            eqCsv.append("series_type,session_date,cash,holdings_value,receivables,total_equity,daily_return,drawdown,peak_equity,units,cost_basis,raw_close\n");
            jdbcTemplate.query(
                    "SELECT series_type, session_date, cash, holdings_value, receivables, total_equity, " +
                            "daily_return, drawdown, peak_equity, units, cost_basis, raw_close " +
                            "FROM backtest_daily_equity WHERE run_id = ? ORDER BY session_date ASC, series_type ASC",
                    rs -> {
                        eqCsv.append(escapeCsv(rs.getString("series_type"))).append(",")
                                .append(escapeCsv(rs.getString("session_date"))).append(",")
                                .append(escapeCsv(rs.getString("cash"))).append(",")
                                .append(escapeCsv(rs.getString("holdings_value"))).append(",")
                                .append(escapeCsv(rs.getString("receivables"))).append(",")
                                .append(escapeCsv(rs.getString("total_equity"))).append(",")
                                .append(rs.getObject("daily_return") != null ? rs.getString("daily_return") : "").append(",")
                                .append(escapeCsv(rs.getString("drawdown"))).append(",")
                                .append(escapeCsv(rs.getString("peak_equity"))).append(",")
                                .append(escapeCsv(rs.getString("units"))).append(",")
                                .append(escapeCsv(rs.getString("cost_basis"))).append(",")
                                .append(escapeCsv(rs.getString("raw_close"))).append("\n");
                    },
                    runId
            );
            addZipEntry(zos, "equity_series.csv", eqCsv.toString());

            // 4. events.csv
            StringBuilder evCsv = new StringBuilder();
            evCsv.append("series_type,event_seq,event_type,event_date,event_time,description,cash_delta,units_delta,basis_delta,receivable_delta\n");
            jdbcTemplate.query(
                    "SELECT series_type, event_seq, event_type, event_date, event_time, description, " +
                            "cash_delta, units_delta, basis_delta, receivable_delta " +
                            "FROM backtest_events WHERE run_id = ? ORDER BY event_seq ASC, series_type ASC",
                    rs -> {
                        evCsv.append(escapeCsv(rs.getString("series_type"))).append(",")
                                .append(rs.getInt("event_seq")).append(",")
                                .append(escapeCsv(rs.getString("event_type"))).append(",")
                                .append(escapeCsv(rs.getString("event_date"))).append(",")
                                .append(escapeCsv(rs.getString("event_time"))).append(",")
                                .append(escapeCsv(rs.getString("description"))).append(",")
                                .append(escapeCsv(rs.getString("cash_delta"))).append(",")
                                .append(escapeCsv(rs.getString("units_delta"))).append(",")
                                .append(escapeCsv(rs.getString("basis_delta"))).append(",")
                                .append(escapeCsv(rs.getString("receivable_delta"))).append("\n");
                    },
                    runId
            );
            addZipEntry(zos, "events.csv", evCsv.toString());

            // 5. orders.csv
            StringBuilder ordCsv = new StringBuilder();
            ordCsv.append("series_type,order_type,listing_id,session_date,requested_quantity,executed_quantity,raw_open,fill_price,commission,spread_cost,slippage_cost,status,skip_reason\n");
            jdbcTemplate.query(
                    "SELECT series_type, order_type, listing_id, session_date, requested_quantity, executed_quantity, " +
                            "raw_open, fill_price, commission, spread_cost, slippage_cost, status, skip_reason " +
                            "FROM backtest_orders WHERE run_id = ? ORDER BY session_date ASC, series_type ASC",
                    rs -> {
                        ordCsv.append(escapeCsv(rs.getString("series_type"))).append(",")
                                .append(escapeCsv(rs.getString("order_type"))).append(",")
                                .append(escapeCsv(rs.getString("listing_id"))).append(",")
                                .append(escapeCsv(rs.getString("session_date"))).append(",")
                                .append(escapeCsv(rs.getString("requested_quantity"))).append(",")
                                .append(escapeCsv(rs.getString("executed_quantity"))).append(",")
                                .append(escapeCsv(rs.getString("raw_open"))).append(",")
                                .append(escapeCsv(rs.getString("fill_price"))).append(",")
                                .append(escapeCsv(rs.getString("commission"))).append(",")
                                .append(escapeCsv(rs.getString("spread_cost"))).append(",")
                                .append(escapeCsv(rs.getString("slippage_cost"))).append(",")
                                .append(escapeCsv(rs.getString("status"))).append(",")
                                .append(escapeCsv(rs.getString("skip_reason"))).append("\n");
                    },
                    runId
            );
            addZipEntry(zos, "orders.csv", ordCsv.toString());

            // 6. holdings.csv
            StringBuilder holdCsv = new StringBuilder();
            holdCsv.append("series_type,listing_id,units,total_cost_basis,average_cost,current_price,market_value,unrealized_gain\n");
            jdbcTemplate.query(
                    "SELECT series_type, listing_id, units, total_cost_basis, average_cost, current_price, market_value, unrealized_gain " +
                            "FROM backtest_holdings WHERE run_id = ? ORDER BY series_type ASC",
                    rs -> {
                        holdCsv.append(escapeCsv(rs.getString("series_type"))).append(",")
                                .append(escapeCsv(rs.getString("listing_id"))).append(",")
                                .append(escapeCsv(rs.getString("units"))).append(",")
                                .append(escapeCsv(rs.getString("total_cost_basis"))).append(",")
                                .append(escapeCsv(rs.getString("average_cost"))).append(",")
                                .append(escapeCsv(rs.getString("current_price"))).append(",")
                                .append(escapeCsv(rs.getString("market_value"))).append(",")
                                .append(escapeCsv(rs.getString("unrealized_gain"))).append("\n");
                    },
                    runId
            );
            addZipEntry(zos, "holdings.csv", holdCsv.toString());
        }

        return baos.toByteArray();
    }

    private void addZipEntry(ZipOutputStream zos, String name, String content) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        zos.putNextEntry(entry);
        zos.write(content.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    private String escapeCsv(String val) {
        if (val == null) return "";
        // Prevent CSV injection if starting with formula characters
        String clean = val;
        if (clean.startsWith("=") || clean.startsWith("+") || clean.startsWith("-") || clean.startsWith("@")) {
            clean = "'" + clean;
        }
        if (clean.contains(",") || clean.contains("\"") || clean.contains("\n") || clean.contains("\r")) {
            return "\"" + clean.replace("\"", "\"\"") + "\"";
        }
        return clean;
    }
}
