# MewCode 记忆与会话恢复 Plan

> 状态：已确认
> 
> 本计划基于已确认的 [spec.md](/Users/bytedance/IdeaProjects/Mewcode-develop/docs/ch9/spec.md)，并吸收用户提供的参考设计。当前阶段只定义实现边界和落点，不写实现代码。

## 1. 实现目标

在不改变现有工具、权限、Provider、取消和 TUI 主流程语义的前提下，补齐三项能力：

1. 启动时加载三层 `MEWCODE.md`，并把展开结果放入系统提示。
2. 以项目内 JSONL 保存和恢复会话；启动默认创建新的空 session，用户显式执行 `/resume <session-id>` 才恢复旧 session。
3. 将跨轮记忆按用户级和项目级分开保存，在普通请求前注入索引，并在 Agent Loop 完成后异步更新。

本章不引入向量数据库、RAG、语义检索、团队同步、独立 Provider 或持久化后台任务队列。

## 2. 参考设计的采用与取舍

### 2.1 采用

- 保留 `instructions`、`session`、`memory` 三个职责边界，包名沿用仓库现有的 `com.mewcode.*`。
- 会话列表摘要采用 `SessionInfo` 六字段模型：`id`、`title`、`modifiedAt`、`model`、`size`、`dir`。
- 每个 session 使用独立目录，目录内只有一个会话 JSONL 和工具结果溢出目录。
- JSONL 使用 `compact` 控制记录作为新的历史边界。
- memory 使用 `NoteType`、结构化更新操作和单级 Store 的职责划分。
- 后台 I/O 使用 JDK virtual thread；并发写入使用 JDK 锁保护。

### 2.2 不直接照搬

- 当前仓库没有 `Main`、`Conversation`、`Agent`、`SessionRuntime` 这些参考类；分别落到现有的 `MewCode`、`ConversationManager`、`AgentTurnCoordinator` 和 `MewCodeModel`。
- 不额外拆出 `Writer`、`SessionList`、`SessionLoader`、`SessionCleaner` 四个只被一个管理器调用的类；由 `HistoryStore` 承担低层 JSONL 操作，`SessionManager` 编排生命周期。
- 不把“每 5 轮触发 memory”作为本章规则；沿用已确认的“每轮无工具最终回复完成后异步更新”。
- 不新增 `AppState.RESUMING` 和交互式列表组件；`/sessions` 输出列表，`/resume <session-id>` 直接按 ID 恢复，保持命令路径最短。
- 不新增独立的 `SessionContext` 抽象；为现有 `ContextManager` 增加按 session 重置入口，把 ch8 外置工具结果绑定到当前 session 的 `tool-results/`，避免恢复后引用旧的临时目录。

## 3. 运行时架构

```text
MewCode.run
  └─ MewCodeModel
       ├─ InstructionLoader              启动时读取三层 MEWCODE.md
       ├─ SessionManager                  创建新 session、管理当前 ConversationManager
       │    └─ HistoryStore               JSONL 追加、扫描、加载、清理
       ├─ MemoryManager                    两级 memory、索引、异步 LLM 更新
       │    └─ MemoryStore × 2             .mewcode/memory 与 ~/.mewcode/memory
       ├─ PromptBuilder/SystemPromptBundle 固定提示 + 指令
       └─ AgentTurnCoordinator             请求前注入 memory/提醒，完成后触发更新
```

普通请求的输入链路：

```text
用户文本
  → ConversationManager 追加并同步写入当前 conversation.jsonl
  → AgentTurnCoordinator 为本轮快照加载 memory 索引和一次性恢复提醒
  → ContextManager 预检/压缩
  → LlmClient.openStream(PromptRequest)
  → 完整工具回合或最终 assistant 回复写回 ConversationManager
  → 无工具最终回复完成后异步触发 MemoryManager
```

## 4. 文件布局

```text
<projectRoot>/
├── MEWCODE.md
└── .mewcode/
    ├── MEWCODE.md
    ├── sessions/
    │   └── YYYYMMDD-HHMMSS-xxxx/
    │       ├── conversation.jsonl
    │       └── tool-results/              # ch8 超大工具结果外置文件
    └── memory/
        ├── MEMORY.md
        └── <type>_<short_slug>.md

~/.mewcode/
├── MEWCODE.md
└── memory/
    ├── MEMORY.md
    └── <type>_<short_slug>.md
```

`SessionInfo.dir` 指向 `<projectRoot>/.mewcode/sessions/<session-id>` 的规范化绝对路径。没有独立 meta 文件；列表信息全部由 `conversation.jsonl` 扫描得到。

