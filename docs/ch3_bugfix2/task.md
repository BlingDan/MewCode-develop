# 项目根目录路径上下文修复 Task

> 状态：已确认
>
> 本任务清单基于已确认的 [spec.md](/Users/bytedance/IdeaProjects/Mewcode-develop/docs/ch5/spec.md) 和 [plan.md](/Users/bytedance/IdeaProjects/Mewcode-develop/docs/ch5/plan.md)。四份文档全部确认前不进入实现阶段。

## 1. 执行规则

- 严格按依赖顺序执行；前置任务失败时先修复并重新验证。
- 所有路径测试使用临时项目根目录或仓库中已存在的只读文件。
- 不读取或使用 `user.home`，不把相对路径静默转换后执行。
- 不修改 DSML、权限确认、MCP 或 Agent Loop 行为。
- 每个任务完成后运行对应的窄范围测试；全部完成后运行完整测试、打包和 tmux 验收。

## 2. 文件清单

| 操作 | 文件 | 职责 |
| --- | --- | --- |
| 修改 | `src/main/java/com/mewcode/MewCode.java` | 创建会话根目录并定位配置 |
| 修改 | `src/main/java/com/mewcode/config/ConfigLoader.java` | 支持 `Path` 配置路径 |
| 修改 | `src/main/java/com/mewcode/tui/MewCodeModel.java` | 保存并复用项目根目录 |
| 修改 | `src/main/java/com/mewcode/prompt/PromptBuilder.java` | 注入真实根目录和路径规则 |
| 修改 | `src/main/java/com/mewcode/tool/Tool.java` | 增加带上下文校验的兼容默认方法 |
| 修改 | `src/main/java/com/mewcode/tool/ToolExecutor.java` | 在执行前传递上下文校验 |
| 修改 | `src/main/java/com/mewcode/tool/support/PathGuard.java` | 统一路径建议和越界错误 |
| 修改 | `src/main/java/com/mewcode/tool/impl/ReadFileTool.java` | 接入上下文路径校验 |
| 修改 | `src/main/java/com/mewcode/tool/impl/WriteFileTool.java` | 接入上下文路径校验 |
| 修改 | `src/main/java/com/mewcode/tool/impl/EditFileTool.java` | 接入上下文路径校验 |
| 修改 | `src/main/java/com/mewcode/tool/impl/GlobTool.java` | 接入上下文模式校验 |
| 修改 | `src/main/java/com/mewcode/tool/impl/GrepTool.java` | 接入上下文路径校验 |
| 新建 | `src/test/java/com/mewcode/prompt/PromptBuilderTest.java` | 根目录提示测试 |
| 新建 | `src/test/java/com/mewcode/tool/support/PathGuardTest.java` | 路径边界和建议测试 |
| 修改 | `src/test/java/com/mewcode/tool/ToolExecutorTest.java` | 上下文校验执行前测试 |
| 修改 | `src/test/java/com/mewcode/config/ConfigLoaderTest.java` | Path 配置入口测试 |
| 修改 | `src/test/java/com/mewcode/tui/MewCodeModelTest.java` | TUI、prompt、executor 根目录一致性测试 |
| 修改 | `src/test/java/com/mewcode/llm/OpenAiClientTest.java` | OpenAI/DeepSeek 兼容请求提示测试 |
| 修改 | `src/test/java/com/mewcode/llm/AnthropicClientTest.java` | Anthropic 请求提示测试 |

## 3. 任务列表

### T0：建立基线

**文件：** 无

**依赖：** 无

**步骤：**

1. 查看当前工作树，保留已有第 3、4 章修改。
2. 运行现有完整测试。
3. 记录构建和测试基线，不修改代码。

**验证：** `./gradlew test` 通过。

### T1：统一应用入口的项目根目录

**文件：** `MewCode.java`、`ConfigLoader.java`

**依赖：** T0

**步骤：**

1. 在应用入口将 `user.dir` 规范化为绝对 `Path`。
2. 通过 `projectRoot.resolve(".mewcode/config.yaml")` 定位项目配置。
3. 为 `ConfigLoader` 增加 `Path` 入参入口，保留现有字符串入口兼容行为。
4. 创建 `MewCodeModel` 时传入同一个 `projectRoot`。

**验证：** 配置加载测试覆盖绝对 Path 入口；编译通过；不存在配置时错误仍指向项目根目录下的 `.mewcode/config.yaml`。

### T2：让 TUI 和模型提示共享根目录

**文件：** `MewCodeModel.java`、`PromptBuilder.java`

**依赖：** T1

**步骤：**

1. 给 `MewCodeModel` 增加不可变 `projectRoot` 字段和显式根目录构造路径。
2. 保留现有单参数构造函数，并让它只负责创建默认根目录。
3. 初始化 provider 时调用 `PromptBuilder.buildSystemPrompt(projectRoot)`。
4. banner 使用字段中的根目录，不再重新读取 `user.dir`。
5. 在系统提示中写入实际根目录、相对路径解析规则和禁止猜测用户目录的约束。

