# MewCode Git Worktree Tasks

> 状态：已确认；67 项任务拆解已获用户批准，进入验收设计阶段。尚未开始实现。
>
> 前置文档：[已确认的 Spec](spec.md)、[已确认的 Plan](plan.md)。任务审批后生成 checklist，四份文档全部批准后才开始实现。

## 执行约定

- 每项聚焦一个行为或接入点，按 2～5 分钟的主动修改单元拆分；构建、外部进程和真实模型对话的等待时间另计，不据此承诺总工期。
- 非平凡行为先补最小失败测试，再实现并运行该项定向测试。下文的验证均是预期结果，当前没有执行或标记通过。
- 默认在当前任务内按顺序执行，不要求新增并行编排。声明依赖用于说明必要前置条件，不能跳过安全边界后直接接通运行入口。
- 所有文件操作使用 explicit cwd，不改变进程目录。不新增依赖，不自动提交、推送、fetch、合并、同步或恢复子任务执行。
- 保护现有无关变更，包括 `.idea/gradle.xml`。不修改实际配置、用户角色或 AGENTS.md；运行夹具使用独立临时 Git 仓库和测试凭据。
- 验证失败先检查当前项；不得将失败、未知状态或未结束进程记作零变更、已删除或已完成。

## 文件清单

本节及各任务中，源码路径相对 `src/main/java/com/mewcode/`，测试路径相对 `src/test/java/com/mewcode/`；配置、构建及文档路径相对仓库根目录。每个文件只在所属行为任务中添加必要改动，不先创建整套空实现。

### 新增源码

| 操作 | 源码文件 | 职责 |
|------|----------|------|
| 新建 | `worktree/SlugValidator.java` | 名称、分支格式、编码与实际路径校验 |
| 新建 | `worktree/WorktreeSession.java` | 主会话进入现场 |
| 新建 | `worktree/WorktreeSessionStore.java` | 会话与内部资源记录、只读 Git 文件验证 |
| 新建 | `worktree/WorktreeManager.java` | 统一生命周期、占用及受保护删除；嵌套 WorktreeInfo |
| 新建 | `worktree/AgentWorkspace.java` | 每 Agent cwd、调用快照及绝对路径资源 |
| 新建 | `worktree/AgentWorktree.java` | 自动隔离适配；嵌套 Result |
| 新建 | `worktree/PostCreationSetup.java` | 配置、Git Hooks、依赖、include 初始化 |
| 新建 | `worktree/WorktreeChanges.java` | 修改和提交保护；嵌套 ChangeSummary |
| 新建 | `worktree/StaleCleanup.java` | 三层安全过滤 |
| 新建 | `worktree/GitCommandRunner.java` | 包内 Git 进程、有界执行与取消 |
| 新建 | `worktree/WorktreeException.java` | 安全错误与实际残留信息 |
| 新建 | `config/WorktreeConfig.java` | 可选配置与边界校验 |
| 新建 | `tool/impl/WorktreeTool.java` | 主会话生命周期工具 |

### 修改源码

| 源码文件 | 接入范围 |
|----------|----------|
| `MewCode.java`、`tui/MewCodeModel.java` | 主工作区、配置注入、工具与 UI、后台扫描 |
| `config/AppConfig.java`、`config/ConfigLoader.java` | 可选 worktree 配置绑定 |
| `command/CommandRegistry.java`、`command/CommandContext.java` | `/worktree` 解析与可信用户来源 |
| `subagent/SubAgentSpec.java`、`subagent/AgentDefinitionParser.java` | 嵌套 IsolationMode 及角色声明 |
| `subagent/SubAgentRuntime.java`、`subagent/SubAgentTaskManager.java` | 派发快照、锁外启动、收尾及结果发布 |
| `agent/AgentTurnCoordinator.java`、`agent/PromptRequestFactory.java`、`agent/ToolPolicy.java` | 工具原序、目录提示词、隔离能力过滤 |
| `tool/ToolExecutor.java`、`tool/ToolExecutionContext.java`、`tool/ToolInvocationResult.java` | 调用快照、占用及生命周期两阶段结果 |
| `tool/support/PathGuard.java`、`permission/PermissionContext.java`、`permission/PermissionGate.java`、`permission/PathSandbox.java` | 不可扩大的真实路径边界 |
| `tool/impl/ReadFileTool.java`、`tool/impl/EditFileTool.java`、`tool/impl/WriteFileTool.java` | 固定目录及所属 Agent 的已读记录 |
| `tool/support/SearchSupport.java`、`tool/impl/GlobTool.java`、`tool/impl/GrepTool.java` | 当前搜索根及管理区域排除 |
| `tool/support/CommandRunner.java`、`permission/BashSandboxRequest.java` | 命令、脚本、Hook 的 cwd、环境与范围 |
| `permission/MacSeatbeltSandbox.java`、`permission/LinuxBubblewrapSandbox.java` | 只读共享、父目录排除及必要 Git 写入 |
| `skill/ScriptTool.java` | 工作树脚本 cwd 与绝对资源路径 |
| `hook/HookEngine.java`、`hook/HookInvocation.java`、`hook/HookActionExecutor.java` | 队列快照、真实收口与占用释放 |
| `memory/MemoryManager.java` | 项目目标固定、用户写锁与子任务只读索引 |
| `compact/ContextManager.java`、`compact/ToolResultExternalizer.java` | 按目录外置结果，保持对话与预算 |

### 测试、构建与文档

| 操作 | 文件 | 用途 |
|------|------|------|
| 新建 | `worktree/GitRepositoryFixture.java`（测试） | 临时真实仓库、已知远端引用、可控时间与故障 |
| 新建 | `worktree/SlugValidatorTest.java`、`worktree/WorktreeSessionStoreTest.java`、`worktree/WorktreeManagerTest.java`（测试） | 安全名称、持久化与生命周期 |
| 新建 | `worktree/AgentWorkspaceTest.java`、`worktree/AgentWorktreeTest.java`（测试） | 目录快照、自动隔离与结果 |
| 新建 | `worktree/PostCreationSetupTest.java`、`worktree/WorktreeChangesTest.java`、`worktree/StaleCleanupTest.java`、`worktree/GitCommandRunnerTest.java`（测试） | 初始化、成果检查、过滤与进程收口 |
| 新建 | `tool/impl/WorktreeToolTest.java`（测试） | 参数、策略、可信丢弃与最终结果 |
| 修改 | `config/ConfigLoaderTest.java`、`command/CommandRegistryTest.java`（测试） | 可选配置及命令兼容 |
| 修改 | `subagent/AgentDefinitionParserTest.java`、`subagent/AgentCatalogTest.java`、`subagent/SubAgentRuntimeTest.java`、`subagent/SubAgentTaskManagerTest.java`（测试） | 角色解析、启动失败及任务发布顺序 |
| 修改 | `agent/AgentTurnCoordinatorTest.java`、`agent/AgentTurnCoordinatorHookTest.java`、`agent/PromptRequestFactoryTest.java`、`agent/ToolPolicyTest.java`（测试） | 原序边界、Hook 及目录提示词 |
| 修改 | `tool/ToolExecutorTest.java`、`tool/PermissionToolExecutorTest.java`、`tool/FileStateCacheTest.java`、`tool/support/PathGuardTest.java`（测试） | 调用快照、编辑前读取与不可绕过路径限制 |
| 修改 | `tool/impl/GlobAndGrepToolTest.java`、`tool/BashSandboxIntegrationTest.java`、`permission/BashSandboxTest.java`、`permission/PathSandboxTest.java`、`permission/PermissionGateTest.java`（测试） | 搜索、实际命令与两类沙箱范围 |
| 修改 | `hook/HookEngineTest.java`、`hook/HookActionExecutorTest.java`、`skill/ScriptToolTest.java`（测试） | 异步取消及脚本 cwd |
| 修改 | `memory/MemoryManagerTest.java`、`compact/ContextManagerTest.java`、`compact/ToolResultExternalizerTest.java`（测试） | 目录目标、共享写锁及外置结果 |
| 修改 | `MewCodeTest.java`、`tui/MewCodeModelTest.java`（测试） | 主会话、非 Git 兼容及 UI 非阻塞 |
| 修改 | `build.gradle.kts` | 仅扩充本次改动文件的 Spotless 范围 |
| 修改 | `.mewcode/config.yaml.example` | 不生效的可复制配置示例 |
| 新建 | `docs/ch14/README.md` | 使用方法、角色示例、边界及 tmux 复现 |
| 后续阶段生成并更新 | `docs/ch14/checklist.md` | 审批后的逐项验收与实际证据 |

