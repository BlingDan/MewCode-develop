# MewCode TUI 工具调用展示 Task

> 状态：已确认
>
> 本任务清单基于已确认的 [spec.md](/Users/bytedance/IdeaProjects/Mewcode-develop/docs/ch4/spec.md) 和 [plan.md](/Users/bytedance/IdeaProjects/Mewcode-develop/docs/ch4/plan.md)。所有任务完成后，仍需依据 `checklist.md` 做最终验收。

## 1. 执行规则

- 严格按依赖顺序执行；前置任务验证失败时，先修复再进入后续任务。
- 本功能只改 TUI 展示和展示所需的 AgentEvent 参数，不修改工具执行、provider 协议或 Agent Loop。
- 工具行和摘要的内容必须经过展示层清理；不得把 ANSI 或截断文本写入模型 conversation。
- 代码和测试注释使用中文；测试中的文件路径使用绝对路径。
- 每个任务完成后运行对应的窄范围验证；全部完成后运行完整 Gradle 测试、打包和 tmux 端到端测试。
- 保留工作树中第 3 章已有修改，不覆盖无关用户变更。

## 2. 任务总览

| 编号 | 任务 | 前置任务 | 主要产物 |
| --- | --- | --- | --- |
| T0 | 建立展示功能基线 | 无 | 基线测试结果 |
| T1 | 扩展 ToolStarted 展示参数 | T0 | 带 arguments 的事件和生产者 |
| T2 | 实现工具调用行格式化 | T1 | 工具名/关键参数/安全截断 |
| T3 | 实现工具结果摘要格式化 | T2 | 成功/错误摘要和结果截断 |
| T4 | 增加工具展示样式 | T3 | 工具行、成功摘要、错误摘要样式 |
| T5 | 接入流式事件和 scrollback | T1、T3、T4 | 有序 PrintLine/Batch 展示 |
| T6 | 完成 TUI 与 Agent 回归测试 | T5 | 事件顺序、隔离和纯文本测试 |
| T7 | 运行完整构建并生成验收清单 | T6 | `checklist.md`、构建证据 |
| T8 | tmux 端到端验收 | T7 | 工具行/摘要/最终答复证据 |

## 3. T0：建立展示功能基线

### T0.1 记录现有测试状态

**文件：** 无生产代码变更。

**步骤：**

1. 阅读当前 `MewCodeModel`、`AgentEvent`、`Program` 和 `Command.PrintLine` 的实现。
2. 运行现有完整测试，记录测试数量和结果。
3. 确认当前纯文本 TUI、一次工具回灌和第二次请求不带工具定义的测试均通过。

**验证：** 运行 `./gradlew test`，期望构建成功且无失败测试；若失败，先记录为基线问题并停止后续任务。

## 4. T1：扩展 ToolStarted 展示参数

### T1.1 修改事件数据

**文件：**

- `src/main/java/com/mewcode/agent/AgentEvent.java`

**依赖：** T0。

**步骤：**

1. 为 `ToolStarted` 增加 `Map<String, Object> arguments` 字段。
2. 在 record 构造阶段创建不可变参数快照；null 参数归一为空 Map。
3. 保持 `toolUseId`、`toolName` 和现有 `ToolCompleted` 结构不变。
4. 不增加 provider SDK 类型、颜色字段或摘要字段。

**验证：** 编译通过；构造事件后修改原始 Map 不影响事件中的 arguments，null 参数得到空 Map。

### T1.2 修改事件生产者

**文件：**

- `src/main/java/com/mewcode/agent/AgentTurnCoordinator.java`
- `src/test/java/com/mewcode/agent/AgentTurnCoordinatorTest.java`

**依赖：** T1.1。

**步骤：**

