# MewCode Agent Loop 与 Plan Mode 验收 Checklist

> 状态：已验收
>
> 本清单基于已确认的 [spec.md](/Users/bytedance/IdeaProjects/Mewcode-develop/docs/ch4/spec.md)、[plan.md](/Users/bytedance/IdeaProjects/Mewcode-develop/docs/ch4/plan.md) 和 [task.md](/Users/bytedance/IdeaProjects/Mewcode-develop/docs/ch4/task.md)。实现阶段逐项勾选，并记录测试名称、命令或 tmux 证据。

## 1. 使用规则

- 只有自动化测试通过或有可复现的命令行/端到端证据，才能勾选项目。
- 任何失败项都要记录实际错误和对应修复任务，不能用“基本可用”替代验收。
- 取消项必须验证当前 Loop 尽快收尾、没有下一次 LLM 请求，不能只验证 UI 文案变化。
- 工具结果的 metadata 只能用于 UI、日志和本地诊断，不能进入 provider 请求体。
- 文件测试全部在独立临时项目根目录中执行，路径输入使用绝对路径。
- 端到端测试必须使用 tmux 启动真实 MewCode 进程，并保留关键输出。
- 在所有项目完成前，不得把“最终验收通过”标记为完成。

## 2. 实现前基线

- [x] 运行并记录当前完整 Gradle 测试结果。
- [x] 运行并记录当前打包结果。
- [x] 确认纯文本请求、现有一次工具结果回灌和空闲态 Ctrl+C 行为可复现。
- [x] 确认工作树中的 `.idea/.name` 等无关用户改动没有被覆盖。

## 3. 构建与配置

- [x] Java 21 编译通过。
- [x] Anthropic Java `2.34.0` 和 OpenAI Java `4.37.0` 未被无关升级。
- [x] `agent.loop.max-iterations` 缺省值为 20。
- [x] `agent.loop.unknown-tool-round-limit` 缺省值为 3。
- [x] 自定义最大轮次和未知工具阈值能从 YAML 正确加载。
- [x] 零值、负值和非法类型配置会被拒绝并给出明确错误。
- [x] 旧 provider 配置在没有 Agent Loop 配置时仍能正常加载。
- [x] `./gradlew test` 通过。
- [x] `./gradlew shadowJar` 通过。

## 4. 公共 AgentEvent 契约

### AC1 七种事件

- [x] 公共事件只有 `stream_text`、`tool_use`、`tool_result`、`turn_complete`、`loop_complete`、`usage`、`error`。
- [x] 没有额外暴露 `progress`、`thinking`、`tool_started` 或 `tool_finished` 事件。
- [x] `stream_text` 携带文本增量。
- [x] `tool_use` 携带工具名、结构化输入和请求 ID。
- [x] `tool_result` 携带请求 ID、完整结果、错误标记和耗时。
- [x] `turn_complete` 携带当前 LLM 轮次。
- [x] `loop_complete` 携带总轮次。
- [x] `usage` 携带累计输入/输出 Token，并能表示未知。
- [x] `error` 携带可展示的错误信息和错误类别。

### AC2 事件顺序和收尾

- [x] 文本增量按到达顺序发布并可实时消费。
- [x] 完整工具调用在工具参数收集完成后发布。
- [x] provider 流完整结束后才发布 `turn_complete`。
- [x] 工具结果按原始调用顺序发布。
- [x] 异常路径在 `loop_complete` 前发布 `error`。
- [x] `loop_complete` 永远是最后一个业务事件。
- [x] `loop_complete` 每个 AgentRun 只发送一次。
- [x] 事件流关闭后没有 provider 或工具后台事件继续泄漏到 UI。

## 5. Agent Loop 正常流程

### AC3 无工具正常结束

- [x] 普通用户消息只创建一个 AgentRun。
- [x] 模型没有 `tool_use` 时不执行工具。
- [x] 模型没有 `tool_use` 时不发起额外 LLM 请求。
- [x] 完整 assistant 文本写入历史。
- [x] 正常路径发送 `turn_complete` 和 `loop_complete`。

### AC4 多轮 ReAct

- [x] 第一轮工具调用完成后，结果写回历史。
- [x] 工具结果写回后能发起下一轮 LLM 请求。
- [x] 至少两轮工具调用可以连续完成。
- [x] 每轮都有正确的 `turn_complete` 轮次。
- [x] 最终无工具响应后才结束整个 Loop。
- [x] 不会保留第 3 章“一次结果回灌后强制结束”的旧逻辑。

### AC5 最大迭代次数

- [x] 默认最大迭代次数为 20。
- [x] 自定义最大迭代次数生效。
- [x] 达到上限后不再发起下一次 LLM 请求。
- [x] 最后一轮工具结果仍按规则完成归并和历史写入。
- [x] 上限停止后发送最终 `loop_complete`。
- [x] 最大迭代路径不会死循环或无限等待。

