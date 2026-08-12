# 多协议 LLM 终端对话客户端 Checklist

> 每一项都通过运行命令或观察程序行为验证。验收时记录实际结果与证据，不以代码阅读代替运行结果。

## 2026-08-03 验收记录

### 已通过

- 自动测试：`clean test shadowJar` 成功；24 个测试全部通过（配置 6、会话 2、Anthropic 3、OpenAI 3、Markdown 2、TUI 8），零 skipped / failure / error。
- 构建产物：Java 21 fat jar 已生成于 `../../build/libs/mewcode.jar`；缺配置启动返回退出码 2，只输出一行安全错误，无堆栈。
- 本地协议集成：官方 Anthropic/OpenAI SDK 均通过本地 HTTP/SSE 服务验证；覆盖 system prompt、完整历史、自定义 `base_url`、thinking 事件、正文顺序、401、429 与关闭自动重试。
- 真实 PTY：验证了单 provider 直进、双 provider 方向键选择、状态栏、`Alt+Enter` 多行提交、流式增量、spinner/秒数、完成后 Markdown、`/exit` 和流式期间 `Ctrl+C`。
- 终端清理：真实长流中发送 `Ctrl+C` 后进程退出，并由 shutdown hook 输出恢复光标序列、关闭 JLine 终端；该项验收曾发现问题并已修复。
- 安全与范围：临时配置均已删除；`../../.mewcode/config.yaml` 被忽略、example 未被忽略；源码无 TBD/TODO、MCP/工具/远程/恢复入口；`git diff --check` 通过。
- 参数隔离：设置 `OPENAI_API_KEY` 并传入 `--remote -p --config` 后，程序仍只查找当前工作目录的 `../../.mewcode/config.yaml`，参数与环境变量未改变运行模式。

### 尚未执行或仅部分覆盖

- 未提供真实 Anthropic/OpenAI 密钥，因此所有明确要求“真实 provider”的云端场景未执行；没有用占位密钥冒充通过。
- 鼠标滚轮 scrollback、运行中反复缩放窗口，以及全部错误类型的真实 TUI 矩阵仅有框架行为/自动测试或部分 PTY 证据，仍需人工终端验收。
- 双 provider 场景已验证选择和状态栏，但未在选中第二项后完成真实云端对话。

### 验收口径例外

- “runtimeClasspath 不包含 Jackson/SLF4J”这一条按字面不通过：项目没有直接声明二者，但已批准采用的官方 Anthropic/OpenAI Java SDK 会传递依赖 Jackson、SLF4J。MCP SDK 与 Javalin 不存在。若强制移除这些传递依赖，官方 SDK 将无法正常工作，与已批准技术方案冲突。

> 下方保留原始逐项清单，未执行的人工/真实云端项目继续保持待验收状态；上述记录是本轮实际执行结果。

## 配置与启动

- [ ] 合法配置仅含一个 provider 时，程序不显示选择页，直接进入聊天界面。（验证：复制单 provider 示例为 `../../.mewcode/config.yaml` 后启动，观察首屏）`AC1/F1`
- [ ] 合法配置包含两个 provider 时，程序显示两项名称和模型；方向键可移动，Enter 后状态栏显示选中项。（验证：使用双 provider 配置进行真实 TUI 操作）`AC2/F2`
- [ ] 配置文件缺失时，程序输出包含缺失路径的单行错误并以非零状态退出，无 Java 堆栈。（验证：在无 `../../.mewcode/config.yaml` 的临时工作目录运行 jar 并记录退出码）`AC1/N4`
- [ ] YAML 语法损坏或 `providers` 为空时，程序分别给出可定位错误并非零退出。（验证：为两个场景分别启动并记录 stderr 与退出码）`AC1/N4`
- [ ] `name`、`protocol`、`model`、`api_key` 任一缺失时，错误准确指出 `providers[index].field`。（验证：运行配置参数化测试）`AC1/F1`
- [ ] 重复 provider 名称、未知 protocol、非法 HTTP/HTTPS `base_url` 均被启动期校验拒绝。（验证：运行配置参数化测试）`AC1/N4`
- [ ] `base_url` 省略或留空时配置仍合法，`thinking` 省略时按关闭处理。（验证：运行合法配置单元测试）`AC1/F1`
- [ ] 程序只读取当前项目的 `../../.mewcode/config.yaml`，不会因用户目录配置、环境变量或命令行参数改变 provider。（验证：设置同名环境变量和用户级配置后在项目目录启动，观察仍采用项目配置）`F1/N9`

