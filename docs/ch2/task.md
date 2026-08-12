# 多协议 LLM 终端对话客户端 Tasks

> 顶层包：`com.mewcode`。所有任务依据已批准的 `spec.md` 与 `plan.md`；每项完成后必须先执行对应验证，再进入后续任务。

## 文件清单

| 操作 | 文件 | 职责 |
|---|---|---|
| 修改 | `../../build.gradle.kts` | 增加 SDK、YAML、Markdown 和测试依赖 |
| 修改 | `../../.gitignore` | 忽略真实配置、构建与 IDE 文件 |
| 新建 | `../../.mewcode/config.yaml.example` | 六字段双 provider 配置示例 |
| 修改 | `../../src/main/java/com/mewcode/MewCode.java` | 配置加载、装配和安全启动错误 |
| 新建 | `../../src/main/java/com/mewcode/config/AppConfig.java` | 配置根 JavaBean |
| 新建 | `../../src/main/java/com/mewcode/config/ProviderConfig.java` | 六字段 provider JavaBean |
| 新建 | `../../src/main/java/com/mewcode/config/ConfigLoader.java` | YAML 绑定、校验与安全错误 |
| 新建 | `../../src/main/java/com/mewcode/conversation/Message.java` | 纯文本 role/content 消息 |
| 新建 | `../../src/main/java/com/mewcode/conversation/ConversationManager.java` | 进程内多轮历史 |
| 新建 | `../../src/main/java/com/mewcode/prompt/PromptBuilder.java` | 固定 system prompt |
| 新建 | `../../src/main/java/com/mewcode/llm/StreamEvent.java` | 统一流式事件 |
| 新建 | `../../src/main/java/com/mewcode/llm/LlmClient.java` | 统一 provider 接口 |
| 新建 | `../../src/main/java/com/mewcode/llm/LlmClients.java` | provider 工厂 |
| 新建 | `../../src/main/java/com/mewcode/llm/AnthropicClient.java` | Anthropic SDK 适配 |
| 新建 | `../../src/main/java/com/mewcode/llm/OpenAiClient.java` | OpenAI Chat Completions 适配 |
| 修改 | `../../src/main/java/com/mewcode/tui/tea/Program.java` | `Alt+Enter`、退出和终端清理 |
| 新建 | `../../src/main/java/com/mewcode/tui/AppState.java` | Provider 选择/聊天状态 |
| 新建 | `../../src/main/java/com/mewcode/tui/ChatMessage.java` | UI 消息与耗时 |
| 新建 | `../../src/main/java/com/mewcode/tui/Styles.java` | TUI 样式常量 |
| 新建 | `../../src/main/java/com/mewcode/tui/SpinnerVerbs.java` | 进行中提示文本 |
| 新建 | `../../src/main/java/com/mewcode/tui/MarkdownRenderer.java` | Mordant Markdown ANSI 渲染 |
| 修改 | `../../src/main/java/com/mewcode/tui/MewCodeModel.java` | 完整对话 TUI 状态机 |
| 新建 | `../../src/test/java/com/mewcode/config/ConfigLoaderTest.java` | 配置成功与失败测试 |
| 新建 | `../../src/test/java/com/mewcode/conversation/ConversationManagerTest.java` | 历史顺序与防修改测试 |
| 新建 | `../../src/test/java/com/mewcode/llm/AnthropicClientTest.java` | Anthropic 本地 SSE 测试 |
| 新建 | `../../src/test/java/com/mewcode/llm/OpenAiClientTest.java` | OpenAI 本地 SSE 测试 |
| 新建 | `../../src/test/java/com/mewcode/tui/MarkdownRendererTest.java` | Markdown 行为测试 |
| 新建 | `../../src/test/java/com/mewcode/tui/MewCodeModelTest.java` | Provider、输入、流式与错误状态测试 |
| 新建 | `../../src/test/resources/sse/anthropic-thinking.txt` | Anthropic thinking + text 流样例 |
| 新建 | `../../src/test/resources/sse/openai-chat.txt` | OpenAI Chat Completions 流样例 |

