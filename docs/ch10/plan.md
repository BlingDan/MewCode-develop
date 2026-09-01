# MewCode Slash Command Plan

> 状态：已确认
>
> 本计划基于已确认的 [spec.md](./spec.md)，并参考飞书文档《Java源码解析：命令注册与分发》。当前阶段只定义实现边界和落点，不写实现代码。

## 1. 架构概览

采用“集中注册表 + 薄处理器”，不使用反射或每命令一个类。

```text
用户输入 / Tab
      │
      ▼
MewCodeModel 输入分流
      │
      ├─ 普通文本 ───────────────→ 现有 Agent Loop
      │
      ├─ Tab ───────────────────→ CommandRegistry 补全查询
      │
      └─ 斜杠输入
             ▼
       CommandRegistry
             ▼
       Command handler
             │
             ├─ LOCAL  ──→ 现有 Context/Session/Memory/Permission 服务
             ├─ UI     ──→ CommandContext.UIController
             └─ PROMPT ──→ sendUserMessage → Agent Loop
```

组件划分：

1. `command` 核心层：定义命令元数据、三种执行类型、处理函数、注册中心、解析执行和补全查询。注册中心保留稳定登记顺序，并分别维护正式名称与别名索引。
2. 内置命令目录：9 条命令在 `CommandRegistry` 的集中入口中声明，元数据和处理函数放在一起。短命令使用 lambda，不拆成 9 个处理器类。
3. `CommandContext.UIController`：由 `MewCodeModel` 实现，为命令提供系统消息、发送 Agent 消息、Plan Mode、Token 查询、状态刷新、新对话和确认提示等界面能力。命令层不引用 Tea、ANSI 样式或 `Program`。
4. 现有领域服务：复用 `SessionManager`、`ContextManager`、`MemoryManager`、`PermissionRuleEngine`、`ToolRegistry` 和 `McpManager`。只补充新命令必需的窄入口，不复制已有的文件、安全或 Provider 逻辑。
5. TUI 状态适配：`MewCodeModel` 继续作为唯一输入状态机，增加补全菜单、Memory 清理确认和命令输出状态。`Program` 只增加一个“清空终端显示”的底层效果。
6. 运行期权限状态：增加一个小型共享状态对象，保存启动权限模式、当前模式和临时规则。命令修改它，`AgentTurnCoordinator` 在每次新 Run 开始时读取快照，保证运行中的 Agent Run 不被中途改变。

本设计不新增依赖，也不为后续用户命令或 Skill 系统预留扩展框架。

## 2. 核心数据结构与接口

### 2.1 Command

```java
public record Command(
    String name,
    List<String> aliases,
    String description,
    String usage,
    CommandType type,
    String argumentHint,
    boolean hidden
) {
    public enum CommandType {
        LOCAL,
        LOCAL_UI,
        PROMPT
    }
}
```

`Command` 只保存不可变元数据。处理函数单独保存在注册中心，但在同一次 `register` 调用中与命令绑定。

### 2.2 CommandContext

沿用参考设计的函数式依赖注入。上下文只持有字符串、JDK 函数接口和自身定义的 UI 抽象，不引用 Agent、Session、Memory、权限或 TUI 实现类。

```java
public record CommandContext(
    String args,
    String workDir,
    String model,
    UIController ui,

    Supplier<String> status,
    Consumer<String> compact,

    Supplier<String> sessionInfo,
    Supplier<List<String>> sessionList,
    Function<String, String> sessionResume,

    Supplier<String> memorySummary,
    Supplier<List<String>> memoryList,
    BiFunction<String, String, String> memoryAdd,
    Runnable memoryClear,

    Supplier<String> permissionSummary,
    Supplier<List<String>> permissionRules,
    Function<String, String> permissionMode,
    BiFunction<String, String, String> permissionAdd,
    Runnable permissionReset
) {
    public interface UIController {
        void addSystemMessage(String text);
        void sendUserMessage(String text);

        boolean isPlanMode();
        void setPlanMode(boolean enabled);

        long getTokenCount();
        void refreshStatus();

        void startNewConversation();
        void requestConfirmation(String text, Runnable onConfirm);
    }
}
```

- `sendUserMessage` 复用现有普通消息入口，保证 `/review` 走完整 Agent Loop。
- `startNewConversation` 负责新 Session、上下文重置和终端清理。
- `requestConfirmation` 只保存一次待确认动作；确认或取消后立即清除。
- 返回字符串的能力负责给出成功结果；校验或执行失败通过参数异常或安全业务异常交给注册中心统一转换为系统消息。

