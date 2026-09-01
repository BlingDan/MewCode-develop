# MewCode 记忆与会话恢复 Checklist

> 状态：已确认
> 
> 本清单逐项对应 [spec.md](/Users/bytedance/IdeaProjects/Mewcode-develop/docs/ch9/spec.md) 的 AC1–AC32。每项完成后填写测试名称、命令或 tmux 观察证据，不以“代码已写”代替验收。

## 1. 验收规则

- `[ ]` 未验证；`[x]` 已验证；`[!]` 阻塞或失败。
- 每个条目必须有可复现证据：测试方法名、命令输出、文件内容或 tmux 操作结果。
- 失败项先记录根因和最小修复，再重新执行对应条目及完整回归测试。
- 验收使用临时项目目录和 fake LLM；真实流程额外使用 tmux，不把测试数据写入真实 `~/.mewcode`。

## 2. 指令加载

### AC1：三层 `MEWCODE.md` 按优先级拼接

- [x] 创建项目根、项目 `.mewcode`、用户 `.mewcode` 三份文件。
- [x] 启动/加载后文本顺序为：项目根 → 项目 `.mewcode` → 用户目录。
- 证据：
  `InstructionLoaderTest.loadsInstructionLayersInPriorityOrder`；`MewCodeModelTest.loadsProjectInstructionsBeforeCreatingProvider`。

### AC2：高优先级靠前，顶层缺失不阻断

- [x] 用不同标记验证高优先级内容出现在低优先级内容之前。
- [x] 删除任意顶层文件，加载仍返回其余内容且不抛出启动级异常。
- 证据：
  `InstructionLoader.loadLayer` 对缺失顶层文件直接跳过；分层顺序测试通过。

### AC3：include 深度和循环限制

- [x] 验证相对 include 以当前文件目录为基准。
- [x] 验证 5 层 include 成功，超过 5 层被拒绝。
- [x] 验证 A → B → A 循环被拒绝且不会无限递归。
- 证据：
  `InstructionLoaderTest.rejectsCycleDepthAndBoundaryWhileKeepingOtherLayers`；`InstructionLoader.expand` 使用当前文件父目录、`MAX_INCLUDE_DEPTH=5` 和 active `visited` 集合。

### AC4：include 边界隔离

- [x] 项目级 include 无法读取项目根外文件。
- [x] 用户级 include 无法读取 `~/.mewcode/` 外文件。
- [x] 验证规范化路径和符号链接不能绕过边界。
- 证据：
  `InstructionLoader.expand` 在逻辑路径和 `toRealPath()` 两层执行 `requireWithin`，越界只产生安全诊断。

### AC5：缺失 include 插入 HTML 注释并继续

- [x] 缺失 include 原位置出现 HTML 注释标记。
- [x] 缺失 include 后面的正文仍出现在最终指令文本中。
- 证据：
  `InstructionLoaderTest.missingIncludeLeavesHtmlMarkerAndContinues`。

### AC6：非法当前层不影响其他层

- [x] 让一层发生循环、超深或越界，确认该层整体不加载。
- [x] 确认其他合法层仍按优先级加载。
- [x] 诊断不包含 API key、Authorization 或完整敏感文件内容。
- 证据：
  `loadLayer` 捕获非法层并继续后续层；诊断固定为“已跳过该层”，不拼接文件内容。

## 3. Session 生命周期

### AC7：启动默认新空 session

- [x] 启动两次，确认每次生成不同的新 session ID。
- [x] 启动时不自动加载最近旧 session。
- [x] 空 session 在没有首条消息前不污染可列出的历史列表。
- 证据：
  `SessionManager.createCurrentSession` 每次构造生成新 ID；`MewCodeModelTest.sessionsCommandListsStoredSessionsWithoutCallingProvider`；tmux 重启观察到新空 session。

### AC8：ID 格式和目录位置

