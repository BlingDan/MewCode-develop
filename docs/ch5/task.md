# MewCode 结构化 System Prompt 与 System Reminder Tasks

> 状态：阶段三草案，等待审批
>
> 本任务清单基于已确认的 [spec.md](./spec.md) 和 [plan.md](./plan.md)。四份文档全部获批前禁止编写实现代码。

## 文件清单

| 操作 | 文件 | 职责 |
|---|---|---|
| 修改 | `build.gradle.kts` | 接入 Gradle Spotless 和 Google Java Format |
| 新建 | `src/main/java/com/mewcode/prompt/PromptModule.java` | 提示词模块 record |
| 新建 | `src/main/java/com/mewcode/prompt/EnvironmentContext.java` | 会话级环境上下文 |
| 新建 | `src/main/java/com/mewcode/prompt/SystemPromptBundle.java` | 稳定 system 片段和扁平兼容文本 |
| 修改 | `src/main/java/com/mewcode/prompt/PromptBuilder.java` | 七个固定模块、可选空槽和兼容入口 |
| 新建 | `src/main/java/com/mewcode/prompt/ReminderContext.java` | Reminder 模式、轮次和完整标记 |
| 新建 | `src/main/java/com/mewcode/prompt/SystemReminderFactory.java` | 完整/精简 XML Reminder |
| 新建 | `src/main/java/com/mewcode/llm/PromptRequest.java` | provider 无关结构化请求 |
| 修改 | `src/main/java/com/mewcode/llm/LlmClient.java` | 结构化请求入口和兼容适配 |
| 修改 | `src/main/java/com/mewcode/llm/AnthropicClient.java` | Anthropic system/message 序列化 |
| 修改 | `src/main/java/com/mewcode/llm/OpenAiClient.java` | OpenAI system/message 序列化 |
| 新建 | `src/main/java/com/mewcode/agent/PromptRequestFactory.java` | Agent 层请求组装 |
| 修改 | `src/main/java/com/mewcode/agent/AgentTurnCoordinator.java` | 每轮使用结构化请求 |
| 新建 | `src/main/java/com/mewcode/tool/ToolPromptRules.java` | 全局规则和工具描述强化 |
| 修改 | `src/main/java/com/mewcode/tool/ToolRegistry.java` | 使用强化后的 description 生成 API 定义 |
| 修改 | `src/main/java/com/mewcode/tool/impl/EditFileTool.java` | 强化编辑前读取规则 |
| 修改 | `src/main/java/com/mewcode/tool/impl/BashTool.java` | 强化优先使用专用工具规则 |
| 修改 | `src/main/java/com/mewcode/tui/MewCodeModel.java` | 传递会话级提示构建上下文 |
| 修改 | `src/test/java/com/mewcode/prompt/PromptBuilderTest.java` | 固定模块和兼容入口回归 |
| 新建 | `src/test/java/com/mewcode/prompt/SystemPromptBundleTest.java` | system 片段和环境边界测试 |
| 新建 | `src/test/java/com/mewcode/prompt/SystemReminderFactoryTest.java` | Reminder XML 和轮次测试 |
| 新建 | `src/test/java/com/mewcode/agent/PromptRequestFactoryTest.java` | 请求快照和历史隔离测试 |
| 修改 | `src/test/java/com/mewcode/llm/AnthropicClientTest.java` | Anthropic 请求分层和 Reminder 测试 |
| 修改 | `src/test/java/com/mewcode/llm/OpenAiClientTest.java` | OpenAI 请求分层和 Reminder 测试 |
| 修改 | `src/test/java/com/mewcode/agent/AgentTurnCoordinatorTest.java` | 多轮请求和 Ch04 回归 |
| 新建 | `src/test/java/com/mewcode/tool/ToolPromptRulesTest.java` | 工具描述双重强化测试 |
| 修改 | `src/test/java/com/mewcode/tool/ToolRegistryTest.java` | API description 回归 |
| 修改 | `src/test/java/com/mewcode/tui/MewCodeModelTest.java` | TUI/模式切换回归 |

## T1：接入 Gradle Spotless

**文件：** `build.gradle.kts`

**依赖：** 无

**步骤：**

1. 添加与当前 Gradle/Java 21 兼容的 Spotless Gradle 插件。
2. 配置 Java 源码使用 Google Java Format。
3. 保持现有 Shadow、测试和 application 配置不变。

**验证：**

