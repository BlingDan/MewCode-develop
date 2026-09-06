# MewCode 代码精简实验记录

> 状态：已完成（2026-09-06）
>
> 基线提交：`fb9dae3`
>
> 日期：2026-09-06

## 基线

| 指标 | 数值 |
|---|---:|
| 生产 Java 文件 | 148 |
| 生产 Java 行数 | 15,203 |
| 测试 Java 文件 | 73 |
| 测试 Java 行数 | 9,853 |
| 自动化测试 | 280 |
| 失败 / 错误 / 跳过 | 0 / 0 / 0 |
| clean test + spotlessCheck + shadowJar | 14.19 秒 |
| Shadow JAR | 80,062,617 字节 |
| Git 跟踪文件 | 1,859,416 字节 |

基线命令：

```bash
./gradlew clean test spotlessCheck shadowJar
find src/main/java -name '*.java' -print0 | xargs -0 wc -l
find src/test/java -name '*.java' -print0 | xargs -0 wc -l
```

## 实验

| 编号 | 假设 | 状态 | 生产行变化 | 测试行变化 | 公开入口变化 | 证据 |
|---|---|---|---:|---:|---:|---|
| A1 | 删除三个零调用方法不改变行为 | 成功 | -9 | 0 | -3 | 专项测试及全量门禁通过 |
| A2 | 统一工具格式方法拼写可删除同义入口 | 成功 | -4 | 0 | 0 | 旧拼写检索为空，schema 测试通过 |
| B1 | 测试替身可全部使用结构化请求 | 成功 | 0 | +24 | 0 | 相关测试改走正式入口，全量测试通过 |
| B2 | Provider 只需 `PromptRequest` 入口 | 成功 | -137 | 0 | -15 | 旧入口检索为空，全量门禁通过 |
| C1 | `AgentRun` 可替代旧阻塞队列桥接 | 成功 | 包含于 C 合计 | 包含于 C/D 合计 | -1 | Agent 专项及全量门禁通过 |
| C2 | 正式 Agent 装配可替代旧构造组合 | 成功 | C 合计 -320 | 包含于 C/D 合计 | -11 | 仅剩一个 Coordinator 构造器 |
| D1 | 权限入口可覆盖全部工具执行行为 | 成功 | -128 | C/D 合计 -62 | -7 | 工具、权限、Agent 与全量门禁通过 |
| E1 | 启动对象可直接注入 TUI，避免重复初始化 | 成功 | 包含于 E 合计 | 包含于 E 合计 | -1 | 启动/TUI/Skill/MCP 专项通过 |
| E2 | 测试 fixture 可替代 TUI 构造器排列组合 | 成功 | E 合计 -136 | E 合计 +34 | -8 | MewCodeModel 仅剩一个构造器 |
| F | 已验证内部不变量可替代重复兜底 | 成功 | -267 | +46 | -20 | 相关专项及完整 build 通过 |

## 保留的边界

以下代码默认保留，只有出现更强的等价证据才重新评估：

- 权限规则、危险命令、路径和 OS Shell 沙箱。
- Provider、MCP、脚本及模型输出校验与凭据脱敏。
- Session JSONL 坏行恢复和工具调用配对。
- Memory 原子提交与失败回滚。
- Context 外置、压缩、熔断和超限恢复。
- 取消、超时和资源关闭。

## A 批记录

- 删除 `ConversationManager.addToolResults`、`ToolRegistry.markDiscovered` 和同义转发入口。
- `toAPIFormate*` 已统一为 `toApiFormat*`，未保留拼写兼容层。
- 生产 Java：15,203 → 15,190（-13）。
- 测试 Java：9,853 → 9,853（0）。
- 公开方法：净减少 3 个。
- 验证：Conversation/Registry 专项测试通过；`test spotlessCheck shadowJar` 和
  `git diff --check` 通过。

## B 批记录

- `LlmClient` 由七个默认/兼容方法收敛为一个 `openStream(PromptRequest)`。
- Anthropic/OpenAI 删除消息列表、Conversation、字符串 Prompt 和队列入口。
- Provider 构造器、工厂和 Router 不再保存或传入默认 System Prompt。
- `PromptRequest.flattenedSystemPrompt` 已删除；测试直接检查 `systemSegments`。
- 生产 Java：15,190 → 15,053（-137）。
- 测试 Java：9,853 → 9,877（+24）；增加内容用于显式构造正式请求，批次总代码净减少 113 行。
- 公开方法：净减少 15 个，累计净减少 18 个。
- 验证：Provider/协议/压缩/Memory/Session/Router 专项测试通过；280 个全量测试、
  `spotlessCheck`、`shadowJar` 和 `git diff --check` 通过。

