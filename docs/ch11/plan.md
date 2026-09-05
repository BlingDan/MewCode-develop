# MewCode Skill 系统 Plan

> 状态：已确认
>
> 上游文档：[spec.md](./spec.md)

## 架构概览

本期增加一个具体的 `com.mewcode.skill` 包，不建设通用插件框架。它负责 Skill 定义解析、三级目录合并、一次请求内的激活状态、参数渲染、脚本工具和 fork 执行。现有命令、提示、工具、权限、Provider 与 TUI 只增加必要的接入点。

核心运行关系如下：

```text
内置 / 用户 / 项目 Skill
          │ 解析、覆盖、校验、热刷新
          ▼
     SkillCatalog ───────→ 动态斜杠命令
          │
          ├── 启动摘要 ─→ PromptRequestFactory
          │
          └── 完整定义 ─→ SkillRun（单次请求）
                              │
                 ┌────────────┴────────────┐
                 ▼                         ▼
          shared 主 Agent             fork 临时 Agent
                 │                         │
                 └────── 工具策略 / Provider 路由 ──────┘
```

采用两类快照：

- `SkillCatalog` 可在 Tab、斜杠提交和 `LoadSkill` 调用前热刷新。
- 已放入某个 `SkillRun` 的 `SkillDefinition` 是不可变快照，磁盘变化不会改变正在运行的 SOP、工具或 Provider；同一请求显式再次加载同名 Skill 时，才用新定义替换旧定义。

## 核心数据结构

### `SkillDefinition`

```java
public record SkillDefinition(
    SkillMeta meta,
    String body,
    SkillSource source,
    Path entry,
    Path directory,
    List<SkillToolSpec> tools) {}

public record SkillMeta(
    String name,
    String description,
    List<String> tools,
    SkillMode mode,
    ForkContext context,
    int contextCount,
    String model) {}

public enum SkillMode { SHARED, FORK }
public enum ForkContext { NONE, RECENT, FULL }
public enum SkillSource { BUILTIN, USER, PROJECT }
```

默认值为 `mode=SHARED`、`context=NONE`、`contextCount=3`、`tools=[]`、`model=null`。名称归一化为小写，必须匹配 `[a-z0-9][a-z0-9_-]*`。

### `SkillToolSpec`

目录型 Skill 可在入口同级声明 `tool.json`：

```json
{
  "tools": [
    {
      "name": "inspect_report",
      "description": "检查报告并返回摘要",
      "input_schema": {
        "type": "object",
        "properties": {"path": {"type": "string"}},
        "required": ["path"]
      },
      "script": "tools/inspect_report.py"
    }
  ]
}
```

```java
public record SkillToolSpec(
    String name,
    String description,
    Map<String, Object> inputSchema,
    Path executable) {}
```

脚本路径必须是 Skill 目录内的普通文件，具有可执行权限且首行是合法 shebang。所有脚本工具固定为 shell 类、非只读、可能破坏、不可并发；Skill 作者不能自行降低风险级别。

### `SkillCatalog`

```java
public final class SkillCatalog {
  public static SkillCatalog load(Path projectRoot, Path userHome);
  public RefreshResult refresh(Set<String> ordinaryToolNames, Set<String> reservedCommands);
  public Optional<SkillDefinition> find(String name);
  public List<SkillDefinition> list();
  public String promptSummary();
}

public record RefreshResult(
    boolean changed,
    List<SkillDefinition> skills,
    List<ScriptTool> scriptTools,
    List<String> diagnostics,
    List<MissingTool> missingTools) {}
```

Catalog 内部保存不可变、有序快照，并只在候选快照完整解析和校验后原子替换。启动时最终胜出定义的 `missingTools` 是致命错误；热更新时跳过引用未知工具的无效定义，重新按优先级选择低层有效版本，并报告诊断。

### `SkillRun`

