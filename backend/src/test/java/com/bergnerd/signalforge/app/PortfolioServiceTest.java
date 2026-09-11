package com.bergnerd.signalforge.app;

import com.bergnerd.signalforge.app.market.MarketDataSource;
import com.bergnerd.signalforge.app.market.PriceTick;
import com.bergnerd.signalforge.app.portfolio.PortfolioResponse;
import com.bergnerd.signalforge.app.portfolio.PortfolioService;
import com.bergnerd.signalforge.app.portfolio.PositionDto;
import com.bergnerd.signalforge.app.portfolio.TradeExceptions;
import com.bergnerd.signalforge.app.portfolio.TradeRequest;
import com.bergnerd.signalforge.app.portfolio.TradeResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PortfolioServiceTest {

    private JdbcTemplate jdbcTemplate;
    private PortfolioService portfolioService;

    @Mock
    private MarketDataSource marketDataSource;

    @BeforeEach
    void setUp() {
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        dataSource.setSuppressClose(true);

        jdbcTemplate = new JdbcTemplate(dataSource);

        jdbcTemplate.execute("CREATE TABLE users_profile (id TEXT PRIMARY KEY, cash_balance REAL, created_at TEXT);");
        jdbcTemplate.execute("CREATE TABLE positions (id TEXT PRIMARY KEY, user_id TEXT, ticker TEXT, quantity REAL, avg_cost REAL, updated_at TEXT, UNIQUE(user_id, ticker));");
        jdbcTemplate.execute("CREATE TABLE trades (id TEXT PRIMARY KEY, user_id TEXT, ticker TEXT, side TEXT, quantity REAL, price REAL, executed_at TEXT);");
        jdbcTemplate.execute("CREATE TABLE portfolio_snapshots (id TEXT PRIMARY KEY, user_id TEXT, total_value REAL, recorded_at TEXT);");

        String now = Instant.now().toString();
        jdbcTemplate.update("INSERT INTO users_profile (id, cash_balance, created_at) VALUES ('default', 10000.0, ?)", now);

        portfolioService = new PortfolioService(jdbcTemplate, marketDataSource);
    }

    @Test
    void shouldGetInitialEmptyPortfolio() {
        PortfolioResponse portfolio = portfolioService.getPortfolio("default");
        assertNotNull(portfolio);
        assertEquals(10000.0, portfolio.cashBalance());
        assertEquals(0.0, portfolio.totalPositionValue());
        assertEquals(10000.0, portfolio.totalPortfolioValue());
        assertTrue(portfolio.positions().isEmpty());
    }

    @Test
    void shouldExecuteBuyTradeSuccessfully() {
        when(marketDataSource.getPrice("AAPL"))
                .thenReturn(new PriceTick("AAPL", 150.0, 150.0, 0.0, 0.0, Instant.now().toString(), "flat"));

        TradeResponse response = portfolioService.executeTrade("default", new TradeRequest("AAPL", 10.0, "buy"));
        assertNotNull(response);
        assertEquals("AAPL", response.ticker());
        assertEquals("buy", response.side());
        assertEquals(10.0, response.quantity());
        assertEquals(150.0, response.price());
        assertEquals(1500.0, response.totalCost());

        PortfolioResponse portfolio = portfolioService.getPortfolio("default");
        assertEquals(8500.0, portfolio.cashBalance());
        assertEquals(1500.0, portfolio.totalPositionValue());
        assertEquals(10000.0, portfolio.totalPortfolioValue());
        assertEquals(1, portfolio.positions().size());

        PositionDto pos = portfolio.positions().get(0);
        assertEquals("AAPL", pos.ticker());
        assertEquals(10.0, pos.quantity());
        assertEquals(150.0, pos.avgCost());
    }

    @Test
    void shouldThrowWhenBuyingWithInsufficientFunds() {
        when(marketDataSource.getPrice("TSLA"))
                .thenReturn(new PriceTick("TSLA", 1000.0, 1000.0, 0.0, 0.0, Instant.now().toString(), "flat"));

        assertThrows(TradeExceptions.InsufficientFundsException.class, () ->
                portfolioService.executeTrade("default", new TradeRequest("TSLA", 20.0, "buy"))
        );
    }

    @Test
    void shouldExecuteSellTradeSuccessfully() {
        when(marketDataSource.getPrice("AAPL"))
                .thenReturn(new PriceTick("AAPL", 100.0, 100.0, 0.0, 0.0, Instant.now().toString(), "flat"));

        portfolioService.executeTrade("default", new TradeRequest("AAPL", 10.0, "buy"));

        when(marketDataSource.getPrice("AAPL"))
                .thenReturn(new PriceTick("AAPL", 120.0, 100.0, 20.0, 20.0, Instant.now().toString(), "up"));

        TradeResponse sellResponse = portfolioService.executeTrade("default", new TradeRequest("AAPL", 5.0, "sell"));
        assertEquals("sell", sellResponse.side());
        assertEquals(5.0, sellResponse.quantity());
        assertEquals(600.0, sellResponse.totalCost());

        PortfolioResponse portfolio = portfolioService.getPortfolio("default");
        assertEquals(9600.0, portfolio.cashBalance());
        assertEquals(600.0, portfolio.totalPositionValue());
        assertEquals(10200.0, portfolio.totalPortfolioValue());
        assertEquals(1, portfolio.positions().size());
        assertEquals(5.0, portfolio.positions().get(0).quantity());
    }

    @Test
    void shouldExecuteConcurrentTradesForDifferentUsers() {
        String now = Instant.now().toString();
        jdbcTemplate.update("INSERT INTO users_profile (id, cash_balance, created_at) VALUES ('user2', 10000.0, ?)", now);

        when(marketDataSource.getPrice("AAPL"))
                .thenReturn(new PriceTick("AAPL", 100.0, 100.0, 0.0, 0.0, now, "flat"));

        TradeResponse response1 = portfolioService.executeTrade("default", new TradeRequest("AAPL", 5.0, "buy"));
        TradeResponse response2 = portfolioService.executeTrade("user2", new TradeRequest("AAPL", 10.0, "buy"));

        assertNotNull(response1);
        assertNotNull(response2);
        assertEquals(9500.0, portfolioService.getPortfolio("default").cashBalance());
        assertEquals(9000.0, portfolioService.getPortfolio("user2").cashBalance());
    }

    @Test
    void shouldThrowWhenSellingUnownedShares() {
        when(marketDataSource.getPrice("GOOGL"))
                .thenReturn(new PriceTick("GOOGL", 150.0, 150.0, 0.0, 0.0, Instant.now().toString(), "flat"));

        assertThrows(TradeExceptions.InsufficientSharesException.class, () ->
                portfolioService.executeTrade("default", new TradeRequest("GOOGL", 5.0, "sell"))
        );
    }
}
