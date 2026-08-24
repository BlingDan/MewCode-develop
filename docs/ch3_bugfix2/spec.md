# 项目根目录路径上下文修复 Spec

> 状态：已确认
>
> 本 Spec 针对截图中模型把 `.trae/skills/mew-spec/SKILL.md` 错误生成
> 为 `/Users/mew/.trae/skills/mew-spec/SKILL.md` 的问题。修复范围覆盖所有
> 文件和搜索工具的路径上下文，不包含 DSML 文本泄漏问题。

## 背景

MewCode 启动时能够从 `user.dir` 得到当前项目目录，并将该目录用于工具执行和项目边界校验。当前运行实例中，终端工作目录、Java `user.dir`、TUI banner 和工具执行根目录都是：

```text
/Users/bytedance/IdeaProjects/Mewcode-develop
```

但是模型收到的系统提示只说“路径必须在项目根目录内”，没有看到项目根目录的实际绝对路径。模型因此自行猜测用户目录，生成了 `/Users/mew/...`，被 `PathGuard` 正确拒绝。

问题不只影响 `ReadFile`。同样的上下文缺失会影响 `WriteFile`、`EditFile`、`Glob` 和 `Grep` 的 `path` 或 `pattern` 参数。

## 目标

- 让模型在系统提示中明确获得本次会话的真实项目根目录绝对路径。
- 让 TUI、模型提示、工具执行上下文和 Bash 工作目录使用同一个根目录值。
- 覆盖所有文件/搜索工具的同类路径错误，而不是只修 ReadFile。
- 当模型仍传入相对路径或项目根目录外路径时，返回包含真实项目根目录和调整方向的可操作错误。
- 保持工具“只接受项目根目录内绝对路径”的安全契约，不静默执行越界路径。

## 功能需求

### F1：统一项目根目录上下文

一次会话初始化时确定一个规范化的项目根目录绝对路径。后续系统提示、TUI 展示、文件工具边界校验和 Bash 工作目录都使用这一个值，不在不同模块中重复读取或推导用户目录。

### F2：向模型提供真实根目录

系统提示必须包含本次会话的具体项目根目录，例如：

```text
当前项目根目录是：
/Users/bytedance/IdeaProjects/Mewcode-develop
```

同时明确说明：用户提到的相对文件路径应基于该目录解析；工具调用中的文件和搜索参数最终必须是该目录内的绝对路径或绝对 glob 模式；禁止猜测其他用户目录。

### F3：覆盖所有路径型工具

以下输入必须使用统一根目录上下文：

| 工具 | 参数 | 约束 |
| --- | --- | --- |
| ReadFile | `path` | 项目根目录内的绝对文件路径 |
| WriteFile | `path` | 项目根目录内的绝对文件路径 |
| EditFile | `path` | 项目根目录内的绝对文件路径 |
| Glob | `pattern` | 项目根目录内的绝对 glob 模式 |
| Grep | `path` | 项目根目录内的绝对搜索根目录 |
| Bash | 工作目录 | 固定为同一个项目根目录 |

### F4：相对路径错误提供具体修正方向

如果模型传入相对 `path` 或相对 `pattern`，工具仍返回 `isError=true`，但错误中必须包含当前真实项目根目录，并给出基于该根目录的建议绝对路径/模式。

例如：

```text
参数 path 必须是绝对路径。
当前项目根目录是：/Users/bytedance/IdeaProjects/Mewcode-develop
建议使用：/Users/bytedance/IdeaProjects/Mewcode-develop/.trae/skills/mew-spec/SKILL.md
```

### F5：项目根目录外路径错误可诊断

如果模型传入 `/Users/mew/...` 这类绝对但越界的路径，工具不得把它重写到项目内执行。错误信息应明确指出当前根目录和越界原因，让模型重新生成路径。

### F6：保持模型协议和工具语义

本修复只改变根目录上下文传递和路径错误提示，不改变工具的读写权限、二进制检测、先读再写、glob 搜索规则、grep 行为、工具协议或 Agent Loop 边界。

## 非功能需求

### N1：单一事实来源

一次会话内不能出现多个不一致的项目根目录值；测试必须能验证提示词根目录、工具执行根目录和 Bash 工作目录一致。

### N2：安全性

不得因为帮助模型纠正路径而放宽项目根目录边界；不得使用 `user.home`、模型猜测的用户目录或未经验证的路径作为项目根目录。

### N3：兼容性

Anthropic、OpenAI 和 DeepSeek 兼容协议都必须收到包含真实根目录的系统提示；六个现有工具的 API schema 保持兼容。

### N4：可测试性

需要有单元测试、协议请求体测试和 tmux 端到端场景覆盖真实根目录注入及五类路径型工具。

## 方案选择

### 方案 A：统一根目录上下文并注入提示词（推荐）

初始化时生成一个共享的项目根目录上下文，将它同时传给 prompt builder、ToolExecutor、TUI 和 Bash。路径校验继续拒绝相对/越界输入，但错误信息给出具体根目录和建议路径。

优点是安全边界不变、修复覆盖面完整、模型能得到准确环境信息；缺点是需要调整初始化链路和错误消息测试。

### 方案 B：只把根目录拼进系统提示

保留各模块当前的根目录读取方式，只修改系统提示内容。

优点是改动小；缺点是多个模块仍可能在不同启动条件下拿到不同目录，无法彻底保证“提示中的根目录”和“工具执行根目录”一致。

### 方案 C：工具端自动把相对路径解析成根目录路径

工具收到相对路径后直接执行 `projectRoot.resolve(relativePath)`。

不采用。它会放宽已经确认的绝对路径契约，掩盖模型参数错误，也会让不同工具对同一参数产生隐式差异。工具应拒绝错误输入并返回可操作的修正建议。

## 不做的事

- 不处理 DeepSeek DSML 文本泄漏；该问题单独立项。
- 不自动扫描 Git 根目录、用户 Home 目录或其他父目录来猜测项目根目录。
- 不允许读取项目根目录外的文件。
- 不改变工具权限确认、并发策略、协议结构或连续 Agent Loop 行为。
- 不把 UI 中显示的路径摘要写入模型对话历史。

## 验收标准

- AC1：启动目录为 `/Users/bytedance/IdeaProjects/Mewcode-develop` 时，系统提示、TUI banner、ToolExecutionContext 和 Bash 工作目录全部使用该绝对路径。
- AC2：系统提示包含真实项目根目录，不包含猜测的 `/Users/mew` 或其他用户目录。
- AC3：模型请求 `.trae/skills/mew-spec/SKILL.md` 时，提示词明确要求生成 `/Users/bytedance/IdeaProjects/Mewcode-develop/.trae/skills/mew-spec/SKILL.md`。
- AC4：ReadFile、WriteFile、EditFile、Glob、Grep 的相对参数都返回 `isError=true`，并包含真实根目录和建议绝对参数。
- AC5：上述工具收到 `/Users/mew/...` 等越界绝对路径时仍拒绝执行，并说明当前项目根目录。
- AC6：绝对路径正常行为不受影响，项目内的 `.trae/skills/mew-spec/SKILL.md` 可以被 ReadFile 读取。
- AC7：Bash 仍在同一个项目根目录执行，其他既有工具测试全部通过。
- AC8：协议请求体测试验证三类 provider 都收到相同的真实根目录系统提示。
- AC9：tmux 端到端测试验证模型不再因为缺少根目录上下文而生成 `/Users/mew/...`；错误路径防线仍有效。
