# MewCode 工具调用系统 Task

> 状态：已确认
>
> 本文件把 [plan.md](/Users/bytedance/IdeaProjects/Mewcode-develop/docs/ch3/plan.md) 拆成可以逐项实施和验证的任务。所有任务完成后，仍需依据 `checklist.md` 做最终验收。

## 1. 执行规则

- 严格按依赖顺序执行；前置任务的编译或测试失败时，先修复再进入后续任务。
- 每个任务只修改任务列出的职责范围，不顺手引入权限系统、连续 Agent Loop 或其他章节功能。
- 工具实现返回 `ToolResult` 表达业务失败；只有不可恢复的基础设施问题才通过统一事件报告，不能让单个工具异常中断整轮会话。
- 文件相关实现和测试全部使用绝对路径；测试临时目录使用独立临时目录，不能操作项目真实文件。
- 代码和测试注释使用中文。
- 每个阶段完成后运行对应的窄范围测试；全部完成后运行完整 Gradle 测试、打包和 tmux 端到端测试。

## 2. 任务总览

| 编号 | 任务 | 前置任务 | 主要产物 |
| --- | --- | --- | --- |
| T0 | 建立基线和 JSON 依赖 | 无 | 测试基线、Jackson 依赖 |
| T1 | 实现工具领域契约和文件状态缓存 | T0 | `Tool`、`ToolResult`、`FileStateCache` |
| T2 | 改造对话内容块和历史 | T1 | `ContentBlock`、多块 `Message` |
| T3 | 实现工具注册中心和 API Schema | T1 | `ToolRegistry`、三种协议格式 |
| T4 | 实现共享文件、命令和搜索能力 | T1 | `PathGuard`、`TextFileSupport` 等 |
| T5 | 实现六个内置工具 | T2、T3、T4 | 六个 `Tool` 实现 |
| T6 | 实现工具执行、超时和并发调度 | T5 | `ToolExecutor` |
| T7 | 实现流式 tool-use 解析和 Provider 适配 | T2、T3 | `StreamEvent`、客户端适配 |
| T8 | 实现一次工具回合和 TUI 接入 | T6、T7 | `AgentTurnCoordinator` |
| T9 | 完成自动化测试、打包和端到端验证 | T8 | 测试报告、E2E 证据 |

## 3. T0：建立基线和 JSON 依赖

### T0.1 记录当前基线

- 文件：无生产代码变更。
- 执行现有测试，记录编译和测试结果。
- 确认当前纯文本 `OpenAiClient`、`AnthropicClient`、`MewCodeModel` 流程可以作为回归基线。

完成条件：基线测试结果可复现，后续失败可以区分为既有问题或本章引入的问题。

### T0.2 增加 JSON 解析依赖

- 文件：`build.gradle.kts`。
- 显式增加 `com.fasterxml.jackson.core:jackson-databind:2.18.2`。
- 不升级现有 Anthropic、OpenAI SDK，不引入 provider 私有 JSON 解析类。

完成条件：项目可编译，Jackson `ObjectMapper` 可以被生产代码和测试使用。

## 4. T1：实现工具领域契约和文件状态缓存

### T1.1 定义工具基础类型

- 文件：
  - `src/main/java/com/mewcode/tool/Tool.java`
  - `src/main/java/com/mewcode/tool/ToolResult.java`
  - `src/main/java/com/mewcode/tool/ToolCategory.java`
  - `src/main/java/com/mewcode/tool/ToolApiProtocol.java`
  - `src/main/java/com/mewcode/tool/ToolExecutionContext.java`
  - `src/main/java/com/mewcode/tool/ToolInvocationResult.java`
- 按 plan 中的签名实现九项工具职责。
- `ToolResult` 的 `content`、`isError`、`metadata` 语义固定；metadata 做不可变快照。
- `ToolCategory` 只包含 `FILE`、`SEARCH`、`SHELL`。
- 不增加 `shouldDefer`、权限确认回调或 provider SDK 类型。

验证：编译通过；构造成功和错误结果时字段符合约定。

### T1.2 实现 FileStateCache

- 文件：`src/main/java/com/mewcode/tool/FileStateCache.java`。
- 使用线程安全 Map 保存规范化绝对路径和读取时 `FileTime`。
- `recordRead` 只在 ReadFile 完整成功后调用。
- `canModify` 同时检查读取记录存在和当前修改时间相同。
- `update` 在成功写入或编辑后刷新时间；文件消失时清理对应记录。

验证：覆盖未读取、读取后未变化、读取后外部修改、成功更新和并发访问。

## 5. T2：改造对话内容块和历史

### T2.1 增加内容块类型