`FileStateCache`、`PromptBuilder`、`InstructionLoader`、`SkillCatalog`、`AgentCatalog`、`MemoryStore`、`SessionManager` 及 `McpManager` 复用现有能力；只有相关测试或调用位置需要接入，不为本章另建平行实现。

## T1：记录实现前基线

**文件：** 不改源码；实现开始后的实际结果写入 `docs/ch14/checklist.md`。
**依赖：** 四份文档均获批准。
**步骤：**

1. 记录当前提交、工作区改动和 Java 21、Git、tmux 可用性，不输出配置内容。
2. 执行现有构建、测试与格式检查，记录既有失败及 JAR 路径。
3. 保留无关改动；环境失败先诊断，不改 toolchain、实际配置或权限模式来掩盖问题。

**验证：** 运行 `./gradlew spotlessCheck test shadowJar`、`git diff --check`，以退出码和测试报告记录基线，不提前写通过。
**覆盖：** N8、N10、AC26。

## T2：建立真实 Git 测试夹具及格式范围

**文件：** 测试 `worktree/GitRepositoryFixture.java`、`worktree/GitCommandRunnerTest.java`；`build.gradle.kts`。
**依赖：** T1。
**步骤：**

1. 使用 JUnit 临时目录建立仓库、测试作者和初始提交；提供仅在夹具内构造已知远端引用的方法，禁止测试依赖网络。
2. 提供 Git 调用记录、可控时钟和阶段故障入口；测试结束只处理夹具拥有的目录。
3. 将新增 worktree 源码/测试及本次改动但尚未覆盖的文件加入 Spotless，不扩大到全仓库重新格式化。

**验证：** `./gradlew test --tests '*GitCommandRunnerTest'` 验证夹具的提交、引用与路径；`./gradlew spotlessCheck` 覆盖新增目标。
**覆盖：** N10、AC26。

## T3：实现名称与分支格式校验

**文件：** `worktree/SlugValidator.java`；测试 `worktree/SlugValidatorTest.java`。
**依赖：** T2。
**步骤：**

1. 增加 1/64/65 长度、允许字符、嵌套名称及空段、点段、绝对路径拒绝测试。
2. 纯本地检查 Git 分支格式，将斜杠编码为 `+`，构造 `codex/worktree/<flatSlug>/task`。
3. 验证编码无碰撞，校验期间不创建文件、不调用 Git。

**验证：** `./gradlew test --tests '*SlugValidatorTest'`，合法边界通过，全部非法输入无副作用。
**覆盖：** F2、AC2。

## T4：绑定可选 Worktree 配置

**文件：** `config/WorktreeConfig.java`、`config/AppConfig.java`、`config/ConfigLoader.java`；测试 `config/ConfigLoaderTest.java`。
**依赖：** T2。
**步骤：**

1. 绑定 `cleanup_interval_minutes=30`、`stale_cutoff_hours=24`、两个默认空路径列表，保留现有字段映射。
2. 拒绝非正时间、非法字段类型和列表中的绝对路径、空段、点段及管理区域；实际符号链接在初始化时再验证。
3. 验证没有 worktree 配置的旧文件仍可加载，错误不包含 YAML 正文或凭据。

**验证：** `./gradlew test --tests '*ConfigLoaderTest'`，默认值、覆盖值、非法值与旧配置均符合预期。
**覆盖：** N5、N8、N9、AC21、AC24、AC26。

## T5：定义会话记录与安全异常

**文件：** `worktree/WorktreeSession.java`、`worktree/WorktreeException.java`；测试 `worktree/WorktreeSessionStoreTest.java`。
**依赖：** T2。
**步骤：**

1. 按 Plan 定义会话字段及规范化绝对路径，允许原目录 detached HEAD 的空分支说明。
2. 区分进入前 HEAD 与资源创建基线；不增加通用结果类型。
3. 异常仅携带阶段、安全原因、实际残留路径和分支，不接收原始配置正文作为展示内容。

**验证：** `./gradlew test --tests '*WorktreeSessionStoreTest'` 验证记录字段、路径及 detached HEAD 表达。
**覆盖：** F8、F13、AC14、AC24。

## T6：保护存放区域的真实路径

**文件：** `worktree/SlugValidator.java`；测试 `worktree/SlugValidatorTest.java`。
**依赖：** T3。
**步骤：**

1. 固定启动项目中的 worktrees/state 区域，目标先规范化再检查实际存在的父路径与符号链接。
2. 拒绝区域、目标或管理父目录被链接替换；不存在的目标仍逐段验证已有父路径。
3. 测试父会话改变 cwd 后存放区域不跟着移动，非法目标不产生分支或目录。

**验证：** `./gradlew test --tests '*SlugValidatorTest'`，越界符号链接及目录替换全部拒绝。
**覆盖：** F2、N5、AC2。

## T7：实现显式 cwd 的 Git 执行器

**文件：** `worktree/GitCommandRunner.java`；测试 `worktree/GitCommandRunnerTest.java`。
**依赖：** T2、T5。
**步骤：**

1. 以参数数组启动 Git，明确进程 cwd，关闭交互式凭据输入，保留有界输出。
2. 默认采用 120 秒截止时间，支持测试注入较短期限；解析结果与展示错误分离。
3. 测试含 Shell 特殊字符的输入只作为参数，不触发额外命令；调用不改变 JVM 工作目录。

**验证：** `./gradlew test --tests '*GitCommandRunnerTest'`，实际命令 cwd 正确，交互请求不会无限等待。
**覆盖：** F5、N7、AC9、AC25。

## T8：确认 Git 超时及取消后的实际退出

**文件：** `worktree/GitCommandRunner.java`；测试 `worktree/GitCommandRunnerTest.java`。
**依赖：** T7。
**步骤：**

1. 使用测试进程模拟阻塞和派生子进程，响应取消并终止此次启动的进程树。
2. 有界等待实际退出及输出读取收口，返回超时、取消或无法确认退出的安全原因。
3. 不将发出终止请求等同于退出；未知状态交给上层保留资源。

**验证：** `./gradlew test --tests '*GitCommandRunnerTest'`，在测试期限内结束，无法确认停止时不返回成功。
**覆盖：** N4、N7、AC19、AC25。

## T9：原子保存会话与内部资源记录

**文件：** `worktree/WorktreeSessionStore.java`；测试 `worktree/WorktreeSessionStoreTest.java`。
**依赖：** T5、T6。
**步骤：**

1. 实现 Plan 中 `save/load/clear`，会话文件按标识隔离，字段采用 snake_case。
2. 增加包内资源记录及 INITIALIZING/READY/PARTIAL 状态，保存身份、基线、归属、时间、Hooks 设置和真实残留。
3. 同目录临时文件写入后原子替换；损坏、非法版本、符号链接或目标冲突明确失败，不回退为可信空记录。

**验证：** `./gradlew test --tests '*WorktreeSessionStoreTest'`，中途写入失败保留旧记录，两个会话互不覆盖。
**覆盖：** F8、N5、N6、AC14、AC22。

## T10：只读验证 Git 指针与资源归属

**文件：** `worktree/WorktreeSessionStore.java`；测试 `worktree/WorktreeSessionStoreTest.java`。
**依赖：** T9。
**步骤：**

1. 读取 `.git` 指针、管理目录 HEAD/commondir、松散或 packed 引用，验证工作树反向关联及共享仓库。
2. 对照记录中的目录、分支和原基线；缺失、错配或不支持且不能验证的布局明确拒绝。
3. 记录读取前后文件内容及 mtime，禁止 Git 调用、补写、刷新时间或初始化。

**验证：** `./gradlew test --tests '*WorktreeSessionStoreTest'`，合法记录只读通过，其他仓库及伪造指针拒绝，Git 调用数为零。
**覆盖：** F3、F8、AC3、AC4。

## T11：实现每资源占用与跨进程锁

**文件：** `worktree/WorktreeManager.java`；测试 `worktree/WorktreeManagerTest.java`。
**依赖：** T9、T10。
**步骤：**

