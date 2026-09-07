package com.financeally.app.portfolio;

import java.util.List;

public record PortfolioResponse(
        String userId,
        double cashBalance,
        double totalPositionValue,
        double totalPortfolioValue,
        double unrealizedPnl,
        double unrealizedPnlPercent,
        List<PositionDto> positions
) {}