```java
public final class SkillRun {
  public void activate(SkillDefinition skill, String arguments);
  public List<ActiveSkill> activeSkills();
  public Set<String> allowedTools();
  public Optional<String> preferredProvider();
  public String promptBlock();
  public void clear();
}

public record ActiveSkill(SkillDefinition definition, String renderedBody) {}
```

使用 `LinkedHashMap` 保持激活顺序。同名再次激活时原位替换；工具白名单为全部激活项的去重并集；最后激活且声明 `model` 的 Skill 决定下一模型轮次的 Provider 偏好。

### `ProviderRouter`

```java
public final class ProviderRouter {
  public ProviderRoute main();
  public ProviderRoute select(String preferredName);
}

public record ProviderRoute(
    ProviderConfig config,
    LlmClient client,
    ToolApiProtocol protocol,
    boolean fallback) {}
```

`select` 按 Provider 配置的唯一 `name` 查找。未指定或未配置时返回主 Provider；已配置 Provider 的客户端按需创建并缓存。一次模型轮次的偏好 Provider 调用失败时，协调器只再尝试一次主 Provider。

### `SkillExecutor`

```java
public final class SkillExecutor {
  public SkillDefinition load(String name, String arguments, SkillRun run);
  public ForkResult runFork(
      SkillDefinition skill,
      String arguments,
      List<Message> mainHistory,
      AgentMode mode,
      AgentRun parentRun);
}

public record ForkResult(String summary, boolean error) {}
```

`load` 只负责刷新 Catalog、渲染参数并激活 shared Skill。`runFork` 构造临时会话与临时 `SkillRun`，同步等待临时 Agent 完成，并把最后一条完整 assistant 文本作为摘要。它不创建 Session、不触发 Memory/标题更新。

## 模块设计

### Skill 解析与 Catalog

**职责：** 解析 frontmatter、正文和可选 `tool.json`，合并三级来源并形成稳定快照。

**规则：**

1. 单文件入口是 `<skills>/<name>.md`；目录入口是 `<skills>/<name>/SKILL.md`。
2. 内置固定读取 classpath 中 `commit`、`review`、`test` 三个已知入口，不扫描 JAR、不解压缓存。
3. 用户目录为 `~/.mewcode/skills`，项目目录为 `<project>/.mewcode/skills`。
4. 每层按路径字典序解析，再按内置、用户、项目覆盖；无效高优先级定义不会遮住低优先级有效定义。
5. frontmatter 仅接受文件开头两条独占行 `---` 之间的 YAML；使用现有 SnakeYAML 手动绑定并拒绝未知类型。
6. `tool.json` 使用现有 Jackson。JSON Schema 只校验当前调用需要的常用子集：`type`、`properties`、`required`、`items`、`enum`、`additionalProperties`。
7. 解析与诊断不输出 SOP 正文、脚本输出或环境值。

### `ScriptTool`

**职责：** 把一个 `SkillToolSpec` 适配为现有 `Tool`。

**执行流程：**

1. 执行前再次确认脚本仍位于 Skill 目录内、是普通可执行文件并有 shebang。
2. 复用工具输入校验入口检查 JSON Schema。
3. 工作目录设为 Skill 目录；stdin 写入 UTF-8 JSON 参数。
4. 通过 `CommandRunner` 的窄扩展复用现有超时、取消和进程清理。
5. stdout、stderr 分离并限长；stdout 必须是单个 JSON 对象：`{"content":"...","is_error":false}`。
6. 非零退出、非法 JSON、超时和取消转换成安全 `ToolResult`；stderr 只用于脱敏诊断，不原样回灌。

脚本进程只显式继承 `PATH`、`TMPDIR` 和 `MEWCODE_PROJECT_ROOT`，不把 Provider/MCP 凭据注入子进程。

### 工具注册与限制

`ToolRegistry` 将工具分成普通工具、MCP 工具和当前 Catalog 的脚本工具，增加原子替换脚本工具的方法。名字与任何已存在工具冲突的脚本定义无效。

`Tool` 增加 `default boolean isSystem()`，默认 `false`；只有 `LoadSkill` 返回 `true`。

