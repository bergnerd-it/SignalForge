package com.bergnerd.signalforge.app.chat;

import com.bergnerd.signalforge.app.db.migration.LegacyDataMigrator;
import com.bergnerd.signalforge.app.db.migration.MigrationBackupService;
import com.bergnerd.signalforge.app.db.migration.MigrationRunner;
import com.bergnerd.signalforge.app.market.MarketDataSource;
import com.bergnerd.signalforge.app.market.PriceTick;
import com.bergnerd.signalforge.app.operation.IdempotencyExceptions;
import com.bergnerd.signalforge.app.operation.OperationService;
import com.bergnerd.signalforge.app.portfolio.PortfolioService;
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

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ChatRecoveryIntegrationTest {

    @TempDir
    Path tempDir;

    private HikariDataSource dataSource;
    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;
    private PortfolioService portfolioService;
    private WatchlistService watchlistService;
    private AtomicInteger modelCalls;
    private LlmStructuredResponse modelResponse;

    @BeforeEach
    void setUp() {
        Path database = tempDir.resolve("chat-recovery.db");
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + database);
        config.setMaximumPoolSize(6);
        config.setConnectionInitSql("PRAGMA foreign_keys=ON");
        config.addDataSourceProperty("busy_timeout", 5000);
        dataSource = new HikariDataSource(config);
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        MigrationRunner runner = new MigrationRunner(
                jdbc, dataSource, transactions, new MigrationBackupService(), new LegacyDataMigrator()
        );
        ReflectionTestUtils.setField(runner, "datasourceUrl", "jdbc:sqlite:" + database);
        runner.runMigration();

        MarketDataSource market = new MarketDataSource() {
            @Override
            public PriceTick getPrice(String ticker) {
                return new PriceTick(
                        ticker, 100.0, 100.0, 0.0, 0.0, Instant.now().toString(),
                        "flat", "SYNTHETIC_TEST", Instant.now().toString(), true
                );
            }

            @Override
            public Map<String, PriceTick> getAllPrices() {
                return Map.of();
            }

            @Override
            public void registerTicker(String ticker) {
            }
        };
        OperationService operations = new OperationService(jdbc, transactions);
        portfolioService = new PortfolioService(jdbc, market, operations);
        watchlistService = new WatchlistService(jdbc, market);
        modelCalls = new AtomicInteger();
    }

    @AfterEach
    void tearDown() {
        dataSource.close();
    }

    @Test
    void completedRetryInvokesModelAndTradeOnce() {
        modelResponse = new LlmStructuredResponse(
                "Bought AAPL", List.of(new TradeInstruction("AAPL", "buy", 1)), List.of()
        );
        ChatService chat = chatService();

        ChatResponse first = chat.processUserMessage("default", "Buy AAPL", "chat-completed");
        ChatResponse retry = chat.processUserMessage("default", "Buy AAPL", "chat-completed");

        assertEquals(first, retry);
        assertEquals(1, modelCalls.get());
        assertEquals(1, count("operations", "kind = 'TRADE'"));
        assertEquals(1, count("chat_actions", "chat_request_id IS NOT NULL"));
        assertEquals("COMPLETED", jdbc.queryForObject(
                "SELECT status FROM chat_requests WHERE idempotency_key = 'chat-completed'", String.class
        ));
    }

    @Test
    void conflictingReuseFailsBeforeModelOrAction() {
        modelResponse = new LlmStructuredResponse("No action", List.of(), List.of());
        ChatService chat = chatService();
        chat.processUserMessage("default", "First request", "chat-conflict");

        assertThrows(IdempotencyExceptions.IdempotencyConflictException.class,
                () -> chat.processUserMessage("default", "Different request", "chat-conflict"));
        assertEquals(1, modelCalls.get());
    }

    @Test
    void assistantPersistenceFailureRecoversWithoutNewPlanOrTrade() {
        modelResponse = new LlmStructuredResponse(
                "Bought AAPL", List.of(new TradeInstruction("AAPL", "buy", 1)), List.of()
        );
        ChatService firstInstance = chatService();
        firstInstance.setFailureInjector(point -> {
            if ("assistant-message".equals(point)) {
                throw new ChatService.InjectedChatFailure("simulated assistant persistence failure");
            }
        });
        assertThrows(ChatService.InjectedChatFailure.class,
                () -> firstInstance.processUserMessage("default", "Buy AAPL", "chat-restart"));
        assertEquals(1, count("operations", "kind = 'TRADE'"));

        ChatResponse recovered = chatService().processUserMessage("default", "Buy AAPL", "chat-restart");

        assertEquals("Bought AAPL", recovered.message());
        assertEquals(1, modelCalls.get());
        assertEquals(1, count("operations", "kind = 'TRADE'"));
    }

    @Test
    void partialPlanResumesOriginalTradeAndWatchlistActions() {
        modelResponse = new LlmStructuredResponse(
                "Updated portfolio",
                List.of(new TradeInstruction("AAPL", "buy", 1)),
                List.of(new WatchlistChange("IBM", "add"))
        );
        ChatService firstInstance = chatService();
        AtomicInteger failures = new AtomicInteger();
        firstInstance.setFailureInjector(point -> {
            if ("after-trade".equals(point) && failures.getAndIncrement() == 0) {
                throw new ChatService.InjectedChatFailure("simulated crash after committed trade");
            }
        });
        assertThrows(ChatService.InjectedChatFailure.class,
                () -> firstInstance.processUserMessage("default", "Buy and watch", "chat-partial"));

        ChatResponse recovered = chatService().processUserMessage("default", "Buy and watch", "chat-partial");

        assertEquals(2, recovered.actions().size());
        assertEquals(1, modelCalls.get());
        assertEquals(1, count("operations", "kind = 'TRADE'"));
        assertEquals(1, count("watchlist", "user_id = 'default' AND ticker = 'IBM'"));
    }

    @Test
    void malformedLaterActionPreventsEveryAction() {
        modelResponse = new LlmStructuredResponse(
                "Invalid plan",
                List.of(
                        new TradeInstruction("AAPL", "buy", 1),
                        new TradeInstruction("BAD!", "buy", 1)
                ),
                List.of()
        );

        ChatResponse response = chatService().processUserMessage("default", "Invalid plan", "chat-invalid");

        assertTrue(response.actions().isEmpty());
        assertEquals(0, count("operations", "kind = 'TRADE'"));
        assertEquals(0, count("chat_actions", "chat_request_id IS NOT NULL"));
    }

    @Test
    void clearingTextPreservesReplayEvidence() {
        modelResponse = new LlmStructuredResponse(
                "Bought AAPL", List.of(new TradeInstruction("AAPL", "buy", 1)), List.of()
        );
        ChatService chat = chatService();
        ChatResponse first = chat.processUserMessage("default", "Buy AAPL", "chat-clear");
        chat.clearChatHistory("default");

        ChatResponse replay = chat.processUserMessage("default", "Buy AAPL", "chat-clear");

        assertEquals(first, replay);
        assertEquals(0, count("chat_messages", "user_id = 'default'"));
        assertEquals(1, count("chat_requests", "idempotency_key = 'chat-clear'"));
        assertEquals(1, count("chat_actions", "chat_request_id IS NOT NULL"));
    }

    @Test
    void simultaneousSameKeyDoesNotRunTwoWorkers() throws Exception {
        CountDownLatch modelEntered = new CountDownLatch(1);
        CountDownLatch releaseModel = new CountDownLatch(1);
        LlmClient blockingModel = (prompt, history, message) -> {
            modelCalls.incrementAndGet();
            modelEntered.countDown();
            try {
                assertTrue(releaseModel.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
            return new LlmStructuredResponse("No action", List.of(), List.of());
        };
        ChatService first = new ChatService(jdbc, portfolioService, watchlistService, blockingModel, transactions);
        ChatService second = new ChatService(jdbc, portfolioService, watchlistService, blockingModel, transactions);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<ChatResponse> original = executor.submit(
                () -> first.processUserMessage("default", "Status", "chat-concurrent")
        );
        assertTrue(modelEntered.await(5, TimeUnit.SECONDS));

        assertThrows(ChatService.ChatRequestInProgressException.class,
                () -> second.processUserMessage("default", "Status", "chat-concurrent"));
        releaseModel.countDown();
        assertEquals("No action", original.get(5, TimeUnit.SECONDS).message());
        executor.shutdown();
        assertEquals(1, modelCalls.get());
    }

    @Test
    void simultaneousSameKeyCannotRunTwoActionWorkers() throws Exception {
        modelResponse = new LlmStructuredResponse(
                "Bought AAPL", List.of(new TradeInstruction("AAPL", "buy", 1)), List.of()
        );
        CountDownLatch tradeCommitted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        ChatService first = chatService();
        ChatService second = chatService();
        first.setFailureInjector(point -> {
            if ("after-trade".equals(point)) {
                tradeCommitted.countDown();
                try {
                    assertTrue(releaseWorker.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }
        });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<ChatResponse> original = executor.submit(
                () -> first.processUserMessage("default", "Buy AAPL", "chat-action-concurrent")
        );
        assertTrue(tradeCommitted.await(5, TimeUnit.SECONDS));

        assertThrows(ChatService.ChatRequestInProgressException.class,
                () -> second.processUserMessage("default", "Buy AAPL", "chat-action-concurrent"));
        releaseWorker.countDown();
        assertEquals("Bought AAPL", original.get(5, TimeUnit.SECONDS).message());
        executor.shutdown();
        assertEquals(1, modelCalls.get());
        assertEquals(1, count("operations", "kind = 'TRADE'"));
    }

    private ChatService chatService() {
        LlmClient model = (prompt, history, message) -> {
            modelCalls.incrementAndGet();
            return modelResponse;
        };
        return new ChatService(jdbc, portfolioService, watchlistService, model, transactions);
    }

    private int count(String table, String predicate) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + predicate, Integer.class);
    }
}