### 2.3 CommandRegistry

```java
public final class CommandRegistry {
    void register(Command command, Function<CommandContext, String> handler);

    Optional<Command> find(String name);
    List<Command> listVisible();
    List<Command> search(String prefix);

    Optional<CommandCall> parse(String input);
    String execute(CommandCall call, CommandContext context);

    public record CommandCall(Command command, String args) {}
}
```

内部状态：

```java
LinkedHashMap<String, Command> commands;
HashMap<String, Command> aliases;
HashMap<String, Function<CommandContext, String>> handlers;
```

规则：

- `register` 使用 `Locale.ROOT` 归一化名称和别名。
- 注册前同时检查 `commands` 与 `aliases`，冲突直接抛出异常。
- `find` 先查 `commands`，再查 `aliases`。
- `search` 同时匹配名称和别名前缀，过滤隐藏项并按登记顺序去重。
- `parse` 负责去掉开头 `/`、按第一个空格拆分名称与参数。
- `execute` 捕获可预期的参数错误并返回安全文本；注册错误不吞掉。

### 2.4 现有领域对象的窄幅扩展

```java
// SessionManager
NewSessionResult startNewSession();

// MemoryManager
MemoryOverview overview();
String add(MemoryType type, String content);
void clear();

// Context / Coordinator
long estimateManualCompactionTokens(AgentMode mode);
AgentRun startManualCompaction(AgentMode mode, String focus);

// PermissionRuntime
PermissionMode currentMode();
List<PermissionRule> effectiveRules();
void setMode(PermissionMode mode);
void addRule(PermissionRule rule);
void reset();
```

`PermissionRuntime` 保存启动模式、当前模式、配置规则和临时规则；临时规则始终排在配置规则之前。`AgentTurnCoordinator` 每次启动新 Run 时读取一次模式与规则快照。

### 2.5 TUI 底层效果

```java
// tea.Command
record ClearScreen() implements Command {}
```

`Program` 执行该效果时清除当前 View 和终端滚屏，然后立即重绘；命令包不接触 ANSI 控制序列。

## 3. 模块设计

### 3.1 命令注册与执行

**职责：**

- 集中声明 9 个内置命令。
- 注册时归一化并检查全部名称、别名冲突。
- 解析斜杠输入、查找命令、执行 handler。
- 生成帮助列表和补全候选。

**实现：**

- `CommandRegistry.createDefault()` 在固定顺序中完成所有注册。
- LOCAL handler 返回系统消息。
- LOCAL_UI handler 调用 `UIController` 后返回可选提示。
- PROMPT handler 返回展开后的提示词，由调用方通过 `sendUserMessage` 发送。
- 参数错误返回命令用法；其他运行时错误由 TUI 转成统一安全错误。
- `/review` 只生成固定 Prompt，不在命令层直接执行 Git。

### 3.2 输入分流与 UI 状态

**职责：**

- 普通输入继续进入现有提交路径。
- 斜杠输入交给注册中心，不再保留命令名称分支。
- 管理补全菜单、Plan Mode 和 Memory 清理确认。

**状态：**

```java
List<Command> completionCandidates;
int completionCursor;
PendingConfirmation pendingConfirmation;
```

**按键优先级：**

1. 正在等待 Memory 清理确认：`y` 确认，`n`/Esc 取消。
2. 补全菜单打开：↑/↓ 移动，Enter 补入，Esc 关闭。
3. 正常输入：Tab 查询补全，Enter 提交。
4. Agent Streaming 状态继续沿用现有取消和权限确认逻辑。

命令执行产生的系统消息和异步 TUI 效果先进入模型内的效果队列，再由当前 `update()` 返回，避免 UI 抽象暴露 Tea 类型。

### 3.3 会话与 /clear

`SessionManager.startNewSession()` 复用现有 Session ID、安全目录和 `HistoryStore` 创建逻辑：

1. 先成功创建新的 Session 目录和 Store。
2. 将共享 `ConversationManager` 静默替换为空历史。
3. 切换当前 Session 和持久化目标。
4. 关闭旧 Store，但不删除旧 Session。
5. 清除恢复提醒和标题请求状态。

