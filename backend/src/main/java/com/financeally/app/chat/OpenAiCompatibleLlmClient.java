package com.financeally.app.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public class OpenAiCompatibleLlmClient implements LlmClient {

    private static final String PROVIDER_OPENAI = "openai";
    private static final String FIELD_CONTENT = "content";
    private static final String FIELD_MESSAGE = "message";
    private static final String FIELD_TRADES = "trades";
    private static final String FIELD_WATCHLIST_CHANGES = "watchlist_changes";
    private static final String FIELD_TICKER = "ticker";
    private static final String TYPE_OBJECT = "object";
    private static final String TYPE_STRING = "string";
    private static final String PROP_PROPERTIES = "properties";
    private static final String PROP_REQUIRED = "required";
    private static final String PROP_ADDITIONAL_PROPERTIES = "additionalProperties";

    @Getter
    private final String provider;
    @Getter
    private final String baseUrl;
    @Getter
    private final String apiKey;
    @Getter
    private final String model;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public OpenAiCompatibleLlmClient(String provider, String baseUrl, String apiKey, String model) {
        this(provider, baseUrl, apiKey, model, null, new ObjectMapper());
    }

    public OpenAiCompatibleLlmClient(
            String provider,
            String baseUrl,
            String apiKey,
            String model,
            RestClient customRestClient,
            ObjectMapper customObjectMapper
    ) {
        this.provider = (provider == null || provider.isBlank()) ? PROVIDER_OPENAI : provider.trim().toLowerCase();
        this.baseUrl = resolveBaseUrl(this.provider, baseUrl);
        this.apiKey = (apiKey == null) ? "" : apiKey.trim();
        this.model = resolveModel(this.provider, model);
        this.objectMapper = (customObjectMapper != null) ? customObjectMapper : new ObjectMapper();

        if (customRestClient != null) {
            this.restClient = customRestClient;
        } else {
            RestClient.Builder builder = RestClient.builder()
                    .baseUrl(this.baseUrl)
                    .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);

            if (!this.apiKey.isBlank()) {
                builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + this.apiKey);
            }

            this.restClient = builder.build();
        }
    }

    private static String resolveBaseUrl(String provider, String baseUrl) {
        if (baseUrl != null && !baseUrl.isBlank()) {
            return baseUrl.trim();
        }
        return switch (provider) {
            case "ollama" -> "http://localhost:11434/v1";
            case "groq" -> "https://api.groq.com/openai/v1";
            case PROVIDER_OPENAI -> "https://api.openai.com/v1";
            default -> "https://api.openai.com/v1";
        };
    }

    private static String resolveModel(String provider, String model) {
        if (model != null && !model.isBlank()) {
            return model.trim();
        }
        return switch (provider) {
            case "ollama" -> "llama3.1";
            case "groq" -> "llama-3.3-70b-versatile";
            case PROVIDER_OPENAI -> "gpt-4o-mini";
            default -> "gpt-4o-mini";
        };
    }

    @Override
    public LlmStructuredResponse generateResponse(String systemPrompt, List<ChatMessageRecord> history, String userMessage) {
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", FIELD_CONTENT, systemPrompt));

        for (ChatMessageRecord msg : history) {
            messages.add(Map.of("role", msg.role(), FIELD_CONTENT, msg.content()));
        }

        messages.add(Map.of("role", "user", FIELD_CONTENT, userMessage));

        Map<String, Object> schema = createJsonSchema();
        Map<String, Object> responseFormat = Map.of(
                "type", "json_schema",
                "json_schema", Map.of(
                        "name", "trading_assistant_response",
                        "strict", true,
                        "schema", schema
                )
        );

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", this.model);
        requestBody.put("messages", messages);
        requestBody.put("response_format", responseFormat);

        try {
            String rawJson = restClient.post()
                    .uri("/chat/completions")
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(rawJson);
            JsonNode choices = root.path("choices");
            if (choices.isArray() && !choices.isEmpty()) {
                String content = choices.get(0).path(FIELD_MESSAGE).path(FIELD_CONTENT).asText();
                return parseStructuredResponse(content);
            } else {
                log.warn("Empty choices returned from LLM provider {}", provider);
                return new LlmStructuredResponse("I apologize, but I received an empty response from the AI model.", List.of(), List.of());
            }
        } catch (Exception e) {
            log.error("Failed to call LLM API (provider={}): {}", provider, e.getMessage(), e);
            return new LlmStructuredResponse("Error communicating with AI model: " + e.getMessage(), List.of(), List.of());
        }
    }

    private LlmStructuredResponse parseStructuredResponse(String content) {
        try {
            return objectMapper.readValue(content, LlmStructuredResponse.class);
        } catch (Exception e) {
            log.warn("Failed direct Jackson parse of LLM response: {}. Content: {}", e.getMessage(), content);
            int start = content.indexOf('{');
            int end = content.lastIndexOf('}');
            if (start >= 0 && end > start) {
                try {
                    String sub = content.substring(start, end + 1);
                    return objectMapper.readValue(sub, LlmStructuredResponse.class);
                } catch (Exception ignored) {
                    //ignored
                }
            }
            return new LlmStructuredResponse(content, List.of(), List.of());
        }
    }

    private Map<String, Object> createJsonSchema() {
        Map<String, Object> tradeProps = Map.of(
                FIELD_TICKER, Map.of("type", TYPE_STRING),
                "side", Map.of("type", TYPE_STRING, "enum", List.of("buy", "sell")),
                "quantity", Map.of("type", "number")
        );
        Map<String, Object> tradeItem = Map.of(
                "type", TYPE_OBJECT,
                PROP_PROPERTIES, tradeProps,
                PROP_REQUIRED, List.of(FIELD_TICKER, "side", "quantity"),
                PROP_ADDITIONAL_PROPERTIES, false
        );

        Map<String, Object> watchlistProps = Map.of(
                FIELD_TICKER, Map.of("type", TYPE_STRING),
                "action", Map.of("type", TYPE_STRING, "enum", List.of("add", "remove"))
        );
        Map<String, Object> watchlistItem = Map.of(
                "type", TYPE_OBJECT,
                PROP_PROPERTIES, watchlistProps,
                PROP_REQUIRED, List.of(FIELD_TICKER, "action"),
                PROP_ADDITIONAL_PROPERTIES, false
        );

        Map<String, Object> properties = Map.of(
                FIELD_MESSAGE, Map.of("type", TYPE_STRING),
                FIELD_TRADES, Map.of("type", "array", "items", tradeItem),
                FIELD_WATCHLIST_CHANGES, Map.of("type", "array", "items", watchlistItem)
        );

        return Map.of(
                "type", TYPE_OBJECT,
                PROP_PROPERTIES, properties,
                PROP_REQUIRED, List.of(FIELD_MESSAGE, FIELD_TRADES, FIELD_WATCHLIST_CHANGES),
                PROP_ADDITIONAL_PROPERTIES, false
        );
    }
}
