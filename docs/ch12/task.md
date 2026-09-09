# Hook 生命周期挂钩系统 Tasks

> 状态：实现已完成；T1—T40 已执行并通过，T41 已建立隔离验收环境。T42—T44 因现有 Provider 认证失败未完成，T45 已整理自动化与 tmux 证据。
>
> 依据：已批准的 [spec.md](./spec.md) 和 [plan.md](./plan.md)。
>
> 当前版本已包含 Hook 生命周期、匹配器、动作执行、Agent/工具/会话接入和 `/hooks` 命令；未完成的真实 Provider 场景详见 [verification.md](./verification.md)。

## 执行约定

- 项目根目录：`/Users/bytedance/IdeaProjects/Mewcode-develop`。下文文件路径相对此目录，Gradle 命令在根目录运行。
- 共 45 个任务，每个实现单元目标为 2—5 分钟；测试、构建和真实模型响应耗时另计，时间不是完成标准。
- 按下列编号串行执行即可满足依赖。依赖表表达技术前提，不授权自动创建子任务 Agent。
- 非平凡行为先增加能失败的测试，再完成实现；沿用已有 JUnit，不新增测试框架或通用测试平台。
- 每项完成后执行所列验证，并记录实际结果再勾选。中间任务只证明该单元完成，不代表整个 Hook 功能已可用。
- 每组任务验证通过后提交本组相关改动；提交前检查暂存范围，不能混入用户已有未跟踪文件，不自动 push。
- 不创建真实用户级 Hook 配置、不覆盖现有 Provider 配置。tmux 验收使用临时项目和临时用户目录；只记录脱敏证据。
- 验收使用现有 `/session resume <id>` 恢复命令，实际 ID 从本次 `/session list` 获取。
- verification.md 在开发阶段创建；此处的 shell 命令和测试用例均为执行要求，不代表已经运行。
- 任务受阻时记录证据并询问，不自行改变已批准语义。

## 文件清单

| 操作 | 文件 | 涉及任务 |
|---|---|---|
| 修改 | `build.gradle.kts` | T1、T40 |
| 读取 | `docs/development-environment.md` | T1 |
| 新建 | `docs/ch12/verification.md` | T1、T40、T41、T42、T43、T44、T45 |
| 新建 | `src/main/java/com/mewcode/permission/RuleMatcher.java` | T2、T3 |
| 新建 | `src/test/java/com/mewcode/permission/RuleMatcherTest.java` | T2、T3 |
| 修改 | `src/main/java/com/mewcode/permission/PermissionRule.java` | T4 |
| 修改 | `src/main/java/com/mewcode/permission/PermissionRuleEngine.java` | T4 |
| 修改 | `src/test/java/com/mewcode/permission/PermissionRuleEngineTest.java` | T4 |
| 修改 | `src/main/java/com/mewcode/config/PermissionConfigLoader.java` | T5 |
| 修改 | `src/test/java/com/mewcode/config/PermissionConfigLoaderTest.java` | T5 |
| 新建 | `src/main/java/com/mewcode/hook/HookEvent.java` | T6 |
| 新建 | `src/main/java/com/mewcode/hook/HookAction.java` | T6 |
| 新建 | `src/main/java/com/mewcode/hook/HookRejection.java` | T6 |
| 新建 | `src/main/java/com/mewcode/hook/HookRule.java` | T7 |
| 新建 | `src/test/java/com/mewcode/hook/HookEngineTest.java` | T7、T8、T9、T10、T18、T19、T20 |
| 新建 | `src/main/java/com/mewcode/hook/HookSessionState.java` | T8、T9 |
| 新建 | `src/main/java/com/mewcode/hook/HookInvocation.java` | T10、T39 |
| 新建 | `src/main/java/com/mewcode/config/HookConfigLoader.java` | T11、T12、T39 |
| 新建 | `src/test/java/com/mewcode/config/HookConfigLoaderTest.java` | T11、T12、T39 |
| 修改 | `src/main/java/com/mewcode/tool/support/CommandRunner.java` | T13、T14 |
| 新建 | `src/test/java/com/mewcode/hook/HookActionExecutorTest.java` | T13、T14、T15、T16、T17、T39 |
| 新建 | `src/main/java/com/mewcode/hook/HookActionExecutor.java` | T15、T16、T17、T39 |
| 新建 | `src/main/java/com/mewcode/hook/HookEngine.java` | T18、T19、T20 |
| 修改 | `src/main/java/com/mewcode/agent/PromptAdditions.java` | T21 |
| 修改 | `src/main/java/com/mewcode/agent/PromptRequestFactory.java` | T21 |
| 修改 | `src/test/java/com/mewcode/agent/PromptRequestFactoryTest.java` | T21 |
| 修改 | `src/main/java/com/mewcode/agent/AgentTurnCoordinator.java` | T22、T23、T24、T25、T26、T30、T36、T37 |
| 新建 | `src/test/java/com/mewcode/agent/AgentTurnCoordinatorHookTest.java` | T22、T23、T24、T25、T26、T30、T36、T37 |
| 修改 | `src/test/java/com/mewcode/agent/AgentTurnCoordinatorSkillTest.java` | T25、T30、T36 |
| 修改 | `src/main/java/com/mewcode/tool/ToolExecutor.java` | T27、T28、T29、T30 |
| 修改 | `src/test/java/com/mewcode/tool/PermissionToolExecutorTest.java` | T27、T28、T29 |
| 修改 | `src/test/java/com/mewcode/tool/ToolExecutorTest.java` | T28、T29 |
| 修改 | `src/main/java/com/mewcode/session/SessionManager.java` | T31 |
| 修改 | `src/test/java/com/mewcode/session/SessionManagerTest.java` | T31、T33 |
| 修改 | `src/main/java/com/mewcode/MewCode.java` | T32、T37 |
| 修改 | `src/main/java/com/mewcode/tui/MewCodeModel.java` | T32、T33、T34、T35、T36、T37、T38 |
| 修改 | `src/test/java/com/mewcode/MewCodeTest.java` | T32、T37 |
| 修改 | `src/test/java/com/mewcode/tui/MewCodeModelTest.java` | T32、T33、T34、T35、T37、T38 |
| 修改 | `src/main/java/com/mewcode/command/CommandRegistry.java` | T38 |
| 修改 | `src/main/java/com/mewcode/command/CommandContext.java` | T38 |
| 修改 | `src/test/java/com/mewcode/command/CommandRegistryTest.java` | T38 |
| 验收时更新 | `docs/ch12/checklist.md` | T45 |
| 执行时更新 | `docs/ch12/task.md` | T45 |

## 有序任务

## T1: 记录开发基线与运行环境