`ToolPolicy` 同时接收 `AgentMode` 和当前 `SkillRun`：

```text
系统工具：始终允许
没有激活 Skill：沿用现有模式限制
有激活 Skill：(白名单并集) ∩ 当前模式限制
```

同一个 `ToolPolicy` 同时用于生成 Provider schemas 和 `ToolExecutor` 的本地执行检查。权限模式下也必须先过 Policy，再进入 `PermissionGate`，修复当前权限路径绕过模式过滤的问题。

同一模型响应中若同时包含 `LoadSkill` 和普通工具调用，本轮只执行全部 `LoadSkill`，普通调用返回“Skill 已更新，请在下一轮重新选择工具”的错误，避免按旧白名单执行。

### 提示两阶段加载

`PromptAdditions` 增加两个字符串字段：

- `skillCatalog`：最终生效 Skill 的名称和一句说明。
- `activeSkills`：本次请求已激活的完整、已渲染 SOP。

`PromptRequestFactory` 的 system segments 顺序为：稳定系统提示 → Skill 摘要 → Memory/恢复信息 → Active Skill SOP。Active Skill 块永远最后追加，以保证每轮重建都位于最显眼位置。未激活项的正文、schema 和脚本内容不会进入请求。

### shared 执行

斜杠命令 `/name args` 在提交前刷新 Catalog，取得不可变定义并创建本次 `SkillRun`。原始斜杠输入作为主历史用户消息，SOP 作为 system addition，而不是伪装成用户消息。自然语言请求由模型先看到摘要，再调用 `LoadSkill` 激活；下一模型轮次重算提示、工具和 Provider。

shared 请求结束、失败或取消时在 `finally` 清空 `SkillRun`。清空只移除激活项，不修改主历史、Catalog 或磁盘文件。

### fork 执行

fork 使用独立 `ConversationManager` 和 `ContextManager`：

- `none`：不复制主历史。
- `recent`：从主历史向前寻找最近 N 个用户轮次边界，复制完整消息段，工具调用与结果保持成对。
- `full`：复制调用前的完整主历史，不额外摘要。

随后把本次参数作为临时会话的用户消息，把渲染后的 SOP 放入临时 `SkillRun`。临时 Agent 复用 ToolRegistry、权限运行时、ProviderRouter 和取消 token，但不接 Session、Memory 或完成监听器。主流程同步等待 fork 完成；子运行的流式文本、工具状态与权限请求转发到父 `AgentRun`。

成功时取临时历史最后一条 assistant 文本作为结果摘要；失败、取消、超时时生成安全摘要。直接斜杠调用通过 `ConversationManager` 的原子方法一次写入“原始 slash 用户消息 + assistant 摘要”；Agent 内部通过 `LoadSkill` 发起的 fork 把摘要作为该工具结果回灌，最终仍由主 Agent 形成主回复。fork 内部消息和工具调用不写入主历史。

fork 可以在临时运行中继续调用 `LoadSkill`；嵌套 fork 被拒绝，避免递归运行和后台残留。

### Provider 路由与回退

每个模型轮次开始时从 `SkillRun.preferredProvider()` 选路由：未指定时使用主 Provider；指定且可配置时使用该 Provider。shared 后激活的 Skill 从下一轮生效，fork 只改变临时 Agent。

偏好 Provider 的流在完整结束前失败时，不提交 assistant 或工具回合，发送 `ProviderFallback` 事件清除 TUI 中该尝试的临时流文本，然后用相同历史、提示和工具重新请求主 Provider。每轮最多回退一次；主 Provider 再失败则终止。两次尝试中 Provider 已上报的 token 都计入总用量。

主 Agent 继续使用主 Provider 对应的 ContextManager 做压缩预算；偏好 Provider 报上下文长度错误也直接走主 Provider，不在偏好 Provider 上再次压缩重试。

### 斜杠命令与热更新

