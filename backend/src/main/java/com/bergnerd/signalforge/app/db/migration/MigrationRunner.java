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

    public static final String CODE_VERSION = "1.0.0-M1b-fixes";
    public static final int CURRENT_SCHEMA_VERSION = 2;

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
        log.info("Applying fresh M1b installation schema (Version 1)...");
        String v1Sql = loadResource("db/migration/V1__init_m1b_schema.sql");
        String v2Sql = loadResource("db/migration/V2__m1b_review_fixes.sql");
        String v1Checksum = computeV1MigrationChecksum(v1Sql);
        String v2Checksum = computeSqlChecksum(v2Sql);

        transactionTemplate.executeWithoutResult(status -> {
            executeSqlScript(v1Sql);
            executeSqlScript(v2Sql);

            // Record migration 1
            String now = Instant.now().toString();
            jdbcTemplate.update(
                    "INSERT INTO schema_migrations (version, description, checksum, applied_at, code_version) VALUES (1, 'M1b initial schema', ?, ?, ?)",
                    v1Checksum, now, CODE_VERSION
            );
            jdbcTemplate.update(
                    "INSERT INTO schema_migrations (version, description, checksum, applied_at, code_version) VALUES (2, 'M1b review corrections', ?, ?, ?)",
                    v2Checksum, now, CODE_VERSION
            );

            // Seed default user legacy demo portfolio and initial cash
            seedFreshDefaults(now);
        });
        validateCurrentSchema();

        log.info("Fresh M1b installation completed successfully.");
    }

    private void applyLegacyMigration() {
        log.info("Recognized legacy database schema. Performing pre-migration backup...");

        // 1. Consistent SQLite backup before any schema modification
        Path backupDir = resolveBackupDirectory();
        backupService.performConsistentBackup(dataSource, datasourceUrl, backupDir);

        // 2. Perform legacy conversion atomically
        String v1Sql = loadResource("db/migration/V1__init_m1b_schema.sql");
        String v2Sql = loadResource("db/migration/V2__m1b_review_fixes.sql");
        String v1Checksum = computeV1MigrationChecksum(v1Sql);
        String v2Checksum = computeSqlChecksum(v2Sql);

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

            // Sync legacy tables for backwards compatibility
            syncLegacyCompatibilityTables();

            // Record migration
            String now = Instant.now().toString();
            jdbcTemplate.update(
                    "INSERT INTO schema_migrations (version, description, checksum, applied_at, code_version) VALUES (1, 'M1b legacy migration', ?, ?, ?)",
                    v1Checksum, now, CODE_VERSION
            );
            jdbcTemplate.update(
                    "INSERT INTO schema_migrations (version, description, checksum, applied_at, code_version) VALUES (2, 'M1b review corrections', ?, ?, ?)",
                    v2Checksum, now, CODE_VERSION
            );
        });
        validateCurrentSchema();

        log.info("Legacy migration and reconciliation completed successfully.");
    }

    private void verifyAndApplyVersionedMigrations() {
        String v1Sql = loadResource("db/migration/V1__init_m1b_schema.sql");
        String v2Sql = loadResource("db/migration/V2__m1b_review_fixes.sql");
        String expectedV1Checksum = computeV1MigrationChecksum(v1Sql);
        String candidateV1Checksum = computeCandidateV1MigrationChecksum(v1Sql);
        String expectedV2Checksum = computeSqlChecksum(v2Sql);

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
        }

        validateCurrentSchema();

        log.info("Database is up-to-date with {} verified migration(s). No actions needed.", migrations.size());
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
                "portfolio_snapshots", "chat_messages"
        );
        Set<String> missing = new HashSet<>(requiredTables);
        missing.removeAll(getExistingTables());
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Versioned schema is missing required tables: " + missing);
        }

        requireColumns("chat_requests", Set.of(
                "id", "user_id", "idempotency_key", "payload_hash", "user_message", "status",
                "response_json", "lease_owner", "lease_until", "created_at", "updated_at"
        ));
        requireColumns("chat_actions", Set.of(
                "id", "chat_message_id", "operation_id", "action_type", "action_payload", "status",
                "chat_request_id", "action_index", "action_key", "result_json", "error_code", "updated_at"
        ));

        Integer immutableTriggers = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'trigger' AND name IN ('prevent_ledger_update', 'prevent_ledger_delete')",
                Integer.class
        );
        if (immutableTriggers == null || immutableTriggers != 2) {
            throw new IllegalStateException("Versioned schema is missing ledger immutability triggers");
        }

        List<Map<String, Object>> ledgerForeignKeys = jdbcTemplate.queryForList("PRAGMA foreign_key_list(ledger_entries)");
        long operationReferences = ledgerForeignKeys.stream()
                .filter(row -> "operations".equals(row.get("table")))
                .count();
        if (operationReferences != 2) {
            throw new IllegalStateException("Ledger portfolio/operation composite foreign key is missing");
        }
        requireUniqueColumns("portfolio_creation_requests", List.of("owner_id", "idempotency_key"));
        requireUniqueColumns("operations", List.of("portfolio_id", "kind", "idempotency_key"));
        requireUniqueColumns("chat_requests", List.of("user_id", "idempotency_key"));
        requireTrigger("validate_portfolio_state_insert");
        requireTrigger("validate_position_insert");
        requireTrigger("validate_execution_insert");
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