1. 使用有序 Map 和短状态锁登记操作，按 Agent 区分会话、工具及 Hook 使用计数。
2. 新资源创建锁文件；使用期间持有排他文件锁，恢复仅打开现有锁做只读探测。
3. 竞争立即返回冲突，Git 与进程等待不在全局锁内；进程退出释放锁，旧记录不当作活跃进程证据。

**验证：** `./gradlew test --tests '*WorktreeManagerTest'`，双请求仅一个取得资源；另一个 JVM 的占用能阻止删除。
**覆盖：** F3、F7、N2、AC4、AC13。

## T12：固定创建来源与提交基线

**文件：** `worktree/WorktreeManager.java`；测试 `worktree/WorktreeManagerTest.java`。
**依赖：** T7、T11。
**步骤：**

1. 仅新建路径在请求入口读取来源 cwd 的已提交 HEAD，并接受可信子任务入口已冻结的 SHA。
2. 明确保存来源与基线，模型输入不能覆盖；父分支随后移动不影响此次创建。
3. 测试父目录已有未提交修改，以及来源为当前工作树而非最初主目录的情况。

**验证：** `./gradlew test --tests '*WorktreeManagerTest'`，创建使用入口时的提交，未提交源码不进入新副本。
**覆盖：** F1、AC1。

## T13：登记并验证管理区域忽略规则

**文件：** `worktree/WorktreeManager.java`；测试 `worktree/WorktreeManagerTest.java`。
**依赖：** T6、T7、T11。
**步骤：**

1. 仅新建时向共享 `info/exclude` 补充管理区域和本工具上下文输出的精确规则，保留原文件内容。
2. 拒绝已被跟踪的管理区域，验证高优先级否定规则没有使其重新可追踪。
3. 冲突时说明需要调整规则，不修改已跟踪 `.gitignore`，不忽略整个 `.mewcode`。

**验证：** `./gradlew test --tests '*WorktreeManagerTest'`，管理区域不出现在普通待提交集合，原忽略规则保留。
**覆盖：** F1、N5、AC1、AC5。

## T14：创建独立分支及工作树

**文件：** `worktree/WorktreeManager.java`；测试 `worktree/WorktreeManagerTest.java`。
**依赖：** T3、T11、T12、T13。
**步骤：**

1. 登记本次 recordId 与 INITIALIZING 状态，在操作占用内执行 `git worktree add -b` 和冻结 SHA。
2. 定义嵌套 WorktreeInfo；创建后确认实际管理目录与独立分支，不把待初始化资源作为 READY 返回。
3. 同名目录或分支冲突保持原内容，不使用 `-B`、覆盖或固定 sleep。

**验证：** `./gradlew test --tests '*WorktreeManagerTest'`，两个新资源有独立目录分支，冲突分支原提交不变。
**覆盖：** F1、F13、N6、AC1、AC4、AC22。

## T15：安全复制本地配置和必要文件

**文件：** `worktree/PostCreationSetup.java`；测试 `worktree/PostCreationSetupTest.java`。
**依赖：** T4、T6、T7、T14。
**步骤：**

1. 处理 `.mewcode/config.yaml`、`.mewcode/permissions.local.yaml`、`.mewcode/hooks.yaml` 和 required_files；已有配置复制失败、必要文件缺失均失败。
2. 验证源、目标及真实父路径，拒绝覆盖已检出源码，保持复制权限不比源宽。
3. 可选缺失只产生安全提示；排除 Git 元数据、会话和管理区域，不输出文件正文。

**验证：** `./gradlew test --tests '*PostCreationSetupTest'`，内容和权限正确，危险路径及必要失败阻止就绪。
**覆盖：** F4、N5、N6、AC5、AC8、AC24。

## T16：配置已启用 worktreeConfig 的 Git Hooks

**文件：** `worktree/PostCreationSetup.java`；测试 `worktree/PostCreationSetupTest.java`。
**依赖：** T15。
**步骤：**

1. 识别有效 Hooks 设置和已启用的 worktreeConfig，安全解析相对 Hooks 路径。
2. 需要覆盖时只写新工作树配置，不改共享 config 或其他工作树设置。
3. 在真实夹具中触发 Hook，比较主目录和另一工作树的设置与行为。

**验证：** `./gradlew test --tests '*PostCreationSetupTest'`，新目录 Hook 执行，其他目录行为不变。
**覆盖：** F4、AC6。

## T17：保存未启用扩展时的 Hooks 环境覆盖

**文件：** `worktree/PostCreationSetup.java`、`worktree/WorktreeSessionStore.java`；测试 `worktree/PostCreationSetupTest.java`。
**依赖：** T9、T16。
**步骤：**

1. 不主动启用 worktreeConfig；保存该资源需要的 Hooks 路径与环境覆盖模式。
2. 为后续命令提供 GIT_CONFIG_COUNT/KEY/VALUE 条目，保留合法已有条目；非法环境条目不静默丢弃或覆盖。
3. 对比使用覆盖环境与外部普通命令，说明覆盖仅适用于 MewCode 启动的命令。

**验证：** `./gradlew test --tests '*PostCreationSetupTest'`，资源设置可恢复，共享 config 未改变。
**覆盖：** F4、F8、AC6、AC14。

## T18：建立只读共享依赖

**文件：** `worktree/PostCreationSetup.java`；测试 `worktree/PostCreationSetupTest.java`。
**依赖：** T4、T15。
**步骤：**

1. 仅处理显式 symlink_directories，默认不共享；验证源、目标及解引用位置。
2. 创建不覆盖检出内容的依赖链接，将真实只读范围登记到资源记录。
3. 需要写依赖时不改父副本；初始化失败返回原因，不自动安装或更新依赖。

**验证：** `./gradlew test --tests '*PostCreationSetupTest'`，默认无链接，安全目标可共享，越界或冲突拒绝。
**覆盖：** F4、F5、N5、AC7、AC8。

## T19：按 Git ignore 规则补充运行文件

**文件：** `worktree/PostCreationSetup.java`；测试 `worktree/PostCreationSetupTest.java`。
**依赖：** T7、T15、T18。
**步骤：**

1. 使用 Git 的标准忽略候选和 `.worktreeinclude` 匹配集合取交集，路径以 NUL 分隔。
2. 覆盖嵌套、否定模式、空格及特殊文件名；只补被忽略的运行文件。
3. 对每个源目标做真实路径及禁止区域检查，复制不覆盖源码，也不递归复制其他工作树或会话。

**验证：** `./gradlew test --tests '*PostCreationSetupTest'`，匹配文件正确，未忽略文件与禁止区域不复制。
**覆盖：** F4、N5、AC5。

## T20：提交初始化完成状态

**文件：** `worktree/WorktreeManager.java`、`worktree/PostCreationSetup.java`；测试 `worktree/WorktreeManagerTest.java`。
**依赖：** T14、T15、T16、T17、T18、T19。
**步骤：**

1. 按配置、Hooks、依赖、include 顺序执行初始化，再统一验证必要资源和忽略状态。
2. 成功后原子保存 READY 再返回；必要失败保存实际 PARTIAL 残留，禁止进入或启动任务。
3. 可选提示随实际创建结果回流；安全回滚接入后续统一删除流程，不临时添加强制清理路径。

**验证：** `./gradlew test --tests '*WorktreeManagerTest'`，初始化任一步必要失败均不返回可用资源。
**覆盖：** F4、F13、N6、AC8、AC22。

## T21：实现零 Git 调用的快速恢复

**文件：** `worktree/WorktreeManager.java`；测试 `worktree/WorktreeManagerTest.java`。
**依赖：** T10、T11、T20。
**步骤：**

1. 目标已存在时先走只读存储验证与现有锁探测，不冻结新 HEAD，不执行 Git 或初始化。
2. 返回首次基线和原资源信息，不写记录、不刷新 mtime；已有修改与新增提交保持原样。
3. 普通目录、缺记录、缺锁、外仓库、PARTIAL 状态或正在使用的资源明确拒绝。
4. 实现 list，汇总持久化和当前已知资源的 WorktreeInfo，不随调用者 cwd 改变存放区域，不把未初始化或残留资源当作可进入资源。

**验证：** `./gradlew test --tests '*WorktreeManagerTest'`，恢复调用数为零，前后文件/提交/mtime 一致，忙碌资源拒绝。
**覆盖：** F3、F8、AC3、AC4。
## T22：提供每 Agent 的不可变调用快照

**文件：** `worktree/AgentWorkspace.java`、`tool/ToolExecutionContext.java`；测试 `worktree/AgentWorkspaceTest.java`。
**依赖：** T5、T11。
**步骤：**

