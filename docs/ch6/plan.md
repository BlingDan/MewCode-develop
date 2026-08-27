# MewCode 五层权限系统 Plan

> 状态：已实现

## 架构概览

权限系统新增统一的 `PermissionGate`，由 `ToolExecutor` 在工具参数校验和实际执行之间调用。所有工具调用都必须经过该入口，避免某个调用方绕过权限检查。

```text
AgentTurnCoordinator
        ↓
ToolExecutor
        ↓
PermissionGate
        ├─ DangerousCommandBlocklist
        ├─ PathSandbox（文件工具）
        ├─ BashSandbox（Bash OS 级进程沙箱）
        ├─ PermissionRuleEngine
        ├─ PermissionModePolicy
        └─ PermissionBroker / HITL
        ↓
Tool.validateInput
        ↓
Tool.execute
```

第一层只处理 Bash 危险命令；第二层包含文件工具的应用层路径边界和 Bash 的 OS 级进程沙箱；第三层处理配置规则；第四层处理未命中规则时的整体模式；第五层处理最终仍为 `Ask` 的操作。路径沙箱产生的确认不能被 `bypassPermissions` 自动跳过，但用户可以明确授权并保存文件路径授权例外。Bash 的 OS 沙箱能力检查失败时直接拒绝，不能通过确认或模式降级为裸执行。

## 核心数据结构

### `PermissionMode`

位置：`com.mewcode.permission.PermissionMode`

```java
enum PermissionMode {
    DEFAULT,
    ACCEPT_EDITS,
    PLAN,
    BYPASS_PERMISSIONS
}
```

负责表达四档整体权限策略，并提供从 YAML 字符串解析和展示名称转换。

### 权限结果类型

```java
enum PermissionDecision {
    ALLOW,
    DENY,
    ASK
}

enum PermissionResponse {
    ALLOW_ONCE,
    ALLOW_SESSION,
    ALLOW_ALWAYS,
    DENY
}

enum RuleDecision {
    ALLOW,
    DENY
}
```

配置文件只能产生 `allow` 和 `deny`；`ask` 由规则未命中、权限模式或路径沙箱运行时生成。

### 规则和判定记录

```java
record PermissionRule(
    String pattern,
    RuleDecision decision,
    RuleSource source
)

enum RuleSource {
    USER,
    PROJECT,
    LOCAL,
    SESSION
}

record RuleMatch(
    PermissionRule rule,
    String matchedSubject
)

record PermissionCheck(
    PermissionDecision decision,
    String reason,
    String matchedPattern,
    String authorizationKey
)
```

`authorizationKey` 用于会话级和永久授权复用；它由工具名、匹配目标和必要的路径范围组成，不包含用户无关的动态文本。

### 路径判定

```java
enum PathBoundary {
    INSIDE_PROJECT,
    OUTSIDE_PROJECT,
    INVALID
}

record PathCheck(
    PathBoundary boundary,
    Path normalizedPath,
    Path resolvedPath,
    String reason,
    String authorizationKey
)
```

对已存在的目标解析真实路径；对不存在的目标解析最近存在的父路径。真实路径只用于安全判断和提示展示，不能替换工具参数中的用户原始语义。

### Bash OS 级沙箱数据

```java
record BashSandboxRequest(
    String command,
    Path projectRoot,
    Set<Path> writableScopes
)

record SandboxedProcess(
    List<String> argv,
    Path workingDirectory
)
```

`writableScopes` 默认只包含项目根目录；普通权限规则不能扩大该集合。`SandboxedProcess.argv` 是待交给 `ProcessBuilder` 的参数列表，外层沙箱参数、Shell 参数和用户命令分开构造，禁止拼接未转义的包装命令字符串。

### HITL 请求

```java
record PermissionRequest(
    String requestId,
    String toolName,
    Map<String, Object> arguments,
    String displayOperation,
    String reason,
    String authorizationKey
)
```

