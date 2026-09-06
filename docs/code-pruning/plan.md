# MewCode 全仓代码精简 Plan

> 状态：已确认（2026-09-06）
>
> 上游文档：[spec.md](./spec.md)
>
> 分支：`Optimize/less_code`

## 方案概览

本轮不做重写，也不按“看起来多余”批量删除。每个候选都作为独立消融实验：

```text
建立基线
  → 选定一个兼容面或重复路径
  → 迁移仍有价值的测试到正式入口
  → 删除候选
  → 专项测试
  → 全量测试 / 格式 / 打包
  → 比较行为与规模
  → 保留成功实验，撤销失败实验
```

消融顺序按依赖方向从外到内：

```text
零调用 API
  → Provider 请求协议
  → Agent 运行协议
  → 工具执行协议
  → 启动与 TUI 装配
  → 已验证内部对象的重复兜底
```

这样先删除确定无调用的叶子，再收敛上游入口。后续批次只能依赖前面已经通过门禁的结果，失败批次不会阻塞其他独立候选。

## 已复验基线

2026-09-06 在提交 `fb9dae3` 上执行：

- 生产 Java：148 个文件，15,203 行。
- 测试 Java：73 个文件，9,853 行。
- 测试：280 个，0 失败、0 错误、0 跳过。
- `./gradlew clean test spotlessCheck shadowJar`：成功，实际用时 14.19 秒。
- `build/libs/mewcode.jar`：80,062,617 字节。
- Git 跟踪文件总大小：1,859,416 字节。
- 生产源码公开可调用声明的文本基线约为 624；最终以批次删除清单复核方法和构造器净变化，不把 record 声明误算成手写 API。

当前最大生产文件：

| 文件 | 行数 | 主要膨胀信号 |
|---|---:|---|
| `MewCodeModel.java` | 1,575 | 9 个构造入口、重复 Skill 启动装配、可选运行时分支 |
| `AgentTurnCoordinator.java` | 940 | 8 个构造入口、旧 Prompt 路径、旧队列桥接、可选权限/上下文路径 |
| `ToolExecutor.java` | 456 | 权限与无权限两套单次/批量执行算法 |
| `AnthropicClient.java` | 412 | 结构化请求和旧列表/会话/字符串入口并存 |
| `OpenAiClient.java` | 401 | 结构化请求和旧列表/会话/字符串入口并存 |

## 实验记录

实现阶段新增 `docs/code-pruning/experiment-log.md`，每个候选记录：

| 字段 | 内容 |
|---|---|
| 编号 | 例如 A1、B2 |
| 假设 | 删除什么，以及为什么不影响正式行为 |
| 对照 | 删除前相关调用、测试和指标 |
| 变量 | 本次唯一被收敛的入口或分支 |
| 验证 | 专项命令、全量命令和用户可观察行为 |
| 结果 | 成功保留或失败撤销 |
| 指标 | 生产/测试净行数、公开入口净变化、测试数 |
| 备注 | 失败原因或保留该防线的依据 |

不把格式化引起的行数变化记为消融收益。删除测试只有在测试仅验证已删除兼容入口时成立，其承载的行为断言必须迁移到正式入口。

## A：零调用与名称兼容

### A1 删除零调用方法

涉及：

- `ConversationManager.addToolResults`
- `ToolRegistry.markDiscovered`
- `ToolRegistry.toApiFormat`

步骤：

1. 用生产与测试全局检索再次确认声明之外无调用。
2. 删除三个方法及只描述这些入口的注释。
3. 保留 `ConversationManager.addToolTurn` 的原子配对语义。
4. 保留 `ToolRegistry.findAndDiscover` 的查找失败不改变状态语义。

验证：

```bash
./gradlew test --tests com.mewcode.conversation.ConversationManagerTest \
  --tests com.mewcode.tool.ToolRegistryTest
```

### A2 修正工具格式方法名称

当前正式代码和测试使用拼写错误的 `toAPIFormate*`，正确拼写的别名反而零调用。直接删除别名不能真正收敛接口，因此：

