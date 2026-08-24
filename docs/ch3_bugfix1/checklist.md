# MewCode TUI 工具调用展示 Checklist

> 状态：已验收
>
> 本清单基于已确认的 [spec.md](/Users/bytedance/IdeaProjects/Mewcode-develop/docs/ch4/spec.md)、[plan.md](/Users/bytedance/IdeaProjects/Mewcode-develop/docs/ch4/plan.md) 和 [task.md](/Users/bytedance/IdeaProjects/Mewcode-develop/docs/ch4/task.md)。实现前不勾选任何项目；每项必须有测试输出或可复现的终端观察证据。

## 1. 使用规则

- 只有自动化测试通过或有可复现的 tmux/终端证据，才能勾选项目。
- 工具展示文本和模型回灌文本分开验证，不能以 TUI 显示正确代替协议验证。
- 摘要截断只允许发生在 UI 展示层；必须检查完整 `ToolResult.content` 仍用于模型回灌。
- 每次工具事件只能产生一次持久打印；重绘后的屏幕内容不能当作新的 scrollback 记录。
- 所有测试使用临时或只读文件，路径参数使用绝对路径。
- 发现失败项时记录实际输出，修复后重新验证，不直接勾选。

## 2. 文档与范围完整性

- [x] `spec.md` 状态为已确认，功能范围只包含 TUI 工具调用展示。
- [x] `plan.md` 状态为已确认，明确复用现有 `BlockingQueue`、JLine 和 `Command.PrintLine`。
- [x] `task.md` 状态为已确认，所有任务均有依赖和验证方式。
- [x] 未引入权限确认、MCP、ToolSearch、实时 Bash stdout 或连续 Agent Loop。
- [x] 未修改第 3 章已完成的工具协议和工具执行语义。

## 3. AC1：工具调用行格式

- [x] ReadFile 事件显示独立的 `● Read(绝对路径)` 行（验证：formatter 单测 + tmux 输出）。
- [x] WriteFile 事件显示独立的 `● Write(绝对路径)` 行（验证：formatter 单测）。
- [x] EditFile 事件显示独立的 `● Edit(绝对路径)` 行（验证：formatter 单测）。
- [x] Bash 事件显示命令摘要（验证：formatter 单测）。
- [x] Glob 事件显示 glob 模式（验证：formatter 单测）。
- [x] Grep 事件显示搜索表达式，并在有值时显示 include（验证：formatter 单测）。
- [x] 未知工具或缺少关键参数时显示安全 fallback 摘要，不倾倒完整 JSON（验证：formatter 单测）。
- [x] 工具调用行在 ToolStarted 到达后的下一次可见刷新中出现，不等待最终答复（验证：带延迟工具的 TUI 测试或 tmux 观察）。

## 4. AC2：参数摘要和终端安全

- [x] 参数中的换行不会让工具调用行变成多行（验证：formatter 单测）。
- [x] 参数中的 ANSI 转义和控制字符不会改变终端颜色、清屏或布局（验证：formatter 单测检查无转义序列）。
- [x] 超长路径、命令、正则和 include 会按显示列数截断并带省略标记（验证：formatter 单测）。
- [x] 中英文和全角参数均按终端显示宽度限制，而不是仅按 Java `String.length()` 限制（验证：formatter 单测）。
- [x] 参数格式化异常会降级为安全文本，不阻塞工具执行和回合结束（验证：异常输入测试）。
- [x] 截断后的参数只用于展示，不被重新作为工具输入执行（验证：Agent/TUI 集成测试）。

## 5. AC3：工具结果摘要

