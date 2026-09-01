# MewCode Slash Command Tasks

> 状态：已确认
>
> 按 [spec.md](./spec.md) 与 [plan.md](./plan.md) 执行。四份文档全部批准前不得开始实现。

## 文件清单

| 操作 | 文件 | 职责 |
|---|---|---|
| 新建 | `src/main/java/com/mewcode/command/Command.java` | 命令元数据与类型 |
| 新建 | `src/main/java/com/mewcode/command/CommandContext.java` | 函数式运行时能力与嵌套 UIController |
| 新建 | `src/main/java/com/mewcode/command/CommandRegistry.java` | 注册、冲突检测、解析、补全和内置 handler |
| 新建 | `src/main/java/com/mewcode/permission/PermissionRuntime.java` | 运行期模式、规则和快照 |
| 修改 | `src/main/java/com/mewcode/permission/PermissionRuleEngine.java` | 规则快照共享 Session 授权 |
| 修改 | `src/main/java/com/mewcode/session/SessionManager.java` | 安全开启新 Session |
| 修改 | `src/main/java/com/mewcode/memory/MemoryManager.java` | Memory 概要、手动添加和清理 |
| 修改 | `src/main/java/com/mewcode/compact/ContextManager.java` | 当前 Token 估算与带重点压缩 |
| 修改 | `src/main/java/com/mewcode/compact/ConversationCompactor.java` | 摘要保留重点 |
| 修改 | `src/main/java/com/mewcode/agent/AgentTurnCoordinator.java` | 手动压缩参数与权限快照 |
| 修改 | `src/main/java/com/mewcode/tui/tea/Command.java` | ClearScreen 效果 |
| 修改 | `src/main/java/com/mewcode/tui/tea/Program.java` | 执行终端清屏 |
| 修改 | `src/main/java/com/mewcode/tui/MewCodeModel.java` | 命令分流、UIController、菜单、确认和状态 |
| 修改 | `src/main/java/com/mewcode/MewCode.java` | 启动期注册和冲突退出 |
| 修改 | `build.gradle.kts` | 新包与测试的格式化范围 |
| 新建 | `src/test/java/com/mewcode/command/CommandRegistryTest.java` | 注册、解析、帮助、别名和补全测试 |
| 新建 | `src/test/java/com/mewcode/permission/PermissionRuntimeTest.java` | 运行期权限测试 |
| 新建 | `src/test/java/com/mewcode/memory/MemoryManagerTest.java` | Memory 命令能力测试 |
| 修改 | `src/test/java/com/mewcode/permission/PermissionRuleEngineTest.java` | 临时规则快照测试 |
| 修改 | `src/test/java/com/mewcode/session/SessionManagerTest.java` | 新 Session 测试 |
| 修改 | `src/test/java/com/mewcode/compact/ContextManagerTest.java` | 5000 Token 阈值测试 |
| 修改 | `src/test/java/com/mewcode/compact/ConversationCompactorTest.java` | 保留重点测试 |
| 修改 | `src/test/java/com/mewcode/agent/AgentTurnCoordinatorTest.java` | 权限与压缩集成测试 |
| 修改 | `src/test/java/com/mewcode/tui/MewCodeModelTest.java` | TUI 命令端到端单测 |

## T1：建立命令核心类型

**文件：**

- `src/main/java/com/mewcode/command/Command.java`
- `src/main/java/com/mewcode/command/CommandContext.java`
- `build.gradle.kts`

**依赖：** 无

**步骤：**

1. 定义 `Command` record 和 `LOCAL`、`LOCAL_UI`、`PROMPT` 三种类型。
2. 在构造阶段复制别名列表，并校验名称、描述、用法和类型非空。
3. 定义 `CommandContext` 的函数式能力字段。
4. 在 `CommandContext` 中定义 `UIController` 嵌套接口及已批准的方法。
5. 将 `command` 主代码和测试路径加入 Spotless 范围。

**验证：** 运行 `./gradlew compileJava`，期望新增类型编译通过。

## T2：实现注册、查找、解析和补全

**文件：**

- `src/main/java/com/mewcode/command/CommandRegistry.java`
- `src/test/java/com/mewcode/command/CommandRegistryTest.java`

**依赖：** T1

**步骤：**

