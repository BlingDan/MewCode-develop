package com.mewcode.config;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.introspector.Property;
import org.yaml.snakeyaml.introspector.PropertyUtils;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/** Loads the project-local MewCode YAML configuration. */
public final class ConfigLoader {

    private static final Set<String> PROTOCOLS = Set.of("anthropic", "openai", "deepseek");

    private ConfigLoader() {}

    public static AppConfig load(String path) throws ConfigException {
        return load(Path.of(path));
    }

    public static AppConfig load(Path path) throws ConfigException {
        Path configPath = path.toAbsolutePath().normalize();
        if (!Files.isRegularFile(configPath)) {
            throw new ConfigException("Config file not found: " + configPath);
        }

        String yamlText;
        try {
            yamlText = Files.readString(configPath);
        } catch (IOException e) {
            throw new ConfigException("Cannot read config file: " + configPath);
        }

        AppConfig config;
        try {
            var options = new LoaderOptions();
            var constructor = new Constructor(AppConfig.class, options);
            constructor.setPropertyUtils(new SnakeCaseProperties());
            config = new Yaml(constructor).load(yamlText);
        } catch (Exception e) {
            throw new ConfigException("Invalid YAML in config file: " + configPath);
        }

        if (config == null) config = new AppConfig();
        validate(config);
        return config;
    }

    private static void validate(AppConfig config) throws ConfigException {
        if (config.getAgent() == null || config.getAgent().getLoop() == null) {
            throw new ConfigException("agent.loop must be an object");
        }
        try {
            config.getAgent().getLoop().validate();
        } catch (IllegalArgumentException error) {
            String field = error.getMessage() != null && error.getMessage().startsWith("maxIterations")
                    ? "max_iterations"
                    : "unknown_tool_round_limit";
            throw new ConfigException("agent.loop." + field + " must be positive");
        }

        if (config.getProviders() == null || config.getProviders().isEmpty()) {
            throw new ConfigException("providers must contain at least one entry");
        }

        var names = new HashSet<String>();
        for (int i = 0; i < config.getProviders().size(); i++) {
            ProviderConfig provider = config.getProviders().get(i);
            String prefix = "providers[" + i + "]";
            if (provider == null) throw new ConfigException(prefix + " must be an object");
            require(prefix, "name", provider.getName());
            require(prefix, "protocol", provider.getProtocol());
            require(prefix, "model", provider.getModel());
            require(prefix, "api_key", provider.getApiKey());

            if (!PROTOCOLS.contains(provider.getProtocol())) {
                throw new ConfigException(prefix + ".protocol must be anthropic, openai, or deepseek");
            }
            if (!names.add(provider.getName())) {
                throw new ConfigException(prefix + ".name must be unique");
            }
            validateBaseUrl(prefix, provider.getBaseUrl());
        }
    }

    private static void require(String prefix, String field, String value) throws ConfigException {
        if (value == null || value.isBlank()) {
            throw new ConfigException(prefix + "." + field + " must not be blank");
        }
    }

    private static void validateBaseUrl(String prefix, String value) throws ConfigException {
        if (value == null || value.isBlank()) return;
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            if (uri.getHost() == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException e) {
            throw new ConfigException(prefix + ".base_url must be a valid HTTP or HTTPS URL");
        }
    }

    private static final class SnakeCaseProperties extends PropertyUtils {
        @Override
        public Property getProperty(Class<?> type, String name) {
            return super.getProperty(type, snakeToCamel(name));
        }

        private static String snakeToCamel(String value) {
            var result = new StringBuilder();
            boolean upper = false;
            for (char c : value.toCharArray()) {
                if (c == '_' || c == '-') {
                    upper = true;
                } else {
                    result.append(upper ? Character.toUpperCase(c) : c);
                    upper = false;
                }
            }
            return result.toString();
        }
    }

    public static final class ConfigException extends Exception {
        public ConfigException(String message) {
            super(message);
        }
    }
}
