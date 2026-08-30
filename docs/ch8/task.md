# MewCode 上下文管理 Tasks

> 状态：已确认，已实现并完成验收
>
> 本任务清单基于已确认的 /Users/li/code/MewCode-develop/docs/ch8/spec.md 和 /Users/li/code/MewCode-develop/docs/ch8/plan.md。
>
> 四份文档已在实现前完成确认；Java 功能已按本清单实现。

## 文件清单

| 操作 | 文件 | 职责 |
|------|------|------|
| 新建 | src/main/java/com/mewcode/compact/ContextManager.java | 上下文策略唯一入口，统一编排两层压缩、usage 和 session 生命周期 |
| 新建 | src/main/java/com/mewcode/compact/ContextRequest.java | 请求上下文快照 |
| 新建 | src/main/java/com/mewcode/compact/ContextTrigger.java | AUTO、MANUAL、EMERGENCY 触发来源 |
| 新建 | src/main/java/com/mewcode/compact/ContextPreparation.java | 请求预检结果 |
| 新建 | src/main/java/com/mewcode/compact/CompactResult.java | 压缩结果 |
| 新建 | src/main/java/com/mewcode/compact/ContextException.java | 上下文管理失败类型 |
| 新建 | src/main/java/com/mewcode/compact/TokenEstimator.java | 字符数和 usage 锚点近似估算 |
| 新建 | src/main/java/com/mewcode/compact/ToolResultExternalizer.java | 第一层工具结果外置和预览 |
| 新建 | src/main/java/com/mewcode/compact/ConversationCompactor.java | 尾部选择、摘要请求、结构校验和历史替换 |
| 新建 | src/main/java/com/mewcode/compact/AutoCompactFuse.java | 自动摘要三次失败熔断 |
| 新建 | src/test/java/com/mewcode/testsupport/FakeLlmClient.java | 单元测试用 Provider 请求记录和确定性响应 |
| 新建 | src/test/java/com/mewcode/testsupport/FakeCancellableLlmStream.java | 单元测试用可取消流 |
| 新建 | src/test/java/com/mewcode/compact/ToolResultExternalizerTest.java | 第一层阈值、预览、文件和清理测试 |
| 新建 | src/test/java/com/mewcode/compact/TokenEstimatorTest.java | Token 近似和 usage 锚点测试 |
| 新建 | src/test/java/com/mewcode/compact/ConversationCompactorTest.java | 摘要输入、尾部保留和历史替换测试 |
| 新建 | src/test/java/com/mewcode/compact/AutoCompactFuseTest.java | 自动摘要熔断测试 |
| 新建 | src/test/java/com/mewcode/compact/ContextManagerTest.java | ContextManager 编排测试 |
| 修改 | src/main/java/com/mewcode/config/ProviderConfig.java | Provider 上下文窗口配置 |
| 修改 | src/main/java/com/mewcode/llm/StreamEvent.java | 四维 usage 和上下文错误类型 |
| 修改 | src/main/java/com/mewcode/agent/CollectedTurn.java | 流尾 usage 和错误类型传递 |
| 修改 | src/main/java/com/mewcode/agent/TurnStreamCollector.java | 捕获四维 usage 和错误类型 |
| 修改 | src/main/java/com/mewcode/agent/TokenUsageAccumulator.java | 保持现有普通 TUI usage 兼容 |
| 修改 | src/main/java/com/mewcode/conversation/ConversationManager.java | 原子替换正式历史 |
| 修改 | src/main/java/com/mewcode/agent/PromptRequestFactory.java | 构造 ContextRequest 和最新 history |
| 修改 | src/main/java/com/mewcode/agent/AgentTurnCoordinator.java | 接入预检、第一层提交和硬超限恢复 |
| 修改 | src/main/java/com/mewcode/agent/AgentEvent.java | 压缩事件和 CONTEXT 错误类别 |
| 修改 | src/main/java/com/mewcode/llm/AnthropicClient.java | usage 透传和 prompt_too_long 映射 |
| 修改 | src/main/java/com/mewcode/llm/OpenAiClient.java | usage 透传和 prompt_too_long 映射 |
| 修改 | src/main/java/com/mewcode/tui/MewCodeModel.java | Provider 注入、/compact 和上下文 UI |
| 修改 | src/test/java/com/mewcode/config/ConfigLoaderTest.java | Provider 窗口配置测试 |
| 修改 | src/test/java/com/mewcode/llm/AnthropicClientTest.java | Anthropic usage 和错误映射测试 |
| 修改 | src/test/java/com/mewcode/llm/OpenAiClientTest.java | OpenAI usage 和错误映射测试 |
| 修改 | src/test/java/com/mewcode/agent/TurnStreamCollectorTest.java | 流尾 usage 和错误类型测试 |
| 修改 | src/test/java/com/mewcode/agent/AgentTurnCoordinatorTest.java | Loop 压缩、工具提交和恢复测试 |
| 修改 | src/test/java/com/mewcode/agent/AgentLoopTest.java | 轮次、重试和错误收口测试 |
| 修改 | src/test/java/com/mewcode/agent/AgentEventTest.java | 上下文事件测试 |
| 修改 | src/test/java/com/mewcode/conversation/ConversationManagerTest.java | 历史替换测试 |
| 修改 | src/test/java/com/mewcode/tui/MewCodeModelTest.java | /compact、UI 和关闭测试 |

