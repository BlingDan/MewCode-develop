package com.mewcode.tui;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mewcode.agent.AgentLoopConfig;
import com.mewcode.config.McpServerConfig;
import com.mewcode.config.ProviderConfig;
import com.mewcode.conversation.ConversationManager;
import com.mewcode.conversation.Message;
import com.mewcode.llm.CancellableLlmStream;
import com.mewcode.llm.LlmClient;
import com.mewcode.llm.PromptRequest;
import com.mewcode.llm.StreamEvent;
import com.mewcode.permission.BashSandboxFactory;
import com.mewcode.permission.PathAuthorizationStore;
import com.mewcode.permission.PermissionMode;
import com.mewcode.permission.PermissionRuleEngine;
import com.mewcode.session.HistoryStore;
import com.mewcode.tui.tea.Command;
import com.mewcode.tui.tea.KeyPressMessage;
import com.mewcode.tui.tea.WindowSizeMessage;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MewCodeModelTest {

  @TempDir Path projectRoot;

  @Test
  void rendersToolCallResultAndFinalTextInOrderWithoutChangingHistory() throws Exception {
    Path readme = projectRoot.resolve("README.md");
    Files.writeString(readme, "test", StandardCharsets.UTF_8);
    String path = readme.toAbsolutePath().normalize().toString();
    var first = new LinkedBlockingQueue<StreamEvent>();
    first.add(new StreamEvent.TextDelta("I will read it."));
    first.add(
        new StreamEvent.ToolCallComplete(
            "call-read", "ReadFile", Map.of("path", path, "offset", 1, "limit", 2)));
    first.add(new StreamEvent.StreamEnd("tool_use"));
    var second = new LinkedBlockingQueue<StreamEvent>();
    second.add(new StreamEvent.TextDelta("Read complete."));
    second.add(new StreamEvent.StreamEnd("end_turn"));
    var client = new QueueClient(first, second);
    var model = model(List.of(provider("one", "model-one")), client);
    model.update(new WindowSizeMessage(100, 30));
    type(model, "read README");
    model.update(key("enter"));

    List<String> printed = pollUntilReady(model, client);

    int callLine = indexContaining(printed, "● Read(");
    int resultLine = indexContaining(printed, "⎿ ");
    int finalLine = indexContaining(printed, "Read complete.");
    assertTrue(callLine >= 0, printed.toString());
    assertTrue(resultLine > callLine, printed.toString());
    assertTrue(finalLine > resultLine, printed.toString());
    assertFalse(
        client.lastMessages.get().stream()
            .map(Message::textContent)
            .anyMatch(text -> text.contains("● Read(") || text.contains("⎿ ")));
    assertTrue(model.view().contains("Send a message..."));
  }

  @Test
  void rendersToolErrorSummaryAndRecoversInput() throws Exception {
    var first = new LinkedBlockingQueue<StreamEvent>();
    first.add(new StreamEvent.ToolCallComplete("call-unknown", "MissingTool", Map.of()));
    first.add(new StreamEvent.StreamEnd("tool_use"));
    var second = new LinkedBlockingQueue<StreamEvent>();
    second.add(new StreamEvent.TextDelta("I could not use that tool."));
    second.add(new StreamEvent.StreamEnd("end_turn"));
    var client = new QueueClient(first, second);
    var model = model(List.of(provider("one", "model-one")), client);
    model.update(new WindowSizeMessage(100, 30));
    type(model, "try missing tool");
    model.update(key("enter"));

    List<String> printed = pollUntilReady(model, client);

    assertTrue(
        printed.stream().anyMatch(line -> line.contains("● MissingTool(")), printed.toString());
    assertTrue(printed.stream().anyMatch(line -> line.contains("⎿ Error:")), printed.toString());
    assertTrue(model.view().contains("Send a message..."));
  }

  @Test
  void rendersMultipleToolRowsInAssistantOrderAndKeepsStreamingText() throws Exception {
    var first = new LinkedBlockingQueue<StreamEvent>();
    first.add(new StreamEvent.TextDelta("Before tools. "));
    first.add(new StreamEvent.ToolCallComplete("call-a", "MissingA", Map.of()));
    first.add(new StreamEvent.ToolCallComplete("call-b", "MissingB", Map.of()));
    first.add(new StreamEvent.TextDelta("After tools."));
    first.add(new StreamEvent.StreamEnd("tool_use"));
    var second = new LinkedBlockingQueue<StreamEvent>();
    second.add(new StreamEvent.TextDelta("Final text."));
    second.add(new StreamEvent.StreamEnd("end_turn"));
    var client = new QueueClient(first, second);
    var model = model(List.of(provider("one", "model-one")), client);
    model.update(new WindowSizeMessage(100, 30));
    type(model, "run two tools");
    model.update(key("enter"));

    List<String> printed = pollUntilReady(model, client);

    int firstCall = indexContaining(printed, "● MissingA(");
    int secondCall = indexContaining(printed, "● MissingB(");
    int firstResult = indexContaining(printed, "未知工具：MissingA");
    int secondResult = indexContaining(printed, "未知工具：MissingB");
    int finalText = indexContaining(printed, "Final text.");
    assertTrue(firstCall >= 0, printed.toString());
    assertTrue(secondCall > firstCall, printed.toString());
    assertTrue(firstResult > secondCall, printed.toString());
    assertTrue(secondResult > firstResult, printed.toString());
    assertTrue(finalText > secondResult, printed.toString());
    assertTrue(
        printed.stream().anyMatch(line -> line.contains("Before tools. After tools.")),
        printed.toString());
  }

  @Test
  void singleProviderGoesDirectlyToChat() {
    var model = model(List.of(provider("one", "model-one")), new QueueClient());
    model.update(new WindowSizeMessage(80, 24));

    String view = model.view();
    assertTrue(view.contains("Ready for conversation and tools"));
    assertTrue(view.contains("Send a message..."));
    assertTrue(view.contains("one"));
    assertTrue(view.contains("model-one"));
  }

  @Test
  void planTogglesLocallyAndRetiredDoDoesNotReachProvider() {
    var client = new QueueClient();
    var model = model(List.of(provider("one", "model-one")), client);
    model.update(new WindowSizeMessage(80, 24));

    type(model, "/plan");
    var plan = model.update(key("enter"));
    assertNotNull(plan.command());
    assertTrue(model.view().contains("Plan Mode"));
    assertEquals(0, client.calls.get());

    type(model, "/plan");
    var execute = model.update(key("enter"));
    assertNotNull(execute.command());
    assertTrue(model.view().contains("Execute Mode"));

    type(model, "/do");
    var unknown = model.update(key("enter"));
    var printed = new ArrayList<String>();
    collectPrintLines(unknown.command(), printed);
    assertTrue(printed.stream().anyMatch(line -> line.contains("未知命令") && line.contains("/help")));
    assertEquals(0, client.calls.get());
  }

  @Test
  void helpIsLocalAndReviewSendsOnlyTheExpandedPromptToAgent() throws Exception {
    var client = new QueueClient(response("审查完成"));
    var model = model(List.of(provider("one", "model-one")), client);
    model.update(new WindowSizeMessage(100, 30));

    type(model, "/help");
    var help = model.update(key("enter"));
    var printed = new ArrayList<String>();
    collectPrintLines(help.command(), printed);
    assertTrue(printed.stream().anyMatch(line -> line.contains("/review")));
    assertEquals(0, client.calls.get());

    type(model, "/review 特别注意并发安全");
    model.update(key("enter"));
    awaitCalls(client, 1);
    assertTrue(client.lastMessages.get().getLast().textContent().contains("git diff"));
    assertTrue(client.lastMessages.get().getLast().textContent().contains("特别注意并发安全"));
    assertFalse(client.lastMessages.get().getLast().textContent().startsWith("/review"));
    awaitIdle(model);
  }

  @Test
  void memorySummaryShowsPinnedTitlesWithoutCallingProvider() {
    var client = new QueueClient();
    var model = model(List.of(provider("one", "model-one")), client);
    model.update(new WindowSizeMessage(100, 30));

    type(model, "/memory add user_preference 始终使用中文");
    model.update(key("enter"));
    type(model, "/memory");
    var result = model.update(key("enter"));
    var printed = new ArrayList<String>();
    collectPrintLines(result.command(), printed);

    assertTrue(printed.stream().anyMatch(line -> line.contains("始终使用中文")), printed.toString());
    assertEquals(0, client.calls.get());
    model.close();
  }

  @Test
  void tabCompletesUniqueCommandAndOffersDeduplicatedMultipleChoices() {
    var unique = model(List.of(provider("one", "model-one")), new QueueClient());
    unique.update(new WindowSizeMessage(100, 30));
    type(unique, "/cl");
    unique.update(key("tab"));
    assertTrue(unique.view().contains("/clear "));
    unique.close();

    var multiple = model(List.of(provider("one", "model-one")), new QueueClient());
    multiple.update(new WindowSizeMessage(100, 30));
    type(multiple, "/p");
    multiple.update(key("tab"));
    assertTrue(multiple.view().contains("/plan"));
    assertTrue(multiple.view().contains("/permission"));
    multiple.update(key("down"));
    multiple.update(key("enter"));
    assertTrue(multiple.view().contains("/permission "));
    multiple.close();
  }

  @Test
  void clearStartsANewSessionAndEmitsTerminalClearWithoutCallingProvider() throws Exception {
    var client = new QueueClient();
    var model = model(List.of(provider("one", "model-one")), client);
    model.update(new WindowSizeMessage(100, 30));
    Path sessions = projectRoot.resolve(".mewcode/sessions");
    long before;
    try (var paths = Files.list(sessions)) {
      before = paths.filter(Files::isDirectory).count();
    }

    type(model, "/clear");
    var result = model.update(key("enter"));

    assertTrue(containsClearScreen(result.command()));
    try (var paths = Files.list(sessions)) {
      assertEquals(before + 1, paths.filter(Files::isDirectory).count());
    }
    assertEquals(0, client.calls.get());
    model.close();
  }

  @Test
  void compactCommandDoesNotBecomeANormalUserRequest() throws Exception {
    var client = new QueueClient();
    var model = model(List.of(provider("one", "model-one")), client);
    model.update(new WindowSizeMessage(80, 24));

    type(model, "/compact");
    model.update(key("enter"));
    awaitIdle(model);

    assertEquals(0, client.calls.get());
    assertTrue(model.view().contains("Send a message..."));
    model.close();
  }

  @Test
  void compactCommandShowsCompletionAndDoesNotCreateANormalRound() throws Exception {
    var client =
        new QueueClient(
            response("O".repeat(40_000)),
            response("R".repeat(40_000)),
            response("recent"),
            response("recent 2"),
            response(summary()));
    var model = model(List.of(provider("one", "model-one")), client);
    model.update(new WindowSizeMessage(80, 24));

    submitAndAwaitIdle(model, client, "old 1", 1);
    submitAndAwaitIdle(model, client, "old 2", 2);
    submitAndAwaitIdle(model, client, "recent 1", 3);
    submitAndAwaitIdle(model, client, "recent 2", 4);

    type(model, "/compact");
    model.update(key("enter"));
    var printed = pollUntilIdleCollect(model, client, 5);

    assertEquals(5, client.calls.get());
    assertTrue(printed.stream().anyMatch(line -> line.contains("上下文压缩完成")), printed.toString());
    assertTrue(
        printed.stream().anyMatch(line -> line.contains("正在执行 /compact")), printed.toString());
    assertTrue(model.view().contains("Send a message..."));
    assertFalse(client.lastRequest.get().history().isEmpty());
    assertTrue(client.lastRequest.get().tools().isEmpty());
    model.close();
  }

  @Test
  void compactCommandShowsFailureAndReturnsToIdle() throws Exception {
    var client =
        new QueueClient(
            response("O".repeat(40_000)),
            response("R".repeat(40_000)),
            response("three"),
            response("four"),
            response(new StreamEvent.Error("summary provider failed")));
    var model = model(List.of(provider("one", "model-one")), client);
    model.update(new WindowSizeMessage(80, 24));

    submitAndAwaitIdle(model, client, "old 1", 1);
    submitAndAwaitIdle(model, client, "old 2", 2);
    submitAndAwaitIdle(model, client, "old 3", 3);
    submitAndAwaitIdle(model, client, "old 4", 4);

    type(model, "/compact");
    model.update(key("enter"));
    var printed = pollUntilIdleCollect(model, client, 5);

    assertEquals(5, client.calls.get());
    assertTrue(printed.stream().anyMatch(line -> line.contains("上下文管理失败")), printed.toString());
    assertTrue(model.view().contains("Send a message..."));
    model.close();
  }

  @Test
  void usesTheExplicitRootForPromptAndBanner() {
    var prompt = new AtomicReference<String>();
    var model =
        new MewCodeModel(
            List.of(provider("one", "model-one")),
            projectRoot,
            (provider, systemPrompt) -> {
              prompt.set(systemPrompt);
              return new QueueClient();
            },
            projectRoot.resolve("test-home"));

    var update = model.update(new WindowSizeMessage(100, 30));
    var printed = new ArrayList<String>();
    collectPrintLines(update.command(), printed);
    String root = projectRoot.toAbsolutePath().normalize().toString();

    assertTrue(printed.stream().anyMatch(line -> line.contains(root)), printed.toString());
    assertNotNull(prompt.get());
    assertTrue(prompt.get().contains("The current project root is: " + root));
    assertTrue(prompt.get().contains(root + "/.trae/skills/mew-spec/SKILL.md"));
  }

  @Test
  void loadsProjectInstructionsBeforeCreatingProvider() throws Exception {
    Files.writeString(projectRoot.resolve("MEWCODE.md"), "项目必须先读取相关文件。", StandardCharsets.UTF_8);
    var prompt = new AtomicReference<String>();
    var model =
        new MewCodeModel(
            List.of(provider("one", "model-one")),
            projectRoot,
            (provider, systemPrompt) -> {
              prompt.set(systemPrompt);
              return new QueueClient();
            },
            projectRoot.resolve("test-home"));

    model.update(new WindowSizeMessage(80, 24));

    assertNotNull(prompt.get());
    assertTrue(prompt.get().contains("项目必须先读取相关文件。"));
    model.close();
  }

  @Test
  void memoryRequestUpdatesProjectStoreWithoutChangingInstructionOrSession() throws Exception {
    Path instructionFile = projectRoot.resolve("MEWCODE.md");
    Files.writeString(instructionFile, "原有项目指令", StandardCharsets.UTF_8);
    var client = new MemoryQueueClient();
    var model = model(List.of(provider("one", "model-one")), client);

    try {
      model.update(new WindowSizeMessage(100, 30));
      type(model, "记录下，项目知识：项目使用 GitHub Actions 做 CI");
      model.update(key("enter"));
      awaitIdle(model);

      Path note = projectRoot.resolve(".mewcode/memory/project_knowledge_ci.md");
      Path index = projectRoot.resolve(".mewcode/memory/MEMORY.md");
      waitForFile(note);
      assertTrue(Files.readString(note).contains("GitHub Actions"));
      waitForContent(index, "project_knowledge_ci.md");
      assertFalse(
          Files.exists(projectRoot.resolve("test-home/.mewcode/memory/project_knowledge_ci.md")));
      assertEquals("原有项目指令", Files.readString(instructionFile));
      assertTrue(client.memoryRequests().stream().allMatch(request -> request.tools().isEmpty()));
      try (var files = Files.walk(projectRoot.resolve(".mewcode/sessions"))) {
        for (Path path : files.filter(Files::isRegularFile).toList()) {
          String text = Files.readString(path, StandardCharsets.UTF_8);
          assertFalse(
              text.contains("长期记忆整理器") || text.contains("project_knowledge_ci"), path.toString());
        }
      }
    } finally {
      model.close();
    }
  }

  @Test
  void sessionListCommandListsStoredSessionsWithoutCallingProvider() throws Exception {
    String id = "20260831-120000-abcd";
    Path directory = projectRoot.resolve(".mewcode/sessions").resolve(id);
    try (var history = new HistoryStore(directory, id, "model-one")) {
      history.appendMessages(List.of(new com.mewcode.conversation.Message("user", "已有会话")));
    }
    var client = new QueueClient();
    var model =
        new MewCodeModel(
            List.of(provider("one", "model-one")),
            projectRoot,
            (provider, prompt) -> client,
            projectRoot.resolve("test-home"));
    model.update(new WindowSizeMessage(80, 24));

    type(model, "/session list");
    var result = model.update(key("enter"));
    var printed = new ArrayList<String>();
    collectPrintLines(result.command(), printed);

    assertEquals(0, client.calls.get());
    assertTrue(printed.stream().anyMatch(line -> line.contains(id)), printed.toString());
    assertTrue(printed.stream().anyMatch(line -> line.contains("messages=1")), printed.toString());
    model.close();
  }

  @Test
  void resumeCommandLoadsHistoryIntoTheNextProviderRequest() throws Exception {
    String id = "20260831-120000-abcd";
    Path directory = projectRoot.resolve(".mewcode/sessions").resolve(id);
    try (var history = new HistoryStore(directory, id, "model-one")) {
      history.appendMessages(
          List.of(
              new com.mewcode.conversation.Message("user", "旧目标"),
              new com.mewcode.conversation.Message("assistant", "旧答案")));
    }
    var client = new QueueClient(response("新答案"));
    var model =
        new MewCodeModel(
            List.of(provider("one", "model-one")),
            projectRoot,
            (provider, prompt) -> client,
            projectRoot.resolve("test-home"));
    model.update(new WindowSizeMessage(80, 24));

    type(model, "/session resume " + id);
    var resume = model.update(key("enter"));
    var printed = new ArrayList<String>();
    collectPrintLines(resume.command(), printed);
    assertTrue(
        printed.stream().anyMatch(line -> line.contains("已恢复 session " + id)), printed.toString());

    type(model, "继续");
    model.update(key("enter"));
    awaitCalls(client, 1);
    assertEquals(
        List.of("旧目标", "旧答案", "继续"),
        client.lastMessages.get().stream().map(Message::textContent).toList());
    awaitIdle(model);
    model.close();
  }

  @Test
  void multipleProvidersCanSelectSecondEntry() {
    var model =
        model(
            List.of(provider("one", "model-one"), provider("two", "model-two")), new QueueClient());
    model.update(new WindowSizeMessage(80, 24));
    assertTrue(model.view().contains("Select a provider"));

    model.update(key("down"));
    model.update(key("enter"));

    assertTrue(model.view().contains("two"));
    assertTrue(model.view().contains("model-two"));
  }

  @Test
  void providerSelectionDoesNotWaitForMcpInitialization() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/mcp", MewCodeModelTest::delayedMcp);
    server.start();
    var slowMcp =
        new McpServerConfig(
            "slow",
            null,
            List.of(),
            Map.of(),
            "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp",
            Map.of());
    var model =
        new MewCodeModel(
            List.of(provider("one", "model-one"), provider("two", "model-two")),
            projectRoot,
            (provider, prompt) -> new QueueClient(),
            new AgentLoopConfig(),
            PermissionMode.DEFAULT,
            new PermissionRuleEngine(),
            new PathAuthorizationStore(projectRoot),
            BashSandboxFactory.create(),
            List.of(slowMcp));
    try {
      model.update(new WindowSizeMessage(80, 24));
      model.update(key("down"));

      long started = System.nanoTime();
      model.update(key("enter"));
      long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

      assertTrue(elapsedMillis < 500, "provider selection waited " + elapsedMillis + "ms");
      assertTrue(model.view().contains("MCP 正在连接"), model.view());

      type(model, "/status");
      long statusStarted = System.nanoTime();
      var status = model.update(key("enter"));
      long statusMillis = (System.nanoTime() - statusStarted) / 1_000_000;

      assertTrue(statusMillis < 500, "/status waited for MCP " + statusMillis + "ms");
      assertNotNull(status.command());
    } finally {
      model.close();
      server.stop(0);
    }
  }

  @Test
  void altEnterSubmitsMultilineAndLocksSecondSubmit() throws Exception {
    var client = new QueueClient();
    var model = model(List.of(provider("one", "model-one")), client);
    model.update(new WindowSizeMessage(80, 24));

    type(model, "first");
    model.update(key("alt+enter"));
    type(model, "second");
    model.update(key("enter"));
    model.update(key("enter"));

    awaitCalls(client, 1);
    assertEquals(1, client.calls.get());
    assertEquals(
        "first\nsecond", client.lastConversation.get().getMessages().getFirst().textContent());
    assertTrue(model.view().contains("Waiting for response"));
  }

  @Test
  void thinkingIsHiddenTextStreamsAndCompletionCommitsAssistant() {
    var queue = new LinkedBlockingQueue<StreamEvent>();
    var client = new QueueClient(queue);
    var model = model(List.of(provider("one", "model-one")), client);
    model.update(new WindowSizeMessage(80, 24));
    type(model, "hello");
    model.update(key("enter"));

    queue.offer(new StreamEvent.ThinkingDelta("NEVER_SHOW_THIS"));
    model.update(new MewCodeModel.StreamPollMessage());
    assertFalse(model.view().contains("NEVER_SHOW_THIS"));

    queue.offer(new StreamEvent.TextDelta("Hello "));
    model.update(new MewCodeModel.StreamPollMessage());
    assertTrue(model.view().contains("Hello "));

    queue.offer(new StreamEvent.TextDelta("world"));
    queue.offer(new StreamEvent.StreamEnd("end_turn"));
    model.update(new MewCodeModel.StreamPollMessage());

    assertTrue(model.view().contains("Send a message..."));
    assertEquals("hello", client.lastMessages.get().getFirst().textContent());
  }

  @Test
  void keepsStreamingViewWithinViewportForLongResponses() throws Exception {
    var queue = new LinkedBlockingQueue<StreamEvent>();
    var client = new QueueClient(queue);
    var model = model(List.of(provider("one", "model-one")), client);
    int width = 80;
    int height = 20;
    model.update(new WindowSizeMessage(width, height));
    type(model, "long response");
    model.update(key("enter"));
    awaitCalls(client, 1);

    for (int i = 0; i < 80; i++) {
      queue.offer(new StreamEvent.TextDelta("line-" + i + "\n"));
    }
    model.update(new MewCodeModel.StreamPollMessage());

    assertTrue(
        physicalLines(model.view(), width) <= height,
        () -> "streaming view exceeded terminal height: " + physicalLines(model.view(), width));
  }

  @Test
  void rendersCumulativeUsageWhileStreaming() throws Exception {
    var queue = new LinkedBlockingQueue<StreamEvent>();
    var client = new QueueClient(queue);
    var model = model(List.of(provider("one", "model-one")), client);
    model.update(new WindowSizeMessage(80, 24));
    type(model, "usage");
    model.update(key("enter"));
    awaitCalls(client, 1);

    queue.offer(new StreamEvent.Usage(OptionalLong.of(11), OptionalLong.of(3)));
    model.update(new MewCodeModel.StreamPollMessage());

    assertTrue(model.view().contains("输入 11"));
    assertTrue(model.view().contains("输出 3"));
    model.update(key("escape"));
  }

  @Test
  void errorRecoversAndAllowsAnotherRequest() throws Exception {
    var first = new LinkedBlockingQueue<StreamEvent>();
    var second = new LinkedBlockingQueue<StreamEvent>();
    var client = new QueueClient(first, second);
    var model = model(List.of(provider("one", "model-one")), client);
    model.update(new WindowSizeMessage(80, 24));
    type(model, "first");
    model.update(key("enter"));
    awaitCalls(client, 1);

    first.offer(new StreamEvent.Error("Authentication failed."));
    awaitIdle(model);
    var result = model.update(new MewCodeModel.StreamPollMessage());

    assertNotNull(result);
    assertTrue(model.view().contains("Send a message..."));
    type(model, "second");
    model.update(key("enter"));
    awaitCalls(client, 2);
    assertEquals(2, client.calls.get());
  }

  @Test
  void promptTooLongRecoveryDropsPartialResponseBeforeRetry() throws Exception {
    var first =
        response(
            new StreamEvent.TextDelta("stale partial"),
            new StreamEvent.Error("prompt_too_long", StreamEvent.ErrorKind.CONTEXT_LENGTH));
    var second = response("fresh response");
    var client = new QueueClient(first, second);
    var model = model(List.of(provider("one", "model-one")), client);
    model.update(new WindowSizeMessage(80, 24));
    type(model, "hello");
    model.update(key("enter"));

    var printed = pollUntilReady(model, client);

    assertTrue(
        printed.stream().anyMatch(line -> line.contains("fresh response")), printed.toString());
    assertFalse(
        printed.stream().anyMatch(line -> line.contains("stale partialfresh response")),
        printed.toString());
    assertTrue(printed.stream().anyMatch(line -> line.contains("正在压缩并重试")), printed.toString());
    model.close();
  }

  @Test
  void ctrlCQuitsWhenIdleButCancelsTheActiveLoop() throws Exception {
    var model = model(List.of(provider("one", "model-one")), new QueueClient());
    model.update(new WindowSizeMessage(80, 24));
    assertNotNull(model.update(key("ctrl+c")).command());

    type(model, "hello");
    model.update(key("enter"));
    assertNotNull(model.update(key("ctrl+c")).command());
    assertTrue(model.view().contains("Send a message..."));
  }

  @Test
  void escapeCancelsTheActiveLoopAndReturnsToIdle() throws Exception {
    var client = new QueueClient(new LinkedBlockingQueue<>());
    var model = model(List.of(provider("one", "model-one")), client);
    model.update(new WindowSizeMessage(80, 24));

    type(model, "hello");
    model.update(key("enter"));
    awaitCalls(client, 1);

    assertNotNull(model.update(key("escape")).command());
    assertTrue(model.view().contains("Send a message..."));
    assertEquals(1, client.calls.get());
  }

  @Test
  void editsMultilineInputAtTheCursor() throws Exception {
    var client = new QueueClient();
    var model = model(List.of(provider("one", "model-one")), client);
    model.update(new WindowSizeMessage(80, 24));

    type(model, "ac");
    model.update(key("left"));
    type(model, "b");
    model.update(key("end"));
    model.update(key("alt+enter"));
    type(model, "xy");
    model.update(key("home"));
    type(model, "<");
    model.update(key("end"));
    type(model, ">");
    model.update(key("backspace"));
    model.update(key("enter"));
    awaitCalls(client, 1);

    assertEquals("abc\n<xy", client.lastMessages.get().getFirst().textContent());
  }

  @Test
  void unknownSlashInputStaysLocalAndPartialErrorIsNotAssistantHistory() throws Exception {
    var first = new LinkedBlockingQueue<StreamEvent>();
    var second = new LinkedBlockingQueue<StreamEvent>();
    var client = new QueueClient(first, second);
    var model = model(List.of(provider("one", "model-one")), client);
    model.update(new WindowSizeMessage(80, 24));

    type(model, "/unknown");
    var unknown = model.update(key("enter"));
    var printed = new ArrayList<String>();
    collectPrintLines(unknown.command(), printed);
    assertTrue(printed.stream().anyMatch(line -> line.contains("/help")));
    assertEquals(0, client.calls.get());

    type(model, "first");
    model.update(key("enter"));
    awaitCalls(client, 1);
    assertEquals("first", client.lastMessages.get().getFirst().textContent());

    first.offer(new StreamEvent.TextDelta("partial-secret"));
    first.offer(new StreamEvent.Error("Connection interrupted."));
    awaitIdle(model);
    type(model, "next");
    model.update(key("enter"));
    awaitCalls(client, 2);

    assertEquals(
        List.of(new Message("user", "first"), new Message("user", "next")),
        client.lastMessages.get());
  }

  private MewCodeModel model(List<ProviderConfig> providers, LlmClient client) {
    return new MewCodeModel(
        providers, projectRoot, (provider, prompt) -> client, projectRoot.resolve("test-home"));
  }

  private static ProviderConfig provider(String name, String model) {
    var provider = new ProviderConfig();
    provider.setName(name);
    provider.setProtocol("openai");
    provider.setModel(model);
    provider.setApiKey("test-key");
    return provider;
  }

  private static KeyPressMessage key(String key) {
    return new KeyPressMessage(key, null);
  }

  private static void type(MewCodeModel model, String text) {
    for (char c : text.toCharArray()) {
      model.update(new KeyPressMessage(String.valueOf(c), new char[] {c}));
    }
  }

  private static void submitAndAwaitIdle(
      MewCodeModel model, QueueClient client, String text, int expectedCalls) throws Exception {
    type(model, text);
    model.update(key("enter"));
    awaitCalls(client, expectedCalls);
    awaitIdle(model);
  }

  private static List<String> pollUntilIdleCollect(
      MewCodeModel model, QueueClient client, int expectedCalls) throws Exception {
    var printed = new ArrayList<String>();
    for (int attempt = 0; attempt < 200; attempt++) {
      collectPrintLines(model.update(new MewCodeModel.StreamPollMessage()).command(), printed);
      if (client.calls.get() >= expectedCalls && model.view().contains("Send a message...")) {
        return printed;
      }
      Thread.sleep(10);
    }
    fail("operation did not return to idle; printed=" + printed);
    return printed;
  }

  private static BlockingQueue<StreamEvent> response(String text) {
    var queue = new LinkedBlockingQueue<StreamEvent>();
    queue.add(new StreamEvent.TextDelta(text));
    queue.add(new StreamEvent.StreamEnd("end_turn"));
    return queue;
  }

  private static BlockingQueue<StreamEvent> response(StreamEvent... events) {
    var queue = new LinkedBlockingQueue<StreamEvent>();
    queue.addAll(List.of(events));
    return queue;
  }

  private static String summary() {
    return """
        # 用户目标与约束
        目标。
        # 已完成工作与关键决策
        工作。
        # 当前代码/文件状态
        状态。
        # 未完成事项与下一步
        下一步。
        # 重要工具结果文件索引
        文件。
        """;
  }

  private static List<String> pollUntilReady(MewCodeModel model, QueueClient client)
      throws Exception {
    var printed = new ArrayList<String>();
    for (int attempt = 0; attempt < 150; attempt++) {
      Thread.sleep(20);
      var update = model.update(new MewCodeModel.StreamPollMessage());
      collectPrintLines(update.command(), printed);
      if (client.calls.get() == 2 && model.view().contains("Send a message...")) {
        return printed;
      }
    }
    fail("agent turn did not complete; printed=" + printed);
    return printed;
  }

  private static void awaitCalls(QueueClient client, int expected) throws Exception {
    for (int attempt = 0; attempt < 100; attempt++) {
      if (client.calls.get() >= expected) return;
      Thread.sleep(10);
    }
    fail("expected " + expected + " provider calls, got " + client.calls.get());
  }

  private static void waitForFile(Path file) throws Exception {
    for (int attempt = 0; attempt < 300; attempt++) {
      if (Files.exists(file)) return;
      Thread.sleep(10);
    }
    fail("memory note was not created: " + file);
  }

  private static void waitForContent(Path file, String content) throws Exception {
    for (int attempt = 0; attempt < 300; attempt++) {
      if (Files.exists(file) && Files.readString(file).contains(content)) return;
      Thread.sleep(10);
    }
    fail("memory file did not contain expected content: " + file);
  }

  private static void awaitIdle(MewCodeModel model) throws Exception {
    for (int attempt = 0; attempt < 150; attempt++) {
      model.update(new MewCodeModel.StreamPollMessage());
      if (model.view().contains("Send a message...")) return;
      Thread.sleep(10);
    }
    fail("agent turn did not return to idle");
  }

  private static void collectPrintLines(Command command, List<String> output) {
    if (command instanceof Command.PrintLine line) {
      output.add(line.text());
    } else if (command instanceof Command.Batch batch) {
      for (Command child : batch.commands()) collectPrintLines(child, output);
    }
  }

  private static boolean containsClearScreen(Command command) {
    if (command instanceof Command.ClearScreen) return true;
    if (command instanceof Command.Batch batch) {
      return batch.commands().stream().anyMatch(MewCodeModelTest::containsClearScreen);
    }
    return false;
  }

  private static void delayedMcp(HttpExchange exchange) throws java.io.IOException {
    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    if ("DELETE".equals(exchange.getRequestMethod())) {
      exchange.sendResponseHeaders(204, -1);
      exchange.close();
      return;
    }
    JsonNode request = JSON.readTree(body);
    String method = request.path("method").asText();
    if ("initialize".equals(method)) {
      try {
        Thread.sleep(1_000);
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
      }
    }
    if ("notifications/initialized".equals(method)) {
      exchange.sendResponseHeaders(202, -1);
      exchange.close();
      return;
    }
    ObjectNode result = JSON.createObjectNode();
    if ("initialize".equals(method)) {
      result.put("protocolVersion", com.mewcode.mcp.McpManager.SUPPORTED_PROTOCOL_VERSION);
      result.set("capabilities", JSON.createObjectNode().set("tools", JSON.createObjectNode()));
      result.set("serverInfo", JSON.createObjectNode().put("name", "slow").put("version", "1"));
      exchange.getResponseHeaders().add("Mcp-Session-Id", "test-session");
    } else if ("tools/list".equals(method)) {
      result.set("tools", JSON.createArrayNode());
    } else {
      exchange.sendResponseHeaders(404, -1);
      exchange.close();
      return;
    }
    ObjectNode response = JSON.createObjectNode().put("jsonrpc", "2.0");
    response.set("id", request.get("id"));
    response.set("result", result);
    byte[] responseBody = JSON.writeValueAsBytes(response);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, responseBody.length);
    exchange.getResponseBody().write(responseBody);
    exchange.close();
  }

  private static int indexContaining(List<String> lines, String needle) {
    for (int i = 0; i < lines.size(); i++) {
      if (lines.get(i).contains(needle)) return i;
    }
    return -1;
  }

  private static int physicalLines(String value, int width) {
    int total = 0;
    for (String line : value.split("\\n", -1)) {
      String plain = ANSI_ESCAPE.matcher(line).replaceAll("");
      total +=
          Math.max(
              1, (int) Math.ceil((double) com.mewcode.tui.tea.Program.displayWidth(plain) / width));
    }
    return total;
  }

  private static final Pattern ANSI_ESCAPE =
      Pattern.compile("\\033\\[[0-9;]*[a-zA-Z]|\\033\\][^\\007\\033]*(?:\\007|\\033\\\\\\\\)");

  private static final ObjectMapper JSON = new ObjectMapper();

  private static final class MemoryQueueClient implements LlmClient {
    private static final String MEMORY_RESPONSE =
        "[{\"action\":\"create\",\"level\":\"project\",\"type\":\"project_knowledge\",\"title\":\"CI\",\"slug\":\"ci\",\"content\":\"Use GitHub Actions.\"}]";

    private final List<PromptRequest> requests = new ArrayList<>();

    @Override
    public synchronized CancellableLlmStream openStream(PromptRequest request) {
      requests.add(request);
      String system = request.flattenedSystemPrompt();
      if (system.contains("会话标题生成器")) return new CancellableLlmStream(response("记忆测试"), () -> {});
      if (system.contains("长期记忆整理器")) {
        return new CancellableLlmStream(response(MEMORY_RESPONSE), () -> {});
      }
      return new CancellableLlmStream(response("已记录。"), () -> {});
    }

    private synchronized List<PromptRequest> memoryRequests() {
      return requests.stream()
          .filter(request -> request.flattenedSystemPrompt().contains("长期记忆整理器"))
          .toList();
    }
  }

  private static final class QueueClient implements LlmClient {
    private final ArrayDeque<BlockingQueue<StreamEvent>> queues = new ArrayDeque<>();
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<ConversationManager> lastConversation = new AtomicReference<>();
    private final AtomicReference<List<Message>> lastMessages = new AtomicReference<>();
    private final AtomicReference<PromptRequest> lastRequest = new AtomicReference<>();

    @SafeVarargs
    private QueueClient(BlockingQueue<StreamEvent>... queues) {
      this.queues.addAll(List.of(queues));
    }

    @Override
    public BlockingQueue<StreamEvent> stream(ConversationManager conversation) {
      calls.incrementAndGet();
      lastConversation.set(conversation);
      lastMessages.set(conversation.getMessages());
      return queues.isEmpty() ? new LinkedBlockingQueue<>() : queues.removeFirst();
    }

    @Override
    public synchronized CancellableLlmStream openStream(PromptRequest request) {
      if (isBackgroundRequest(request)) {
        return new CancellableLlmStream(
            response(request.flattenedSystemPrompt().contains("会话标题生成器") ? "标题" : "[]"), () -> {});
      }
      calls.incrementAndGet();
      lastRequest.set(request);
      lastMessages.set(request.history());
      var history = new ConversationManager();
      request.history().forEach(history::addMessage);
      lastConversation.set(history);
      BlockingQueue<StreamEvent> response =
          queues.isEmpty() ? new LinkedBlockingQueue<>() : queues.removeFirst();
      return new CancellableLlmStream(response, () -> {});
    }

    private static boolean isBackgroundRequest(PromptRequest request) {
      String system = request.flattenedSystemPrompt();
      return system.contains("长期记忆整理器")
          || system.contains("会话标题生成器")
          || system.contains("memory 索引裁剪器");
    }
  }
}
