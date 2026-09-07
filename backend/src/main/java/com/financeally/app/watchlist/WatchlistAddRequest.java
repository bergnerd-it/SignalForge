package com.financeally.app.watchlist;

import jakarta.validation.constraints.NotBlank;

public record WatchlistAddRequest(
        @NotBlank(message = "Ticker is required")
        String ticker
) {}