- [x] 成功 ToolCompleted 后紧随工具行显示 `⎿` 摘要（验证：MewCodeModel 单测 + tmux 输出）。
- [x] `isError=true` 的结果显示错误样式并保留失败原因首段（验证：错误结果单测）。
- [x] 空成功结果显示固定完成提示，不出现大块空白（验证：formatter 单测）。
- [x] 多行结果只显示有限摘要，并带截断或行数提示（验证：长结果单测）。
- [x] 摘要长度有集中定义的上限，单次工具结果不会撑满终端（验证：formatter 单测）。
- [x] 摘要格式化不会修改原始 `ToolResult.content`、`isError` 或 `metadata`（验证：不可变结果断言）。

## 6. AC4：流式实时展示和顺序

- [x] ToolStarted 的调用行先于同一调用的结果摘要（验证：事件顺序单测）。
- [x] 一条 assistant 消息包含多个工具调用时，调用行按原始 tool-use 顺序出现（验证：多工具事件测试）。
- [x] 多工具结果按对应 ID 配对，不因安全工具并发完成顺序而串线（验证：AgentTurnCoordinator + TUI 测试）。
- [x] 同一次轮询收到多个工具事件时，所有工具行/结果行都不会因 Completed 提前到达而丢失（验证：Command.Batch 顺序测试）。
- [x] 工具行和结果行出现期间，已有流式文本仍能继续显示（验证：混合 TextDelta/Tool 事件测试）。
- [x] 工具展示不会触发额外工具执行、权限确认或模型请求（验证：请求计数和执行计数断言）。

## 7. AC5：Scrollback 历史和重绘

- [x] 工具调用行通过 `Command.PrintLine` 进入终端 scrollback，而不是只存在于临时 view（验证：Program/tmux 观察）。
- [x] 结果摘要通过同一路径进入 scrollback，并位于对应调用行之后（验证：tmux 输出）。
- [x] 当前动态 view 清理后，工具行和结果摘要仍保留（验证：连续 PrintLine 测试）。
- [x] 定时轮询不会重复打印已经处理的 ToolStarted/ToolCompleted（验证：事件队列重复轮询测试）。
- [x] 窗口大小变化后工具行和结果行不重复、不重排（验证：tmux 触发 resize 后捕获输出）。
- [x] 继续发送下一条消息后，上一轮工具行仍可见且只出现一次（验证：tmux 连续对话）。
- [x] 工具展示记录没有被追加到 `ConversationManager` 或 provider 请求消息（验证：请求体/历史断言）。

## 8. AC6：纯文本回归

- [x] 模型不返回工具调用时，不出现 `● Read`、`● Bash` 或 `⎿` 等工具展示行（验证：MewCodeModelTest）。
- [x] 纯文本流式 assistant 输出仍按原顺序显示（验证：现有文本流测试）。
- [x] thinking 内容仍不显示给用户（验证：现有 thinking 测试）。
- [x] 最终文本和完成耗时提示仍正常显示（验证：现有 TUI 测试）。
- [x] 输入编辑、Alt+Enter、多 provider 选择和 Ctrl+C 行为不受影响（验证：现有 TUI 测试）。

## 9. AC7：协议和历史隔离

- [x] provider 请求中的工具定义、assistant tool-use 和 tool-result 协议结构保持第 3 章行为（验证：OpenAI/Anthropic 客户端测试）。
- [x] 工具结果回灌使用完整 `ToolResult.content`，不是 UI 摘要（验证：AgentTurnCoordinator 测试）。
- [x] `metadata` 没有进入 tool-result 请求内容（验证：请求体断言）。
- [x] UI ANSI 样式、`●`/`⎿` 展示文本和截断标记没有进入模型消息（验证：请求体断言）。
- [x] 第二次最终请求仍不携带工具定义，且最终阶段不执行新的工具调用（验证：既有 coordinator/E2E 请求日志）。

## 10. AC8：失败和异常隔离

- [x] 未知工具仍显示调用行和错误摘要（验证：错误事件测试）。
- [x] 参数校验失败仍显示调用行和可读错误原因（验证：错误 ToolResult 测试）。
- [x] 工具超时仍显示错误摘要，并恢复可输入状态（验证：超时测试或模拟事件）。
- [x] 摘要格式化异常不会导致 TUI 崩溃或永久锁定输入（验证：异常降级测试）。
- [x] 工具展示失败不会导致额外模型请求、第三次工具执行或未处理异常（验证：请求/执行计数断言）。