## T1: 建立现有行为基线

**文件：** 现有 Gradle 项目和测试目录

**依赖：** 无

**步骤：**

1. 在未修改 Java 实现的状态下运行全量测试。
2. 记录测试总数、失败数和构建结果。
3. 确认当前测试没有依赖真实 Provider 网络。
4. 检查 docs/ch8 之外没有本功能相关的未提交文件。

**验证：** 运行 ./gradlew test，期望现有测试全部通过，且没有新增失败。

## T2: 建立确定性 Provider 测试替身

**文件：** src/test/java/com/mewcode/testsupport/FakeLlmClient.java、src/test/java/com/mewcode/testsupport/FakeCancellableLlmStream.java

**依赖：** T1

**步骤：**

1. 定义可按顺序消费预置 StreamEvent 的 FakeCancellableLlmStream。
2. 定义 FakeLlmClient，记录每次 PromptRequest、工具列表、调用次数和请求顺序。
3. 让测试替身能返回正常结束、工具调用、摘要文本、普通错误和 CONTEXT_LENGTH 错误。
4. 提供读取最近一次请求 history 的测试方法。
5. 不连接网络，不读取 API key，不写入项目目录之外的文件。

**验证：** 编译测试代码并运行一个最小 fake stream 测试，期望事件顺序和 PromptRequest 快照都可断言。

## T3: 增加 Provider 上下文窗口配置

**文件：** src/main/java/com/mewcode/config/ProviderConfig.java、src/test/java/com/mewcode/config/ConfigLoaderTest.java

**依赖：** T1

**步骤：**

1. 在 ProviderConfig 增加 contextWindowTokens 属性和 getter/setter。
2. 保留现有构造、字段映射和 toString 脱敏行为。
3. 为缺省配置、正数配置和非正数配置增加测试。
4. 验证 YAML 字段 context_window_tokens 可以映射到 Java 属性。
5. 将默认值解析责任固定在 Provider/MewCodeModel 适配层，默认使用 128,000。

**验证：** 运行 ./gradlew test --tests com.mewcode.config.ConfigLoaderTest，期望旧配置通过且新字段可正确读取。

## T4: 扩展 StreamEvent usage 和错误类型

**文件：** src/main/java/com/mewcode/llm/StreamEvent.java、src/test/java/com/mewcode/llm/AnthropicClientTest.java、src/test/java/com/mewcode/llm/OpenAiClientTest.java

**依赖：** T2

**步骤：**

1. 为 StreamEvent.Usage 增加 input、cache_read、cache_creation、output 四个 OptionalLong 维度。
2. 保留现有两参数构造器，并把 cache 维度表示为缺省值。
3. 为 StreamEvent.Error 增加 GENERAL 和 CONTEXT_LENGTH 类型。
4. 保留单参数 Error 构造器并默认 GENERAL。
5. 增加对象不可变性、空 Optional 和负数 token 的测试。
6. 先用固定 Provider 响应验证四维字段可以被适配层构造。