- [ ] 已完成并记录验证证据

**文件：** `build.gradle.kts`、`docs/development-environment.md`、`docs/ch12/verification.md`

**依赖：** 无

**步骤：**
1. 记录当前分支、工作区已有改动及 Java/Gradle 版本；保留用户的未跟踪文件，不提交无关内容。
2. 确认 tmux 可用，按项目环境文档定位 JDK 21；创建仅记录实际验证结果的 verification.md，不记录 Provider 凭据。
3. 运行现有测试建立基线，记录既有失败，不把已有问题算成本期回归。

**验证：**

```bash
git status --short
java -version
./gradlew --version
tmux -V
./gradlew test
```

**期望：** 确认 JDK 21、Gradle 和 tmux 可用；记录测试实际结果及既有失败。

## T2: 实现共用匹配类型

- [ ] 已完成并记录验证证据

**文件：** `src/main/java/com/mewcode/permission/RuleMatcher.java`、`src/test/java/com/mewcode/permission/RuleMatcherTest.java`

**依赖：** T1

**步骤：**
1. 实现 RuleMatcher 的 Exact、Glob、Regex、Not，glob 保持 *、?、跨路径和换行的旧语义。
2. 正则加载时编译，匹配使用 find；not 包装已有匹配，不改变内部语义。
3. 加入空串、整串精确、特殊字符、正则锚点及嵌套取反的行为测试。

**验证：**

```bash
./gradlew test --tests com.mewcode.permission.RuleMatcherTest
```

**期望：** 四种匹配与边界用例通过，glob 与既有实现行为一致。

## T3: 解析结构化匹配定义

- [ ] 已完成并记录验证证据

**文件：** `src/main/java/com/mewcode/permission/RuleMatcher.java`、`src/test/java/com/mewcode/permission/RuleMatcherTest.java`

**依赖：** T2

**步骤：**
1. 实现 parse(Map<?, ?>) 与 glob(String) 入口；检查 type、value、inner 的类型和必填关系。
2. 非法正则、未知类型、缺少 inner 和递归非法结构抛出可转换的解析错误，不回显完整定义。
3. 增加合法结构与非法输入测试，保留旧字面值 =、!、~ 不作为前缀解析。

**验证：**

```bash
./gradlew test --tests com.mewcode.permission.RuleMatcherTest
```

**期望：** 合法定义生成可复用匹配器，非法定义被拒绝，旧前缀字面量仍按 glob。

## T4: 让权限规则使用共用匹配器

- [ ] 已完成并记录验证证据

**文件：** `src/main/java/com/mewcode/permission/PermissionRule.java`、`src/main/java/com/mewcode/permission/PermissionRuleEngine.java`、`src/test/java/com/mewcode/permission/PermissionRuleEngineTest.java`

**依赖：** T3

**步骤：**
1. 为 PermissionRule 保存工具名、RuleMatcher、决定、来源和显示文本，保留旧构造器、of 与 pattern() 调用兼容。
2. 替换 PermissionRuleEngine 的匹配调用，保持 target 路径规范化、授权复用和第一条命中逻辑。
3. 增加旧字符串前缀、同名多规则先命中及结构化 matcher 的回归断言。

**验证：**

```bash
./gradlew test --tests 'com.mewcode.permission.*'
```

**期望：** 现有权限测试通过，结构化规则可命中，规则优先级与旧字面值不变。

## T5: 扩展权限 YAML，保持失败策略

- [ ] 已完成并记录验证证据

**文件：** `src/main/java/com/mewcode/config/PermissionConfigLoader.java`、`src/test/java/com/mewcode/config/PermissionConfigLoaderTest.java`

**依赖：** T4

**步骤：**
1. 接受原 pattern + decision 和新增 tool + match + decision，两种格式互斥。
2. 把匹配解析异常转换为原有 ConfigException，不能跳过非法权限规则后继续启动。
3. 覆盖两种格式、混用、缺字段、非法正则及 =/!/~ 旧字符串。

**验证：**

```bash
./gradlew test --tests com.mewcode.config.PermissionConfigLoaderTest --tests 'com.mewcode.permission.*'
```

**期望：** 新旧配置均可用；非法权限配置仍失败，原有权限测试通过。

## T6: 定义事件、动作与拒绝数据

- [ ] 已完成并记录验证证据

**文件：** `src/main/java/com/mewcode/hook/HookEvent.java`、`src/main/java/com/mewcode/hook/HookAction.java`、`src/main/java/com/mewcode/hook/HookRejection.java`

**依赖：** T1

**步骤：**
1. 定义 Plan 中 14 个 HookEvent，配置名严格区分大小写，仅 PreToolUse 和 UserPromptSubmit 可拦截。
2. 定义 Shell、Prompt、Http、Subagent 数据类型，校验必要字符串并复制 headers。
3. 定义只接受非空 Hook 名和原因的 HookRejection，不用异常代表正常主动拒绝。

**验证：**

```bash
./gradlew compileJava
```

**期望：** 事件、四种动作和拒绝类型编译通过，未引入新依赖。

## T7: 实现规则与条件求值

- [ ] 已完成并记录验证证据

**文件：** `src/main/java/com/mewcode/hook/HookRule.java`、`src/test/java/com/mewcode/hook/HookEngineTest.java`

**依赖：** T3、T6

**步骤：**
1. 定义 HookRule 及嵌套 HookCondition、Combination、FieldMatch，复制列表并保留来源。
2. 实现点路径标量访问和 all_of/any_of；字段缺失、null、对象、数组在取反前即返回不匹配。
3. 用嵌套工具参数验证布尔/数字文本、精确与反向条件、两个组合的差异。

**验证：**

```bash
./gradlew test --tests com.mewcode.hook.HookEngineTest
```

**期望：** 条件行为用例通过，缺失字段不会被 not 命中。

## T8: 实现一次性执行状态

- [ ] 已完成并记录验证证据

**文件：** `src/main/java/com/mewcode/hook/HookSessionState.java`、`src/test/java/com/mewcode/hook/HookEngineTest.java`

**依赖：** T7

**步骤：**
1. 实现短锁内的 tryStartOnce、markExecuted、关闭标记和未启动占用撤销。
2. 明确执行中也禁止再次启动；动作结束后才完成标记，失败不释放已执行次数。
3. 用并发屏障验证同名竞争只一个成功，新状态可以再次执行，关闭后不能占用。

**验证：**

```bash
./gradlew test --tests com.mewcode.hook.HookEngineTest
```

**期望：** 竞争触发最多启动一次，状态关闭和新建行为正确。

## T9: 实现 reminder 快照与消费

- [ ] 已完成并记录验证证据

