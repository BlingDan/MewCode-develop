# MewCode 面试案例记录

更新于 2026-09-24。只记录有源码或运行证据的问题；“设计时预防的风险”和“实际复现的故障”分开表述，不编造性能收益。

## 可写简历：SubAgent 异步任务的结果与通知一致性

**问题。** 子 Agent 可以前台运行、转后台或直接后台运行。多轮工具调用时，前几轮的流式文本不能混入最终结果；后台完成时，任务状态、结果和通知必须一起对父会话可见，且通知只能出现一次。

**怎么发现。** 实现任务管理器时检查事件顺序：`StreamText` 先于 `ToolUse` 到达，而完成 Future 可能先于 UI 轮询通知。针对这两个边界编写了 `SubAgentTaskManagerTest`，分别检查最终文本和通知唯一性；tmux 验收又检查了真实 TUI 的 Fork 回流。

**怎么解决。** [SubAgentTaskManager](../src/main/java/com/mewcode/subagent/SubAgentTaskManager.java) 在进入工具轮时清除该轮的过渡文本；终态处理集中在同步的 `finish` 中，先写入结果并入队唯一通知，再完成 Future。[MewCodeModel](../src/main/java/com/mewcode/tui/MewCodeModel.java) 在下一次用户请求前吸收尚未轮询的通知。

**证据与边界。** 2026-09-24 在目标分支重新运行 `./gradlew spotlessCheck test shadowJar --no-daemon --rerun-tasks`，333 个测试通过。tmux 中 `fork now` 立即返回 `async_launched` 和任务 ID，Session 历史只有一条 `<task-notification>`，包含最终摘要 `FORK_DONE`。真实 TUI 的取消调用路径走通过；取消终态主要由任务管理器测试证明，不能说长 SSE 场景已完整验证。

**简历候选句。** 设计并实现 SubAgent 异步任务管理，统一前后台切换、取消与终态通知；处理多轮工具结果污染及通知竞态，并通过自动化测试和真实 TUI 验收闭环。

## 可写简历：子 Agent 工具权限的双层约束

**问题。** 只从发给模型的工具列表中隐藏 `Agent` 或任务工具，不能阻止模型构造同名调用；普通模式对 system 工具的放行规则也不能直接复用于子 Agent。这里是设计时识别的安全风险，并非已发生的越权事故。

**怎么发现。** 对照 [第 13 章验收清单](ch13/checklist.md) 的 D01/D04，追踪 Provider schema 生成与 `ToolExecutor` 执行入口，发现两个入口必须消费同一份不可放宽的策略。

**怎么解决。** [ToolPolicy](../src/main/java/com/mewcode/agent/ToolPolicy.java) 从父策略出发，依次与全局禁用、后台白名单、角色黑白名单取交集，并以 `absolute` 策略覆盖普通 system 工具特例。[AgentTurnCoordinator](../src/main/java/com/mewcode/agent/AgentTurnCoordinator.java) 用它过滤 Provider schema；[ToolExecutor](../src/main/java/com/mewcode/tool/ToolExecutor.java) 在执行前再次校验。

**证据与边界。** `ToolPolicyTest` 验证子 Agent 不允许 `Agent`、`TaskList`。2026-09-24 的真实 MewCode 请求日志中，主 Agent 可见 `Agent` 与 Task 工具，`explore` 子 Agent 只可见 `ReadFile/Glob/Grep`，Fork 子 Agent 也没有 Agent/Task 工具。这里没有声称完成了针对伪造调用的独立端到端攻击测试。

**简历候选句。** 为 SubAgent 建立与父任务、角色定义和运行状态相交的工具权限模型，在模型工具声明和本地执行入口双重校验，限制递归委派及任务管理越权。

## 面试补充：验收时运行的不是实现所在工作树

**实际故障。** 2026-09-24 在主目录运行旧 JAR，输入 `delegate explore` 得到“未知工具：Agent”；Provider 请求中没有 `Agent`。检查发现实现仍是 detached worktree 中的未提交改动，`feature/subagent` 当时只有规范文档。这是交付与构建来源问题，不是子 Agent 运行时缺陷。

**定位和处理。** 同时核对 `git worktree list`、两份目录的 `git status`、JAR 内类清单和 tmux 真实调用。把实现提交为 `865b7c0`，在目标分支执行 `git merge --ff-only 865b7c0`，从目标目录重新构建。相同对话随后返回 `CHILD_DONE`；Fork 异步与任务查询也通过。单独的“忘记合码”不建议写成简历亮点，但可在面试中说明如何用工作树、二进制和 Provider 请求三层证据排除误判。

## 面试补充：参考文档的 Agent 示例与本项目协议不一致

**实际问题。** 飞书[功能验证过程](https://my.feishu.cn/wiki/WK97wEjSVi8m2RkEJOhcxO2XnQb#share-HwDTdqjR7oMqRYxCt4sckC8Rnid)中的 `security-reviewer` 示例使用 `permissionMode: bypassPermissions`。本项目已批准的 [spec](ch13/spec.md) 仅支持 `default` 和 `dontAsk`。用真实解析器运行该字段得到“permissionMode 的值无效：bypassPermissions”；改为 `dontAsk` 后定义解析通过。原样复制示例会被 Catalog 跳过，因此看不到这个自定义 Agent。

**处理和边界。** 本项目示例应改用 `dontAsk`，保留危险命令、路径、Hook 和系统沙箱检查；不为外部示例扩大权限枚举。示例里的 `model: sonnet` 还要求配置可用的 Sonnet 路由，否则调用会明确失败。本次只验证了解析，没有声称完整运行 `security-reviewer` 审查流程。这个问题适合说明如何核对外部教程与项目契约，不宜单独写成简历成果。
