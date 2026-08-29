# MewCode MCP 客户端 Checklist

> 状态：已完成
>
> 本清单基于已确认的 [spec.md](./spec.md)、[plan.md](./plan.md) 和 [task.md](./task.md)。实现前必须完成四份文档审批；实现后每项必须有测试、命令输出或 tmux 观察证据。

## 0. 范围与兼容基线

- [x] 保持 Java 21、现有 Gradle、Provider 请求格式、Agent Loop 轮次、取消和工具结果配对语义不变。`N1/N3/N7/T1/T8`
- [x] 只声明并接受 MCP `2025-11-25`；不做旧版/新版降级、升级或猜测。`F2/F5/F11/A3`
- [x] 复用官方 MCP Java SDK 处理 JSON-RPC、stdio、Streamable HTTP、会话和异步响应配对，不存在第二套协议栈。`F3/F4/F5/T1/T3`
- [x] 不实现 resources、prompts、sampling、OAuth、健康检查、自动重连、运行时重新发现或工具列表刷新。`O1/O3/O4/O5`
- [x] MCP 失败不会阻塞内置工具启动和使用。`F2/F9/F11/N1/A4/A8`

## 1. 配置读取与合并

- [x] 读取用户级 `~/.mewcode/config.yaml` 的 `mcp_servers`。`F1/A1/T2`
- [x] 读取项目级 `<projectRoot>/.mewcode/config.yaml` 的 `mcp_servers`。`F1/A1/T2`
- [x] 不同 Server 名称合并保留。`F1/A1/T2`
- [x] 同名 Server 使用项目级完整条目覆盖，不做字段级深合并。`F1/A1/T2`
- [x] map key 作为 Server 名称，配置项不要求重复 `name` 字段。`F1/A1/T2`
- [x] `command` 存在时按 stdio 处理；`url` 存在时按 HTTP 处理。`F1/A1/T2`
- [x] `command` 和 `url` 同时存在或同时缺失时，当前 Server 无效。`F1/A1/T2`
- [x] stdio 只接受 `command`、`args`、`env`；HTTP 只接受 `url`、`headers`。`F1/A1/T2`
- [x] `args` 必须是字符串列表；`env` 和 `headers` 必须是字符串到字符串的 map。`F1/A1/T2`
- [x] `url` 只接受 HTTP 或 HTTPS URL。`F1/A1/T2`
- [x] `env` 和 `headers` 的值直接读取配置文件字面量；`command`、`args`、`url` 也不做变量展开。`F1/A1/T2`
- [x] 必填字段缺失、类型错误和未知字段只禁用当前 Server。`F1/A1/N4/T2`
- [x] 无效配置不启动进程、不发起 HTTP 请求。`F1/N4/A1/T2`
- [x] 配置错误只包含 Server 名称和字段名，不包含 Token、Secret 或 Header 值。`N4/N5/A1/A8/T2`
- [x] 没有 MCP 配置时，现有配置加载和内置工具行为不变。`F11/A8/T2`

## 2. 会话初始化与协议版本

- [x] 每个有效 Server 最多建立一个当前进程内可复用会话。`F2/F9/N2/A2/A7/T3/T7`
- [x] 可观察到严格的逻辑顺序：`initialize` → `notifications/initialized` → `tools/list`。`F2/A2/T3`
- [x] `initialize` 每个会话只执行一次。`F2/A2/N2/T3`
- [x] `notifications/initialized` 在初始化成功后只执行一次。`F2/A2/T3`
- [x] `tools/list` 的分页请求合并为一次逻辑发现，直到 `nextCursor` 为空。`F2/F6/A2/A5/T3`
- [x] 所有工具发现完成后才注册该 Server 的工具。`F2/F6/A2/A5/T3`
- [x] `tools/call` 可在同一会话内重复执行，不重复握手或发现。`F2/F9/A2/A5/A7/T3`
- [x] 客户端初始化声明支持版本 `2025-11-25`。`F2/F4/A3/T3`
- [x] 能协商到 `2025-11-25` 的双版本 Server 可以正常使用。`N7/A3/T3`
- [x] 服务端返回旧版、新版、缺失版本或非法版本时拒绝该 Server。`F2/A3/T3`
- [x] 版本错误日志包含 Server 名称、服务端版本和客户端支持版本，且不泄露 Secret。`N5/A3/T3`
- [x] 初始化/发现失败会关闭当前资源、跳过当前 Server 并继续其他 Server。`F2/F9/N1/A4/T3/T7`
- [x] 连接建立具有有限超时，不会无限阻塞 MewCode 启动。`F2/N1/T3/T7`

