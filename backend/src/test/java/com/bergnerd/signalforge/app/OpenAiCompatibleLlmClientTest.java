package com.bergnerd.signalforge.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.bergnerd.signalforge.app.chat.LlmStructuredResponse;
import com.bergnerd.signalforge.app.chat.OpenAiCompatibleLlmClient;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OpenAiCompatibleLlmClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldResolveDefaultBaseUrlAndModelForOpenAi() {
        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient("openai", "", "test-key", "");
        assertEquals("openai", client.getProvider());
        assertEquals("https://api.openai.com/v1", client.getBaseUrl());
        assertEquals("gpt-4o-mini", client.getModel());
        assertEquals("test-key", client.getApiKey());
    }

    @Test
    void shouldResolveDefaultBaseUrlAndModelForOllama() {
        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient("ollama", null, null, null);
        assertEquals("ollama", client.getProvider());
        assertEquals("http://localhost:11434/v1", client.getBaseUrl());
        assertEquals("llama3.1", client.getModel());
        assertEquals("", client.getApiKey());
    }

    @Test
    void shouldResolveDefaultBaseUrlAndModelForGroq() {
        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient("groq", null, "g-key", null);
        assertEquals("groq", client.getProvider());
        assertEquals("https://api.groq.com/openai/v1", client.getBaseUrl());
        assertEquals("llama-3.3-70b-versatile", client.getModel());
    }

    @Test
    void shouldUseCustomBaseUrlAndModelWhenProvided() {
        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient("custom", "http://my-llm:8080/v1", "custom-key", "my-custom-model");
        assertEquals("custom", client.getProvider());
        assertEquals("http://my-llm:8080/v1", client.getBaseUrl());
        assertEquals("my-custom-model", client.getModel());
    }

    @Test
    void shouldSendPostRequestAndParseJsonSchemaResponse() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.openai.com/v1");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();

        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(
                "openai",
                "https://api.openai.com/v1",
                "sk-test",
                "gpt-4o",
                restClient,
                objectMapper
        );

        String mockResponseBody = """
                {
                  "choices": [
                    {
                      "message": {
                        "content": "{\\"message\\":\\"Executed purchase of 10 MSFT shares.\\",\\"trades\\\":[{\\"ticker\\\":\\"MSFT\\",\\"side\\\":\\"buy\\",\\"quantity\\\":10.0}],\\"watchlist_changes\\\":[{\\"ticker\\\":\\"MSFT\\",\\"action\\\":\\"add\\"}]}"
                      }
                    }
                  ]
                }
                """;

        server.expect(requestTo("https://api.openai.com/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.model").value("gpt-4o"))
                .andExpect(jsonPath("$.messages[0].role").value("system"))
                .andExpect(jsonPath("$.messages[1].role").value("user"))
                .andExpect(jsonPath("$.response_format.type").value("json_schema"))
                .andExpect(jsonPath("$.response_format.json_schema.strict").value(true))
                .andExpect(jsonPath("$.response_format.json_schema.schema.required[0]").value("message"))
                .andExpect(jsonPath("$.response_format.json_schema.schema.required[1]").value("trades"))
                .andExpect(jsonPath("$.response_format.json_schema.schema.required[2]").value("watchlist_changes"))
                .andExpect(jsonPath("$.response_format.json_schema.schema.additionalProperties").value(false))
                .andRespond(withSuccess(mockResponseBody, MediaType.APPLICATION_JSON));

        LlmStructuredResponse response = client.generateResponse(
                "You are an assistant",
                List.of(),
                "Buy 10 MSFT"
        );

        assertNotNull(response);
        assertEquals("Executed purchase of 10 MSFT shares.", response.message());
        assertEquals(1, response.trades().size());
        assertEquals("MSFT", response.trades().get(0).ticker());
        assertEquals("buy", response.trades().get(0).side());
        assertEquals(10.0, response.trades().get(0).quantity());
        assertEquals(1, response.safeWatchlistChanges().size());
        assertEquals("MSFT", response.safeWatchlistChanges().get(0).ticker());

        server.verify();
    }

    @Test
    void shouldDefaultToOpenAiWhenProviderIsNull() {
        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(null, null, "test-key", null);
        assertEquals("openai", client.getProvider());
        assertEquals("https://api.openai.com/v1", client.getBaseUrl());
        assertEquals("gpt-4o-mini", client.getModel());
        assertEquals("test-key", client.getApiKey());
    }

    @Test
    void shouldHandleApiErrorGracefully() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.openai.com/v1");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();

        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(
                "openai",
                "https://api.openai.com/v1",
                "sk-test",
                "gpt-4o",
                restClient,
                objectMapper
        );

        server.expect(requestTo("https://api.openai.com/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withServerError());

        LlmStructuredResponse response = client.generateResponse("System", List.of(), "Hi");
        assertNotNull(response);
        assertTrue(response.message().contains("Error communicating with AI model"));
        assertTrue(response.trades().isEmpty());
        assertTrue(response.safeWatchlistChanges().isEmpty());

        server.verify();
    }

    @Test
    void shouldExtractEmbeddedJsonWhenContentContainsSurroundingText() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.openai.com/v1");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();

        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(
                "openai",
                "https://api.openai.com/v1",
                "sk-test",
                "gpt-4o",
                restClient,
                objectMapper
        );

        String mockResponseBody = """
                {
                  "choices": [
                    {
                      "message": {
                        "content": "Here is the response: {\\"message\\":\\"Extracted successfully\\",\\"trades\\\":[],\\"watchlist_changes\\\":[]} Thank you!"
                      }
                    }
                  ]
                }
                """;

        server.expect(requestTo("https://api.openai.com/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(mockResponseBody, MediaType.APPLICATION_JSON));

        LlmStructuredResponse response = client.generateResponse("System", List.of(), "Hi");
        assertNotNull(response);
        assertEquals("Extracted successfully", response.message());

        server.verify();
    }
}