**文件：** `src/main/java/com/mewcode/hook/HookSessionState.java`、`src/test/java/com/mewcode/hook/HookEngineTest.java`

**依赖：** T8

**步骤：**
1. 增加序号队列、enqueuePrompt、snapshotPrompts、consumePrompts 和嵌套 ReminderBatch。
2. 只消费快照序号及之前条目；取快照后到达的注入保留；close 清空并丢弃迟到写入。
3. 验证快照不消费、重复消费、新旧状态隔离及并发追加顺序。

**验证：**

```bash
./gradlew test --tests com.mewcode.hook.HookEngineTest
```

**期望：** 已发送批次被消费，新注入不丢失，关闭状态不接收提示词。

## T10: 构造不可变事件上下文

- [ ] 已完成并记录验证证据

**文件：** `src/main/java/com/mewcode/hook/HookInvocation.java`、`src/test/java/com/mewcode/hook/HookEngineTest.java`

**依赖：** T6、T9

**步骤：**
1. 实现 HookInvocation，绑定事件、payload、明确状态引用和 CancellationToken。
2. 递归复制 Map/List，校验通用 event/cwd；省略不存在的可选字段，不读取宿主凭据。
3. 修改构造前原始嵌套参数，验证快照不变且条件与 JSON 序列化读取相同数据。

**验证：**

```bash
./gradlew test --tests com.mewcode.hook.HookEngineTest
```

**期望：** 嵌套快照不可变、缺失字段保持缺失，取消和状态引用正确。

## T11: 解析单条 Hook 配置

- [ ] 已完成并记录验证证据

**文件：** `src/main/java/com/mewcode/config/HookConfigLoader.java`、`src/test/java/com/mewcode/config/HookConfigLoaderTest.java`

**依赖：** T7、T10

**步骤：**
1. 实现安全 YAML 加载和单条规则解析，覆盖必填 name/event/action、条件和四类动作参数。
2. 实现 only_once/async 默认 false、timeout 默认 30s 和正整数 ms/s/m 解析，拒绝溢出及非法值。
3. 两个拦截事件的任意动作配置 async 都拒绝；两种条件组混用、嵌套、空数组均拒绝。

**验证：**

```bash
./gradlew test --tests com.mewcode.config.HookConfigLoaderTest
```

**期望：** 合法定义及默认值正确，非法字段和拦截异步组合均产生规则诊断。

## T12: 实现两级合并与错误隔离

- [ ] 已完成并记录验证证据

**文件：** `src/main/java/com/mewcode/config/HookConfigLoader.java`、`src/test/java/com/mewcode/config/HookConfigLoaderTest.java`

**依赖：** T11

**步骤：**
1. 实现 load(projectRoot,userHome,diagnostics) 和 LoadedHooks，项目先用户后，保留合法规则顺序。
2. 先加载合法名称占位，冲突跳过后者；不存在文件忽略，非法文件或规则隔离并记录安全来源。
3. 验证重复 YAML 键、非法顶层、无效项目规则不抢占合法用户规则，以及来源列表的确定性。

**验证：**

```bash
./gradlew test --tests com.mewcode.config.HookConfigLoaderTest --tests com.mewcode.config.PermissionConfigLoaderTest
```

**期望：** Hook 错误不阻断其他规则，权限错误仍失败，来源和同名行为稳定。

## T13: 为 shell 提供 stdin 与独立输出

- [ ] 已完成并记录验证证据

**文件：** `src/main/java/com/mewcode/tool/support/CommandRunner.java`、`src/test/java/com/mewcode/hook/HookActionExecutorTest.java`

**依赖：** T10

**步骤：**
1. 增加 Plan 定义的 runHook 入口并复用 ScriptResult 和已有 OS 沙箱。
2. 事件 JSON 加换行写入 stdin，stdout/stderr 分别并行读取，工作目录和可写范围固定为项目。
3. 用实际短进程验证 stdin 原样传递、cwd、退出码、独立输出，不经过 Bash 模型工具入口。

**验证：**

```bash
./gradlew test --tests com.mewcode.hook.HookActionExecutorTest --tests com.mewcode.tool.BashSandboxIntegrationTest
```

**期望：** 事件参数原样作为数据传入，原有 Bash 沙箱集成不回归。

## T14: 补齐 shell 全程超时与取消

- [ ] 已完成并记录验证证据

**文件：** `src/main/java/com/mewcode/tool/support/CommandRunner.java`、`src/test/java/com/mewcode/hook/HookActionExecutorTest.java`

**依赖：** T13

**步骤：**
1. 将 stdin 写入与两路读取同时启动，统一截止时间覆盖管道和进程等待。
2. 取消或超时清理进程及管道，有界等待线程；保留输出上限和截断标志。
3. 验证不读 stdin、连续输出、长时间运行及取消的场景，确保不会在写输入阶段绕过超时。

**验证：**

```bash
./gradlew test --tests com.mewcode.hook.HookActionExecutorTest --tests com.mewcode.tool.BashSandboxIntegrationTest
```

**期望：** 阻塞 stdin 和持续输出也会按时结束，取消后没有本次测试的残留进程。

## T15: 执行 shell、prompt 与子 Agent 占位

- [ ] 已完成并记录验证证据

**文件：** `src/main/java/com/mewcode/hook/HookActionExecutor.java`、`src/test/java/com/mewcode/hook/HookActionExecutorTest.java`

**依赖：** T6、T10、T14

**步骤：**
1. 实现 execute 对 shell/prompt/subagent 的完整分支，并建立 HTTP 分支所需同一执行器结构。
2. shell 仅在拦截事件且 exit=2、有有效原因时返回 HookRejection；stderr 优先，空原因及截断按失败。
3. prompt 只向所属状态排队；subagent 只输出规定占位日志；覆盖非拦截事件不能改变主流程决定。

**验证：**

```bash
./gradlew test --tests com.mewcode.hook.HookActionExecutorTest
```

**期望：** 0/2/其他退出码、空原因、prompt 入队和占位动作行为符合协议。

## T16: 构造 HTTP 请求与模板

- [ ] 已完成并记录验证证据

**文件：** `src/main/java/com/mewcode/hook/HookActionExecutor.java`、`src/test/java/com/mewcode/hook/HookActionExecutorTest.java`

**依赖：** T15

**步骤：**
1. 使用 JDK HttpClient，实现默认 POST、默认事件 JSON、headers 和显式 body。
2. 只替换 ${field}/${nested.path}，不再次解释替换值；缺失和非标量模板字段失败且不发送。
3. 用本地 HttpServer 捕获方法、头和正文，验证 JSON 特殊字符及用户 Content-Type 优先。