- 文件：
  - `src/main/java/com/mewcode/conversation/ContentBlock.java`
  - `src/main/java/com/mewcode/conversation/TextBlock.java`
  - `src/main/java/com/mewcode/conversation/ToolUseBlock.java`
  - `src/main/java/com/mewcode/conversation/ToolResultBlock.java`
- 使用 sealed interface/record 表达 text、tool-use、tool-result。
- `ToolUseBlock` 的 arguments 做不可变快照。
- `ToolResultBlock` 只保存模型需要的 content 和 isError，不带 metadata。

### T2.2 改造 Message 和 ConversationManager

- 文件：
  - `src/main/java/com/mewcode/conversation/Message.java`
  - `src/main/java/com/mewcode/conversation/ConversationManager.java`
- 将消息模型改为 `role + List<ContentBlock>`。
- 保留普通用户文本和 assistant 文本的便捷添加方法。
- 增加 assistant 多块消息和单条 user tool-result 消息的添加方法。
- 返回历史时提供不可修改快照，避免 provider 适配器修改对话状态。

验证：覆盖混合文本/多个 tool-use、多个 tool-result 同消息、ID 配对和历史顺序。

## 6. T3：实现工具注册中心和 API Schema

### T3.1 实现注册中心

- 文件：`src/main/java/com/mewcode/tool/ToolRegistry.java`。
- 使用 `ConcurrentHashMap<String, Tool>`。
- 实现注册、按名查找、稳定顺序快照和默认六工具注册。
- 同名工具注册时新实例覆盖旧实例。
- 默认注册顺序为 ReadFile、WriteFile、EditFile、Bash、Glob、Grep。

### T3.2 实现 `toAPIFormate`

- 遍历注册工具并读取 `name`、`description`、`inputSchema`。
- Anthropic 生成 `input_schema` 结构。
- OpenAI 和 DeepSeek 生成 `type=function` 与 `function.parameters` 结构。
- 生成结果只包含 API 所需字段，不把 `metadata`、权限字段或 Java 实例发送给模型。

验证：分别断言三种 provider 的名称、描述、参数 Schema 和工具数量；断言工具实现不导入 provider SDK。

## 7. T4：实现共享文件、命令和搜索能力

### T4.1 PathGuard

- 文件：`src/main/java/com/mewcode/tool/support/PathGuard.java`。
- 拒绝非绝对路径。
- 对路径做 normalize，并验证位于规范化项目根目录内。
- 已存在路径通过真实路径检查，阻止符号链接逃逸；搜索遍历默认不跟随符号链接。
- 生成区分非绝对、越界、不存在和无权限的错误信息。

### T4.2 TextFileSupport

- 文件：`src/main/java/com/mewcode/tool/support/TextFileSupport.java`。
- 读取文件前最多读取前 512 字节。
- 发现 NUL 字符时返回二进制判定，不把内容解码给模型。
- 提供统一文本读取、按行读取、二进制拒绝和权限错误转换能力。

### T4.3 CommandRunner

- 文件：`src/main/java/com/mewcode/tool/support/CommandRunner.java`。
- 创建系统 shell 子进程，固定 cwd 为项目根目录。
- 合并 stdout/stderr，持续消费输出避免子进程管道阻塞。
- 集中定义默认超时 120 秒和输出最大字符数。
- 超时强制销毁进程并返回超时信息；输出超限只保留前部和截断标记。
- 提取命令首个可识别程序名，维护 grep/diff/find 的 exit code 1 正常语义表。

### T4.4 SearchSupport

- 文件：`src/main/java/com/mewcode/tool/support/SearchSupport.java`。
- 集中定义排除目录：`.git`、`node_modules`、`vendor`、`.idea`、`__pycache__` 等。
- 提供搜索根目录规范化、相对路径格式化、修改时间排序和 200 条上限。
- 统一跳过符号链接目录和不可访问目录，保留可诊断的本地 metadata。

验证：共享类先用临时目录测试路径逃逸、二进制检测、输出截断、超时和排除目录，不依赖真实项目文件。

## 8. T5：实现六个内置工具

### T5.1 ReadFileTool

- 文件：`src/main/java/com/mewcode/tool/impl/ReadFileTool.java`。
- 实现绝对 path、1-based offset、limit 和默认 limit 2000 的 Schema/校验。
- 输出 `行号<TAB>内容`，支持大文件分段读取。
- 成功后记录 FileStateCache；错误不更新缓存。
- 标记：`FILE`、只读、非破坏性、可并发。

测试：分页、行号、空文件、文件不存在、相对路径、项目外路径、权限错误、二进制和超大文件。

### T5.2 WriteFileTool

