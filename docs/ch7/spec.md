# MewCode MCP 客户端 Spec

> 状态：已确认

## 背景

MewCode 已有统一的工具注册、权限执行和 Agent Loop，但目前只能使用内置工具，无法将外部 MCP Server 提供的工具接入工具中心。

本章为 MewCode 增加 MCP 客户端能力：启动时从用户级和项目级配置读取 Server，使用 MCP 2025-11-25 协议完成会话初始化和工具发现，再将远端工具适配为现有的 MewCode Tool。Agent 不需要感知工具来自本地还是 MCP Server。

## 目标

- 支持本地 stdio 子进程和远程 Streamable HTTP 两种传输。
- 在首次 Agent 请求前完成有效 MCP Server 的初始化和工具发现。
- 复用已有 Tool、ToolRegistry、ToolExecutor 和权限机制。
- 通过延迟加载减少首轮发送给模型的工具 schema。
- 支持用户级、项目级 MCP Server 配置合并。
- 缓存多个 Server 的连接并管理生命周期；单个 Server 失败不影响其他 Server 和内置工具。
- 第一版只兼容能够协商到 MCP 2025-11-25 的 Server。

## 协议基线

- 使用 JSON-RPC 2.0。
- 客户端初始化时声明 2025-11-25，服务端协商结果为该版本时继续会话。
- 能协商到 2025-11-25 的双版本 Server 可以使用；现代专属 Server 不在首版支持范围内。
- 本章按 2025-11-25 的 legacy 会话模型实现，不实现 2026-07-28 modern 模式。
- 参考协议：
  - [MCP 2025-11-25 Lifecycle](https://modelcontextprotocol.io/specification/2025-11-25/basic/lifecycle)
  - [MCP 2025-11-25 Transports](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports)
  - [MCP 2025-11-25 Tools](https://modelcontextprotocol.io/specification/2025-11-25/server/tools)

## 功能需求

### F1：配置读取与合并

MewCode 从以下文件读取 MCP 配置：

- 用户级：~/.mewcode/config.yaml
- 项目级：<projectRoot>/.mewcode/config.yaml

配置根节点使用 mcp_servers map，每个 key 是 Server 名称，配置项本身不再重复声明 name。项目级和用户级的合并规则如下：

- 不同 Server 名称合并保留；
- 同名 Server 使用项目级完整配置覆盖用户级完整配置；
- 只对 mcp_servers 使用上述合并规则，现有 providers、Agent 和权限配置行为保持不变。

传输类型由字段判断，不增加 type 字段：

- stdio：command 必填，args 和 env 可选；
- HTTP：url 必填，headers 可选；
- command 和 url 必须二选一，不能同时填写，也不能同时缺失；
- args、env 只能用于 stdio；
- headers 只能用于 HTTP；
- 配置项中出现与所选传输无关的字段时，该 Server 配置无效。

env 的值和 headers 的值直接读取配置文件中的字符串，不从 MewCode 进程环境展开变量。缺失必填字段或字段类型错误时，该 Server 配置无效，不启动进程、不发起请求，并记录明确原因。stdio 仍需使用 Server 要求的环境变量名，但变量值由配置文件直接提供。

示例：

    mcp_servers:
      github:
        command: github-mcp-server
        args: [stdio]
        env:
          GITHUB_PERSONAL_ACCESS_TOKEN: replace-with-github-token
      remote:
        url: https://example.com/mcp
        headers:
          Authorization: "Bearer replace-with-mcp-token"

### F2：启动初始化与会话流程

每个有效 Server 建立一个可复用的 MCP 会话，并在首次 Agent 请求前完成初始化和工具发现。一次完整会话的逻辑流程为：

    initialize
    notifications/initialized
    tools/list
    tools/call × N

要求：

- initialize 每个会话只执行一次；
- notifications/initialized 在初始化成功后只执行一次；
- tools/list 完成一次逻辑发现，返回 nextCursor 时允许继续分页请求；
- 所有分页完成后才注册该 Server 的有效工具；
- tools/call 可以在同一会话内重复执行；
- 初始化或发现失败时关闭并跳过该 Server，记录警告，其他 Server 和内置工具继续工作；
- 连接失败不会阻塞 MewCode 启动到无限等待。

### F3：stdio 传输

- 使用 command 和 args 直接启动本地子进程；
- 通过子进程 stdin 发送 JSON-RPC 消息；
- 从子进程 stdout 读取换行分隔的 JSON-RPC 消息；
- 子进程 stderr 只用于日志，不作为协议输入；
- 正常处理请求、响应和通知；
- 子进程退出、stdout 出现非法 JSON 或连接断开时，将当前及后续调用转换为工具错误；
- 不自动重启或重连子进程。

### F4：Streamable HTTP 传输

按 2025-11-25 Streamable HTTP 语义：

- MCP 请求通过 HTTP POST 发送；
- 接受 JSON 响应和 text/event-stream 响应；
- MCP 请求携带 MCP-Protocol-Version；
- 服务端返回 MCP-Session-Id 时保存该会话 ID，后续请求继续携带；
- 支持通过 HTTP GET 的 SSE 通道接收服务端消息；
- 配置中的静态 headers 随请求发送；
- HTTP 状态错误、响应格式错误、会话失效、超时或断开转换为工具错误；
- 不实现 OAuth、浏览器授权或 Token 刷新；
- 不按 2026-07-28 的无初始化、无会话现代传输语义处理请求；
- 不自动重连或创建替代会话。

### F5：JSON-RPC 消息处理

- 每个 Server 连接生成唯一请求 ID；
- 响应按 ID 与请求关联，支持响应乱序返回；
- 正确区分成功响应、JSON-RPC error response 和通知；
- 未知或无需处理的通知不导致连接崩溃；
- 对未实现的服务端请求返回标准的 Method not found 错误；
- 支持 MCP 会话所需的基础协议交互，包括 ping；
- 请求超时、连接断开、非法响应和协议错误都产生可被 Agent 理解的工具错误；
- 未实现 resources、prompts、sampling 等能力的服务端请求不触发对应能力。

### F6：工具发现与注册

- 使用 tools/list 发现工具，支持 nextCursor 分页；
- 读取工具的 name、description、inputSchema，并保留可用的输出定义；
- 名称缺失、schema 非法或无法转换为 MewCode Tool 的工具跳过并记录原因；
- 使用稳定的全局名称注册工具，默认格式为 mcp_<serverName>_<toolName>；
- Server 名称或工具名称中的非法字符使用稳定转义，生成名称冲突时不得覆盖已有工具，冲突工具跳过并记录原因；
- 收到 notifications/tools/list_changed 时只记录日志，不在当前会话自动重新发现工具。

### F7：MCP 工具适配与调用

通过适配器将 MCP 工具包装为现有 MewCode Tool：

    MCPToolWrapper implements Tool

适配行为：

- name 返回稳定的全局注册名称；
- description 和输入 schema 来自 MCP 工具定义；
- 执行时把 MewCode 参数作为 tools/call 的 arguments 发送给原始 Server；
- 调用结果中的文本内容按现有 ToolResult 规则返回；
- 非文本内容转换为 Agent 可读的结构化文本；
- MCP isError、JSON-RPC 错误、HTTP 错误、进程错误和超时都转换为错误的 ToolResult；
- 不把传输、会话或 JSON-RPC 细节作为模型必须理解的协议要求；
- MCP Server 的 annotations 不得绕过 MewCode 现有权限机制。

### F8：工具延迟加载

MCP 工具在本地 Registry 中完整注册，但默认标记为延迟工具。

- 每轮生成模型工具列表时，内置工具始终按现有规则提供；
- MCP 工具的完整 schema 默认不进入模型工具列表；
- system-reminder 只列出尚未向模型发现的 MCP 工具名称；
- 存在未发现的 MCP 工具时提供 ToolSearch；
- ToolSearch 使用工具的完整注册名称在本地 Registry 中精确查找；
- 查找成功后返回完整工具定义，并将该工具标记为已发现；
- 工具从下一轮开始进入正常工具列表，同一轮不动态注入；
- 查找失败不改变工具状态，也不请求 MCP Server；
- 已发现状态只在当前 MewCode 进程内有效；
- 不做模糊搜索、语义搜索或远端搜索；
- 没有未发现的 MCP 工具时不需要继续提供 ToolSearch。

### F9：连接缓存与生命周期

- 每个已配置 Server 在当前进程内最多维护一个可复用连接；
- 同一 Server 的多次 tools/call 复用该连接；
- 一个 Server 的失败不影响其他 Server；
- 初始化失败的 Server 不注册工具；
- 已建立连接后来失效时，已有工具调用返回错误，不自动创建新连接；
- 应用退出时关闭所有 HTTP 会话、流、stdio 管道和本地子进程；
- 配置变更、工具列表变化或连接失效不会触发自动刷新。

### F10：权限与安全边界

- MCP 工具调用统一经过现有 ToolExecutor 和权限流程；
- MCP 工具不直接执行，适配器不能绕过权限、确认、取消和错误处理；
- 未被明确允许的外部工具按现有默认策略处理，不能因为来自 MCP 就自动放行；
- 不信任 MCP Server 返回的 annotations 来降低风险等级；
- 配置文件中的 API key、环境变量值和 HTTP Header 只用于启动进程或发送请求；
- 环境变量和 HTTP Header 中的敏感值不得出现在日志、异常信息或 Agent 可见内容中。

### F11：既有行为兼容

- 内置工具继续由现有 ToolRegistry 注册和执行；
- 现有 Provider 配置、权限配置、Agent Loop、工具结果配对、/plan 和 /do 行为保持不变；
- 所有 MCP Server 都失败时，MewCode 仍可正常使用内置工具；
- MCP 工具错误不会终止 Agent Loop。

## 非功能需求

### N1：可靠性

- 外部进程和网络操作必须有有限等待时间，不得无限阻塞；
- 单个 Server 的异常必须被隔离在该 Server 的连接和工具调用范围内；
- MCP 初始化失败不能阻止 MewCode 继续提供内置能力。

### N2：性能

- 每个 Server 在一次会话中只做一次初始化和一次逻辑工具发现；
- 后续 Agent Loop 不重复握手、发现或建立连接；
- 延迟加载时首轮不发送 MCP 工具完整 schema；
- ToolSearch 使用本地 Registry，不为查 schema 增加一次远程请求。

### N3：并发正确性

- 请求 ID 在单个连接内唯一；
- 乱序响应不会导致工具结果串联；
- 多个 Server 的调用状态互不共享；
- 并发访问 Registry 和会话状态不会覆盖工具或错误关联。

### N4：安全

- 配置错误和协议错误不得默认转为允许；
- 敏感配置只用于启动进程或发送请求，不进入普通日志；
- 外部工具始终经过现有权限执行入口。

### N5：可观测性

- 日志至少包含 Server 名称、传输类型、生命周期阶段和失败原因；
- 协议版本不兼容时记录服务端返回版本和客户端支持版本；
- 工具注册冲突、工具跳过和工具调用失败可定位到具体 Server 和工具；
- 日志不得泄露 Secret、Token 或 Header 敏感值。

### N6：资源管理

- 会话关闭后不遗留子进程、输入输出流、HTTP 连接或后台监听；
- 应用退出清理失败时记录错误，但不能阻止其他资源继续清理；
- 不把连接或发现结果持久化到磁盘。

### N7：兼容性

- 不要求 MCP Server 必须只支持 2025-11-25，只要能与客户端协商到该版本即可；
- 现代专属 Server 被清晰拒绝，不影响其他 Server；
- 不改变 MewCode 既有配置和工具调用行为。

## 首版不做范围

### O1：非工具能力

不实现 Resources、Prompts、Sampling 及其他非工具扩展能力。

### O2：其他协议时代

- 不实现 MCP 2026-07-28 modern 模式；
- 不实现 2024 及更早的 HTTP+SSE 传输；
- 不对不兼容版本做隐式降级或升级。

### O3：连接自愈

- 不做健康检查；
- 不做自动重连；
- 不做自动重启；
- 不因连接失败自动创建替代会话。

### O4：运行期间的工具重新发现

每个 Server 每次会话只执行一次初始化和工具发现。运行期间不自动重新建立会话或刷新工具列表；工具调用可以在同一会话内重复执行。收到 notifications/tools/list_changed 时只记录日志。工具变更需重启 MewCode 建立新会话后生效。

### O5：复杂认证

不实现 OAuth、浏览器授权、Token 刷新、凭据管理或远程登录流程。HTTP 只使用配置中的静态 URL 和 Headers。

### O6：配置管理界面

不新增配置编辑 UI、配置管理命令或远程配置中心，只读取用户级和项目级配置文件。

### O7：跨进程持久化

不持久化 MCP 连接、工具发现结果或 ToolSearch 的已发现状态。

## 验收标准

### A1：配置

- 用户级和项目级 mcp_servers 都能被读取；
- 同名 Server 由项目级完整配置覆盖；
- command 存在时按 stdio 创建连接，url 存在时按 HTTP 创建连接；
- command 和 url 同时存在或同时缺失时，该 Server 被判定为无效；
- stdio 专属字段和 HTTP 专属字段混用时，该 Server 被判定为无效；
- stdio 的 env 值和 HTTP 的 headers 值直接使用配置文件中的字面量；
- 无效 Server 被单独跳过，内置工具和其他有效 Server 仍可用。

### A2：完整会话

对有效 Server 可观察到以下顺序：

    initialize
    notifications/initialized
    tools/list
    tools/call × N

其中：

- 前两步各执行一次；
- tools/list 完成一次逻辑发现，分页时允许多个请求；
- tools/call 可在同一会话内重复执行；
- MewCode 重启后才重新执行新会话流程。

### A3：版本

- 客户端声明 2025-11-25；
- 能协商到该版本的双版本 Server 可正常完成初始化和工具调用；
- 返回其他版本、缺失版本或非法版本的 Server 被跳过；
- 错误日志包含 Server 名称、服务端版本和客户端支持版本。

### A4：传输

- stdio Server 能通过 stdout/stderr 分离完成 JSON-RPC 通信；
- HTTP Server 能通过 POST 处理 JSON 和 SSE 响应；
- HTTP 会话 ID、协议版本 Header 和配置 Headers 正确传递；
- 进程退出、网络断开、超时和非法响应会返回工具错误；
- 一个 Server 故障不阻止其他 Server 和内置工具工作。

### A5：注册与调用

- MCP 工具以稳定、全局唯一的 MewCode 工具名注册；
- 工具描述和输入 schema 可被 ToolSearch 返回；
- 工具调用发送到正确的 Server 和原始工具；
- 成功结果和各类错误都能转换为现有 ToolResult；
- 重复工具名不会覆盖已有工具。

### A6：延迟加载

- 初始模型工具列表不包含 MCP 工具完整 schema；
- system-reminder 包含尚未发现的 MCP 工具名；
- ToolSearch 能按完整注册名从本地 Registry 找到工具；
- 成功搜索后工具从下一轮开始进入正常工具列表；
- ToolSearch 不产生远程 tools/list 请求；
- 不存在的工具返回错误且不改变 Registry 状态。

### A7：生命周期与刷新边界

- 同一 Server 的多次调用复用同一会话；
- Server 连接失效后不自动重连；
- notifications/tools/list_changed 不触发当前会话重新发现；
- MewCode 退出时关闭所有 MCP 资源和本地子进程；
- 工具列表变更在重启后才能生效。

### A8：权限与回归

- MCP 工具调用经过现有权限执行流程；
- 敏感环境变量和 Header 不出现在日志或 Agent 可见错误中；
- 现有内置工具、Provider、权限、/plan、/do 和 Agent Loop 行为不回归；
- MCP 全部不可用时，内置工具仍可正常完成任务。
