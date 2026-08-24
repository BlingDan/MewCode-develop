# MewCode TUI 工具调用展示 Plan

> 状态：已确认
>
> 本计划基于已确认的 [spec.md](/Users/bytedance/IdeaProjects/Mewcode-develop/docs/ch4/spec.md)。本阶段只设计 TUI 展示增量，不修改第 3 章的工具协议、工具执行器和 Agent Loop。

## 1. 架构概览

工具调用展示沿用现有的单向事件链：

```text
AgentTurnCoordinator
    ├─ ToolStarted(toolUseId, toolName, arguments)
    └─ ToolCompleted(toolUseId, toolName, ToolResult)
                │
                ▼
MewCodeModel.pollStream()
    ├─ ToolDisplayFormatter 生成有限长度的展示文本
    └─ Command.println 写入终端 scrollback
                │
                ▼
Program.executeCommand()
    ├─ 清除当前动态 view
    ├─ 打印工具行/结果行
    └─ 重绘输入区和流式文本
```

`AgentTurnCoordinator` 继续只负责业务事件，不感知颜色、终端宽度或 scrollback。`MewCodeModel` 负责把事件转换为 UI 命令；`ToolDisplayFormatter` 负责安全摘要和长度限制；`Program` 复用现有 `Command.PrintLine` 的持久打印机制。

一次轮询中如果收到多个工具事件，模型先按事件顺序收集要打印的命令，再用一个有序 `Command.Batch` 执行，确保调用行先于对应结果行，并且最终文本/错误行排在工具记录之后。

## 2. 设计目标与不变边界

### 2.1 本次新增

- 统一工具调用行格式和工具名映射；
- 从工具输入中提取关键参数并安全截断；
- 从 `ToolResult` 生成成功或错误结果摘要；
- 将开始/完成事件转换为持久 scrollback 打印命令；
- 增加展示层单元测试和 tmux 端到端验收。

### 2.2 明确不改

- `Tool`、`ToolResult`、`ToolRegistry`、`ToolExecutor` 的业务契约不变；
- provider 请求和响应协议不变；
- `ConversationManager` 不增加 TUI 展示消息；
- 一次工具结果回灌后不启动新的 Agent Loop；
- 不新增工具确认、取消、权限或实时 Bash stdout 流。

## 3. 核心数据结构

### 3.1 ToolStarted 展示事件

现有 `ToolStarted` 只有工具 ID 和名称，无法生成关键参数摘要。将其扩展为不可变事件：

```java
record ToolStarted(
        String toolUseId,
        String toolName,
        Map<String, Object> arguments) implements AgentEvent {}
```

职责：携带一次工具调用的最小展示信息。`arguments` 在事件创建时做不可变快照；它只供 TUI 展示，不会改变发送给模型的对话内容。

`AgentTurnCoordinator` 在完整工具调用和 JSON 解析失败调用两条路径上都创建该事件。解析失败时参数使用空 Map，工具 ID 和工具名仍保留，便于用户看到失败的调用。

### 3.2 ToolDisplayFormatter

新增一个 TUI 内部格式化组件，提供以下能力：

```java
final class ToolDisplayFormatter {
    static String invocation(String toolName,
                             Map<String, Object> arguments,
                             int maxColumns);

    static ResultSummary result(ToolResult toolResult, int maxColumns);

    record ResultSummary(String text, boolean isError) {}
}
```

职责边界：

- `invocation` 只生成 `● ToolName(argument)` 的纯文本，不负责颜色；
- `result` 只生成 `⎿ summary` 的纯文本和错误标记，不修改 `ToolResult`；
- 两个入口都清理控制字符、ANSI 转义和换行；
- 两个入口都按终端显示列数截断并追加统一省略标记；
- 任何格式化异常都返回安全的降级文本，不向上抛出异常。

### 3.3 关键参数选择

格式化组件使用固定、可测试的优先级：

