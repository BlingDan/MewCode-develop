# MewCode 五层权限系统 Tasks

> 状态：已实现
>
> 本任务清单基于已确认的 [spec.md](./spec.md) 和 [plan.md](./plan.md)。四份文档全部获批前禁止编写实现代码。

## 实现约束

- 保持 Java 21、现有 Gradle、工具协议、Agent Loop 轮次上限、取消机制和工具调度语义不变。
- 所有权限判断只能从 `ToolExecutor` 的统一入口进入；工具自身不得新增绕过入口的执行路径。
- 黑名单、文件路径沙箱和 Bash OS 级沙箱按顺序生效；后一层不能关闭前一层。
- 黑名单命中、配置拒绝、路径拒绝、用户拒绝和沙箱失败都转换为错误 `ToolResult`，不得终止 Agent Loop。
- OS 沙箱不可用时 Fail-Closed，禁止退回裸 `sh -c` 或其他未隔离执行。
- 新增代码使用中文注释；不实现网络限制、资源配额、审计日志、Shell 应用层解析、完整命令白名单或远程能力。

## 文件清单

| 操作 | 文件 | 职责 |
|---|---|---|
| 新建 | `src/main/java/com/mewcode/permission/PermissionMode.java` | 四档权限模式和模式默认决策 |
| 新建 | `src/main/java/com/mewcode/permission/PermissionDecision.java` | `ALLOW / DENY / ASK` |
| 新建 | `src/main/java/com/mewcode/permission/PermissionResponse.java` | 本次、会话、永久放行和拒绝 |
| 新建 | `src/main/java/com/mewcode/permission/PermissionReason.java` | 黑名单、路径、规则、模式、用户和沙箱原因 |
| 新建 | `src/main/java/com/mewcode/permission/PermissionRule.java` | 单条规则和 `allow / deny` |
| 新建 | `src/main/java/com/mewcode/permission/RuleSource.java` | 用户、项目、本地、会话来源 |
| 新建 | `src/main/java/com/mewcode/permission/RuleMatch.java` | 规则命中记录 |
| 新建 | `src/main/java/com/mewcode/permission/PermissionCheck.java` | 统一权限判定结果 |
| 新建 | `src/main/java/com/mewcode/permission/PathBoundary.java` | 项目内、项目外、无效路径 |
| 新建 | `src/main/java/com/mewcode/permission/PathCheck.java` | 路径解析和边界检查结果 |
| 新建 | `src/main/java/com/mewcode/permission/BashSandboxRequest.java` | Bash OS 沙箱输入和写入范围 |
| 新建 | `src/main/java/com/mewcode/permission/SandboxedProcess.java` | 参数化进程启动描述 |
| 新建 | `src/main/java/com/mewcode/permission/PermissionRequest.java` | HITL 请求内容 |
| 新建 | `src/main/java/com/mewcode/permission/PermissionContext.java` | 单次 Agent Run 权限上下文 |
| 新建 | `src/main/java/com/mewcode/permission/DangerousCommandBlocklist.java` | Bash 不可配置黑名单 |
| 新建 | `src/main/java/com/mewcode/permission/PathSandbox.java` | 文件工具符号链接和路径边界 |
| 新建 | `src/main/java/com/mewcode/permission/BashSandbox.java` | Bash OS 级沙箱接口 |
| 新建 | `src/main/java/com/mewcode/permission/MacSeatbeltSandbox.java` | macOS seatbelt 适配 |
| 新建 | `src/main/java/com/mewcode/permission/LinuxBubblewrapSandbox.java` | Linux bubblewrap 适配 |
| 新建 | `src/main/java/com/mewcode/permission/BashSandboxFactory.java` | 平台和沙箱能力探测 |
| 新建 | `src/main/java/com/mewcode/permission/PermissionRuleEngine.java` | 四层规则匹配和会话授权 |
| 新建 | `src/main/java/com/mewcode/permission/PermissionGate.java` | 五层权限总入口 |
| 新建 | `src/main/java/com/mewcode/permission/PermissionBroker.java` | 异步 HITL 桥接 |
| 新建 | `src/main/java/com/mewcode/permission/PathAuthorizationStore.java` | 文件路径授权存储 |
| 新建 | `src/main/java/com/mewcode/config/PermissionConfig.java` | 权限模式配置 |
| 新建 | `src/main/java/com/mewcode/config/PermissionConfigLoader.java` | 权限 YAML 加载和校验 |
| 新建 | `.mewcode/permissions.yaml.example` | 规则配置示例 |
| 修改 | `src/main/java/com/mewcode/config/AppConfig.java` | 增加权限配置 |
| 修改 | `src/main/java/com/mewcode/config/ConfigLoader.java` | 加载和校验权限模式 |
| 修改 | `src/main/java/com/mewcode/MewCode.java` | 组装权限运行时 |
| 修改 | `src/main/java/com/mewcode/tool/ToolExecutor.java` | 接入权限闸门和确认等待 |
| 修改 | `src/main/java/com/mewcode/tool/ToolExecutionContext.java` | 传递授权上下文和沙箱范围 |
| 修改 | `src/main/java/com/mewcode/tool/support/PathGuard.java` | 复用真实路径判断 |
| 修改 | `src/main/java/com/mewcode/tool/support/CommandRunner.java` | 通过 Bash OS 沙箱启动进程 |
| 修改 | `src/main/java/com/mewcode/tool/BashTool.java` | 传递 Bash 沙箱上下文 |
| 修改 | `src/main/java/com/mewcode/agent/ToolPolicy.java` | 保留兼容入口，不再隐藏 Plan 工具 |
| 修改 | `src/main/java/com/mewcode/tool/ToolRegistry.java` | 所有模式提供完整工具定义 |
| 修改 | `src/main/java/com/mewcode/agent/AgentEvent.java` | 增加权限请求事件 |
| 修改 | `src/main/java/com/mewcode/agent/AgentRun.java` | 接收权限响应 |
| 修改 | `src/main/java/com/mewcode/agent/AgentTurnCoordinator.java` | 传递权限上下文和事件 |
| 修改 | `src/main/java/com/mewcode/prompt/PromptBuilder.java` | 强化 Plan Mode 只读提示 |
| 新建 | `src/main/java/com/mewcode/tui/PermissionPromptFormatter.java` | 确认框文案 |
| 修改 | `src/main/java/com/mewcode/tui/MewCodeModel.java` | 确认状态和按键处理 |
| 修改 | `.gitignore` | 忽略本地权限文件 |
| 新建 | `src/test/java/com/mewcode/permission/DangerousCommandBlocklistTest.java` | 黑名单覆盖和不可绕过 |
| 新建 | `src/test/java/com/mewcode/permission/PathSandboxTest.java` | 路径、父目录和符号链接 |
| 新建 | `src/test/java/com/mewcode/permission/BashSandboxTest.java` | profile、参数和 Fail-Closed |
| 新建 | `src/test/java/com/mewcode/permission/PermissionRuleEngineTest.java` | 精确、glob 和优先级 |
| 新建 | `src/test/java/com/mewcode/permission/PermissionGateTest.java` | 五层顺序和模式矩阵 |
| 新建 | `src/test/java/com/mewcode/permission/PermissionBrokerTest.java` | HITL 生命周期和取消 |
| 新建 | `src/test/java/com/mewcode/config/PermissionConfigLoaderTest.java` | YAML 和 fail-closed |
| 修改 | `src/test/java/com/mewcode/tool/ToolExecutorTest.java` | 执行前权限和拒绝结果 |
| 新建 | `src/test/java/com/mewcode/tool/CommandRunnerTest.java` | 黑名单前置和禁止裸执行 |
| 新建 | `src/test/java/com/mewcode/tool/BashSandboxIntegrationTest.java` | 真实越界写入拦截 |
| 新建 | `src/test/java/com/mewcode/agent/AgentEventTest.java` | 权限事件 |
| 新建 | `src/test/java/com/mewcode/agent/AgentRunTest.java` | 权限响应回传 |
| 新建 | `src/test/java/com/mewcode/agent/AgentLoopTest.java` | 拒绝后继续 Loop |
| 新建 | `src/test/java/com/mewcode/tui/PermissionPromptFormatterTest.java` | 确认框格式 |
| 修改 | `src/test/java/com/mewcode/tui/MewCodeModelTest.java` | TUI 确认、会话状态和输入恢复 |
| 新建 | `src/test/java/com/mewcode/agent/PermissionIntegrationTest.java` | 跨模块流程 |