## T1：补齐构建依赖

**文件：** `../../build.gradle.kts`  
**依赖：** 无

**步骤：**

1. 保留 Java 21、application、Shadow 与现有 JLine 配置。
2. 加入 Mordant `3.0.2`、`mordant-markdown` `3.0.2`。
3. 加入 Anthropic Java SDK `2.34.0`、OpenAI Java SDK `4.37.0`。
4. 加入 SnakeYAML `2.2` 与 JUnit Jupiter `5.11.4`。
5. 为 `test` 任务启用 JUnit Platform；不加入 Jackson、MCP、Javalin 或 SLF4J。

**验证：** 运行 `./gradlew compileJava`，期望现有源码在新增依赖下编译通过。

## T2：定义 Provider 配置 JavaBean

**文件：** `../../src/main/java/com/mewcode/config/AppConfig.java`、`ProviderConfig.java`  
**依赖：** T1

**步骤：**

1. 定义 `AppConfig.providers` 及 getter/setter，默认使用空列表而非 `null`。
2. 定义 `ProviderConfig` 六个字段及标准 getter/setter。
3. 将 `thinking` 的 Java 默认值保留为 `false`。
4. 覆写 `toString()`，只显示非敏感字段，并将密钥表示为 `[REDACTED]`。

**验证：** 运行 `./gradlew compileJava`，期望 config 模型编译通过。

## T3：实现 YAML 文件读取与绑定

**文件：** `../../src/main/java/com/mewcode/config/ConfigLoader.java`  
**依赖：** T2

**步骤：**

1. 实现 `load(String path)`，只读取调用方传入的固定路径。
2. 文件不存在或无法读取时抛出 `ConfigException`，消息包含路径但不含堆栈或密钥。
3. 使用 SnakeYAML `Constructor(AppConfig.class, LoaderOptions)` 绑定 JavaBean。
4. 加入 snake_case → camelCase 的属性映射，使 `base_url`、`api_key` 正确绑定。
5. 将 YAML 解析异常转换为单行安全错误。

**验证：** 运行 `./gradlew compileJava`，期望 `ConfigLoader` 编译通过。

## T4：实现配置语义校验

**文件：** `../../src/main/java/com/mewcode/config/ConfigLoader.java`  
**依赖：** T3

**步骤：**

1. 校验 providers 列表非空。
2. 逐项校验 `name`、`protocol`、`model`、`api_key` 非空。
3. 校验 protocol 只能为 `anthropic` 或 `openai`。
4. 校验 provider 名称唯一。
5. `base_url` 非空时校验为 HTTP/HTTPS URI。
6. 错误消息包含 `providers[index].field`，不拼接 `api_key` 值。

**验证：** 运行 `./gradlew compileJava`，期望校验逻辑编译通过。

## T5：覆盖合法配置场景

**文件：** `../../src/test/java/com/mewcode/config/ConfigLoaderTest.java`  
**依赖：** T4

**步骤：**

1. 使用 JUnit 临时目录创建单 provider 配置。
2. 断言六字段正确绑定，缺失 thinking 时为 `false`。
3. 创建双 provider 配置，断言顺序和两种 protocol 保持不变。
4. 断言空 `base_url` 被接受。
5. 断言 `ProviderConfig.toString()` 不包含测试密钥。

**验证：** 运行 `./gradlew test --tests '*ConfigLoaderTest'`，期望合法配置用例全部通过。

## T6：覆盖非法配置场景

**文件：** `../../src/test/java/com/mewcode/config/ConfigLoaderTest.java`  
**依赖：** T5

**步骤：**

1. 增加文件缺失、YAML 损坏和 providers 为空用例。
2. 增加四个必要字段分别缺失的参数化用例。
3. 增加未知 protocol、重复 name 和非法 `base_url` 用例。
4. 断言错误消息定位字段且不包含配置中的密钥。

**验证：** 运行 `./gradlew test --tests '*ConfigLoaderTest'`，期望全部失败场景返回预期 `ConfigException`。