`CommandRegistry` 保留静态命令表，增加可整体替换的动态 Skill 命令表。动态命令无别名，帮助文本和补全说明直接来自 Catalog。删除硬编码 `/review` 及 `/r`。

Tab、斜杠提交和 `LoadSkill` 先触发刷新。刷新会隔离解析失败或引用未知工具的定义，重新计算三级覆盖结果，再整体替换 Catalog、脚本工具和动态命令；低层没有有效版本时该 Skill 从新快照移除。系统命令名及其别名均为保留标识，冲突 Skill 被跳过。

### 启动、清理与内置样板

启动顺序固定为：配置与权限 → 普通工具和 `LoadSkill` → Skill 候选解析及脚本工具 → 校验普通/脚本白名单。只有白名单仍缺少 `mcp_*` 工具时才在进入 TUI 前同步发现 MCP 并再次校验；没有 Skill 依赖的 MCP 沿用 `MewCodeModel` 的后台初始化，界面显示“连接中”且保持可交互。白名单缺失工具时打印全部 `Skill/工具` 对并返回退出码 2。

`MewCodeModel` 接收已初始化的 Catalog、ToolRegistry 与 McpManager，Provider 切换时复用它们，不重复连接 MCP。退出时关闭 MCP 和工具执行器。`/clear` 取消当前运行并清空请求态；Catalog、Provider 配置、Memory、MCP 和磁盘文件保留。

内置资源：

- `commit`：shared；`Bash`、`ReadFile`、`Grep`。
- `review`：fork + none；`ReadFile`、`Grep`、`Glob`、`Bash`。
- `test`：shared；`Bash`、`ReadFile`、`Grep`、`Glob`。

## 模块交互

### 启动

```text
MewCode
  → ToolRegistry.createDefault + LoadSkill
  → SkillCatalog.load（三级解析）
  → ToolRegistry.replaceSkillTools
  → SkillCatalog.validateAllowlist（普通/脚本）
  → 若缺少 mcp_*：McpManager.connectAll（同步发现）并再次校验
  → 失败：打印缺失工具并退出 2
  → 成功：构造 MewCodeModel，进入 TUI；无关 MCP 后台连接
```

### shared 斜杠调用

```text
用户 /commit 参数
  → 刷新 Catalog/脚本工具/动态命令
  → 创建 SkillRun 并激活 commit
  → 主历史写入原始 slash 用户消息
  → 每轮：Active SOP → ProviderRouter → ToolPolicy → Provider
  → 无工具回复 / 错误 / 取消
  → finally 清空 SkillRun
```

### Agent 按需加载

```text
普通用户请求
  → Provider 只看到 Skill 摘要
  → 调用 LoadSkill{name, arguments}
  → 刷新并激活 shared Skill
  → 回传已加载及被模式过滤的工具
  → 下一轮携带完整 SOP、收窄工具、应用 Provider 偏好
```

### fork 调用

```text
/review 参数 或 LoadSkill 加载 fork Skill
  → 截取 none/recent/full 历史
  → 创建临时会话、SkillRun、ContextManager
  → 临时 Agent 阻塞运行，事件/权限/取消桥接父 Run
  → 取最终 assistant 文本或安全错误摘要
  → 直接 slash：原子写回主历史
  → Agent 内调用：作为 LoadSkill 工具结果回流
  → 丢弃全部临时状态
```

### Provider 回退

```text
本轮选择 Skill 指定 Provider
  → 成功：正常提交
  → 失败：清除本次临时流展示
           → 主 Provider 重试一次
              → 成功：正常提交
              → 失败：结束本次请求
```

## 文件组织