1. 使用正式名称、别名和 handler 三个 Map 保存稳定注册状态。
2. 使用 `Locale.ROOT` 归一化标识。
3. 在写入前检测命令名—命令名、命令名—别名、别名—别名和大小写冲突。
4. 实现名称优先、别名其次的 `find`。
5. 实现首字符检查、首个空格拆分和原始参数保留。
6. 实现隐藏过滤、前缀搜索、登记顺序和同命令去重。
7. 测试所有冲突组合、大小写、未知命令、隐藏命令和别名前缀。

**验证：** 运行 `./gradlew test --tests com.mewcode.command.CommandRegistryTest`，期望注册与解析测试通过。

## T3：注册元数据、帮助和 Review Prompt

**文件：**

- `src/main/java/com/mewcode/command/CommandRegistry.java`
- `src/test/java/com/mewcode/command/CommandRegistryTest.java`

**依赖：** T2

**步骤：**

1. 按 spec 的固定顺序登记 9 个正式名称、别名、描述、用法、参数提示和类型。
2. 实现 `/help` 列表和按名称、别名查看详情。
3. 实现 `/review` 固定 Prompt，并原样追加可选关注点。
4. 验证 `/do`、`/d`、`/exit`、`/sessions`、`/resume` 未注册。
5. 验证全部别名与帮助输出来自相同元数据。

**验证：** 运行 `./gradlew test --tests com.mewcode.command.CommandRegistryTest`，期望 9 个内置定义和 Prompt 测试通过。

## T4：实现其余内置 handler 的参数编排

**文件：**

- `src/main/java/com/mewcode/command/CommandRegistry.java`
- `src/test/java/com/mewcode/command/CommandRegistryTest.java`

**依赖：** T3

**步骤：**

1. `/compact` 把完整参数传给压缩能力。
2. `/clear`、`/plan` 只通过 `UIController` 改变界面状态。
3. `/session` 精确解析空参数、`list` 和 `resume <id>`。
4. `/memory` 精确解析概要、`list`、`add <type> <content>` 和 `clear` 确认。
5. `/permission` 精确解析概要、`rules`、`mode`、`add` 和 `reset`；`add` 从参数尾部取 `allow|deny`，保留规则内部空格。
6. `/status` 调用惰性状态 Supplier。
7. 为缺失参数、空内容、未知子命令和非法效果验证对应 usage。

**验证：** 运行 `./gradlew test --tests com.mewcode.command.CommandRegistryTest`，期望所有 handler 编排测试通过。

## T5：实现运行期权限状态

**文件：**

- `src/main/java/com/mewcode/permission/PermissionRuntime.java`
- `src/main/java/com/mewcode/permission/PermissionRuleEngine.java`
- `src/test/java/com/mewcode/permission/PermissionRuntimeTest.java`
- `src/test/java/com/mewcode/permission/PermissionRuleEngineTest.java`

**依赖：** 无

**步骤：**

1. 保存启动模式、当前模式、配置规则和临时规则。
2. 只允许命令切换到 `default`、`acceptEdits`、`bypassPermissions`。
3. 临时规则使用 `SESSION` 来源并排在配置规则之前。
4. `reset` 清空临时规则并恢复启动模式。
5. 为每次 Agent Run 生成不可变模式和规则快照。
6. 让快照规则引擎共享既有 Session 授权集合，保持跨 Run 授权。
7. 测试优先级、非法输入、reset、快照隔离和 Session 授权。

**验证：** 运行 `./gradlew test --tests 'com.mewcode.permission.*'`，期望权限测试全部通过。

## T6：支持安全开启新 Session

**文件：**

- `src/main/java/com/mewcode/session/SessionManager.java`
- `src/test/java/com/mewcode/session/SessionManagerTest.java`

**依赖：** 无

**步骤：**

1. 增加 `startNewSession()` 和返回新 ID、目录的结果类型。
2. 先创建新目录和 Store，成功后再切换当前状态。
3. 静默清空共享 Conversation，重置标题与恢复提醒。
4. 关闭旧 Store但保留旧目录和历史。
5. 测试新旧 ID、空历史、旧会话仍可列表和恢复，以及创建失败时状态不变。

**验证：** 运行 `./gradlew test --tests com.mewcode.session.SessionManagerTest`，期望 Session 生命周期测试通过。

## T7：实现 Memory 命令能力

**文件：**

- `src/main/java/com/mewcode/memory/MemoryManager.java`
- `src/test/java/com/mewcode/memory/MemoryManagerTest.java`

**依赖：** 无

**步骤：**

