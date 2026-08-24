# MewCode Agent Loop 与 Plan Mode Task

> 状态：已实施并验收
>
> 本文件把已确认的 [plan.md](/Users/bytedance/IdeaProjects/Mewcode-develop/docs/ch4/plan.md) 拆成可以逐项实施和验证的任务。所有任务完成后，仍需依据 `checklist.md` 做最终验收。

## 1. 执行规则

- 严格按照依赖顺序执行；前置任务编译或窄范围测试失败时，先修复再进入后续任务。
- 每个任务只修改列出的职责范围，不顺手引入权限系统、上下文压缩、交互式确认、MCP 或子代理。
- 代码和测试注释使用中文。
- 工具输入、事件字段、对话消息和 provider 请求必须保持 provider 无关；协议差异只留在 `com.mewcode.llm`。
- 文件测试使用独立临时目录，不能修改项目真实文件；路径相关测试统一使用绝对路径。
- 取消测试必须验证“当前 Loop 尽快收尾、没有下一次 LLM 请求”，不能用等待自然超时来伪造取消成功。
- 每个任务完成后运行对应的窄范围测试；所有任务完成后运行完整 Gradle 测试、打包和 tmux 端到端测试。
- 除用户明确要求的章节文件外，不修改 `.idea/.name` 等无关的既有用户改动。

## 2. 任务总览

| 编号 | 任务 | 前置任务 | 主要产物 |
| --- | --- | --- | --- |
| T0 | 建立第 4 章基线 | 无 | 基线测试结果 |
| T1 | 固化 Agent Loop 公共契约和配置 | T0 | `AgentEvent`、`AgentLoopConfig`、`AgentMode` |
| T2 | 实现可取消 provider 流和统一用量模型 | T1 | `CancellableLlmStream`、`TokenUsage`、provider 流接口 |
| T3 | 实现双路流式收集器 | T2 | `TurnStreamCollector`、`CollectedTurn` |
| T4 | 改造对话历史和 Prompt Mode | T1 | 完整 turn 提交、模式提示 |
| T5 | 增强工具过滤、取消和稳定批调度 | T1 | `ToolPolicy`、取消结果、稳定批处理 |
| T6 | 实现连续 Agent Loop 状态机 | T3、T4、T5 | 多轮 `AgentTurnCoordinator` |
| T7 | 接入 Plan Mode 和 TUI 事件展示 | T6 | `/plan`、`/do`、迭代进度、事件消费 |
| T8 | 完成三协议一致性与用量测试 | T2、T3、T6 | Anthropic/OpenAI/DeepSeek 测试 |
| T9 | 完成取消、回归、打包和 tmux 验收 | T6、T7、T8 | 自动化测试和 E2E 证据 |

## 3. T0：建立第 4 章基线

### T0.1 记录编译和测试基线

- 不修改生产代码。
- 执行当前完整 Gradle 测试和打包。
- 记录现有 `AgentTurnCoordinatorTest`、provider 测试和 `MewCodeModelTest` 的结果。
- 确认当前纯文本对话、一次工具结果回灌和空闲态 Ctrl+C 行为可复现。

完成条件：基线命令和结果已记录，后续失败可以区分为本章引入的问题或已有问题。

## 4. T1：固化 Agent Loop 公共契约和配置

### T1.1 重构 AgentEvent 公共事件

- 文件：`src/main/java/com/mewcode/agent/AgentEvent.java`。
- 将公共事件收敛为七种：`StreamText`、`ToolUse`、`ToolResult`、`TurnComplete`、`LoopComplete`、`Usage`、`Error`。
- `ToolUse` 必须包含请求 ID、工具名和结构化输入。
- `ToolResult` 必须包含请求 ID、工具名、完整结果、错误标记和耗时。
- `TurnComplete` 只表示一次 LLM 调用完成；`LoopComplete` 只表示整个 Loop 结束。
- usage 字段允许未知，禁止使用字符数估算。
- 不新增公共 `progress`、`thinking`、`tool_started` 或 `tool_finished` 事件。
- 旧事件到新事件的迁移必须同步更新所有生产调用方和测试。

验证：事件类型可被 UI 以 provider 无关方式消费；编译时不存在旧事件的生产引用。

### T1.2 增加运行状态和模式类型

