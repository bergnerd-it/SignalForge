package com.bergnerd.signalforge.app.portfolio;

import com.bergnerd.signalforge.app.accounting.AccountingCore;
import com.bergnerd.signalforge.app.market.MarketDataSource;
import com.bergnerd.signalforge.app.market.MarketExceptions;
import com.bergnerd.signalforge.app.market.PriceTick;
import com.bergnerd.signalforge.app.operation.IdempotencyExceptions;
import com.bergnerd.signalforge.app.operation.OperationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class PortfolioService {

    private final JdbcTemplate jdbcTemplate;
    private final MarketDataSource marketDataSource;
    private final OperationService operationService;

    public PortfolioResponse getPortfolio(String userId) {
        String uid = (userId == null || userId.isBlank()) ? "default" : userId;
        String portfolioId = resolveLegacyPortfolioId(uid);

        OperationService.PortfolioView view = operationService.getPortfolioView(portfolioId);
        double cashBalance = new BigDecimal(view.cashBalance()).doubleValue();

        List<PositionDto> positions = new ArrayList<>();
        double totalPositionValue = 0.0;
        double totalCostBasis = 0.0;
        boolean valuationComplete = true;

        for (OperationService.PositionView pos : view.positions()) {
            String ticker = pos.ticker();
            double quantity = new BigDecimal(pos.quantity()).doubleValue();
            double avgCost = new BigDecimal(pos.averageCost()).doubleValue();
            double costBasis = round(new BigDecimal(pos.totalAcquisitionCost()).doubleValue());

            PriceTick priceTick = marketDataSource.getPrice(ticker);
            Double currentPrice = priceTick != null ? priceTick.price() : null;
            Double totalValue = currentPrice == null ? null : round(quantity * currentPrice);
            Double unrealizedPnl = totalValue == null ? null : round(totalValue - costBasis);
            Double unrealizedPnlPercent = unrealizedPnl == null
                    ? null
                    : costBasis > 0 ? round((unrealizedPnl / costBasis) * 100.0) : 0.0;

            positions.add(new PositionDto(
                    pos.listingId(), ticker, quantity, avgCost, currentPrice,
                    totalValue, unrealizedPnl, unrealizedPnlPercent, pos.updatedAt()
            ));

            if (totalValue == null) {
                valuationComplete = false;
            } else {
                totalPositionValue += totalValue;
            }
            totalCostBasis += costBasis;
        }

        totalPositionValue = round(totalPositionValue);
        Double completePositionValue = valuationComplete ? totalPositionValue : null;
        Double totalPortfolioValue = valuationComplete ? round(cashBalance + totalPositionValue) : null;
        Double unrealizedPnl = valuationComplete ? round(totalPositionValue - totalCostBasis) : null;
        Double unrealizedPnlPercent = valuationComplete
                ? totalCostBasis > 0 ? round((unrealizedPnl / totalCostBasis) * 100.0) : 0.0
                : null;

        return new PortfolioResponse(
                uid, cashBalance, completePositionValue, totalPortfolioValue,
                unrealizedPnl, unrealizedPnlPercent, positions
        );
    }

    public TradeResponse executeTrade(String userId, TradeRequest request) {
        return executeTrade(userId, request, request.idempotencyKey());
    }

    public TradeResponse executeTrade(String userId, TradeRequest request, String effectiveKey) {
        String uid = (userId == null || userId.isBlank()) ? "default" : userId;

        // 1. Enforce explicit scope boundary: reject legacy requests targeting research/PAPER
        if (request.portfolioScope() != null && !request.portfolioScope().isBlank()) {
            String scope = request.portfolioScope().trim().toUpperCase();
            if ("PAPER".equals(scope) || "BACKTEST".equals(scope) || request.portfolioScope().startsWith("portfolio-")) {
                throw new TradeExceptions.InvalidTradeException(
                        "Explicit research or PAPER scope is forbidden on the legacy trading endpoint: " + request.portfolioScope()
                );
            }
        }

        // 2. Validate idempotency key
        if (effectiveKey == null || effectiveKey.isBlank()) {
            throw new IdempotencyExceptions.MissingIdempotencyKeyException(
                    "Idempotency key is required for trade execution"
            );
        }

        String ticker = request.ticker().trim().toUpperCase();
        String side = request.side().trim().toLowerCase();
        double quantity = request.quantity();

        if (quantity <= 0) {
            throw new TradeExceptions.InvalidTradeException("Quantity must be greater than zero");
        }
        if (!"buy".equals(side) && !"sell".equals(side)) {
            throw new TradeExceptions.InvalidTradeException("Invalid trade side: " + side + " (must be 'buy' or 'sell')");
        }

        BigDecimal exactQuantity = AccountingCore.normalizeQuantity(BigDecimal.valueOf(quantity));
        String portfolioId = resolveLegacyPortfolioId(uid);

        Optional<OperationService.TradeExecutionResult> completed = operationService.findCompletedTrade(
                portfolioId, ticker, side, exactQuantity, effectiveKey
        );
        if (completed.isPresent()) {
            return toTradeResponse(uid, completed.get(), false);
        }

        PriceTick priceTick = marketDataSource.getPrice(ticker);
        if (priceTick == null || !priceTick.isExecutable()) {
            throw new MarketExceptions.QuoteUnavailableException("Executable quote unavailable for ticker: " + ticker);
        }
        BigDecimal currentPrice = AccountingCore.normalizePrice(BigDecimal.valueOf(priceTick.price()));

        // 5. Execute through pure OperationService boundary
        OperationService.TradeExecutionResult opResult;
        try {
            opResult = operationService.executeTrade(
                    portfolioId,
                    ticker,
                    side,
                    exactQuantity,
                    currentPrice,
                    BigDecimal.ZERO,
                    effectiveKey,
                    "LEGACY_DEMO_FILL"
            );
        } catch (AccountingCore.InsufficientFundsException e) {
            throw new TradeExceptions.InsufficientFundsException(e.getMessage());
        } catch (AccountingCore.InsufficientSharesException e) {
            throw new TradeExceptions.InsufficientSharesException(e.getMessage());
        }

        log.info("Trade executed via OperationService: user={} ticker={} side={} qty={} price=${} key={}",
                uid, ticker, side, quantity, currentPrice, effectiveKey);
        TradeResponse response = toTradeResponse(uid, opResult, true);
        if (response.updatedPortfolio() != null && response.updatedPortfolio().totalPortfolioValue() != null) {
            recordSnapshotForUser(uid, response.updatedPortfolio().totalPortfolioValue());
        }
        return response;
    }

    public List<PortfolioSnapshotDto> getHistory(String userId) {
        String uid = (userId == null || userId.isBlank()) ? "default" : userId;
        // Bounded query for performance and stability
        return jdbcTemplate.query(
                "SELECT id, total_value, recorded_at FROM portfolio_snapshots WHERE user_id = ? ORDER BY recorded_at ASC LIMIT 1000",
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
            if (portfolio.totalPortfolioValue() != null) {
                recordSnapshotForUser("default", portfolio.totalPortfolioValue());
                log.debug("Recorded 30-sec portfolio snapshot: totalValue=${}", portfolio.totalPortfolioValue());
            }
        } catch (Exception e) {
            log.warn("Failed to record periodic snapshot: {}", e.getMessage());
        }
    }

    public void recordSnapshotForUser(String userId, double totalValue) {
        String now = Instant.now().toString();
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update(
                "INSERT INTO portfolio_snapshots (id, user_id, total_value, recorded_at) VALUES (?, ?, ?, ?)",
                id, userId, totalValue, now
        );

        String portfolioId = resolveLegacyPortfolioId(userId);
        jdbcTemplate.update(
                "INSERT OR IGNORE INTO valuations (id, portfolio_id, business_at, valuation_sequence, cash, positions_value, receivables_value, equity, source_quality) " +
                        "VALUES (?, ?, ?, 1, '0.00', '0.00', '0.00', ?, 'DEMO_VALUATION')",
                id, portfolioId, now, BigDecimal.valueOf(totalValue).setScale(2, RoundingMode.HALF_EVEN).toPlainString()
        );
    }

    private String resolveLegacyPortfolioId(String userId) {
        String portfolioId = "portfolio-legacy-demo-" + userId;
        Integer exists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM portfolios WHERE id = ?",
                Integer.class,
                portfolioId
        );
        if (exists == null || exists == 0) {
            String now = Instant.now().toString();
            jdbcTemplate.update(
                    "INSERT OR IGNORE INTO portfolios (id, owner_id, name, mode, base_currency, initial_cash, created_at, paper_started_at, rounding_policy_version) " +
                            "VALUES (?, ?, ?, 'LEGACY_DEMO', 'USD', '10000.00', ?, NULL, ?)",
                    portfolioId, userId, "Legacy Demo (" + userId + ")", now, AccountingCore.POLICY_VERSION
            );
            jdbcTemplate.update(
                    "INSERT OR IGNORE INTO portfolio_state (portfolio_id, cash_amount, revision) VALUES (?, '10000.00', 1)",
                    portfolioId
            );
        }
        return portfolioId;
    }

    private TradeResponse toTradeResponse(
            String userId,
            OperationService.TradeExecutionResult result,
            boolean includeLivePortfolio
    ) {
        double resultQuantity = new BigDecimal(result.units()).doubleValue();
        double resultPrice = new BigDecimal(result.fillPrice()).doubleValue();
        PortfolioResponse portfolio = null;
        if (includeLivePortfolio) {
            try {
                portfolio = getPortfolio(userId);
            } catch (MarketExceptions.QuoteUnavailableException exception) {
                log.info("Returning trade result without a live portfolio valuation: {}", exception.getMessage());
            }
        }
        return new TradeResponse(
                result.executionId(), result.ticker(), result.side(), resultQuantity, resultPrice,
                round(resultQuantity * resultPrice), result.executedAt(), portfolio
        );
    }

    private double round(double val) {
        return BigDecimal.valueOf(val).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}
