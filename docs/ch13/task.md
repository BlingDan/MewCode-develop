# MewCode SubAgent 实施任务

> 状态：已确认
>
> 前置文档：[spec.md](./spec.md)、[plan.md](./plan.md)
>
> 约束：本文批准前不进入实现；实现时按任务顺序完成测试、代码和验证，不把预期结果提前标记为通过。

## 执行规则

- 每个非平凡行为先补最小失败测试，再实现使其通过。
- 复用现有 `AgentTurnCoordinator`、`ToolPolicy`、权限、Hook、Session 和 Skill seam，不创建第二套框架。
- 每完成一项先运行该项定向测试；阶段结束再运行相关测试集。
- 不修改或删除用户已有的无关变更。
- 不新增依赖；新增 Java 路径纳入 Spotless。
- 只有自动化、构建和 tmux 真实流程都取得证据后，才能宣布功能完成。

## T1：建立实现基线

**目标：** 在修改功能代码前确认当前分支的测试、格式和打包基线，记录既有失败，避免把历史问题归因于 SubAgent。

**文件：** 不修改功能文件；实际结果后续写入 `docs/ch13/verification.md`。

**步骤：**

1. 检查工作区状态，确认 `docs/ch13` 之外是否存在用户改动。
2. 运行现有格式检查、测试和 shadow JAR 构建。
3. 记录测试数量、失败项、构建产物和当前提交，不把失败静默忽略。

**验证：**

```bash
./gradlew spotlessCheck test shadowJar
git diff --check
```

**覆盖：** N13、N15、N16。

## T2：提取共享 Markdown frontmatter 读取器

**目标：** 复用现有 SnakeYAML 安全解析，消除 Skill 与 Agent 的重复语法处理，同时保持 Skill 外部行为不变。

**文件：**

- 新增 `src/main/java/com/mewcode/definition/MarkdownFrontmatter.java`
- 修改 `src/main/java/com/mewcode/skill/SkillParser.java`
- 修改或新增对应测试

**步骤：**

1. 先增加 frontmatter 分隔、CRLF、非对象 YAML、alias 限制、嵌套限制和空正文测试。
2. 实现只返回 YAML map 与正文的共享读取器。
3. 将 `SkillParser` 切换到共享读取器，保留 Skill 自己的字段白名单和业务绑定。
4. 运行全部 Skill 解析与 Catalog 回归测试。

**验证：** `./gradlew test --tests '*SkillParserTest' --tests '*SkillCatalogTest' --tests '*MarkdownFrontmatterTest'`

**覆盖：** F2、F16、AC2、AC35。

## T3：增加 SubAgent 全局配置

**目标：** 支持默认 20 秒自动转后台，并复用全局 Agent Loop 轮次上限作为角色默认值。

**文件：**

- 新增 `src/main/java/com/mewcode/config/SubAgentConfig.java`
- 修改 `src/main/java/com/mewcode/config/AgentConfig.java`
- 修改 `src/main/java/com/mewcode/config/ConfigLoader.java`
- 修改 `src/test/java/com/mewcode/config/ConfigLoaderTest.java`

**步骤：**

1. 增加默认值和 snake_case / kebab-case 绑定测试。
2. 增加零、负数和非法类型拒绝测试。
3. 实现 `agent.subagent.auto_background_ms`，默认 `20000`。
4. 确认旧配置文件不添加字段仍保持兼容。

**验证：** `./gradlew test --tests '*ConfigLoaderTest'`

**覆盖：** F2、F10、AC24、N13。

## T4：实现 SubAgentSpec 与定义解析

**目标：** 将 Markdown Agent 定义绑定为单一不可变配置对象。

**文件：**

- 新增 `src/main/java/com/mewcode/subagent/SubAgentSpec.java`
- 新增 `src/main/java/com/mewcode/subagent/AgentDefinitionParser.java`
- 新增 `src/test/java/com/mewcode/subagent/AgentDefinitionParserTest.java`

**步骤：**

1. 为全部字段、缺失必填项、未知字段、非法模型、非法权限模式和非正数 `maxTurns` 写测试。
2. 明确区分 `tools` 缺省与显式空数组。
3. 将 Markdown 正文绑定为 `systemPrompt`。
4. 缺省 `maxTurns` 使用调用方传入的全局默认值。
5. 所有集合在构造边界复制为不可变值。

