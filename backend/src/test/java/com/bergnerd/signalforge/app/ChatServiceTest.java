package com.bergnerd.signalforge.app;

import com.bergnerd.signalforge.app.chat.*;
import com.bergnerd.signalforge.app.db.migration.LegacyDataMigrator;
import com.bergnerd.signalforge.app.db.migration.MigrationBackupService;
import com.bergnerd.signalforge.app.db.migration.MigrationRunner;
import com.bergnerd.signalforge.app.portfolio.PortfolioResponse;
import com.bergnerd.signalforge.app.portfolio.PortfolioService;
import com.bergnerd.signalforge.app.portfolio.TradeRequest;
import com.bergnerd.signalforge.app.portfolio.TradeResponse;
import com.bergnerd.signalforge.app.watchlist.WatchlistService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    private JdbcTemplate jdbcTemplate;
    private ChatService chatService;

    @Mock
    private PortfolioService portfolioService;

    @Mock
    private WatchlistService watchlistService;

    @Mock
    private LlmClient llmClient;

    @BeforeEach
    void setUp() {
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        dataSource.setSuppressClose(true);

        jdbcTemplate = new JdbcTemplate(dataSource);
        TransactionTemplate transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        MigrationRunner runner = new MigrationRunner(
                jdbcTemplate, dataSource, transactionTemplate, new MigrationBackupService(), new LegacyDataMigrator()
        );
        ReflectionTestUtils.setField(runner, "datasourceUrl", "jdbc:sqlite::memory:");
        runner.runMigration();
        chatService = new ChatService(jdbcTemplate, portfolioService, watchlistService, llmClient, transactionTemplate);
    }

    @Test
    void shouldProcessMessageAndAutoExecuteTrade() {
        PortfolioResponse mockPortfolio = new PortfolioResponse("default", 10000.0, 0.0, 10000.0, 0.0, 0.0, List.of());
        when(portfolioService.getPortfolio("default")).thenReturn(mockPortfolio);
        when(watchlistService.getWatchlist("default")).thenReturn(List.of());

        LlmStructuredResponse llmResponse = new LlmStructuredResponse(
                "I have executed a market buy for 5 shares of AAPL.",
                List.of(new TradeInstruction("AAPL", "buy", 5.0)),
                List.of()
        );
        when(llmClient.generateResponse(anyString(), anyList(), eq("Buy 5 AAPL"))).thenReturn(llmResponse);

        TradeResponse tradeResponse = new TradeResponse("t-1", "AAPL", "buy", 5.0, 190.0, 950.0, Instant.now().toString(), mockPortfolio);
        when(portfolioService.executeTrade(eq("default"), any(TradeRequest.class), anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(2);
            jdbcTemplate.update(
                    "INSERT INTO operations (id, portfolio_id, kind, idempotency_key, payload_hash, result_json, business_at, created_at) " +
                            "VALUES ('op-chat-test', 'portfolio-legacy-demo-default', 'TRADE', ?, 'hash', '{}', ?, ?)",
                    key, Instant.now().toString(), Instant.now().toString()
            );
            return tradeResponse;
        });

        ChatResponse response = chatService.processUserMessage("default", "Buy 5 AAPL", "chat-buy-1");
        assertNotNull(response);
        assertEquals("I have executed a market buy for 5 shares of AAPL.", response.message());
        assertEquals(1, response.actions().size());

        ChatActionExecution action = response.actions().get(0);
        assertEquals("trade", action.type());
        assertEquals("AAPL", action.ticker());
        assertTrue(action.success());

        List<ChatMessageRecord> history = chatService.getChatHistory("default");
        assertEquals(2, history.size()); // 1 user + 1 assistant
        assertEquals("user", history.get(0).role());
        assertEquals("assistant", history.get(1).role());
    }

    @Test
    void shouldProcessMessageWithWatchlistChange() {
        PortfolioResponse mockPortfolio = new PortfolioResponse("default", 10000.0, 0.0, 10000.0, 0.0, 0.0, List.of());
        when(portfolioService.getPortfolio("default")).thenReturn(mockPortfolio);
        when(watchlistService.getWatchlist("default")).thenReturn(List.of());

        LlmStructuredResponse llmResponse = new LlmStructuredResponse(
                "Added NVDA to your watchlist.",
                List.of(),
                List.of(new WatchlistChange("NVDA", "add"))
        );
        when(llmClient.generateResponse(anyString(), anyList(), eq("Track NVDA"))).thenReturn(llmResponse);

        ChatResponse response = chatService.processUserMessage("default", "Track NVDA", "chat-watch-1");
        assertNotNull(response);
        assertEquals(1, response.actions().size());
        assertEquals("watchlist", response.actions().get(0).type());
        assertEquals("NVDA", response.actions().get(0).ticker());

        verify(watchlistService, times(1)).addTicker("default", "NVDA");
    }

    @Test
    void shouldClearChatHistory() {
        jdbcTemplate.update("INSERT INTO chat_messages (id, user_id, role, content, actions, created_at) VALUES ('1', 'default', 'user', 'Hi', null, '2026-09-01T00:00:00Z')");
        assertEquals(1, chatService.getChatHistory("default").size());

        chatService.clearChatHistory("default");
        assertEquals(0, chatService.getChatHistory("default").size());
    }
}
