package com.bergnerd.signalforge.app;

import com.bergnerd.signalforge.app.market.MarketSimulator;
import com.bergnerd.signalforge.app.market.PriceTick;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MarketSimulatorTest {

    private MarketSimulator simulator;

    @BeforeEach
    void setUp() {
        simulator = new MarketSimulator(true);
        simulator.init();
    }

    @Test
    void shouldInitializeWithDefaultTickers() {
        Map<String, PriceTick> prices = simulator.getAllPrices();
        assertFalse(prices.isEmpty());
        assertTrue(prices.containsKey("AAPL"));
        assertTrue(prices.containsKey("TSLA"));
        assertTrue(prices.containsKey("NVDA"));

        PriceTick aapl = simulator.getPrice("AAPL");
        assertNotNull(aapl);
        assertEquals("AAPL", aapl.ticker());
        assertTrue(aapl.price() > 0);
    }

    @Test
    void shouldRegisterNewTickerDynamically() {
        PriceTick custom = simulator.getPrice("XYZ");
        assertNotNull(custom);
        assertEquals("XYZ", custom.ticker());
        assertEquals(100.0, custom.price());
    }

    @Test
    void shouldRejectInvalidTickers() {
        assertThrows(IllegalArgumentException.class, () -> simulator.getPrice(null));
        assertThrows(IllegalArgumentException.class, () -> simulator.getPrice(""));
        assertThrows(IllegalArgumentException.class, () -> simulator.getPrice("   "));
        assertThrows(IllegalArgumentException.class, () -> simulator.getPrice("INVALID123"));
        assertThrows(IllegalArgumentException.class, () -> simulator.getPrice("TOOLONGTICKER"));
        assertThrows(IllegalArgumentException.class, () -> simulator.getPrice("$$$"));
    }

    @Test
    void shouldUpdatePricesOnTick() {
        PriceTick initial = simulator.getPrice("AAPL");
        double initialPrice = initial.price();

        // Perform tick simulation
        simulator.tick();

        PriceTick updated = simulator.getPrice("AAPL");
        assertNotNull(updated);
        assertTrue(updated.price() > 0);
        assertNotNull(updated.direction());
    }
}