- 新增 `src/main/java/com/mewcode/agent/AgentMode.java`，定义 `PLAN` 和 `EXECUTE`。
- 新增 `src/main/java/com/mewcode/agent/AgentRun.java`，定义事件流、取消、运行状态和当前运行句柄。
- 新增 `src/main/java/com/mewcode/agent/AgentEventStream.java`，封装有序异步事件消费和关闭。
- 新增内部 `LoopStopReason`，至少覆盖 `MODEL_COMPLETE`、`MAX_ITERATIONS`、`UNKNOWN_TOOLS`、`USER_CANCELLED`、`STREAM_ERROR`、`INTERNAL_ERROR`。
- 保证一个 `AgentRun` 的取消和收尾幂等。

验证：可以创建并关闭一个空运行；重复调用 cancel 不产生重复终止行为。

### T1.3 增加 AgentLoopConfig

- 新增 `src/main/java/com/mewcode/agent/AgentLoopConfig.java`。
- 默认 `maxIterations=20`。
- 默认 `unknownToolRoundLimit=3`。
- 校验两个配置必须为正数；非法配置在加载阶段给出明确错误。
- 不增加权限确认、上下文压缩和后台任务配置。

### T1.4 扩展应用配置加载

- 文件：`src/main/java/com/mewcode/config/AppConfig.java`、`ConfigLoader.java`。
- 解析以下 YAML 路径：

  ```yaml
  agent:
    loop:
      max-iterations: 20
      unknown-tool-round-limit: 3
  ```

- 配置缺失时回退到默认值。
- 保持现有 provider 配置兼容。

验证：覆盖缺省、完整配置、零值、负值、非数字和旧配置文件。

## 5. T2：实现可取消 provider 流和统一用量模型

### T2.1 设计可取消流句柄

- 文件：`src/main/java/com/mewcode/llm/LlmClient.java`。
- 增加可取消流的返回类型，例如 `CancellableLlmStream`，包含事件队列和幂等 `close()`。
- 保留一次性纯文本调用所需的兼容入口，迁移完成后删除无用重载。
- `close()` 必须关闭 SDK 流、停止后台生产线程，并向消费者提供可识别的结束状态。
- provider 线程不能因为 UI 取消而永久阻塞在队列写入或 SDK 读取上。

验证：fake provider 流关闭后，协调器可在没有 `StreamEnd` 的情况下完成取消收尾。

### T2.2 增加统一 TokenUsage

- 文件：`src/main/java/com/mewcode/llm/StreamEvent.java`，必要时新增 `TokenUsage.java`。
- 增加本轮 usage 事件，区分 input/output 和 unknown 状态。
- provider 未提供 usage 时保持 unknown；不根据文本长度估算。
- 支持协调器累加跨轮用量并生成累计 `AgentEvent.Usage`。

验证：单轮有 usage、多轮累加、重复 usage 不重复累加、中断无最终 usage 四类场景。

### T2.3 改造 AnthropicClient

- 文件：`src/main/java/com/mewcode/llm/AnthropicClient.java`。
- 保留当前内容块和 tool JSON delta 解析。
- 在消息 usage 更新处发布统一 usage。
- provider 流资源关闭时停止继续消费，不把关闭异常误报成普通 provider 错误。
- 保留 thinking 内容供下一轮请求使用，但不发布新的公共 thinking 事件。

验证：现有 Anthropic SSE fixture 继续通过；补充 usage、流关闭和中断场景。

### T2.4 改造 OpenAiClient 和 DeepSeek 路由

- 文件：`src/main/java/com/mewcode/llm/OpenAiClient.java`、`LlmClients.java`。
- 按工具调用 index/id 合并 delta。
- 开启流式 usage 选项并读取最终 usage chunk。
- 通过现有 `baseUrl` 支持 OpenAI 兼容服务。
- DeepSeek OpenAI 兼容端点复用 OpenAI adapter；不新增 DeepSeek 专用客户端。
- 识别并保留 DeepSeek thinking 模式所需的 reasoning 字段。

验证：现有 OpenAI fixture、兼容 base URL、多个工具 delta、最终 usage 和 reasoning 回传。

## 6. T3：实现双路流式收集器

### T3.1 定义 CollectedTurn 和 ToolCall

