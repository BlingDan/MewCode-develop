# MewCode MCP 客户端 Plan

> 状态：已确认

## 方案概览

采用官方 MCP Java SDK 负责 JSON-RPC、stdio、Streamable HTTP、会话和异步消息配对；MewCode 只负责配置、生命周期、工具注册、权限接入和 Agent 延迟加载。

整体调用链：

    MewCode
      ↓
    MewCodeModel
      ├─ McpConfigLoader
      ├─ McpManager
      │    ├─ StdioClientTransport
      │    └─ HttpClientStreamableHttpTransport
      └─ ToolRegistry
           ├─ 内置 Tool
           ├─ ToolSearch
           └─ McpToolWrapper
                ↓
           ToolExecutor → McpSyncClient → MCP Server

核心取舍：

- 使用 SDK 同步门面适配现有 Tool.execute 的同步接口；SDK 内部继续负责异步消息接收和请求响应配对。
- 使用 SDK 的 mcp-core 和 mcp-json-jackson2，复用项目当前 Jackson 2，不引入 Spring 或手写 MCP 传输层。
- SDK 版本固定为 2.0.1，并通过 transport 的 supportedProtocolVersions 只声明 2025-11-25；初始化后再次校验协商结果。
- 按参考文档采用 command、args、env 和 url、headers 字段，不增加 type 字段。
- 每个 MCP 工具将 isConcurrencySafe 设为 false，调用通过现有批量执行器串行化；外部工具的副作用未知，不为并发节省少量耗时引入共享连接竞态。
- 不新增连接健康检查、自动重连、自动重启或工具列表刷新。

官方 SDK 2.0.x 当前跟踪 MCP 2025-11-25，并提供 stdio、Streamable HTTP 和 Jackson 2 模块：

- https://github.com/modelcontextprotocol/java-sdk/releases
- https://java.sdk.modelcontextprotocol.io/latest/client/
- https://java.sdk.modelcontextprotocol.io/latest/quickstart/

## 实现步骤

### P1：引入 MCP SDK

修改 build.gradle.kts：

- 使用 io.modelcontextprotocol.sdk:mcp-bom:2.0.1；
- 添加 io.modelcontextprotocol.sdk:mcp-core；
- 添加 io.modelcontextprotocol.sdk:mcp-json-jackson2；
- 不添加 convenience mcp bundle，避免引入 Jackson 3；
- 保留现有 Jackson 2、SnakeYAML、JLine 和测试依赖。

先执行一次依赖解析和空编译，确认 SDK 2.0.1 与 Java 21、现有 Jackson 2 依赖兼容。

### P2：配置模型和加载器

新增 com.mewcode.config.McpServerConfig：

- serverName：来自 mcp_servers 的 map key；
- command：stdio 命令；
- args：stdio 参数列表；
- env：stdio 环境变量；
- url：HTTP 基础 URL；
- headers：HTTP 静态请求头。

新增 McpConfigLoader，职责只包含 MCP 配置，不改变现有 ConfigLoader 的 Provider、Agent 和权限校验：

1. 从用户级 ~/.mewcode/config.yaml 读取可选的 mcp_servers；
2. 从项目级 AppConfig 取得 mcp_servers 原始 map；
3. 按用户级先入、项目级后入合并；
4. 同名 Server 使用项目级完整条目覆盖；
5. 逐条校验和转换为 McpServerConfig；
6. 返回有效配置和逐条错误，错误 Server 不阻塞其他配置。

AppConfig 增加 mcpServers 原始 map 字段，使现有项目配置可以继续由 ConfigLoader 读取；McpConfigLoader 不把 MCP 条目直接绑定为强类型对象，避免单条坏配置导致整份项目配置失败。

配置校验：

- command 和 url 必须二选一；
- stdio 只允许 command、args、env；
- HTTP 只允许 url、headers；
- args 必须是字符串列表；
- env 和 headers 必须是字符串到字符串的 map；
- url 必须是 HTTP 或 HTTPS URL；
- env 和 headers 的值展开 ${VAR}；
- 未定义环境变量、缺字段、类型错误或未知字段只使当前 Server 无效；
- 错误文本只包含 Server 名称和字段名，不包含 Secret 值。

### P3：McpManager 和传输

新增 com.mewcode.mcp.McpManager，内部使用 LinkedHashMap 保存：

- Server 配置；
- 已建立的 McpSyncClient；
- 已注册的 Server 工具来源。

Manager 对外提供：