## 3. stdio 传输

- [x] 使用 `command` 和 `args` 直接启动子进程，不通过 Shell 拼接命令。`F3/A1/A4/T3`
- [x] JSON-RPC 请求通过子进程 stdin 发送。`F3/A4/T3`
- [x] JSON-RPC 消息从 stdout 按行读取。`F3/A4/T3`
- [x] stderr 只用于日志，不被当作协议输入。`F3/A4/T3`
- [x] 正确处理 stdio 的请求、响应和通知。`F3/F5/A4/T3`
- [x] 子进程退出、stdout 非法 JSON 或连接断开会转换为工具错误。`F3/F7/A4/A5/T3/T4`
- [x] 子进程异常后不自动重启、不自动重连、不创建替代会话。`F3/F9/O3/A7/T3/T7`
- [x] Server 进程退出后不会影响其他 Server 和内置工具。`F2/F9/A4/T3/T7`

## 4. Streamable HTTP 传输

- [x] MCP 请求通过 HTTP POST 发送。`F4/A4/T3`
- [x] 能处理 JSON 响应和 `text/event-stream` 响应。`F4/A4/T3`
- [x] 请求携带 `MCP-Protocol-Version: 2025-11-25`。`F4/A3/A4/T3`
- [x] 服务端返回 `MCP-Session-Id` 后保存，并在后续请求中继续携带。`F4/A4/A7/T3`
- [x] SDK transport 能处理 HTTP GET SSE 通道的服务端消息。`F4/A4/T3`
- [x] 配置中的 headers 随 HTTP 请求发送，字面量值正确生效。`F1/F4/A1/A4/T2/T3`
- [x] HTTP 状态错误、响应格式错误、会话失效、超时和断开会转换为工具错误。`F4/F7/A4/A5/T3/T4`
- [x] 不实现 OAuth、浏览器授权和 Token 刷新。`F4/O5/T3`
- [x] 不按 2026-07-28 modern 传输的无初始化/无会话语义发送请求。`F4/O2/A3/A4/T3`
- [x] HTTP 连接异常后不自动重连或创建替代会话。`F4/F9/O3/A7/T3/T7`

## 5. JSON-RPC 与消息配对

- [x] 单个 Server 连接内的请求 ID 唯一。`F5/N3/A2/A4/T3`
- [x] 响应按 ID 关联请求，乱序响应不会串联到错误的调用。`F5/N3/A4/A5/T3`
- [x] 正确区分成功响应、JSON-RPC error response 和通知。`F5/A4/A5/T3/T4`
- [x] 未知或无需处理的通知不会导致连接崩溃。`F5/T3`
- [x] 未实现的服务端请求返回标准 `Method not found` 错误。`F5/T3`
- [x] 基础 `ping` 交互可正常处理。`F5/T3`
- [x] resources、prompts、sampling 请求不会触发未实现能力。`F5/O1/T3`
- [x] 请求超时、断开、非法响应和协议错误最终产生 Agent 可理解的 ToolResult 错误。`F5/F7/A4/A5/T3/T4`

## 6. 工具发现、注册与适配

- [x] 读取并保留 MCP 工具的 name、description、inputSchema 和可用输出定义。`F6/A5/T3/T4`
- [x] 工具名缺失、schema 非法或无法转换时跳过该工具并记录原因。`F6/N5/A5/T4`
- [x] 对外注册名使用稳定格式 `mcp_<serverName>_<toolName>`。`F6/F7/A5/T4`
- [x] Server 名称和工具名称中的非法字符使用稳定转义。`F6/A5/T4`
- [x] 注册名冲突时不覆盖已有工具，不静默改名，记录并跳过冲突工具。`F6/A5/T4`
- [x] wrapper 调用时向正确 Server 发送原始工具名和 arguments。`F7/A5/T4`
- [x] description 和 input schema 可由 ToolSearch 返回。`F7/F8/A5/A6/T4/T5`
- [x] 文本 content 按顺序转换为 ToolResult 文本。`F7/A5/T4`
- [x] 非文本 content 转换为 Agent 可读的结构化文本。`F7/A5/T4`
- [x] MCP `isError`、JSON-RPC、HTTP、进程和超时错误均转换为 `ToolResult.error`。`F7/A4/A5/T4`
- [x] MCP annotations 不会降低 MewCode 权限风险或绕过 ToolExecutor。`F7/F10/A8/T4/T7`
- [x] `notifications/tools/list_changed` 只记录日志，不触发当前会话重新发现。`F6/F9/O4/A7/T3/T7`

