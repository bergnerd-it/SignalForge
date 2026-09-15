package com.bergnerd.signalforge.app.research.paper;

import com.bergnerd.signalforge.app.research.backtest.BuildIdentityResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaperExportService {

    private final JdbcTemplate jdbcTemplate;
    private final BuildIdentityResolver buildIdentityResolver;
    private final Clock clock;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public byte[] createPaperAuditZip(String portfolioId, String ownerId) throws IOException {
        String uid = (ownerId == null || ownerId.isBlank()) ? "default" : ownerId.trim();

        // Validate portfolio
        List<Map<String, Object>> portRows = jdbcTemplate.queryForList(
                "SELECT id, owner_id, name, mode, base_currency, initial_cash, created_at, paper_started_at FROM portfolios WHERE id = ? AND owner_id = ? AND mode = 'PAPER'",
                portfolioId, uid
        );
        if (portRows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Paper portfolio not found");
        }
        Map<String, Object> port = portRows.get(0);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos, StandardCharsets.UTF_8)) {
            // 1. Manifest
            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("portfolioId", portfolioId);
            manifest.put("ownerId", uid);
            manifest.put("name", port.get("name"));
            manifest.put("mode", port.get("mode"));
            manifest.put("baseCurrency", port.get("base_currency"));
            manifest.put("initialCash", port.get("initial_cash"));
            manifest.put("createdAt", port.get("created_at"));
            manifest.put("paperStartedAt", port.get("paper_started_at"));
            manifest.put("exportedAt", clock.instant().toString());
            manifest.put("buildFingerprint", buildIdentityResolver.getCodeFingerprint());
            manifest.put("engineVersion", "2.0.0-M5");

            writeZipEntry(zos, "manifest.json", objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(manifest));

            // 2. Proposals CSV
            List<Map<String, Object>> proposals = jdbcTemplate.queryForList(
                    "SELECT id, cycle_id, strategy_id, strategy_version, dataset_id, evaluation_session_date, " +
                            "scheduled_open_session_date, status, accepted_at, rejected_at, reason_code FROM paper_proposals WHERE portfolio_id = ? ORDER BY created_at ASC",
                    portfolioId
            );
            StringBuilder propCsv = new StringBuilder("id,cycle_id,strategy_id,strategy_version,dataset_id,evaluation_session_date,scheduled_open_session_date,status,accepted_at,rejected_at,reason_code\n");
            for (Map<String, Object> p : proposals) {
                propCsv.append(escapeCsv((String) p.get("id"))).append(",")
                        .append(escapeCsv((String) p.get("cycle_id"))).append(",")
                        .append(escapeCsv((String) p.get("strategy_id"))).append(",")
                        .append(escapeCsv((String) p.get("strategy_version"))).append(",")
                        .append(escapeCsv((String) p.get("dataset_id"))).append(",")
                        .append(escapeCsv((String) p.get("evaluation_session_date"))).append(",")
                        .append(escapeCsv((String) p.get("scheduled_open_session_date"))).append(",")
                        .append(escapeCsv((String) p.get("status"))).append(",")
                        .append(escapeCsv((String) p.get("accepted_at"))).append(",")
                        .append(escapeCsv((String) p.get("rejected_at"))).append(",")
                        .append(escapeCsv((String) p.get("reason_code"))).append("\n");
            }
            writeZipEntry(zos, "proposals.csv", propCsv.toString());

            // 3. Executions CSV
            List<Map<String, Object>> executions = jdbcTemplate.queryForList(
                    "SELECT id, intent_id, proposal_id, listing_id, side, requested_quantity, executed_quantity, " +
                            "raw_open_price, fill_price, commission, cost_basis, realized_gain, market_effective_instant, booked_instant " +
                            "FROM paper_execution_results WHERE intent_id IN (SELECT id FROM paper_execution_intents WHERE portfolio_id = ?) ORDER BY booked_instant ASC",
                    portfolioId
            );
            StringBuilder execCsv = new StringBuilder("id,intent_id,proposal_id,listing_id,side,requested_quantity,executed_quantity,raw_open_price,fill_price,commission,cost_basis,realized_gain,market_effective_instant,booked_instant\n");
            for (Map<String, Object> e : executions) {
                execCsv.append(escapeCsv((String) e.get("id"))).append(",")
                        .append(escapeCsv((String) e.get("intent_id"))).append(",")
                        .append(escapeCsv((String) e.get("proposal_id"))).append(",")
                        .append(escapeCsv((String) e.get("listing_id"))).append(",")
                        .append(escapeCsv((String) e.get("side"))).append(",")
                        .append(escapeCsv((String) e.get("requested_quantity"))).append(",")
                        .append(escapeCsv((String) e.get("executed_quantity"))).append(",")
                        .append(escapeCsv((String) e.get("raw_open_price"))).append(",")
                        .append(escapeCsv((String) e.get("fill_price"))).append(",")
                        .append(escapeCsv((String) e.get("commission"))).append(",")
                        .append(escapeCsv((String) e.get("cost_basis"))).append(",")
                        .append(escapeCsv((String) e.get("realized_gain"))).append(",")
                        .append(escapeCsv((String) e.get("market_effective_instant"))).append(",")
                        .append(escapeCsv((String) e.get("booked_instant"))).append("\n");
            }
            writeZipEntry(zos, "executions.csv", execCsv.toString());

            // 4. Valuations CSV
            List<Map<String, Object>> valuations = jdbcTemplate.queryForList(
                    "SELECT session_date, observation_kind, observation_instant, cash_balance, positions_market_value, " +
                            "receivables_value, total_equity, cumulative_return, drawdown, data_readiness_status FROM paper_valuations WHERE portfolio_id = ? ORDER BY observation_instant ASC",
                    portfolioId
            );
            StringBuilder valCsv = new StringBuilder("session_date,observation_kind,observation_instant,cash_balance,positions_market_value,receivables_value,total_equity,cumulative_return,drawdown,data_readiness_status\n");
            for (Map<String, Object> v : valuations) {
                valCsv.append(escapeCsv((String) v.get("session_date"))).append(",")
                        .append(escapeCsv((String) v.get("observation_kind"))).append(",")
                        .append(escapeCsv((String) v.get("observation_instant"))).append(",")
                        .append(escapeCsv((String) v.get("cash_balance"))).append(",")
                        .append(escapeCsv((String) v.get("positions_market_value"))).append(",")
                        .append(escapeCsv((String) v.get("receivables_value"))).append(",")
                        .append(escapeCsv((String) v.get("total_equity"))).append(",")
                        .append(escapeCsv((String) v.get("cumulative_return"))).append(",")
                        .append(escapeCsv((String) v.get("drawdown"))).append(",")
                        .append(escapeCsv((String) v.get("data_readiness_status"))).append("\n");
            }
            writeZipEntry(zos, "valuations.csv", valCsv.toString());

            // 5. Holdings CSV
            List<Map<String, Object>> holdings = jdbcTemplate.queryForList(
                    "SELECT listing_id, quantity, total_acquisition_cost, updated_at FROM positions WHERE portfolio_id = ? ORDER BY listing_id ASC",
                    portfolioId
            );
            StringBuilder holdCsv = new StringBuilder("listing_id,quantity,total_acquisition_cost,updated_at\n");
            for (Map<String, Object> h : holdings) {
                holdCsv.append(escapeCsv((String) h.get("listing_id"))).append(",")
                        .append(escapeCsv((String) h.get("quantity"))).append(",")
                        .append(escapeCsv((String) h.get("total_acquisition_cost"))).append(",")
                        .append(escapeCsv((String) h.get("updated_at"))).append("\n");
            }
            writeZipEntry(zos, "holdings.csv", holdCsv.toString());
        }

        return baos.toByteArray();
    }

    private void writeZipEntry(ZipOutputStream zos, String name, String content) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        zos.putNextEntry(entry);
        zos.write(content.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    private String escapeCsv(String value) {
        if (value == null) return "";
        // Check if value is a pure number or negative decimal (e.g. "-100.50", "-0.05") -> do not escape leading minus
        if (value.matches("^-?\\d+(\\.\\d+)?$")) {
            return value;
        }
        // Formula injection protection for text fields
        if (value.startsWith("=") || value.startsWith("+") || value.startsWith("-") || value.startsWith("@")) {
            value = "\t" + value;
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
