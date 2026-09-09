# Hook 生命周期挂钩系统 Plan

> 状态：已确认；进入任务拆解阶段。
>
> 依据：已批准的 [spec.md](./spec.md)。四份文档全部批准前不编写实现代码。
>
> 技术基础：Java 21、现有 SnakeYAML、Jackson、JUnit、Gradle、OS shell 沙箱。

## 架构概览

采用集中 Hook 引擎，由生命周期调用方直接调用分派接口。已有 `AgentEvent` 继续负责 UI 事件传递，不改成通用事件总线，也不依赖异步 UI 订阅实现同步拦截。

| 组件 | 职责 |
|---|---|
| `RuleMatcher` | 提取并扩展现有权限匹配能力，供权限规则和 Hook 共用 |
| `HookConfigLoader` | 两级 YAML 加载、来源记录、集中校验、同名冲突处理 |
| `HookEngine` | 按事件匹配和顺序调度、汇总拒绝、失败隔离、后台任务管理 |
| `HookSessionState` | 会话内一次性状态、执行中状态、reminder 队列与生命周期 |
| `HookActionExecutor` | 集中执行 shell、prompt、HTTP、subagent 占位动作 |
| 现有应用与 Agent 模块 | 在真实生命周期节点触发事件，处理拒绝与请求组装 |

规则、条件、事件快照在创建后不可变；只有会话状态和后台任务集合可变。状态对象显式随事件传递，后台动作不通过全局“当前会话”寻找写入目标。

依赖方向：权限和 Hook 依赖共用匹配器；Hook 使用已有取消、命令执行和 JSON 能力；应用、TUI、Agent 和工具入口调用 Hook。Hook 不依赖 TUI 或 Agent 协调器，不回调模型工具入口。

## 核心数据结构与接口

以下签名用于约束实现，不是可独立编译的实现代码；record 的匹配方法体和构造校验在开发阶段补齐。

### 共用匹配器

位置：`permission/RuleMatcher.java`。

```java
public sealed interface RuleMatcher {
    boolean matches(String value);
    static RuleMatcher parse(Map<?, ?> definition);
    static RuleMatcher glob(String value);

    record Exact(String value) implements RuleMatcher {}
    record Glob(String value) implements RuleMatcher {}
    record Regex(Pattern pattern) implements RuleMatcher {}
    record Not(RuleMatcher inner) implements RuleMatcher {}
}
```

- `Exact` 整串、区分大小写比较；`Glob` 保持旧 `*`、`?` 及 DOTALL 语义；`Regex` 使用 `find()`，锚点由配置明确声明；`Not` 包装内部匹配。
- glob 和 regex 在加载时完成必要编译；运行时复用。旧精确字符串仍可使用直接相等比较。
- `parse` 只解析匹配结构，非法定义抛出 `IllegalArgumentException`。权限加载器将其转为启动配置错误，Hook 加载器将其转为单规则诊断。
- Hook 字段缺失、null 或非标量时，在调用匹配器之前返回不匹配，不能让缺失值进入 `Not`。

### 权限规则配置与兼容

```yaml
rules:
  # 旧字符串永远按原有语法解释
  - pattern: "Bash(git *)"
    decision: allow

  # 新增结构化格式
  - tool: Bash
    match:
      type: regex
      value: "^git (status|diff)$"
    decision: allow
```

- 一条规则只能使用 `pattern` 或 `tool + match` 一种写法；混用、缺字段、非法匹配直接导致权限配置加载失败。
- 旧字符串中的 `=`、`!`、`~` 不获得新的前缀含义。
- `PermissionRule` 内部统一保存工具名、匹配器、决定、来源和供诊断使用的显示文本。保留旧构造器、`of` 和 `pattern()` 的旧调用兼容；为结构化规则增加创建入口。
- `PermissionRuleEngine.match` 继续使用原有目标提取及路径规范化，仅替换底层匹配调用。会话授权和规则顺序保持原样。
- `/permission add` 的字符串输入继续走旧语法；本期不额外设计命令行结构化输入语言。

### 规则、条件与动作