## C/D 批记录

- `AgentTurnCoordinator` 只剩一个结构化构造器和 `AgentRun` 入口。
- 删除阻塞队列桥接、字符串 Prompt、缺失 Context/权限运行路径及旧 Prompt Builder API。
- `ToolExecutor` 只剩带 `ToolPolicy + PermissionContext` 的单次/批量入口。
- 测试新增一个 `src/test` 运行夹具，替代散落的生产构造器排列组合。
- 生产 Java：15,053 → 14,605（-448，其中 C -320、D -128）。
- 测试 Java：9,877 → 9,815（-62），测试文件增加 1 个。
- 公开方法/构造器：C 减少 12 个，D 减少 7 个；累计净减少 37 个。
- 验证：Agent、Prompt、Skill、TUI、ToolExecutor、Permission 专项测试通过；280 个
  全量测试、`spotlessCheck`、`shadowJar` 和 `git diff --check` 通过。

## E 批记录

- `MewCode` 构造一次 Catalog、Registry 和 MCP Manager，并直接注入 TUI。
- 删除 `MewCodeModel.useSkillBootstrap`、模型内重复 Catalog 加载/刷新和空 Registry 分支。
- `MewCodeModel` 构造器由 9 个收敛为 1 个。
- 生产 Java：14,605 → 14,469（-136）。
- 测试 Java：9,815 → 9,849（+34）；测试工厂显式复现正式启动装配，批次总代码净减少 102 行。
- 公开方法/构造器：净减少 9 个；累计净减少 46 个。
- 验证：入口、TUI、Command、Skill、MCP 专项测试通过；280 个全量测试、
  `spotlessCheck`、`shadowJar` 和 `git diff --check` 通过。

## F 批记录

- `Tool` 参数校验由无上下文/有上下文双接口收敛为唯一的上下文接口，删除五个路径工具中的重复校验实现。
- 删除仅供历史测试使用的事件构造器、usage 便捷入口、Coordinator 访问器和底层队列访问器。
- 删除 `ToolResult.withMetadata`、`FileStateCache.clear`、`SearchSupport.copySkipDirs` 等零调用方法。
- 删除未被模型消费的 MouseMessage，以及 Program/Style 中未调用的消息、高度、背景色和 padding 骨架。
- 删除 ToolExecutor 在唯一装配完成后不可达的“权限运行时缺失”分支，构造时一次性校验依赖。
- 删除两条已经与实际行为不符的 TODO 注释。
- 生产 Java：14,469 → 14,202（-267），生产文件 148 → 147。
- 测试 Java：9,849 → 9,895（+46）；显式事件字段和正式 Tool 校验上下文增加了测试文本，批次总代码净减少 221 行。
- 公开方法/构造器：净减少 20 个；累计文本口径从 624 → 558（-66）。
- 验证：相关专项测试和 280 个全量测试通过，`spotlessCheck`、`shadowJar`、
  `git diff --check` 通过。

## 放弃与保留的候选

- 未合并 `MewCodeModel` 普通请求、fork 和 Session 切换的状态清理。它们的生命周期和历史提交语义不同，抽取后需要更多状态参数，不能形成可靠的净精简。
- 未删除配置、YAML、JSONL、Provider、MCP、脚本、工具参数的空值与格式校验。这些值跨越不可信边界，删除会降低故障隔离能力。
- 未删除权限请求空响应、Provider 空事件、工具空结果和资源关闭保护。这些分支覆盖取消、断连或第三方实现异常。
- 未拆分 `MewCodeModel` 或为构造参数新增容器类。拆分类会增加生产文件和概念数，不符合本轮消融目标。
- A 批首次全量门禁仅发现 Spotless 换行问题；格式化后原实验通过，因此没有失败并撤销的代码消融。

## 历史 Checklist 复核

已检查 `docs/ch2` 至 `docs/ch11` 的 checklist。用户可观察行为仍由全量测试和 tmux 场景覆盖。以下历史条目描述的是本轮明确删除的内部兼容 API，已由当前 Spec 取代，历史文档本身未修改：