**验证：** 运行相关 LLM 测试，期望旧构造器和新四维构造器都能编译、构造并通过断言。

## T5: 让收集器保存流尾 usage 和错误类别

**文件：** src/main/java/com/mewcode/agent/CollectedTurn.java、src/main/java/com/mewcode/agent/TurnStreamCollector.java、src/main/java/com/mewcode/agent/TokenUsageAccumulator.java、src/test/java/com/mewcode/agent/TurnStreamCollectorTest.java

**依赖：** T4

**步骤：**

1. 为 CollectedTurn 增加最后一次 usage 快照和错误类别字段。
2. TurnStreamCollector 收到 Usage 时同时更新 TokenUsageAccumulator 和 CollectedTurn 快照。
3. StreamEnd、提前结束和 Error 都返回正确的 usage 状态。
4. 错误事件的 CONTEXT_LENGTH 类型不能被转换成 GENERAL。
5. 保持 AgentEvent.Usage 只发布现有 input/output 两维累计值。
6. 无 usage 时保持原有 unknown 行为。

**验证：** 运行 ./gradlew test --tests com.mewcode.agent.TurnStreamCollectorTest，期望四维 usage 可被读取，同时现有 UI usage 断言不变。

## T6: 增加正式历史的原子替换

**文件：** src/main/java/com/mewcode/conversation/ConversationManager.java、src/test/java/com/mewcode/conversation/ConversationManagerTest.java

**依赖：** T1

**步骤：**

1. 增加 replaceMessages(List<Message>)。
2. 在 synchronized 临界区复制输入列表并整体替换内部列表。
3. 确保外部修改输入列表不会影响会话。
4. 确保 getMessages 返回的快照仍不可变。
5. 增加并发读取和替换不会观察到半成品列表的测试。
6. 保留 addUserMessage、addToolTurn 和 addMessage 的既有行为。

**验证：** 运行 ./gradlew test --tests com.mewcode.conversation.ConversationManagerTest，期望原子替换和旧写入接口都通过。

## T7: 定义 compact 包的公共数据类型

**文件：** src/main/java/com/mewcode/compact/ContextRequest.java、ContextTrigger.java、ContextPreparation.java、CompactResult.java、ContextException.java

**依赖：** T6

**步骤：**

1. 定义不可变 ContextRequest，复制 system、tools 和 reminder。
2. 定义 ContextTrigger 的 AUTO、MANUAL、EMERGENCY。
3. 定义 ContextPreparation，包含估算值、是否压缩和可选结果。
4. 定义 CompactResult，包含压缩前后估算和 changed 状态。
5. 定义 ContextException，保留原始 cause 但对外提供安全消息。
6. 对空列表、空 Optional、负 token 和非法结果状态增加构造校验。

**验证：** 运行 compact 包类型测试或 ./gradlew test，期望所有 record/class 可以独立构造且输入修改不会污染对象。

## T8: 实现预览生成的确定性规则

**文件：** src/main/java/com/mewcode/compact/ToolResultExternalizer.java、src/test/java/com/mewcode/compact/ToolResultExternalizerTest.java

**依赖：** T7

**步骤：**

1. 先为预览规则写测试：前 2,000、后 2,000、中间省略标记、绝对路径和原始长度。
2. 使用 Unicode code point 作为字符长度和截取单位。
3. 对长度不超过 4,000 的结果避免重复截取。
4. 将预览构造与文件写入逻辑分开，便于边界测试。
5. 确认预览中不得出现完整超大结果的中间部分。

**验证：** 运行 ./gradlew test --tests com.mewcode.compact.ToolResultExternalizerTest，期望预览边界、Unicode 和短文本测试通过。

## T9: 实现单结果外置和文件读回

**文件：** src/main/java/com/mewcode/compact/ToolResultExternalizer.java、src/test/java/com/mewcode/compact/ToolResultExternalizerTest.java

