package com.bergnerd.signalforge.app.watchlist;

import com.bergnerd.signalforge.app.market.MarketDataSource;
import com.bergnerd.signalforge.app.market.PriceTick;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class WatchlistService {

    private final JdbcTemplate jdbcTemplate;
    private final MarketDataSource marketDataSource;

    public List<WatchlistEntryDto> getWatchlist(String userId) {
        String uid = (userId == null || userId.isBlank()) ? "default" : userId;

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, ticker, added_at FROM watchlist WHERE user_id = ? ORDER BY added_at ASC",
                uid
        );

        List<WatchlistEntryDto> entries = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String id = (String) row.get("id");
            String ticker = (String) row.get("ticker");
            String addedAt = (String) row.get("added_at");

            PriceTick tick = marketDataSource.getPrice(ticker);
            entries.add(new WatchlistEntryDto(
                    id, ticker, tick.price(), tick.previousPrice(),
                    tick.change(), tick.changePercent(), tick.direction(), addedAt
            ));
        }

        return entries;
    }

    @Transactional
    public WatchlistEntryDto addTicker(String userId, String ticker) {
        String uid = (userId == null || userId.isBlank()) ? "default" : userId;
        String symbol = ticker.trim().toUpperCase();
        marketDataSource.registerTicker(symbol);

        String now = Instant.now().toString();
        String id = UUID.randomUUID().toString();

        jdbcTemplate.update(
                "INSERT OR IGNORE INTO watchlist (id, user_id, ticker, added_at) VALUES (?, ?, ?, ?)",
                id, uid, symbol, now
        );

        PriceTick tick = marketDataSource.getPrice(symbol);
        log.info("Ticker added to watchlist: user={} ticker={}", uid, symbol);

        return new WatchlistEntryDto(
                id, symbol, tick.price(), tick.previousPrice(),
                tick.change(), tick.changePercent(), tick.direction(), now
        );
    }

    @Transactional
    public boolean removeTicker(String userId, String ticker) {
        String uid = (userId == null || userId.isBlank()) ? "default" : userId;
        String symbol = ticker.trim().toUpperCase();

        int deleted = jdbcTemplate.update(
                "DELETE FROM watchlist WHERE user_id = ? AND ticker = ?",
                uid, symbol
        );
        log.info("Ticker removed from watchlist: user={} ticker={} deleted={}", uid, symbol, deleted > 0);
        return deleted > 0;
    }
}