- `docs/ch3/checklist.md` 的 `toAPIFormate` 拼写。
- `docs/ch3_bugfix2/checklist.md` 的旧 `Tool.validateInput(input)` 重载。
- `docs/ch5/checklist.md` 的字符串式 `LlmClient` 兼容入口。
- `docs/ch3_bugfix1/checklist.md` 中旧 Agent 内部 `BlockingQueue` 约束；Provider 内部队列和 TUI `Command.PrintLine` 行为仍保留。

## tmux 验收

使用 Java 21 和本轮 `build/libs/mewcode.jar` 在 tmux 中完成：

1. Claude Provider 启动后真实请求返回脱敏的鉴权失败，未显示 Key；随后选择 DeepSeek 继续验收。
2. 请求读取 `README.md` 第一行，观察到 `ReadFile` 调用、结果 `# MewCode` 和最终回复。
3. 切换 Plan Mode 后要求改写 `hello.txt`，Agent 只读取并输出计划；文件 SHA-256 前后均为
   `4355a46b19d348dc2f57c046f8ef63d4538ebb936000f3c9ee954a27460dd865`。
4. `/test` shared Skill 只调用其白名单中的 Bash/Glob；首次 Bash 受 macOS sandbox 环境限制失败，后续权限提示可拒绝。
5. 在上述权限等待中按 Esc，当前运行取消；随后发送普通请求得到 `CANCEL_OK`，证明取消后可继续对话。
6. `/review` fork Skill 使用 Glob/ReadFile 完成检查，主 Session JSONL 最后只新增 slash 用户消息和最终摘要，两条记录均无 fork 内部工具消息。
7. `/clear` 后新 Session 只包含 `CLEAR_OK` 的 user/assistant 两条消息。
8. 从 tmux shell 启动后按 Ctrl+C 退出，pane 状态为 `zsh`、`pane_dead=0`。
9. 本轮生成的四个测试 Session 已清理，`hello.txt` 未变化。

## 构建漏验修正

初次验收只运行了 `clean test spotlessCheck shadowJar`，没有运行完整的 Gradle `build`，因此错误地报告了构建通过。用户指出后补做并复现了两类问题：

1. `spotlessJavaCheck` 检出 8 个文件的格式问题。
2. 应用格式器后，10 个测试替身仍使用旧的 `Tool.validateInput(input)` 签名，导致 `compileTestJava` 出现 19 个错误。

修复方式：

- 应用仓库 Spotless 格式器。
- 将全部测试 Tool 替身显式迁移到 `validateInput(ToolExecutionContext, input)`。
- 在格式化后静态确认旧测试签名为零命中。

最终验证：

- `./gradlew clean build` 成功，17 个任务完成。
- 未再执行格式化，直接运行 `./gradlew build` 再次成功。
- build 包含 `compileJava`、`compileTestJava`、280 个测试、`spotlessCheck`、普通/Shadow JAR、启动脚本和分发包。
- 使用新 build 产物在 tmux 选择 DeepSeek，真实请求返回 `BUILD_OK`；Ctrl+C 后 pane 为 `zsh`、`pane_dead=0`。

## 最终指标

| 指标 | 基线 | 最终 | 变化 |
|---|---:|---:|---:|
| 生产 Java 文件 | 148 | 147 | -1 |
| 生产 Java 行数 | 15,203 | 14,202 | -1,001 |
| 测试 Java 文件 | 73 | 74 | +1 |
| 测试 Java 行数 | 9,853 | 9,895 | +42 |
| 生产 + 测试 Java 行数 | 25,056 | 24,097 | -959 |
| 公开可调用声明文本口径 | 624 | 558 | -66 |
| 自动化测试 | 280 | 280 | 0 |
| 失败 / 错误 / 跳过 | 0 / 0 / 0 | 0 / 0 / 0 | 0 |
| clean 全量门禁 | 14.19 秒 | 13.23 秒 | -0.96 秒（仅观察） |
| clean Gradle build | 未记录 | 21 秒 | 17 个任务成功 |
| Shadow JAR | 80,062,617 | 80,048,598 | -14,019 字节 |

最终命令：

```bash
./gradlew clean build
./gradlew build
git diff --check
```