**依赖：** T8

**步骤：**

1. 为单结果 50,000 和 50,001 字符边界先写测试。
2. 在项目根目录下创建 .mewcode/context/<session-id>/。
3. 对超过阈值的结果使用 UTF-8 保存完整原文。
4. 历史结果只返回预览、绝对路径和原始长度。
5. 使用安全内部序号生成文件名，不拼接原始 toolUseId。
6. 验证通过现有项目根路径读取规则可以读回完整内容。
7. 不超过阈值的结果不创建上下文文件。

**验证：** 运行 ToolResultExternalizerTest，期望 50,000 字符不外置、50,001 字符外置、文件内容与原文完全一致。

## T10: 实现聚合外置、失败占位和 session 清理

**文件：** src/main/java/com/mewcode/compact/ToolResultExternalizer.java、src/test/java/com/mewcode/compact/ToolResultExternalizerTest.java

**依赖：** T9

**步骤：**

1. 为同一批工具结果总量 200,000 和 200,001 字符先写测试。
2. 对已经逐结果处理后的最终模型可见结果重新计算聚合长度。
3. 超限时按原始结果长度降序外置，等长按原始调用顺序处理。
4. 保持 ToolResultBlock 的原始顺序不变。
5. 使用临时文件和原子移动避免半文件可读。
6. 模拟文件写入失败，确认结果列表不包含完整原文，而是保留 toolUseId 的安全错误占位。
7. 实现只清理当前 session 目录的幂等 close/cleanup。

**验证：** 运行 ToolResultExternalizerTest，期望最大结果优先、最终聚合不超过 200,000、失败不回填原文、清理不删除其他目录。

## T11: 实现请求字符计数和无锚点估算

**文件：** src/main/java/com/mewcode/compact/TokenEstimator.java、src/test/java/com/mewcode/compact/TokenEstimatorTest.java

**依赖：** T7

**步骤：**

1. 为 system、tools、reminder 和 history 的字符贡献分别写测试。
2. 定义稳定序列化规则，使相同请求快照始终得到相同字符数。
3. 使用 code point 计数，不调用精确 tokenizer。
4. 没有 usage 锚点时使用当前请求字符数除以 3.5，并向上取整。
5. 让工具结果的路径、预览和长度按正式历史内容计入，而不是重新读取完整外置文件。

**验证：** 运行 TokenEstimatorTest，期望相同输入结果确定，所有请求组成部分都被计入，且无锚点估算符合 ceil(chars / 3.5)。

## T12: 实现 usage 锚点和基线失效

**文件：** src/main/java/com/mewcode/compact/TokenEstimator.java、src/main/java/com/mewcode/compact/ContextManager.java、src/test/java/com/mewcode/compact/TokenEstimatorTest.java、src/test/java/com/mewcode/compact/ContextManagerTest.java

**依赖：** T5、T11

**步骤：**

1. 为 usage 总量 input + cache_read + cache_creation + output 写测试。
2. Provider 缺失 cache 维度时按 0 计入。
3. 保存上一次真实 usage 总量和对应请求字符数。
4. 锚点有效时按新增字符数除以 3.5 估算。
5. 没有可用 input/output usage 时回退完整近似估算。
6. 历史成功替换后立即使旧 request-character 基线失效。
7. 证明下一次普通请求使用完整近似，直到新的普通请求 usage 重新建立基线。
8. 摘要请求 usage 也更新内部状态，但不更新普通 TUI usage。

**验证：** 运行 TokenEstimatorTest 和 ContextManagerTest，期望四维求和、增量估算、压缩后回退和摘要 usage 行为全部符合断言。

## T13: 实现尾部选择和用户原文保留

**文件：** src/main/java/com/mewcode/compact/ConversationCompactor.java、src/test/java/com/mewcode/compact/ConversationCompactorTest.java

**依赖：** T6、T11

**步骤：**