1. 完整 JSON 工具调用创建 `ToolStarted` 时传入 `ToolCall.arguments()`。
2. JSON 参数解析失败创建 `ToolStarted` 时传入空 Map。
3. 增加断言：事件中的 ID、名称和参数与原始调用一致。
4. 保留工具执行、结果回灌、第二次请求和不发起第三次请求的现有断言。

**验证：** 运行 `./gradlew test --tests com.mewcode.agent.AgentTurnCoordinatorTest`，期望所有测试通过，并确认第二次请求仍不携带工具定义。

## 5. T2：实现工具调用行格式化

### T2.1 新增 ToolDisplayFormatter 基础入口

**文件：**

- `src/main/java/com/mewcode/tui/ToolDisplayFormatter.java`
- `src/test/java/com/mewcode/tui/ToolDisplayFormatterTest.java`

**依赖：** T1。

**步骤：**

1. 新增纯文本格式化入口，接收工具名、参数 Map 和最大显示列数。
2. 实现六个内置工具的展示标签：Read、Write、Edit、Bash、Glob、Grep。
3. 按 plan 中的规则提取 `path`、`command`、`pattern` 和 `include` 等关键参数。
4. 未知工具或缺少关键参数时，按 key 稳定排序生成紧凑 fallback 摘要。
5. 输出以 `● ToolName(argument)` 开头，不添加 ANSI 样式。

**验证：** 测试六个工具、未知工具和缺少参数场景；期望标签和关键参数稳定，且不输出完整 JSON。

### T2.2 实现参数安全清理和长度限制

**文件：**

- `src/main/java/com/mewcode/tui/ToolDisplayFormatter.java`
- `src/test/java/com/mewcode/tui/ToolDisplayFormatterTest.java`

**依赖：** T2.1。

**步骤：**

1. 清理 ANSI 转义、控制字符和换行；工具行始终为单行。
2. 按终端显示列数计算长度，兼容中英文和全角字符。
3. 超过上限时只保留前部并追加统一省略标记。
4. 格式化异常返回安全的降级工具行，不抛出异常。

**验证：** 使用换行、制表符、ESC、超长路径和超长命令测试；期望输出无 ANSI 注入、长度受限且工具调用流程继续运行。

## 6. T3：实现工具结果摘要格式化

### T3.1 实现成功和错误结果摘要

**文件：**

- `src/main/java/com/mewcode/tui/ToolDisplayFormatter.java`
- `src/test/java/com/mewcode/tui/ToolDisplayFormatterTest.java`

**依赖：** T2。

**步骤：**

1. 新增结果格式化入口，接收 `ToolResult` 和最大显示列数。
2. 从 `content` 中选取首个有意义的非空片段，生成 `⎿` 摘要。
3. 空成功结果使用固定完成提示。
4. `isError=true` 时保留错误原因首段，并返回错误标记供 TUI 选择样式。
5. 多行和超长结果追加截断或行数提示；不修改原始 `ToolResult`。
6. 明确不读取或拼接 `metadata`。

**验证：** 测试空结果、单行结果、多行结果、超长结果和错误结果；期望摘要长度受限，原始 content 和 metadata 保持不变。

### T3.2 验证摘要与模型结果隔离

**文件：**

- `src/test/java/com/mewcode/tui/ToolDisplayFormatterTest.java`
- `src/test/java/com/mewcode/agent/AgentTurnCoordinatorTest.java`

**依赖：** T3.1。

**步骤：**

1. 构造带敏感 metadata 和长 content 的 ToolResult。
2. 验证 formatter 只生成 UI 摘要。
3. 验证 coordinator 回灌的 ToolResultBlock 仍使用完整 content，不包含摘要文本、颜色或 metadata。

**验证：** 运行 formatter 和 coordinator 相关测试，期望 UI 输出与 provider 请求内容完全分离。

## 7. T4：增加工具展示样式

### T4.1 增加工具行和结果样式

**文件：**

- `src/main/java/com/mewcode/tui/Styles.java`

**依赖：** T3。

**步骤：**