### AC6 连续未知工具

- [x] 未知工具不会被执行。
- [x] 未知工具会收到结构化错误 `tool_result`。
- [x] 同轮存在已知工具时，已知工具仍然执行。
- [x] 一轮没有可执行已知工具时，连续计数加一。
- [x] 一轮至少执行一个已知工具时，连续计数归零。
- [x] 连续三轮没有可执行已知工具后停止。
- [x] 未知工具停止路径最终发送 `loop_complete`。

### AC7 Provider 流错误

- [x] 模拟 provider 流异常时不会崩溃。
- [x] 流错误会发送 `error`。
- [x] 流错误后不会发起下一轮 LLM 请求。
- [x] 未完成的 assistant 流不会写入历史。
- [x] 流错误路径最终发送 `loop_complete`。

## 6. 立即取消

### AC8 LLM 流阶段取消

- [x] 流式态按 `Esc` 能取消当前 AgentRun。
- [x] 流式态按 `Ctrl+C` 能取消当前 AgentRun。
- [x] 当前 provider 流的 close 方法确实被调用。
- [x] 取消后不再发起下一轮 LLM 请求。
- [x] 未完整结束的 turn buffer 不写入历史。
- [x] 尚未提交的工具调用不执行。
- [x] 取消后发送 `loop_complete` 并回到空闲态。
- [x] 取消不会生成程序退出消息。

### AC9 工具执行阶段取消

- [x] 活动工具收到 CancellationToken 或等价取消通知。
- [x] 活动工具收到 `Future.cancel(true)` 或等价中断请求。
- [x] 已完成工具保留真实结果。
- [x] 活动工具生成 `CANCELLED` 结果，不等待自然超时。
- [x] 所有已经提交的工具调用都有成功、错误或取消结果。
- [x] 取消期间工具结果仍按原始调用顺序发布。
- [x] 取消后不再发起下一次 LLM 请求。
- [x] 已发生的文件或 shell 副作用不被假定为已回滚。
- [x] 工具取消路径最终发送 `loop_complete` 并恢复输入。

### AC10 空闲态按键

- [x] 空闲态按 `Ctrl+C` 仍然退出整个程序。
- [x] 空闲态按 `Esc` 不退出程序。
- [x] 重复按 Esc/Ctrl+C 不重复关闭事件流或程序。
- [x] 取消完成后可以继续发送下一条普通消息。

## 7. 双路流式收集

### AC11 文本收集

- [x] 文本 delta 到达时立即发送 `stream_text`。
- [x] 同一份文本增量被累积为完整 assistant 文本。
- [x] 最终 assistant 历史内容与所有增量拼接结果一致。
- [x] 取消或流错误时，不完整文本不会写入历史。

### AC12 工具参数收集

- [x] 单个工具调用的 JSON 增量可以合并为完整 JSON 对象。
- [x] 多个工具调用交错到达时参数不会串线。
- [x] 每个工具调用保留稳定的请求 ID和原始索引。
- [x] 完整工具调用才发布 `tool_use`。
- [x] 单个调用解析失败不会破坏其他调用。
- [x] 解析错误能转化为错误结果或统一错误路径。

### AC13 工具结果非流式

- [x] 工具执行期间不发布部分 `tool_result`。
- [x] 工具完成后只发布一次完整 `tool_result`。
- [x] `tool_result` 包含完整结果、错误标记和耗时。
- [x] 工具结果一次性回灌模型。

## 8. 多工具调度与历史

### AC14 安全批处理

- [x] `isReadOnly && !isDestructive` 的安全工具可以进入并发批次。
- [x] 不具备并发安全性的工具不会和其他工具并发交叉执行。
- [x] 有副作用工具形成串行屏障。
- [x] 单个工具失败不会取消同批其他安全工具。
- [x] 并发结果最终按模型原始调用顺序发布。
- [x] 结果 ID 与请求 ID 一一对应，不会串线或覆盖。

### AC15 工具错误隔离

- [x] 未知工具返回结构化错误结果。
- [x] 参数校验失败不执行工具。
- [x] 单个工具抛异常会转为错误 `tool_result`。
- [x] 单个工具超时会转为错误 `tool_result`。
- [x] 工具错误结果可以回灌模型继续判断。
- [x] 工具执行器异常不会让程序崩溃。

### AC16 历史合法性

- [x] 初始用户消息写入历史。
- [x] 完整 assistant 工具轮包含文本和全部 tool-use。
- [x] 所有工具结果写入同一条 user tool-result 消息。
- [x] 每个 tool-result 通过请求 ID 配对。
- [x] metadata、耗时、迭代状态和 UI 文本不进入 provider 请求体。
- [x] 最终无工具 assistant 回复只写入一次。
- [x] 取消后历史可以继续发起下一条对话。