```java
record PermissionContext(
    Path projectRoot,
    PermissionMode mode,
    PermissionRuleEngine ruleEngine,
    PathAuthorizationStore pathAuthorizationStore,
    BashSandbox bashSandbox,
    PermissionBroker permissionBroker,
    CancellationToken cancellationToken
)
```

## 核心接口

### `DangerousCommandBlocklist`

位置：`com.mewcode.permission.DangerousCommandBlocklist`

```java
Optional<String> findMatch(String command)
```

只接收 Bash 命令文本，返回命中的危险命令片段。规则是程序内置的不可变正则集合，不从 YAML 加载。

### `PathSandbox`

位置：`com.mewcode.permission.PathSandbox`

```java
PathCheck inspect(ToolCall call, Path projectRoot)
```

负责识别文件工具、提取路径或路径模式、规范化路径、解析符号链接和判断项目边界。项目外路径默认返回 `OUTSIDE_PROJECT`，由 `PermissionGate` 转换为 `Ask`。该接口不解析 Bash Shell 语法。

### `BashSandbox`

位置：`com.mewcode.permission.BashSandbox`

```java
boolean isAvailable()
SandboxedProcess prepare(BashSandboxRequest request)
```

提供 OS 级 Bash 进程沙箱适配层：macOS 使用 seatbelt，Linux 使用 bubblewrap。`prepare` 负责生成安全 profile 和参数化进程参数；沙箱工具不存在、profile 生成失败或进程启动失败时抛出可转换为明确 `ToolResult` 的失败，不允许回退到裸 `sh -c`。沙箱默认允许项目目录写入，系统依赖路径只读；不创建网络隔离 namespace，以保持本章网络范围不变。

### `PermissionRuleEngine`

位置：`com.mewcode.permission.PermissionRuleEngine`

```java
Optional<RuleMatch> match(ToolCall call)
void addSessionGrant(String authorizationKey)
```

规则引擎持有用户级、项目级、本地级和会话级规则快照，按 `SESSION → LOCAL → PROJECT → USER` 顺序查找。明确规则不能关闭黑名单，也不能自动消除路径沙箱确认。

### `PermissionGate`

位置：`com.mewcode.permission.PermissionGate`

```java
PermissionCheck check(
    ToolCall call,
    Tool tool,
    PermissionContext context
)
```

执行顺序固定为：

1. 如果是 Bash，检查危险命令黑名单；
2. 文件工具检查应用层路径沙箱及已保存路径授权；Bash 检查 OS 沙箱能力；
3. 匹配分层权限规则；
4. 对未命中规则的操作应用 `PermissionMode`；
5. 返回 `ALLOW`、`DENY` 或 `ASK`，并在 Bash 执行阶段附加 OS 沙箱。

`PermissionGate` 不执行工具，也不直接操作 TUI。

### `PermissionBroker`

位置：`com.mewcode.permission.PermissionBroker`

```java
PermissionResponse await(
    PermissionRequest request,
    CancellationToken cancellationToken
)

boolean resolve(
    String requestId,
    PermissionResponse response
)
```

Broker 使用请求 ID 管理待确认 Future。`await` 在虚拟线程中等待，取消时立即返回取消结果；`resolve` 由 TUI 通过 `AgentRun` 调用。

### `PathAuthorizationStore`

位置：`com.mewcode.permission.PathAuthorizationStore`

负责保存当前会话和永久路径授权。永久授权写入本地权限文件或路径授权记录；写入失败时不自动放行当前操作。路径授权只能影响路径沙箱的显式授权例外，不能关闭整个路径检查。

### `PermissionPromptFormatter`

位置：`com.mewcode.tui.PermissionPromptFormatter`

```java
String format(PermissionRequest request)
```

Bash 至少格式化为：

```text
MewCode 想要执行以下操作：

[Bash] git commit -m "fix: resolve null reference in handler"

允许执行？(y)是 / (n)否 / (a)始终允许此类操作
```

