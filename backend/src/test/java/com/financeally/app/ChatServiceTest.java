package com.financeally.app;

import com.financeally.app.chat.*;
import com.financeally.app.portfolio.PortfolioResponse;
import com.financeally.app.portfolio.PortfolioService;
import com.financeally.app.portfolio.TradeRequest;
import com.financeally.app.portfolio.TradeResponse;
import com.financeally.app.watchlist.WatchlistEntryDto;
import com.financeally.app.watchlist.WatchlistService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

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
        jdbcTemplate.execute("CREATE TABLE chat_messages (id TEXT PRIMARY KEY, user_id TEXT, role TEXT, content TEXT, actions TEXT, created_at TEXT);");

        chatService = new ChatService(jdbcTemplate, portfolioService, watchlistService, llmClient);
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
        when(portfolioService.executeTrade(eq("default"), any(TradeRequest.class))).thenReturn(tradeResponse);

        ChatResponse response = chatService.processUserMessage("default", "Buy 5 AAPL");
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

        ChatResponse response = chatService.processUserMessage("default", "Track NVDA");
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
