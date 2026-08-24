# MewCode 工具调用系统验收 Checklist

> 状态：已验收
>
> 本清单基于已确认的 [spec.md](/Users/bytedance/IdeaProjects/Mewcode-develop/docs/ch3/spec.md)、[plan.md](/Users/bytedance/IdeaProjects/Mewcode-develop/docs/ch3/plan.md) 和 [task.md](/Users/bytedance/IdeaProjects/Mewcode-develop/docs/ch3/task.md)。实现阶段逐项勾选，并在需要时记录测试名称、命令或 tmux 证据。

## 1. 使用规则

- 只有自动化测试通过或有可复现的命令行/端到端证据，才能勾选项目。
- 任何失败项都要记录实际错误和修复任务，不能用“基本可用”代替验收。
- `metadata` 只检查本地 UI/日志可见性，确认它没有进入发送给模型的 tool-result。
- 文件测试全部在临时项目根目录中执行，输入路径使用绝对路径。
- 端到端测试必须使用 tmux 启动真实 MewCode 进程，并保留关键输出。
- 在所有项目完成前，不得把“最终验收通过”标记为完成。

## 2. 实现前基线

- [x] 运行现有测试并记录基线结果。
- [x] 确认纯文本请求仍能经过 OpenAI/Anthropic 客户端并生成最终文本。
- [x] 确认当前工作树中没有把用户已有的无关修改覆盖掉。

## 3. 构建与依赖

- [x] `build.gradle.kts` 显式声明 Jackson Databind `2.18.2`。
- [x] Anthropic Java `2.34.0` 和 OpenAI Java `4.37.0` 版本未被无关升级。
- [x] `./gradlew test` 编译通过。
- [x] `./gradlew shadowJar` 打包通过。

## 4. 工具契约和注册中心

### AC1 工具注册与元信息

- [x] 注册中心默认包含且只缺少无关工具：ReadFile、WriteFile、EditFile、Bash、Glob、Grep。
- [x] 每个工具均实现名称、描述、`inputSchema`、`validateInput`、`execute`、分类、只读性、破坏性和并发安全性。
- [x] `ToolResult` 包含 `content`、`isError`、`metadata`，且 metadata 是不可变快照。
- [x] ReadFile、Glob、Grep 为只读、非破坏性、可并发。
- [x] WriteFile、EditFile 为非只读、非破坏性、不可并发。
- [x] Bash 是唯一 `isDestructive=true` 的内置工具，分类为 `shell` 语义。
- [x] 输入校验失败不抛出异常，而是返回 `isError=true` 的结果。

### AC2 API 工具定义

- [x] Anthropic 请求包含六个工具的 `name`、`description` 和 `input_schema`。
- [x] OpenAI 请求包含六个 `type=function` 工具和正确的 `function.parameters`。
- [x] DeepSeek 使用 OpenAI 兼容工具格式。
- [x] `toAPIFormate` 每次请求前从注册中心生成定义。
- [x] 工具实现和 conversation 模型不导入 Anthropic/OpenAI SDK 类型。

## 5. 消息模型和流式解析

### AC3 消息内容块

- [x] assistant 消息可以同时包含普通文本和多个 `tool_use`。
- [x] 每个 `tool_use` 都有唯一 ID、工具名和 JSON 参数。
- [x] 所有工具结果放在同一条 user 消息中。
- [x] 每个 `tool_result` 通过 `toolUseId` 与对应 `tool_use` 配对。
- [x] tool-result 中没有 metadata。

### AC4 流式参数解析

- [x] 单个 tool-use 的开始事件能记录 ID 和名称。
- [x] 多个 JSON 增量能按 ID 拼接成完整参数对象。
- [x] 多个工具调用交错到达时，各自参数不串线。
- [x] 文本增量仍按到达顺序保留。
- [x] 非法 JSON 生成对应的解析错误/错误 tool-result，程序不崩溃。
- [x] 一个调用解析失败时，其他可解析调用继续处理。

## 6. 文件状态和文件工具

### AC5 ReadFile

