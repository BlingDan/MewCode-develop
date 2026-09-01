# MewCode 记忆与会话恢复 Task

> 状态：已确认
> 
> 本任务清单基于已确认的 [spec.md](/Users/bytedance/IdeaProjects/Mewcode-develop/docs/ch9/spec.md) 和 [plan.md](/Users/bytedance/IdeaProjects/Mewcode-develop/docs/ch9/plan.md)。全部任务完成后，使用 `checklist.md` 做逐项验收。

## 1. 执行约束

- 沿用现有 `com.mewcode.*` 包和 Java 21；不新增依赖。
- 保留现有兼容构造器、Provider 适配器、工具、权限、取消和 `/compact` 语义。
- 每完成一个有分支、循环、解析或持久化逻辑的任务，至少补一个能独立失败的单元测试。
- 外部输入统一做路径、枚举、文件名、JSON 字段和状态校验；错误输出只使用安全诊断。
- 任务按依赖顺序执行；在本文件和后续 `checklist.md` 获得批准前，不写 Java 实现代码。

## 2. 任务总览

| ID | 任务 | 依赖 | 主要产物 |
|---|---|---|---|
| T00 | 建立基线 | 无 | 测试基线、工作区检查 |
| T01 | 实现三层指令加载 | T00 | `InstructionLoader`、指令测试 |
| T02 | 实现 JSONL 历史存储 | T00 | `HistoryStore`、`SessionInfo`、恢复测试 |
| T03 | 增加 Conversation 持久化钩子 | T02 | mutation listener、回调测试 |
| T04 | 实现 session 生命周期 | T01、T02、T03 | `SessionManager`、session 测试 |
| T05 | 绑定 ch8 外置结果到 session | T04 | Context reset、压缩回归测试 |
| T06 | 接入动态系统提示 | T01 | prompt additions、提示测试 |
| T07 | 实现 memory 文件和索引 Store | T00 | memory 类型、Store、文件测试 |
| T08 | 实现异步 memory LLM 更新 | T06、T07 | `MemoryManager`、结构化操作测试 |
| T09 | 接入 Agent Loop 生命周期 | T04、T05、T06、T08 | coordinator hooks、Agent 测试 |
| T10 | 接入启动流程和 TUI 命令 | T04、T05、T08、T09 | `MewCodeModel`、`MewCode` 集成测试 |
| T11 | 回归与端到端验收 | T01–T10 | 测试报告、tmux 验收记录 |

## 3. T00：建立基线

### 工作项

- 检查工作区已有改动，避免覆盖用户文件。
- 运行现有测试，记录实现前基线。
- 确认 `docs/ch9/spec.md`、`plan.md` 和当前源码路径一致。

### 完成条件

- 基线测试结果已记录。
- 没有为本任务清理或重置无关改动。

## 4. T01：实现三层指令加载

### 工作项

- 新增 `com.mewcode.instructions.InstructionLoadResult`。
- 新增 `InstructionLoader(projectRoot, userHome)`，加载顺序固定为：项目根、项目 `.mewcode`、用户 `~/.mewcode`。
- 实现行级 `@include <path>` 展开：相对当前文件解析，最多 5 层，使用 active visited 集合检测环路。
- 对现有路径解析真实路径并检查边界：项目层不能越过项目根，用户层不能越过 `~/.mewcode`。
- 顶层文件缺失时跳过；被 include 文件缺失时在原位写入 HTML 注释并继续。
- 环路、越深和越界只拒绝当前顶层层级，记录不泄露敏感内容的诊断，其他层级继续。
- 保持高优先级内容在前，并清楚区分“文件缺失”和“文件非法”。

### 测试

- 三层顺序和优先级。
- 顶层缺失、include 缺失占位及后续文本继续加载。
- 相对路径、5 层边界、循环引用。
- 项目层和用户层的越界路径、符号链接越界。
- 非法层失败不影响其他层；诊断不包含密钥或完整文件敏感内容。

### 完成条件

- `InstructionLoaderTest` 覆盖 AC1–AC6、AC28 的 include 部分。
- 没有 `MEWCODE.md` 时返回空文本且不抛出启动级异常。

## 5. T02：实现 JSONL 历史存储

### 工作项