格式化不得修改实际命令，不写入会话历史。

## 模块设计

### 1. 权限核心模块

**目录：** `src/main/java/com/mewcode/permission/`

**职责：** 定义权限类型，执行五层决策，匹配规则，管理路径授权，并通过 Broker 连接 HITL。

**依赖：** `ToolCall`、`Tool`、`CancellationToken`、Java NIO 文件 API、SnakeYAML 产生的规则快照。

### 2. Bash OS 沙箱模块

**目录：** `src/main/java/com/mewcode/permission/`

**职责：** 为 Bash 创建平台相关的 OS 级进程沙箱，限制默认可写范围，保证沙箱不可用时 fail-closed，并向 `CommandRunner` 提供参数化的进程启动描述。

**关键实现：**

- `BashSandbox` 定义跨平台接口；
- `MacSeatbeltSandbox` 生成 macOS seatbelt profile；
- `LinuxBubblewrapSandbox` 生成 Linux bubblewrap 参数；
- `BashSandboxFactory` 根据操作系统和工具可用性选择实现；
- profile 默认将项目根目录作为可写范围，系统依赖路径保持只读；
- 不使用 `--unshare-net` 等网络隔离参数；
- 不可用、构造失败或启动失败时返回 fail-closed 错误，不提供裸执行后备路径。

该模块不解析 Shell 语法，也不从命令文本推导额外写入路径；重定向、管道、命令替换、脚本解释器和符号链接的越界写入由 OS 沙箱在进程层拒绝。

### 3. 配置模块

**目录：** `src/main/java/com/mewcode/config/`

**职责：** 解析 `permissions.mode`，加载权限规则文件，校验 decision 和模式，并将永久授权持久化到本地级配置。

**依赖：** 现有 `ConfigLoader`、SnakeYAML、权限核心数据结构。

provider 配置仍由现有流程负责，权限文件不存在时视为空规则；权限文件格式错误时 fail-closed。

### 4. 工具执行模块

**目录：** `src/main/java/com/mewcode/tool/`

**职责：** 在工具输入校验和实际执行前调用 `PermissionGate`，将 Deny/用户拒绝转换成错误 `ToolResult`，并保留现有超时、取消、并发和结果保序逻辑。

**关键改动：**

- `ToolExecutor` 增加 `PermissionGate` 和单次运行权限上下文；
- `ToolExecutionContext` 携带已批准授权上下文；
- `PathGuard` 复用 `PathSandbox` 的真实路径判断；
- `ToolRegistry` 在所有模式下返回完整工具定义；
- `BashTool` 不负责黑名单，黑名单必须在 `CommandRunner` 启动前完成；
- `CommandRunner` 只能通过 `BashSandbox.prepare` 得到的参数启动 Bash，不能直接执行裸 `sh -c`。

### 5. Agent Loop 模块

**目录：** `src/main/java/com/mewcode/agent/`

**职责：** 为每次 Agent Run 固定权限上下文，发布确认事件，接收确认响应，并把拒绝转换为可回灌模型的工具结果。

**关键改动：**

- `AgentEvent` 增加 `PermissionRequested`；
- `AgentRun` 增加 `resolvePermission`；
- `AgentTurnCoordinator` 传递 `PermissionMode`、`PermissionContext` 和取消 token；
- `ToolPolicy` 保留为兼容层，不再单独隐藏 Plan Mode 工具；
- `AgentMode` 保留现有 Plan/Execute 提示词语义。

### 6. TUI 模块

**目录：** `src/main/java/com/mewcode/tui/`

**职责：** 展示确认请求、处理确认按键、保存待确认状态、将响应交回 AgentRun，并在操作结束后恢复输入。

**关键改动：**

