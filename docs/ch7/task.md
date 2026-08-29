# MewCode MCP 客户端 Tasks

> 状态：已确认
>
> 本任务清单基于已确认的 [spec.md](./spec.md) 和 [plan.md](./plan.md)。四份文档全部获批前禁止编写实现代码。

## 实现约束

- 保持 Java 21、现有 Gradle、Provider 请求格式、Agent Loop 轮次、取消和工具结果配对语义不变。
- MCP 只支持协议版本 `2025-11-25`；服务端返回旧版本、新版本、缺失版本或非法版本时拒绝该 Server，不降级、不猜测、不影响其他 Server。
- 复用官方 MCP Java SDK 处理 JSON-RPC、stdio、Streamable HTTP、会话和异步响应配对；不手写第二套协议栈。
- MCP 配置按用户级先、项目级后合并；项目级同名 Server 整条覆盖；单条配置错误只禁用当前 Server。
- MCP 工具必须经过现有 `ToolExecutor` 和权限入口；外部 Server 的描述、注解和返回内容不能绕过权限或执行器。
- 延迟工具只延迟模型可见的完整 schema，不延迟本地 Registry 注册；`ToolSearch` 只查本地 Registry，不访问 Server。
- 不实现 resources、prompts、sampling、OAuth、健康检查、自动重连、运行时重新发现或工具列表刷新。
- 新增代码和测试使用中文注释；敏感配置值不得进入异常、启动日志或工具结果。

## 文件清单

| 操作 | 文件 | 职责 |
|---|---|---|
| 修改 | `build.gradle.kts` | 引入 MCP Java SDK BOM、core 和 Jackson 2 模块 |
| 新建 | `src/main/java/com/mewcode/config/McpServerConfig.java` | 单个 MCP Server 的规范化配置 |
| 新建 | `src/main/java/com/mewcode/config/McpConfigLoader.java` | 双层配置合并、敏感值读取和逐条校验 |
| 修改 | `src/main/java/com/mewcode/config/AppConfig.java` | 保留项目级 `mcp_servers` 原始 map |
| 新建 | `src/main/java/com/mewcode/mcp/McpManager.java` | 连接、会话、工具发现、缓存、注册和关闭 |
| 修改 | `src/main/java/com/mewcode/tool/Tool.java` | 增加默认 `shouldDefer()` |
| 修改 | `src/main/java/com/mewcode/tool/ToolCategory.java` | 增加 MCP 类别 |
| 修改 | `src/main/java/com/mewcode/tool/ToolRegistry.java` | 延迟状态、发现状态和 Agent 可见性 |
| 新建 | `src/main/java/com/mewcode/tool/impl/ToolSearchTool.java` | 精确查找并发现延迟 MCP 工具 |
| 修改 | `src/main/java/com/mewcode/agent/AgentTurnCoordinator.java` | 每轮过滤工具 schema 和传递延迟名称 |
| 修改 | `src/main/java/com/mewcode/agent/PromptRequestFactory.java` | 支持带延迟工具名的请求构造 |
| 修改 | `src/main/java/com/mewcode/prompt/SystemReminderFactory.java` | 输出延迟工具名称并转义 XML 文本 |
| 修改 | `src/main/java/com/mewcode/MewCode.java` | 启动时加载 MCP 配置并注入模型 |
| 修改 | `src/main/java/com/mewcode/tui/MewCodeModel.java` | 启动连接、Provider 切换和幂等关闭 |
| 修改 | `src/main/java/com/mewcode/tui/tea/Program.java` | 退出时关闭可关闭模型 |
| 新建 | `src/test/java/com/mewcode/config/McpConfigLoaderTest.java` | 配置合并和校验 |
| 新建 | `src/test/java/com/mewcode/mcp/McpManagerTest.java` | 会话顺序、版本和故障隔离 |
| 新建 | `src/test/java/com/mewcode/mcp/McpTransportIntegrationTest.java` | stdio、HTTP、SSE 和会话行为 |
| 新建 | `src/test/java/com/mewcode/mcp/McpToolWrapperTest.java` | 工具适配和结果转换 |
| 修改/新建 | `src/test/java/com/mewcode/tool/ToolRegistryTest.java`、`ToolSearchToolTest.java` | 延迟与发现状态 |
| 修改 | `src/test/java/com/mewcode/agent/AgentTurnCoordinatorTest.java` | Agent Loop 延迟工具行为 |
| 修改 | `src/test/java/com/mewcode/tui/MewCodeModelTest.java` | 生命周期回归 |

## 任务拆分

