# MewCode 上下文管理 Implementation Plan

> 状态：已确认，已实现并完成验收
>
> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans) when executing this plan.

**Goal:** 为 MewCode 增加两层上下文管理能力，在有限 Token 窗口内持续运行 Agent Loop；第一层在工具结果进入历史前做本地外置，第二层在每次 API 请求前处理累积历史，并对 Provider 的 prompt_too_long 做一次有界恢复。

**Architecture:** 新增 com.mewcode.compact 作为上下文策略唯一权威入口。AgentTurnCoordinator 只负责在请求前、工具结果提交时和 Provider 出错时调用它；ConversationManager 继续保存 provider 无关历史；Provider 适配器只负责传递四维 usage 和识别明确的上下文长度错误。

**Tech Stack:** Java 21、现有 Gradle 构建、现有 Provider 适配器、现有 ConversationManager、现有 ReadFile 工具、JUnit 5 测试、tmux 端到端验证。

**Spec:** /Users/li/code/MewCode-develop/docs/ch8/spec.md

## Global Constraints

- 必须先完成并确认 spec.md、plan.md、task.md、checklist.md，四份文档全部确认后才能编写 Java 实现。
- 所有新增注释和用户可见信息使用中文；Provider、工具、权限和取消的既有语义不能被无关改动。
- 不引入精确 tokenizer、机器学习估算、跨会话历史恢复、摘要质量学习或多级递进重试。
- 用户原始消息逐字保留；只有较早的 assistant/tool 内容允许被摘要替换。
- 第一层只处理工具结果，并且必须在工具结果进入 ConversationManager 前完成。
- 第二层只能读取第一层处理后的正式历史。
- 自动摘要使用 13,000 Token 安全余量；手动 /compact 使用 3,000；prompt_too_long 恢复使用 13,000。
- 只有明确的 prompt_too_long 才触发硬超限恢复，普通 Provider 错误不触发。
- prompt_too_long 恢复最多执行一次，重试属于同一 Agent Loop 回合。
- 摘要调用工具列表必须为空；摘要草稿、完整摘要响应和内部状态不得进入正常用户可见输出。
- 外置文件只允许写入当前项目的 .mewcode/context/<session-id>/，正常退出只清理当前 session 目录。
- 修改前先运行现有测试作为基线；每个实现批次先补确定性测试，再实现代码。

---

## 1. 架构概览

当前仓库使用以下真实调用链：

    MewCode
      ↓
    MewCodeModel
      ├─ ProviderConfig
      ├─ LlmClient
      ├─ ToolRegistry / ToolExecutor
      ├─ ConversationManager
      └─ AgentTurnCoordinator
           ├─ PromptRequestFactory
           ├─ LlmClient.openStream
           └─ 工具执行与历史提交

本功能把上下文策略接入为：

    AgentTurnCoordinator
      └─ ContextManager
           ├─ ToolResultExternalizer
           ├─ TokenEstimator
           ├─ ConversationCompactor
           ├─ UsageAnchor
           ├─ ReplacementLedger
           └─ AutoCompactFuse

不新增参考稿中的 Agent、Provider 或 SessionRuntime 类。当前仓库已有长期存活的 AgentTurnCoordinator 和 ConversationManager，ContextManager 以当前 Provider 会话为生命周期单位。

### 1.1 ContextManager 对外责任

ContextManager 是所有上下文策略的唯一入口，至少提供以下语义：

- commitToolTurn：对原始工具结果执行第一层外置后，原子提交 assistant 工具调用和最终 user 工具结果。
- prepareForRequest：在普通 API 请求前估算上下文；必要时触发自动重量压缩。
- forceCompact：执行一次不依赖自动阈值的重量压缩，供 /compact 和 prompt_too_long 恢复使用。
- recordUsage：消费一次 LLM 请求的流尾 usage，更新下一次估算锚点。
- close：关闭当前 session 的文件资源并清理当前 session 目录。

ContextManager 不负责工具执行、权限判断、Provider 协议转换、TUI 渲染或 Agent Loop 轮次计数。

### 1.2 生命周期