- `MewCodeModel` 增加 pending permission 状态；
- 流式状态下允许处理权限确认按键；
- 普通输入在等待确认时保持锁定，取消仍能结束当前 Agent Run；
- 确认文案通过 `PermissionPromptFormatter` 生成；
- 确认文本不写入 `chatMessages` 或 `ConversationManager`。

### 7. Prompt 模块

**目录：** `src/main/java/com/mewcode/prompt/`

**职责：** 保留现有 Plan/Execute 提示词，并明确 Plan Mode 应优先只读，但不把提示词作为唯一的安全边界。

### 8. 测试模块

按黑名单、路径、规则、模式、Broker、执行器、Agent Loop、配置和 TUI 分层测试；额外使用临时目录、确定性 provider 和 tmux 覆盖真实交互链路。

## 模块交互

### 启动阶段

```text
MewCode
  ↓
ConfigLoader
  ├─ provider 配置
  └─ permissions.mode
  ↓
PermissionConfigLoader
  ├─ 用户级规则
  ├─ 项目级规则
  └─ 本地级规则
  ↓
PermissionRuleEngine + PathAuthorizationStore + BashSandboxFactory
  ↓
PermissionBroker
  ↓
MewCodeModel → AgentTurnCoordinator → ToolExecutor
```

### 普通调用阶段

```text
模型返回 ToolCall
      ↓
AgentTurnCoordinator
      ↓
ToolExecutor
      ↓
PermissionGate
      ├─ 黑名单硬拒绝
      ├─ 文件路径边界 Ask
      ├─ Bash OS 沙箱能力检查
      ├─ 规则 Allow/Deny
      ├─ 模式 Allow/Ask
      └─ Broker 确认
      ↓
Tool.validateInput
      ↓
Tool.execute
```

权限检查必须先于工具参数校验和工具 Future 提交，防止校验或执行线程先产生副作用。

### Bash OS 沙箱执行阶段

```text
PermissionGate 返回 Allow 或用户确认 Allow
      ↓
BashTool → CommandRunner
      ↓
BashSandbox.prepare(projectRoot, writableScopes)
      ├─ MacSeatbeltSandbox
      └─ LinuxBubblewrapSandbox
      ↓
ProcessBuilder.start(parameterizedArgv)
      ↓
命令在 OS 沙箱中执行
```

`BashSandbox.prepare` 失败时不启动任何裸 Shell，并返回 fail-closed 的工具错误。沙箱允许 Bash 保留完整 Shell 语义，但只对项目根目录及显式配置的写入范围开放写权限；系统依赖路径保持只读。网络不在本阶段关闭或额外限制。

### HITL 阶段

```text
PermissionBroker 创建请求
      ↓
AgentRun 发布 PermissionRequested
      ↓
MewCodeModel 展示确认框
      ↓
用户输入确认结果
      ↓
AgentRun.resolvePermission
      ↓
PermissionBroker 唤醒等待线程
      ↓
允许执行 / 返回错误 ToolResult
```

确认请求不写入模型历史。用户拒绝、配置拒绝和黑名单拦截都使用错误 `ToolResult`，让 Agent Loop 继续下一轮。

### 多工具调用

工具调用先按模型原始顺序执行权限预检查。需要用户确认的调用按原始顺序串行进入 Broker；已经通过权限检查的安全只读工具继续使用现有并发调度；不安全工具继续串行执行。最终结果仍按原始调用顺序回灌。

### 授权生命周期

```text
ALLOW_ONCE   → 当前调用
ALLOW_SESSION → 当前 Agent 会话内存
ALLOW_ALWAYS  → permissions.local.yaml 或路径授权记录
DENY         → 不保存授权，返回错误结果
```

永久授权写入失败时，当前调用保持未授权状态，不自动执行。

## 权限规则和模式实现

### 规则文件

规则文件使用：

- `~/.mewcode/permissions.yaml`；
- `.mewcode/permissions.yaml`；
- `.mewcode/permissions.local.yaml`。

示例：

