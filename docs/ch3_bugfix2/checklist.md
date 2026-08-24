# 项目根目录路径上下文修复 Checklist

> 状态：已验收
>
> 本清单基于已确认的 [spec.md](/Users/bytedance/IdeaProjects/Mewcode-develop/docs/ch5/spec.md)、[plan.md](/Users/bytedance/IdeaProjects/Mewcode-develop/docs/ch5/plan.md) 和 [task.md](/Users/bytedance/IdeaProjects/Mewcode-develop/docs/ch5/task.md)。每项均有自动化测试输出或可复现的终端证据。

## 1. 使用规则

- 所有路径测试使用临时项目根目录或仓库内已存在的只读文件。
- 测试输入使用绝对路径；相对路径仅作为错误处理场景。
- 检查模型提示、工具执行和 TUI 展示时，不能泄露 API key 或其他敏感配置。
- 工具仍拒绝项目根目录外路径；错误提示增强不能被当成静默路径重写。
- 发现失败项时记录实际输出，修复后重新验证，不直接勾选。

## 2. 文档与范围

- [x] `spec.md` 状态为已确认，范围覆盖所有文件/搜索工具路径上下文。
- [x] `plan.md` 状态为已确认，明确入口单次计算和上下文传递方案。
- [x] `task.md` 状态为已确认，任务依赖和验证方式完整。
- [x] 未引入 Git 根目录自动发现、user.home 路径、权限确认或路径越界放行。
- [x] 未修改 DSML 文本泄漏、工具协议结构或 Agent Loop 边界。

## 3. AC1：项目根目录单一事实来源

- [x] `MewCode` 从启动目录计算规范化绝对 `projectRoot`（验证：入口运行时 banner）。
- [x] 配置加载使用 `projectRoot/.mewcode/config.yaml`，不依赖另一个工作目录（验证：ConfigLoaderTest）。
- [x] `MewCodeModel` 的 banner 使用传入根目录，不重新读取其他目录（验证：MewCodeModelTest）。
- [x] `PromptBuilder`、`ToolExecutor` 和 `ToolExecutionContext` 使用同一个根目录实例值（验证：PromptBuilderTest、ToolExecutorTest）。
- [x] Bash 的工作目录与文件工具根目录一致（验证：BashToolTest 的 `pwd` 断言）。
- [x] 代码中不存在 `/Users/mew`、`user.home` 或额外用户目录推导（验证：源码搜索 + PromptBuilderTest）。

## 4. AC2：系统提示包含真实根目录

- [x] 系统提示包含当前会话真实项目根目录的完整绝对路径（验证：PromptBuilderTest）。
- [x] 系统提示明确要求将用户相对路径基于该根目录解析（验证：PromptBuilderTest）。
- [x] 系统提示明确要求文件和搜索工具最终使用绝对路径/绝对 glob 模式（验证：PromptBuilderTest）。
- [x] `.trae/skills/mew-spec/SKILL.md` 的示例解析结果为 `<projectRoot>/.trae/skills/mew-spec/SKILL.md`（验证：提示文本断言）。
- [x] OpenAI、DeepSeek 兼容协议和 Anthropic 请求都携带同一份根目录提示（验证：OpenAiClientTest、AnthropicClientTest）。

## 5. AC3：路径型工具统一校验

- [x] ReadFile 的 `path` 使用上下文根目录做绝对性和边界校验。
- [x] WriteFile 的 `path` 使用上下文根目录做绝对性和边界校验。
- [x] EditFile 的 `path` 使用上下文根目录做绝对性和边界校验。
- [x] Glob 的 `pattern` 使用上下文根目录做绝对模式和边界校验。
- [x] Grep 的 `path` 使用上下文根目录做绝对性和边界校验。
- [x] 五个工具的现有参数校验、二进制检测、文件状态和搜索规则没有改变。
- [x] Bash 继续固定在上下文根目录执行，不新增路径参数语义。

## 6. AC4：相对路径错误可恢复

- [x] ReadFile 相对 `path` 返回 `isError=true`，包含当前根目录和建议绝对路径。
- [x] WriteFile 相对 `path` 返回 `isError=true`，包含当前根目录和建议绝对路径。
- [x] EditFile 相对 `path` 返回 `isError=true`，包含当前根目录和建议绝对路径。
- [x] Glob 相对 `pattern` 返回 `isError=true`，包含当前根目录和建议绝对模式。
- [x] Grep 相对 `path` 返回 `isError=true`，包含当前根目录和建议绝对路径。
- [x] 建议路径只出现在错误结果文本中，不修改原始 tool-use 参数。
- [x] 相对路径校验失败时不启动实际工具执行线程（验证：ToolExecutorTest 中 WriteFile 未创建文件）。

## 7. AC5：越界路径安全防线

