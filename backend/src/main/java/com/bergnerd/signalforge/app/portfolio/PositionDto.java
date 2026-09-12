package com.bergnerd.signalforge.app.portfolio;

public record PositionDto(
        String id,
        String ticker,
        double quantity,
        double avgCost,
        Double currentPrice,
        Double totalValue,
        Double unrealizedPnl,
        Double unrealizedPnlPercent,
        String updatedAt
) {}
