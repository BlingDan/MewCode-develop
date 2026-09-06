# MewCode 全仓代码精简 Spec

> 状态：已确认（2026-09-06）
>
> 分支：`Optimize/less_code`
>
> 基线日期：2026-09-06

## 背景

MewCode 按章节持续加入 Agent Loop、权限、MCP、上下文、Memory、命令和 Skill。为了兼容早期实现与测试，代码中同时保留了多套构造器、请求接口、执行路径和空值兜底。它们扩大了公开 API、分支数量和测试样板，但不再承担独立产品能力。

本轮采用消融实验：每次只删除一组职责明确的候选，使用自动化测试、构建、静态指标和 tmux 真实对话比较删除前后行为。只有行为不退化且代码净减少的批次才保留。

## 当前基线

- 生产 Java：148 个文件，15,203 行。
- 测试 Java：73 个文件，9,853 行。
- 自动化测试：280 个，0 失败、0 跳过。
- 非增量命令 `./gradlew clean test spotlessCheck shadowJar`：13.83 秒。
- Shadow JAR：80,062,617 字节；该指标主要受 SDK 依赖影响，仅作观察，不设硬性降幅。
- Git 工作区干净；跟踪文件总大小约 1.86 MB，`build/`、`out/` 不属于仓库体积。

## 已确认的膨胀信号

1. 零调用代码：
   - `ConversationManager.addToolResults`
   - `ToolRegistry.markDiscovered`
   - `ToolRegistry.toApiFormat`
2. 仅测试仍使用的历史入口：
   - `AgentTurnCoordinator.start` 及阻塞队列桥接。
   - 多个旧式 `AgentTurnCoordinator` 构造器和字符串 Prompt 路径。
   - `LlmClient.stream(...)`、基于 `ConversationManager` 的 `openStream(...)` 等兼容层。
   - `StreamEvent`、`AgentEvent`、`TokenUsageAccumulator` 的旧式便捷入口。
3. 真实运行只有一条路径、实现却保留两套逻辑：
   - `ToolExecutor` 同时维护无权限和有权限的单次/批量执行流程；正式入口始终启用权限运行时。
   - Provider 已统一接收 `PromptRequest`，但真实客户端仍保留列表、会话、字符串 System Prompt 多种入口。
4. 重复装配：
   - `MewCode` 已完成 Skill/MCP 启动校验，`MewCodeModel` 构造时仍先创建并刷新另一份 Skill Catalog，再由入口覆盖。
   - `MewCodeModel` 和 `AgentTurnCoordinator` 的多数构造器只服务历史测试组合。
5. 防御代码混合了两类责任：
   - 配置、文件、Provider、MCP、脚本和模型输出属于不可信边界，校验必须保留。
   - 已校验对象在内部层层接受 `null`、重复复制或再次兜底，部分可以通过单一构造路径消除。

## 目标

- 删除无调用代码和仅为历史内部 API 保留的兼容层。
- 将 Provider 请求、Agent 运行、工具执行分别收敛到一条正式路径。
- 删除正式启动路径中的重复 Skill 初始化和测试专用构造分支。
- 精简重复测试装配，但保留全部独立行为、安全和故障案例。
- 不新增依赖，不为未来扩展预留新框架，不以迁移代码替代被删代码。
- 本轮生产 Java 净减少不少于 400 行，公开方法/构造器净减少不少于 20 个。

## 消融批次

### A：零调用与拼写兼容

- 删除静态检索确认零调用的方法。
- 将 `ToolRegistry` 的 API 格式方法统一为一个正确命名的入口，迁移测试后删除拼写错误和转发别名。
- 删除只验证旧便捷构造器的测试写法，改为显式构造当前完整事件。

保留条件：编译、相关单测和全量测试通过，生产代码净减少。

### B：Provider 单一请求协议

- `LlmClient` 只保留 `openStream(PromptRequest)`。
- Anthropic、OpenAI 和测试替身统一消费结构化请求。
- 删除 `stream(...)`、`ConversationManager` 参数、字符串 System Prompt 和默认桥接实现。
- Provider 构造器及 `ProviderRouter` 不再保存默认 System Prompt；Memory、标题、Agent 请求继续通过各自 `PromptRequest` 传入提示。
- 删除仅为旧字符串接口存在的扁平化方法；测试直接检查 `systemSegments`。

保留条件：两种 Provider 的请求体、工具、Reminder、Thinking、错误分类和凭据脱敏测试全部通过。

### C：Agent 单一运行路径

- 删除阻塞队列式 `start` 和桥接线程，统一使用 `AgentRun`。
- 删除字符串 Prompt 和无上下文的旧构造路径，`PromptRequestFactory` 成为唯一提示组装入口。
- 收敛 `AgentTurnCoordinator` 构造器；测试使用与正式运行相同的最小装配 fixture。
- 删除只供测试读取且没有行为职责的访问器。
- 保留 Context Manager 可选性的范围仅限确有独立单测价值的场景；若统一装配后可消除空分支，则继续消融。

保留条件：多轮工具调用、未知工具熔断、取消、自动/手动压缩、Provider 回退和 Skill 生命周期行为不变。

