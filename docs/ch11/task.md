# MewCode Skill 系统 Tasks

> 状态：已完成（2026-09-02）
>
> 上游文档：[spec.md](./spec.md) · [plan.md](./plan.md)

## 文件清单

| 操作 | 文件 | 职责 |
|---|---|---|
| 新建 | `src/main/java/com/mewcode/skill/SkillDefinition.java` | Skill 元数据、来源、模式与工具声明 |
| 新建 | `src/main/java/com/mewcode/skill/SkillParser.java` | Markdown、YAML、tool.json 解析与校验 |
| 新建 | `src/main/java/com/mewcode/skill/SkillCatalog.java` | 三级发现、覆盖、快照、刷新和白名单检查 |
| 新建 | `src/main/java/com/mewcode/skill/SkillRun.java` | 单次请求激活态、参数渲染和工具并集 |
| 新建 | `src/main/java/com/mewcode/skill/ScriptTool.java` | 专属脚本 Tool 适配器 |
| 新建 | `src/main/java/com/mewcode/skill/SkillExecutor.java` | shared 加载和 fork 临时运行 |
| 新建 | `src/main/java/com/mewcode/skill/ProviderRouter.java` | Provider 名称解析、客户端缓存和主路由 |
| 新建 | `src/main/java/com/mewcode/tool/impl/LoadSkillTool.java` | 系统级加载工具 schema 与标识 |
| 新建 | `src/main/resources/skills/builtin/{commit,review,test}.md` | 三个内置 Skill |
| 修改 | `src/main/java/com/mewcode/tool/{Tool,ToolRegistry,ToolExecutor}.java` | 系统工具、脚本工具替换、双层策略检查 |
| 修改 | `src/main/java/com/mewcode/tool/support/CommandRunner.java` | stdin、cwd、受限环境和分离输出 |
| 修改 | `src/main/java/com/mewcode/agent/{ToolPolicy,PromptAdditions,PromptRequestFactory,AgentEvent,AgentTurnCoordinator}.java` | Skill 提示、工具过滤、加载、Provider 回退与事件 |
| 修改 | `src/main/java/com/mewcode/command/CommandRegistry.java` | 动态 Skill 命令并删除硬编码 review |
| 修改 | `src/main/java/com/mewcode/conversation/ConversationManager.java` | fork 摘要原子写回 |
| 修改 | `src/main/java/com/mewcode/tui/MewCodeModel.java` | 刷新、slash 调度、生命周期与展示 |
| 修改 | `src/main/java/com/mewcode/MewCode.java` | 启动组装、按依赖发现 MCP 与致命校验 |
| 修改 | `build.gradle.kts` | 将新增 Java 文件纳入 Spotless |
| 新建/修改 | `src/test/java/com/mewcode/**` | 单元、集成与回归测试 |

## T1：定义不可变 Skill 模型

**文件：** `src/main/java/com/mewcode/skill/SkillDefinition.java`

**依赖：** 无

**步骤：**

1. 定义 `SkillDefinition`、`SkillMeta`、`SkillToolSpec` 和三个枚举。
2. 在 record 构造器中复制集合、归一化路径并落实默认值。
3. 拒绝空名称、说明、正文和越界脚本路径。

**验证：** 运行 `./gradlew compileJava`，期望新增类型编译通过。

## T2：实现参数渲染与请求激活态

**文件：** `src/main/java/com/mewcode/skill/SkillRun.java`、`src/test/java/com/mewcode/skill/SkillRunTest.java`

**依赖：** T1

**步骤：**

1. 实现全部 `{{arguments}}` 原样替换及无占位符时的“用户输入”追加。
2. 用插入有序映射保存多个激活项，同名重载时替换定义。
3. 实现 SOP 块、白名单并集和最后声明模型的 Provider 偏好。
4. 实现 `clear`，确认不会改动 Catalog。

**验证：** 运行 `./gradlew test --tests com.mewcode.skill.SkillRunTest`，期望参数、顺序、并集、模型偏好和清理用例通过。

## T3：解析单文件 Skill

**文件：** `src/main/java/com/mewcode/skill/SkillParser.java`、`src/test/java/com/mewcode/skill/SkillParserTest.java`

**依赖：** T1

**步骤：**

