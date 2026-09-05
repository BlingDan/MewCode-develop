# MewCode Skill 系统 Checklist

> 状态：已通过（2026-09-02）
>
> 上游文档：[spec.md](./spec.md) · [plan.md](./plan.md) · [task.md](./task.md)
>
> 每项均以运行结果或可观察行为为准；开发完成后在 tmux 中逐项记录实际证据。

## 定义、解析与发现

- [x] 单文件和目录型 Skill 均能被发现，启动摘要只出现名称与一句说明。（验证：捕获首轮 Provider 请求并检查 system segments；AC1、AC5）
- [x] 缺字段、坏 YAML、非法枚举和非法 `context_count` 只跳过对应文件，诊断含来源、不含正文，其他 Skill 可用。（验证：分别放入四类坏定义后启动；AC2）
- [x] 同名内置、用户、项目定义按项目 > 用户 > 内置生效，删除高优先级后触发刷新会逐级回退。（验证：三层使用不同说明并依次删除；AC3）
- [x] 大小写不同但归一后同名的定义只保留一个确定结果。（验证：放入 `Review`/`review` 后重复启动对比列表；AC4）
- [x] 最终 Skill 列表和顺序在相同文件集下多次启动保持一致。（验证：连续启动三次并对比 `/help`；N1）
- [x] 白名单引用不存在的普通、脚本或 MCP 工具时，进入 TUI 前显示 Skill 名和工具名并以状态 2 退出。（验证：三类未知引用分别启动；AC6）
- [x] 未被 Skill 引用的 MCP Server 连接失败只显示既有诊断，其他功能仍进入 TUI。（验证：添加不可连接且未引用的 MCP 配置；AC7）

## 两阶段加载与生命周期

- [x] 普通请求首轮只能看到 Skill 摘要；Agent 调用 `LoadSkill` 后，下一轮才看到完整 SOP。（验证：用假 Provider 记录连续请求；AC8）
- [x] `LoadSkill` 在 Plan Mode、空白名单和未列入任何 Skill 白名单时始终可见可执行。（验证：三种状态检查 schemas 并实际调用；AC9）
- [x] `/skill-name 原始 参数内容` 会替换全部 `{{arguments}}`；无占位符时追加独立“用户输入”；空参数不追加。（验证：三个样例 Skill 捕获最终 SOP；AC10）
- [x] shared 请求经历多轮 Provider/工具调用时，每轮都包含相同已激活 SOP，且 SOP 位于动态 system segments 最后。（验证：三轮假 Provider 请求对比；AC11）
- [x] shared 请求正常、失败、取消后，下一普通请求不含上次 SOP、脚本工具和 Provider 偏好。（验证：依次触发三种收尾再发普通消息；AC12）
- [x] 同一请求加载两个 Skill 后，两份 SOP 同时存在，工具列表恰好是白名单去重并集。（验证：两个重叠白名单 Skill 连续加载；AC13）
- [x] Plan Mode 中声明写工具的 Skill 可以加载，并明确显示被过滤工具；Provider 不见该工具，伪造调用也被本地拒绝。（验证：检查 schemas 并注入工具调用；AC14）
- [x] 同一请求运行期间修改磁盘定义不会改变已激活内容；显式重载同名 Skill 后才替换。（验证：加载、改文件、继续一轮、再 LoadSkill；N8）

## shared、fork 与历史

- [x] shared Skill 的原始用户输入、assistant 回复、工具调用及结果保留在主历史，请求结束仅停用 Skill。（验证：执行含工具 shared Skill 后检查 session 历史；AC15）
- [x] fork 启动后主流程等待，完成后恢复，Session 列表中没有 fork 会话。（验证：运行延迟 fork 并同时观察 UI 与 `/session list`；AC16）
- [x] `context:none` 的 fork 只看到本次参数与 SOP，看不到主历史。（验证：主历史写入唯一标记后运行 none；AC17）
- [x] `context:recent` 默认复制最近 3 个完整用户轮次，显式数量生效，工具调用与结果不会被拆散。（验证：构造 5 轮含工具历史并检查 fork 请求；AC18）
- [x] `context:full` 原样包含调用前完整主历史。（验证：逐条比较主历史与 fork 初始 history；AC19）
- [x] fork 成功只向主历史回流结果摘要；失败、取消、超时只回流安全错误摘要，不包含内部消息、工具细节、堆栈或凭据。（验证：四种 fork 结果后检查主历史；AC20）
- [x] fork 内可加载 shared Skill，但嵌套 fork 被明确拒绝且没有残留后台运行。（验证：子 Skill 分别请求 shared 与 fork；N10）

## Provider 选择与回退

