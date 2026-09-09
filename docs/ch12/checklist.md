# Hook 生命周期挂钩系统 Checklist

> 状态：开发已完成；V1—V8、B01—B04 已执行并通过。真实 Provider 认证失败，完整 tmux 对话闭环条目保持未勾选。
>
> 依据：已批准的 [spec.md](./spec.md)、[plan.md](./plan.md)、[task.md](./task.md)。等待用户后续明确指示再开始开发。

## 验收方式

- 验收对象是可观察行为，不以类、方法或文件存在作为功能通过依据。
- 所有 Gradle 命令在 `/Users/bytedance/IdeaProjects/Mewcode-develop` 运行，使用项目要求的 JDK 21。
- 下文 V1—V8 是可执行验证组，条目同时说明输入与预期；测试方法名可以变化，但被验证的行为不能省略。
- 自动化测试必须确实包含对应场景和断言，不能仅凭同名测试类通过就勾选。仍适用于当前代码的同次测试结果可复用。
- 在开发阶段的 `docs/ch12/verification.md` 中按条目 ID 记录实际命令、结果、测试报告或终端/事件证据；此时不预填成功记录。
- 失败、跳过、环境受阻均保持未勾选，记录原因。修复后重跑受影响条目，不能把预期写成实际结果。
- tmux 使用真实 Provider 和真实输入；仅靠模型复述“已执行”不算工具成功证据，需核对工具结果、文件或本地接收记录。
- reminder 是否仅发送一次，以捕获到的 Provider 请求为准；模型在后续回复中重复测试短语不能证明 reminder 被重复发送。

## 验证命令组

### V1：匹配与权限兼容

```bash
./gradlew test --tests com.mewcode.permission.RuleMatcherTest --tests 'com.mewcode.permission.*' --tests com.mewcode.config.PermissionConfigLoaderTest
```

### V2：Hook 配置与条件

```bash
./gradlew test --tests com.mewcode.config.HookConfigLoaderTest --tests com.mewcode.hook.HookEngineTest
```

### V3：动作协议与资源边界

```bash
./gradlew test --tests com.mewcode.hook.HookActionExecutorTest --tests com.mewcode.tool.BashSandboxIntegrationTest
```

### V4：模型、reminder 与压缩

```bash
./gradlew test --tests com.mewcode.agent.AgentTurnCoordinatorHookTest --tests com.mewcode.agent.PromptRequestFactoryTest --tests com.mewcode.compact.ContextManagerTest
```

### V5：工具与 Skill 集成

```bash
./gradlew test --tests com.mewcode.tool.ToolExecutorTest --tests com.mewcode.tool.PermissionToolExecutorTest --tests com.mewcode.agent.AgentTurnCoordinatorHookTest --tests com.mewcode.agent.AgentTurnCoordinatorSkillTest --tests com.mewcode.agent.AgentProtocolIntegrationTest
```

### V6：会话、输入与启停

```bash
./gradlew test --tests com.mewcode.MewCodeTest --tests com.mewcode.tui.MewCodeModelTest --tests com.mewcode.session.SessionManagerTest
```

### V7：异步与状态隔离

```bash
./gradlew test --tests com.mewcode.hook.HookEngineTest --tests com.mewcode.hook.HookActionExecutorTest --tests com.mewcode.tui.MewCodeModelTest
```

### V8：命令与发现

```bash
./gradlew test --tests com.mewcode.command.CommandRegistryTest --tests com.mewcode.tui.MewCodeModelTest --tests com.mewcode.skill.SkillCatalogTest
```

## 功能验收：对应 Spec 的 AC1—AC25

- [x] C01 / AC1：加载旧字符串权限规则，验证 `*`、`?`、路径、换行以及以 `=`、`!`、`~` 开头的目标仍保持旧含义；使用新增结构化 exact、glob、regex、not 验证命中与不命中，并保持第一条命中及既有授权优先级。（验证：V1；核对输入目标和判定结果，非法新旧格式混用被拒绝。）