- MewCodeModel 完成 Provider 初始化后创建 ContextManager。
- 同一个 ContextManager 服务于该 Provider 会话的普通请求、工具结果、摘要和恢复重试。
- MewCodeModel.close 取消活动运行后关闭 ContextManager、工具和 MCP 资源。
- close 必须幂等；清理失败只记录安全错误，不阻止其他资源关闭。

## 2. 核心数据结构和接口

### 2.1 compact 包类型

新增以下类型：

- ContextRequest：待发送请求的 system segments、tools 和 reminder 快照，不持有会话 history。
- ContextTrigger：AUTO、MANUAL、EMERGENCY 三种触发来源。
- ContextPreparation：本次预检估算值、是否发生压缩和可选 CompactResult。
- CompactResult：压缩前估算、压缩后估算和是否发生历史变化。
- ContextException：文件外置、估算、摘要、熔断或上下文预算失败的统一异常。
- TokenEstimator：对请求快照和正式历史执行字符数和 Token 近似估算。
- ToolResultExternalizer：执行第一层结果外置。
- ConversationCompactor：选择旧内容、生成摘要、解析摘要并原子替换历史。
- AutoCompactFuse：维护当前 session 的自动摘要连续失败次数和熔断状态。

UsageAnchor 和 ReplacementLedger 作为 compact 包内的记录或私有状态实现。只有跨类测试和调用真正需要的类型才公开，避免为简单状态扩散公共 API。

### 2.2 ContextManager 接口语义

ContextManager 构造参数：

- projectRoot：当前项目根目录。
- client：当前选中的 LlmClient。
- contextWindowTokens：Provider 的有效上下文窗口。

核心方法的语义签名：

    void commitToolTurn(
        ConversationManager conversation,
        List<ContentBlock> assistantContent,
        List<ToolResultBlock> rawResults)

    ContextPreparation prepareForRequest(
        ConversationManager conversation,
        ContextRequest request)

    CompactResult forceCompact(
        ConversationManager conversation,
        ContextRequest request,
        ContextTrigger trigger)

    void recordUsage(
        StreamEvent.Usage usage,
        List<Message> sentHistory,
        ContextRequest request)

    void close()

所有输入快照都必须复制或不可变保存。ContextManager 不允许把完整工具原文通过异常、日志或回调泄露到历史之外。

### 2.3 ConversationManager 扩展

增加：

    void replaceMessages(List<Message> messages)

替换必须在 synchronized 临界区内一次完成。调用方只能在摘要完成且摘要结构校验成功后调用；摘要失败、取消或 Provider 错误都保留旧历史。

保留现有 addToolTurn 的兼容行为，但 AgentTurnCoordinator 的工具结果提交改为调用 ContextManager.commitToolTurn。

### 2.4 StreamEvent 和 CollectedTurn 扩展

StreamEvent.Usage 增加：

- inputTokens
- cacheReadTokens
- cacheCreationTokens
- outputTokens

保留现有两参数构造器，使旧测试和 Provider 未提供 cache 维度时仍可工作。缺失 cache 维度按 0 计入内部锚点。

StreamEvent.Error 增加 ErrorKind：

- GENERAL
- CONTEXT_LENGTH

现有单参数构造器默认 GENERAL。只有 Provider 响应中的明确 prompt_too_long 才映射为 CONTEXT_LENGTH。

CollectedTurn 增加：

- 本轮最后一个 usage 快照，供 ContextManager 更新内部锚点。
- Provider 错误类型，供 AgentTurnCoordinator 区分普通错误和上下文恢复。

TurnStreamCollector 继续使用 TokenUsageAccumulator 发布现有两维 TUI usage；四维 usage 同时保存到 CollectedTurn，不改变正常 UI 展示契约。

## 3. 第一层：工具结果外置

### 3.1 单结果规则

ToolResultExternalizer 对每个 ToolResultBlock 先统计原始模型可见结果长度：

- 小于或等于 50,000 字符：原文进入历史。
- 大于 50,000 字符：完整原文写入当前 session 文件，历史只保存预览。

字符计数使用确定性的 Unicode code point 数，不调用 tokenizer。文件使用 UTF-8 写入。

预览固定由以下部分组成：

- 前 2,000 字符。
- 明确的中间省略标记。
- 后 2,000 字符。
- 绝对文件路径。
- 原始字符长度。

结果长度不足 4,000 字符时，预览不能重复截取同一段内容。

