# MewCode 全仓代码精简 Tasks

> 状态：已完成（2026-09-06）
>
> 上游文档：[spec.md](./spec.md) · [plan.md](./plan.md)

## 执行规则

- 严格按 T0 至 T12 执行，A-F 每批独立验证。
- 每个实验只收敛一个兼容面或重复路径。
- 失败实验恢复该候选并写入实验日志，不放宽断言。
- 不新增生产依赖、生产类或仓库外兼容层。
- 不删除权限、路径、持久化、Provider、MCP 等不可信边界校验。

## T0：建立实验日志与基线

**文件：** `docs/code-pruning/experiment-log.md`

**步骤：**

1. 记录提交、Java 文件数与行数、测试数、JAR 字节数和构建耗时。
2. 记录目标公开入口及调用点。
3. 建立 A-F 的成功/失败实验表。

**验证：** 指标与 `spec.md`、`plan.md` 一致。

## T1：执行 A 批零调用消融

**文件：**

- `ConversationManager.java`
- `ToolRegistry.java`
- `AgentTurnCoordinator.java`
- `ToolRegistryTest.java`

**依赖：** T0

**步骤：**

1. 删除 `addToolResults`、`markDiscovered` 和零调用的 `toApiFormat` 别名。
2. 将 `toAPIFormate*` 统一为 `toApiFormat*`，迁移生产与测试调用。
3. 确认旧拼写和零调用声明完全消失。

**验证：**

```bash
./gradlew test --tests com.mewcode.conversation.ConversationManagerTest \
  --tests com.mewcode.tool.ToolRegistryTest
```

## T2：迁移 Provider 调用方和测试替身

**文件：**

- `src/test/java/com/mewcode/agent/*Test.java`
- `src/test/java/com/mewcode/tui/MewCodeModelTest.java`
- `src/test/java/com/mewcode/llm/{AnthropicClientTest,OpenAiClientTest}.java`
- `src/test/java/com/mewcode/testsupport/FakeLlmClient.java`

**依赖：** T1

**步骤：**

1. 所有 LLM 测试替身统一实现 `openStream(PromptRequest)`。
2. 旧列表、Conversation 和队列调用改为显式 `PromptRequest`。
3. 保留请求体、事件顺序、取消和错误断言。

**验证：** 相关测试先允许因生产旧接口尚未删除而通过。

## T3：执行 B 批 Provider 消融

**文件：**

- `LlmClient.java`
- `AnthropicClient.java`
- `OpenAiClient.java`
- `LlmClients.java`
- `ProviderRouter.java`
- `PromptRequest.java`
- `SystemPromptBundle.java`
- 对应生产调用方与测试

**依赖：** T2

**步骤：**

1. `LlmClient` 只保留 `openStream(PromptRequest)`。
2. 删除 Provider 的列表、Conversation、字符串 Prompt 和 `stream` 重载。
3. Provider 构造器、工厂和 Router 不再保存或传递默认 System Prompt。
4. 删除只服务旧入口的扁平化方法。

**验证：**

```bash
./gradlew test --tests 'com.mewcode.llm.*Test' \
  --tests com.mewcode.agent.AgentProtocolIntegrationTest \
  --tests com.mewcode.compact.ConversationCompactorTest \
  --tests com.mewcode.memory.MemoryManagerTest \
  --tests com.mewcode.session.SessionManagerTest \
  --tests com.mewcode.skill.ProviderRouterTest
```

## T4：建立正式 Agent 测试装配

**文件：**

- `src/test/java/com/mewcode/agent/*Test.java`
- 必要的 `src/test/java/com/mewcode/testsupport/*`

**依赖：** T3

**步骤：**

1. 测试统一创建结构化 Prompt、Context 和权限运行时。
2. 将 `start(...)` 测试迁移为 `startRun(...)`。
3. 共享重复 fixture，但不引入生产测试钩子。

**验证：** Agent 测试的行为断言和原测试用例数不减少。

## T5：执行 C 批 Agent 消融

**文件：** `AgentTurnCoordinator.java` 及对应测试

**依赖：** T4

**步骤：**

1. 删除阻塞队列入口、桥接线程及常量。
2. 删除字符串 Prompt、无 Context、无权限构造路径。
3. 仅保留正式构造入口，删除已无调用访问器。
4. 删除由旧构造组合产生的可选分支。

