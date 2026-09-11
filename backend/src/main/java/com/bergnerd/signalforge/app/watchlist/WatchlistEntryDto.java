package com.bergnerd.signalforge.app.watchlist;

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
