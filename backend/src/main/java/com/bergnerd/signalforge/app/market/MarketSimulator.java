package com.bergnerd.signalforge.app.market;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@Slf4j
public class MarketSimulator implements MarketDataSource {

    private static final Pattern TICKER_PATTERN = Pattern.compile("^[A-Z]{1,5}$");

    private final Random random;
    private final ConcurrentHashMap<String, TickerState> tickers = new ConcurrentHashMap<>();

    private static class TickerState {
        double currentPrice;
        double previousPrice;
        double initialPrice;
        double volatility;
        double drift;
        double marketBeta;
        String lastUpdated;

        TickerState(double price, double volatility, double drift, double beta) {
            this.currentPrice = price;
            this.previousPrice = price;
            this.initialPrice = price;
            this.volatility = volatility;
            this.drift = drift;
            this.marketBeta = beta;
            this.lastUpdated = Instant.now().toString();
        }
    }

    public MarketSimulator(@Value("${signalforge.llm.mock:false}") boolean mockMode) {
        if (mockMode) {
            this.random = new Random(42);
        } else {
            this.random = new Random();
        }
    }

    @PostConstruct
    public void init() {
        initTicker("AAPL", 190.50, 0.20, 0.05, 1.1);
        initTicker("GOOGL", 175.25, 0.22, 0.05, 1.05);
        initTicker("MSFT", 420.80, 0.18, 0.06, 0.95);
        initTicker("AMZN", 185.40, 0.25, 0.07, 1.2);
        initTicker("TSLA", 215.10, 0.45, 0.08, 1.8);
        initTicker("NVDA", 125.60, 0.40, 0.12, 1.7);
        initTicker("META", 505.30, 0.28, 0.07, 1.3);
        initTicker("JPM", 198.70, 0.16, 0.04, 0.8);
        initTicker("V", 275.90, 0.15, 0.04, 0.75);
        initTicker("NFLX", 645.50, 0.30, 0.06, 1.25);
        log.info("Market simulator initialized with {} seed tickers", tickers.size());
    }

    private void initTicker(String symbol, double price, double vol, double drift, double beta) {
        tickers.put(symbol.toUpperCase(), new TickerState(price, vol, drift, beta));
    }

    public static boolean isValidTicker(String ticker) {
        if (ticker == null) {
            return false;
        }
        String symbol = ticker.trim().toUpperCase();
        return TICKER_PATTERN.matcher(symbol).matches();
    }

    @Override
    public void registerTicker(String ticker) {
        if (!isValidTicker(ticker)) {
            throw new IllegalArgumentException("Invalid ticker symbol: " + ticker);
        }
        String symbol = ticker.trim().toUpperCase();
        tickers.computeIfAbsent(symbol, s -> {
            log.info("Registered new ticker dynamically in simulator: {}", s);
            return new TickerState(100.0, 0.25, 0.05, 1.0);
        });
    }

    @Scheduled(fixedRate = 500)
    public void tick() {
        if (tickers.isEmpty()) {
            return;
        }

        // Common market factor shock for correlated movement
        double marketShock = random.nextGaussian();
        // Time delta ~500ms scaled to annual trading days (252 days * 6.5 hours * 3600 seconds * 2 ticks/sec)
        double dt = 1.0 / (252.0 * 6.5 * 7200.0);
        String now = Instant.now().toString();

        for (Map.Entry<String, TickerState> entry : tickers.entrySet()) {
            TickerState state = entry.getValue();
            state.previousPrice = state.currentPrice;

            double idiosyncraticShock = random.nextGaussian();
            // Combined shock with beta weighting
            double combinedShock = (0.6 * state.marketBeta * marketShock) + (0.8 * idiosyncraticShock);

            // Geometric Brownian Motion step: S_t * exp((mu - 0.5 * sigma^2)*dt + sigma * sqrt(dt) * Z)
            double driftTerm = (state.drift - 0.5 * state.volatility * state.volatility) * dt;
            double diffusionTerm = state.volatility * Math.sqrt(dt) * combinedShock;
            double newPrice = state.currentPrice * Math.exp(driftTerm + diffusionTerm);

            // Occasional random event (0.5% chance per tick) -> jump move +- 2% to 5%
            if (random.nextDouble() < 0.005) {
                double jump = (random.nextBoolean() ? 1.0 : -1.0) * (0.02 + random.nextDouble() * 0.03);
                newPrice = newPrice * (1.0 + jump);
                log.debug("Market event jump on {}: {}%", entry.getKey(), round(jump * 100));
            }

            // Ensure price stays positive
            if (newPrice < 0.01) {
                newPrice = 0.01;
            }

            state.currentPrice = round(newPrice);
            state.lastUpdated = now;
        }
    }

    @Override
    public PriceTick getPrice(String ticker) {
        if (!isValidTicker(ticker)) {
            throw new IllegalArgumentException("Invalid ticker symbol: " + ticker);
        }
        String symbol = ticker.trim().toUpperCase();
        registerTicker(symbol);
        TickerState state = tickers.get(symbol);
        return toPriceTick(symbol, state);
    }

    @Override
    public Map<String, PriceTick> getAllPrices() {
        Map<String, PriceTick> map = new ConcurrentHashMap<>();
        for (Map.Entry<String, TickerState> entry : tickers.entrySet()) {
            map.put(entry.getKey(), toPriceTick(entry.getKey(), entry.getValue()));
        }
        return map;
    }

    private PriceTick toPriceTick(String symbol, TickerState state) {
        double current = state.currentPrice;
        double prev = state.previousPrice;
        double change = round(current - prev);
        double changePercent = prev > 0 ? round(((current - prev) / prev) * 100.0) : 0.0;
        String direction = current > prev ? "up" : (current < prev ? "down" : "flat");
        return new PriceTick(symbol, current, prev, change, changePercent, state.lastUpdated, direction);
    }

    private double round(double val) {
        return BigDecimal.valueOf(val).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}