| 工具 | 展示标签 | 参数来源 |
| --- | --- | --- |
| `ReadFile` | `Read` | `path` |
| `WriteFile` | `Write` | `path` |
| `EditFile` | `Edit` | `path` |
| `Bash` | `Bash` | `command` |
| `Glob` | `Glob` | `pattern` |
| `Grep` | `Grep` | `pattern`，可附 `include` |

参数缺失或工具未知时，按 key 排序生成有限长度的紧凑摘要；不会把完整参数 JSON 原样输出。

### 3.4 结果摘要模型

`ToolDisplayFormatter.result` 只读取 `ToolResult.content` 和 `ToolResult.isError`：

- 先规范化换行和控制字符；
- 选取首个有意义的非空片段；
- 多行或超长时追加行数/截断提示；
- 空成功结果使用固定完成提示；
- 错误结果保留首段诊断文本，并把 `isError` 传给调用方选择错误样式；
- `metadata` 不显示、不拼接到模型 content，也不修改原结果。

摘要最大列数、工具行最大列数和统一截断标记集中定义在格式化组件中，测试使用这些公开的行为边界而不是复制常量值。

## 4. 模块设计

### 4.1 `AgentEvent`

**职责：** 向 TUI 传递工具调用的展示所需参数。

**改动：** 扩展 `ToolStarted` 的 arguments 字段，并在 record 构造时做不可变快照。

**不改：** `ToolCompleted` 继续携带完整 `ToolResult`；不增加 UI 专用结果字段，不把摘要写入 AgentEvent。

**依赖：** 仅依赖 Java 集合和 `ToolResult`。

### 4.2 `AgentTurnCoordinator`

**职责：** 在正确的工具调用时机发出 `ToolStarted`，并在结果组装完成后发出 `ToolCompleted`。

**改动：** 两个现有 `ToolStarted` 创建点都传入调用参数；不改变工具执行顺序、结果配对和第二次请求边界。

**依赖：** `ToolCall`、`ToolExecutor`、`AgentEvent`。

### 4.3 `ToolDisplayFormatter`

**职责：** 统一处理工具名、关键参数、安全清理、摘要、截断和降级。

**输入：** 工具名称、不可变参数 Map、`ToolResult`、当前可用终端列数。

**输出：** 不含 ANSI 控制序列的纯展示文本和错误标记。

**关键决策：** 格式化器不访问文件系统、不解析工具结果的业务协议、不读取 metadata、不执行工具。

### 4.4 `MewCodeModel`

**职责：** 将 AgentEvent 转成有序 UI 命令，并维持流式文本、输入锁定和请求结束状态。

**事件处理：**

1. `TextDelta` 继续追加到现有 stream buffer；
2. `ToolStarted` 生成工具调用 `PrintLine`；
3. `ToolCompleted` 生成结果摘要 `PrintLine`；
4. `Completed`/`Error` 先执行当前轮已收集的工具打印命令，再执行现有最终文本或错误打印命令；
5. 没有工具事件时保持原有命令路径。

**有序命令组合：** `pollStream` 不在 while 循环中立即返回工具事件，而是先收集当前批次的 `Command.println`。轮询结束后按顺序追加下一次轮询 tick，或追加 `completeStream`/`failStream` 产生的终止命令。单个事件只转成一个打印命令。

**滚屏与动态视图：** 继续复用 `Command.println`。`Program` 每次持久打印前清除动态 view，打印工具行，再重绘当前流式文本和输入状态；因此工具行留在 scrollback，动态内容不会覆盖它。

### 4.5 `Styles`

**职责：** 提供工具调用和结果的视觉层级。

**改动：** 增加工具调用专用弱强调样式和成功结果弱化样式；错误结果复用现有错误样式。

**约束：** 颜色只在 TUI 输出层添加，传给 `ToolDisplayFormatter` 和模型的文本不含颜色控制。

### 4.6 `Program`

**职责：** 维持持久打印和动态 view 重绘的终端行为。

**改动：** 原则上不改生产逻辑；通过现有 `Command.PrintLine` 和有序 `Command.Batch` 完成需求。若测试暴露多条连续打印的重绘问题，只在 `Program` 增加最小回归修复，不引入新的历史存储。

