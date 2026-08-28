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
    void bindsMcpServersFromTheMainConfig() throws Exception {
        AppConfig loaded = ConfigLoader.load(write("""
                mcp_servers:
                  local:
                    command: sample-mcp
                    args: [stdio]
                    env:
                      TOKEN: "${TOKEN}"
                  remote:
                    url: https://example.com/mcp
                    headers:
                      Authorization: Bearer test-token
                """ + validProvider()));

        assertEquals(2, loaded.getMcpServers().size());
        assertEquals("sample-mcp", ((java.util.Map<?, ?>) loaded.getMcpServers().get("local")).get("command"));
        assertEquals("https://example.com/mcp", ((java.util.Map<?, ?>) loaded.getMcpServers().get("remote")).get("url"));
    }

    @Test
    void acceptsDeepSeekAsAnOpenAiCompatibleProtocol() throws Exception {
        Path config = write(validProvider().replace("protocol: anthropic", "protocol: deepseek"));

        AppConfig loaded = ConfigLoader.load(config.toString());

        assertEquals("deepseek", loaded.getProviders().getFirst().getProtocol());
    }

    @Test
    void loadsAgentLoopDefaultsWhenAgentSectionIsMissing() throws Exception {
        AppConfig loaded = ConfigLoader.load(write(validProvider()));

        assertEquals(20, loaded.getAgent().getLoop().getMaxIterations());
        assertEquals(3, loaded.getAgent().getLoop().getUnknownToolRoundLimit());
    }

    @Test
    void loadsConfiguredAgentLoopLimitsWithHyphenatedKeys() throws Exception {
        Path config = write("""
                agent:
                  loop:
                    max-iterations: 7
                    unknown-tool-round-limit: 5
                """ + validProvider());

        AppConfig loaded = ConfigLoader.load(config);

        assertEquals(7, loaded.getAgent().getLoop().getMaxIterations());
        assertEquals(5, loaded.getAgent().getLoop().getUnknownToolRoundLimit());
    }

    @Test
    void rejectsNonPositiveAgentLoopLimits() throws Exception {
        Path config = write("""
                agent:
                  loop:
                    max-iterations: 0
                """ + validProvider());

        var error = assertThrows(ConfigLoader.ConfigException.class,
                () -> ConfigLoader.load(config));

        assertTrue(error.getMessage().contains("max_iterations"));
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