**验证：** `./gradlew test --tests '*AgentDefinitionParserTest'`

**覆盖：** F2、AC2、N2。

## T5：实现四级 AgentCatalog 与内置角色

**目标：** 确定性加载插件、内置、用户、项目四级定义，并正确回退无效高优先级版本。

**文件：**

- 新增 `src/main/java/com/mewcode/subagent/AgentCatalog.java`
- 新增 `src/main/resources/agents/builtin/general-purpose.md`
- 新增 `src/main/resources/agents/builtin/plan.md`
- 新增 `src/main/resources/agents/builtin/explore.md`
- 新增 `src/test/java/com/mewcode/subagent/AgentCatalogTest.java`

**依赖：** T3、T4。

**步骤：**

1. 测试每级单独加载、四级同名覆盖、无效高优先级回退和同级排序。
2. 测试插件目录为零个或多个时的行为。
3. 实现不可变 Catalog 快照、`find/list/promptSummary` 和带路径诊断。
4. 加入三个最小内置角色，并验证项目或用户定义可以覆盖。
5. 对未知工具名生成诊断但不阻断定义加载。

**验证：** `./gradlew test --tests '*AgentCatalogTest'`

**覆盖：** F3、AC3、AC4、N2。

## T6：注册固定 Agent 工具

**目标：** 提供始终存在且 schema 稳定的统一委派入口。

**文件：**

- 新增 `src/main/java/com/mewcode/tool/impl/AgentTool.java`
- 新增 `src/test/java/com/mewcode/tool/impl/AgentToolTest.java`
- 修改启动注册代码

**依赖：** T5。

**步骤：**

1. 测试固定字段、必填项、模型枚举、附加字段拒绝和直接执行错误。
2. `subagent_type` 保持普通字符串，不生成动态 enum。
3. 测试 Catalog 内容变化前后 ToolRegistry 中仍是同一个 Agent 工具和同一 schema。
4. 暂只完成声明和注册，实际执行留给协调器任务。

**验证：** `./gradlew test --tests '*AgentToolTest' --tests '*ToolRegistryTest'`

**覆盖：** F1、AC1、N1。

## T7：扩展 ToolPolicy 支持绝对子 Agent 限制

**目标：** 用同一不可变策略实现所有过滤层，并消除 system tool 和 MCP 绕过路径。

**文件：**

- 修改 `src/main/java/com/mewcode/agent/ToolPolicy.java`
- 修改 `src/test/java/com/mewcode/agent/ToolPolicyTest.java`
- 按需补充 `ToolExecutor` 策略测试

**依赖：** T4、T6。

**步骤：**

1. 为全局禁用、自定义禁用、后台白名单、定义黑名单和定义白名单分别写测试。
2. 增加所有层叠加和顺序无误的组合测试。
3. 增加 `Agent`、四个 Task 工具、伪造 system tool 和 MCP 工具不可绕过测试。
4. 扩展现有策略表示，使普通主 Agent 继续保留当前 system tool 语义，子 Agent 使用绝对 allow set。
5. 确认 schema 过滤和 `ToolExecutor` 执行拒绝共用同一实例。

**验证：** `./gradlew test --tests '*ToolPolicyTest' --tests '*ToolExecutorTest'`

**覆盖：** F8、AC14—AC18、N3、N4、N12。

## T8：增加后台化与非交互权限原语

**目标：** 为不中断的前台转后台和后台 `default` 拒绝提供最小底层能力。

**文件：**

- 修改 `src/main/java/com/mewcode/agent/AgentRun.java`
- 修改 `src/main/java/com/mewcode/agent/AgentEvent.java`
- 修改 `src/main/java/com/mewcode/permission/PermissionBroker.java`
- 修改相关测试

**步骤：**

1. 为后台回调安装、单次触发、完成后清除和无回调行为写测试。
2. 增加 `AgentEvent.SubAgentBackgrounded`。
3. 为 broker 关闭后当前请求与未来请求均返回 `DENY` 写并发测试。
4. 保持主 Agent 原有权限响应和取消行为兼容。

