package com.bergnerd.signalforge.app.portfolio;

import java.util.List;

public record PortfolioResponse(
        String userId,
        double cashBalance,
        Double totalPositionValue,
        Double totalPortfolioValue,
        Double unrealizedPnl,
        Double unrealizedPnlPercent,
        List<PositionDto> positions
) {}
