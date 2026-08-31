package com.mewcode.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mewcode.config.McpServerConfig;
import com.mewcode.tool.Tool;
import com.mewcode.tool.ToolCategory;
import com.mewcode.tool.ToolExecutionContext;
import com.mewcode.tool.ToolRegistry;
import com.mewcode.tool.ToolResult;
import com.mewcode.tool.impl.ToolSearchTool;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import reactor.core.publisher.Mono;

/** 管理多个 MCP Server 的连接、工具发现、适配注册和生命周期。 */
public final class McpManager implements AutoCloseable {

  public static final String SUPPORTED_PROTOCOL_VERSION = "2025-11-25";
  private static final Logger LOGGER = Logger.getLogger(McpManager.class.getName());
  private static final Duration REQUEST_TIMEOUT = ToolExecutionContext.DEFAULT_TIMEOUT;
  private static final ObjectMapper JSON = new ObjectMapper();

  private final ToolRegistry registry;
  private final Function<McpServerConfig, McpClientTransport> transportFactory;
  private final Map<String, McpSyncClient> clients = new LinkedHashMap<>();
  private final Map<String, List<String>> registeredSources = new LinkedHashMap<>();
  private final List<String> errors = new ArrayList<>();
  private boolean started;
  private boolean closed;

  public McpManager(ToolRegistry registry) {
    this(registry, McpManager::createTransport);
  }

  /** 允许测试注入传输工厂；生产代码使用官方 SDK 的两种传输。 */
  public McpManager(
      ToolRegistry registry, Function<McpServerConfig, McpClientTransport> transportFactory) {
    this.registry = java.util.Objects.requireNonNull(registry, "registry");
    this.transportFactory = java.util.Objects.requireNonNull(transportFactory, "transportFactory");
  }

  /** 连接所有配置的 Server；同一 Manager 只执行一次初始化和发现。 */
  public synchronized ConnectionReport connectAll(List<McpServerConfig> configurations) {
    if (started || closed) return report();
    started = true;
    if (configurations == null) return report();
    for (McpServerConfig configuration : configurations) connectOne(configuration);
    if (registeredSources.values().stream().anyMatch(tools -> !tools.isEmpty())
        && registry.get(ToolSearchTool.NAME).isEmpty()) {
      registry.register(new ToolSearchTool(registry));
    }
    return report();
  }

  /** 返回启动阶段的脱敏错误快照。 */
  public synchronized List<String> errors() {
    return List.copyOf(errors);
  }

  /** 返回成功建立会话的 Server 名称。 */
  public synchronized List<String> connectedServers() {
    return List.copyOf(clients.keySet());
  }

  /** 返回已注册的 MCP 工具来源。 */
  public synchronized Map<String, List<String>> registeredSources() {
    var snapshot = new LinkedHashMap<String, List<String>>();
    registeredSources.forEach((server, tools) -> snapshot.put(server, List.copyOf(tools)));
    return Map.copyOf(snapshot);
  }

  private void connectOne(McpServerConfig configuration) {
    if (configuration == null) {
      errors.add("MCP Server 配置为空");
      return;
    }

    McpClientTransport transport = null;
    ProtocolTrackingTransport trackingTransport = null;
    McpSyncClient client = null;
    try {
      trackingTransport = new ProtocolTrackingTransport(transportFactory.apply(configuration));
      transport = trackingTransport;
      client =
          McpClient.sync(transport)
              .requestTimeout(REQUEST_TIMEOUT)
              .initializationTimeout(REQUEST_TIMEOUT)
              .clientInfo(McpSchema.Implementation.builder("MewCode", "0.1.0").build())
              .build();

      McpSchema.InitializeResult initializeResult = client.initialize();
      validateProtocol(initializeResult);
      List<McpSchema.Tool> definitions = listAllTools(client);

      var wrappers = new ArrayList<Tool>();
      var names = new HashSet<String>();
      for (McpSchema.Tool definition : definitions) {
        Tool wrapper = createWrapper(configuration, client, definition);
        if (wrapper == null) continue;
        if (!names.add(wrapper.name()) || registry.get(wrapper.name()).isPresent()) {
          addError(configuration.serverName(), "工具名称冲突，已跳过 " + wrapper.name());
          continue;
        }
        wrappers.add(wrapper);
      }

      for (Tool wrapper : wrappers) registry.register(wrapper);
      clients.put(configuration.serverName(), client);
      registeredSources.put(configuration.serverName(), wrappers.stream().map(Tool::name).toList());
      client = null;
      transport = null;
    } catch (RuntimeException error) {
      addError(configuration.serverName(), failureReason(error, trackingTransport));
    } finally {
      if (client != null || transport != null) {
        closeQuietly(client, transport, configuration.serverName());
      }
    }
  }