## 任务拆分

### T1：建立权限核心值对象

**文件：** 权限核心值对象和枚举文件。

**依赖：** 无。

**步骤：**

1. 实现四档 `PermissionMode`，支持 `default`、`acceptEdits`、`plan`、`bypassPermissions` 的解析和展示。
2. 实现 `PermissionDecision`、`PermissionResponse`、`PermissionReason` 和 `RuleSource`。
3. 实现不可变的规则、路径、判定、请求和运行上下文记录。
4. 对集合、映射和路径授权范围做不可变快照；拒绝空工具名、空项目根目录和非法枚举值。
5. 保持配置规则结果只有 `allow` 和 `deny`，不把 `ask` 暴露为规则值。

**验证：** 编译权限包；单测覆盖模式解析、枚举映射、不可变快照和非法输入。

### T2：实现 Bash 危险命令硬拦截

**文件：** `DangerousCommandBlocklist.java`、`DangerousCommandBlocklistTest.java`。

**依赖：** T1。

**步骤：**

1. 定义程序内置、不可变的 Bash 危险命令正则集合，至少覆盖 `rm -rf /` 及等价危险根路径变体。
2. 只接收 Bash 的完整 `command` 文本，不读取 YAML，不接受运行时关闭参数。
3. 返回命中的危险片段，生成包含原命令片段的清晰拒绝原因。
4. 确保检查发生在沙箱 profile 构造、Shell 启动和任何 `Future` 提交之前。
5. 命中时只返回拒绝结果，不启动进程；`bypassPermissions`、规则和用户授权均不能改变结果。