- [x] 未声明 `model` 的 shared 与 fork 均使用用户当前选择的主 Provider。（验证：记录两种运行的实际 client；AC21）
- [x] 声明可用 Provider 时对应轮次使用它；shared 后加载另一个带模型 Skill 后，下一轮切换到后者 Provider。（验证：三个可区分假 client 记录顺序；AC22）
- [x] Skill Provider 未配置时直接走主 Provider；调用失败时最多回退主 Provider 一次；主 Provider 也失败则结束。（验证：未配置、单失败、双失败三组计数；AC23）
- [x] 偏好 Provider 失败的半截流文本会被清除，不写入历史；两次尝试已报告 token 均计入用量。（验证：首 client 输出半截再失败；N7）
- [x] shared、fork、失败和取消结束后，下一普通请求仍使用原主 Provider。（验证：四类收尾后检查 client；AC24）

## 专属脚本工具与安全

- [x] 目录型专属工具在 Skill 激活前不进入 Provider schemas，激活后仅按白名单出现，结束后再次消失。（验证：记录激活前中后三轮 schemas；AC25）
- [x] 有效 shebang 与可执行权限的脚本从 stdin 收到符合 schema 的 JSON，并通过 stdout 合法 JSON 返回内容。（验证：临时脚本回显参数；AC26）
- [x] 缺 shebang、不可执行、非法 schema、越出 Skill 目录和工具名冲突均拒绝对应定义并显示安全诊断。（验证：五个独立坏工具包；AC27）
- [x] 参数不合 schema、非零退出、非法 stdout、超时和取消均返回可操作错误，TUI 之后仍能对话且不显示环境变量、凭据或堆栈。（验证：五个脚本场景后发送普通请求；AC28）
- [x] 脚本只得到允许的基础环境，工作目录是 Skill 目录，不能经路径或符号链接逃逸。（验证：脚本打印 cwd/环境键并构造链接逃逸；N6）
- [x] 脚本工具进入与 Bash 同级的现有权限确认/拒绝流程，拒绝后不启动进程。（验证：权限规则 DENY 与 ASK→DENY；AC29）

## 斜杠命令、热更新与内置样板

- [x] 最终 Skill 自动出现在 `/help` 和 Tab 补全，说明一致；同名 slash 能启动对应 Skill。（验证：对比帮助、补全和实际请求；AC30）
- [x] Skill 使用 `/help`、`/clear` 或静态别名时被跳过并诊断，原命令行为不变。（验证：逐个保留名定义；AC31）
- [x] `/review` 使用最终生效的 review Skill，不存在旧硬编码 Prompt 或 `/r`；用户/项目版本可覆盖内置。（验证：捕获 SOP 并做三级覆盖；AC32）
- [x] 新增、修改、删除 Skill 后无需重启；下一次 Tab、slash 提交或 `LoadSkill` 分别能触发刷新。（验证：三个触发点各做一次磁盘变更；AC33）
- [x] 解析失败或引用未知工具的高优先级热更新会跳过该定义并使用低优先级有效版本；没有低层版本时从 Catalog 移除，TUI 不退出。（验证：解析错误和白名单错误两组更新；AC34）
- [x] 内置 `commit`、`review`、`test` 均能通过 slash 执行，并只获得各自声明工具和模式。（验证：逐个捕获 mode、context 和 schemas；AC35）
- [x] `/clear` 后无活动 SOP、脚本工具和临时 Provider；Catalog、Memory、MCP、主 Provider 配置和磁盘 Skill 不变。（验证：激活后 clear 并逐项查询；AC36）

## 集成、构建与回归

- [x] Provider schemas 与 ToolExecutor 使用同一个工具策略，包括启用权限系统的路径。（验证：策略矩阵集成测试通过；N5）
- [x] 同一响应同时请求 `LoadSkill` 和普通工具时，只执行加载，普通工具收到下一轮重选提示。（验证：假 Provider 发出混合 tool calls；F8、F9）
- [x] MCP、脚本、普通工具名冲突均产生确定诊断，不发生静默覆盖。（验证：构造三类同名；N12）
- [x] 普通对话、Plan Mode、权限、自动压缩、Session 恢复、Memory、系统命令和 MCP 既有测试全部通过。（验证：`./gradlew test`；AC37）
- [x] 所有新增 Java 代码格式正确且可打包运行。（验证：`./gradlew spotlessCheck shadowJar` 均退出 0）
- [x] 实现未新增模板引擎、文件监听器、运行时安装、市场/安装/版本或通用插件框架。（验证：检查依赖变更和用户可见命令；N15）

## tmux 端到端：正常流程