**验证：** `./gradlew test --tests '*AgentRunTest' --tests '*PermissionBrokerTest'`

**覆盖：** F9、F10、AC20、AC21、AC25、N5。

## T9：实现 SubAgentTaskManager 核心

**目标：** 使用一个事件消费者管理子任务全生命周期，并保证终态和通知原子可见。

**文件：**

- 新增 `src/main/java/com/mewcode/subagent/SubAgentTaskManager.java`
- 新增 `src/test/java/com/mewcode/subagent/SubAgentTaskManagerTest.java`

**依赖：** T8。

**步骤：**

1. 用现有 `AgentRun` 和事件流构造最小测试，不新增测试框架。
2. 实现内部任务 ID、类型、状态、时间、进度、结果、用量、取消句柄和 `published` 状态。
3. 子任务启动时立即由唯一 virtual thread 消费事件。
4. 实现幂等 `publish`、终态 CAS、通知单次入队和 Session 过滤。
5. 并发测试多个任务完成、失败、取消和用量不串线。
6. 测试取消未发布前台任务和已发布后台任务均能停止真实 `AgentRun`。

**验证：** `./gradlew test --tests '*SubAgentTaskManagerTest'`

**覆盖：** F11、AC26、AC27、N5—N7、N10、N11。

## T10：实现 TaskList、TaskGet、TaskCreate、TaskUpdate

**目标：** 以四个固定工具暴露当前 Session 的任务查询、手工记录和取消能力。

**文件：**

- 新增 `src/main/java/com/mewcode/tool/impl/TaskTools.java`
- 新增 `src/test/java/com/mewcode/tool/impl/TaskToolsTest.java`
- 修改工具注册代码

**依赖：** T9。

**步骤：**

1. 测试四个固定 schema 和结构化 JSON 返回。
2. 实现当前 Session 列表、单项查询和不存在任务错误。
3. `TaskCreate` 创建不启动 Agent 的 `PENDING` 手工任务。
4. `TaskUpdate` 校验状态转换；对子 Agent 只允许请求取消。
5. 测试手工任务不产生通知且其他 Session 不可见。
6. 确认不存在 `TaskStop`，四个工具均被子 Agent 全局策略排除。

**验证：** `./gradlew test --tests '*TaskToolsTest' --tests '*ToolPolicyTest'`

**覆盖：** F12、AC28、AC29、N1、N4。

## T11：扩展提示组装 seam

**目标：** 向主提示加入 Agent Catalog 摘要，并允许 Fork 固定父请求 system segments。

**文件：**

- 修改 `src/main/java/com/mewcode/agent/PromptAdditions.java`
- 修改 `src/main/java/com/mewcode/agent/PromptRequestFactory.java`
- 修改对应测试

**依赖：** T5。

**步骤：**

1. 为 Agent Catalog 摘要位置和空摘要行为写测试。
2. 为固定 system segments 构造入口写不可变复制测试。
3. 保持现有 Memory、resume、Skill 和 Hook reminder 顺序及内容不变。
4. 运行全部 PromptRequestFactory 和 Provider 请求序列化回归测试。

**验证：** `./gradlew test --tests '*PromptRequestFactoryTest' --tests '*PromptRequestTest' --tests '*AnthropicClientTest' --tests '*OpenAiClientTest'`

**覆盖：** F1、F4、F5、AC1、AC5、AC7、N9、N13。

## T12：实现子 Agent 隔离运行构造

**目标：** 创建共享基础设施、隔离运行状态的 `SubAgentRuntime` 骨架和模型选择逻辑。

**文件：**

- 新增 `src/main/java/com/mewcode/subagent/SubAgentRuntime.java`
- 新增 `src/test/java/com/mewcode/subagent/SubAgentRuntimeTest.java`
- 按需为现有协调器增加受限配置入口

**依赖：** T3、T5、T7—T9、T11。

**步骤：**

1. 使用现有 `FakeLlmClient` 测试每次调用获得不同 Conversation、文件缓存、权限 grants、Hook state、取消和 token 状态。
2. 测试共享 ToolRegistry、HookEngine、项目路径和文件系统。
3. 实现调用模型 > 定义模型 > 父 route 的选择。
4. 显式模型不可解析时返回错误；Fork route 留给后续任务固定。
5. 为子协调器配置独立 `maxTurns` 和动态 `ToolPolicy` supplier。

