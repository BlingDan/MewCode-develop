# MewCode Agent Loop 与 Plan Mode 实施计划

> 状态：已实施并验收
>
> 本计划基于已确认的 [spec.md](/Users/bytedance/IdeaProjects/Mewcode-develop/docs/ch4/spec.md)。
> 本阶段只规划连续 Agent Loop、异步事件流、立即取消、跨协议一致和持久 Plan Mode；不实现权限审批、上下文压缩或交互式确认。

## 1. 实施目标

把第 3 章的“一次工具结果回灌”扩展为可持续的 ReAct Agent Loop：

```text
用户消息
  -> 创建 AgentRun
  -> LLM 流式响应
  -> 实时发布文本，同时收集完整响应
  -> 无工具调用：完成 Loop
  -> 有工具调用：过滤、分批、执行
  -> 按原始顺序发布并回灌工具结果
  -> 检查停止条件
  -> 继续下一轮 LLM
```

同时让 `/plan` 和 `/do` 成为持久的本地模式切换，并让 Anthropic、OpenAI 以及 DeepSeek 兼容端点共享同一套 Loop、事件、用量和取消语义。

## 2. 当前代码基础与改造约束

- 项目使用 Java 21 和 Gradle Kotlin DSL。
- 第 3 章已经存在 `AgentTurnCoordinator`、`AgentEvent`、`ToolResultAssembler`、`LlmClient`、`StreamEvent`、`ToolCallAccumulator`、`ToolExecutor` 和 `MewCodeModel`。
- 当前 `AgentTurnCoordinator` 只支持一次工具结果回灌，本章将其演进为连续循环协调器，不并存两套 Loop 实现。
- 当前 `LlmClient` 和 provider 客户端使用阻塞队列承载后台流式响应，本章继续沿用这一模型，外部增加稳定的 `AgentEventStream` 接口。
- 当前对话历史用 provider 无关的 `Message` 和 `ContentBlock` 表示，工具结果沿用现有 user 消息表示，不引入新的 `Role.TOOL`。
- 当前 `ToolExecutor` 已有安全工具并发、非安全工具串行的批量执行基础，本章增加取消传播、结果状态和 Loop 需要的稳定顺序。
- 当前 provider 配置已有 `protocol`、`model`、`baseUrl`、`apiKey` 和 thinking 配置；DeepSeek 兼容端点通过已有协议和 `baseUrl` 路由，不引入 DeepSeek SDK。
- 保留第 3 章纯文本对话、六个内置工具、路径安全、文件状态缓存和既有退出行为。
- `.idea/.name` 等与本章无关的用户改动必须保留，不在本章处理。

## 3. 目标模块边界

### 3.1 `com.mewcode.agent`

负责 Loop 状态机、停止条件、模式策略、事件流、取消句柄和对话回合编排。该模块只依赖领域消息、`LlmClient` 和 `ToolExecutor`，不依赖 provider SDK 和具体 TUI 组件。

主要对象：

- `AgentTurnCoordinator`：连续 Loop 的唯一协调入口；
- `AgentRun`：一次用户请求的运行句柄；
- `AgentEventStream`：面向 UI 的异步事件流；
- `AgentLoopConfig`：最大轮次和未知工具阈值；
- `AgentMode`：`PLAN`、`EXECUTE`；
- `CancellationToken`：跨 provider、工具和协调器传播取消；
- `TurnStreamCollector`：双路收集一轮 LLM 流；
- `LoopStopReason`：内部终止原因，不直接扩展公共事件类型。

### 3.2 `com.mewcode.llm`

负责领域消息与 provider 请求之间的转换，并将不同协议的流转换成统一 `StreamEvent`。本模块提供可关闭的 provider 流句柄和协议无关的 Token 用量模型。

主要改造对象：