## 5. 核心数据结构

### 5.1 指令加载

```java
public record InstructionLoadResult(
    String text,
    List<String> diagnostics
) {}

public final class InstructionLoader {
    InstructionLoader(Path projectRoot, Path userHome);
    InstructionLoadResult load();
}
```

`load()` 按以下顺序处理顶层文件，并将每层成功展开后的文本依次拼接：

1. `<projectRoot>/MEWCODE.md`
2. `<projectRoot>/.mewcode/MEWCODE.md`
3. `<userHome>/.mewcode/MEWCODE.md`

内部递归函数接收当前文件、边界根、深度和 active visited 集合。相对 `@include` 路径以当前文件目录为基准；现有文件先规范化并解析真实路径，再执行边界检查。深度上限固定为 5。被引用文件不存在时写入 HTML 注释占位并继续；环路、超深或越界则使当前顶层文件整体失效，其他层继续。

### 5.2 会话摘要与恢复结果

```java
public record SessionInfo(
    String id,
    String title,
    Instant modifiedAt,
    String model,
    long size,
    Path dir
) {}

public record ResumeResult(
    String sessionId,
    Path sessionDir,
    Instant lastActive,
    boolean stale
) {}
```

`title` 优先使用 JSONL 中最后一个有效 `type=title` 记录；不存在时使用第一条 user 消息的单行截断文本。`modifiedAt` 使用最后一条有效记录的 `ts`，不依赖文件系统 mtime；`size` 使用当前 JSONL 字节数；`messageCount` 作为扫描内部字段统计最后一个 compact 边界之后的有效消息数，不额外写入 `SessionInfo`。

### 5.3 ConversationManager 变更

保留现有公开追加 API 和无回调构造器，增加一个窄幅的 mutation listener：

```java
enum MutationKind { APPEND, REPLACE }

record Mutation(MutationKind kind, List<Message> messages) {}

void setMutationListener(Consumer<Mutation> listener);
void loadMessages(List<Message> messages); // 恢复时静默替换，不触发持久化
```

普通单条消息产生一个 `APPEND`，完整工具回合以包含 assistant 调用和 tool 结果的消息列表产生一个 `APPEND`，上下文压缩产生一个 `REPLACE`。监听器在内存变更提交前同步执行，使持久化失败不会留下已进入内存但完全没有落盘机会的 mutation；默认无 listener 时保持现有行为。

### 5.4 JSONL 记录

低层记录使用内部 `HistoryEntry`/Jackson 映射，不把序列化细节扩散到 Agent 和 TUI：

```text
普通消息：
  role       user | assistant | tool       必填
  content    文本，可选
  tool_calls assistant 专用，可选
  tool_results tool 专用，可选
  ts         Unix 秒，必填
  model      仅首条消息可写

控制记录：
  type       compact | title
  ts         Unix 秒
  title      type=title 时使用
```

出站/入站映射保持现有 provider 无关模型：

- `Message("user", TextBlock)` 写成 `role=user`。
- assistant 文本和工具调用写成一条 `role=assistant`，工具调用数组复用现有 `ToolCall` 字段。
- 内部的工具结果消息仍可保持 `Message.role=user`，但落盘写成 `role=tool`，恢复时还原为现有 `ToolResultBlock`，不改 Provider 适配器。
- 思考内容不作为用户可见文本或 memory 输入；如果现有 Provider 需要签名，序列化适配器保留当前协议需要的最小字段，不改变无 thinking 配置下的记录格式。

## 6. session 包设计

### 6.1 HistoryStore

```java
final class HistoryStore implements Closeable {
    HistoryStore(Path sessionDir, String sessionId, String model);

    LoadedHistory load();
    void appendMessages(List<Message> messages);
    void appendCompact();
    void appendTitle(String title);

    static List<SessionInfo> scan(Path sessionsRoot);
    static void deleteExpired(Path sessionsRoot, Duration maxAge);
}
```

实现规则：

