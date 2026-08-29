package com.mewcode.config;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** 读取、合并并逐条校验用户级和项目级 MCP Server 配置。 */
public final class McpConfigLoader {

  private static final Set<String> STDIO_FIELDS = Set.of("command", "args", "env");
  private static final Set<String> HTTP_FIELDS = Set.of("url", "headers");

  private McpConfigLoader() {}

  /** 加载默认用户配置和当前项目配置。 */
  public static Loaded load(Path projectRoot, AppConfig projectConfig) {
    Path userConfig =
        Path.of(System.getProperty("user.home"), ".mewcode", "config.yaml")
            .toAbsolutePath()
            .normalize();
    return load(userConfig, projectConfig == null ? Map.of() : projectConfig.getMcpServers());
  }

  /** 使用指定用户配置路径加载，供测试和嵌入式调用复用。 */
  public static Loaded load(Path userConfigPath, Map<String, Object> projectServers) {
    var errors = new ArrayList<String>();
    var merged = new LinkedHashMap<String, Object>();
    merged.putAll(readUserServers(userConfigPath, errors));
    if (projectServers != null) merged.putAll(projectServers);

    var servers = new ArrayList<McpServerConfig>();
    for (var entry : merged.entrySet()) {
      try {
        servers.add(parse(entry.getKey(), entry.getValue()));
      } catch (InvalidServer error) {
        errors.add("MCP Server " + safeName(entry.getKey()) + " 配置无效：" + error.getMessage());
      }
    }
    return new Loaded(servers, errors);
  }

  private static Map<String, Object> readUserServers(Path path, List<String> errors) {
    if (path == null || !Files.isRegularFile(path)) return Map.of();
    try {
      Object root = new Yaml(new SafeConstructor(new LoaderOptions())).load(Files.readString(path));
      if (root == null) return Map.of();
      if (!(root instanceof Map<?, ?> rootMap)) {
        errors.add("用户级 MCP 配置根节点必须是 map");
        return Map.of();
      }
      Object section = rootMap.get("mcp_servers");
      if (section == null) return Map.of();
      return stringKeyedMap(section, "用户级 mcp_servers", errors);
    } catch (IOException | RuntimeException error) {
      errors.add("用户级 mcp_servers 配置无法读取或解析");
      return Map.of();
    }
  }

  private static Map<String, Object> stringKeyedMap(
      Object value, String label, List<String> errors) {
    if (!(value instanceof Map<?, ?> raw)) {
      errors.add(label + " 必须是 map");
      return Map.of();
    }
    var result = new LinkedHashMap<String, Object>();
    for (var entry : raw.entrySet()) {
      if (!(entry.getKey() instanceof String name) || name.isBlank()) {
        errors.add(label + " 包含空或非字符串 Server 名称");
        continue;
      }
      result.put(name, entry.getValue());
    }
    return result;
  }

  private static McpServerConfig parse(String serverName, Object raw) throws InvalidServer {
    if (serverName == null || serverName.isBlank()) throw new InvalidServer("名称不能为空");
    if (!(raw instanceof Map<?, ?> values)) throw new InvalidServer("必须是 map");

    var fields = new LinkedHashMap<String, Object>();
    for (var entry : values.entrySet()) {
      if (!(entry.getKey() instanceof String field)) throw new InvalidServer("字段名必须是字符串");
      fields.put(field, entry.getValue());
    }

    boolean hasCommand = fields.containsKey("command");
    boolean hasUrl = fields.containsKey("url");
    if (hasCommand == hasUrl) throw new InvalidServer("command 和 url 必须二选一");
    Set<String> allowed = hasCommand ? STDIO_FIELDS : HTTP_FIELDS;
    for (String field : fields.keySet()) {
      if (!allowed.contains(field)) throw new InvalidServer("字段 " + field + " 不属于当前传输类型");
    }

    if (hasCommand) {
      String command = requiredString(fields.get("command"), "command");
      List<String> args = stringList(fields.get("args"), "args");
      Map<String, String> env = stringMap(fields.get("env"), "env");
      return new McpServerConfig(serverName, command, args, env, null, Map.of());
    }

    String url = requiredString(fields.get("url"), "url");
    validateUrl(url);
    Map<String, String> headers = stringMap(fields.get("headers"), "headers");
    return new McpServerConfig(serverName, null, List.of(), Map.of(), url, headers);
  }

  private static String requiredString(Object value, String field) throws InvalidServer {
    if (!(value instanceof String text) || text.isBlank()) {
      throw new InvalidServer(field + " 必须是非空字符串");
    }
    return text;
  }

  private static List<String> stringList(Object value, String field) throws InvalidServer {
    if (value == null) return List.of();
    if (!(value instanceof List<?> list)) throw new InvalidServer(field + " 必须是字符串列表");
    var result = new ArrayList<String>(list.size());
    for (Object item : list) {
      if (!(item instanceof String text)) throw new InvalidServer(field + " 必须是字符串列表");
      result.add(text);
    }
    return result;
  }

  private static Map<String, String> stringMap(Object value, String field) throws InvalidServer {
    if (value == null) return Map.of();
    if (!(value instanceof Map<?, ?> raw)) throw new InvalidServer(field + " 必须是字符串 map");
    var result = new LinkedHashMap<String, String>();
    for (var entry : raw.entrySet()) {
      if (!(entry.getKey() instanceof String key) || key.isBlank()) {
        throw new InvalidServer(field + " 的 key 必须是非空字符串");
      }
      if (!(entry.getValue() instanceof String text)) {
        throw new InvalidServer(field + " 的 value 必须是字符串");
      }
      result.put(key, text);
    }
    return result;
  }

  private static void validateUrl(String value) throws InvalidServer {
    try {
      URI uri = URI.create(value);
      String scheme = uri.getScheme();
      if (uri.getHost() == null
          || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
        throw new IllegalArgumentException();
      }
    } catch (IllegalArgumentException error) {
      throw new InvalidServer("url 必须是 HTTP 或 HTTPS URL");
    }
  }

  private static String safeName(String value) {
    return value == null || value.isBlank() ? "<unknown>" : value;
  }

  public record Loaded(List<McpServerConfig> servers, List<String> errors) {
    public Loaded {
      servers = servers == null ? List.of() : List.copyOf(servers);
      errors = errors == null ? List.of() : List.copyOf(errors);
    }
  }

  private static final class InvalidServer extends Exception {
    private InvalidServer(String message) {
      super(message);
    }
  }
}