运行 `./gradlew tasks --all`，期望能看到 `spotlessApply` 和 `spotlessCheck`；运行 `./gradlew compileJava`，期望现有源码编译通过。

## T2：定义提示词基础数据结构

**文件：**

- `src/main/java/com/mewcode/prompt/PromptModule.java`
- `src/main/java/com/mewcode/prompt/EnvironmentContext.java`
- `src/main/java/com/mewcode/prompt/SystemPromptBundle.java`

**依赖：** T1

**步骤：**

1. 定义不可变的 `PromptModule`，保存名称、优先级和内容。
2. 定义 `EnvironmentContext`，至少保存规范化的项目根目录。
3. 定义 `SystemPromptBundle`，分别保存模块列表和环境上下文。
4. 实现稳定的 system 片段输出：固定模块文本一个片段，环境文本一个片段。
5. 实现旧调用方需要的扁平 system 文本输出。
6. 对集合做不可变快照，空内容模块不参与渲染。

**验证：**

运行 `./gradlew test --tests com.mewcode.prompt.SystemPromptBundleTest`，期望模块顺序、环境片段边界、空模块跳过和重复构造结果一致。

## T3：重构 PromptBuilder 的固定模块

**文件：**

- `src/main/java/com/mewcode/prompt/PromptBuilder.java`
- `src/test/java/com/mewcode/prompt/PromptBuilderTest.java`

**依赖：** T2

**步骤：**

1. 将现有通用提示拆成身份、系统约束、任务模式、动作执行、工具使用、语气风格、文本输出七个模块。
2. 按固定优先级装配模块，并为自定义指令、Skill、长期记忆保留空槽。
3. 在工具使用模块中加入优先使用专用工具、编辑前先读取、错误后调整参数等规则。
4. 把项目根目录放入独立环境上下文，不混入固定模块常量。
5. 保留现有 `buildSystemPrompt` 字符串入口，并使旧模式调用方继续得到合理的兼容文本。
6. 更新测试，验证七个模块顺序、环境字段和 Plan/Execute 兼容行为。

**验证：**

运行 `./gradlew test --tests com.mewcode.prompt.PromptBuilderTest`，期望固定模块顺序、项目根目录、工具规则和既有模式断言全部通过。

## T4：实现 Reminder 上下文和消息工厂

**文件：**

- `src/main/java/com/mewcode/prompt/ReminderContext.java`
- `src/main/java/com/mewcode/prompt/SystemReminderFactory.java`
- `src/test/java/com/mewcode/prompt/SystemReminderFactoryTest.java`

**依赖：** T2

**步骤：**

1. 定义 Reminder 上下文，保存当前模式、Agent Loop 轮次和是否强制完整提醒。
2. 生成 `<system-reminder>...</system-reminder>` XML 文本。
3. 生成完整 Reminder，包含当前模式、轮次和完整行为约束。
4. 生成精简 Reminder，至少包含当前模式和关键约束。
5. 将结果封装为 `role=user` 且 content 为单个 `TextBlock` 的 provider 无关消息。
6. 确认空内容、换行和特殊字符不会破坏开始/结束标签结构。

**验证：**

运行 `./gradlew test --tests com.mewcode.prompt.SystemReminderFactoryTest`，期望 XML 标签、文本块结构、完整/精简内容和边界字符测试通过。

## T5：定义结构化 PromptRequest 和 LlmClient 兼容入口

**文件：**

- `src/main/java/com/mewcode/llm/PromptRequest.java`
- `src/main/java/com/mewcode/llm/LlmClient.java`

**依赖：** T2、T4

**步骤：**

1. 定义不可变 `PromptRequest`，保存 system 片段、工具定义、历史快照和可选 Reminder。
2. 对列表和映射做请求级不可变快照，防止调用方在流式请求期间修改数据。
3. 增加结构化 `openStream(PromptRequest)` 入口。
4. 保留现有三个 `openStream` 入口及其语义。
5. 为旧实现提供临时兼容视图，兼容视图不得回写真实会话历史。

**验证：**

运行 `./gradlew compileJava` 和现有 `./gradlew test`，期望接口新增后既有 provider、Agent 和测试客户端仍能编译。

## T6：实现 Agent 层 PromptRequestFactory

**文件：**

- `src/main/java/com/mewcode/agent/PromptRequestFactory.java`
- `src/test/java/com/mewcode/agent/PromptRequestFactoryTest.java`

**依赖：** T3、T4、T5

**步骤：**

