# 架构与文档导航

本文是源码和功能文档的入口。运行行为以当前源码为准；`ch*` 目录记录各阶段的需求、方案和验收，不自动代表当前实现。

## 运行链路

```text
MewCode.run（读取配置、组装组件）
  → Program / MewCodeModel（终端输入、状态与事件展示）
      ├→ AgentTurnCoordinator（请求与对话循环）
      │    ├↔ LlmClient（协议适配与流式事件）
      │    ├→ ToolRegistry / ToolExecutor（工具发现、策略、权限与执行）
      │    └↔ ConversationManager / ContextManager（对话与上下文）
      └↔ SessionManager（会话保存与恢复）
```

一次请求从 `MewCodeModel.startAgentRequest` 进入 `AgentTurnCoordinator.startRun`。协调器收集完整模型响应，执行工具并把调用和结果成对写入对话；`MewCodeModel` 消费 `AgentEvent` 展示进度和回复。`MewCode.run` 在进入 TUI 前加载项目配置、注册内置与 Skill 工具，并准备 MCP 连接。

## 源码入口

| 要处理的问题 | 先看这里 |
| --- | --- |
| 启动、配置、终端交互 | [`MewCode.java`](../src/main/java/com/mewcode/MewCode.java)、[`MewCodeModel.java`](../src/main/java/com/mewcode/tui/MewCodeModel.java)、`config/` |
| 对话循环、提示词、模型协议 | [`AgentTurnCoordinator.java`](../src/main/java/com/mewcode/agent/AgentTurnCoordinator.java)、`prompt/`、`llm/` |
| 工具调用、安全边界 | [`ToolRegistry.java`](../src/main/java/com/mewcode/tool/ToolRegistry.java)、[`ToolExecutor.java`](../src/main/java/com/mewcode/tool/ToolExecutor.java)、`permission/` |
| 上下文、历史和恢复 | `conversation/`、`compact/`、`session/`、`memory/` |
| 扩展与任务分发 | `mcp/`、`skill/`、`hook/`、`subagent/`、`worktree/`、`command/` |

上述目录均位于 `src/main/java/com/mewcode/`；相关测试位于 `src/test/java/com/mewcode/`。