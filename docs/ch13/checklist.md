# MewCode SubAgent 验收清单

> 状态：已确认；等待实施指令
>
> 对应文档：[spec.md](./spec.md)、[plan.md](./plan.md)、[task.md](./task.md)
>
> 当前条目均为待验证状态。只有取得对应测试输出、构建退出码或 tmux 可观察证据后才能勾选。

## 证据规则

- 单元测试通过只能证明对应类或 seam，不替代真实 TUI 流程。
- `spotlessCheck`、`test`、`shadowJar` 和 `git diff --check` 分别记录退出码。
- tmux 验收使用打包后的真实 MewCode；本地假 Provider 只能控制模型输出，不能替代 Agent Loop、工具、权限、任务管理或 TUI。
- 模型声称“已调用工具”不算证据，必须核对工具事件、任务状态、文件结果、Provider 请求或 Session 历史。
- 失败、跳过或环境受阻的条目保持未勾选，并在 `verification.md` 写明边界。
- 不在文档、日志或终端截图中记录 API key、环境凭据、完整角色提示或敏感工具参数。

## A. Agent 工具与定义加载

- [ ] **A01 / AC1：Agent 工具固定注册。** 增删 Agent 定义并重新启动后，Provider 看到的仍是同一个 `Agent` 工具，字段和 schema 结构不变。（证据：`AgentToolTest`、`ToolRegistryTest`、两组 Provider 请求）
- [ ] **A02 / AC2：定义字段完整且非法定义隔离。** 合法 Markdown 的全部字段和正文可读取；缺少必填字段、非法枚举、未知字段、空正文或非正数 `maxTurns` 的定义被安全跳过。（证据：`AgentDefinitionParserTest`）
- [ ] **A03 / AC3：四级优先级正确。** 插件、内置、用户、项目同名时项目获胜；逐级删除后依次回退至用户、内置、插件。（证据：`AgentCatalogTest`）
- [ ] **A04 / AC4：无效高优先级不遮蔽。** 无效项目或用户定义不会遮蔽低优先级合法版本，也不会阻止其他角色加载。（证据：`AgentCatalogTest`、启动诊断）

## B. 两种创建路径与模型

- [ ] **B01 / AC5：定义式使用干净上下文。** 首次请求只含角色 system、环境和本次任务，不含父历史、Memory、恢复提醒或已激活 Skill。（证据：`SubAgentRuntimeTest` 捕获的 `PromptRequest`）
- [ ] **B02 / AC6：模型优先级正确。** 定义式按调用参数、定义、父 route 选择；Fork 忽略模型覆盖并使用父本轮最终 route；显式不可用模型明确报错。（证据：`SubAgentRuntimeTest`）
- [ ] **B03 / AC7：Fork 继承实际父请求。** Fork 首次请求包含父本轮实际 system/history 前缀及末尾子任务，不重新读取未经压缩的 Session。（证据：Fork 请求快照测试）
- [ ] **B04 / AC8：Fork 修复未闭合工具调用。** 当前 assistant response 中每个未闭合 `tool_use` 都有配对结果，Provider 不因孤立工具块失败。（证据：多工具 Fork 测试、Provider 序列化测试）
- [ ] **B05 / AC9：Fork 始终立即后台。** 未指定 `subagent_type` 时走 Fork，Agent 工具立即返回 `async_launched` 和任务 ID，不等待子任务完成。（证据：协调器集成测试、tmux）

## C. 状态隔离与 RunToCompletion

- [ ] **C01 / AC10：并发状态隔离。** 两个子 Agent 的消息、权限 grants、文件读缓存、Hook once 状态、取消和 token 用量互不影响，同时能观察共享文件系统中已提交的改动。（证据：并发集成测试）
- [ ] **C02 / AC11：无工具响应正常收口。** 连续工具调用时继续循环，第一次完整无工具响应后立即结束并返回最后完整 assistant 文本。（证据：`SubAgentRuntimeTest`）
- [ ] **C03 / AC12：最大轮次生效。** 达到角色 `maxTurns` 后形成明确失败终态，不再请求 Provider。（证据：请求计数和任务快照）
- [ ] **C04 / AC13：错误和取消隔离。** 工具异常、Provider 异常、Loop 异常和取消形成可区分终态，不终止父 Agent 或其他任务。（证据：任务管理器并发测试）

## D. 工具与权限防线

