package com.bluecode.config;

import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class ConfigLoader {
    private static final Set<String> SUPPORTED_PROTOCOLS = Set.of("anthropic", "openai", "openai-compat");

    private ConfigLoader() {
    }

    public static AppConfig load(String path) {
        Path configPath = Path.of(path);
        if (!Files.exists(configPath)) {
            throw new ConfigException("配置文件不存在：" + configPath.toAbsolutePath());
        }

        Object root;
        try (Reader reader = Files.newBufferedReader(configPath)) {
            root = new Yaml().load(reader);
        } catch (YAMLException e) {
            throw new ConfigException("配置文件 YAML 格式错误：" + e.getMessage(), e);
        } catch (IOException e) {
            throw new ConfigException("读取配置文件失败：" + e.getMessage(), e);
        }

        if (!(root instanceof Map<?, ?> rootMap)) {
            throw new ConfigException("配置文件根节点必须是对象，并包含 providers 列表");
        }

        Object providersRaw = rootMap.get("providers");
        if (!(providersRaw instanceof List<?> providersList) || providersList.isEmpty()) {
            throw new ConfigException("providers 不能为空");
        }

        List<ProviderConfig> providers = new ArrayList<>();
        for (int i = 0; i < providersList.size(); i++) {
            Object raw = providersList.get(i);
            if (!(raw instanceof Map<?, ?> item)) {
                throw new ConfigException("providers[" + i + "] 必须是对象");
            }
            ProviderConfig provider = bindProvider(item, i);
            validateProvider(provider, i);
            providers.add(provider);
        }

        AppConfig config = new AppConfig();
        config.setProviders(providers);
        config.setEnableSubAgentBackground(firstBooleanValue(
                rootMap,
                "enable_subagent_background",
                "enableSubAgentBackground"));
        return config;
    }

    private static ProviderConfig bindProvider(Map<?, ?> item, int index) {
        ProviderConfig provider = new ProviderConfig();
        provider.setName(stringValue(item, "name"));
        provider.setProtocol(stringValue(item, "protocol"));
        provider.setBaseUrl(firstStringValue(item, "base_url", "baseUrl"));
        provider.setApiKey(firstStringValue(item, "api_key", "apiKey"));
        provider.setModel(stringValue(item, "model"));
        provider.setThinking(booleanValue(item, "thinking"));
        provider.setContextWindow(intValue(item, "context_window", "contextWindow"));

        if (provider.getProtocol() != null) {
            provider.setProtocol(provider.getProtocol().toLowerCase(Locale.ROOT));
        }
        return provider;
    }

    private static void validateProvider(ProviderConfig provider, int index) {
        requireText(provider.getName(), "providers[" + index + "].name");
        requireText(provider.getProtocol(), "providers[" + index + "].protocol");
        requireText(provider.getApiKey(), "providers[" + index + "].api_key");
        requireText(provider.getModel(), "providers[" + index + "].model");
        if (!SUPPORTED_PROTOCOLS.contains(provider.getProtocol())) {
            throw new ConfigException("providers[" + index + "].protocol 不支持：" + provider.getProtocol());
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ConfigException(field + " 不能为空");
        }
    }

    private static String firstStringValue(Map<?, ?> item, String first, String second) {
        String value = stringValue(item, first);
        return value == null ? stringValue(item, second) : value;
    }

    private static String stringValue(Map<?, ?> item, String key) {
        Object value = item.get(key);
        if (value == null) {
            return null;
        }
        return String.valueOf(value).trim();
    }

    private static boolean booleanValue(Map<?, ?> item, String key) {
        Object value = item.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value == null) {
            return false;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private static Boolean firstBooleanValue(Map<?, ?> item, String first, String second) {
        Object value = item.containsKey(first) ? item.get(first) : item.get(second);
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private static int intValue(Map<?, ?> item, String first, String second) {
        Object value = item.containsKey(first) ? item.get(first) : item.get(second);
        if (value == null) {
            return 0;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            throw new ConfigException(first + " 必须是整数");
        }
    }
}