```java
public record HookRule(
    String name,
    HookEvent event,
    Optional<HookCondition> condition,
    HookAction action,
    boolean onlyOnce,
    boolean async,
    Duration timeout,
    Path source
) {
    public record HookCondition(Combination combination, List<FieldMatch> fields) {
        public enum Combination { ALL_OF, ANY_OF }
        public record FieldMatch(String field, RuleMatcher matcher) {}
        public boolean matches(Map<String, Object> payload);
    }
}

public sealed interface HookAction {
    record Shell(String command) implements HookAction {}
    record Prompt(String text) implements HookAction {}
    record Http(
        URI url,
        String method,
        Map<String, String> headers,
        Optional<String> body
    ) implements HookAction {}
    record Subagent(String agentName, String prompt) implements HookAction {}
}
```

- `HookCondition`、`FieldMatch` 嵌在 `HookRule`，不增加独立条件服务。
- 条件数组非空，只能选 `ALL_OF` 或 `ANY_OF`；一个条件也使用单元素数组。
- 字段路径逐级访问 Map；字符串原样匹配，布尔值使用 `true/false`，数字使用 JSON 标量文本；对象、数组、缺失和 null 不匹配。
- 四种动作只定义数据，集中由同一个执行器分支处理，不引入动作工厂或插件注册表。
- 所有集合防御性复制；加载后不可修改动作、条件或来源。

### 事件、调用与拒绝

```java
public enum HookEvent {
    SESSION_START, SESSION_END, SESSION_RESUME,
    USER_PROMPT_SUBMIT, TURN_START, STOP,
    MESSAGE_START, MESSAGE_END, PRE_TOOL_USE, POST_TOOL_USE,
    STARTUP, SHUTDOWN, ERROR, COMPACT;

    public String configName();
    public boolean blocking();
    public static HookEvent parse(String value);
}

public record HookInvocation(
    HookEvent event,
    Map<String, Object> payload,
    HookSessionState state,
    CancellationToken cancellation
) {}

public record HookRejection(String hookName, String reason) {}
```

- 枚举的配置名严格使用 Spec 的大小写；不开放别名。只有 `PreToolUse` 和 `UserPromptSubmit` 的 `blocking()` 为真。
- `HookInvocation` 对 payload 递归复制 Map/List，形成不可变快照；通用字段在构造时统一填入，`event` 与枚举一致。
- `HookRejection` 要求名称、原因非空白。没有拒绝用 `Optional.empty()`，不将普通失败映射为拒绝。

### 会话状态与 reminder 批次

```java
public final class HookSessionState {
    public boolean tryStartOnce(String hookName);
    public void markExecuted(String hookName);
    public void enqueuePrompt(String text);
    public ReminderBatch snapshotPrompts();
    public void consumePrompts(ReminderBatch batch);
    public void close();

    public record ReminderBatch(long throughSequence, List<String> texts) {}
}
```

- 内部使用短同步区维护执行中集合、已执行集合、有序提示词队列、递增序号和关闭标记；不在锁内执行外部动作。
- `tryStartOnce` 在未关闭、未执行、未执行中的情况下原子占用。动作开始后的 `finally` 调用 `markExecuted`，失败也算执行；异步提交失败且动作未启动时撤销占用。
- `snapshotPrompts` 不消费；`consumePrompts` 仅删除该批次序号及之前的条目，保留之后到达的异步注入。批次只允许用于产生它的状态对象。
- `close` 幂等关闭并清空队列，之后拒绝占用和注入；重复消费无副作用。
- 启动阶段先创建一个状态对象，首个会话沿用；后续新建或恢复会话换成新状态对象，旧状态永不复用。
- 无会话的退出阶段使用独立终止状态，不重新挂回任何已结束会话；其 prompt 没有后续请求时自然丢弃。

### 加载器与运行入口

```java
public final class HookConfigLoader {
    public static LoadedHooks load(
        Path projectRoot, Path userHome, Consumer<String> diagnostics);

    public record LoadedHooks(List<HookRule> rules, List<Path> sources) {}
}

public final class HookEngine implements AutoCloseable {
    public HookEngine(
        HookConfigLoader.LoadedHooks loaded,
        CommandRunner commandRunner,
        Consumer<String> diagnostics);
    public Optional<HookRejection> dispatch(HookInvocation invocation);
    public HookConfigLoader.LoadedHooks loaded();
    public void cancelSession(HookSessionState state);
    public void close();
}

public final class HookActionExecutor {
    public HookActionExecutor(CommandRunner commandRunner, HttpClient httpClient);
    public Optional<HookRejection> execute(HookRule rule, HookInvocation invocation)
        throws IOException, InterruptedException;
}
```

