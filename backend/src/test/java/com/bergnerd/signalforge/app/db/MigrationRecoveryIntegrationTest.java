package com.bergnerd.signalforge.app.db;

import com.bergnerd.signalforge.app.db.migration.LegacyDataMigrator;
import com.bergnerd.signalforge.app.db.migration.MigrationBackupService;
import com.bergnerd.signalforge.app.db.migration.MigrationRunner;
import com.bergnerd.signalforge.app.db.migration.SqlScriptParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.sqlite.SQLiteDataSource;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class MigrationRecoveryIntegrationTest {

    @TempDir
    Path tempDir;

    private Path dbPath;
    private SQLiteDataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private TransactionTemplate transactionTemplate;
    private MigrationBackupService backupService;
    private LegacyDataMigrator legacyDataMigrator;
    private MigrationRunner migrationRunner;

    @BeforeEach
    void setUp() throws Exception {
        dbPath = tempDir.resolve("test-signalforge.db");
        dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + dbPath.toAbsolutePath());

        jdbcTemplate = new JdbcTemplate(dataSource);
        DataSourceTransactionManager txManager = new DataSourceTransactionManager(dataSource);
        transactionTemplate = new TransactionTemplate(txManager);

        backupService = new MigrationBackupService();
        legacyDataMigrator = new LegacyDataMigrator();

        migrationRunner = new MigrationRunner(
                jdbcTemplate,
                dataSource,
                transactionTemplate,
                backupService,
                legacyDataMigrator
        );
        ReflectionTestUtils.setField(migrationRunner, "datasourceUrl", "jdbc:sqlite:" + dbPath.toAbsolutePath());
    }

    @AfterEach
    void tearDown() {
        // SQLite will close file handles when garbage collected or pool released
    }

    @Test
    void freshInstall_createsValidSchemaAndDefaults() {
        // Ensure starting completely empty
        assertFalse(Files.exists(dbPath) && dbPath.toFile().length() > 0);

        migrationRunner.runMigration();

        // 1. Verify schema_migrations record
        Integer migCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM schema_migrations WHERE version = 1", Integer.class);
        assertEquals(1, migCount);

        String checksum = jdbcTemplate.queryForObject(
                "SELECT checksum FROM schema_migrations WHERE version = 1", String.class);
        assertNotNull(checksum);
        assertFalse(checksum.isBlank());

        // 2. Verify default seeded portfolio
        Map<String, Object> portfolio = jdbcTemplate.queryForMap(
                "SELECT id, owner_id, name, mode, base_currency, initial_cash FROM portfolios WHERE id = 'portfolio-legacy-demo-default'");
        assertEquals("default", portfolio.get("owner_id"));
        assertEquals("LEGACY_DEMO", portfolio.get("mode"));
        assertEquals("USD", portfolio.get("base_currency"));
        assertEquals("10000.00", portfolio.get("initial_cash"));

        // 3. Verify portfolio state cash
        String stateCash = jdbcTemplate.queryForObject(
                "SELECT cash_amount FROM portfolio_state WHERE portfolio_id = 'portfolio-legacy-demo-default'",
                String.class
        );
        assertEquals("10000.00", stateCash);

        // 4. Verify initial funding ledger entry
        Integer ledgerCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ledger_entries WHERE portfolio_id = 'portfolio-legacy-demo-default'",
                Integer.class
        );
        assertEquals(1, ledgerCount);

        // 5. Verify default 10 tickers seeded in legacy_watchlist
        Integer watchlistCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM legacy_watchlist WHERE portfolio_id = 'portfolio-legacy-demo-default'",
                Integer.class
        );
        assertEquals(10, watchlistCount);

        // 6. Verify ledger trigger protects against modification
        assertThrows(Exception.class, () -> jdbcTemplate.execute(
                "UPDATE ledger_entries SET signed_cash_delta = '99999.00' WHERE portfolio_id = 'portfolio-legacy-demo-default'"
        ));
    }

    private Path resolveRepoFile(String relativePath) {
        Path p = Path.of(relativePath);
        if (Files.exists(p)) {
            return p;
        }
        Path parent = Path.of("..").resolve(relativePath);
        if (Files.exists(parent)) {
            return parent;
        }
        return p;
    }

    @Test
    void multiOwnerLegacyUpgrade_convertsAndReconcilesAccurately() throws IOException {
        Path legacyFixture = resolveRepoFile("test/fixtures/legacy_multi_owner.db");
        assertTrue(Files.exists(legacyFixture), "Fixture legacy_multi_owner.db must exist at: " + legacyFixture);

        // Copy legacy fixture to temp test path
        Files.copy(legacyFixture, dbPath, StandardCopyOption.REPLACE_EXISTING);

        migrationRunner.runMigration();

        // 1. Schema migration record recorded
        Integer migCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM schema_migrations WHERE version = 1", Integer.class);
        assertEquals(1, migCount);

        // 2. Pre-migration backup file generated in backups dir
        Path backupDir = tempDir.resolve("backups");
        assertTrue(Files.exists(backupDir) && Files.isDirectory(backupDir), "Backup directory must be created");
        List<Path> backups;
        try (var stream = Files.list(backupDir)) {
            backups = stream.filter(p -> p.getFileName().toString().endsWith(".db")).toList();
        }
        assertFalse(backups.isEmpty(), "Pre-migration backup .db file must exist");

        // 3. Verify reconciliations
        Integer reconCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM migration_reconciliations", Integer.class);
        assertTrue(reconCount >= 4, "Must have cash and position reconciliations across owners");

        // 4. Verify Alice's migrated cash and positions
        String aliceCash = jdbcTemplate.queryForObject(
                "SELECT cash_amount FROM portfolio_state WHERE portfolio_id = 'portfolio-legacy-demo-alice'",
                String.class
        );
        assertEquals("4200.00", aliceCash);

        Integer alicePositionsCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM positions WHERE portfolio_id = 'portfolio-legacy-demo-alice'",
                Integer.class
        );
        assertEquals(1, alicePositionsCount);

        // 5. Verify Bob's migrated cash and intentionally EMPTY watchlist
        String bobCash = jdbcTemplate.queryForObject(
                "SELECT cash_amount FROM portfolio_state WHERE portfolio_id = 'portfolio-legacy-demo-bob'",
                String.class
        );
        assertEquals("10000.00", bobCash);

        Integer bobLegacyWatchlistCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM legacy_watchlist WHERE portfolio_id = 'portfolio-legacy-demo-bob'",
                Integer.class
        );
        assertEquals(0, bobLegacyWatchlistCount, "Bob's intentionally empty watchlist must remain empty in legacy_watchlist");

        Integer bobCompatWatchlistCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM watchlist WHERE user_id = 'bob'",
                Integer.class
        );
        assertEquals(0, bobCompatWatchlistCount, "Bob's intentionally empty watchlist must remain empty in compatibility watchlist");

        // 6. Verify raw legacy archive tables exist and remain intact
        Integer rawUsersCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM legacy_users_profile_raw", Integer.class);
        assertEquals(3, rawUsersCount); // default, alice, bob

        List<Map<String, Object>> positionReconciliations = jdbcTemplate.queryForList(
                "SELECT raw_value, converted_value, difference FROM migration_reconciliations "
                        + "WHERE reconciliation_type = 'POSITION'"
        );
        assertFalse(positionReconciliations.isEmpty());
        assertTrue(positionReconciliations.stream().allMatch(row -> {
            String raw = (String) row.get("raw_value");
            String converted = (String) row.get("converted_value");
            BigDecimal rawBasis = new BigDecimal(raw.substring(raw.indexOf("basis=") + 6));
            BigDecimal convertedBasis = new BigDecimal(converted.substring(converted.indexOf("totalBasis=") + 11));
            BigDecimal recordedDifference = new BigDecimal((String) row.get("difference"));
            return convertedBasis.subtract(rawBasis).compareTo(recordedDifference) == 0;
        }));
    }

    @Test
    void reconciliationPreservesSubCentHalfEvenDifferences() throws IOException {
        Files.copy(resolveRepoFile("test/fixtures/legacy_multi_owner.db"), dbPath, StandardCopyOption.REPLACE_EXISTING);
        jdbcTemplate.update("UPDATE users_profile SET cash_balance = 1.005 WHERE id = 'default'");
        jdbcTemplate.update("UPDATE users_profile SET cash_balance = 1.015 WHERE id = 'alice'");

        migrationRunner.runMigration();

        assertEquals("-0.005", jdbcTemplate.queryForObject(
                "SELECT difference FROM migration_reconciliations WHERE owner_id = 'default' AND reconciliation_type = 'CASH_BALANCE'",
                String.class
        ));
        assertEquals("0.005", jdbcTemplate.queryForObject(
                "SELECT difference FROM migration_reconciliations WHERE owner_id = 'alice' AND reconciliation_type = 'CASH_BALANCE'",
                String.class
        ));
    }

    @Test
    void malformedSchema_isRejectedWithDiagnosticException() {
        // Create an unrecognized partial table
        jdbcTemplate.execute("CREATE TABLE corrupt_random_table (id TEXT PRIMARY KEY, val TEXT)");
        jdbcTemplate.execute("INSERT INTO corrupt_random_table VALUES ('1', 'bad')");

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> migrationRunner.runMigration());
        assertTrue(ex.getMessage().contains("unrecognized, partial, or corrupted schema"),
                "Expected diagnostic message for unrecognized schema: " + ex.getMessage());
    }

    @Test
    void changedChecksum_isRejected() {
        // Run clean fresh install first
        migrationRunner.runMigration();

        // Alter recorded checksum in schema_migrations
        jdbcTemplate.update(
                "UPDATE schema_migrations SET checksum = 'tampered-or-altered-checksum' WHERE version = 1");

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> migrationRunner.runMigration());
        assertTrue(ex.getMessage().contains("checksum mismatch"),
                "Expected checksum mismatch diagnostic exception: " + ex.getMessage());
    }

    @Test
    void repeatStartup_isIdempotent() {
        migrationRunner.runMigration();

        String initialCash = jdbcTemplate.queryForObject(
                "SELECT cash_amount FROM portfolio_state WHERE portfolio_id = 'portfolio-legacy-demo-default'",
                String.class
        );
        Integer initialLedgerRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ledger_entries WHERE portfolio_id = 'portfolio-legacy-demo-default'",
                Integer.class
        );
        Integer initialWatchlistRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM legacy_watchlist WHERE portfolio_id = 'portfolio-legacy-demo-default'",
                Integer.class
        );

        // Run second time (repeat startup)
        assertDoesNotThrow(() -> migrationRunner.runMigration());

        assertEquals(initialCash, jdbcTemplate.queryForObject(
                "SELECT cash_amount FROM portfolio_state WHERE portfolio_id = 'portfolio-legacy-demo-default'",
                String.class
        ));
        assertEquals(initialLedgerRows, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ledger_entries WHERE portfolio_id = 'portfolio-legacy-demo-default'",
                Integer.class
        ));
        assertEquals(initialWatchlistRows, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM legacy_watchlist WHERE portfolio_id = 'portfolio-legacy-demo-default'",
                Integer.class
        ));
    }

    @Test
    void restoreRehearsal_verifiesBackupAndCheckpointArtifact() throws Exception {
        // 1. Verify pre-migration checkpoint binary exists and SHA-256 matches manifest
        Path checkpointJar = resolveRepoFile(".checkpoint/m1a-checkpoint.jar");
        assertTrue(Files.exists(checkpointJar), "Checkpoint jar must exist at: " + checkpointJar);

        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        byte[] jarBytes = Files.readAllBytes(checkpointJar);
        byte[] hash = sha256.digest(jarBytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        String computedJarHash = sb.toString();

        Path manifest = resolveRepoFile(".checkpoint/checkpoint-manifest.json");
        assertTrue(Files.exists(manifest), "Manifest must exist at: " + manifest);
        String manifestContent = Files.readString(manifest);
        assertTrue(manifestContent.contains(computedJarHash), "Checkpoint manifest must contain jar SHA-256: " + computedJarHash);

        // 2. Perform a backup on legacy db and verify the backup can be read as a pure legacy database
        Path legacyFixture = resolveRepoFile("test/fixtures/legacy_multi_owner.db");
        Files.copy(legacyFixture, dbPath, StandardCopyOption.REPLACE_EXISTING);

        Path backupDir = tempDir.resolve("restore-test-backups");
        backupService.performConsistentBackup(dataSource, "jdbc:sqlite:" + dbPath.toAbsolutePath(), backupDir);

        List<Path> backups;
        try (var s = Files.list(backupDir)) {
            backups = s.filter(p -> p.getFileName().toString().endsWith(".db")).toList();
        }
        assertFalse(backups.isEmpty());
        Path backupFile = backups.get(0);

        SQLiteDataSource backupDs = new SQLiteDataSource();
        backupDs.setUrl("jdbc:sqlite:" + backupFile.toAbsolutePath());
        JdbcTemplate backupJdbc = new JdbcTemplate(backupDs);

        // Verify that backup retains the pure legacy schema without M1b modifications
        Integer usersInBackup = backupJdbc.queryForObject("SELECT COUNT(*) FROM users_profile", Integer.class);
        assertEquals(3, usersInBackup);

        Integer tablesCount = backupJdbc.queryForObject(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='schema_migrations'", Integer.class);
        assertEquals(0, tablesCount, "Pre-migration backup must not have M1b schema_migrations");

        Path restored = tempDir.resolve("restored-for-old-binary.db");
        Files.copy(backupFile, restored);
        String cashBefore = backupJdbc.queryForObject(
                "SELECT CAST(cash_balance AS TEXT) FROM users_profile WHERE id = 'default'", String.class
        );
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        Path oldLog = tempDir.resolve("old-binary.log");
        Process oldApplication = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-jar", checkpointJar.toString(),
                "--server.port=" + port,
                "--spring.datasource.url=jdbc:sqlite:" + restored,
                "--spring.config.import=",
                "--spring.profiles.active=",
                "--signalforge.llm.mock=true",
                "--signalforge.massive.api-key="
        ).redirectErrorStream(true).redirectOutput(oldLog.toFile()).start();
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
            boolean ready = false;
            for (int attempt = 0; attempt < 40 && !ready; attempt++) {
                try {
                    HttpResponse<String> response = client.send(
                            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/health"))
                                    .timeout(Duration.ofSeconds(1)).build(),
                            HttpResponse.BodyHandlers.ofString()
                    );
                    ready = response.statusCode() == 200;
                } catch (Exception ignored) {
                    Thread.sleep(250);
                }
            }
            assertTrue(ready, "Preserved old binary did not become ready:\n" + Files.readString(oldLog));

            SQLiteDataSource restoredDataSource = new SQLiteDataSource();
            restoredDataSource.setUrl("jdbc:sqlite:" + restored);
            JdbcTemplate restoredJdbc = new JdbcTemplate(restoredDataSource);
            assertEquals(3, restoredJdbc.queryForObject("SELECT COUNT(*) FROM users_profile", Integer.class));
            assertEquals(cashBefore, restoredJdbc.queryForObject(
                    "SELECT CAST(cash_balance AS TEXT) FROM users_profile WHERE id = 'default'", String.class
            ));
            assertEquals(0, restoredJdbc.queryForObject(
                    "SELECT COUNT(*) FROM watchlist WHERE user_id = 'bob'", Integer.class
            ));
        } finally {
            oldApplication.destroy();
            if (!oldApplication.waitFor(5, TimeUnit.SECONDS)) {
                oldApplication.destroyForcibly();
            }
        }
    }

    @Test
    void emptyMigrationHistory_isRejected() {
        jdbcTemplate.execute("""
                CREATE TABLE schema_migrations (
                    version INTEGER PRIMARY KEY,
                    description TEXT NOT NULL,
                    checksum TEXT NOT NULL,
                    applied_at TEXT NOT NULL,
                    code_version TEXT NOT NULL
                )
                """);

        IllegalStateException failure = assertThrows(IllegalStateException.class, migrationRunner::runMigration);
        assertTrue(failure.getMessage().contains("contains no applied versions"));
    }

    @Test
    void forgedHistoryWithMissingSchema_isRejected() {
        jdbcTemplate.execute("""
                CREATE TABLE schema_migrations (
                    version INTEGER PRIMARY KEY,
                    description TEXT NOT NULL,
                    checksum TEXT NOT NULL,
                    applied_at TEXT NOT NULL,
                    code_version TEXT NOT NULL
                )
                """);
        String v1 = resource("db/migration/V1__init_m1b_schema.sql");
        String v2 = resource("db/migration/V2__m1b_review_fixes.sql");
        jdbcTemplate.update(
                "INSERT INTO schema_migrations VALUES (1, 'forged', ?, '2026-09-12T00:00:00Z', 'candidate')",
                migrationRunner.computeV1MigrationChecksum(v1)
        );
        jdbcTemplate.update(
                "INSERT INTO schema_migrations VALUES (2, 'forged', ?, '2026-09-12T00:00:00Z', 'candidate')",
                migrationRunner.computeSqlChecksum(v2)
        );

        IllegalStateException failure = assertThrows(IllegalStateException.class, migrationRunner::runMigration);
        assertTrue(failure.getMessage().contains("missing required tables"));
    }

    @Test
    void recognizedCandidateV1_isUpgradedWithoutRewritingHistory() {
        String v1 = resource("db/migration/V1__init_m1b_schema.sql");
        for (String statement : SqlScriptParser.parseStatements(v1)) {
            jdbcTemplate.execute(statement);
        }
        String candidateChecksum = migrationRunner.computeCandidateV1MigrationChecksum(v1);
        jdbcTemplate.update(
                "INSERT INTO schema_migrations VALUES (1, 'M1b initial schema', ?, '2026-09-12T00:00:00Z', '1.0.0-M1b')",
                candidateChecksum
        );
        jdbcTemplate.update(
                "INSERT INTO instruments (id, type, name, isin, provenance) "
                        + "VALUES ('inst-research-generated', 'ETF', 'Generated', 'IE00B4L5Y983', 'RESEARCH_PROVIDER')"
        );
        jdbcTemplate.update(
                "INSERT INTO listings (id, instrument_id, venue, symbol, quote_currency, identity_status) "
                        + "VALUES ('listing-research-generated', 'inst-research-generated', 'XETRA', 'FAKE', 'EUR', 'RESOLVED')"
        );
        jdbcTemplate.update(
                "INSERT INTO instruments (id, type, name, isin, provenance) "
                        + "VALUES ('inst-trusted-real', 'ETF', 'Trusted', 'IE00B4L5Y983', 'TRUSTED_IMPORT')"
        );
        jdbcTemplate.update(
                "INSERT INTO listings (id, instrument_id, venue, symbol, quote_currency, identity_status) "
                        + "VALUES ('listing-trusted-real', 'inst-trusted-real', 'LSE', 'REAL', 'EUR', 'RESOLVED')"
        );

        migrationRunner.runMigration();

        assertEquals(candidateChecksum, jdbcTemplate.queryForObject(
                "SELECT checksum FROM schema_migrations WHERE version = 1", String.class
        ));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM schema_migrations WHERE version = 2", Integer.class
        ));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM schema_migrations WHERE version = 3", Integer.class
        ));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM schema_migrations WHERE version = 4", Integer.class
        ));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM schema_migrations WHERE version = 5", Integer.class
        ));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM schema_migrations WHERE version = 6", Integer.class
        ));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'chat_requests'", Integer.class
        ));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'datasets'", Integer.class
        ));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'backtest_runs'", Integer.class
        ));
        assertEquals("UNVERIFIED_CANDIDATE", jdbcTemplate.queryForObject(
                "SELECT identity_status FROM listings WHERE id = 'listing-research-generated'", String.class
        ));
        assertEquals("M1B_CANDIDATE_UNVERIFIED", jdbcTemplate.queryForObject(
                "SELECT provenance FROM instruments WHERE id = 'inst-research-generated'", String.class
        ));
        assertEquals("RESOLVED", jdbcTemplate.queryForObject(
                "SELECT identity_status FROM listings WHERE id = 'listing-trusted-real'", String.class
        ));
    }

    @Test
    void conversionFailure_rollsBackAndRetrySucceeds() throws Exception {
        Path fixture = resolveRepoFile("test/fixtures/legacy_multi_owner.db");
        Files.copy(fixture, dbPath, StandardCopyOption.REPLACE_EXISTING);
        LegacyDataMigrator failingMigrator = new LegacyDataMigrator() {
            @Override
            protected void migrationCheckpoint(String checkpoint) {
                throw new IllegalStateException("injected conversion failure at " + checkpoint);
            }
        };
        MigrationRunner failingRunner = new MigrationRunner(
                jdbcTemplate, dataSource, transactionTemplate, backupService, failingMigrator
        );
        ReflectionTestUtils.setField(failingRunner, "datasourceUrl", "jdbc:sqlite:" + dbPath.toAbsolutePath());

        assertThrows(IllegalStateException.class, failingRunner::runMigration);
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'users_profile'", Integer.class
        ));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'schema_migrations'", Integer.class
        ));

        assertDoesNotThrow(migrationRunner::runMigration);
        assertEquals(10, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM schema_migrations", Integer.class));
    }

    @Test
    void conversionPolicyDigest_changesWithArtifactContent() {
        byte[] migrator = "compiled-migrator".getBytes(StandardCharsets.UTF_8);
        byte[] accounting = "compiled-accounting".getBytes(StandardCharsets.UTF_8);
        byte[] policy = resource("db/migration/V1__legacy_conversion_policy.txt").getBytes(StandardCharsets.UTF_8);
        assertNotEquals(
                LegacyDataMigrator.computeLogicChecksum(migrator, accounting, policy),
                LegacyDataMigrator.computeLogicChecksum(
                        "changed-compiled-migrator".getBytes(StandardCharsets.UTF_8), accounting, policy
                )
        );
    }

    @Test
    void v6Upgrade_preservesPopulatedBacktestsAndEnforcesForeignKeys() {
        String v1 = resource("db/migration/V1__init_m1b_schema.sql");
        String v2 = resource("db/migration/V2__m1b_review_fixes.sql");
        String v3 = resource("db/migration/V3__historical_datasets_and_import.sql");
        String v4 = resource("db/migration/V4__backtest_engine.sql");
        String v5 = resource("db/migration/V5__backtest_engine_hardening.sql");

        for (String stmt : SqlScriptParser.parseStatements(v1)) { jdbcTemplate.execute(stmt); }
        for (String stmt : SqlScriptParser.parseStatements(v2)) { jdbcTemplate.execute(stmt); }
        for (String stmt : SqlScriptParser.parseStatements(v3)) { jdbcTemplate.execute(stmt); }
        for (String stmt : SqlScriptParser.parseStatements(v4)) { jdbcTemplate.execute(stmt); }
        for (String stmt : SqlScriptParser.parseStatements(v5)) { jdbcTemplate.execute(stmt); }

        String now = "2026-09-12T00:00:00Z";
        jdbcTemplate.update("INSERT INTO schema_migrations VALUES (1, 'v1', ?, ?, '2.0.0-M3')", migrationRunner.computeV1MigrationChecksum(v1), now);
        jdbcTemplate.update("INSERT INTO schema_migrations VALUES (2, 'v2', ?, ?, '2.0.0-M3')", migrationRunner.computeSqlChecksum(v2), now);
        jdbcTemplate.update("INSERT INTO schema_migrations VALUES (3, 'v3', ?, ?, '2.0.0-M3')", migrationRunner.computeSqlChecksum(v3), now);
        jdbcTemplate.update("INSERT INTO schema_migrations VALUES (4, 'v4', ?, ?, '2.0.0-M3')", migrationRunner.computeSqlChecksum(v4), now);
        jdbcTemplate.update("INSERT INTO schema_migrations VALUES (5, 'v5', ?, ?, '2.0.0-M3')", migrationRunner.computeSqlChecksum(v5), now);

        // Seed populated dataset and listings
        jdbcTemplate.update("INSERT INTO datasets (id, name, source, classification, schema_version, parser_version, input_checksum, content_checksum, manifest_json, coverage_start, coverage_end, validation_status, validation_findings_json, quality_label, imported_at, created_at) " +
                "VALUES ('ds-v6-test', 'Dataset V6', 'TEST', 'HISTORICAL', 'v1', 'p1', 'in-cs', 'out-cs', '{}', '2026-01-01', '2026-01-10', 'VALID', '[]', 'VERIFIED', ?, ?)", now, now);
        jdbcTemplate.update("INSERT INTO dataset_listings (dataset_id, listing_id, instrument_id, symbol, quote_currency, calendar_id) " +
                "VALUES ('ds-v6-test', 'cand-1', 'inst-1', 'CAND', 'EUR', 'XETR')");
        jdbcTemplate.update("INSERT INTO dataset_listings (dataset_id, listing_id, instrument_id, symbol, quote_currency, calendar_id) " +
                "VALUES ('ds-v6-test', 'bm-1', 'inst-2', 'BM', 'EUR', 'XETR')");

        // Seed populated backtest_runs in RUNNING state, insert child records, then transition to COMPLETED
        jdbcTemplate.update("INSERT INTO backtest_runs (id, owner_id, idempotency_key, canonical_hash, strategy_id, strategy_version, dataset_id, candidate_listing_id, benchmark_listing_id, initial_cash, currency, evaluation_cutoff, requested_start_date, requested_end_date, commission_per_fill, spread_bps, slippage_bps, status, progress_pct, config_json, created_at, updated_at) " +
                "VALUES ('run-v6-pop', 'default', 'idemp-v6', 'hash-v6', 'buy_and_hold', '1.0', 'ds-v6-test', 'cand-1', 'bm-1', '10000.00', 'EUR', '2026-01-01T20:00:00Z', '2026-01-02', '2026-01-05', '1.00', '10', '5', 'RUNNING', 50, '{}', ?, ?)", now, now);
        jdbcTemplate.update("INSERT INTO backtest_daily_equity (run_id, series_type, session_date, cash, holdings_value, receivables, total_equity, drawdown, peak_equity, units, cost_basis, raw_close) " +
                "VALUES ('run-v6-pop', 'CANDIDATE', '2026-01-02', '500.00', '9500.00', '0.00', '10000.00', '0.00', '10000.00', '95.00', '9500.00', '100.00')");
        jdbcTemplate.update("INSERT INTO backtest_orders (id, run_id, series_type, order_type, listing_id, session_date, requested_quantity, executed_quantity, raw_open, fill_price, commission, spread_cost, slippage_cost, status, created_at) " +
                "VALUES ('ord-v6-1', 'run-v6-pop', 'CANDIDATE', 'INITIAL_BUY', 'cand-1', '2026-01-02', '95.00', '95.00', '100.00', '100.00', '1.00', '0.10', '0.05', 'FILLED', ?)", now);
        jdbcTemplate.update("INSERT INTO backtest_events (id, run_id, series_type, event_seq, event_type, event_date, event_time, description, cash_delta, units_delta, basis_delta, receivable_delta, created_at) " +
                "VALUES ('evt-v6-1', 'run-v6-pop', 'CANDIDATE', 1, 'FUNDING', '2026-01-02', '2026-01-02T07:45:00Z', 'Initial Funding', '10000.00', '0', '0', '0', ?)", now);
        jdbcTemplate.update("INSERT INTO backtest_holdings (run_id, series_type, listing_id, units, total_cost_basis, average_cost, current_price, market_value, unrealized_gain, updated_at) " +
                "VALUES ('run-v6-pop', 'CANDIDATE', 'cand-1', '95.00', '9500.00', '100.00', '100.00', '9500.00', '0.00', ?)", now);
        jdbcTemplate.update("UPDATE backtest_runs SET status = 'COMPLETED', progress_pct = 100 WHERE id = 'run-v6-pop'");

        // Run upgrade migration
        migrationRunner.runMigration();

        // Verify both upgrades recorded and the V4-V6 row preserved through the V7 rebuild and V8 update.
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM schema_migrations WHERE version = 6", Integer.class));
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM schema_migrations WHERE version = 7", Integer.class));
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM schema_migrations WHERE version = 8", Integer.class));
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM schema_migrations WHERE version = 9", Integer.class));

        // 2. Verify all parent and child rows preserved intact
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM backtest_runs WHERE id = 'run-v6-pop'", Integer.class));
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM backtest_daily_equity WHERE run_id = 'run-v6-pop'", Integer.class));
        assertEquals("INITIAL_FUNDED", jdbcTemplate.queryForObject(
                "SELECT point_kind FROM backtest_daily_equity WHERE run_id = 'run-v6-pop'", String.class));
        assertEquals("10000.00", jdbcTemplate.queryForObject(
                "SELECT total_equity FROM backtest_daily_equity WHERE run_id = 'run-v6-pop'", String.class));
        assertThrows(Exception.class, () -> jdbcTemplate.update(
                "UPDATE backtest_daily_equity SET total_equity = '0.00' WHERE run_id = 'run-v6-pop'"));
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM backtest_orders WHERE run_id = 'run-v6-pop'", Integer.class));
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM backtest_events WHERE run_id = 'run-v6-pop'", Integer.class));
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM backtest_holdings WHERE run_id = 'run-v6-pop'", Integer.class));

        // 3. Verify foreign keys on backtest_runs include composite dataset_listings
        List<Map<String, Object>> fks = jdbcTemplate.queryForList("PRAGMA foreign_key_list(backtest_runs)");
        long distinctListingFks = fks.stream()
                .filter(row -> "dataset_listings".equals(row.get("table")))
                .map(row -> row.get("id"))
                .distinct()
                .count();
        assertEquals(2, distinctListingFks, "Must have 2 composite foreign keys referencing dataset_listings");
        long totalListingFkColumns = fks.stream()
                .filter(row -> "dataset_listings".equals(row.get("table")))
                .count();
        assertEquals(4, totalListingFkColumns, "Each composite foreign key must map 2 columns");

        // 4. Verify inserting an orphaned listing into backtest_runs is rejected
        assertThrows(Exception.class, () -> jdbcTemplate.update(
                "INSERT INTO backtest_runs (id, owner_id, idempotency_key, canonical_hash, strategy_id, strategy_version, dataset_id, candidate_listing_id, benchmark_listing_id, initial_cash, currency, evaluation_cutoff, requested_start_date, requested_end_date, commission_per_fill, spread_bps, slippage_bps, status, progress_pct, config_json, created_at, updated_at) " +
                        "VALUES ('run-orphaned', 'default', 'idemp-orphan', 'hash-orphan', 'buy_and_hold', '1.0', 'ds-v6-test', 'cand-nonexistent', 'bm-1', '10000.00', 'EUR', '2026-01-01T20:00:00Z', '2026-01-02', '2026-01-05', '1.00', '10', '5', 'QUEUED', 0, '{}', '2026-09-12T00:00:00Z', '2026-09-12T00:00:00Z')"
        ));
    }

    @Test
    void v6Upgrade_refusesInconsistentExistingBacktests() {
        String v1 = resource("db/migration/V1__init_m1b_schema.sql");
        String v2 = resource("db/migration/V2__m1b_review_fixes.sql");
        String v3 = resource("db/migration/V3__historical_datasets_and_import.sql");
        String v4 = resource("db/migration/V4__backtest_engine.sql");
        String v5 = resource("db/migration/V5__backtest_engine_hardening.sql");

        for (String stmt : SqlScriptParser.parseStatements(v1)) { jdbcTemplate.execute(stmt); }
        for (String stmt : SqlScriptParser.parseStatements(v2)) { jdbcTemplate.execute(stmt); }
        for (String stmt : SqlScriptParser.parseStatements(v3)) { jdbcTemplate.execute(stmt); }
        for (String stmt : SqlScriptParser.parseStatements(v4)) { jdbcTemplate.execute(stmt); }

        String now = "2026-09-12T00:00:00Z";
        jdbcTemplate.update("INSERT INTO schema_migrations VALUES (1, 'v1', ?, ?, '2.0.0-M3')", migrationRunner.computeV1MigrationChecksum(v1), now);
        jdbcTemplate.update("INSERT INTO schema_migrations VALUES (2, 'v2', ?, ?, '2.0.0-M3')", migrationRunner.computeSqlChecksum(v2), now);
        jdbcTemplate.update("INSERT INTO schema_migrations VALUES (3, 'v3', ?, ?, '2.0.0-M3')", migrationRunner.computeSqlChecksum(v3), now);
        jdbcTemplate.update("INSERT INTO schema_migrations VALUES (4, 'v4', ?, ?, '2.0.0-M3')", migrationRunner.computeSqlChecksum(v4), now);

        // Seed dataset but do NOT include the candidate listing in dataset_listings
        jdbcTemplate.update("INSERT INTO datasets (id, name, source, classification, schema_version, parser_version, input_checksum, content_checksum, manifest_json, coverage_start, coverage_end, validation_status, validation_findings_json, quality_label, imported_at, created_at) " +
                "VALUES ('ds-inconsistent', 'Dataset Bad', 'TEST', 'HISTORICAL', 'v1', 'p1', 'in-cs', 'out-cs', '{}', '2026-01-01', '2026-01-10', 'VALID', '[]', 'VERIFIED', ?, ?)", now, now);
        jdbcTemplate.update("INSERT INTO dataset_listings (dataset_id, listing_id, instrument_id, symbol, quote_currency, calendar_id) " +
                "VALUES ('ds-inconsistent', 'bm-only', 'inst-2', 'BM', 'EUR', 'XETR')");

        // Insert an inconsistent run while on V4 schema (before V5 trigger was applied)
        jdbcTemplate.update("INSERT INTO backtest_runs (id, owner_id, idempotency_key, canonical_hash, strategy_id, strategy_version, dataset_id, candidate_listing_id, benchmark_listing_id, initial_cash, currency, evaluation_cutoff, requested_start_date, requested_end_date, commission_per_fill, spread_bps, slippage_bps, status, progress_pct, config_json, created_at, updated_at) " +
                "VALUES ('run-bad-listings', 'default', 'idemp-bad', 'hash-bad', 'buy_and_hold', '1.0', 'ds-inconsistent', 'cand-missing', 'bm-only', '10000.00', 'EUR', '2026-01-01T20:00:00Z', '2026-01-02', '2026-01-05', '1.00', '10', '5', 'COMPLETED', 100, '{}', ?, ?)", now, now);

        // Now apply V5
        for (String stmt : SqlScriptParser.parseStatements(v5)) { jdbcTemplate.execute(stmt); }
        jdbcTemplate.update("INSERT INTO schema_migrations VALUES (5, 'v5', ?, ?, '2.0.0-M3')", migrationRunner.computeSqlChecksum(v5), now);

        // Attempting to upgrade to V6 must fail during pre-migration scan!
        Exception ex = assertThrows(Exception.class, migrationRunner::runMigration);
        assertTrue(ex.getMessage().contains("invalid candidate or benchmark listing") ||
                (ex.getCause() != null && ex.getCause().getMessage().contains("invalid candidate or benchmark listing")));
    }

    private String resource(String name) {
        try {
            return new String(new ClassPathResource(name).getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