- 首次追加前创建 session 目录和 `conversation.jsonl`；启动创建的空 session 不因空文件出现在 `/sessions` 列表中。
- 每次 mutation 在同一把锁下逐行追加，单次追加完成后 flush/force；不重写历史行。
- `REPLACE` 先追加 `{"type":"compact","ts":...}`，再追加替换后的每条消息。
- `load()` 从最后一个 compact 标记之后开始构造有效历史；坏 JSON 行跳过；最后不完整行按坏行跳过。
- assistant 工具调用必须紧跟匹配的完整 tool 结果；未配对、结果 ID 不匹配或工具回合不完整时，截断该回合及其后内容。
- `/sessions` 只扫描 sessions 根目录的合法 session ID 子目录和 `conversation.jsonl`，按 `modifiedAt` 倒序；ID、标题、模型、消息数、最后活动时间均由有效行计算。
- 过期清理只删除当前项目 sessions 根内、ID 能解析且最后有效 `ts` 超过 30 天的会话目录；清理失败只记录安全诊断。

### 6.2 SessionManager

```java
final class SessionManager implements AutoCloseable {
    SessionManager(Path projectRoot, Path userHome, Consumer<String> diagnostics);

    ConversationManager conversation();
    String currentSessionId();
    List<SessionInfo> listSessions();
    ResumeResult resume(String sessionId);
    Optional<Message> consumeResumeReminder();
    void onCompletedTurn(List<Message> completedTurn);
    void attachTitleClient(LlmClient client, String model);
}
```

职责：

- 启动时生成 `YYYYMMDD-HHMMSS-xxxx`，使用创建目录的方式处理同秒碰撞；当前会话历史默认为空。
- 把 `ConversationManager` 的 mutation 路由到当前 `HistoryStore`；切换 session 时先完整加载并校验目标历史，再静默替换同一个 `ConversationManager` 实例，避免重建 Agent 协调器。
- `/resume` 先校验 ID 只能是新格式的单段名称，目标必须位于当前项目 sessions 根内；成功后切换 writer/store，并依据最后活动时间生成一次性 24 小时提醒。
- 恢复后的后续消息直接追加到目标 `conversation.jsonl`；切换失败时当前 session、历史和 writer 均保持不变。
- 首次完整最终回复后启动无工具标题请求；标题失败使用首条 user 消息 fallback，最终通过 `type=title` 追加记录保存。标题和 fallback 写入由 session 锁串行化。
- `close()` 关闭当前 JSONL writer；不删除已持久化的会话目录。

## 7. memory 包设计

### 7.1 类型模型

```java
enum MemoryLevel { USER, PROJECT }

enum MemoryType {
    USER_PREFERENCE("user_preference"),
    CORRECTION_FEEDBACK("correction_feedback"),
    PROJECT_KNOWLEDGE("project_knowledge"),
    REFERENCE_MATERIAL("reference_material");
}

record MemoryOperation(
    String action,
    MemoryLevel level,
    MemoryType type,
    String title,
    String slug,
    String filename,
    String content
) {}
```

JSON 解析先使用字符串字段，再转换成白名单 enum；解析、字段、level/type 对应关系、文件名和 slug 任一非法时，本次操作整体失败。

### 7.2 MemoryStore

```java
final class MemoryStore {
    MemoryStore(Path directory, MemoryLevel level);

    String loadIndex();
    List<MemoryNote> scanNotes();
    StagedMemory stage(List<MemoryOperation> operations);
    void commit(StagedMemory staged);
}
```

每个 Store 只管理一个目录：项目 Store 使用 `<projectRoot>/.mewcode/memory/`，用户 Store 使用 `<userHome>/.mewcode/memory/`。项目级只允许 `project_knowledge`、`reference_material`；用户级只允许 `user_preference`、`correction_feedback`。

笔记文件使用 `<type>_<short_slug>.md`，slug 只允许小写字母、数字和下划线。frontmatter 至少写入 `type`、`title`、`created`、`updated`；更新保留 `created` 并刷新 `updated`。`MEMORY.md` 从笔记快照重建，按文件名稳定排序。

`stage` 在内存中完成全部校验、创建/更新/删除和索引生成；`commit` 使用临时文件加原子替换提交同一级目录，任何一步失败都保留旧笔记和旧索引。

### 7.3 MemoryManager

```java
final class MemoryManager implements AutoCloseable {
    MemoryManager(Path projectRoot, Path userHome, Consumer<String> diagnostics);

    String indexText();
    void attachClient(LlmClient client, String model);
    void updateAsync(List<Message> completedTurn);
}
```

运行规则：