- 新增 `src/main/java/com/mewcode/agent/CollectedTurn.java` 或等价内部类型。
- 工具调用使用统一 `ToolCall(requestId, toolName, input, originalIndex)`。
- 收集器必须保存完整文本、thinking/reasoning、工具调用列表、usage、完整状态和错误。
- provider SDK 类型不得出现在这些类型的字段中。

### T3.2 实现 TurnStreamCollector

- 新增 `src/main/java/com/mewcode/agent/TurnStreamCollector.java`。
- `TextDelta` 到达时立即发送 `AgentEvent.StreamText`，同时追加到完整文本缓冲区。
- `ToolCallComplete` 到达时立即发送 `AgentEvent.ToolUse`，同时按原始顺序写入 turn buffer。
- `ToolCallParseError` 转换为该调用的错误状态，不影响其他调用；由协调器决定是否进入工具结果回灌。
- usage 到达时累加到本轮状态，并发送累计 `AgentEvent.Usage`。
- 收到流结束才将 `CollectedTurn.complete=true`。
- 流异常或取消时标记不完整，不提交 assistant 响应。

验证：文本和工具调用交错、多个调用参数交错、工具解析失败、流中断、usage 缺失和事件顺序。

### T3.3 保证事件流收尾

- 所有正常、错误、取消和上限路径统一由协调器负责结束事件流。
- `loop_complete` 必须只发送一次且是最后一个业务事件。
- 事件流关闭后禁止 provider/工具后台任务继续向 UI 发布事件。

## 7. T4：改造对话历史和 Prompt Mode

### T4.1 增加完整 turn 的历史提交边界

- 文件：`src/main/java/com/mewcode/conversation/ConversationManager.java`、`ToolResultAssembler.java`。
- 增加一次性提交 assistant 完整文本/工具调用的方法。
- 增加把多个工具结果写入同一条 user 消息的方法。
- 工具结果必须按原始调用顺序排列并保持 request ID 配对。
- 未完成的 provider turn 不得写入历史。
- 工具执行阶段取消时，为所有已提交调用补齐成功、错误或取消结果。
- 不写入事件 metadata、耗时、迭代状态和内部停止原因。

验证：正常多轮、LLM 流取消、工具批次取消、provider 错误后的历史均可继续对话。

### T4.2 更新 PromptBuilder

- 文件：`src/main/java/com/mewcode/prompt/PromptBuilder.java`。
- 删除第 3 章“一次工具结果回灌后结束”的约束。
- 增加基于 `AgentMode` 的模式提示。
- Plan Mode 提示模型只使用只读调查能力并输出计划；不通过伪造用户消息注入 `/do`。
- 不把 `/plan`、`/do` 命令写入对话历史。

验证：执行模式和规划模式的系统提示不同；普通纯文本请求不出现旧的一轮限制。

### T4.3 实现 ToolPolicy

- 新增 `src/main/java/com/mewcode/agent/ToolPolicy.java` 或等价策略类。
- Plan Mode 的允许条件固定为 `isReadOnly && !isDestructive`。
- Execute Mode 允许全部已注册工具。
- 每个 AgentRun 创建工具策略快照，运行期间不随 UI 模式变化。

验证：六个内置工具在两种模式下的允许集合符合要求；模式禁用工具无法进入执行器。

## 8. T5：增强工具过滤、取消和稳定批调度

### T5.1 增加执行取消上下文

- 文件：`src/main/java/com/mewcode/tool/ToolExecutionContext.java`。
- 增加 `CancellationToken` 或等价只读取消接口。
- 提供 `isCancelled()` 和 `throwIfCancelled()`。
- 不要求本章所有工具都支持任意时刻停止，但工具不得吞掉取消状态。

### T5.2 改造 ToolExecutor 批处理

- 文件：`src/main/java/com/mewcode/tool/ToolExecutor.java`、`ToolInvocationResult.java`。
- 请求层先拒绝模式禁用工具和未知工具。
- 执行层再次检查工具策略，防止绕过请求过滤。
- 安全工具组成并发批次；有副作用或不安全工具形成串行屏障。
- 使用 originalIndex 槽位保存结果，最终按原始顺序归并。
- 单个任务异常转为错误结果，不取消同批其他任务。
- Loop 取消时调用 `Future.cancel(true)` 和取消令牌；活动调用立即返回 `CANCELLED` 结果。
- 结果包含耗时，但耗时只进入 AgentEvent 和本地 metadata，不进入模型正文。