**验证：** 断言危险命令返回 `DENY` 和指定中文错误；安全命令不误拦；通过模拟 `CommandRunner` 证明命中时没有进程启动。

### T3：实现文件工具路径沙箱

**文件：** `PathSandbox.java`、`PathGuard.java`、`PathSandboxTest.java`。

**依赖：** T1。

**步骤：**

1. 为 `ReadFile`、`WriteFile`、`EditFile`、`Glob`、`Grep` 提取路径或路径模式。
2. 将相对路径解析到项目根目录下，保留原始参数用于实际工具执行和确认展示。
3. 对已存在目标先解析真实路径；对不存在目标解析最近存在的父路径。
4. 解析沿途符号链接后，再使用真实项目根目录做路径前缀边界判断，避免 `startsWith` 字符串前缀误判。
5. 项目内返回 `INSIDE_PROJECT`；项目外、符号链接逃逸和无法可靠解析的路径返回可确认的边界结果或 `INVALID`。
6. 不让 `bypassPermissions` 或普通 `allow` 规则跳过项目外路径确认；确认后的路径授权只作用于文件工具应用层。

**验证：** 临时目录测试项目内读写、相邻目录前缀、已存在符号链接、指向项目外的符号链接、新建文件和 Glob/Grep 路径模式。

### T4：实现 Bash OS 级沙箱

**文件：** `BashSandbox.java`、`BashSandboxRequest.java`、`SandboxedProcess.java`、`MacSeatbeltSandbox.java`、`LinuxBubblewrapSandbox.java`、`BashSandboxFactory.java`、`BashSandboxTest.java`。

**依赖：** T1。

**步骤：**

