package com.financeally.app.chat;

import com.fasterxml.jackson.annotation.JsonProperty;

public record WatchlistChange(
        @JsonProperty("ticker") String ticker,
        @JsonProperty("action") String action
) {}