- `LlmClient`：增加可取消流的入口，同时保留必要的兼容调用方式；
- `StreamEvent`：增加 usage 和取消所需的流结束/错误语义；
- `AnthropicClient`：解析内容块、工具 JSON 增量、message usage，并支持关闭流；
- `OpenAiClient`：解析文本和工具 delta、合并工具调用、开启最终 usage，并支持关闭流；
- `LlmClients`：按 `protocol` 和 `baseUrl` 复用 OpenAI/Anthropic adapter；
- `ToolCallAccumulator`：继续按请求 ID 或工具调用索引隔离拼接参数。

### 3.3 `com.mewcode.tool`

负责工具筛选、输入校验、批次调度、并发控制、超时和取消传播。

主要改造对象：

- `ToolRegistry`：提供稳定顺序的工具快照和模式过滤结果；
- `ToolExecutor`：接收取消令牌，返回带状态和耗时的完整结果；
- `ToolExecutionContext`：向工具暴露取消检查；
- `ToolInvocationResult`、`ToolResult`：补充取消、失败和耗时所需的领域信息。

六个内置工具的业务语义不变，只在需要时接入取消检查；Bash 的子进程取消沿用已有强制终止能力。

### 3.4 `com.mewcode.conversation`

负责完整回合的原子写入和取消后的合法历史：

- 完整 LLM turn 才提交 assistant 消息；
- 工具轮提交 assistant tool-use 消息和对应的工具结果消息；
- 流阶段取消时丢弃不完整的 turn；
- 工具阶段取消时为所有已经提交的调用补齐成功、失败或取消结果；
- 不把事件 metadata、耗时、内部 stop reason 或 UI 文本写入模型历史。

### 3.5 `com.mewcode.prompt`

根据 `AgentMode` 生成模式相关的系统提示：

- 执行模式：允许模型使用全部注册工具；
- 规划模式：明确当前只允许安全只读工具，要求模型先调查并形成计划；
- 删除第 3 章中“一次工具结果回灌后必须结束”的旧提示。

`/plan` 和 `/do` 本身不经过 `PromptBuilder`，不生成模型请求。

### 3.6 `com.mewcode.config`

增加可选的 Agent Loop 配置，并为旧配置提供默认值：

```yaml
agent:
  loop:
    max-iterations: 20
    unknown-tool-round-limit: 3
```

未配置时使用默认值；不增加权限、确认、压缩和后台任务配置。

### 3.7 `com.mewcode.tui`

只消费 `AgentEventStream` 并维护展示状态：

- `stream_text` 追加模型文本；
- `tool_use` 显示工具执行提示；
- `tool_result` 折叠显示完整结果；
- `usage` 更新累计用量；
- `turn_complete` 更新动态区的当前迭代轮次；
- `loop_complete` 清理流式态并恢复输入；
- `error` 展示错误信息。

键盘行为：

- 流式态 `Esc`：取消当前 Loop；
- 流式态 `Ctrl+C`：取消当前 Loop，不发送退出消息；
- 空闲态 `Ctrl+C`：发送现有 `QuitMessage`；
- 空闲态 `Esc`：忽略。

## 4. 核心数据契约

### 4.1 公共 `AgentEvent`

`AgentEvent` 使用 sealed interface 和不可变 record，公共类型固定为：

```text
StreamText(text)
ToolUse(requestId, toolName, input)
ToolResult(requestId, toolName, result, isError, duration)
TurnComplete(round)
LoopComplete(totalRounds)
Usage(cumulativeInputTokens, cumulativeOutputTokens)
Error(message, category)
```

Java 层的输入使用 Jackson `JsonNode` 或不可变 Map，不能泄漏 Anthropic/OpenAI SDK 对象。Token 字段允许表示未知，不用负数或字符数估算作为伪值。

公共事件顺序约束：

1. 一轮 LLM 流中发布 `stream_text`、完整 `tool_use` 和 usage 更新；
2. provider 流完整结束后发布 `turn_complete`；
3. 工具完成后按原始顺序发布 `tool_result`；
4. 异常路径在 `loop_complete` 前发布 `error`；
5. `loop_complete` 永远是最后一个业务事件。

