# MewCode 结构化 System Prompt 与 System Reminder Plan

> 状态：阶段二已确认
>
> 本计划基于已确认的 [spec.md](./spec.md)。本章只实现提示词模块化与动态消息注入，不实现实际缓存控制、缓存命中统计、项目指令文件加载、自动记忆或真实 MCP 接入。

## 架构概览

本章采用“稳定提示构建 + 运行时提醒调度 + provider 请求封装”的三层结构，不改 Ch04 的 Agent Loop 控制流。

### 稳定提示构建层

负责生成会话级稳定内容：

```text
system:
  七个固定模块
  环境上下文
```

七个固定模块按 Spec 规定的顺序拼装。环境上下文单独保留为一个系统片段，至少包含项目根目录。

该层不接收轮次、当前用户消息或临时状态，因此在一次会话内可以复用。现有的单字符串系统提示入口保留为兼容入口，但新的请求路径使用结构化系统片段。

### 运行时提醒调度层

负责根据 Agent Loop 的模式、轮次和模式切换状态生成临时 System Reminder：

```text
messages:
  持久历史快照
  当前用户消息
  临时 System Reminder
```

Reminder 只追加到本轮 provider 请求的临时消息快照末尾，不写入 `ConversationManager`。调度规则为：

- 第 1 轮完整提醒；
- 第 5、9、13……轮完整提醒；
- 其他轮次精简提醒；
- 模式切换后的下一轮强制完整提醒。

该层不调用 provider，也不修改 Agent Loop 的工具执行、事件发布和历史提交逻辑。

### provider 无关请求层

新增一个不可变的 provider 无关请求对象，统一承载：

- 独立的 system 片段；
- 工具定义；
- 持久历史快照；
- 本轮临时 Reminder。

`LlmClient` 保留当前公开调用方式，并增加结构化请求入口。旧入口继续使用原有语义，结构化入口由 Anthropic 和 OpenAI 客户端分别序列化。

### provider 适配层

Anthropic 和 OpenAI 客户端只负责把结构化请求转换为各自 API 格式：

- system 片段进入各自的系统级字段；
- 工具定义继续进入 tools；
- Reminder 和对话历史进入 messages；
- 现有 SSE 解析、工具调用累积、用量提取和取消逻辑保持不变。

OpenAI 兼容的 DeepSeek 继续复用 OpenAI 适配路径。

### Agent Loop 集成层

`AgentTurnCoordinator` 在每轮发起模型请求前：

1. 根据当前模式生成工具定义；
2. 获取当前会话历史快照；
3. 询问运行时提醒调度层生成本轮 Reminder；
4. 构造临时 provider 请求；
5. 调用结构化请求入口。

Coordinator 不把 Reminder 写入会话历史，也不改变现有多轮编排、工具执行、取消和历史提交路径。

### 工具规则强化层

全局提示中的工具使用模块维护通用规则；工具注册转 API 描述时，在对应工具 description 中补充相同规则的局部强化。

工具的名称、参数 schema、执行逻辑、权限属性和注册顺序保持不变。

## 核心数据结构

### `PromptModule`

表示一个可独立装配的系统提示模块。

```java
public record PromptModule(
        String name,
        int priority,
        String content
) {}
```

- `name`：模块标识；
- `priority`：拼装优先级；
- `content`：模块正文；
- 空内容模块不参与输出；
- 相同优先级按注册顺序保持稳定。

固定模块优先级使用 10、20、30、40、50、60、70；预留的可选模块使用 80、90、100，但本章内容为空且不加载外部来源。

### `EnvironmentContext`

表示会话级环境信息。

```java
public record EnvironmentContext(
        Path projectRoot,
        Map<String, String> attributes
) {}
```

至少包含项目根目录，其他字段可扩展。环境上下文在会话创建时生成，后续轮次复用。本章不执行 git 命令、不采集当前日期等易变化信息。

### `SystemPromptBundle`

表示稳定的系统提示结果。

```java
public record SystemPromptBundle(
        List<PromptModule> modules,
        EnvironmentContext environment
) {
    public List<String> systemSegments();
    public String flattenedText();
}
```

- `systemSegments()` 返回固定模块文本和环境上下文两个独立 system 片段；
- `flattenedText()` 只用于兼容现有字符串式调用；
- 新请求路径使用 `systemSegments()`，不依赖扁平字符串。

### `PromptRequest`

表示一次 provider 请求的完整、不可变快照。

```java
public record PromptRequest(
        List<String> systemSegments,
        List<Map<String, Object>> tools,
        List<Message> history,
        Optional<Message> reminder
) {
    public String flattenedSystemPrompt();
}
```

