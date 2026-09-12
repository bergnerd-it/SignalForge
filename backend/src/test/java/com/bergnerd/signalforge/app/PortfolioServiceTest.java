package com.bergnerd.signalforge.app;

import com.bergnerd.signalforge.app.db.migration.SqlScriptParser;
import com.bergnerd.signalforge.app.market.MarketDataSource;
import com.bergnerd.signalforge.app.market.PriceTick;
import com.bergnerd.signalforge.app.market.MarketExceptions;
import com.bergnerd.signalforge.app.operation.OperationService;
import com.bergnerd.signalforge.app.portfolio.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PortfolioServiceTest {

    private PortfolioService portfolioService;
    private JdbcTemplate jdbcTemplate;

    @Mock
    private MarketDataSource marketDataSource;

    @BeforeEach
    void setUp() throws Exception {
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        dataSource.setSuppressClose(true);

        jdbcTemplate = new JdbcTemplate(dataSource);

        // Load V1 schema
        ClassPathResource resource = new ClassPathResource("db/migration/V1__init_m1b_schema.sql");
        String sql;
        try (InputStream is = resource.getInputStream()) {
            sql = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
        for (String stmt : SqlScriptParser.parseStatements(sql)) {
            jdbcTemplate.execute(stmt);
        }

        DataSourceTransactionManager tm = new DataSourceTransactionManager(dataSource);
        TransactionTemplate tt = new TransactionTemplate(tm);
        OperationService operationService = new OperationService(jdbcTemplate, tt);

        portfolioService = new PortfolioService(jdbcTemplate, marketDataSource, operationService);

        // Seed default portfolio
        String now = Instant.now().toString();
        jdbcTemplate.update(
                "INSERT INTO portfolios (id, owner_id, name, mode, base_currency, initial_cash, created_at, paper_started_at, rounding_policy_version) " +
                        "VALUES ('portfolio-legacy-demo-default', 'default', 'Legacy Demo Portfolio', 'LEGACY_DEMO', 'USD', '10000.00', ?, NULL, 'v1-half-even')",
                now
        );
        jdbcTemplate.update(
                "INSERT INTO portfolio_state (portfolio_id, cash_amount, revision) VALUES ('portfolio-legacy-demo-default', '10000.00', 1)"
        );
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

        TradeResponse response = portfolioService.executeTrade("default", new TradeRequest("AAPL", 10.0, "buy"), "buy-aapl-1");
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
                portfolioService.executeTrade("default", new TradeRequest("TSLA", 20.0, "buy"), "insufficient-1")
        );
    }

    @Test
    void shouldExecuteSellTradeSuccessfully() {
        when(marketDataSource.getPrice("AAPL"))
                .thenReturn(new PriceTick("AAPL", 100.0, 100.0, 0.0, 0.0, Instant.now().toString(), "flat"));

        portfolioService.executeTrade("default", new TradeRequest("AAPL", 10.0, "buy"), "buy-aapl-2");

        when(marketDataSource.getPrice("AAPL"))
                .thenReturn(new PriceTick("AAPL", 120.0, 100.0, 20.0, 20.0, Instant.now().toString(), "up"));

        TradeResponse sellResponse = portfolioService.executeTrade("default", new TradeRequest("AAPL", 5.0, "sell"), "sell-aapl-2");
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
        jdbcTemplate.update(
                "INSERT INTO portfolios (id, owner_id, name, mode, base_currency, initial_cash, created_at, paper_started_at, rounding_policy_version) " +
                        "VALUES ('portfolio-legacy-demo-user2', 'user2', 'Legacy Demo (user2)', 'LEGACY_DEMO', 'USD', '10000.00', ?, NULL, 'v1-half-even')",
                now
        );
        jdbcTemplate.update(
                "INSERT INTO portfolio_state (portfolio_id, cash_amount, revision) VALUES ('portfolio-legacy-demo-user2', '10000.00', 1)"
        );

        when(marketDataSource.getPrice("AAPL"))
                .thenReturn(new PriceTick("AAPL", 100.0, 100.0, 0.0, 0.0, now, "flat"));

        TradeResponse response1 = portfolioService.executeTrade("default", new TradeRequest("AAPL", 5.0, "buy"), "trade-user1");
        TradeResponse response2 = portfolioService.executeTrade("user2", new TradeRequest("AAPL", 10.0, "buy"), "trade-user2");

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
                portfolioService.executeTrade("default", new TradeRequest("GOOGL", 5.0, "sell"), "unowned-1")
        );
    }

    @Test
    void completedRetryReturnsStoredFillWithoutMarketAccess() {
        when(marketDataSource.getPrice("AAPL"))
                .thenReturn(new PriceTick("AAPL", 100.0, 100.0, 0.0, 0.0, Instant.now().toString(), "flat"));
        TradeRequest request = new TradeRequest("AAPL", 1.0, "buy");
        TradeResponse first = portfolioService.executeTrade("default", request, "stable-replay-key");
        int callsAfterCommit = mockingDetails(marketDataSource).getInvocations().size();

        reset(marketDataSource);
        TradeResponse retry = portfolioService.executeTrade("default", request, "stable-replay-key");

        assertEquals(first.tradeId(), retry.tradeId());
        assertEquals(100.0, retry.price());
        assertNull(retry.updatedPortfolio());
        verifyNoInteractions(marketDataSource);
        assertTrue(callsAfterCommit >= 1);
    }

    @Test
    void missingQuoteIsReportedWithoutUsingPurchaseCostAsMarketValue() {
        when(marketDataSource.getPrice("AAPL"))
                .thenReturn(new PriceTick("AAPL", 100.0, 100.0, 0.0, 0.0, Instant.now().toString(), "flat"));
        portfolioService.executeTrade("default", new TradeRequest("AAPL", 1.0, "buy"), "missing-valuation-buy");

        reset(marketDataSource);
        PortfolioResponse portfolio = portfolioService.getPortfolio("default");

        PositionDto position = portfolio.positions().getFirst();
        assertNull(position.currentPrice());
        assertNull(position.totalValue());
        assertNull(position.unrealizedPnl());
        assertNull(portfolio.totalPositionValue());
        assertNull(portfolio.totalPortfolioValue());
    }
}