**验证：** `./gradlew test --tests '*SubAgentRuntimeTest'`

**覆盖：** F4、F6、AC6、AC10、N10、N14。

## T13：打通 Definition-based 前台 RunToCompletion

**目标：** 定义式子 Agent 从空白上下文运行到底，并把最终完整文本作为 Agent 工具结果返回。

**文件：**

- 修改 `SubAgentRuntime.java`
- 修改 `SubAgentTaskManager.java`
- 扩展对应测试

**依赖：** T12。

**步骤：**

1. 测试首次请求只含角色 system、环境和本次任务，不含父历史、Memory 或 Skill。
2. 测试连续多轮工具调用后在首次无工具响应时完成。
3. 测试结果取最后完整 assistant 消息，而非跨轮流式文本拼接。
4. 测试 `maxTurns`、Provider 错误、工具错误和取消形成可区分终态。
5. 测试前台 `default` 权限经父 TUI 代理，`dontAsk` 不请求人工批准但安全层仍生效。

**验证：** `./gradlew test --tests '*SubAgentRuntimeTest' --tests '*SubAgentTaskManagerTest'`

**覆盖：** F4、F6、F7、F9、F14、AC5、AC10—AC13、AC19—AC22。

## T14：实现 Fork 上下文构造

**目标：** 从父本轮实际请求构造合法、缓存友好的 Fork 首次请求。

**文件：**

- 修改 `SubAgentRuntime.java`
- 新增或扩展 Fork 构造测试

**依赖：** T11—T13。

**步骤：**

1. 测试 system segments 和已发送 history 保持原顺序与内容。
2. 测试当前 assistant blocks 被追加，全部未闭合工具调用都有配对结果。
3. 测试 Fork Boilerplate 与任务位于历史末尾。
4. 测试 Fork 使用父本轮最终 route，忽略调用模型覆盖。
5. 测试工具 schema 使用过滤后的后台策略，不能为缓存放宽权限。
6. 测试父历史已经压缩或发生 Provider fallback 时使用实际发送快照。

**验证：** `./gradlew test --tests '*SubAgentRuntimeTest'`

**覆盖：** F5、AC6—AC9、N9。

## T15：在 AgentTurnCoordinator 接入统一 Agent 调度

**目标：** 在持有完整父上下文的唯一 seam 执行 Agent 工具，并保持普通工具、Hook 和消息配对正确。

**文件：**

- 修改 `src/main/java/com/mewcode/agent/AgentTurnCoordinator.java`
- 新增 `src/test/java/com/mewcode/agent/AgentTurnCoordinatorSubAgentTest.java`

**依赖：** T6、T13、T14。

**步骤：**

1. 测试未指定 `subagent_type` 分派 Fork，指定后分派定义式。
2. 捕获最终成功的 `PromptRequest`、assistant blocks、ToolPolicy、route、mode、parent run 和 Session ID。
3. Agent 调用手工执行现有 PreToolUse/PostToolUse Hook。
4. 普通工具仍走现有批执行，最终结果按原始调用顺序组装。
5. 测试混合普通工具、单个 Agent 和多个 Agent 调用时工具回合完整。
6. 测试子 Agent 无法再次调用 Agent，即使伪造 tool call 也在执行入口被拒绝。

**验证：** `./gradlew test --tests '*AgentTurnCoordinatorSubAgentTest' --tests '*AgentTurnCoordinatorTest' --tests '*AgentTurnCoordinatorHookTest'`

**覆盖：** F1、F5、F8、F15、AC7、AC8、AC14、AC17、AC33。

## T16：打通显式后台和 Fork 强制后台

**目标：** 后台调用立即把控制权交回父 Agent，实际子任务继续运行。

**文件：**

- 修改 `SubAgentRuntime.java`
- 修改 `SubAgentTaskManager.java`
- 扩展协调器和运行时测试

**依赖：** T15。

**步骤：**