### 4.2 `AgentRun` 和事件流

设计为：

```text
AgentRun
  ├─ events() -> AgentEventStream
  ├─ cancel()
  ├─ isRunning()
  └─ state()
```

`AgentRun` 保存当前 provider 流句柄和活动工具执行句柄。`cancel()` 必须幂等，并且只影响当前请求。

`AgentEventStream` 负责：

- 保证事件发布顺序；
- 在 `loop_complete` 后关闭；
- 支持 TUI 在独立消费线程中持续读取；
- Loop 取消后不再接收后续 provider 或工具事件。

### 4.3 一轮收集结果

`TurnStreamCollector` 返回 `CollectedTurn`：

- 完整文本；
- 内部 thinking/reasoning 内容；
- 按原始顺序排列的 `ToolCall`；
- 本轮 usage；
- 是否完整结束；
- provider 错误或参数解析错误。

工具调用先进入 turn buffer。只有 provider turn 正常结束后，协调器才提交 assistant 消息并执行这些调用。这样在 LLM 流阶段取消时可以安全丢弃不完整消息。

### 4.4 工具执行结果

```text
ToolCall
  ├─ requestId
  ├─ toolName
  ├─ input
  └─ originalIndex

ToolExecutionResult
  ├─ requestId
  ├─ toolName
  ├─ output
  ├─ isError
  ├─ status: SUCCESS / ERROR / CANCELLED
  └─ duration
```

工具执行器可以并发运行安全工具，但结果必须在归并后按 `originalIndex` 发布和写回。

## 5. Loop 关键流程

### 5.1 正常路径

1. TUI 将普通用户消息交给 `AgentTurnCoordinator`；
2. 协调器保存用户消息并创建 `AgentRun`；
3. 根据当前模式构造工具定义；
4. 检查取消后打开可取消的 provider 流；
5. `TurnStreamCollector` 同时转发文本和收集完整响应；
6. provider 流结束后发送 usage 和 `turn_complete`；
7. 没有工具调用时提交 assistant 文本，发送 `loop_complete`；
8. 有工具调用时提交 assistant tool-use 消息；
9. 工具策略执行请求层过滤和执行层二次校验；
10. `ToolExecutor` 按安全性分批执行；
11. 按原始顺序发送并写入全部 `tool_result`；
12. 检查最大轮次和未知工具阈值；
13. 未触发停止条件则发起下一轮。

### 5.2 立即取消路径

LLM 流阶段：

1. 键盘层识别流式态的 `Esc` 或 `Ctrl+C`；
2. 调用当前 `AgentRun.cancel()`；
3. 关闭 provider 流并中断消费；
4. 丢弃尚未完整结束的 turn buffer；
5. 不执行尚未提交的工具调用；
6. 清理运行状态并发送 `loop_complete`。

工具阶段：

1. 设置取消令牌并取消活动任务；
2. 已完成调用保留真实结果；
3. 活动调用立即生成取消结果，不等待其自然结束；
4. 按原始顺序发送所有 `tool_result`；
5. 写入合法工具结果消息；
6. 不再发起下一次 LLM 请求；
7. 发送 `loop_complete` 并回到空闲态。

已经发生的文件写入、编辑或 shell 副作用不回滚。底层任务即使因工具自身限制无法瞬间停止，也不能阻塞当前 Loop 的收尾。

### 5.3 最大轮次和未知工具路径

- 每次完整 LLM 请求完成后轮次加一；
- 默认最多 20 轮；
- 最后一轮仍可以完成当前工具执行，但不再发起下一轮；
- 一轮没有任何可执行已知工具时，未知工具计数加一；
- 一轮至少执行一个已知工具时，未知工具计数归零；
- 连续三轮达到阈值后发送最终错误信息并结束。

## 6. Provider 改造方案

### 6.1 Anthropic