## T7：添加安全配置模板与忽略规则

**文件：** `../../.mewcode/config.yaml.example`、`../../.gitignore`  
**依赖：** T4

**步骤：**

1. 创建包含 Anthropic 与 OpenAI 两项的六字段示例。
2. 使用 `replace-me` 占位密钥，不加入真实凭据。
3. 忽略 `../../.mewcode/config.yaml`、`../../build`、`../../.gradle` 和 `../../.idea`。
4. 保证 `../../.mewcode/config.yaml.example` 不被忽略。

**验证：** 运行 `git check-ignore .mewcode/config.yaml` 应命中；运行 `git check-ignore .mewcode/config.yaml.example` 应不命中。

## T8：实现纯文本会话历史

**文件：** `../../src/main/java/com/mewcode/conversation/Message.java`、`ConversationManager.java`  
**依赖：** T1

**步骤：**

1. 定义只有 `role`、`content` 的不可变 `Message` record。
2. 用进程内 `ArrayList` 保存消息。
3. 实现 user 和 assistant 追加方法。
4. `getMessages()` 返回保持顺序的不可修改副本。
5. 拒绝空 role；消息内容允许包含多行 Markdown。

**验证：** 运行 `./gradlew compileJava`，期望 conversation 包编译通过。

## T9：验证会话顺序与封装

**文件：** `../../src/test/java/com/mewcode/conversation/ConversationManagerTest.java`  
**依赖：** T8

**步骤：**

1. 追加两轮 user/assistant 消息并断言顺序和内容。
2. 断言多行内容不被修改。
3. 尝试修改 `getMessages()` 返回值并断言被拒绝。
4. 断言取得快照后继续追加不会反向修改旧快照。

**验证：** 运行 `./gradlew test --tests '*ConversationManagerTest'`，期望全部通过。

## T10：实现内置 System Prompt

**文件：** `../../src/main/java/com/mewcode/prompt/PromptBuilder.java`  
**依赖：** T1

**步骤：**

1. 提供无参数 `buildSystemPrompt()`。
2. 声明 MewCode 是终端编程对话助手并可输出 Markdown。
3. 明确不声称拥有工具、文件、命令、记忆或网络能力。
4. 返回稳定的非空文本，不读取环境或项目文件。

**验证：** 运行 `./gradlew compileJava`，期望 prompt 包编译通过。

## T11：定义统一流事件与接口

**文件：** `../../src/main/java/com/mewcode/llm/StreamEvent.java`、`LlmClient.java`  
**依赖：** T2、T8

**步骤：**

1. 定义 `TextDelta`、`ThinkingDelta`、`StreamEnd`、`Error` 四种 sealed 事件。
2. 定义 `LlmClient.stream(ConversationManager)`。

**验证：** 运行 `./gradlew compileJava`，期望统一事件与接口编译通过。

## T12：构造 Anthropic 客户端与消息历史

**文件：** `../../src/main/java/com/mewcode/llm/AnthropicClient.java`  
**依赖：** T10、T11

**步骤：**

1. 用 `AnthropicOkHttpClient.builder()` 配置 YAML 密钥并设 `maxRetries(0)`。
2. 仅在 `base_url` 非空时调用 SDK 的地址覆盖方法。
3. 保存 model、thinking 和 system prompt。
4. 将 conversation 消息映射为 SDK user/assistant 消息。
5. 合并连续同 role 的文本，保持原始顺序。

**验证：** 运行 `./gradlew compileJava`，期望客户端构造和历史映射编译通过。

## T13：构造 Anthropic 普通与 Thinking 请求

**文件：** `../../src/main/java/com/mewcode/llm/AnthropicClient.java`  
**依赖：** T12

**步骤：**

1. 构造 Messages 请求并注入 model、system 和历史。
2. 普通模式设置 `max_tokens=8192`。
3. thinking 模式设置 `max_tokens=16384` 与 `budget_tokens=8192`。
4. 不加入工具、缓存、用量统计或额外采样参数。

**验证：** 运行 `./gradlew compileJava`，期望两种请求构造均匹配 SDK 类型。

