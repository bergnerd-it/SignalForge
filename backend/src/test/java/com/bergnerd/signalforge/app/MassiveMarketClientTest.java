package com.bergnerd.signalforge.app;

import com.bergnerd.signalforge.app.market.MassiveMarketClient;
import com.bergnerd.signalforge.app.market.PriceTick;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MassiveMarketClientTest {

    @Test
    void shouldInitializeAndProvideDefaultPrices() {
        MassiveMarketClient client = new MassiveMarketClient("dummy-key");
        client.registerTicker("AAPL");
        PriceTick tick = client.getPrice("AAPL");

        assertNotNull(tick);
        assertEquals("AAPL", tick.ticker());
        assertEquals(100.0, tick.price());

        Map<String, PriceTick> allPrices = client.getAllPrices();
        assertTrue(allPrices.containsKey("AAPL"));
    }

    @Test
    void shouldHandleEmptyApiKeyGracefully() {
        MassiveMarketClient client = new MassiveMarketClient("");
        client.registerTicker("AAPL");
        assertDoesNotThrow(client::poll);
    }
}
