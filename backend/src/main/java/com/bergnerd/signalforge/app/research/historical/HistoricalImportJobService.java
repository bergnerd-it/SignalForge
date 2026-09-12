package com.bergnerd.signalforge.app.research.historical;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class HistoricalImportJobService {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final HistoricalBundleParser bundleParser;
    private final HistoricalDataValidator dataValidator;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final BlockingQueue<Runnable> workQueue = new LinkedBlockingQueue<>(10);
    private ThreadPoolExecutor executor;

    @PostConstruct
    public void init() {
        executor = new ThreadPoolExecutor(
                1, 1,
                0L, TimeUnit.MILLISECONDS,
                workQueue,
                new ThreadFactory() {
                    private int count = 1;
                    @Override
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "historical-import-worker-" + count++);
                        t.setDaemon(true);
                        return t;
                    }
                }
        );

        recoverInterruptedJobs();
    }

    @PreDestroy
    public void shutdown() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    public synchronized void recoverInterruptedJobs() {
        int interruptedCount = jdbcTemplate.update(
                "UPDATE import_jobs SET status = 'INTERRUPTED', error_detail = 'Application restarted while job was in progress', updated_at = ? " +
                        "WHERE status IN ('QUEUED', 'RUNNING')",
                Instant.now().toString()
        );
        if (interruptedCount > 0) {
            log.warn("Marked {} unfinished import job(s) as INTERRUPTED upon application startup", interruptedCount);
        }
    }

    public HistoricalDtos.ImportJobResponse submitImport(String requestKey, byte[] zipBytes) {
        if (requestKey == null || requestKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key header is required for import");
        }
        String cleanKey = requestKey.trim();
        String inputChecksum = computeSha256(zipBytes);

        // 1. Check existing job by requestKey
        List<HistoricalDtos.ImportJobResponse> existing = jdbcTemplate.query(
                "SELECT id, request_key, input_checksum, status, dataset_id, progress_pct, message, error_detail, created_at, updated_at " +
                        "FROM import_jobs WHERE request_key = ?",
                (rs, rowNum) -> mapJobRow(rs),
                cleanKey
        );

        if (!existing.isEmpty()) {
            HistoricalDtos.ImportJobResponse existingJob = existing.get(0);
            if (!existingJob.inputChecksum().equals(inputChecksum)) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Request key '" + cleanKey + "' was previously used with different upload content"
                );
            }
            return existingJob; // Idempotent replay
        }

        // 2. Check deduplication by identical input checksum on an already completed dataset
        List<Map<String, Object>> existingDataset = jdbcTemplate.queryForList(
                "SELECT id, name FROM datasets WHERE input_checksum = ? AND parser_version = ? LIMIT 1",
                inputChecksum, HistoricalBundleParser.PARSER_VERSION
        );

        if (!existingDataset.isEmpty()) {
            String datasetId = (String) existingDataset.get(0).get("id");
            String jobId = "job-" + UUID.randomUUID();
            String now = Instant.now().toString();
            jdbcTemplate.update(
                    "INSERT INTO import_jobs (id, request_key, input_checksum, status, dataset_id, progress_pct, message, error_detail, created_at, updated_at) " +
                            "VALUES (?, ?, ?, 'COMPLETED', ?, 100, 'Reused existing identical dataset', NULL, ?, ?)",
                    jobId, cleanKey, inputChecksum, datasetId, now, now
            );
            return new HistoricalDtos.ImportJobResponse(
                    jobId, cleanKey, inputChecksum, "COMPLETED", 100, datasetId, "Reused existing identical dataset", null, now, now
            );
        }

        // 3. Queue new import job
        String jobId = "job-" + UUID.randomUUID();
        String now = Instant.now().toString();

        jdbcTemplate.update(
                "INSERT INTO import_jobs (id, request_key, input_checksum, status, dataset_id, progress_pct, message, error_detail, created_at, updated_at) " +
                        "VALUES (?, ?, ?, 'QUEUED', NULL, 0, 'Import queued', NULL, ?, ?)",
                jobId, cleanKey, inputChecksum, now, now
        );

        try {
            executor.submit(() -> processJob(jobId, cleanKey, zipBytes, inputChecksum));
        } catch (RejectedExecutionException e) {
            jdbcTemplate.update(
                    "UPDATE import_jobs SET status = 'FAILED', error_detail = 'Queue overloaded', updated_at = ? WHERE id = ?",
                    Instant.now().toString(), jobId
            );
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Import queue is overloaded, please retry later");
        }

        return getJob(jobId);
    }

    public HistoricalDtos.ImportJobResponse getJob(String jobId) {
        List<HistoricalDtos.ImportJobResponse> list = jdbcTemplate.query(
                "SELECT id, request_key, input_checksum, status, dataset_id, progress_pct, message, error_detail, created_at, updated_at " +
                        "FROM import_jobs WHERE id = ?",
                (rs, rowNum) -> mapJobRow(rs),
                jobId
        );
        if (list.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Import job not found: " + jobId);
        }
        return list.get(0);
    }

    private void processJob(String jobId, String requestKey, byte[] zipBytes, String inputChecksum) {
        try {
            updateJobProgress(jobId, "RUNNING", 15, "Decompressing and parsing archive...", null);

            HistoricalDtos.ParsedBundle bundle = bundleParser.parseBundle(zipBytes);

            updateJobProgress(jobId, "RUNNING", 45, "Validating data integrity and coverage...", null);

            HistoricalDtos.ValidationResult valResult = dataValidator.validate(bundle);

            String findingsJson = objectMapper.writeValueAsString(valResult.findings());

            if (!valResult.isValid()) {
                updateJobProgress(jobId, "FAILED", 100, "Validation rejected dataset", findingsJson);
                return;
            }

            updateJobProgress(jobId, "RUNNING", 75, "Publishing immutable dataset...", null);

            String datasetId = "dataset-" + UUID.randomUUID();
            String now = Instant.now().toString();
            String contentChecksum = computeContentChecksum(bundle);

            transactionTemplate.executeWithoutResult(status -> {
                // Insert dataset
                jdbcTemplate.update(
                        "INSERT INTO datasets (id, name, source, classification, schema_version, parser_version, input_checksum, content_checksum, manifest_json, coverage_start, coverage_end, validation_status, validation_findings_json, quality_label, imported_at, created_at) " +
                                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        datasetId,
                        bundle.manifest().source() + " (" + bundle.manifest().coverage().startDate() + ".." + bundle.manifest().coverage().endDate() + ")",
                        bundle.manifest().source(),
                        bundle.manifest().classification().toUpperCase(),
                        bundle.manifest().schemaVersion(),
                        HistoricalBundleParser.PARSER_VERSION,
                        inputChecksum,
                        contentChecksum,
                        bundle.manifestRawJson(),
                        bundle.manifest().coverage().startDate(),
                        bundle.manifest().coverage().endDate(),
                        valResult.status(),
                        findingsJson,
                        valResult.qualityLabel(),
                        now,
                        now
                );

                // Insert dataset_listings
                for (HistoricalDtos.ParsedInstrumentRecord inst : bundle.instruments()) {
                    jdbcTemplate.update(
                            "INSERT INTO dataset_listings (dataset_id, listing_id, instrument_id, symbol, venue, quote_currency, calendar_id, inception_date, termination_date, isin) " +
                                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                            datasetId, inst.listingId(), inst.instrumentId(), inst.symbol(), inst.venue(), inst.quoteCurrency(), inst.calendarId(), inst.inceptionDate(), inst.terminationDate(), inst.isin()
                    );
                }

                // Insert dataset_sessions
                for (HistoricalDtos.ParsedSessionRecord sess : bundle.sessions()) {
                    jdbcTemplate.update(
                            "INSERT INTO dataset_sessions (dataset_id, calendar_id, session_date, open_time, close_time, session_type) " +
                                    "VALUES (?, ?, ?, ?, ?, ?)",
                            datasetId, sess.calendarId(), sess.sessionDate(), sess.openTime(), sess.closeTime(), sess.sessionType().toUpperCase()
                    );
                }

                // Insert historical_bars
                for (HistoricalDtos.ParsedPriceRecord p : bundle.prices()) {
                    jdbcTemplate.update(
                            "INSERT INTO historical_bars (dataset_id, listing_id, session_date, open, high, low, close, volume, available_at) " +
                                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                            datasetId, p.listingId(), p.sessionDate(), p.open(), p.high(), p.low(), p.close(), p.volume(), p.availableAt()
                    );
                }

                // Insert historical_actions
                for (HistoricalDtos.ParsedActionRecord a : bundle.actions()) {
                    jdbcTemplate.update(
                            "INSERT INTO historical_actions (dataset_id, action_id, listing_id, action_type, effective_date, available_at, split_ratio_numerator, split_ratio_denominator, distribution_amount, distribution_currency, payment_date, payment_instant) " +
                                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                            datasetId, a.actionId(), a.listingId(), a.actionType().toUpperCase(), a.effectiveDate(), a.availableAt(), a.splitRatioNumerator(), a.splitRatioDenominator(), a.distributionAmount(), a.distributionCurrency(), a.paymentDate(), a.paymentInstant()
                    );
                }

                // Update job
                jdbcTemplate.update(
                        "UPDATE import_jobs SET status = 'COMPLETED', dataset_id = ?, progress_pct = 100, message = 'Dataset published successfully', updated_at = ? WHERE id = ?",
                        datasetId, now, jobId
                );
            });

            log.info("Successfully published dataset {} with {} bars and {} actions",
                    datasetId, bundle.prices().size(), bundle.actions().size());

        } catch (Exception e) {
            log.error("Import job {} failed: {}", jobId, e.getMessage(), e);
            updateJobProgress(jobId, "FAILED", 100, "Import processing error", e.getMessage());
        }
    }

    private void updateJobProgress(String jobId, String status, int progressPct, String message, String errorDetail) {
        jdbcTemplate.update(
                "UPDATE import_jobs SET status = ?, progress_pct = ?, message = ?, error_detail = ?, updated_at = ? WHERE id = ?",
                status, progressPct, message, errorDetail, Instant.now().toString(), jobId
        );
    }

    private String computeContentChecksum(HistoricalDtos.ParsedBundle bundle) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(bundle.manifestRawJson().getBytes(StandardCharsets.UTF_8));
            for (HistoricalDtos.ParsedPriceRecord p : bundle.prices()) {
                md.update((p.listingId() + p.sessionDate() + p.close()).getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public static String computeSha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private HistoricalDtos.ImportJobResponse mapJobRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new HistoricalDtos.ImportJobResponse(
                rs.getString("id"),
                rs.getString("request_key"),
                rs.getString("input_checksum"),
                rs.getString("status"),
                rs.getInt("progress_pct"),
                rs.getString("dataset_id"),
                rs.getString("message"),
                rs.getString("error_detail"),
                rs.getString("created_at"),
                rs.getString("updated_at")
        );
    }
}