1. 测试 `run_in_background=true` 立即返回 `async_launched` 和任务 ID。
2. 测试 Fork 无论输入参数如何始终发布后台。
3. 测试 Agent 工具返回后父 Agent 在同一轮继续生成。
4. 测试任务发布前后使用同一 run、线程、事件消费者和 token 累计。
5. 测试后台策略从首次请求起应用，任务工具和 Agent 不可见且不可执行。

**验证：** `./gradlew test --tests '*SubAgentRuntimeTest' --tests '*AgentTurnCoordinatorSubAgentTest'`

**覆盖：** F5、F10、F14、AC9、AC23、AC32、N5。

## T17：实现超时自动转后台

**目标：** 定义式前台任务达到可配置阈值后发布原任务，不取消重启。

**文件：**

- 修改 `SubAgentRuntime.java`
- 扩展运行时和任务管理器测试

**依赖：** T3、T16。

**步骤：**

1. 使用短测试阈值验证自动发布，不在测试中真实等待 20 秒。
2. 验证发布 ID、child run、请求次数、工具调用次数和 token 累计不变化。
3. 验证发布后的下一轮 schema 与实际执行只允许后台白名单。
4. 验证 `default` 当前等待审批和未来审批均直接拒绝。
5. 验证任务先完成时正常前台返回，不产生后台通知。

**验证：** `./gradlew test --tests '*SubAgentRuntimeTest' --tests '*SubAgentTaskManagerTest'`

**覆盖：** F8—F10、AC18、AC20、AC24、N5。

## T18：实现 Ctrl+B 手动转后台

**目标：** 在 TUI 等待定义式前台子任务时，用户可以发布同一任务并让父 Agent 继续。

**文件：**

- 修改 `src/main/java/com/mewcode/tui/MewCodeModel.java`
- 修改 `src/test/java/com/mewcode/tui/MewCodeModelTest.java`

**依赖：** T8、T16、T17。

**步骤：**

1. 在 TUI 顶层输入处理增加 `ctrl+b`，优先于流式态普通按键处理。
2. 只有 `activeRun.requestBackground()` 成功时显示任务 ID。
3. 处理 `SubAgentBackgrounded` 事件并清除残留 `pendingPermission`。
4. 测试普通主 Agent 流式生成、后台任务已经发布或没有子任务时按键无副作用。
5. 测试手动发布后父 Agent 收到异步工具结果并继续，而不是取消整轮。

**验证：** `./gradlew test --tests '*MewCodeModelTest' --tests '*AgentRunTest'`

**覆盖：** F10、AC25、N5、N13。

## T19：实现 task-notification 排队与安全注入

**目标：** 后台终态只生成一次安全通知，并在主 Agent 空闲时进入历史而不自动调用模型。

**文件：**

- 修改 `SubAgentTaskManager.java`
- 修改 `MewCodeModel.java`
- 修改 `ConversationManager` 测试或 TUI 测试

**依赖：** T9、T18。

**步骤：**

1. 实现 XML 转义、固定长度截断和 success/failure/cancelled 格式。
2. 测试系统提示、内部工具参数、堆栈和敏感字段不会进入摘要。
3. 增加独立任务轮询消息；有运行任务或待注入通知时保持轮询。
4. 主 Agent 流式期间只排队，空闲后以 user-role 消息注入当前 Conversation。
5. 测试注入通过现有 Session mutation listener 持久化，但不触发新 Provider 请求。
6. 测试同一任务重复终态和重复轮询只注入一次。

**验证：** `./gradlew test --tests '*SubAgentTaskManagerTest' --tests '*MewCodeModelTest' --tests '*ConversationManagerTest'`

**覆盖：** F13、F14、AC30—AC32、N6—N9。

## T20：接入启动、Provider 与 Session 生命周期

**目标：** 完成 Catalog、Runtime、TaskManager 的应用接线，并保证旧资源关闭前取消所属任务。

**文件：**

- 修改 `src/main/java/com/mewcode/MewCode.java`
- 修改 `src/main/java/com/mewcode/tui/MewCodeModel.java`
- 修改 `src/main/java/com/mewcode/agent/PromptAdditions.java`
- 修改启动和 TUI 测试

**依赖：** T5、T10、T15、T19。

**步骤：**

