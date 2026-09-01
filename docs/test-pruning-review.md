# 回归测试精简审阅与执行记录

## 结论

- 删除前基线：61 个测试类、241 个测试、8,473 行测试代码。
- 已确认删除：6 个测试、1 个测试文件、139 行测试代码；生产代码零修改。
- 删除后基线：60 个测试类、235 个测试；`./gradlew test spotlessCheck` 于 2026-09-01 通过。
- 另有 3 组测试适合后续折叠，但在补上等价断言前不应直接删除。

## 判定标准

只有同时满足以下条件的测试才进入“直接删除”名单：

1. 不验证独立业务规则、安全边界、数据原子性或并发行为；
2. 相同行为已由更接近真实调用链的测试覆盖，或该测试实际上没有证明其名称声称的性质；
3. 删除后不需要修改生产代码；
4. 现有 spec/checklist 若引用该测试，能改用已经存在的等价证据。

## A 组：已确认并删除

| 优先级 | 标签 | 测试 | 原因 | 保留下来的等价/更强保护 | 预计净减少 |
|---|---|---|---|---|---:|
| 1 | `delete:` | `FakeLlmClientTest.recordsStructuredRequestsAndReturnsResponsesInOrder`（整个文件） | 只测试测试桩自身的队列和请求记录；测试桩不属于产品行为，并已被 4 个生产模块测试类反复实际使用。 | `MemoryManagerTest`、`ContextManagerTest`、`ConversationCompactorTest`、`SessionManagerTest` 对该 fake 的真实消费会直接暴露其失效。 | 28 行 |
| 2 | `delete:` | `AgentEventTest.exposesTheContextCompactionEventKinds` | 构造 Java record 后断言其类型和 getter 返回构造参数，主要重复编译器保证；这些事件已在 Agent Loop、流收集器和 TUI 测试中被真实产生和消费。 | 保留同文件的 `makesToolInputImmutableAndCanRepresentUnknownUsage`；`AgentLoopTest`、`TurnStreamCollectorTest`、`MewCodeModelTest` 继续覆盖事件链路。 | 约 32 行（含无用 import） |
| 3 | `delete:` | `CancellableLlmStreamTest.exposesEventsProducedByTheProvider` | 只验证预先放入 `BlockingQueue` 的对象可被 `next()` 取出，没有覆盖关闭、等待或取消分支。 | 保留 `closeIsIdempotentAndInvokesProviderCloseOnce`；`TurnStreamCollectorTest`、Provider 测试和 Agent Loop 测试均通过 `next()` 消费真实事件序列。 | 8 行 |
| 4 | `delete:` | `PromptRequestFactoryTest.snapshotsToolDefinitionsAtFactoryBoundary` | 只做输入列表与输出列表相等断言，没有修改输入，因此并未证明“snapshot”。 | `PromptRequestTest.takesImmutableSnapshotsAndKeepsReminderSeparateFromHistory` 会修改原始嵌套 Map，并验证深层不可变快照。 | 9 行 |
| 5 | `delete:` | `PromptRequestFactoryTest.injectsDynamicMemoryAndOneShotReminderIntoRequestSnapshot` | 使用反射调用已经存在的公开 API；对 memory segment、resume reminder、history 的断言已被真实协调器链路覆盖。 | `AgentTurnCoordinatorPromptTest.injectsDynamicPromptAdditionsAndNotifiesAfterCompletedTurn` 覆盖同样三项，并额外验证完成回调。 | 约 37 行（含专用反射 helper/import） |
| 6 | `delete:` | `PromptBuilderTest.putsLoadedInstructionsIntoTheCustomInstructionModule` | 反射调用 `buildBundle(root, text)` 后只断言传入字符串仍存在；没有测试“加载”，名称比实际覆盖范围更大。 | `InstructionLoaderTest.loadsInstructionLayersInPriorityOrder` 验证加载；`MewCodeModelTest.loadsProjectInstructionsBeforeCreatingProvider` 验证加载结果进入真实 Provider prompt。 | 约 20 行（含专用反射 helper/import） |

