package com.bergnerd.signalforge.app.research.paper;

import com.bergnerd.signalforge.app.research.backtest.BuildIdentityResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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

    @Transactional(readOnly = true)
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
            // Load active segment for manifest
            List<Map<String, Object>> segRows = jdbcTemplate.queryForList(
                    "SELECT id, strategy_id, strategy_version, universe_id, benchmark_listing_id, cost_policy_json, " +
                            "approval_mode, status, initial_equity, opening_observation_instant, adopted_dataset_id, created_at " +
                            "FROM paper_portfolio_segments WHERE portfolio_id = ? AND status = 'ACTIVE'",
                    portfolioId
            );
            Map<String, Object> seg = segRows.isEmpty() ? Collections.emptyMap() : segRows.get(0);

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
            manifest.put("segmentConfig", seg);
            manifest.put("archiveLimitRows", 10000);

            writeZipEntry(zos, "manifest.json", objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(manifest));

            // 2. Adoptions CSV
            List<Map<String, Object>> adoptions = jdbcTemplate.queryForList(
                    "SELECT id, portfolio_id, dataset_id, dataset_checksum, coverage_start_session, coverage_end_session, validation_status, rejection_reason, adopted_at " +
                            "FROM paper_dataset_adoptions WHERE portfolio_id = ? ORDER BY adopted_at ASC LIMIT 10000",
                    portfolioId
            );
            StringBuilder adoptCsv = new StringBuilder("id,portfolio_id,dataset_id,dataset_checksum,coverage_start_session,coverage_end_session,validation_status,rejection_reason,adopted_at\n");
            for (Map<String, Object> a : adoptions) {
                adoptCsv.append(escapeCsv((String) a.get("id"))).append(",")
                        .append(escapeCsv((String) a.get("portfolio_id"))).append(",")
                        .append(escapeCsv((String) a.get("dataset_id"))).append(",")
                        .append(escapeCsv((String) a.get("dataset_checksum"))).append(",")
                        .append(escapeCsv((String) a.get("coverage_start_session"))).append(",")
                        .append(escapeCsv((String) a.get("coverage_end_session"))).append(",")
                        .append(escapeCsv((String) a.get("validation_status"))).append(",")
                        .append(escapeCsv((String) a.get("rejection_reason"))).append(",")
                        .append(escapeCsv((String) a.get("adopted_at"))).append("\n");
            }
            writeZipEntry(zos, "adoptions.csv", adoptCsv.toString());

            // 3. Proposals CSV
            List<Map<String, Object>> proposals = jdbcTemplate.queryForList(
                    "SELECT id, cycle_id, strategy_id, strategy_version, dataset_id, dataset_checksum, calendar_id, calendar_version, " +
                            "evaluation_session_date, input_cutoff_instant, evaluation_instant, scheduled_open_session_date, scheduled_open_instant, " +
                            "reason_code, portfolio_state_version, status, accepted_at, rejected_at, rejection_reason, superseding_proposal_id, reinvestment_receivable_id, created_at " +
                            "FROM paper_proposals WHERE portfolio_id = ? ORDER BY created_at ASC LIMIT 10000",
                    portfolioId
            );
            StringBuilder propCsv = new StringBuilder("id,cycle_id,strategy_id,strategy_version,dataset_id,dataset_checksum,calendar_id,calendar_version,evaluation_session_date,input_cutoff_instant,evaluation_instant,scheduled_open_session_date,scheduled_open_instant,reason_code,portfolio_state_version,status,accepted_at,rejected_at,rejection_reason,superseding_proposal_id,reinvestment_receivable_id,created_at\n");
            for (Map<String, Object> p : proposals) {
                propCsv.append(escapeCsv((String) p.get("id"))).append(",")
                        .append(escapeCsv((String) p.get("cycle_id"))).append(",")
                        .append(escapeCsv((String) p.get("strategy_id"))).append(",")
                        .append(escapeCsv((String) p.get("strategy_version"))).append(",")
                        .append(escapeCsv((String) p.get("dataset_id"))).append(",")
                        .append(escapeCsv((String) p.get("dataset_checksum"))).append(",")
                        .append(escapeCsv((String) p.get("calendar_id"))).append(",")
                        .append(escapeCsv((String) p.get("calendar_version"))).append(",")
                        .append(escapeCsv((String) p.get("evaluation_session_date"))).append(",")
                        .append(escapeCsv((String) p.get("input_cutoff_instant"))).append(",")
                        .append(escapeCsv((String) p.get("evaluation_instant"))).append(",")
                        .append(escapeCsv((String) p.get("scheduled_open_session_date"))).append(",")
                        .append(escapeCsv((String) p.get("scheduled_open_instant"))).append(",")
                        .append(escapeCsv((String) p.get("reason_code"))).append(",")
                        .append(p.get("portfolio_state_version")).append(",")
                        .append(escapeCsv((String) p.get("status"))).append(",")
                        .append(escapeCsv((String) p.get("accepted_at"))).append(",")
                        .append(escapeCsv((String) p.get("rejected_at"))).append(",")
                        .append(escapeCsv((String) p.get("rejection_reason"))).append(",")
                        .append(escapeCsv((String) p.get("superseding_proposal_id"))).append(",")
                        .append(escapeCsv((String) p.get("reinvestment_receivable_id"))).append(",")
                        .append(escapeCsv((String) p.get("created_at"))).append("\n");
            }
            writeZipEntry(zos, "proposals.csv", propCsv.toString());

            // 4. Proposal Items CSV
            List<Map<String, Object>> items = jdbcTemplate.queryForList(
                    "SELECT id, proposal_id, listing_id, rank, target_weight, cutoff_estimated_units, score, reason_code, reason_description, raw_price_reference, observation_kind " +
                            "FROM paper_proposal_items WHERE proposal_id IN (SELECT id FROM paper_proposals WHERE portfolio_id = ?) ORDER BY rank ASC LIMIT 10000",
                    portfolioId
            );
            StringBuilder itemsCsv = new StringBuilder("id,proposal_id,listing_id,rank,target_weight,cutoff_estimated_units,score,reason_code,reason_description,raw_price_reference,observation_kind\n");
            for (Map<String, Object> it : items) {
                itemsCsv.append(escapeCsv((String) it.get("id"))).append(",")
                        .append(escapeCsv((String) it.get("proposal_id"))).append(",")
                        .append(escapeCsv((String) it.get("listing_id"))).append(",")
                        .append(it.get("rank")).append(",")
                        .append(escapeCsv((String) it.get("target_weight"))).append(",")
                        .append(escapeCsv((String) it.get("cutoff_estimated_units"))).append(",")
                        .append(escapeCsv((String) it.get("score"))).append(",")
                        .append(escapeCsv((String) it.get("reason_code"))).append(",")
                        .append(escapeCsv((String) it.get("reason_description"))).append(",")
                        .append(escapeCsv((String) it.get("raw_price_reference"))).append(",")
                        .append(escapeCsv((String) it.get("observation_kind"))).append("\n");
            }
            writeZipEntry(zos, "proposal_items.csv", itemsCsv.toString());

            // 5. Proposal Observations CSV
            List<Map<String, Object>> obs = jdbcTemplate.queryForList(
                    "SELECT id, proposal_id, listing_id, observation_session_date, observation_type, observation_value, observed_at " +
                            "FROM paper_proposal_observations WHERE proposal_id IN (SELECT id FROM paper_proposals WHERE portfolio_id = ?) ORDER BY observed_at ASC LIMIT 10000",
                    portfolioId
            );
            StringBuilder obsCsv = new StringBuilder("id,proposal_id,listing_id,observation_session_date,observation_type,observation_value,observed_at\n");
            for (Map<String, Object> o : obs) {
                obsCsv.append(escapeCsv((String) o.get("id"))).append(",")
                        .append(escapeCsv((String) o.get("proposal_id"))).append(",")
                        .append(escapeCsv((String) o.get("listing_id"))).append(",")
                        .append(escapeCsv((String) o.get("observation_session_date"))).append(",")
                        .append(escapeCsv((String) o.get("observation_type"))).append(",")
                        .append(escapeCsv((String) o.get("observation_value"))).append(",")
                        .append(escapeCsv((String) o.get("observed_at"))).append("\n");
            }
            writeZipEntry(zos, "proposal_observations.csv", obsCsv.toString());

            // 6. Intents CSV
            List<Map<String, Object>> intents = jdbcTemplate.queryForList(
                    "SELECT id, portfolio_id, proposal_id, reinvestment_receivable_id, order_type, scheduled_session_date, scheduled_open_instant, approval_mode, status, created_at " +
                            "FROM paper_execution_intents WHERE portfolio_id = ? ORDER BY created_at ASC LIMIT 10000",
                    portfolioId
            );
            StringBuilder intCsv = new StringBuilder("id,portfolio_id,proposal_id,reinvestment_receivable_id,order_type,scheduled_session_date,scheduled_open_instant,approval_mode,status,created_at\n");
            for (Map<String, Object> in : intents) {
                intCsv.append(escapeCsv((String) in.get("id"))).append(",")
                        .append(escapeCsv((String) in.get("portfolio_id"))).append(",")
                        .append(escapeCsv((String) in.get("proposal_id"))).append(",")
                        .append(escapeCsv((String) in.get("reinvestment_receivable_id"))).append(",")
                        .append(escapeCsv((String) in.get("order_type"))).append(",")
                        .append(escapeCsv((String) in.get("scheduled_session_date"))).append(",")
                        .append(escapeCsv((String) in.get("scheduled_open_instant"))).append(",")
                        .append(escapeCsv((String) in.get("approval_mode"))).append(",")
                        .append(escapeCsv((String) in.get("status"))).append(",")
                        .append(escapeCsv((String) in.get("created_at"))).append("\n");
            }
            writeZipEntry(zos, "intents.csv", intCsv.toString());

            // 7. Intent Transitions CSV
            List<Map<String, Object>> transitions = jdbcTemplate.queryForList(
                    "SELECT id, intent_id, from_status, to_status, trigger_type, transition_instant, notes " +
                            "FROM paper_intent_transitions WHERE intent_id IN (SELECT id FROM paper_execution_intents WHERE portfolio_id = ?) ORDER BY transition_instant ASC LIMIT 10000",
                    portfolioId
            );
            StringBuilder transCsv = new StringBuilder("id,intent_id,from_status,to_status,trigger_type,transition_instant,notes\n");
            for (Map<String, Object> tr : transitions) {
                transCsv.append(escapeCsv((String) tr.get("id"))).append(",")
                        .append(escapeCsv((String) tr.get("intent_id"))).append(",")
                        .append(escapeCsv((String) tr.get("from_status"))).append(",")
                        .append(escapeCsv((String) tr.get("to_status"))).append(",")
                        .append(escapeCsv((String) tr.get("trigger_type"))).append(",")
                        .append(escapeCsv((String) tr.get("transition_instant"))).append(",")
                        .append(escapeCsv((String) tr.get("notes"))).append("\n");
            }
            writeZipEntry(zos, "intent_transitions.csv", transCsv.toString());

            // 8. Execution Results CSV
            List<Map<String, Object>> executions = jdbcTemplate.queryForList(
                    "SELECT id, intent_id, proposal_id, reinvestment_receivable_id, operation_id, execution_id, listing_id, side, requested_quantity, executed_quantity, " +
                            "shortfall_reason, raw_open_price, fill_price, commission, spread_slippage_cost, cost_basis, realized_gain, dataset_id, dataset_checksum, " +
                            "market_effective_instant, observed_instant, booked_instant " +
                            "FROM paper_execution_results WHERE intent_id IN (SELECT id FROM paper_execution_intents WHERE portfolio_id = ?) ORDER BY booked_instant ASC LIMIT 10000",
                    portfolioId
            );
            StringBuilder execCsv = new StringBuilder("id,intent_id,proposal_id,reinvestment_receivable_id,operation_id,execution_id,listing_id,side,requested_quantity,executed_quantity,shortfall_reason,raw_open_price,fill_price,commission,spread_slippage_cost,cost_basis,realized_gain,dataset_id,dataset_checksum,market_effective_instant,observed_instant,booked_instant\n");
            for (Map<String, Object> e : executions) {
                execCsv.append(escapeCsv((String) e.get("id"))).append(",")
                        .append(escapeCsv((String) e.get("intent_id"))).append(",")
                        .append(escapeCsv((String) e.get("proposal_id"))).append(",")
                        .append(escapeCsv((String) e.get("reinvestment_receivable_id"))).append(",")
                        .append(escapeCsv((String) e.get("operation_id"))).append(",")
                        .append(escapeCsv((String) e.get("execution_id"))).append(",")
                        .append(escapeCsv((String) e.get("listing_id"))).append(",")
                        .append(escapeCsv((String) e.get("side"))).append(",")
                        .append(escapeCsv((String) e.get("requested_quantity"))).append(",")
                        .append(escapeCsv((String) e.get("executed_quantity"))).append(",")
                        .append(escapeCsv((String) e.get("shortfall_reason"))).append(",")
                        .append(escapeCsv((String) e.get("raw_open_price"))).append(",")
                        .append(escapeCsv((String) e.get("fill_price"))).append(",")
                        .append(escapeCsv((String) e.get("commission"))).append(",")
                        .append(escapeCsv((String) e.get("spread_slippage_cost"))).append(",")
                        .append(escapeCsv((String) e.get("cost_basis"))).append(",")
                        .append(escapeCsv((String) e.get("realized_gain"))).append(",")
                        .append(escapeCsv((String) e.get("dataset_id"))).append(",")
                        .append(escapeCsv((String) e.get("dataset_checksum"))).append(",")
                        .append(escapeCsv((String) e.get("market_effective_instant"))).append(",")
                        .append(escapeCsv((String) e.get("observed_instant"))).append(",")
                        .append(escapeCsv((String) e.get("booked_instant"))).append("\n");
            }
            writeZipEntry(zos, "execution_results.csv", execCsv.toString());

            // 9. Receivables CSV
            List<Map<String, Object>> receivables = jdbcTemplate.queryForList(
                    "SELECT id, portfolio_id, listing_id, source_namespace, action_id, action_type, record_instant, ex_date, payment_date, payment_instant, " +
                            "availability_instant, gross_amount, withholding_tax, net_amount, status, paid_operation_id, paid_at, created_at, dataset_id, dataset_checksum, terms_hash " +
                            "FROM paper_receivables WHERE portfolio_id = ? ORDER BY created_at ASC LIMIT 10000",
                    portfolioId
            );
            StringBuilder recCsv = new StringBuilder("id,portfolio_id,listing_id,source_namespace,action_id,action_type,record_instant,ex_date,payment_date,payment_instant,availability_instant,gross_amount,withholding_tax,net_amount,status,paid_operation_id,paid_at,created_at,dataset_id,dataset_checksum,terms_hash\n");
            for (Map<String, Object> r : receivables) {
                recCsv.append(escapeCsv((String) r.get("id"))).append(",")
                        .append(escapeCsv((String) r.get("portfolio_id"))).append(",")
                        .append(escapeCsv((String) r.get("listing_id"))).append(",")
                        .append(escapeCsv((String) r.get("source_namespace"))).append(",")
                        .append(escapeCsv((String) r.get("action_id"))).append(",")
                        .append(escapeCsv((String) r.get("action_type"))).append(",")
                        .append(escapeCsv((String) r.get("record_instant"))).append(",")
                        .append(escapeCsv((String) r.get("ex_date"))).append(",")
                        .append(escapeCsv((String) r.get("payment_date"))).append(",")
                        .append(escapeCsv((String) r.get("payment_instant"))).append(",")
                        .append(escapeCsv((String) r.get("availability_instant"))).append(",")
                        .append(escapeCsv((String) r.get("gross_amount"))).append(",")
                        .append(escapeCsv((String) r.get("withholding_tax"))).append(",")
                        .append(escapeCsv((String) r.get("net_amount"))).append(",")
                        .append(escapeCsv((String) r.get("status"))).append(",")
                        .append(escapeCsv((String) r.get("paid_operation_id"))).append(",")
                        .append(escapeCsv((String) r.get("paid_at"))).append(",")
                        .append(escapeCsv((String) r.get("created_at"))).append(",")
                        .append(escapeCsv((String) r.get("dataset_id"))).append(",")
                        .append(escapeCsv((String) r.get("dataset_checksum"))).append(",")
                        .append(escapeCsv((String) r.get("terms_hash"))).append("\n");
            }
            writeZipEntry(zos, "receivables.csv", recCsv.toString());

            // 10. Processed Corporate Actions CSV
            List<Map<String, Object>> procActions = jdbcTemplate.queryForList(
                    "SELECT id, portfolio_id, source_namespace, listing_id, action_id, action_type, terms_hash, payload_hash, effective_date, availability_instant, processing_instant, dataset_id, dataset_checksum, status, linked_operation_id, linked_receivable_id " +
                            "FROM paper_processed_corporate_actions WHERE portfolio_id = ? ORDER BY processing_instant ASC LIMIT 10000",
                    portfolioId
            );
            StringBuilder procCsv = new StringBuilder("id,portfolio_id,source_namespace,listing_id,action_id,action_type,terms_hash,payload_hash,effective_date,availability_instant,processing_instant,dataset_id,dataset_checksum,status,linked_operation_id,linked_receivable_id\n");
            for (Map<String, Object> ca : procActions) {
                procCsv.append(escapeCsv((String) ca.get("id"))).append(",")
                        .append(escapeCsv((String) ca.get("portfolio_id"))).append(",")
                        .append(escapeCsv((String) ca.get("source_namespace"))).append(",")
                        .append(escapeCsv((String) ca.get("listing_id"))).append(",")
                        .append(escapeCsv((String) ca.get("action_id"))).append(",")
                        .append(escapeCsv((String) ca.get("action_type"))).append(",")
                        .append(escapeCsv((String) ca.get("terms_hash"))).append(",")
                        .append(escapeCsv((String) ca.get("payload_hash"))).append(",")
                        .append(escapeCsv((String) ca.get("effective_date"))).append(",")
                        .append(escapeCsv((String) ca.get("availability_instant"))).append(",")
                        .append(escapeCsv((String) ca.get("processing_instant"))).append(",")
                        .append(escapeCsv((String) ca.get("dataset_id"))).append(",")
                        .append(escapeCsv((String) ca.get("dataset_checksum"))).append(",")
                        .append(escapeCsv((String) ca.get("status"))).append(",")
                        .append(escapeCsv((String) ca.get("linked_operation_id"))).append(",")
                        .append(escapeCsv((String) ca.get("linked_receivable_id"))).append("\n");
            }
            writeZipEntry(zos, "corporate_actions.csv", procCsv.toString());

            // 11. Valuations CSV
            List<Map<String, Object>> valuations = jdbcTemplate.queryForList(
                    "SELECT id, portfolio_id, session_date, observation_kind, observation_instant, portfolio_state_revision, cash_balance, positions_market_value, receivables_value, total_equity, cumulative_return, high_water_mark, drawdown, data_readiness_status, is_complete, last_supported_observation_instant, missing_requirements_detail, adopted_dataset_id, adopted_dataset_checksum " +
                            "FROM paper_valuations WHERE portfolio_id = ? ORDER BY observation_instant ASC LIMIT 10000",
                    portfolioId
            );
            StringBuilder valCsv = new StringBuilder("id,portfolio_id,session_date,observation_kind,observation_instant,portfolio_state_revision,cash_balance,positions_market_value,receivables_value,total_equity,cumulative_return,high_water_mark,drawdown,data_readiness_status,is_complete,last_supported_observation_instant,missing_requirements_detail,adopted_dataset_id,adopted_dataset_checksum\n");
            for (Map<String, Object> v : valuations) {
                valCsv.append(escapeCsv((String) v.get("id"))).append(",")
                        .append(escapeCsv((String) v.get("portfolio_id"))).append(",")
                        .append(escapeCsv((String) v.get("session_date"))).append(",")
                        .append(escapeCsv((String) v.get("observation_kind"))).append(",")
                        .append(escapeCsv((String) v.get("observation_instant"))).append(",")
                        .append(v.get("portfolio_state_revision")).append(",")
                        .append(escapeCsv((String) v.get("cash_balance"))).append(",")
                        .append(escapeCsv((String) v.get("positions_market_value"))).append(",")
                        .append(escapeCsv((String) v.get("receivables_value"))).append(",")
                        .append(escapeCsv((String) v.get("total_equity"))).append(",")
                        .append(escapeCsv((String) v.get("cumulative_return"))).append(",")
                        .append(escapeCsv((String) v.get("high_water_mark"))).append(",")
                        .append(escapeCsv((String) v.get("drawdown"))).append(",")
                        .append(escapeCsv((String) v.get("data_readiness_status"))).append(",")
                        .append(v.get("is_complete")).append(",")
                        .append(escapeCsv((String) v.get("last_supported_observation_instant"))).append(",")
                        .append(escapeCsv((String) v.get("missing_requirements_detail"))).append(",")
                        .append(escapeCsv((String) v.get("adopted_dataset_id"))).append(",")
                        .append(escapeCsv((String) v.get("adopted_dataset_checksum"))).append("\n");
            }
            writeZipEntry(zos, "valuations.csv", valCsv.toString());

            // 12. Holdings CSV
            List<Map<String, Object>> holdings = jdbcTemplate.queryForList(
                    "SELECT listing_id, quantity, total_acquisition_cost, updated_at FROM positions WHERE portfolio_id = ? ORDER BY listing_id ASC LIMIT 10000",
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

            // 13. Mode History CSV
            List<Map<String, Object>> modeHist = jdbcTemplate.queryForList(
                    "SELECT id, portfolio_id, from_mode, to_mode, transition_instant, trigger_type, notes " +
                            "FROM paper_mode_history WHERE portfolio_id = ? ORDER BY transition_instant ASC LIMIT 10000",
                    portfolioId
            );
            StringBuilder modeCsv = new StringBuilder("id,portfolio_id,from_mode,to_mode,transition_instant,trigger_type,notes\n");
            for (Map<String, Object> m : modeHist) {
                modeCsv.append(escapeCsv((String) m.get("id"))).append(",")
                        .append(escapeCsv((String) m.get("portfolio_id"))).append(",")
                        .append(escapeCsv((String) m.get("from_mode"))).append(",")
                        .append(escapeCsv((String) m.get("to_mode"))).append(",")
                        .append(escapeCsv((String) m.get("transition_instant"))).append(",")
                        .append(escapeCsv((String) m.get("trigger_type"))).append(",")
                        .append(escapeCsv((String) m.get("notes"))).append("\n");
            }
            writeZipEntry(zos, "mode_history.csv", modeCsv.toString());
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