## 9. Plan Mode

### AC17 本地命令

- [x] 默认模式为 Execute Mode。
- [x] `/plan` 只切换本地模式，不调用模型。
- [x] `/do` 只切换本地模式，不调用模型。
- [x] `/plan` 和 `/do` 不写入对话历史。
- [x] 模式持续到下一次显式切换。
- [x] 模式在一个 AgentRun 开始时固定。

### AC18 工具过滤和二次防线

- [x] Plan Mode 动态开放全部 `isReadOnly=true && isDestructive=false` 工具。
- [x] Plan Mode 不使用固定工具名称白名单。
- [x] Plan Mode 请求中不包含 WriteFile、EditFile、Bash 等禁用工具。
- [x] Execute Mode 请求中包含全部已注册工具。
- [x] 执行层再次拒绝模式禁用工具。
- [x] 模型绕过请求过滤直接请求禁用工具时，不发生副作用，并返回错误结果。

## 10. Token 用量和跨协议一致

### AC19 Token 用量

- [x] provider 返回本轮 usage 时发送累计 `usage` 事件。
- [x] 多轮 input/output Token 累计正确。
- [x] 重复或重复消费 provider usage 不会重复累加。
- [x] provider 未提供 usage 时保持 unknown。
- [x] 流中断导致最终 usage 缺失时保持 unknown。
- [x] 不使用字符数或文本长度估算 Token。

### AC20 Anthropic

- [x] Anthropic 文本流可以转换为 `stream_text`。
- [x] Anthropic 工具 JSON 增量可以转换为完整 `tool_use`。
- [x] Anthropic 工具结果可以回灌并完成下一轮。
- [x] Anthropic message usage 可以累计。
- [x] Anthropic thinking 内容按协议要求保留，不泄漏为公共事件。
- [x] Anthropic 流阶段和工具阶段取消行为正确。

### AC21 OpenAI

- [x] OpenAI 文本流可以转换为 `stream_text`。
- [x] 多个 OpenAI tool-call delta 可以按 index/id 正确合并。
- [x] `include_usage` 返回的最终 usage 可以累计。
- [x] OpenAI 兼容 `baseUrl` 能复用同一 adapter。
- [x] 流被中断且没有最终 usage 时不伪造用量。
- [x] OpenAI 流阶段和工具阶段取消行为正确。

### AC22 DeepSeek

- [x] DeepSeek OpenAI 兼容端点可以完成多轮工具调用。
- [x] DeepSeek Anthropic 兼容端点可以完成多轮工具调用。
- [x] DeepSeek 兼容端点使用 `baseUrl`，不新增第三套 Loop。
- [x] thinking 模式所需的 reasoning 内容在后续请求中保留。
- [x] DeepSeek usage、取消和 `loop_complete` 语义与对应 adapter 一致。

## 11. TUI 和迭代进度

### AC23 事件展示

- [x] `stream_text` 能追加到输出区域。
- [x] `tool_use` 能显示“正在执行工具”的状态。
- [x] `tool_result` 能折叠展示完整结果、错误标记和耗时。
- [x] `usage` 能刷新累计 Token 展示。
- [x] `error` 能展示错误且不让程序崩溃。
- [x] `loop_complete` 后动态区结束、输入恢复。

### AC24 迭代轮次

- [x] 流式态能展示当前迭代轮次。
- [x] 至少两轮 Loop 中能看到轮次从第 1 轮推进到第 2 轮。
- [x] 轮次通过 `turn_complete` 更新，不依赖额外 progress 事件。
- [x] Loop 正常完成或取消后轮次状态被清理。

## 12. 自动化测试

- [x] `AgentEvent` 类型和事件顺序测试通过。
- [x] `AgentRun` 幂等取消和事件流关闭测试通过。
- [x] `TurnStreamCollector` 双路收集测试通过。
- [x] 多工具交错参数和解析错误测试通过。
- [x] Agent Loop 多轮、无工具、最大轮次和未知工具测试通过。
- [x] provider 流错误测试通过。
- [x] LLM 流阶段取消测试通过。
- [x] 工具执行阶段取消测试通过。
- [x] 工具并发、串行屏障和结果稳定顺序测试通过。
- [x] 历史合法性和取消后继续对话测试通过。
- [x] Plan Mode 过滤和执行层二次防线测试通过。
- [x] `/plan`、`/do` 不触发模型请求测试通过。
- [x] TUI 事件、轮次和按键路由测试通过。
- [x] Anthropic、OpenAI、DeepSeek 兼容协议测试通过。
- [x] 现有纯文本、六个内置工具和文件状态测试全部回归通过。
- [x] `./gradlew test` 通过。
- [x] `./gradlew shadowJar` 通过。