1. 先构造包含多轮 user、assistant、tool 消息的测试历史。
2. 从尾部向前扫描，直到同时达到约 10,000 token 和至少 5 条消息。
3. 历史不足时保留全部消息。
4. 尾部之前的所有 user 消息逐字保留。
5. 尾部之前的 assistant/tool 消息标记为摘要范围。
6. 保持尾部消息对象的内容、顺序和工具调用配对不变。

**验证：** 运行 ConversationCompactorTest，期望尾部双条件、历史不足和所有用户原文断言通过。

## T14: 实现摘要 Prompt 和正式摘要解析

**文件：** src/main/java/com/mewcode/compact/ConversationCompactor.java、src/test/java/com/mewcode/compact/ConversationCompactorTest.java

**依赖：** T2、T13

**步骤：**

1. 为摘要 Prompt 写固定字符串测试。
2. 明确要求禁止工具调用。
3. 明确要求先写内部分析草稿，再输出正式摘要。
4. 明确要求正式输出五个固定部分。
5. 将旧历史和重要外置文件索引作为摘要输入。
6. 构造无工具的摘要 PromptRequest。
7. 收集摘要文本和 usage，但不发布普通 StreamText、ToolUse 或 AgentEvent.Usage。
8. 若摘要流出现工具调用、Provider Error、提前结束或缺少任一固定标题，则返回摘要失败。
9. 正式摘要只保留结构化输出，内部草稿不落历史、不写文件。

**验证：** 运行 ConversationCompactorTest，期望摘要请求 tools 为空、Prompt 包含禁止工具和五段要求、非法摘要被拒绝。

## T15: 实现摘要成功后的历史替换和边界消息

**文件：** src/main/java/com/mewcode/compact/ConversationCompactor.java、src/main/java/com/mewcode/conversation/ConversationManager.java、src/test/java/com/mewcode/compact/ConversationCompactorTest.java

**依赖：** T6、T13、T14

**步骤：**

1. 为摘要成功写历史前后的快照测试。
2. 在第一次被摘要的旧 assistant/tool 位置插入摘要消息。
3. 保留旧范围中的所有 user 原始消息。
4. 保留尾部原始消息。
5. 在尾部追加边界 user 消息。
6. 边界消息说明摘要不是完整代码、细节需重新读取、不能臆测内容。
7. 只在摘要结构校验成功后调用 replaceMessages。
8. 摘要失败、取消或 Provider 错误时保持旧历史完全不变。
9. Provider 出站时可合并相邻同角色消息，但不能修改正式历史中的用户文本。

**验证：** 运行 ConversationCompactorTest，期望替换后的用户消息逐字不变、边界消息存在、失败时旧快照不变。

## T16: 实现自动摘要熔断状态机

**文件：** src/main/java/com/mewcode/compact/AutoCompactFuse.java、src/test/java/com/mewcode/compact/AutoCompactFuseTest.java

**依赖：** T7

**步骤：**

1. 先测试 AUTO 失败计数从 0、1、2 到 3 的状态变化。
2. 连续第三次失败后标记熔断。
3. AUTO 成功清零计数和熔断状态。
4. MANUAL、EMERGENCY 不增加自动失败计数。
5. 手动成功后清零自动失败计数。
6. 新建对象从零开始，不跨 session 保存。

**验证：** 运行 ./gradlew test --tests com.mewcode.compact.AutoCompactFuseTest，期望三次熔断、成功清零和触发来源隔离全部通过。

## T17: 实现 ContextManager 自动预检

**文件：** src/main/java/com/mewcode/compact/ContextManager.java、src/test/java/com/mewcode/compact/ContextManagerTest.java

**依赖：** T10、T12、T16

**步骤：**

1. 先写低于阈值、达到阈值和熔断后仍超限三种测试。
2. 使用 Provider 有效窗口和 13,000 安全余量计算自动阈值。
3. 低于阈值时不调用摘要 Provider。
4. 达到阈值且未熔断时调用 ConversationCompactor。
5. 压缩后返回新估算和 changed 状态。
6. 摘要失败时保留原历史并更新 Fuse。
7. Fuse 已熔断且仍超限时直接返回 ContextException，不调用原模型请求。
8. prepareForRequest 不负责发送普通请求，只负责准备上下文状态。

