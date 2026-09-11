package com.bergnerd.signalforge.app.chat;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record LlmStructuredResponse(
        @JsonProperty("message") String message,
        @JsonProperty("trades") List<TradeInstruction> trades,
        @JsonProperty("watchlist_changes") List<WatchlistChange> watchlist_changes
) {
    public List<TradeInstruction> safeTrades() {
        return trades != null ? trades : List.of();
    }

    public List<WatchlistChange> safeWatchlistChanges() {
        return watchlist_changes != null ? watchlist_changes : List.of();
    }
}
