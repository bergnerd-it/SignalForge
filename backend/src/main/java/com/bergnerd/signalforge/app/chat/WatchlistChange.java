package com.bergnerd.signalforge.app.chat;

import com.fasterxml.jackson.annotation.JsonProperty;

public record WatchlistChange(
        @JsonProperty("ticker") String ticker,
        @JsonProperty("action") String action
) {}