- [ ] **D01 / AC14：全局禁止不可绕过。** 所有子 Agent 都看不到且无法伪造调用 `Agent`、`AskUserQuestion`、`TaskList`、`TaskGet`、`TaskCreate` 或 `TaskUpdate`。（证据：ToolPolicy schema/执行双层测试）
- [ ] **D02 / AC15：自定义 Agent 附加限制。** 项目、用户和插件定义应用 `CUSTOM_AGENT_DISALLOWED_TOOLS`，内置定义不应用该层。（证据：来源参数化策略测试）
- [ ] **D03 / AC16：定义黑白名单叠加。** `disallowedTools` 从候选集中排除；`tools` 存在时严格取交集；缺省与显式空数组语义不同。（证据：ToolPolicy 组合测试）
- [ ] **D04 / AC17：schema 与执行一致。** 发给 Provider 的工具列表和执行入口使用同一策略；伪造白名单外、本地 system 或 MCP 调用均在执行前拒绝。（证据：PromptRequest + ToolExecutor 集成测试）
- [ ] **D05 / AC18：后台后立即收窄。** 前台任务发布后台后，下一次 Provider 请求和下一次实际执行仅允许异步白名单工具。（证据：转后台跨轮测试）
- [ ] **D06 / AC19：dontAsk 不降低安全层。** 不出现人工审批，但危险 Bash、无 OS sandbox、越界路径、拒绝规则和 Hook 拒绝仍能阻止调用。（证据：权限集成测试、tmux）
- [ ] **D07 / AC20：后台 default 非交互拒绝。** 当前等待和后续需要审批的调用直接返回拒绝，不弹窗口、不挂起任务、不升级权限。（证据：PermissionBroker 并发测试、tmux）
- [ ] **D08 / AC21：Fork 权限独立。** Fork 固定使用独立 `dontAsk`，父 Agent 的临时 session grants 不出现在子规则引擎中。（证据：权限状态断言）

## E. 前后台切换

- [ ] **E01 / AC22：定义式默认前台。** 未指定后台时等待子 Agent 完成，最终文本作为本次 Agent 工具结果返回，不生成后台通知。（证据：运行时测试、tmux）
- [ ] **E02 / AC23：显式后台立即返回。** `run_in_background=true` 返回任务 ID，原子任务在后台继续运行。（证据：运行时测试、tmux）
- [ ] **E03 / AC24：自动后台可配置。** 默认满 20 秒发布后台；测试配置和实际配置修改后按新阈值生效。（证据：虚拟短阈值测试、tmux 20 秒流程）
- [ ] **E04 / AC25：Ctrl+B 不重启任务。** 按键后主 Agent 恢复，child run、首个请求、工具调用、事件消费者和 token 累计保持连续。（证据：`MewCodeModelTest`、Provider 请求计数、tmux）

## F. 任务管理、通知与 Hook

- [ ] **F01 / AC26：任务信息完整。** 已发布任务可查询 ID、类型、状态、起止时间、最近活动、工具进度、结果和独立 token 用量。（证据：TaskList/TaskGet 测试与 tmux 输出）
- [ ] **F02 / AC27：终态和并发原子。** 每个任务只有一个成功、失败或取消终态；并发任务的状态、结果、用量和通知不串线。（证据：任务管理器并发测试）
- [ ] **F03 / AC28：四个任务工具工作。** 主 Agent 可列出、查询、创建和更新当前 Session 任务；子 Agent 无法使用它们。（证据：`TaskToolsTest`、策略测试、tmux）
- [ ] **F04 / AC29：TaskUpdate 取消真实执行。** 对运行子任务请求取消会触发对应 `AgentRun.cancel()` 并最终进入 `CANCELLED`；不存在 `TaskStop`。（证据：取消句柄测试、工具清单）
- [ ] **F05 / AC30：通知唯一且安全。** 后台任务终态只向所属主历史增加一条 `<task-notification>`，包含 ID、状态、安全摘要和用量，不包含内部历史、系统提示、工具参数、堆栈或凭据。（证据：通知测试、Session JSONL）
- [ ] **F06 / AC31：通知不自动请求模型。** 通知在父 Agent 空闲时注入；Provider 请求数不增加，下一次用户消息的请求可以读取通知。（证据：TUI 测试、Provider 请求日志、tmux）
- [ ] **F07 / AC32：转后台结果不重复。** 原 Agent 工具调用只返回任务 ID，最终结果只通过通知回传，不再出现迟到工具结果。（证据：Conversation 历史和 tmux 输出）
- [ ] **F08 / AC33：Hook 规则共享、状态隔离。** 子工具调用触发共享规则，Pre/Post/Stop 行为正确；once、提醒和运行状态不污染父 Agent，结束后无残留。（证据：Hook 集成测试）