### 3.2 聚合规则

完成单结果处理后，再计算同一条 user 工具结果消息的最终模型可见体积。

- 将最终写入历史的工具结果块按稳定序列化文本计数，保证检查和实际历史内容使用同一套计数。
- 如果总量小于或等于 200,000 字符，不再外置。
- 如果超过 200,000 字符，只从尚未外置的结果中按原始长度降序选择。
- 每次替换后重新计算最终体积，直到不超过 200,000 字符。
- 相同长度按模型发起调用的原始顺序决胜。
- 历史中的结果顺序始终保持模型调用顺序。

### 3.3 文件安全和失败

- session 目录在首次需要外置时创建，目录名使用当前运行生成的不可猜测 session ID。
- 文件名只使用内部序号和安全扩展名，不拼接未经校验的工具 ID。
- 写入采用临时文件后原子移动，避免模型读到半个文件。
- 写入失败时，不能把完整原文回填历史。
- 失败结果以保留 toolUseId 的安全错误占位写入，明确说明完整结果未保存。
- 新增错误信息不得包含完整工具内容、API key 或 Authorization。

## 4. 第二层：Token 估算和重量压缩

### 4.1 请求字符计数

TokenEstimator 计算待发送请求的稳定字符数，至少包含：

- system segments。
- tools 的稳定序列化文本。
- reminder。
- ConversationManager 当前正式 history。

Provider 具体的 JSON 包装开销不精确建模；必须保证同一快照反复计算得到相同结果。

### 4.2 UsageAnchor 算法

UsageAnchor 保存：

- 上一次 Provider usage 总量。
- 上一次已发送请求的字符数。
- 是否存在可用的锚点和有效基线。

usage 总量严格计算为：

    input + cache_read + cache_creation + output

当锚点有效且请求形态仍可比较时：

    estimatedTokens =
        anchorTotal
        + ceil(max(0, currentRequestCharacters - anchorRequestCharacters) / 3.5)

当没有锚点、Provider 没有可用 input/output usage，或历史形态发生不可比较的替换时：

    estimatedTokens = ceil(currentRequestCharacters / 3.5)

摘要成功替换历史后，旧的 request-character 基线立即失效；下一次普通请求使用完整近似估算，直到新的普通请求 usage 重新建立基线。这样不会把摘要请求的 usage 错当成压缩后普通请求的历史基线。

每次 LLM 请求结束后，只要流尾或错误前已有可用 usage，就更新锚点。摘要请求也更新内部锚点，但不进入 Agent Loop 轮次、不更新普通 TUI Token usage。

### 4.3 自动预检

普通请求发送前：

1. 确认 history 已经是第一层处理后的正式历史。
2. 使用当前 ContextRequest 和 history 估算。
3. 若估算值小于 contextWindowTokens - 13,000，直接发送普通请求。
4. 若估算值达到或超过该阈值，调用 AUTO 触发的 forceCompact。
5. 压缩成功后重新获取 history，重新构造 PromptRequest，再发送普通请求。
6. 自动摘要失败时保留旧历史；连续失败达到三次后熔断。
7. 熔断后若估算仍超预算，直接返回 ContextException，不调用原模型请求。

### 4.4 旧消息和尾部保留

ConversationCompactor 从 history 尾部向前扫描，保留原文直到同时满足：

- 近似达到 10,000 Token。
- 至少 5 条消息。

历史不足时全部保留。

压缩范围：

- 尾部选中的消息完全原样保留。
- 尾部之前的 user 消息完全原样保留。
- 尾部之前的 assistant/tool 消息进入摘要输入，并从正式 history 中移除。
- 摘要消息插入旧内容的替换位置。
- 边界消息追加在保留尾部之后。

如果没有较早的可压缩 assistant/tool 内容：

- 不调用摘要模型。
- 返回无需压缩结果。
- 不伪造摘要。

相邻同角色消息的 provider 兼容合并只在 Provider 出站序列化时做，不修改 ConversationManager 中保留的用户原文。

### 4.5 摘要请求

摘要请求使用当前 LlmClient 和当前 model，但：