**验证：**

```bash
./gradlew test --tests com.mewcode.hook.HookActionExecutorTest
```

**期望：** 接收端获取预期请求；无效模板不产生请求，不需要外部服务。

## T17: 处理 HTTP 拒绝、超时与有界响应

- [ ] 已完成并记录验证证据

**文件：** `src/main/java/com/mewcode/hook/HookActionExecutor.java`、`src/test/java/com/mewcode/hook/HookActionExecutorTest.java`

**依赖：** T16

**步骤：**
1. 仅 2xx、合法对象、decision=block、非空 reason 返回拒绝；合法非拒绝响应放行。
2. 统一请求和读取超时、取消 Future/流，限制响应体 20,000 字符，不重试或跟随重定向。
3. 验证非 2xx、坏 JSON、空原因、超长正文、慢速响应和取消；诊断不含测试凭据。

**验证：**

```bash
./gradlew test --tests com.mewcode.hook.HookActionExecutorTest
```

**期望：** 只有有效 block 拦截，故障不伪装拒绝，慢响应与超长响应受控。

## T18: 实现同步规则分派

- [ ] 已完成并记录验证证据

**文件：** `src/main/java/com/mewcode/hook/HookEngine.java`、`src/test/java/com/mewcode/hook/HookEngineTest.java`

**依赖：** T12、T17

**步骤：**
1. 按事件及声明顺序选择规则、计算条件并同步调用动作执行器。
2. 接入 once 占用和 finally 完成，失败安全记录后继续，明确拒绝后停止本次事件剩余规则。
3. 无规则直接返回空拒绝，不引入替代引擎；验证不匹配不消耗 once，普通失败不触发 error。

**验证：**

```bash
./gradlew test --tests com.mewcode.hook.HookEngineTest
```

**期望：** 规则顺序、失败继续、拒绝短路与 once 语义通过。

## T19: 实现异步调度和会话取消

- [ ] 已完成并记录验证证据

**文件：** `src/main/java/com/mewcode/hook/HookEngine.java`、`src/test/java/com/mewcode/hook/HookEngineTest.java`

**依赖：** T18

**步骤：**
1. 在一个虚拟线程执行器运行异步动作，登记状态、token、Future，完成后移除记录。
2. 提交前原子占用 once，提交失败撤销未启动占用；绑定不可变调用快照，不等待异步完成。
3. 实现 cancelSession；用可控阻塞动作验证分派立即返回、竞争只启动一次、会话取消终止任务。

**验证：**

```bash
./gradlew test --tests com.mewcode.hook.HookEngineTest
```

**期望：** 异步不阻塞分派，提交失败不遗留占用，取消只影响所属会话任务。

## T20: 实现引擎有界关闭

- [ ] 已完成并记录验证证据

**文件：** `src/main/java/com/mewcode/hook/HookEngine.java`、`src/test/java/com/mewcode/hook/HookEngineTest.java`

**依赖：** T19

**步骤：**
1. 实现幂等 close，停止接收新后台任务，取消现存任务并最多等待 5 秒。
2. 关闭共享 HTTP 资源时避免无界等待，所有完成回调只能清理旧任务状态。
3. 测试重复关闭、执行中关闭、关闭后分派以及旧任务迟到，不依赖长时间 sleep。

**验证：**

```bash
./gradlew test --tests com.mewcode.hook.HookEngineTest
```

**期望：** 引擎在有界时间内关闭，没有新任务启动或迟到写入新状态。

## T21: 接入 reminder 请求快照

- [ ] 已完成并记录验证证据

**文件：** `src/main/java/com/mewcode/agent/PromptAdditions.java`、`src/main/java/com/mewcode/agent/PromptRequestFactory.java`、`src/test/java/com/mewcode/agent/PromptRequestFactoryTest.java`

**依赖：** T9

**步骤：**
1. 扩展 PromptAdditions 增加 Hook reminder，保留既有构造器；更新复制 additions 的调用避免丢字段。
2. 在既有 Plan/恢复 reminder 后追加 Hook 文本，createContextRequest 与 create 使用同一快照。
3. 验证合并顺序、空注入、历史不变、预检和请求 reminder 完全一致。

**验证：**

```bash
./gradlew test --tests com.mewcode.agent.PromptRequestFactoryTest --tests com.mewcode.agent.AgentTurnCoordinatorPromptTest
```

**期望：** Hook reminder 只进入动态区域，旧请求组装行为保持兼容。

## T22: 注入 Agent 状态与轮次事件

- [ ] 已完成并记录验证证据

**文件：** `src/main/java/com/mewcode/agent/AgentTurnCoordinator.java`、`src/test/java/com/mewcode/agent/AgentTurnCoordinatorHookTest.java`

**依赖：** T18、T21

**步骤：**
1. 为真实 Agent 请求传入 HookEngine、明确 HookSessionState 和请求标识，旧调用入口使用空规则兼容。
2. 请求接受后触发一次 TurnStart；仅最终无工具回复的自然完成路径触发 Stop，随后原有完成通知。
3. 测试多次模型调用仅一次 TurnStart/Stop，取消、迭代上限和错误不触发 Stop。

**验证：**

```bash
./gradlew test --tests com.mewcode.agent.AgentTurnCoordinatorHookTest --tests com.mewcode.agent.AgentTurnCoordinatorTest
```

**期望：** 轮次语义与用户请求对应，既有模型轮数/UI 事件没有改变。

## T23: 配对每次模型请求的事件

- [ ] 已完成并记录验证证据

**文件：** `src/main/java/com/mewcode/agent/AgentTurnCoordinator.java`、`src/test/java/com/mewcode/agent/AgentTurnCoordinatorHookTest.java`

**依赖：** T22

**步骤：**
1. 在每次请求尝试前分派 MessageStart，建立稳定 request_id、iteration、attempt。
2. 从 openStream 到 collect 使用统一收口发布 MessageEnd，填入可见响应、工具调用或错误/取消状态。
3. 覆盖同步 openStream 异常、流失败和取消；预检未发出时不伪造结束，不按文本片段触发。

**验证：**

```bash
./gradlew test --tests com.mewcode.agent.AgentTurnCoordinatorHookTest
```

**期望：** 每次实际模型调用有准确终态，流式片段不会增加事件数量。

## T24: 在实际发送时消费 Hook reminder

- [ ] 已完成并记录验证证据

**文件：** `src/main/java/com/mewcode/agent/AgentTurnCoordinator.java`、`src/test/java/com/mewcode/agent/AgentTurnCoordinatorHookTest.java`

**依赖：** T23、T21