- connectAll：逐个建立会话、发现工具并返回有效包装工具和错误；
- shutdown：关闭所有 client 和底层 transport；
- errors 或等价结果，供启动日志显示。

每个 Server 的连接流程：

1. 根据 command 或 url 创建 transport；
2. 将 transport 支持版本限制为单一的 2025-11-25；
3. 创建 McpSyncClient，并复用 ToolExecutionContext 的 120 秒默认超时；
4. 调用 initialize；
5. 校验 InitializeResult.protocolVersion 为 2025-11-25；
6. SDK 完成 notifications/initialized；
7. 循环调用 listTools(cursor)，直到 nextCursor 为空；
8. 为有效工具创建 McpToolWrapper；
9. 只有整个 Server 成功发现后，才把 client 放入缓存并注册工具；
10. 单个步骤异常时关闭当前 client，记录错误并继续下一个 Server。

stdio：

- 使用 SDK ServerParameters 传递 command、args 和 env；
- 不通过 shell 拼接 command；
- 使用 SDK 的 stdin/stdout 管道和 stderr 处理；
- 对 SDK transport 做最小版本限制包装，避免默认协商到其他协议版本。

HTTP：

- 使用 SDK HttpClientStreamableHttpTransport；
- 复用 JDK HttpClient，不引入 WebFlux；
- 使用参考实现的 URL 基础地址和 SDK 默认 MCP endpoint；
- 通过 HTTP request customizer 注入展开后的 headers；
- 只声明 2025-11-25；
- 关闭可恢复流的自动恢复行为，不在 Manager 层调用重连；
- 不注册工具列表变更回调，或仅记录通知日志；
- 关闭时调用 SDK 的 closeGracefully。

SDK 的会话、MCP-Session-Id、MCP-Protocol-Version、JSON/SSE 响应和 HTTP GET SSE 处理交给 transport；Manager 只处理成功、失败和关闭，不实现第二套协议栈。

### P4：MCP 工具适配

在 McpManager 内使用 private static McpToolWrapper，减少公共类型和跨模块依赖。

Wrapper 行为：

- 原始工具名保存在 wrapper 中，调用时始终使用原始名称；
- 对 Agent 暴露 mcp_<serverName>_<toolName>；
- 对 Server 发送 tools/call 和 arguments；
- description 和 inputSchema 透传，使用已有 Jackson 2 mapper 转成 Map；
- MCP content 中的文本按顺序拼接；
- 非文本 content 转成可读的结构化文本；
- MCP isError、JSON-RPC、HTTP、进程和超时异常转换为安全的 ToolResult.error；
- category 返回 MCP；
- isReadOnly 返回 false，isDestructive 返回 false，isConcurrencySafe 返回 false；
- validateInput 只做空参数归一化和必要的对象校验，详细 schema 校验交给 SDK 或 Server。

### P5：Registry 和 ToolSearch

修改 Tool.java：

- 增加默认 shouldDefer 方法，默认 false；
- 不破坏现有自定义 Tool 实现。

修改 ToolRegistry：

- 增加当前进程内的 discoveredTools 集合；
- 延迟工具注册后默认不处于模型可见状态；
- 增加 modelVisible、deferredToolNames、findAndDiscover 等最小方法；
- 保留现有 toAPIFormate 的全部工具快照语义，新增 Agent 专用可见性过滤入口；
- 工具注册和 discoveredTools 更新保持线程安全；
- 发现工具名冲突时不覆盖已有工具。

新增 ToolSearchTool：

- 注册到默认 Registry；
- category 使用 SEARCH；
- shouldDefer 返回 false；
- isReadOnly 返回 true，isDestructive 返回 false，isConcurrencySafe 返回 true；
- 只有存在未发现的 MCP 工具时才进入模型工具列表；
- 输入使用完整注册名称做精确查找；
- 找到后返回完整 schema 文本并调用 Registry 标记已发现；
- 找不到时返回错误，不访问 Server，不改变状态。

延迟工具的定义生成规则：

- 内置工具始终按现有规则提供；
- shouldDefer 为 true 且未发现的 MCP 工具不发送完整 schema；
- 已发现的 MCP 工具从下一轮起发送完整 schema；
- ToolSearch 的激活状态由 Registry 每轮计算。

### P6：Agent Loop 和 system-reminder

修改 AgentTurnCoordinator：