- [x] `/Users/mew/.trae/skills/mew-spec/SKILL.md` 被拒绝，并说明当前项目根目录。
- [x] 项目根目录外的绝对 `path` 不会被重写成项目内路径。
- [x] 项目根目录外的绝对 `pattern` 不会开始遍历。
- [x] `..` 规范化后逃逸项目根目录时仍被拒绝。
- [x] 符号链接逃逸检查仍然生效。
- [x] 错误结果为结构化 `ToolResult`，不会抛出异常中断 Agent 回合。

## 8. AC6：合法绝对路径回归

- [x] `/Users/bytedance/IdeaProjects/Mewcode-develop/.trae/skills/mew-spec/SKILL.md` 可以被 ReadFile 读取。
- [x] 合法绝对路径的行号、offset、limit 行为不变。
- [x] 合法绝对 WriteFile/EditFile 仍遵守先读再写和文件状态保护。
- [x] 合法绝对 Glob/Grep 仍支持既有递归、排除目录、排序和结果上限。
- [x] Bash 命令仍在项目根目录执行。

## 9. AC7：接口、协议和历史隔离

- [x] 旧的 `Tool.validateInput(input)` 实现仍可通过默认上下文重载工作。
- [x] 校验失败仍包装为 `ToolResult.isError=true`，不改变工具结果 metadata 语义。
- [x] provider 工具定义 schema 保持不变。
- [x] 根目录提示进入 system prompt，但不会作为用户消息或 TUI 展示文本追加到 conversation。
- [x] 一次工具结果回灌边界和最终请求不带工具定义的既有行为保持不变。

## 10. AC8：自动化测试

- [x] `./gradlew test --tests com.mewcode.prompt.PromptBuilderTest` 通过。
- [x] `./gradlew test --tests com.mewcode.tool.support.PathGuardTest` 通过。
- [x] `./gradlew test --tests com.mewcode.tool.ToolExecutorTest` 通过。
- [x] `./gradlew test --tests com.mewcode.config.ConfigLoaderTest` 通过。
- [x] `./gradlew test --tests com.mewcode.llm.OpenAiClientTest --tests com.mewcode.llm.AnthropicClientTest` 通过。
- [x] `./gradlew test --tests com.mewcode.tui.MewCodeModelTest` 通过。
- [x] `./gradlew test` 全部通过。
- [x] `./gradlew shadowJar` 成功生成 `build/libs/mewcode.jar`。
- [x] `git diff --check` 无格式错误。

## 11. AC9：tmux 端到端验收

- [x] 使用独立临时项目根目录启动本地 OpenAI 兼容 SSE mock。
- [x] 在 tmux 中启动真实 Java 21 MewCode JAR。
- [x] 请求日志中的 system prompt 包含临时项目根目录绝对路径。
- [x] 发送“读取 `.trae/skills/mew-spec/SKILL.md`”请求时，工具调用参数使用临时根目录下的绝对路径。
- [x] 捕获到合法 ReadFile 工具结果和最终答复。
- [x] 使用 `/Users/mew/...` 越界路径时，捕获到带当前根目录的错误结果。
- [x] 工具展示、路径建议和错误文本没有被追加为额外用户消息。
- [x] tmux 会话和临时目录已清理，不影响真实项目配置。

## 12. 最终门禁

- [x] AC1～AC9 全部通过并有证据。
- [x] 所有失败测试已修复并重新运行，没有已知失败。
- [x] 没有使用 `user.home` 或模型猜测的用户目录作为项目根目录。
- [x] 没有放宽项目外路径访问，也没有静默解析相对路径执行。
- [x] 没有修改 DSML、权限确认、MCP 或连续 Agent Loop。
- [x] checklist 状态更新为“已验收”，并记录实际命令和 tmux 输出。

## 13. 验收记录

### 自动化测试

`./gradlew test --tests com.mewcode.prompt.PromptBuilderTest --tests com.mewcode.tool.support.PathGuardTest --tests com.mewcode.tool.ToolExecutorTest --tests com.mewcode.config.ConfigLoaderTest --tests com.mewcode.tui.MewCodeModelTest`：通过。

`./gradlew test --tests com.mewcode.llm.OpenAiClientTest --tests com.mewcode.llm.AnthropicClientTest`：通过。

`./gradlew test shadowJar`：通过，生成 `build/libs/mewcode.jar`。

`git diff --check`：通过。

### tmux 端到端

使用独立临时根目录 `/private/tmp/mewcode-path-e2e.kkrkjb` 和本地 OpenAI SSE mock，Java 21 TUI banner、请求日志和工具结果均使用该根目录。合法路径场景捕获：

`Read(/private/tmp/mewcode-path-e2e.kkrkjb/.trae/skills/mew-spec/SKILL.md)` → `1 # E2E skill fixture ...` → 最终答复。

越界场景捕获：`Read(/Users/mew/.trae/skills/mew-spec/SKILL.md)` → 带当前项目根目录的 `ToolResult.isError=true` → 最终答复。验收完成后 tmux 会话和临时目录已清理。