- Engine 持有一个虚拟线程后台执行器和一个共享 HTTP 客户端，并构造动作执行器。动作异常在分派边界转为安全诊断。
- 每个异步任务记录所属状态、取消 token 和 Future。会话关闭取消对应任务，进程退出取消全部任务。
- 无 Hook 场景传入空的 `LoadedHooks`，沿同一路径快速返回；不增加一套无操作引擎实现。
- 同步 dispatch 等待动作结果；异步 dispatch 提交后继续下一条规则，不等待或接受迟到的拒绝。
- 异步提交与 once 占用之间的竞态须受控；未成功提交的任务不得永远停留在执行中。
- `close` 幂等，停止后台提交、取消任务并最多等待 5 秒；不调用可能无限等待的默认执行器 `close()`。
- `/hooks` 从 `loaded()` 读取，不提供重载或重置入口。

## 配置与动作协议

### 配置文件

顺序加载 `<projectRoot>/.mewcode/hooks.yaml`、`<userHome>/.mewcode/hooks.yaml`。使用已有 SnakeYAML 的安全对象加载方式，禁止任意类型构造、重复键；非法文件隔离，非法单条定义隔离。同名比较区分大小写，先加载的合法定义占用名称。

```yaml
hooks:
  - name: check-command
    event: PreToolUse
    if:
      all_of:
        - field: tool_name
          match: {type: exact, value: Bash}
        - field: tool_input.command
          match: {type: regex, value: "^git push"}
    action:
      type: shell
      command: "echo '请先完成检查' >&2; exit 2"
    only_once: false
    async: false
    timeout: 30s
```

- 时长接受正整数加 `ms`、`s`、`m`；拒绝溢出、零、负数及未知单位。
- 动作必填字符串拒绝空白；HTTP 验证 URI、请求方法及请求头；匹配结构和 `not.inner` 递归校验。
- Hook 加载诊断输出文件、条目索引或名称和安全原因，不回显整份 YAML、命令正文或请求头。
- 来源列表记录成功解析为合法顶层 Hook 文档的文件；条目全部被跳过时仍可列出该来源。

### shell

扩展 `CommandRunner`，加入接收原始配置命令、事件 JSON、工作目录、超时与取消 token 的入口，复用 `ScriptResult`：

```java
public ScriptResult runHook(
    String command, Path workingDirectory, String input,
    Duration timeout, CancellationToken cancellation
) throws IOException;
```

- 直接使用已有 OS 沙箱准备进程，写范围保持项目目录，不经 `Bash` 模型工具或权限审批回路。
- 沙箱不可用按 Hook 动作失败处理，不退回裸执行。
- 并发启动 stdout、stderr 读取及 stdin 写入；截止时间覆盖进程启动后的全部 I/O 和等待，不先阻塞写完 stdin 才开始计时。
- 两个输出通道分别有界，复用现有 20,000 字符上限。异常退出、取消和超时清理子进程及管道，有界等待读取线程。
- 同步拦截时 `exit=2` 且有非空原因才返回拒绝；优先 stderr，否则 stdout；输出截断导致无法可靠判定时按动作失败处理。其他非零退出不借用 Bash 工具的 grep/diff 例外规则。
- 共享底层读取、等待与清理代码时，保留既有 Bash 和 Skill 脚本公开行为。

### HTTP

- 使用 JDK `HttpClient`，默认 `POST`；无 body 时发送事件 JSON 并默认 `Content-Type: application/json`，用户配置的同名请求头优先。
- 显式 body 仅替换 `${field}` 和 `${nested.path}`，按字段标量文本原样替换，不再次扫描替换出来的内容。用户负责显式模板的目标格式，不自动猜测 JSON 转义。
- 缺失字段、null、非标量插值视为渲染失败，不发送请求。
- 请求和响应读取使用同一截止时间，响应体有界读取；超过 20,000 字符或截断均视为动作失败，不能解析残缺拒绝响应。
- 仅 2xx、合法 JSON 对象、`decision == "block"`、非空字符串 `reason` 表达拦截。合法非拒绝响应放行，非 2xx 或畸形响应输出安全诊断。
- 不将请求头、完整 URL 查询串、请求体或远端错误正文直接写入失败日志。明确配置的拒绝原因按业务协议回流。
- 取消时结束 HTTP Future/响应流；不重试，不自动跟随重定向。