A 组已全部删除，测试数从 241 降到 235，测试文件从 61 降到 60。测试运行时间收益很小；主要收益是减少重复维护和误导性测试名称。

### 文档引用同步

A5、A6 删除后已同步两处证据文本，验收项本身不变：

- `docs/ch9/checklist.md:21`：把 `PromptBuilderTest.putsLoadedInstructionsIntoTheCustomInstructionModule` 替换为 `MewCodeModelTest.loadsProjectInstructionsBeforeCreatingProvider`。
- `docs/ch9/checklist.md:163`：把 `PromptRequestFactoryTest.injectsDynamicMemoryAndOneShotReminderIntoRequestSnapshot` 替换为 `AgentTurnCoordinatorPromptTest.injectsDynamicPromptAdditionsAndNotifiesAfterCompletedTurn`，并继续保留 `SessionManagerTest.marksSessionStaleAfterTwentyFourHours`。

## B 组：先折叠或补等价断言，再删除

这些测试有冗余，但直接删除会丢掉一小块当前唯一的显式覆盖，因此本轮不列入安全删除批次。

| 标签 | 测试 | 建议 |
|---|---|---|
| `shrink:` | `AgentTurnCoordinatorPromptTest.routesRememberRequestsToMemoryInsteadOfProjectInstructionFiles` | 当前只是启动完整 Agent 后检查两段静态 Prompt 文本，用户输入并不影响断言。将这两个文本断言并入 `PromptBuilderTest` 后，可删除约 30 行集成样板。 |
| `shrink:` | `PromptRequestFactoryTest.modeSwitchCanForceTheNextReminderToBeCompleteWithoutChangingHistory` | 目前只验证调用方显式传入 `forceFull=true`，没有验证 `/plan` 或 `/do` 确实设置该值；且最后一个 `assertNotEquals` 信息量很低。应先在 TUI 模式切换测试中验证下一次真实请求收到完整 reminder，再删除本测试。 |
| `shrink:` | memory 意图识别的 5 个正反例测试 | `memoryOnlyRequestsDoNotExposeFileTools`、`commonMemoryPhrasesDoNotExposeFileTools` 以及三个“保留工具”测试包含大量相同的 coordinator 构造代码。应保留全部输入样本，但改成一个参数化测试或正反例表；这是去样板，不是删业务案例。 |

## 明确保留

以下测试即使看起来细碎，也承载独立风险，不应因“数量多”删除：

- 权限、危险命令、路径越界、符号链接、Bash 沙箱和文件写入前置校验；
- JSONL 坏行恢复、工具调用配对、compact 边界、memory 跨层级校验和事务式提交；
- 取消竞态、异步 memory 关闭等待、并发/串行工具执行；
- Anthropic/OpenAI/MCP 协议适配和本地 HTTP 集成；
- `ReadFileToolTest.readsTheRepositorySkillFileWhenGivenItsAbsolutePath`：虽然依赖仓库 fixture，但它是 ch3 bugfix2 AC6 的明确验收证据，不能按普通重复读取测试删除；
- `PromptBuilderTest.exposesSevenFixedModulesInPriorityOrderAndThreeEmptySlots`：精确结构来自 ch5 F1/AC1，不是偶然实现细节；
- `ToolPromptRulesTest` 与 `ToolRegistryTest.apiDescriptionsRepeatTheCriticalToolRulesWithoutChangingSchemas`：前者验证规则拼接不覆盖原描述，后者验证规则确实进入 Provider schema，层次不同。

## 删除批次的验证结果

- `./gradlew test spotlessCheck`：`BUILD SUCCESSFUL`，235 个测试通过。
- `./gradlew shadowJar`：`BUILD SUCCESSFUL`。
- `git diff --check`：通过。
- tmux smoke：真实 MewCode 启动后输入“请读取 README 并告诉我项目状态”，观察到 `ReadFile` 调用、带行号工具结果、第二轮最终答复“已读取项目文件。”，并正常回到输入状态；验收会话已清理。

net: `-139 行测试代码，-1 个测试文件，-6 个测试，-0 个依赖`。