## 5. 显示流程

### 5.1 单工具调用

```text
模型流式事件
  -> ToolStarted(call-1, ReadFile, {path: /absolute/project/a.txt})
  -> MewCodeModel 生成 PrintLine
  -> scrollback: ● Read(/absolute/project/a.txt)
  -> ToolCompleted(call-1, ToolResult)
  -> MewCodeModel 生成 PrintLine
  -> scrollback: ⎿ 3 lines
  -> Completed
  -> scrollback: ● 最终模型文本
```

### 5.2 多工具调用

调用行以 assistant tool-use 原始顺序进入队列。执行器可能并发执行安全工具，但 `AgentTurnCoordinator` 已按调用顺序发出结果事件，TUI 只按收到的统一事件顺序打印，不根据本地完成时间重排。

### 5.3 错误结果

工具调用行无论成功失败都打印。`ToolCompleted.result.isError()` 为 true 时，结果行使用错误样式，但仍只展示有限摘要；随后既有 Agent 流程继续处理模型最终回复或错误事件。

### 5.4 同一轮一次轮询收到多个事件

命令组合顺序固定为：

```text
已收到的工具调用行
  -> 已收到的工具结果行
  -> 最终 assistant/error 输出（若本轮结束）
  -> 下一次轮询 tick（若仍在流式）
```

这样避免 `Completed` 提前返回导致同一批次内尚未打印的工具行丢失。

## 6. 文件组织

```text
src/main/java/com/mewcode/agent/AgentEvent.java
    — ToolStarted 携带不可变 arguments
src/main/java/com/mewcode/agent/AgentTurnCoordinator.java
    — 发出带参数的 ToolStarted，其他回合逻辑不变
src/main/java/com/mewcode/tui/ToolDisplayFormatter.java
    — 工具行、结果摘要、安全清理和截断
src/main/java/com/mewcode/tui/MewCodeModel.java
    — AgentEvent 到 PrintLine/Batch 的转换
src/main/java/com/mewcode/tui/Styles.java
    — 工具行和结果行样式
src/main/java/com/mewcode/tui/tea/Program.java
    — 仅在回归失败时最小修改；复用现有 scrollback 路径

src/test/java/com/mewcode/tui/ToolDisplayFormatterTest.java
    — 参数映射、清理、截断、成功/错误摘要
src/test/java/com/mewcode/tui/MewCodeModelTest.java
    — 工具事件命令顺序、纯文本回归和输入恢复
src/test/java/com/mewcode/agent/AgentTurnCoordinatorTest.java
    — ToolStarted 参数传递和多工具顺序回归
```

## 7. 测试设计

### 7.1 格式化器单元测试

- 六个内置工具分别验证展示标签和关键参数；
- 路径、命令、正则和 include 中包含换行、控制字符、ANSI 转义时，输出仍为安全单行；
- 超长调用参数和超长结果均出现统一截断标记；
- 多行结果只显示首段摘要，不改变原始 `ToolResult.content`；
- 空结果、错误结果和未知工具均有稳定输出；
- 参数 Map 顺序变化不会改变输出顺序。

### 7.2 MewCodeModel 测试

- 收到 ToolStarted 后返回包含工具行的打印命令；
- 收到 ToolCompleted 后返回紧随其后的结果行；
- 一个 poll 同时收到多个工具事件时，打印命令顺序稳定；
- 工具事件之后的 Completed 仍打印最终 assistant 文本；
- 工具错误后恢复输入，下一次请求仍能提交；
- 纯文本响应不生成工具行和结果行；
- 展示文本不出现在传给 LlmClient 的 conversation 消息中。

### 7.3 AgentTurnCoordinator 测试

- ToolStarted 携带与 ToolCall 相同的 arguments；
- JSON 解析失败的 ToolStarted 使用空 Map，但仍保留 ID/名称；
- 多工具调用的 ToolStarted/ToolCompleted 事件保持 ID 和调用顺序；
- 已有一次回灌和禁止第三次请求测试继续通过。

### 7.4 tmux 端到端测试

