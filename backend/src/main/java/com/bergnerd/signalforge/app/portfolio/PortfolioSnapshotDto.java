package com.bergnerd.signalforge.app.portfolio;

public record PortfolioSnapshotDto(
        String id,
        double totalValue,
        String recordedAt
) {}