### T1：引入 SDK 并确认依赖边界

**依赖：** 无。

**步骤：**

1. 在 `build.gradle.kts` 使用 MCP Java SDK BOM `2.0.1`。
2. 添加 `mcp-core` 和 `mcp-json-jackson2`，不添加 Jackson 3 convenience bundle、Spring 或其他 HTTP 客户端。
3. 保留现有 Jackson 2 版本和 Java 21 配置。
4. 执行依赖解析和空编译，确认 SDK 类型可被项目引用。

**验证：** Gradle 编译通过，依赖树没有引入未计划的 MCP 传输实现或 Jackson 3 冲突。

### T2：实现 MCP 配置模型和双层加载

**依赖：** T1。

**步骤：**

1. 增加 `McpServerConfig`，由 map key 作为 Server 名称，不重复保存 `name` 配置字段。
2. 在 `AppConfig` 保留项目级 `mcp_servers` 原始 map，不让现有 Provider 配置校验被 MCP 单条错误拖垮。
3. 从用户级 `~/.mewcode/config.yaml` 读取可选 `mcp_servers`，再合并项目级 map。
4. 对同名 Server 使用项目级完整条目覆盖，不做字段级深合并。
5. 识别 stdio：必须有 `command`，可选 `args`、`env`；识别 HTTP：必须有 `url`，可选 `headers`。
6. 拒绝同时设置 `command` 和 `url`、两者都缺失、字段混用、未知字段和错误类型。
7. 对 `env` 与 `headers` 的字符串值直接读取配置文件字面量，不从运行时环境展开变量。
8. 返回有效配置及逐条错误；错误只包含 Server 名称和字段名，不输出 Secret 值。

**验证：** 覆盖双层合并、完整覆盖、两种合法形态、字段混用、字面量敏感值、类型错误、未知字段和单条隔离。

### T3：实现单 Server 会话与两种传输

**依赖：** T1、T2。

**步骤：**

1. 在 `McpManager` 中为每个有效配置创建 stdio 或 Streamable HTTP transport。
2. stdio 使用 SDK `ServerParameters` 的 command、args、env 和 stdin/stdout 管道，不拼接 Shell 命令。
3. HTTP 使用 SDK 的 JDK HttpClient transport；以配置 URL 作为基础地址，通过 request customizer 注入 headers。
4. transport 仅声明 `2025-11-25`，避免默认协商到其他版本。
5. 用 SDK 同步客户端设置 120 秒请求超时。
6. 严格执行一次会话顺序：`initialize` → SDK 的 `notifications/initialized` → `tools/list`；`tools/call` 可在会话内重复。
7. 初始化后校验服务端返回的 `protocolVersion`；旧版、新版、缺失或非法版本均关闭当前连接并报错。
8. `tools/list` 按 `nextCursor` 分页，直到没有游标；不要在运行期注册列表变更刷新。
9. 单个 Server 任一步骤失败时关闭当前资源并继续其他 Server。

**验证：** 使用可记录消息顺序的 fake Server 断言握手只执行一次、列工具分页完整、调用可重复、版本不匹配拒绝、乱序响应按 request id 配对、单 Server 失败不影响其他连接。

### T4：实现 MCP 工具适配和结果转换

**依赖：** T3。

**步骤：**

1. 在 `McpManager` 内使用私有 `McpToolWrapper`，保存 Server 名称、原始工具名、描述、schema 和 client 引用。
2. 对 Agent 暴露唯一名称 `mcp_<serverName>_<toolName>`，调用时向 Server 发送原始工具名。
3. 将 MCP `inputSchema` 转成现有 `Tool.inputSchema()` 的 Jackson 2 `Map`；无法转换的工具使当前 Server 发现失败并清理已创建资源。
4. `execute` 将输入作为 `arguments` 调用 `tools/call`；文本 content 按顺序拼接，非文本 content 转为可读结构化文本。
5. 将 MCP `isError`、JSON-RPC、HTTP、进程退出和超时转换为安全的 `ToolResult.error`，不把密钥写入结果。
6. 返回 MCP 类别；`isReadOnly=false`、`isDestructive=false`、`isConcurrencySafe=false`；空参数做最小归一化。
7. 工具名称冲突时保留已有 Registry 工具，不覆盖、不静默改名，并记录不含 Secret 的错误。

**验证：** 覆盖名称、schema、arguments 桥接、文本/非文本结果、业务错误、异常和冲突行为。

### T5：接入 Registry 和 ToolSearch 延迟发现

**依赖：** T4。

**步骤：**

