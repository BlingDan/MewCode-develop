# MewCode Slash Command Checklist

> 状态：已确认
>
> 每项都通过运行测试或观察真实行为验证。自动化检查先执行，tmux 端到端场景最后执行。
>
> 验收记录（2026-09-01）：`./gradlew spotlessApply build` 返回 0，完整测试集通过并生成
> `build/libs/mewcode.jar`；tmux 使用隔离 project/home 完成 9 个端到端场景。验收中发现并修复
> `/status` 等待 MCP 同步锁及 macOS Seatbelt 禁止写 `/dev/null` 两个真实环境问题。

## 注册、解析与分流

- [x] 9 个内置命令均包含名称、别名、描述、用法、类型、参数提示、隐藏状态和 handler（验证：运行命令注册测试，断言完整元数据）。
- [x] 命令名重复会在启动交互界面前失败（验证：注册两个同名命令，期望抛出含冲突标识的异常）。
- [x] 命令名与别名重复会在启动前失败（验证：分别测试先注册名称和先注册别名两种顺序）。
- [x] 两个别名重复会在启动前失败（验证：注册冲突别名，期望失败）。
- [x] 仅大小写不同的名称或别名也视为冲突（验证：以不同大小写注册同一标识）。
- [x] 启动期注册冲突会输出冲突标识并以非零状态退出，不进入 TUI（验证：使用冲突注册表启动应用入口并检查退出码与输出）。
- [x] 空输入和纯空白输入无输出、无 Provider 调用、无 Agent Run（验证：模型测试记录三类调用均为 0）。
- [x] `/HeLp`、`/HELP`、`/h` 均执行帮助命令（验证：比较三个输入的命令结果）。
- [x] 命令参数大小写和内部空格保持原样（验证：执行带混合大小写和多词关注点的 `/review`）。
- [x] 首字符不是 `/` 的文本进入普通 Agent 流程（验证：提交前导空格加 `/help`，观察其成为用户消息）。
- [x] 未知斜杠命令只显示错误和 `/help` 引导（验证：Provider 和 Agent 调用数保持 0）。
- [x] LOCAL 与 LOCAL_UI 命令不进入 Agent Loop，PROMPT 命令进入正常 Agent Loop（验证：使用调用计数替身分别执行三类命令）。

## 帮助、别名与补全

- [x] `/help` 按固定顺序列出全部可见命令的名称、别名、描述和用法（验证：命令注册测试比较完整输出）。
- [x] `/help <名称>` 与 `/help <别名>` 返回同一命令详情（验证：分别查询 `compact` 和 `c`）。
- [x] 隐藏命令可以执行，但不出现在帮助或补全中（验证：注册一条隐藏测试命令）。
- [x] `/h`、`/?`、`/c`、`/cls`、`/p`、`/s`、`/m`、`/perm`、`/st`、`/r` 均命中正确正式命令（验证：遍历别名映射）。
- [x] `/do`、`/d`、`/exit`、`/sessions`、`/resume` 均按未知命令处理（验证：逐个提交，Agent 调用数为 0）。
- [x] 唯一前缀按 Tab 后补成正式名称并追加空格（验证：输入别名前缀并检查输入缓冲区）。
- [x] 多匹配前缀按 Tab 后出现去重菜单（验证：输入 `/p` 等测试前缀，检查候选名称不重复）。
- [x] 补全菜单支持 ↑/↓ 选择、Enter 补入、Esc 关闭（验证：模型按键测试逐步检查菜单和输入）。
- [x] 继续输入会刷新或关闭候选（验证：打开菜单后输入字符，观察候选变化）。
- [x] 光标离开首个命令标识后 Tab 不触发补全（验证：在参数中和多行输入中按 Tab）。

## `/compact`

- [x] 当前上下文估算少于 5000 Token 时显示“当前上下文无需压缩”（验证：空会话执行 `/compact`）。
- [x] 少于阈值时不调用 Provider、不进入 Agent Loop、不写历史（验证：检查 fake client 调用数和 Conversation）。
- [x] 达到阈值时 `/compact` 执行现有标准压缩并保留结构化摘要（验证：压缩集成测试检查前后 Token 和摘要标题）。
- [x] `/compact 保留数据库相关内容` 将重点加入摘要请求（验证：检查发给摘要 Provider 的系统段）。
- [x] 保留重点和命令原文都不写入 Conversation（验证：压缩后扫描内存历史和 JSONL）。
- [x] 手动压缩的 Provider usage 被当前 Token 估算记录（验证：fake usage 后查询 Token 状态）。