- `indexText()` 每次普通请求前读取两级 `MEMORY.md`，输出顺序为用户级、项目级；两个索引合计不超过 200 行且不超过 25KB。
- memory LLM 输入包含已完成轮次、现有两级笔记快照和约束提示；请求不带工具，内部草稿不写入 history、memory 或 UI。
- LLM 必须返回结构化 JSON 数组；`[]` 不产生文件变化。所有操作先整体校验，再按固定顺序锁定两级 Store 并提交，避免异步更新互相覆盖。
- `create` 依据 `level/type/slug` 派生目标文件；`update/delete` 只能操作相应 Store 内的白名单文件名；不允许通过绝对路径、`..` 或分隔符越界。
- 更新后若索引超过行数或字节上限，追加一次无工具索引裁剪请求；返回内容必须再次通过硬限制校验，否则整次更新回滚。
- 更新在 virtual thread 中执行，主 Agent Loop 不等待；LLM、解析或写入失败只记录安全诊断，不建立持久化重试队列。
- `close()` 停止后续后台更新并等待当前文件提交进入安全收口。

## 8. prompt 与 Agent 集成

### 8.1 PromptBuilder

给现有 `PromptBuilder` 增加带指令文本的 bundle 构造入口：

```java
SystemPromptBundle buildBundle(Path projectRoot, String instructionText);
```

指令文本放入已有 `custom-instructions` 模块，保留固定系统模块和环境上下文。用户级/项目级 memory 不写进稳定 bundle，因为它会在异步更新后变化。

### 8.2 PromptRequestFactory

增加本轮动态输入快照：

```java
public record PromptAdditions(
    String memoryIndex,
    Optional<Message> resumeReminder
) {
    static PromptAdditions empty();
}
```

`create()` 和 `createContextRequest()` 增加 `PromptAdditions` 重载；旧签名委托给空 additions，确保现有测试和调用方兼容。每轮只创建一次 additions，同时用于 ContextManager 预检和真正的 `PromptRequest`，避免预算估算和实际请求不一致。

memory 非空时作为额外 system segment 注入；恢复提醒使用现有 `PromptRequest.reminder`，只在目标 session 恢复后的第一条普通请求中消费一次。

### 8.3 AgentTurnCoordinator

- 保留当前 `AgentTurnCoordinator` 的兼容构造器；增加可选的 additions provider 和完成回调，默认 no-op。
- 在每轮开始前读取动态 additions，然后按现有顺序执行 ContextManager 预检、压缩、Provider 请求和用量记录。
- 无工具且 stream 完整结束时，先提交 assistant 消息，再调用 SessionManager 的完成回调和 MemoryManager 的 `updateAsync`。
- 有工具时只提交完整的 assistant/tool 回合；取消、Provider 错误、上下文压缩失败都不触发 memory 更新。
- 保留现有 `ContextManager` 语义，并增加 `resetForSession(Path sessionDir)`：关闭旧外置目录，重新绑定 `sessionDir/tool-results`，同时清空 usage baseline 和自动压缩熔断状态；恢复后仍遵循一次压缩和失败停止规则。

## 9. TUI 与启动集成

### 9.1 MewCodeModel

- 构造时使用 `InstructionLoader` 读取指令、创建 `SessionManager` 和空 `ConversationManager`，再用指令文本构建 `SystemPromptBundle`。
- 初始化 Provider 后把当前 `LlmClient` 和模型名交给 `SessionManager`、`MemoryManager`；现有 MCP、权限、工具和 ContextManager 初始化保持原顺序。
- 普通请求仍从现有 `submit()` 进入；ConversationManager 的 listener 自动持久化用户消息和 Agent 回合。
- 新增 `/sessions`：扫描当前项目会话并输出 ID、标题、最后活跃时间、消息数和模型/大小信息。
- 新增 `/resume <session-id>`：非流式状态下执行恢复；成功刷新 UI 可见历史、清空旧流临时状态、调用 `ContextManager.resetForSession(sessionDir)`；失败只显示安全错误。
- `/resume` 无参数、参数过多、ID 不合法或目标不存在时不改变当前 session。
- 关闭顺序为取消活动 Agent → 关闭 ContextManager/MCP/ToolExecutor → 关闭 MemoryManager/SessionManager，确保 JSONL writer 收口。

### 9.2 MewCode

保留现有配置读取、权限加载、MCP 错误输出和 TUI 启动流程；仅把项目根传给 `MewCodeModel`，并在启动后台执行一次当前项目的 30 天 session 清理。不存在指令、memory 或 session 时继续使用当前默认行为。

## 10. 实现顺序

### Phase 1：本地基础设施

1. 实现 `InstructionLoader` 和 include 安全检查。
2. 实现 `SessionInfo`、JSONL 映射、`HistoryStore` 的追加/扫描/加载/过期清理。
3. 扩展 `ConversationManager` mutation listener 和静默加载。

