# MewCode 记忆与会话恢复 Spec

> 状态：已确认

## 背景

MewCode 当前已有上下文压缩能力，但会话历史主要存在于进程内；程序重启后，Agent 会丢失此前的项目上下文、用户习惯和未完成工作。现有提示词系统也只预留了自定义指令和长期 memory 插槽，尚未接入持久化加载。

本功能通过三类本地数据建立分层记忆：

- `MEWCODE.md`：项目和用户级固定指令。
- JSONL session：可恢复的工作记忆。
- 项目级和用户级 memory：跨 session 保留的知识、偏好和纠正反馈。

## 目标

- 程序启动时创建新的空 session，不自动恢复旧会话。
- 通过 `/sessions` 查看会话列表，通过 `/resume <session-id>` 显式恢复。
- 恢复会话时保留可验证的历史内容，并提示可能存在的代码变更。
- 启动和每次请求前自动加载适用的项目指令与 memory。
- 让 memory 在对话结束后异步更新，不阻塞主对话。
- 保持 JSONL 追加写、坏行可跳过、崩溃后可继续恢复。
- 不实现向量数据库、RAG 语义检索或团队间 memory 同步。

## 功能需求

### 指令加载

- F1：程序启动时加载三层 `MEWCODE.md`，顺序为：
  1. `<项目根>/MEWCODE.md`
  2. `<项目根>/.mewcode/MEWCODE.md`
  3. `~/.mewcode/MEWCODE.md`
- F2：高优先级内容排在低优先级内容之前；不存在的顶层文件直接跳过。
- F3：支持 `@include`。相对路径以引用文件所在目录为基准，最多展开 5 层，并使用已访问文件集合防止循环引用。
- F4：项目级文件只能引用项目根目录内的文件；用户级文件只能引用 `~/.mewcode/` 内的文件。
- F5：被引用文件不存在时，在原位置插入 HTML 注释标记，并继续处理后续内容。
- F6：发生循环引用、超过深度限制或路径越界时，拒绝当前层级的 `MEWCODE.md`，记录安全诊断，但其他层级继续加载。

### 会话生命周期与命令

- F7：每次程序启动都创建新的空 session，不自动恢复旧会话。
- F8：session ID 使用 `YYYYMMDD-HHMMSS-xxxx` 格式，会话文件存放在 `<项目根>/.mewcode/sessions/`。
- F9：`/sessions` 扫描当前项目的 JSONL 文件，展示 session ID、标题、最后活跃时间和消息数，并按最近活跃时间倒序排列。
- F10：首次会话完成后调用当前 LLM 生成标题；标题生成不提供工具。标题生成失败时退回首条用户消息的单行截断文本，并将结果追加保存。
- F11：`/resume <session-id>` 只允许恢复当前项目的会话；恢复后替换当前内存历史，并继续向目标 JSONL 追加后续消息。
- F12：会话 ID、标题、消息数和最后活跃时间都从 JSONL 即时计算，不维护独立 meta 文件。

### JSONL 存档与恢复

- F13：普通消息记录每行一个 JSON 对象，字段约定如下：
  - `role`：必需，取值为 `user`、`assistant` 或 `tool`。
  - `content`：可选，消息正文。
  - `tool_calls`：可选，仅 assistant 消息使用，结构与现有工具调用结构一致。
  - `tool_results`：可选，仅 tool 消息使用，结构与现有工具结果结构一致。
  - `ts`：必需，写入时刻的 Unix 时间戳，单位为秒。
  - `model`：可选，仅第一条消息携带，记录当前 Provider 的模型名。
- F14：JSONL 只做追加写，不重写已有内容；进程崩溃最多丢失最后一行不完整写入。
- F15：恢复时逐行解析 JSONL；坏行跳过。出现未配对的工具调用或不完整工具回合时，截断到最后一个完整历史边界，不恢复悬空调用。
- F16：ch8 上下文压缩完成后，先追加一行：

  ```json
  {"type":"compact","ts":<unix_ts>}
  ```

  然后逐条追加新的压缩后消息。恢复时从最后一个 `compact` 标记之后开始加载。
- F17：恢复后的历史超过当前上下文预算时，先执行一次上下文压缩；压缩失败或仍超限时停止恢复请求并报告明确错误，不无限重试。
- F18：若恢复会话距最后活跃时间超过 24 小时，只在恢复后的第一次请求中注入时间跨度提醒。提醒包含上次活跃时间，并提示期间代码可能变化、应重新读取相关文件；提醒不写入 JSONL。
- F19：程序每次启动时清理最后一条有效记录超过 30 天的 session。

### Memory

- F20：memory 分两级存放：项目级目录为 `<项目根>/.mewcode/memory/`，用户级目录为 `~/.mewcode/memory/`。每条笔记是带 YAML frontmatter 的 Markdown 文件，每个目录维护一份 `MEMORY.md` 索引。
- F21：项目知识、参考资料写入项目级；用户偏好、纠正反馈写入用户级。
- F22：笔记 frontmatter 至少包含 `type`、`title`、`created` 和 `updated` 字段；正文保存可供 Agent 使用的记忆内容。例如：

  ```markdown
  ---
  type: user_preference
  title: 简洁回复，不要尾部摘要
  created: 2026-06-01T10:30:00+08:00
  updated: 2026-06-01T10:30:00+08:00
  ---
  用户偏好简洁回复，每次完成后不要在结尾重述刚做了什么。
  ```