  private static List<McpSchema.Tool> listAllTools(McpSyncClient client) {
    var definitions = new ArrayList<McpSchema.Tool>();
    String cursor = null;
    do {
      McpSchema.ListToolsResult page =
          cursor == null ? client.listTools() : client.listTools(cursor);
      if (page == null) throw new IllegalStateException("tools/list 返回为空");
      if (page.tools() != null) definitions.addAll(page.tools());
      String next = page.nextCursor();
      if (next != null && next.equals(cursor)) {
        throw new IllegalStateException("tools/list 返回重复分页游标");
      }
      cursor = next == null || next.isBlank() ? null : next;
    } while (cursor != null);
    return definitions;
  }

  private static void validateProtocol(McpSchema.InitializeResult result) {
    String version = result == null ? null : result.protocolVersion();
    if (!SUPPORTED_PROTOCOL_VERSION.equals(version)) {
      throw new IllegalStateException(
          "协议版本不兼容（服务端 "
              + (version == null ? "缺失或非法" : version)
              + "，客户端仅支持 "
              + SUPPORTED_PROTOCOL_VERSION
              + "）");
    }
  }

  private Tool createWrapper(
      McpServerConfig configuration, McpSyncClient client, McpSchema.Tool definition) {
    if (definition == null || definition.name() == null || definition.name().isBlank()) {
      addError(configuration.serverName(), "工具缺少名称，已跳过");
      return null;
    }
    if (definition.inputSchema() == null) {
      addError(configuration.serverName(), "工具 " + definition.name() + " 的 inputSchema 无效，已跳过");
      return null;
    }
    String publicName =
        "mcp_" + escapeName(configuration.serverName()) + "_" + escapeName(definition.name());
    return new McpToolWrapper(configuration.serverName(), publicName, definition, client);
  }

  private void addError(String serverName, String message) {
    String safeServer = serverName == null || serverName.isBlank() ? "<unknown>" : serverName;
    String error = "MCP Server " + safeServer + "：" + message;
    errors.add(error);
    LOGGER.log(Level.WARNING, error);
  }

  private static String failureReason(
      Throwable error, ProtocolTrackingTransport trackingTransport) {
    String version = trackingTransport == null ? null : trackingTransport.initializedVersion();
    String detail = "连接或工具发现失败（" + error.getClass().getSimpleName() + "）";
    if (version == null || version.isBlank()) {
      detail += "（服务端版本缺失或非法，客户端仅支持 " + SUPPORTED_PROTOCOL_VERSION + "）";
    } else if (!SUPPORTED_PROTOCOL_VERSION.equals(version)) {
      detail += "（服务端版本 " + version + "，客户端仅支持 " + SUPPORTED_PROTOCOL_VERSION + "）";
    }
    return detail;
  }

  private static String escapeName(String value) {
    var result = new StringBuilder();
    for (int offset = 0; offset < value.length(); ) {
      int codePoint = value.codePointAt(offset);
      boolean allowed =
          codePoint < 128
              && (Character.isLetterOrDigit(codePoint) || codePoint == '_' || codePoint == '-');
      if (allowed) {
        result.appendCodePoint(codePoint);
      } else {
        result.append("_x").append(Integer.toHexString(codePoint)).append('_');
      }
      offset += Character.charCount(codePoint);
    }
    return result.isEmpty() ? "_" : result.toString();
  }

  private static McpClientTransport createTransport(McpServerConfig configuration) {
    if (configuration.isStdio()) {
      ServerParameters parameters =
          ServerParameters.builder(configuration.command())
              .args(configuration.args())
              .env(configuration.env())
              .build();
      return new VersionRestrictedStdioClientTransport(parameters, McpJsonDefaults.getMapper());
    }

    McpSyncHttpClientRequestCustomizer headers =
        (builder, method, endpoint, body, context) ->
            configuration.headers().forEach(builder::header);
    return HttpClientStreamableHttpTransport.builder(configuration.url())
        .supportedProtocolVersions(List.of(SUPPORTED_PROTOCOL_VERSION))
        .resumableStreams(false)
        .httpRequestCustomizer(headers)
        .build();
  }

  private static void closeQuietly(
      McpSyncClient client, McpClientTransport transport, String serverName) {
    try {
      if (client != null) {
        if (!client.closeGracefully()) client.close();
        return;
      }
      if (transport != null) transport.closeGracefully().block(REQUEST_TIMEOUT);
    } catch (RuntimeException error) {
      LOGGER.log(Level.WARNING, "MCP Server " + serverName + " 关闭失败", error);
    }
  }

  /** 关闭所有客户端；一个 Server 关闭失败不影响其他 Server。 */
  public synchronized void shutdown() {
    if (closed) return;
    closed = true;
    for (var entry : new ArrayList<>(clients.entrySet())) {
      closeQuietly(entry.getValue(), null, entry.getKey());
    }
    clients.clear();
    registeredSources.clear();
  }

  private synchronized ConnectionReport report() {
    return new ConnectionReport(List.copyOf(clients.keySet()), List.copyOf(errors));
  }

  @Override
  public void close() {
    shutdown();
  }

