# 项目根目录路径上下文修复 Plan

> 状态：已确认
>
> 本计划基于已确认的 [spec.md](/Users/bytedance/IdeaProjects/Mewcode-develop/docs/ch5/spec.md)。本阶段只设计根目录上下文统一和路径错误增强，不处理 DSML 文本泄漏。

## 1. 架构概览

应用入口在启动时只计算一次项目根目录，并将同一个 `Path` 传入配置加载、TUI 模型、LLM 系统提示和工具执行器：

```text
MewCode.run()
    └─ projectRoot = normalize(user.dir)
          ├─ ConfigLoader.load(projectRoot/.mewcode/config.yaml)
          └─ MewCodeModel(providers, projectRoot)
                ├─ PromptBuilder.buildSystemPrompt(projectRoot)
                ├─ ToolExecutor(..., projectRoot, ...)
                ├─ renderBanner(projectRoot)
                └─ AgentTurnCoordinator
                      └─ Tool.validateInput(context, input)
                            └─ PathGuard 统一路径校验和修正提示
```

`user.dir` 仍然表示当前启动目录，这是现有产品对“项目根目录”的定义；本次不引入 Git 根目录自动发现。关键变化是：根目录只在入口确定一次，后续模块不再各自重新读取系统属性。

## 2. 核心数据结构与接口

### 2.1 `MewCodeModel` 的项目根目录字段

在 `MewCodeModel` 中增加不可变的 `Path projectRoot` 字段。构造时执行：

```java
projectRoot = projectRoot.toAbsolutePath().normalize();
```

公开的单参数构造函数继续保留，用于兼容现有调用方；它只负责从启动目录创建默认根目录。测试构造函数接收显式根目录，以便使用临时项目根目录。

### 2.2 `PromptBuilder` 根目录参数

将系统提示入口改为接收 `Path projectRoot`，输出经过规范化的绝对路径。提示中固定包含：

- 当前项目根目录的完整路径；
- 相对用户路径必须基于该根目录解析；
- 工具参数必须是根目录内绝对路径/绝对 glob 模式；
- 禁止猜测 `user.home`、其他用户目录或 `/Users/mew`。

### 2.3 `Tool.validateInput` 的兼容扩展

保留现有接口方法：

```java
String validateInput(Map<String, Object> input);
```

增加带上下文的默认重载：

```java
default String validateInput(ToolExecutionContext context,
                             Map<String, Object> input) {
    return validateInput(input);
}
```

`ToolExecutor` 调用带上下文的重载；未实现新重载的第三方/测试工具仍沿用旧校验逻辑。ReadFile、WriteFile、EditFile、Glob、Grep 覆盖新重载，使用真实 `projectRoot` 生成具体错误提示。

## 3. 模块设计

### 3.1 `MewCode`

**职责：** 创建会话级项目根目录，并将配置文件也按该根目录定位。

**改动：**

- 在 `run()` 开始处计算规范化绝对根目录；
- 使用 `projectRoot.resolve(".mewcode/config.yaml")` 加载配置；
- 创建 `MewCodeModel` 时传入同一个 `projectRoot`。

**约束：** 不读取 `user.home`，不根据模型文本改变根目录。

### 3.2 `MewCodeModel`

**职责：** 保存会话根目录，并把它传给 prompt、工具执行器和 TUI banner。

**改动：**

- 初始化 provider 时调用 `PromptBuilder.buildSystemPrompt(projectRoot)`；
- 创建 `ToolExecutor` 时使用字段中的 `projectRoot`；
- banner 展示字段中的 `projectRoot`；
- 测试专用构造路径显式传入临时根目录。

### 3.3 `PromptBuilder`

**职责：** 将真实环境上下文注入模型，但不参与工具执行。

**改动：** 增加根目录参数和绝对路径规则，使用统一的路径示例。提示明确告诉模型：用户输入 `.trae/skills/mew-spec/SKILL.md` 时，必须生成 `<projectRoot>/.trae/skills/mew-spec/SKILL.md`。

### 3.4 `ToolExecutor`

**职责：** 在执行前向工具校验传递完整 `ToolExecutionContext`。

**改动：**

- 将 `safeValidate(tool, input)` 改为调用 `tool.validateInput(baseContext, input)`；
- 保持校验失败包装为 `ToolResult.error`，不启动工具执行线程；
- 不改变超时、并发、安全元信息和结果配对逻辑。

### 3.5 `PathGuard`

**职责：** 统一绝对路径、项目边界和具体修正提示。

**改动：**

- 相对 `path`：指出当前根目录，并用 `root.resolve(relative).normalize()` 给出建议路径；
- 相对 `pattern`：指出当前根目录，并给出根目录加 glob 模式的建议；
- 越界绝对路径：拒绝执行，指出真实项目根目录，不把外部路径静默映射到项目内；
- 既有文件存在、符号链接逃逸、权限和不存在错误继续保留原有语义。

