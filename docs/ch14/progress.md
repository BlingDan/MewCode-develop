# MewCode Worktree 开发进度

> 任务基线：docs/ch14/task.md；Spec、Plan、Tasks、Checklist 已全部批准。
> 工作目录：/Users/li/code/MewCode-develop；用户当前分支 feature/worktree。
> 起始提交：ffae6f371222aceaf4d210b2add6982ed0ff56bd。

## 执行决定

- 沿用用户当前 feature/worktree 分支及已批准文档，单 Agent 在当前 checkout 实现；产品 Worktree 测试使用独立临时仓库，不修改用户仓库进行破坏性测试。
- 以 mew-spec 的任务编号记录执行，不使用要求不同标题格式的额外任务脚本。核心类型、接口及安全边界按已批准 Plan。
- JDK 21 已存在于 /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home；仅通过每条构建命令的 JAVA_HOME/PATH 指定，不修改系统 Java 配置。

## 任务状态

| 任务 | 内容 | 状态 | 证据 |
|------|------|------|------|
| T1 | 记录实现前基线 | 完成 | 基线构建通过，见下文 |
| T2 | 建立真实 Git 测试夹具及格式范围 | 完成 | 定向测试与 Spotless 通过 |
| T3 | 实现名称与分支格式校验 | 完成 | 定向测试与 Spotless 通过 |
| T4 | 绑定可选 Worktree 配置 | 完成 | 定向测试与 Spotless 通过 |
| T5 | 定义会话记录与安全异常 | 完成 | 存储、路径、进程和恢复测试通过 |
| T6 | 保护存放区域的真实路径 | 完成 | 存储、路径、进程和恢复测试通过 |
| T7 | 实现显式 cwd 的 Git 执行器 | 完成 | 存储、路径、进程和恢复测试通过 |
| T8 | 确认 Git 超时及取消后的实际退出 | 完成 | 存储、路径、进程和恢复测试通过 |
| T9 | 原子保存会话与内部资源记录 | 完成 | 存储、路径、进程和恢复测试通过 |
| T10 | 只读验证 Git 指针与资源归属 | 完成 | 存储、路径、进程和恢复测试通过 |
| T11 | 实现每资源占用与跨进程锁 | 完成 | 生命周期真实 Git 定向测试通过 |
| T12 | 固定创建来源与提交基线 | 完成 | 生命周期真实 Git 定向测试通过 |
| T13 | 登记并验证管理区域忽略规则 | 完成 | 生命周期真实 Git 定向测试通过 |
| T14 | 创建独立分支及工作树 | 完成 | 生命周期真实 Git 定向测试通过 |
| T15 | 安全复制本地配置和必要文件 | 完成 | 生命周期真实 Git 定向测试通过 |
| T16 | 配置已启用 worktreeConfig 的 Git Hooks | 完成 | 生命周期真实 Git 定向测试通过 |
| T17 | 保存未启用扩展时的 Hooks 环境覆盖 | 完成 | 生命周期真实 Git 定向测试通过 |
| T18 | 建立只读共享依赖 | 完成 | 生命周期真实 Git 定向测试通过 |
| T19 | 按 Git ignore 规则补充运行文件 | 完成 | 生命周期真实 Git 定向测试通过 |
| T20 | 提交初始化完成状态 | 完成 | 生命周期真实 Git 定向测试通过 |
| T21 | 实现零 Git 调用的快速恢复 | 完成 | 生命周期真实 Git 定向测试通过 |
| T22 | 提供每 Agent 的不可变调用快照 | 完成 | 生命周期真实 Git 定向测试通过 |
| T23 | 按绝对 cwd 取得项目提示词与定义 | 完成 | 生命周期真实 Git 定向测试通过 |
| T24 | 让工具校验、权限和执行共用一次捕获 | 完成 | 生命周期真实 Git 定向测试通过 |
| T25 | 原子进入工作树 | 完成 | 生命周期真实 Git 定向测试通过 |
| T26 | 保留后退出且保护旧调用 | 完成 | 生命周期真实 Git 定向测试通过 |
| T27 | 恢复有效的主会话目录状态 | 完成 | 生命周期真实 Git 定向测试通过 |
| T28 | 统计未提交修改与新增提交 | 完成 | 生命周期真实 Git 定向测试通过 |
| T29 | 检查本地已知远端引用 | 完成 | 生命周期真实 Git 定向测试通过 |
| T30 | 默认受保护删除 | 完成 | 生命周期真实 Git 定向测试通过 |
| T31 | 绑定可信的用户丢弃授权 | 完成 | 生命周期真实 Git 定向测试通过 |
| T32 | 记录部分删除并安全处理初始化失败 | 完成 | 生命周期真实 Git 定向测试通过 |
| T33 | 在应用层落实不可扩大的路径范围 | 完成 | 组合回归重跑与 Spotless 通过，见下文 |
| T34 | 排除父目录搜索中的其他副本 | 完成 | 组合回归重跑与 Spotless 通过，见下文 |
| T35 | 推导命令沙箱的读写范围 | 完成 | 组合回归重跑与 Spotless 通过，见下文 |
| T36 | 在 macOS 沙箱中执行隔离范围 | 完成 | 组合回归重跑与 Spotless 通过，见下文 |
| T37 | 在 Linux 沙箱中表达隔离范围 | 完成 | 组合回归重跑与 Spotless 通过，见下文 |
| T38 | 命令携带固定 cwd 与 Hooks 环境 | 完成 | 组合回归重跑与 Spotless 通过，见下文 |
| T39 | 隔离 Skill 脚本的执行目录 | 完成 | 组合回归重跑与 Spotless 通过，见下文 |
| T40 | Hook 入队前固定目录与使用权 | 完成 | 组合回归重跑与 Spotless 通过，见下文 |
| T41 | Hook 取消后仅在实际结束时释放 | 完成 | 组合回归重跑与 Spotless 通过，见下文 |
| T42 | 实现当前目录删除的两阶段流程 | 完成 | 同步及异步 Post Hook 两阶段测试通过 |
| T43 | 保持工具批次的原始调用顺序 | 完成 | 先观察错误顺序，再通过协调器与 Hook 回归 |
| T44 | 固定项目 Memory 的目标目录 | 完成 | Memory、外置结果和上下文定向回归通过 |
| T45 | 保护用户 Memory 并保持子任务只读 | 完成 | Memory、外置结果和上下文定向回归通过 |
| T46 | 按调用目录外置结果且保留预算 | 完成 | Memory、外置结果和上下文定向回归通过 |
| T47 | 解析角色的 isolation 声明 | 完成 | 默认 NONE、WORKTREE 与非法值和 Catalog 回归通过 |
| T48 | 在锁外固定子任务派发现场 | 完成 | 子任务适配、冻结基线、收尾与成果回流回归通过 |
| T49 | 实现 AgentWorktree 轻量适配 | 完成 | 子任务适配、冻结基线、收尾与成果回流回归通过 |
| T50 | 在子目录构造独立运行资源 | 完成 | 子任务适配、冻结基线、收尾与成果回流回归通过 |
| T51 | 收尾后再发布子任务完成结果 | 完成 | 子任务适配、冻结基线、收尾与成果回流回归通过 |
| T52 | 回流自动清理或保留的实际结果 | 完成 | 子任务适配、冻结基线、收尾与成果回流回归通过 |
| T53 | 限制工作树会话的工具能力 | 完成 | 工具发现/直接调用限制及五动作生命周期回归通过 |
| T54 | 实现主会话 Worktree 工具参数及结果 | 完成 | 工具发现/直接调用限制及五动作生命周期回归通过 |
| T55 | 主会话接入当前目录及自然语言工具 | 待执行 | 尚未执行 |
| T56 | 接入斜杠命令及明确丢弃要求 | 待执行 | 尚未执行 |
| T57 | 实现临时资源的三层清理 | 待执行 | 尚未执行 |
| T58 | 后台调度与非 Git 项目兼容 | 待执行 | 尚未执行 |
| T59 | 验证故障结果与凭据不泄露 | 待执行 | 尚未执行 |
| T60 | 验证未启用隔离的行为兼容 | 待执行 | 尚未执行 |
| T61 | 完成构建、格式及整体回归 | 待执行 | 尚未执行 |
| T62 | 编写配置示例与运行说明 | 待执行 | 尚未执行 |
| T63 | tmux 验证父子并行修改同名文件 | 待执行 | 尚未执行 |
| T64 | tmux 验证主会话进入与 KEEP 退出 | 待执行 | 尚未执行 |
| T65 | tmux 验证只读子任务自动清理 | 待执行 | 尚未执行 |
| T66 | tmux 验证有成果时默认拒绝删除 | 待执行 | 尚未执行 |
| T67 | 逐项验收并交付 | 待执行 | 尚未执行 |

