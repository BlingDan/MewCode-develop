# Memory Regression Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让显式“记住/记录为项目知识”的请求不再调用文件工具，并保证已提交的异步 memory 更新在 MewCode 关闭前完成。

**Architecture:** 保留 ch09 的两级 `MemoryManager`/`MemoryStore` 和“无工具最终回复后异步更新”协议。在 Agent Loop 入口增加纯记忆请求的工具隔离；在 memory 管理器中把已提交任务与新任务接收状态分开，关闭时等待已提交任务收口。所有失败继续保留旧 notes/index，不写入 `MEWCODE.md` 或 session。

**Tech Stack:** Java 21、Gradle、JUnit 5、现有 `LlmClient`/SSE 抽象；不新增依赖。

**Spec:** `/Users/bytedance/IdeaProjects/Mewcode-develop/docs/ch9/spec.md`；实现边界参照 `plan.md`、`task.md`、`checklist.md` 的 F20–F33、AC20–AC32。

## Global Constraints

- 沿用 Java 21，不新增依赖。
- 普通请求未完成时不触发 memory 更新；无工具最终回复完成后才异步更新。
- memory 更新、解析或写入失败时保留旧笔记和旧索引，不建立持久化重试任务。
- 项目知识和参考资料只能写入项目级 memory；用户偏好和纠正反馈只能写入用户级 memory。
- `MEWCODE.md`、memory、session 和恢复提醒彼此分离；memory 草稿不得进入 session、UI 或普通请求历史。
- 标题、memory 更新和索引裁剪请求不携带工具定义。
- 所有测试使用临时项目/用户目录，不污染真实用户目录。

---

### Task 1: 固定显式记忆请求的工具隔离

**Files:**
- Modify: `src/test/java/com/mewcode/agent/AgentTurnCoordinatorPromptTest.java`
- Modify: `src/main/java/com/mewcode/agent/AgentTurnCoordinator.java`

**Interfaces:**
- Consumes: `startRun(String, AgentMode)` 的原始用户文本和现有 `ToolRegistry`。
- Produces: 纯记忆请求的 `PromptRequest.tools()` 为空；包含明确文件修改意图的请求保持现有工具声明。

- [ ] **Step 1: Write the failing test**

增加测试：输入 `记录下，项目知识：项目使用 GitHub Actions 做 CI`，Provider 收到的普通请求工具列表必须为空；测试仍使用现有 `CapturingClient` 和 `PromptBuilder`。

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.mewcode.agent.AgentTurnCoordinatorPromptTest`

Expected: FAIL，当前请求仍声明默认文件工具。

- [ ] **Step 3: Write minimal implementation**

在 `AgentTurnCoordinator` 根据记忆触发词和明确文件修改词计算纯记忆请求；纯记忆请求构造空工具 schema，并继续使用现有 completion listener 触发后台 memory 更新。不要修改普通请求和显式文件修改请求的工具策略。

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.mewcode.agent.AgentTurnCoordinatorPromptTest`

Expected: PASS。

### Task 2: 保证已提交的异步 memory 更新安全收口

**Files:**
- Modify: `src/test/java/com/mewcode/memory/MemoryManagerTest.java`
- Modify: `src/main/java/com/mewcode/memory/MemoryManager.java`

**Interfaces:**
- Consumes: `MemoryManager.updateAsync(List<Message>)` 和 `AutoCloseable.close()`。
- Produces: `close()` 拒绝新任务但等待 close 前已提交的任务完成；已提交任务使用提交时的 client；任务失败仍只输出安全诊断。

- [ ] **Step 1: Write the failing test**

增加阻塞 fake stream：memory 请求开始后暂停，调用 `manager.close()`；释放 Provider 后，`close()` 才返回且项目 note 和 `MEMORY.md` 已存在。

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.mewcode.memory.MemoryManagerTest.closeWaitsForInFlightUpdate`

Expected: FAIL，当前 `close()` 立即返回，后台任务可能被丢弃。

- [ ] **Step 3: Write minimal implementation**

以同步接收状态保护 `updateAsync` 与 `close` 的竞态；关闭时停止新任务并等待 executor 收口；已提交任务不因 `closed` 标记跳过，捕获提交时的 `LlmClient`。

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.mewcode.memory.MemoryManagerTest`

Expected: PASS。

### Task 3: 补齐 memory 全链路回归矩阵

**Files:**
- Modify: `src/test/java/com/mewcode/agent/AgentTurnCoordinatorPromptTest.java`
- Modify: `src/test/java/com/mewcode/memory/MemoryManagerTest.java`
- Modify: `src/test/java/com/mewcode/tui/MewCodeModelTest.java`

**Interfaces:**
- Consumes: ch09 AC20–AC32 对 memory 的既有测试入口和 fake LLM。
- Produces: 覆盖成功 create/update/delete、`[]`、非法 JSON/字段、类型越权、工具隔离、异步失败不阻塞、最新索引注入、session/UI 隔离的可重跑测试。

- [ ] **Step 1: 为现有遗漏场景增加失败测试**

覆盖普通最终回复触发一次 memory 更新、memory 请求不进入 `conversation.jsonl`、失败后仍可继续下一轮、下一轮读取最新 `MEMORY.md`，以及显式修改 `MEWCODE.md` 时不被误判为纯记忆请求。

- [ ] **Step 2: 运行窄范围测试并确认失败原因**

Run: `./gradlew test --tests com.mewcode.agent.AgentTurnCoordinatorPromptTest --tests com.mewcode.memory.MemoryManagerTest --tests com.mewcode.tui.MewCodeModelTest`

Expected: 新增测试在修复前暴露真实行为差异，而不是测试编译错误。

- [ ] **Step 3: 实现最小修复并保留 ch09 语义**

只改触发/隔离/收口所需代码，不把 memory 草稿写入 session，不改 `MEWCODE.md` 加载协议和两级目录格式。

- [ ] **Step 4: 运行窄范围测试**

Run: `./gradlew test --tests com.mewcode.agent.AgentTurnCoordinatorPromptTest --tests com.mewcode.memory.MemoryManagerTest --tests com.mewcode.tui.MewCodeModelTest`

Expected: PASS。

### Task 4: 全量验证与 tmux 验收

**Files:**
- Modify: `docs/ch9/checklist.md` only when updating evidence for newly verified items.

**Interfaces:**
- Consumes: 全部生产代码、JUnit 测试、打包产物和本地 SSE fake Provider。
- Produces: 全量 Gradle 测试、格式/空白检查、临时目录隔离证据，以及 tmux 中真实 MewCode 的记忆成功和失败隔离结果。

- [ ] **Step 1: Run the full automated suite**

Run: `./gradlew spotlessApply test shadowJar`

Expected: `BUILD SUCCESSFUL`，无失败测试。

- [ ] **Step 2: Run static and workspace checks**

Run: `./gradlew spotlessCheck` and `git diff --check`

Expected: 两个命令均成功；不触碰真实 `~/.mewcode`。

- [ ] **Step 3: Run tmux end-to-end verification**

在临时项目和临时配置中启动 fake OpenAI SSE 与 MewCode，分别输入“记录下，项目知识：项目使用 GitHub Actions 做 CI”和普通请求；确认 memory note/index 写入临时项目 `.mewcode/memory/`，`MEWCODE.md` 内容不变，memory 内部请求不出现在 session JSONL，重启后下一轮能注入索引。

- [ ] **Step 4: 对照 checklist 记录证据**

逐项复核 AC20–AC32；未实际验证的条目保持未勾选，不用“代码存在”代替运行证据。
