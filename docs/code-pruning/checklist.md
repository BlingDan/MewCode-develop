# MewCode 全仓代码精简 Checklist

> 状态：已验收（2026-09-06）
>
> 上游文档：[spec.md](./spec.md) · [plan.md](./plan.md) · [task.md](./task.md)

## 基线

- [x] 分支为 `Optimize/less_code`，基线提交为 `fb9dae3`。
- [x] 生产 Java 为 148 个文件、15,203 行。
- [x] 测试 Java 为 73 个文件、9,853 行。
- [x] 基线为 280 个测试，0 失败、0 错误、0 跳过。
- [x] `./gradlew clean test spotlessCheck shadowJar` 成功，耗时 14.19 秒。
- [x] 基线 Shadow JAR 为 80,062,617 字节。

## A：零调用与名称兼容

- [x] `ConversationManager.addToolResults` 已删除且全仓无调用。
- [x] `ToolRegistry.markDiscovered` 已删除且全仓无调用。
- [x] 工具 API 格式方法只保留正确拼写的 `toApiFormat*`。
- [x] Anthropic/OpenAI 工具 schema、顺序和可见性测试通过。
- [x] A 批生产代码及总代码净减少。

## B：Provider 单一协议

- [x] `LlmClient` 只声明 `openStream(PromptRequest)`。
- [x] 生产代码不存在 Provider `stream(...)` 兼容入口。
- [x] 生产代码不存在基于 `ConversationManager`、消息列表或字符串 Prompt 的 Provider 入口。
- [x] Provider 构造器和 Router 不保存默认 System Prompt。
- [x] 结构化 system segments、Reminder、工具和 history 映射保持不变。
- [x] Thinking、usage、取消和错误分类测试通过。
- [x] 两种 Provider 凭据脱敏和单请求不重试行为保持不变。
- [x] B 批生产代码及总代码净减少。

## C：Agent 单一运行路径

- [x] `AgentTurnCoordinator.start` 和事件桥接线程已删除。
- [x] Agent 只通过 `AgentRun` 暴露事件与取消。
- [x] `PromptRequestFactory` 是唯一 Prompt 组装入口。
- [x] Coordinator 不再保留字符串 Prompt 或无 Context 的旧构造路径。
- [x] Coordinator 构造器显著收敛。
- [x] 多轮工具调用、最大轮次和未知工具熔断行为不变。
- [x] 自动/手动/紧急压缩行为不变。
- [x] Provider 回退、Skill 生命周期和取消收口行为不变。
- [x] C 批生产代码及总代码净减少。

## D：工具执行单一路径

- [x] `ToolExecutor` 只保留带 `ToolPolicy + PermissionContext` 的执行入口。
- [x] 无权限单次/批量算法及重复安全分组辅助已删除。
- [x] 未知工具和参数错误仍返回结构化结果。
- [x] 权限 DENY、ASK、会话授权和永久授权失败行为不变。
- [x] Plan Mode、路径越界和危险命令仍 Fail-Closed。
- [x] 并发安全批次、串行屏障和结果顺序不变。
- [x] 重复 ID、超时、取消和执行异常测试通过。
- [x] D 批生产代码及总代码净减少。

## E：启动与 TUI 装配

- [x] Skill Catalog、ToolRegistry 和 McpManager 在正式启动中只装配一次。
- [x] `MewCodeModel.useSkillBootstrap` 已删除。
- [x] `MewCodeModel` 不再先加载 Catalog 再覆盖。
- [x] `MewCodeModel` 只保留必要的正式与测试构造入口。
- [x] 单/多 Provider 启动和切换行为不变。
- [x] MCP 同步依赖发现和无关 MCP 后台初始化行为不变。
- [x] shared/fork、`/clear`、Session resume 和资源关闭行为不变。
- [x] E 批生产代码及总代码净减少。

## F：内部不变量与测试样板

- [x] 已重新审计内部 `null`、空集合复制、不可达异常和兼容入口。
- [x] 只删除唯一上游已保证的不变量检查。
- [x] 测试 fixture 合并后独立行为与失败样本没有减少。
- [x] 配置、磁盘、Provider、MCP、脚本和工具参数校验保留。
- [x] 权限、路径、Shell、Session、Memory 和 Context 恢复防线保留。
- [x] 每个放弃的消融候选都记录失败原因。

## 规模与静态门禁

- [x] 生产 Java 净减少至少 400 行：实际减少 1,001 行。
- [x] 生产加测试 Java 总行数净减少：实际减少 959 行。
- [x] 公开方法/构造器净减少至少 20 个：文本口径实际减少 66 个。
- [x] 没有新增生产 Java 文件，删除 1 个无效消息类型文件。
- [x] 没有新增生产依赖。
- [x] 没有通过纯格式化、搬包或删除文档制造指标。
- [x] 旧 API 名和“兼容旧调用”实现均已按批准范围消失。
- [x] `git diff --check` 通过。

## 自动化回归

- [x] 全量 280 个测试全部通过。
- [x] 测试失败为 0。
- [x] 测试错误为 0。
- [x] 测试跳过为 0。
- [x] 因接口收敛改写的测试保留原行为断言。
- [x] `spotlessCheck` 通过。
- [x] `shadowJar` 通过且 JAR 已由 Java 21 启动。
- [x] `./gradlew clean build` 与后续直接执行的 `./gradlew build` 均通过。
- [x] `docs/ch2` 至 `docs/ch11` 的既有用户可观察行为未被删除；三个历史内部兼容条目由本轮 Spec 明确取代。

## tmux 端到端

- [x] 在 tmux 中使用 Java 21 启动本轮构建的 MewCode JAR。
- [x] DeepSeek 真实对话调用 ReadFile 读取 `README.md` 并返回 `# MewCode`。
- [x] Plan Mode 中写文件请求被拒绝，`hello.txt` 的 SHA-256 前后相同。
- [x] `/test` shared Skill 只使用 Bash/Glob 等声明白名单工具。
- [x] `/review` fork Skill 完成，主 Session 只新增 slash 用户消息和最终摘要。
- [x] 权限等待中按 Esc 取消后，下一请求成功返回 `CANCEL_OK`。
- [x] `/clear` 后新 Session 只包含 `CLEAR_OK` 一轮，无旧状态泄漏。
- [x] 退出后 tmux pane 返回 `zsh`，`pane_dead=0`。
- [x] 修复后新 build 产物在 tmux 真实返回 `BUILD_OK`。

## 最终报告

- [x] `experiment-log.md` 列出每个成功实验。
- [x] `experiment-log.md` 说明没有失败并撤销的代码实验，并列出主动放弃的候选。
- [x] 报告包含基线、最终值和净变化。
- [x] 报告说明保留的防御代码及其边界依据。
- [x] AC1-AC11 均有直接证据。
