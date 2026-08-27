# MewCode 五层权限系统 Checklist

> 状态：已实现；自动化测试与关键 tmux 场景已验收
>
> 本清单基于已确认的 [spec.md](./spec.md)、[plan.md](./plan.md) 和 [task.md](./task.md)。实现前必须完成四份文档审批；实现后每一项都要有测试、命令输出或 tmux 观察证据。

## 0. 范围和基本约束

- [ ] 保持 Java 21、现有 Gradle 构建方式、工具协议、Agent Loop 轮次上限、取消机制和工具调度语义不变。`N1/N8/T1/T9`
- [ ] 所有工具调用都从 `ToolExecutor` 的统一权限入口执行，工具自身没有绕过权限闸门的路径。`F1/N1/T6/T8`
- [ ] 五层顺序固定为：Bash 黑名单 → 文件路径/ Bash OS 沙箱 → 规则 → 模式 → HITL → 工具执行。`F1/T6`
- [ ] 黑名单、配置拒绝、路径拒绝、用户拒绝和沙箱失败都返回错误 `ToolResult`，不直接终止 Agent Loop。`F7/N5/T8/T9`
- [ ] 本章没有新增网络请求限制、资源配额、审计日志、Shell 应用层解析、完整命令白名单、远程执行、MCP 或子代理能力。`N8/T1/T10/T14`

## 1. 第一层：Bash 危险命令黑名单

- [ ] 黑名单只针对 `Bash` 工具的 `command` 参数，不影响 `ReadFile`、`Glob`、`Grep` 或文件编辑工具。`F2/T2`
- [ ] 黑名单由程序内置不可变正则组成，不从 YAML 加载，也不存在模式、规则或运行时开关关闭黑名单。`F2/N2/T2`
- [ ] `rm -rf /` 命中黑名单并被绝对拒绝。`F2/AC1/T2`
- [ ] 危险命令的空格、参数顺序或等价根路径写法不会绕过既定黑名单覆盖范围。`F2/AC1/T2`
- [ ] 明确安全的普通命令不会被危险黑名单误拦截。`T2`
- [ ] 黑名单命中时返回以下清晰错误信息或保持等价的完整语义：

  ```text
  操作被拒绝：检测到危险命令 "rm -rf /"。
  此操作可能造成不可逆的系统损坏，已被安全策略硬拦截。
  ```

  `F2/AC1/T2`
- [ ] 黑名单检查发生在 OS 沙箱包装、Shell 启动、参数校验副作用和执行 Future 提交之前。`F2/AC1/T2/T8`
- [ ] 黑名单命中时没有创建 Shell 进程、没有写入文件、没有网络或其他命令副作用。`F2/AC1/T2/T13`
- [ ] `bypassPermissions`、`allow` 规则、会话授权和永久授权都不能放行黑名单命令。`F2/F4/F5/AC1/T2/T6`
- [ ] 黑名单错误作为成对的工具结果回灌模型，Agent Loop 可以继续。`F2/F7/AC2/T8/T9`

## 2. 第二层：文件工具路径沙箱

