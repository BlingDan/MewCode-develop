# MewCode 工具调用系统实施计划

> 状态：已确认
>
> 本计划基于已确认的 [spec.md](/Users/bytedance/IdeaProjects/Mewcode-develop/docs/ch3/spec.md)。本阶段只实现一次工具结果回灌，不实现连续 Agent Loop 和权限确认。

## 1. 实施目标

在现有纯文本流式对话的基础上，增加一条完整且可测试的 Function Calling 链路：

```text
用户输入
  -> 构造带工具定义的模型请求
  -> 接收文本和多个 tool_use 流事件
  -> 本地校验、调度并执行工具
  -> 写入一条包含全部 tool_result 的 user 消息
  -> 再请求模型一次
  -> 输出最终文本
```

本次改造要保持工具实现、对话模型和 provider SDK 解耦，使后续增加权限系统、连续 Agent Loop 或新工具时不需要重写协议适配层。

## 2. 当前代码基础与约束

- 项目使用 Java 21 和 Gradle Kotlin DSL。
- 当前已有 `ConversationManager`、`Message`、`LlmClient`、`StreamEvent`、`OpenAiClient`、`AnthropicClient` 和 `MewCodeModel`，它们目前只处理纯文本流。
- 当前 provider SDK 版本保持不变：Anthropic Java `2.34.0`、OpenAI Java `4.37.0`。
- 在 `build.gradle.kts` 中显式声明 Jackson Databind `2.18.2`，用于工具参数 JSON 拼接后的解析；不引用任一 provider SDK 的私有 JSON 工具类。
- 保留现有纯文本测试和 TUI 行为；工具调用相关逻辑通过新增的协调层接入。
- 本章不加入用户确认、权限审批、命令白名单、shell 沙箱、MCP、ToolSearch 或延迟工具发现。

## 3. 目标模块边界

### 3.1 `com.mewcode.tool`

负责工具领域模型、注册、输入校验、执行调度、超时和文件状态缓存。该模块不依赖 TUI 和 provider SDK。

### 3.2 `com.mewcode.tool.impl`

实现六个内置工具：`ReadFileTool`、`WriteFileTool`、`EditFileTool`、`BashTool`、`GlobTool`、`GrepTool`。

### 3.3 `com.mewcode.tool.support`

承载六个工具共享的低层能力：项目根目录和绝对路径校验、文本/二进制检测、命令执行、搜索目录过滤和输出限制。

### 3.4 `com.mewcode.conversation`

使用 provider 无关的内容块表达文本、tool-use 和 tool-result，并维护带工具调用的对话历史。

### 3.5 `com.mewcode.llm`

负责把领域消息和工具定义转换为 Anthropic、OpenAI 或 DeepSeek 兼容 API 请求，并把不同 provider 的流式响应转换为统一 `StreamEvent`。

### 3.6 `com.mewcode.agent`

负责一次工具调用回合的编排：初次模型请求、工具执行、结果回灌和一次最终模型请求。它不执行用户确认，也不进入下一轮工具链。

### 3.7 `com.mewcode.tui`

只负责输入、文本输出、工具执行状态展示和生命周期，不直接查找或执行工具。

## 4. 核心数据契约

### 4.1 Tool 接口

新增 `com.mewcode.tool.Tool`，每个方法职责固定如下：

```java
public interface Tool {
    String name();
    String description();
    ToolCategory category();
    Map<String, Object> inputSchema();
    ToolResult execute(ToolExecutionContext context,
                       Map<String, Object> input);
    boolean isReadOnly();
    boolean isDestructive();
    boolean isConcurrencySafe(Map<String, Object> input);
    String validateInput(Map<String, Object> input);
}
```

`validateInput` 返回 `null` 表示通过，否则返回面向模型的调整提示。默认并发安全策略为 `false`，具体工具显式返回自己的策略。本章不加入 `shouldDefer`，也不实现延迟工具发现。

### 4.2 ToolResult

使用不可变 record：