## T14：解析 Anthropic 流式事件

**文件：** `../../src/main/java/com/mewcode/llm/AnthropicClient.java`  
**依赖：** T13

**步骤：**

1. 创建容量固定的 `LinkedBlockingQueue<StreamEvent>` 并启动 virtual thread。
2. 用 try-with-resources 消费 `createStreaming` 返回值。
3. 将 `text_delta` 映射为 `TextDelta`。
4. 将 `thinking_delta` 映射为 `ThinkingDelta`，不累计 thinking 或 signature。
5. 在完整结束时写入一次 `StreamEnd`。

**验证：** 运行 `./gradlew compileJava`，期望流式分支编译通过且没有工具事件类型。

## T15：清洗 Anthropic 错误

**文件：** `../../src/main/java/com/mewcode/llm/AnthropicClient.java`  
**依赖：** T14

**步骤：**

1. 分类鉴权、限流、Not Found、Bad Request、网络与未知异常。
2. 把分类结果转换为简短用户消息。
3. 保证异常路径只写入一次 `StreamEvent.Error`。
4. 处理中断时恢复线程中断标记，不打印堆栈。
5. 清理可能包含认证头或请求体的原始异常文本。

**验证：** 运行 `./gradlew compileJava`，期望所有错误分支编译通过。

## T16：验证 Anthropic SSE 与 Thinking 隔离

**文件：** `../../src/test/resources/sse/anthropic-thinking.txt`、`../../src/test/java/com/mewcode/llm/AnthropicClientTest.java`  
**依赖：** T15

**步骤：**

1. 创建包含 message_start、thinking_delta、text_delta、message_stop 的合法测试流。
2. 测试中启动本地 HTTP server，记录请求体并返回该流。
3. 断言请求含 system、完整历史和 thinking 参数。
4. 断言队列事件顺序为 ThinkingDelta、TextDelta、StreamEnd。
5. 增加鉴权错误响应，断言只得到安全 Error 且消息不含测试密钥。

**验证：** 运行 `./gradlew test --tests '*AnthropicClientTest'`，期望全部通过且不访问外网。

## T17：构造 OpenAI Chat Completions 请求

**文件：** `../../src/main/java/com/mewcode/llm/OpenAiClient.java`  
**依赖：** T10、T11

**步骤：**

1. 用 `OpenAIOkHttpClient.builder()` 配置 YAML 密钥并设 `maxRetries(0)`。
2. 仅在 `base_url` 非空时覆盖 SDK 默认地址。
3. 将 system prompt 作为第一条 system 消息。
4. 按原顺序追加 conversation 的 user/assistant 消息。
5. 构造 `stream=true` 的 Chat Completions 参数，不设置 reasoning 或工具。

**验证：** 运行 `./gradlew compileJava`，期望 OpenAI 请求构造编译通过。

## T18：解析 OpenAI 流式正文

**文件：** `../../src/main/java/com/mewcode/llm/OpenAiClient.java`  
**依赖：** T17

**步骤：**

1. 创建独立有界队列和 virtual thread。
2. 用 try-with-resources 消费 Chat Completions 流。
3. 遍历 choices 并提取非空 delta content。
4. 按到达顺序写入 `TextDelta`。
5. 正常完成后写入一次 `StreamEnd`，不发 ThinkingDelta。

**验证：** 运行 `./gradlew compileJava`，期望 OpenAI 流式实现编译通过。

## T19：清洗 OpenAI 与兼容端点错误

**文件：** `../../src/main/java/com/mewcode/llm/OpenAiClient.java`  
**依赖：** T18

**步骤：**

1. 分类鉴权、限流、Not Found、Bad Request、网络与未知异常。
2. 将兼容端点的非标准失败统一成安全协议错误。
3. 保证异常路径只写入一次 Error。
4. 中断时恢复线程中断标记，不打印堆栈。
5. 不在消息中拼接请求体、认证头或 provider 配置对象。

**验证：** 运行 `./gradlew compileJava`，期望错误路径编译通过。