## 7. 延迟加载与 ToolSearch

- [x] MCP 工具完整注册在本地 Registry，不因延迟加载而丢失。`F8/A6/T5`
- [x] MCP wrapper 的 `shouldDefer()` 固定为 true。`F8/T5`
- [x] 首轮模型工具列表不包含未发现 MCP 工具的完整 schema。`F8/N2/A6/T5/T6`
- [x] 内置工具仍按现有规则进入模型工具列表。`F8/F11/A6/A8/T5/T6`
- [x] 存在未发现 MCP 工具时，ToolSearch 对模型可见；不存在时不继续提供。`F8/A6/T5/T6`
- [x] system-reminder 只列出未发现 MCP 工具名称，不包含完整 schema。`F8/A6/T6`
- [x] Server 名称和工具名称写入 reminder 前完成 XML 文本转义。`F8/A6/T6`
- [x] ToolSearch 按完整注册名称精确查找本地 Registry。`F8/A6/T5`
- [x] ToolSearch 成功返回完整工具定义并标记已发现。`F8/A6/T5`
- [x] 工具从下一轮开始出现在正常模型工具列表。`F8/A6/T5/T6`
- [x] 同一轮不会动态注入刚发现的完整 schema。`F8/A6/T6`
- [x] ToolSearch 不发起远程 `tools/list` 或其他 Server 请求。`F8/N2/A6/T5`
- [x] 查找失败返回错误且不改变 Registry 状态。`F8/A6/T5`
- [x] 已发现状态只存在于当前进程，重启后重新延迟。`F8/O7/A6/T5/T7`
- [x] 不提供模糊搜索、语义搜索或远端搜索。`F8/O7/A6/T5`

## 8. 权限、错误和安全

- [x] MCP 工具调用统一经过现有 ToolExecutor 和权限流程。`F7/F10/A8/T4/T5/T7`
- [x] MCP wrapper 不直接执行外部操作，不绕过确认、取消、超时和错误处理。`F7/F10/A8/T4/T7`
- [x] MCP 工具不会因为来源是外部 Server 而自动放行。`F10/A8/T4/T7`
- [x] MCP 工具的风险标记不采信 Server annotations，使用 MewCode 本地策略。`F7/F10/A8/T4/T7`
- [x] 配置文件中的 API key、环境变量值、Authorization 和其他敏感 Header 不进入日志、异常或 Agent 可见内容。`F1/N4/N5/A1/A8/T2/T3/T4`
- [x] 配置、协议、连接和工具错误都返回安全且可定位的错误，不终止 Agent Loop。`F2/F7/F10/F11/A4/A5/A8/T4/T6`

## 9. 连接缓存与生命周期

- [x] 同一 Server 的多次 tools/call 复用同一个 client/transport。`F9/N2/A7/T3/T7`
- [x] 每个 Server 的 client、工具来源和错误状态彼此隔离。`F9/N3/A4/A7/T3/T7`
- [x] 初始化失败的 Server 不进入缓存、不注册工具。`F2/F9/A4/A7/T3/T7`
- [x] 已建立连接失效后，已有工具调用返回错误，不创建新连接。`F9/O3/A4/A7/T3/T7`
- [x] 配置变更、工具列表变化和连接失效不会自动刷新工具。`F9/O3/O4/A7/T3/T7`
- [x] 应用退出时关闭 HTTP 会话、流、stdio 管道和子进程。`F9/N6/A7/T7/T8`
- [x] 一个资源关闭失败时仍继续清理其他 Server 资源，并记录错误。`N6/T7`
- [x] 不把连接、发现结果或已发现状态持久化到磁盘。`F8/O7/N6/A6/A7/T7`
- [x] Provider 切换和重复 close 不留下旧 Manager 或子进程。`F9/A7/T7`

## 10. Agent Loop 与既有行为回归

