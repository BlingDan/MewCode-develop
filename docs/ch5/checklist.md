# MewCode 结构化 System Prompt 与 System Reminder Checklist

> 状态：阶段五已实现并验收
>
> 每项都必须通过运行代码、检查请求快照或观察终端行为验证。缓存控制、缓存命中统计、`MEWCODE.md`、自动记忆和 MCP 不属于本章验收范围。

## 1. 提示词模块化

- [x] 七个固定模块均存在且顺序为“身份 → 系统约束 → 任务模式 → 动作执行 → 工具使用 → 语气风格 → 文本输出”。（验证：提示词构造测试，检查模块名称序列）`F1/AC1`
- [x] 固定模块之间使用规范空行分隔，没有多余空行或黏连文本。（验证：稳定 system 文本断言）`F1/AC1`
- [x] 空的可选模块不会出现在最终 system 内容中，也不会产生多余分隔符。（验证：加入空模块后构造提示并检查文本）`F1/F8/AC8`
- [x] 新增或替换单个模块时，其他模块的内容和顺序保持不变。（验证：模块列表独立装配与稳定快照测试）`N1/N11`
- [x] 相同模块输入重复构造得到完全一致的 system 内容。（验证：连续构造两次并比较字节内容）`N4/AC8`

## 2. 环境上下文

- [x] system 请求中包含规范化的当前项目根目录。（验证：使用临时项目根目录构造请求并检查 system 内容）`F2/AC2`
- [x] 环境上下文作为独立 system 片段位于固定模块之后。（验证：检查结构化 system segments 的数量、顺序和边界）`F2/F3/AC2/AC3`
- [x] 连续多轮请求复用相同环境上下文，不混入轮次、模式或 Reminder 文本。（验证：比较至少三轮请求的 environment segment）`F2/F7/N4`
- [x] 环境上下文构造不读取 `MEWCODE.md`、自动记忆，不执行 git 命令，也不引入日期等易变化字段。（验证：范围搜索和构造测试）`F9/N8/AC11`

## 3. 请求通道分层

- [x] 静态提示进入 provider 的 system 通道。（验证：Anthropic/OpenAI 请求记录）`F3/AC3`
- [x] 环境上下文进入 provider 的 system 通道，不进入用户消息或工具描述。（验证：请求体字段断言）`F2/F3/N3`
- [x] 工具定义只进入 tools 通道，工具 schema 和注册顺序保持既有行为。（验证：ToolRegistry 和 provider 请求测试）`F3/N7/AC3`
- [x] 对话历史进入 messages，历史角色、工具调用和工具结果顺序不变。（验证：多轮请求消息快照断言）`F3/N2/N9`
- [x] 结构化请求对象对列表和映射做不可变快照，调用方后续修改不会改变已打开的请求。（验证：构造请求后修改输入集合并检查请求内容）`F3/N4`
- [x] 现有字符串式 LlmClient 调用入口仍可工作，旧测试客户端无需改变原有语义。（验证：运行既有 provider、Agent 和协议集成测试）`N7/N11`

## 4. System Reminder

- [x] Reminder 的传输角色为 `user`，内容由单个文本块承载，并严格包含 `<system-reminder>` 开始标签和结束标签。（验证：SystemReminderFactory 单测）`F5/AC5`
- [x] 完整 Reminder 包含当前模式、轮次状态和完整行为约束。（验证：完整 Reminder 文本断言）`F6/AC6`
- [x] 精简 Reminder 至少包含当前模式和该模式下的关键约束。（验证：精简 Reminder 文本断言）`F6/AC6`
- [x] 第 1、5、9 轮生成完整 Reminder，第 2、3、4、6、7、8 轮生成精简 Reminder。（验证：至少 9 轮确定性请求记录）`F6/AC6`
- [x] 模式切换后的下一轮生成完整 Reminder，即使该轮不在固定重复位置。（验证：切换 `/plan` 或 `/do` 后检查下一次请求）`F6/AC6`
- [x] Reminder 只存在于本轮结构化请求，不写入 `ConversationManager` 或持久历史。（验证：请求前后比较历史消息数量和内容）`F5/F7/N3/AC5/AC7`
- [x] 用户原始消息正文不会被 XML 标签或 Reminder 文本改写。（验证：对包含 XML、换行和特殊字符的用户输入做前后比较）`F8/N6/AC8`
- [x] Reminder 注入不会额外触发模型请求、工具执行或 TUI 用户消息打印。（验证：请求计数、执行计数和 UI 事件断言）`N5/N7/AC7`

## 5. Provider 适配

- [x] Anthropic 请求将稳定 system、环境 system、工具定义和历史消息分别映射到正确协议字段。（验证：Anthropic 本地请求记录测试）`N2/AC9`
- [x] Anthropic Reminder 追加到最后一个 user 消息的文本块；末尾没有 user 消息时才创建临时 user 消息。（验证：请求副本序列化实现与 provider 回归测试）`N2/N3/AC9`
- [x] OpenAI 请求将稳定 system 和环境信息映射到 system 内容，将工具定义映射到 tools，将历史和 Reminder 映射到 messages。（验证：OpenAI 本地请求记录测试）`N2/AC9`
- [x] OpenAI Reminder 出现在历史快照之后，且不被写入下一轮历史。（验证：连续两轮请求消息断言）`F5/N3/AC7`
- [x] DeepSeek OpenAI 兼容端点沿用 OpenAI 的消息分层语义。（验证：兼容 base URL 请求测试或序列化断言）`N2/AC9`
- [x] provider 适配不添加 `cacheControl`、缓存写入字段、缓存读取字段或其他缓存统计协议。（验证：请求/响应结构范围搜索）`F7/F9/N8/AC11`
- [x] provider 既有 SSE、Thinking、工具调用累积、工具结果转换、Token 用量、取消和错误收口行为不变。（验证：现有 Anthropic/OpenAI/Agent 协议测试）`N7/N9/AC10`