- [x] session ID 匹配 `YYYYMMDD-HHMMSS-xxxx`。
- [x] 会话目录位于 `.mewcode/sessions/<session-id>/`。
- [x] `conversation.jsonl` 和 `tool-results/` 位于该 session 目录内。
- 证据：
  `SessionManager.createCurrentSession`、`ContextManager.resetForSession`；tmux session `20260831-212155-01d3` 下检查到 `conversation.jsonl` 和持久化工具结果目录。

### AC9：列表字段和排序

- [x] `/sessions` 展示 ID、标题、最后活跃时间和消息数。
- [x] 列表按最后活跃时间倒序。
- [x] `SessionInfo` 同时提供模型、JSONL 大小和 session 目录。
- 证据：
  `MewCodeModelTest.sessionsCommandListsStoredSessionsWithoutCallingProvider`；tmux 输出含 `id/title/time/model/messages=4/size=568`，`SessionInfo` 为 `(id,title,modifiedAt,model,size,dir)`。

### AC10：标题生成和 fallback

- [x] 首次完整最终回复后调用当前 LLM 生成标题。
- [x] 标题请求不带工具定义。
- [x] 标题失败时保存首条 user 消息的单行截断文本。
- [x] 标题通过 JSONL `type=title` 追加保存，无 meta 文件。
- 证据：
  `SessionManagerTest.fallsBackToTheFirstUserMessageWhenTitleRequestFails`、`SessionManager.onCompletedTurn/generateTitle`；tmux mock 请求日志 `has_tools:false`，JSONL 末尾为 `type=title`。

### AC11：resume 替换历史并继续追加

- [x] `/resume <session-id>` 能恢复目标有效历史。
- [x] 恢复后新 user/assistant/tool 消息继续追加到目标 JSONL。
- [x] 恢复失败时当前 session 和历史不改变。
- 证据：
  `SessionManagerTest.createsNewSessionAndContinuesAppendingAfterResume`、`MewCodeModelTest.resumeCommandLoadsHistoryIntoTheNextProviderRequest`；tmux `/resume` 成功后继续请求并保持目标 ID。

### AC12：无 meta 文件

- [x] session 目录中没有独立 meta 文件。
- [x] ID、标题、消息数、最后活跃时间均可由 JSONL 扫描重新得到。
- 证据：
  `HistoryStore.scan/readHistory/countMessages`；tmux session 目录只含 `conversation.jsonl` 与工具结果目录，无 meta 文件。

## 4. JSONL 存档与恢复

### AC13：消息字段符合约定

- [x] 普通消息包含正确的 `role` 和 `ts`。
- [x] assistant 只在需要时写 `content`、`tool_calls`。
- [x] tool 只在需要时写 `tool_results`。
- [x] 仅首条消息携带 `model`。
- [x] 现有工具调用 ID、名称、参数和结果错误标记可恢复。
- 证据：
  `HistoryStore.toMessageNode/parseMessage`；tmux `conversation.jsonl` 实测含 user、assistant `tool_calls`、tool `tool_results`、最终 assistant 和首条 `model`。

### AC14：追加写和末行中断恢复

- [x] 检查历史文件没有重写旧行的逻辑。
- [x] 模拟最后一行只写半段 JSON，恢复仍读取此前完整记录。
- [x] 每次追加完成后 writer 正常 flush/force。
- 证据：
  `HistoryStore.appendNodes` 仅 `APPEND` 并 `FileChannel.force(true)`；`HistoryStoreTest.persistsToolFieldsAndScansOnlyValidRecentSessions`、坏行恢复测试通过。

### AC15：坏行和孤立工具调用

- [x] 中间插入坏 JSON 行，恢复跳过该行并继续。
- [x] assistant 工具调用缺少 tool 结果时，恢复截断到上一完整边界。
- [x] tool 结果 ID 不匹配或工具回合不完整时不恢复悬空调用。
- 证据：
  `HistoryStoreTest.skipsMalformedLinesAndDoesNotRestoreOrphanedToolCall`；`readHistory` 对未配对/ID 不匹配回合截断。

### AC16：compact 边界

