# 防御逻辑消融记录

## 方法与基线

逐模块追踪入口、数据构造和所有调用点；只有能够证明分支不可达或校验重复，才删除。每轮只修改一个模块，随后运行相关测试。保留公开入口的兼容语义、输入错误提示、取消、权限、沙箱、文件状态检查及持久化恢复。

修改前工作区干净。`./gradlew clean spotlessCheck test shadowJar --console=plain` 通过：181 个生产 Java 文件，23,733 行；98 个测试套件，427 项测试，0 失败、0 跳过；耗时 65 秒。行数含注释和空行，耗时仅作本机观测，不能作为性能结论。

本次证据目录：`/tmp/Mewcode-develop_defensive_20260929/`。保存基线 JAR、JUnit XML、逐轮日志及补充的行为对比程序。原始基线构建日志：`/tmp/mewcode-defensive-baseline.log`。

## 模块记录

### 01 配置

- 调用链：`MewCode.run → ConfigLoader.load → SnakeYAML Bean setter → ConfigLoader.validate`。
- 删除依据：`AppConfig` 和 `AgentConfig` 是 final 类，字段默认初始化，所有对应 setter 均将 null 恢复成默认对象或空列表；没有绕过 setter 的字段绑定。因此 `agent/loop/subagent/permissions/providers` 的后续 null 分支不可达。`"subagent"` 错误分支也没有抛出来源。
- 保留：YAML 节点类型校验、正数阈值、Provider 条目/必填字段/唯一性/协议/URL、权限模式和 worktree 安全路径校验，以及错误脱敏。
- [x] 配置测试通过：`test --tests 'com.mewcode.config.*'`，28 项，0 失败/跳过，日志 `01-config.log`。
- [x] `ConfigProbe.java` 的 12 组缺省、null、无效输入与基线返回结果一致，`config-before.txt` 与 `config-after.txt` 无差异。

### 02 Agent 与提示请求

- 调用链：`startRunWithCancellation → runLoop → skillAdditions → openAttempt → withHookReminders`；`PromptRequestFactory.create → mergeReminders/hookReminder`。
- 删除依据：入口已拒绝 null 用户文本；`skillAdditions` 始终创建 `PromptAdditions`；该 record 将 Optional 和 Hook 文本归一化，`ReminderBatch` 同样保证非空列表，因此私有助手函数不再重复处理 null。可注入的 supplier 返回 null 仍由 `skillAdditions` 处理，公开 `create` 的 additions 默认值也保留。
- `AgentLoopConfig.copy → 有参构造器 → validate` 已完成验证，删除构造协调器后的第二次 `validate`。
- `collectAttempt` 的两段 catch 完全同构，合并为 multi-catch，保持错误 Hook、原异常重抛和上层中断恢复。
- 保留：迭代/未知工具上限、取消检查、流提前结束、上下文压缩失败、工具结果配对、可选功能依赖检查。
- [x] `test --tests 'com.mewcode.agent.*' --tests 'com.mewcode.prompt.*' --tests 'com.mewcode.tui.MewCodeModelTest'`：94 项，0 失败/跳过，日志 `02-agent.log`。

### 03 LLM 协议适配

- 调用链：`PromptRequest → AnthropicClient.openStream → appendReminder`；`ToolUseBlock.arguments → 协议序列化助手`。
- 删除依据：`PromptRequest` 的历史和 Optional 已归一化且不可变；`ToolUseBlock.arguments` 已归一化为非空 Map。只删私有方法的重复 null 处理，旧列表式客户端入口仍接受 null。
- OpenAI 参数分片调用 `ToolCallAccumulator.start` 前的 `has` 检查冗余：`start` 自身在同步方法中使用 `putIfAbsent`，保留首次名称与已有 JSON 缓冲区。
- 保留：外部响应缺字段、参数解析失败、序列化异常、协议错误脱敏、HTTP 关闭与取消的 best-effort catch。
- [x] `test --tests 'com.mewcode.llm.*' --tests 'com.mewcode.agent.AgentProtocolIntegrationTest'`：23 项，0 失败/跳过，日志 `03-llm.log`。

### 04 工具注册与参数校验

