# MewCode

MewCode 是一个使用 Java 构建的终端 AI 编程助手，面向希望在终端中让大语言模型协助阅读、搜索、修改和验证代码的开发者。它以当前工作目录作为项目边界，通过统一的 Agent Loop 协调模型、工具、会话上下文和权限控制。

## 核心能力

- 终端 TUI：支持流式输出、Markdown 渲染、Provider 选择、Plan Mode 和 Execute Mode。
- 多模型接入：支持 Anthropic、OpenAI Chat Completions，以及通过兼容 `base_url` 接入的 DeepSeek 和其他服务。
- Agent Loop：支持多轮对话、工具调用、取消当前任务、会话历史和 Token 用量统计。
- 内置工具：提供 `ReadFile`、`WriteFile`、`EditFile`、`Glob`、`Grep` 和 `Bash`，覆盖代码阅读、搜索、修改与命令执行。
- MCP 扩展：支持通过 stdio 或 Streamable HTTP 连接 MCP Server，并按需发现 MCP 工具。
- 安全边界：提供项目路径限制、权限确认、权限规则和操作系统级 Shell 沙箱。
- 上下文管理：在接近上下文窗口上限时自动压缩历史，并外置过大的工具结果。

## Roadmap / TODO

以下能力属于后续规划，当前尚未实现：

- [ ] 记忆系统——跨会话的 Agent 记忆
- [ ] Slash Command——内置命令框架
- [ ] Skill 系统——可复用的技能包
- [ ] Hook 系统——生命周期钩子与自动化
- [ ] SubAgent——子 Agent 与任务分发
- [ ] Worktree——Git Worktree 并行开发
- [ ] Agent Teams——从一次性子任务到长期协作

## 工作方式

```text
用户输入
   ↓
终端 TUI → Agent Loop → LLM Provider
                     ↓
        Tool Registry / Tool Executor / MCP
                     ↓
          权限检查、沙箱执行、结果回写
```

代码主要按职责划分在以下包中：

- `tui`：终端交互、渲染和输入处理
- `agent`：Agent Loop、事件流和任务取消
- `llm`：Anthropic、OpenAI 兼容协议适配
- `tool`：工具注册、执行和内置工具
- `permission`：权限规则、路径边界和 Shell 沙箱
- `mcp`：MCP Server 连接和工具封装
- `conversation`、`compact`：会话历史和上下文管理
- `config`：项目配置加载与校验

## 开发环境

- JDK 21（发行版和安装路径可因电脑而异）
- 使用仓库自带的 Gradle Wrapper，无需单独安装 Gradle

环境准备和多电脑协作说明见：[开发环境准备](docs/development-environment.md)。

## 构建与测试

```bash
./gradlew build
```

常用命令：

```bash
./gradlew spotlessApply  # 自动格式化
./gradlew spotlessCheck  # 检查格式
./gradlew test           # 运行测试
./gradlew shadowJar      # 生成可运行 JAR
```

## 配置

启动前需要在当前项目目录创建 `.mewcode/config.yaml`，至少配置一个 LLM Provider。该文件还可配置 Agent Loop、权限模式和项目级 MCP Server；真实 API Key 不要提交到版本库。

## 运行

```bash
java -jar build/libs/mewcode.jar
```