- F23：笔记文件名格式为 `<type>_<short_slug>.md`，例如 `user_preference_terse_replies.md` 和 `project_knowledge_api_conventions.md`；slug 必须全小写、使用下划线分隔。
- F24：每轮 Agent Loop 在模型完成最终回复且没有工具调用后，异步调用当前 LLM 更新 memory。LLM 负责判断新增、修改、去重、删除或无需变更。
- F25：memory LLM 返回结构化 JSON 数组。操作格式为：

  ```json
  [
    {"action":"create","level":"project","type":"project_knowledge","title":"...","slug":"...","content":"..."},
    {"action":"update","level":"user","filename":"user_preference_terse_replies.md","title":"...","content":"..."},
    {"action":"delete","level":"project","filename":"project_knowledge_old_api.md"}
  ]
  ```

  返回空数组 `[]` 表示无需更新。非法操作、非法字段或非法 JSON 整体视为本次更新失败。
- F26：memory 更新不阻塞主对话；更新失败时保留旧笔记和旧索引，仅记录安全错误，不建立持久化重试任务。
- F27：每次处理普通请求前加载当前用户级和项目级 `MEMORY.md` 并注入上下文；项目级内容排在用户级内容之后。
- F28：索引最多 200 行且不超过 25KB；超过任一限制时，由 memory LLM 重写或裁剪索引，恢复到限制内。
- F29：memory 更新、索引裁剪和标题生成请求不包含工具定义；内部分析草稿不得进入会话、文件或用户可见输出。

### 安全与隔离

- F30：`/sessions` 和 `/resume` 只能操作当前项目 `.mewcode/sessions/` 内的合法 session ID，不能通过路径穿越访问其他文件。
- F31：LLM 返回的 memory `level`、`type`、`slug` 和 `filename` 必须经过白名单校验，不能通过构造路径写出对应 memory 目录。
- F32：项目指令、memory、session 存档和恢复提醒彼此分离；系统指令与 memory 不写入普通会话历史。
- F33：指令加载、会话恢复、标题生成和 memory 更新任一失败，都不能泄露 API key、Authorization 或完整敏感工具结果。

## 非功能需求

- N1：用户、assistant 和完整工具回合的有效内容必须保持原样；JSONL 只能追加写，不能通过重写旧文件维护状态。
- N2：单条 JSONL 写入应尽量保持原子性；恢复不得加载半条 JSON、悬空工具调用或不完整压缩结果。`compact` 标记后的消息作为新的有效历史边界。
- N3：`@include` 必须执行规范化路径检查、深度限制和循环检测，任何越界路径都不能被读取。
- N4：`/sessions`、`/resume` 和过期清理只能操作当前项目的 `.mewcode/sessions/`；用户级 memory 只能位于 `~/.mewcode/memory/`。
- N5：LLM 返回的 memory 文件名、slug 和类型不能绕过目录边界或覆盖目录之外的文件。
- N6：指令加载只执行本地文件读取；普通对话未结束时不触发 memory 更新；memory 更新不阻塞主 Agent Loop；标题生成只在首次会话完成后执行一次；索引未超限时不执行额外裁剪调用。
- N7：注入的用户级和项目级 memory 索引合计不超过约 200 行、25KB；超限时必须先裁剪，再发送普通请求。
- N8：同一 session 的 JSONL 追加和同一 memory 目录的更新必须串行化，不能因为异步更新导致文件互相覆盖或索引丢失。
- N9：指令解析、会话恢复、标题生成或 memory 更新失败时，不能破坏已有有效历史；memory 失败不得阻塞用户继续对话。
- N10：没有 `MEWCODE.md`、memory 文件或可恢复 session 时，MewCode 仍能按现有方式启动和对话；上下文功能未触发时，现有工具、权限、Provider、取消和 TUI 行为保持不变。
- N11：用户可以看到 `/sessions`、`/resume`、恢复失败、上下文压缩、标题生成失败和 memory 更新失败等明确状态；错误信息不得包含 API key、Authorization 或完整工具结果。
- N12：指令优先级、include 安全、JSONL 坏行恢复、工具回合截断、compact 边界、24 小时提醒、30 天清理、memory 结构化操作、索引裁剪和异步失败都必须能在不依赖真实 API 的测试中验证，并通过 tmux 验证真实 MewCode 流程。

## 不做的事