**步骤：**
1. MessageStart 后从请求绑定状态取 reminder 快照，预检和最终请求共用该批次。
2. 只在进入 openStream 发送尝试时消费批次；预检失败保留，快照后新到的文本留到下一次。
3. 捕获连续请求和持久历史，断言一次注入只在下一次请求出现，失败的已发送请求也不回填。

**验证：**

```bash
./gradlew test --tests com.mewcode.agent.AgentTurnCoordinatorHookTest --tests com.mewcode.agent.PromptRequestFactoryTest
```

**期望：** 一次性注入、发送前失败保留及新文本保留均符合批准语义。

## T25: 修正 Provider 回退时的消费边界

- [ ] 已完成并记录验证证据

**文件：** `src/main/java/com/mewcode/agent/AgentTurnCoordinator.java`、`src/test/java/com/mewcode/agent/AgentTurnCoordinatorHookTest.java`、`src/test/java/com/mewcode/agent/AgentTurnCoordinatorSkillTest.java`

**依赖：** T24

**步骤：**
1. 回退到主 Provider 时重新获取 Hook additions，不复用含已消费文本的旧快照。
2. 每个真实尝试重新分派消息事件并递增 attempt，保留已有 Skill Provider 回退策略。
3. 测试首个 Provider 失败后主 Provider 看不到已消费文本，但能看到失败后新产生的注入。

**验证：**

```bash
./gradlew test --tests com.mewcode.agent.AgentTurnCoordinatorHookTest --tests com.mewcode.agent.AgentTurnCoordinatorSkillTest
```

**期望：** 回退不重复注入，Skill 模型选择和回退的原有行为通过。

## T26: 接入三条压缩路径与最终预算

- [ ] 已完成并记录验证证据

**文件：** `src/main/java/com/mewcode/agent/AgentTurnCoordinator.java`、`src/test/java/com/mewcode/agent/AgentTurnCoordinatorHookTest.java`

**依赖：** T25

**步骤：**
1. 自动、手动、紧急压缩仅在 changed=true 后发布 compact，包含触发来源及可用 token 统计。
2. 压缩后重新取 reminder 快照并检查最终预算，包含同步 compact 注入；超预算不循环压缩。
3. 测试三条路径、无变化不发事件、压缩后新增提示计入预算，以及未发送失败不消费。

**验证：**

```bash
./gradlew test --tests com.mewcode.agent.AgentTurnCoordinatorHookTest --tests com.mewcode.compact.ContextManagerTest
```

**期望：** 压缩事件准确一次，实际发送预算包含全部本次 reminder，原压缩行为通过。

## T27: 实现权限前的工具准备步骤

- [ ] 已完成并记录验证证据

**文件：** `src/main/java/com/mewcode/tool/ToolExecutor.java`、`src/test/java/com/mewcode/tool/PermissionToolExecutorTest.java`

**依赖：** T18、T10

**步骤：**
1. 在工具入口增加一次性 Hook 准备结果，按调用位置保存放行或拒绝，拒绝生成关联的 ToolInvocationResult。
2. 确保 PreToolUse 在任何 permissionGate.check/isPermissionSafe 前执行，不能只包住 executeSingle 内层。
3. 用现有可替换沙箱/工具测试接缝观测权限入口与执行计数，验证 Hook 拒绝时权限和工具均未运行。

**验证：**

```bash
./gradlew test --tests com.mewcode.tool.PermissionToolExecutorTest
```

**期望：** 主动拒绝先于权限预判，放行仍接受原有权限拒绝。

## T28: 把准备步骤接入批量调度

- [ ] 已完成并记录验证证据

**文件：** `src/main/java/com/mewcode/tool/ToolExecutor.java`、`src/test/java/com/mewcode/tool/ToolExecutorTest.java`、`src/test/java/com/mewcode/tool/PermissionToolExecutorTest.java`

**依赖：** T27

**步骤：**
1. 对本批已解析的适用调用先做 Hook 准备，之后才做权限安全分类和并发分组。
2. 内部单次执行复用已准备状态，不能重复分派 PreToolUse；保留安全并发和有副作用的串行屏障。
3. 验证不同调用按原顺序准备、重复 ID 不误关联、多个安全调用仍可并发。

**验证：**

```bash
./gradlew test --tests com.mewcode.tool.ToolExecutorTest --tests com.mewcode.tool.PermissionToolExecutorTest
```

**期望：** 批量路径不提前检查权限，调用准备不重复，原调度顺序和并发能力保持。

## T29: 统一工具终态事件

- [ ] 已完成并记录验证证据

**文件：** `src/main/java/com/mewcode/tool/ToolExecutor.java`、`src/test/java/com/mewcode/tool/ToolExecutorTest.java`、`src/test/java/com/mewcode/tool/PermissionToolExecutorTest.java`

**依赖：** T28

**步骤：**
1. 提供统一结果终态步骤，从最终结果填充 status/is_error/duration_ms，不靠中文错误内容猜测拒绝类型。
2. 让成功、错误、权限拒绝、Hook 拒绝、取消和超时路径各形成一次 PostToolUse；内部工作线程不直接重复发布。
3. 用延迟返回工具验证超时结果收口后没有第二个终态，Post 动作失败不改工具结果。

**验证：**

```bash
./gradlew test --tests com.mewcode.tool.ToolExecutorTest --tests com.mewcode.tool.PermissionToolExecutorTest
```

**期望：** 终态只一次，拒绝状态准确，迟到结果不破坏返回值或重复事件。

## T30: 让 Agent 与 Skill 共用工具 Hook 路径

- [ ] 已完成并记录验证证据

**文件：** `src/main/java/com/mewcode/agent/AgentTurnCoordinator.java`、`src/main/java/com/mewcode/tool/ToolExecutor.java`、`src/test/java/com/mewcode/agent/AgentTurnCoordinatorHookTest.java`、`src/test/java/com/mewcode/agent/AgentTurnCoordinatorSkillTest.java`

**依赖：** T29、T22

**步骤：**
1. 在普通批次和 executeSkillLoads 前复用统一准备步骤，保留混合 Skill 批次既有错误行为。
2. 用 ToolResultAssembler 最终组装结果触发统一 Post，再成对写历史并发布原 UI 事件，避免单次和批量重复通知。
3. 验证 LoadSkillTool、未知工具、解析错误及拒绝回流的调用/结果配对，Hook 不能绕过 Skill 或 Plan 限制。

**验证：**

```bash
./gradlew test --tests com.mewcode.agent.AgentTurnCoordinatorHookTest --tests com.mewcode.agent.AgentTurnCoordinatorSkillTest --tests com.mewcode.agent.AgentProtocolIntegrationTest
```