## 请求与协议适配

- [ ] Anthropic 首轮请求包含 model、内置 system prompt 和当前 user 消息。（验证：指向记录请求体的本地 Anthropic SSE server 并断言内容）`AC4/F4`
- [ ] OpenAI 首轮请求以 system 消息开头，随后包含当前 user 消息，并使用 Chat Completions 流式端点。（验证：指向记录请求体的本地 OpenAI SSE server 并断言路径与内容）`AC4/F4`
- [ ] 第二轮请求包含第一轮完整 user/assistant 消息及新的 user 消息，消息正文中不含 ANSI 控制码。（验证：本地 server 记录连续两轮请求体并比对）`AC4/AC6/F4/F6`
- [ ] Anthropic `thinking: true` 时请求包含约定的 thinking 配置；`thinking: false` 时不包含。（验证：运行两种配置的 Anthropic 请求测试并检查请求体）`AC4/F4`
- [ ] OpenAI 配置中的 `thinking: true` 不产生 reasoning/thinking 请求字段。（验证：本地 server 捕获 OpenAI 请求体并搜索相关字段）`F4`
- [ ] 两个 SDK 遇到一次 429 响应时只发送一次 HTTP 请求，不自动重试。（验证：本地 server 计数并返回 429，断言计数为 1）`N3/不自动重试`
- [ ] 自定义 OpenAI `base_url` 能正确访问该地址下的 Chat Completions 流并收到正文。（验证：配置本地兼容 server 的 `/v1` 地址跑通一轮）`AC3/F3`
- [ ] 使用真实 Anthropic 配置可完成一轮流式对话。（验证：在不打印密钥的前提下运行真实 provider，观察成功回复）`AC3/F3`
- [ ] 使用真实 OpenAI 配置可完成一轮流式对话。（验证：切换项目配置后运行真实 provider，观察成功回复）`AC3/F3`

## 流式、Thinking 与多轮

- [ ] SSE 正文被拆成至少三个增量时，界面按到达顺序逐步显示，而不是最后一次性出现。（验证：本地延迟 SSE server 每段间隔发送，录屏或逐帧观察）`AC5/F5`
- [ ] Anthropic thinking 增量到达时，界面持续显示进行中状态，但任何 thinking 文字均不可见。（验证：使用包含唯一 thinking 标记的测试流，检查全部终端输出不存在该标记）`AC5/F5`
- [ ] thinking 唯一标记不进入下一轮请求、Markdown 结果或会话退出输出。（验证：thinking 测试流后发第二轮，检查请求体及终端捕获输出）`AC5/N5`
- [ ] 第一轮告知随机信息，第二轮追问时模型能正确引用该信息。（验证：用真实 provider 完成两轮对话并记录回答）`AC6/F6`
- [ ] 退出并重新启动后追问上一进程的随机信息，模型没有上一会话上下文。（验证：重启后直接追问并观察回答）`AC6/F6`
- [ ] 调用失败后没有虚构的 assistant 消息进入下一轮请求；此前 user 消息仍按协议允许的方式保留。（验证：本地 server 首轮中断、第二轮记录请求体）`F6/F11`

## TUI 布局与输入

- [ ] 聊天首屏同时包含 ASCII 猫、`MewCode 0.1.0`、当前工作目录和就绪提示。（验证：启动后截图或终端捕获）`AC7/F7`
- [ ] 首屏包含带边界的输入区、`❯`、`Send a message...`，状态栏左侧是 provider 名、右侧是 model。（验证：启动后截图并逐项比对）`AC7/F7`
- [ ] `Alt+Enter` 在当前光标处插入换行，Enter 提交完整多行文本并清空输入框。（验证：输入两行带唯一标记的文本，检查用户消息显示与请求体）`AC9/F9`
- [ ] Backspace、左右移动、Home 和 End 在多行输入中不会破坏字符顺序。（验证：编辑预设文本后提交，检查最终请求体）`F9`
- [ ] 等待或流式期间按 Enter 不会发起第二个请求，输入区域不可编辑；本轮结束后恢复。（验证：延迟 SSE server 计数并在生成期间连续按 Enter）`AC9/F9`
- [ ] 完成消息进入原生终端 scrollback，鼠标滚轮可查看先前终端输出和长对话，内容不重复。（验证：启动前先输出标记文本，再生成超过一屏的回复并滚动检查）`F7/N1`