```yaml
rules:
  - pattern: "Bash(git *)"
    decision: allow
  - pattern: "Bash(git push *)"
    decision: deny
```

规则引擎为每条规则记录来源。规则加载失败、decision 非法或 pattern 无法解析时返回配置错误，不忽略为 allow。

### 工具目标提取

- Bash：目标为完整 `command` 文本；
- ReadFile/WriteFile/EditFile：目标为 `path`；
- Glob：目标为 `pattern`；
- Grep：目标为搜索表达式和路径范围的稳定组合。

目标先规范化，再用于规则匹配和授权 key 生成。展示仍保留足够的原始命令或路径信息。

### 模式策略

模式只处理未命中明确规则的普通操作：

- `DEFAULT`：只读 Allow，文件写入和 Bash Ask；
- `ACCEPT_EDITS`：只读和文件写入 Allow，Bash Ask；
- `PLAN`：只读 Allow，文件写入和 Bash Ask；
- `BYPASS_PERMISSIONS`：普通操作 Allow，但路径沙箱产生的 Ask 和黑名单 Deny 仍保留。

`deny` 规则优先于模式，不被 `BYPASS_PERMISSIONS` 覆盖。路径普通规则不能消除路径沙箱的确认；只有路径授权存储中的显式授权例外可以复用已批准的越界范围。

`BashSandbox` 的 OS 边界独立于规则和模式：规则的 `allow` 只表示通过权限层，不会改变 sandbox profile；`bypassPermissions` 也只能跳过普通 Bash 的 HITL，不会关闭沙箱或获得沙箱外写权限。

## 文件组织

### 新建：权限核心

| 文件 | 职责 |
|---|---|
| `src/main/java/com/mewcode/permission/PermissionMode.java` | 四档权限模式 |
| `src/main/java/com/mewcode/permission/PermissionDecision.java` | `ALLOW / DENY / ASK` |
| `src/main/java/com/mewcode/permission/PermissionResponse.java` | 用户确认结果 |
| `src/main/java/com/mewcode/permission/PermissionReason.java` | 权限原因 |
| `src/main/java/com/mewcode/permission/PermissionRule.java` | 单条规则 |
| `src/main/java/com/mewcode/permission/RuleSource.java` | 规则来源 |
| `src/main/java/com/mewcode/permission/RuleMatch.java` | 规则匹配结果 |
| `src/main/java/com/mewcode/permission/PermissionCheck.java` | 统一判定结果 |
| `src/main/java/com/mewcode/permission/PathBoundary.java` | 路径边界结果 |
| `src/main/java/com/mewcode/permission/PathCheck.java` | 路径检查结果 |
| `src/main/java/com/mewcode/permission/PermissionRequest.java` | HITL 请求 |
| `src/main/java/com/mewcode/permission/PermissionContext.java` | 单次运行权限上下文 |
| `src/main/java/com/mewcode/permission/DangerousCommandBlocklist.java` | Bash 黑名单 |
| `src/main/java/com/mewcode/permission/PathSandbox.java` | 符号链接和边界检查 |
| `src/main/java/com/mewcode/permission/BashSandbox.java` | Bash OS 级沙箱接口 |
| `src/main/java/com/mewcode/permission/BashSandboxRequest.java` | Bash 沙箱输入和写入范围 |
| `src/main/java/com/mewcode/permission/SandboxedProcess.java` | 参数化进程启动描述 |
| `src/main/java/com/mewcode/permission/MacSeatbeltSandbox.java` | macOS seatbelt 适配 |
| `src/main/java/com/mewcode/permission/LinuxBubblewrapSandbox.java` | Linux bubblewrap 适配 |
| `src/main/java/com/mewcode/permission/BashSandboxFactory.java` | 平台和能力探测 |
| `src/main/java/com/mewcode/permission/PermissionRuleEngine.java` | 分层规则匹配 |
| `src/main/java/com/mewcode/permission/PermissionGate.java` | 五层权限总入口 |
| `src/main/java/com/mewcode/permission/PermissionBroker.java` | 异步 HITL 桥接 |
| `src/main/java/com/mewcode/permission/PathAuthorizationStore.java` | 路径授权存储 |

