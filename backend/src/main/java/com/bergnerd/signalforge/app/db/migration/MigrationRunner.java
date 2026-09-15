package com.bergnerd.signalforge.app.db.migration;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;

/**
 * Fixed ordered JDBC migration runner replacing DatabaseInitializer.
 * Detects genuinely empty databases, recognized legacy schemas, and supported versioned schemas.
 * Enforces atomic schema changes, checksum verification, WAL checkpoint backups before conversion,
 * trigger-aware statement execution, and idempotent repeat startup.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MigrationRunner {

    private final JdbcTemplate jdbcTemplate;
    private final DataSource dataSource;
    private final TransactionTemplate transactionTemplate;
    private final MigrationBackupService backupService;
    private final LegacyDataMigrator legacyDataMigrator;

    @Value("${spring.datasource.url}")
    private String datasourceUrl;

    public static final String CODE_VERSION = "2.0.0-M5";
    public static final int CURRENT_SCHEMA_VERSION = 12;

    public static final List<String> DEFAULT_TICKERS = List.of(
            "AAPL", "GOOGL", "MSFT", "AMZN", "TSLA",
            "NVDA", "META", "JPM", "V", "NFLX"
    );

    public static final Set<String> RECOGNIZED_LEGACY_TABLES = Set.of(
            "users_profile", "watchlist", "positions", "trades", "portfolio_snapshots", "chat_messages"
    );

    public enum SchemaStatus {
        EMPTY,
        LEGACY_RECOGNIZED,
        VERSIONED,
        UNKNOWN_OR_PARTIAL,
        NEWER_VERSION
    }

    @PostConstruct
    public void migrate() {
        ensureDatabaseDirectory();
        runMigration();
    }

    public synchronized void runMigration() {
        Set<String> existingTables = getExistingTables();
        SchemaStatus status = detectSchemaStatus(existingTables);
        log.info("Detected database schema status: {}", status);

        switch (status) {
            case EMPTY -> applyFreshInstall();
            case LEGACY_RECOGNIZED -> applyLegacyMigration();
            case VERSIONED -> verifyAndApplyVersionedMigrations();
            case NEWER_VERSION -> throw new IllegalStateException(
                    "Database schema is newer than supported code version. Downgrade requires manual restore."
            );
            case UNKNOWN_OR_PARTIAL -> throw new IllegalStateException(
                    "Database contains an unrecognized, partial, or corrupted schema: " + existingTables
            );
        }
    }

    private void ensureDatabaseDirectory() {
        if (datasourceUrl != null && datasourceUrl.startsWith("jdbc:sqlite:")) {
            String dbPath = datasourceUrl.substring("jdbc:sqlite:".length());
            if (!":memory:".equals(dbPath)) {
                File dbFile = new File(dbPath);
                File parent = dbFile.getParentFile();
                if (parent != null && !parent.exists()) {
                    boolean created = parent.mkdirs();
                    log.info("Created database parent directory: {} (success={})", parent.getAbsolutePath(), created);
                }
            }
        }
    }

    private Set<String> getExistingTables() {
        List<String> tables = jdbcTemplate.query(
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'",
                (rs, rowNum) -> rs.getString(1)
        );
        return new HashSet<>(tables);
    }

    public SchemaStatus detectSchemaStatus(Set<String> tables) {
        if (tables.isEmpty()) {
            return SchemaStatus.EMPTY;
        }

        if (tables.contains("schema_migrations")) {
            Integer maxVersion = jdbcTemplate.queryForObject(
                    "SELECT MAX(version) FROM schema_migrations",
                    Integer.class
            );
            if (maxVersion != null && maxVersion > CURRENT_SCHEMA_VERSION) {
                return SchemaStatus.NEWER_VERSION;
            }
            return SchemaStatus.VERSIONED;
        }

        // Check legacy recognition
        if (tables.containsAll(RECOGNIZED_LEGACY_TABLES)) {
            // Check if any unexpected non-legacy tables exist
            Set<String> unexpected = new HashSet<>(tables);
            unexpected.removeAll(RECOGNIZED_LEGACY_TABLES);
            if (unexpected.isEmpty()) {
                return SchemaStatus.LEGACY_RECOGNIZED;
            }
        }

        return SchemaStatus.UNKNOWN_OR_PARTIAL;
    }

    private void applyFreshInstall() {
        log.info("Applying fresh M3 installation schema (Versions 1-7)...");
        String v1Sql = loadResource("db/migration/V1__init_m1b_schema.sql");
        String v2Sql = loadResource("db/migration/V2__m1b_review_fixes.sql");
        String v3Sql = loadResource("db/migration/V3__historical_datasets_and_import.sql");
        String v4Sql = loadResource("db/migration/V4__backtest_engine.sql");
        String v5Sql = loadResource("db/migration/V5__backtest_engine_hardening.sql");
        String v6Sql = loadResource("db/migration/V6__backtest_listing_integrity.sql");
        String v7Sql = loadResource("db/migration/V7__backtest_funded_observation.sql");
        String v8Sql = loadResource("db/migration/V8__strategy_versions_and_comparisons.sql");
        String v9Sql = loadResource("db/migration/V9__fix_strategy_schema_and_integrity.sql");
        String v10Sql = loadResource("db/migration/V10__restore_immutable_trend_version.sql");
        String v11Sql = loadResource("db/migration/V11__protect_terminal_signal_items.sql");
        String v12Sql = loadResource("db/migration/V12__paper_tracking_and_proposals.sql");
        String v1Checksum = computeV1MigrationChecksum(v1Sql);
        String v2Checksum = computeSqlChecksum(v2Sql);
        String v3Checksum = computeSqlChecksum(v3Sql);
        String v4Checksum = computeSqlChecksum(v4Sql);
        String v5Checksum = computeSqlChecksum(v5Sql);
        String v6Checksum = computeSqlChecksum(v6Sql);
        String v7Checksum = computeSqlChecksum(v7Sql);
        String v8Checksum = computeSqlChecksum(v8Sql);
        String v9Checksum = computeSqlChecksum(v9Sql);
        String v10Checksum = computeSqlChecksum(v10Sql);
        String v11Checksum = computeSqlChecksum(v11Sql);
        String v12Checksum = computeSqlChecksum(v12Sql);

        transactionTemplate.executeWithoutResult(status -> {
            executeSqlScript(v1Sql);
            executeSqlScript(v2Sql);
            executeSqlScript(v3Sql);
            executeSqlScript(v4Sql);
            executeSqlScript(v5Sql);
            executeSqlScript(v6Sql);
            executeSqlScript(v7Sql);
            executeSqlScript(v8Sql);
            executeSqlScript(v9Sql);
            executeSqlScript(v10Sql);
            executeSqlScript(v11Sql);
            executeSqlScript(v12Sql);

            // Record migrations
            String now = Instant.now().toString();
            jdbcTemplate.update(
                    "INSERT INTO schema_migrations (version, description, checksum, applied_at, code_version) VALUES (1, 'M1b initial schema', ?, ?, ?)",
                    v1Checksum, now, CODE_VERSION
            );
            jdbcTemplate.update(
                    "INSERT INTO schema_migrations (version, description, checksum, applied_at, code_version) VALUES (2, 'M1b review corrections', ?, ?, ?)",
                    v2Checksum, now, CODE_VERSION
            );
            jdbcTemplate.update(
                    "INSERT INTO schema_migrations (version, description, checksum, applied_at, code_version) VALUES (3, 'M2 historical datasets and import', ?, ?, ?)",
                    v3Checksum, now, CODE_VERSION
            );
            jdbcTemplate.update(
                    "INSERT INTO schema_migrations (version, description, checksum, applied_at, code_version) VALUES (4, 'M3 backtest engine and baseline', ?, ?, ?)",
                    v4Checksum, now, CODE_VERSION
            );
            jdbcTemplate.update(
                    "INSERT INTO schema_migrations (version, description, checksum, applied_at, code_version) VALUES (5, 'M3 backtest engine hardening', ?, ?, ?)",
                    v5Checksum, now, CODE_VERSION
            );
            jdbcTemplate.update(
                    "INSERT INTO schema_migrations (version, description, checksum, applied_at, code_version) VALUES (6, 'M3 backtest listing integrity', ?, ?, ?)",
                    v6Checksum, now, CODE_VERSION
            );
            jdbcTemplate.update(
                    "INSERT INTO schema_migrations (version, description, checksum, applied_at, code_version) VALUES (7, 'M3 funded observation timing', ?, ?, ?)",
                    v7Checksum, now, CODE_VERSION
            );
            jdbcTemplate.update(
                    "INSERT INTO schema_migrations (version, description, checksum, applied_at, code_version) VALUES (8, 'M4 strategy versions and comparisons', ?, ?, ?)",
                    v8Checksum, now, CODE_VERSION
            );
            jdbcTemplate.update(
                    "INSERT INTO schema_migrations (version, description, checksum, applied_at, code_version) VALUES (9, 'M4 strategy schema and integrity fix', ?, ?, ?)",
                    v9Checksum, now, CODE_VERSION
            );
            jdbcTemplate.update(
                    "INSERT INTO schema_migrations (version, description, checksum, applied_at, code_version) VALUES (10, 'M4 immutable trend version restoration', ?, ?, ?)",
                    v10Checksum, now, CODE_VERSION
            );
            jdbcTemplate.update(
                    "INSERT INTO schema_migrations (version, description, checksum, applied_at, code_version) VALUES (11, 'M4 terminal signal item integrity', ?, ?, ?)",
                    v11Checksum, now, CODE_VERSION
            );
            jdbcTemplate.update(
                    "INSERT INTO schema_migrations (version, description, checksum, applied_at, code_version) VALUES (12, 'M5 paper tracking and proposals', ?, ?, ?)",
                    v12Checksum, now, CODE_VERSION
            );

            // Seed default user legacy demo portfolio and initial cash
            seedFreshDefaults(now);
        });
        validateCurrentSchema();

        log.info("Fresh M4 installation completed successfully.");
    }

    private void applyLegacyMigration() {
        log.info("Recognized legacy database schema. Performing pre-migration backup...");

        // 1. Consistent SQLite backup before any schema modification
        Path backupDir = resolveBackupDirectory();
        backupService.performConsistentBackup(dataSource, datasourceUrl, backupDir);

        // 2. Perform legacy conversion atomically
        String v1Sql = loadResource("db/migration/V1__init_m1b_schema.sql");
        String v2Sql = loadResource("db/migration/V2__m1b_review_fixes.sql");
        String v3Sql = loadResource("db/migration/V3__historical_datasets_and_import.sql");
        String v4Sql = loadResource("db/migration/V4__backtest_engine.sql");
        String v5Sql = loadResource("db/migration/V5__backtest_engine_hardening.sql");
        String v6Sql = loadResource("db/migration/V6__backtest_listing_integrity.sql");
        String v7Sql = loadResource("db/migration/V7__backtest_funded_observation.sql");
        String v8Sql = loadResource("db/migration/V8__strategy_versions_and_comparisons.sql");
        String v9Sql = loadResource("db/migration/V9__fix_strategy_schema_and_integrity.sql");
        String v10Sql = loadResource("db/migration/V10__restore_immutable_trend_version.sql");
        String v11Sql = loadResource("db/migration/V11__protect_terminal_signal_items.sql");
        String v12Sql = loadResource("db/migration/V12__paper_tracking_and_proposals.sql");
        String v1Checksum = computeV1MigrationChecksum(v1Sql);
        String v2Checksum = computeSqlChecksum(v2Sql);
        String v3Checksum = computeSqlChecksum(v3Sql);
        String v4Checksum = computeSqlChecksum(v4Sql);
        String v5Checksum = computeSqlChecksum(v5Sql);
        String v6Checksum = computeSqlChecksum(v6Sql);
        String v7Checksum = computeSqlChecksum(v7Sql);
        String v8Checksum = computeSqlChecksum(v8Sql);
        String v9Checksum = computeSqlChecksum(v9Sql);
        String v10Checksum = computeSqlChecksum(v10Sql);
        String v11Checksum = computeSqlChecksum(v11Sql);
        String v12Checksum = computeSqlChecksum(v12Sql);

        transactionTemplate.executeWithoutResult(status -> {
            log.info("Renaming legacy tables to raw archive tables...");
            for (String table : RECOGNIZED_LEGACY_TABLES) {
                jdbcTemplate.execute("ALTER TABLE " + table + " RENAME TO legacy_" + table + "_raw");
            }

            log.info("Applying V1 M1b versioned schema...");
            executeSqlScript(v1Sql);

            log.info("Executing legacy data conversion and reconciliation...");
            legacyDataMigrator.migrateLegacyData(jdbcTemplate);

            executeSqlScript(v2Sql);
            executeSqlScript(v3Sql);
            executeSqlScript(v4Sql);
            executeSqlScript(v5Sql);
            executeSqlScript(v6Sql);
            executeSqlScript(v7Sql);
            executeSqlScript(v8Sql);
            executeSqlScript(v9Sql);
            executeSqlScript(v10Sql);
            executeSqlScript(v11Sql);
            executeSqlScript(v12Sql);

            // Sync legacy tables for backwards compatibility
            syncLegacyCompatibilityTables();

            // Record migrations
            String now = Instant.now().toString();
            jdbcTemplate.update(
                    "INSERT INTO schema_migrations (version, description, checksum, applied_at, code_version) VALUES (1, 'M1b legacy migration', ?, ?, ?)",
                    v1Checksum, now, CODE_VERSION
            );
            jdbcTemplate.update(
                    "INSERT INTO schema_migrations (version, description, checksum, applied_at, code_version) VALUES (2, 'M1b review corrections', ?, ?, ?)",
                    v2Checksum, now, CODE_VERSION
            );
            jdbcTemplate.update(
                    "INSERT INTO schema_migrations (version, description, checksum, applied_at, code_version) VALUES (3, 'M2 historical datasets and import', ?, ?, ?)",
                    v3Checksum, now, CODE_VERSION
            );
            jdbcTemplate.update(
                    "INSERT INTO schema_migrations (version, description, checksum, applied_at, code_version) VALUES (4, 'M3 backtest engine and baseline', ?, ?, ?)",
                    v4Checksum, now, CODE_VERSION
            );
            jdbcTemplate.update(
                    "INSERT INTO schema_migrations (version, description, checksum, applied_at, code_version) VALUES (5, 'M3 backtest engine hardening', ?, ?, ?)",
                    v5Checksum, now, CODE_VERSION
            );
            jdbcTemplate.update(
                    "INSERT INTO schema_migrations (version, description, checksum, applied_at, code_version) VALUES (6, 'M3 backtest listing integrity', ?, ?, ?)",
                    v6Checksum, now, CODE_VERSION
            );
            jdbcTemplate.update(
                    "INSERT INTO schema_migrations (version, description, checksum, applied_at, code_version) VALUES (7, 'M3 funded observation timing', ?, ?, ?)",
                    v7Checksum, now, CODE_VERSION
            );
            jdbcTemplate.update(
                    "INSERT INTO schema_migrations (version, description, checksum, applied_at, code_version) VALUES (8, 'M4 strategy versions and comparisons', ?, ?, ?)",
                    v8Checksum, now, CODE_VERSION
            );
            jdbcTemplate.update(
                    "INSERT INTO schema_migrations (version, description, checksum, applied_at, code_version) VALUES (9, 'M4 strategy schema and integrity fix', ?, ?, ?)",
                    v9Checksum, now, CODE_VERSION
            );
            jdbcTemplate.update(
                    "INSERT INTO schema_migrations (version, description, checksum, applied_at, code_version) VALUES (10, 'M4 immutable trend version restoration', ?, ?, ?)",
                    v10Checksum, now, CODE_VERSION
            );
            jdbcTemplate.update(
                    "INSERT INTO schema_migrations (version, description, checksum, applied_at, code_version) VALUES (11, 'M4 terminal signal item integrity', ?, ?, ?)",
                    v11Checksum, now, CODE_VERSION
            );
            jdbcTemplate.update(
                    "INSERT INTO schema_migrations (version, description, checksum, applied_at, code_version) VALUES (12, 'M5 paper tracking and proposals', ?, ?, ?)",
                    v12Checksum, now, CODE_VERSION
            );
        });
        validateCurrentSchema();

        log.info("Legacy migration and reconciliation completed successfully.");
    }

    private void verifyAndApplyVersionedMigrations() {
        String v1Sql = loadResource("db/migration/V1__init_m1b_schema.sql");
        String v2Sql = loadResource("db/migration/V2__m1b_review_fixes.sql");
        String v3Sql = loadResource("db/migration/V3__historical_datasets_and_import.sql");
        String v4Sql = loadResource("db/migration/V4__backtest_engine.sql");
        String v5Sql = loadResource("db/migration/V5__backtest_engine_hardening.sql");
        String v6Sql = loadResource("db/migration/V6__backtest_listing_integrity.sql");
        String v7Sql = loadResource("db/migration/V7__backtest_funded_observation.sql");
        String v8Sql = loadResource("db/migration/V8__strategy_versions_and_comparisons.sql");
        String v9Sql = loadResource("db/migration/V9__fix_strategy_schema_and_integrity.sql");
        String v10Sql = loadResource("db/migration/V10__restore_immutable_trend_version.sql");
        String v11Sql = loadResource("db/migration/V11__protect_terminal_signal_items.sql");
        String v12Sql = loadResource("db/migration/V12__paper_tracking_and_proposals.sql");
        String expectedV1Checksum = computeV1MigrationChecksum(v1Sql);
        String candidateV1Checksum = computeCandidateV1MigrationChecksum(v1Sql);
        String expectedV2Checksum = computeSqlChecksum(v2Sql);
        String expectedV3Checksum = computeSqlChecksum(v3Sql);
        String expectedV4Checksum = computeSqlChecksum(v4Sql);
        String expectedV5Checksum = computeSqlChecksum(v5Sql);
        String expectedV6Checksum = computeSqlChecksum(v6Sql);
        String expectedV7Checksum = computeSqlChecksum(v7Sql);
        String expectedV8Checksum = computeSqlChecksum(v8Sql);
        String expectedV9Checksum = computeSqlChecksum(v9Sql);
        String expectedV10Checksum = computeSqlChecksum(v10Sql);
        String expectedV11Checksum = computeSqlChecksum(v11Sql);
        String expectedV12Checksum = computeSqlChecksum(v12Sql);

        List<Map<String, Object>> migrations = jdbcTemplate.queryForList(
                "SELECT version, checksum, description, applied_at FROM schema_migrations ORDER BY version ASC"
        );

        if (migrations.isEmpty()) {
            throw new IllegalStateException("schema_migrations exists but contains no applied versions");
        }
        Set<Integer> versions = new HashSet<>();
        for (Map<String, Object> mig : migrations) {
            int version = ((Number) mig.get("version")).intValue();
            versions.add(version);
            String recordedChecksum = (String) mig.get("checksum");

            if (version == 1) {
                if (!expectedV1Checksum.equals(recordedChecksum) && !candidateV1Checksum.equals(recordedChecksum)) {
                    throw new IllegalStateException(String.format(
                            "Migration version 1 checksum mismatch! Recorded: %s",
                            recordedChecksum
                    ));
                }
            } else if (version == 2) {
                if (!expectedV2Checksum.equals(recordedChecksum)) {
                    throw new IllegalStateException("Migration version 2 checksum mismatch! Recorded: " + recordedChecksum);
                }
            } else if (version == 3) {
                if (!expectedV3Checksum.equals(recordedChecksum)) {
                    throw new IllegalStateException("Migration version 3 checksum mismatch! Recorded: " + recordedChecksum);
                }
            } else if (version == 4) {
                if (!expectedV4Checksum.equals(recordedChecksum)) {
                    throw new IllegalStateException("Migration version 4 checksum mismatch! Recorded: " + recordedChecksum);
                }
            } else if (version == 5) {
                if (!expectedV5Checksum.equals(recordedChecksum)) {
                    throw new IllegalStateException("Migration version 5 checksum mismatch! Recorded: " + recordedChecksum);
                }
            } else if (version == 6) {
                if (!expectedV6Checksum.equals(recordedChecksum)) {
                    throw new IllegalStateException("Migration version 6 checksum mismatch! Recorded: " + recordedChecksum);
                }
            } else if (version == 7) {
                if (!expectedV7Checksum.equals(recordedChecksum)) {
                    throw new IllegalStateException("Migration version 7 checksum mismatch! Recorded: " + recordedChecksum);
                }
            } else if (version == 8) {
                if (!expectedV8Checksum.equals(recordedChecksum)) {
                    throw new IllegalStateException("Migration version 8 checksum mismatch! Recorded: " + recordedChecksum);
                }
            } else if (version == 9) {
                if (!expectedV9Checksum.equals(recordedChecksum)) {
                    throw new IllegalStateException("Migration version 9 checksum mismatch! Recorded: " + recordedChecksum);
                }
            } else if (version == 10) {
                if (!expectedV10Checksum.equals(recordedChecksum)) {
                    throw new IllegalStateException("Migration version 10 checksum mismatch! Recorded: " + recordedChecksum);
                }
            } else if (version == 11) {
                if (!expectedV11Checksum.equals(recordedChecksum)) {
                    throw new IllegalStateException("Migration version 11 checksum mismatch! Recorded: " + recordedChecksum);
                }
            } else if (version == 12) {
                if (!expectedV12Checksum.equals(recordedChecksum)) {
                    throw new IllegalStateException("Migration version 12 checksum mismatch! Recorded: " + recordedChecksum);
                }
            } else {
                throw new IllegalStateException("Unknown migration version found in database: " + version);
            }
        }

        if (!versions.contains(1)) {
            throw new IllegalStateException("Versioned schema has a missing or gapped migration history: " + versions);
        }

        if (!versions.contains(2)) {
            transactionTemplate.executeWithoutResult(status -> {
                executeSqlScript(v2Sql);
                jdbcTemplate.update(
                        "INSERT INTO schema_migrations (version, description, checksum, applied_at, code_version) VALUES (2, 'M1b review corrections', ?, ?, ?)",
                        expectedV2Checksum, Instant.now().toString(), CODE_VERSION
                );
            });
            versions.add(2);
        }

        if (!versions.contains(3)) {
            transactionTemplate.executeWithoutResult(status -> {
                executeSqlScript(v3Sql);
                jdbcTemplate.update(
                        "INSERT INTO schema_migrations (version, description, checksum, applied_at, code_version) VALUES (3, 'M2 historical datasets and import', ?, ?, ?)",
                        expectedV3Checksum, Instant.now().toString(), CODE_VERSION
                );
            });
            versions.add(3);
        }

        if (!versions.contains(4)) {
            transactionTemplate.executeWithoutResult(status -> {
                executeSqlScript(v4Sql);
                jdbcTemplate.update(
                        "INSERT INTO schema_migrations (version, description, checksum, applied_at, code_version) VALUES (4, 'M3 backtest engine and baseline', ?, ?, ?)",
                        expectedV4Checksum, Instant.now().toString(), CODE_VERSION
                );
            });
            versions.add(4);
        }

        if (!versions.contains(5)) {
            transactionTemplate.executeWithoutResult(status -> {
                executeSqlScript(v5Sql);
                jdbcTemplate.update(
                        "INSERT INTO schema_migrations (version, description, checksum, applied_at, code_version) VALUES (5, 'M3 backtest engine hardening', ?, ?, ?)",
                        expectedV5Checksum, Instant.now().toString(), CODE_VERSION
                );
            });
            versions.add(5);
        }

        if (!versions.contains(6)) {
            executeMigrationWithFkToggle(v6Sql, 6, "M3 backtest listing integrity", expectedV6Checksum);
            versions.add(6);
        }

        if (!versions.contains(7)) {
            executeMigrationWithFkToggle(v7Sql, 7, "M3 funded observation timing", expectedV7Checksum);
            versions.add(7);
        }

        if (!versions.contains(8)) {
            executeMigrationWithFkToggle(v8Sql, 8, "M4 strategy versions and comparisons", expectedV8Checksum);
            versions.add(8);
        }

        if (!versions.contains(9)) {
            executeMigrationWithFkToggle(v9Sql, 9, "M4 strategy schema and integrity fix", expectedV9Checksum);
            versions.add(9);
        }

        if (!versions.contains(10)) {
            executeMigrationWithFkToggle(v10Sql, 10, "M4 immutable trend version restoration", expectedV10Checksum);
            versions.add(10);
        }
        if (!versions.contains(11)) {
            transactionTemplate.executeWithoutResult(status -> {
                executeSqlScript(v11Sql);
                jdbcTemplate.update(
                        "INSERT INTO schema_migrations (version, description, checksum, applied_at, code_version) VALUES (11, 'M4 terminal signal item integrity', ?, ?, ?)",
                        expectedV11Checksum, Instant.now().toString(), CODE_VERSION
                );
            });
            versions.add(11);
        }

        if (!versions.contains(12)) {
            transactionTemplate.executeWithoutResult(status -> {
                executeSqlScript(v12Sql);
                jdbcTemplate.update(
                        "INSERT INTO schema_migrations (version, description, checksum, applied_at, code_version) VALUES (12, 'M5 paper tracking and proposals', ?, ?, ?)",
                        expectedV12Checksum, Instant.now().toString(), CODE_VERSION
                );
            });
            versions.add(12);
        }

        validateCurrentSchema();

        log.info("Database is up-to-date with {} verified migration(s). No actions needed.", versions.size());
    }

    private void seedFreshDefaults(String now) {
        // Seed default legacy demo portfolio for single-user local workstation
        jdbcTemplate.update(
                "INSERT INTO portfolios (id, owner_id, name, mode, base_currency, initial_cash, created_at, paper_started_at, rounding_policy_version) " +
                        "VALUES ('portfolio-legacy-demo-default', 'default', 'Legacy Demo Portfolio', 'LEGACY_DEMO', 'USD', '10000.00', ?, NULL, 'v1-half-even')",
                now
        );

        jdbcTemplate.update(
                "INSERT INTO portfolio_state (portfolio_id, cash_amount, revision) VALUES ('portfolio-legacy-demo-default', '10000.00', 1)"
        );

        jdbcTemplate.update(
                "INSERT INTO operations (id, portfolio_id, kind, idempotency_key, payload_hash, result_json, business_at, created_at) " +
                        "VALUES ('op-initial-funding-default', 'portfolio-legacy-demo-default', 'INITIAL_FUNDING', 'seed-initial-funding', 'seed-hash', '{\"seeded\":true}', ?, ?)",
                now, now
        );

        jdbcTemplate.update(
                "INSERT INTO ledger_entries (id, portfolio_id, operation_id, sequence, entry_type, listing_id, signed_quantity_delta, signed_cash_delta, acquisition_cost_delta, currency, business_at, recorded_at, legacy_record_id) " +
                        "VALUES ('ledger-initial-funding-default', 'portfolio-legacy-demo-default', 'op-initial-funding-default', 1, 'INITIAL_FUNDING', NULL, '0', '10000.00', '0.00', 'USD', ?, ?, NULL)",
                now, now
        );

        // Seed default watchlist in legacy_watchlist AND compatibility watchlist
        for (String ticker : DEFAULT_TICKERS) {
            String watchId = UUID.randomUUID().toString();
            jdbcTemplate.update(
                    "INSERT INTO legacy_watchlist (id, portfolio_id, ticker, added_at) VALUES (?, 'portfolio-legacy-demo-default', ?, ?)",
                    watchId, ticker, now
            );
            jdbcTemplate.update(
                    "INSERT OR IGNORE INTO watchlist (id, user_id, ticker, added_at) VALUES (?, 'default', ?, ?)",
                    watchId, ticker, now
            );
        }

        // Seed legacy compatibility tables
        jdbcTemplate.update(
                "INSERT OR IGNORE INTO users_profile (id, cash_balance, created_at) VALUES ('default', 10000.0, ?)",
                now
        );
        jdbcTemplate.update(
                "INSERT OR IGNORE INTO portfolio_snapshots (id, user_id, total_value, recorded_at) VALUES (?, 'default', 10000.0, ?)",
                UUID.randomUUID().toString(), now
        );
    }

    private void syncLegacyCompatibilityTables() {
        // Copy raw legacy rows into active compatibility tables
        jdbcTemplate.execute("INSERT OR IGNORE INTO users_profile SELECT * FROM legacy_users_profile_raw");
        jdbcTemplate.execute("INSERT OR IGNORE INTO watchlist SELECT * FROM legacy_watchlist_raw");
        jdbcTemplate.execute("INSERT OR IGNORE INTO trades SELECT * FROM legacy_trades_raw");
        jdbcTemplate.execute("INSERT OR IGNORE INTO portfolio_snapshots SELECT * FROM legacy_portfolio_snapshots_raw");
        jdbcTemplate.execute("INSERT OR IGNORE INTO chat_messages SELECT * FROM legacy_chat_messages_raw");
    }

    private void executeSqlScript(String script) {
        List<String> statements = SqlScriptParser.parseStatements(script);
        for (String stmt : statements) {
            jdbcTemplate.execute(stmt);
        }
    }

    private void executeMigrationWithFkToggle(String script, int version, String description, String checksum) {
        try (var conn = dataSource.getConnection()) {
            boolean initialAutoCommit = conn.getAutoCommit();
            try {
                conn.setAutoCommit(true);
                try (var stmt = conn.createStatement()) {
                    stmt.execute("PRAGMA foreign_keys = OFF;");
                }
                conn.setAutoCommit(false);
                List<String> statements = SqlScriptParser.parseStatements(script);
                for (String stmtSql : statements) {
                    try (var stmt = conn.createStatement()) {
                        stmt.execute(stmtSql);
                    }
                }
                try (var ps = conn.prepareStatement(
                        "INSERT INTO schema_migrations (version, description, checksum, applied_at, code_version) VALUES (?, ?, ?, ?, ?)")) {
                    ps.setInt(1, version);
                    ps.setString(2, description);
                    ps.setString(3, checksum);
                    ps.setString(4, Instant.now().toString());
                    ps.setString(5, CODE_VERSION);
                    ps.executeUpdate();
                }
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                try (var stmt = conn.createStatement()) {
                    stmt.execute("PRAGMA foreign_keys = ON;");
                } catch (Exception ignored) {
                }
                conn.setAutoCommit(initialAutoCommit);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to apply migration version " + version, e);
        }
    }

    public String computeV1MigrationChecksum(String v1Sql) {
        return combineChecksums(v1Sql, LegacyDataMigrator.getLogicChecksum());
    }

    public String computeCandidateV1MigrationChecksum(String v1Sql) {
        return combineChecksums(v1Sql, LegacyDataMigrator.getCandidateLogicChecksum());
    }

    public String computeSqlChecksum(String sql) {
        return combineChecksums(sql, "");
    }

    private String combineChecksums(String sql, String conversionChecksum) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(sql.getBytes(StandardCharsets.UTF_8));
            md.update(conversionChecksum.getBytes(StandardCharsets.UTF_8));
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private void validateCurrentSchema() {
        Set<String> requiredTables = Set.of(
                "schema_migrations", "portfolios", "portfolio_creation_requests", "portfolio_state",
                "instruments", "listings", "listing_aliases", "positions", "operations",
                "ledger_entries", "executions", "valuations", "legacy_watchlist", "chat_requests",
                "chat_actions", "migration_reconciliations", "users_profile", "watchlist", "trades",
                "portfolio_snapshots", "chat_messages",
                "datasets", "dataset_listings", "dataset_sessions", "historical_bars", "historical_actions", "import_jobs",
                "backtest_runs", "backtest_daily_equity", "backtest_orders", "backtest_events", "backtest_holdings",
                "universes", "universe_listings", "strategy_versions", "experiments", "experiment_exposure_events",
                "backtest_signals", "backtest_signal_items", "backtest_comparisons", "backtest_comparison_items",
                "paper_portfolio_segments", "paper_mode_history", "paper_dataset_adoptions", "paper_proposals",
                "paper_proposal_items", "paper_proposal_observations", "paper_receivables", "paper_processed_corporate_actions",
                "paper_execution_intents", "paper_intent_transitions", "paper_execution_results", "paper_valuations"
        );
        Set<String> missing = new HashSet<>(requiredTables);
        missing.removeAll(getExistingTables());
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Versioned schema is missing required tables: " + missing);
        }

        requireColumns("paper_portfolio_segments", Set.of(
                "id", "portfolio_id", "strategy_id", "strategy_version", "universe_id", "benchmark_listing_id",
                "cost_policy_json", "approval_mode", "status", "initial_equity", "opening_observation_instant", "created_at"
        ));
        requireColumns("paper_proposals", Set.of(
                "id", "portfolio_id", "cycle_id", "strategy_id", "strategy_version", "dataset_id", "dataset_checksum",
                "calendar_id", "calendar_version", "evaluation_session_date", "input_cutoff_instant", "evaluation_instant",
                "scheduled_open_session_date", "scheduled_open_instant", "reason_code", "portfolio_state_version", "status", "created_at"
        ));
        requireColumns("paper_execution_intents", Set.of(
                "id", "portfolio_id", "order_type", "scheduled_session_date", "scheduled_open_instant", "approval_mode", "status", "created_at"
        ));
        requireColumns("paper_execution_results", Set.of(
                "id", "intent_id", "operation_id", "execution_id", "listing_id", "side", "requested_quantity", "executed_quantity",
                "raw_open_price", "fill_price", "commission", "spread_slippage_cost", "cost_basis", "realized_gain",
                "dataset_id", "dataset_checksum", "market_effective_instant", "observed_instant", "booked_instant"
        ));
        requireColumns("paper_receivables", Set.of(
                "id", "portfolio_id", "listing_id", "action_id", "action_type", "record_instant", "ex_date", "payment_date",
                "gross_amount", "withholding_tax", "net_amount", "status", "created_at"
        ));
        requireColumns("paper_valuations", Set.of(
                "id", "portfolio_id", "session_date", "observation_kind", "observation_instant", "cash_balance",
                "positions_market_value", "receivables_value", "is_complete", "adopted_dataset_id", "adopted_dataset_checksum"
        ));

        requireColumns("chat_requests", Set.of(
                "id", "user_id", "idempotency_key", "payload_hash", "user_message", "status",
                "response_json", "lease_owner", "lease_until", "created_at", "updated_at"
        ));
        requireColumns("chat_actions", Set.of(
                "id", "chat_message_id", "operation_id", "action_type", "action_payload", "status",
                "chat_request_id", "action_index", "action_key", "result_json", "error_code", "updated_at"
        ));
        requireColumns("datasets", Set.of(
                "id", "name", "source", "classification", "schema_version", "parser_version",
                "input_checksum", "content_checksum", "manifest_json", "coverage_start", "coverage_end",
                "validation_status", "validation_findings_json", "quality_label", "imported_at", "created_at"
        ));
        requireColumns("dataset_listings", Set.of(
                "dataset_id", "listing_id", "instrument_id", "symbol", "venue", "quote_currency",
                "calendar_id", "inception_date", "termination_date", "isin"
        ));
        requireColumns("dataset_sessions", Set.of(
                "dataset_id", "calendar_id", "session_date", "open_time", "close_time", "session_type"
        ));
        requireColumns("historical_bars", Set.of(
                "dataset_id", "listing_id", "session_date", "open", "high", "low", "close", "volume", "available_at"
        ));
        requireColumns("historical_actions", Set.of(
                "dataset_id", "action_id", "listing_id", "action_type", "effective_date", "available_at",
                "split_ratio_numerator", "split_ratio_denominator", "distribution_amount", "distribution_currency",
                "payment_date", "payment_instant"
        ));
        requireColumns("import_jobs", Set.of(
                "id", "request_key", "input_checksum", "status", "dataset_id", "progress_pct", "message", "error_detail", "created_at", "updated_at"
        ));
        requireColumns("backtest_runs", Set.of(
                "id", "owner_id", "idempotency_key", "canonical_hash", "strategy_id", "strategy_version",
                "dataset_id", "candidate_listing_id", "benchmark_listing_id", "universe_id", "parameters_json", "experiment_id",
                "initial_cash", "currency", "evaluation_cutoff", "requested_start_date", "requested_end_date", "commission_per_fill",
                "spread_bps", "slippage_bps", "status", "progress_pct", "config_json", "created_at", "updated_at"
        ));
        requireColumns("backtest_daily_equity", Set.of(
                "run_id", "series_type", "session_date", "cash", "holdings_value", "receivables",
                "total_equity", "drawdown", "peak_equity", "units", "cost_basis", "raw_close",
                "point_kind", "observation_time"
        ));
        requireColumns("backtest_orders", Set.of(
                "id", "run_id", "series_type", "order_type", "listing_id", "session_date",
                "requested_quantity", "executed_quantity", "raw_open", "fill_price", "commission",
                "spread_cost", "slippage_cost", "status", "created_at"
        ));
        requireColumns("backtest_events", Set.of(
                "id", "run_id", "series_type", "event_seq", "event_type", "event_date", "event_time",
                "description", "cash_delta", "units_delta", "basis_delta", "receivable_delta", "created_at"
        ));
        requireColumns("backtest_holdings", Set.of(
                "run_id", "series_type", "listing_id", "units", "total_cost_basis", "average_cost",
                "current_price", "market_value", "unrealized_gain", "updated_at"
        ));
        requireColumns("universes", Set.of(
                "id", "owner_id", "name", "version", "description", "dataset_id", "calendar_id",
                "currency", "provenance", "created_at"
        ));
        requireColumns("universe_listings", Set.of(
                "universe_id", "listing_id", "ordinal"
        ));
        requireColumns("strategy_versions", Set.of(
                "strategy_id", "strategy_version", "name", "description", "parameters_schema_json",
                "calculation_policy_version", "decision_schedule", "created_at"
        ));
        requireColumns("experiments", Set.of(
                "id", "owner_id", "name", "version", "strategy_id", "strategy_version", "dataset_id",
                "universe_id", "candidate_listing_id", "benchmark_listing_id", "development_start_date",
                "development_end_date", "holdout_start_date", "holdout_end_date", "declared_holdout_status",
                "parameters_json", "created_at"
        ));
        requireColumns("experiment_exposure_events", Set.of(
                "id", "experiment_id", "run_id", "access_type", "exposed_by", "exposed_at", "details_json"
        ));
        requireColumns("backtest_signals", Set.of(
                "id", "run_id", "strategy_id", "strategy_version", "universe_id", "evaluation_date",
                "evaluation_time", "decision_instant", "scheduled_execution_date", "target_allocation_summary",
                "status", "reason_code", "details_json", "created_at"
        ));
        requireColumns("backtest_signal_items", Set.of(
                "id", "signal_id", "listing_id", "score", "index_value", "sma_value", "rank",
                "eligible", "selected", "target_weight", "reason_code"
        ));
        requireColumns("backtest_comparisons", Set.of(
                "id", "owner_id", "idempotency_key", "name", "benchmark_listing_id", "dataset_id",
                "effective_start_date", "effective_end_date", "initial_cash", "currency", "status",
                "mismatch_reasons_json", "summary_json", "created_at", "updated_at"
        ));
        requireColumns("backtest_comparison_items", Set.of(
                "comparison_id", "run_id", "role", "ordinal"
        ));

        Integer immutableTriggers = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'trigger' AND name IN ('prevent_ledger_update', 'prevent_ledger_delete')",
                Integer.class
        );
        if (immutableTriggers == null || immutableTriggers != 2) {
            throw new IllegalStateException("Versioned schema is missing ledger immutability triggers");
        }

        Integer historicalImmutabilityTriggers = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'trigger' AND name IN (" +
                        "'prevent_dataset_update', 'prevent_dataset_delete', " +
                        "'prevent_dataset_listings_update', 'prevent_dataset_listings_delete', " +
                        "'prevent_dataset_sessions_update', 'prevent_dataset_sessions_delete', " +
                        "'prevent_historical_bars_update', 'prevent_historical_bars_delete', " +
                        "'prevent_historical_actions_update', 'prevent_historical_actions_delete')",
                Integer.class
        );
        if (historicalImmutabilityTriggers == null || historicalImmutabilityTriggers != 10) {
            throw new IllegalStateException("Versioned schema is missing historical dataset immutability triggers: found " + historicalImmutabilityTriggers);
        }

        List<Map<String, Object>> ledgerForeignKeys = jdbcTemplate.queryForList("PRAGMA foreign_key_list(ledger_entries)");
        long operationReferences = ledgerForeignKeys.stream()
                .filter(row -> "operations".equals(row.get("table")))
                .count();
        if (operationReferences != 2) {
            throw new IllegalStateException("Ledger portfolio/operation composite foreign key is missing");
        }

        List<Map<String, Object>> backtestForeignKeys = jdbcTemplate.queryForList("PRAGMA foreign_key_list(backtest_runs)");
        long datasetListingReferences = backtestForeignKeys.stream()
                .filter(row -> "dataset_listings".equals(row.get("table")))
                .count();
        if (datasetListingReferences < 1) {
            throw new IllegalStateException("backtest_runs benchmark listing composite foreign key is missing: found " + datasetListingReferences);
        }

        requireUniqueColumns("portfolio_creation_requests", List.of("owner_id", "idempotency_key"));
        requireUniqueColumns("operations", List.of("portfolio_id", "kind", "idempotency_key"));
        requireUniqueColumns("chat_requests", List.of("user_id", "idempotency_key"));
        requireUniqueColumns("import_jobs", List.of("request_key"));
        requireTrigger("validate_portfolio_state_insert");
        requireTrigger("validate_position_insert");
        requireTrigger("validate_execution_insert");
        requireTrigger("validate_historical_bars_insert");
        requireTrigger("prevent_completed_daily_equity_insert");
        requireTrigger("prevent_completed_orders_insert");
        requireTrigger("prevent_completed_events_insert");
        requireTrigger("prevent_completed_holdings_insert");
        requireTrigger("validate_backtest_run_listings_insert");
        requireTrigger("validate_backtest_run_listings_update");
        requireTrigger("prevent_universes_update");
        requireTrigger("prevent_universes_delete");
        requireTrigger("prevent_strategy_versions_update");
        requireTrigger("prevent_strategy_versions_delete");
        requireTrigger("prevent_experiments_update");
        requireTrigger("prevent_experiments_delete");
        requireTrigger("prevent_exposure_events_update");
        requireTrigger("prevent_exposure_events_delete");
        requireTrigger("prevent_completed_signals_insert");
        requireTrigger("prevent_completed_signal_items_insert");
        requireTrigger("prevent_completed_signal_items_update");
        requireTrigger("prevent_completed_signal_items_delete");
        requireTrigger("prevent_comparisons_delete");
    }

    private void requireColumns(String table, Set<String> required) {
        Set<String> actual = new HashSet<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList("PRAGMA table_info(" + table + ")")) {
            actual.add((String) row.get("name"));
        }
        Set<String> missing = new HashSet<>(required);
        missing.removeAll(actual);
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Versioned schema table " + table + " is missing columns: " + missing);
        }
    }

    private void requireUniqueColumns(String table, List<String> expectedColumns) {
        for (Map<String, Object> index : jdbcTemplate.queryForList("PRAGMA index_list(" + table + ")")) {
            if (((Number) index.get("unique")).intValue() != 1) {
                continue;
            }
            String indexName = (String) index.get("name");
            List<String> columns = jdbcTemplate.queryForList("PRAGMA index_info('" + indexName + "')").stream()
                    .map(row -> (String) row.get("name"))
                    .toList();
            if (columns.equals(expectedColumns)) {
                return;
            }
        }
        throw new IllegalStateException("Versioned schema is missing unique columns on " + table + ": " + expectedColumns);
    }

    private void requireTrigger(String trigger) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'trigger' AND name = ?", Integer.class, trigger
        );
        if (count == null || count != 1) {
            throw new IllegalStateException("Versioned schema is missing required trigger: " + trigger);
        }
    }

    private String loadResource(String path) {
        try {
            ClassPathResource resource = new ClassPathResource(path);
            try (InputStream is = resource.getInputStream()) {
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load SQL resource: " + path, e);
        }
    }

    private Path resolveBackupDirectory() {
        if (datasourceUrl != null && datasourceUrl.startsWith("jdbc:sqlite:")) {
            String dbPath = datasourceUrl.substring("jdbc:sqlite:".length());
            if (!":memory:".equals(dbPath)) {
                File dbFile = new File(dbPath);
                File parent = dbFile.getParentFile();
                if (parent != null) {
                    return parent.toPath().resolve("backups");
                }
            }
        }
        return Path.of("db/backups");
    }
}