- [ ] C02 / AC2：分别加载非法权限规则和非法 Hook 规则，前者使权限配置失败且应用不进入交互，后者只跳过并诊断，其他合法 Hook 与主流程继续。（验证：V1、V2、V6；检查启动结果及诊断来源，不只检查解析异常。）

- [ ] C03 / AC3：项目级与用户级文件同时存在时规则共同生效；同名保留项目级、报告后加载冲突；无配置正常启动；无效项目规则不抢占合法用户规则名称。（验证：V2、V8；比较最终规则和来源列表。）

- [ ] C04 / AC4：逐一输入缺少 name/event/action、未知事件/动作、非法正则、非法取反、零/负/溢出超时、条件组同时出现或嵌套、空条件数组，均跳过出错 Hook；任意动作在两个拦截事件配置 async 都拒绝。（验证：V2；有效相邻规则仍加载，诊断含来源及原因。）

- [ ] C05 / AC5：对 `tool_input.command`、`tool_input.path` 和布尔/数字字段验证四种匹配及 all_of/any_of；缺失、null、对象及数组不被隐式匹配，缺失字段在 not 下也不成立；省略 if 无条件触发。（验证：V2；用输入矩阵和实际执行次数判断。）

- [ ] C06 / AC6：启动、新建、恢复和结束会话时按约定触发 SessionStart/SessionEnd/SessionResume；恢复不同时触发 SessionStart，新会话事件先于首条用户历史写入。（验证：V6；记录事件顺序、会话 ID 和写历史时点。）

- [ ] C07 / AC7：一次用户请求产生多次模型调用，TurnStart 只有一次，每次实际模型调用有对应 MessageStart/MessageEnd；Stop 仅自然完成一次，取消、失败和迭代上限不触发。（验证：V4；比较事件日志与实际请求次数，流式片段不增加事件数。）

- [ ] C08 / AC8：初始化后触发 startup，正常退出先 SessionEnd 再 shutdown；主流程错误触发 error，Hook 自身失败不递归触发，用户取消不算 error。（验证：V4、V6；检查调用序列，重复关闭不重复触发。）

- [ ] C09 / AC9：自动、手动和紧急恢复三种实际压缩完成后各触发一次 compact，trigger 及可用 token 统计准确；没有改变历史的压缩不伪造完成事件。（验证：V4；比较压缩结果和事件 payload。）

- [x] C10 / AC10：PreToolUse 明确拒绝时，权限预判、权限确认和真实工具均未执行；模型获得与该调用配对的拒绝原因。Hook 放行后原权限仍可拒绝。（验证：V5；用权限路径观测点和工具执行计数证明，批量安全分类也不能提前检查权限。）

- [ ] C11 / AC11：工具成功、执行失败、Hook 拒绝及权限拒绝均生成一次 PostToolUse，结果文本、is_error、status 与最终回流一致；超时后迟到的工作线程不再发送第二次终态。（验证：V5；比较事件数量、调用位置及模型结果配对。）

- [x] C12 / AC12：UserPromptSubmit 拒绝后无用户历史、无模型请求，UI 展示原因且可继续编辑；普通 Hook 故障允许继续提交。Slash 原始输入不被该事件拦截。（验证：V6；比较提交前后历史和 Provider 调用计数，检查输入内容及界面状态。）

- [x] C13 / AC13：shell 在项目目录接收完整 stdin JSON，stdout/stderr 独立；exit 2 且有效原因才拒绝，stderr 优先，否则 stdout，exit 0 正常，其他非零仅失败。（验证：V3；真实短进程返回不同状态，检查 cwd、输入和拒绝原因。）

- [x] C14 / AC14：本地 HTTP 接收端获得配置的方法、头和正文；默认 POST/事件 JSON，显式模板正确取嵌套字段；仅 2xx 且合法 block 和非空 reason 表达拦截。（验证：V3；捕获实际请求与返回决定，模板缺字段时接收请求数为零。）

