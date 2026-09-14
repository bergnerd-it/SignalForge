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
    private final BuildIdentityResolver buildIdentityResolver;
    private final ExperimentService experimentService;

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

        String intentHash;
        try {
            intentHash = request.canonicalHash();
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid backtest request: " + e.getMessage(), e);
        }
        List<Map<String, Object>> existing = jdbcTemplate.queryForList(
                "SELECT * FROM backtest_runs WHERE owner_id = ? AND idempotency_key = ?", uid, idempotencyKey);
        if (!existing.isEmpty()) {
            return resolveReplay(existing.get(0), uid, idempotencyKey, intentHash);
        }

        // Preflight data validation to build complete normalized configuration snapshot
        BacktestDataReader.PreflightValidationResult preflightData;
        try {
            preflightData = dataReader.validatePreflight(
                    uid,
                    request.strategyId(),
                    request.universeId(),
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

        // Validate strategy existence and version
        List<Map<String, Object>> stratVersions = jdbcTemplate.queryForList(
                "SELECT * FROM strategy_versions WHERE strategy_id = ? AND strategy_version = ?",
                request.strategyId(), request.strategyVersion()
        );
        if (stratVersions.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unknown strategy " + request.strategyId() + " version " + request.strategyVersion());
        }

        if (request.experimentId() != null && !request.experimentId().isBlank()) {
            List<Map<String, Object>> experiments = jdbcTemplate.queryForList(
                    "SELECT owner_id, strategy_id, strategy_version, dataset_id, universe_id, " +
                            "candidate_listing_id, benchmark_listing_id, parameters_json FROM experiments WHERE id = ?",
                    request.experimentId());
            if (experiments.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Experiment not found: " + request.experimentId());
            }
            Map<String, Object> experiment = experiments.get(0);
            String experimentOwner = (String) experiment.get("owner_id");
            if (!uid.equals(experimentOwner) && !"default".equals(experimentOwner)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Experiment belongs to another owner");
            }
            if (!request.strategyId().equals(experiment.get("strategy_id")) ||
                    !request.strategyVersion().equals(experiment.get("strategy_version")) ||
                    !request.datasetId().equals(experiment.get("dataset_id")) ||
                    !java.util.Objects.equals(request.universeId(), experiment.get("universe_id")) ||
                    !java.util.Objects.equals(request.candidateListingId(), experiment.get("candidate_listing_id")) ||
                    !request.benchmarkListingId().equals(experiment.get("benchmark_listing_id")) ||
                    !java.util.Objects.equals(request.parametersJson(), experiment.get("parameters_json"))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Run configuration does not match frozen experiment identities");
            }
        }

        // Validate strategy parameters against schema / constraints
        Map<String, Object> params = new HashMap<>();
        if (request.parametersJson() != null && !request.parametersJson().isBlank()) {
            try {
                params = objectMapper.readValue(request.parametersJson(), Map.class);
            } catch (Exception e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid parametersJson: " + e.getMessage());
            }
        }

        if ("S1-BUY-AND-HOLD".equalsIgnoreCase(request.strategyId()) || "ETF_BUY_HOLD_V1".equalsIgnoreCase(request.strategyId())) {
            if (!params.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "S1 buy-and-hold does not accept parameters");
            }
        } else if ("S2-MOMENTUM-TOP-K".equalsIgnoreCase(request.strategyId()) || "ETF_MOMENTUM_12_1_V1".equalsIgnoreCase(request.strategyId())) {
            if (request.universeId() == null || request.universeId().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "universeId is required for S2 momentum strategy");
            }
            if (params.size() > 1) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unrecognized parameter for S2 momentum strategy");
            }
            if (!params.isEmpty() && !params.containsKey("k")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unrecognized parameter for S2 momentum strategy");
            }
            int kVal = params.containsKey("k") ? parseIntegerParameter(params.get("k"), "k") : 3;
            Integer universeSize = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM universe_listings WHERE universe_id = ?", Integer.class, request.universeId()
            );
            int uSize = universeSize != null ? universeSize : 0;
            if (kVal < 1 || kVal > uSize) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Parameter 'k' must be between 1 and " + uSize + ", but was: " + kVal);
            }
        } else if ("S3-DUAL-MOMENTUM-CASH-FILTER".equalsIgnoreCase(request.strategyId()) || "ETF_TREND_10M_V1".equalsIgnoreCase(request.strategyId())) {
            if ("1.0.1".equals(request.strategyVersion()) && !params.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "S3 version 1.0.1 has a fixed ten-month rule and accepts no parameters");
            }
            if (params.containsKey("lookbackMonths")) {
                int lbVal = parseIntegerParameter(params.get("lookbackMonths"), "lookbackMonths");
                if (lbVal != 10) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Lookback months for S3 trend strategy is fixed at 10, got: " + lbVal);
                }
            }
            for (String key : params.keySet()) {
                if (!"lookbackMonths".equals(key)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unrecognized parameter for S3 trend strategy: " + key);
                }
            }
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
                buildIdentityResolver.getEngineVersion(),
                buildIdentityResolver.getSourceCommit(),
                buildIdentityResolver.isDirty(),
                buildIdentityResolver.getCodeFingerprint(),
                preflightData.classification(),
                "SYNTHETIC_DATA_TESTS_SOFTWARE_BEHAVIOR_ONLY",
                request.universeId(),
                request.parametersJson(),
                request.experimentId()
        );

        String canonicalHash = configSnapshot.canonicalHash();

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
                        "SELECT * FROM backtest_runs WHERE owner_id = ? AND idempotency_key = ?",
                        uid, idempotencyKey
                );
                if (!existingCheck.isEmpty()) {
                    return resolveReplay(existingCheck.get(0), uid, idempotencyKey, intentHash);
                }

                jdbcTemplate.update(
                        "INSERT INTO backtest_runs (" +
                                "id, owner_id, idempotency_key, canonical_hash, strategy_id, strategy_version, " +
                                "dataset_id, candidate_listing_id, benchmark_listing_id, initial_cash, currency, " +
                                "evaluation_cutoff, requested_start_date, requested_end_date, commission_per_fill, " +
                                "spread_bps, slippage_bps, universe_id, parameters_json, experiment_id, " +
                                "status, progress_pct, config_json, created_at, updated_at" +
                                ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'QUEUED', 0, ?, ?, ?)",
                        runId, uid, idempotencyKey, canonicalHash, request.strategyId(), request.strategyVersion(),
                        request.datasetId(), request.candidateListingId(), request.benchmarkListingId(),
                        normCash.toPlainString(), request.currency(), request.evaluationCutoff(),
                        request.requestedStartDate(), request.requestedEndDate(), normComm.toPlainString(),
                        normSpread.toPlainString(), normSlippage.toPlainString(),
                        request.universeId(), request.parametersJson(), request.experimentId(),
                        configJson, now, now
                );
                inserted = true;
                break;
            } catch (org.springframework.dao.DataIntegrityViolationException e) {
                // Concurrent creation race: another thread inserted (owner_id, idempotency_key)
                List<Map<String, Object>> conflictRows = jdbcTemplate.queryForList(
                        "SELECT * FROM backtest_runs WHERE owner_id = ? AND idempotency_key = ?",
                        uid, idempotencyKey
                );
                if (!conflictRows.isEmpty()) {
                    return resolveReplay(conflictRows.get(0), uid, idempotencyKey, intentHash);
                }
            } catch (org.springframework.dao.DataAccessException e) {
                // SQLite busy or lock contention: check if another thread inserted
                List<Map<String, Object>> conflictRows = jdbcTemplate.queryForList(
                        "SELECT * FROM backtest_runs WHERE owner_id = ? AND idempotency_key = ?",
                        uid, idempotencyKey
                );
                if (!conflictRows.isEmpty()) {
                    return resolveReplay(conflictRows.get(0), uid, idempotencyKey, intentHash);
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
                    "SELECT * FROM backtest_runs WHERE owner_id = ? AND idempotency_key = ?",
                    uid, idempotencyKey
            );
            if (!conflictRows.isEmpty()) {
                return resolveReplay(conflictRows.get(0), uid, idempotencyKey, intentHash);
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

    private CreationResult resolveReplay(Map<String, Object> stored, String ownerId, String key, String intentHash) {
        BacktestDtos.CreateBacktestRequest originalIntent = new BacktestDtos.CreateBacktestRequest(
                (String) stored.get("dataset_id"),
                (String) stored.get("candidate_listing_id"),
                (String) stored.get("benchmark_listing_id"),
                (String) stored.get("evaluation_cutoff"),
                (String) stored.get("requested_start_date"),
                (String) stored.get("requested_end_date"),
                (String) stored.get("initial_cash"),
                (String) stored.get("currency"),
                (String) stored.get("commission_per_fill"),
                (String) stored.get("spread_bps"),
                (String) stored.get("slippage_bps"),
                (String) stored.get("strategy_id"),
                (String) stored.get("strategy_version"),
                (String) stored.get("universe_id"),
                (String) stored.get("parameters_json"),
                (String) stored.get("experiment_id")
        );
        if (!intentHash.equals(originalIntent.canonicalHash())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Idempotency key '" + key + "' already exists with conflicting request");
        }
        return new CreationResult(getBacktestDetail((String) stored.get("id"), ownerId), false);
    }

    private void executeRun(String runId, BacktestDtos.CreateBacktestRequest request) {
        log.info("Starting backtest execution for run {}", runId);
        try {
            String ownerId = jdbcTemplate.queryForObject(
                    "SELECT owner_id FROM backtest_runs WHERE id = ?", String.class, runId);
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
                    ownerId,
                    request.strategyId(),
                    request.universeId(),
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

            BacktestEngine.SimulationResult candidateResult;
            String stratId = (request.strategyId() != null && !request.strategyId().isBlank())
                    ? request.strategyId().trim() : "ETF_BUY_HOLD_V1";
            if ("ETF_BUY_HOLD_V1".equalsIgnoreCase(stratId)) {
                candidateResult = backtestEngine.runS1(
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
            } else {
                Map<String, Object> params = new LinkedHashMap<>();
                if (request.parametersJson() != null && !request.parametersJson().isBlank()) {
                    try {
                        params = objectMapper.readValue(request.parametersJson(), Map.class);
                    } catch (Exception e) {
                        log.warn("Failed to parse parametersJson for run {}: {}", runId, e.getMessage());
                    }
                }
                candidateResult = backtestEngine.runStrategy(
                        runId,
                        BacktestDtos.SeriesType.CANDIDATE,
                        stratId,
                        request.strategyVersion() != null ? request.strategyVersion() : "1.0.0",
                        request.universeId(),
                        data.targetListingIds(),
                        request.candidateListingId(),
                        initialCash,
                        commission,
                        spreadBps,
                        slippageBps,
                        data.evaluationSession(),
                        data.tradingSessions(),
                        data.calendarSessions(),
                        data.barsByListingAndDate(),
                        data.actionsByListing(),
                        params
                );
            }

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
                    candidateResult, benchmarkResult, data.calendarSessions()
            );
            BacktestDtos.BacktestAnalyticsSummary benchmarkSummary = analyticsCalculator.calculateSummary(
                    benchmarkResult, null, data.calendarSessions()
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

                // Write holdings
                if (candidateResult.allHoldings() != null && !candidateResult.allHoldings().isEmpty()) {
                    for (BacktestDtos.BacktestHoldingsDto h : candidateResult.allHoldings()) {
                        persistHoldings(h, runId);
                    }
                } else if (candidateResult.finalHoldings() != null) {
                    persistHoldings(candidateResult.finalHoldings(), runId);
                }
                if (benchmarkResult.allHoldings() != null && !benchmarkResult.allHoldings().isEmpty()) {
                    for (BacktestDtos.BacktestHoldingsDto h : benchmarkResult.allHoldings()) {
                        persistHoldings(h, runId);
                    }
                } else if (benchmarkResult.finalHoldings() != null) {
                    persistHoldings(benchmarkResult.finalHoldings(), runId);
                }

                // Write signals
                if (candidateResult.signals() != null && !candidateResult.signals().isEmpty()) {
                    persistSignals(candidateResult.signals(), runId);
                }

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
                            "daily_return, drawdown, peak_equity, units, cost_basis, raw_close, point_kind, observation_time" +
                            ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    runId, pt.seriesType(), pt.sessionDate(), pt.cash(), pt.holdingsValue(), pt.receivables(),
                    pt.totalEquity(), pt.dailyReturn() != null ? pt.dailyReturn().toString() : null,
                    String.valueOf(pt.drawdown()), pt.peakEquity(), pt.units(), pt.costBasis(), pt.rawClose(),
                    pt.pointKind(), pt.observationTime()
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

    private void persistSignals(List<BacktestDtos.BacktestSignalDto> signals, String runId) {
        for (BacktestDtos.BacktestSignalDto sig : signals) {
            String sigId = (sig.id() != null && !sig.id().isBlank()) ? sig.id() : "sig-" + UUID.randomUUID();
            jdbcTemplate.update(
                    "INSERT INTO backtest_signals (" +
                            "id, run_id, strategy_id, strategy_version, universe_id, " +
                            "evaluation_date, evaluation_time, decision_instant, scheduled_execution_date, " +
                            "target_allocation_summary, status, reason_code, details_json, created_at" +
                            ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    sigId, runId, sig.strategyId(), sig.strategyVersion(), sig.universeId(),
                    sig.evaluationDate(), sig.evaluationTime(), sig.decisionInstant(), sig.scheduledExecutionDate(),
                    sig.targetAllocationSummary(), sig.status(), sig.reasonCode(), sig.detailsJson(), sig.createdAt()
            );

            if (sig.items() != null) {
                for (BacktestDtos.BacktestSignalItemDto item : sig.items()) {
                    String itemId = (item.id() != null && !item.id().isBlank()) ? item.id() : "sigi-" + UUID.randomUUID();
                    jdbcTemplate.update(
                            "INSERT INTO backtest_signal_items (" +
                                    "id, signal_id, listing_id, score, index_value, sma_value, " +
                                    "rank, eligible, selected, target_weight, reason_code" +
                                    ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                            itemId, sigId, item.listingId(), item.score(), item.indexValue(), item.smaValue(),
                            item.rank(), item.eligible() ? 1 : 0, item.selected() ? 1 : 0, item.targetWeight(), item.reasonCode()
                    );
                }
            }
        }
    }

    void recordHoldoutExposure(String runId, String ownerId, String accessType) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT experiment_id FROM backtest_runs WHERE id = ?", runId
        );
        if (!rows.isEmpty() && rows.get(0).get("experiment_id") != null) {
            String expId = (String) rows.get(0).get("experiment_id");
            experimentService.recordExposure(expId, runId, accessType, ownerId, "{\"runId\":\"" + runId + "\"}");
        }
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
        recordHoldoutExposure(runId, uid, "VIEW_DETAIL");
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

        for (Map<String, Object> r : rows) {
            if (r.get("experiment_id") != null) {
                recordHoldoutExposure((String) r.get("id"), uid, "SUMMARY_VIEW");
            }
        }

        List<BacktestDtos.BacktestSummaryResponse> items = rows.stream().map(this::mapRunRow).toList();
        return new BacktestDtos.PagedResponse<>(items, total, limit, offset, offset + items.size() < total);
    }

    public BacktestDtos.PagedResponse<BacktestDtos.DailyEquityPoint> getDailyEquity(String runId, String ownerId, String seriesType, int rawLimit, int rawOffset) {
        verifyOwnerAccess(runId, ownerId);
        String uid = (ownerId == null || ownerId.isBlank()) ? "default" : ownerId;
        recordHoldoutExposure(runId, uid, "VIEW_DETAIL");

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
                        "daily_return, drawdown, peak_equity, units, cost_basis, raw_close, point_kind, observation_time " +
                        "FROM backtest_daily_equity WHERE run_id = ? AND series_type = ? " +
                        "ORDER BY session_date ASC, point_kind ASC LIMIT ? OFFSET ?",
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
                        rs.getString("raw_close"),
                        rs.getString("point_kind"),
                        rs.getString("observation_time")
                ),
                runId, st.name(), limit, offset
        );

        return new BacktestDtos.PagedResponse<>(items, total, limit, offset, offset + items.size() < total);
    }

    public BacktestDtos.PagedResponse<BacktestDtos.BacktestOrderDto> getOrders(String runId, String ownerId, String seriesType, int rawLimit, int rawOffset) {
        verifyOwnerAccess(runId, ownerId);
        String uid = (ownerId == null || ownerId.isBlank()) ? "default" : ownerId;
        recordHoldoutExposure(runId, uid, "VIEW_DETAIL");

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
        String uid = (ownerId == null || ownerId.isBlank()) ? "default" : ownerId;
        recordHoldoutExposure(runId, uid, "VIEW_DETAIL");

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

    public BacktestDtos.PagedResponse<BacktestDtos.BacktestSignalDto> getBacktestSignals(String runId, String ownerId, int rawLimit, int rawOffset) {
        verifyOwnerAccess(runId, ownerId);
        int limit = Math.max(1, Math.min(rawLimit <= 0 ? 50 : rawLimit, 500));
        int offset = Math.max(0, rawOffset);
        String uid = (ownerId == null || ownerId.isBlank()) ? "default" : ownerId;

        recordHoldoutExposure(runId, uid, "VIEW_DETAIL");

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM backtest_signals WHERE run_id = ?",
                Integer.class, runId
        );
        int total = count != null ? count : 0;

        List<Map<String, Object>> signalRows = jdbcTemplate.queryForList(
                "SELECT * FROM backtest_signals WHERE run_id = ? ORDER BY evaluation_date ASC LIMIT ? OFFSET ?",
                runId, limit, offset
        );

        List<BacktestDtos.BacktestSignalDto> items = new ArrayList<>();
        for (Map<String, Object> sr : signalRows) {
            String sigId = (String) sr.get("id");
            List<BacktestDtos.BacktestSignalItemDto> signalItems = jdbcTemplate.query(
                    "SELECT id, signal_id, listing_id, score, index_value, sma_value, rank, eligible, selected, target_weight, reason_code " +
                            "FROM backtest_signal_items WHERE signal_id = ? ORDER BY rank ASC, listing_id ASC",
                    (rs, i) -> new BacktestDtos.BacktestSignalItemDto(
                            rs.getString("id"),
                            rs.getString("signal_id"),
                            rs.getString("listing_id"),
                            rs.getString("score"),
                            rs.getString("index_value"),
                            rs.getString("sma_value"),
                            rs.getObject("rank") != null ? rs.getInt("rank") : null,
                            rs.getInt("eligible") == 1,
                            rs.getInt("selected") == 1,
                            rs.getString("target_weight"),
                            rs.getString("reason_code")
                    ),
                    sigId
            );

            items.add(new BacktestDtos.BacktestSignalDto(
                    sigId,
                    (String) sr.get("run_id"),
                    (String) sr.get("strategy_id"),
                    (String) sr.get("strategy_version"),
                    (String) sr.get("universe_id"),
                    (String) sr.get("evaluation_date"),
                    (String) sr.get("evaluation_time"),
                    (String) sr.get("decision_instant"),
                    (String) sr.get("scheduled_execution_date"),
                    (String) sr.get("target_allocation_summary"),
                    (String) sr.get("status"),
                    (String) sr.get("reason_code"),
                    (String) sr.get("details_json"),
                    signalItems,
                    (String) sr.get("created_at")
            ));
        }

        return new BacktestDtos.PagedResponse<>(items, total, limit, offset, offset + items.size() < total);
    }

    BacktestDtos.BacktestSummaryResponse mapRunRow(Map<String, Object> r) {
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

        List<BacktestDtos.BacktestHoldingsDto> candidateHoldings = jdbcTemplate.query(
                "SELECT series_type, listing_id, units, total_cost_basis, average_cost, current_price, market_value, unrealized_gain, updated_at " +
                        "FROM backtest_holdings WHERE run_id = ? AND series_type = 'CANDIDATE' ORDER BY listing_id ASC",
                (rs, rowNum) -> new BacktestDtos.BacktestHoldingsDto(
                        rs.getString("series_type"),
                        rs.getString("listing_id"),
                        rs.getString("units"),
                        rs.getString("total_cost_basis"),
                        rs.getString("average_cost"),
                        rs.getString("current_price"),
                        rs.getString("market_value"),
                        rs.getString("unrealized_gain"),
                        rs.getString("updated_at")
                ),
                r.get("id")
        );

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
                (String) r.get("universe_id"),
                (String) r.get("parameters_json"),
                (String) r.get("experiment_id"),
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
                candidateHoldings,
                (String) r.get("created_at"),
                (String) r.get("updated_at"),
                (String) r.get("completed_at")
        );
    }

    public String exportSignalsAsCsv(String runId, String ownerId) {
        verifyOwnerAccess(runId, ownerId);
        Integer rowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM backtest_signals s LEFT JOIN backtest_signal_items i ON s.id = i.signal_id WHERE s.run_id = ?",
                Integer.class, runId);
        final int maxRows = 10_000;
        final int maxChars = 5_000_000;
        if (rowCount != null && rowCount > maxRows) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Signals CSV exceeds the 10,000-row limit");
        }
        String uid = (ownerId == null || ownerId.isBlank()) ? "default" : ownerId;
        recordHoldoutExposure(runId, uid, "EXPORT_DOWNLOAD");

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT s.evaluation_date, s.strategy_id, s.universe_id, i.listing_id, i.score, i.index_value, i.sma_value, " +
                        "i.rank, i.eligible, i.selected, i.target_weight, i.reason_code, s.scheduled_execution_date, s.status " +
                        "FROM backtest_signals s " +
                        "LEFT JOIN backtest_signal_items i ON s.id = i.signal_id " +
                        "WHERE s.run_id = ? " +
                        "ORDER BY s.evaluation_date ASC, i.rank ASC, i.listing_id ASC LIMIT ?",
                runId, maxRows
        );

        StringBuilder sb = new StringBuilder();
        sb.append("evaluation_date,strategy_id,universe_id,listing_id,score,index_value,sma_value,rank,eligible,selected,target_weight,reason_code,scheduled_execution_date,status\r\n");
        for (Map<String, Object> r : rows) {
            sb.append(csvVal(r.get("evaluation_date"))).append(",");
            sb.append(csvVal(r.get("strategy_id"))).append(",");
            sb.append(csvVal(r.get("universe_id"))).append(",");
            sb.append(csvVal(r.get("listing_id"))).append(",");
            sb.append(csvVal(r.get("score"))).append(",");
            sb.append(csvVal(r.get("index_value"))).append(",");
            sb.append(csvVal(r.get("sma_value"))).append(",");
            sb.append(csvVal(r.get("rank"))).append(",");
            Object elig = r.get("eligible");
            sb.append(elig == null ? "" : (((Number) elig).intValue() == 1 ? "true" : "false")).append(",");
            Object sel = r.get("selected");
            sb.append(sel == null ? "" : (((Number) sel).intValue() == 1 ? "true" : "false")).append(",");
            sb.append(csvVal(r.get("target_weight"))).append(",");
            sb.append(csvVal(r.get("reason_code"))).append(",");
            sb.append(csvVal(r.get("scheduled_execution_date"))).append(",");
            sb.append(csvVal(r.get("status"))).append("\r\n");
            if (sb.length() > maxChars) {
                throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Signals CSV exceeds the 5,000,000-character limit");
            }
        }
        return sb.toString();
    }

    private String csvVal(Object obj) {
        if (obj == null) {
            return "";
        }
        String s = obj.toString();
        if (!s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0) {
            try {
                new BigDecimal(s);
            } catch (NumberFormatException e) {
                s = "'" + s;
            }
        }
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    private int parseIntegerParameter(Object value, String name) {
        if (!(value instanceof Number)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Parameter '" + name + "' must be an integer");
        }
        try {
            return new BigDecimal(value.toString()).intValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Parameter '" + name + "' must be an integer");
        }
    }
}
