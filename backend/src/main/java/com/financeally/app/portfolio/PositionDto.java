package com.financeally.app.portfolio;

public record PositionDto(
        String id,
        String ticker,
        double quantity,
        double avgCost,
        double currentPrice,
        double totalValue,
        double unrealizedPnl,
        double unrealizedPnlPercent,
        String updatedAt
) {}