## T20：验证 OpenAI 标准与自定义 Base URL

**文件：** `../../src/test/resources/sse/openai-chat.txt`、`../../src/test/java/com/mewcode/llm/OpenAiClientTest.java`、`../../src/main/java/com/mewcode/llm/LlmClients.java`  
**依赖：** T19

**步骤：**

1. 创建包含多个 content delta 和 `[DONE]` 的 Chat Completions 测试流。
2. 启动本地 HTTP server 并将其 `/v1` 地址作为 `base_url`。
3. 断言请求路径、system、model 和完整历史正确。
4. 断言多个 TextDelta 按顺序到达并以 StreamEnd 结束。
5. 增加错误响应，断言 Error 安全且测试密钥未泄漏。
6. 创建 `LlmClients`，按 `anthropic`、`openai` 构造对应适配器；未知 protocol 抛出不含配置详情的 `IllegalArgumentException`。

**验证：** 运行 `./gradlew test --tests '*OpenAiClientTest'`，期望全部通过且不访问外网。

## T21：实现 Mordant Markdown 字符串渲染

**文件：** `../../src/main/java/com/mewcode/tui/MarkdownRenderer.java`  
**依赖：** T1

**步骤：**

1. 创建 Mordant `Markdown` widget，不使用正则替换 Markdown。
2. 将有效宽度限制为至少 20 列。
3. 通过 widget 指定宽度渲染，再由 Mordant Terminal 转成 ANSI 字符串。
4. 空输入返回空字符串。
5. 不直接打印 stdout/stderr。

**验证：** 运行 `./gradlew compileJava`，期望 Java 调用 Mordant API 编译通过。

## T22：验证 Markdown 内容与宽度

**文件：** `../../src/test/java/com/mewcode/tui/MarkdownRendererTest.java`  
**依赖：** T21

**步骤：**

1. 渲染标题、强调、列表和 fenced code block。
2. 断言输出包含原始文字且不再包含 fenced code 标记。
3. 分别用窄宽和常规宽度渲染，断言均不抛异常。
4. 断言空字符串得到空输出。

**验证：** 运行 `./gradlew test --tests '*MarkdownRendererTest'`，期望全部通过。

## T23：补齐终端按键与退出解析

**文件：** `../../src/main/java/com/mewcode/tui/tea/Program.java`  
**依赖：** T1

**步骤：**

1. 在 ESC 序列解析中识别 `Alt+Enter` 并产生 `alt+enter`。
2. 保留方向键、Home/End、PageUp/PageDown 与 CJK 宽度行为。
3. SIGINT 和字节 `0x03` 均产生统一 `ctrl+c` 按键消息。
4. 检查 `run()` 的 `finally` 始终清除 view、恢复光标并关闭 JLine terminal。
5. 不启用 alternate screen 或拦截终端原生 scrollback。

**验证：** 运行 `./gradlew compileJava`，期望 tea 运行时编译通过。

## T24：定义 TUI 状态、消息和样式

**文件：** `../../src/main/java/com/mewcode/tui/AppState.java`、`ChatMessage.java`、`Styles.java`、`SpinnerVerbs.java`  
**依赖：** T1

**步骤：**

1. 定义 `PROVIDER_SELECT`、`CHAT` 两个应用状态。
2. 定义 user/assistant/error UI 消息及 elapsed 字段。
3. 定义 banner、提示符、正文、错误、状态栏、分隔线和弱化文字样式。
4. 定义不包含工具含义的进行中动词，至少包含 `Imagining`。
5. 不加入权限模式、工具状态或 slash 菜单样式。

**验证：** 运行 `./gradlew compileJava`，期望所有 TUI 支撑类型编译通过。

## T25：实现单/多 Provider 启动状态

**文件：** `../../src/main/java/com/mewcode/tui/MewCodeModel.java`  
**依赖：** T2、T10、T11、T16、T20、T24

**步骤：**