- 调用链：`ToolExecutor → validateInput → ToolInput.requireString(path)`；`replaceSkillTools → List.copyOf → 冲突检查`。
- 删除依据：合法 path 意味着 input 已非空，EditFile 的后续重复 input 判空不可达；WriteFile 的 content 原来先按非空字符串校验，再为所有字符串清除错误，等价于一次类型判断；注册表的 `List.copyOf` 已拒绝 null 元素，后面的 null 元素分支不可达。
- 保留：null input 的原有错误、空/纯空白 content 支持、错误提示原文、重复工具冲突和原子替换；路径权限、文件读取状态、修改时间、二进制保护及所有写入错误分支不变。
- 没有删除 `ToolRegistry.getAll/deferredToolNames` 的缺失项检查：注册过程会调用扩展工具的 `name/shouldDefer`，异常可能发生在多步更新之间，不能仅凭 synchronized 假设两份容器总是一致。
- [x] 工具、权限及两个工作区集成套件：89 项，0 失败/跳过，日志 `04-tool.log`。命令含 `--tests 'com.mewcode.tool.*' --tests 'com.mewcode.permission.*' --tests 'com.mewcode.worktree.WorkspaceToolExecutionTest' --tests 'com.mewcode.worktree.WorktreePathIsolationTest'`。
- [x] `ToolValidationProbe.java` 比较 124 次参数校验及注册表 null 元素拒绝/原集合保留，`tool-before.txt` 与 `tool-after.txt` 完全一致。

### 05 SubAgent 运行

- 调用链：`AgentTurnCoordinator → ParentAgentSnapshot/SubAgentInvocation → SubAgentRuntime.execute → executeTask`。
- 删除依据：两个 record 的构造器已拒绝空请求、路由和父回合，并归一化 mode、subagentType；record 不可继承且字段不可变，因此 Fork 的空快照报错及任务回调中重复的父回合判空不可达。Fork 直接进入同一个任务执行入口。
- 保留：外部调用参数检查、未知 Agent/模型错误、权限策略、取消传播、前后台切换、可选 workspace/hook/dispatchContext 状态以及失败时资源释放和成果保留。
- [x] `test --tests 'com.mewcode.subagent.*'` 覆盖的 19 项通过，0 失败/跳过，日志 `05-subagent.log`。执行命令另含一个未匹配的 worktree 测试名；实际测试计数来自 JUnit XML，隔离流程由 `SubAgentRuntimeTest` 覆盖，最终仍运行全部 worktree 测试。

### 06 Memory 读取

- 调用链：`MemoryManager → MemoryStore.loadIndex/scanNotes`，与 `snapshot/stage/describeNotes → loadIndexUnlocked/scanNotesUnlocked`。
- 删除依据：公开方法锁内实现与已有私有读取方法逐行同构；统一复用后，文件判定、排序、坏笔记隔离及 IOException 传播不变。公开笔记列表仍通过 `List.copyOf` 返回不可变快照。
- 保留：锁、符号链接处理、路径边界、提交前校验、原子写入降级、备份/回滚、后台任务取消和可变 client 的状态检查；会话切换、历史损坏恢复也未删除。
- [x] `test --tests 'com.mewcode.memory.*' --tests 'com.mewcode.session.*'`：24 项，0 失败/跳过，日志 `06-memory.log`。
- [x] `MemoryReadProbe.java` 的 7 组缺失目录、文件型目录、空目录、索引、混合坏笔记、符号链接、非法 UTF-8 输入与基线一致；同时检查排序和列表不可变，`memory-before.txt` 与 `memory-after.txt` 无差异。

### 07 Hook 分派

- 调用链：`HookEngine.dispatch → schedule → FutureTask/run`。
- 删除依据：私有 `schedule` 只有一个调用点，旧代码在返回 true/false 时都无条件 continue；改为 void，删除无效分支及返回值，内部执行顺序不变。
- 保留：onlyOnce 占位与释放、会话关闭复查、异步取消、工作树占用、提交拒绝处理、诊断和运行失败隔离；FutureTask 的 run/done 清理承担不同取消时序，未合并。
- [x] `test --tests 'com.mewcode.hook.*' --tests 'com.mewcode.agent.AgentTurnCoordinatorHookTest' --tests 'com.mewcode.worktree.WorktreeHookLifecycleTest'`：19 项，0 失败/跳过，日志 `07-hook.log`。

### 08 Skill / Agent 目录

- 调用链：`load/refreshInternal → addCandidate → selectWinners`；Skill 热更新冲突处理在移除最后一个版本时同步移除 key。
- 删除依据：候选 Map 只在方法内创建，唯一增加入口立即添加一个版本；Agent 无删除入口，Skill 删除后不保留空列表，因此取获胜版本时的空列表分支不可达。
- 保留：定义解析、名称冲突、未知工具、覆盖优先级、无效高优先级版本回退、外部目录读取失败诊断。
- [x] Skill、definition、AgentCatalog 和 AgentDefinitionParser 测试：19 项，0 失败/跳过，日志 `08-catalog.log`，包含覆盖与热刷新回退用例。

## 全项目检查范围