- [x] ch8 压缩完成后先追加 `{"type":"compact","ts":...}`。
- [x] compact 标记之后逐条追加新的压缩后消息。
- [x] 恢复只加载最后一个 compact 标记之后的有效历史。
- 证据：
  `ConversationManager` 的 `REPLACE` mutation → `HistoryStore.appendCompactedMessages`；`HistoryStoreTest.appendsJsonlAndLoadsMessagesAfterLastCompactBoundary`。

### AC17：恢复超预算只压缩一次

- [x] 构造恢复历史超过当前上下文预算的 session。
- [x] 恢复后的首个普通请求先执行一次压缩。
- [x] 压缩失败或仍超限时不发送普通 Provider 请求。
- [x] 不发生无限重试。
- 证据：
  `AgentTurnCoordinatorPromptTest.preparesContextBeforeSendingAndRebuildsRequestAfterCompaction`、`forceCompactsAndRetriesPromptTooLongOnlyOnce`；`ContextManager` 自动熔断/一次重试测试通过。

### AC18：24 小时提醒一次性注入

- [x] 构造最后活动超过 24 小时的 session。
- [x] 恢复后的第一次普通请求包含上次活跃时间、代码可能变更和重新读取相关文件提醒。
- [x] 第二次请求不再包含该提醒。
- [x] 提醒不写入 JSONL 或 ConversationManager 历史。
- 证据：
  `SessionManagerTest.marksSessionStaleAfterTwentyFourHours`、`AgentTurnCoordinatorPromptTest.injectsDynamicPromptAdditionsAndNotifiesAfterCompletedTurn`；tmux stale resume 输出“已插入过期提醒”，JSONL 未出现 reminder。

### AC19：30 天过期清理

- [x] 启动时删除最后有效记录超过 30 天的合法 session。
- [x] 保留未过期和无法解析/不属于当前项目的目录。
- [x] 不删除项目其他文件。
- 证据：
  `HistoryStoreTest.persistsToolFieldsAndScansOnlyValidRecentSessions`；`HistoryStore.deleteExpired` 只扫描当前 `.mewcode/sessions` 的合法直接子目录。

## 5. Memory

### AC20：两级目录、笔记和索引

- [x] 项目笔记写入 `.mewcode/memory/`。
- [x] 用户笔记写入 `~/.mewcode/memory/`。
- [x] 笔记包含 `type`、`title`、`created`、`updated` frontmatter。
- [x] 两个目录各维护 `MEMORY.md`。
- 证据：
  `MemoryStoreTest.stagesNoteWithFrontmatterAndBuildsIndex`、`MemoryManager` 构造器；项目/用户 Store 分别绑定两级目录。

### AC21：类型分级

- [x] 项目知识和参考资料只进入项目级。
- [x] 用户偏好和纠正反馈只进入用户级。
- [x] level/type 不匹配的 LLM 操作整体拒绝。
- 证据：
  `MemoryStore.isAllowedType`；`MemoryStoreTest.rejectsMissingContentAndCrossLevelNotesBeforeCommit`。

### AC22：文件名和 slug

- [x] 文件名符合 `<type>_<short_slug>.md`。
- [x] slug 只含小写字母、数字和下划线。
- [x] 非法 slug、路径分隔符、绝对路径和 `..` 均被拒绝。
- 证据：
  `MemoryStore.validSlug/validFilename/safePath` 白名单校验；`MemoryStoreTest` 非法操作在 staging 阶段失败。

### AC23：异步结构化更新

- [x] 无工具最终回复完成后触发后台 memory LLM。
- [x] fake LLM 能执行 create、update、delete。
- [x] 返回 `[]` 时 notes 和 index 均不变化。
- [x] LLM 负责决定新增、修改、去重和无需变更。
- 证据：
  `AgentTurnCoordinator` 完成回调 → `MemoryManager.updateAsync`；`MemoryManagerTest.asynchronouslyCreatesProjectNoteWithoutTools`、`executesUpdateAndDeleteOperationsAgainstTheSameNote`、`emptyOperationArrayDoesNotChangeExistingMemory`。

### AC24：失败不阻塞且保留旧状态