- [ ] `ReadFile`、`WriteFile`、`EditFile`、`Glob`、`Grep` 均经过路径沙箱。`F3/AC3/T3`
- [ ] 相对路径先解析到项目根目录，实际执行仍保留原始工具参数语义。`F3/T3`
- [ ] 已存在目标先解析真实路径，再进行项目根目录前缀判断。`F3/AC4/T3`
- [ ] 不存在目标解析最近存在的父路径或路径组件，再进行真实边界判断。`F3/AC4/T3`
- [ ] 路径检查能识别指向项目外的符号链接，不能只使用未经解析的字符串前缀判断。`F3/AC4/T3`
- [ ] 相邻目录名称如项目目录 `project` 与 `project-other` 不发生字符串前缀误判。`F3/AC4/T3`
- [ ] 项目内文件操作进入后续规则和模式判断。`F3/AC3/T3/T6`
- [ ] 项目外路径、符号链接逃逸和无法可靠解析的边界结果不会被静默放行。`F3/AC4/T3`
- [ ] 项目外文件操作默认产生 `Ask`，并展示真实路径、原始路径和触发原因。`F3/AC5/T3/T11`
- [ ] `bypassPermissions` 不能自动跳过项目外路径确认。`F5/AC5/T6`
- [ ] 用户确认本次后只放行当前调用；同类下一次仍按规则重新判断。`F6/AC5/T7/T11`
- [ ] 用户确认会话放行后，当前 Agent 会话内相同授权范围可复用。`F6/T7/T11`
- [ ] 用户确认永久放行后，重启程序仍可读取本地路径授权记录。`F6/AC5/T7/T10`
- [ ] 文件工具路径授权只影响文件工具应用层检查，不能关闭 Bash OS 沙箱。`F3/F4/T3/T5/T6`
- [ ] 路径授权持久化失败时当前操作不自动执行，并返回清晰错误。`F6/AC5/T7`

## 3. 第二层：Bash OS 级进程沙箱

- [ ] Bash 命令在支持的平台上通过 OS 级进程沙箱启动，而不是直接裸执行。`F3/AC13/T4/T8`
- [ ] macOS 使用 seatbelt profile，Linux 使用 bubblewrap；平台选择由工厂统一完成。`F3/AC13/T4`
- [ ] 沙箱默认以项目根目录作为可写范围，系统依赖路径保持只读。`F3/AC13/T4`
- [ ] 普通权限规则不能扩大 OS 沙箱的未授权写入范围。`F4/F5/T4/T5/T6`
- [ ] Bash 保留完整 Shell 语义，重定向、管道、命令替换和脚本解释器仍可启动。`F3/AC13/T4`
- [ ] 通过重定向写入沙箱外路径时，OS 拒绝写入且不产生外部副作用。`F3/AC13/T13`
- [ ] 通过管道、命令替换或脚本解释器间接写入沙箱外路径时，OS 仍拒绝写入。`F3/AC13/T13`
- [ ] 通过符号链接指向沙箱外路径写入时，OS 仍拒绝写入。`F3/AC13/T13`
- [ ] 项目内允许写入可以正常完成，返回码、标准输出和错误输出保持现有语义。`F3/AC13/T13`
- [ ] 沙箱参数、profile 参数、Shell 参数和用户命令使用独立参数构造，不拼接未转义的包装 Shell 字符串。`N1/T4/T13`
- [ ] 不使用网络隔离 namespace 或其他新增网络限制；本章不改变网络能力范围。`F3/AC13/T4/T13`
- [ ] seatbelt/bubblewrap 不存在时 Bash 调用 Fail-Closed。`N1/AC17/T4/T13`
- [ ] profile 构造失败时 Bash 调用 Fail-Closed，不启动裸 Shell。`N1/AC17/T4/T13`
- [ ] 沙箱进程启动失败时返回清晰工具错误，不执行未隔离后备路径。`N1/AC17/T4/T8/T13`
- [ ] 沙箱不可用、profile 失败和启动失败都不会被 HITL、规则或 `bypassPermissions` 绕过。`F1/F3/F5/T4/T6`
- [ ] Bash OS 沙箱不依赖应用层 Shell 解析，也不使用安全命令白名单替代 OS 边界。`F3/N1/T4`

## 4. 第三层：分层权限规则