```java
public record ToolResult(
        String content,
        boolean isError,
        Map<String, Object> metadata) {}
```

提供 `success(content)` 和 `error(content)` 工厂方法，并对 `metadata` 做不可变快照。`content` 会发送给模型，`metadata` 只保留给 UI、日志和未来权限系统。

### 4.3 工具分类和协议

- `ToolCategory`：`FILE`、`SEARCH`、`SHELL`。
- `ToolApiProtocol`：`ANTHROPIC`、`OPENAI`；DeepSeek 使用 `OPENAI` 格式。
- `ToolExecutionContext`：项目根目录、调用超时和 `FileStateCache`。
- `ToolInvocationResult`：保存 `toolUseId` 和对应的 `ToolResult`，供批量执行结果保持 ID 配对。

### 4.4 对话内容块

使用 sealed interface 和 record 表达：

- `TextBlock(String text)`
- `ToolUseBlock(String toolUseId, String toolName, Map<String, Object> arguments)`
- `ToolResultBlock(String toolUseId, String content, boolean isError)`

`Message` 改为 `role + List<ContentBlock>`。assistant 消息可以混合文本和多个 `ToolUseBlock`；所有结果组成一条 user 消息，包含多个 `ToolResultBlock`。

## 5. 注册中心和 API 工具定义

### 5.1 ToolRegistry

`ToolRegistry` 使用 `ConcurrentHashMap<String, Tool>`：

- `register(tool)` 登记工具，同名注册覆盖旧实例；
- `get(name)` 按名称查找；
- `getAll()` 返回稳定顺序的工具快照；
- `toAPIFormate(protocol)` 遍历当前六个工具，生成 provider 所需的工具定义。

默认注册顺序固定为：`ReadFile`、`WriteFile`、`EditFile`、`Bash`、`Glob`、`Grep`。

`toAPIFormate` 的外部结构为普通 Map，具体格式为：

- Anthropic：`name`、`description`、`input_schema`；
- OpenAI/DeepSeek：`type=function`，内嵌 `function.name`、`function.description`、`function.parameters`。

工具自身只返回 JSON Schema，不感知 provider。每次模型请求前重新从注册中心生成工具列表，确保注册状态与请求一致。

## 6. 工具执行器和调度策略

### 6.1 单次执行流程

`ToolExecutor` 按以下顺序处理单个调用：

1. 按名称查找工具；不存在时返回未知工具错误。
2. 调用 `validateInput`；失败时不执行工具，返回参数调整提示。
3. 创建带项目根目录、超时和文件状态缓存的执行上下文。
4. 在有界执行器中运行工具；捕获异常并转换为 `ToolResult(isError=true)`。
5. 超时后取消任务；对 Bash 确保子进程被强制终止。
6. 为结果补充本地 metadata，但只把 `content` 和 `isError` 转成 tool-result。

### 6.2 批量执行流程

批量调用按原始 tool-use 顺序建立结果槽位：

- 连续的 `isConcurrencySafe(input)=true` 调用组成一个并发批次；
- `false` 调用作为串行屏障，前后批次不交叉；
- 同一批次使用 Java 21 虚拟线程或等价的有界任务执行器；
- 某个调用失败不取消其他调用；
- 所有任务结束后按原始位置生成 `ToolInvocationResult` 列表。

这样既允许 ReadFile、Glob、Grep 同时运行，也避免写入、编辑和 Bash 与其他调用发生未定义的竞态。

## 7. 六个内置工具实现顺序

### 7.1 共享支持类

先实现以下共享能力，再实现具体工具：

- `PathGuard`：要求输入路径为绝对路径，规范化后验证位于项目根目录内；对已有路径检查真实路径，避免符号链接逃逸；Bash 不使用该限制，只固定工作目录。
- `TextFileSupport`：读取前检查前 512 字节的 NUL；区分文本、二进制、不存在和权限错误；统一生成面向模型的错误内容。
- `CommandRunner`：`ProcessBuilder.redirectErrorStream(true)` 合并输出，负责超时、强杀和输出截断。
- `SearchSupport`：维护排除目录集合、搜索根目录、相对路径输出、修改时间排序和 200 条上限。