- [x] 使用绝对路径和 `offset=1`、`limit=N` 能按行读取。
- [x] 每行格式为 `行号<TAB>内容`，行号连续且从真实文件行号开始。
- [x] 大文件读取不会一次性返回全部内容，分页参数生效。
- [x] 文件不存在返回文件不存在错误和调整提示。
- [x] 相对路径返回绝对路径错误和调整提示。
- [x] 项目根目录外路径返回越界错误。
- [x] 无权限文件返回权限错误。
- [x] 前 512 字节包含 NUL 时拒绝读取，并提示使用命令行工具。
- [x] 成功读取后 FileStateCache 有对应绝对路径和修改时间记录。

### AC6 WriteFile

- [x] 新文件写入时递归创建父目录。
- [x] POSIX 系统父目录权限为 `0755`。
- [x] POSIX 系统文件权限为 `0644`。
- [x] 已有文本文件未先 ReadFile 时拒绝覆盖。
- [x] ReadFile 后文件未变化时允许覆盖。
- [x] ReadFile 后文件修改时间变化时拒绝覆盖且文件内容不变。
- [x] 已有二进制文件拒绝写入。
- [x] 写入成功后 FileStateCache 更新为新修改时间。
- [x] 写入失败不会错误更新缓存。

### AC7 EditFile

- [x] 已读取且文件未变化时，`old_string` 恰好一次出现才替换成功。
- [x] 原文出现 0 次时返回“未找到”错误且文件不变。
- [x] 原文出现多次时返回“不唯一”错误且文件不变。
- [x] 未先读取时拒绝编辑。
- [x] 读取后文件变化时拒绝编辑且文件不变。
- [x] 二进制文件拒绝编辑。
- [x] 成功编辑后 FileStateCache 更新。

## 7. 搜索和命令工具

### AC8 Glob

- [x] `**` 可以递归匹配子目录。
- [x] `*` 和 `?` 匹配行为符合 Schema 描述。
- [x] 自动排除 `.git`、`node_modules`、`vendor`、`.idea`、`__pycache__` 等目录。
- [x] 结果按修改时间倒序排列。
- [x] 最多返回 200 个结果。
- [x] 每行是相对于搜索根目录的文件路径。
- [x] 不跟随符号链接逃逸项目根目录。
- [x] 空结果返回正常的空结果而不是执行异常。

### AC9 Grep

- [x] 正则表达式匹配成功时输出 `相对路径:行号<TAB>匹配行内容`。
- [x] 支持 include 文件名过滤。
- [x] 自动排除与 Glob 相同的目录。
- [x] 结果按文件修改时间倒序排列。
- [x] 最多返回 200 条结果。
- [x] 二进制文件被跳过，不返回乱码。
- [x] metadata 记录跳过的二进制文件数量。
- [x] 非法正则返回 `isError=true` 的可调整错误。

### AC10 Bash

- [x] 命令通过系统 shell 执行。
- [x] 工作目录固定为 MewCode 启动时的项目根目录。
- [x] stdout 和 stderr 合并成一个输出流。
- [x] 默认超时为 120 秒。
- [x] 输出超过阈值时只保留前部并追加截断标记。
- [x] 输出包含 `<output>` 和 `<exit_code>`。
- [x] 子进程超时后被强制销毁。

### AC11 Bash 退出码语义

- [x] 普通命令退出码非零时 `isError=true`。
- [x] grep 退出码 1 视为正常查询结果。
- [x] diff 退出码 1 视为正常差异结果。
- [x] find 退出码 1 视为正常查询结果。
- [x] grep/diff/find 退出码 2 及以上为错误。
- [x] Bash 超时始终为错误结果。

## 8. 执行器和回合编排

### AC12 校验、超时与异常隔离

- [x] 未知工具返回结构化错误，不抛出未处理异常。
- [x] 缺少参数、类型错误、非法路径在执行前被拒绝。
- [x] 工具运行异常转换为 `isError=true`。
- [x] 每个工具调用独立计时。
- [x] 一个并发调用失败时，其他调用仍完成并返回结果。
- [x] 错误结果包含能帮助模型调整参数的原因和建议。

### AC13 多工具并发

- [x] ReadFile、Glob、Grep 可在同一批次并发执行。
- [x] WriteFile、EditFile、Bash 必须串行执行。
- [x] 不安全调用形成串行屏障，不与前后安全批次交叉。
- [x] 结果无论完成顺序如何，都按原始 tool-use 顺序排列。
- [x] 每个结果通过唯一 ID 配对，没有调用结果串线。