1. 定义跨平台沙箱接口和不可变请求，默认将项目根目录作为唯一可写范围。
2. 通过平台工厂探测 macOS seatbelt 或 Linux bubblewrap；仅在能力可用时返回对应实现。
3. 生成 profile 和进程参数时使用 `List<String>`/`ProcessBuilder`，将沙箱参数、Shell 参数和用户命令作为独立参数构造。
4. 保留完整 Shell 语义，允许重定向、管道、命令替换和脚本解释器启动，但让这些进程继承 OS 写入边界。
5. 使系统依赖路径保持只读；项目根目录和显式允许的写入范围可写；不创建网络隔离 namespace。
6. 沙箱工具缺失、profile 构造失败或进程启动失败时抛出可转换的安全错误，不返回裸执行命令。
7. 不实现 Bash Shell 应用层语法解析，不从命令文本推导额外路径，不以普通规则扩大 OS 写入范围。

**验证：** 使用 fake adapter 检查参数没有未转义包装拼接；检查写入范围和网络参数；在当前支持平台上真实验证项目内写入成功、重定向/解释器/符号链接写出失败；模拟不可用平台确认无裸执行。

### T5：实现分层规则引擎和权限配置

**文件：** `PermissionRule.java`、`RuleMatch.java`、`PermissionRuleEngine.java`、`PermissionConfig.java`、`PermissionConfigLoader.java`、示例文件、配置测试。

**依赖：** T1、T3。

**步骤：**

1. 加载用户级 `~/.mewcode/permissions.yaml`、项目级 `.mewcode/permissions.yaml` 和本地级 `.mewcode/permissions.local.yaml`。
2. 解析 `工具名(模式)`，支持精确匹配和 glob 匹配；Bash 匹配完整命令文本，文件工具匹配规范化目标。
3. 按 `SESSION → LOCAL → PROJECT → USER` 查找首个命中规则。
4. 只接受 `allow` 和 `deny`；YAML、字段、decision 或模式语法错误均 fail-closed，不静默忽略。
5. 保存会话级授权；永久授权只写入本地级权限文件或路径授权记录，写入失败不得自动放行。
6. 明确禁止规则关闭黑名单、消除路径沙箱确认、关闭 Bash OS 沙箱或扩大未授权 OS 写入范围。

**验证：** 测试四层优先级、精确/glob 边界、命令和路径目标提取、空文件、重复规则、非法 YAML 和本地文件覆盖。

### T6：实现五层权限闸门

**文件：** `PermissionGate.java` 及其判定测试。

**依赖：** T2、T3、T4、T5。

**步骤：**

1. 固定执行顺序：Bash 黑名单 → 文件路径沙箱或 Bash 沙箱能力 → 分层规则 → 权限模式 → 最终 Ask。
2. 黑名单命中立即 `DENY`；文件项目外路径在没有显式路径授权时返回 `ASK`；Bash 沙箱不可用返回不可授权的 `DENY`。
3. 明确规则命中时使用规则结果；规则未命中时使用模式矩阵：`default`/`plan` 对写入和 Bash Ask，`acceptEdits` 放行文件编辑，`bypassPermissions` 放行普通操作。
4. 保持显式 `deny` 高于模式；`bypassPermissions` 只跳过普通 Ask，不能改变前三层安全限制。
5. 为每个结果填充原因、匹配规则、授权 key 和可供 HITL 展示的参数摘要。

**验证：** 用 fake 黑名单、路径沙箱、规则引擎和沙箱能力逐层断言调用顺序；覆盖四档模式、规则覆盖、路径确认不可跳过和沙箱失败不可授权。

### T7：实现授权存储和 HITL Broker

**文件：** `PermissionBroker.java`、`PathAuthorizationStore.java`、对应测试。

**依赖：** T1、T5、T6。

**步骤：**

1. 用请求 ID 管理待确认 Future；`await` 支持取消，`resolve` 只处理仍然 pending 的请求。
2. 实现 `ALLOW_ONCE`、`ALLOW_SESSION`、`ALLOW_ALWAYS` 和 `DENY` 的生命周期。
3. 本次授权只影响当前工具调用；会话授权绑定当前 Agent Run；永久授权写入本地权限文件或路径授权记录。
4. 将工具名、关键参数、原因和稳定授权范围放入 `PermissionRequest`，不把确认文本写入会话历史。
5. 永久写入失败时保留当前请求未授权状态，不自动执行。
6. 保证用户拒绝返回结构化错误，不抛出终止 Agent Loop 的异常。