### prompt 与 subagent

- prompt 只调用事件状态的 `enqueuePrompt(text)`；不改变系统提示稳定区，不创建模型请求，不返回拒绝。
- subagent 只输出 `[hook subagent] not yet implemented, skipped: <name>`，不调用现有 Skill fork。

## 事件 payload

通用字段：`event: string`、`cwd: string`；适用时包含 `session_id: string` 和 `mode: string`。省略未知字段，不填空字符串或虚构默认值。

| 事件 | 专属字段 |
|---|---|
| `SessionStart` / `SessionEnd` / `SessionResume` | `reason: startup / clear / resume / shutdown` |
| `UserPromptSubmit` | `prompt: string` |
| `TurnStart` | `prompt: string`、`request_id: string` |
| `Stop` | `request_id: string`、`iterations: integer` |
| `MessageStart` | `request_id`、`iteration: integer`、`attempt: integer`、适用的 `provider`、`model`、`prompt` |
| `MessageEnd` | 相同调用标识；`status: success / error / cancelled`、可用的 `response: string`、`tool_calls: array`、失败时的 `error: string` |
| `PreToolUse` | `request_id`、`tool_use_id: string`、`tool_name: string`、`tool_input: object` |
| `PostToolUse` | 相同工具字段；`tool_result: string`、`is_error: boolean`、`status: success / error / cancelled / denied`、`duration_ms: integer` |
| `startup` / `shutdown` | 无额外字段 |
| `error` | `source: string`、`message: string`、适用的请求标识 |
| `compact` | `trigger: auto / manual / emergency`、可用的 `before_tokens: integer`、`after_tokens: integer` |

- `request_id` 为本次 Agent 请求的稳定标识；提交检查尚无 Agent 请求时不伪造 ID。
- `iteration` 为模型轮次，`attempt` 区分同轮的实际发送尝试，包含 Provider 回退与紧急恢复重试。
- `response` 仅为可见文本，不包括内部推理。`tool_calls` 每项使用 `tool_use_id`、`tool_name`、`tool_input`。
- `mode` 使用 PermissionMode 的配置名称；工具名使用实际注册名称，如 `WriteFile`、`Bash`。
- `PostToolUse` 的文本与准备回写模型的最终结果一致；错误分类由结果元数据明确传递，不用中文错误字符串猜测是否权限拒绝。
- payload 稳定序列化为 JSON；条件与动作读取同一份快照，不包含宿主凭据。

## 模块交互

### 启动、会话与关闭

1. `MewCode` 完成原有配置和权限加载，加载 Hook 并创建引擎、启动状态；Hook 初始化不得改掉原有启动错误策略。
2. 初始化必要组件后先触发 `startup`，再将启动状态绑定首个会话，在环境上下文就绪且接受首条输入前触发 `SessionStart`。
3. `MewCodeModel` 持有引擎和当前 Hook 状态，Provider 切换只更新运行信息，不重置会话 once 状态。
4. 新建/恢复先让 `SessionManager` 准备目标资源并验证成功，再在存储锁外触发旧会话 `SessionEnd`、关闭旧 Hook 状态并取消所属后台任务。
5. 提交存储切换、重置上下文，创建新 Hook 状态，分别触发 `SessionStart` 或 `SessionResume`。准备失败保留旧会话及状态；准备资源在取消或失败时释放。
6. `SessionManager` 将准备/提交方法及准备结果嵌在自身，保留原有 `startNewSession`、`resume` 入口作为兼容调用，不增加独立会话事务框架。准备结果仅可提交一次。
7. 正常退出先等待有超时的同步 `SessionEnd`，关闭会话状态；用终止状态触发 `shutdown`，随后关闭引擎及已有资源。关闭路径幂等，重复回调不重复触发生命周期事件。

当前实际恢复命令为 `/session resume <id>`。Spec 的 `/resume` 是恢复操作简称，实现和验收沿用现有命令，不增加别名。

### 用户提交