1. 增加包含 user/project 不可变笔记列表的概要结果。
2. 实现四种类别到现有 Memory 级别的映射。
3. 使用首个非空行生成标题，使用 `manual_<时间戳>_<短随机串>` 生成 slug。
4. 复用 `MemoryOperation`、`stage`、`commit` 完成手动添加。
5. 在 `updateLock` 内实现两级快照、清空提交和失败回滚。
6. 测试概要数量、四类落点、内容保真、非法输入、全部清理和回滚。

**验证：** 运行 `./gradlew test --tests com.mewcode.memory.MemoryManagerTest`，期望 Memory 命令测试通过。

## T8：扩展带重点的手动压缩

**文件：**

- `src/main/java/com/mewcode/compact/ContextManager.java`
- `src/main/java/com/mewcode/compact/ConversationCompactor.java`
- `src/test/java/com/mewcode/compact/ContextManagerTest.java`
- `src/test/java/com/mewcode/compact/ConversationCompactorTest.java`

**依赖：** 无

**步骤：**

1. 暴露当前手动压缩请求的 Token 估算入口。
2. 为 `forceCompact` 和 `compact` 增加可选保留重点，同时保留旧重载兼容测试和调用方。
3. 将非空重点作为摘要请求的独立指令段。
4. 确保重点不写入 Conversation，摘要请求仍无工具。
5. 验证估算值、空重点兼容、非空重点传递和 Provider usage 记录。

**验证：** 运行 `./gradlew test --tests 'com.mewcode.compact.*'`，期望上下文测试全部通过。

## T9：接入 Coordinator 的压缩和权限快照

**文件：**

- `src/main/java/com/mewcode/agent/AgentTurnCoordinator.java`
- `src/test/java/com/mewcode/agent/AgentTurnCoordinatorTest.java`

**依赖：** T5、T8

**步骤：**

1. 增加 `estimateManualCompactionTokens(mode)`。
2. 增加 `startManualCompaction(mode, focus)`，旧方法委托空重点重载。
3. 构造器接收 `PermissionRuntime`，并保留现有固定权限构造器兼容性。
4. `startRun` 时取得一次权限快照，后续所有轮次复用。
5. 测试压缩重点、阈值估算、运行中快照稳定和下一 Run 读取新权限。

**验证：** 运行 `./gradlew test --tests com.mewcode.agent.AgentTurnCoordinatorTest`，期望协调器测试通过。

## T10：增加终端 ClearScreen 效果

**文件：**

- `src/main/java/com/mewcode/tui/tea/Command.java`
- `src/main/java/com/mewcode/tui/tea/Program.java`

**依赖：** 无

**步骤：**

1. 在 sealed `Command` 中登记 `ClearScreen`。
2. 执行时清除当前 View、滚屏和缓存的最后视图。
3. 清屏后立即允许下一次 `renderView()` 完整重绘。
4. 保持退出时终端恢复和现有 `PrintLine` 行为不变。

**验证：** 运行 `./gradlew compileJava`，并在最终 tmux 验收中观察清屏效果。

## T11：在 MewCodeModel 中装配命令上下文

**文件：**

- `src/main/java/com/mewcode/tui/MewCodeModel.java`

**依赖：** T4–T10

**步骤：**

1. 实现 `CommandContext.UIController`。
2. 持有 `CommandRegistry`、`PermissionRuntime` 和当前 `ToolRegistry`。
3. 建立模型内 UI 效果队列，让接口方法不返回 Tea 类型。
4. 创建 `CommandContext`，连接 Session、Memory、权限、压缩、状态和 MCP 能力。
5. 实现固定状态文本和 Token/窗口百分比格式。
6. 保持现有 Provider 初始化失败、MCP 异步初始化和资源关闭语义。

**验证：** 运行 `./gradlew test --tests com.mewcode.tui.MewCodeModelTest`，期望模型现有基础测试仍通过。

## T12：替换提交入口的硬编码命令分支

**文件：**

- `src/main/java/com/mewcode/tui/MewCodeModel.java`
- `src/test/java/com/mewcode/tui/MewCodeModelTest.java`

**依赖：** T11

**步骤：**

1. 把普通 Agent 提交提取为 `sendUserMessage` 的唯一实现。
2. 空白输入早返回；非斜杠输入直接走普通提交。
3. 斜杠输入只通过 Registry 解析和执行。
4. 按 LOCAL、LOCAL_UI、PROMPT 处理返回值。
5. 删除 `/exit`、`/plan`、`/do`、`/compact`、`/sessions`、`/resume` 的名称分支。
6. 测试普通输入、未知命令、大小写、别名、Prompt 历史和旧命令失效。