**验证：** 覆盖并发请求 ID、重复响应、取消等待、一次/会话/永久授权复用、持久化失败和拒绝结果。

### T8：接入 ToolExecutor 和 BashTool

**文件：** `ToolExecutor.java`、`ToolExecutionContext.java`、`CommandRunner.java`、`BashTool.java`、相关测试。

**依赖：** T2、T4、T6、T7。

**步骤：**

1. 在工具参数校验、Future 提交和实际执行之前调用 `PermissionGate`。
2. `DENY` 直接构造错误 `ToolResult`；`ASK` 发布请求并等待 Broker；只有获准后才校验并提交工具执行。
3. `BashTool`/`CommandRunner` 不自行实现黑名单，必须使用 `BashSandbox.prepare` 的参数化进程描述。
4. 将黑名单、路径、规则、模式、用户拒绝和沙箱失败格式化为模型可理解的错误文本。
5. 保留现有安全工具并发、不安全工具串行、原始结果保序、超时、取消和未知工具错误行为。
6. 不因权限错误终止 Agent Loop；确保每个 ToolCall 都产生对应 ToolResult。

**验证：** 单测证明拒绝时 `validateInput`/执行器/进程均未调用；确认后副作用发生；回归现有调度、取消、超时、结果保序和调用 ID 配对测试。

### T9：接入 Agent Loop 和完整工具声明

**文件：** `AgentEvent.java`、`AgentRun.java`、`AgentTurnCoordinator.java`、`ToolPolicy.java`、`ToolRegistry.java`、对应测试。

**依赖：** T7、T8。

**步骤：**

1. 增加 `PermissionRequested` 事件和 `AgentRun.resolvePermission`。
2. 每个 Agent Run 创建并复用权限上下文、Broker、会话授权和取消 token。
3. 在所有模式下向模型声明完整工具；`plan` 只通过 Prompt 引导只读，实际违规调用由 Ask 兜底。
4. 将权限错误作为工具结果回灌模型，继续执行后续 Loop，不破坏 assistant tool-use 与 tool-result 配对。
5. 保持现有轮次上限、取消、超时、未知工具、并发和历史提交顺序。

**验证：** 测试权限请求事件到响应的往返、拒绝后模型继续一轮、Plan 工具列表完整、取消 pending 请求和既有 Agent Loop 回归。

### T10：接入配置、模式切换和 Prompt

**文件：** `AppConfig.java`、`ConfigLoader.java`、`MewCode.java`、`PromptBuilder.java`、相关测试。

**依赖：** T1、T5、T9。

**步骤：**

1. 从现有配置读取 `permissions.mode`，缺省为 `default`，非法值 fail-closed。
2. 启动时组装规则引擎、路径授权存储、Bash 沙箱工厂和 Broker；权限文件不存在视为空规则。
3. `/plan` 使用 `plan` 模式，`/do` 恢复 `default`，保持命令不触发模型调用且不写入历史。
4. 强化 Plan Prompt 的“优先只读”说明，但不把 Prompt 当作安全边界。
5. 将永久授权文件加入本地忽略范围，不影响项目级共享配置。

**验证：** 测试默认模式、四档解析、非法配置、模式切换后的工具声明、规则加载和运行时组装。

### T11：实现 TUI 确认交互

**文件：** `PermissionPromptFormatter.java`、`MewCodeModel.java`、对应测试。

**依赖：** T7、T9。

**步骤：**

1. 确认框展示工具名称、关键参数/路径、触发原因和影响范围。
2. Bash 至少展示：

   ```text
   MewCode 想要执行以下操作：

   [Bash] git commit -m "fix: resolve null reference in handler"

   允许执行？(y)是 / (n)否 / (a)始终允许此类操作
   ```

3. `y` 映射本次放行，`n` 映射拒绝，`a` 映射永久放行；Broker 同时保留会话放行能力，并通过 `s` 快捷键支持会话级授权，不改变上述默认展示文案。
4. 等待确认时锁定普通输入，但允许确认、取消和 Ctrl+C；处理后恢复输入状态。
5. 确认文本不写入 `chatMessages`、`ConversationManager` 或模型历史。
6. 黑名单和不可授权的沙箱失败不展示可绕过的确认按钮。