1. 给 `Tool` 增加默认 `shouldDefer=false`，保证已有工具编译和行为不变。
2. MCP wrapper 的 `shouldDefer` 固定返回 `true`；内置工具保持非延迟。
3. 在 `ToolRegistry` 增加线程安全的未发现/已发现状态，保留所有工具本地注册。
4. 增加 Agent 专用可见性查询：未发现的 MCP 工具不返回完整 schema，已发现工具从下一轮开始返回。
5. 增加 `ToolSearchTool`：只读、非破坏、可并发；存在未发现 MCP 工具时才对模型可见。
6. `ToolSearch` 只按完整注册名称精确查找；成功时返回完整 schema 文本并标记发现，失败时返回错误且不改变状态。
7. 保留现有 `toAPIFormate` 的全部工具快照语义，避免影响既有调用方。

**验证：** 覆盖注册后延迟、精确查找、标记发现、下一轮可见、失败不改变状态、无 MCP 时的既有工具列表和并发注册。

### T6：接入 Agent Loop 和 system-reminder

**依赖：** T5。

**步骤：**

1. `AgentTurnCoordinator` 每轮只向模型提供当前可见工具 schema。
2. 将未发现 MCP 工具名传给 `PromptRequestFactory` 和 `SystemReminderFactory`；reminder 只列名称，不放完整 schema。
3. 对 Server 名称和工具名称做 XML 文本转义，避免名称破坏 reminder 结构。
4. 模型调用 `ToolSearch` 后，将其结果按普通 tool-result 写回；不在同一轮动态注入 MCP schema。
5. 下一轮重新从 Registry 计算 schema，使刚发现的 MCP 工具正常进入工具列表。
6. 保留现有 Plan/Execute、权限过滤、轮次上限、取消、超时和工具调用 ID 配对。

**验证：** 断言首轮只出现 ToolSearch 和名称 reminder，ToolSearch 成功后下一轮出现完整 schema，工具调用结果保序，既有 Agent Loop 测试不回归。

### T7：接入启动、切换和关闭生命周期

**依赖：** T2、T3、T5、T6。

**步骤：**

1. `MewCode` 启动时加载 MCP 配置；MCP 配置错误只输出脱敏警告，不阻塞 Provider 初始化。
2. `MewCodeModel` 创建默认 Registry 后，启动 Coordinator 前调用 `McpManager.connectAll` 并注册成功 Server 的 wrapper。
3. 缓存每个成功 Server 的 config、client 和工具来源；连接失败 Server 不进入缓存。
4. Provider 切换或重新初始化时先关闭旧 Manager，避免留下子进程和 HTTP 会话。
5. `MewCodeModel` 实现幂等关闭，关闭 Manager 和 ToolExecutor；旧构造器使用空 MCP 配置。
6. `Program` 在现有终端恢复逻辑的 finally 中关闭可关闭 Model；退出、异常和 shutdown hook 不遗留 stdio 子进程。

**验证：** 覆盖空配置回归、启动连接、Provider 切换、重复 close、异常退出和子进程回收。

### T8：测试、验收和回归

**依赖：** T1–T7。

**步骤：**

1. 完成配置、Manager、wrapper、Registry、ToolSearch、Agent Loop 和生命周期单测。
2. 用临时 Java stdio Server 验证完整握手、分页、重复调用、错误响应和进程退出。
3. 用本地 HTTP Server 验证 Streamable HTTP、请求头、会话 ID、JSON/SSE 响应和协议版本 Header。
4. 执行全量 `./gradlew test`，修复与既有 Provider、权限、TUI 和工具测试的回归。
5. 按 `AGENTS.md` 在 tmux 中启动 MewCode，输入真实对话，观察首轮延迟工具、ToolSearch、下一轮 MCP 调用和最终回复。
6. 对照 `checklist.md` 逐项验收，并确认退出后没有残留测试 Server 子进程。

**验证：** 全量测试通过，tmux 端到端流程完成，checklist 全部可勾选；失败项记录为阻塞问题，不以跳过测试代替通过。

## 明确不做

- 不实现 MCP resources、prompts、sampling 或其他非工具能力。
- 不实现旧版/新版协议兼容、版本降级、能力猜测或多协议 fallback。
- 不实现健康检查、自动重连、自动重启、运行时重新发现和列表刷新。
- 不实现 OAuth、浏览器授权、Token 刷新或凭据管理。
- 不修改 Provider 协议、现有权限语义或内置工具的默认可见性。
- 不新增独立 MCP 协议栈、通用传输抽象层或不必要的公共类。
