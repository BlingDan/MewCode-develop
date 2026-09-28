# MewCode

MewCode 是一个使用 Java 构建的终端 AI 编程助手，面向希望在终端中让大语言模型协助阅读、搜索、修改和验证代码的开发者。启动时以当前工作目录确定项目根目录，通过统一的 Agent Loop 协调模型、工具、会话上下文和权限控制。

## 核心能力

- 终端 TUI：支持流式输出、Markdown 渲染、Provider 选择、Plan Mode 和 Execute Mode。
- 多模型接入：支持 Anthropic、OpenAI Chat Completions，以及通过兼容 `base_url` 接入的 DeepSeek 和其他服务。
- Agent Loop：支持多轮对话、工具调用、取消当前任务、会话历史和 Token 用量统计。
- 内置工具：提供 `ReadFile`、`WriteFile`、`EditFile`、`Glob`、`Grep` 和 `Bash`，覆盖代码阅读、搜索、修改与命令执行。
- MCP 扩展：支持通过 stdio 或 Streamable HTTP 连接 MCP Server，并按需发现 MCP 工具。
- 安全边界：提供项目路径限制、权限确认、权限规则和操作系统级 Shell 沙箱。
- 上下文管理：在接近上下文窗口上限时自动压缩历史，并外置过大的工具结果。

## 能力状态

- [x] 记忆系统——跨会话的 Agent 记忆
- [x] Slash Command——内置命令框架
- [x] Skill 系统——可复用的技能包
- [x] Hook 系统——生命周期钩子与自动化
- [x] SubAgent——子 Agent 与任务分发
- [x] Worktree——Git Worktree 并行开发
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

源码入口、模块职责和各章节文档见 [架构与文档导航](docs/architecture.md)。

## 开发环境

- JDK 21（发行版和安装路径可因电脑而异）
- 使用仓库自带的 Gradle Wrapper，无需单独安装 Gradle

环境准备、配置与启动步骤见 [开发环境准备](docs/development-environment.md)。

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

Hook 配置可参考 `.mewcode/hooks.yaml.example`，按需复制为 `.mewcode/hooks.yaml` 后重启生效。也可以使用用户级 `~/.mewcode/hooks.yaml`；项目级规则会先于用户级规则加载。启动后输入 `/hooks` 可查看已加载规则。

## 运行

```bash
java -jar build/libs/mewcode.jar
```