- [ ] 用户级规则文件为 `~/.mewcode/permissions.yaml`。`F4/T5`
- [ ] 项目级规则文件为 `.mewcode/permissions.yaml`。`F4/T5`
- [ ] 本地级规则文件为 `.mewcode/permissions.local.yaml`。`F4/T5`
- [ ] 规则格式支持 `工具名(模式)`，例如 `Bash(git *)`。`F4/AC6/T5`
- [ ] 规则 decision 只有 `allow` 和 `deny`，不接受 `ask` 或其他值。`F4/AC6/T5`
- [ ] 支持工具名和参数/路径的精确匹配。`F4/AC6/T5`
- [ ] 支持 glob 匹配，并正确处理通配符边界。`F4/AC6/T5`
- [ ] Bash 规则匹配完整 `command` 文本。`F4/T5`
- [ ] 文件工具规则匹配规范化后的路径或路径模式。`F4/T5`
- [ ] `Grep` 的匹配目标包含稳定的搜索表达式和路径范围，不使用易变的运行时文本。`F4/T5`
- [ ] 规则优先级为：会话级 → 本地级 → 项目级 → 用户级。`F4/AC7/T5`
- [ ] 会话级临时规则覆盖项目级、本地级和用户级规则。`F4/AC7/T5/T7`
- [ ] 项目级规则覆盖用户全局默认规则。`F4/AC7/T5`
- [ ] 明确命中的 `deny` 高于权限模式，不能被 `bypassPermissions` 覆盖。`F4/F5/AC8/T5/T6`
- [ ] `allow` 规则不能关闭危险黑名单、消除文件路径沙箱确认或关闭 Bash OS 沙箱。`F2/F3/F4/AC8/T5/T6`
- [ ] 规则文件缺失时按空规则处理。`F4/T5/T10`
- [ ] YAML 语法错误、字段缺失、非法 decision 或非法 pattern 均 Fail-Closed。`F4/AC12/T5`
- [ ] 配置错误不会被解释成 `allow`，也不会执行对应工具。`N3/AC12/T5/T8`

## 5. 第四层：权限模式

- [ ] `default` 模式下 `ReadFile`、`Glob`、`Grep` 自动 Allow。`F5/AC9/T1/T6`
- [ ] `default` 模式下 `WriteFile`、`EditFile` 和 Bash 默认 Ask。`F5/AC9/T6`
- [ ] `acceptEdits` 模式下只读工具和文件写工具自动 Allow，Bash 默认 Ask。`F5/AC9/T6`
- [ ] `plan` 模式下权限矩阵与 `default` 完全一致。`F5/AC9/T6`
- [ ] `plan` 模式通过 System Prompt 引导只读，但 Prompt 不是安全边界。`F5/AC10/T9/T10`
- [ ] `plan` 模式下模型可以看到完整工具定义；违规调用写工具或 Bash 时触发 Ask。`F5/AC10/T9`
- [ ] `bypassPermissions` 模式下普通只读、文件写入和 Bash 都可自动执行。`F5/AC9/T6`
- [ ] `bypassPermissions` 不能跳过黑名单、文件路径沙箱确认或 Bash OS 沙箱。`F1/F3/F5/AC9/T6`
- [ ] 未命中明确规则时才使用当前模式决定 Allow 或 Ask。`F4/F5/AC8/T6`
- [ ] 模式解析缺省值为 `default`，非法模式配置 Fail-Closed。`F5/F8/AC12/T10`
- [ ] `/plan` 切换为 `plan`，`/do` 恢复 `default`，且命令本身不触发模型调用或写入历史。`F8/T10`

## 6. 第五层：HITL 人在回路

- [ ] 最终结果为 `Ask` 的操作在执行前才展示确认框。`F6/AC11/T7/T8/T11`
- [ ] 确认框包含工具名称、关键参数或路径、触发原因和潜在影响。`F6/AC11/T11`
- [ ] Bash 确认框至少展示：

  ```text
  MewCode 想要执行以下操作：

  [Bash] git commit -m "fix: resolve null reference in handler"

  允许执行？(y)是 / (n)否 / (a)始终允许此类操作
  ```

  `F6/AC11/T11`
