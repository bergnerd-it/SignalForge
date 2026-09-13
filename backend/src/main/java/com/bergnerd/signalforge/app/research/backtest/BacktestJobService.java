package com.bergnerd.signalforge.app.research.backtest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.bergnerd.signalforge.app.accounting.AccountingCore;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class BacktestJobService {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private final BacktestDataReader dataReader;
    private final BacktestEngine backtestEngine;
    private final BacktestAnalyticsCalculator analyticsCalculator;

    private final ExecutorService calculationExecutor = new ThreadPoolExecutor(
            1, 1,
            0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(50),
            new ThreadFactory() {
                private int counter = 0;
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "backtest-worker-" + (++counter));
                    t.setDaemon(true);
                    return t;
                }
            },
            new ThreadPoolExecutor.AbortPolicy()
    );

    @PostConstruct
    public void recoverInterruptedJobs() {
        int updated = jdbcTemplate.update(
                "UPDATE backtest_runs SET status = 'INTERRUPTED', failure_reason = 'Process restarted while job was in progress', " +
                        "updated_at = ? WHERE status IN ('QUEUED', 'RUNNING')",
                Instant.now().toString()
        );
        if (updated > 0) {
            log.info("Recovered and marked {} unfinished backtest run(s) as INTERRUPTED on startup", updated);
        }
    }

    @PreDestroy
    public void shutdown() {
        calculationExecutor.shutdownNow();
    }

    public record CreationResult(
            BacktestDtos.BacktestSummaryResponse response,
            boolean isNew
    ) {}

    public CreationResult createBacktest(String ownerId, String idempotencyKey, BacktestDtos.CreateBacktestRequest request) {
        String uid = (ownerId == null || ownerId.isBlank()) ? "default" : ownerId;
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key header is required");
        }

        String canonicalHash = request.canonicalHash();

        // Check if idempotency key already exists for this owner
        List<Map<String, Object>> existing = jdbcTemplate.queryForList(
                "SELECT id, canonical_hash, status FROM backtest_runs WHERE owner_id = ? AND idempotency_key = ?",
                uid, idempotencyKey
        );

        if (!existing.isEmpty()) {
            Map<String, Object> row = existing.get(0);
            String existingHash = (String) row.get("canonical_hash");
            String existingId = (String) row.get("id");

            if (!canonicalHash.equals(existingHash)) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Idempotency key '" + idempotencyKey + "' already exists with conflicting configuration"
                );
            }

            // Same intent replay
            return new CreationResult(getBacktestDetail(existingId, uid), false);
        }

        // Validate basic numeric bounds
        try {
            BigDecimal cash = new BigDecimal(request.initialCash());
            if (cash.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("initialCash must be positive: " + request.initialCash());
            }
            BigDecimal comm = new BigDecimal(request.commissionPerFill());
            if (comm.compareTo(BigDecimal.ZERO) < 0) {
                throw new IllegalArgumentException("commissionPerFill cannot be negative");
            }
            BigDecimal spread = new BigDecimal(request.spreadBps());
            if (spread.compareTo(BigDecimal.ZERO) < 0) {
                throw new IllegalArgumentException("spreadBps cannot be negative");
            }
            BigDecimal slippage = new BigDecimal(request.slippageBps());
            if (slippage.compareTo(BigDecimal.ZERO) < 0) {
                throw new IllegalArgumentException("slippageBps cannot be negative");
            }
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid numeric input: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }

        // Preflight data validation to build complete normalized configuration snapshot
        BacktestDataReader.PreflightValidationResult preflightData;
        try {
            preflightData = dataReader.validatePreflight(
                    request.datasetId(),
                    request.candidateListingId(),
                    request.benchmarkListingId(),
                    request.evaluationCutoff(),
                    request.requestedStartDate(),
                    request.requestedEndDate()
            );
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }

        BigDecimal normCash = AccountingCore.normalizeStartingCash(new BigDecimal(request.initialCash()));
        BigDecimal normComm = AccountingCore.normalizeCash(new BigDecimal(request.commissionPerFill()), "commission");
        BigDecimal normSpread = new BigDecimal(request.spreadBps()).setScale(4, RoundingMode.HALF_EVEN);
        BigDecimal normSlippage = new BigDecimal(request.slippageBps()).setScale(4, RoundingMode.HALF_EVEN);

        BacktestDtos.BacktestNormalizedConfig configSnapshot = new BacktestDtos.BacktestNormalizedConfig(
                request.strategyId(),
                request.strategyVersion(),
                request.datasetId(),
                preflightData.inputChecksum(),
                preflightData.contentChecksum(),
                preflightData.parserVersion(),
                preflightData.schemaVersion(),
                preflightData.calendarId(),
                preflightData.calendarTimezone(),
                preflightData.coverageStart(),
                preflightData.coverageEnd(),
                request.candidateListingId(),
                request.benchmarkListingId(),
                request.currency(),
                normCash.toPlainString(),
                request.evaluationCutoff(),
                preflightData.selectedEvaluationSession(),
                preflightData.selectedEndSession(),
                request.requestedStartDate(),
                request.requestedEndDate(),
                preflightData.effectiveStartDate(),
                preflightData.effectiveEndDate(),
                normComm.toPlainString(),
                normSpread.toPlainString(),
                normSlippage.toPlainString(),
                "v1-basis-points-spread-slippage",
                "v2-decimal-grammar-half-even",
                "v1-open-auction-s1",
                "2.0.0-M3",
                "a13a5ad6423be4dac54e4d5e572eed41068b3f83",
                true,
                preflightData.classification(),
                "SYNTHETIC_DATA_TESTS_SOFTWARE_BEHAVIOR_ONLY"
        );

        String configJson;
        try {
            configJson = objectMapper.writeValueAsString(configSnapshot);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize normalized config", e);
        }

        String runId = "run-" + UUID.randomUUID();
        String now = Instant.now().toString();

        boolean inserted = false;
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                // Check if another thread already inserted it
                List<Map<String, Object>> existingCheck = jdbcTemplate.queryForList(
                        "SELECT id, canonical_hash FROM backtest_runs WHERE owner_id = ? AND idempotency_key = ?",
                        uid, idempotencyKey
                );
                if (!existingCheck.isEmpty()) {
                    Map<String, Object> conflictRow = existingCheck.get(0);
                    String conflictHash = (String) conflictRow.get("canonical_hash");
                    String conflictId = (String) conflictRow.get("id");
                    if (canonicalHash.equals(conflictHash)) {
                        return new CreationResult(getBacktestDetail(conflictId, uid), false);
                    } else {
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency key '" + idempotencyKey + "' already exists with conflicting configuration");
                    }
                }

                jdbcTemplate.update(
                        "INSERT INTO backtest_runs (" +
                                "id, owner_id, idempotency_key, canonical_hash, strategy_id, strategy_version, " +
                                "dataset_id, candidate_listing_id, benchmark_listing_id, initial_cash, currency, " +
                                "evaluation_cutoff, requested_start_date, requested_end_date, commission_per_fill, " +
                                "spread_bps, slippage_bps, status, progress_pct, config_json, created_at, updated_at" +
                                ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'QUEUED', 0, ?, ?, ?)",
                        runId, uid, idempotencyKey, canonicalHash, request.strategyId(), request.strategyVersion(),
                        request.datasetId(), request.candidateListingId(), request.benchmarkListingId(),
                        normCash.toPlainString(), request.currency(), request.evaluationCutoff(),
                        request.requestedStartDate(), request.requestedEndDate(), normComm.toPlainString(),
                        normSpread.toPlainString(), normSlippage.toPlainString(), configJson, now, now
                );
                inserted = true;
                break;
            } catch (org.springframework.dao.DataIntegrityViolationException e) {
                // Concurrent creation race: another thread inserted (owner_id, idempotency_key)
                List<Map<String, Object>> conflictRows = jdbcTemplate.queryForList(
                        "SELECT id, canonical_hash FROM backtest_runs WHERE owner_id = ? AND idempotency_key = ?",
                        uid, idempotencyKey
                );
                if (!conflictRows.isEmpty()) {
                    Map<String, Object> conflictRow = conflictRows.get(0);
                    String conflictHash = (String) conflictRow.get("canonical_hash");
                    String conflictId = (String) conflictRow.get("id");
                    if (canonicalHash.equals(conflictHash)) {
                        return new CreationResult(getBacktestDetail(conflictId, uid), false);
                    } else {
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency key '" + idempotencyKey + "' already exists with conflicting configuration");
                    }
                }
            } catch (org.springframework.dao.DataAccessException e) {
                // SQLite busy or lock contention: check if another thread inserted
                List<Map<String, Object>> conflictRows = jdbcTemplate.queryForList(
                        "SELECT id, canonical_hash FROM backtest_runs WHERE owner_id = ? AND idempotency_key = ?",
                        uid, idempotencyKey
                );
                if (!conflictRows.isEmpty()) {
                    Map<String, Object> conflictRow = conflictRows.get(0);
                    String conflictHash = (String) conflictRow.get("canonical_hash");
                    String conflictId = (String) conflictRow.get("id");
                    if (canonicalHash.equals(conflictHash)) {
                        return new CreationResult(getBacktestDetail(conflictId, uid), false);
                    } else {
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency key '" + idempotencyKey + "' already exists with conflicting configuration");
                    }
                }
                // Retry with brief backoff
                try {
                    Thread.sleep(30L * (attempt + 1));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted during backtest reservation retry", ie);
                }
            }
        }

        if (!inserted) {
            List<Map<String, Object>> conflictRows = jdbcTemplate.queryForList(
                    "SELECT id, canonical_hash FROM backtest_runs WHERE owner_id = ? AND idempotency_key = ?",
                    uid, idempotencyKey
            );
            if (!conflictRows.isEmpty()) {
                Map<String, Object> conflictRow = conflictRows.get(0);
                String conflictHash = (String) conflictRow.get("canonical_hash");
                String conflictId = (String) conflictRow.get("id");
                if (canonicalHash.equals(conflictHash)) {
                    return new CreationResult(getBacktestDetail(conflictId, uid), false);
                } else {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency key '" + idempotencyKey + "' already exists with conflicting configuration");
                }
            }
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Database is busy, please retry later");
        }

        // Submit to bounded async calculation worker
        try {
            calculationExecutor.submit(() -> executeRun(runId, request));
        } catch (RejectedExecutionException e) {
            jdbcTemplate.update(
                    "UPDATE backtest_runs SET status = 'FAILED', failure_reason = 'Calculation queue is full, please retry later', updated_at = ? WHERE id = ? AND status = 'QUEUED'",
                    Instant.now().toString(), runId
            );
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Calculation queue is full, please retry later");
        }

        return new CreationResult(getBacktestDetail(runId, uid), true);
    }

    private void executeRun(String runId, BacktestDtos.CreateBacktestRequest request) {
        log.info("Starting backtest execution for run {}", runId);
        try {
            List<String> statusList = jdbcTemplate.query(
                    "SELECT status FROM backtest_runs WHERE id = ?",
                    (rs, rowNum) -> rs.getString("status"),
                    runId
            );
            if (statusList.isEmpty() || "CANCELLED".equalsIgnoreCase(statusList.get(0)) ||
                    "FAILED".equalsIgnoreCase(statusList.get(0)) ||
                    "COMPLETED".equalsIgnoreCase(statusList.get(0)) ||
                    "INTERRUPTED".equalsIgnoreCase(statusList.get(0))) {
                return;
            }
            if (isCancelled(runId)) {
                markCancelled(runId);
                return;
            }

            BacktestDataReader.LoadedBacktestData data = dataReader.loadAndValidateData(
                    request.datasetId(),
                    request.candidateListingId(),
                    request.benchmarkListingId(),
                    request.evaluationCutoff(),
                    request.requestedStartDate(),
                    request.requestedEndDate()
            );

            int rowsUpdated = jdbcTemplate.update(
                    "UPDATE backtest_runs SET status = 'RUNNING', progress_pct = 10, updated_at = ? WHERE id = ? AND status = 'QUEUED'",
                    Instant.now().toString(), runId
            );
            if (rowsUpdated == 0) {
                if (isCancelled(runId)) {
                    markCancelled(runId);
                }
                return;
            }

            if (isCancelled(runId)) {
                markCancelled(runId);
                return;
            }

            jdbcTemplate.update(
                    "UPDATE backtest_runs SET progress_pct = 30, updated_at = ? WHERE id = ? AND status = 'RUNNING'",
                    Instant.now().toString(), runId
            );

            // 2. Run candidate simulation
            BigDecimal initialCash = new BigDecimal(request.initialCash());
            BigDecimal commission = new BigDecimal(request.commissionPerFill());
            BigDecimal spreadBps = new BigDecimal(request.spreadBps());
            BigDecimal slippageBps = new BigDecimal(request.slippageBps());

            BacktestEngine.SimulationResult candidateResult = backtestEngine.runS1(
                    runId,
                    BacktestDtos.SeriesType.CANDIDATE,
                    request.candidateListingId(),
                    initialCash,
                    commission,
                    spreadBps,
                    slippageBps,
                    data.evaluationSession(),
                    data.tradingSessions(),
                    data.barsByListingAndDate().get(request.candidateListingId()),
                    data.actionsByListing().get(request.candidateListingId())
            );

            if (isCancelled(runId)) {
                markCancelled(runId);
                return;
            }

            jdbcTemplate.update(
                    "UPDATE backtest_runs SET progress_pct = 60, updated_at = ? WHERE id = ? AND status = 'RUNNING'",
                    Instant.now().toString(), runId
            );

            // 3. Run benchmark simulation
            BacktestEngine.SimulationResult benchmarkResult = backtestEngine.runS1(
                    runId,
                    BacktestDtos.SeriesType.BENCHMARK,
                    request.benchmarkListingId(),
                    initialCash,
                    commission,
                    spreadBps,
                    slippageBps,
                    data.evaluationSession(),
                    data.tradingSessions(),
                    data.barsByListingAndDate().get(request.benchmarkListingId()),
                    data.actionsByListing().get(request.benchmarkListingId())
            );

            if (isCancelled(runId)) {
                markCancelled(runId);
                return;
            }

            jdbcTemplate.update(
                    "UPDATE backtest_runs SET progress_pct = 80, updated_at = ? WHERE id = ? AND status = 'RUNNING'",
                    Instant.now().toString(), runId
            );

            // 4. Calculate analytics summaries
            BacktestDtos.BacktestAnalyticsSummary candidateSummary = analyticsCalculator.calculateSummary(
                    candidateResult, benchmarkResult
            );
            BacktestDtos.BacktestAnalyticsSummary benchmarkSummary = analyticsCalculator.calculateSummary(
                    benchmarkResult, null
            );

            Map<String, Object> summaryMap = new LinkedHashMap<>();
            summaryMap.put("candidate", candidateSummary);
            summaryMap.put("benchmark", benchmarkSummary);
            String summaryJson = objectMapper.writeValueAsString(summaryMap);

            // 5. Atomic publication
            transactionTemplate.executeWithoutResult(status -> {
                if (isCancelled(runId)) {
                    throw new CancellationException("Run was cancelled before publication");
                }

                // Write daily equity rows
                persistDailyEquity(candidateResult.dailyEquity(), runId);
                persistDailyEquity(benchmarkResult.dailyEquity(), runId);

                // Write orders
                persistOrders(candidateResult.orders());
                persistOrders(benchmarkResult.orders());

                // Write events
                persistEvents(candidateResult.events());
                persistEvents(benchmarkResult.events());

                // Write final holdings
                persistHoldings(candidateResult.finalHoldings(), runId);
                persistHoldings(benchmarkResult.finalHoldings(), runId);

                // Update run to COMPLETED atomically with conditional transition
                String now = Instant.now().toString();
                int completedRows = jdbcTemplate.update(
                        "UPDATE backtest_runs SET status = 'COMPLETED', progress_pct = 100, " +
                                "effective_start_date = ?, effective_end_date = ?, " +
                                "summary_json = ?, completed_at = ?, updated_at = ? " +
                                "WHERE id = ? AND status = 'RUNNING' AND cancel_requested = 0",
                        data.effectiveStartDate(), data.effectiveEndDate(),
                        summaryJson, now, now, runId
                );
                if (completedRows == 0) {
                    throw new CancellationException("Run was cancelled before completion could be recorded");
                }
            });

            log.info("Backtest run {} completed successfully", runId);

        } catch (CancellationException e) {
            log.info("Backtest run {} was cancelled", runId);
            markCancelled(runId);
        } catch (Exception e) {
            log.error("Backtest run {} failed: {}", runId, e.getMessage(), e);
            markFailed(runId, e.getMessage());
        }
    }

    private boolean isCancelled(String runId) {
        List<Integer> list = jdbcTemplate.query(
                "SELECT cancel_requested FROM backtest_runs WHERE id = ?",
                (rs, rowNum) -> rs.getInt("cancel_requested"),
                runId
        );
        return !list.isEmpty() && list.get(0) == 1;
    }

    private void markCancelled(String runId) {
        jdbcTemplate.update(
                "UPDATE backtest_runs SET status = 'CANCELLED', updated_at = ? WHERE id = ? AND status IN ('QUEUED', 'RUNNING')",
                Instant.now().toString(), runId
        );
    }

    private void markFailed(String runId, String error) {
        String now = Instant.now().toString();
        jdbcTemplate.update(
                "UPDATE backtest_runs SET status = 'FAILED', failure_reason = ?, updated_at = ? WHERE id = ? AND status IN ('QUEUED', 'RUNNING')",
                error, now, runId
        );
    }

    private void persistDailyEquity(List<BacktestDtos.DailyEquityPoint> points, String runId) {
        for (BacktestDtos.DailyEquityPoint pt : points) {
            jdbcTemplate.update(
                    "INSERT INTO backtest_daily_equity (" +
                            "run_id, series_type, session_date, cash, holdings_value, receivables, total_equity, " +
                            "daily_return, drawdown, peak_equity, units, cost_basis, raw_close" +
                            ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    runId, pt.seriesType(), pt.sessionDate(), pt.cash(), pt.holdingsValue(), pt.receivables(),
                    pt.totalEquity(), pt.dailyReturn() != null ? pt.dailyReturn().toString() : null,
                    String.valueOf(pt.drawdown()), pt.peakEquity(), pt.units(), pt.costBasis(), pt.rawClose()
            );
        }
    }

    private void persistOrders(List<BacktestDtos.BacktestOrderDto> orders) {
        for (BacktestDtos.BacktestOrderDto ord : orders) {
            jdbcTemplate.update(
                    "INSERT INTO backtest_orders (" +
                            "id, run_id, series_type, order_type, listing_id, session_date, requested_quantity, " +
                            "executed_quantity, raw_open, fill_price, commission, spread_cost, slippage_cost, " +
                            "status, skip_reason, created_at" +
                            ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    ord.id(), ord.runId(), ord.seriesType(), ord.orderType(), ord.listingId(), ord.sessionDate(),
                    ord.requestedQuantity(), ord.executedQuantity(), ord.rawOpen(), ord.fillPrice(),
                    ord.commission(), ord.spreadCost(), ord.slippageCost(), ord.status(), ord.skipReason(), ord.createdAt()
            );
        }
    }

    private void persistEvents(List<BacktestDtos.BacktestEventDto> events) {
        for (BacktestDtos.BacktestEventDto ev : events) {
            jdbcTemplate.update(
                    "INSERT INTO backtest_events (" +
                            "id, run_id, series_type, event_seq, event_type, event_date, event_time, " +
                            "description, details_json, cash_delta, units_delta, basis_delta, receivable_delta, created_at" +
                            ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    ev.id(), ev.runId(), ev.seriesType(), ev.eventSeq(), ev.eventType(), ev.eventDate(), ev.eventTime(),
                    ev.description(), ev.detailsJson(), ev.cashDelta(), ev.unitsDelta(), ev.basisDelta(),
                    ev.receivableDelta(), ev.createdAt()
            );
        }
    }

    private void persistHoldings(BacktestDtos.BacktestHoldingsDto h, String runId) {
        jdbcTemplate.update(
                "INSERT INTO backtest_holdings (" +
                        "run_id, series_type, listing_id, units, total_cost_basis, average_cost, " +
                        "current_price, market_value, unrealized_gain, updated_at" +
                        ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                runId, h.seriesType(), h.listingId(), h.units(), h.totalCostBasis(), h.averageCost(),
                h.currentPrice(), h.marketValue(), h.unrealizedGain(), h.updatedAt()
        );
    }

    private void verifyOwnerAccess(String runId, String ownerId) {
        String uid = (ownerId == null || ownerId.isBlank()) ? "default" : ownerId;
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id FROM backtest_runs WHERE id = ? AND owner_id = ?",
                runId, uid
        );
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Backtest run not found: " + runId);
        }
    }

    public BacktestDtos.BacktestSummaryResponse cancelBacktest(String runId, String ownerId) {
        String uid = (ownerId == null || ownerId.isBlank()) ? "default" : ownerId;
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT status FROM backtest_runs WHERE id = ? AND owner_id = ?", runId, uid
        );
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Backtest run not found: " + runId);
        }

        String currentStatus = (String) rows.get(0).get("status");
        if ("COMPLETED".equalsIgnoreCase(currentStatus) ||
                "FAILED".equalsIgnoreCase(currentStatus) ||
                "CANCELLED".equalsIgnoreCase(currentStatus) ||
                "INTERRUPTED".equalsIgnoreCase(currentStatus)) {
            // Terminal - repeat safe no-op
            return getBacktestDetail(runId, uid);
        }

        // Conditional durable transition
        String now = Instant.now().toString();
        jdbcTemplate.update(
                "UPDATE backtest_runs SET cancel_requested = 1, " +
                        "status = CASE WHEN status = 'QUEUED' THEN 'CANCELLED' ELSE status END, " +
                        "updated_at = ? WHERE id = ? AND owner_id = ? AND status IN ('QUEUED', 'RUNNING')",
                now, runId, uid
        );

        return getBacktestDetail(runId, uid);
    }

    public BacktestDtos.BacktestSummaryResponse getBacktestDetail(String runId, String ownerId) {
        String uid = (ownerId == null || ownerId.isBlank()) ? "default" : ownerId;
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM backtest_runs WHERE id = ? AND owner_id = ?", runId, uid
        );
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Backtest run not found: " + runId);
        }
        return mapRunRow(rows.get(0));
    }

    public BacktestDtos.PagedResponse<BacktestDtos.BacktestSummaryResponse> listBacktests(String ownerId, int rawLimit, int rawOffset) {
        int limit = Math.max(1, Math.min(rawLimit <= 0 ? 50 : rawLimit, 200));
        int offset = Math.max(0, rawOffset);
        String uid = (ownerId == null || ownerId.isBlank()) ? "default" : ownerId;

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM backtest_runs WHERE owner_id = ?", Integer.class, uid
        );
        int total = count != null ? count : 0;

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM backtest_runs WHERE owner_id = ? ORDER BY created_at DESC, id ASC LIMIT ? OFFSET ?",
                uid, limit, offset
        );

        List<BacktestDtos.BacktestSummaryResponse> items = rows.stream().map(this::mapRunRow).toList();
        return new BacktestDtos.PagedResponse<>(items, total, limit, offset, offset + items.size() < total);
    }

    public BacktestDtos.PagedResponse<BacktestDtos.DailyEquityPoint> getDailyEquity(String runId, String ownerId, String seriesType, int rawLimit, int rawOffset) {
        verifyOwnerAccess(runId, ownerId);
        int limit = Math.max(1, Math.min(rawLimit <= 0 ? 500 : rawLimit, 5000));
        int offset = Math.max(0, rawOffset);

        BacktestDtos.SeriesType st;
        try {
            String typeStr = (seriesType != null && !seriesType.isBlank()) ? seriesType.trim().toUpperCase() : "CANDIDATE";
            st = BacktestDtos.SeriesType.valueOf(typeStr);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid series: '" + seriesType + "'. Allowed values: CANDIDATE, BENCHMARK");
        }

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM backtest_daily_equity WHERE run_id = ? AND series_type = ?",
                Integer.class, runId, st.name()
        );
        int total = count != null ? count : 0;

        List<BacktestDtos.DailyEquityPoint> items = jdbcTemplate.query(
                "SELECT session_date, series_type, cash, holdings_value, receivables, total_equity, " +
                        "daily_return, drawdown, peak_equity, units, cost_basis, raw_close " +
                        "FROM backtest_daily_equity WHERE run_id = ? AND series_type = ? " +
                        "ORDER BY session_date ASC LIMIT ? OFFSET ?",
                (rs, rowNum) -> new BacktestDtos.DailyEquityPoint(
                        rs.getString("session_date"),
                        rs.getString("series_type"),
                        rs.getString("cash"),
                        rs.getString("holdings_value"),
                        rs.getString("receivables"),
                        rs.getString("total_equity"),
                        rs.getObject("daily_return") != null ? rs.getDouble("daily_return") : null,
                        rs.getDouble("drawdown"),
                        rs.getString("peak_equity"),
                        rs.getString("units"),
                        rs.getString("cost_basis"),
                        rs.getString("raw_close")
                ),
                runId, st.name(), limit, offset
        );

        return new BacktestDtos.PagedResponse<>(items, total, limit, offset, offset + items.size() < total);
    }

    public BacktestDtos.PagedResponse<BacktestDtos.BacktestOrderDto> getOrders(String runId, String ownerId, String seriesType, int rawLimit, int rawOffset) {
        verifyOwnerAccess(runId, ownerId);
        int limit = Math.max(1, Math.min(rawLimit <= 0 ? 100 : rawLimit, 1000));
        int offset = Math.max(0, rawOffset);

        BacktestDtos.SeriesType st = null;
        if (seriesType != null && !seriesType.isBlank()) {
            try {
                st = BacktestDtos.SeriesType.valueOf(seriesType.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid series: '" + seriesType + "'. Allowed values: CANDIDATE, BENCHMARK");
            }
        }

        Integer count;
        List<BacktestDtos.BacktestOrderDto> items;
        if (st != null) {
            count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM backtest_orders WHERE run_id = ? AND series_type = ?",
                    Integer.class, runId, st.name()
            );
            items = jdbcTemplate.query(
                    "SELECT id, run_id, series_type, order_type, listing_id, session_date, requested_quantity, " +
                            "executed_quantity, raw_open, fill_price, commission, spread_cost, slippage_cost, status, skip_reason, created_at " +
                            "FROM backtest_orders WHERE run_id = ? AND series_type = ? ORDER BY session_date ASC, id ASC LIMIT ? OFFSET ?",
                    (rs, rowNum) -> mapOrderDto(rs),
                    runId, st.name(), limit, offset
            );
        } else {
            count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM backtest_orders WHERE run_id = ?",
                    Integer.class, runId
            );
            items = jdbcTemplate.query(
                    "SELECT id, run_id, series_type, order_type, listing_id, session_date, requested_quantity, " +
                            "executed_quantity, raw_open, fill_price, commission, spread_cost, slippage_cost, status, skip_reason, created_at " +
                            "FROM backtest_orders WHERE run_id = ? ORDER BY session_date ASC, id ASC LIMIT ? OFFSET ?",
                    (rs, rowNum) -> mapOrderDto(rs),
                    runId, limit, offset
            );
        }

        int total = count != null ? count : 0;
        return new BacktestDtos.PagedResponse<>(items, total, limit, offset, offset + items.size() < total);
    }

    private BacktestDtos.BacktestOrderDto mapOrderDto(java.sql.ResultSet rs) throws java.sql.SQLException {
        String status = rs.getString("status");
        String totalCashImpact = "0.00";
        if ("FILLED".equals(status)) {
            BigDecimal execQty = new BigDecimal(rs.getString("executed_quantity"));
            BigDecimal fillPrice = new BigDecimal(rs.getString("fill_price"));
            BigDecimal commission = new BigDecimal(rs.getString("commission"));
            BigDecimal gross = AccountingCore.roundCash(execQty.multiply(fillPrice, AccountingCore.MATH_CONTEXT));
            totalCashImpact = gross.add(commission).negate().toPlainString();
        }

        return new BacktestDtos.BacktestOrderDto(
                rs.getString("id"),
                rs.getString("run_id"),
                rs.getString("series_type"),
                rs.getString("order_type"),
                rs.getString("listing_id"),
                rs.getString("session_date"),
                rs.getString("requested_quantity"),
                rs.getString("executed_quantity"),
                rs.getString("raw_open"),
                rs.getString("fill_price"),
                rs.getString("commission"),
                rs.getString("spread_cost"),
                rs.getString("slippage_cost"),
                totalCashImpact,
                status,
                rs.getString("skip_reason"),
                rs.getString("created_at")
        );
    }

    public BacktestDtos.PagedResponse<BacktestDtos.BacktestEventDto> getEvents(String runId, String ownerId, String seriesType, int rawLimit, int rawOffset) {
        verifyOwnerAccess(runId, ownerId);
        int limit = Math.max(1, Math.min(rawLimit <= 0 ? 100 : rawLimit, 1000));
        int offset = Math.max(0, rawOffset);

        BacktestDtos.SeriesType st = null;
        if (seriesType != null && !seriesType.isBlank()) {
            try {
                st = BacktestDtos.SeriesType.valueOf(seriesType.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid series: '" + seriesType + "'. Allowed values: CANDIDATE, BENCHMARK");
            }
        }

        Integer count;
        List<BacktestDtos.BacktestEventDto> items;
        if (st != null) {
            count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM backtest_events WHERE run_id = ? AND series_type = ?",
                    Integer.class, runId, st.name()
            );
            items = jdbcTemplate.query(
                    "SELECT id, run_id, series_type, event_seq, event_type, event_date, event_time, description, " +
                            "details_json, cash_delta, units_delta, basis_delta, receivable_delta, created_at " +
                            "FROM backtest_events WHERE run_id = ? AND series_type = ? ORDER BY event_seq ASC LIMIT ? OFFSET ?",
                    (rs, rowNum) -> new BacktestDtos.BacktestEventDto(
                            rs.getString("id"),
                            rs.getString("run_id"),
                            rs.getString("series_type"),
                            rs.getInt("event_seq"),
                            rs.getString("event_type"),
                            rs.getString("event_date"),
                            rs.getString("event_time"),
                            rs.getString("description"),
                            rs.getString("details_json"),
                            rs.getString("cash_delta"),
                            rs.getString("units_delta"),
                            rs.getString("basis_delta"),
                            rs.getString("receivable_delta"),
                            rs.getString("created_at")
                    ),
                    runId, st.name(), limit, offset
            );
        } else {
            count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM backtest_events WHERE run_id = ?",
                    Integer.class, runId
            );
            items = jdbcTemplate.query(
                    "SELECT id, run_id, series_type, event_seq, event_type, event_date, event_time, description, " +
                            "details_json, cash_delta, units_delta, basis_delta, receivable_delta, created_at " +
                            "FROM backtest_events WHERE run_id = ? ORDER BY event_seq ASC LIMIT ? OFFSET ?",
                    (rs, rowNum) -> new BacktestDtos.BacktestEventDto(
                            rs.getString("id"),
                            rs.getString("run_id"),
                            rs.getString("series_type"),
                            rs.getInt("event_seq"),
                            rs.getString("event_type"),
                            rs.getString("event_date"),
                            rs.getString("event_time"),
                            rs.getString("description"),
                            rs.getString("details_json"),
                            rs.getString("cash_delta"),
                            rs.getString("units_delta"),
                            rs.getString("basis_delta"),
                            rs.getString("receivable_delta"),
                            rs.getString("created_at")
                    ),
                    runId, limit, offset
            );
        }

        int total = count != null ? count : 0;
        return new BacktestDtos.PagedResponse<>(items, total, limit, offset, offset + items.size() < total);
    }

    private BacktestDtos.BacktestSummaryResponse mapRunRow(Map<String, Object> r) {
        String summaryJson = (String) r.get("summary_json");
        BacktestDtos.BacktestAnalyticsSummary candSummary = null;
        BacktestDtos.BacktestAnalyticsSummary benchSummary = null;
        if (summaryJson != null && !summaryJson.isBlank()) {
            try {
                Map<String, Object> m = objectMapper.readValue(summaryJson, Map.class);
                if (m.containsKey("candidate")) {
                    candSummary = objectMapper.convertValue(m.get("candidate"), BacktestDtos.BacktestAnalyticsSummary.class);
                }
                if (m.containsKey("benchmark")) {
                    benchSummary = objectMapper.convertValue(m.get("benchmark"), BacktestDtos.BacktestAnalyticsSummary.class);
                }
            } catch (Exception e) {
                log.warn("Failed to parse summary_json for run {}: {}", r.get("id"), e.getMessage());
            }
        }

        String configJson = (String) r.get("config_json");
        BacktestDtos.BacktestNormalizedConfig normalizedConfig = null;
        if (configJson != null && !configJson.isBlank()) {
            try {
                normalizedConfig = objectMapper.readValue(configJson, BacktestDtos.BacktestNormalizedConfig.class);
            } catch (Exception e) {
                log.warn("Failed to parse config_json for run {}: {}", r.get("id"), e.getMessage());
            }
        }

        return new BacktestDtos.BacktestSummaryResponse(
                (String) r.get("id"),
                (String) r.get("owner_id"),
                (String) r.get("idempotency_key"),
                (String) r.get("canonical_hash"),
                (String) r.get("status"),
                ((Number) r.get("progress_pct")).intValue(),
                (String) r.get("strategy_id"),
                (String) r.get("strategy_version"),
                (String) r.get("dataset_id"),
                (String) r.get("candidate_listing_id"),
                (String) r.get("benchmark_listing_id"),
                (String) r.get("initial_cash"),
                (String) r.get("currency"),
                (String) r.get("evaluation_cutoff"),
                (String) r.get("requested_start_date"),
                (String) r.get("requested_end_date"),
                (String) r.get("effective_start_date"),
                (String) r.get("effective_end_date"),
                (String) r.get("commission_per_fill"),
                (String) r.get("spread_bps"),
                (String) r.get("slippage_bps"),
                (String) r.get("failure_reason"),
                candSummary,
                benchSummary,
                normalizedConfig,
                (String) r.get("created_at"),
                (String) r.get("updated_at"),
                (String) r.get("completed_at")
        );
    }
}