- [ ] C15 / AC15：命令超时、网络失败、非 2xx、坏 JSON、空拒绝原因及超限响应均只记失败并放行；默认超时解析为 30 秒，显式短超时实际终止长动作。（验证：V2、V3；默认值用配置断言，实际截止用短超时场景，不用等待 30 秒证明默认值。）

- [ ] C16 / AC16：分别由会话事件和工具事件注入文本，下一次模型请求 reminder 包含文本，再下一次没有；已发请求失败后不重复注入，持久历史不保存 Hook reminder。（验证：V4；比较捕获的请求和历史快照，不以模型回复措辞代替。）

- [ ] C17 / AC17：多条同步注入保持声明顺序并位于既有 reminder 之后；预检与实际发送内容一致且预算包含注入，compact 新增注入也参与最终预算。（验证：V4；构造接近预算边界请求，核对发送内容、估算和超预算结果。）

- [x] C18 / AC18：only_once 第一次匹配执行后跳过后续触发，动作失败也算执行过，不匹配不消耗次数；并发及执行未结束时同规则最多启动一次。（验证：V7；用屏障与执行计数，异步提交失败不能遗留执行中占用。）

- [ ] C19 / AC19：/clear、实际恢复命令 `/session resume` 和进程重启重置 only_once；旧未消费队列被清空，旧异步完成不能把提示词写入新会话。（验证：V6、V7；用不同会话 ID 和唯一注入文本比对新请求。）

- [x] C20 / AC20：耗时异步动作不阻塞主流程；拦截事件不接受 async；动作响应取消，后台关闭最多等待 5 秒，不无限阻塞退出。（验证：V2、V7；以可控阻塞任务观察 UI 响应与关闭耗时，并检查本次任务资源已释放。）

- [x] C21 / AC21：同步 Hook 按加载及声明顺序运行，失败后仍运行后续规则，明确拒绝后不再运行该次拦截事件剩余规则；异步只保证启动顺序而非完成顺序。（验证：V2、V7；动作写入执行序列并比对。）

- [x] C22 / AC22：子 Agent 动作缺字段时跳过并诊断；合法占位动作只产生规定的未实现日志，不创建 Agent、不调用模型、不拦截。（验证：V2、V3；检查日志及调用计数。）

- [x] C23 / AC23：/hooks 按事件显示名称、动作、once/async 标志和来源；空列表明确提示；运行中改配置后查询不重载，重启才生效。（验证：V8、E01；同时确认帮助/补全可发现命令，查询不触发模型或重置状态。）

- [x] C24 / AC24：事件参数包含引号、换行和命令替换字面量时仍只作为数据传入，不产生额外文件或命令；配置/HTTP 失败诊断不包含测试凭据。（验证：V2、V3；用固定测试标记检查子进程输出、文件和诊断，禁用真实凭据作为断言样本。）

- [ ] C25 / AC25：项目格式检查、全部测试和构建通过，且下文 tmux 正常、拦截、异步及会话场景全部有真实证据。（验证：B01—B04、E01—E08；结果与本次代码版本对应。）

## 集成与边界

- [x] I01：事件嵌套输入在提交后被原调用方修改，Hook 仍看到触发时快照；可选字段缺省，不包含 Provider 凭据。（验证：V2；修改原 Map/List 后比较条件结果和动作收到的 JSON。）

- [ ] I02：reminder 快照后异步到达的新文本不会随旧快照被删除；预检失败不消费，实际发送后即使失败也消费。（验证：V4、V7；捕获连续请求及队列后续可见文本。）

- [ ] I03：Provider 回退和紧急恢复重试获得独立 attempt，旧 reminder 不重复，新到达的注入可进入下一次实际请求；MessageEnd 对应实际发送，预检失败不伪造结束。（验证：V4；控制首个 Provider 失败并逐次比较请求与事件。）

- [ ] I04：compact 注入导致最终预算仍超限时，按上下文错误结束，不无限压缩、不发超预算请求，未发送提示仍保留。（验证：V4；记录压缩和 Provider 调用计数以及错误结果。）