1. 构造函数接收 providers，并初始化 conversation、输入与显示状态。
2. 单 provider 时选中并创建 LlmClient，进入 CHAT。
3. 多 provider 时进入 PROVIDER_SELECT，并维护 cursor。
4. 处理上下方向键与 Enter 选择。
5. 为测试提供包内可见的 client factory 注入构造方式，生产默认使用 `LlmClients.create`。

**验证：** 运行 `./gradlew compileJava`，期望 provider 状态机编译通过。

## T26：实现 Banner 与空闲聊天布局

**文件：** `../../src/main/java/com/mewcode/tui/MewCodeModel.java`  
**依赖：** T25

**步骤：**

1. 渲染 ASCII 猫、`MewCode 0.1.0`、模型名与当前工作目录。
2. 增加纯对话就绪提示行。
3. 渲染带边框/分隔线的 `❯` 输入区及 `Send a message...` 占位文字。
4. 状态栏左侧显示 provider 名，右侧按终端宽度对齐模型名。
5. 多 provider 选择页显示 `name (model)` 与方向键提示。

**验证：** 运行 `./gradlew compileJava`，期望两种 view 均可构造。

## T27：实现多行输入编辑

**文件：** `../../src/main/java/com/mewcode/tui/MewCodeModel.java`  
**依赖：** T26

**步骤：**

1. 处理普通字符、Backspace、Left/Right、Home/End。
2. `alt+enter` 在 cursor 位置插入换行。
3. `enter` 对空白输入不提交。
4. 多行 view 的续行保持与首行文本对齐。
5. streaming 期间忽略除 Ctrl+C 外的输入修改和提交。

**验证：** 运行 `./gradlew compileJava`，期望输入状态机编译通过。

## T28：实现提交与启动流式请求

**文件：** `../../src/main/java/com/mewcode/tui/MewCodeModel.java`  
**依赖：** T9、T23、T27

**步骤：**

1. Enter 提交时保存原始多行文本并清空输入缓冲。
2. `/exit` 返回 QuitMessage，不发起模型请求。
3. 普通消息通过 `Command.println` 提交用户显示内容。
4. 将 user 消息加入 ConversationManager 后调用 `client.stream()`。
5. 设置 streaming、开始时间、spinner 和 streamBuffer，并安排首次 50ms poll。

**验证：** 运行 `./gradlew compileJava`，期望提交路径编译通过。

## T29：处理 Thinking、正文与实时计时

**文件：** `../../src/main/java/com/mewcode/tui/MewCodeModel.java`  
**依赖：** T28

**步骤：**

1. 定义嵌套 `StreamPollMessage`。
2. poll 时非阻塞排空当前队列。
3. `ThinkingDelta` 立即丢弃，不写入任何 buffer 或消息。
4. `TextDelta` 按顺序追加 streamBuffer 并实时重绘纯文本。
5. 依据开始时间渲染 `Imagining… (Ns)`，未结束时安排下一个 poll。

**验证：** 运行 `./gradlew compileJava`，期望轮询和流式显示路径编译通过。

## T30：完成回复并 Markdown 定型

**文件：** `../../src/main/java/com/mewcode/tui/MewCodeModel.java`  
**依赖：** T22、T29

**步骤：**

1. 收到 StreamEnd 后停止后续 poll。
2. 将 streamBuffer 原始正文追加为 assistant 会话消息。
3. 按当前有效宽度调用 MarkdownRenderer。
4. 用 `Command.println` 提交助手 marker、Markdown 结果和总耗时。
5. 清空流状态并恢复空闲输入框。

**验证：** 运行 `./gradlew compileJava`，期望成功终止路径编译通过。

## T31：实现可恢复错误展示

**文件：** `../../src/main/java/com/mewcode/tui/MewCodeModel.java`  
**依赖：** T29

**步骤：**

1. 收到 Error 后停止 poll 并计算总耗时。
2. streamBuffer 非空时将部分正文作为失败轮次显示内容提交，但不追加 assistant 历史。
3. 以错误样式提交安全消息和耗时。
4. 清空 streamBuffer、queue 与 streaming 状态。
5. 恢复输入框，允许用户继续提交。