## 13. tmux 端到端验收

- [x] 在 tmux 中启动真实 MewCode 进程。
- [x] 发送需要至少两轮工具调用的真实用户请求。
- [x] 观察到文本增量、工具调用、工具结果、轮次和最终回答。
- [x] 观察到累计 Token 用量或明确的 unknown 状态。
- [x] 在 LLM 流阶段按 Esc，确认立即取消当前 Loop。
- [x] 在工具执行阶段按 Ctrl+C，确认活动工具收到取消请求。
- [x] 两种流式态取消都不退出程序。
- [x] 两种流式态取消后都回到空闲态并可以继续对话。
- [x] 取消后对话历史仍然合法。
- [x] 空闲态按 Esc 不退出、不触发模型请求。
- [x] 空闲态按 Ctrl+C 退出整个程序。
- [x] 至少使用一个 Anthropic、OpenAI 或 DeepSeek 兼容配置完成真实端到端 Loop。
- [x] 保留 tmux 关键输出和测试服务日志作为证据。

## 14. 最终门禁

- [x] AC1～AC24 全部完成并有测试或运行证据。
- [x] 所有失败测试均已修复并重新运行，不保留已知失败。
- [x] 没有修改或删除用户无关的既有代码和配置。
- [x] 没有引入权限审批、上下文压缩或交互式确认。
- [x] 没有新增独立的 DeepSeek Loop 状态机。
- [x] 没有把 UI 展示信息或事件 metadata 写入模型历史。
- [x] 已保存完整测试命令和 tmux 端到端关键输出。
- [x] `checklist.md` 状态更新为“已验收”。
- [x] 最终回复列出实现文件、测试命令、端到端结果和已知限制。

## 15. 验收记录

> 实现阶段填写。每项至少记录命令、测试名称或 tmux 输出位置。

- 基线：实施前既有 `./gradlew test --no-daemon` 与 `./gradlew shadowJar --no-daemon` 均通过；`.idea/.name` 的用户改动保持未覆盖。
- 编译/测试：`./gradlew test shadowJar --no-daemon` 通过；构建产物 class major version 为 65（Java 21）；`git diff --check` 通过。
- 打包：`build/libs/mewcode.jar` 生成成功，`shadowJar` 通过。
- Anthropic：`AnthropicClientTest` 与 `AgentProtocolIntegrationTest` 的 Anthropic Messages SSE 两轮 Loop、工具 JSON 增量、message usage 和 thinking 字段测试通过。
- OpenAI：`OpenAiClientTest` 与 `AgentProtocolIntegrationTest` 的 Chat Completions SSE 两轮 Loop、tool-call delta、`include_usage` 和 base URL 测试通过。
- DeepSeek OpenAI 兼容：以 OpenAI adapter + DeepSeek 风格 base URL/reasoning_content fixture 完成两轮工具 Loop，用量累计和后续 reasoning 回传测试通过。
- DeepSeek Anthropic 兼容：以 Anthropic adapter + Anthropic-compatible base URL fixture 完成两轮工具 Loop，用量累计和收尾事件测试通过。
- Agent Loop：`AgentLoopTest`、`AgentTurnCoordinatorTest`、`TurnStreamCollectorTest` 覆盖多轮、无工具、最大轮次、未知工具、事件顺序、历史配对和非流式工具结果。
- 取消：`AgentRunTest`、`AgentLoopTest`、`ToolExecutorTest`、`MewCodeModelTest` 覆盖 provider close、工具 token/Future 取消、历史合法性和继续对话；tmux 复核了流式态 Ctrl+C/既有 Esc 取消。
- Plan Mode：`AgentLoopTest`、`MewCodeModelTest` 覆盖 `/plan`、`/do` 本地切换、只读工具过滤和执行层二次防线。
- TUI：`MewCodeModelTest` 覆盖七种事件展示、轮次进度、Token 用量、错误和 Esc/Ctrl+C 路由；真实 tmux 输出看到“第 1 轮”“第 2 轮”和输入恢复。
- tmux E2E：临时项目 `/private/tmp/mewcode-agent-loop-e2e` + 本地 OpenAI-compatible SSE 服务；真实 MewCode 完成两轮 ReadFile Loop，Ctrl+C 取消 `sleep 60` 后回到空闲态并继续对话，空闲 Esc 不退出，空闲 Ctrl+C 退出。
- 已知限制：本章未实现权限系统、上下文压缩、交互式确认、回滚或后台任务；取消和 provider close 是 best-effort，已发生的副作用不回滚；provider 缺少 usage 时显示 unknown；工具结果不流式输出。