### AC14 结果回灌与最终回复

- [x] 初始用户消息加入历史。
- [x] 第一次 assistant 消息保存文本和全部 tool-use。
- [x] 所有工具结果组成一条 user tool-result 消息。
- [x] 结果回灌后只发起一次模型请求。
- [x] 第二次请求得到最终文本并展示给用户。
- [x] metadata 未出现在 provider 请求体中。

### AC15 Agent Loop 边界

- [x] 第二次模型响应再次包含 tool-use 时，不执行该工具。
- [x] 不发起第三次模型请求。
- [x] 用户能看到明确的“不支持连续工具调用”提示。

## 9. 路径、输出和范围边界

### AC16 路径与输出边界

- [x] 所有文件工具路径参数必须是绝对路径。
- [x] 文件工具无法访问项目根目录外的路径。
- [x] `..` 和符号链接不能绕过项目根目录校验。
- [x] ReadFile 的 offset/limit 能限制单次返回 token 规模。
- [x] Glob/Grep 的 200 条上限生效。
- [x] Bash 输出截断阈值可通过集中常量调整。
- [x] 不存在删除、移动、重命名、MCP、ToolSearch 或用户确认逻辑。

## 10. 自动化测试和端到端

### AC17 编译与自动化测试

- [x] 工具契约和 ToolResult 单元测试通过。
- [x] ToolRegistry 和三种 Schema 格式测试通过。
- [x] FileStateCache 测试通过。
- [x] 六个工具的正常、错误和边界测试通过。
- [x] StreamEvent/ToolCallAccumulator 测试通过。
- [x] OpenAI、Anthropic 和 DeepSeek 兼容格式测试通过。
- [x] AgentTurnCoordinator 测试通过。
- [x] 现有纯文本测试全部通过。
- [x] `./gradlew test` 通过。
- [x] `./gradlew shadowJar` 通过。

### AC18 端到端场景

- [x] 在 tmux 中启动 MewCode。
- [x] 在项目根目录发送真实请求：“读取一个文件并总结”。
- [x] 观察到模型请求中包含工具定义。
- [x] 观察到模型返回 tool-use 及唯一 ID。
- [x] 观察到本地执行 ReadFile。
- [x] 观察到包含对应 ID 的 tool-result user 消息。
- [x] 观察到第二次模型请求和最终总结文本。
- [x] 整个流程无未处理异常、乱码或卡死。

### AC19 先读再写

- [x] 对已有文件直接 WriteFile 被拒绝，并提示先读取。
- [x] 对已有文件直接 EditFile 被拒绝，并提示先读取。
- [x] ReadFile 后外部修改文件，再 WriteFile 被拒绝。
- [x] ReadFile 后外部修改文件，再 EditFile 被拒绝。
- [x] ReadFile 且文件未变化时 WriteFile 成功。
- [x] ReadFile 且文件未变化时 EditFile 成功。
- [x] 成功写入或编辑后缓存时间被刷新。
- [x] 新文件不要求预先读取。

## 11. 最终门禁

- [x] AC1～AC19 全部完成并有测试或运行证据。
- [x] 所有失败测试均已修复并重新运行，不保留已知失败。
- [x] 没有修改或删除用户无关的已有代码和配置。
- [x] 没有引入权限确认或连续 Agent Loop。
- [x] 已保存完整测试命令和 tmux 端到端关键输出。
- [x] 在最终回复中列出实现文件、测试命令、端到端结果和已知限制。

## 12. 验收记录

- `./gradlew test`：54 个测试通过，0 failures，0 errors。
- `./gradlew shadowJar`：构建成功，产物为 `build/libs/mewcode.jar`。
- tmux E2E：使用 Java 21 启动真实 JAR 和本地 OpenAI SSE mock；输入“读取并总结绝对路径文件”，观察到六个工具定义、`ReadFile` 的 `call_e2e` tool-use、带行号的 tool-result，以及第二次请求返回最终总结；请求日志确认 metadata 未进入 provider 消息。
- 端到端临时目录和 mock 进程已清理；项目原有 `.mewcode/config.yaml` 未修改。