1. 实现 Plan 中 currentCwd/currentSession/capture，复用 ToolExecutionContext，不新增公开快照类型。
2. 将规范化绝对 cwd、所属 Agent 文件缓存、取消控制及内部范围绑定到本次调用，保留旧构造兼容性。
3. 通过注入的窄幅占用接口登记和释放调用；之后切换不修改已取得的快照。

**验证：** `./gradlew test --tests '*AgentWorkspaceTest'`，两个 Agent 及切换前后快照相互独立。
**覆盖：** F5、F6、N2、AC9、AC11、AC13。

## T23：按绝对 cwd 取得项目提示词与定义

**文件：** `worktree/AgentWorkspace.java`、`agent/PromptRequestFactory.java`；测试 `worktree/AgentWorkspaceTest.java`、`agent/PromptRequestFactoryTest.java`。
**依赖：** T22。
**步骤：**

1. 在工作区内部以绝对目录保存提示词、InstructionLoader、SkillCatalog 和 AgentCatalog 对应资源，复用现有加载能力。
2. 每次模型请求固定本次目录资源；已选定的子任务角色不在执行中跟随父目录刷新。
3. 测试父/子同名指令不同、用户级定义范围不变；切换不清空已有资源或改写已发请求。

**验证：** `./gradlew test --tests '*AgentWorkspaceTest' --tests '*PromptRequestFactoryTest'`，请求显示当前目录的指令和环境。
**覆盖：** F6、N8、AC11、AC15。

## T24：让工具校验、权限和执行共用一次捕获

**文件：** `tool/ToolExecutor.java`、`tool/ToolExecutionContext.java`、`tool/impl/ReadFileTool.java`、`tool/impl/EditFileTool.java`、`tool/impl/WriteFileTool.java`；测试 `tool/ToolExecutorTest.java`、`tool/FileStateCacheTest.java`。
**依赖：** T22。
**步骤：**

1. 提交调用前取得快照；输入校验、权限检查、文件操作和结果均使用该快照，不再重复读取全局 cwd。
2. 复用每 Agent 的 FileStateCache，所有记录仍是绝对路径；不复制父已读记录，也不在切换时清缓存。
3. 测试调用暂停期间切换目录，以及父已读但子未读的同名文件编辑请求。

**验证：** `./gradlew test --tests '*ToolExecutorTest' --tests '*FileStateCacheTest'`，旧调用留在旧目录，子文件需重新读取。
**覆盖：** F5、F6、N2、AC9、AC11、AC13。

## T25：原子进入工作树

**文件：** `worktree/WorktreeManager.java`、`worktree/AgentWorkspace.java`；测试 `worktree/WorktreeManagerTest.java`。
**依赖：** T9、T11、T20、T21、T23。
**步骤：**

1. 校验 READY 资源并取得独占使用权，加载目标资源，再原子保存 WorktreeSession。
2. 保存成功后才绑定 cwd 和当前会话；失败释放此次占用，原目录不变。
3. 拒绝已进入时再次进入，不覆盖返回现场；当前目录仍可作为新建其他资源的来源。

**验证：** `./gradlew test --tests '*WorktreeManagerTest'`，进入保存失败及重复进入均保持原现场，其他 Agent 不能同时进入。
**覆盖：** F7、F8、AC12、AC13、AC14。

## T26：保留后退出且保护旧调用

**文件：** `worktree/WorktreeManager.java`、`worktree/AgentWorkspace.java`；测试 `worktree/AgentWorkspaceTest.java`、`worktree/WorktreeManagerTest.java`。
**依赖：** T24、T25。
**步骤：**

1. 准备原目录资源并清除所属会话的恢复记录，再提交 KEEP 退出的 cwd 切换。
2. 释放会话占用，运行中的旧工具和 Hook 继续持有使用权；使用归零前禁止接管或删除。
3. 清除记录失败时不切换；退出不 checkout 原分支、不重置预算或文件缓存。

**验证：** `./gradlew test --tests '*AgentWorkspaceTest' --tests '*WorktreeManagerTest'`，新调用回原目录，旧调用仍在原工作树完成。
**覆盖：** F5、F6、F7、N2、AC11、AC12、AC13。

## T27：恢复有效的主会话目录状态

**文件：** `worktree/WorktreeManager.java`、`worktree/WorktreeSessionStore.java`；测试 `worktree/WorktreeManagerTest.java`。
**依赖：** T10、T21、T25、T26。
**步骤：**

1. 重启恢复时按原会话标识读取现场，重新验证资源并取得使用权。
2. 已退出、已删除、损坏或已被其他进程占用的目录不绑定；返回安全原因。
3. 保留原主会话历史目录，不复制 Session 文件，不恢复后台子任务执行。

**验证：** `./gradlew test --tests '*WorktreeManagerTest'`，有效目录可重新进入，退出或删除后的记录不误恢复。
**覆盖：** F8、AC14。

## T28：统计未提交修改与新增提交

**文件：** `worktree/WorktreeChanges.java`；测试 `worktree/WorktreeChangesTest.java`。
**依赖：** T7、T9。
**步骤：**

1. 定义嵌套 ChangeSummary，解析 NUL 分隔 status，覆盖已跟踪修改、重命名及未被忽略的未跟踪文件。
2. 相对资源首次基线统计新增提交，恢复后的当前 HEAD 不能成为新基线。
3. 计数失败抛异常，hasChanges 失败表示需要保留，不返回伪造零值。

**验证：** `./gradlew test --tests '*WorktreeChangesTest'`，修改、未跟踪文件和新提交分别触发保护，忽略运行文件不计入。
**覆盖：** F10、F11、AC17、AC18、AC19。

## T29：检查本地已知远端引用

**文件：** `worktree/WorktreeChanges.java`；测试 `worktree/WorktreeChangesTest.java`。
**依赖：** T28。
**步骤：**

1. 判断提交是否被本地已知远端引用包含，禁止联网 fetch 或推送。
2. 无可用引用、命令失败或无法证明包含时视为需要保留，保留具体原因。
3. 验证已被远端引用包含的新增提交仍由创建基线保护，不能自动清理。

**验证：** `./gradlew test --tests '*WorktreeChangesTest'`，已知/未知远端和已推送新增成果符合保留规则，调用中没有 fetch。
**覆盖：** F10、F11、N4、AC17、AC18、AC19。

## T30：默认受保护删除

**文件：** `worktree/WorktreeManager.java`；测试 `worktree/WorktreeManagerTest.java`。
**依赖：** T11、T20、T28、T29。
**步骤：**

1. 保留排他操作占用，验证资源身份、无其他会话/调用/Hook、全部成果检查通过。
2. 使用非强制 worktree 删除与分支删除，目录和分支都确认不存在后才返回 true 并移除资源记录。
3. 任何未知、冲突或 Git 锁都保留；不使用默认 force、reset、强删分支或 prune。

**验证：** `./gradlew test --tests '*WorktreeManagerTest'`，安全夹具删除成功，有修改/提交/占用或未知状态均拒绝。
**覆盖：** F11、F13、N4、AC18、AC19、AC22。

## T31：绑定可信的用户丢弃授权

**文件：** `worktree/WorktreeManager.java`；测试 `worktree/WorktreeManagerTest.java`。
**依赖：** T30。
**步骤：**

1. 增加仅内部受信调用可提供的授权，绑定目标 recordId 和明确的用户请求来源。
2. 仅允许越过已确认的成果保护；其他占用、未知状态及 Git 锁仍拒绝。
3. 测试模型字段、bypassPermissions、后台清理及旧同名资源授权均不能获得丢弃权。

**验证：** `./gradlew test --tests '*WorktreeManagerTest'`，只有匹配本次资源的可信用户授权能丢弃已知成果。
**覆盖：** F11、N3、AC18、AC23。

## T32：记录部分删除并安全处理初始化失败

**文件：** `worktree/WorktreeManager.java`、`worktree/WorktreeSessionStore.java`；测试 `worktree/WorktreeManagerTest.java`。
**依赖：** T20、T25、T30、T31。
**步骤：**

1. 注入目录删除成功、分支删除失败，保留 PARTIAL 记录和真实残留，不返回删除成功。
2. 请求退出删除被保护拒绝时，cwd、现场、目录、分支不变；目录已实际消失时恢复原 cwd，不显示已删除位置。
3. 初始化失败仅对本次 recordId 创建且经检查可安全处理的资源复用删除核心；原目录/分支或未知残留保留并说明。