`history` 是 `ConversationManager` 的不可变快照，已经包含当前用户消息，但不包含 Reminder。`reminder` 是本轮临时合成的 user 消息，只在 provider 序列化时注入。

### `ReminderContext`

表示生成 Reminder 所需的运行时状态。

```java
public record ReminderContext(
        AgentMode mode,
        int round,
        boolean forceFull
) {}
```

`forceFull` 在模式切换后的下一轮为 `true`。当 `round == 1` 或 `round % 4 == 1` 时也生成完整 Reminder。

### `SystemReminderFactory`

负责把补充内容转换为合成 user 消息。

```java
public final class SystemReminderFactory {
    public static Optional<Message> create(ReminderContext context);
    public static Message full(ReminderContext context);
    public static Message compact(ReminderContext context);
}
```

生成结果必须等价于：

```java
new Message(
        "user",
        List.of(new TextBlock(
                "<system-reminder>\n"
                        + content
                        + "\n</system-reminder>"))
)
```

该消息只存在于 `PromptRequest.reminder`，不进入会话历史。

### `PromptRequestFactory`

负责组合稳定提示、工具定义、历史快照和本轮 Reminder。

```java
public final class PromptRequestFactory {
    public PromptRequest create(
            AgentMode mode,
            int round,
            boolean forceFull,
            List<Message> history,
            List<Map<String, Object>> tools
    );
}
```

它不调用 provider、不执行工具，也不修改历史。

### `LlmClient` 结构化入口

在现有接口基础上增加：

```java
default CancellableLlmStream openStream(PromptRequest request);
```

现有以下入口继续保留并维持原有语义：

```java
openStream(List<Message> messages, List<Map<String, Object>> tools);
openStream(ConversationManager conversation,
           List<Map<String, Object>> tools);
openStream(ConversationManager conversation,
           List<Map<String, Object>> tools,
           String systemPrompt);
```

结构化入口由真实 provider 实现；默认实现可将请求转换为临时兼容视图，以保证既有测试客户端和外部调用方兼容。临时视图不会回写真实 `ConversationManager`。

### `ToolPromptRules`

集中维护全局工具规则和工具描述强化逻辑。

```java
public final class ToolPromptRules {
    public static String globalInstructions();
    public static String descriptionFor(Tool tool);
}
```

`ToolRegistry` 生成 API 工具定义时调用 `descriptionFor`，不改变 `Tool` 接口、工具 schema 或执行逻辑。

## 模块设计

### `com.mewcode.prompt`

**职责：** 生成稳定系统提示、环境上下文和 System Reminder。

**组件：**

- `PromptModule`：模块名称、优先级和正文；
- `EnvironmentContext`：项目根目录等会话级环境信息；
- `SystemPromptBundle`：固定模块和环境片段，并提供结构化输出及旧版扁平文本；
- `PromptBuilder`：按优先级装配七个固定模块，保留现有字符串入口；
- `SystemReminderFactory`：生成完整或精简的 XML Reminder 消息。

**约束：**

- 不读取 `MEWCODE.md`、记忆或 MCP；
- 不执行工具、不调用 provider；
- 不把 Reminder 写入 `ConversationManager`；
- 固定模块内容使用常量或局部模块定义，避免每轮重新生成。

### `com.mewcode.agent`

**职责：** 在 Agent Loop 每轮准备请求上下文。

**改动：**

- 保留现有轮次计数和 Agent Loop 控制流；
- 每轮从 `ConversationManager` 获取不可变历史快照；
- 根据当前模式和轮次决定完整或精简 Reminder；
- 通过 `PromptRequestFactory` 将稳定提示、历史、工具定义和临时 Reminder 组装为结构化请求；
- 继续使用现有工具过滤、工具执行、事件发布、取消和历史提交逻辑；
- 不把 Reminder 作为普通用户消息写回会话。

`PromptRequestFactory` 放在 `agent` 包，由 Agent 层协调 prompt 内容和 llm 请求对象，避免 prompt 包反向依赖 provider 请求类型。

现有基于 `Function<AgentMode, String>` 的构造方式保留兼容适配，新的真实请求路径使用结构化请求工厂。

### `com.mewcode.llm`

**职责：** 将结构化请求序列化为 provider 请求。

**改动：**

- 在 `LlmClient` 增加结构化请求入口；
- 保留当前基于 `ConversationManager`、工具列表和字符串 system prompt 的入口；
- `AnthropicClient` 将 Reminder 文本块并入最后一个 user 消息；
- `OpenAiClient` 将 Reminder 作为尾部临时 user 消息追加；
- 两个客户端的流式解析、工具调用累积、Token 用量和取消逻辑保持不变；
- 不新增缓存控制字段或缓存用量字段。