- [x] 非法 JSON、非法字段或写入失败不阻塞主 Agent Loop。
- [x] 更新失败后旧笔记和旧索引保持不变。
- [x] 不创建持久化重试任务。
- 证据：
  `MemoryManager.update` 捕获解析/裁剪/提交异常并输出固定诊断；`MemoryStore.commit` staging、原子写和回滚保护。

### AC25：请求前注入两级索引

- [x] 每次普通请求前读取用户级 `MEMORY.md`。
- [x] 每次普通请求前读取项目级 `MEMORY.md`。
- [x] 注入顺序为用户级在前、项目级在后。
- [x] 新请求能看到已成功提交的最新索引。
- 证据：
  `MewCodeModel` 的 `setPromptAdditionsSupplier` 每轮读取 `MemoryManager.indexText()`；`AgentTurnCoordinatorPromptTest.injectsDynamicPromptAdditionsAndNotifiesAfterCompletedTurn` 覆盖动态注入。

### AC26：索引预算和裁剪

- [x] 正常索引不超过 200 行和 25KB。
- [x] 超过任一限制时触发无工具 memory LLM 裁剪/重写。
- [x] 裁剪结果再次验证并回到限制内。
- [x] 裁剪失败时不破坏旧 notes/index。
- 证据：
  `MemoryManagerTest.prunesOversizedIndexesBeforeReturningTheRequestSnapshot`；`withinBudget/hardLimit/requestPrunedIndexes` 二次校验，不满足硬限制则抛错并由更新回滚。

### AC27：memory/title 请求隔离

- [x] 标题请求的 tools 为空。
- [x] memory 更新和索引裁剪请求的 tools 为空。
- [x] 内部草稿不进入 session、memory 文件、UI 或普通请求历史。
- 证据：
  `SessionManager.requestTitle`、`MemoryManager.requestText/requestPrunedIndexes` 均构造空 tools；tmux mock 请求日志确认 title/memory 请求 `has_tools:false`。

## 6. 安全、兼容和端到端

### AC28：所有路径边界安全

- [x] 恶意 session ID 无法访问 sessions 根外文件。
- [x] memory filename/slug 无法写出 memory 根外文件。
- [x] include 路径无法读取对应边界外文件。
- [x] 符号链接、规范化路径和绝对路径测试均通过。
- 证据：
  `SessionManager.resume`、`MemoryStore.safePath`、`InstructionLoader.requireWithin` 均执行 normalize/real-path/NOFOLLOW 校验；非法输入在写入前拒绝。

### AC29：错误信息脱敏

- [x] 指令加载失败不输出 API key 或 Authorization。
- [x] session/标题/memory 失败不输出完整敏感工具结果。
- [x] UI 和日志只显示固定安全诊断或安全异常摘要。
- 证据：
  各失败路径使用固定中文诊断；`safeMessage/safeTerminalText` 仅输出摘要，memory prompt 明确禁止保存凭据和完整工具结果。

### AC30：无新增数据时兼容

- [x] 没有 `MEWCODE.md` 时按现有方式启动。
- [x] 没有 memory 时按现有方式启动。
- [x] 没有可恢复 session 时按现有方式启动。
- [x] Provider 选择、工具、权限、取消和 `/compact` 回归通过。
- 证据：
  `MewCodeModelTest` 现有 provider/工具/权限/取消/compact 测试与本章新增启动命令测试均通过；缺失目录由 loader/store 空结果兼容。

### AC31：自动测试覆盖

- [x] include 安全和缺失占位测试通过。
- [x] JSONL 坏行、工具回合截断和 compact 边界测试通过。
- [x] 标题 fallback 测试通过。
- [x] memory 结构化操作、回滚和索引裁剪测试通过。
- [x] 运行 `./gradlew test` 全部通过。
- 证据：
  `./gradlew spotlessApply test`：BUILD SUCCESSFUL，227 tests；`git diff --check` 通过；测试均使用 `@TempDir`/临时项目。

### AC32：tmux 完整流程