1. 分离文件开头 YAML frontmatter 与 Markdown 正文。
2. 用 SnakeYAML 手动绑定七个字段并实现默认值、名称、枚举和正整数校验。
3. 生成不包含正文内容的定位诊断。

**验证：** 运行 `./gradlew test --tests com.mewcode.skill.SkillParserTest`，期望合法、缺字段、坏 YAML、非法枚举和默认值用例通过。

## T4：解析目录工具声明

**文件：** `src/main/java/com/mewcode/skill/SkillParser.java`、`src/test/java/com/mewcode/skill/SkillParserTest.java`

**依赖：** T3

**步骤：**

1. 读取可选 `tool.json` 并用 Jackson 绑定工具项。
2. 校验工具名、说明、常用 JSON Schema 子集和脚本相对路径。
3. 校验普通文件、目录边界、可执行权限与 shebang。

**验证：** 重跑 `SkillParserTest`，期望合法目录包成功，schema、越界路径、权限和 shebang 错误被隔离。

## T5：实现三级 Catalog 与覆盖

**文件：** `src/main/java/com/mewcode/skill/SkillCatalog.java`、`src/test/java/com/mewcode/skill/SkillCatalogTest.java`

**依赖：** T3、T4

**步骤：**

1. 固定加载三个 classpath 内置资源。
2. 扫描用户和项目目录中的 `.md` 与目录入口并按路径排序。
3. 按内置、用户、项目合并；无效高优先级版本回退低优先级有效版本。
4. 输出名称/说明摘要，并拒绝系统命令及别名冲突。

**验证：** 运行 `./gradlew test --tests com.mewcode.skill.SkillCatalogTest`，期望三层覆盖、大小写归一、稳定排序、回退和保留名用例通过。

## T6：实现 Catalog 原子刷新与白名单结果

**文件：** `src/main/java/com/mewcode/skill/SkillCatalog.java`、`src/test/java/com/mewcode/skill/SkillCatalogTest.java`

**依赖：** T5

**步骤：**

1. 先在局部变量构建完整候选快照。
2. 返回脚本工具清单、诊断和缺失工具对。
3. 热更新遇到未知工具时跳过无效定义并重新选择低优先级有效版本，再一次替换完整快照。
4. 保证此前取得的 `SkillDefinition` 不随刷新变化。

**验证：** 重跑 `SkillCatalogTest`，期望新增、修改、删除、无效更新、未知工具和运行中快照稳定用例通过。

## T7：扩展 Tool 系统标识与动态注册

**文件：** `src/main/java/com/mewcode/tool/Tool.java`、`src/main/java/com/mewcode/tool/ToolRegistry.java`、`src/test/java/com/mewcode/tool/ToolRegistryTest.java`

**依赖：** T1

**步骤：**

1. 为 `Tool` 增加默认 `isSystem=false`。
2. 在 Registry 中记录脚本工具来源，并提供整体替换方法。
3. 替换前检测与普通/MCP/其他脚本工具名称冲突，保留稳定注册顺序。

**验证：** 运行 `./gradlew test --tests com.mewcode.tool.ToolRegistryTest`，期望系统标记默认值、原子替换和冲突测试通过。

## T8：实现脚本输入 schema 校验

**文件：** `src/main/java/com/mewcode/skill/ScriptTool.java`、`src/test/java/com/mewcode/skill/ScriptToolTest.java`

**依赖：** T1、T7

**步骤：**

1. 把 `SkillToolSpec` 适配成固定高风险的 `Tool`。
2. 校验 `type/properties/required/items/enum/additionalProperties`。
3. 返回面向 Agent 的短错误，不抛出原始解析异常。

**验证：** 运行 `./gradlew test --tests com.mewcode.skill.ScriptToolTest`，期望对象、数组、必填、枚举和额外字段用例通过。

## T9：扩展安全命令执行协议

**文件：** `src/main/java/com/mewcode/tool/support/CommandRunner.java`、`src/main/java/com/mewcode/skill/ScriptTool.java`、`src/test/java/com/mewcode/skill/ScriptToolTest.java`

**依赖：** T8

**步骤：**