## Markdown 与计时

- [ ] 流式期间代码围栏、列表符号和强调标记以原始纯文本增量出现，不进行中途 Markdown 重排。（验证：延迟返回包含 Markdown 的测试流并观察生成过程）`AC8/F8`
- [ ] 回复结束后，标题、强调、列表和 fenced code block 被终端 Markdown 样式正确定型，文字无丢失、重复或乱序。（验证：使用固定 Markdown 测试回复并比对原文与最终显示）`AC8/F8`
- [ ] 窄于 20 列的输入宽度不会导致 Markdown 渲染异常；常规宽度下内容按可用宽度换行。（验证：运行 Markdown 宽度单测并手动缩窄终端）`AC15/N6`
- [ ] 提交后、首个正文增量前立即出现 `Imagining… (0s)` 或等价进行中状态，秒数随时间递增。（验证：本地 server 延迟首个事件至少 3 秒并观察 0、1、2、3 秒）`AC12/F12`
- [ ] 成功回复与错误回复均显示从请求发出开始计算的总耗时。（验证：分别运行固定延迟成功流和固定延迟错误流，比对显示时间）`AC12/F12`

## 错误恢复与安全

- [ ] 鉴权失败、429 限流、模型不存在、上下文超限、网络中断和无效 SSE 均显示可区分的安全错误。（验证：本地 server 逐一模拟并记录终端输出）`AC11/F11`
- [ ] 每种请求错误发生后程序保持运行、输入框恢复，并能提交下一条消息。（验证：每个模拟错误后再提交唯一文本，检查 server 收到新请求）`AC11/F11`
- [ ] 流中已经出现部分正文后连接中断，部分正文和错误均可见，但部分正文不进入下一轮 assistant 历史。（验证：先发送文本再断开，随后检查下一轮请求体）`AC11/F11`
- [ ] 配置错误、鉴权错误、协议错误和正常输出均不包含完整 API 密钥。（验证：使用唯一测试密钥运行全部相关测试，对捕获输出执行精确搜索）`AC14/N5`
- [ ] `ProviderConfig.toString()`、异常消息和测试失败输出不会回显密钥。（验证：运行安全相关单元测试）`AC14/N5`
- [ ] `../../.mewcode/config.yaml` 被 Git 忽略，example 被跟踪且只含占位密钥。（验证：运行 `git check-ignore` 并检查 example 内容）`AC14/N5`

## 响应性与退出

- [ ] 首事件延迟和长流期间 spinner、计时与窗口尺寸更新持续变化，界面没有冻结。（验证：延迟 SSE 场景中调整窗口并观察）`AC13/N1/N2`
- [ ] Provider 选择页、空闲聊天页和流式回复期间按 `Ctrl+C` 均直接结束程序。（验证：三个状态分别运行并记录退出行为）`AC10/F10`
- [ ] 空闲时输入 `/exit` 安全结束，且不会向 provider 发请求。（验证：本地 server 计数保持 0）`AC10/F10`
- [ ] 流式期间 `Ctrl+C` 不返回输入框、不继续打印后续增量，并结束进程。（验证：长流场景中按 Ctrl+C，观察退出和 server 连接结束）`AC10/F10`
- [ ] 每种退出路径后终端光标、输入回显和行编辑均正常，无 raw mode 残留。（验证：退出后立即输入并编辑一条 shell 命令）`AC15/N7`
- [ ] 动态缩放终端后 banner、流式区域、输入框和状态栏不崩溃、不越界破坏终端。（验证：宽、窄尺寸间多次切换并观察）`AC15/N6`

## 架构与范围集成

- [ ] Anthropic 与 OpenAI 都通过同一 TUI 发送、流式、完成和错误路径工作，切换协议不需要改变上层操作。（验证：对两种 provider 执行同一组脚本化场景并比对状态转换）`AC3/AC16/N3/N8`
- [ ] 队列中的增量顺序与最终 assistant 原文完全一致，且每轮只有一个 StreamEnd 或 Error。（验证：两套本地 SSE 测试统计事件并拼接比对）`N1/N3`
- [ ] 最终应用没有用户可触达的工具调用、文件操作、MCP、权限、远程、Agent 循环或会话恢复入口。（验证：启动界面和可用输入行为检查，并对源码包名/入口做范围搜索）`AC16/N9`
- [ ] 除 `/exit` 外，`/help`、`/clear`、`/model` 等输入按普通对话消息发送，不触发本地命令。（验证：本地 server 记录三条输入均作为 user 内容到达）`AC16/N9`
- [ ] 程序不接受 `--config`、`-p` 或 `--remote` 改变运行模式。（验证：分别带参数启动，观察仍只按项目配置进入相同 TUI）`AC16/N9`

