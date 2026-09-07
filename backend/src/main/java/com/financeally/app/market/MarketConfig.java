package com.financeally.app.market;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MarketConfig {

    @Bean
    public MarketDataSource marketDataSource(
            @Value("${finally.massive.api-key:}") String massiveApiKey,
            @Value("${finally.llm.mock:false}") boolean mockMode
    ) {
        if (massiveApiKey != null && !massiveApiKey.trim().isEmpty()) {
            return new MassiveMarketClient(massiveApiKey.trim());
        }
        return new MarketSimulator(mockMode);
    }
}