- [x] ToolSearch 结果按普通 tool-result 写回，assistant tool-use 与 tool-result 保持配对。`F8/F11/A6/A8/T6`
- [x] ToolSearch 后下一轮重新计算工具列表，其他工具调用仍可继续。`F8/F11/A6/T6`
- [x] MCP 工具失败不会终止 Agent Loop。`F7/F11/A5/A8/T4/T6`
- [x] 内置工具名称、schema、注册顺序和执行逻辑没有无关变化。`F11/A8/T5/T6/T7`
- [x] 现有 Provider 配置、权限、/plan、/do 和历史提交语义没有回归。`F11/A8/T6/T7/T8`
- [x] MCP 全部连接失败时，内置工具仍可正常完成任务。`F2/F9/F11/A4/A8/T7/T8`

## 11. 自动化测试与构建证据

- [x] `McpConfigLoaderTest` 覆盖双层合并、完整覆盖、字段校验、字面量敏感值、Secret 脱敏和单条隔离。`A1/T2`
- [x] `McpManagerTest` 覆盖握手顺序、版本拒绝、分页、重复调用、错误隔离和 shutdown。`A2/A3/A7/T3/T7`
- [x] `McpTransportIntegrationTest` 覆盖 stdio、HTTP POST、JSON/SSE、会话 ID、协议 Header、乱序响应和错误响应。`A2/A4/T3`
- [x] `McpToolWrapperTest` 覆盖名称、schema、arguments、文本/非文本结果、业务错误和异常。`A5/T4`
- [x] Registry/ToolSearch 测试覆盖延迟、精确查找、标记发现、下一轮可见、远端零请求和失败不改状态。`A6/T5`
- [x] `AgentTurnCoordinatorTest` 覆盖首轮 schema、reminder、ToolSearch 结果、下一轮 schema 和调用 ID 配对。`A6/A8/T6`
- [x] `MewCodeModelTest` 覆盖启动注入、空配置回归、Provider 切换、重复 close 和子进程清理。`A7/A8/T7`
- [x] 全量 `./gradlew test` 通过。`A8/T8`
- [x] 既有配置、Provider、权限、Agent、工具和 TUI 测试全部通过。`F11/A8/T8`
- [x] 测试没有通过关闭版本校验、权限入口、错误处理或资源清理来规避失败。`F2/F10/F11/T8`

## 12. tmux 端到端验收

- [x] 使用临时 stdio MCP Server 配置，在 tmux 中启动真实 MewCode。`A2/A4/T8`
- [x] 输入真实开发请求，MewCode 启动完成并继续正常 Agent Loop。`A8/T8`
- [x] 首轮观察到内置工具和 ToolSearch；未观察到 MCP 工具完整 schema。`A6/T8`
- [x] system-reminder 观察到未发现 MCP 工具名称。`A6/T8`
- [x] Agent 先调用 ToolSearch，下一轮再调用 MCP 工具。`A5/A6/T8`
- [x] MCP 工具结果和最终回复正确显示。`A5/A6/A8/T8`
- [x] 同一 Server 连续调用时没有重复 initialize 或 tools/list。`A2/A7/T8`
- [x] 配置一个故障 Server 时，其他有效 Server 和内置工具仍工作。`A1/A4/A8/T8`
- [x] 退出 MewCode 后临时 MCP Server 子进程已退出，无残留连接。`A7/T8`
- [x] 记录测试命令、运行平台、tmux 观察结果和所有失败项；每个必选项都有证据。`A1–A8/T8`

## 13. 完成标准

- [x] `spec.md`、`plan.md`、`task.md` 和本 `checklist.md` 状态均与实际阶段一致。`T8`
- [x] 本清单所有必选项均已勾选并附验证证据。`T8`
- [x] 没有实现“明确不做”的能力，也没有为了通过测试而放宽协议版本或安全边界。`O1–O7/T8`

## 验收记录

- `./gradlew test --no-daemon`：全量测试通过。
- `./gradlew shadowJar --no-daemon`：可执行 JAR 构建通过。
- 聚焦测试覆盖配置合并、MCP stdio 会话分页/重复调用/版本隔离、Streamable HTTP JSON/SSE/Session Header、ToolSearch 延迟发现和 Agent Loop。
- tmux 观察结果：首轮仅出现 `ToolSearch` 与 reminder 中的 `mcp_local_local_echo`；随后按 `ToolSearch → mcp_local_local_echo → MCP tmux success` 完成对话；stdio 日志为 `initialize → notifications/initialized → tools/list → tools/call`，退出后测试 Server 进程结束。
- `spotlessCheck`：项目现有 Google Java Format 1.24.0 对 Java 21 语法和原有四空格文件仍报告格式差异；不影响编译、测试和运行验收。