`MewCodeModel.startNewConversation()` 随后：

- 清空终端消息投影和当前输入。
- 让 `ContextManager` 绑定新 Session 并重置 Token 估算。
- 发出 `ClearScreen` 和新 Session 提示。
- 保留 Provider、MCP、Memory 与运行期权限状态。

### 3.4 上下文与 /compact

在真正启动压缩前，使用与手动压缩相同的上下文快照估算 Token：

- `< 5000`：直接返回“当前上下文无需压缩”。
- `>= 5000`：启动现有异步手动压缩流程。
- 有参数时，把参数作为独立的“必须保留重点”加入摘要请求。
- 保留重点只影响本次摘要，不写入 Conversation。
- 摘要仍禁止工具调用，Provider usage 继续记录到现有 Token 估算器。
- 完成、失败和取消继续复用现有 `AgentEvent` 流。

### 3.5 Memory 管理

`MemoryManager` 在现有 `updateLock` 内提供同步命令入口。

`overview()`：

- 分别扫描 user/project Store。
- 返回两级笔记数量及完整不可变快照。

`add(type, content)`：

- 复用现有 `MemoryType` 与类型—级别约束。
- 标题取内容首个非空行并截断到 80 字符。
- slug 使用 `manual_<时间戳>_<短随机串>`，不调用 LLM。
- 通过现有 `stage`、`commit` 更新笔记和索引。

`clear()`：

1. 先取得 user/project 两级快照。
2. 为两个 Store 构造空笔记、空索引状态。
3. 依次提交；任一失败时恢复两级快照。
4. 只有两级均成功才向 UI 报告成功。

确认动作由 `UIController.requestConfirmation` 管理，Memory 层本身不感知按键。

### 3.6 运行期权限

`PermissionRuntime` 是命令与 Agent 共用的唯一运行期权限状态：

- 保存启动模式和当前模式。
- 保留配置规则的不可变列表。
- 维护按加入顺序排列的临时 `SESSION` 规则。
- `effectiveRules()` 返回“临时规则 → 配置规则”。
- `reset()` 清空临时规则并恢复启动模式。
- 不写 YAML，不改变永久路径授权。

启动一次 Agent Run 时取得不可变权限快照；该 Run 后续轮次始终使用同一快照。命令切换只影响之后启动的 Run。

### 3.7 状态汇总

`/status` handler 从 `CommandContext` 的惰性能力按需读取：

```text
MewCode 状态
─────────────
模式：[DEFAULT]
权限：default
模型：provider / model
Session：20260901-...
Token：45,230 / 200,000（23%）
工具：6 个已启用
记忆：user 3 条，project 5 条
MCP：2/2 已连接
工作目录：/home/user/project
版本：v0.1.0
```

工具数取 `ToolRegistry.getAll()` 当前快照，包含成功注册的 MCP 工具；MCP 状态复用现有连接和错误快照。

### 3.8 Prompt 命令

`/review` handler 返回固定 Prompt：

```text
检查当前工作区的 git diff。
重点识别缺陷、行为回归、安全风险和测试缺口。
先报告按严重度排序的发现，再给出简短总结。
```

若存在参数，追加：

```text
额外关注：<原始参数>
```

注册中心根据 `PROMPT` 类型调用 `UIController.sendUserMessage`，因此只有展开后的 Prompt 进入历史并消耗 Agent Loop Token。

## 4. 模块交互与数据流

### 4.1 启动注册

```text
MewCode.run
  → CommandRegistry.createDefault()
  → register 9 个命令
  → 每次 register 检查正式名称与别名
  → 成功后创建 MewCodeModel / Program
```

发生冲突时，异常在 TUI 启动前返回到应用入口，输出冲突标识并以非零状态结束。

### 4.2 用户提交

```text
Enter
  → 空白输入？直接返回
  → 首字符不是 "/"？
      → UIController.sendUserMessage(原始输入)
      → 现有 Agent Loop
  → 首字符是 "/"？
      → CommandRegistry.parse()
      → 未命中：系统错误 + /help
      → 命中：execute(handler)
```

执行结果按命令类型处理：

```text
LOCAL     → addSystemMessage(handler 返回值)
LOCAL_UI  → handler 调 UIController → 可选系统消息
PROMPT    → sendUserMessage(handler 返回的 Prompt)
```

任何已识别的斜杠输入都不会回退到普通 Agent 消息。