### 新建：配置和示例

| 文件 | 职责 |
|---|---|
| `src/main/java/com/mewcode/config/PermissionConfig.java` | 默认权限模式配置 |
| `src/main/java/com/mewcode/config/PermissionConfigLoader.java` | 权限规则文件加载 |
| `.mewcode/permissions.yaml.example` | 安全规则示例 |

### 修改：运行时和执行链

| 文件 | 职责 |
|---|---|
| `src/main/java/com/mewcode/config/AppConfig.java` | 增加权限配置 |
| `src/main/java/com/mewcode/config/ConfigLoader.java` | 校验权限配置 |
| `src/main/java/com/mewcode/MewCode.java` | 组装权限运行时 |
| `src/main/java/com/mewcode/tool/ToolExecutor.java` | 接入权限闸门 |
| `src/main/java/com/mewcode/tool/ToolExecutionContext.java` | 传递授权上下文 |
| `src/main/java/com/mewcode/tool/support/PathGuard.java` | 复用路径沙箱结果 |
| `src/main/java/com/mewcode/tool/support/CommandRunner.java` | 通过 Bash OS 沙箱启动进程 |
| `src/main/java/com/mewcode/tool/BashTool.java` | 传递 Bash 沙箱上下文 |
| `src/main/java/com/mewcode/agent/ToolPolicy.java` | 保留兼容入口 |
| `src/main/java/com/mewcode/tool/ToolRegistry.java` | 所有模式提供完整工具定义 |
| `src/main/java/com/mewcode/agent/AgentEvent.java` | 增加权限请求事件 |
| `src/main/java/com/mewcode/agent/AgentRun.java` | 接收权限响应 |
| `src/main/java/com/mewcode/agent/AgentTurnCoordinator.java` | 传递权限上下文 |
| `src/main/java/com/mewcode/prompt/PromptBuilder.java` | 强化 Plan Mode 提示 |
| `src/main/java/com/mewcode/tui/MewCodeModel.java` | 确认状态和按键 |
| `src/main/java/com/mewcode/tui/PermissionPromptFormatter.java` | 确认文案 |
| `.gitignore` | 忽略本地权限文件 |

### 测试文件

| 文件 | 职责 |
|---|---|
| `src/test/java/com/mewcode/permission/DangerousCommandBlocklistTest.java` | 黑名单硬拦截 |
| `src/test/java/com/mewcode/permission/PathSandboxTest.java` | 路径和符号链接 |
| `src/test/java/com/mewcode/permission/BashSandboxTest.java` | 沙箱参数、写入范围和 fail-closed |
| `src/test/java/com/mewcode/tool/CommandRunnerTest.java` | 黑名单前置和禁止裸执行 |
| `src/test/java/com/mewcode/tool/BashSandboxIntegrationTest.java` | 支持平台上的真实越界写入拦截 |
| `src/test/java/com/mewcode/permission/PermissionRuleEngineTest.java` | 规则匹配和优先级 |
| `src/test/java/com/mewcode/permission/PermissionGateTest.java` | 五层判定顺序 |
| `src/test/java/com/mewcode/permission/PermissionBrokerTest.java` | HITL 生命周期 |
| `src/test/java/com/mewcode/config/PermissionConfigLoaderTest.java` | YAML 加载和 fail-closed |
| `src/test/java/com/mewcode/tool/ToolExecutorTest.java` | 执行前权限和副作用隔离 |
| `src/test/java/com/mewcode/agent/AgentEventTest.java` | 权限事件 |
| `src/test/java/com/mewcode/agent/AgentRunTest.java` | 权限响应回传 |
| `src/test/java/com/mewcode/agent/AgentLoopTest.java` | 拒绝后继续 Loop |
| `src/test/java/com/mewcode/tui/PermissionPromptFormatterTest.java` | 确认框格式 |
| `src/test/java/com/mewcode/tui/MewCodeModelTest.java` | TUI 确认和输入恢复 |
| `src/test/java/com/mewcode/agent/PermissionIntegrationTest.java` | 跨模块流程 |