1. 为 `CommandRunner` 增加只接受 argv、cwd、stdin、环境白名单和取消 token 的窄入口。
2. 分离且限长读取 stdout/stderr，沿用超时与进程销毁。
3. 执行前重验脚本边界、文件、权限和 shebang。
4. 解析 stdout 结果协议，安全处理非零退出、非法 JSON、超时与取消。

**验证：** 重跑 `ScriptToolTest`，期望 stdin/stdout、cwd、环境隔离、错误、超时和取消用例通过。

## T10：实现系统级 `LoadSkill` 工具声明

**文件：** `src/main/java/com/mewcode/tool/impl/LoadSkillTool.java`、`src/test/java/com/mewcode/tool/impl/LoadSkillToolTest.java`

**依赖：** T7

**步骤：**

1. 定义 `name` 与可选 `arguments` 输入 schema。
2. 标记为系统工具、只读、非破坏且不可延迟隐藏。
3. 对绕过 Agent 协调器的直接执行返回安全错误。

**验证：** 运行 `./gradlew test --tests com.mewcode.tool.impl.LoadSkillToolTest`，期望 schema、系统标识和直接执行保护通过。

## T11：把白名单并入 ToolPolicy

**文件：** `src/main/java/com/mewcode/agent/ToolPolicy.java`、`src/test/java/com/mewcode/agent/ToolPolicyTest.java`

**依赖：** T2、T7

**步骤：**

1. 让 Policy 接收 AgentMode 和 Skill 白名单快照。
2. 实现系统工具永远允许、无 Skill 沿用旧行为、有 Skill 使用并集与模式交集。
3. 提供被模式过滤的工具名列表用于加载反馈。

**验证：** 运行 `./gradlew test --tests com.mewcode.agent.ToolPolicyTest`，期望 Execute、Plan、空白名单、多 Skill 与系统工具矩阵通过。

## T12：修正权限路径的执行层检查

**文件：** `src/main/java/com/mewcode/tool/ToolExecutor.java`、`src/test/java/com/mewcode/tool/PermissionToolExecutorTest.java`

**依赖：** T11

**步骤：**

1. 让 PermissionContext 的单个和批量入口同时接收 ToolPolicy。
2. 在 `PermissionGate` 前拒绝 Policy 禁止的调用。
3. 并发安全判断也使用相同 Policy。

**验证：** 运行 `./gradlew test --tests com.mewcode.tool.PermissionToolExecutorTest`，期望伪造白名单外调用和 Plan 写调用均在执行前被拒绝。

## T13：注入 Catalog 摘要和 Active SOP

**文件：** `src/main/java/com/mewcode/agent/PromptAdditions.java`、`src/main/java/com/mewcode/agent/PromptRequestFactory.java`、对应现有测试

**依赖：** T2、T5

**步骤：**

1. 扩展 additions 字段并保持空值兼容。
2. 按“摘要在前、Active SOP 最后”的顺序构造 system segments。
3. 保证 context 预检与真实请求使用同一 additions 快照。

**验证：** 运行 `./gradlew test --tests com.mewcode.agent.PromptRequestFactoryTest`，期望未激活正文不泄漏，激活 SOP 每轮存在且位于最后。

## T14：实现动态 Skill 斜杠命令

**文件：** `src/main/java/com/mewcode/command/CommandRegistry.java`、`src/test/java/com/mewcode/command/CommandRegistryTest.java`

**依赖：** T5

**步骤：**

1. 删除硬编码 review Prompt 和 `/r`。
2. 增加整体替换、无别名的 Skill 命令集合。
3. 将动态命令合并到查找、帮助、解析和补全，并公开静态保留标识。

**验证：** 运行 `./gradlew test --tests com.mewcode.command.CommandRegistryTest`，期望动态增删、帮助、补全、参数保留和 review 替换通过。

## T15：实现 ProviderRouter

**文件：** `src/main/java/com/mewcode/skill/ProviderRouter.java`、`src/test/java/com/mewcode/skill/ProviderRouterTest.java`

**依赖：** 无

**步骤：**

1. 以配置 `name` 建立确定映射并保留主 Provider。
2. 未指定或未配置时返回主路由。
3. 对已配置 Provider 按需创建并缓存 client/protocol，关闭时释放客户端资源。

**验证：** 运行 `./gradlew test --tests com.mewcode.skill.ProviderRouterTest`，期望默认、命中、缺失、缓存和关闭用例通过。

