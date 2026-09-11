package com.bergnerd.signalforge.app;

import com.bergnerd.signalforge.app.chat.ChatConfig;
import com.bergnerd.signalforge.app.chat.MockLlmClient;
import com.bergnerd.signalforge.app.chat.OpenAiCompatibleLlmClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationLocalConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ChatConfig.class);

    @Test
    void shouldLoadDefaultConfigWhenNoProfileOrLocalOverrides() {
        contextRunner
                .withPropertyValues(
                        "signalforge.llm.provider=openai",
                        "signalforge.llm.mock=false"
                )
                .run(context -> {
                    // With no API key, falls back to MockLlmClient
                    assertThat(context).hasSingleBean(MockLlmClient.class);
                });
    }

    @Test
    void shouldBindLocalProfileYamlProperties() {
        contextRunner
                .withPropertyValues(
                        "spring.profiles.active=local",
                        "signalforge.llm.provider=groq",
                        "signalforge.llm.api-key=test-groq-key",
                        "signalforge.llm.model=llama-3.3-70b-versatile",
                        "signalforge.massive.api-key=test-massive-key"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(OpenAiCompatibleLlmClient.class);
                    OpenAiCompatibleLlmClient client = context.getBean(OpenAiCompatibleLlmClient.class);
                    assertThat(client.getProvider()).isEqualTo("groq");
                    assertThat(client.getApiKey()).isEqualTo("test-groq-key");
                    assertThat(client.getModel()).isEqualTo("llama-3.3-70b-versatile");
                    assertThat(context.getEnvironment().getProperty("signalforge.massive.api-key"))
                            .isEqualTo("test-massive-key");
                });
    }

    @Test
    void shouldFallbackToLegacyFinallyProperties() {
        contextRunner
                .withPropertyValues(
                        "finally.llm.provider=groq",
                        "finally.llm.api-key=test-legacy-key",
                        "finally.llm.model=llama-3.3-70b-versatile"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(OpenAiCompatibleLlmClient.class);
                    OpenAiCompatibleLlmClient client = context.getBean(OpenAiCompatibleLlmClient.class);
                    assertThat(client.getApiKey()).isEqualTo("test-legacy-key");
                });
    }

    @Test
    void shouldActivateMockModeWhenLocalProfileEnablesMock() {
        contextRunner
                .withPropertyValues(
                        "spring.profiles.active=local",
                        "signalforge.llm.provider=openai",
                        "signalforge.llm.api-key=dummy-key",
                        "signalforge.llm.mock=true"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(MockLlmClient.class);
                });
    }
}