- 不在启动时自动恢复最近会话；默认始终创建新的空 session。
- 不支持跨项目恢复会话，也不支持 session 副本、分支或只读恢复。
- 不维护独立的 meta 文件；ID、标题、消息数和活跃时间均从 JSONL 扫描。
- 不重写已有 JSONL；压缩通过 `compact` 边界和追加新消息完成。
- 不加载或兼容 `AGENTS.md`，项目指令固定使用 `MEWCODE.md`。
- 不允许 include 越过项目根目录或用户级指令边界。
- 不引入向量数据库、RAG、语义召回或按相关性筛选 memory；索引按规则直接注入。
- 不引入精确 tokenizer、机器学习估算或动态调整阈值。
- 不使用独立 Provider、独立模型或独立凭据执行标题和 memory 操作。
- 不允许标题或 memory LLM 调用工具。
- 不实现 memory 后台任务的跨启动持久化重试队列。
- 不实现团队 memory 同步、远程同步或云端会话存储。
- 不强制按照参考架构拆出额外的 `MemoryAge`、`MemoryRecall`、`MemoryConsolidator` 等独立类；只实现本期确认的行为。
- 不改变现有工具执行、权限、取消、Provider 协议和 TUI 的既有语义。

## 验收标准

### 指令加载

- AC1：存在三层 `MEWCODE.md` 时，启动后上下文按“项目根 → 项目 `.mewcode` → 用户目录”顺序包含三层内容。
- AC2：高优先级指令排在低优先级指令之前；顶层文件不存在时不影响启动。
- AC3：`@include` 最多展开 5 层，并能阻止循环引用。
- AC4：项目级 include 不能读取项目根外文件；用户级 include 不能读取 `~/.mewcode/` 外文件。
- AC5：被引用文件不存在时，原位置出现 HTML 注释标记，后续内容仍被加载。
- AC6：循环、超深或越界 include 会拒绝当前层级文件，但其他层级文件仍正常加载。

### 会话管理

- AC7：每次启动都创建新的空 session，不自动恢复旧 session。
- AC8：新 session ID 符合 `YYYYMMDD-HHMMSS-xxxx` 格式，文件位于 `.mewcode/sessions/`。
- AC9：`/sessions` 能列出当前项目会话的 ID、标题、最后活跃时间和消息数，并按最近活跃时间倒序排列。
- AC10：首次会话完成后生成 LLM 标题并追加保存；生成失败时使用首条用户消息作为标题。
- AC11：`/resume <session-id>` 能替换当前历史，并将后续消息继续追加到目标 session。
- AC12：不创建 meta 文件；列表信息全部由 JSONL 扫描得到。

### JSONL 存档与恢复

- AC13：消息记录包含规定的 `role`、`content`、`tool_calls`、`tool_results`、`ts` 和首条消息的 `model` 字段，字段使用范围正确。
- AC14：JSONL 只追加写；模拟进程在最后一行中断后，恢复仍能读取此前完整记录。
- AC15：坏 JSON 行会被跳过；未配对工具调用会截断到最后一个完整历史边界。
- AC16：ch8 压缩完成后，JSONL 先追加 `type=compact` 记录，再追加压缩后消息；恢复从最后一个 `compact` 标记之后加载。
- AC17：恢复历史超过上下文预算时只执行一次压缩；压缩失败或仍超限时不发送普通请求。
- AC18：恢复距最后活跃超过 24 小时时，第一次请求包含上次活跃时间和重新读取文件提醒；该提醒不写入 JSONL。
- AC19：启动时会删除最后一条有效记录超过 30 天的 session，不影响其他有效会话和项目文件。

### Memory

- AC20：项目级笔记位于 `.mewcode/memory/`，用户级笔记位于 `~/.mewcode/memory/`；笔记包含规定的 frontmatter，两个目录各有 `MEMORY.md` 索引。
- AC21：项目知识、参考资料写入项目级；用户偏好、纠正反馈写入用户级。
- AC22：笔记文件名符合 `<type>_<short_slug>.md`，slug 全小写、下划线分隔。
- AC23：无工具的最终回复完成后，后台 LLM 能返回并执行 `create`、`update`、`delete` 操作；返回 `[]` 时不产生文件变化。
- AC24：LLM 输出非法、写入失败或更新失败时，主对话不受阻塞，旧笔记和旧索引保持不变。
- AC25：每次普通请求前都注入用户级和项目级 `MEMORY.md`；项目级内容排在用户级内容之后。
- AC26：索引超过 200 行或 25KB 时，memory LLM 能重写/裁剪到限制内。
- AC27：标题和 memory LLM 请求不包含工具定义，内部草稿不进入历史、文件或用户界面。

### 安全、兼容与端到端

- AC28：恶意 session ID、memory filename、slug 或 include 路径不能越过对应目录边界。
- AC29：指令、session、标题或 memory 失败时，错误信息不包含 API key、Authorization 或完整工具结果。
- AC30：没有新增指令、memory 或可恢复 session 时，MewCode 仍保持现有启动、工具、权限、取消和 TUI 行为。
- AC31：单元测试覆盖 include 安全、JSONL 恢复、compact 边界、标题 fallback、memory 操作和索引裁剪。
- AC32：在 tmux 中验证完整流程：启动新 session → 对话并生成 memory → 退出 → 再启动 → `/sessions` → `/resume` → 恢复历史、注入 memory，并在超过 24 小时时显示文件变更提醒。
