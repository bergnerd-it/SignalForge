package com.bergnerd.signalforge.app.market;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class MassiveMarketClient implements MarketDataSource {

    private final String apiKey;
    private final RestClient restClient;
    private final Set<String> watchedTickers = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, PriceTick> priceCache = new ConcurrentHashMap<>();

    public MassiveMarketClient(String apiKey) {
        this.apiKey = apiKey;
        this.restClient = RestClient.builder()
                .baseUrl("https://api.polygon.io")
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .build();
    }

    @Override
    public void registerTicker(String ticker) {
        String symbol = ticker.trim().toUpperCase();
        watchedTickers.add(symbol);
    }

    @Override
    public PriceTick getPrice(String ticker) {
        String symbol = ticker.trim().toUpperCase();
        registerTicker(symbol);
        PriceTick tick = priceCache.get(symbol);
        if (tick == null) {
            throw new MarketExceptions.QuoteUnavailableException("Market quote unavailable for ticker: " + symbol);
        }
        return tick;
    }

    @Override
    public Map<String, PriceTick> getAllPrices() {
        return priceCache;
    }

    @Scheduled(fixedRate = 15000)
    public void poll() {
        if (apiKey == null || apiKey.isBlank() || watchedTickers.isEmpty()) {
            return;
        }

        try {
            String tickersParam = String.join(",", watchedTickers);
            Map response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/v2/snapshot/locale/us/markets/stocks/tickers")
                            .queryParam("tickers", tickersParam)
                            .build())
                    .retrieve()
                    .body(Map.class);

            if (response != null && response.containsKey("tickers")) {
                var tickerList = (java.util.List<?>) response.get("tickers");
                if (tickerList != null) {
                    String now = Instant.now().toString();
                    for (Object item : tickerList) {
                        if (item instanceof Map<?, ?> tickerData) {
                            String symbol = (String) tickerData.get("ticker");
                            if (symbol == null) {
                                continue;
                            }
                            Map<?, ?> day = (Map<?, ?>) tickerData.get("day");
                            Map<?, ?> prevDay = (Map<?, ?>) tickerData.get("prevDay");
                            Map<?, ?> min = (Map<?, ?>) tickerData.get("min");

                            double current = 0.0;
                            double prev = 0.0;

                            if (day != null && day.get("c") != null) {
                                current = ((Number) day.get("c")).doubleValue();
                            } else if (min != null && min.get("c") != null) {
                                current = ((Number) min.get("c")).doubleValue();
                            }

                            if (prevDay != null && prevDay.get("c") != null) {
                                prev = ((Number) prevDay.get("c")).doubleValue();
                            } else if (day != null && day.get("o") != null) {
                                prev = ((Number) day.get("o")).doubleValue();
                            }

                            if (current > 0) {
                                double roundedCurrent = round(current);
                                double roundedPrev = prev > 0 ? round(prev) : roundedCurrent;
                                double change = round(roundedCurrent - roundedPrev);
                                double changePercent = roundedPrev > 0 ? round(((roundedCurrent - roundedPrev) / roundedPrev) * 100.0) : 0.0;
                                String direction = roundedCurrent > roundedPrev ? "up" : (roundedCurrent < roundedPrev ? "down" : "flat");

                                PriceTick tick = new PriceTick(
                                        symbol, roundedCurrent, roundedPrev, change, changePercent, now, direction,
                                        "POLYGON_PROVIDER", now, true
                                );
                                priceCache.put(symbol, tick);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to bulk poll market data from Massive: {}", e.getMessage());
        }
    }

    private double round(double val) {
        return BigDecimal.valueOf(val).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}