- 新增 `SessionInfo`：`id`、`title`、`modifiedAt`、`model`、`size`、`dir`。
- 新增 `ResumeResult`，携带 session ID、session 目录、最后活动时间和 stale 状态。
- 实现 `HistoryStore` 和内部 JSONL 映射记录：普通消息支持 `role`、`content`、`tool_calls`、`tool_results`、`ts`、首条 `model`；控制记录支持 `compact` 和 `title`。
- 使用 `<projectRoot>/.mewcode/sessions/<session-id>/conversation.jsonl`；首次追加时才创建空 session 的 JSONL 文件。
- session ID 使用 `YYYYMMDD-HHMMSS-xxxx`，创建目录时处理同秒碰撞；列表和清理只接受合法新格式。
- 所有写入只追加，单条 mutation 在锁内写入并 flush/force；不维护 meta 文件、不重写旧 JSONL。
- `REPLACE` 先追加 compact 标记，再追加新的消息行。
- 逐行恢复：坏 JSON 跳过，从最后 compact 之后加载；未配对工具调用、ID 不匹配或不完整工具回合截断到上一完整边界。
- `/sessions` 扫描有效行计算标题、模型、消息数、最后活跃时间和文件大小，按最后活跃倒序。
- 过期清理只处理当前项目 sessions 根下的合法目录，按最后有效 `ts` 清理超过 30 天的 session。

### 测试

- ID 格式、同秒碰撞、session 目录边界。
- user/assistant/tool 记录字段和现有工具调用/结果映射。
- 追加写和末行截断恢复。
- 中间坏行跳过、compact 最后边界、孤立工具调用截断。
- title 记录优先、无 title 时首条 user fallback。
- 列表排序、消息数、`modifiedAt`、`size` 和无 meta 文件检查。
- 30 天清理只删除目标 session，不影响其他项目文件。

### 完成条件

- `HistoryStoreTest` 覆盖 AC8–AC19、AC28 的 session 部分。
- 对恶意 ID、绝对路径和 `..` 的请求不读写 sessions 根外文件。

## 6. T03：增加 Conversation 持久化钩子

### 工作项

- 在 `ConversationManager` 增加 `MutationKind.APPEND/REPLACE`、mutation listener 和静默 `loadMessages`。
- 单条消息产生单条 APPEND；完整 assistant/tool 回合以同一 mutation 提交，保持工具调用和结果的完整边界。
- 压缩替换生成 REPLACE；恢复加载不触发 listener。
- 监听器在内存 mutation 提交前执行；持久化失败时不把未落盘 mutation 留在内存中。
- 保留无 listener 时的现有同步和不可变快照行为。

### 测试

- 现有追加接口的回调类型和顺序。
- 工具回合原子回调。
- replace 回调和静默恢复。
- listener 抛出持久化错误时的内存状态。
- 现有 ConversationManager 测试全部保持通过。

### 完成条件

- 不改变现有 Agent/Provider 对 `ConversationManager` 的消息语义。
- `HistoryStore` 能通过 mutation listener 接收所有普通历史变化。

## 7. T04：实现 session 生命周期

### 工作项

- 新增 `SessionManager`，启动时创建新的空 session，并持有当前 `ConversationManager` 和 `HistoryStore`。
- 将 Conversation mutation 路由到当前 HistoryStore；恢复时先读完整目标历史，再静默替换同一个 ConversationManager 实例。
- 实现 `listSessions()`、`resume(id)`、`currentSessionId()` 和一次性 `consumeResumeReminder()`。
- 恢复成功后切换 writer/store；失败时当前 session、内存历史和 writer 全部保持不变。
- stale 判定使用最后有效记录时间与 24 小时阈值；提醒只注入下一条普通请求，不写入 JSONL。
- 首次无工具最终回复完成后触发标题请求；标题请求不带工具，失败时保存首条 user 单行截断 fallback。
- 关闭时 flush/close 当前 writer，不删除历史 session。

### 测试

- 启动总是新空 session，不自动恢复最近会话。
- resume 成功替换历史且后续消息写入目标 JSONL。
- 非法 ID、目录外 ID、不存在 ID、目标坏历史均不改变当前 session。
- 24 小时提醒只消费一次且不落盘。
- 标题成功追加、失败 fallback、标题调用无工具。
- session writer 与切换过程串行化。

### 完成条件

- `SessionManagerTest` 覆盖 AC7、AC10–AC12、AC18。
- 为 T05 和 T10 提供稳定的当前 session 目录/历史接口。

## 8. T05：绑定 ch8 外置结果到 session

### 工作项

