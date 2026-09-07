package com.financeally.app.portfolio;

public record PortfolioSnapshotDto(
        String id,
        double totalValue,
        String recordedAt
) {}