1. 启动时创建 TaskManager、注册五个工具并加载 Catalog；插件目录传空列表。
2. Provider 初始化后创建 Runtime 并配置协调器。
3. 主提示加入 Catalog 摘要，Skill 摘要保持原位置和行为。
4. `/clear`、`/resume`、Provider 重建和 `close` 在关闭共享资源前取消对应任务。
5. 迟到终态再次核对 Session ID，只清理资源不注入新会话。
6. 启动诊断不打印角色正文、API key 或异常堆栈。

**验证：** `./gradlew test --tests '*MewCodeTest' --tests '*MewCodeModelTest' --tests '*PromptRequestFactoryTest'`

**覆盖：** F3、F6、F13、F16、AC30、AC31、AC34、N8、N10、N13。

## T21：补齐 Hook、用量、错误和安全集成测试

**目标：** 验证跨模块组合行为，而不是只依赖单元测试分别成立。

**文件：** 新增或扩展 `src/test/java/com/mewcode/subagent/*IntegrationTest.java` 及现有 Hook/权限测试。

**依赖：** T13—T20。

**步骤：**

1. 并发运行两个子 Agent，验证消息、权限、文件缓存、Hook once、取消和 token 不串线。
2. 验证共享文件系统中的已提交修改可被另一 Agent 观察。
3. 验证 PreToolUse 拒绝、PostToolUse、Stop 和 Hook reminder 状态隔离。
4. 验证 `dontAsk` 不能绕过危险 Bash、越界路径、显式拒绝和 OS sandbox。
5. 验证异常、取消和 maxTurns 的模型可见消息均已脱敏。
6. 验证子 Agent 无法调用 Agent、AskUserQuestion 或任何 Task 工具。

**验证：** `./gradlew test --tests '*SubAgent*Test' --tests '*AgentTurnCoordinatorHookTest' --tests '*Permission*Test'`

**覆盖：** F6—F9、F11、F15、AC10—AC21、AC26、AC27、AC33、N4、N7、N8、N12。

## T22：完成兼容与全量回归

**目标：** 确认 SubAgent 没有改变现有用户路径。

**文件：** 仅在发现真实回归时修改根因所在文件和对应测试。

**依赖：** T2—T21。

**步骤：**

1. 运行 Skill shared/fork、普通 Agent Loop、Plan Mode、权限、Hook、压缩、Session、Memory、MCP 和 Provider 测试。
2. 修复回归时优先修改所有调用共享的根因 seam，不在单个测试路径增加补丁。
3. 确认 ToolRegistry 工具列表只新增需求明确的五个工具。
4. 确认现有 Skill 定义格式、热更新和 fork 外部行为保持兼容。

**验证：** `./gradlew test`

**覆盖：** F16、AC35、N13、N15。

## T23：格式、静态检查与打包

**目标：** 生成可用于真实验收的 shadow JAR。

**文件：** `build.gradle.kts` 及本阶段新增 Java 文件。

**依赖：** T22。

**步骤：**

1. 将新增 Java 包和测试纳入 Spotless，运行自动格式化。
2. 运行格式检查、全量测试、打包和 diff whitespace 检查。
3. 记录实际测试数量、失败/跳过数和 JAR 路径。

**验证：**

```bash
./gradlew spotlessApply spotlessCheck test shadowJar
git diff --check
```

**覆盖：** N14、N15、AC35。

## T24：准备隔离的 tmux 验收环境

**目标：** 使用打包后的真实 MewCode 和可控本地 Provider，避免依赖生产凭据或用户配置。

**文件：** 新增 `docs/ch13/verification.md`；临时配置放在独立临时目录，不提交凭据。

**依赖：** T23。

**步骤：**

1. 复用 Chapter 11 的本地 OpenAI SSE 假 Provider 模式，不新增产品依赖。
2. 创建临时 project、临时 user home、Agent 定义和 Hook 配置。
3. 用固定且确认未被占用的 tmux 会话启动 `build/libs/mewcode.jar`。
4. 保存启动命令、Session 名、临时路径、端口和初始输出。
5. 只记录脱敏证据；不覆盖真实用户配置。

**验证：** tmux 中出现 MewCode banner、输入框和五个新工具可被模型发现，进程可正常退出。

**覆盖：** N16。

## T25：执行 tmux 正常与后台流程