1. 将正式入口统一为 `toApiFormat` 和 `toApiFormatForModel`。
2. 一次性迁移生产调用与测试。
3. 删除所有 `toAPIFormate*` 声明，不保留转发别名。
4. 工具顺序、Anthropic/OpenAI schema 和可见性过滤断言保持不变。

保留条件：方法总数减少，且没有为了兼容再增加同义入口。

## B：Provider 单一结构化协议

### 目标形态

```java
public interface LlmClient {
  CancellableLlmStream openStream(PromptRequest request);
}
```

### B1 收敛接口和测试替身

1. 将所有测试客户端改为实现 `openStream(PromptRequest)`。
2. 用共享测试替身承载“预置事件 + 捕获请求”，只在测试确有特殊并发行为时保留局部替身。
3. 将旧 `stream(...)` 测试改为消费 `CancellableLlmStream`，保留事件顺序、结束、取消和错误断言。
4. 删除 `LlmClient` 中基于 `ConversationManager`、消息列表、字符串 Prompt 和 `BlockingQueue` 的默认桥接。

### B2 收敛真实 Provider

涉及：

- `LlmClient`
- `AnthropicClient`
- `OpenAiClient`
- `LlmClients`
- `ProviderRouter`
- `SystemPromptBundle`
- `PromptRequest`

步骤：

1. Anthropic 和 OpenAI 仅公开 `openStream(PromptRequest)`。
2. 构造器不再保存默认 System Prompt；每次请求只读 `PromptRequest.systemSegments()`。
3. 删除列表、会话和字符串 Prompt 的重载及转发私有方法。
4. `LlmClients` 与 `ProviderRouter` 的工厂签名不再传入 System Prompt。
5. 删除只服务旧接口的 `flattenedSystemPrompt()`；TUI 展示之外若仍需扁平文本，改由明确拥有该职责的调用点处理，不能保留 Provider 兼容层。

专项验证：

```bash
./gradlew test \
  --tests com.mewcode.llm.AnthropicClientTest \
  --tests com.mewcode.llm.OpenAiClientTest \
  --tests com.mewcode.agent.AgentProtocolIntegrationTest \
  --tests com.mewcode.compact.ConversationCompactorTest \
  --tests com.mewcode.memory.MemoryManagerTest \
  --tests com.mewcode.session.SessionManagerTest \
  --tests com.mewcode.skill.ProviderRouterTest
```

重点保持：请求体分层、Reminder、Thinking、工具 schema、usage、取消、错误分类、单次回退和凭据脱敏。

## C：Agent 单一运行路径

### C1 删除旧队列入口

1. 将 `AgentTurnCoordinatorTest` 的三个 `start(...)` 调用迁移为 `startRun(...)`。
2. 直接从 `AgentRun.events()` 消费事件，保留顺序和完成断言。
3. 删除 `start`、`bridge`、`QUEUE_CAPACITY`、`LinkedBlockingQueue` 和相关导入。

### C2 收敛构造与 Prompt

目标是只保留正式完整构造路径；测试通过 fixture 使用同一装配，不再靠生产构造器表达测试排列组合。

1. 所有 Agent 测试改用结构化 `PromptRequestFactory`。
2. 删除 `systemPromptProvider`、`contextRequestFromLegacyPrompt` 和旧字符串 Prompt 构造器。
3. `PromptRequestFactory`、`ContextManager`、`PermissionGate`、`PermissionRuntime` 成为正式运行必需依赖。
4. 删除 `promptRequestFactory == null`、`contextManager == null`、`permissionGate == null` 对应的旧执行分支。
5. 用测试 fixture 提供临时 Context 目录和 BYPASS/DEFAULT 权限运行时；fixture 仅减少测试装配，不进入生产代码。
6. 检查 `conversation()`、`config()` 等访问器；只有生产或独立行为测试仍使用时保留。

### C3 保持运行语义

不得借构造收敛改变：

