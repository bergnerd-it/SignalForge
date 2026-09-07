package com.financeally.app.portfolio;

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
        String side
) {}