### `com.mewcode.tool`

**职责：** 提供工具描述中的规则强化。

**改动：**

- 仅调整 `EditFileTool`、`BashTool` 等相关工具的 description；
- 强化“编辑前先读取”和“优先使用专用工具”等规则；
- 不修改 `Tool` 接口、schema、权限属性、执行逻辑和注册顺序；
- `ToolRegistry` 继续负责生成 provider 无关的工具定义。

### `com.mewcode.tui`

**职责：** 提供稳定提示构建所需的项目根目录和 provider 初始化上下文。

**改动：**

- 初始化时创建并传递会话级提示构建上下文；
- 不改变 TUI 的输入、流式展示、取消、轮次状态或最终答复行为；
- 不向用户显示 System Reminder 的内部 XML 内容。

## 模块交互

### 会话初始化

```text
MewCodeModel
  → PromptBuilder 构造七个固定模块
  → EnvironmentContext 保存项目根目录等稳定环境信息
  → SystemPromptBundle 在会话内复用
  → 初始化 LlmClient、ToolRegistry、AgentTurnCoordinator
```

初始化阶段不生成 System Reminder，因为 Reminder 只属于具体模型请求轮次。

### 每轮 Agent Loop

```text
AgentTurnCoordinator
  │
  ├─ 计算当前 round 和 AgentMode
  ├─ ToolRegistry 生成本轮工具定义
  ├─ ConversationManager.getMessages()
  │    └─ 获得不可变历史快照
  ├─ SystemReminderFactory 生成完整/精简 Reminder
  ├─ PromptRequestFactory 组装 PromptRequest
  └─ LlmClient.openStream(PromptRequest)
          │
          ├─ AnthropicClient
          │    ├─ system = stable + environment
          │    ├─ messages = history
          │    └─ 将 Reminder 文本块追加到最后一个 user 消息
          │
          └─ OpenAiClient
               ├─ system = stable + environment
               ├─ messages = history
               └─ 追加临时 Reminder user 消息
```

`PromptRequest` 中的 `history` 是请求前的持久历史快照，`reminder` 独立保存。provider 只在序列化请求时把 Reminder 放入消息通道。

### 工具轮次完成后

```text
provider stream
  → StreamEvent
  → TurnStreamCollector
  → ToolExecutor
  → ToolResultAssembler
  → ConversationManager.addToolTurn(...)
  → 下一轮重新获取历史快照
```

Reminder 不会进入 `addUserMessage`、`addToolTurn` 或其他历史写入路径，因此下一轮不会重复携带上一轮 Reminder。

### 模式切换

```text
用户输入 /plan 或 /do
  → TUI 只切换 AgentMode
  → 不调用模型
  → 不写入 ConversationManager
  → 下一条普通消息启动新 Agent Loop
  → 新 Loop 第 1 轮注入完整 Reminder
```

### Ch04 行为保持

结构化请求只替换“每轮调用 provider 前的请求准备方式”，不改变以下路径：

```text
LLM 流
→ 流式文本/工具调用收集
→ 工具分批执行
→ 结果按原始顺序回灌
→ 取消和错误收口
→ AgentEvent 发布
→ TUI 展示
```

现有旧版 `LlmClient` 调用入口通过兼容适配保留，测试客户端无需改变既有行为。

## Spec 覆盖关系

| Spec 项 | 设计归属 |
|---|---|
| F1、F2、F8 | `PromptModule`、`EnvironmentContext`、`SystemPromptBundle` |
| F3、F5、F7 | `PromptRequest` 与两个 provider 的结构化序列化 |
| F4 | `ToolPromptRules`、全局工具模块和工具 description |
| F6 | `ReminderContext`、`SystemReminderFactory`、Agent 每轮组装 |
| F9 | 模块职责边界和不加载外部来源的约束 |
| N1、N4、N5、N6 | 不可变 record、会话级稳定 bundle、临时 Reminder |
| N2、N3 | provider-specific 请求适配和历史快照隔离 |
| N7、N9 | 保留 Ch04 Agent Loop、事件流和 TUI 路径 |
| N10、N11 | Gradle Spotless 和模块局部扩展设计 |

## 文件组织