**验证：** `./gradlew test --tests '*WorktreeManagerTest'`，失败状态与磁盘一致，回滚不触碰旧资源。
**覆盖：** F11、F13、N4、N6、AC8、AC18、AC19、AC22。

## T33：在应用层落实不可扩大的路径范围

**文件：** `tool/support/PathGuard.java`、`permission/PermissionContext.java`、`permission/PermissionGate.java`、`permission/PathSandbox.java`；测试 `tool/support/PathGuardTest.java`、`permission/PathSandboxTest.java`、`tool/PermissionToolExecutorTest.java`。
**依赖：** T6、T18、T24。
**步骤：**

1. 隔离检查先于可询问权限，拒绝父源码绝对路径、越界链接和管理记录写入；允许读取声明的共享依赖但拒绝写入。
2. 主目录普通工具排除 worktrees/state，进入资源后仅开放所属副本；范围由系统推导，输入不能扩大。
3. 验证外部路径批准、非交互模式和已存在的权限规则均不能越过隔离，原有拒绝规则仍生效。

**验证：** `./gradlew test --tests '*PathGuardTest' --tests '*PathSandboxTest' --tests '*PermissionToolExecutorTest'`，直接与链接绕过尝试均拒绝。
**覆盖：** F5、N1、N3、AC7、AC9、AC23。

## T34：排除父目录搜索中的其他副本

**文件：** `tool/support/SearchSupport.java`、`tool/impl/GlobTool.java`、`tool/impl/GrepTool.java`；测试 `tool/impl/GlobAndGrepToolTest.java`。
**依赖：** T24、T33。
**步骤：**

1. 把实际管理区域作为本次搜索范围的排除项，不只匹配目录 basename。
2. 在父目录与两个工作树放置相同关键字，父搜索不混入副本，当前工作树可搜索自己的代码。
3. 同时验证显式搜索路径及切换期间的调用仍使用固定根。

**验证：** `./gradlew test --tests '*GlobAndGrepToolTest'`，返回路径集合与所属目录一致。
**覆盖：** F5、N1、AC9、AC10。

## T35：推导命令沙箱的读写范围

**文件：** `permission/BashSandboxRequest.java`、`tool/ToolExecutionContext.java`、`worktree/AgentWorkspace.java`；测试 `permission/BashSandboxTest.java`。
**依赖：** T18、T22、T33。
**步骤：**

1. 扩充内部请求以表达排除区域、只读共享和必要 Git 范围，保留旧请求构造行为。
2. 从已验证资源推导自身管理目录、共享 objects 及本分支 refs/reflog 命名空间，不开放共享 config 或其他分支。
3. 布局未知时不扩大写范围；模型参数和普通项目外授权不能改变范围。

**验证：** `./gradlew test --tests '*BashSandboxTest'`，范围集合准确，父源码、其他分支和共享配置不可写。
**覆盖：** F5、N3、AC7、AC23。

## T36：在 macOS 沙箱中执行隔离范围

**文件：** `permission/MacSeatbeltSandbox.java`；测试 `permission/BashSandboxTest.java`、`tool/BashSandboxIntegrationTest.java`。
**依赖：** T35。
**步骤：**

1. 生成当前目录、管理区域排除、只读依赖和必要 Git 范围对应的 Seatbelt 规则。
2. 验证 broad 项目写范围不能覆盖排除项；转义所有实际路径，拒绝无法表达或验证的范围。
3. 当前 macOS 主机实际执行读、执行、写拒绝及所属分支提交，核对父文件与其他 Git 设置。

**验证：** `./gradlew test --tests '*BashSandboxTest' --tests '*BashSandboxIntegrationTest'`，真实共享依赖写失败而读取/执行成功。
**覆盖：** F5、N3、AC7、AC9、AC23。

## T37：在 Linux 沙箱中表达隔离范围

**文件：** `permission/LinuxBubblewrapSandbox.java`；测试 `permission/BashSandboxTest.java`、`tool/BashSandboxIntegrationTest.java`。
**依赖：** T35。
**步骤：**

1. 以现有只读根为基础绑定所属工作树及必要 Git 范围，确保父写范围内的依赖和管理区域仍被只读覆盖。
2. 检查挂载顺序、路径验证和子进程 cwd；不把子进程的 chdir 当作 JVM 目录切换。
3. 验证 argv/范围；Linux 主机才执行真实 bubblewrap 场景，macOS 上明确记录 Linux 实际执行未验证。

**验证：** `./gradlew test --tests '*BashSandboxTest' --tests '*BashSandboxIntegrationTest'`，当前平台实测与其他平台参数检查分别记录，跳过不算实测通过。
**覆盖：** F5、N3、AC7、AC9、AC23。

## T38：命令携带固定 cwd 与 Hooks 环境

**文件：** `tool/support/CommandRunner.java`；测试 `tool/BashSandboxIntegrationTest.java`。
**依赖：** T8、T17、T35、T36、T37。
**步骤：**

1. Bash 和后续脚本/Hook 共用本次 cwd、沙箱范围及资源 Hooks 环境覆盖，不重新查询主会话位置。
2. 取消与超时后确认所启动进程实际收口，在确认前保留调用使用权。
3. 使用两个目录的同名输出文件验证落点，并触发需环境覆盖的真实 Git Hook。

**验证：** `./gradlew test --tests '*BashSandboxIntegrationTest'`，输出和 Hook 位于本次目录，父源码不被修改。
**覆盖：** F4、F5、N7、AC6、AC9、AC25。

## T39：隔离 Skill 脚本的执行目录

**文件：** `skill/ScriptTool.java`、`tool/support/CommandRunner.java`；测试 `skill/ScriptToolTest.java`。
**依赖：** T23、T33、T38。
**步骤：**

1. 隔离模式将工作目录设为 Agent cwd，脚本可执行路径和资源目录固定为绝对路径。
2. 提供 MEWCODE_PROJECT_ROOT 与 MEWCODE_SKILL_DIR，并保留本资源 Git Hooks 环境；不回退父目录运行旧脚本。
3. 验证读附属资源、写相对文件及拒绝写父源码，未隔离脚本保留原运行目录协议。

**验证：** `./gradlew test --tests '*ScriptToolTest'`，隔离输出位于工作树，普通脚本回归通过。
**覆盖：** F5、N3、N8、AC9、AC23、AC26。

## T40：Hook 入队前固定目录与使用权

**文件：** `hook/HookInvocation.java`、`hook/HookEngine.java`、`hook/HookActionExecutor.java`、`tool/ToolExecutor.java`；测试 `hook/HookEngineTest.java`、`hook/HookActionExecutorTest.java`。
**依赖：** T22、T24、T38。
**步骤：**

1. 捕获 cwd、范围、Hooks 环境及占用后再提交异步 Hook，payload 使用同一目录。
2. Hook 开始执行时不读取新的主会话位置，Shell 执行沿用相同 CommandRunner 范围。
3. 阻塞队列后切换目录，验证已排队 Hook 的文件落点与删除保护。

**验证：** `./gradlew test --tests '*HookEngineTest' --tests '*HookActionExecutorTest'`，排队 Hook 位于原目录并阻止删除。
**覆盖：** F5、N2、AC9、AC13。

## T41：Hook 取消后仅在实际结束时释放

**文件：** `hook/HookEngine.java`、`hook/HookActionExecutor.java`；测试 `hook/HookEngineTest.java`。
**依赖：** T40。
**步骤：**

1. 实际执行体 finally 释放使用权；确认从未启动的取消任务单独释放且只执行一次。
2. Future.done、取消标记及会话队列移除不直接释放仍运行的进程使用权。
3. 测试取消后延迟退出和入队未启动两种情况，未知停止状态继续保留资源。

**验证：** `./gradlew test --tests '*HookEngineTest'`，运行中的取消 Hook 仍阻止删除，停止后计数归零且无重复释放。
**覆盖：** F10、F11、N2、N4、AC13、AC19、AC25。

## T42：实现当前目录删除的两阶段流程

**文件：** `worktree/WorktreeManager.java`、`tool/ToolExecutor.java`、`tool/ToolInvocationResult.java`；测试 `tool/ToolExecutorTest.java`、`agent/AgentTurnCoordinatorHookTest.java`。
**依赖：** T30、T32、T40、T41。
**步骤：**