### T5.3 处理 Bash 子进程取消

- 文件：`src/main/java/com/mewcode/tool/support/CommandRunner.java`、`BashTool.java`。
- 将取消令牌传入命令执行上下文。
- 取消或超时时强制销毁子进程，关闭输出消费。
- 区分用户取消、超时和普通非零退出码。
- 不改变第 3 章已有的工作目录、输出截断和退出码语义。

验证：阻塞命令收到取消后不阻塞 Loop；命令进程不存在残留；普通命令错误仍可回灌模型。

## 9. T6：实现连续 Agent Loop 状态机

### T6.1 重构 AgentTurnCoordinator 入口

- 文件：`src/main/java/com/mewcode/agent/AgentTurnCoordinator.java`。
- 普通用户消息只创建一个连续 AgentRun。
- 每轮按 `AgentLoopConfig.maxIterations` 运行 LLM、收集、工具执行和历史回灌。
- 当前轮次从 1 开始，`TurnComplete` 使用已完成 LLM 轮次。
- 达到最大轮次后不发起下一次 LLM 请求。
- 每轮开始和工具执行前检查取消。

### T6.2 实现正常完成路径

- provider turn 完整结束后发送 `turn_complete`。
- 没有工具调用时提交完整 assistant 响应并发送 `loop_complete`。
- 有工具调用时提交 assistant tool-use，执行工具，按顺序发布/写入 tool-result，再进入下一轮。
- 最终回复只写入一次，不重复拼接流式片段。

### T6.3 实现未知工具路径

- 未知工具发送 `tool_use` 后生成结构化错误 `tool_result`。
- 同轮有已知工具时继续执行已知工具。
- 没有可执行已知工具时增加连续计数。
- 达到三轮后结束，不再请求下一轮 LLM。

### T6.4 实现错误和取消路径

- provider/Loop 错误发送 `error` 后统一 `loop_complete`。
- LLM 流阶段取消：关闭 provider 流，丢弃未完成 turn，不执行未提交工具。
- 工具阶段取消：取消活动任务，补齐结果，不再发起下一轮。
- 所有路径只发送一次 `loop_complete`。
- 取消本身不当作业务 error；关闭流产生非预期异常时才发送 `error`。

### T6.5 实现累计 usage

- 按 provider 回传的本轮 usage 更新累计值。
- 每轮用量只累计一次。
- 中断流没有最终 usage 时保持 unknown。
- 最后一轮和取消前已收到的用量仍然可以通过 `usage` 事件发布。

## 10. T7：接入 Plan Mode 和 TUI 事件展示

### T7.1 实现本地模式切换

- 文件：`src/main/java/com/mewcode/tui/MewCodeModel.java` 及命令处理分支。
- `/plan` 只更新会话模式并回到空闲态，不调用模型。
- `/do` 只更新会话模式并回到空闲态，不调用模型。
- 两个命令不写入 ConversationManager。
- 普通消息使用当前模式创建 AgentRun。

### T7.2 迁移 AgentEvent 消费

- 文件：`src/main/java/com/mewcode/tui/MewCodeModel.java`、`ToolDisplayFormatter.java`、`AppState.java`。
- `StreamText` 追加动态文本。
- `ToolUse` 创建工具状态项。
- `ToolResult` 折叠显示完整结果、错误标记和耗时。
- `Usage` 更新 Token 展示。
- `TurnComplete` 更新“第 N 轮”迭代状态。
- `Error` 展示错误但不崩溃。
- `LoopComplete` 清理活动运行句柄、动态区和输入锁。

### T7.3 修改按键路由

- 文件：`src/main/java/com/mewcode/tui/tea/KeyPressMessage.java`、`Program.java`、`MewCodeModel.java`。
- 活动 AgentRun 存在时，Esc/Ctrl+C 路由到 `AgentRun.cancel()`。
- 没有活动 AgentRun 时，Ctrl+C 保留现有 `QuitMessage` 路径。
- 空闲 Esc 直接忽略。
- 取消期间防止重复创建请求和重复退出。