- 保留当前 SDK 流式资源的 try-with-resources 生命周期；
- 将文本 delta 转换为内部文本事件；
- 使用内容块 ID 累积工具 JSON；
- 在消息 usage 更新时输出本轮用量；
- `CancellableLlmStream.close()` 关闭 SDK 流；
- 保留 thinking 内容用于后续协议消息构造，但不转换为公共 UI 事件。

### 6.2 OpenAI Chat Completions

- 按工具调用 index 合并多个 delta；
- 使用请求选项开启 `stream_options.include_usage`；
- 在最后 usage chunk 到达时更新累计用量；
- 流被关闭或中断时不伪造 usage；
- 通过现有 `baseUrl` 支持 OpenAI 兼容服务。

### 6.3 DeepSeek

- OpenAI 兼容端点复用 `OpenAiClient`；
- Anthropic 兼容端点复用 `AnthropicClient`；
- 使用 `ProviderConfig.baseUrl` 区分服务地址；
- 保留 thinking 模式所需的 reasoning 内容并在下一轮回传；
- 统一转换成同一套 `StreamEvent` 和 `AgentEvent`。

不新增 DeepSeek 专用 Loop 分支，避免三套状态机产生行为差异。

## 7. 计划修改的代码文件

### 7.1 Agent 层

- `src/main/java/com/mewcode/agent/AgentEvent.java`：迁移为七种公共事件；
- `src/main/java/com/mewcode/agent/AgentTurnCoordinator.java`：改造成持续 Loop 状态机；
- 新增 `AgentRun.java`：保存一次运行的事件流、状态和取消句柄；
- 新增 `AgentEventStream.java`：封装异步事件消费；
- 新增 `AgentMode.java`：定义 `PLAN` 和 `EXECUTE`；
- 新增 `AgentLoopConfig.java`：定义最大轮次和未知工具阈值；
- 新增 `CancellationToken.java`：统一取消状态和监听；
- 新增 `TurnStreamCollector.java`：双路收集 provider 流；
- 新增或调整内部 stop reason、turn buffer 和 usage 类型；
- `ToolResultAssembler.java`：支持稳定顺序、错误结果和取消结果。

### 7.2 LLM 层

- `src/main/java/com/mewcode/llm/LlmClient.java`：增加可取消流入口；
- `src/main/java/com/mewcode/llm/StreamEvent.java`：增加 usage 和关闭/错误语义；
- `src/main/java/com/mewcode/llm/AnthropicClient.java`：usage、工具增量和取消；
- `src/main/java/com/mewcode/llm/OpenAiClient.java`：usage、工具 delta 和取消；
- `src/main/java/com/mewcode/llm/LlmClients.java`：统一 OpenAI/Anthropic 兼容协议路由；
- `src/main/java/com/mewcode/llm/ToolCallAccumulator.java`：适配新的 turn collector。

### 7.3 工具和历史层

- `src/main/java/com/mewcode/tool/ToolRegistry.java`：提供模式过滤和稳定快照；
- `src/main/java/com/mewcode/tool/ToolExecutor.java`：接收取消令牌、生成取消结果、保留原始顺序；
- `src/main/java/com/mewcode/tool/ToolExecutionContext.java`：增加取消检查；
- `src/main/java/com/mewcode/tool/ToolInvocationResult.java`：补充状态和耗时；
- `src/main/java/com/mewcode/conversation/ConversationManager.java`：支持完整 turn 原子提交；
- `src/main/java/com/mewcode/prompt/PromptBuilder.java`：删除一次性回灌约束并增加模式提示；
- `src/main/java/com/mewcode/config/AppConfig.java`：增加 Agent Loop 配置；
- `src/main/java/com/mewcode/config/ConfigLoader.java`：解析配置并提供默认值。

### 7.4 TUI 层