## 技术决策

| 决策点 | 选择 | 理由 |
|---|---|---|
| 权限总入口 | `ToolExecutor` 前的 `PermissionGate` | 统一覆盖所有工具调用 |
| 黑名单实现 | 内置不可变正则 | 不允许配置绕过 |
| 黑名单时机 | 启动 shell 前 | 命中时无进程和副作用 |
| 路径解析 | Java NIO 真实路径和最近存在父路径 | 覆盖已有与新建目标 |
| 路径越界 | `Ask`，经用户授权后执行 | 用户可明确承担危险操作责任 |
| Bash 路径边界 | OS 级沙箱，项目根目录默认可写 | 不解析 Shell 语法，也不能被规则或模式关闭 |
| Bash 沙箱平台 | macOS seatbelt / Linux bubblewrap | 使用平台能力限制进程写入范围 |
| Bash 沙箱不可用 | Fail-Closed，不裸执行 | 防止降级路径绕过第二层边界 |
| Bash 网络行为 | 不创建网络隔离 | 网络请求限制留到后续章节 |
| 规则优先级 | SESSION → LOCAL → PROJECT → USER | 临近当前操作的规则优先 |
| 规则与模式 | 明确规则优先，未命中才使用模式 | 支持项目本地细粒度覆盖 |
| 规则与路径 | 普通规则不能消除路径 Ask | 防止 allow 误关闭边界 |
| 模式兼容 | 新增 `PermissionMode`，保留 `AgentMode` | 降低现有 Prompt/Loop 回归风险 |
| 工具声明 | 所有模式声明完整工具列表 | Plan 违规调用必须能触发 Ask |
| HITL 解耦 | Broker + AgentEvent + AgentRun | TUI 不直接依赖权限核心 |
| 确认等待 | 虚拟线程等待 Future | 不阻塞 TUI，支持取消 |
| 多工具确认 | 原始顺序串行确认 | 避免确认输入竞争 |
| 工具并发 | 继续使用现有安全/不安全调度 | 权限系统不改变执行语义 |
| 拒绝结果 | 错误 `ToolResult` | 模型可以继续调整策略 |
| 永久授权 | 写入本地权限文件或路径授权记录 | 授权可复用且不污染全局规则 |
| 配置错误 | fail-closed | 错误不能意外放行 |
| 默认模式 | 配置读取，缺省为 `default` | 满足日常开发场景 |
| Plan/Do | `/plan` 使用 `plan`，`/do` 恢复 `default` | 保留既有命令语义 |

## 设计覆盖检查

| Spec 需求 | 设计归属 |
|---|---|
| F1 五层链路 | `PermissionGate` 和模块交互顺序 |
| F2 Bash 黑名单 | `DangerousCommandBlocklist` |
| F3 路径沙箱 | 文件工具 `PathSandbox`、Bash `BashSandbox`、`PathAuthorizationStore` |
| F4 分层规则 | `PermissionRuleEngine`、`PermissionConfigLoader` |
| F5 四档模式 | `PermissionMode`、模式策略 |
| F6 HITL | `PermissionBroker`、`PermissionRequested`、TUI |
| F7 Agent Loop 容错 | `ToolExecutor`、`AgentTurnCoordinator` |
| F8 配置和模式切换 | `ConfigLoader`、`MewCodeModel`、`PromptBuilder` |
| N1–N8 | 权限核心、Bash OS 沙箱、Broker、配置 fail-closed 和回归测试 |