- [ ] I05：普通、单次、批量、LoadSkillTool 特殊路径均应用工具前后事件；安全工具仍可并发，有副作用的串行屏障保留；解析失败及重复调用 ID 不破坏结果配对。（验证：V5；使用可控工具屏障、重复 ID 和不合法参数，核对最终模型消息。）

- [ ] I06：目标会话恢复或新建准备失败时旧会话仍可继续对话，未提前触发 SessionEnd 或清空其 once/reminder；放弃准备后资源关闭。（验证：V6；构造无效目标与取消切换，继续向原会话提交。）

- [ ] I07：用户提交检查期间重复 Enter 不重复启动；Esc/Ctrl+C 可取消；取消或会话切换后的迟到检查结果不会新增历史或改写新输入。（验证：V6；暂停检查动作，发送输入/取消/迟到完成事件，观察调用计数和 UI。）

- [ ] I08：Skill fork 使用独立状态，父子 once/reminder 互不消费；结束后不残留后台任务；标题、记忆与压缩摘要调用不消费对话 reminder。（验证：V4、V5、V7；使用父子唯一文本与 Provider 请求捕获判断。）

- [ ] I09：shell 不读 stdin、持续写 stdout/stderr 时仍能超时；HTTP 慢速正文和超长响应受限；沙箱不可用不退回裸执行；旧 Bash/Skill 脚本行为不回归。（验证：V3、V5；检查动作终态、时间边界和本次子进程清理。）

- [x] I10：正常退出重复调用不会重复执行 SessionEnd/shutdown；关闭状态不再接收后台注入，后台提交失败不遗留 once 占用；Hook 故障没有自动重试和 error 递归。（验证：V6、V7；重复关闭并完成迟到任务，核对事件/执行次数。）

## 编译、测试与格式

- [x] B01：生产和测试源码均能编译。（验证：运行 `./gradlew compileJava compileTestJava`，退出码为零，无编译错误。）

- [x] B02：现有 Spotless 检查覆盖本期新增及修改范围并通过，不引入无关格式变动。（验证：运行 `./gradlew spotlessCheck` 和 `git diff --check`，退出码均为零；检查改动范围仅属于本次任务。）

- [x] B03：全量单元与集成测试通过；需要的本地进程/HTTP/并发场景实际运行，未因环境条件静默跳过。（验证：运行 `./gradlew test`，检查 `build/reports/tests/test/index.html` 与测试结果中的失败、跳过记录；与 T1 基线区分。）

- [x] B04：可执行 JAR 打包成功并用于本次 tmux 验收。（验证：运行 `./gradlew shadowJar`，确认 `build/libs/mewcode.jar` 生成；记录验收启动命令和对应代码版本。）

## tmux 端到端场景

执行前按 task.md 的 T41 建立临时项目、临时用户目录和本地 HTTP 接收器。使用专用会话 `mewcode-ch12`；若已被其他工作占用，先核实，不终止不属于本次验收的会话。所有配置变更均正常重启后生效。

只使用验收项目中的无害文件。设置文件类测试所需的现有权限，避免因用户未批准工具而把普通权限拒绝误判为 Hook 拒绝；不绕过既有硬限制。记录事件可使用用户配置的测试 shell 动作写入项目内 `events.jsonl`，这不是新增应用日志功能。

捕获终端命令：

```bash
tmux capture-pane -p -S -300 -t mewcode-ch12
```

- [ ] E01：启动与规则发现。准备两级不同名规则、一个同名规则和一条非法 Hook，启动后输入 `/hooks`、`/help hooks` 并操作补全；合法规则及来源正确，同名后者/非法条目有诊断，程序仍能对话。运行中修改文件再查询不生效，重启后才变化。（验证：终端截屏文本、启动诊断、两次列表对照；覆盖 AC2、AC3、AC23。）

- [ ] E02：真实读取与提示注入。准备 `sample.txt`，配置 SessionStart prompt “本次回复开头写 HOOK_CONTEXT_OK”，提交“请用 ReadFile 读取 sample.txt 并概括内容”；观察实际调用与回复，随后提交“请再次读取 sample.txt 并只概括内容”。（验证：工具结果、终端回复和事件日志证明完整流程；提示只发一次的确定性证据来自 C16/C17 的请求捕获，不能仅凭回复中的标记判断；覆盖 AC7、AC16、AC17。）