使用本地 OpenAI 兼容模拟服务启动真实 MewCode：

1. 模型第一次响应包含文本和 ReadFile tool-use；
2. 观察终端先出现 `● Read(...)`；
3. 观察其后出现 `⎿ ...` 结果摘要；
4. 观察最终 assistant 文本；
5. 在下一次输入和窗口重绘后确认工具行、结果行没有重复；
6. 检查模拟服务请求体，确认展示文本没有作为额外消息发送。

## 8. 技术决策

| 决策点 | 选择 | 理由 |
| --- | --- | --- |
| 工具调用参数来源 | 扩展 `ToolStarted` 携带不可变 arguments | TUI 不应从 conversation history 反查，事件本身包含完整展示上下文 |
| 展示持久化 | 复用 `Command.println`/`Command.Batch` | 现有 `Program` 已验证打印后留在 scrollback，避免新增历史存储和重绘分支 |
| 摘要位置 | 新增 TUI 格式化组件 | 统一参数、控制字符、宽度和结果摘要规则，易于独立测试 |
| 结果内容 | 只读 `content`/`isError`，不读 metadata | UI 截断不应改变模型看到的完整结果，也避免元信息泄漏到展示文本 |
| 颜色位置 | `MewCodeModel`/`Styles` 添加 | Formatter 保持纯文本，协议层和测试不携带 ANSI |
| 多事件处理 | 轮询内先收集、再有序 Batch 执行 | 防止同批次遇到 Completed 时丢失尚未输出的工具行 |
| 截断单位 | 终端显示列数 | 兼容中英文、全角字符和实际窗口宽度，避免视觉溢出 |

## 9. 参考方案的适配与取舍

本计划参考了用户提供的工具系统 Plan，但只吸收与当前仓库和本功能直接相关的设计：

### 9.1 采纳的思路

- `ToolStarted` 携带调用参数，TUI 不通过反查 conversation history 获取展示信息；
- Agent 事件只表达统一业务事件，颜色、摘要和终端布局由 TUI 展示层处理；
- 工具开始和工具结束分别产生可观察事件，调用行先于结果摘要；
- UI 摘要与模型回灌内容严格分离，截断只发生在终端展示侧；
- 多工具结果按调用 ID 和原始顺序稳定呈现，不按并发完成时间重排。

### 9.2 不直接采用的部分

- 不把当前 `BlockingQueue<AgentEvent>` 重构为 `Flow.Publisher`：本仓库已有 AgentTurnCoordinator、TUI 轮询和测试体系，新增展示不需要替换跨模块事件模型；
- 不引入参考方案中的 `Role.TOOL`、平铺 Message 或新的 provider 抽象：第 3 章已使用 provider 无关 content block，并已通过 Anthropic/OpenAI/DeepSeek 兼容测试；
- 不引入 Maven、Lanterna 或另一套 `View/Scrollback Panel`：当前项目使用 Gradle、JLine 和 `Command.PrintLine`，现有终端运行时已经提供持久 scrollback 能力；
- 不让最终请求继续携带工具定义：已确认的本项目行为是结果回灌后第二次请求不传工具，模型若仍返回 tool-use 也不执行、不发起第三次请求；
- 不增加独立的 `START/END` 枚举包装层：现有 `ToolStarted`/`ToolCompleted` 已表达同样的生命周期，扩展开始事件参数即可。

参考方案提到的“当前工具执行指示器”可以作为后续增强，但不纳入本次已确认 spec；本次以持久的 `● 调用行` 和 `⎿ 结果摘要` 为准。

## 10. 计划完成定义

`plan.md` 阶段完成需要满足：

- spec 的 F1～F6、N1～N8 和 AC1～AC9 均有对应模块或测试归属；
- 工具行、结果摘要和 scrollback 的调用链可独立实现；
- 只扩展 UI 事件所需的 ToolStarted 参数，不引入模型协议变更；
- 任务清单会覆盖生产代码、单元测试、完整构建和 tmux 端到端验证；
- 用户确认本文件后，才生成 `task.md`。