## T16：接入每轮 Provider 路由与回退

**文件：** `src/main/java/com/mewcode/agent/AgentEvent.java`、`src/main/java/com/mewcode/agent/AgentTurnCoordinator.java`、对应现有 Agent 测试

**依赖：** T11–T13、T15

**步骤：**

1. 协调器每轮从 `SkillRun` 获取 Policy、Prompt additions 和 ProviderRoute。
2. 增加 `ProviderFallback` 事件；偏好路由失败时不提交历史并用主路由重试一次。
3. 两次尝试分别累计已报告 token，主路由失败后正常结束错误。
4. 确保 ContextManager 使用实际发送的 protocol、schemas 和 prompt 快照。

**验证：** 运行 `./gradlew test --tests 'com.mewcode.agent.AgentTurnCoordinator*Test'`，期望每轮切换、一次回退、双失败、无重复历史和 token 统计通过。

## T17：在 Agent Loop 处理 `LoadSkill`

**文件：** `src/main/java/com/mewcode/agent/AgentTurnCoordinator.java`、对应 Agent 测试

**依赖：** T6、T10–T13、T16

**步骤：**

1. 在普通工具批处理前分离 `LoadSkill` 调用。
2. 刷新 Catalog，加载 shared 定义并激活到当前 `SkillRun`。
3. 返回成功、未知 Skill、刷新诊断和被模式过滤工具。
4. 同轮普通工具返回重选错误，下一轮重新生成 schemas。

**验证：** 运行 Agent 协调器测试，期望两阶段加载、多 Skill 并集、Plan 过滤、同轮隔离和请求结束清理通过。

## T18：实现 fork 历史切片

**文件：** `src/main/java/com/mewcode/skill/SkillExecutor.java`、`src/test/java/com/mewcode/skill/SkillExecutorTest.java`

**依赖：** T1、T2

**步骤：**

1. 实现 none、recent、full 三种主历史复制。
2. recent 从用户轮次边界截取默认或指定数量。
3. 检查 assistant 工具调用与 user 工具结果不被拆散。

**验证：** 运行 `./gradlew test --tests com.mewcode.skill.SkillExecutorTest`，期望 0、少于 N、等于 N、多于 N 和含工具回合用例通过。

## T19：实现阻塞 fork Agent

**文件：** `src/main/java/com/mewcode/skill/SkillExecutor.java`、`src/test/java/com/mewcode/skill/SkillExecutorTest.java`

**依赖：** T9、T15、T16、T18

**步骤：**

1. 构造不接 Session/Memory 的临时 Conversation、ContextManager、SkillRun 和协调器。
2. 把子事件、权限请求与取消挂到父 AgentRun，主流程同步等待结束。
3. 返回最后完整 assistant 文本或安全错误摘要。
4. fork 内允许 shared LoadSkill，拒绝嵌套 fork，并在 finally 清理临时状态。

**验证：** 重跑 `SkillExecutorTest`，期望阻塞、摘要、失败、取消、权限桥接、无 Session 和嵌套拒绝用例通过。

## T20：接入 fork 的 LoadSkill 调用

**文件：** `src/main/java/com/mewcode/agent/AgentTurnCoordinator.java`、对应 Agent 测试

**依赖：** T17、T19

**步骤：**

1. `LoadSkill` 命中 fork 定义时调用 `SkillExecutor.runFork`。
2. 将 fork 摘要作为工具结果回灌主 Agent。
3. 保证 fork 内部消息不进入主历史，主 Agent 继续生成最终回答。

**验证：** 运行 Agent 协调器测试，期望自然语言触发 fork 后仅工具摘要进入主工具回合。

## T21：增加 fork 摘要原子写回

**文件：** `src/main/java/com/mewcode/conversation/ConversationManager.java`、`src/test/java/com/mewcode/conversation/ConversationManagerTest.java`

**依赖：** 无

**步骤：**

1. 增加一次追加 user 与 assistant 文本的方法。
2. 复用现有 mutation listener，以单次 APPEND 持久化。

**验证：** 运行 `./gradlew test --tests com.mewcode.conversation.ConversationManagerTest`，期望消息顺序、单次 mutation 和异常前不部分提交。

