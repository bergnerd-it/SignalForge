package com.bergnerd.signalforge.app.portfolio;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TradeRequest(
        @NotBlank(message = "Ticker is required")
        String ticker,

        @Positive(message = "Quantity must be greater than zero")
        double quantity,

        @NotBlank(message = "Side is required (buy or sell)")
        String side,

        String idempotencyKey,

        String portfolioScope
) {
    public TradeRequest(String ticker, double quantity, String side) {
        this(ticker, quantity, side, null, null);
    }

    public TradeRequest(String ticker, double quantity, String side, String idempotencyKey) {
        this(ticker, quantity, side, idempotencyKey, null);
    }
}