### 7.2 ReadFileTool

- 参数：绝对 `path`、1-based `offset`、`limit`；默认从第 1 行读取最多 2000 行。
- 使用 `Files.newBufferedReader` 分段读取，不把整个大文件一次性放入结果。
- 每行输出为 `行号<TAB>内容`。
- 成功读取后调用 `FileStateCache.recordRead(path)`。
- 不存在、非绝对路径、越界、无权限和二进制分别返回不同错误类型。
- 元信息：只读、非破坏性、`file`，允许并发。

### 7.3 WriteFileTool

- 参数：绝对 `path`、文本 `content`。
- 新文件递归创建父目录；POSIX 系统目录设为 `0755`、文件设为 `0644`。
- 覆盖已有文件前检查文本属性和 `FileStateCache.canModify(path)`。
- 缺少读取记录或修改时间变化时保持文件不变，并提示模型重新读取。
- 成功后调用 `FileStateCache.update(path)`。
- 元信息：非只读、非破坏性、`file`，不可并发。

### 7.4 EditFileTool

- 参数：绝对 `path`、`old_string`、`new_string`。
- 先检查读取记录、当前修改时间和二进制状态。
- 使用大小写敏感的 `indexOf` 循环计算出现次数；必须恰好一次才替换。
- 零次或多次匹配时不写文件，错误内容明确说明“未找到”或“不唯一”。
- 成功后更新 `FileStateCache`。
- 元信息：非只读、非破坏性、`file`，不可并发。

### 7.5 BashTool

- 参数：命令文本，可选超时由上下文提供，默认 120 秒。
- 使用系统 shell，工作目录为启动时的项目根目录。
- 合并 stdout/stderr；超过集中定义的最大字符数时保留前部并追加截断标记。
- 输出使用 `<output>` 和 `<exit_code>` 标签包装。
- `grep`、`diff`、`find` 的退出码 1 视为正常，退出码 2 及以上为错误；其他命令所有非零退出码为错误。
- 超时调用 `destroyForcibly()` 并返回错误。
- 元信息：非只读、破坏性、`shell`，不可并发。

### 7.6 GlobTool

- 参数：项目根目录内的绝对 glob 模式。
- 使用 `FileVisitor` 递归遍历，支持 `*`、`?` 和 `**`。
- 跳过 `.git`、`node_modules`、`vendor`、`.idea`、`__pycache__` 等目录，不跟随符号链接。
- 按文件修改时间倒序，最多 200 条；每行返回相对于搜索根目录的文件路径。
- 元信息：只读、非破坏性、`search`，允许并发。

### 7.7 GrepTool

- 参数：正则表达式、绝对搜索根目录、可选文件名 `include` 过滤。
- 对每个候选文件先检查前 512 字节 NUL；二进制跳过并累加 `skippedBinaryCount`。
- 逐行正则匹配，输出 `相对路径:行号<TAB>匹配行内容`。
- 按文件修改时间倒序整理，最多 200 条；达到上限立即停止继续输出。
- 元信息：只读、非破坏性、`search`，允许并发。

## 8. 流式响应和 provider 适配

### 8.1 统一 LlmClient

将接口调整为接收领域消息和 API 工具定义：

```java
BlockingQueue<StreamEvent> stream(
        List<Message> messages,
        List<Map<String, Object>> apiTools);
```

继续使用现有阻塞队列和后台线程模型，减少 TUI 改动。provider 适配器自行把领域对象转换为 SDK 类型。

### 8.2 StreamEvent

扩展统一事件：

- `TextDelta`
- `ThinkingDelta`
- `ToolCallComplete(toolUseId, toolName, arguments)`
- `ToolCallParseError(toolUseId, toolName, message)`
- `StreamEnd`
- `Error`

### 8.3 ToolCallAccumulator

