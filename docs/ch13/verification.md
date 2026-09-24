# Chapter 13 验收记录

日期：2026-09-21

## 自动化检查

以下命令均在当前工作树执行并通过：

```text
./gradlew spotlessCheck test shadowJar
git diff --check
```

- 全量测试：333 个，失败 0，错误 0，跳过 0。
- shadow JAR：[build/libs/mewcode.jar](../../build/libs/mewcode.jar)，约 76 MB。
- 定向回归覆盖 `MarkdownFrontmatter`、Agent Catalog、工具策略、`AgentTool`、`TaskTools`、`SubAgentTaskManager`、`SubAgentRuntime`、TUI 接线和既有测试。
- `SubAgentTaskManagerTest` 验证了未发布前台任务也能被父取消钩子取消；`TaskToolsTest` 验证了手工任务的创建、查询和状态转换。
- `SubAgentTaskManagerTest` 还验证了多轮工具调用只把最后一轮无工具文本作为任务结果，并保证终态通知先于 completion Future 对外可见。

## tmux 真实验收

- tmux 会话：`mewcode-ch13`
- 程序：打包后的 `build/libs/mewcode.jar`，Java 21
- 临时项目：`/private/tmp/mewcode-ch13-e2e/project`
- 临时 OpenAI SSE Provider：`127.0.0.1:18765`，不使用真实凭据
- 伪 Provider 请求日志：`/private/tmp/mewcode-ch13-e2e/requests.jsonl`

已观察到：

1. `delegate explore` 创建定义式 `explore`，子 Agent 返回 `CHILD_DONE`；子请求工具仅含 `ReadFile/Glob/Grep`，没有 `Agent` 或任何 Task 工具。
2. `fork now` 立即返回 `{"status":"async_launched","task_id":"agent-2"}`，随后主对话只收到一次 `task-notification`，摘要为 `FORK_DONE`；Fork 子请求同样没有 Agent/Task 工具。
3. `background now` 立即返回后台任务 ID 并回流完成通知。
4. `auto now` 在配置的 300ms 阈值后显示“子 Agent 已转入后台”，随后收到 `agent-4` 完成通知。
5. `slow now` 配合 `Ctrl+B` 显示手动转后台并继续完成，未重新提交首个子请求。
6. `list tasks` 和 `get fork task` 成功调用 `TaskList`、`TaskGet`，返回任务状态、时间、结果和 token 字段。
7. `TaskUpdate(status=CANCELLED)` 的真实 TUI 调用已走通；最终取消状态由自动化任务句柄测试确认，长 SSE 假服务场景未将其作为取消终态证据。

本次未修改 `docs/ch13/checklist.md` 的复选框；复选框只应在逐项证据需要长期维护时更新。