**验证：** 测试精确文案、特殊参数展示、y/n/s/a 状态映射、重复响应、取消恢复、流式期间输入锁定和历史隔离。

### T12：补齐核心自动化测试

**文件：** T2–T11 对应测试文件。

**依赖：** T2–T11。

**步骤：**

1. 建立确定性 fake Tool、fake Provider、fake PermissionBroker 和 fake BashSandbox，避免核心测试依赖真实模型或当前机器工具。
2. 覆盖五层短路顺序、所有模式矩阵、规则优先级、路径符号链接、拒绝结果和 Agent Loop 继续。
3. 覆盖 macOS/Linux adapter 的参数构造和 unsupported/failure 分支；真实 OS 沙箱测试按平台能力条件执行。
4. 保持现有 Ch02–Ch05 测试通过，新增测试不得改变旧工具和 Prompt 的既有语义。

**验证：** 运行 `./gradlew test`，所有测试通过；失败测试能定位到单一任务或模块。

### T13：真实 Bash 沙箱和跨模块集成验收

**文件：** `BashSandboxIntegrationTest.java`、`PermissionIntegrationTest.java`。

**依赖：** T4、T8、T9、T12。

**步骤：**

1. 在临时项目目录中执行项目内文件写入，确认 Bash 完整 Shell 语义和正常退出码保持不变。
2. 通过重定向、管道、命令替换、解释器和项目内符号链接尝试写入沙箱外路径，确认 OS 拒绝且不产生外部副作用。
3. 验证 `bypassPermissions` 可跳过普通 Bash Ask，但不能关闭 OS 沙箱。
4. 模拟 seatbelt/bubblewrap 不存在和 profile/启动失败，确认返回清晰 ToolResult 且不裸执行。
5. 验证黑名单发生在沙箱包装之前，命中 `rm -rf /` 时没有沙箱进程和副作用。

**验证：** 运行平台相关集成测试和跨模块测试；记录不支持平台时的明确跳过条件，不把跳过当作裸执行通过。

### T14：构建、格式化和 tmux 端到端验收

**文件：** 无新增实现文件；更新 [checklist.md](./checklist.md) 验收结果。

**依赖：** T12、T13。

**步骤：**

1. 运行 `./gradlew spotlessCheck`、`./gradlew test` 和 `./gradlew shadowJar`。
2. 使用 tmux 启动 MewCode，输入真实开发请求，观察只读调用、写入确认、Bash 确认和权限错误回灌。
3. 按 `checklist.md` 验证四档模式、规则优先级、一次/会话/永久授权、Plan 工具可见性、路径符号链接和黑名单。
4. 额外验证 Bash OS 沙箱的项目内写入、越界写入拒绝、沙箱不可用 Fail-Closed 和 Agent Loop 恢复输入。
5. 记录测试命令、平台、结果和任何不在本章范围内的未覆盖能力。

**验证：** 构建成功生成 `build/libs/mewcode.jar`；tmux 场景全部通过；checklist 所有必须项有证据。

## 依赖顺序

```text
T1
├─ T2 ───────────────┐
├─ T3 ──┐           │
├─ T4 ──┼─ T6 ─ T7 ─ T8 ─ T9 ─ T10/T11 ─ T12 ─ T13 ─ T14
└─ T5 ──┘
```

T10 与 T11 在 T9 后可并行；T12 必须等待实现链路完成，T13 必须等待 Bash 执行链和自动化测试完成，T14 是最终验收入口。

## 任务完成定义

只有同时满足以下条件，才可将本章标记为完成：

- 所有任务的实现和验证步骤完成；
- `spec.md`、`plan.md`、`task.md`、`checklist.md` 状态均为已确认/已验收；
- `spotlessCheck`、全部测试和 `shadowJar` 通过；
- tmux 真实对话覆盖 checklist 中的权限拒绝、确认、模式切换、沙箱和 Agent Loop 恢复场景；
- 没有新增网络限制、资源配额、审计日志或其他范围外行为。