| 模块 | 检查重点 | 状态 |
| --- | --- | --- |
| 启动、config | 配置绑定、默认值、启动异常 | 配置已验证；启动错误处理保留 |
| agent、prompt | 请求构造、内部快照、回合结束 | 已消融并验证 |
| llm | 请求归一化、序列化、流关闭 | 已消融并验证 |
| tool | 注册表、参数校验、执行和结果收口 | 已消融并验证 |
| permission | 权限判定、规则、沙箱、授权持久化 | 已复核，保留安全边界 |
| conversation、compact | 原子回合、摘要校验、外置结果 | 已复核，保留原子回合、工具配对和摘要完整性检查 |
| session、memory | 会话切换、持久化、恢复、后台更新 | 读取已合并并验证；保留写入/恢复保护 |
| skill、subagent | 解析、目录覆盖、Fork、任务生命周期 | 已消融并验证 |
| hook、mcp | 扩展输入、失败隔离、异步取消 | Hook 已消融并验证；MCP 外部输入/断连边界保留 |
| worktree | 资源归属、占用、路径隔离、成果保留 | 已复核，保留边界与故障恢复 |
| command、tui、instructions、definition | 输入解析、显示、终端生命周期 | 已复核，保留输入与终端恢复处理 |

重要保留依据：

- 工具的参数验证与执行阶段路径/权限复查之间，文件和授权状态可能变化；不能因为校验文本相似就删除执行阶段检查。
- worktree 的锁前/锁内就绪检查、占用计数和删除前成果复查覆盖不同并发时序；删除失败时保留工作树，避免丢失成果。
- 会话恢复先 prepare 再 commit，历史坏行恢复和原子工具回合保护当前会话与工具配对；压缩须确认流完整结束、摘要结构完整后才替换历史。
- MCP、Skill、Hook 和 instructions 均接收外部输入；解析失败隔离、包含路径限制以及 TUI 终端恢复不能由内部非空契约替代。

## 最终验证结果

| 指标 | 修改前 | 修改后 |
| --- | --- | --- |
| 生产 Java 文件 | 181 | 181 |
| 生产代码行数（含注释、空行） | 23,733 | 23,672 |
| 测试套件 / 测试数 | 98 / 427 | 98 / 427 |
| 失败 / 跳过 | 0 / 0 | 0 / 0 |

共修改 13 个生产 Java 文件，新增 49 行、删除 110 行，净减 61 行。测试源码未修改；前后测试用例名称集合完全一致。最终 `./gradlew clean spotlessCheck test shadowJar --console=plain` 通过，日志 `10-final.log`，完整 JUnit XML 在 `final-tests/`；构建已有的弃用与 unchecked 提示仍存在。

tmux 验收使用最终 `build/libs/mewcode.jar`、JDK 21 和本地已配置的 `deepseek-v4-flash`（OpenAI 兼容协议）。在独立临时项目中复制 README 和 Provider 配置，隔离用户目录与会话；请求 ReadFile 读取 README 并回答语言、JDK 与启动命令。观察到真实 ReadFile 调用、工具结果、最终回复和回到 Ready；本次对话显示耗时 3.4 秒。退出码 0，README SHA-256 与原文件一致，临时 Provider 配置已删除，tmux 会话已关闭。证据为 `e2e-tmux.txt`、`e2e-conversation.jsonl`、`e2e-result.json`。

验证范围：真实网络验收覆盖一个 OpenAI 兼容 Provider 的只读对话；其他协议、故障、取消、权限、持久化及子任务路径依靠现有测试和调用链核对。本次结果证明已验证场景下行为一致，不作为所有外部故障组合的穷尽证明，也不据构建耗时推断性能收益。

bits-code-guard 自检按请求链、工具/存储、扩展/子任务分为 3 组独立评审，再单独执行跨组校验。覆盖全部 13 个修改的 Java 文件、159 行增删变更，未发现本次引入的 P0–P2 缺陷；`comments.jsonl` 为空，`final_comments.json` 为 `[]`。分组证据在 `group/`，跨组证据在 `cross_review.md`，最终报告：[HTML](file:///tmp/Mewcode-develop_defensive_20260929/report.html) / [Markdown](file:///tmp/Mewcode-develop_defensive_20260929/report.md)。`git diff --check` 通过，生产代码在全量测试后未再改动。

## 最终验收

- [x] 每个修改模块均有调用链、删除依据和相关测试结果。
- [x] 全量 `spotlessCheck test shadowJar` 通过，并核对测试数和跳过数。
- [x] tmux 中使用真实 Provider 发起对话，观察工具调用与最终回复。
- [x] 对本次变更执行 bits-code-guard 自检。
- [x] 汇总生产代码行数变化、保留项和验证限制。