  public record ConnectionReport(List<String> connectedServers, List<String> errors) {
    public ConnectionReport {
      connectedServers = connectedServers == null ? List.of() : List.copyOf(connectedServers);
      errors = errors == null ? List.of() : List.copyOf(errors);
    }
  }

  private static final class VersionRestrictedStdioClientTransport extends StdioClientTransport {

    private VersionRestrictedStdioClientTransport(
        ServerParameters parameters, McpJsonMapper jsonMapper) {
      super(parameters, jsonMapper);
    }

    @Override
    public List<String> protocolVersions() {
      return List.of(SUPPORTED_PROTOCOL_VERSION);
    }
  }

  /** 只观察初始化响应中的版本，用于 SDK 在协商失败时补充可定位的错误。 */
  private static final class ProtocolTrackingTransport implements McpClientTransport {

    private final McpClientTransport delegate;
    private volatile String initializedVersion;

    private ProtocolTrackingTransport(McpClientTransport delegate) {
      this.delegate = java.util.Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public Mono<Void> connect(
        java.util.function.Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>>
            handler) {
      return delegate.connect(inbound -> handler.apply(inbound.doOnNext(this::observe)));
    }

    @Override
    public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
      return delegate.sendMessage(message);
    }

    @Override
    public <T> T unmarshalFrom(Object data, io.modelcontextprotocol.json.TypeRef<T> typeRef) {
      return delegate.unmarshalFrom(data, typeRef);
    }

    @Override
    public List<String> protocolVersions() {
      return List.of(SUPPORTED_PROTOCOL_VERSION);
    }

    @Override
    public Mono<Void> closeGracefully() {
      return delegate.closeGracefully();
    }

    @Override
    public void setExceptionHandler(java.util.function.Consumer<Throwable> handler) {
      delegate.setExceptionHandler(handler);
    }

    private void observe(McpSchema.JSONRPCMessage message) {
      if (!(message instanceof McpSchema.JSONRPCResponse response)) return;
      if (!(response.result() instanceof Map<?, ?> result)) return;
      Object version = result.get("protocolVersion");
      if (version != null) initializedVersion = String.valueOf(version);
    }

    private String initializedVersion() {
      return initializedVersion;
    }
  }

  private static final class McpToolWrapper implements Tool {

    private final String serverName;
    private final String publicName;
    private final McpSchema.Tool definition;
    private final McpSyncClient client;

    private McpToolWrapper(
        String serverName, String publicName, McpSchema.Tool definition, McpSyncClient client) {
      this.serverName = serverName;
      this.publicName = publicName;
      this.definition = definition;
      this.client = client;
    }

    @Override
    public String name() {
      return publicName;
    }

    @Override
    public String description() {
      return definition.description() == null
          ? "MCP 工具：" + definition.name()
          : definition.description();
    }

    @Override
    public ToolCategory category() {
      return ToolCategory.MCP;
    }

    @Override
    public Map<String, Object> inputSchema() {
      return definition.inputSchema();
    }

    @Override
    public Map<String, Object> outputSchema() {
      return definition.outputSchema() == null ? Map.of() : definition.outputSchema();
    }

    @Override
    public ToolResult execute(ToolExecutionContext context, Map<String, Object> input) {
      try {
        var request =
            McpSchema.CallToolRequest.builder()
                .name(definition.name())
                .arguments(input == null ? Map.of() : input)
                .build();
        McpSchema.CallToolResult result = client.callTool(request);
        if (result == null) return ToolResult.error("MCP 工具返回为空：" + publicName);
        String content = renderResult(result);
        return new ToolResult(content, Boolean.TRUE.equals(result.isError()), Map.of());
      } catch (RuntimeException error) {
        return ToolResult.error("MCP 工具调用失败：" + serverName + "/" + definition.name());
      }
    }

    @Override
    public boolean isReadOnly() {
      return false;
    }

    @Override
    public boolean isDestructive() {
      return false;
    }

    @Override
    public boolean isConcurrencySafe(Map<String, Object> input) {
      return false;
    }

    @Override
    public String validateInput(Map<String, Object> input) {
      return null;
    }

    @Override
    public boolean shouldDefer() {
      return true;
    }

    private static String renderResult(McpSchema.CallToolResult result) {
      var text = new StringBuilder();
      if (result.content() != null) {
        for (McpSchema.Content content : result.content()) {
          if (!text.isEmpty()) text.append('\n');
          if (content instanceof McpSchema.TextContent textContent) {
            text.append(textContent.text());
          } else {
            text.append(toJson(content));
          }
        }
      }
      if (result.structuredContent() != null) {
        if (!text.isEmpty()) text.append('\n');
        text.append(toJson(result.structuredContent()));
      }
      return text.toString();
    }

    private static String toJson(Object value) {
      try {
        return JSON.writeValueAsString(value);
      } catch (JsonProcessingException error) {
        return String.valueOf(value);
      }
    }
  }
}