**目标：** 验证真实 TUI、Agent Loop、工具和任务管理闭环。

**依赖：** T24。

**步骤：**

1. 调用定义式只读 Agent，观察独立上下文、限定工具、RunToCompletion 和前台最终结果。
2. 发起 Fork，确认 Agent 工具立即返回任务 ID，父 Agent 同轮继续，后台完成后只注入一次通知。
3. 分别验证显式后台、默认 20 秒自动后台和 `Ctrl+B` 手动后台。
4. 用 `TaskList`、`TaskGet` 查询状态、进度、时间和用量。
5. 用 `TaskUpdate` 取消运行任务，确认实际 child run 进入取消终态。
6. 核对转后台前后没有重复首个请求、工具调用或 token 统计。
7. 发送下一条用户消息，确认主 Agent 能读取此前通知结果。

**验证：** 捕获 tmux pane、假 Provider 请求日志和任务查询输出，逐项写入 `verification.md`。

**覆盖：** AC36—AC38、N16。

## T26：执行 tmux 安全、清理与兼容流程

**目标：** 验证能力边界、Session 隔离和现有 Skill 不受影响。

**依赖：** T25。

**步骤：**

1. 在 `dontAsk` 角色中请求危险 Bash、越界路径或被 Hook 拒绝的操作，确认仍被拦截。
2. 让后台 `default` 角色请求需审批工具，确认不弹窗口并收到拒绝结果。
3. 启动慢任务后执行 `/clear`，确认旧任务取消且不会向新 Session 注入通知。
4. 重建 Provider 或正常退出，确认任务有界取消、进程不残留。
5. 执行现有 shared Skill 和 fork Skill，确认用户可见行为保持兼容。
6. 清理临时 tmux、Provider 进程和无敏感信息的临时配置。

**验证：** 保存安全拦截、Session 历史、退出状态和 Skill 回归的实际输出。

**覆盖：** AC19—AC21、AC33—AC38、N10、N12、N13、N16。

## T27：汇总验收证据

**目标：** 将实现状态与证据对应到全部验收标准，不用计划文本代替运行结果。

**文件：**

- `docs/ch13/checklist.md`
- `docs/ch13/verification.md`

**依赖：** T1—T26。

**步骤：**

1. 将 AC1—AC38 映射到自动化测试或 tmux 证据。
2. 仅勾选实际通过的条目；失败或受阻项保持未勾选并写明原因。
3. 记录最终提交、测试总数、构建产物、tmux 会话和清理结果。
4. 再次运行 `git diff --check` 并检查没有凭据、临时绝对路径或测试残留进入提交范围。

**验证：** checklist 无遗漏映射，verification 中每个完成结论都有命令、退出码或可观察输出。

## 依赖顺序

```text
T1
 ├─→ T2 ───────────────┐
 ├─→ T3 → T4 → T5 → T6├─→ T7 ─────────────┐
 │                     └─→ T11 ────────────┤
 └─→ T8 → T9 → T10 ────────────────────────┤
                                            ▼
                                           T12 → T13 → T14 → T15 → T16
                                                                    │
                                                                    ├─→ T17 → T18
                                                                    │           │
                                                                    └───────────┴→ T19
                                                                                   │
                                                                                   ▼
                                                                                  T20
                                                                                   │
                                                                                   ▼
                                                                                  T21
                                                                                   │
                                                                                   ▼
                                                                                  T22 → T23
                                                                                          │
                                                                                          ▼
                                                                                  T24 → T25 → T26 → T27
```

## 验收覆盖索引

| 范围 | 主要任务 |
|---|---|
| AC1—AC4：工具与定义加载 | T2—T6 |
| AC5—AC9：两种创建路径与模型 | T11—T16 |
| AC10—AC13：隔离与 RunToCompletion | T12、T13、T21 |
| AC14—AC21：工具与权限防线 | T7、T8、T13、T17、T21 |
| AC22—AC25：前后台切换 | T13、T16—T18 |
| AC26—AC33：任务、通知与 Hook | T9、T10、T15、T19、T21 |
| AC34—AC35：生命周期与兼容 | T20、T22 |
| AC36—AC38：tmux 端到端 | T24—T26 |
