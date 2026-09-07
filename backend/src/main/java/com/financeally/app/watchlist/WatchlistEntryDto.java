package com.financeally.app.watchlist;

public record WatchlistEntryDto(
        String id,
        String ticker,
        double price,
        double previousPrice,
        double change,
        double changePercent,
        String direction,
        String addedAt
) {}