### 4.3 Tab 补全

```text
Tab
  → 检查光标是否位于首个 "/命令标识"
  → registry.search(prefix)
      → 0 项：不改输入
      → 1 项：替换为正式名称 + 空格
      → 多项：保存候选与选中下标
  → view() 在输入框上方渲染候选菜单
```

候选直接持有 `Command`，Enter 时无需再次按别名查找。

### 4.4 /clear

```text
Command handler
  → UIController.startNewConversation()
      → SessionManager.startNewSession()
      → ConversationManager.loadMessages([])
      → ContextManager.resetForSession(newDirectory)
      → 清空 MewCodeModel 的聊天投影、输入和补全状态
      → 发出 ClearScreen
      → 刷新状态栏
```

任一步在 Session 切换前失败时保留旧 Session；切换成功后旧 Session 仍可恢复。

### 4.5 /memory clear

```text
Command handler
  → UIController.requestConfirmation(...)
  → TUI 显示确认提示
      → n / Esc：丢弃 callback，不写文件
      → y：
          → MemoryManager.clear()
          → 两级 Store 快照、提交、必要时回滚
          → 成功消息 + refreshStatus()
```

等待确认期间不接受其他命令，避免 callback 与后续输入交叉。

### 4.6 /compact [重点]

```text
Command handler
  → 当前上下文估算
      → < 5000：显示无需压缩
      → >= 5000：
          → AgentTurnCoordinator.startManualCompaction(mode, focus)
          → ContextManager.forceCompact()
          → ConversationCompactor 直连 Provider 生成摘要
          → AgentEvent 更新 spinner、usage 和结果
```

这条路径使用 `AgentRun` 的异步事件设施，但不执行 ReAct Agent Loop、不调用工具、不写入命令原文。

### 4.7 /review [关注点]

```text
Command handler
  → 生成固定 Prompt + 可选关注点
  → UIController.sendUserMessage(prompt)
  → ConversationManager 追加展开后的 Prompt
  → 正常 Agent Loop
  → Agent 使用现有工具检查 git diff
```

### 4.8 权限快照

```text
/permission mode|add|reset
  → 修改 PermissionRuntime
  → refreshStatus()

下一次普通消息或 /review
  → AgentTurnCoordinator.startRun()
  → PermissionRuntime.snapshot()
  → 本次 Run 所有轮次复用该快照
```

已经运行中的 Agent 不受随后命令影响；Streaming 期间仍不接受普通命令。

## 5. 文件组织

```text
src/main/java/com/mewcode/
├── command/
│   ├── Command.java                 # 元数据与 CommandType
│   ├── CommandContext.java          # 函数式能力与嵌套 UIController
│   └── CommandRegistry.java         # 注册、冲突检测、解析、补全、9 个 handler
├── tui/
│   └── MewCodeModel.java            # 分流、补全菜单、确认状态、UIController 实现
├── tui/tea/
│   ├── Command.java                 # 增加 ClearScreen 效果
│   └── Program.java                 # 执行终端清屏
├── session/
│   └── SessionManager.java          # 增加安全的新 Session 切换
├── memory/
│   └── MemoryManager.java           # 概要、列表、手动添加、两级清理
├── compact/
│   ├── ContextManager.java          # 暴露当前估算与带重点压缩
│   └── ConversationCompactor.java   # 摘要请求接收可选保留重点
├── permission/
│   ├── PermissionRuntime.java       # 启动模式、当前模式、临时规则、快照
│   └── PermissionRuleEngine.java    # 支持有效规则快照及共享 Session 授权
├── agent/
│   └── AgentTurnCoordinator.java    # 带重点手动压缩、每 Run 权限快照
└── MewCode.java                     # 启动期创建注册表并报告冲突

src/test/java/com/mewcode/
├── command/
│   └── CommandRegistryTest.java     # 注册、冲突、解析、帮助、别名、补全
├── tui/
│   └── MewCodeModelTest.java        # 分流、菜单、确认、9 个命令集成
├── session/
│   └── SessionManagerTest.java      # 新建并切换空 Session
├── memory/
│   └── MemoryManagerTest.java       # 概要、添加、清理及回滚
├── compact/
│   ├── ContextManagerTest.java      # 5000 Token 阈值与估算
│   └── ConversationCompactorTest.java # 保留重点进入摘要请求
├── permission/
│   ├── PermissionRuntimeTest.java   # 模式、优先级、reset、快照
│   └── PermissionRuleEngineTest.java # 临时规则与 Session 授权
└── agent/
    └── AgentTurnCoordinatorTest.java # Prompt/压缩/权限快照链路
```

