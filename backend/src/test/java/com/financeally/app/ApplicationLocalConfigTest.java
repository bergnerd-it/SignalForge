package com.financeally.app;

import com.financeally.app.chat.ChatConfig;
import com.financeally.app.chat.MockLlmClient;
import com.financeally.app.chat.OpenAiCompatibleLlmClient;
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
                        "finally.llm.provider=openai",
                        "finally.llm.mock=false"
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
                        "finally.llm.provider=groq",
                        "finally.llm.api-key=test-groq-key",
                        "finally.llm.model=llama-3.3-70b-versatile",
                        "finally.massive.api-key=test-massive-key"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(OpenAiCompatibleLlmClient.class);
                    OpenAiCompatibleLlmClient client = context.getBean(OpenAiCompatibleLlmClient.class);
                    assertThat(client.getProvider()).isEqualTo("groq");
                    assertThat(client.getApiKey()).isEqualTo("test-groq-key");
                    assertThat(client.getModel()).isEqualTo("llama-3.3-70b-versatile");
                    assertThat(context.getEnvironment().getProperty("finally.massive.api-key"))
                            .isEqualTo("test-massive-key");
                });
    }

    @Test
    void shouldActivateMockModeWhenLocalProfileEnablesMock() {
        contextRunner
                .withPropertyValues(
                        "spring.profiles.active=local",
                        "finally.llm.provider=openai",
                        "finally.llm.api-key=dummy-key",
                        "finally.llm.mock=true"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(MockLlmClient.class);
                });
    }
}