1. 旧目录执行策略/权限/Pre Hook，完成预检并保留操作占用；内部准备结果明确标为尚未提交。
2. Post Hook 在旧目录接收准备状态；未结束的异步 Hook 阻止提交，释放本控制调用后重新检查其他使用与成果。
3. 实际退出/删除后才发布最终 ToolResult；不把准备状态回流为成功，不在删除目录二次派发 Post Hook，普通工具顺序不变。

**验证：** `./gradlew test --tests '*ToolExecutorTest' --tests '*AgentTurnCoordinatorHookTest'`，Hook 新增成果或延迟退出均能阻止删除，最终结果与实际目录一致。
**覆盖：** F11、F13、N2、N4、AC13、AC18、AC22。

## T43：保持工具批次的原始调用顺序

**文件：** `agent/AgentTurnCoordinator.java`；测试 `agent/AgentTurnCoordinatorTest.java`。
**依赖：** T24、T42。
**步骤：**

1. 将安全读批次与 Worktree/Agent 边界按原工具顺序分段，边界前先收口，不再把普通调用统一提到 Agent 派发前。
2. Worktree 切换后的调用取得新快照，Agent 派发固定发生在模型要求的位置。
3. 用记录执行顺序和 cwd 的测试验证 Read→切换→Read 及父写→派发→父写；保留安全读并发能力。

**验证：** `./gradlew test --tests '*AgentTurnCoordinatorTest'`，调用顺序和目录准确，普通批次回归通过。
**覆盖：** F5、F7、F9、N2、AC9、AC13、AC15。

## T44：固定项目 Memory 的目标目录

**文件：** `memory/MemoryManager.java`、`worktree/AgentWorkspace.java`；测试 `memory/MemoryManagerTest.java`。
**依赖：** T22、T23。
**步骤：**

1. 按绝对 cwd 取得项目 Memory 实例，切换使用对应目录，不清空旧实例。
2. 异步更新提交时捕获目标实例及目录；执行完成时不重新查询当前 cwd。
3. 阻塞一次更新后切换目录，验证项目内容只落到提交时的目录，父/子同名记忆可区分。

**验证：** `./gradlew test --tests '*MemoryManagerTest'`，切换期间的更新和索引均使用正确项目目录。
**覆盖：** F6、N8、AC11、AC26。

## T45：保护用户 Memory 并保持子任务只读

**文件：** `memory/MemoryManager.java`、`worktree/AgentWorkspace.java`；测试 `memory/MemoryManagerTest.java`。
**依赖：** T44。
**步骤：**

1. 按用户存储绝对路径共用写锁，覆盖读取、更新及写回的完整操作，不仅锁最后一次写文件。
2. 为子任务提供不触发修剪或写回的只读索引，不挂接自动 Memory 更新器。
3. 两个项目实例并发更新用户记录不丢失内容，用户资源范围保持既有行为。

**验证：** `./gradlew test --tests '*MemoryManagerTest'`，并发更新均保留，子任务读取前后磁盘内容和 mtime 不变。
**覆盖：** F6、N8、AC11、AC26。

## T46：按调用目录外置结果且保留预算

**文件：** `compact/ContextManager.java`、`compact/ToolResultExternalizer.java`、`worktree/AgentWorkspace.java`；测试 `compact/ContextManagerTest.java`、`compact/ToolResultExternalizerTest.java`。
**依赖：** T22、T24。
**步骤：**

1. 同一 Agent 保持一份对话、预算及压缩状态，按固定调用 cwd 取得外置结果资源。
2. 进入退出不调用 resetForSession，不关闭仍被旧调用使用的资源；对话历史继续锚定原 Session 目录。
3. 测试两个目录产生大型结果，引用路径各自正确，切换前后预算和历史不重置。

**验证：** `./gradlew test --tests '*ContextManagerTest' --tests '*ToolResultExternalizerTest'`，文件落点、引用和预算符合预期。
**覆盖：** F5、F6、F8、AC9、AC11、AC14。

## T47：解析角色的 isolation 声明

**文件：** `subagent/SubAgentSpec.java`、`subagent/AgentDefinitionParser.java`；测试 `subagent/AgentDefinitionParserTest.java`、`subagent/AgentCatalogTest.java`。
**依赖：** T2。
**步骤：**

1. 定义嵌套 IsolationMode.NONE/WORKTREE，省略字段为 NONE，接受 `isolation: worktree`。
2. 其他值或类型按无效角色处理，不连带拒绝其他合法角色。
3. 保留旧角色构造及解析兼容，不为 Fork 或 Skill fork 增加参数，不改用户现有角色文件。

**验证：** `./gradlew test --tests '*AgentDefinitionParserTest' --tests '*AgentCatalogTest'`，默认、worktree、非法与混合目录均符合预期。
**覆盖：** F9、N8、AC15、AC26。

## T48：在锁外固定子任务派发现场

**文件：** `subagent/SubAgentRuntime.java`、`subagent/SubAgentTaskManager.java`；测试 `subagent/SubAgentTaskManagerTest.java`、`subagent/SubAgentRuntimeTest.java`。
**依赖：** T12、T43、T47。
**步骤：**

1. 短锁分配任务标识并登记启动状态，在父调用位置捕获 cwd；锁外固定新建基线后再排入创建/运行阶段。
2. 唯一临时名称与归属使用可信任务标识；模型不能指定其他资源身份或 SHA。
3. Git 和运行工厂均不在任务管理器或 TUI 状态锁内执行；启动失败保持其他任务和 UI 可用。

**验证：** `./gradlew test --tests '*SubAgentTaskManagerTest' --tests '*SubAgentRuntimeTest'`，父分支排队后移动不改变基线，阻塞创建不锁住其他任务。
**覆盖：** F9、N2、N7、AC1、AC15、AC16、AC25。

## T49：实现 AgentWorktree 轻量适配

**文件：** `worktree/AgentWorktree.java`；测试 `worktree/AgentWorktreeTest.java`。
**依赖：** T20、T21、T30、T32、T48。
**步骤：**

1. 定义嵌套 Result，create 委托管理器共用核心并登记系统临时归属，不写主会话现场。
2. Result.headCommit 始终使用资源首次基线；取得使用权并完成必要初始化后才交给运行时。
3. remove 复用默认保护；buildNotice 包含父/子绝对路径、路径转换和编辑前重新读取说明。

**验证：** `./gradlew test --tests '*AgentWorktreeTest'`，适配前后主 cwd/现场不变，错误不回退父目录。
**覆盖：** F9、F10、AC15、AC16、AC17。

## T50：在子目录构造独立运行资源

**文件：** `subagent/SubAgentRuntime.java`；测试 `subagent/SubAgentRuntimeTest.java`。
**依赖：** T23、T24、T33、T38、T39、T40、T45、T46、T49。
**步骤：**

1. 隔离成功后创建子 Agent 的工作区、执行器、文件缓存、提示词、只读 Memory 索引、Hook 会话和结果目录。
2. 不直接继承父环境段或父已读记录；注入路径通知，工具始终固定在所属目录。
3. 创建、初始化或资源加载失败时子任务不调用任何任务工具；未声明隔离仍走原流程。

**验证：** `./gradlew test --tests '*SubAgentRuntimeTest'`，同名文件和提示词使用子副本，启动失败没有父目录工具副作用。
**覆盖：** F5、F6、F9、N8、AC9、AC11、AC15、AC16、AC26。

## T51：收尾后再发布子任务完成结果

**文件：** `subagent/SubAgentTaskManager.java`、`subagent/SubAgentRuntime.java`；测试 `subagent/SubAgentTaskManagerTest.java`。
**依赖：** T41、T49、T50。
**步骤：**

1. 冻结原执行结果，停止接收新调用，在工作线程中执行有界收尾，不持有全局状态锁。
2. 实际工具与 Hook 未结束时决定保留；使用独立取消控制，不复用已经取消的任务 token。
3. 收尾完成或明确保留后再完成 Future 和后台通知；成功、失败、取消及启动失败最多收尾一次，清理错误不改变原任务终态。

**验证：** `./gradlew test --tests '*SubAgentTaskManagerTest'`，阻塞收尾期间不发布完成通知，其他任务可运行，取消不重复通知。
**覆盖：** F10、F13、N4、N7、AC17、AC22、AC25。

## T52：回流自动清理或保留的实际结果