```text
src/main/java/com/mewcode/
├── skill/
│   ├── SkillDefinition.java   — 元信息、来源、模式、工具声明
│   ├── SkillParser.java       — Markdown/frontmatter/tool.json 解析
│   ├── SkillCatalog.java      — 三级发现、覆盖、快照与刷新
│   ├── SkillRun.java          — 单次请求激活态与 SOP 渲染
│   ├── ScriptTool.java        — 专属脚本工具适配
│   ├── SkillExecutor.java     — shared 激活与阻塞 fork
│   └── ProviderRouter.java    — Provider 选择、缓存与主路由
├── tool/impl/LoadSkillTool.java
├── agent/{AgentEvent,AgentTurnCoordinator,PromptAdditions,PromptRequestFactory,ToolPolicy}.java
├── tool/{Tool,ToolRegistry,ToolExecutor}.java
├── command/CommandRegistry.java
├── conversation/ConversationManager.java
├── tui/MewCodeModel.java
└── MewCode.java

src/main/resources/skills/builtin/
├── commit.md
├── review.md
└── test.md

src/test/java/com/mewcode/
├── skill/{SkillParserTest,SkillCatalogTest,SkillRunTest,ScriptToolTest,SkillExecutorTest,ProviderRouterTest}.java
├── tool/impl/LoadSkillToolTest.java
└── （扩展现有 agent/tool/command/tui/conversation 测试）
```

## 技术决策

| 决策点 | 选择 | 理由 |
|---|---|---|
| 包边界 | 单一具体 `com.mewcode.skill` 包 | 满足本期闭环，避免通用插件抽象 |
| YAML/JSON | 复用 SnakeYAML 与 Jackson | 已安装，无需新增依赖 |
| 内置发现 | 固定三个 classpath 资源名 | 不扫描 JAR、不落缓存，行为确定 |
| 热更新 | 触发式全量重扫、有效快照原子替换 | Skill 数量小；比 watcher 简单且满足需求 |
| 运行一致性 | Catalog 可刷新，已激活定义不可变 | 同时满足热更新和请求内稳定性 |
| 工具限制 | schemas 与执行入口共用 `ToolPolicy` | 防止只隐藏不拦截 |
| 系统工具 | `Tool.isSystem()` 默认方法 | 最小改动，`LoadSkill` 可绕过白名单和 Plan Mode |
| 脚本协议 | stdin/stdout JSON + shebang | 与语言无关，不安装运行时 |
| 脚本风险 | 固定高风险、串行 | Skill 元数据不能降低宿主安全策略 |
| shared 历史 | 原始请求进历史，SOP 进 system segment | 保留用户语义且避免提示伪装 |
| fork 结果 | 最后一条完整 assistant 文本 | 无需第二次模型摘要，减少成本与失败点 |
| fork 调度 | 阻塞、串行、拒绝嵌套 fork | 与已确认语义一致，避免并行生命周期 |
| Provider | 每轮路由，失败回主 Provider 一次 | 支持多 Skill 切换且避免重试环 |
| full 上下文 | 原样复制完整主历史 | `full` 语义明确；不偷换成摘要 |
| recent 上下文 | 按完整用户轮次边界截取，默认 3 | 避免孤立工具消息 |
| 动态命令 | 整体替换，无 alias | 防冲突且无额外命令 DSL |
| 启动 MCP | Skill 依赖同步发现，无关 MCP 后台连接 | 保住依赖校验，同时不让无关 MCP 阻塞 TUI |
| 分发/安装 | 本期不实现 | 已在 spec 明确留给后续 |

## Spec 覆盖

- F1–F4：`SkillDefinition`、`SkillParser`、`SkillCatalog`。
- F5–F9：`PromptAdditions`、`SkillRun`、`LoadSkillTool`、`ToolPolicy`。
- F10：Catalog 先校验普通/脚本工具，仅对未解析的 `mcp_*` 依赖同步发现，其他 MCP 后台连接。
- F11–F13：`SkillExecutor`、`ProviderRouter` 与每轮 Agent 路由。
- F14–F15：`ScriptTool`、`CommandRunner` 与现有权限链。
- F16–F18：动态 `CommandRegistry`、内置资源和触发式刷新。

没有未归属的功能需求；模块依赖由入口层向具体组件单向组装，不引入循环接口或预留扩展层。
