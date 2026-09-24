# MewCode SubAgent 技术方案

> 状态：已确认
>
> 对应需求：[spec.md](./spec.md)
>
> 参考：[理论学习：SubAgent 子任务分发](https://my.feishu.cn/wiki/XUM0wDbGzieCykkMdMQcWVXlnQe)、[SubAgent 模块实现参考](https://my.feishu.cn/wiki/D7opwF6zciCQOKkx0Fdc4F9hnnc)

## 1. 设计原则

本功能沿现有 Agent Loop 增量扩展，不创建第二套循环、权限系统、Hook 引擎或工具注册机制。设计遵守以下边界：

- `Agent` 是主 Agent 唯一的子任务委派入口，schema 固定，不随角色定义变化。
- 定义式和 Fork 式只在上下文构造、模型选择和默认前后台行为上分流，之后共用同一运行路径。
- 每个子 Agent 隔离可变运行状态，共享只读或线程安全的基础设施。
- 所有子 Agent 从启动起都由同一个进程内任务管理器托管，前台转后台只发布现有任务，不接管或重启执行实例。
- 工具能力从父 Agent 当前能力逐层收窄，模型可见 schema 和实际执行入口使用同一策略。
- 后台结果只通过 `<task-notification>` 进入主历史，不把子 Agent 内部过程复制回来。
- 本期不引入 Worktree、Agent Team、任务持久化、插件生命周期或热重载。

## 2. 总体架构

### 2.1 模块

#### AgentCatalog

负责 Markdown 定义解析、四级来源加载、同名覆盖、内置角色和提示摘要。它只保存不可变定义快照，不参与 Agent 执行。

#### SubAgentRuntime

是定义式与 Fork 式的统一运行入口。它接收一次调用和父运行快照，创建隔离的子运行状态，复用现有 `AgentTurnCoordinator`，最后返回前台结果或后台任务 ID。

#### SubAgentTaskManager

负责进程内任务状态、取消句柄、结果、用量和待注入通知。它同时托管尚未发布的前台子任务，避免前台转后台时转移事件消费者。

#### ToolPolicy

继续作为模型 schema 和本地执行共同使用的能力边界。现有类增加子 Agent 工厂，不另建一套策略类。

#### AgentTurnCoordinator

继续拥有唯一的 RunToCompletion 循环。它在工具分发 seam 截获 `Agent` 调用，因为只有该层持有父请求、当前 assistant blocks、实际 Provider route 和父 `AgentRun`。

#### MewCodeModel

只承担 TUI adapter 职责：处理 `Ctrl+B`、轮询后台通知、在安全时机注入主历史，以及在 Session 生命周期变化时取消任务。

### 2.2 主数据流

```text
主模型返回 Agent tool_use
        │
        ▼
AgentTurnCoordinator 捕获父运行快照
        │
        ▼
SubAgentRuntime 选择 definition / fork
        │
        ├── 创建隔离 Conversation / Permission / File cache / Hook state
        ├── 计算子 Agent ToolPolicy
        └── 创建现有 AgentTurnCoordinator
        │
        ▼
SubAgentTaskManager.start（唯一 virtual thread 消费事件）
        │
        ├── 前台完成 ──→ Agent 工具返回最终文本
        └── 发布后台 ──→ Agent 工具返回 async_launched + task ID
                              │
                              ▼
                        任务进入唯一终态
                              │
                              ▼
                    <task-notification> 排队
                              │
                              ▼
                 主 Agent 空闲时注入 Conversation
```

## 3. 核心类型

### 3.1 SubAgentSpec

定义加载结果和运行配置使用同一个不可变 record：

```java
record SubAgentSpec(
    String name,
    String description,
    Set<String> tools,
    Set<String> disallowedTools,
    String systemPrompt,
    int maxTurns,
    String model,
    SubAgentPermissionMode permissionMode,
    AgentSource source,
    Path sourcePath
) {}
```

- `tools == null` 表示没有定义白名单；空集合表示明确不允许任何工具。
- `disallowedTools` 总是不可变集合。
- `model` 在解析边界严格限制为 `inherit`、`haiku`、`sonnet`、`opus`。
- `permissionMode` 只支持 `default`、`dontAsk`。
- `source` 用于决定是否应用自定义 Agent 限制。
- `sourcePath` 只用于可操作的解析诊断。

内置定义为 `general-purpose`、`plan`、`explore`。`plan` 和 `explore` 同时使用提示词约束和工具黑名单限制写操作。

### 3.2 AgentCatalog

不增加单实现接口或工厂：

```java
final class AgentCatalog {
    static AgentCatalog load(
        Path projectRoot,
        Path userHome,
        List<Path> pluginDirs,
        int defaultMaxTurns
    );

    Optional<SubAgentSpec> find(String name);
    Collection<SubAgentSpec> list();
    String promptSummary();
}
```

Catalog 启动时构造一次不可变快照，不实现 watcher 或运行中刷新。

### 3.3 父运行快照

协调器在本轮模型响应完整后创建：

```java
record ParentAgentSnapshot(
    PromptRequest sentRequest,
    List<ContentBlock> assistantBlocks,
    ToolPolicy toolPolicy,
    ProviderRouter.Route route,
    AgentMode mode,
    AgentRun parentRun,
    String sessionId
) {}
```

`sentRequest` 必须是本轮最终成功发送的请求；若发生 Provider fallback，应记录 fallback 后的 route 和请求。

### 3.4 SubAgentTaskManager

任务管理器是具体类，内部使用 `LinkedHashMap` 保持稳定顺序，公开方法使用 `synchronized` 保护：

```java
final class SubAgentTaskManager implements AutoCloseable {
    TaskHandle start(TaskRequest request);
    Optional<String> publish(String sessionId, String taskId);
    List<TaskSnapshot> list(String sessionId);
    Optional<TaskSnapshot> get(String sessionId, String taskId);
    TaskSnapshot createManual(String sessionId, String subject, String description);
    UpdateResult update(String sessionId, TaskUpdate update);
    List<TaskNotification> drainNotifications(String sessionId);
    void cancelSession(String sessionId);
}
```

内部保留五种状态：

```text
PENDING → RUNNING → COMPLETED / FAILED / CANCELLED
```

任务终态、结果、结束时间和通知入队必须在同一同步临界区提交。`TaskEntry`、`TaskSnapshot`、`TaskHandle`、`TaskNotification` 作为管理器的嵌套类型，避免拆分无独立职责的小文件。

### 3.5 SubAgentRuntime

运行时只暴露一个入口：

```java
final class SubAgentRuntime {
    ToolResult execute(SubAgentInvocation invocation, ParentAgentSnapshot parent);
}
```

它直接返回模型可见 `ToolResult`：前台完成返回最终文本，进入后台返回 `async_launched` 和任务 ID，不额外引入单实现 outcome 层级。

## 4. Agent 定义加载

### 4.1 共享 Markdown frontmatter seam

从现有 `SkillParser` 提取只负责语法的 `MarkdownFrontmatter`：

```java
record MarkdownDocument(Map<String, Object> frontmatter, String body) {}
```

它统一完成：

- UTF-8 读取及换行规范化。
- 文件开头和结尾 `---` 检查。
- SnakeYAML `SafeConstructor`。
- YAML alias 和嵌套深度限制。
- YAML 对象与非空正文拆分。

Skill 和 Agent 继续各自校验允许字段和绑定业务类型，Skill 的外部格式与行为不变。

### 4.2 来源与覆盖

加载顺序固定为：

```text
外部注入插件目录
→ classpath 内置定义
→ ~/.mewcode/agents
→ <project>/.mewcode/agents
```

同级文件按文件名排序。每个合法定义加入候选集，后加载的合法版本获胜。无效文件只生成带路径诊断，不进入候选集，因此不会遮蔽低优先级合法版本。

当前 `MewCode.run` 向 Catalog 传入空插件目录；本期不发现、安装或管理插件。

未知工具名不作为 Markdown 解析错误。Catalog 在最终 ToolRegistry 可用后报告诊断，运行时按交集自然排除，避免尚未完成 MCP 发现时误删合法定义。

### 4.3 稳定 Agent schema

参考实现中的动态 `subagent_type` enum 不采用。`Agent` 工具固定包含：

```text
prompt              string，必填
description         string，必填
subagent_type       string，可选；缺省表示 Fork
model               inherit/haiku/sonnet/opus，可选
run_in_background   boolean，可选
```

可用角色名称和一句说明通过 `AgentCatalog.promptSummary()` 加入 `PromptAdditions`。定义变化不会重新注册工具或改变 schema 结构。

## 5. 工具分发 seam

`AgentTool` 采用与 `LoadSkillTool` 相同的声明模式：在 ToolRegistry 中提供固定 schema，若脱离协调器直接执行则返回安全错误。

`AgentTurnCoordinator` 在完成一轮响应、取得 `sentRequest` 后：

1. 分离 `Agent` 调用和普通工具调用。
2. 普通调用继续使用现有 `ToolExecutor`。
3. `Agent` 调用执行现有 PreToolUse Hook。
4. 创建 `ParentAgentSnapshot` 并调用 `SubAgentRuntime`。
5. 执行现有 PostToolUse Hook。
6. 由 `ToolResultAssembler` 按模型原始顺序提交完整工具回合。

多个前台 `Agent` 调用顺序执行；本期不为同一模型回合增加新的并发调度层。后台调用只执行启动步骤，会快速返回。

## 6. Definition-based 执行

### 6.1 上下文

定义式创建全新的 `ConversationManager`，只加入本次任务。定义正文作为稳定 system segment，不继承父历史、Memory 索引、恢复提醒或已激活 Skill；项目环境信息仍由现有 prompt 基础设施提供。

### 6.2 模型

模型按以下优先级解析：

```text
调用 model > 定义 model > 父 Agent route
```

`inherit` 等同于父 route。显式指定的别名找不到对应 Provider 时返回明确错误，不静默回退。

### 6.3 隔离状态

每次运行创建新的：

- `ConversationManager`
- `FileStateCache`
- `PermissionRuleEngine` 及 session grants
- `PermissionBroker`
- `HookSessionState`
- `CancellationToken`
- `ContextManager` 用量状态

共享：

- 已选择的 LLM client / ProviderRouter
- `HookEngine` 规则
- `ToolRegistry` 中的工具实现
- 项目根目录和文件系统
- `PermissionGate`、路径永久授权存储和 OS Bash sandbox

### 6.4 启动和等待

子协调器复用 `AgentTurnCoordinator`，但 `AgentLoopConfig.maxIterations` 使用定义的 `maxTurns`。任务启动后由唯一 virtual thread 消费 child `AgentRun` 事件。

定义式前台调用等待以下事件中的第一个：

- 子任务到达终态。
- `agent.subagent.auto_background_ms` 到期。
- 用户触发 `Ctrl+B`。

自然完成时从子 Conversation 的最后一条完整 assistant 消息取结果，不拼接跨轮流式文本。参考实现中的“60 秒无事件即失败”不采用；任务边界由 `maxTurns`、取消和真实异常控制。

## 7. Fork-based 执行

Fork 使用父 Agent 本轮实际发送成功的 `PromptRequest`，而不是重新读取可能已经变化或未经压缩的完整 Session：

1. 原样复制 `sentRequest.systemSegments()`。
2. 原样复制 `sentRequest.history()`。
3. 追加当前 assistant response 的全部 blocks。
4. 为所有未闭合 `tool_use` 追加配对结果：当前 Fork 调用标记为交给该工作进程，其他调用标记为仅由父 Agent 继续处理。
5. 追加不可协商的 Fork Boilerplate 和本次任务。
6. 使用父本轮最终成功的 route，忽略 `model` 覆盖。
7. 立即发布后台任务并返回 ID。

`PromptRequestFactory` 增加接收固定 system segments 的构造入口，使 Fork 后续轮次继续使用同一 system 前缀。

Fork 工具 schema 必须使用过滤后的策略，不能为了缓存命中继续暴露 `Agent` 或任务工具。因此系统只保证 system/history 前缀尽量稳定，不承诺 Provider 缓存命中。

## 8. 工具过滤

在现有 `ToolPolicy` 增加子 Agent 工厂：

```java
ToolPolicy.forSubAgent(
    ToolRegistry registry,
    ToolPolicy parent,
    SubAgentSpec spec,
    boolean background
)
```

计算顺序：

1. 枚举父 `ToolPolicy` 当前允许的注册工具。
2. 排除 `ALL_AGENT_DISALLOWED_TOOLS`，至少包含 `Agent`、`AskUserQuestion` 和全部任务工具。
3. 项目、用户、插件定义再排除 `CUSTOM_AGENT_DISALLOWED_TOOLS`。
4. 后台任务与 `ASYNC_AGENT_ALLOWED_TOOLS` 取交集。
5. 排除定义的 `disallowedTools`。
6. `tools` 非 `null` 时与其取交集。

最终集合不可变。`ToolPolicy.isAllowed` 不再无条件放行 system tool；子 Agent 策略拥有绝对限制模式。MCP 工具不设直通分支，和本地工具经过相同过滤。

子协调器每轮通过 supplier 读取任务是否已发布，并生成对应策略。前台转后台后已在执行中的调用不强制中断，下一次请求和下一次执行立即应用后台白名单。

## 9. 权限语义

### 9.1 dontAsk

映射到现有 `BYPASS_PERMISSIONS` 的普通操作默认决定，但继续经过：

- 全局工具过滤
- PreToolUse Hook
- 危险 Bash 命令黑名单
- Bash OS sandbox 可用性检查
- 项目路径边界
- 显式拒绝规则

### 9.2 default 前台

子 `PermissionRequested` 事件由任务消费者转发到父 `AgentRun`。父运行通过现有 `delegatePermissionsTo` 把 TUI 的响应交给子 broker。子 Agent 的 session grants 使用独立集合，不继承父临时批准。

### 9.3 default 后台

任务发布后台时：

- 移除父子权限委派。
- 将子 `PermissionBroker` 设为非交互。
- 当前等待及未来 `ASK` 均收口为 `DENY`。
- 不弹 TUI 审批，不自动切换为 `dontAsk`。

`PermissionBroker` 增加关闭状态检查，使关闭后新请求也直接拒绝，而不是再次发布。

Fork 固定使用独立 `dontAsk`。

## 10. 前后台转换

所有子 Agent 从启动起都有内部任务 ID 和唯一事件消费者。任务增加 `published` 状态：未发布表示前台等待，已发布表示进入后台并对任务工具可见。

`AgentRun` 增加一个原子后台化回调：

```java
Optional<String> requestBackground();
```

运行时只在父 Agent 正等待一个定义式前台子任务时安装回调，任务完成或发布后清除。

三条路径共用 `SubAgentTaskManager.publish`：

- `run_in_background=true`：启动后立即发布。
- 超过全局阈值：等待方发布同一任务。
- `Ctrl+B`：TUI 调用父 `AgentRun.requestBackground()`。

发布使用幂等状态转换。原 child run、virtual thread、事件消费者、Conversation 和 token 累计保持不变。

新增 `AgentEvent.SubAgentBackgrounded`，用于让 TUI 清除可能残留的权限提示并显示任务 ID。该事件不结束父 Agent Loop；父模型收到 `async_launched` 工具结果后继续本轮工作。

## 11. RunToCompletion 与任务终态

子协调器继续使用现有循环语义：

- 模型请求工具则执行并进入下一轮。
- 第一次获得无工具调用的完整响应即成功。
- 达到 `maxTurns`、取消或异常时停止。

任务消费者处理：

- `ToolUse` / `ToolResult`：更新最近活动、工具名和完成数量。
- `Usage`：更新独立输入、输出 token。
- `PermissionRequested`：只在未发布的 `default` 任务中转发。
- `Error`：记录安全类别与消息。
- `LoopComplete`：根据错误和取消状态提交唯一终态。

内部异常只记录日志；模型可见结果不包含堆栈、系统提示、完整工具参数、环境变量或凭据。

## 12. 任务工具

四个工具实现在一个 `TaskTools` 文件中，共享同一任务管理器：

| 工具 | 输入 | 结果 |
|---|---|---|
| `TaskList` | 无 | 当前 Session 已发布子任务和手工任务摘要 |
| `TaskGet` | `task_id` | 单项状态、时间、进度、结果和用量 |
| `TaskCreate` | `subject`、`description` | 创建 `PENDING` 手工任务，不启动 Agent |
| `TaskUpdate` | `task_id` 及至少一个更新字段 | 更新手工任务；对子 Agent 可请求取消 |

规则：

- 子任务成功和失败只能由实际运行线程提交。
- 运行中的子任务只接受取消更新，随后调用对应 `AgentRun.cancel()`。
- 手工任务允许合法的前向状态转换，但不产生 `<task-notification>`。
- 全部操作按当前 Session 过滤。
- 返回稳定的结构化 JSON 文本。
- 子 Agent 的全局工具过滤始终排除四个任务工具。

## 13. 通知注入

后台子任务进入终态时生成一次通知：

```xml
<task-notification>
  <task-id>agent-1</task-id>
  <status>completed</status>
  <summary>安全且有长度上限的结果</summary>
  <usage input-tokens="..." output-tokens="..." />
</task-notification>
```

- 所有动态文本执行 XML 转义。
- 摘要使用代码常量截断，不新增配置项。
- 通知不包含内部对话、角色提示、工具参数或异常栈。
- `TaskEntry` 的终态提交和通知入队原子完成。

`MewCodeModel` 增加独立任务轮询消息。主 Agent 仍在流式执行时只保留通知，不注入 Conversation，避免插入尚未闭合的工具回合；主 Agent 空闲后才调用 `ConversationManager.addUserMessage`，由现有 mutation listener 持久化。

TUI 可以显示一行后台完成提示，但不调用 `startAgentRequest`。下一次主模型请求自然读取该 user-role `<task-notification>`。

## 14. 配置与启动接线

### 14.1 配置

新增：

```yaml
agent:
  loop:
    max_iterations: 100
  subagent:
    auto_background_ms: 20000
```

`auto_background_ms` 默认 `20000`，必须为正整数。定义未填写 `maxTurns` 时使用 `agent.loop.max_iterations`。

### 14.2 启动顺序

1. 读取并校验 AppConfig。
2. 创建 `SubAgentTaskManager`。
3. 注册固定的 `Agent` 和四个任务工具。
4. 加载 `AgentCatalog`。
5. 按现有流程完成 Skill、脚本工具和必要的 MCP 初始化。
6. Provider 选定后创建 `SubAgentRuntime` 并配置主协调器。
7. 将 Agent Catalog 摘要加入 `PromptAdditions`。

Agent 定义不改变 ToolRegistry 的工具数量和 schema。

## 15. Session 与资源生命周期

- `/clear`：先取消旧 Session 全部任务、清空其待注入通知，再提交新 Session。
- `/resume`：先取消当前 Session 任务，再切换会话。
- Provider 重新初始化：取消仍引用旧 client/executor 的任务后再关闭资源。
- 正常退出：取消全部任务并有界收尾，再关闭 Hook、工具、MCP、Memory 和 Session。
- 任务完成时再次核对 Session ID；旧 Session 的迟到结果只收尾资源，不入新历史。
- 子 Agent 完成后关闭自己的 Hook state、ContextManager、ToolExecutor、PermissionBroker 和事件流。

任务只存在内存中，进程重启后不恢复。

## 16. 文件边界

### 16.1 新增

```text
src/main/java/com/mewcode/subagent/SubAgentSpec.java
src/main/java/com/mewcode/subagent/AgentDefinitionParser.java
src/main/java/com/mewcode/subagent/AgentCatalog.java
src/main/java/com/mewcode/subagent/SubAgentTaskManager.java
src/main/java/com/mewcode/subagent/SubAgentRuntime.java
src/main/java/com/mewcode/definition/MarkdownFrontmatter.java
src/main/java/com/mewcode/tool/impl/AgentTool.java
src/main/java/com/mewcode/tool/impl/TaskTools.java
src/main/java/com/mewcode/config/SubAgentConfig.java
src/main/resources/agents/builtin/general-purpose.md
src/main/resources/agents/builtin/plan.md
src/main/resources/agents/builtin/explore.md
```

### 16.2 修改

```text
AgentTurnCoordinator.java
AgentRun.java
AgentEvent.java
ToolPolicy.java
PromptRequestFactory.java
PromptAdditions.java
PermissionBroker.java
SkillParser.java
AgentConfig.java
ConfigLoader.java
MewCode.java
MewCodeModel.java
build.gradle.kts
```

若实现时发现某个嵌套类型需要被三个以上模块独立使用，再提取文件；不为预想扩展提前增加 interface、factory 或 DTO 层。

## 17. 验证方案

### 17.1 自动化

#### 定义与 Catalog

- 全部 frontmatter 字段、正文和默认值。
- 非法字段、枚举、非正数 `maxTurns`、空正文。
- 插件、内置、用户、项目覆盖及无效高优先级回退。
- `Agent` schema 在定义集合变化后保持不变。

#### 工具与权限

- 全局禁用、自定义禁用、后台白名单、定义黑名单、定义白名单的顺序。
- MCP 工具不直通。
- Provider schema 和本地执行使用同一策略。
- `dontAsk` 仍受危险命令、路径、Hook 和 OS sandbox 限制。
- 后台 `default` 当前及未来审批均直接拒绝。

#### Definition 与 Fork

- 定义式不包含父历史、Memory 或 Skill。
- 模型优先级和显式模型不可用错误。
- Fork 复制实际父请求前缀并修复所有未闭合工具调用。
- Fork 忽略模型覆盖、固定后台并立即返回 ID。
- 无工具响应、连续工具调用、最大轮次、异常和取消终态。

#### 任务和通知

- 并发任务状态、结果、用量互不串线。
- 显式、自动、手动三种发布方式不重启运行实例。
- 每项任务只有一个终态和一次通知。
- 手工任务不产生通知。
- TaskUpdate 取消真实 child run。
- XML 转义、摘要截断和安全错误。

#### TUI 与生命周期

- `Ctrl+B` 只在存在可后台化子任务时生效。
- 主流式期间通知排队，空闲后注入且不触发模型。
- `/clear`、`/resume`、Provider 重建和退出取消对应任务。
- 迟到通知不进入新 Session。
- 现有 Skill shared/fork、Plan、权限、Hook、压缩、Memory、MCP 和 Session 测试继续通过。

### 17.2 构建检查

```bash
./gradlew spotlessCheck test shadowJar
git diff --check
```

不新增依赖。Spotless 覆盖新增 Java 包和测试。

### 17.3 tmux 端到端

复用 Chapter 11 已验证的本地 OpenAI SSE 假 Provider，只控制模型输出；打包后的 MewCode、TUI、Agent Loop、工具、权限和任务管理使用真实实现。

场景至少覆盖：

1. 定义式只读 Agent 前台完成并返回结果。
2. Fork 立即返回任务 ID，主 Agent 继续工作，稍后只注入一次 `<task-notification>`。
3. 显式后台、默认 20 秒自动后台和 `Ctrl+B` 手动后台。
4. 后台后工具集收窄，执行实例、工具调用次数和 token 累计不重置。
5. `TaskList`、`TaskGet`、`TaskUpdate` 查询与取消。
6. `dontAsk` 下危险 Bash、越界路径或 Hook 拒绝仍生效。
7. `/clear` 后旧任务不能污染新 Session。
8. 现有 Skill shared/fork 行为保持兼容。

实际结果在后续 `checklist.md` 和验收记录中逐项填写；未获得证据的项目不得标记为通过。

## 18. 明确不实现

- Worktree、容器或目录级文件隔离。
- Agent Team、子 Agent 互发消息或嵌套创建。
- 后台任务持久化、恢复或跨 Session 查询。
- Agent 定义热重载和插件发现生命周期。
- 动态 Agent schema、动态工具表达式或任意模型名。
- `TaskStop`、任务 slash command 或独立任务页面。
- prompt cache 本地实现、命中统计或安全策略降级。
- 多子 Agent 同时修改同一文件时的自动冲突合并。
