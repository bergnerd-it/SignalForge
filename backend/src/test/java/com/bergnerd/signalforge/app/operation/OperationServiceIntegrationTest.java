package com.bergnerd.signalforge.app.operation;

import com.bergnerd.signalforge.app.accounting.AccountingCore;
import com.bergnerd.signalforge.app.chat.ChatService;
import com.bergnerd.signalforge.app.db.migration.LegacyDataMigrator;
import com.bergnerd.signalforge.app.db.migration.MigrationBackupService;
import com.bergnerd.signalforge.app.db.migration.MigrationRunner;
import com.bergnerd.signalforge.app.market.MarketDataSource;
import com.bergnerd.signalforge.app.market.MarketExceptions;
import com.bergnerd.signalforge.app.market.MassiveMarketClient;
import com.bergnerd.signalforge.app.market.PriceTick;
import com.bergnerd.signalforge.app.portfolio.PortfolioService;
import com.bergnerd.signalforge.app.portfolio.TradeExceptions;
import com.bergnerd.signalforge.app.portfolio.TradeRequest;
import com.bergnerd.signalforge.app.watchlist.WatchlistService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.sqlite.SQLiteDataSource;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class OperationServiceIntegrationTest {

    @TempDir
    Path tempDir;

    private Path dbPath;
    private HikariDataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private TransactionTemplate transactionTemplate;
    private OperationService operationService;
    private PortfolioService portfolioService;
    private MarketDataSource marketDataSource;

    @BeforeEach
    void setUp() {
        dbPath = tempDir.resolve("test-operations.db");
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + dbPath.toAbsolutePath());
        config.setMaximumPoolSize(6);
        config.setConnectionInitSql("PRAGMA foreign_keys=ON");
        config.addDataSourceProperty("busy_timeout", 5000);
        dataSource = new HikariDataSource(config);

        jdbcTemplate = new JdbcTemplate(dataSource);
        DataSourceTransactionManager txManager = new DataSourceTransactionManager(dataSource);
        transactionTemplate = new TransactionTemplate(txManager);

        MigrationBackupService backupService = new MigrationBackupService();
        LegacyDataMigrator legacyDataMigrator = new LegacyDataMigrator();
        MigrationRunner migrationRunner = new MigrationRunner(
                jdbcTemplate, dataSource, transactionTemplate, backupService, legacyDataMigrator
        );
        ReflectionTestUtils.setField(migrationRunner, "datasourceUrl", "jdbc:sqlite:" + dbPath.toAbsolutePath());
        migrationRunner.runMigration();
        seedResolvedListing("AAPL");
        seedResolvedListing("MSFT");

        operationService = new OperationService(jdbcTemplate, transactionTemplate);
        marketDataSource = new MarketDataSource() {
            private final Map<String, PriceTick> cache = new HashMap<>();

            @Override
            public PriceTick getPrice(String ticker) {
                return new PriceTick(ticker, 150.0, 149.0, 1.0, 0.67, Instant.now().toString(), "up", "MOCK", Instant.now().toString(), true);
            }

            @Override
            public Map<String, PriceTick> getAllPrices() {
                return cache;
            }

            @Override
            public void registerTicker(String ticker) {
            }
        };
        portfolioService = new PortfolioService(jdbcTemplate, marketDataSource, operationService);
    }

    @AfterEach
    void tearDown() {
        dataSource.close();
    }

    private void seedResolvedListing(String ticker) {
        jdbcTemplate.update(
                "INSERT INTO instruments (id, type, name, isin, provenance) VALUES (?, 'EQUITY', ?, NULL, 'TEST_FIXTURE')",
                "inst-test-" + ticker, ticker
        );
        jdbcTemplate.update(
                "INSERT INTO listings (id, instrument_id, venue, symbol, quote_currency, identity_status) " +
                        "VALUES (?, ?, 'TEST', ?, 'EUR', 'RESOLVED')",
                "listing-test-" + ticker, "inst-test-" + ticker, ticker
        );
    }

    @Test
    void atomicity_rollsBackAllStateOnInjectedFailure() {
        var creation = operationService.createPortfolio(
                "owner-atomicity", "Atomic Fund", "PAPER", "EUR", new BigDecimal("1000.00"), "create-atomicity"
        );
        String portfolioId = creation.portfolioId();

        // Ensure clean initial state: 1000.00 cash, 0 positions, 1 ledger entry (initial funding)
        assertEquals("1000.00", jdbcTemplate.queryForObject(
                "SELECT cash_amount FROM portfolio_state WHERE portfolio_id = ?", String.class, portfolioId));
        int initialLedgerCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ledger_entries WHERE portfolio_id = ?", Integer.class, portfolioId);
        assertEquals(1, initialLedgerCount);

        // Inject failure inside transaction: attempt an invalid SQL operation midway through a financial flow
        assertThrows(RuntimeException.class, () -> transactionTemplate.executeWithoutResult(status -> {
            // Deduct cash
            jdbcTemplate.update("UPDATE portfolio_state SET cash_amount = '500.00' WHERE portfolio_id = ?", portfolioId);

            // Record fake ledger entry
            jdbcTemplate.update(
                    "INSERT INTO ledger_entries (id, portfolio_id, operation_id, sequence, entry_type, listing_id, signed_quantity_delta, signed_cash_delta, acquisition_cost_delta, currency, business_at, recorded_at) " +
                            "VALUES ('bad-ledger-id', ?, 'bad-op', 2, 'TRADE', 'non-existent-listing', '5', '-500.00', '500.00', 'EUR', '2026-09-12T00:00:00Z', '2026-09-12T00:00:00Z')",
                    portfolioId
            );

            // Trigger exception to force rollback
            throw new RuntimeException("Simulated mid-transaction crash!");
        }));

        // Verify full rollback: cash reverted to 1000.00, no second ledger entry
        assertEquals("1000.00", jdbcTemplate.queryForObject(
                "SELECT cash_amount FROM portfolio_state WHERE portfolio_id = ?", String.class, portfolioId));
        assertEquals(initialLedgerCount, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ledger_entries WHERE portfolio_id = ?", Integer.class, portfolioId));
    }

    @Test
    void operationFailureAtEveryPersistenceStage_rollsBackAndSameKeyRetries() {
        for (String failurePoint : List.of("cash", "position", "execution", "ledger", "operation-result")) {
            String keySuffix = failurePoint.replace("-", "");
            var creation = operationService.createPortfolio(
                    "owner-" + keySuffix, "Failure " + failurePoint, "PAPER", "EUR",
                    new BigDecimal("1000.00"), "create-" + keySuffix
            );
            operationService.setFailureInjector(point -> {
                if (failurePoint.equals(point)) {
                    throw new IllegalStateException("injected " + point);
                }
            });

            assertThrows(IllegalStateException.class, () -> operationService.executeTrade(
                    creation.portfolioId(), "AAPL", "buy", BigDecimal.ONE,
                    new BigDecimal("100.00"), BigDecimal.ZERO, "trade-" + keySuffix, "TEST_FILL"
            ));
            assertEquals("1000.00", jdbcTemplate.queryForObject(
                    "SELECT cash_amount FROM portfolio_state WHERE portfolio_id = ?",
                    String.class, creation.portfolioId()
            ));
            assertEquals(0, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM operations WHERE portfolio_id = ? AND kind = 'TRADE'",
                    Integer.class, creation.portfolioId()
            ));
            assertEquals(0, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM executions WHERE portfolio_id = ?",
                    Integer.class, creation.portfolioId()
            ));
            assertEquals(0, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM positions WHERE portfolio_id = ?",
                    Integer.class, creation.portfolioId()
            ));

            operationService.setFailureInjector(null);
            assertDoesNotThrow(() -> operationService.executeTrade(
                    creation.portfolioId(), "AAPL", "buy", BigDecimal.ONE,
                    new BigDecimal("100.00"), BigDecimal.ZERO, "trade-" + keySuffix, "TEST_FILL"
            ));
        }
    }

    @Test
    void idempotency_sequentialRetryDoesNotDuplicateNorChangeOnQuoteMovement() {
        var creation = operationService.createPortfolio(
                "owner-idempotency", "Idempotency Fund", "PAPER", "EUR", new BigDecimal("10000.00"), "create-idemp"
        );
        String portfolioId = creation.portfolioId();

        // 1. Initial trade: Buy 10 AAPL at 150.00, commission 1.00
        var firstResult = operationService.executeTrade(
                portfolioId, "AAPL", "buy", new BigDecimal("10"),
                new BigDecimal("150.00"), new BigDecimal("1.00"),
                "trade-key-101", "TEST_FILL"
        );

        assertFalse(firstResult.isRetry());
        assertEquals("8499.00", firstResult.remainingCash()); // 10000 - 1500 - 1
        assertEquals(new BigDecimal("10"), new BigDecimal(firstResult.remainingUnits()));
        assertEquals("150.00", firstResult.fillPrice());

        // 2. Sequential retry with SAME key, but market quote moves to 200.00
        var retryResult = operationService.executeTrade(
                portfolioId, "AAPL", "buy", new BigDecimal("10"),
                new BigDecimal("200.00"), new BigDecimal("1.00"),
                "trade-key-101", "TEST_FILL"
        );

        assertTrue(retryResult.isRetry());
        assertEquals(firstResult.operationId(), retryResult.operationId());
        assertEquals(firstResult.executionId(), retryResult.executionId());
        assertEquals("8499.00", retryResult.remainingCash(), "Cash must not be deducted a second time!");
        assertEquals("150.00", retryResult.fillPrice(), "Fill price must remain the original executed fill, not new quote!");

        // Verify database state: exactly 1 trade operation, 2 ledger entries (funding + 1 trade)
        Integer tradeOps = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM operations WHERE portfolio_id = ? AND kind = 'TRADE'", Integer.class, portfolioId);
        assertEquals(1, tradeOps);
    }

    @Test
    void idempotency_payloadConflictRejection() {
        var creation = operationService.createPortfolio(
                "owner-conflict", "Conflict Fund", "PAPER", "EUR", new BigDecimal("10000.00"), "create-conflict"
        );
        String portfolioId = creation.portfolioId();

        // Execute initial trade: BUY 10 AAPL
        operationService.executeTrade(
                portfolioId, "AAPL", "buy", new BigDecimal("10"),
                new BigDecimal("150.00"), new BigDecimal("1.00"),
                "trade-key-conflict", "TEST_FILL"
        );

        // Attempt same key with DIFFERENT payload: BUY 20 AAPL
        assertThrows(IdempotencyExceptions.IdempotencyConflictException.class, () -> {
            operationService.executeTrade(
                    portfolioId, "AAPL", "buy", new BigDecimal("20"),
                    new BigDecimal("150.00"), new BigDecimal("1.00"),
                    "trade-key-conflict", "TEST_FILL"
            );
        });
    }

    @Test
    void idempotency_independentScopesForIndependentAccounts() {
        var portA = operationService.createPortfolio(
                "owner-multi", "Port A", "PAPER", "EUR", new BigDecimal("5000.00"), "create-port-a"
        );
        var portB = operationService.createPortfolio(
                "owner-multi", "Port B", "PAPER", "EUR", new BigDecimal("5000.00"), "create-port-b"
        );

        // Both portfolios use the SAME idempotency key "first-allocation"
        var resA = operationService.executeTrade(
                portA.portfolioId(), "AAPL", "buy", new BigDecimal("5"),
                new BigDecimal("100.00"), BigDecimal.ZERO, "first-allocation", "TEST_FILL"
        );
        var resB = operationService.executeTrade(
                portB.portfolioId(), "AAPL", "buy", new BigDecimal("5"),
                new BigDecimal("100.00"), BigDecimal.ZERO, "first-allocation", "TEST_FILL"
        );

        assertNotNull(resA);
        assertNotNull(resB);
        assertNotEquals(resA.operationId(), resB.operationId());
        assertEquals("4500.00", resA.remainingCash());
        assertEquals("4500.00", resB.remainingCash());
    }

    @Test
    void creation_sameOwnerKeyCreatesExactlyOneFundedAccountAcrossRetriesAndConcurrency() throws Exception {
        String ownerId = "owner-concurrent-creation";
        String key = "create-unique-fund-key";

        int threads = 6;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<Future<OperationService.PortfolioCreationResult>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                barrier.await();
                OperationService independentService = new OperationService(jdbcTemplate, transactionTemplate);
                return independentService.createPortfolio(
                        ownerId, "Concurrent Fund", "PAPER", "EUR", new BigDecimal("5000.00"), key
                );
            }));
        }

        List<OperationService.PortfolioCreationResult> results = new ArrayList<>();
        for (var f : futures) {
            results.add(f.get(5, TimeUnit.SECONDS));
        }
        executor.shutdown();

        // All threads must return the exact same portfolioId
        String expectedPortfolioId = results.get(0).portfolioId();
        for (var r : results) {
            assertEquals(expectedPortfolioId, r.portfolioId());
        }

        // Verify only 1 portfolio was created in database
        Integer portCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM portfolios WHERE owner_id = ?", Integer.class, ownerId);
        assertEquals(1, portCount);

        // Verify only 1 funding operation occurred
        Integer fundingCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM operations WHERE portfolio_id = ? AND kind = 'INITIAL_FUNDING'",
                Integer.class, expectedPortfolioId);
        assertEquals(1, fundingCount);

        // Verify cash is exactly 5000.00 (not multiplied)
        String cash = jdbcTemplate.queryForObject(
                "SELECT cash_amount FROM portfolio_state WHERE portfolio_id = ?", String.class, expectedPortfolioId);
        assertEquals("5000.00", cash);
    }

    @Test
    void competingOperations_cannotOverspendOrOversell() throws Exception {
        // Fund account with 500.00 EUR
        var creation = operationService.createPortfolio(
                "owner-competing", "Limited Fund", "PAPER", "EUR", new BigDecimal("500.00"), "create-competing"
        );
        String portfolioId = creation.portfolioId();

        // Two competing threads try to buy 3 shares at 100 EUR each (300 EUR each, total 600 > 500)
        int threads = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);

        AtomicInteger successes = new AtomicInteger(0);
        AtomicInteger failures = new AtomicInteger(0);

        List<Callable<Void>> tasks = List.of(
                () -> {
                    barrier.await();
                    try {
                        operationService.executeTrade(
                                portfolioId, "AAPL", "buy", new BigDecimal("3"),
                                new BigDecimal("100.00"), BigDecimal.ZERO, "compete-buy-1", "TEST_FILL"
                        );
                        successes.incrementAndGet();
                    } catch (AccountingCore.InsufficientFundsException e) {
                        failures.incrementAndGet();
                    }
                    return null;
                },
                () -> {
                    barrier.await();
                    try {
                        operationService.executeTrade(
                                portfolioId, "AAPL", "buy", new BigDecimal("3"),
                                new BigDecimal("100.00"), BigDecimal.ZERO, "compete-buy-2", "TEST_FILL"
                        );
                        successes.incrementAndGet();
                    } catch (AccountingCore.InsufficientFundsException e) {
                        failures.incrementAndGet();
                    }
                    return null;
                }
        );

        for (var f : executor.invokeAll(tasks)) {
            f.get(5, TimeUnit.SECONDS);
        }
        executor.shutdown();

        assertEquals(1, successes.get(), "Exactly one trade must succeed");
        assertEquals(1, failures.get(), "Competing trade must fail due to insufficient cash");

        String remainingCash = jdbcTemplate.queryForObject(
                "SELECT cash_amount FROM portfolio_state WHERE portfolio_id = ?", String.class, portfolioId);
        assertEquals("200.00", remainingCash, "Cash balance must be 200.00 EUR, never negative!");
    }

    @Test
    void scopeIsolation_twoPaperAndLegacyDemo_heldIndependently() {
        var paperA = operationService.createPortfolio(
                "owner-scoped", "Paper Alpha", "PAPER", "EUR", new BigDecimal("10000.00"), "create-scoped-a"
        );
        var paperB = operationService.createPortfolio(
                "owner-scoped", "Paper Beta", "PAPER", "EUR", new BigDecimal("10000.00"), "create-scoped-b"
        );

        // Buy MSFT in Paper Alpha
        operationService.executeTrade(
                paperA.portfolioId(), "MSFT", "buy", new BigDecimal("5"),
                new BigDecimal("200.00"), BigDecimal.ZERO, "trade-scoped-a", "TEST_FILL"
        );

        // Buy MSFT in Paper Beta
        operationService.executeTrade(
                paperB.portfolioId(), "MSFT", "buy", new BigDecimal("12"),
                new BigDecimal("200.00"), BigDecimal.ZERO, "trade-scoped-b", "TEST_FILL"
        );

        // Buy MSFT in legacy demo default
        operationService.executeTrade(
                "portfolio-legacy-demo-default", "MSFT", "buy", new BigDecimal("2"),
                new BigDecimal("200.00"), BigDecimal.ZERO, "trade-scoped-legacy", "TEST_FILL"
        );

        // Verify independent holdings
        var viewA = operationService.getPortfolioView(paperA.portfolioId());
        var viewB = operationService.getPortfolioView(paperB.portfolioId());
        var viewLegacy = operationService.getPortfolioView("portfolio-legacy-demo-default");

        assertEquals(new BigDecimal("5"), new BigDecimal(viewA.positions().get(0).quantity()));
        assertEquals(new BigDecimal("12"), new BigDecimal(viewB.positions().get(0).quantity()));
        assertEquals(new BigDecimal("2"), new BigDecimal(viewLegacy.positions().get(0).quantity()));

        // Legacy PortfolioService rejects targeting research / PAPER portfolios
        assertThrows(TradeExceptions.InvalidTradeException.class, () -> {
            portfolioService.executeTrade(
                    "default",
                    new TradeRequest("MSFT", 1.0, "buy", "bad-key", "PAPER"),
                    "bad-key"
            );
        });
    }

    @Test
    void quotes_missingOrUnavailableQuoteRejection() {
        MassiveMarketClient marketClient = new MassiveMarketClient("");

        // Querying non-existent tick without provider produces QuoteUnavailableException
        assertThrows(MarketExceptions.QuoteUnavailableException.class, () -> {
            marketClient.getPrice("UNKNOWN_TICKER");
        });

        // PriceTick with isExecutable = false
        PriceTick unavailableTick = new PriceTick(
                "NONEXIST", 0.0, 0.0, 0.0, 0.0,
                Instant.now().toString(), "none", "NONE", Instant.now().toString(), false
        );
        assertFalse(unavailableTick.isExecutable());
    }

    @Test
    void chatRecovery_clearingChatRetainsExecutionEvidence() {
        // Create an operation and chat action
        String opId = "op-chat-test-1";
        String chatMsgId = "msg-chat-test-1";
        String now = "2026-09-12T00:00:00Z";

        jdbcTemplate.update(
                "INSERT INTO chat_messages (id, user_id, role, content, actions, created_at) VALUES (?, 'default', 'user', 'Buy 1 AAPL', NULL, ?)",
                chatMsgId, now
        );
        jdbcTemplate.update(
                "INSERT INTO operations (id, portfolio_id, kind, idempotency_key, payload_hash, result_json, business_at, created_at) " +
                        "VALUES (?, 'portfolio-legacy-demo-default', 'TRADE', 'chat-test', 'hash', '{}', ?, ?)",
                opId, now, now
        );

        jdbcTemplate.update(
                "INSERT INTO chat_actions (id, chat_message_id, operation_id, action_type, action_payload, status, created_at) VALUES (?, ?, ?, 'TRADE', 'BUY 1 AAPL', 'SUCCESS', ?)",
                "action-chat-test-1", chatMsgId, opId, now
        );

        // Execute clear chat history
        WatchlistService watchlistService = new WatchlistService(jdbcTemplate, marketDataSource);
        ChatService chatService = new ChatService(jdbcTemplate, portfolioService, watchlistService, null, transactionTemplate);
        chatService.clearChatHistory("default");

        // Verify chat_messages is cleared
        Integer msgCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM chat_messages WHERE user_id = 'default'", Integer.class);
        assertEquals(0, msgCount, "chat_messages must be cleared");

        // Verify chat_actions is RETAINED
        Integer actionCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM chat_actions WHERE id = 'action-chat-test-1'", Integer.class);
        assertEquals(1, actionCount, "Durable chat action evidence must be preserved!");
    }

    @Test
    void multiConnectionConcurrency_independentDbConnectionsEnforceLocking() throws Exception {
        var portfolio = operationService.createPortfolio(
                "independent-writers", "Independent writers", "PAPER", "EUR",
                new BigDecimal("500.00"), "create-independent-writers"
        );
        OperationService firstService = new OperationService(jdbcTemplate, transactionTemplate);
        OperationService secondService = new OperationService(jdbcTemplate, transactionTemplate);
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Callable<Boolean>> calls = List.of(
                () -> executeCompetingBuy(firstService, portfolio.portfolioId(), "writer-one", barrier),
                () -> executeCompetingBuy(secondService, portfolio.portfolioId(), "writer-two", barrier)
        );

        List<Future<Boolean>> futures = executor.invokeAll(calls);
        executor.shutdown();
        long successes = 0;
        for (Future<Boolean> future : futures) {
            if (future.get(10, TimeUnit.SECONDS)) {
                successes++;
            }
        }
        assertEquals(1, successes);
        assertEquals("200.00", jdbcTemplate.queryForObject(
                "SELECT cash_amount FROM portfolio_state WHERE portfolio_id = ?",
                String.class, portfolio.portfolioId()
        ));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM operations WHERE portfolio_id = ? AND kind = 'TRADE'",
                Integer.class, portfolio.portfolioId()
        ));
        assertEquals(2, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ledger_entries WHERE portfolio_id = ?",
                Integer.class, portfolio.portfolioId()
        ));
    }

    @Test
    void sameKeyConcurrentTradesAcrossServiceInstancesReturnOneExecution() throws Exception {
        var portfolio = operationService.createPortfolio(
                "same-key-writers", "Same key writers", "PAPER", "EUR",
                new BigDecimal("500.00"), "create-same-key-writers"
        );
        OperationService first = new OperationService(jdbcTemplate, transactionTemplate);
        OperationService second = new OperationService(jdbcTemplate, transactionTemplate);
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        Callable<OperationService.TradeExecutionResult> firstCall = () -> {
            barrier.await();
            return first.executeTrade(portfolio.portfolioId(), "AAPL", "buy", BigDecimal.ONE,
                    new BigDecimal("100.00"), BigDecimal.ZERO, "same-trade-key", "TEST_FILL");
        };
        Callable<OperationService.TradeExecutionResult> secondCall = () -> {
            barrier.await();
            return second.executeTrade(portfolio.portfolioId(), "AAPL", "buy", BigDecimal.ONE,
                    new BigDecimal("100.00"), BigDecimal.ZERO, "same-trade-key", "TEST_FILL");
        };

        List<Future<OperationService.TradeExecutionResult>> futures = executor.invokeAll(List.of(firstCall, secondCall));
        executor.shutdown();
        OperationService.TradeExecutionResult firstResult = futures.get(0).get(10, TimeUnit.SECONDS);
        OperationService.TradeExecutionResult secondResult = futures.get(1).get(10, TimeUnit.SECONDS);

        assertEquals(firstResult.operationId(), secondResult.operationId());
        assertEquals(firstResult.executionId(), secondResult.executionId());
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM operations WHERE portfolio_id = ? AND kind = 'TRADE'",
                Integer.class, portfolio.portfolioId()
        ));
    }

    @Test
    void concurrentSellsCannotOversell() throws Exception {
        var portfolio = operationService.createPortfolio(
                "sell-writers", "Sell writers", "PAPER", "EUR",
                new BigDecimal("1000.00"), "create-sell-writers"
        );
        operationService.executeTrade(portfolio.portfolioId(), "AAPL", "buy", new BigDecimal("5"),
                new BigDecimal("10.00"), BigDecimal.ZERO, "seed-shares", "TEST_FILL");
        OperationService first = new OperationService(jdbcTemplate, transactionTemplate);
        OperationService second = new OperationService(jdbcTemplate, transactionTemplate);
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Callable<Boolean>> calls = List.of(
                () -> executeCompetingSell(first, portfolio.portfolioId(), "sell-one", barrier),
                () -> executeCompetingSell(second, portfolio.portfolioId(), "sell-two", barrier)
        );

        List<Future<Boolean>> futures = executor.invokeAll(calls);
        executor.shutdown();
        int successes = 0;
        for (Future<Boolean> future : futures) {
            if (future.get(10, TimeUnit.SECONDS)) {
                successes++;
            }
        }
        assertEquals(1, successes);
        assertEquals("1", operationService.getPortfolioView(portfolio.portfolioId()).positions().getFirst().quantity());
    }

    @Test
    void unresolvedResearchListingRejectsWithoutFinancialWrites() {
        var portfolio = operationService.createPortfolio(
                "unknown-listing", "Unknown listing", "PAPER", "EUR",
                new BigDecimal("1000.00"), "create-unknown-listing"
        );

        assertThrows(IllegalArgumentException.class, () -> operationService.executeTrade(
                portfolio.portfolioId(), "ZZZZ", "buy", BigDecimal.ONE,
                new BigDecimal("10.00"), BigDecimal.ZERO, "unknown-trade", "TEST_FILL"
        ));
        assertEquals("1000.00", operationService.getPortfolioView(portfolio.portfolioId()).cashBalance());
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM operations WHERE portfolio_id = ? AND kind = 'TRADE'",
                Integer.class, portfolio.portfolioId()
        ));
    }

    @Test
    void sqliteBusyRetriesAreBoundedAndSameKeySucceedsAfterLockRelease() throws Exception {
        var portfolio = operationService.createPortfolio(
                "busy-owner", "Busy portfolio", "PAPER", "EUR",
                new BigDecimal("500.00"), "create-busy-owner"
        );
        HikariConfig shortBusyConfig = new HikariConfig();
        shortBusyConfig.setJdbcUrl("jdbc:sqlite:" + dbPath.toAbsolutePath());
        shortBusyConfig.setMaximumPoolSize(2);
        shortBusyConfig.setConnectionInitSql("PRAGMA foreign_keys=ON");
        shortBusyConfig.addDataSourceProperty("busy_timeout", 1);

        try (HikariDataSource shortBusyPool = new HikariDataSource(shortBusyConfig);
             Connection lockConnection = dataSource.getConnection()) {
            JdbcTemplate competingJdbc = new JdbcTemplate(shortBusyPool);
            TransactionTemplate competingTransactions = new TransactionTemplate(
                    new DataSourceTransactionManager(shortBusyPool)
            );
            OperationService restartedService = new OperationService(competingJdbc, competingTransactions);
            lockConnection.setAutoCommit(false);
            try (PreparedStatement lock = lockConnection.prepareStatement(
                    "UPDATE portfolio_state SET revision = revision WHERE portfolio_id = ?"
            )) {
                lock.setString(1, portfolio.portfolioId());
                lock.executeUpdate();
            }

            long started = System.nanoTime();
            assertThrows(RuntimeException.class, () -> restartedService.executeTrade(
                    portfolio.portfolioId(), "AAPL", "buy", BigDecimal.ONE,
                    new BigDecimal("100.00"), BigDecimal.ZERO, "busy-retry-key", "TEST_FILL"
            ));
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2000);
            lockConnection.rollback();

            OperationService.TradeExecutionResult recovered = restartedService.executeTrade(
                    portfolio.portfolioId(), "AAPL", "buy", BigDecimal.ONE,
                    new BigDecimal("100.00"), BigDecimal.ZERO, "busy-retry-key", "TEST_FILL"
            );
            assertNotNull(recovered.operationId());
            assertEquals(1, competingJdbc.queryForObject(
                    "SELECT COUNT(*) FROM operations WHERE portfolio_id = ? AND idempotency_key = 'busy-retry-key'",
                    Integer.class, portfolio.portfolioId()
            ));
        }
    }

    private boolean executeCompetingBuy(
            OperationService service,
            String portfolioId,
            String key,
            CyclicBarrier barrier
    ) throws Exception {
        barrier.await();
        try {
            service.executeTrade(
                    portfolioId, "AAPL", "buy", new BigDecimal("3"),
                    new BigDecimal("100.00"), BigDecimal.ZERO, key, "TEST_FILL"
            );
            return true;
        } catch (AccountingCore.InsufficientFundsException exception) {
            return false;
        }
    }

    private boolean executeCompetingSell(
            OperationService service,
            String portfolioId,
            String key,
            CyclicBarrier barrier
    ) throws Exception {
        barrier.await();
        try {
            service.executeTrade(
                    portfolioId, "AAPL", "sell", new BigDecimal("4"),
                    new BigDecimal("10.00"), BigDecimal.ZERO, key, "TEST_FILL"
            );
            return true;
        } catch (AccountingCore.InsufficientSharesException exception) {
            return false;
        }
    }
}
