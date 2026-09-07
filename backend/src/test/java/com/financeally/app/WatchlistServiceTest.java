package com.financeally.app;

import com.financeally.app.market.MarketDataSource;
import com.financeally.app.market.PriceTick;
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
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WatchlistServiceTest {

    private JdbcTemplate jdbcTemplate;
    private WatchlistService watchlistService;

    @Mock
    private MarketDataSource marketDataSource;

    @BeforeEach
    void setUp() {
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        dataSource.setSuppressClose(true);

        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("CREATE TABLE watchlist (id TEXT PRIMARY KEY, user_id TEXT, ticker TEXT, added_at TEXT, UNIQUE(user_id, ticker));");

        watchlistService = new WatchlistService(jdbcTemplate, marketDataSource);
    }

    @Test
    void shouldAddAndRetrieveWatchlist() {
        when(marketDataSource.getPrice("NVDA"))
                .thenReturn(new PriceTick("NVDA", 120.0, 118.0, 2.0, 1.69, Instant.now().toString(), "up"));

        WatchlistEntryDto entry = watchlistService.addTicker("default", "NVDA");
        assertNotNull(entry);
        assertEquals("NVDA", entry.ticker());
        assertEquals(120.0, entry.price());

        List<WatchlistEntryDto> list = watchlistService.getWatchlist("default");
        assertEquals(1, list.size());
        assertEquals("NVDA", list.get(0).ticker());
    }

    @Test
    void shouldRemoveTickerFromWatchlist() {
        when(marketDataSource.getPrice("TSLA"))
                .thenReturn(new PriceTick("TSLA", 200.0, 200.0, 0.0, 0.0, Instant.now().toString(), "flat"));

        watchlistService.addTicker("default", "TSLA");
        assertEquals(1, watchlistService.getWatchlist("default").size());

        boolean removed = watchlistService.removeTicker("default", "TSLA");
        assertTrue(removed);
        assertTrue(watchlistService.getWatchlist("default").isEmpty());
    }
}