## 编译与自动测试

- [ ] `./gradlew compileJava` 无编译错误。（验证：记录命令退出码为 0）
- [ ] `./gradlew test` 全部通过，包含配置、会话、双 SSE、Markdown 和 TUI 状态测试。（验证：记录 Gradle 测试摘要）
- [ ] `./gradlew clean shadowJar` 成功生成 `../../build/libs/mewcode.jar`，jar 可由 Java 21 启动。（验证：检查产物并运行缺配置启动场景）
- [ ] `git diff --check` 无空白错误，源码中无 TBD/TODO 占位实现。（验证：运行命令及 `rg 'TBD|TODO' src`）
- [ ] 构建依赖不包含 MCP SDK、Javalin、Jackson、SLF4J 或其他本期外组件。（验证：运行 `./gradlew dependencies --configuration runtimeClasspath` 并检查输出）`AC16/N9`

## 端到端场景

- [ ] **场景 1：Anthropic 多轮 Thinking**——单 Anthropic provider 启动 → 首轮开启 thinking 并流式回答 → thinking 文本不可见 → 第二轮正确引用第一轮 → Markdown 定型 → `/exit` 后终端正常。（验证：真实 Anthropic API + 终端录屏）
- [ ] **场景 2：OpenAI Markdown**——单 OpenAI provider 启动 → 请求含标题、列表、强调和代码块的回答 → 增量逐步显示 → 完成后正确定型 → Ctrl+C 退出。（验证：真实 OpenAI API + 终端录屏）
- [ ] **场景 3：多 Provider 选择**——双 provider 配置启动 → 方向键选择第二项 → 状态栏匹配 → 完成一轮对话。（验证：真实 TUI 操作和截图）
- [ ] **场景 4：自定义兼容端点**——OpenAI protocol + 自定义 `base_url` → 完成一轮延迟 SSE 对话 → 行为与标准 OpenAI 一致。（验证：本地兼容 server 请求日志与终端输出）
- [ ] **场景 5：错误恢复**——本地端点先返回鉴权错误 → 界面显示安全错误并恢复输入 → 下一请求返回正常流 → 程序继续对话。（验证：可切换响应的本地 server + 终端录屏）
- [ ] **场景 6：长回复与退出**——生成超过一屏的长回复 → 等待/流式期间界面响应 → scrollback 可回看且不重复 → 流式期间 Ctrl+C → shell 立即可正常编辑。（验证：长延迟流 + 终端操作记录）

---

## DeepSeek OpenAI 兼容配置增量

- [x] 本地配置与示例模板都包含第三个 `deepseek-openai` provider，使用 `protocol: openai`、`model: deepseek-v4-flash`、`base_url: https://api.deepseek.com`、`thinking: false`。（证据：两份 YAML 的第三项非敏感字段一致）`DAC1/DF1/DF3`
- [x] `../../.mewcode/config.yaml.example` 只含占位 Key，`../../.mewcode/config.yaml` 继续被 Git 忽略，Git diff 不包含真实 API Key。（证据：`git check-ignore` 命中本地配置，example 未被忽略且 diff 只有占位配置）`DAC2/DF2/DN1`
- [x] `./gradlew clean test shadowJar` 成功，24 个现有测试全部通过并生成 `../../build/libs/mewcode.jar`。（证据：24 tests、0 skipped/failure/error，Shadow Jar 约 73 MB）`DAC5/DF5/DN2`
- [x] 使用 Java 21 在 tmux 启动后，选择页依次显示 Claude、OpenAI、`deepseek-openai (deepseek-v4-flash)`；选择第三项后状态栏匹配，输入 `/exit` 后恢复 shell。（证据：Java 21.0.11 启动，状态栏匹配，退出后 pane 命令为 `zsh`）`DAC3/DF4`
- [x] 用户在 IDE 中替换本地 DeepSeek Key 后，tmux 中选择第三项并发送真实请求，正文以流式方式返回，完成后输入框恢复，退出后终端正常。（证据：真实请求在 2.2 秒返回 `DeepSeek E2E OK`，输入框恢复，`/exit` 后 pane 命令为 `zsh`）`DAC4/DF1/DF2`