**验证：**

```bash
./gradlew test --tests 'com.mewcode.agent.*Test' \
  --tests com.mewcode.skill.SkillExecutorTest \
  --tests com.mewcode.tui.MewCodeModelTest
```

## T6：迁移工具执行测试

**文件：**

- `ToolExecutorTest.java`
- `PermissionToolExecutorTest.java`
- Agent 测试装配

**依赖：** T5

**步骤：**

1. 建立最小权限 fixture。
2. 所有工具执行测试改走 `ToolPolicy + PermissionContext`。
3. 保留未知工具、参数、并发、保序、重复 ID、超时、取消和异常用例。

**验证：** 新入口在删除旧算法前覆盖现有行为矩阵。

## T7：执行 D 批 ToolExecutor 消融

**文件：** `ToolExecutor.java`、`AgentTurnCoordinator.java` 及对应测试

**依赖：** T6

**步骤：**

1. 删除无权限 `executeSingle/executeBatch` 和重复调度辅助。
2. 构造时强制注入 `PermissionGate`。
3. Agent 始终创建并传入权限上下文。
4. 保留共享的超时、取消、结果 metadata 和保序逻辑。

**验证：**

```bash
./gradlew test --tests com.mewcode.tool.ToolExecutorTest \
  --tests com.mewcode.tool.PermissionToolExecutorTest \
  --tests 'com.mewcode.permission.*Test' \
  --tests com.mewcode.agent.AgentLoopTest
```

## T8：执行 E 批启动装配消融

**文件：**

- `MewCode.java`
- `MewCodeModel.java`
- `MewCodeModelTest.java`
- `MewCodeTest.java`

**依赖：** T7

**步骤：**

1. `MewCodeModel` 正式构造时直接接收已校验 Skill/MCP 启动对象。
2. 删除模型内部重复 Catalog 初始化和 `useSkillBootstrap`。
3. 收敛 9 个构造器为一个正式入口和至多一个包内测试入口。
4. 仅在净减少时合并重复运行状态初始化。

**验证：**

```bash
./gradlew test --tests com.mewcode.MewCodeTest \
  --tests com.mewcode.tui.MewCodeModelTest \
  --tests com.mewcode.command.CommandRegistryTest \
  --tests 'com.mewcode.skill.*Test' \
  --tests 'com.mewcode.mcp.*Test'
```

## T9：执行 F 批内部不变量消融

**文件：** A-E 修改后仍含重复兜底的内部模块

**依赖：** T8

**步骤：**

1. 重建 `null`、空集合复制、不可达异常和兼容注释清单。
2. 只删除唯一入口已经保证的不变量检查。
3. 优先收敛事件便捷构造器、usage 便捷入口和测试样板。
4. 保留所有不可信边界与恢复代码。

**验证：** 每个候选单独跑所属测试，并在实验日志写明保留或撤销。

## T10：静态验收与指标复核

**文件：** 全仓、`experiment-log.md`

**依赖：** T9

**步骤：**

1. 检索旧 API、旧拼写、兼容注释和重复构造器。
2. 统计生产/测试文件与行数、公开入口、测试数和 JAR。
3. 对照 AC1-AC9 判断是否达标。
4. 复核 `docs/ch2` 至 `docs/ch11` checklist 引用。

**验证：**

```bash
./gradlew clean build
git diff --check
```

## T11：tmux 端到端验收

**依赖：** T10

**步骤：**

1. 在 tmux 启动新构建的 MewCode。
2. 验证普通文件读取对话。
3. 验证 Plan Mode 拒绝写入且文件不变。
4. 验证 shared Skill 工具白名单。
5. 验证 fork 只回流摘要。
6. 验证取消后可继续对话。
7. 验证 `/clear` 后状态不泄漏。

**验证：** 将关键输入、输出、文件状态和进程退出状态写入 checklist。

## T12：完成实验报告

**文件：**

- `experiment-log.md`
- `checklist.md`

**依赖：** T11

**步骤：**

1. 汇总成功与失败消融实验。
2. 记录最终指标和净变化。
3. 列出保留的防御代码及其信任边界理由。
4. 对 AC1-AC11 给出直接证据。

**验证：** 所有 checklist 项有实际证据，无“预计通过”。
