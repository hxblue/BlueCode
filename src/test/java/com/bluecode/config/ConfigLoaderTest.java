package com.bluecode.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigLoaderTest {
    @TempDir
    Path tempDir;

    @Test
    void loadsValidProviders() throws IOException {
        Path config = write("""
            providers:
              - name: Test
                protocol: openai-compat
                base_url: http://localhost:8000/v1
                api_key: test-key
                model: test-model
                thinking: false
            """);

        AppConfig appConfig = ConfigLoader.load(config.toString());

        assertEquals(1, appConfig.getProviders().size());
        ProviderConfig provider = appConfig.getProviders().getFirst();
        assertEquals("Test", provider.getName());
        assertEquals("openai-compat", provider.getProtocol());
        assertEquals("http://localhost:8000/v1", provider.getBaseUrl());
        assertEquals(200000, provider.effectiveContextWindow());
    }

    @Test
    void loadsContextWindowWhenConfigured() throws IOException {
        Path config = write("""
            providers:
              - name: Test
                protocol: anthropic
                api_key: test-key
                model: test-model
                context_window: 80000
            """);

        ProviderConfig provider = ConfigLoader.load(config.toString()).getProviders().getFirst();

        assertEquals(80000, provider.getContextWindow());
        assertEquals(80000, provider.effectiveContextWindow());
    }

    @Test
    void usesOpenAiDefaultWhenContextWindowIsZero() throws IOException {
        Path config = write("""
            providers:
              - name: Test
                protocol: openai
                api_key: test-key
                model: test-model
                context_window: 0
            """);

        ProviderConfig provider = ConfigLoader.load(config.toString()).getProviders().getFirst();

        assertEquals(128000, provider.effectiveContextWindow());
    }

    @Test
    void rejectsMissingApiKey() throws IOException {
        Path config = write("""
            providers:
              - name: Test
                protocol: openai
                model: test-model
            """);

        ConfigException error = assertThrows(ConfigException.class, () -> ConfigLoader.load(config.toString()));
        assertTrue(error.getMessage().contains("api_key"));
    }

    @Test
    void rejectsUnsupportedProtocol() throws IOException {
        Path config = write("""
            providers:
              - name: Test
                protocol: unknown
                api_key: test-key
                model: test-model
            """);

        ConfigException error = assertThrows(ConfigException.class, () -> ConfigLoader.load(config.toString()));
        assertTrue(error.getMessage().contains("protocol"));
    }

    private Path write(String content) throws IOException {
        Path path = tempDir.resolve("config.yaml");
        Files.writeString(path, content);
        return path;
    }
}