1. 让工厂持有会话级 `SystemPromptBundle`。
2. 接收当前模式、轮次、完整标记、历史快照和工具定义。
3. 通过 `SystemReminderFactory` 生成当前轮次的 Reminder。
4. 将历史和工具列表复制到 `PromptRequest`，不修改 `ConversationManager`。
5. 确认同一稳定 bundle 在多轮请求中复用，动态 Reminder 单独变化。

**验证：**

运行 `./gradlew test --tests com.mewcode.agent.PromptRequestFactoryTest`，期望第 1/5/9 轮完整、其余轮次精简，Reminder 不出现在历史快照中。

## T7：实现工具规则双重强化

**文件：**

- `src/main/java/com/mewcode/tool/ToolPromptRules.java`
- `src/main/java/com/mewcode/tool/ToolRegistry.java`
- `src/main/java/com/mewcode/tool/impl/EditFileTool.java`
- `src/main/java/com/mewcode/tool/impl/BashTool.java`
- `src/test/java/com/mewcode/tool/ToolPromptRulesTest.java`
- `src/test/java/com/mewcode/tool/ToolRegistryTest.java`

**依赖：** T3

**步骤：**

1. 集中定义全局工具使用规则和工具 description 的强化文本。
2. 修改 `EditFileTool` description，明确编辑前必须先读取并确认目标内容。
3. 修改 `BashTool` description，明确读取、查找和搜索优先使用专用工具。
4. 让 `ToolRegistry` 生成 API 定义时使用强化后的 description。
5. 保持工具名称、schema、注册顺序、权限属性和执行逻辑不变。

**验证：**

运行 `./gradlew test --tests com.mewcode.tool.ToolPromptRulesTest --tests com.mewcode.tool.ToolRegistryTest`，期望系统规则和对应工具描述均包含强化文本，既有 schema 断言不变。

## T8：接入 AgentTurnCoordinator 的结构化请求

**文件：**

- `src/main/java/com/mewcode/agent/AgentTurnCoordinator.java`
- `src/test/java/com/mewcode/agent/AgentTurnCoordinatorTest.java`

**依赖：** T5、T6、T7

**步骤：**

1. 为 Coordinator 注入或创建 `PromptRequestFactory`。
2. 保留现有构造器和字符串 system prompt provider 的兼容适配。
3. 在每轮生成工具 schema 后获取历史快照并创建 `PromptRequest`。
4. 调用结构化 `LlmClient.openStream`，移除每轮重建完整模式 system prompt 的主路径。
5. 保持工具过滤、轮次计数、流式收集、工具执行、结果回灌和结束条件不变。
6. 保证 Reminder 不经由任何 ConversationManager 写入方法落入历史。

**验证：**

运行 `./gradlew test --tests com.mewcode.agent.AgentTurnCoordinatorTest`，期望多轮工具调用、历史配对、取消和旧构造器测试全部通过，并能捕获每轮结构化请求。

## T9：实现 Anthropic 结构化请求序列化

**文件：**

- `src/main/java/com/mewcode/llm/AnthropicClient.java`
- `src/test/java/com/mewcode/llm/AnthropicClientTest.java`

**依赖：** T5、T8

**步骤：**

1. 实现结构化请求入口，保留现有字符串 system prompt 入口。
2. 将稳定 system 片段和环境上下文转换为 Anthropic system 内容块。
3. 按现有协议转换历史消息和工具定义。
4. 如果存在 Reminder，将其文本块追加到最后一个 user 消息的 provider 内容块；末尾没有 user 消息时新建临时 user 消息。
5. 只修改本次请求的序列化副本，不修改 `PromptRequest.history` 或 `ConversationManager`。
6. 保持现有 SSE、Thinking、工具调用、用量和取消处理不变；不加入缓存控制字段。

**验证：**

运行 `./gradlew test --tests com.mewcode.llm.AnthropicClientTest`，期望请求记录中 system、tools、messages 分层正确，Reminder 不进入历史，既有 Anthropic 流式测试通过。

## T10：实现 OpenAI 结构化请求序列化

**文件：**

- `src/main/java/com/mewcode/llm/OpenAiClient.java`
- `src/test/java/com/mewcode/llm/OpenAiClientTest.java`

**依赖：** T5、T8

**步骤：**

