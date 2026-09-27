# Git Worktree 验收报告

2026-09-27，本机 macOS / Java 21.0.12.1 / Git 2.50.1 / tmux 3.7c。已完成 T1～T67；[Checklist](checklist.md) 53/53 项通过，覆盖本机执行与平台参数检查。全量编译、Spotless、427 个测试和 shadowJar 均通过，0 失败、错误、跳过。实际测试方法与逐项证据见 [验收索引](acceptance-evidence.json) 和 [构建索引](verification.json)。

最终 JAR 构建于 12:55:57，SHA-256 为 `588517f7d3e3468cbc26125549c5072ebf07c9872aebe1cd6fb39c87f630856c`。后续固定这份产物，在独立 Git 夹具中用真实 `deepseek-v4-flash` 完成以下四场景：

| 场景 | 实际结果 | 证据 |
|------|----------|------|
| E01 并行修改 | 主 Edit 结果时间 1790485437；下一次 TaskGet 时间 1790485438 仍 running。父/子最终为 parent-change / child-change；子返回保留路径、分支和未知进程原因。没有合并。 | [实际任务状态](evidence/final-task-states.json)、[终端](evidence/tmux-final-parallel.txt) |
| E02 手动进入/KEEP | 新 manual-final 从 baseline 读写为 manual-change，KEEP 后主目录读到 parent-change。模型首次 exit 错带 name，被拒后自行纠正。 | [最终对话](evidence/conversation-final.jsonl)、[终端](evidence/tmux-final-main.txt) |
| E03 只读清理 | wt-reader 只用 ReadFile 读取 baseline；完成回流已清理，目录和分支均不存在。 | [终端](evidence/tmux-final-reader.txt)、[磁盘/引用核对](evidence/final-reader-state.json) |
| E04 成果保护 | 自然语言 exit/delete 默认拒绝，manual-final 的修改和 cwd 保留；之后 KEEP 成功。明确 CLI --discard 仅删除 discard-final，目录、分支、现场均消失。模型字段和同名旧授权的负例通过。 | [终端](evidence/tmux-final-main.txt)、[可信丢弃](evidence/tmux-final-discard-resume.txt)、[删除后状态](evidence/final-discard-after.json) |

另已实际验证 `/clear` 后恢复带资源 UUID 的会话、无 Git/非 Git 目录的普通文件对话，以及 Worktree 请求明确失败。缺失身份或同名重建不能误恢复。测试应用和三个 tmux 测试会话均已正常退出，观测及核对命令退出 0；Java 进程的单独退出码未留存。[最终清点](evidence/final-state.json) 保留四个有成果副本和独立分支，均位于 `/private/tmp/mewcode-ch14-e2e-yygckul6/.mewcode/worktrees/`：manual-demo、manual-final、temp-agent-1-c334434d、temp-agent-2-5dbd5d9b。未推送、合并或同步。

本次限制：Linux 的挂载和 PID 隔离只有参数检查，未实测执行。macOS 无法证明任意外部程序的全部后代退出，因此执行不透明 Bash、Shell Hook 或脚本后保守保留工作树，明确丢弃也不能越过未知状态保护；仅使用文件工具且安全状态已知的无成果角色可以自动清理。真实 Provider 的异步 Memory 更新多次安全失败并保留旧笔记/索引，本次未取得真实 Memory 内容更新成功证据；固定目录、只读子索引和既有 Memory 自动化回归通过。

验收中曾重建正在运行的 JAR，造成一次 ZIP 读取错误；已正常停止并固定最终产物重跑四场景，最终轮次未再出现该错误。原始异常保留在 [记录](evidence/tmux-discard-pre-final.txt)，不计作最终通过依据。

使用与配置见 [README](README.md) 和 [不会自动启用的配置示例](../../.mewcode/config.yaml.example)。用户原有 `.idea/gradle.xml` 改动及真实配置保留，交付证据已扫描实际凭据，没有发现泄漏。