维护 `toolUseId -> {toolName, StringBuilder}`：

1. tool-use 开始事件记录 ID 和名称；
2. 每个 JSON 增量按 ID 追加；
3. 结束事件使用 `ObjectMapper` 解析完整 JSON 对象；
4. 解析成功生成 `ToolCallComplete`；
5. 解析失败生成 `ToolCallParseError`，不抛异常，不影响其他 ID。

解析失败的调用仍保留 assistant 侧的 tool-use ID，并由协调器生成同 ID 的错误 tool-result，使 provider 的消息配对完整。

### 8.4 Provider 映射

- Anthropic：文本和工具调用使用内容块；工具结果作为 user 消息中的 tool-result 内容块。
- OpenAI：assistant tool call 和 `tool` role 消息使用 `tool_call_id` 配对。
- DeepSeek：复用 OpenAI 兼容格式和适配路径。
- provider SDK 的异常统一转成 `StreamEvent.Error`，不把密钥或完整请求内容写入错误信息。

## 9. AgentTurnCoordinator 编排

`AgentTurnCoordinator` 负责以下固定状态转换：

1. 将用户文本加入历史。
2. 调用 `ToolRegistry.toAPIFormate(protocol)`，发起第一次模型请求。
3. 消费流事件，累积文本和所有 tool-use；文本和工具调用共同形成 assistant 消息。
4. 没有工具调用时直接结束本轮。
5. 有工具调用时调用 `ToolExecutor.executeBatch`。
6. 把所有结果按调用顺序组装成一条 user tool-result 消息并写入历史。
7. 发起第二次且唯一一次模型请求，只允许输出最终文本。
8. 若第二次响应仍包含 tool-use，不执行它们，返回明确的“本章不支持连续工具调用”错误事件。

TUI 通过 `AgentEvent` 接收文本增量、工具开始、工具完成、工具错误和最终结束事件；不直接调用任何 Tool。

## 10. 文件变更清单

### 10.1 新增生产代码

```text
src/main/java/com/mewcode/tool/Tool.java
src/main/java/com/mewcode/tool/ToolResult.java
src/main/java/com/mewcode/tool/ToolCategory.java
src/main/java/com/mewcode/tool/ToolApiProtocol.java
src/main/java/com/mewcode/tool/ToolExecutionContext.java
src/main/java/com/mewcode/tool/ToolInvocationResult.java
src/main/java/com/mewcode/tool/FileStateCache.java
src/main/java/com/mewcode/tool/ToolRegistry.java
src/main/java/com/mewcode/tool/ToolExecutor.java
src/main/java/com/mewcode/tool/support/PathGuard.java
src/main/java/com/mewcode/tool/support/TextFileSupport.java
src/main/java/com/mewcode/tool/support/CommandRunner.java
src/main/java/com/mewcode/tool/support/SearchSupport.java
src/main/java/com/mewcode/tool/impl/ReadFileTool.java
src/main/java/com/mewcode/tool/impl/WriteFileTool.java
src/main/java/com/mewcode/tool/impl/EditFileTool.java
src/main/java/com/mewcode/tool/impl/BashTool.java
src/main/java/com/mewcode/tool/impl/GlobTool.java
src/main/java/com/mewcode/tool/impl/GrepTool.java
src/main/java/com/mewcode/conversation/ContentBlock.java
src/main/java/com/mewcode/conversation/TextBlock.java
src/main/java/com/mewcode/conversation/ToolUseBlock.java
src/main/java/com/mewcode/conversation/ToolResultBlock.java
src/main/java/com/mewcode/agent/AgentEvent.java
src/main/java/com/mewcode/agent/AgentTurnCoordinator.java
src/main/java/com/mewcode/agent/ToolResultAssembler.java
src/main/java/com/mewcode/llm/ToolCallAccumulator.java
```

### 10.2 修改生产代码