- 调整 `ToolResultExternalizer`，支持接收当前 session 的 `tool-results/` 目录，并保留现有大小阈值、预览和原子文件写入行为。
- 为 `ContextManager` 增加 `resetForSession(Path sessionDir)`：关闭旧外置目录，重新绑定当前 session，清空 usage baseline 和自动压缩熔断状态。
- 使 `ConversationCompactor` 使用 reset 后的新 externalizer；保留现有自动、手动和 emergency 压缩逻辑。

### 测试

- 新 session 的大工具结果写入对应 `tool-results/`。
- resume 后新外置结果不写入旧 session 目录。
- reset 后 usage baseline/fuse 不继承旧 session。
- 原有工具结果外置和压缩测试保持通过。

### 完成条件

- 恢复 session 后不会引用旧 session 的外置结果目录。
- 不改变 ch8 已确认的压缩阈值和失败熔断语义。

## 9. T06：接入动态系统提示

### 工作项

- 为 `PromptBuilder` 增加带 `instructionText` 的 bundle 构造入口，将指令放进现有 `custom-instructions` 模块。
- 新增 `PromptAdditions(memoryIndex, Optional<Message> resumeReminder)`。
- 为 `PromptRequestFactory.create` 和 `createContextRequest` 增加 additions 重载，旧签名委托空 additions。
- memory 作为额外 system segment；恢复提醒继续使用 `PromptRequest.reminder`。
- 每次 Agent round 固定一个 additions 快照，同时用于 ContextManager 预检和真实 Provider 请求。

### 测试

- 指令文本进入 system prompt 且顺序正确。
- memory 进入 system segment，用户索引在前、项目索引在后。
- reminder 只进入请求副本，不改变 ConversationManager 历史。
- ContextRequest 和 PromptRequest 使用同一动态快照。
- 旧 PromptBuilder/PromptRequestFactory 调用路径保持原输出。

### 完成条件

- `PromptBuilderTest` 和现有 Agent prompt 测试通过。
- 不把 instruction、memory 或 reminder 写入普通 session JSONL。

## 10. T07：实现 memory 文件和索引 Store

### 工作项

- 新增 `MemoryLevel`、`MemoryType`、`MemoryNote`、`MemoryOperation`。
- 新增单级 `MemoryStore`，分别绑定 `.mewcode/memory/` 和 `~/.mewcode/memory/`。
- 实现 Markdown frontmatter 读写：`type`、`title`、`created`、`updated`；更新保留 created 并刷新 updated。
- 实现 `<type>_<short_slug>.md` 文件名生成和白名单校验；禁止绝对路径、分隔符、`..` 和类型越权。
- 项目级只接受项目知识/参考资料，用户级只接受用户偏好/纠正反馈。
- 从 note 快照稳定重建 `MEMORY.md`；支持 staging 后原子 commit，失败保留旧笔记和旧索引。
- 将用户/项目两个索引合并为普通请求注入文本，限制总计 200 行、25KB。

### 测试

- frontmatter 创建、更新、时间字段和正文保持。
- 两级目录和四种类型映射。
- 合法 slug、非法 slug、路径穿越和已有文件冲突。
- create/update/delete 的 staging/commit 和失败回滚。
- 索引生成排序、缺失索引和 200 行/25KB 边界。

### 完成条件

- `MemoryStore` 不会写出对应 memory 根目录。
- `MemoryManager` 能获得一致的两级索引快照。

## 11. T08：实现异步 memory LLM 更新

### 工作项

- 新增 `MemoryManager`，接收当前 `LlmClient` 和模型名，使用当前 Provider，不新增凭据配置。
- 在 virtual thread 中发送无工具 memory prompt；输入包含已完成轮次和现有 note 快照。
- 解析结构化 JSON 数组；整体校验 action、level、type、title、slug、filename、content 后再执行。
- 支持 `create`、`update`、`delete` 和 `[]`；LLM 负责语义去重，代码只负责安全和结构校验。
- 通过单一更新锁串行化异步更新，避免两个任务覆盖 note/index；主 Agent Loop 不等待。
- 更新后超出行数或字节预算时执行一次无工具索引裁剪请求；结果不满足硬限制则整次回滚。
- 更新、解析、裁剪或提交失败只记录安全诊断，不建立持久化重试队列。
- close 时停止新任务并安全收口正在进行的文件提交。

### 测试

