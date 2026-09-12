package com.bergnerd.signalforge.app;

import com.bergnerd.signalforge.app.market.MarketExceptions;
import com.bergnerd.signalforge.app.market.MassiveMarketClient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MassiveMarketClientTest {

    @Test
    void shouldRejectMissingQuoteInsteadOfFabricatingPrice() {
        MassiveMarketClient client = new MassiveMarketClient("dummy-key");
        client.registerTicker("AAPL");

        assertThrows(MarketExceptions.QuoteUnavailableException.class, () -> client.getPrice("AAPL"));
    }

    @Test
    void shouldHandleEmptyApiKeyGracefully() {
        MassiveMarketClient client = new MassiveMarketClient("");
        client.registerTicker("AAPL");
        assertDoesNotThrow(client::poll);
    }
}