**验证：** 运行 ContextManagerTest，期望阈值判断、摘要调用次数、熔断短路和旧历史保留全部正确。

## T18: 实现 ContextManager 强制压缩和工具回合提交

**文件：** src/main/java/com/mewcode/compact/ContextManager.java、src/test/java/com/mewcode/compact/ContextManagerTest.java

**依赖：** T10、T15、T17

**步骤：**

1. 为 MANUAL 在低于自动阈值时仍调用一次摘要写测试。
2. 为 EMERGENCY 使用 13,000 安全余量写测试。
3. 无可压缩内容时返回无需压缩，不伪造摘要。
4. 摘要成功后原子替换历史并使 usage 基线失效。
5. 摘要 usage 更新内部锚点，但不改变正常轮次。
6. commitToolTurn 先调用 ToolResultExternalizer，再调用 ConversationManager.addToolTurn。
7. 确认 ContextManager 不直接执行工具、不修改权限状态。
8. close 只清理当前 session 目录，并对重复调用安全。

**验证：** 运行 ContextManagerTest，期望手动强制、emergency、无内容、工具结果外置和 close 测试通过。

## T19: 为 PromptRequestFactory 增加上下文请求快照

**文件：** src/main/java/com/mewcode/agent/PromptRequestFactory.java、src/test/java/com/mewcode/agent/PromptRequestFactoryTest.java

**依赖：** T7

**步骤：**

1. 增加从当前模式、轮次、工具 schema 和 deferred tool 构造 ContextRequest 的方法。
2. 保留现有 PromptRequestFactory 构造器和 create 重载。
3. 确保 ContextRequest 不持有可变的 history。
4. 确保普通 PromptRequest 仍使用传入的最新 history 和 reminder。
5. 为 system、tools、reminder 与 deferred tool 传递增加断言。

**验证：** 运行 ./gradlew test --tests com.mewcode.agent.PromptRequestFactoryTest，期望旧测试和新增快照测试都通过。

## T20: 接入 Agent Loop 请求前预检和第一层提交

**文件：** src/main/java/com/mewcode/agent/AgentTurnCoordinator.java、src/main/java/com/mewcode/agent/PromptRequestFactory.java、src/test/java/com/mewcode/agent/AgentTurnCoordinatorTest.java、src/test/java/com/mewcode/agent/AgentLoopTest.java

**依赖：** T5、T17、T18、T19

**步骤：**

1. 保留现有 AgentTurnCoordinator 构造器，增加带 ContextManager 的入口。
2. 每轮生成 ContextRequest 后先调用 prepareForRequest。
3. 压缩发生后重新读取 ConversationManager.getMessages。
4. 使用最新 history 创建 PromptRequest，再调用 client.openStream。
5. 每次流收集结束后把 usage 快照交给 ContextManager.recordUsage。
6. 完整工具回合调用 ContextManager.commitToolTurn。
7. 取消、提前结束和普通错误不得写入半截 assistant 或悬空工具调用。
8. 用户消息只在 startRun 写入一次。

**验证：** 运行 AgentTurnCoordinatorTest 和 AgentLoopTest，期望 20 个小结果触发第二层、80,000 字符结果只触发第一层、正常流程不增加额外请求。

## T21: 实现 Provider prompt_too_long 映射

**文件：** src/main/java/com/mewcode/llm/AnthropicClient.java、src/main/java/com/mewcode/llm/OpenAiClient.java、src/test/java/com/mewcode/llm/AnthropicClientTest.java、src/test/java/com/mewcode/llm/OpenAiClientTest.java

**依赖：** T4

**步骤：**

1. 为两个 Provider 构造固定的 prompt_too_long JSON 错误响应。
2. 只读取明确的 prompt_too_long 标识并映射为 CONTEXT_LENGTH。
3. 普通 400、认证、限流、网络和模型不存在错误保持 GENERAL。
4. 不把请求正文、工具结果、API key 或 Authorization 放进 Error message。
5. 保留既有流错误处理和关闭逻辑。
6. 验证 Provider 未提供 cache 维度时仍能发出 Usage。