## `/clear` 与 `/plan`

- [x] `/clear` 创建新 Session，并将当前 Conversation 置空（验证：比较执行前后 Session ID 和历史）。
- [x] `/clear` 后旧 Session 仍在列表中且可以恢复（验证：恢复后消息与清屏前一致）。
- [x] `/clear` 重置当前 Token 上下文并清除终端旧显示（验证：模型状态测试 + tmux 屏幕观察）。
- [x] `/clear` 保留 Provider、MCP、Memory 和运行期权限状态（验证：清屏前设置状态，清屏后执行 `/status` 比较）。
- [x] 第一次 `/plan` 将 `[DEFAULT]` 切到 `[PLAN]`，第二次恢复 `[DEFAULT]`（验证：连续执行并读取状态栏）。
- [x] Plan Mode 切换不调用 Provider，并立即刷新状态栏（验证：调用计数与模型 view）。

## `/session`

- [x] `/session` 显示当前 Session ID（验证：与 SessionManager 当前值比较）。
- [x] `/session list` 显示 ID、标题、最后活跃时间、模型、消息数和大小（验证：预置两个 Session 后检查顺序和字段）。
- [x] `/session resume <id>` 恢复历史并重绑上下文目录（验证：下一次 Provider 请求包含恢复历史，工具结果目录指向目标 Session）。
- [x] 缺失 ID、非法 ID、不存在或损坏的 Session 不改变当前状态（验证：逐种输入后比较当前 ID 和历史）。

## `/memory`

- [x] `/memory` 显示 user/project 数量和概要（验证：两级各预置笔记后检查输出）。
- [x] `/memory list` 显示每条笔记的级别、类别、标题和内容（验证：比较完整笔记快照）。
- [x] 四种合法类别分别写入正确的 user/project Store（验证：逐类 `/memory add` 后扫描目录和索引）。
- [x] 手动添加不调用 Provider，内容保持原样，文件名符合既有规则（验证：fake client 调用数、文件正文和文件名）。
- [x] 非法类别、空内容或缺失参数不创建或修改文件（验证：操作前后目录快照一致）。
- [x] `/memory clear` 显示明确确认提示，`n` 或 Esc 后两级文件不变（验证：比较执行前后文件内容）。
- [x] 确认 `y` 后删除 user/project 全部笔记并重建空索引（验证：扫描两个隔离测试目录）。
- [x] 两级清理任一提交失败时恢复两级旧快照，不显示成功（验证：故障注入后比较两级文件）。

## `/permission`

- [x] `/permission` 显示当前模式和有效规则数量（验证：配置规则与临时规则组合测试）。
- [x] `/permission rules` 按优先级显示规则模式、效果和来源（验证：临时规则排在配置规则之前）。
- [x] `/permission mode default|acceptEdits|bypassPermissions` 只改变当前进程（验证：切换后新 Run 使用新模式，重新构造后恢复配置值）。
- [x] 非法模式不改变当前模式（验证：操作前后快照一致并显示 usage）。
- [x] `/permission add WriteFile(**/secrets/**) deny` 立即优先于已有允许规则（验证：权限判定返回拒绝）。
- [x] 包含空格的规则目标可以完整解析（验证：添加 `Bash(git status) allow` 并匹配调用）。
- [x] 非法规则或非法效果不改变规则集合（验证：操作前后规则快照一致）。
- [x] `/permission reset` 清空临时规则并恢复启动模式（验证：配置规则和 Session 授权仍保留）。
- [x] 同一 Agent Run 的多轮使用固定权限快照，下一 Run 才读取命令变更（验证：协调器多轮测试）。
- [x] 权限命令不修改任何 YAML 或永久路径授权文件（验证：执行前后比较文件内容和 mtime）。

## `/status` 与 `/review`