**文件：** `subagent/SubAgentRuntime.java`、`subagent/SubAgentTaskManager.java`、`worktree/AgentWorktree.java`；测试 `subagent/SubAgentRuntimeTest.java`。
**依赖：** T29、T49、T51。
**步骤：**

1. 成功、失败、取消均释放自己的会话占用，再按真实工具/Hook 使用和成果检测执行默认处理。
2. 已知远端包含基线且无成果的资源清理；未提交、新增提交、无远端或停止未知均保留。
3. 在现有结果文本附实际路径、分支和原因，清理失败只追加说明，不遮盖原成功/失败/取消结果。

**验证：** `./gradlew test --tests '*SubAgentRuntimeTest'`，三种终态、推送后的新提交及清理故障均回流正确。
**覆盖：** F10、F13、AC17、AC19、AC22。

## T53：限制工作树会话的工具能力

**文件：** `agent/ToolPolicy.java`、`tool/ToolExecutor.java`；测试 `agent/ToolPolicyTest.java`、`tool/ToolExecutorTest.java`。
**依赖：** T24、T33、T50。
**步骤：**

1. 工作树会话隐藏且在执行入口拒绝无法验证 cwd 隔离的 MCP 工具，直接调用和 ToolSearch 发现不能绕过。
2. 子 Agent 不开放主会话 Worktree 管理工具；原 Skill/Agent 白名单、Plan Mode、权限与 Hook 裁剪继续生效。
3. 未隔离主会话保留原 MCP 行为，不新增按目录服务池或重启逻辑。

**验证：** `./gradlew test --tests '*ToolPolicyTest' --tests '*ToolExecutorTest'`，隐藏工具的伪造直接调用拒绝，普通会话回归通过。
**覆盖：** F9、N3、N8、AC23、AC26。

## T54：实现主会话 Worktree 工具参数及结果

**文件：** `tool/impl/WorktreeTool.java`；测试 `tool/impl/WorktreeToolTest.java`。
**依赖：** T25、T26、T31、T32、T42、T53。
**步骤：**

1. 暴露 Plan 的 action/name/delete/discardChanges 字段，校验动作与名称组合；来源、基线、临时归属及路径不得由输入提供。
2. 非系统工具，不豁免策略，不宣称只读或并发安全；创建只创建，进入为独立动作，删除当前目录使用两阶段入口。
3. 对模型、UI 返回实际结果；模型丢弃意图需取得目标明确的可信用户授权才能调用内部例外。

**验证：** `./gradlew test --tests '*WorktreeToolTest'`，五种动作、非法参数、模型伪造授权及部分残留符合预期。
**覆盖：** F7、F11、F13、N3、AC12、AC18、AC22、AC23。

## T55：主会话接入当前目录及自然语言工具

**文件：** `MewCode.java`、`tui/MewCodeModel.java`、`agent/AgentTurnCoordinator.java`；测试 `MewCodeTest.java`、`tui/MewCodeModelTest.java`。
**依赖：** T4、T23、T27、T43、T44、T46、T50、T53、T54。
**步骤：**

1. 启动时建立主 AgentWorkspace 并注入可选配置、共享管理器及自然语言工具，默认 cwd 仍是原项目目录。
2. 每轮请求取得本次目录资源，状态栏和状态查询显示真实当前目录、分支与保留原因。
3. 生命周期操作在工作线程执行，UI 只提交和接收结果；恢复目录与原 Session 历史分开，启动不强制检测 Git。

**验证：** `./gradlew test --tests '*MewCodeTest' --tests '*MewCodeModelTest'`，切换后提示词/工具/显示一致，阻塞 Git 不阻塞 UI。
**覆盖：** F6、F7、F8、F13、N7、N8、AC11、AC12、AC14、AC25、AC26。

## T56：接入斜杠命令及明确丢弃要求

**文件：** `command/CommandRegistry.java`、`command/CommandContext.java`、`tui/MewCodeModel.java`；测试 `command/CommandRegistryTest.java`、`tui/MewCodeModelTest.java`。
**依赖：** T31、T42、T54、T55。
**步骤：**

1. 注册 create/enter/list/exit/delete 与 exit 的 --delete，只有明确删除目标才接受用户输入的 --discard。
2. 窄幅回调携带可信用户来源，经同一工具/生命周期管道执行策略、权限和 Hook，执行前绑定实际 recordId。
3. 不在 UI 回调内等待 Git，保留旧 CommandContext 构造与命令行为；非法命令不改变 cwd 或资源。

**验证：** `./gradlew test --tests '*CommandRegistryTest' --tests '*MewCodeModelTest'`，命令与自然语言共用保护，可信授权不能用于后来同名资源。
**覆盖：** F7、F11、F13、N3、AC12、AC18、AC23。

## T57：实现临时资源的三层清理

**文件：** `worktree/StaleCleanup.java`、`worktree/WorktreeManager.java`；测试 `worktree/StaleCleanupTest.java`。
**依赖：** T11、T29、T30、T32、T52。
**步骤：**

1. 从持久化记录枚举资源，包括重启前留下的临时资源；依次验证系统临时归属、过期且无会话/任务/工具/Hook/进程使用、完整成果安全，再调用默认删除。
2. 取得/释放使用权更新 lastUsedAt，恢复读取仍不更新；使用可控时间验证超过阈值才成为候选。
3. 手动、仅名称相似、身份不明、PARTIAL、未知或失败候选保留且继续扫描其他项，不使用丢弃例外。

**验证：** `./gradlew test --tests '*StaleCleanupTest'`，只有符合全部三层的夹具删除，实际清理数量准确。
**覆盖：** F12、N4、N9、AC19、AC20、AC21、AC22。

## T58：后台调度与非 Git 项目兼容

**文件：** `tui/MewCodeModel.java`、`worktree/StaleCleanup.java`、`worktree/WorktreeManager.java`；测试 `tui/MewCodeModelTest.java`、`worktree/StaleCleanupTest.java`。
**依赖：** T4、T55、T57。
**步骤：**

1. 默认 30 分钟触发、24 小时过期，用配置覆盖；一次最多一个扫描，不在 UI 锁内执行。
2. 没有已登记资源时扫描不强制 Git 能力检测；缺 Git/非 Git 项目普通会话可启动，仅 Worktree 请求失败。
3. 应用关闭停止后续调度，有界取消当前扫描并保留不确定资源；单资源失败不结束整个扫描。

**验证：** `./gradlew test --tests '*MewCodeModelTest' --tests '*StaleCleanupTest'`，可控调度无重叠，关闭和单项故障不误删。
**覆盖：** F12、N7、N8、N9、AC20、AC21、AC25、AC26。

## T59：验证故障结果与凭据不泄露

**文件：** 测试 `worktree/WorktreeManagerTest.java`、`worktree/PostCreationSetupTest.java`、`worktree/GitCommandRunnerTest.java`、`subagent/SubAgentRuntimeTest.java`；必要修复限定在对应生命周期源码。
**依赖：** T8、T15、T32、T42、T52、T54、T57。
**步骤：**

1. 用测试凭据分别注入 Git、配置复制、初始化、成果检测及部分删除故障，收集日志、ToolResult 和任务通知。
2. 验证没有配置正文、凭据或未经处理的 Git stderr，同时保留阶段、路径、退出码与可操作原因。
3. 对比实际目录和分支残留，确认失败不报告成功、清理不覆盖原子任务结果。

**验证：** `./gradlew test --tests '*WorktreeManagerTest' --tests '*PostCreationSetupTest' --tests '*GitCommandRunnerTest' --tests '*SubAgentRuntimeTest'`，故障及日志断言通过。
**覆盖：** F13、N4、N5、N6、AC8、AC19、AC22、AC24。

## T60：验证未启用隔离的行为兼容

**文件：** 测试 `MewCodeTest.java`、`tui/MewCodeModelTest.java`、`subagent/SubAgentRuntimeTest.java`、`agent/ToolPolicyTest.java`、`skill/ScriptToolTest.java`、`permission/PermissionGateTest.java`。
**依赖：** T39、T45、T53、T55、T56、T58。
**步骤：**

1. 验证旧配置、普通主对话、NONE 子角色、原 Skill cwd、原 MCP 及权限/Hook 行为。
2. 用无 Git 的测试进程环境及非 Git 目录启动普通会话，仅请求 Worktree 时返回明确失败。
3. 检查用户 Memory 与原 Session 历史位置未扩大或迁移，隔离被禁用不意味着原安全规则被禁用。