1. 实现结构化请求入口，保留现有字符串 system prompt 入口。
2. 将稳定 system 片段和环境上下文按兼容端点规则序列化为 system 内容。
3. 按现有协议转换历史消息和工具定义。
4. 如果存在 Reminder，追加一条临时 user 消息，不修改历史快照。
5. 保持现有 assistant tool call、tool result、reasoning content、SSE 和取消处理不变。
6. OpenAI 兼容端点继续复用该路径，不加入缓存统计字段。

**验证：**

运行 `./gradlew test --tests com.mewcode.llm.OpenAiClientTest`，期望 system、tools、messages 分层正确，Reminder 位于尾部且未污染历史，既有 OpenAI/兼容端点测试通过。

## T11：接入 MewCodeModel 会话上下文

**文件：**

- `src/main/java/com/mewcode/tui/MewCodeModel.java`
- `src/test/java/com/mewcode/tui/MewCodeModelTest.java`

**依赖：** T3、T6、T8

**步骤：**

1. provider 初始化时创建会话级稳定提示 bundle。
2. 将项目根目录和提示构建上下文传递给 Coordinator/RequestFactory。
3. 保留现有 provider factory 兼容方式，避免改变测试注入接口的语义。
4. 保持 `/plan`、`/do` 本地切换，不向模型发送命令文本。
5. 保持输入框、流式文本、工具展示、轮次状态、取消和最终答复行为不变。

**验证：**

运行 `./gradlew test --tests com.mewcode.tui.MewCodeModelTest`，期望模式切换不发起模型请求，普通请求仍能启动 Agent Loop，UI 状态和历史行为不变。

## T12：完成跨模块回归测试

**文件：**

- `src/test/java/com/mewcode/prompt/PromptBuilderTest.java`
- `src/test/java/com/mewcode/prompt/SystemPromptBundleTest.java`
- `src/test/java/com/mewcode/prompt/SystemReminderFactoryTest.java`
- `src/test/java/com/mewcode/agent/PromptRequestFactoryTest.java`
- `src/test/java/com/mewcode/agent/AgentTurnCoordinatorTest.java`
- `src/test/java/com/mewcode/tui/MewCodeModelTest.java`

**依赖：** T2–T11

**步骤：**

1. 补齐至少 9 轮 Reminder 周期和模式切换后的完整 Reminder 场景。
2. 增加静态 system 内容不随轮次变化的断言。
3. 增加 system、tools、messages 三通道边界断言。
4. 增加 Reminder 不进入历史、用户正文不被改写的断言。
5. 运行现有 Agent Loop、协议集成、工具执行和 TUI 测试，确认 Ch04 行为不退化。

**验证：**

运行 `./gradlew test`，期望全部单元测试和集成测试通过，且没有新增失败或未处理异常。

## T13：完成格式、编译和打包验证

**文件：** 所有本章修改的 Java 文件和 `build.gradle.kts`

**依赖：** T12

**步骤：**

1. 对本章修改的 Java 文件执行 Spotless 格式化。
2. 检查格式化不会改变非本章功能代码的语义。
3. 运行完整编译、测试和 Shadow 打包。
4. 检查构建输出，没有明显新增编译警告。

**验证：**

依次运行 `./gradlew spotlessCheck`、`./gradlew test`、`./gradlew shadowJar`，期望全部通过并生成可运行的 `build/libs/mewcode.jar`。

## T14：tmux 端到端验收

**文件：** `docs/ch5/checklist.md`、运行构建产物

**依赖：** T13

**步骤：**

1. 在 tmux 中启动构建后的 MewCode。
2. 发送一条会触发工具调用的真实请求，记录 provider 请求和终端输出。
3. 完成一次 `/plan` 或 `/do` 模式切换，再发送一条普通请求。
4. 观察多轮 Agent Loop 的工具调用、结果回灌、轮次进度和最终答复。
5. 确认请求日志中 Reminder 使用 XML 标签、分层进入 messages 且没有重复历史 Reminder。
6. 确认退出、取消和后续输入流程正常。

**验证：**

保存 tmux 输出和请求日志作为验收证据；期望 System Prompt、环境上下文、工具定义、Reminder、历史和最终答复均符合 `checklist.md`，且没有 UI 崩溃或额外模型请求。

## 执行顺序

```text
T1 → T2 → T3 → T4 → T5 → T6
                         ├→ T7
                         └→ T8 → T9 ─┐
                                  T10 ├→ T11 → T12 → T13 → T14
                         T7 ─────────┘
```

其中 T9 和 T10 可在 T8 完成后并行实现；T12 必须等待所有功能集成任务完成。