- create/update/delete、`[]` 和重复更新。
- 非法 JSON、非法字段、level/type 越权、filename/slug 穿越。
- 异步调用不携带工具定义、不写入 session、不污染 UI。
- 并发更新串行化、失败保留旧 notes/index、索引裁剪成功/失败。
- 主 Agent Loop 不因 memory LLM 失败而失败。

### 完成条件

- `MemoryManagerTest` 覆盖 AC20–AC27、AC28 的 memory 部分。
- 两级索引在下一次普通请求前可读到已提交变更。

## 12. T09：接入 Agent Loop 生命周期

### 工作项

- 为 `AgentTurnCoordinator` 增加可选 additions provider 和完成回调，保留现有构造器行为。
- 在每轮开始时生成 additions 快照，并把它同时传给上下文预检和实际请求。
- 无工具、完整 stream 结束后，先提交 assistant 消息，再触发 SessionManager 标题/完成处理和 MemoryManager 异步更新。
- 工具回合、取消、Provider 错误、上下文错误不触发 memory 更新。
- 保持现有工具执行顺序、权限等待、事件发布、迭代上限和 context emergency recovery。

### 测试

- 无工具完成触发一次 memory 更新；工具多轮完成后仅在最终无工具回复触发。
- 取消、错误和不完整 stream 不触发更新。
- 动态 additions 在 compact 前后快照一致。
- 现有 AgentTurnCoordinator 构造器和 fake client 测试全部通过。

### 完成条件

- 无 memory/instruction/session 注入时行为与 ch8 前路径一致。
- ContextManager 只在请求前按现有规则执行压缩。

## 13. T10：接入启动流程和 TUI 命令

### 工作项

- 在 `MewCode`/`MewCodeModel` 启动时串联 instruction、SessionManager、MemoryManager，并后台执行 30 天清理。
- Provider 选定后把 client/model 绑定给标题和 memory 管理器；普通请求仍使用现有 Provider 选择流程。
- 接入 `/sessions`，输出 ID、标题、最后活跃时间、消息数以及模型/大小信息。
- 接入 `/resume <session-id>`，只允许非流式状态；成功更新可见历史、当前 session、ContextManager 和一次性提醒；失败只显示安全错误。
- `/resume` 参数错误时不改变当前 session；`/exit`、`/plan`、`/do`、`/compact` 保持原语义。
- 关闭顺序保证活动 Agent 取消、上下文/工具/MCP 关闭、memory/session writer 收口。

### 测试

- MewCodeModel 现有 TUI、provider 选择、权限确认和取消测试。
- `/sessions` 输出和空目录行为。
- `/resume` 成功、失败、参数错误和恢复后继续对话。
- 启动清理不阻塞 TUI，关闭不丢最后已成功追加行。
- 无 MEWCODE、无 memory、无可恢复 session 时的兼容启动。

### 完成条件

- `MewCodeModelTest` 覆盖 AC30，并覆盖命令与状态边界。
- 不新增交互式 RESUMING 状态或额外列表组件。

## 14. T11：回归与端到端验收

### 自动验证

- 运行 `./gradlew test`。
- 运行 `git diff --check`。
- 检查测试临时目录、session 目录和 memory 目录均使用临时路径，不污染真实用户目录。
- 对照 `checklist.md` 逐项记录通过/失败证据。

### tmux 验证

1. 在 tmux 中启动真实 MewCode。
2. 输入真实对话，让 Agent 读取/修改文件并完成最终回复。
3. 检查 `.mewcode/sessions/<id>/conversation.jsonl` 的消息追加和 memory 文件更新。
4. 退出并重新启动，确认默认是新空 session。
5. 执行 `/sessions`，确认列表字段和排序。
6. 执行 `/resume <id>`，确认历史恢复、memory 注入和后续消息追加。
7. 构造超过 24 小时的最后活动时间，确认第一次请求出现代码变更提醒且不写入 JSONL。
8. 检查工具调用、权限确认、取消、`/compact` 和错误路径未回归。

### 完成条件

- 所有 AC1–AC32 均有自动测试或 tmux 证据。
- 失败项修复后重新运行最小相关测试和完整测试。
- 未引入与本章无关的重构、依赖或持久化格式。

## 15. 交付顺序

```text
T00 → T01/T02 → T03 → T04 → T05/T06/T07 → T08 → T09 → T10 → T11
```

`task.md` 获得批准后，生成 `checklist.md`，再按任务顺序进入实现；实现前不创建 Java 源文件或修改现有实现文件。
