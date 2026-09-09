# Hook 生命周期挂钩系统验证记录

> 本文件只记录实际执行结果；Provider 配置和凭据不写入此处。

## T1：开发基线

- 日期：2026-09-08
- 分支：`feature/ch12_hook`
- 工作区基线：已有未跟踪 `.trae/skills/frontend-design/`、`docs/ch12/`；未修改或提交用户已有文件。
- `java -version`：系统启动 JVM 为 OpenJDK 11.0.24；项目 Gradle Daemon 按 `gradle/gradle-daemon-jvm.properties` 使用兼容 Java 21。
- `./gradlew --version`：Gradle 8.14.5，Daemon JVM 显示 Compatible with Java 21。
- `tmux -V`：`tmux 3.7b`。
- `./gradlew test`：通过，`BUILD SUCCESSFUL`，耗时约 14 秒；未发现基线测试失败。

后续任务在对应小节追加实际命令、退出状态、测试报告或 tmux/事件证据；未验证的条目不预填通过。

## 自动化验证

- 日期：2026-09-08
- V1：`./gradlew test --tests com.mewcode.permission.RuleMatcherTest --tests 'com.mewcode.permission.*' --tests com.mewcode.config.PermissionConfigLoaderTest`，退出码 0。
- V2：`./gradlew test --tests com.mewcode.config.HookConfigLoaderTest --tests com.mewcode.hook.HookEngineTest`，退出码 0。
- V3：`./gradlew test --tests com.mewcode.hook.HookActionExecutorTest --tests com.mewcode.tool.BashSandboxIntegrationTest`，退出码 0。
- V4：`./gradlew test --tests com.mewcode.agent.AgentTurnCoordinatorHookTest --tests com.mewcode.agent.PromptRequestFactoryTest --tests com.mewcode.compact.ContextManagerTest`，退出码 0。
- V5：工具、权限、Skill 和协议集成分组命令退出码 0。
- V6：`MewCodeTest`、`MewCodeModelTest`、`SessionManagerTest` 串行执行，退出码 0。
- V7：`HookEngineTest`、`HookActionExecutorTest`、`MewCodeModelTest` 串行执行，退出码 0。
- V8：命令、TUI、Skill 发现分组命令退出码 0。
- 最终：`./gradlew spotlessApply spotlessCheck test shadowJar`，退出码 0；76 个测试套件、318 个测试，失败/错误/跳过均为 0。`git diff --check` 通过，`build/libs/mewcode.jar` 已生成。

V1—V8 首次并行运行时 V6/V7 曾因 Gradle 多进程同时写同一 XML 报告失败；改为串行重跑后通过，不是测试断言失败。

## tmux 真实入口证据

- 日期：2026-09-08；专用会话：`mewcode-ch12`；使用 Java 21 和临时项目/临时用户目录，关闭前未保留会话。
- 启动与发现：真实 JAR 启动成功；`/hooks` 展示项目级和用户级规则、`[once]`、动作类型及绝对来源；`/help hooks` 展示用法。项目级同名规则保留，用户级重复规则输出跳过诊断。
- 输入拦截：真实输入 `HOOK_INPUT_DENY 请回复你好` 被 `UserPromptSubmit` 拒绝；界面保留可编辑输入，临时项目未生成历史或模型请求文件。
- 不重载：运行中把无效 `startup` 规则改为有效配置后再次 `/hooks`，列表不变；正常退出并重启后 `[startup]`、`[shutdown]` 规则出现，证明只在启动时加载。
- 生命周期：临时 `events.log` 实际记录了 `startup`、`session-start`、`session-end`、`shutdown`；第二次启动和正常 Ctrl+C 退出再次记录，顺序为 `session-end` 在 `shutdown` 前。
- 真实模型请求：首个已配置 Provider 实际发出请求，但返回 `Authentication failed. Check api_key.`，因此没有把 ReadFile、工具拒绝、HTTP 通知、异步文件写入等场景伪报为通过。第二个 Provider 未继续尝试，避免未经明确授权把 `sample.txt` 内容外发到另一目的地。

## 当前 checklist 结论

- 已有自动化证据：匹配器与权限兼容、Hook 配置隔离、shell/HTTP/prompt/subagent 动作、一次性与异步状态、工具前后置事件、用户输入拦截、命令发现、安全 stdin 快照、格式/测试/构建。
- 真实 Provider 依赖的 E02、E03、E05、E06、E07 及其对应完整验收保持未通过/未勾选；原因是现有 Provider 认证失败，且不擅自更换或外发测试文件。
