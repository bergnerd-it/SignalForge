package com.financeally.app.portfolio;

import com.financeally.app.market.MarketDataSource;
import com.financeally.app.market.PriceTick;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class PortfolioService {

    private final JdbcTemplate jdbcTemplate;
    private final MarketDataSource marketDataSource;
    private final ConcurrentHashMap<String, Object> userLocks = new ConcurrentHashMap<>();

    public PortfolioResponse getPortfolio(String userId) {
        String uid = (userId == null || userId.isBlank()) ? "default" : userId;

        Double cashBalance = getCashBalance(uid);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, ticker, quantity, avg_cost, updated_at FROM positions WHERE user_id = ? ORDER BY ticker ASC",
                uid
        );

        List<PositionDto> positions = new ArrayList<>();
        double totalPositionValue = 0.0;
        double totalCostBasis = 0.0;

        for (Map<String, Object> row : rows) {
            String id = (String) row.get("id");
            String ticker = (String) row.get("ticker");
            double quantity = ((Number) row.get("quantity")).doubleValue();
            double avgCost = ((Number) row.get("avg_cost")).doubleValue();
            String updatedAt = (String) row.get("updated_at");

            PriceTick priceTick = marketDataSource.getPrice(ticker);
            double currentPrice = priceTick.price();
            double totalValue = round(quantity * currentPrice);
            double costBasis = round(quantity * avgCost);
            double unrealizedPnl = round(totalValue - costBasis);
            double unrealizedPnlPercent = costBasis > 0 ? round((unrealizedPnl / costBasis) * 100.0) : 0.0;

            positions.add(new PositionDto(
                    id, ticker, quantity, avgCost, currentPrice,
                    totalValue, unrealizedPnl, unrealizedPnlPercent, updatedAt
            ));

            totalPositionValue += totalValue;
            totalCostBasis += costBasis;
        }

        totalPositionValue = round(totalPositionValue);
        double totalPortfolioValue = round(cashBalance + totalPositionValue);
        double unrealizedPnl = round(totalPositionValue - totalCostBasis);
        double unrealizedPnlPercent = totalCostBasis > 0 ? round((unrealizedPnl / totalCostBasis) * 100.0) : 0.0;

        return new PortfolioResponse(
                uid, cashBalance, totalPositionValue, totalPortfolioValue,
                unrealizedPnl, unrealizedPnlPercent, positions
        );
    }

    @Transactional
    public TradeResponse executeTrade(String userId, TradeRequest request) {
        String uid = (userId == null || userId.isBlank()) ? "default" : userId;
        synchronized (userLocks.computeIfAbsent(uid, k -> new Object())) {
            String ticker = request.ticker().trim().toUpperCase();
            String side = request.side().trim().toLowerCase();
            double quantity = request.quantity();

            if (quantity <= 0) {
                throw new TradeExceptions.InvalidTradeException("Quantity must be greater than zero");
            }
            if (!"buy".equals(side) && !"sell".equals(side)) {
                throw new TradeExceptions.InvalidTradeException("Invalid trade side: " + side + " (must be 'buy' or 'sell')");
            }

            PriceTick priceTick = marketDataSource.getPrice(ticker);
            double currentPrice = priceTick.price();
            double cashBalance = getCashBalance(uid);
            String now = Instant.now().toString();
            String tradeId = UUID.randomUUID().toString();
            double totalCost;

            if ("buy".equals(side)) {
                totalCost = round(quantity * currentPrice);
                if (cashBalance < totalCost) {
                    throw new TradeExceptions.InsufficientFundsException(
                            String.format("Insufficient funds: required $%.2f, available $%.2f", totalCost, cashBalance)
                    );
                }

                double newCash = round(cashBalance - totalCost);
                jdbcTemplate.update("UPDATE users_profile SET cash_balance = ? WHERE id = ?", newCash, uid);

                try {
                    Map<String, Object> existing = jdbcTemplate.queryForMap(
                            "SELECT id, quantity, avg_cost FROM positions WHERE user_id = ? AND ticker = ?",
                            uid, ticker
                    );
                    String posId = (String) existing.get("id");
                    double oldQty = ((Number) existing.get("quantity")).doubleValue();
                    double oldAvgCost = ((Number) existing.get("avg_cost")).doubleValue();

                    double newQty = round(oldQty + quantity);
                    double newAvgCost = round(((oldQty * oldAvgCost) + totalCost) / newQty);

                    jdbcTemplate.update(
                            "UPDATE positions SET quantity = ?, avg_cost = ?, updated_at = ? WHERE id = ?",
                            newQty, newAvgCost, now, posId
                    );
                } catch (EmptyResultDataAccessException e) {
                    jdbcTemplate.update(
                            "INSERT INTO positions (id, user_id, ticker, quantity, avg_cost, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
                            UUID.randomUUID().toString(), uid, ticker, quantity, currentPrice, now
                    );
                }
            } else {
                // Sell
                Map<String, Object> existing;
                try {
                    existing = jdbcTemplate.queryForMap(
                            "SELECT id, quantity, avg_cost FROM positions WHERE user_id = ? AND ticker = ?",
                            uid, ticker
                    );
                } catch (EmptyResultDataAccessException e) {
                    throw new TradeExceptions.InsufficientSharesException(
                            String.format("No open position in %s to sell", ticker)
                    );
                }

                String posId = (String) existing.get("id");
                double oldQty = ((Number) existing.get("quantity")).doubleValue();
                if (oldQty < quantity) {
                    throw new TradeExceptions.InsufficientSharesException(
                            String.format("Insufficient shares: attempted to sell %.2f %s, owned %.2f", quantity, ticker, oldQty)
                    );
                }

                totalCost = round(quantity * currentPrice);
                double newCash = round(cashBalance + totalCost);
                jdbcTemplate.update("UPDATE users_profile SET cash_balance = ? WHERE id = ?", newCash, uid);

                double newQty = round(oldQty - quantity);
                if (newQty <= 0.00001) {
                    jdbcTemplate.update("DELETE FROM positions WHERE id = ?", posId);
                } else {
                    jdbcTemplate.update(
                            "UPDATE positions SET quantity = ?, updated_at = ? WHERE id = ?",
                            newQty, now, posId
                    );
                }
            }

            // Record trade in append-only log
            jdbcTemplate.update(
                    "INSERT INTO trades (id, user_id, ticker, side, quantity, price, executed_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    tradeId, uid, ticker, side, quantity, currentPrice, now
            );

            // Record immediate snapshot after trade
            PortfolioResponse updatedPortfolio = getPortfolio(uid);
            recordSnapshotForUser(uid, updatedPortfolio.totalPortfolioValue());

            log.info("Trade executed: user={} ticker={} side={} qty={} price=${}", uid, ticker, side, quantity, currentPrice);

            return new TradeResponse(
                    tradeId, ticker, side, quantity, currentPrice, totalCost, now, updatedPortfolio
            );
        }
    }

    public List<PortfolioSnapshotDto> getHistory(String userId) {
        String uid = (userId == null || userId.isBlank()) ? "default" : userId;
        return jdbcTemplate.query(
                "SELECT id, total_value, recorded_at FROM portfolio_snapshots WHERE user_id = ? ORDER BY recorded_at ASC",
                (rs, rowNum) -> new PortfolioSnapshotDto(
                        rs.getString("id"),
                        rs.getDouble("total_value"),
                        rs.getString("recorded_at")
                ),
                uid
        );
    }

    @Scheduled(fixedRate = 30000)
    public void recordPeriodicSnapshot() {
        try {
            PortfolioResponse portfolio = getPortfolio("default");
            recordSnapshotForUser("default", portfolio.totalPortfolioValue());
            log.debug("Recorded 30-sec portfolio snapshot: totalValue=${}", portfolio.totalPortfolioValue());
        } catch (Exception e) {
            log.warn("Failed to record periodic snapshot: {}", e.getMessage());
        }
    }

    public void recordSnapshotForUser(String userId, double totalValue) {
        String now = Instant.now().toString();
        jdbcTemplate.update(
                "INSERT INTO portfolio_snapshots (id, user_id, total_value, recorded_at) VALUES (?, ?, ?, ?)",
                UUID.randomUUID().toString(), userId, totalValue, now
        );
    }

    private double getCashBalance(String userId) {
        try {
            Double balance = jdbcTemplate.queryForObject(
                    "SELECT cash_balance FROM users_profile WHERE id = ?",
                    Double.class,
                    userId
            );
            return balance != null ? round(balance) : 10000.0;
        } catch (EmptyResultDataAccessException e) {
            String now = Instant.now().toString();
            jdbcTemplate.update(
                    "INSERT INTO users_profile (id, cash_balance, created_at) VALUES (?, ?, ?)",
                    userId, 10000.0, now
            );
            return 10000.0;
        }
    }

    private double round(double val) {
        return BigDecimal.valueOf(val).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}