- 每轮生成 schema 时增加 Registry 可见性过滤；
- 保留现有 Plan/Execute 和权限分支；
- 将未发现的 MCP 工具名称传给 PromptRequestFactory；
- ToolSearch 执行成功后正常写入 tool-result，下一轮重新计算工具列表；
- 同一轮不动态注入新 schema。

修改 PromptRequestFactory 和 SystemReminderFactory：

- 保留现有构造器和测试语义；
- 增加带延迟工具名称的重载；
- 在每轮 system-reminder 中列出当前未发现的 MCP 工具名称；
- 使用 XML 文本转义处理 Server 或工具名称；
- 不把完整 MCP schema 放进 reminder。

### P7：启动注入和关闭

修改 MewCode：

- ConfigLoader 继续负责项目配置的现有必填校验；
- 启动时加载 MCP 配置；
- MCP 配置问题只记录逐条警告，不阻止 Provider 启动；
- 将有效 McpServerConfig 列表传给 MewCodeModel。

修改 MewCodeModel：

- 增加 MCP 配置和 McpManager 字段；
- 保留现有构造器，旧构造器默认使用空 MCP 配置；
- 初始化 Provider 时创建默认 Registry；
- 创建 Coordinator 前调用 McpManager.connectAll 并注册有效工具；
- 切换或重新初始化 Provider 时先关闭旧 Manager；
- 增加幂等 close，关闭 Manager 和 ToolExecutor。

修改 Program：

- 主循环 finally 中检测并关闭可关闭的 Model；
- 保留现有终端恢复逻辑；
- 退出、异常和 JVM shutdown hook 都不遗留 MCP 子进程。

### P8：测试和验证

新增或修改测试：

- McpConfigLoaderTest：双层合并、同名覆盖、两种字段组合、环境变量展开、非法条目隔离和 Secret 脱敏；
- McpManagerTest：initialize 顺序、版本限制、tools/list 分页、tools/call 重复调用、单 Server 失败隔离和 shutdown；
- MCP 传输集成测试：临时 stdio Server 和本地 HTTP Server 覆盖 JSON、SSE、会话 Header、乱序响应和错误响应；
- McpToolWrapper 测试：名称、schema、参数桥接、文本结果、业务错误和异常结果；
- ToolRegistry/ToolSearch 测试：延迟状态、精确搜索、标记发现、下一轮可见和失败不改变状态；
- AgentTurnCoordinator 测试：初始 schema、system-reminder、ToolSearch 后下一轮 schema 和工具结果配对；
- 既有配置、Agent Loop、权限和内置工具测试全部回归。

按 AGENTS.md 做一次 tmux 端到端验收：

1. 使用临时 stdio MCP Server 配置启动 MewCode；
2. 在 tmux 中输入真实对话请求；
3. 观察首轮只出现 ToolSearch 和 MCP 工具名；
4. 观察 Agent 先调用 ToolSearch，再在下一轮调用 MCP 工具；
5. 观察工具结果和最终回复；
6. 对照 checklist.md 逐项验收；
7. 关闭 MewCode，确认测试 Server 子进程退出。

## 变更文件清单

预计新增：

- src/main/java/com/mewcode/config/McpServerConfig.java
- src/main/java/com/mewcode/config/McpConfigLoader.java
- src/main/java/com/mewcode/mcp/McpManager.java
- src/main/java/com/mewcode/tool/impl/ToolSearchTool.java
- 对应 MCP、配置、Registry、Agent 和 TUI 测试文件

预计修改：

- build.gradle.kts
- src/main/java/com/mewcode/config/AppConfig.java
- src/main/java/com/mewcode/MewCode.java
- src/main/java/com/mewcode/tui/MewCodeModel.java
- src/main/java/com/mewcode/tui/tea/Program.java
- src/main/java/com/mewcode/tool/Tool.java
- src/main/java/com/mewcode/tool/ToolCategory.java
- src/main/java/com/mewcode/tool/ToolRegistry.java
- src/main/java/com/mewcode/agent/AgentTurnCoordinator.java
- src/main/java/com/mewcode/agent/PromptRequestFactory.java
- src/main/java/com/mewcode/prompt/SystemReminderFactory.java

## 明确不改

- 不改 MCP resources、prompts、sampling 等非工具能力；
- 不改现有 Provider 协议和请求格式；
- 不改权限规则语义，只让 MCP Tool 进入现有权限入口；
- 不引入 OAuth、浏览器授权或 Token 刷新；
- 不实现自动重连、健康检查和运行期间重新发现；
- 不实现自定义 JSON-RPC 或 HTTP 协议栈。