## 证据记录

- T1：初次 java/Gradle 因默认 PATH 找不到 Java 失败；找到 Homebrew JDK 21。sandbox 内 Gradle cache lock 不允许，授权运行构建后退出码 0。
- T1：使用显式 JAVA_HOME/PATH 运行 `./gradlew spotlessCheck test shadowJar`，BUILD SUCCESSFUL in 30s；日志 /private/tmp/mewcode-ch14-baseline.log；334 tests，0 failures，0 errors，0 skipped。
- T1：Git 2.50.1，tmux 3.7c，JDK 21.0.12.1；用户已有 .idea/gradle.xml 改动保持不变。产物 build/libs/mewcode.jar。

- T2–T4：先观察 SlugValidator 缺失、worktree 配置无法加载的失败，再实现；`spotlessApply test --tests "*SlugValidatorTest" --tests "*ConfigLoaderTest" --tests "*GitCommandRunnerTest" spotlessCheck` 通过，日志 /private/tmp/mewcode-ch14-t2-t4-green.log。

- T5–T10：会话独立原子写入、损坏与软链拒绝、实际 Git cwd/参数/超时退出、恢复只读指针验证通过；曾观察到软链 HEAD 被接受的失败并修复。日志 /private/tmp/mewcode-ch14-t5-t10-green.log。

