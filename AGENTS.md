# MewCode

MewCode 是使用 Java 21 实现的终端 AI 编程助手。

## 按任务阅读

- 了解项目能力和启动方式：读 [README.md](README.md)。
- 定位代码、梳理运行链路或查找功能文档：读 [架构与文档导航](docs/architecture.md)，再进入相关源码或章节。
- 配置 JDK、构建或本地运行：读 [开发环境准备](docs/development-environment.md)。

## 语言
中文回答，中文注释。

## 测试

开发完功能后，用 tmux 做端到端测试：

1. 在 tmux 中启动 MewCode
2. 输入一段真实的对话请求
3. 观察 MewCode 是否正确调用工具、生成回复
4. 对照 checklist.md 逐项验收
