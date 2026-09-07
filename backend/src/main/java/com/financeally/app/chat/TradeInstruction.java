package com.financeally.app.chat;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TradeInstruction(
        @JsonProperty("ticker") String ticker,
        @JsonProperty("side") String side,
        @JsonProperty("quantity") double quantity
) {}