**验证：** 运行 `./gradlew compileJava`，期望错误恢复路径编译通过。

## T32：实现统一退出行为

**文件：** `../../src/main/java/com/mewcode/tui/MewCodeModel.java`  
**依赖：** T28、T31

**步骤：**

1. PROVIDER_SELECT、空闲 CHAT 和 streaming CHAT 都处理 `ctrl+c`。
2. 三种状态均直接返回 QuitMessage。
3. streaming 时不保存部分回复到 ConversationManager，也不返回输入框。
4. `/exit` 只在可编辑状态识别并走相同 QuitMessage。

**验证：** 运行 `./gradlew compileJava`，期望退出分支编译通过。

## T33：验证 Provider 选择与多行输入

**文件：** `../../src/test/java/com/mewcode/tui/MewCodeModelTest.java`  
**依赖：** T27、T32

**步骤：**

1. 注入 fake client factory，避免测试访问网络。
2. 断言单 provider 直接出现聊天布局和状态栏。
3. 断言双 provider 可用上下键选择第二项。
4. 断言 `Alt+Enter` 形成多行输入，Enter 后提交完整文本。
5. 断言生成期间的新 Enter 不调用第二次 stream。

**验证：** 运行 `./gradlew test --tests '*MewCodeModelTest'`，期望 provider 和输入用例通过。

## T34：验证流式成功、Thinking 与错误恢复

**文件：** `../../src/test/java/com/mewcode/tui/MewCodeModelTest.java`  
**依赖：** T30、T31、T33

**步骤：**

1. fake client 返回可控 BlockingQueue。
2. 写入 ThinkingDelta 后断言 view 不包含思考文本。
3. 分批写入 TextDelta，断言 view 逐步出现正文和计时。
4. 写入 StreamEnd，断言原始正文进入 conversation，界面恢复输入。
5. 单独写入 Error，断言错误可见、程序未退出且下一轮可提交。

**验证：** 运行 `./gradlew test --tests '*MewCodeModelTest'`，期望全部流式状态测试通过。

## T35：装配应用入口

**文件：** `../../src/main/java/com/mewcode/MewCode.java`  
**依赖：** T6、T25、T32

**步骤：**

1. 固定加载 `../../.mewcode/config.yaml`。
2. 创建 MewCodeModel 与 Program 并运行。
3. 配置错误时只向 stderr 输出 `MewCode: <safe message>`。
4. 配置错误使用非零退出状态，不输出堆栈。
5. 保留终端光标恢复的 finally 防线，不解析任何 CLI 参数。

**验证：** 运行 `./gradlew shadowJar`；在缺少配置的临时工作目录运行生成的 jar，期望非零退出且只有可读错误。

## T36：执行全量构建与范围检查

**文件：** 全部本期文件  
**依赖：** T7、T9、T16、T20、T22、T23、T34、T35

**步骤：**

1. 运行全部单元和本地 SSE 集成测试。
2. 构建无 classifier 的 Shadow fat jar。
3. 检查 jar 主类为 `com.mewcode.MewCode`。
4. 搜索源码，确认不存在工具、MCP、权限、远程、会话持久化入口。
5. 搜索测试输出与源码日志，确认没有示例以外的密钥文本。

**验证：** `./gradlew clean test shadowJar` 全部通过，生成 `../../build/libs/mewcode.jar`。

## 执行顺序

```text
T1
├── T2 → T3 → T4 → T5 → T6 → T7
├── T8 → T9
├── T10
├── T21 → T22
├── T23
└── T24

T2 + T8 + T10 → T11
T11 → T12 → T13 → T14 → T15 → T16
T11 → T17 → T18 → T19 → T20

T2 + T10 + T11 + T16 + T20 + T24 → T25 → T26 → T27
T9 + T23 + T27 → T28 → T29
T22 + T29 → T30
T29 → T31
T28 + T31 → T32
T27 + T32 → T33
T30 + T31 + T33 → T34
T6 + T25 + T32 → T35

T7 + T9 + T16 + T20 + T22 + T23 + T34 + T35 → T36
```

