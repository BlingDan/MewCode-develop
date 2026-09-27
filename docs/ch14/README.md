# Git Worktree 使用与验证

主会话可以手动管理工作树；角色声明 `isolation: worktree` 时，每次派发自动取得自己的目录、分支、缓存、项目指令与只读记忆。子任务从派发时冻结的已提交 HEAD 开始，不复制父目录未提交的源码。

## 角色与目录

在项目的 `.mewcode/agents/wt-editor.md` 中声明：

```markdown
---
name: wt-editor
description: 在独立目录中修改分配的文件
isolation: worktree
tools: [ReadFile, EditFile, WriteFile, Bash]
---
只修改分配的文件。使用自己的绝对路径，编辑前重新读取。
完成后报告修改结果，不自动提交、推送或合并。
```

没有 `isolation` 的旧角色保持原行为；不识别的值只使对应角色无效。后台角色的权限仍遵守现有后台限制，隔离不会自动批准写入。测试写角色可显式使用既有的 `permissionMode: dontAsk`；父源码、管理记录和共享目标的硬隔离检查仍然生效。

目录在 `.mewcode/worktrees/<名称>/`，记录在 `.mewcode/worktree-state/`，会自动精确加入本地 Git exclude。名称限 1～64 个 ASCII 字符，允许字母、数字、点、横线、下划线和斜杠；拒绝绝对路径、点段、空段及非法分支名。斜杠转换为 `+`，例如 `fix/parser` 存为 `fix+parser`，分支为 `codex/worktree/fix+parser/task`。

## 主会话操作

```text
/worktree create manual-demo
/worktree enter manual-demo
/worktree list
/worktree exit
/worktree delete manual-demo
```

`create` 只创建或只读恢复；`enter` 才切换当前会话。不能嵌套进入。`exit` 默认 KEEP，恢复进入前目录。也可以用自然语言要求主 Agent 调用 Worktree 工具；终端目录行和 `/status` 会显示当前路径与分支。

默认删除会保护已跟踪修改、未跟踪文件、所有相对创建基线的新提交，以及占用、归属、远端引用或进程状态未知的资源。没有已知本地远端引用也会保留，不会自动 fetch。新提交即使已被远端引用包含仍然保留。

只有用户直接输入且目标明确的命令可以授权丢弃已知成果：

```text
/worktree delete manual-demo --discard
/worktree exit --delete --discard
```

这两条会丢弃指定资源的修改和新提交，使用前应先检查成果。授权绑定当时的资源身份；同名重建不能复用。模型传 `discardChanges: true`、普通权限 bypass 和后台清理均不获得这项授权。未知安全状态仍拒绝删除。

## 初始化与执行范围

可选配置及默认值见 [config.yaml.example](../../.mewcode/config.yaml.example)。示例不会生效，启用时将所需字段加入自己的本地配置。

创建会复制存在的 `.mewcode/config.yaml`、`permissions.local.yaml` 和 `hooks.yaml`，按权限复制 `required_files`，为显式配置的依赖目录建立只读链接。必要文件缺失或路径不安全时不启动任务。`.worktreeinclude` 使用 Git ignore 规则，仅从 Git 已忽略的文件中补入命中项，支持否定规则，不覆盖已检出的源码、Git 元数据、会话、记忆或其他工作树。

已有 Git `worktreeConfig` 扩展时，只设置子工作树的 Hooks；未启用时不迁移仓库配置，MewCode 的 Bash、Skill 脚本和 Shell Hook 通过当前资源的环境覆盖使用原 Hooks 路径。外部终端进程仍使用原 Git 配置。

不改变 JVM 的 cwd。每次工具调用捕获显式目录，贯穿权限、校验、执行、Hook 和结果保存。KEEP 后正在运行的旧调用仍用旧目录并持有使用权；后续调用取得新目录。主会话历史与上下文预算持续保留。隔离脚本在子目录执行，资产路径通过 `MEWCODE_SKILL_DIR` 提供。

工作树中的 MCP 和 ToolSearch 不可用，因为其服务端 cwd 无法验证。子 Agent 没有手动 Worktree 入口。Bash 沙箱只允许写自己的副本、所属 Git 管理目录和引用；父源码、其他副本、共享配置与共享依赖受保护。

临时子任务结束后，先停止工具和 Hook，再确定保留或删除，最后发布完成结果。修改或安全状态未知时，返回保留路径、分支和原因。后台默认每 30 分钟扫描一次，最后使用后 24 小时才过期；只处理可验证的系统临时资源，手动工作树不会被扫描删除。不执行 merge、代码同步、自动提交、push 或依赖安装。

macOS 的进程树观测无法证明任意程序的全部后代都已退出。因此工作树中执行 Bash、Shell Hook 或 Skill 脚本后会保守标记进程状态未知并保留目录，即使命令已返回；只豁免完整的 `true`、`false`、`pwd`、`:` 无参数命令。明确 `--discard` 也不能越过这项保护。仅使用文件工具且安全检查已知的无成果子任务仍会自动清理。Linux 使用独立 PID 命名空间收口后代进程，本次 macOS 验收只检查 Linux 参数，未实测 Linux 执行。

保存现场同时绑定资源 UUID，防止旧会话进入同名重建的工作树。开发过程中缺少该字段的旧现场记录会拒绝自动恢复；目录和成果保留，可重新手动进入。

## 真实 tmux 验收

使用 Java 21、Git 和 tmux，在项目根目录构建：

```sh
./gradlew compileJava compileTestJava spotlessCheck test shadowJar
```

在独立临时 Git 仓库放入已提交的 `notes.txt=baseline` 和两个隔离角色 `wt-editor`、`wt-reader`。读角色仅配置 `ReadFile`。忽略本地配置、权限、Hook、sessions、memory 和测试用户目录；安全复制可用的真实 Provider 配置，不把凭据打印到终端或证据。用下列本地引用建立安全清理正例，不连接远端：

```sh
git update-ref refs/remotes/fixture/main HEAD
```

在夹具目录启动，替换下列三个实际绝对路径；测试使用独立 `user.home`，保留真实用户的记忆与配置：

```sh
tmux new-session -d -s mew-worktree-test -c /absolute/fixture \
  'java -Duser.home=/absolute/test-home -jar /Users/li/code/MewCode-develop/build/libs/mewcode.jar'
tmux attach -t mew-worktree-test
```

按 [checklist.md](checklist.md) 的 E01～E04 输入真实对话并正常响应权限请求。以工具事件时间、实际文件内容和 Git 分支确认并行隔离、KEEP、只读自动清理和脏目录拒绝删除。`capture-pane` 前确认没有凭据；结束后正常退出 MewCode，保留含成果的副本。各项实际证据与平台限制记录在 checklist 和 progress 中，单元测试不能替代真实对话。