1. TUI 接受普通输入，保存原始文本和本次检查标识，进入可取消的提交检查状态。
2. 在后台运行 `UserPromptSubmit` 的同步 dispatch；UI 线程不等待 shell 或 HTTP，不展示正式用户气泡、不写对话历史。
3. 完成消息携带检查标识和拒绝结果回到 TUI；检查已取消、状态已切换或标识过期时忽略结果。
4. 拒绝则展示原因并恢复可编辑输入；放行才调用现有启动链，展示用户消息、写历史并触发一次 `TurnStart`。
5. 等待阶段不能重复提交，Esc/Ctrl+C 取消本次检查。Slash 输入按现有命令分派；Skill 启动的 Agent 请求仍触发轮次和模型事件。

### 模型请求、压缩与 reminder

1. 在每次实际请求尝试中触发 `MessageStart`，获取该会话 reminder 快照。
2. 扩展 `PromptAdditions` 增加 Hook reminder，保留旧构造调用；`PromptRequestFactory` 在已有 reminder 后追加文本，预检和实际请求使用同一快照。
3. 正常上下文预检包括 Hook token 成本；未发出请求不消费。
4. 若实际压缩完成，触发一次 `compact`，重新获取快照以包含同步压缩 Hook 的注入，重建请求并检查最终预算。仅重新估算，不因为新注入无限重复压缩；仍超预算则按原有上下文失败结束，保留未发送队列。
5. 最终快照确定后新到的异步文本留到下一次调用。开始调用 Provider 的 `openStream` 即视为一次发送尝试并消费该快照；进入该调用后抛出的同步错误也属于本次请求失败。
6. 从打开流到收集结束统一配对 `MessageEnd`；成功、Provider 错误及取消均有结束状态。预检失败没有实际发送，不伪造结束事件。
7. Provider 回退和紧急压缩恢复的下一次尝试重新触发 `MessageStart` 并取快照，不能复用已消费 Hook reminder 的旧 `PromptAdditions`。
8. 自动、手动、紧急三条压缩入口只在 `CompactResult.changed()` 为真时触发 `compact`；已有 UI 压缩事件不变。
9. 标题、记忆和压缩摘要等辅助模型调用不触发对话消息 Hook，也不消费对话 reminder。Skill fork 的独立 Agent 请求使用独立临时 Hook 状态，结束时释放，不污染父会话队列或 once 标记，不生成正式 Session 事件。

### 工具调用与批量调度

实际代码中 `ToolExecutor.isPermissionSafe()` 会在批次分类阶段调用权限闸门。因此只在 `executeSingle()` 内添加 Hook 太晚，无法满足“Hook 拒绝后权限检查从未发生”。

采用准备和终态两个集中步骤，作为现有工具调度入口的内部职责：

1. 取得已解析的调用及稳定调用位置，去除不合法的重复调用，按原始顺序执行每个适用调用的 `PreToolUse`。在这一步前不得调用 `isPermissionSafe` 或权限闸门。
2. 保存每个调用的前置检查结果。Hook 拒绝立即形成带原因的 `ToolInvocationResult`；放行者才进入原有分类、权限、校验和执行。批量路径向单次执行核心传递“已准备”状态，避免再次触发前置 Hook。
3. 现有安全并发和串行屏障保留，不把所有工具强制串行化；同一批各工具的前置 Hook 已按调用顺序准备，命令副作用不与权限预判倒序执行。
4. Skill 加载也先经过同一准备步骤，之后才进入现有 `executeSkillLoads` 分支；混合 Skill 批次的拒绝及现有错误均进入统一结果组装。
5. 超时、取消、未知工具、权限拒绝、Hook 拒绝等结果继续交给 `ToolResultAssembler` 按调用位置组装，不以不可靠的 tool ID 唯一性替代位置关系。
6. 组装出本轮最终回流结果后逐调用触发一次 `PostToolUse`，再按现有流程成对提交对话、发布 UI 结果。单次调用独立入口使用同一终态步骤，批量执行中的内部单次函数不再次发送终态 Hook。
7. 不因原始参数无法解析而伪造有效 `PreToolUse`；其错误结果仍能进入统一终态路径。取消后不再启动新的外部动作，但保留完整结果配对。
8. 后台工作线程不直接发布 Post 事件；由拥有最终结果的调用层发送，防止超时回收后工作线程迟到导致双重事件。Post 的动作失败不改变已确定的工具结果。