完成条件：不依赖 LLM 的指令和 JSONL 单元测试通过，现有 conversation/compact 测试不回归。

### Phase 2：提示与 session 生命周期

1. 扩展 `PromptBuilder`、`PromptRequestFactory` 的指令和动态 additions。
2. 实现 `SessionManager`、标题 fallback、24 小时提醒和 `/resume` 的核心切换。
3. 给 `ContextManager` 增加 session reset。

完成条件：fake LLM 能验证启动新 session、列表扫描、恢复、compact 边界、一次性提醒和超预算停止。

### Phase 3：memory

1. 实现 note frontmatter 解析、Store 快照和安全文件名校验。
2. 实现结构化 memory 操作的 staging/commit、索引生成和预算裁剪。
3. 接入无工具完成回合后的异步 LLM 更新。

完成条件：fake LLM 覆盖 create/update/delete、去重决策、`[]`、非法 JSON、回滚和索引上限。

### Phase 4：应用集成

1. 在 `MewCodeModel` 接入三个管理器和 session 命令。
2. 接通普通请求前的 memory/恢复提醒快照。
3. 接通启动清理和资源关闭。

完成条件：现有 Provider 选择、工具执行、权限确认、取消和 `/compact` 行为保持原测试结果。

### Phase 5：验证

1. 运行 `./gradlew test` 和 `git diff --check`。
2. 针对 checklist 的失败项补最小修复和回归测试。
3. 使用 tmux 启动真实 MewCode，验证“新 session → 对话 → 退出 → 重启 → `/sessions` → `/resume` → memory 注入 → 24 小时提醒”的完整链路。

## 11. 主要变更文件

新增：

```text
src/main/java/com/mewcode/instructions/InstructionLoader.java
src/main/java/com/mewcode/instructions/InstructionLoadResult.java
src/main/java/com/mewcode/session/SessionInfo.java
src/main/java/com/mewcode/session/ResumeResult.java
src/main/java/com/mewcode/session/HistoryStore.java
src/main/java/com/mewcode/session/SessionManager.java
src/main/java/com/mewcode/memory/MemoryLevel.java
src/main/java/com/mewcode/memory/MemoryType.java
src/main/java/com/mewcode/memory/MemoryNote.java
src/main/java/com/mewcode/memory/MemoryOperation.java
src/main/java/com/mewcode/memory/MemoryStore.java
src/main/java/com/mewcode/memory/MemoryManager.java
```

修改：

```text
src/main/java/com/mewcode/conversation/ConversationManager.java
src/main/java/com/mewcode/prompt/PromptBuilder.java
src/main/java/com/mewcode/agent/PromptRequestFactory.java
src/main/java/com/mewcode/agent/AgentTurnCoordinator.java
src/main/java/com/mewcode/compact/ContextManager.java
src/main/java/com/mewcode/tui/MewCodeModel.java
src/main/java/com/mewcode/MewCode.java
```

测试新增或扩展：

```text
src/test/java/com/mewcode/instructions/InstructionLoaderTest.java
src/test/java/com/mewcode/session/HistoryStoreTest.java
src/test/java/com/mewcode/session/SessionManagerTest.java
src/test/java/com/mewcode/memory/MemoryManagerTest.java
src/test/java/com/mewcode/prompt/PromptBuilderTest.java
src/test/java/com/mewcode/conversation/ConversationManagerTest.java
src/test/java/com/mewcode/compact/ContextManagerTest.java
src/test/java/com/mewcode/tui/MewCodeModelTest.java
```

## 12. 风险控制

- 所有外部路径在进入读写前规范化并检查边界；session ID、memory filename、slug 和 include 路径不接受路径穿越。
- JSONL 永不重写；compact 和标题均为追加控制记录，进程崩溃时只允许丢失最后一条不完整写入。
- memory 更新先 staging 后 commit；结构化输出或索引裁剪失败时不修改已有笔记和索引。
- 恢复加载失败不替换当前内存 session；普通请求在恢复压缩失败时不发送 Provider 请求。
- 所有 LLM 内部错误只输出固定安全诊断，不输出 API key、Authorization 或完整工具结果。
- 兼容构造器和已有无 memory/instruction 的调用路径保留，降低对当前测试和 Provider 适配器的影响。

## 13. 审批门

本文件获得明确批准后，才生成 `task.md`。`task.md` 获得批准后，再生成 `checklist.md`；四份文档全部批准前不写 Java 实现代码。