- `src/main/java/com/mewcode/tui/MewCodeModel.java`：消费七种事件、展示迭代轮次、管理当前 AgentRun；
- `src/main/java/com/mewcode/tui/AppState.java`：区分空闲态和流式态；
- `src/main/java/com/mewcode/tui/tea/KeyPressMessage.java`：识别 Esc 和 Ctrl+C；
- `src/main/java/com/mewcode/tui/tea/Program.java`：保证流式态 Ctrl+C 不生成退出消息；
- `/plan`、`/do` 的命令分支：只改变本地模式，不触发模型调用。

## 8. 实施顺序

### P1：公共契约和配置

- 固化七种 `AgentEvent`；
- 增加 `AgentMode`、`AgentLoopConfig`、`TokenUsage` 和内部停止原因；
- 扩展配置加载和默认值；
- 先更新领域层单元测试。

### P2：可取消 provider 流和双路收集

- 调整 `LlmClient` 流接口；
- 实现 `CancellableLlmStream`；
- 完成 `TurnStreamCollector`；
- 补齐 Anthropic/OpenAI usage；
- 增加 DeepSeek OpenAI/Anthropic 兼容配置测试；
- 处理中断流的未知 usage。

### P3：Loop 协调器

- 将现有一次性协调逻辑改为状态机；
- 实现无工具、正常多轮、最大轮次、未知工具和流错误路径；
- 使用 `ConversationManager` 完整提交 turn；
- 保证 `loop_complete` 是最后事件。

### P4：工具取消和稳定批处理

- 将取消令牌传入 `ToolExecutionContext`；
- 为活动任务增加 best-effort 取消；
- 并发安全工具并行、其他工具串行；
- 结果按原始调用顺序归并；
- 补齐工具错误和取消结果。

### P5：Plan Mode

- 增加持久模式状态；
- 实现 `/plan` 和 `/do` 本地切换；
- 请求层动态过滤 `isReadOnly && !isDestructive` 工具；
- 执行层增加二次校验；
- 更新模式相关 prompt。

### P6：TUI 事件消费与按键行为

- 迁移旧 AgentEvent 消费逻辑；
- 增加当前迭代轮次展示；
- 流式态 Esc/Ctrl+C 连接到 `AgentRun.cancel()`；
- 空闲态 Ctrl+C 保留程序退出；
- `loop_complete` 后恢复输入和空闲状态。

### P7：测试与端到端验收

- 先通过单元测试和 provider SSE fixture；
- 使用确定性 HTTP 测试服务验证三种协议路径；
- 用 tmux 启动真实 MewCode 进程；
- 发送真实用户消息，观察多轮工具调用和最终回复；
- 分别验证流式态 Esc/Ctrl+C 与空闲态 Ctrl+C；
- 按 `checklist.md` 逐项验收。

## 9. 测试设计

### 9.1 Agent Loop 单元测试

使用 fake `LlmClient` 和 fake `Tool` 控制每轮响应，覆盖：

- 无工具直接完成；
- 两轮及以上工具调用；
- 多个工具调用和原始顺序；
- 最大迭代次数；
- 连续未知工具；
- provider 流错误；
- 工具失败后继续 Loop；
- `loop_complete` 最后发送且只发送一次。

### 9.2 取消测试

使用 CountDownLatch 或可控 fake stream 验证：

- LLM 流阶段 Esc/Ctrl+C 关闭 provider 流；
- 工具阶段取消活动任务；
- 取消后没有下一次 LLM 请求；
- 已提交调用都有结果；
- 未完成 LLM 响应没有写入历史；
- 取消后可以继续发送下一条消息；
- 重复取消不会重复收尾。

### 9.3 Provider 测试

- Anthropic 文本、工具 JSON 增量和 message usage；
- OpenAI 文本、多个工具 delta 和最终 usage；
- DeepSeek OpenAI 兼容 base URL；
- DeepSeek Anthropic 兼容 base URL；
- thinking/reasoning 内容在后续请求中保留；
- provider 流中断时 usage 为未知。

### 9.4 工具和模式测试