```text
src/main/java/com/mewcode/
├── prompt/
│   ├── PromptBuilder.java
│   │   — 保留旧字符串入口，新增稳定模块和 SystemPromptBundle 构建
│   ├── PromptModule.java
│   │   — 模块名称、优先级和正文
│   ├── EnvironmentContext.java
│   │   — 项目根目录及会话级环境信息
│   ├── SystemPromptBundle.java
│   │   — 稳定 system 片段和扁平兼容文本
│   ├── ReminderContext.java
│   │   — Reminder 生成所需的模式、轮次和完整标记
│   └── SystemReminderFactory.java
│       — 完整/精简 Reminder 生成
│
├── llm/
│   ├── PromptRequest.java
│   │   — provider 无关的结构化请求
│   ├── LlmClient.java
│   │   — 增加结构化请求入口，保留旧入口
│   ├── AnthropicClient.java
│   │   — system 片段和末尾 user 内容块序列化
│   └── OpenAiClient.java
│       — system 内容和尾部 Reminder user 消息序列化
│
├── agent/
│   ├── AgentTurnCoordinator.java
│   │   — 每轮生成 PromptRequest，保持 Ch04 Loop 控制流
│   └── PromptRequestFactory.java
│       — 组合历史、工具定义、system 片段和临时 Reminder
│
├── tool/
│   ├── ToolPromptRules.java
│   │   — 全局规则和工具 description 强化
│   └── impl/
│       ├── EditFileTool.java
│       │   — 强化编辑前读取规则的 description
│       └── BashTool.java
│           — 强化优先使用专用工具规则的 description
│
├── tui/
│   └── MewCodeModel.java
│       — 初始化并传递会话级提示构建上下文，不改变 UI 语义
│
└── build.gradle.kts
    — 增加 Gradle Spotless 和 Google Java Format 配置
```

测试文件：

```text
src/test/java/com/mewcode/
├── prompt/
│   ├── PromptBuilderTest.java
│   ├── SystemPromptBundleTest.java
│   ├── SystemReminderFactoryTest.java
│   └── PromptRequestFactoryTest.java
├── llm/
│   ├── AnthropicClientTest.java
│   └── OpenAiClientTest.java
├── agent/
│   └── AgentTurnCoordinatorTest.java
├── tool/
│   └── ToolPromptRulesTest.java
└── tui/
    └── MewCodeModelTest.java
```

测试覆盖重点：

- 模块顺序、优先级、空模块跳过和稳定拼装；
- 环境上下文独立注入；
- Reminder XML 格式、完整/精简周期和历史隔离；
- Anthropic/OpenAI 的不同 Reminder 序列化；
- Ch04 Agent Loop、取消、历史和 TUI 回归；
- 工具描述双重强化；
- Spotless、完整测试和构建。

## 技术决策

| 决策点 | 选择 | 理由 |
|---|---|---|
| 系统提示组织 | `PromptModule(name, priority, content)`，按优先级排序 | 满足模块化和局部扩展要求 |
| 固定模块顺序 | 身份 → 系统约束 → 任务模式 → 动作执行 → 工具使用 → 语气风格 → 文本输出 | 与已确认 Spec 一致 |
| 可选模块 | 只保留空模块扩展位置，不加载 `MEWCODE.md`、Skill 或记忆 | 预留能力但控制本章范围 |
| 环境上下文 | 由调用方提供项目根目录等稳定字段 | 避免读取 git 状态、当前日期等易变化信息 |
| system 内容 | 内部保持稳定提示和环境上下文两个独立片段 | 为后续缓存接入保留结构，不实现缓存参数 |
| Reminder 模型 | `PromptRequest` 中单独保存临时 Reminder，不混入历史 | 保证消息隔离和历史一致 |
| Anthropic Reminder | 追加到最后一个 user 消息的文本块；无 user 消息时新建 user 消息 | 避免连续 user 消息并保持协议兼容 |
| OpenAI Reminder | 追加一条临时 user 消息 | 兼容当前 OpenAI Chat Completions 序列化路径 |
| Reminder 周期 | 第 1、5、9……轮完整，其余轮次精简 | 实现每四轮重复一次完整 Reminder |
| 模式切换 | `/plan`、`/do` 仍由 TUI 本地处理；下一次模型请求使用完整 Reminder | 不增加模型请求，不写入历史 |
| provider 接口 | 增加结构化请求入口，保留旧入口和旧语义 | 满足兼容性和局部演进要求 |
| 流式边界 | 继续使用当前 `CancellableLlmStream` 和事件流 | 不改 Ch04 Agent Loop、取消和 TUI 路径 |
| 工具规则强化 | 修改相关工具的 description，不改变工具接口和执行逻辑 | 满足双重强化，同时降低回归风险 |
| 缓存能力 | 本章不加入 `cacheControl`、缓存用量字段或命中验证 | 缓存属于后续章节，当前只保留结构边界 |
| 构建格式 | Gradle Spotless + Google Java Format | 当前项目使用 Gradle，不引入 Maven |
| 环境副作用 | 不执行 git 命令、不读取外部配置文件 | 保持提示构建纯粹、稳定、可测试 |
```