- [ ] E03：工具主动拒绝。配置 PreToolUse 对 `WriteFile` 的 `blocked.txt` 返回 exit 2 和“该文件仅用于验证 Hook 拒绝”，提交“请用 WriteFile 创建 blocked.txt，内容为 hello”；确认模型实际尝试指定工具、收到原因且文件未创建。（验证：终端工具错误、项目文件检查、对应 Pre/Post 事件与错误配对；若模型没有尝试工具则本场景未完成；覆盖 AC10、AC11、AC13。）

- [ ] E04：输入拒绝后继续使用。配置 UserPromptSubmit 拦截 `HOOK_INPUT_DENY`，提交“HOOK_INPUT_DENY 请回复你好”；原因显示且输入可编辑，当前 JSONL 没有该用户消息；删去关键字再次提交可以正常完成。（验证：输入框状态、历史检查、终端与请求计数的集成证据；覆盖 AC12。）

- [ ] E05：普通 Hook 故障继续运行。将验收 shell 动作改为普通非零退出或短超时后重启，提交无害读文件请求；观察失败诊断，工具和模型仍能完成，无重试或 error 递归。（验证：终端、诊断、事件次数及实际工具结果；覆盖 AC8、AC15、AC21。）

- [ ] E06：异步动作与 HTTP 通知。配置成功 WriteFile 后的异步 shell 在延迟后写 `async-done.txt`，Stop 向本地接收器发送 HTTP；请求模型创建 `written.txt`，确认主流程继续、界面可响应、标记随后出现且通知每次自然完成仅一次。（验证：终端响应、两个文件、本地 HTTP 请求正文和次数；所有通知仅发送至测试接收器；覆盖 AC14、AC20。）

- [ ] E07：once 与会话切换。配置 `MessageStart + only_once` 写唯一计数标记，连续两次请求只执行一次；执行 `/clear` 后再次触发，再用 `/session list` 的真实 ID 执行 `/session resume`，恢复后再次触发；同时核对独立 SessionResume 和旧状态隔离。（验证：事件日志中的会话 ID/次数、终端和 C19/I06/I08 的确定性测试证据；覆盖 AC6、AC18、AC19。）

- [ ] E08：正常退出、重启和清理。让一个后台测试动作处于运行中后正常退出，检查 SessionEnd 在 shutdown 前且各一次，退出有界；重新启动验证 startup 和 once 重置，最后正常关闭本次 tmux/接收器，清理临时敏感配置。（验证：安全事件日志、实际退出耗时、进程及测试端口检查；不使用强杀进程替代正常退出验收；覆盖 AC8、AC19、AC20。）

## 证据与结果登记

实际执行时在 verification.md 为每个 C/I/B/E 条目记录：

1. 条目 ID、验证日期和代码版本。
2. 实际命令或输入、使用的测试场景、退出状态。
3. 实际观察结果及报告/终端/事件证据位置。
4. 如失败，记录与预期的差异、修复及重跑结果。

同一测试结果可以支撑多条检查，但要逐项说明对应行为确实被验证。任何必需条目未通过时，不宣布整体完成；完成后报告通过数、未通过项及真实端到端结果。

## 覆盖自检

- Spec AC1—AC25 分别对应 C01—C25，不以端到端抽样替代完整行为覆盖。
- I01—I10 补充快照、预算、回退、并发、会话准备和资源清理等 Plan 集成约束。
- B01—B04 覆盖编译、格式、测试与实际打包产物。
- E01—E08 覆盖真实 TUI、模型、工具、拦截、通知和会话闭环。
- 共 47 个检查项；自动化与安全边界已有对应通过项，真实 Provider 依赖的完整对话、工具、异步通知和会话切换场景仍保持未勾选，详见 [verification.md](./verification.md)。