1. 增加工具调用行专用样式，保证与用户输入和 assistant 文本可区分。
2. 增加成功结果弱化样式；错误结果复用现有 ERROR 样式。
3. 颜色只在输出层添加，formatter 返回的纯文本保持无 ANSI。

**验证：** 编译通过；样式测试或 TUI 输出检查确认调用行、成功摘要和错误摘要视觉层级不同。

## 8. T5：接入流式事件和 scrollback

### T5.1 收集并按顺序执行打印命令

**文件：**

- `src/main/java/com/mewcode/tui/MewCodeModel.java`

**依赖：** T1、T3、T4。

**步骤：**

1. 在 `pollStream` 中新增当前轮询的有序 Command 列表。
2. `ToolStarted` 转成带工具样式的 `Command.println`。
3. `ToolCompleted` 转成带成功/错误样式的结果 `Command.println`。
4. 同一轮收到多个事件时先收集打印命令，不在 while 中提前返回。
5. 遇到 `Completed` 或 `Error` 时，先执行已收集的工具打印命令，再执行最终文本或错误命令。
6. 流仍未结束时，在工具打印命令之后追加下一次轮询 tick。
7. 保持 `Command.PrintLine` 作为 scrollback 唯一写入路径，不把展示内容追加到 conversation。

**验证：** 使用伪造事件队列检查 `PrintLine` 顺序：调用行 → 结果行 → 最终文本/错误；同一事件不能生成两条打印命令。

### T5.2 验证动态视图和 scrollback 重绘

**文件：**

- `src/main/java/com/mewcode/tui/MewCodeModel.java`
- `src/main/java/com/mewcode/tui/tea/Program.java`（仅在验证发现缺陷时修改）

**依赖：** T5.1。

**步骤：**

1. 确认 `Command.PrintLine` 执行前清理当前动态 view，打印后重绘流式文本和输入区。
2. 确认工具行和结果行不会被下一次 view 重绘覆盖。
3. 如果连续多个 PrintLine 暴露重绘问题，只做最小修复，不新增历史存储。
4. 确认窗口大小变化和后续输入不会重新打印已有工具事件。

**验证：** 运行 TUI 回归测试，并在 tmux 端到端测试中捕获 scrollback；期望工具行、结果行各出现一次且顺序不变。

## 9. T6：完成 TUI 与 Agent 回归测试

### T6.1 扩展 MewCodeModelTest

**文件：**

- `src/test/java/com/mewcode/tui/MewCodeModelTest.java`

**依赖：** T5。

**步骤：**

1. 增加包含单个 ReadFile tool-use、ToolCompleted 和最终文本的伪造客户端响应。
2. 提取 `UpdateResult.command()` 中的 PrintLine 文本，断言调用行先于结果行。
3. 增加多工具场景，断言调用 ID 对应结果顺序稳定。
4. 增加错误结果场景，断言错误摘要出现且输入可继续提交。
5. 保留纯文本、thinking 隐藏、部分响应错误和输入编辑测试。
6. 断言工具展示文本不出现在下一次 LlmClient 的 conversation messages 中。

**验证：** 运行 `./gradlew test --tests com.mewcode.tui.MewCodeModelTest`，期望新增和既有测试全部通过。

### T6.2 完成 AgentEvent 回归

**文件：**

- `src/test/java/com/mewcode/agent/AgentTurnCoordinatorTest.java`

**依赖：** T5。

**步骤：**

1. 断言 ToolStarted 携带完整参数快照。
2. 断言解析失败调用使用空参数但保留 ID/名称。
3. 断言多个工具调用的事件顺序、结果 ID 和现有一次回灌边界不变。

**验证：** 运行 `./gradlew test --tests com.mewcode.agent.AgentTurnCoordinatorTest`，期望无第三次模型请求，所有结果仍按原调用顺序配对。

## 10. T7：完整构建并生成验收清单

### T7.1 创建 checklist.md