- 用户消息提交时机。
- 多轮 ReAct、最大轮次和未知工具熔断。
- Provider 回退、上下文压缩和 usage 记录。
- Skill shared/fork 生命周期。
- 取消后的历史原子性和 `LoopComplete` 唯一收口。

专项验证：

```bash
./gradlew test --tests 'com.mewcode.agent.*Test' \
  --tests com.mewcode.skill.SkillExecutorTest \
  --tests com.mewcode.tui.MewCodeModelTest
```

## D：ToolExecutor 单一权限路径

### 目标形态

正式工具执行统一为：

```text
ToolPolicy
  → PermissionGate
  → 参数校验
  → 超时/取消 Future
  → ToolResult
```

### D1 迁移测试

1. 为工具测试建立最小权限 fixture，使用可预测的 `BYPASS_PERMISSIONS` 或明确规则。
2. 原 `ToolExecutorTest` 的未知工具、参数错误、并发屏障、结果保序、重复 ID、超时、取消和异常用例改走权限入口。
3. `PermissionToolExecutorTest` 保留 DENY、ASK、会话授权、永久授权失败和 Plan Mode 用例。

### D2 删除重复算法

1. 删除无权限版 `executeSingle` 和 `executeBatch`。
2. 删除无权限版 `isSafe`、`duplicateAware` 及对应构造器。
3. `ToolExecutor` 构造时强制接收 `PermissionGate`。
4. `AgentTurnCoordinator` 删除权限是否存在的二选一，始终传入本次 `PermissionContext`。
5. 保留共享的 `awaitSingle`、`awaitBatchResult`、metadata、重复 ID、超时和取消逻辑。

专项验证：

```bash
./gradlew test \
  --tests com.mewcode.tool.ToolExecutorTest \
  --tests com.mewcode.tool.PermissionToolExecutorTest \
  --tests com.mewcode.permission.*Test \
  --tests com.mewcode.agent.AgentLoopTest
```

## E：启动与 TUI 装配

### E1 消除重复 Skill 初始化

当前 `MewCode` 已构建并校验 `SkillCatalog`、`ToolRegistry`、`McpManager`，但 `MewCodeModel` 构造时又加载和刷新一份 Catalog，随后由 `useSkillBootstrap` 覆盖。

目标：

1. `MewCodeModel` 的正式构造器直接接收已完成启动校验的 Catalog、Registry 和 MCP Manager。
2. 删除构造器中的默认 Catalog 加载/首次刷新。
3. 删除“先构造再覆盖”的 `useSkillBootstrap`。
4. 保留热刷新、动态命令替换和无关 MCP 后台连接。

测试不应通过生产代码保留第二条 Skill 启动路径；测试 fixture 负责构造最小 Catalog/Registry/Manager。

### E2 收敛 MewCodeModel 构造器

1. 统计 9 个构造器的生产与测试调用。
2. 保留一个正式构造器和至多一个包内测试辅助入口。
3. 测试中的默认参数由 fixture 提供，不再扩展生产构造器重载。
4. Provider 切换仍复用同一 Catalog、Registry、MCP Manager。

### E3 局部重复状态

只在代码净减少且测试更清楚时合并：

- 普通请求与 fork 请求的运行状态初始化。
- Provider 切换时的资源关闭与重建。
- `/clear`、Session resume 的请求态清理。

如果抽取方法导致更多状态参数或新增类，则该候选判失败，不保留。

专项验证：

```bash
./gradlew test \
  --tests com.mewcode.MewCodeTest \
  --tests com.mewcode.tui.MewCodeModelTest \
  --tests com.mewcode.command.CommandRegistryTest \
  --tests com.mewcode.skill.*Test \
  --tests com.mewcode.mcp.*Test
```

## F：内部不变量与测试样板

这一批不按 `null`、`catch` 或 `requireNonNull` 数量机械删除。先标记数据来源：