同时修改 `build.gradle.kts`，把新 `command` 包和对应测试纳入现有 Spotless 范围。

不新增以下文件或依赖：

- 不为 9 个命令分别创建 handler 类。
- 不新增 Dispatcher、Parser、CompletionService；这些逻辑留在 `CommandRegistry`。
- 不修改 `ToolRegistry`、`McpManager`、Provider 客户端或配置格式。
- 不新增第三方依赖。

## 6. 技术决策

| 决策点 | 选择 | 理由 |
|---|---|---|
| 命令模型 | Java `record` 保存不可变元数据 | 字段固定，无需继承或 Builder |
| Handler 存储 | `Function<CommandContext, String>` 与元数据分开存储 | 沿用参考设计，定义保持纯数据，注册时仍绑定在一起 |
| 注册顺序 | `LinkedHashMap` | 帮助和补全顺序稳定，无需额外排序配置 |
| 查找索引 | 正式名称 Map + 别名 Map | 保留“名称优先、别名其次”语义，同时避免每次线性扫描 |
| 大小写规则 | `Locale.ROOT` 小写归一化 | 避免系统 Locale 改变命令行为 |
| 输入解析 | 仅识别首字符 `/`，按第一个空格切分 | 严格满足 spec，不引入 shell quoting 或自然语言解析 |
| 依赖注入 | `CommandContext` 使用 JDK 函数接口 | 不传 Agent/TUI 业务对象，不增加 DI 框架 |
| UI 解耦 | `CommandContext.UIController` + 模型内效果队列 | 保持三个核心文件，避免包依赖环，也不暴露 Tea 或 ANSI |
| 异步命令 | 复用现有 `AgentRun`/事件轮询 | `/compact` 与 `/review` 不建立第二套异步设施 |
| 压缩阈值 | 固定 5000 Token 常量 | 已由 spec 明确，本期不增加配置项 |
| 压缩重点 | 作为摘要请求的独立指令段 | 不污染对话历史，也不和待摘要消息混在一起 |
| 新对话 | 创建新 Session，不删除旧 Session | `/clear` 可恢复且不破坏历史 |
| 手动 Memory | 首行标题 + 时间戳短随机 slug | 不调用 LLM，行为确定，并避免文件名碰撞 |
| Memory 清理 | 两级快照、提交失败回滚 | 用户确认后仍要避免只清掉一半 |
| 权限切换 | 进程内 `PermissionRuntime` | 不改配置文件，命令与 Agent 共用同一状态 |
| 权限一致性 | 每个 Agent Run 固定模式和规则快照 | 多轮工具调用期间策略不漂移 |
| Session 授权 | 权限快照共享现有授权集合 | 保留“本会话允许”跨 Agent Run 生效的现有语义 |
| 终端清屏 | 新增一个 Tea `ClearScreen` 效果 | ANSI 操作只存在于 `Program` |
| 错误处理 | 参数错误显示用法；运行错误显示脱敏通用消息 | 命令失败不退出 TUI，不泄露内部异常 |
| 测试 | JUnit + 现有 fake client/临时目录 | 不新增 Mock 框架或测试依赖 |

### 6.1 Spec 覆盖检查

| Spec | 设计归属 |
|---|---|
| F1–F4 | `Command`、`CommandRegistry` 注册与解析 |
| F5–F6 | `CommandContext`、嵌套 UIController、输入分流 |
| F7–F9 | 注册中心帮助、隐藏、别名和补全 |
| F10 | Context/Coordinator/Compactor |
| F11–F12 | UIController、SessionManager、Tea |
| F13 | SessionManager |
| F14 | MemoryManager |
| F15 | PermissionRuntime、PermissionRuleEngine |
| F16 | CommandContext 状态 Supplier |
| F17 | PROMPT handler、现有 Agent Loop |
| F18 | 默认注册表与未知命令路径 |
| N1–N13 | 类型分流、启动检查、函数式上下文、安全错误、测试方案 |

依赖方向保持单向：

```text
tui → command（包含 UIController 抽象）
tui → 现有领域服务
agent → permission runtime snapshot
领域服务不依赖 command 或 tui
```