**文件：**

- `/checklist.md`

**依赖：** T6。

**步骤：**

1. 将 spec 的 AC1～AC9 转为可观察的勾选项。
2. 补充工具格式化安全、动态 view 重绘、scrollback 不重复和协议隔离检查。
3. 加入完整测试、shadowJar 和 tmux 端到端命令。
4. 保持 checklist 初始状态为待验收，不提前勾选未验证项目。

**验证：** 检查每条 F/N/AC 都有对应条目，每个条目都有运行命令或可观察结果。

### T7.2 运行完整自动化验证

**文件：** 无生产代码变更。

**依赖：** T7.1。

**步骤：**

1. 运行完整 Gradle 测试。
2. 运行 shadowJar 生成可运行产物。
3. 执行 `git diff --check` 检查文档和代码空白错误。
4. 按 checklist 记录自动化测试证据，不将 tmux 项目提前标记为通过。

**验证：** `./gradlew test` 和 `./gradlew shadowJar` 均成功；没有失败测试或未解释的构建错误。

## 11. T8：tmux 端到端验收

### T8.1 启动本地模拟服务和 MewCode

**文件：** 临时目录中的模拟服务、测试配置和测试文件；不修改真实 `.mewcode/config.yaml`。

**依赖：** T7。

**步骤：**

1. 创建独立临时项目根目录和带绝对路径的文本测试文件。
2. 启动本地 OpenAI 兼容 SSE 模拟服务，第一次响应返回文本加 ReadFile tool-use，第二次响应返回最终文本。
3. 在 tmux 中启动打包后的 MewCode，工作目录设为临时项目根目录。
4. 输入“读取文件并总结”之类的真实请求。

**验证：** 应用正常进入对话状态，服务收到第一次带工具定义的请求和第二次不带工具定义的最终请求。

### T8.2 验证工具展示和 scrollback

**文件：** tmux 捕获输出和模拟服务请求日志。

**依赖：** T8.1。

**步骤：**

1. 观察 ReadFile 开始后出现 `● Read(绝对路径)`。
2. 观察工具完成后出现紧随其后的 `⎿` 结果摘要。
3. 观察最终 assistant 文本和完成状态。
4. 触发一次窗口重绘或继续发送下一条消息。
5. 检查工具行和结果行没有重复，且调用行在结果行之前。
6. 清理 tmux 会话和临时测试目录。

**验证：** tmux 输出无乱码、无未处理异常、无重复工具行；工具结果正确回灌模型，展示摘要未进入请求体。

## 12. 任务与验收映射

| 需求/验收 | 覆盖任务 |
| --- | --- |
| F1、AC1 工具调用行 | T1、T2、T4、T5、T6 |
| F2、AC2 参数安全 | T2 |
| F3、AC3 结果摘要 | T3、T4、T5、T6 |
| F4、AC4 实时顺序 | T1、T5、T6、T8 |
| F5、AC5 scrollback | T5、T7、T8 |
| F6、AC8 错误展示 | T3、T5、T6 |
| N1/N2、AC7 协议隔离 | T3、T5、T6、T8 |
| N3/N4 输出与终端安全 | T2、T3、T6、T8 |
| N5/N6 顺序和重绘 | T5、T6、T8 |
| N7、AC6 纯文本回归 | T6、T7 |
| N8 Provider 无关 | T1、T5、T7、T8 |
| AC9 自动化和端到端 | T6、T7、T8 |

## 13. 任务完成定义

`task.md` 阶段完成需要满足：

- T0～T8 的文件范围、步骤、依赖和验证方式均明确；
- 每个任务都是独立可验证的工作单元；
- 每条 spec 功能需求和验收标准都有任务覆盖；
- 任务不包含权限系统、工具确认、实时 Bash stdout、MCP 或连续 Agent Loop；
- 用户确认本文件后，才生成 `checklist.md` 并进入开发阶段。