这些准备/结束辅助结构嵌在 `ToolExecutor`，通过协调器复用；不增加另一个工具调度服务。保留无 Hook 的旧重载，真实 Agent 路径显式传入调用状态。

### 自然完成与错误

- 只有已取得最终无工具回复的自然完成路径触发 `Stop`，然后执行原有完成通知。迭代上限、异常及取消不能因共用 `finish` 而误发 Stop。
- 主流程错误在现有统一错误收口处触发 `error`，每次实际错误只分派一次，再发布现有 UI 错误事件。
- Hook 内部只记录诊断，不调用主流程错误分派入口。用户取消不作为 error。

## 文件组织

项目根目录：`/Users/bytedance/IdeaProjects/Mewcode-develop`。

### 新增生产文件

```text
src/main/java/com/mewcode/
├── permission/RuleMatcher.java
├── config/HookConfigLoader.java
└── hook/
    ├── HookEvent.java
    ├── HookRule.java
    ├── HookAction.java
    ├── HookInvocation.java
    ├── HookRejection.java
    ├── HookSessionState.java
    ├── HookEngine.java
    └── HookActionExecutor.java
```

### 修改生产文件

| 文件（相对项目根目录） | 职责 |
|---|---|
| `src/main/java/com/mewcode/permission/PermissionRule.java` | 兼容旧创建入口，保存共用匹配结构 |
| `src/main/java/com/mewcode/permission/PermissionRuleEngine.java` | 使用匹配器，保持目标提取和判定顺序 |
| `src/main/java/com/mewcode/config/PermissionConfigLoader.java` | 接受互斥的旧、新格式，继续启动失败策略 |
| `src/main/java/com/mewcode/MewCode.java` | Hook 加载、资源组装、初始化失败清理 |
| `src/main/java/com/mewcode/tui/MewCodeModel.java` | 启停事件、异步提交检查、会话切换、Skill fork 状态、命令上下文 |
| `src/main/java/com/mewcode/session/SessionManager.java` | 准备/提交会话切换，避免在存储锁内等待 Hook |
| `src/main/java/com/mewcode/agent/AgentTurnCoordinator.java` | 轮次、实际模型尝试、压缩、错误、工具和 Skill 分支集成 |
| `src/main/java/com/mewcode/tool/ToolExecutor.java` | 前置准备先于权限分类、统一终态、保留批量调度 |
| `src/main/java/com/mewcode/agent/PromptAdditions.java` | Hook reminder 快照字段及旧构造兼容 |
| `src/main/java/com/mewcode/agent/PromptRequestFactory.java` | reminder 合并与预算预检使用同一内容 |
| `src/main/java/com/mewcode/tool/support/CommandRunner.java` | 复用 shell 执行，完整 stdin/stdout/stderr 超时取消 |
| `src/main/java/com/mewcode/command/CommandRegistry.java` | 静态注册 `/hooks`，纳入帮助、补全和 Skill 保留名 |
| `src/main/java/com/mewcode/command/CommandContext.java` | 增加只读 Hook 列表 Supplier |
| `build.gradle.kts` | 将新增文件及本期修改文件纳入现有 Spotless 范围，不增加依赖 |

`ToolResultAssembler`、`ContextManager` 和 Provider 客户端优先直接复用，不新增事件职责。Hook 侧不改变已有 JSONL 持久化格式。

### 测试文件

新增：

- `src/test/java/com/mewcode/permission/RuleMatcherTest.java`
- `src/test/java/com/mewcode/config/HookConfigLoaderTest.java`
- `src/test/java/com/mewcode/hook/HookEngineTest.java`：覆盖分派、条件、once、队列与状态隔离。
- `src/test/java/com/mewcode/hook/HookActionExecutorTest.java`：真实小进程和本地 HTTP 服务验证动作协议、超时与取消。
- `src/test/java/com/mewcode/agent/AgentTurnCoordinatorHookTest.java`：捕获实际模型请求和工具回流验证完整事件链。

补充现有测试：权限加载和匹配、`PermissionToolExecutorTest`、`ToolExecutorTest`、`AgentTurnCoordinatorSkillTest`、`PromptRequestFactoryTest`、`SessionManagerTest`、`MewCodeModelTest`、`CommandRegistryTest`、`MewCodeTest`。按行为补用例，不逐方法复制实现。