## 6. 工具规则双重强化

- [x] 全局工具使用模块包含“优先使用专用工具”“编辑前必须先读取”“工具出错后根据错误调整”等规则。（验证：system 模块文本断言）`F4/AC4`
- [x] EditFile/WriteFile 相关 description 明确编辑前读取目标文件并确认内容。（验证：工具 API 定义 description 断言）`F4/AC4`
- [x] Bash description 明确读取、查找和搜索优先使用专用工具。（验证：工具 API 定义 description 断言）`F4/AC4`
- [x] 工具描述强化只改变发送给模型的文本，不改变工具名称、schema、注册顺序、权限属性或执行逻辑。（验证：ToolRegistry 与工具行为回归测试）`N7/N9`

## 7. Agent Loop 与 Ch04 回归

- [x] Agent Loop 每轮使用新的动态 Reminder，但复用稳定提示和环境上下文。（验证：多轮请求快照比较）`F6/F7/N5`
- [x] 多轮工具调用、工具结果回灌和下一轮请求顺序保持 Ch04 行为。（验证：既有 AgentTurnCoordinator/AgentLoop 测试）`N9/AC10`
- [x] 流式文本和工具调用仍能双路收集，Reminder 不进入 assistant 历史内容。（验证：流式事件和 ConversationManager 断言）`N9`
- [x] 安全工具并发、不安全工具串行、结果保序和工具调用 ID 配对不退化。（验证：既有工具调度和协议集成测试）`N9/AC10`
- [x] 流式态按 ESC 或 Ctrl+C 仍只取消当前 Loop；空闲态 Ctrl+C 仍退出，ESC 不退出。（验证：MewCodeModel 回归测试和 tmux 观察）`N9/AC10`
- [x] `/plan` 和 `/do` 不调用模型、不写入历史；后续普通消息仍按当前模式过滤工具。（验证：请求计数、历史快照和工具列表断言）`N9/AC10`
- [x] Token 用量、迭代进度、工具展示、最终答复和输入恢复行为不退化。（验证：完整 TUI 测试和 tmux 观察）`N9/AC10`

## 8. 代码质量与构建

- [x] Google Java Format 检查通过。（验证：运行 `./gradlew spotlessCheck`）`N10/AC12`
- [x] 全部单元测试和集成测试通过。（验证：运行 `./gradlew test`）`N7/N9/AC10/AC12`
- [x] Shadow 打包成功并生成 `build/libs/mewcode.jar`。（验证：运行 `./gradlew shadowJar`）`N10/AC12`
- [x] 构建输出没有明显新增编译警告，且没有引入 Maven、缓存统计或范围外依赖。（验证：检查构建日志和依赖/源码范围）`N8/N10/AC11`

## 9. tmux 端到端场景

- [x] 场景 A：规划模式下分析任务 → 只发送规划 Reminder 和只读工具定义 → 模型完成调查并输出计划，不发生文件修改。（验证：tmux 终端、请求日志和文件状态）`F6/N9/AC13`
- [x] 场景 B：执行模式下修复一个已知代码问题 → 请求包含稳定 system、环境、工具定义和 Reminder 分层 → 工具调用、结果回灌、轮次展示和最终答复正常。（验证：tmux 终端和 provider 请求日志）`F3/F4/N9/AC13`
- [x] 场景 C：同一会话连续发送两条普通消息 → 第二条请求不重复携带第一条 Reminder，持久历史只包含真实用户、assistant 和工具结果消息。（验证：两次请求日志和历史快照）`F5/F7/N3/AC13`
- [x] 场景 D：流式处理中按 ESC 或 Ctrl+C → 当前请求停止、输入恢复、程序不退出，后续消息仍可发送。（验证：MewCodeModel 回归测试；tmux 会话生命周期保持正常）`N9/AC10/AC13`
- [x] 终端最终可见内容不显示 System Reminder XML，不出现重复用户消息或内部提示标签。（验证：tmux scrollback 检查）`N3/AC5/AC13`

## 10. 范围排除检查

- [x] 源码中没有本章新增的 `MEWCODE.md` 加载、自动记忆、MCP 接入、缓存控制或缓存命中统计路径。（验证：`rg` 范围搜索）`F9/AC11`
- [x] 没有新增工具类型、工具权限体系、provider 协议或 Agent Loop 控制流语义。（验证：现有工具/Agent/协议测试和 diff 检查）`F9/N9/AC11`

## 验收记录

- `JAVA_HOME=...jbr-21.0.11... ./gradlew spotlessCheck test`：`BUILD SUCCESSFUL`。
- `JAVA_HOME=...jbr-21.0.11... ./gradlew shadowJar`：`BUILD SUCCESSFUL`，生成 `build/libs/mewcode.jar`。
- `git diff --check`：无输出；范围搜索确认没有新增缓存控制、缓存命中统计、`MEWCODE.md`、自动记忆或 MCP 接入。
- tmux 使用本地 OpenAI 兼容 SSE 服务完成真实中文请求：首轮完整 Reminder 触发 ReadFile，第二轮精简 Reminder 完成最终答复；同会话第二条消息只携带当前 Reminder；`/plan` 不发起模型请求且后续请求只提供 ReadFile、Glob、Grep。
- 终端输出未显示 `<system-reminder>`，工具调用、结果摘要、轮次进度、Token 用量和输入恢复均正常。