- 文件：`src/main/java/com/mewcode/tool/impl/WriteFileTool.java`。
- 新文件递归创建父目录；POSIX 系统设置目录 `0755`、文件 `0644`。
- 已有文件必须通过 FileStateCache；检查二进制后再覆盖。
- 写入成功后更新缓存；失败不得留下部分成功状态。
- 标记：`FILE`、非只读、非破坏性、不可并发。

测试：新建目录和文件权限、覆盖已读文件、未读拒绝、mtime 变化拒绝、已有二进制拒绝和异常不改文件。

### T5.3 EditFileTool

- 文件：`src/main/java/com/mewcode/tool/impl/EditFileTool.java`。
- 实现 `old_string` 大小写敏感且恰好一次匹配。
- 零次、多次、未读、mtime 变化和二进制场景都不写文件。
- 成功后更新缓存。
- 标记：`FILE`、非只读、非破坏性、不可并发。

测试：唯一替换、未找到、不唯一、文件变化、未读、二进制和替换内容包含特殊字符。

### T5.4 BashTool

- 文件：`src/main/java/com/mewcode/tool/impl/BashTool.java`。
- 通过 CommandRunner 执行命令，工作目录为项目根目录。
- 返回 `<output>`、`<exit_code>`，正确处理 exit code 1 语义表。
- 标记：`SHELL`、非只读、破坏性、不可并发。

测试：工作目录、stdout/stderr 合并、成功/失败退出码、grep/diff/find 退出码语义、超时强杀和输出截断。

### T5.5 GlobTool

- 文件：`src/main/java/com/mewcode/tool/impl/GlobTool.java`。
- 实现绝对模式、`*`、`?`、`**` 递归匹配。
- 应用排除目录、修改时间倒序和 200 条上限。
- 输出相对于搜索根目录的路径。
- 标记：`SEARCH`、只读、非破坏性、可并发。

测试：子目录递归、通配符、排除目录、排序、上限、空结果和不可访问目录。

### T5.6 GrepTool

- 文件：`src/main/java/com/mewcode/tool/impl/GrepTool.java`。
- 实现正则、绝对搜索根目录和可选 include 文件名过滤。
- 二进制先检测后跳过，输出 `相对路径:行号<TAB>匹配行内容`。
- 应用排除目录、修改时间倒序、200 条上限和 binary skip metadata。
- 标记：`SEARCH`、只读、非破坏性、可并发。

测试：正则、行号、include、二进制跳过、排除目录、排序、上限、非法正则和无匹配结果。

## 9. T6：实现工具执行、超时和并发调度

### T6.1 单工具执行

- 文件：`src/main/java/com/mewcode/tool/ToolExecutor.java`。
- 按名称查找、输入校验、构造上下文并调用 `execute`。
- 将未知工具、校验失败、异常、超时统一转换为 `ToolResult(isError=true)`。
- metadata 记录本地错误类型、工具名、耗时等信息，但不能进入 ToolResultBlock。

### T6.2 批量调度

- 根据每个调用的 `isConcurrencySafe(input)` 将调用分成安全并发批次和串行屏障。
- 使用 Java 21 虚拟线程或等价实现执行安全批次。
- 单个任务失败不影响其他任务；最终按原始调用顺序返回。
- 验证多个调用的唯一 ID 不重复；重复 ID 生成错误结果而不是覆盖其他调用。

测试：三类安全工具并发、写工具串行、混合调用屏障、失败隔离、顺序恢复、超时和未知工具。

## 10. T7：实现流式 tool-use 解析和 Provider 适配

### T7.1 扩展 StreamEvent 和累积器

- 文件：
  - `src/main/java/com/mewcode/llm/StreamEvent.java`
  - `src/main/java/com/mewcode/llm/ToolCallAccumulator.java`
- 增加 `ToolCallComplete` 和 `ToolCallParseError`。
- 每个 ID 独立维护名称和 JSON 缓冲区，支持多个调用交错到达。
- 使用 Jackson 解析完整 JSON 对象；非法 JSON 只产生解析错误事件。

测试：单调用碎片、多调用交错、混合文本、空参数、非法 JSON、结束事件缺失和一个调用失败不影响另一个调用。

### T7.2 OpenAI/DeepSeek 适配

- 文件：`src/main/java/com/mewcode/llm/OpenAiClient.java`，必要时增加 DeepSeek 配置复用路径。
- 将领域消息转换为 assistant tool call、tool role result 和 function schema。
- 读取流式 tool-call delta，按 provider 的 index/id 映射到统一累积器。
- 保留现有纯文本事件和错误脱敏行为。

### T7.3 Anthropic 适配

- 文件：`src/main/java/com/mewcode/llm/AnthropicClient.java`。
- 将领域消息转换为 Anthropic content block。
- 处理 tool-use start、input JSON delta、block stop 和 tool-result user block。
- 保留 thinking/text 事件的现有语义。