```text
build.gradle.kts
src/main/java/com/mewcode/conversation/Message.java
src/main/java/com/mewcode/conversation/ConversationManager.java
src/main/java/com/mewcode/llm/LlmClient.java
src/main/java/com/mewcode/llm/StreamEvent.java
src/main/java/com/mewcode/llm/OpenAiClient.java
src/main/java/com/mewcode/llm/AnthropicClient.java
src/main/java/com/mewcode/prompt/PromptBuilder.java
src/main/java/com/mewcode/tui/MewCodeModel.java
src/main/java/com/mewcode/MewCode.java
```

### 10.3 测试代码和资源

新增工具、注册中心、缓存、消息、流式解析、provider 映射和 Agent 协调器测试；保留并适配现有纯文本测试。增加以下 SSE 固定样例：

```text
src/test/resources/sse/anthropic-tool-use.txt
src/test/resources/sse/openai-tool-use.txt
```

## 11. 测试实施顺序

1. 先运行现有测试，记录纯文本基线。
2. 为 `ToolResult`、`ToolRegistry`、`FileStateCache` 和 `ToolCallAccumulator` 编写无 I/O 或小范围单元测试。
3. 分别测试六个工具的正常路径、参数错误、权限错误、二进制文件和边界条件。
4. 测试批量执行的并发安全标记、串行屏障、失败隔离和原始顺序恢复。
5. 使用固定 SSE 样例测试 Anthropic、OpenAI、DeepSeek 格式转换、文本与 tool-use 混合、多调用和非法 JSON。
6. 测试 AgentTurnCoordinator 的无工具、单工具、多工具、工具失败、结果回灌和第二次请求仍返回 tool-use 的场景。
7. 运行完整 Gradle 测试和打包任务。
8. 按仓库要求使用 tmux 启动 MewCode，发送真实的“读取文件并总结”请求，观察 tool-use、tool-result 和最终回复完整闭环。

## 12. 验收映射

| 实施阶段 | 覆盖验收标准 |
| --- | --- |
| 核心契约、注册中心、API 定义 | AC1、AC2 |
| 内容块、流式解析、provider 适配 | AC3、AC4、AC14、AC15 |
| ReadFile、WriteFile、EditFile、FileStateCache | AC5、AC6、AC7、AC19 |
| Bash、Glob、Grep 和共享支持类 | AC8、AC9、AC10、AC11、AC16 |
| ToolExecutor 和批量调度 | AC12、AC13 |
| AgentTurnCoordinator 和 TUI 接入 | AC14、AC15、AC18 |
| 自动化测试、打包和 tmux | AC17、AC18 |

## 13. 风险与处理方案

- provider SDK 的内容块类型不同：所有 SDK 类型只留在 `llm` 包，通过统一 `StreamEvent` 和领域内容块隔离。
- 流式工具调用可能交错到达：累积器以调用 ID 为 key，不使用单一全局 JSON 缓冲区。
- 工具输出阻塞子进程：使用 `redirectErrorStream(true)` 并持续消费输出，同时限制保留字符数。
- 文件在模型读取后被外部修改：所有已有文件写入和编辑都经过 `FileStateCache` 的修改时间检查。
- 并发读与串行写产生竞态：批量执行采用安全批次和串行屏障，结果槽位独立保存。
- 二进制文件误读：ReadFile 和 Grep/WriteFile/EditFile 共用前 512 字节 NUL 检测。
- 现有纯文本行为回归：先保留旧测试，再通过适配器兼容旧的文本事件和 TUI 消费方式。

## 14. 完成定义

只有同时满足以下条件，计划才算实施完成：

- 六个工具可以通过统一接口注册、描述、校验和执行；
- 三种 provider 都能收到正确的工具定义，并能完成一次工具结果回灌；
- 多工具调用的 ID、消息角色、结果顺序和错误语义正确；
- 文件路径、二进制检测、先读再写、权限模式、超时和输出上限均有自动化测试；
- 现有测试、完整测试、打包和 tmux 端到端场景全部通过；
- 没有引入本章明确排除的权限系统或连续 Agent Loop。