- 安全工具并发执行；
- 有副作用工具串行执行；
- 结果按原始顺序归并；
- Plan Mode 不提供写入和 Bash 工具；
- 执行层拒绝被模式过滤的工具；
- `/plan`、`/do` 不创建模型请求；
- 模式切换后下一条普通消息使用新模式。

### 9.5 TUI 测试

- `stream_text` 追加内容；
- `tool_use` 和 `tool_result` 正确展示；
- `turn_complete` 更新迭代轮次；
- `loop_complete` 恢复空闲态；
- 流式态 Esc/Ctrl+C 取消而不退出；
- 空闲态 Ctrl+C 退出；
- 空闲态 Esc 无操作。

### 9.6 tmux 端到端测试

启动真实 MewCode 进程后至少执行：

1. 发送一个需要读取文件、搜索结果后继续行动的请求，确认出现多轮 Loop；
2. 发送一个需要多个安全工具的请求，确认工具能并发且结果顺序稳定；
3. 切换 `/plan`，确认不会调用写入或 Bash；
4. 切换 `/do`，确认下一条消息恢复全工具；
5. 在 LLM 流阶段按 Esc；
6. 在工具执行阶段按 Ctrl+C；
7. 确认两种取消都回到空闲态且能继续对话；
8. 空闲态按 Ctrl+C，确认程序退出。

## 10. 风险与处理

| 风险 | 处理方式 |
|---|---|
| provider SDK 流无法及时关闭 | 统一封装 closeable stream；关闭后不再消费后续事件，并以 best-effort 结束后台线程 |
| 工具忽略中断信号 | `CancellationToken` 与 `Future.cancel(true)` 双重通知；Loop 不等待无限期清理 |
| 并发工具完成顺序不同 | 使用原始索引槽位归并，发布和回灌都按原始顺序 |
| 流式工具 JSON 不完整 | 只在内容块结束后解析；未完成 turn 不写入历史 |
| 中断流没有 usage | 标记未知，不做字符估算 |
| Plan Mode 漏掉危险工具 | 请求层过滤和执行层二次校验同时存在 |
| Ctrl+C 误退出程序 | TUI 根据当前 AppState 路由：流式态取消，空闲态退出 |
| 旧 TUI 依赖旧事件类型 | 先保留适配层完成事件迁移，再删除旧事件分支 |
| 三协议字段不一致 | provider 适配层保留协议专属字段，Agent 层只使用统一领域模型 |

## 11. 完成定义

满足以下条件后，本计划视为完成：

- `spec.md`、`plan.md`、`task.md`、`checklist.md` 均已确认；
- Agent Loop 能完成至少两轮工具调用并正常结束；
- 最大轮次、未知工具、流错误和用户取消均能正确收尾；
- 事件流只暴露确认的七种公共事件；
- 工具并发、串行和结果顺序符合设计；
- Plan Mode 和 Execute Mode 行为符合安全边界；
- Anthropic、OpenAI、DeepSeek 兼容端点通过一致性测试；
- Token 用量累计和未知状态正确；
- 取消后历史合法且可以继续对话；
- 所有自动化测试通过；
- 按 `checklist.md` 完成 tmux 端到端验收。

## 12. 协议参考

实现 provider 适配时以官方协议文档为准：

- [Anthropic Messages Streaming](https://docs.anthropic.com/en/api/messages-streaming)：内容块流、工具输入 JSON 增量和消息级 usage；
- [OpenAI Chat Completions API](https://platform.openai.com/docs/api-reference/chat)：流式工具调用和 `stream_options.include_usage`；
- [DeepSeek Function Calling](https://api-docs.deepseek.com/guides/function_calling)：OpenAI/Anthropic 兼容接口和流式工具调用；
- [DeepSeek Thinking Mode](https://api-docs.deepseek.com/guides/thinking_mode)：思考模式下 `reasoning_content` 的请求回传要求。

这些文档只用于确定协议适配细节；本章的功能范围、事件契约、停止条件和取消语义以 `spec.md` 为准。