- T11–T21：真实创建、同名竞争、另一 JVM 锁、冻结 SHA、父当前副本来源、精确忽略冲突、配置/include、共享链接、两种 Hooks 设置及实际 Hook 执行、必要失败和零 Git 恢复均通过。
- T22–T27：绝对目录提示词资源、不可变 ToolExecutionContext、校验期间 KEEP 切换、旧调用使用权、进入/退出与有效主会话恢复均通过。处理了 macOS /var 与 /private/var 别名导致现场路径不一致的失败。
- T28–T32：NUL 状态、未跟踪文件、创建基线新增提交、已知远端/缺失引用、默认拒绝与可信身份丢弃、分支删除故障后的 PARTIAL 真实残留、仅本次新建资源的安全回滚通过。恢复拒绝不补写或更新时间。
- 以上定向测试与 Spotless 日志：/private/tmp/mewcode-ch14-lifecycle-green.log。模型、UI、子 Agent、命令沙箱和异步 Hook 接入仍属于后续任务；本阶段通过不代表最终端到端验收完成。

- T33–T34：已有 externalPathAuthorized 仍不能写父目录、共享依赖或管理记录；父 Grep 不含副本，进入后可以搜索自己的代码。权限上下文同时绑定不可扩大的 Scope。
- T35–T39：本机真实 Seatbelt 验证自己的 Git 提交成功，父文件/共享依赖/共享 config/其他分支写入及父源码读取拒绝；环境模式 Hooks 在子目录触发，Skill 资产可读且相对输出写到子目录。根因修复：Git 需要父目录元数据查询，内容读取仍禁止。
- T37：Linux 挂载参数的遮蔽及只读覆盖顺序测试通过；本机 macOS 未运行真实 bubblewrap，不将参数检查记为 Linux 实测。
- T40–T41：阻塞异步 Hook 后 KEEP 退出，cancel(true) 仍保留实际运行体的资源使用权；真实结束后释放，落点保持旧 cwd，随后无成果资源可删除。
- 独立定向测试曾通过；组合回归 /private/tmp/mewcode-ch14-isolation-hooks-green.log 实际 77 tests / 1 failure，待修复重跑；入口、子任务收尾及两阶段当前目录删除尚在后续任务，端到端未验收。

- T33–T41 复验：原失败仅发生在夹具的 git init，尚未进入产品逻辑；单例与同一组合分别重跑通过，日志 /private/tmp/mewcode-ch14-init-timeout-recheck.log、/private/tmp/mewcode-ch14-isolation-hooks-recheck.log。未放宽产品超时。
- T42：Post Hook 在旧 cwd 只收到 prepared 结果且执行一次，真正结束后才删除；异步 Post Hook 未结束时拒绝提交删除并保留会话。日志 /private/tmp/mewcode-ch14-t42-recheck.log，BUILD SUCCESSFUL，Spotless 通过。

- T43：失败测试实测 first/last/agent，修复后 first/agent/last；协调器与 Hook 回归以及 Spotless 通过，日志 /private/tmp/mewcode-ch14-t43-green.log。

- T44–T46：项目 Memory 实例绑定绝对目录并复用，两个项目并发更新用户索引不丢记录，子只读索引不建目录不修剪；大型结果按调用现场 cwd 路由。上下文及执行器回归、Spotless 通过，日志 /private/tmp/mewcode-ch14-t44-t46-green.log。

- T47：角色解析及 Catalog、Spotless 通过，日志 /private/tmp/mewcode-ch14-t47-green.log。

- T48–T52：父提交在派发时冻结，后续父提交移动不改变子基线；启动工厂及收尾在全局锁外。子执行器/缓存/提示/Memory/Hook/Skill 目录独立，启动取消沿用独立 token。只读无成果在返回前清理，成功及 Provider 失败后的修改保留并回流路径分支。日志 /private/tmp/mewcode-ch14-child-runtime-green.log，BUILD SUCCESSFUL，Spotless 通过。

- T53–T54：隔离范围先于策略豁免拦截 MCP/ToolSearch；子任务策略禁止主 Worktree 工具。Worktree 五动作、创建与进入分离、模型丢弃伪造拒绝、Post Hook 两阶段回归通过，日志 /private/tmp/mewcode-ch14-t53-t54-green.log。