- [ ] `y` 只允许当前调用。`F6/AC11/T7/T11`
- [ ] `n` 拒绝当前调用。`F6/AC11/T7/T11`
- [ ] `s` 支持本次会话放行，并只影响当前 Agent 会话。`F6/T7/T11`
- [ ] `a` 保存当前工具和匹配范围的永久授权。`F6/AC11/T7/T11`
- [ ] 任何授权选项都不能放行黑名单命令或关闭 Bash OS 沙箱。`F2/F3/F6/T6/T11`
- [ ] 黑名单命中和不可授权的沙箱失败不展示可绕过的确认按钮。`F2/F3/T11`
- [ ] `PermissionBroker` 以请求 ID 管理 pending 请求，重复响应不会重复执行。`F6/N5/T7`
- [ ] 取消或 Ctrl+C 能结束 pending 确认，且不留下可执行的悬挂请求。`F7/N6/T7/T11`
- [ ] 等待确认时普通输入被锁定，确认结束后输入状态恢复。`F6/AC11/T11`
- [ ] 确认文本不写入 `chatMessages`、`ConversationManager` 或模型历史。`F6/AC11/T11`
- [ ] 永久授权写入失败时不自动放行当前请求。`F6/T7`

## 7. Agent Loop 和工具结果

- [ ] 权限拒绝仍保留 assistant tool-use 与对应 tool-result 的配对关系。`F7/AC14/T8/T9`
- [ ] 黑名单拦截、规则 deny、路径拒绝、用户拒绝和沙箱错误都能回灌模型。`F7/AC2/AC14/T8/T9`
- [ ] 模型收到拒绝结果后可以继续下一轮并调整策略。`F7/AC2/T9/T13`
- [ ] 多个工具调用的结果仍按原始调用顺序回灌。`F7/AC14/T8/T9`
- [ ] 安全只读工具并发、不安全工具串行的既有调度行为不退化。`N8/AC14/T8/T9`
- [ ] 权限等待支持取消、超时和 Agent Run 结束，不阻塞 TUI 主循环。`F7/N5/AC14/T7/T9/T11`
- [ ] 未知工具、参数错误和工具运行时错误仍按既有错误协议处理。`N8/AC14/T8/T9`
- [ ] Agent Run 结束时释放 pending 权限请求和会话级授权上下文。`F7/T7/T9`
- [ ] 权限事件不会改变现有模型请求、历史提交和最终答复顺序。`F7/AC14/T9`

## 8. 配置、文件组织和回归

- [ ] 权限运行时在启动阶段完成组装：模式、规则引擎、路径授权存储、Bash 沙箱工厂和 Broker 均注入执行链。`F8/T5/T10`
- [ ] `.mewcode/permissions.local.yaml` 不被提交到版本库；示例规则文件可以共享。`F4/T5/T10`
- [ ] 权限核心、配置、工具、Agent、Prompt 和 TUI 模块之间依赖方向与 Plan 一致。`N1/T1/T8/T9/T10/T11`
- [ ] 既有工具名称、参数 schema、注册顺序和工具执行逻辑未被无关改变。`N8/T9/T12`
- [ ] 既有 Plan/Execute Prompt 语义保留，并新增 Plan 只读提醒。`F5/F8/T10`
- [ ] 错误信息不包含不必要的敏感环境信息，同时足够说明拒绝原因和影响。`F2/F6/T2/T8/T11`

## 9. 自动化测试和构建