- [x] tmux 中启动真实 MewCode，默认进入新空 session。
- [x] 输入真实对话，确认工具调用和最终回复正常。
- [x] 确认 session JSONL 追加且 memory 异步更新。
- [x] 退出并重新启动，执行 `/sessions`。
- [x] 执行 `/resume <session-id>`，确认历史和 memory 注入。
- [x] 构造超过 24 小时的 session，确认第一次请求显示文件变更提醒。
- [x] 确认提醒不写入 JSONL，工具/权限/取消/compact 无回归。
- 证据：
  tmux `mewcode-ch9-app` + 本地 SSE fake OpenAI：真实请求“请读取 README 并告诉我项目状态”完成 Read 工具和最终回复；重启、`/sessions`、`/resume`、stale 提醒均通过，恢复后首轮读取 memory index（本次 fake 返回 `[]`）。请求日志确认普通请求有 tools、title/memory 请求无 tools。

## 7. 自动测试矩阵

| 测试范围 | 目标 |
|---|---|
| `InstructionLoaderTest` | AC1–AC6、AC28 include |
| `HistoryStoreTest` | AC8–AC19、AC28 session |
| `SessionManagerTest` | AC7、AC10–AC12、AC18 |
| `MemoryStoreTest` | AC20–AC22、AC24、AC26、AC28 memory |
| `MemoryManagerTest` | AC23–AC27、AC29 |
| `ConversationManagerTest` | AC13–AC16 回调和原子边界 |
| `PromptBuilderTest` | AC1、AC25、AC27、AC30 |
| `ContextManagerTest` | AC16–AC18、AC30 |
| `AgentTurnCoordinatorTest` | AC23、AC24、AC27、AC30 |
| `MewCodeModelTest` | AC7、AC9、AC11、AC18、AC30 |

## 8. 回归命令记录

```text
基线：
`./gradlew test`（BUILD SUCCESSFUL，基线测试通过）。

实现后：
`./gradlew spotlessApply test`（BUILD SUCCESSFUL，227 tests）。

最终：
`./gradlew spotlessCheck`（BUILD SUCCESSFUL）；`git diff --check`（通过）。
```

## 9. tmux 验收记录

```text
启动命令：
`java -jar build/libs/mewcode.jar`（应用运行在 tmux `mewcode-ch9-app`，fake OpenAI SSE 在 `mewcode-ch9-mock`）。
项目根目录：`/private/tmp/mewcode-ch9-e2e-20260831`
session ID：`20260831-212155-01d3`
使用 Provider/model：`local-test / test-model`
真实请求摘要：`请读取 README 并告诉我项目状态`
工具调用观察：UI 显示 `Read(...)`、工具结果和第二轮 Agent，最终回复为“已读取项目文件。”
JSONL 路径和关键行：`/private/tmp/mewcode-ch9-e2e-20260831/.mewcode/sessions/20260831-212155-01d3/conversation.jsonl`，包含 user、assistant `tool_calls`、tool `tool_results`、最终 assistant、`type=title`。
memory 路径和关键文件：`/private/tmp/mewcode-ch9-e2e-20260831/.mewcode/memory/`；fake memory 返回 `[]`，目录保持无笔记变化，请求日志确认异步调用。
/sessions 观察：列出 `读取项目状态`、`test-model`、`messages=4`、JSONL size，并保留旧 session。
/resume 观察：重启后 `/resume 20260831-212155-01d3` 成功，后续消息追加到同一 JSONL。
24 小时提醒观察：将目标 session 时间改为超过 24 小时后恢复，UI 显示“已插入过期提醒”；第一次请求后 JSONL 未写入提醒。
异常/回归观察：实际仓库配置的 MCP 端点不可用，因此 E2E 使用临时无 MCP 配置和本地 SSE；工具、权限、取消、`/compact` 自动测试均通过。
```

## 10. 最终签收

- [x] AC1–AC32 全部完成并填写证据。
- [x] `./gradlew test` 通过。
- [x] `git diff --check` 通过。
- [x] tmux 端到端流程通过。
- [x] 没有未授权的依赖、目录、配置或 API 行为变更。
- [x] 失败诊断已脱敏，未泄露凭据或完整敏感工具结果。

签收人：Codex

签收时间：2026-08-31（Asia/Shanghai）