- tools 始终为空列表。
- 不使用普通 Agent Loop 的 PromptRequestFactory reminder。
- 将需要整理的旧历史和外置文件索引序列化为摘要输入。
- 摘要 Prompt 明确禁止工具调用。
- 摘要 Prompt 要求先写内部分析草稿，再输出正式五段摘要。
- 草稿只存在当前生成过程，不进入 history、文件或 UI。

ConversationCompactor 使用独立的摘要流收集逻辑，不发布普通 StreamText、ToolUse 或 AgentEvent.Usage。若摘要流出现工具调用、结构校验失败、Provider 错误或提前结束，视为一次摘要失败。

正式摘要必须出现以下五个固定部分：

1. 用户目标与约束
2. 已完成工作与关键决策
3. 当前代码/文件状态
4. 未完成事项与下一步
5. 重要工具结果文件索引

解析成功后才允许 replaceMessages。替换结果还要追加边界 user 消息，明确：

- 摘要只是历史概况，不是完整代码。
- 需要文件细节时必须重新读取对应文件。
- 不得依据摘要臆测代码、工具输出或文件内容。

### 4.6 自动熔断

AutoCompactFuse 以当前会话为范围：

- AUTO 摘要失败一次，计数加一。
- AUTO 摘要成功，计数清零。
- MANUAL 和 EMERGENCY 不增加自动失败计数。
- 连续失败达到三次后，AUTO 路径直接熔断。
- /compact 仍可执行；手动成功后清零自动失败计数。
- 新建 ContextManager 时重新开始计数。

## 5. Agent Loop 接入时序

### 5.1 普通请求

AgentTurnCoordinator 每一轮执行：

1. 计算工具 schema、deferred tool 和本轮 reminder。
2. 创建 ContextRequest。
3. 调用 ContextManager.prepareForRequest。
4. 压缩发生后重新调用 conversation.getMessages。
5. 调用 PromptRequestFactory 用最新 history 创建 PromptRequest。
6. 调用 client.openStream。
7. TurnStreamCollector 收集流，并保留最后 usage。
8. 收集完成后先把 usage 交给 ContextManager.recordUsage。
9. 完整无工具响应才写入 assistant history。
10. 完整有工具响应才执行工具并调用 commitToolTurn。

当前的用户消息只在 startRun 开始时写入一次。预检、摘要和重试都不得再次添加用户消息。

### 5.2 prompt_too_long 恢复

每个 Agent Loop 回合维护一个 emergencyRetried 标记：

1. Provider 返回 CONTEXT_LENGTH 时，丢弃 CollectedTurn 的半截 blocks、calls 和文本。
2. 若本回合尚未恢复，发布压缩中状态。
3. 调用 EMERGENCY 触发的 forceCompact，使用 13,000 Token 安全余量。
4. 压缩成功后重新读取 history，使用相同的 system、tools、reminder 和 round 重建原请求。
5. 重试一次；completedRounds 不增加。
6. 若 ForceCompact 失败，按 Context/Provider 错误结束。
7. 若重试再次返回 CONTEXT_LENGTH，按普通 Provider 错误结束。
8. 第二次失败不再摘要、不再重试。

只有 CONTEXT_LENGTH 触发这段流程。GENERAL 错误直接沿用现有错误收口。

### 5.3 工具结果提交

工具执行仍由现有 ToolExecutor、权限和取消逻辑负责。工具全部执行完成并装配 ToolResultBlock 后：

- ContextManager 先完成第一层外置。
- 只有最终处理结果才传入 ConversationManager。
- assistant 工具调用和 user 工具结果保持原子提交。
- 取消或 Provider 错误不会留下悬空工具调用。

## 6. TUI、配置和生命周期接入

### 6.1 Provider 配置

ProviderConfig 增加可选的 contextWindowTokens 属性，对应 YAML：

    context_window_tokens: 128000

有效值由 MewCodeModel 解析：

- 未配置或非正数：使用 128,000。
- 正数：使用该 Provider 的独立窗口。
- 不按 protocol 选择不同默认值。

保留旧构造和旧配置语义；ConfigLoader 继续使用当前 YAML 映射方式。

### 6.2 MewCodeModel