## 11. AC9：自动化测试与端到端

### 11.1 自动化测试

- [x] `ToolDisplayFormatterTest` 覆盖六个工具标签、关键参数、fallback、控制字符、全角宽度、截断、空结果和错误结果。
- [x] `MewCodeModelTest` 覆盖调用行、结果行、多工具顺序、错误恢复、纯文本回归和展示/历史隔离。
- [x] `AgentTurnCoordinatorTest` 覆盖 ToolStarted 参数快照、解析失败、ID 配对和一次回灌边界。
- [x] 运行 `./gradlew test --tests com.mewcode.tui.ToolDisplayFormatterTest` 通过。
- [x] 运行 `./gradlew test --tests com.mewcode.tui.MewCodeModelTest` 通过。
- [x] 运行 `./gradlew test --tests com.mewcode.agent.AgentTurnCoordinatorTest` 通过。
- [x] 运行 `./gradlew test` 全部通过。
- [x] 运行 `./gradlew shadowJar` 成功生成 `build/libs/mewcode.jar`。
- [x] 运行 `git diff --check` 无格式错误。

### 11.2 tmux 端到端场景

- [x] 使用独立临时项目根目录和绝对路径测试文件。
- [x] 在 tmux 中启动本地 OpenAI 兼容 SSE 模拟服务。
- [x] 在 tmux 中启动真实 MewCode 进程。
- [x] 发送“读取文件并总结”请求。
- [x] 捕获到 `● Read(绝对路径)` 工具调用行。
- [x] 捕获到其后的 `⎿` 结果摘要。
- [x] 捕获到最终 assistant 文本和完成状态。
- [x] 触发窗口重绘或继续对话后，工具行和结果行仍只出现一次。
- [x] 请求日志确认展示文本没有作为额外消息发送，第二次请求不含工具定义。
- [x] 测试会话和临时目录已清理，不影响真实项目配置。

## 12. 最终门禁

- [x] AC1～AC9 全部通过并有证据。
- [x] 所有失败测试已修复并重新运行，没有已知失败。
- [x] 未修改用户无关的代码、真实 API 配置或真实项目文件。
- [x] 没有引入权限确认、MCP、实时 Bash stdout 或连续 Agent Loop。
- [x] checklist 状态更新为“已验收”，并记录实际测试命令和 tmux 关键输出。

## 13. 验收记录

### 自动化测试

已执行并通过：

- `./gradlew test --tests com.mewcode.tui.ToolDisplayFormatterTest --tests com.mewcode.tui.MewCodeModelTest --tests com.mewcode.agent.AgentTurnCoordinatorTest`：`BUILD SUCCESSFUL`。
- `./gradlew test`：`BUILD SUCCESSFUL`。
- `./gradlew shadowJar`：`BUILD SUCCESSFUL`，生成 `build/libs/mewcode.jar`。
- `git diff --check`：无输出，退出码 0。

### tmux 端到端

已完成 tmux 验收：

- 临时根目录：`/private/tmp/mewcode-ch4-e2e.rQXJ6z`；测试文件使用绝对路径。
- 实际捕获：`● Read(/private/tmp/mewcode-ch4-e2e.rQXJ6z/src/main.py)`，随后是 `⎿ 1 def main(): … [truncated]`，再后是最终答复文本和 `Completed in 0.3s`。
- 调整窗口大小并继续发送第二条消息后，上一轮工具行/结果行仍保留且只出现一次。
- 请求日志：首次请求含 6 个工具定义；第二次最终请求不含 `tools`，消息中保留 assistant tool-use 与 user tool-result；第三次普通新对话请求恢复工具定义。
- tmux 会话和临时目录已清理。
