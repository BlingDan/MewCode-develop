package com.mewcode.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ConfigLoaderTest {

    @TempDir Path tempDir;

    @Test
    void loadsProvidersAndDefaultsThinkingToFalse() throws Exception {
        Path config = write("""
                providers:
                  - name: first
                    protocol: anthropic
                    model: claude-test
                    api_key: secret-one
                  - name: second
                    protocol: openai
                    model: gpt-test
                    base_url: ""
                    api_key: secret-two
                    thinking: true
                """);

        AppConfig loaded = ConfigLoader.load(config);

        assertEquals(2, loaded.getProviders().size());
        assertFalse(loaded.getProviders().get(0).isThinking());
        assertTrue(loaded.getProviders().get(1).isThinking());
        assertEquals("openai", loaded.getProviders().get(1).getProtocol());
        assertFalse(loaded.getProviders().get(0).toString().contains("secret-one"));
    }

    @Test
    void acceptsDeepSeekAsAnOpenAiCompatibleProtocol() throws Exception {
        Path config = write(validProvider().replace("protocol: anthropic", "protocol: deepseek"));

        AppConfig loaded = ConfigLoader.load(config.toString());

        assertEquals("deepseek", loaded.getProviders().getFirst().getProtocol());
    }

    @Test
    void rejectsMissingFileWithoutStackDetails() {
        var error = assertThrows(ConfigLoader.ConfigException.class,
                () -> ConfigLoader.load(tempDir.resolve("missing.yaml").toString()));
        assertTrue(error.getMessage().contains("missing.yaml"));
    }

    @Test
    void rejectsInvalidYamlWithoutEchoingSecret() throws Exception {
        Path config = write("providers: [ api_key: ultra-secret");
        var error = assertThrows(ConfigLoader.ConfigException.class,
                () -> ConfigLoader.load(config.toString()));
        assertFalse(error.getMessage().contains("ultra-secret"));
    }

    @Test
    void rejectsEmptyProviders() throws Exception {
        assertErrorContains("providers: []", "at least one");
    }

    @Test
    void rejectsMissingRequiredFieldsWithoutLeakingKey() throws Exception {
        String[] fields = {"name", "protocol", "model", "api_key"};
        for (String missing : fields) {
            String yaml = validProvider().replace(
                    missing + ": " + valueFor(missing), missing + ": \"\"");
            var error = assertThrows(ConfigLoader.ConfigException.class,
                    () -> ConfigLoader.load(write(yaml).toString()), missing);
            assertTrue(error.getMessage().contains(missing));
            assertFalse(error.getMessage().contains("test-secret"));
        }
    }

    @Test
    void rejectsUnknownProtocolDuplicateNameAndBadUrl() throws Exception {
        assertErrorContains(validProvider().replace("protocol: anthropic", "protocol: unknown"), "protocol");
        assertErrorContains(validProvider() + validProvider().replace("providers:\n", ""), "unique");
        assertErrorContains(validProvider() + "    base_url: file:///tmp/model\n", "base_url");
    }

    private void assertErrorContains(String yaml, String text) throws Exception {
        var error = assertThrows(ConfigLoader.ConfigException.class,
                () -> ConfigLoader.load(write(yaml).toString()));
        assertTrue(error.getMessage().contains(text), error.getMessage());
    }

    private Path write(String content) throws Exception {
        Path path = tempDir.resolve("config-" + System.nanoTime() + ".yaml");
        Files.writeString(path, content);
        return path;
    }

    private static String validProvider() {
        return """
                providers:
                  - name: sample
                    protocol: anthropic
                    model: claude-test
                    api_key: test-secret
                """;
    }

    private static String valueFor(String field) {
        return switch (field) {
            case "name" -> "sample";
            case "protocol" -> "anthropic";
            case "model" -> "claude-test";
            case "api_key" -> "test-secret";
            default -> throw new IllegalArgumentException(field);
        };
    }
}
