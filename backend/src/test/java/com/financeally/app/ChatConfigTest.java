package com.financeally.app;

import com.financeally.app.chat.ChatConfig;
import com.financeally.app.chat.LlmClient;
import com.financeally.app.chat.MockLlmClient;
import com.financeally.app.chat.OpenAiCompatibleLlmClient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChatConfigTest {

    private final ChatConfig chatConfig = new ChatConfig();

    @Test
    void shouldReturnMockLlmClientWhenMockModeIsTrue() {
        LlmClient client = chatConfig.llmClient(
                "openai",
                "",
                "test-api-key",
                "gpt-4o-mini",
                true
        );
        assertInstanceOf(MockLlmClient.class, client);
    }

    @Test
    void shouldReturnMockLlmClientWhenProviderIsMock() {
        LlmClient client = chatConfig.llmClient(
                "mock",
                "",
                "test-api-key",
                "gpt-4o-mini",
                false
        );
        assertInstanceOf(MockLlmClient.class, client);
    }

    @Test
    void shouldReturnMockLlmClientWhenApiKeyIsMissingForCloudProvider() {
        LlmClient client = chatConfig.llmClient(
                "openai",
                "",
                "",
                "gpt-4o-mini",
                false
        );
        assertInstanceOf(MockLlmClient.class, client);
    }

    @Test
    void shouldReturnOpenAiCompatibleClientForOllamaWithoutApiKey() {
        LlmClient client = chatConfig.llmClient(
                "ollama",
                "",
                "",
                "",
                false
        );
        assertInstanceOf(OpenAiCompatibleLlmClient.class, client);
        OpenAiCompatibleLlmClient openAiClient = (OpenAiCompatibleLlmClient) client;
        assertEquals("ollama", openAiClient.getProvider());
        assertEquals("http://localhost:11434/v1", openAiClient.getBaseUrl());
        assertEquals("llama3.1", openAiClient.getModel());
    }

    @Test
    void shouldReturnOpenAiCompatibleClientForOpenAiWithApiKey() {
        LlmClient client = chatConfig.llmClient(
                "openai",
                "",
                "sk-proj-test",
                "gpt-4o-mini",
                false
        );
        assertInstanceOf(OpenAiCompatibleLlmClient.class, client);
        OpenAiCompatibleLlmClient openAiClient = (OpenAiCompatibleLlmClient) client;
        assertEquals("openai", openAiClient.getProvider());
        assertEquals("https://api.openai.com/v1", openAiClient.getBaseUrl());
        assertEquals("gpt-4o-mini", openAiClient.getModel());
        assertEquals("sk-proj-test", openAiClient.getApiKey());
    }

    @Test
    void shouldReturnOpenAiCompatibleClientForGroqWithApiKey() {
        LlmClient client = chatConfig.llmClient(
                "groq",
                "",
                "gsk-test",
                "",
                false
        );
        assertInstanceOf(OpenAiCompatibleLlmClient.class, client);
        OpenAiCompatibleLlmClient openAiClient = (OpenAiCompatibleLlmClient) client;
        assertEquals("groq", openAiClient.getProvider());
        assertEquals("https://api.groq.com/openai/v1", openAiClient.getBaseUrl());
        assertEquals("llama-3.3-70b-versatile", openAiClient.getModel());
        assertEquals("gsk-test", openAiClient.getApiKey());
    }

    @Test
    void shouldDefaultToOpenAiWhenProviderIsBlank() {
        LlmClient client = chatConfig.llmClient(
                "",
                "",
                "sk-test-key",
                "",
                false
        );
        assertInstanceOf(OpenAiCompatibleLlmClient.class, client);
        OpenAiCompatibleLlmClient openAiClient = (OpenAiCompatibleLlmClient) client;
        assertEquals("openai", openAiClient.getProvider());
        assertEquals("https://api.openai.com/v1", openAiClient.getBaseUrl());
        assertEquals("gpt-4o-mini", openAiClient.getModel());
        assertEquals("sk-test-key", openAiClient.getApiKey());
    }
}