**期望：** 特殊加载路径也被拦截，模型和 UI 收到对应结果，消息协议保持合法。

## T31: 让会话切换可先准备再提交

- [ ] 已完成并记录验证证据

**文件：** `src/main/java/com/mewcode/session/SessionManager.java`、`src/test/java/com/mewcode/session/SessionManagerTest.java`

**依赖：** T1

**步骤：**
1. 把目标新建/恢复验证与当前会话替换分成 prepare/commit，准备结果嵌在 SessionManager 并只能提交一次。
2. 保留原 startNewSession/resume 接口；取消或准备失败关闭目标资源并保留旧会话、历史和 writer。
3. 测试无效恢复、准备后放弃、重复提交和成功切换，不在存储同步锁内调用外部 Hook。

**验证：**

```bash
./gradlew test --tests com.mewcode.session.SessionManagerTest
```

**期望：** 准备失败不损伤旧会话，成功提交后数据和恢复提醒与原行为一致。

## T32: 启动时加载并绑定 Hook

- [ ] 已完成并记录验证证据

**文件：** `src/main/java/com/mewcode/MewCode.java`、`src/main/java/com/mewcode/tui/MewCodeModel.java`、`src/test/java/com/mewcode/MewCodeTest.java`、`src/test/java/com/mewcode/tui/MewCodeModelTest.java`

**依赖：** T20、T22、T31

**步骤：**
1. 在应用组装处加载 Hook、创建引擎和启动状态，传入模型与 Agent 的真实路径。
2. 初始化完成后触发 startup，首会话环境就绪且接受输入前触发 SessionStart；首会话沿用启动状态。
3. 覆盖空配置、非法 Hook 不阻断、启动期注入能到首请求，以及 Provider 切换不重置状态。

**验证：**

```bash
./gradlew test --tests com.mewcode.MewCodeTest --tests com.mewcode.tui.MewCodeModelTest
```

**期望：** 启动事件按序且只一次，正常初始化和无 Hook 模式兼容。

## T33: 接入 clear 和恢复会话生命周期

- [ ] 已完成并记录验证证据

**文件：** `src/main/java/com/mewcode/tui/MewCodeModel.java`、`src/test/java/com/mewcode/tui/MewCodeModelTest.java`、`src/test/java/com/mewcode/session/SessionManagerTest.java`

**依赖：** T32

**步骤：**
1. 目标准备成功后在存储锁外触发 SessionEnd，再关闭旧状态、取消旧后台任务并提交会话切换。
2. 为新会话创建状态并触发 SessionStart；/session resume <id> 只触发 SessionResume，不额外新增命令别名。
3. 验证 once 重置、未消费提示丢弃、旧任务迟到不写新状态、无效恢复不触发虚假的结束事件。

**验证：**

```bash
./gradlew test --tests com.mewcode.tui.MewCodeModelTest --tests com.mewcode.session.SessionManagerTest
```

**期望：** 新建/恢复事件准确，旧异步状态隔离，恢复失败保留原会话。

## T34: 在普通输入写入前执行检查

- [ ] 已完成并记录验证证据

**文件：** `src/main/java/com/mewcode/tui/MewCodeModel.java`、`src/test/java/com/mewcode/tui/MewCodeModelTest.java`

**依赖：** T32

**步骤：**
1. 普通提交先进入后台 UserPromptSubmit 检查，使用原始文本、取消 token 和唯一检查标识。
2. 放行后才显示正式用户消息、写历史并启动 Agent；拒绝则显示原因并保留可编辑输入。
3. Slash 原始输入绕过该检查，启动 Agent 的 Skill 命令仍走 TurnStart；验证 UI 不等待 shell。

**验证：**

```bash
./gradlew test --tests com.mewcode.tui.MewCodeModelTest
```

**期望：** 拒绝没有历史/模型副作用，放行仅提交一次，Slash 分派兼容。

## T35: 处理提交等待中的取消和迟到结果

- [ ] 已完成并记录验证证据

**文件：** `src/main/java/com/mewcode/tui/MewCodeModel.java`、`src/test/java/com/mewcode/tui/MewCodeModelTest.java`

**依赖：** T34

**步骤：**
1. 等待阶段 Esc/Ctrl+C 取消检查，重复 Enter 不启动新请求。
2. 检查完成消息携带标识，只处理当前仍有效且属于原会话的结果。
3. 验证取消后迟到放行、切换后迟到拒绝都不改变新输入和历史。

**验证：**

```bash
./gradlew test --tests com.mewcode.tui.MewCodeModelTest
```

**期望：** 取消及时，过期检查结果被忽略，不出现双重提交。

## T36: 隔离 Skill fork 与辅助模型调用

- [ ] 已完成并记录验证证据

**文件：** `src/main/java/com/mewcode/tui/MewCodeModel.java`、`src/main/java/com/mewcode/agent/AgentTurnCoordinator.java`、`src/test/java/com/mewcode/agent/AgentTurnCoordinatorSkillTest.java`、`src/test/java/com/mewcode/agent/AgentTurnCoordinatorHookTest.java`

**依赖：** T30、T33

**步骤：**
1. 临时 Skill fork 创建独立 HookSessionState，运行完成关闭并取消所属任务，不产生正式 Session 事件。
2. 父请求继续使用父状态，不复制或消费父队列/一次性标记。
3. 验证标题、记忆和压缩摘要等辅助 LLM 调用不触发对话模型事件，也不消费待发 reminder。

**验证：**

```bash
./gradlew test --tests com.mewcode.agent.AgentTurnCoordinatorSkillTest --tests com.mewcode.agent.AgentTurnCoordinatorHookTest
```

**期望：** fork 不污染主会话，辅助调用不会提前消耗用户对话提示。

## T37: 接入 error 与正常退出

- [ ] 已完成并记录验证证据

**文件：** `src/main/java/com/mewcode/MewCode.java`、`src/main/java/com/mewcode/tui/MewCodeModel.java`、`src/main/java/com/mewcode/agent/AgentTurnCoordinator.java`、`src/test/java/com/mewcode/MewCodeTest.java`、`src/test/java/com/mewcode/tui/MewCodeModelTest.java`、`src/test/java/com/mewcode/agent/AgentTurnCoordinatorHookTest.java`

**依赖：** T20、T33、T35、T36

**步骤：**
1. 在统一主流程错误入口分派 error，Hook 自身故障只输出诊断，用户取消不发 error。
2. 正常关闭先 SessionEnd、关闭会话状态，再用终止状态执行 shutdown，最后清理 Hook 和原有资源。
3. 使关闭幂等；验证重复 close 不重复发事件，shutdown 同步动作有超时，后台关闭最多等待 5 秒。

