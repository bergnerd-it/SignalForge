package com.bergnerd.signalforge.app.chat;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
public class ChatConfig {

    @Bean
    public LlmClient llmClient(
            @Value("${signalforge.llm.provider:${finally.llm.provider:openai}}") String provider,
            @Value("${signalforge.llm.base-url:${finally.llm.base-url:}}") String baseUrl,
            @Value("${signalforge.llm.api-key:${finally.llm.api-key:}}") String apiKey,
            @Value("${signalforge.llm.model:${finally.llm.model:}}") String model,
            @Value("${signalforge.llm.mock:${finally.llm.mock:false}}") boolean mockMode
    ) {
        String effectiveProvider = (provider == null || provider.isBlank()) ? "openai" : provider.trim().toLowerCase();

        if (mockMode || "mock".equals(effectiveProvider)) {
            log.info("Using MockLlmClient for chat assistant");
            return new MockLlmClient();
        }

        String effectiveApiKey = (apiKey != null && !apiKey.isBlank()) ? apiKey.trim() : "";
        String effectiveModel = (model != null && !model.isBlank()) ? model.trim() : "";

        boolean requiresApiKey = !"ollama".equalsIgnoreCase(effectiveProvider) && !"custom".equalsIgnoreCase(effectiveProvider);
        if (requiresApiKey && effectiveApiKey.isBlank()) {
            log.warn("No API key configured for LLM provider '{}'. Falling back to MockLlmClient.", effectiveProvider);
            return new MockLlmClient();
        }

        log.info("Configuring OpenAiCompatibleLlmClient for provider '{}'", effectiveProvider);
        return new OpenAiCompatibleLlmClient(effectiveProvider, baseUrl, effectiveApiKey, effectiveModel);
    }
}