## 技术决策

| 决策点 | 选择 | 理由 |
|---|---|---|
| 分派 | 直接调用一个 HookEngine | 同步取得拒绝结果，复用原有 UI 流 |
| 配置扩展 | 旧 pattern 与新 tool/match 互斥 | 不重解释旧合法字面值 |
| 动作 | 一个执行器处理四种数据类型 | 固定范围，无需插件体系和工厂 |
| 依赖 | 已有 SnakeYAML、Jackson，JDK HttpClient | 不引入新库 |
| shell | 复用 CommandRunner 和 OS 沙箱 | 保留现有项目边界，不递归调用模型工具 |
| I/O | 并行管道、统一截止时间、有界输出 | 防止大 stdin 或输出阻塞使 timeout 失效 |
| once | 短锁内占用，执行后完成 | 并发和异步下也最多执行一次 |
| 会话 | 显式状态引用，切换时换对象 | 旧异步结果不能写入新会话 |
| reminder | 序号快照，发送时消费 | 预检失败不丢提示，新注入不被误消费 |
| 请求回退 | 每次实际尝试重新组装 Hook additions | 已消费的 reminder 不因复用旧快照重复发送 |
| 工具批量 | Hook 准备先于权限分类 | 满足真正的权限前拦截，保持安全并发 |
| 关闭 | 取消后台任务，最多等待 5 秒 | 不依赖无限等待的执行器关闭 |
| 事件异常 | Hook 只记录诊断 | 不递归触发 error，不掩盖主流程结果 |
| 配置查看 | 现有命令上下文读取 LoadedHooks | 无需专门查询服务或热重载 |

## Spec 覆盖

| Spec 功能 | 设计归属 | 主要验收 |
|---|---|---|
| F1 匹配与权限兼容 | RuleMatcher、PermissionRule、PermissionRuleEngine、PermissionConfigLoader | AC1、AC2 |
| F2 规则与加载 | HookConfigLoader、LoadedHooks | AC2、AC3、AC4 |
| F3 生命周期事件 | HookEvent、应用/TUI、Agent 协调器、工具入口 | AC6—AC12 |
| F4 事件上下文 | HookInvocation、payload 表、生命周期调用方 | AC5、AC9、AC11、AC13、AC14、AC24 |
| F5 条件表达式 | HookRule.HookCondition、RuleMatcher | AC4、AC5 |
| F6 shell | HookActionExecutor、CommandRunner | AC13、AC15、AC24 |
| F7 prompt | HookSessionState、PromptAdditions、PromptRequestFactory、实际请求尝试 | AC16、AC17、AC19 |
| F8 HTTP | HookActionExecutor、JDK HttpClient | AC14、AC15 |
| F9 subagent | HookAction.Subagent、执行器占位分支 | AC22 |
| F10 拦截 | HookRejection、提交检查、工具前置准备 | AC10—AC15 |
| F11 only_once | HookSessionState、会话切换 | AC18、AC19 |
| F12 异步与超时 | HookEngine、动作执行器、取消与关闭 | AC15、AC18—AC20 |
| F13 顺序与隔离 | HookEngine、统一结果收口与诊断 | AC2、AC8、AC15、AC21、AC24 |
| F14 查看 | CommandRegistry、CommandContext、LoadedHooks | AC23 |

## 验证策略

- 使用已有 JUnit 和本地测试资源验证匹配、加载、失败隔离及动作协议，不依赖外部 HTTP 服务验证确定性行为。
- 以捕获 Provider 请求的集成测试验证 reminder 预算、只消费一次、回退重试、压缩后注入；通过权限闸门调用计数证明主动拦截发生在权限预判之前。
- 覆盖会话准备失败、输入检查取消、旧异步任务迟到、后台任务超时、Hook 拒绝和权限拒绝的工具结果配对。
- 运行 `./gradlew spotlessCheck`、`./gradlew test`、`./gradlew shadowJar`；仅在开发阶段执行，不将文档自检宣称为代码测试通过。
- 按后续 checklist 在 tmux 启动真实 MewCode，使用实际注册工具名与 Gradle 命令，记录正常和拒绝流程的可观察证据。