**验证：** `./gradlew test --tests '*MewCodeTest' --tests '*MewCodeModelTest' --tests '*SubAgentRuntimeTest' --tests '*ToolPolicyTest' --tests '*ScriptToolTest' --tests '*PermissionGateTest'`，旧流程及负例通过。
**覆盖：** N3、N8、AC11、AC15、AC23、AC26。

## T61：完成构建、格式及整体回归

**文件：** 本次新增/修改 Java 文件、`build.gradle.kts`；实际结果写入 `docs/ch14/checklist.md`。
**依赖：** T1～T60。
**步骤：**

1. 检查本次文件的格式范围及 git diff，不格式化无关文件，不引入新依赖或 live 配置修改。
2. 运行整体测试、格式和打包，核对测试报告及 `build/libs/mewcode.jar`；修复本次引入的失败后仅重跑受影响检查。
3. 区分自动化通过、实际 macOS 沙箱通过与 Linux 未实测，记录环境限制，不把跳过记作通过。

**验证：** `./gradlew spotlessCheck test shadowJar`、`git diff --check` 通过；报告无本次未解决失败，产物存在。
**覆盖：** N10、AC26。

## T62：编写配置示例与运行说明

**文件：** `.mewcode/config.yaml.example`、`docs/ch14/README.md`。
**依赖：** T54、T56、T58、T61。
**步骤：**

1. 增加可选 worktree 配置及 `isolation: worktree` 角色示例，保持示例不生效，不改实际配置或用户角色。
2. 说明创建与进入分离、KEEP 退出、成果保护、只读依赖、Hooks 环境范围及 MCP 限制。
3. 给出本章 tmux 复现用的临时仓库、忽略运行配置、已知本地远端引用和独立会话准备步骤；只记录路径与结果，不记录凭据。

**验证：** 对照实际参数与默认值逐项检查示例，运行 `git diff --check`；确认实际 config.yaml、用户角色及 AGENTS.md 无改动。
**覆盖：** F7、F9、F11、N5、N8、N10、AC24、AC27、AC28。

## T63：tmux 验证父子并行修改同名文件

**文件：** 临时测试仓库及角色；证据写入 `docs/ch14/checklist.md`。
**依赖：** T61、T62。
**步骤：**

1. 在 tmux 中以真实 JAR 启动 MewCode，使用独立测试 Session 和声明隔离的后台角色，保持现有凭据不输出。
2. 输入真实请求：让后台子 Agent 修改测试文件，同时主 Agent 在原目录修改同名文件；观察实际 Agent/读写/命令工具调用。
3. 检查父/子两个文件内容独立，结果含保留路径、分支和原因，没有自动合并或同步；记录对话片段和最终落点。

**验证：** 通过真实 tmux 交互及目录/分支检查取得 AC27 证据；不能以测试桩、直接调用运行时或注释代码代替真实对话。
**覆盖：** F1、F5、F9、F10、N10、AC27。

## T64：tmux 验证主会话进入与 KEEP 退出

**文件：** 临时测试仓库；证据写入 `docs/ch14/checklist.md`。
**依赖：** T63。
**步骤：**

1. 输入真实对话创建并进入手动工作树，观察 Worktree 工具、当前目录及分支显示。
2. 请求读取或写入相对路径测试文件，确认实际落在当前副本。
3. 请求保留后退出，再操作同名文件，确认返回原目录且手动资源仍在。

**验证：** tmux 对话、工具 cwd、显示和磁盘内容共同证明切换正确，满足 AC28 的手动管理部分。
**覆盖：** F5、F7、F13、N10、AC28。

## T65：tmux 验证只读子任务自动清理

**文件：** 临时测试仓库及只读隔离角色；证据写入 `docs/ch14/checklist.md`。
**依赖：** T63。
**步骤：**

1. 使用运行文件已忽略、基线被本地已知远端引用包含的夹具，输入真实只读子任务请求。
2. 观察子 Agent 实际调用和完成回流，确认任务没有生成待保护的修改或提交。
3. 检查临时目录及分支均删除，完成通知没有提前发布；无已知远端引用的夹具应保留，不能为使测试通过放松保护。

**验证：** 真实对话和最终目录/分支状态证明安全自动清理，满足 AC28 的只读任务部分。
**覆盖：** F10、F13、N10、AC28。

## T66：tmux 验证有成果时默认拒绝删除

**文件：** 临时测试仓库；证据写入 `docs/ch14/checklist.md`。
**依赖：** T64。
**步骤：**

1. 进入测试工作树，留下真实文件修改，输入退出并删除请求，观察默认保护错误。
2. 检查 cwd、现场、目录、分支与修改均保留，随后真实对话选择 KEEP 退出应成功。
3. 仅在临时夹具中验证明确 --discard 用户请求与模型仅传 discardChanges 的区别，记录实际结果；不丢弃用户项目成果。

**验证：** tmux 工具回复与磁盘状态证明默认保护及可信授权边界，满足 AC28 的拒绝删除部分。
**覆盖：** F7、F11、F13、N3、N10、AC18、AC28。

## T67：逐项验收并交付

**文件：** `docs/ch14/checklist.md`、`docs/ch14/README.md`；不额外改源码。
**依赖：** T59、T60、T61、T62、T63、T64、T65、T66。
**步骤：**

1. 对照 AC1～AC28 逐项附命令、测试方法或 tmux 证据，只有实际通过的项目勾选。
2. 记录未执行平台、既有失败和其他实际限制；有本期必需项未完成则继续修复验证，不宣称功能完成。
3. 清点测试资源和最终工作区，仅处理已确认无成果的夹具；向用户交付使用方法和真实验收结果，不自动合并或推送。

**验证：** checklist 中每个验收项有可追溯证据，真实 tmux 场景齐全；运行 `git diff --check`，无无关或 live 配置改动。
**覆盖：** F13、N10、AC1～AC28。

## 执行顺序

默认 T1 → T2 → … → T67 顺序执行。各项依赖均指向先前任务，没有循环；前三份批准不意味着可以跳过 checklist 审批。

| 阶段 | 任务 | 阶段结果 |
|------|------|----------|
| 基础与生命周期 | T1～T21 | 名称、配置、进程、记录、锁、创建/初始化及只读恢复 |
| 目录与删除安全 | T22～T43 | 快照与资源、进入退出、成果保护、沙箱、Hook 收口及工具原序 |
| 运行时与清理接入 | T44～T60 | Memory/结果目录、角色隔离、任务回流、主入口、扫描与兼容 |
| 实际验证与交付 | T61～T67 | 构建回归、说明、四个 tmux 场景及逐项证据 |

## Plan 覆盖与任务自检

| Plan 组件或边界 | 对应任务 |
|-----------------|----------|
| SlugValidator | T3、T6 |
| WorktreeConfig | T4 |
| WorktreeSession / WorktreeSessionStore / WorktreeException | T5、T9、T10、T27、T32、T59 |
| GitCommandRunner | T7、T8 |
| WorktreeManager / WorktreeInfo | T11～T14、T20、T21、T25～T27、T30～T32 |
| PostCreationSetup | T15～T20 |
| AgentWorkspace / ToolExecutionContext / 文件缓存 | T22～T26、T33～T35、T44～T46 |
| WorktreeChanges / ChangeSummary | T28、T29 |
| 工具、搜索、命令及系统沙箱 | T24、T33～T39、T43、T53、T54 |
| Hook 与当前目录删除的两阶段处理 | T40～T42 |
| AgentWorktree / Result / IsolationMode | T47～T52 |
| 主会话工具、命令、显示及状态恢复 | T25～T27、T54～T56 |
| StaleCleanup 与后台调度 | T57、T58 |
| 初始化回滚、部分残留、安全错误与兼容 | T20、T31、T32、T59、T60 |
| 构建、示例、真实 tmux 及证据 | T1、T2、T61～T67 |

自检结果：T1～T67 编号连续，依赖均指向先前任务，无循环；全部 Plan 组件至少有一项任务，每项均有具体文件、前置条件、步骤、验证及覆盖。F1～F13、N1～N10、AC1～AC28 均有实现或专门验收任务，源码与现有测试路径已核对。核心类型和方法沿用 Plan，未加入合并、同步、并行编排或 MCP 服务池。以上只表示任务文档检查通过，不代表实现或测试已经完成。