**验证：**

```bash
./gradlew test --tests com.mewcode.MewCodeTest --tests com.mewcode.tui.MewCodeModelTest --tests com.mewcode.agent.AgentTurnCoordinatorHookTest
```

**期望：** 错误不会递归，退出事件顺序准确且清理有界。

## T38: 注册只读 hooks 命令

- [ ] 已完成并记录验证证据

**文件：** `src/main/java/com/mewcode/command/CommandRegistry.java`、`src/main/java/com/mewcode/command/CommandContext.java`、`src/main/java/com/mewcode/tui/MewCodeModel.java`、`src/test/java/com/mewcode/command/CommandRegistryTest.java`、`src/test/java/com/mewcode/tui/MewCodeModelTest.java`

**依赖：** T12、T32

**步骤：**
1. 静态注册零参数 /hooks，增加命令上下文只读 Supplier 并更新既有构造调用。
2. 按事件列出名称、动作、[once]/[async] 和来源；无规则明确提示，非空参数显示用法。
3. 验证帮助、补全及 Skill 保留名，命令不调用模型、不执行 Hook、不重载或重置。

**验证：**

```bash
./gradlew test --tests com.mewcode.command.CommandRegistryTest --tests com.mewcode.tui.MewCodeModelTest --tests com.mewcode.skill.SkillCatalogTest
```

**期望：** 规则查看可用，命令查询无副作用，Skill 同名冲突被处理。

## T39: 检查敏感数据与不可信输入边界

- [ ] 已完成并记录验证证据

**文件：** `src/main/java/com/mewcode/config/HookConfigLoader.java`、`src/main/java/com/mewcode/hook/HookActionExecutor.java`、`src/main/java/com/mewcode/hook/HookInvocation.java`、`src/test/java/com/mewcode/config/HookConfigLoaderTest.java`、`src/test/java/com/mewcode/hook/HookActionExecutorTest.java`

**依赖：** T17、T30、T37、T38

**步骤：**
1. 使用带引号、换行、美元符号和命令替换字面量的事件数据，断言不会产生额外 shell 指令。
2. 用假凭据触发非法 YAML、HTTP 错误和超时，确认诊断不打印请求头、敏感查询或正文。
3. 检查 YAML 重复键/类型构造、输出上限和不递归模板替换，修复违反既定边界的实际失败。

**验证：**

```bash
./gradlew test --tests com.mewcode.config.HookConfigLoaderTest --tests com.mewcode.hook.HookActionExecutorTest
```

**期望：** 事件仅作为数据，错误诊断不含假凭据，输入边界检查通过。

## T40: 纳入格式检查并完成构建回归

- [ ] 已完成并记录验证证据

**文件：** `build.gradle.kts`、`docs/ch12/verification.md`

**依赖：** T5、T26、T30、T36、T37、T38、T39

**步骤：**
1. 将新增 Hook、配置和测试文件，以及本期修改但未覆盖的文件加入现有 Spotless target；不扩大到无关目录。
2. 执行格式化、格式检查、全量测试和打包；失败则定位到所属任务修复，不仅记录失败。
3. 记录测试报告和 JAR 路径，对照 T1 区分既有问题和回归；检查 diff 不含用户无关文件。

**验证：**

```bash
./gradlew spotlessApply
./gradlew spotlessCheck
./gradlew test
./gradlew shadowJar
git diff --check
```

**期望：** 格式、测试和打包通过，生成 build/libs/mewcode.jar，无新增无关改动。

## T41: 准备 tmux 隔离验收环境

- [ ] 已完成并记录验证证据

**文件：** `docs/ch12/verification.md`

**依赖：** T40

**步骤：**
1. 在临时目录建立独立验收项目、项目级/临时用户级 hooks 配置和本地 HTTP 接收器，不覆盖真实项目或用户 Hook 配置。
2. 沿用本机已有 Provider 配置并限制临时文件权限，日志只记录非敏感标识；不创建或猜测凭据。
3. 启动专用 tmux 会话运行已构建 JAR，保存实际会话名、端口、文件路径和启动输出；确认 /hooks 能列出两级合法规则。
4. 固定使用专用 tmux 名 mewcode-ch12；若已存在则先查看归属，不终止不属于本次验收的会话。临时目录实际路径写入 verification.md，后续场景沿用。

**验证：**

```bash
tmux has-session -t mewcode-ch12
# 上条仅用于确认名称是否已占用；未占用时在验收 shell 中继续：
MEWCODE_HOOK_E2E_DIR=$(mktemp -d /private/tmp/mewcode-ch12.XXXXXX)
mkdir -p "$MEWCODE_HOOK_E2E_DIR/project/.mewcode" "$MEWCODE_HOOK_E2E_DIR/user"
# 按本任务步骤准备临时配置与本地接收器后，启动：
tmux new-session -d -s mewcode-ch12 -c "$MEWCODE_HOOK_E2E_DIR/project" java "-Duser.home=$MEWCODE_HOOK_E2E_DIR/user" -jar /Users/bytedance/IdeaProjects/Mewcode-develop/build/libs/mewcode.jar
tmux capture-pane -p -S -100 -t mewcode-ch12
```

**期望：** MewCode 在 tmux 可交互启动，/hooks 显示来源，HTTP 接收器仅用于本地验收；实际位置记入 verification.md。

## T42: 验收正常对话与一次性 reminder

- [ ] 已完成并记录验证证据

**文件：** `docs/ch12/verification.md`

**依赖：** T41

**步骤：**
1. 配置 SessionStart prompt 提醒“本次回复开头写 HOOK_CONTEXT_OK”，另配 shell 事件日志；在验收项目准备内容明确的 sample.txt。
2. 在 tmux 输入“请用 ReadFile 读取 sample.txt 并概括内容”，观察工具调用和最终回复；随后输入“请再次读取 sample.txt 并只概括内容”，确认继续工作。
3. 保存终端和事件证据；以 T24/T25 的请求捕获测试为提示词只发送一次的确定性证据，不能仅凭模型是否重复短语判断。

**验证：**

```bash
# 使用 T41 记录的 tmux 会话执行两次真实请求后：
tmux capture-pane -p -S -300 -t mewcode-ch12
./gradlew test --tests com.mewcode.agent.AgentTurnCoordinatorHookTest
```

**期望：** 真实对话能调用工具和完成；消息/轮次事件顺序正确，一次性 reminder 有请求捕获证据。

## T43: 验收工具拒绝与用户输入拒绝

- [ ] 已完成并记录验证证据

**文件：** `docs/ch12/verification.md`

**依赖：** T42