- [x] 在 tmux 启动 MewCode，Tab 中看到三个内置 Skill；执行带参数 shared Skill，观察 SOP 生效及工具过滤。（验证：保存终端输出；AC38）
- [x] 在同一 tmux 会话执行 fork Skill，主界面等待后只收到摘要；修改项目 Skill，再次 Tab/调用看到热更新。（验证：保存修改前后终端输出；AC38）
- [x] 在上述流程后执行 `/clear` 并发送普通请求，确认没有上次 Skill 状态。（验证：保存 `/status`、请求与响应；AC38）

## tmux 端到端：错误流程

- [x] 无效 Skill 被隔离且其他 Skill 可用；缺失白名单工具在 TUI 前阻止启动。（验证：两个隔离工作目录的进程输出和退出码；AC39）
- [x] Skill Provider 不可用时界面提示一次回退且主 Provider完成请求。（验证：临时 Provider 配置与终端输出；AC39）
- [x] 脚本权限被拒绝或执行中取消后，进程无残留且 TUI 可继续普通对话。（验证：权限提示、取消、进程检查和后续响应；AC39）

## 验收收口

- [x] `spec.md` 的 AC1–AC39 均至少由上面一个条目覆盖。（验证：按条目标注逐号核对）
- [x] 所有 checklist 条目均已记录实际结果，不以“预计通过”代替证据。（验证：验收报告包含命令、现象或测试输出）

## 验收记录（2026-09-02）

### 自动化与构建

- `./gradlew spotlessCheck test shadowJar`：退出码 0；280 个测试全部通过、无跳过，shadow JAR 生成成功。
- 新增测试覆盖：定义解析、参数渲染、三级覆盖及回退、热更新未知工具与工具冲突、系统加载工具、白名单和 Plan Mode 交集、权限执行层拦截、Provider 选择与单次回退、fork 历史切片与临时运行、脚本协议及安全错误、动态 slash、摘要写回和 TUI 生命周期。
- `git diff --check`：退出码 0；`build.gradle.kts` 只扩展 Spotless 文件范围，没有新增依赖。

### tmux 正常流程

- 隔离项目启动后，`/help` 同时列出静态命令和内置 `/commit`、`/review`、`/test`。
- 输入“请使用可用的 test skill 运行单元测试”：首轮显示 `LoadSkill(name=test)`，工具结果为“已加载 Skill test”，第二轮按完整 SOP 返回“测试 Skill 已按完整 SOP 执行完成”。
- 输入 `/review 关注并发`：主界面等待临时运行完成，只显示最终摘要“独立审查完成：未发现阻塞问题”。
- 运行中新增项目级 `hello.md` 后直接输入 `/hello 最终验收`，未重启 MewCode 即返回“项目 Skill 热更新成功”。
- 激活 `/hello` 后执行 `/clear`，再发送 `CHECK_CLEAR`，返回“清理成功：无活动 SOP”，新会话仍保留磁盘 Skill 和主 Provider。

### tmux 错误流程

- 同目录放置坏 YAML `broken.md` 与合法 `working.md`：终端显示坏文件路径及解析诊断，TUI 正常启动，`/help` 中存在 `/working`。
- Skill 白名单声明 `DefinitelyMissingTool`：程序在 banner/TUI 前输出 Skill 名与工具名，并以状态 2 退出。
- Skill 指定不可连接的 `unavailable-e2e` Provider：界面只显示一次“已回退到 main-e2e”，随后主 Provider 返回“主 Provider 回退成功”。
- 目录型 `script-demo` 请求专属 `skill_echo`：出现现有权限确认；输入 `n` 后脚本未执行，Agent 收到拒绝结果并完成本轮；随后 `CHECK_AFTER_DENY` 返回“权限拒绝后仍可继续对话”。

以上 tmux 流程使用本地 OpenAI SSE 假 Provider，只控制模型输出；Skill 发现、提示构造、工具选择、权限、fork、Session 与 TUI 均运行打包后的真实 MewCode 代码。

### 启动回归补充（2026-09-04）

- 更正：此前只用隔离配置完成 Skill 流程，没有用项目当前真实 MCP 配置确认 TUI 能打开；原“全部通过”结论不完整。
- 原始复现：Java 21 启动后主线程持续停在 `MewCode.run → McpManager.connectAll → McpSyncClient.initialize`，只显示 SLF4J 提示、不出现 banner。
- 最小复现：配置一个永不响应且未被 Skill 引用的 stdio MCP；修复前 1 分 46 秒仍无 TUI，修复后数秒内出现 banner、输入框和“`MCP 正在连接…`”。
- 真实配置复测：Provider 选择界面和聊天界面均正常出现；MCP 后台连接期间 `/help` 可立即执行；Ctrl+C 可干净返回 shell。