### T7.4 统一客户端接口

- 文件：`src/main/java/com/mewcode/llm/LlmClient.java`。
- 接收 `List<Message>` 和 `List<Map<String,Object>> apiTools`。
- provider 适配器只负责协议转换和流事件归一化，不执行本地工具。
- 现有纯文本调用方迁移到新接口，确保不传工具时行为不变。

## 11. T8：实现一次工具回合和 TUI 接入

### T8.1 AgentTurnCoordinator

- 文件：`src/main/java/com/mewcode/agent/AgentTurnCoordinator.java`。
- 实现第一次模型请求、assistant 消息收集、工具批量执行、单条 user tool-result 消息和第二次模型请求。
- parse error 也使用对应 toolUseId 生成错误结果，不调用具体工具。
- 第二次响应继续返回 tool-use 时停止，不执行第三次请求，发出明确错误事件。

### T8.2 AgentEvent 和结果组装

- 文件：
  - `src/main/java/com/mewcode/agent/AgentEvent.java`
  - `src/main/java/com/mewcode/agent/ToolResultAssembler.java`
- 提供文本增量、工具开始、工具结束、工具错误、最终结束等 UI 事件。
- 组装器保证所有结果位于一条 user 消息中，并按原始顺序和 ID 配对。

### T8.3 Prompt、TUI 和启动 wiring

- 文件：
  - `src/main/java/com/mewcode/prompt/PromptBuilder.java`
  - `src/main/java/com/mewcode/tui/MewCodeModel.java`
  - `src/main/java/com/mewcode/MewCode.java`
- 删除旧 prompt 中“模型不能使用工具”等不再准确的说明，补充工具结果和一次回合边界。
- MewCode 启动时确定项目根目录，创建 `FileStateCache`、默认 `ToolRegistry`、`ToolExecutor` 和 `AgentTurnCoordinator`。
- TUI 只消费 AgentEvent，显示工具调用状态，不增加确认交互。
- 保持用户输入、思考内容隐藏和纯文本最终输出的既有体验。

测试：无工具文本回合、单工具回合、多工具回合、工具错误回合、最终连续 tool-use 被阻止、TUI 事件消费和启动 wiring。

## 12. T9：自动化测试、打包和端到端验证

### T9.1 单元测试文件

新增或调整：

```text
src/test/java/com/mewcode/tool/ToolRegistryTest.java
src/test/java/com/mewcode/tool/FileStateCacheTest.java
src/test/java/com/mewcode/tool/ToolExecutorTest.java
src/test/java/com/mewcode/tool/ReadFileToolTest.java
src/test/java/com/mewcode/tool/WriteFileToolTest.java
src/test/java/com/mewcode/tool/EditFileToolTest.java
src/test/java/com/mewcode/tool/BashToolTest.java
src/test/java/com/mewcode/tool/GlobToolTest.java
src/test/java/com/mewcode/tool/GrepToolTest.java
src/test/java/com/mewcode/conversation/ConversationManagerTest.java
src/test/java/com/mewcode/llm/ToolCallAccumulatorTest.java
src/test/java/com/mewcode/llm/OpenAiClientTest.java
src/test/java/com/mewcode/llm/AnthropicClientTest.java
src/test/java/com/mewcode/agent/AgentTurnCoordinatorTest.java
src/test/java/com/mewcode/tui/MewCodeModelTest.java
```

### T9.2 固定流事件资源

- 文件：
  - `src/test/resources/sse/anthropic-tool-use.txt`
  - `src/test/resources/sse/openai-tool-use.txt`
- 覆盖文本和工具调用混合、多个调用、JSON 碎片和 tool-result 回灌所需的最小事件序列。
- 测试不依赖真实 API key、网络或线上模型。

### T9.3 完整验证

- 运行完整 Gradle 测试和 shadow 打包。
- 检查六个工具的 schema、元信息和错误结果。
- 用 tmux 启动 MewCode，在项目根目录发起真实请求“读取一个文件并总结”。
- 观察并记录：模型 tool-use、工具执行、tool-result user 消息、第二次模型请求和最终文本。
- 按 `checklist.md` 逐项记录通过、失败和证据。

## 13. 任务完成定义

`task.md` 阶段完成需要满足：

- T0 到 T9 的文件范围、执行顺序、依赖和验证方式均明确；
- 每个功能需求都有对应实现任务和测试任务；
- 任务不包含本章排除的权限确认、ToolSearch 或连续 Agent Loop；
- 用户确认本文件后，才生成 `checklist.md`，确认 `checklist.md` 后才开始编写生产代码。