**验证：** 运行 AnthropicClientTest 和 OpenAiClientTest，期望只有明确 prompt_too_long 被分类为 CONTEXT_LENGTH。

## T22: 接入 Agent Loop 的一次性硬超限恢复

**文件：** src/main/java/com/mewcode/agent/AgentTurnCoordinator.java、src/test/java/com/mewcode/agent/AgentTurnCoordinatorTest.java、src/test/java/com/mewcode/agent/AgentLoopTest.java

**依赖：** T18、T20、T21

**步骤：**

1. 为第一次请求返回 CONTEXT_LENGTH、ForceCompact 成功、重试成功写测试。
2. 收到 CONTEXT_LENGTH 时丢弃 CollectedTurn 的半截 blocks、calls 和文本。
3. 发布 EMERGENCY 压缩中状态。
4. 调用一次 ForceCompact，使用 13,000 安全余量。
5. 用新的 history、相同 system/tools/reminder 和相同 round 重建原请求。
6. 重试不新增 user 消息、不增加 completedRounds、不增加 Loop 迭代。
7. 为 ForceCompact 失败写测试，期望直接错误收口。
8. 为第二次 CONTEXT_LENGTH 写测试，期望不再压缩、不再重试。
9. 为 GENERAL 错误写测试，期望不进入恢复路径。

**验证：** 运行 AgentTurnCoordinatorTest 和 AgentLoopTest，期望请求次数、history 中 user 数量、round 数量和最终错误都符合一次性恢复规则。

## T23: 增加上下文事件和错误类别

**文件：** src/main/java/com/mewcode/agent/AgentEvent.java、src/test/java/com/mewcode/agent/AgentEventTest.java

**依赖：** T7、T18

**步骤：**

1. 增加 CompactionStarted 事件，携带 ContextTrigger。
2. 增加 CompactionComplete 事件，携带 CompactResult。
3. 在 ErrorCategory 中增加 CONTEXT。
4. 保持现有 AgentEvent sealed permits、构造校验和普通 Usage 语义。
5. 为 AUTO、MANUAL、EMERGENCY 事件和 Context 错误增加测试。

**验证：** 运行 ./gradlew test --tests com.mewcode.agent.AgentEventTest，期望事件不可变、字段完整、旧事件兼容。

## T24: 在 MewCodeModel 初始化 Provider 时注入 ContextManager

**文件：** src/main/java/com/mewcode/tui/MewCodeModel.java、src/test/java/com/mewcode/tui/MewCodeModelTest.java

**依赖：** T17、T20、T23

**步骤：**

1. 增加 ContextManager 字段。
2. 根据 selectedProvider 的 contextWindowTokens 解析有效窗口，缺省使用 128,000。
3. Provider 初始化成功后创建 ContextManager，并注入 Coordinator。
4. 初始化失败时关闭 ContextManager、ToolExecutor 和 MCP 资源。
5. 保留现有 Provider、权限、工具和模式初始化行为。
6. 为自定义窗口和默认窗口增加测试。

**验证：** 运行 MewCodeModelTest，期望旧初始化测试通过，Coordinator 能拿到上下文管理器，默认窗口为 128,000。

## T25: 接入 /compact 和压缩 UI

**文件：** src/main/java/com/mewcode/tui/MewCodeModel.java、src/main/java/com/mewcode/agent/AgentTurnCoordinator.java、src/test/java/com/mewcode/tui/MewCodeModelTest.java

**依赖：** T23、T24

**步骤：**

1. 在普通 user 分支前识别 /compact。
2. /compact 不调用 startRun(String) 写入 user history。
3. 空闲态启动一次手动强制压缩，使用 3,000 安全余量。
4. 压缩期间复用 streaming、poll 和取消状态。
5. 处理 CompactionStarted、CompactionComplete、无需压缩和 CONTEXT 错误。
6. 不把摘要正文显示为普通 assistant 回复。
7. 不把摘要 usage 发布为普通 AgentEvent.Usage 或 TUI Token usage。
8. 压缩完成或失败后恢复可输入状态。
9. 为 /compact 在有内容、无内容和失败三种情况增加测试。