**验证：** 新增 `PromptBuilderTest`；`MewCodeModelTest` 断言 banner 和客户端收到同一根目录；提示中包含测试根目录且不包含 `/Users/mew`。

### T3：增加带执行上下文的输入校验入口

**文件：** `Tool.java`、`ToolExecutor.java`

**依赖：** T2

**步骤：**

1. 在 `Tool` 中增加默认的 `validateInput(context, input)` 重载，默认委托旧方法。
2. `ToolExecutor.executeSingle` 调用带上下文的校验方法。
3. 保持校验失败包装为 `ToolResult.error`，不提交工具执行任务。
4. 保留既有测试工具只实现旧 `validateInput(input)` 的兼容性。

**验证：** `ToolExecutorTest` 验证自定义工具能继续工作，并验证上下文校验失败时执行计数为 0。

### T4：统一 PathGuard 的错误和建议信息

**文件：** `PathGuard.java`

**依赖：** T3

**步骤：**

1. 增加只校验绝对性和项目边界的上下文参数辅助方法，不在该阶段检查文件存在性。
2. 相对 `path` 给出 `projectRoot.resolve(relative)` 的建议绝对路径。
3. 相对 `pattern` 给出根目录前缀加 glob 模式的建议。
4. 绝对但越界的路径只报告当前项目根目录和越界原因，不进行静默重写。
5. 保留已有的存在性、权限、符号链接和二进制相关错误语义。

**验证：** 新增 `PathGuardTest` 覆盖相对、合法绝对、越界绝对、`..` 和 glob 模式；断言错误内容包含根目录和调整方向。

### T5：接入五个路径型工具

**文件：** `ReadFileTool.java`、`WriteFileTool.java`、`EditFileTool.java`、`GlobTool.java`、`GrepTool.java`

**依赖：** T4

**步骤：**

1. 五个工具覆盖带上下文的 `validateInput`。
2. 将 `path` 或 `pattern` 的绝对性/边界错误统一委托给 PathGuard。
3. 保留各自的 offset/limit、include、内容、old_string 和其他参数校验。
4. 执行方法继续使用带 `projectRoot` 的最终防线。
5. 不改变工具结果结构、元信息、并发标记和执行语义。

**验证：** 各工具单测验证相对参数返回 `isError=true` 和建议绝对参数；项目内 `.trae/skills/mew-spec/SKILL.md` 的绝对 ReadFile 成功。

### T6：验证 provider 请求中的系统提示

**文件：** `OpenAiClientTest.java`、`AnthropicClientTest.java`

**依赖：** T2

**步骤：**

1. 使用测试根目录构造带真实根目录的 system prompt。
2. 通过本地 HTTP SSE mock 捕获 OpenAI/DeepSeek 兼容请求体。
3. 捕获 Anthropic 请求体。
4. 断言三类协议都收到相同的根目录提示，且没有使用 `user.home`。

**验证：** 两个客户端测试通过；DeepSeek 继续复用 OpenAI 请求适配路径。

### T7：补充 TUI 和 Agent 集成回归

**文件：** `MewCodeModelTest.java`、`ToolExecutorTest.java`

**依赖：** T3、T5、T6

**步骤：**

1. 使用临时项目根目录创建模型和执行器。
2. 模拟模型请求 `.trae/skills/mew-spec/SKILL.md` 的正确绝对路径，断言 ReadFile 成功。
3. 模拟 `/Users/mew/...` 越界路径，断言显示可读错误且不执行读取。
4. 断言工具展示、对话历史和 provider 消息不被路径建议文本污染。

**验证：** `./gradlew test --tests com.mewcode.tui.MewCodeModelTest --tests com.mewcode.tool.ToolExecutorTest` 通过。

### T8：完整构建和 tmux 验收

**文件：** `/checklist.md`、临时测试目录

**依赖：** T1-T7

**步骤：**

1. 运行所有窄范围测试和完整 Gradle 测试。
2. 生成 `build/libs/mewcode.jar`。
3. 在独立临时项目根目录启动本地 OpenAI 兼容 SSE 服务和真实 MewCode。
4. 发送读取 `.trae/skills/mew-spec/SKILL.md` 的请求，检查 system prompt、工具参数和工具结果。
5. 再发送一个越界路径请求，确认拒绝信息带真实根目录。
6. 清理 tmux 会话和临时目录，不修改真实 `.mewcode/config.yaml`。

**验证：** `./gradlew test`、`./gradlew shadowJar`、`git diff --check` 全部通过；按 checklist 记录 tmux 输出。

## 4. 执行顺序

```text
T0 → T1 → T2 → T3 → T4 → T5 → T7 → T8
                 ↘ T6 ↗
```

T6 依赖 T2，可与 T3-T5 的实现准备并行，但必须在 T7 前完成。

## 5. 明确不修改

- `OpenAiClient`/`AnthropicClient` 的工具调用协议解析逻辑不改，仅补充 system prompt 请求断言。
- `AgentTurnCoordinator` 的一次工具结果回灌边界不改。
- 不增加权限确认、MCP、ToolSearch 或 DSML 解析。