- Provider 初始化成功后创建 ContextManager，并把它注入 AgentTurnCoordinator。
- 初始化失败时关闭已创建的 ContextManager、MCP 和工具资源。
- submit 中新增 /compact 分支，必须在普通 user 消息分支前处理，不能把 /compact 写入 history。
- /compact 运行期间复用现有 streaming、poll 和取消状态。
- 压缩摘要内容不显示为普通 assistant 回复。
- CompactionComplete、无需压缩和 Context 错误转换为用户可读中文提示。
- prompt_too_long 恢复过程显示“正在压缩并重试”状态。
- close 中先取消活动 Agent Run，再关闭 ContextManager、MCP 和 ToolExecutor；所有关闭操作继续保持幂等。

### 6.3 AgentEvent

增加上下文相关事件或等价的专用事件：

- CompactionStarted：包含 AUTO、MANUAL 或 EMERGENCY。
- CompactionComplete：包含 CompactResult。

ErrorCategory 增加 CONTEXT，用于区分摘要失败、预算熔断、文件外置失败和二次上下文超限。

正常 AgentEvent.Usage 继续只表示普通 Agent Loop 的现有 TUI usage；摘要不会发布该事件。

## 7. 实现任务和顺序

### P0：基线和测试替身

文件：

- 现有 Gradle 配置和测试目录。
- 新增测试使用的 FakeLlmClient、FakeCancellableLlmStream 或测试内私有替身。

工作：

1. 运行全量现有 Gradle 测试并记录基线。
2. 确认 fake stream 可以按顺序发出文本、usage、工具调用、StreamEnd 和 Error。
3. 确认测试可以捕获每次 PromptRequest、工具列表和请求次数。

完成标准：

- 基线测试通过。
- 测试替身无需真实 API key 或网络。

### P1：基础契约和配置

修改：

- src/main/java/com/mewcode/config/ProviderConfig.java
- src/main/java/com/mewcode/llm/StreamEvent.java
- src/main/java/com/mewcode/agent/CollectedTurn.java
- src/main/java/com/mewcode/agent/TurnStreamCollector.java
- src/main/java/com/mewcode/agent/TokenUsageAccumulator.java
- src/main/java/com/mewcode/conversation/ConversationManager.java

新增测试或修改：

- src/test/java/com/mewcode/config/ConfigLoaderTest.java
- src/test/java/com/mewcode/llm/AnthropicClientTest.java
- src/test/java/com/mewcode/llm/OpenAiClientTest.java
- src/test/java/com/mewcode/agent/TurnStreamCollectorTest.java
- src/test/java/com/mewcode/conversation/ConversationManagerTest.java

工作：

1. 增加 Provider 上下文窗口字段和默认值解析测试。
2. 扩展四维 Usage，并保证旧构造器兼容。
3. 保留缺失 cache usage 的兼容行为。
4. 增加上下文长度错误类型和 CollectedTurn 传递。
5. 增加 replaceMessages 的不可变快照和原子替换测试。

### P2：第一层外置

新增：

- src/main/java/com/mewcode/compact/ToolResultExternalizer.java

新增测试：

- src/test/java/com/mewcode/compact/ToolResultExternalizerTest.java

工作：

1. 实现 session 目录创建和 UTF-8 完整文件写入。
2. 实现单结果 50,000 字符边界。
3. 实现前后 2,000 字符预览、绝对路径和原始长度。
4. 实现 200,000 字符聚合限制及最大结果优先选择。
5. 实现并行结果顺序保留。
6. 实现安全文件名、原子写入和写入失败占位。
7. 实现当前 session 目录清理。

### P3：估算和摘要领域逻辑

新增：

- src/main/java/com/mewcode/compact/ContextRequest.java
- src/main/java/com/mewcode/compact/ContextTrigger.java
- src/main/java/com/mewcode/compact/ContextPreparation.java
- src/main/java/com/mewcode/compact/CompactResult.java
- src/main/java/com/mewcode/compact/ContextException.java
- src/main/java/com/mewcode/compact/TokenEstimator.java
- src/main/java/com/mewcode/compact/ConversationCompactor.java
- src/main/java/com/mewcode/compact/AutoCompactFuse.java
- src/main/java/com/mewcode/compact/ContextManager.java

新增测试：

