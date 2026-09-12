package com.bergnerd.signalforge.app.market;

public record PriceTick(
        String ticker,
        double price,
        double previousPrice,
        double change,
        double changePercent,
        String timestamp,
        String direction,
        String source,
        String fetchTime,
        boolean isExecutable
) {
    public PriceTick(
            String ticker,
            double price,
            double previousPrice,
            double change,
            double changePercent,
            String timestamp,
            String direction
    ) {
        this(ticker, price, previousPrice, change, changePercent, timestamp, direction, "SIMULATOR", timestamp, true);
    }
}
