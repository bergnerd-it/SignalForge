package com.bergnerd.signalforge.app.market;

public record PriceTick(
        String ticker,
        double price,
        double previousPrice,
        double change,
        double changePercent,
        String timestamp,
        String direction
) {}