验证：流式态两种按键均不退出程序；空闲态 Ctrl+C 仍退出；取消后可以继续输入。

## 11. T8：完成三协议一致性与用量测试

### T8.1 Anthropic 测试

- 文件：`src/test/java/com/mewcode/llm/AnthropicClientTest.java` 及新增 SSE fixture。
- 覆盖文本增量、工具 JSON 增量、多个 tool-use、message usage、thinking 内容和流关闭。
- 断言 provider SDK 事件转换为统一 `StreamEvent`。

### T8.2 OpenAI 测试

- 文件：`src/test/java/com/mewcode/llm/OpenAiClientTest.java` 及新增 SSE fixture。
- 覆盖多个工具 delta、按 index 合并、最终 usage、流中断无 usage 和 base URL。
- 断言工具调用参数不会因 delta 交错而串线。

### T8.3 DeepSeek 兼容测试

- 通过确定性 HTTP/SSE 测试服务配置 OpenAI 兼容 base URL。
- 通过确定性 HTTP/SSE 测试服务配置 Anthropic 兼容 base URL。
- 覆盖多轮工具调用、reasoning 内容回传、累计 usage 和取消。
- 不添加第三套 Loop 状态机，只验证两个既有 adapter 的协议路径。

## 12. T9：完成取消、回归、打包和 tmux 验收

### T9.1 Agent Loop 自动化测试

- 文件：`src/test/java/com/mewcode/agent/AgentTurnCoordinatorTest.java` 及新增测试类。
- 使用 fake `LlmClient` 和 fake `Tool` 控制每一轮响应。
- 覆盖无工具、多轮工具、最大轮次、连续未知工具、工具失败、provider 错误和事件终止顺序。
- 断言 `loop_complete` 最后且只出现一次。

### T9.2 取消和历史测试

- 使用可控 stream、CountDownLatch 和阻塞工具模拟流阶段/工具阶段取消。
- 断言 provider 流 close 被调用、工具收到取消、没有后续 LLM 请求。
- 断言活动工具生成取消结果，历史仍保持 assistant/tool-result 配对。
- 断言不完整的 LLM 响应不会写入历史，取消后下一条消息可以继续。

### T9.3 TUI 和模式测试

- 文件：`src/test/java/com/mewcode/tui/MewCodeModelTest.java`，必要时增加按键测试。
- 覆盖七种事件的展示、轮次动态区、`/plan`、`/do`、流式态 Esc/Ctrl+C、空闲态 Ctrl+C 和空闲态 Esc。
- 断言模式命令不触发模型请求、不写入历史。

### T9.4 完整 Gradle 验证

- 运行全部单元测试。
- 运行编译、打包和现有 provider 测试。
- 检查没有后台线程、子进程或事件消费者泄漏。
- 检查 `git diff --check`。

### T9.5 tmux 端到端验收

按仓库 `AGENTS.md` 要求：

1. 在 tmux 中启动真实 MewCode 进程；
2. 发送需要多轮工具调用的真实用户请求；
3. 观察工具调用、工具结果回灌、迭代轮次、Token 用量和最终回答；
4. 在 LLM 流阶段按 Esc；
5. 在工具执行阶段按 Ctrl+C；
6. 确认两种取消均回到空闲态、不退出程序且可以继续对话；
7. 空闲态按 Ctrl+C 确认程序退出；
8. 对照 `checklist.md` 逐项记录结果。

完成条件：真实进程能够通过至少一个 Anthropic、OpenAI 或 DeepSeek 兼容配置完成多轮 Loop，并保留可复核的 tmux 输出。

## 13. 任务完成定义

- T0-T9 全部完成并通过对应窄范围测试；
- `spec.md`、`plan.md`、`task.md` 和后续 `checklist.md` 内容一致；
- 七种公共事件没有额外泄漏 provider SDK 类型；
- 流式态 Esc/Ctrl+C 取消当前 Loop，空闲态 Ctrl+C 退出程序；
- 取消后不发起下一轮 LLM，历史仍合法并能继续对话；
- Anthropic、OpenAI、DeepSeek 兼容端点的多轮工具调用、usage、取消和收尾语义一致；
- 完成 tmux 端到端验收后，才进入实现交付阶段。