PathGuard 的建议文本只用于 `ToolResult.content`，不会修改原始 tool-use 参数，也不会进入 UI 摘要之外的模型协议字段。

### 3.6 五个路径型工具

`ReadFileTool`、`WriteFileTool`、`EditFileTool`、`GlobTool`、`GrepTool` 的带上下文校验只负责调用统一的 PathGuard 辅助方法；执行阶段仍使用已有的 `validatePath`/`validatePattern` 做最终防线。Bash 不增加参数校验，只继续使用 `context.projectRoot()` 作为工作目录。

## 4. 路径处理流程

### 4.1 模型输入相对路径

```text
用户：读取 .trae/skills/mew-spec/SKILL.md
    ↓
系统提示提供 projectRoot
    ↓
模型生成：/Users/bytedance/IdeaProjects/Mewcode-develop/.trae/skills/mew-spec/SKILL.md
    ↓
Tool.validateInput(context, input)
    ↓
PathGuard 验证绝对性和项目边界
    ↓
ReadFile 执行
```

如果模型仍传 `.trae/skills/mew-spec/SKILL.md`，不自动执行，而是返回包含建议绝对路径的结构化错误。

### 4.2 模型输入错误绝对路径

```text
/Users/mew/.trae/skills/mew-spec/SKILL.md
    ↓
PathGuard.normalize()
    ↓
startsWith(projectRoot) == false
    ↓
isError=true + 当前项目根目录 + 越界调整提示
```

## 5. 文件组织

```text
src/main/java/com/mewcode/MewCode.java
    — 会话根目录创建和配置路径定位
src/main/java/com/mewcode/tui/MewCodeModel.java
    — 保存并复用 projectRoot
src/main/java/com/mewcode/prompt/PromptBuilder.java
    — 注入真实根目录和路径规则
src/main/java/com/mewcode/tool/Tool.java
    — 增加带上下文校验的兼容默认方法
src/main/java/com/mewcode/tool/ToolExecutor.java
    — 调用上下文校验入口
src/main/java/com/mewcode/tool/support/PathGuard.java
    — 统一路径建议和边界错误
src/main/java/com/mewcode/tool/impl/ReadFileTool.java
src/main/java/com/mewcode/tool/impl/WriteFileTool.java
src/main/java/com/mewcode/tool/impl/EditFileTool.java
src/main/java/com/mewcode/tool/impl/GlobTool.java
src/main/java/com/mewcode/tool/impl/GrepTool.java
    — 接入上下文校验
src/main/java/com/mewcode/config/ConfigLoader.java
    — 如需，增加 Path 入参重载

src/test/java/com/mewcode/prompt/PromptBuilderTest.java
    — 根目录注入和禁止猜测目录
src/test/java/com/mewcode/tool/support/PathGuardTest.java
    — 相对、越界、绝对合法路径/模式
src/test/java/com/mewcode/tool/ToolExecutorTest.java
    — 上下文校验在执行前生效
src/test/java/com/mewcode/llm/OpenAiClientTest.java
src/test/java/com/mewcode/llm/AnthropicClientTest.java
    — provider 请求中的 system prompt 根目录
src/test/java/com/mewcode/tui/MewCodeModelTest.java
    — banner、prompt 和 executor 共享根目录
```

## 6. 技术决策

| 决策点 | 选择 | 理由 |
| --- | --- | --- |
| 根目录来源 | 入口处规范化 `user.dir` | 保持当前“从项目根目录启动”的产品语义，不引入隐式 Git 扫描 |
| 根目录传递 | 会话级不可变 `Path` 向下传递 | 避免 prompt、TUI、工具各自读取导致不一致 |
| 相对路径处理 | 提示模型解析；工具端拒绝并给建议 | 保持绝对路径安全契约，错误可恢复 |
| 校验时机 | 增加带上下文的 `validateInput` 默认重载 | 保留既有 Tool 接口兼容性，同时让路径错误拿到根目录 |
| 越界路径 | 永不静默重写 | 防止模型误读项目外文件 |
| 工具 schema | 保持结构不变 | 根目录属于会话提示上下文，不应污染 provider schema |

## 7. 验证策略

- 单元测试验证当前根目录下 `.trae/skills/mew-spec/SKILL.md` 的正确绝对路径可通过校验。
- 单元测试验证 `/Users/mew/...` 和相对 `.trae/...` 分别返回不同、可操作的错误。
- Prompt 测试验证实际根目录被注入，且不会读取或使用 `user.home`。
- Provider 请求体测试验证 Anthropic/OpenAI/DeepSeek 兼容路径都收到同一 system prompt。
- tmux 使用本地 OpenAI 兼容服务检查请求中的 system prompt、工具执行根目录和最终展示路径。
- 完成后运行完整 Gradle 测试、`shadowJar` 和既有 checklist。

## 8. 不在本计划内

- DeepSeek DSML 文本解析/过滤。
- 权限确认和用户授权流程。
- 连续 Agent Loop。
- 自动识别 Git 根目录或跨目录工作区。
