package com.bergnerd.signalforge.app.portfolio;

public record TradeResponse(
        String tradeId,
        String ticker,
        String side,
        double quantity,
        double price,
        double totalCost,
        String executedAt,
        PortfolioResponse updatedPortfolio
) {}