- src/test/java/com/mewcode/compact/TokenEstimatorTest.java
- src/test/java/com/mewcode/compact/ConversationCompactorTest.java
- src/test/java/com/mewcode/compact/AutoCompactFuseTest.java
- src/test/java/com/mewcode/compact/ContextManagerTest.java

工作：

1. 实现 ContextRequest 和不可变快照。
2. 实现无锚点完整估算和有锚点增量估算。
3. 实现四维 usage 求和、缺失 cache 维度和基线失效。
4. 实现尾部保留和用户原文保留。
5. 实现摘要输入、五段 Prompt、无工具请求和摘要流收集。
6. 实现正式摘要结构校验、失败回滚和边界消息。
7. 实现自动阈值、手动强制和 emergency 强制语义。
8. 实现自动三次熔断和成功清零。
9. 实现 ContextManager 对第一层、第二层、usage 和 session 清理的统一编排。

### P4：Agent Loop 接入

修改：

- src/main/java/com/mewcode/agent/AgentTurnCoordinator.java
- src/main/java/com/mewcode/agent/PromptRequestFactory.java
- src/main/java/com/mewcode/agent/AgentEvent.java

新增或修改测试：

- src/test/java/com/mewcode/agent/AgentTurnCoordinatorTest.java
- src/test/java/com/mewcode/agent/AgentLoopTest.java
- src/test/java/com/mewcode/agent/AgentEventTest.java

工作：

1. 保留现有构造器，增加带 ContextManager 的构造入口。
2. 每轮普通请求前调用 prepareForRequest。
3. 压缩后重新获取 history 和 PromptRequest。
4. 每次请求收集结束后把 usage 交给 ContextManager。
5. 把工具结果提交改为 ContextManager.commitToolTurn。
6. 增加 CONTEXT_LENGTH 的一次性同轮恢复。
7. 保证恢复不重复 user、不增加 round、不提交半截响应。
8. 增加上下文事件，但不把摘要 usage 发为普通 TUI usage。

### P5：Provider 错误映射

修改：

- src/main/java/com/mewcode/llm/AnthropicClient.java
- src/main/java/com/mewcode/llm/OpenAiClient.java

新增或修改测试：

- src/test/java/com/mewcode/llm/AnthropicClientTest.java
- src/test/java/com/mewcode/llm/OpenAiClientTest.java

工作：

1. 从 Provider 错误响应中读取明确的 prompt_too_long 标识。
2. 只将该标识映射为 CONTEXT_LENGTH。
3. 普通 400、认证失败、限流、网络错误和模型不存在保持 GENERAL。
4. 确认错误文本不泄露请求正文、API key 或 Authorization。

### P6：TUI 和 Provider 生命周期

修改：

- src/main/java/com/mewcode/tui/MewCodeModel.java

新增或修改测试：

- src/test/java/com/mewcode/tui/MewCodeModelTest.java

工作：

1. Provider 初始化时创建并注入 ContextManager。
2. 增加 /compact 本地命令。
3. 增加压缩状态、完成、无需压缩和失败的 UI 展示。
4. 确认 /compact 不写入会话历史、不增加 Agent Loop 轮次。
5. 确认摘要不进入普通 assistant UI 和普通 Token usage。
6. close 时清理当前 session 目录，并保证其他资源继续关闭。

### P7：回归、全量测试和 tmux

工作：

1. 运行所有新增和修改的单元测试。
2. 运行 ./gradlew test。
3. 检查 git diff --check。
4. 使用 tmux 启动真实 MewCode。
5. 输入真实对话请求，观察工具调用和最终回复。
6. 验证一个大工具结果外置后通过 ReadFile 重新读取。
7. 验证多个小结果累积后触发摘要。
8. 验证单个大结果只触发第一层、不额外摘要。
9. 使用确定性 Provider 测试流验证 prompt_too_long、ForceCompact、同轮重试和最终回复。
10. 关闭 MewCode，确认当前 session 目录清理。
11. 逐项填写 docs/ch8/checklist.md。

## 8. 测试策略

### 8.1 单元测试边界

ToolResultExternalizerTest 必须覆盖：

- 50,000 和 50,001 字符边界。
- 空结果、短结果和不足 4,000 字符结果。
- Unicode code point 计数。
- 2,000 字符头尾预览。
- 多结果按原始长度降序外置。
- 同长度按原顺序处理。
- 完整文件读回。
- 写入失败不回填完整原文。
- session 目录隔离和清理。

