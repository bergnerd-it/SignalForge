package com.bergnerd.signalforge.app.market;

import java.util.Map;

public interface MarketDataSource {
    PriceTick getPrice(String ticker);
    Map<String, PriceTick> getAllPrices();
    void registerTicker(String ticker);
}