**验证：** 运行 MewCodeModelTest，期望 /compact 不改变用户 history、不增加正常轮次，且三种结果都能回到空闲态。

## T26: 完成生命周期清理和资源关闭

**文件：** src/main/java/com/mewcode/tui/MewCodeModel.java、src/main/java/com/mewcode/compact/ContextManager.java、src/test/java/com/mewcode/tui/MewCodeModelTest.java、src/test/java/com/mewcode/compact/ContextManagerTest.java

**依赖：** T10、T24、T25

**步骤：**

1. close 时先取消活动 Agent Run。
2. 关闭 ContextManager、MCP、ToolExecutor 和 client。
3. ContextManager 只删除当前 session 目录。
4. 清理失败不能阻止其他资源关闭。
5. 重复 close 不重复删除、不重复关闭、不抛异常。
6. 验证异常初始化和正常退出都能清理已经创建的资源。
7. 确认其他 session 目录和项目已有文件不被删除。

**验证：** 运行 MewCodeModelTest 和 ContextManagerTest，期望 close 幂等、资源继续关闭、当前 session 文件消失且其他文件保留。

## T27: 增加跨模块确定性集成测试

**文件：** src/test/java/com/mewcode/agent/AgentTurnCoordinatorTest.java、src/test/java/com/mewcode/agent/AgentLoopTest.java、src/test/java/com/mewcode/compact/ContextManagerTest.java、src/test/java/com/mewcode/tui/MewCodeModelTest.java

**依赖：** T20、T22、T25、T26

**步骤：**

1. 构造 20 个低于 50,000 字符的工具结果，确认第一层不外置，累计接近窗口时第二层摘要。
2. 构造一个 80,000 字符工具结果，确认第一层外置且整体未到阈值时不调用摘要。
3. 构造摘要成功的 prompt_too_long 恢复链路，确认新 history 进入重试请求。
4. 构造第二次 prompt_too_long，确认不会出现第三次请求。
5. 断言用户原始消息、工具调用顺序、外置文件路径和摘要边界消息。
6. 断言摘要 Provider 请求 tools 为空。
7. 断言摘要 usage 与普通 Agent Loop usage 相互隔离。

**验证：** 运行上述四个测试类，期望两个相反压缩场景、硬错误恢复和 UI 隔离全部通过。

## T28: 全量回归和实现前交接

**文件：** 全部新增和修改文件

**依赖：** T27

**步骤：**

1. 运行 ./gradlew test。
2. 检查 git diff --check。
3. 执行未决项扫描，期望无输出。
4. 检查新增错误、日志和测试夹具没有 API key 或 Authorization。
5. 确认 task.md 中所有任务均有依赖和验证方式。
6. 不在本任务中编写 checklist.md，也不在本任务中启动 Java 功能实现。
7. 将验证结果交给下一阶段生成 checklist.md。

**验证：** 全量 Gradle 测试通过、git diff --check 通过、任务文档无未决占位，并且工作区只有文档变更。

## 执行顺序

    T1 → T2
    T1 → T3
    T1 → T6
    T2 → T4 → T5
    T6 → T7
    T7 → T8 → T9 → T10
    T7 → T11 → T12
    T6 + T11 → T13 → T14 → T15
    T7 → T16
    T10 + T12 + T16 → T17 → T18
    T7 → T19
    T5 + T17 + T18 + T19 → T20
    T4 → T21
    T18 + T20 + T21 → T22
    T7 + T18 → T23
    T17 + T20 + T23 → T24 → T25 → T26
    T20 + T22 + T25 + T26 → T27 → T28

## 任务与验收项覆盖

- T3：AC1
- T8–T10：AC2–AC6、AC24–AC25
- T11–T12、T17：AC7–AC11
- T13–T15：AC12–AC18
- T21–T23：AC19–AC21、AC27、AC29
- T16–T18、T25：AC22–AC23
- T24–T26：AC24–AC27、AC34
- T27–T28：AC28–AC33