TokenEstimatorTest 必须覆盖：

- 无锚点完整近似。
- 四字段 usage 求和。
- 缺失 cache 字段按 0。
- 增量字符数除以 3.5。
- system、tools、reminder、history 都计入。
- 压缩后基线失效并回退完整估算。

ConversationCompactorTest 必须覆盖：

- 尾部同时满足 10,000 token 和 5 条消息。
- 历史不足时全部保留。
- 所有 user 原文逐字不变。
- assistant/tool 旧内容进入摘要。
- 五段摘要结构校验。
- 摘要 tools 为空。
- 摘要失败时旧历史不变。
- 边界消息内容完整。
- 无可压缩内容时不伪造摘要。

AgentTurnCoordinatorTest 和 AgentLoopTest 必须覆盖：

- 20 个小结果累计触发第二层。
- 一个 80,000 字符结果只触发第一层。
- 自动压缩后使用新 history。
- 每次普通请求都捕获 usage。
- 摘要 usage 不改变普通轮次和 UI usage。
- prompt_too_long 丢弃半截流。
- ForceCompact 后同轮只重试一次。
- 没有重复 user 消息。
- 没有增加迭代次数。
- 第二次 prompt_too_long 直接结束。
- 普通 Provider 错误不触发压缩。
- 自动摘要第三次失败熔断，手动 /compact 仍可用。

MewCodeModelTest 必须覆盖：

- /compact 不写入会话历史。
- /compact 在空闲态启动一次强制压缩。
- 成功、无需压缩和失败均能回到可输入状态。
- 摘要 usage 不显示为普通 TUI Token usage。
- close 幂等并清理 session 文件。

### 8.2 测试替身约束

- 不依赖真实 Provider key。
- FakeLlmClient 记录 PromptRequest 快照，能区分普通请求和摘要请求。
- 摘要测试必须断言 tools 为空。
- prompt_too_long 测试必须让第一次请求返回错误、第二次请求只在 ForceCompact 成功后出现。
- Provider 适配器错误映射测试使用固定 JSON 错误响应。

## 9. 验收映射

- AC1–AC6：Provider 窗口、单结果外置、聚合外置、路径读回和正式历史。
- AC7–AC11：小结果累积、大结果不触发第二层、usage 锚点、每次请求更新和三种安全余量。
- AC12–AC18：用户原文、尾部保留、五段摘要、无工具、草稿隔离、边界消息和摘要 usage。
- AC19–AC23：prompt_too_long 恢复、同轮一次重试、二次失败、自动熔断和手动绕过。
- AC24–AC27：session 文件生命周期、失败安全、既有行为兼容和 TUI 可见性。
- AC28–AC30：单元测试、集成测试和全量 Gradle 测试。
- AC31–AC34：tmux 大结果、小结果累积、硬超限恢复和退出清理。

## 10. 风险控制和明确不纳入范围

- 不复制参考稿的九段摘要结构；以已确认的五段结构为准。
- 不使用参考稿的 200K 默认窗口；统一默认 128K。
- 不使用 bytes/lines 作为阈值；使用字符数和固定头尾预览。
- 不实现多级 PTL 丢组重试；硬超限只做一次 ForceCompact 和一次原请求重试。
- 不新增 ReadFile 快照、RecoveryState 或跨会话恢复系统。
- 不让摘要请求更新普通 Agent Loop 轮次或 TUI Token usage，但仍更新内部 usage 锚点。
- 不把 PromptRequestFactory 的旧接口全部替换；保留兼容重载，减少既有测试和调用方风险。
- 不把用户输入 /compact 作为普通 user 消息写入 ConversationManager。

## 11. 实现完成判定

只有同时满足以下条件，才能将本计划标记为完成：

- docs/ch8/spec.md、plan.md、task.md、checklist.md 均已确认。
- 全量 Gradle 测试通过。
- 关键 Agent Loop、Provider 错误和 TUI 测试通过。
- tmux 端到端场景完成并记录在 checklist.md。
- prompt_too_long 没有第二次恢复循环。
- 当前 session 上下文目录在正常退出后被清理。
- git diff --check 通过，且未出现敏感信息或无关文件。