---

## 配置增量任务：DeepSeek OpenAI 兼容接口

### 文件清单

| 操作 | 文件 | 职责 |
|---|---|---|
| 修改 | `../../.mewcode/config.yaml` | 追加本机 DeepSeek provider，占位 Key 后由用户本地替换 |
| 修改 | `../../.mewcode/config.yaml.example` | 追加可提交的 DeepSeek 安全示例 |
| 不修改 | `src/main/java/com/mewcode/**` | 复用现有 OpenAI Chat Completions 适配链路 |
| 不修改 | `src/test/java/com/mewcode/**` | 复用现有配置、协议和 TUI 测试 |

### DT1：追加本地 DeepSeek Provider

**文件：** `../../.mewcode/config.yaml`
**依赖：** 无

**步骤：**

1. 保留现有 Claude 与 OpenAI provider 的顺序和字段。
2. 在列表末尾追加名称为 `deepseek-openai` 的 provider。
3. 设置 `protocol: openai`、`model: deepseek-v4-flash`。
4. 设置官方端点 `https://api.deepseek.com`。
5. 使用 `replace-with-deepseek-api-key` 占位，不接收或打印真实 Key。
6. 显式设置 `thinking: false`。

**验证：** 使用现有 `ConfigLoader` 加载本地配置，断言 provider 数量为 3、第三项字段正确且名称唯一。

### DT2：更新安全配置模板

**文件：** `../../.mewcode/config.yaml.example`
**依赖：** 无

**步骤：**

1. 保留原有两个示例不变。
2. 追加与 DT1 相同的 DeepSeek provider。
3. `api_key` 只使用 `replace-with-deepseek-api-key`。
4. 检查模板不存在真实密钥格式或本地配置内容泄漏。

**验证：** `git check-ignore .mewcode/config.yaml.example` 返回未忽略；`git diff -- .mewcode/config.yaml.example` 只包含 DeepSeek 占位配置。

### DT3：验证配置与现有测试

**文件：** `src/test/java/com/mewcode/**`（只运行，不修改）
**依赖：** DT1、DT2

**步骤：**

1. 运行配置加载测试。
2. 运行全部自动测试。
3. 构建 Shadow fat jar。
4. 检查 `../../.mewcode/config.yaml` 仍被 Git 忽略。
5. 检查 Git diff 中没有真实 API Key。

**验证：** `./gradlew clean test shadowJar` 成功，24 个现有测试全部通过，`../../build/libs/mewcode.jar` 生成。

### DT4：验证三 Provider TUI

**文件：** `../../build/libs/mewcode.jar`（运行产物）
**依赖：** DT3

**步骤：**

1. 使用项目 Java 21 toolchain 的绝对路径在 tmux 启动 MewCode。
2. 确认选择页依次显示 Claude、OpenAI、DeepSeek 三项。
3. 向下移动两次，确认第三项为 `deepseek-openai (deepseek-v4-flash)`。
4. 按 Enter 后确认状态栏显示 DeepSeek provider 与模型。
5. 输入 `/exit`，确认 shell 恢复正常。

**验证：** tmux 捕获输出包含第三项及状态栏字段，退出后 pane 当前命令恢复为 shell。

### DT5：验证真实 DeepSeek 对话

**文件：** `../../.mewcode/config.yaml`（用户仅在 IDE 中替换占位 Key）
**依赖：** DT4、用户完成本地 Key 替换

**步骤：**

1. 检查占位符已被用户本地替换，但不读取、不打印 Key。
2. 在 tmux 启动 MewCode 并选择第三项。
3. 发送一条真实、短小的对话请求。
4. 观察正文按流式增量显示，完成后输入框恢复。
5. 输入 `/exit` 并确认终端状态恢复。

**验证：** tmux 观察到 DeepSeek 返回正文、完成耗时和恢复后的输入框；任何输出均不包含 API Key。

### 增量执行顺序

```text
DT1 + DT2 → DT3 → DT4 → 用户在 IDE 中替换 Key → DT5
```