- [x] `/status` 显示模式、权限、Provider/模型、Session、Token/窗口占比、工具数、两级 Memory 数量、MCP、工作目录和版本（验证：构造已知状态并逐字段比较）。
- [x] 工具数包含当前注册成功的内置和 MCP 工具（验证：连接测试 MCP 后检查数量）。
- [x] MCP 连接中、成功和有错误时状态文本均准确（验证：使用现有 MCP fake 场景）。
- [x] `/review` 把固定代码审查 Prompt 作为用户消息发送给 Agent（验证：检查 Conversation 和 Provider 请求）。
- [x] `/review 特别注意并发安全` 原样追加额外关注点（验证：检查最终用户消息）。
- [x] `/review` 进入 Agent Loop、允许使用现有工具并统计 Token（验证：fake tool round 和 usage 事件）。
- [x] 命令原文 `/review...` 不出现在历史中（验证：只存在展开后的 Prompt）。

## 集成、安全与回归

- [x] 使用 `UIController` 替身即可测试 UI 命令，不需要启动真实终端（验证：命令注册测试只使用内存替身）。
- [x] 不需要 Provider 的命令不会发起网络请求（验证：统一 fake client 调用计数）。
- [x] 本地命令不会等待 MCP 或其他网络初始化，TUI 仍可继续响应按键（验证：使用延迟 MCP 场景执行 `/help` 和 `/status`，检查事件循环未被阻塞）。
- [x] 参数错误、命令执行异常和确认取消后仍能提交下一条普通消息（验证：错误后执行一次成功对话）。
- [x] 命令错误不包含 API Key、Authorization、异常堆栈或模型原始响应（验证：注入带敏感文本的异常并检查输出）。
- [x] 帮助、执行和补全使用同一注册数据（验证：遍历可见命令并交叉检查三个入口）。
- [x] 普通对话、工具执行、权限确认、自动压缩、Session 恢复、Memory 自动更新和 MCP 的既有测试仍通过（验证：运行完整测试集）。
- [x] 项目没有新增命令插件、反射发现、动态加载或第三方依赖（验证：审查构建文件和新增命令包）。

## 编译、格式与自动化测试

- [x] 新增和修改的 Java 代码符合格式规则（验证：`./gradlew spotlessCheck` 返回 0）。
- [x] 命令注册与解析测试通过（验证：`./gradlew test --tests com.mewcode.command.CommandRegistryTest` 返回 0）。
- [x] Session、Memory、权限和上下文专项测试通过（验证：运行对应包的测试过滤器，全部返回 0）。
- [x] TUI 命令集成测试通过（验证：`./gradlew test --tests com.mewcode.tui.MewCodeModelTest` 返回 0）。
- [x] 完整测试集通过（验证：`./gradlew test` 返回 0）。
- [x] 项目完整构建成功并生成可运行 JAR（验证：`./gradlew build` 返回 0，`build/libs/mewcode.jar` 存在）。

## tmux 端到端场景

> 使用 `mktemp -d` 创建临时项目和临时 `user.home`，只复制运行所需配置。Memory 清理、权限变更和 Session 文件全部落在临时目录，不接触真实用户数据。

- [x] 场景 1：启动与帮助——在 tmux 启动 JAR，输入 `/help`、`/h`、未知命令，观察完整帮助、别名一致和 `/help` 引导。
- [x] 场景 2：补全菜单——输入共享前缀并发送 Tab，观察菜单；用 ↑/↓、Enter 完成选择，再用 Esc 关闭另一次菜单。
- [x] 场景 3：模式与状态——输入 `/plan`、`/status`、再次 `/plan`，观察 `[PLAN]`/`[DEFAULT]` 往返和完整状态字段。
- [x] 场景 4：新对话与恢复——完成一轮真实对话，记下 Session ID，执行 `/clear`，观察清屏和新 ID，再用 `/session resume <id>` 恢复并继续对话。
- [x] 场景 5：Memory——在临时 home/project 中分别添加 user/project Memory，执行概要和列表；先取消 `/memory clear` 验证保留，再确认清理并观察两级归零。
- [x] 场景 6：权限——添加临时 deny 规则、查看规则、切到 `bypassPermissions`、查看状态、执行 reset，观察优先级和模式恢复。
- [x] 场景 7：压缩——空上下文执行 `/compact` 观察无需压缩；准备超过阈值的临时 Session 后执行 `/compact 保留数据库相关内容`，观察压缩完成和重点保留。
- [x] 场景 8：Review——在临时 Git 项目制造未提交改动，执行 `/review 特别注意并发安全`，观察 Agent 调用 Git 工具并返回按严重度排序的审查结果。
- [x] 场景 9：退出——空闲时 Ctrl+C 退出，确认终端光标、输入模式和屏幕状态恢复正常。
