package com.bergnerd.signalforge.app;

import com.bergnerd.signalforge.app.chat.ChatConfig;
import com.bergnerd.signalforge.app.chat.MockLlmClient;
import com.bergnerd.signalforge.app.chat.OpenAiCompatibleLlmClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationLocalConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ChatConfig.class);

    @Test
    void shouldStartFromShippedYamlWithoutPrivateLocalConfig() throws Exception {
        Path database = Files.createTempFile("signalforge-yaml-", ".db");
        try (ConfigurableApplicationContext context = start(database, "--spring.config.import=")) {
            assertThat(context.getEnvironment().getProperty("spring.application.name"))
                    .isEqualTo("signalforge-backend");
            assertThat(context.getEnvironment().getProperty("server.address")).isEqualTo("127.0.0.1");
            assertThat(context.getEnvironment().getProperty("signalforge.llm.model")).isEqualTo("gpt-4o-mini");
        } finally {
            TemporarySqliteInitializer.deleteDatabaseFiles(database);
        }
    }

    @Test
    void shouldApplySafeTemporaryYamlOverride() throws Exception {
        Path database = Files.createTempFile("signalforge-yaml-override-", ".db");
        Path override = Files.createTempFile("signalforge-local-", ".yml");
        Files.writeString(override, "signalforge:\n  llm:\n    model: safe-test-model\n    mock: true\n");
        try (ConfigurableApplicationContext context = start(
                database, "--spring.config.import=optional:file:" + override)) {
            assertThat(context.getEnvironment().getProperty("signalforge.llm.model"))
                    .isEqualTo("safe-test-model");
            assertThat(context.getEnvironment().getProperty("signalforge.llm.mock")).isEqualTo("true");
        } finally {
            Files.deleteIfExists(override);
            TemporarySqliteInitializer.deleteDatabaseFiles(database);
        }
    }

    private ConfigurableApplicationContext start(Path database, String configImport) {
        return new SpringApplicationBuilder(SignalForgeApplication.class)
                .web(WebApplicationType.NONE)
                .run(
                        "--spring.datasource.url=jdbc:sqlite:" + database,
                        configImport,
                        "--signalforge.llm.mock=true",
                        "--signalforge.massive.api-key="
                );
    }

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
                        "signalforge.llm.provider=groq",
                        "signalforge.llm.api-key=test-legacy-key",
                        "signalforge.llm.model=llama-3.3-70b-versatile"
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