**验证：** 运行 `./gradlew test --tests com.mewcode.tui.MewCodeModelTest`，期望输入分流测试通过。

## T13：实现 Tab 补全菜单

**文件：**

- `src/main/java/com/mewcode/tui/MewCodeModel.java`
- `src/test/java/com/mewcode/tui/MewCodeModelTest.java`

**依赖：** T12

**步骤：**

1. 只在光标位于首个命令标识时处理 Tab。
2. 单候选替换成正式名称并追加空格。
3. 多候选保存列表和游标，并在输入框上方渲染菜单。
4. 实现 ↑/↓、Enter、Esc 和继续输入刷新。
5. 在提交、清屏、Streaming 和离开命令位置时关闭菜单。
6. 测试别名前缀、隐藏过滤、去重、方向键边界和非命令位置。

**验证：** 运行 `./gradlew test --tests com.mewcode.tui.MewCodeModelTest`，期望补全交互测试通过。

## T14：接入确认、新对话和模式切换

**文件：**

- `src/main/java/com/mewcode/tui/MewCodeModel.java`
- `src/test/java/com/mewcode/tui/MewCodeModelTest.java`

**依赖：** T13

**步骤：**

1. 实现一次性确认状态，`y` 执行、`n`/Esc 取消，其余按键不触发动作。
2. `/memory clear` 成功、取消和失败均清除确认状态并显示准确结果。
3. `/clear` 新建 Session、重绑 Context、清空投影并发出 ClearScreen。
4. `/plan` 在 `[DEFAULT]` 与 `[PLAN]` 间切换并立即刷新状态栏。
5. 测试旧 Session 可恢复、保留 Provider/MCP/权限/Memory，以及两次 `/plan` 往返。

**验证：** 运行 `./gradlew test --tests com.mewcode.tui.MewCodeModelTest`，期望 UI 状态命令测试通过。

## T15：完成 Session、Memory、Permission、Status 和 Compact 集成

**文件：**

- `src/main/java/com/mewcode/tui/MewCodeModel.java`
- `src/test/java/com/mewcode/tui/MewCodeModelTest.java`

**依赖：** T14

**步骤：**

1. 验证 `/session` 三条路径和安全错误。
2. 验证 `/memory` 概要、列表、四类添加和确认清理。
3. 验证 `/permission` 概要、规则、模式、添加、优先级和 reset。
4. 验证 `/status` 全字段及状态变化后的刷新。
5. 在启动压缩前执行 5000 Token 判断，并把重点交给 Coordinator。
6. 保持压缩事件、spinner、取消和错误收尾行为。

**验证：** 运行 `./gradlew test --tests com.mewcode.tui.MewCodeModelTest`，期望全部内置命令集成测试通过。

## T16：接入启动失败、格式化和全量回归

**文件：**

- `src/main/java/com/mewcode/MewCode.java`
- `build.gradle.kts`
- 本任务涉及的全部 Java 测试文件

**依赖：** T1–T15

**步骤：**

1. 在创建 Program 前构建默认命令注册表，并把注册冲突转换为明确错误和非零退出码。
2. 运行 Spotless 自动格式化新增和修改文件。
3. 运行完整测试，修复所有既有回归。
4. 运行完整构建，确认可执行 JAR 生成。
5. 对照 spec 检查没有用户命令、反射、动态加载或新增依赖。

**验证：** 依次运行 `./gradlew spotlessApply`、`./gradlew spotlessCheck`、`./gradlew test`、`./gradlew build`，期望全部成功。

## 执行顺序

```text
T1 → T2 → T3 → T4 ───────────────────────────────┐
T5 ───────────────────────→ T9 ──────────────────┤
T6 ──────────────────────────────────────────────┤
T7 ──────────────────────────────────────────────┤
T8 ───────────────────────→ T9 ──────────────────┤
T10 ─────────────────────────────────────────────┤
                                                  ▼
                                                T11
                                                  ↓
                                                T12
                                                  ↓
                                                T13
                                                  ↓
                                                T14
                                                  ↓
                                                T15
                                                  ↓
                                                T16
```

T1/T5/T6/T7/T8/T10 在实现落点互不重叠时可以并行；本轮默认按上述顺序执行，减少共享模型和构造器同时改动。