## G. 生命周期与兼容

- [ ] **G01 / AC34：Session 清理安全。** `/clear`、`/resume`、Provider 重建和退出取消所属未完成任务；旧任务迟到完成也不能注入新 Session。（证据：TUI 生命周期测试、tmux、Session 历史）
- [ ] **G02 / AC35：现有功能兼容。** Skill shared/fork、普通对话、Plan、权限、Hook、压缩、Session、Memory、MCP、Provider fallback 和主 token 统计测试继续通过。（证据：全量测试、tmux Skill 回归）

## H. 自动化与构建门禁

- [ ] **H01：共享 frontmatter 与 Skill 回归通过。**（证据：`MarkdownFrontmatterTest`、`SkillParserTest`、`SkillCatalogTest`）
- [ ] **H02：SubAgent 定义、Catalog、策略和任务测试通过。**（证据：`./gradlew test --tests '*SubAgent*Test' --tests '*AgentCatalogTest' --tests '*ToolPolicyTest' --tests '*TaskToolsTest'`）
- [ ] **H03：协调器、权限、Hook 和 TUI 集成测试通过。**（证据：相关定向 Gradle 测试）
- [ ] **H04：全部自动化测试通过且无意外跳过。**（证据：`./gradlew test` 输出及测试数量）
- [ ] **H05：格式检查通过。**（证据：`./gradlew spotlessApply spotlessCheck` 退出码 0）
- [ ] **H06：shadow JAR 构建成功。**（证据：`./gradlew shadowJar` 退出码 0，记录 `build/libs/mewcode.jar`）
- [ ] **H07：Diff whitespace 检查通过。**（证据：`git diff --check` 退出码 0）
- [ ] **H08：无新增依赖、凭据或临时测试残留。**（证据：`build.gradle.kts`、`git status --short` 和 diff 审查）

## I. tmux 端到端

- [ ] **I01 / AC36：定义式前台闭环。** 在 tmux 启动打包后的 MewCode，调用只读定义式角色，观察干净上下文、限定工具、真实 RunToCompletion 和前台结果返回。（证据：pane 输出、Provider 请求、工具事件）
- [ ] **I02 / AC37：Fork 异步闭环。** Fork 后主 Agent 立即继续；后台完成只产生一条通知，下一轮主 Agent 能使用结果。（证据：时间顺序、任务状态、Conversation 历史、Provider 请求）
- [ ] **I03 / AC38：显式后台流程。** 显式后台立即返回任务 ID，TaskList/Get 可观察连续进度和用量。（证据：pane 输出、任务 JSON）
- [ ] **I04 / AC38：20 秒自动后台流程。** 前台任务运行满默认阈值后自动发布，首个请求和工具调用没有重复。（证据：时间戳、Provider 请求计数）
- [ ] **I05 / AC38：Ctrl+B 手动后台流程。** 手动发布后父 Agent 恢复，任务从原进度继续，后台工具集收窄。（证据：pane 输出、任务 ID、工具 schema）
- [ ] **I06 / AC38：任务取消流程。** 用 TaskUpdate 取消正在运行的任务，观察真实执行终止和单次取消通知。（证据：任务状态、Provider/工具停止、历史通知）
- [ ] **I07：权限安全流程。** `dontAsk` 角色仍被危险命令、路径或 Hook 阻止；后台 `default` 不弹审批。（证据：工具拒绝结果和无审批 UI）
- [ ] **I08：Session 清理流程。** 慢任务运行时执行 `/clear`，旧任务取消且新 Session 无迟到通知。（证据：旧/新 Session 历史、任务状态）
- [ ] **I09：Skill 兼容流程。** 在同一构建中执行现有 shared Skill 和 fork Skill，观察原有接口与行为不变。（证据：pane 输出）
- [ ] **I10：正常退出与环境清理。** MewCode、假 Provider 和 tmux 正常退出，无遗留进程、端口、凭据或用户配置修改。（证据：进程/端口检查和清理记录）

## J. 最终证据完整性

- [ ] **J01：AC1—AC38 全部有证据映射。** 每个 AC 至少对应一个自动化或 tmux 条目。
- [ ] **J02：verification.md 只记录实际结果。** 包含命令、退出码、测试统计、tmux 会话、关键现象和未通过项。
- [ ] **J03：文档状态与事实一致。** `task.md`、`checklist.md` 和 `verification.md` 不将未验证项标为完成。
- [ ] **J04：最终工作区可审查。** `git status --short`、diff 和新增文件均在需求范围内，无无关或生成垃圾。