| 来源 | 策略 |
|---|---|
| 配置/YAML/JSONL/文件系统 | 保留严格校验和坏数据恢复 |
| Provider/MCP/脚本/工具参数 | 保留空值、协议错误、超时和脱敏处理 |
| 权限、路径、Shell | 保留 Fail-Closed 和重复执行防护 |
| 已由唯一构造器创建的内部对象 | 可删除下游重复归一化 |
| 测试主动传入的非法内部状态 | 若产品路径不可达，删除测试而非保留生产分支 |

执行方式：

1. 从 A-E 收敛后的调用图重新检索 `== null`、空集合回退、重复 `List.copyOf`、不可达异常和兼容注释。
2. 每次只选择一个内部不变量，例如“`PromptRequest` 永不为 null”。
3. 在唯一入口保留一次校验，下游删除重复兜底。
4. 对 Session、Memory、Context、MCP 和权限包逐个审计；没有充分证据的检查默认保留。
5. 合并测试 fixture，但不删除输入边界、失败恢复或安全断言。

以下内容不属于 F 的删除目标：

- `HistoryStore` 坏行、半行和孤立工具调用恢复。
- `MemoryStore` 原子提交、回滚、路径和类型校验。
- `ContextManager` 超限恢复、熔断和外置失败处理。
- `McpManager` 外部协议空值、版本、分页和关闭隔离。
- `PermissionGate`、路径沙箱、危险命令和 Bash OS 沙箱。

## 每批统一门禁

每个 A-F 批次依次执行：

1. 静态检索确认旧声明和调用均消失。
2. 运行该批专项测试。
3. 运行：

```bash
./gradlew clean build
git diff --check
```

4. 汇总测试 XML，确认失败、错误和跳过均为 0。
5. 重新统计生产/测试 Java 行数、生产文件数、公开入口和 JAR 大小。
6. 对照 `docs/ch2` 至 `docs/ch11` 的 checklist 搜索被删 API 所承载的行为。
7. 更新 experiment log 后才进入下一批。

批次失败条件：

- 任一用户可观察行为或既有安全断言退化。
- 需要新增生产依赖或生产类才能弥补删除。
- 生产代码或生产加测试总代码没有净减少。
- 仅把重复逻辑搬到新抽象，分支和概念数没有下降。
- 只能靠降低测试覆盖、放宽断言或增加重试通过。

失败实验只撤销该候选，不撤销同批其他已独立验证的候选。

## 最终回归与 tmux 验收

自动化门禁完成后构建 JAR，并在 tmux 使用真实 MewCode 进程验证：

1. 普通请求要求读取仓库文件并总结，确认工具调用、结果回灌和最终回复。
2. `/plan` 后要求修改文件，确认写工具不可见或被本地拒绝，文件不变。
3. 执行一个 shared Skill，确认 SOP 生效且只提供白名单工具。
4. 执行 fork Skill，确认主界面等待且只回流摘要。
5. 流式中取消，确认停止后可以继续发送普通请求。
6. `/clear` 后继续对话，确认 Session、Skill 和 Provider 状态没有泄漏。

验收后在 `checklist.md` 记录 tmux 会话名、输入、关键输出、文件状态和退出状态；不记录 API Key。

## 预期结果

- Provider、Agent、ToolExecutor 各只保留一条正式协议。
- 启动期 Catalog/Registry/MCP 只装配一次。
- 删除至少 20 个公开方法或构造器。
- 生产 Java 净减少至少 400 行，生产加测试总代码也净减少。
- 不新增生产依赖和生产类。
- 280 个既有测试所表达的独立行为全部保留；测试总数可因参数化或删除纯兼容入口测试而变化，但行为矩阵不得缩小。
- 最终报告同时列出成功实验、失败实验、保留的防御代码及其边界理由。

## 实施边界

- 本仓库按终端应用处理，不维护内部 Java API 的仓库外兼容。
- CLI、配置格式、Session/Memory 磁盘格式和 Provider 协议保持兼容。
- 不清理历史需求文档、IDE 本地目录或 Gradle 缓存来制造代码精简数字。
- 不提交真实配置、凭据、Session、Memory、构建产物或 tmux 捕获中的敏感内容。
- 不在本计划确认前修改 Java 代码。