**步骤：**
1. 配置 PreToolUse 对 blocked.txt 的 WriteFile 返回 exit 2 和原因“该文件仅用于验证 Hook 拒绝”；在 tmux 输入“请用 WriteFile 创建 blocked.txt，内容为 hello”，确认未创建、模型收到理由并调整。
2. 配置 UserPromptSubmit 拦截关键字 HOOK_INPUT_DENY，输入“HOOK_INPUT_DENY 请回复你好”，确认原因可见、输入可继续编辑；检查当前 JSONL 没有被拒文本。
3. 将动作改为普通失败并重启使配置生效，验证记录失败但对话仍继续；使用新的独立测试文件避免已有状态干扰。

**验证：**

```bash
# 完成上述请求后捕获 T41 记录的 tmux 会话：
tmux capture-pane -p -S -300 -t mewcode-ch12
# 检查临时验收项目的目标文件及当前 conversation.jsonl，不输出 Provider 配置。
```

**期望：** Hook 主动拒绝阻止实际副作用，普通故障不阻断；用户拒绝输入没有写历史或调用模型。

## T44: 验收异步通知与会话切换

- [ ] 已完成并记录验证证据

**文件：** `docs/ch12/verification.md`

**依赖：** T43

**步骤：**
1. 配置文件写后异步动作及 Stop HTTP 通知，在 tmux 发起真实工具请求，确认界面继续响应、本地接收端收到一次请求。
2. 用可重复事件的 only_once 动作记录次数；连续请求不重复，/clear 和 /session resume <id> 后再次触发，旧异步注入不进入新会话。
3. 执行正常退出再重启，确认 startup/SessionEnd/shutdown 顺序和标记重置；结束本次 tmux 和 HTTP 服务并清理临时敏感配置，保留安全证据。

**验证：**

```bash
# 使用本次实际会话标识捕获终端：
tmux capture-pane -p -S -300 -t mewcode-ch12
# 对照临时事件日志、HTTP 接收日志和真实会话 ID 检查次数与顺序。
```

**期望：** 异步不冻结 UI，通知到达，切换/重启重置 only_once，退出无本次后台残留。

## T45: 逐项完成 checklist 并整理证据

- [ ] 已完成并记录验证证据

**文件：** `docs/ch12/checklist.md`、`docs/ch12/verification.md`、`docs/ch12/task.md`

**依赖：** T44

**步骤：**
1. 开发前必须已有获批 checklist；此任务仅在开发及验收阶段逐项运行其中验证，不提前创建或批准它。
2. 逐项关联 AC1—AC25 的实际结果，复用本次仍有效证据；发现失败回到对应任务修复并重跑受影响检查。
3. 只有获得证据后勾选任务和 checklist；记录提交、测试报告、tmux 场景及未通过项，不把预期写成实际通过。

**验证：**

```bash
# 按获批 checklist 逐项执行；检查证据文件不存在未验证的完成标记。
git diff --check
git status --short
```

**期望：** 全部条目有可追踪实际证据；若存在阻塞或失败，明确未完成并说明，不宣称整体通过。

## 执行顺序与提交边界

按 T1 至 T45 顺序执行是合法的完整顺序；不要求引入并行开发。

| 阶段 | 任务 | 验证通过后的提交内容 |
|---|---|---|
| 基线与权限匹配 | T1—T5 | 共用匹配器、新权限格式与兼容测试 |
| Hook 定义与加载 | T6—T12 | 事件、条件、状态、快照及 YAML 加载 |
| 动作与调度 | T13—T20 | shell/HTTP 协议、分派、异步和取消关闭 |
| 模型与上下文 | T21—T26 | reminder、消息/轮次事件、回退和压缩 |
| 工具链 | T27—T30 | 权限前准备、批量执行、终态及 Skill 接入 |
| 会话与 UI | T31—T38 | 会话准备、启停、提交拦截、fork 隔离和 /hooks |
| 回归与验收 | T39—T45 | 边界检查、格式范围、真实验收及证据文档 |

提交标题描述实际完成的行为，不创建空提交。最终验收阶段复用本次仍有效证据，不为勾选 checklist 无意义地重复全部测试；修复后重跑受影响检查。

## Plan 组件覆盖

| 组件/交互 | 任务 |
|---|---|
| RuleMatcher 与权限兼容 | T2、T3、T4、T5 |
| HookEvent、HookAction、HookRejection | T6 |
| HookRule 与条件 | T7、T11 |
| HookSessionState 与 ReminderBatch | T8、T9、T19、T33 |
| HookInvocation 与 payload | T10、T22、T23、T26、T29 |
| HookConfigLoader、LoadedHooks | T11、T12 |
| CommandRunner.runHook | T13、T14 |
| HookActionExecutor 四种动作 | T15、T16、T17 |
| HookEngine 同步/异步/关闭 | T18、T19、T20 |
| PromptAdditions / PromptRequestFactory | T21、T24 |
| 模型尝试、回退和压缩 | T22、T23、T24、T25、T26 |
| 工具准备、权限分类、终态及 Skill | T27、T28、T29、T30 |
| SessionManager prepare/commit | T31、T33 |
| 启动、切换、提交、取消和关闭 | T32、T33、T34、T35、T37 |
| Skill fork 与辅助模型调用边界 | T36 |
| /hooks 和命令上下文 | T38 |
| 安全数据边界与格式构建 | T39、T40 |
| tmux 与实际验收证据 | T41、T42、T43、T44、T45 |

## 验收标准与任务对应

| 验收标准 | 提供实现或验证证据的任务 |
|---|---|
| AC1 | T2、T3、T4、T5 |
| AC2 | T5、T11、T12、T32 |
| AC3 | T12、T38、T41 |
| AC4 | T3、T11、T12 |
| AC5 | T7、T10、T11 |
| AC6 | T31、T32、T33、T44 |
| AC7 | T22、T23、T25、T42 |
| AC8 | T18、T32、T37、T44 |
| AC9 | T26 |
| AC10 | T27、T28、T30、T43 |
| AC11 | T29、T30、T43 |
| AC12 | T34、T35、T43 |
| AC13 | T13、T15、T43 |
| AC14 | T16、T17、T44 |
| AC15 | T11、T14、T17、T43 |
| AC16 | T9、T24、T25、T36、T42 |
| AC17 | T21、T24、T26 |
| AC18 | T8、T18、T19 |
| AC19 | T9、T33、T36、T44 |
| AC20 | T11、T14、T17、T19、T20、T35、T37、T44 |
| AC21 | T18、T19 |
| AC22 | T11、T15 |
| AC23 | T38、T41 |
| AC24 | T10、T13、T17、T39 |
| AC25 | T1、T40、T41、T42、T43、T44、T45 |
