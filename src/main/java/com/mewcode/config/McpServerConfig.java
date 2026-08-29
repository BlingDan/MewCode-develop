package com.mewcode.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 一个已经完成字段校验、敏感值仍只用于传输的 MCP Server 配置。 */
public record McpServerConfig(
    String serverName,
    String command,
    List<String> args,
    Map<String, String> env,
    String url,
    Map<String, String> headers) {

  public McpServerConfig {
    serverName = Objects.requireNonNull(serverName, "serverName");
    args = args == null ? List.of() : List.copyOf(args);
    env = env == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(env));
    headers = headers == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(headers));
  }

  /** 是否为本地 stdio Server。 */
  public boolean isStdio() {
    return command != null;
  }

  /** 是否为 Streamable HTTP Server。 */
  public boolean isHttp() {
    return url != null;
  }
}