### D：工具执行单一路径

- 正式化权限运行时为工具执行的唯一入口。
- 删除无权限版 `executeSingle/executeBatch` 及其重复并发、超时、取消、重复 ID 处理。
- 测试通过 BYPASS/DEFAULT 权限 fixture 覆盖同一生产路径，不保留测试专用执行分支。
- 合并 Tool 参数校验接口时，以净减少为前提；若需要增加等量适配代码则放弃该候选。

保留条件：权限拒绝与确认、Plan Mode、路径越界、并发顺序、超时、取消和重复工具 ID 测试全部通过。

### E：启动与 TUI 装配

- 消除 `MewCode` 与 `MewCodeModel` 的重复 Skill 初始化。
- 收敛只服务测试排列组合的 `MewCodeModel` 构造器。
- 合并普通请求与 fork 请求中重复的流状态初始化，仅在能降低分支和总行数时保留。
- 不拆分类来制造更多文件，不进行与净精简无关的目录重构。

保留条件：单/多 Provider 启动、Skill 启动校验、MCP 后台初始化、fork、`/clear` 和 Session 切换行为不变。

### F：内部不变量与测试样板

- 只对来源已受控的内部对象移除重复 `null` 归一化、重复不可变复制和不可达异常分支。
- 配置文件、磁盘数据、模型响应、工具参数、MCP 响应和用户输入继续视为不可信。
- 合并重复测试 fixture，优先参数化；不得删除独立输入样本或只通过降低覆盖率换取行数。

保留条件：变异后的非法边界仍由上游校验拒绝，且现有故障恢复测试不减少。

## 明确保留

- 权限规则、危险命令拦截、路径边界、符号链接检查和系统 Shell 沙箱。
- 写文件前读取约束、原子写入、Memory 双层提交与失败回滚。
- Session JSONL 坏行恢复、过期清理和工具调用/结果配对。
- Provider 错误分类、凭据脱敏、取消、超时和资源关闭。
- 上下文预算、自动压缩熔断、紧急恢复和工具结果外置。
- Skill 三级覆盖、热更新快照、工具白名单、fork 隔离和 Provider 单次回退。
- 各章节 `checklist.md` 已声明的用户可观察行为。

## 实验规则

1. 每个批次开始前记录生产/测试行数、公开方法数量和测试基线。
2. 一个实验只删除一个兼容面或一条重复路径，不混入功能修改。
3. 先运行受影响测试，再运行 `./gradlew clean build` 和 `git diff --check`。
4. 批次必须同时满足：
   - 用户可观察行为不变；
   - 安全与故障恢复断言不减少；
   - 无新增依赖和生产类；
   - 生产代码及总代码均净减少；
   - 没有用更复杂的新抽象替代旧代码。
5. 任一条件失败即判定该消融不成立，恢复该候选，不为了指标放宽测试。
6. 每批记录删除内容、净行数、验证命令、失败实验及恢复原因。

## 验收标准

- AC1：所有零调用候选被删除，静态检索不再出现对应声明。
- AC2：Provider 只有一个结构化请求入口，正式代码不再包含“旧 Provider/旧调用方/旧测试客户端”兼容实现。
- AC3：Agent 正式运行只使用 `AgentRun` 和结构化 Prompt，不再存在旧阻塞队列桥接。
- AC4：ToolExecutor 不再维护无权限与有权限两套重复执行算法。
- AC5：正式启动过程中 Skill Catalog 只装配一次，不先创建再覆盖。
- AC6：生产 Java 净减少至少 400 行，公开方法/构造器净减少至少 20 个，且不新增生产依赖或生产类。
- AC7：280 个现有测试全部通过、无跳过；因接口收敛改写的测试必须保留原行为断言。
- AC8：`./gradlew clean build`、后续直接执行的 `./gradlew build` 和 `git diff --check` 通过。
- AC9：逐项复核 `docs/ch2` 至 `docs/ch11` 的 checklist，不删除仍被引用的验收证据。
- AC10：tmux 完成真实流程：普通对话读取文件、Plan Mode 拒绝写操作、Skill 加载后调用白名单工具、fork 返回摘要、取消运行后继续对话。
- AC11：最终报告列出每个成功/失败消融批次及前后指标，不只报告代码总行数。

## 非目标

- 不删除任何已交付功能或配置项。
- 不重写 Provider SDK、不改变模型协议、不为减小 76 MB Shadow JAR 替换依赖。
- 不把安全检查、数据恢复或错误脱敏视为冗余防御。
- 不删除历史需求文档；文档体积不是本轮生产复杂度目标。
- 不进行纯格式化、重命名风暴或跨包搬运。

## 前提与待确认

- 本项目按终端应用而非对外 Java SDK 处理，内部 Java API 不承诺二进制兼容；CLI、配置文件、Session/Memory 磁盘格式保持兼容。
- 本轮先按 A 至 F 的顺序推进，每批可以独立停止和回滚。
- 若存在仓库外部代码直接依赖 `LlmClient.stream(...)`、旧构造器或 `ToolRegistry.toAPIFormate(...)`，需在批准前说明，这些入口将从删除目标中移除。