- [ ] `DangerousCommandBlocklistTest` 覆盖危险命令、安全命令、错误文本和不可绕过行为。`AC1/T2`
- [ ] `PathSandboxTest` 覆盖真实路径、最近存在父目录、相邻目录、符号链接和路径模式。`AC3/AC4/T3`
- [ ] `BashSandboxTest` 覆盖平台选择、profile、参数化 argv、写入范围、网络参数和 Fail-Closed。`AC13/AC17/T4`
- [ ] `PermissionRuleEngineTest` 覆盖精确匹配、glob、四层优先级、非法 YAML 和非法 decision。`AC6/AC7/AC12/T5`
- [ ] `PermissionGateTest` 覆盖五层短路顺序、四档矩阵、规则覆盖和不可绕过边界。`AC8/AC9/T6`
- [ ] `PermissionBrokerTest` 覆盖一次、会话、永久、拒绝、重复响应、取消和持久化失败。`AC11/T7`
- [ ] `ToolExecutorTest` 覆盖权限检查先于校验/Future/副作用，以及错误 ToolResult。`AC2/AC14/T8`
- [ ] `CommandRunnerTest` 覆盖黑名单前置、沙箱参数和禁止裸执行。`AC1/AC13/AC17/T2/T4/T8`
- [ ] `BashSandboxIntegrationTest` 在支持平台覆盖项目内写入和多种沙箱外写入路径。`AC13/AC17/T13`
- [ ] `AgentLoopTest` 和 `PermissionIntegrationTest` 覆盖拒绝后继续 Loop、结果配对和模式切换。`AC2/AC14/T9/T13`
- [ ] `PermissionPromptFormatterTest` 覆盖 Bash 精确文案、参数展示和授权选项。`AC11/T11`
- [ ] `MewCodeModelTest` 覆盖确认状态、输入锁定、取消和历史隔离。`AC11/T11`
- [ ] 运行 `./gradlew spotlessCheck` 并通过。`AC15/T14`
- [ ] 运行 `./gradlew test` 并通过。`AC15/T12/T14`
- [ ] 运行 `./gradlew shadowJar` 并成功生成 `build/libs/mewcode.jar`。`AC15/T14`
- [ ] Ch02–Ch05 既有测试全部通过，没有新增构建警告或范围外依赖。`N8/AC14/AC15/T12/T14`

## 10. tmux 端到端验收

- [ ] 使用 tmux 启动真实 MewCode，输入真实开发请求，程序可以完成启动、模型交互和输入恢复。`AC16/T14`
- [ ] `default` 下读取代码、Glob、Grep 自动执行；写文件前出现确认框。`AC16/T14`
- [ ] 用户选择本次放行后写入完成，同类下一次操作再次确认。`AC16/T14`
- [ ] 用户选择会话放行后，同会话同类操作不再重复确认，重启后不复用会话授权。`AC16/T14`
- [ ] 用户选择永久放行后，重启程序仍能复用本地授权。`AC16/T14`
- [ ] `acceptEdits` 下文件编辑自动执行，Bash 仍然确认。`AC16/T14`
- [ ] `plan` 下模型可以看到完整工具，但写操作和 Bash 仍确认。`AC10/AC16/T14`
- [ ] `bypassPermissions` 下普通写入和 Bash 自动执行，但黑名单、路径确认和 Bash OS 沙箱仍生效。`AC9/AC16/T14`
- [ ] 文件工具访问项目外路径或通过符号链接逃逸时出现确认，不能被 `bypassPermissions` 自动跳过。`AC4/AC5/AC16/T14`
- [ ] Bash 在项目目录内写入成功，尝试写入沙箱外路径时被 OS 拒绝。`AC13/AC16/T13/T14`
- [ ] 输入 `rm -rf /` 时始终返回硬拦截错误，不出现授权选项。`AC1/AC16/T14`
- [ ] 拒绝任一操作后，模型继续运行并最终恢复用户输入，不退出 Agent Loop。`AC2/AC14/AC16/T14`
- [ ] tmux 观察结果、测试命令、运行平台和构建产物路径已记录，所有必须项都有证据。`AC16/T14`

## 11. 完成标准

- [ ] 本清单所有必选项均已勾选并附有验证证据。
- [ ] `spec.md`、`plan.md`、`task.md` 和本 `checklist.md` 的状态均已更新为真实阶段状态。
- [ ] 没有为了通过测试而关闭黑名单、路径沙箱、Bash OS 沙箱或 Fail-Closed 行为。
- [ ] 没有实现本章明确排除的网络限制、资源配额、审计日志或远程能力。