## T22：重构启动前 Skill/MCP 组装

**文件：** `src/main/java/com/mewcode/MewCode.java`、相关测试

**依赖：** T5–T10、T14

**步骤：**

1. 在进入 TUI 前创建 Registry、LoadSkill、Catalog 和脚本工具。
2. 仅当 Skill 白名单引用未发现的 `mcp_*` 工具时同步连接 MCP；无关 MCP 交回 TUI 后台初始化。
3. 用完整工具名集合验证最终白名单；缺失项完整打印并退出 2。
4. 把已初始化对象所有权交给 MewCodeModel，失败路径关闭 MCP。

**验证：** 运行入口测试和最小卡死 MCP 夹具，期望无关 MCP 不阻塞 TUI、被引用的缺失工具退出 2。

## T23：接入 TUI shared/fork 命令与热更新

**文件：** `src/main/java/com/mewcode/tui/MewCodeModel.java`、`src/test/java/com/mewcode/tui/MewCodeModelTest.java`

**依赖：** T6、T14、T16、T19、T21、T22

**步骤：**

1. 接收并复用启动完成的 Catalog、Registry、MCP、ProviderRouter。
2. 在 Tab、slash 提交和 Agent LoadSkill 回调上刷新 Catalog、脚本工具与动态命令。
3. shared slash 创建 SkillRun 并启动主协调器；fork slash 阻塞执行后原子写回摘要。
4. 处理 `ProviderFallback`，清除失败尝试的临时 stream buffer 并展示简短提示。
5. 请求完成、失败、取消、provider 切换、`/clear` 和关闭路径统一清理请求态。

**验证：** 运行 `./gradlew test --tests com.mewcode.tui.MewCodeModelTest`，期望补全刷新、shared/fork 调度、回退显示和全部清理路径通过。

## T24：添加内置 Skill

**文件：** `src/main/resources/skills/builtin/commit.md`、`review.md`、`test.md`、Catalog 测试

**依赖：** T3、T5

**步骤：**

1. 按已确认模式和白名单编写三个最小 SOP。
2. commit 指导检查状态、暂存内容并生成提交；review 聚焦 diff 缺陷；test 选择并运行相关测试。
3. 验证用户/项目同名定义可以覆盖内置版本。

**验证：** 运行 `SkillCatalogTest`，期望三项均发现、元数据正确、覆盖与回退正常。

## T25：补齐格式、集成和回归测试

**文件：** `build.gradle.kts`、上述新增与现有测试文件

**依赖：** T1–T24

**步骤：**

1. 将新增 Java 路径纳入 Spotless，不新增依赖。
2. 补齐启动摘要保密、运行快照、脚本安全、Plan/权限、Provider、热更新与清理集成测试。
3. 运行格式检查、全量测试和打包。

**验证：** 依次运行 `./gradlew spotlessCheck`、`./gradlew test`、`./gradlew shadowJar`，期望全部退出码为 0。

## T26：执行 tmux 端到端验收

**文件：** `docs/ch11/checklist.md`（记录结果）

**依赖：** T25

**步骤：**

1. 在 tmux 启动打包后的 MewCode，执行 checklist 的正常流程。
2. 构造隔离目录执行缺失工具、无效 Skill、Provider 回退和脚本权限/取消流程。
3. 将每项实际命令与可观测结果写入验收报告，失败项修复后重跑。

**验证：** checklist 全部标记通过，并附 tmux 中观察到的实际证据。

## 执行顺序

```text
T1 ─┬→ T2 ──────────────┐
    └→ T3 → T4 → T5 → T6├→ T13 → T17 ─┐
T1 ─→ T7 → T8 → T9 → T10┤              ├→ T20 ─┐
T2,T7 → T11 → T12 ──────┤              │       │
T15 ─────────────→ T16 ──┘              │       ├→ T23 → T25 → T26
T1,T2 → T18 → T19 ──────────────────────┘       │
T21 ────────────────────────────────────────────┤
T5 → T14 ───────────────────────────────────────┤
T5 → T24 ───────────────────────────────────────┘
T5–T10,T14 → T22 ──────────────────────────────→ T23
```

所有任务都有可运行验证；没有市场、安装、watcher、持久激活或通用插件任务。
