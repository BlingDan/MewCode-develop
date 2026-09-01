package com.mewcode.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mewcode.compact.ContextManager;
import com.mewcode.compact.ContextTrigger;
import com.mewcode.conversation.ConversationManager;
import com.mewcode.conversation.Message;
import com.mewcode.llm.CancellableLlmStream;
import com.mewcode.llm.LlmClient;
import com.mewcode.llm.PromptRequest;
import com.mewcode.llm.StreamEvent;
import com.mewcode.prompt.PromptBuilder;
import com.mewcode.tool.FileStateCache;
import com.mewcode.tool.ToolApiProtocol;
import com.mewcode.tool.ToolExecutionContext;
import com.mewcode.tool.ToolExecutor;
import com.mewcode.tool.ToolRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentTurnCoordinatorPromptTest {

  @TempDir Path projectRoot;

  @Test
  void sendsStructuredRequestsWithoutPersistingReminderMessages() throws Exception {
    var client = new CapturingClient(List.of(response("done")));
    var conversation = new ConversationManager();
    var registry = ToolRegistry.createDefault();

    try (var executor =
        new ToolExecutor(
            registry,
            new ToolExecutionContext(projectRoot, Duration.ofSeconds(2), new FileStateCache()))) {
      var coordinator =
          new AgentTurnCoordinator(
              client,
              registry,
              executor,
              conversation,
              ToolApiProtocol.OPENAI,
              new AgentLoopConfig(),
              new PromptRequestFactory(PromptBuilder.buildBundle(projectRoot)));

      var events = new ArrayList<AgentEvent>();
      AgentRun run = coordinator.startRun("hello", AgentMode.EXECUTE);
      while (true) {
        AgentEvent event = run.events().next();
        events.add(event);
        if (event instanceof AgentEvent.LoopComplete) break;
      }

      assertEquals(1, client.requests.size());
      PromptRequest request = client.requests.getFirst();
      assertEquals(
          List.of(new com.mewcode.conversation.Message("user", "hello")), request.history());
      assertTrue(request.reminder().isPresent());
      assertTrue(request.reminder().orElseThrow().textContent().contains("<system-reminder>"));
      assertEquals(2, conversation.getMessages().size());
      assertFalse(
          conversation.getMessages().stream()
              .anyMatch(message -> message.textContent().contains("system-reminder")));
      assertTrue(events.stream().anyMatch(event -> event instanceof AgentEvent.LoopComplete));
    }
  }

  @Test
  void injectsDynamicPromptAdditionsAndNotifiesAfterCompletedTurn() throws Exception {
    var client = new CapturingClient(List.of(response("done")));
    var conversation = new ConversationManager();
    var registry = ToolRegistry.createDefault();
    var completed = new AtomicReference<List<Message>>();

    try (var executor =
        new ToolExecutor(
            registry,
            new ToolExecutionContext(projectRoot, Duration.ofSeconds(2), new FileStateCache()))) {
      var coordinator =
          new AgentTurnCoordinator(
              client,
              registry,
              executor,
              conversation,
              ToolApiProtocol.OPENAI,
              new AgentLoopConfig(),
              new PromptRequestFactory(PromptBuilder.buildBundle(projectRoot)));
      coordinator.setPromptAdditionsSupplier(
          () ->
              new PromptAdditions(
                  "MEMORY_INDEX", Optional.of(new Message("user", "RESUME_REMINDER"))));
      coordinator.setCompletionListener(completed::set);

      AgentRun run = coordinator.startRun("hello", AgentMode.EXECUTE);
      while (!(run.events().next() instanceof AgentEvent.LoopComplete)) {
        // drain the run until the completion callback has run
      }

      PromptRequest request = client.requests.getFirst();
      assertTrue(
          request.systemSegments().stream().anyMatch(segment -> segment.contains("MEMORY_INDEX")));
      assertTrue(request.reminder().orElseThrow().textContent().contains("RESUME_REMINDER"));
      assertEquals(
          List.of(new Message("user", "hello"), new Message("assistant", "done")), completed.get());
    }
  }

  @Test
  void routesRememberRequestsToMemoryInsteadOfProjectInstructionFiles() throws Exception {
    var client = new CapturingClient(List.of(response("已记住。")));
    var conversation = new ConversationManager();
    var registry = ToolRegistry.createDefault();

    try (var executor =
        new ToolExecutor(
            registry,
            new ToolExecutionContext(projectRoot, Duration.ofSeconds(2), new FileStateCache()))) {
      var coordinator =
          new AgentTurnCoordinator(
              client,
              registry,
              executor,
              conversation,
              ToolApiProtocol.OPENAI,
              new AgentLoopConfig(),
              new PromptRequestFactory(PromptBuilder.buildBundle(projectRoot)));

      AgentRun run = coordinator.startRun("记住我正在找 agent 相关工作", AgentMode.EXECUTE);
      while (!(run.events().next() instanceof AgentEvent.LoopComplete)) {
        // drain the run
      }

      String system = client.requests.getFirst().flattenedSystemPrompt();
      assertTrue(system.contains("长期 memory"), system);
      assertTrue(system.contains("不要修改 MEWCODE.md"), system);
    }
  }

  @Test
  void memoryOnlyRequestsDoNotExposeFileTools() throws Exception {
    assertMemoryRequestHasNoTools("记录下，项目知识：项目使用 GitHub Actions 做 CI");
  }

  @Test
  void commonMemoryPhrasesDoNotExposeFileTools() throws Exception {
    for (String request :
        List.of("记下：项目使用 Gradle 构建", "保存为个人偏好：回答使用中文", "remember that this project uses Java 21")) {
      assertMemoryRequestHasNoTools(request);
    }
  }

  @Test
  void memoryOnlyRequestsRejectAProviderToolCallBeforeItCanWriteFiles() throws Exception {
    Path instructionFile = projectRoot.resolve("MEWCODE.md");
    String path = instructionFile.toAbsolutePath().normalize().toString();
    var client =
        new CapturingClient(
            List.of(
                response(
                    new StreamEvent.ToolCallComplete(
                        "call-write", "WriteFile", Map.of("path", path, "content", "bad")),
                    new StreamEvent.StreamEnd("tool_use"))));
    var conversation = new ConversationManager();
    var registry = ToolRegistry.createDefault();

    try (var executor =
        new ToolExecutor(
            registry,
            new ToolExecutionContext(projectRoot, Duration.ofSeconds(2), new FileStateCache()))) {
      var coordinator =
          new AgentTurnCoordinator(
              client,
              registry,
              executor,
              conversation,
              ToolApiProtocol.OPENAI,
              new AgentLoopConfig(),
              new PromptRequestFactory(PromptBuilder.buildBundle(projectRoot)));

      AgentRun run = coordinator.startRun("记住这条项目知识：不要把它写进文件", AgentMode.EXECUTE);
      while (!(run.events().next() instanceof AgentEvent.LoopComplete)) {
        // drain the run
      }

      assertTrue(client.requests.getFirst().tools().isEmpty());
      assertFalse(Files.exists(instructionFile));
    }
  }

  @Test
  void explicitFileMutationKeepsToolsAvailableEvenWhenTheRequestSaysRemember() throws Exception {
    var client = new CapturingClient(List.of(response("我会修改文件。")));
    var conversation = new ConversationManager();
    var registry = ToolRegistry.createDefault();

    try (var executor =
        new ToolExecutor(
            registry,
            new ToolExecutionContext(projectRoot, Duration.ofSeconds(2), new FileStateCache()))) {
      var coordinator =
          new AgentTurnCoordinator(
              client,
              registry,
              executor,
              conversation,
              ToolApiProtocol.OPENAI,
              new AgentLoopConfig(),
              new PromptRequestFactory(PromptBuilder.buildBundle(projectRoot)));

      AgentRun run = coordinator.startRun("记住这条规则，并修改 README.md", AgentMode.EXECUTE);
      while (!(run.events().next() instanceof AgentEvent.LoopComplete)) {
        // drain the run
      }

      assertFalse(client.requests.getFirst().tools().isEmpty());
    }
  }

  @Test
  void recordingToARegularFileIsNotMistakenForAMemoryRequest() throws Exception {
    var client = new CapturingClient(List.of(response("我会记录到文件。")));
    var conversation = new ConversationManager();
    var registry = ToolRegistry.createDefault();

    try (var executor =
        new ToolExecutor(
            registry,
            new ToolExecutionContext(projectRoot, Duration.ofSeconds(2), new FileStateCache()))) {
      var coordinator =
          new AgentTurnCoordinator(
              client,
              registry,
              executor,
              conversation,
              ToolApiProtocol.OPENAI,
              new AgentLoopConfig(),
              new PromptRequestFactory(PromptBuilder.buildBundle(projectRoot)));

      AgentRun run = coordinator.startRun("记录测试结果到 results.txt", AgentMode.EXECUTE);
      while (!(run.events().next() instanceof AgentEvent.LoopComplete)) {
        // drain the run
      }

      assertFalse(client.requests.getFirst().tools().isEmpty());
    }
  }

  @Test
  void investigatingARecordInTheCodebaseStillKeepsToolsAvailable() throws Exception {
    var client = new CapturingClient(List.of(response("我会检查代码。")));
    var conversation = new ConversationManager();
    var registry = ToolRegistry.createDefault();

    try (var executor =
        new ToolExecutor(
            registry,
            new ToolExecutionContext(projectRoot, Duration.ofSeconds(2), new FileStateCache()))) {
      var coordinator =
          new AgentTurnCoordinator(
              client,
              registry,
              executor,
              conversation,
              ToolApiProtocol.OPENAI,
              new AgentLoopConfig(),
              new PromptRequestFactory(PromptBuilder.buildBundle(projectRoot)));

      AgentRun run = coordinator.startRun("记录一下当前代码中的 TODO", AgentMode.EXECUTE);
      while (!(run.events().next() instanceof AgentEvent.LoopComplete)) {
        // drain the run
      }

      assertFalse(client.requests.getFirst().tools().isEmpty());
    }
  }

  @Test
  void providerFailureDoesNotNotifyTheMemoryCompletionListener() throws Exception {
    var client = new CapturingClient(List.of(response(new StreamEvent.Error("provider failed"))));
    var conversation = new ConversationManager();
    var registry = ToolRegistry.createDefault();
    var notified = new AtomicReference<List<Message>>();

    try (var executor =
        new ToolExecutor(
            registry,
            new ToolExecutionContext(projectRoot, Duration.ofSeconds(2), new FileStateCache()))) {
      var coordinator =
          new AgentTurnCoordinator(
              client,
              registry,
              executor,
              conversation,
              ToolApiProtocol.OPENAI,
              new AgentLoopConfig(),
              new PromptRequestFactory(PromptBuilder.buildBundle(projectRoot)));
      coordinator.setCompletionListener(notified::set);

      AgentRun run = coordinator.startRun("记住这条信息", AgentMode.EXECUTE);
      while (!(run.events().next() instanceof AgentEvent.LoopComplete)) {
        // drain the run
      }

      assertTrue(notified.get() == null);
      assertEquals(1, conversation.getMessages().size());
    }
  }

  private void assertMemoryRequestHasNoTools(String requestText) throws Exception {
    var client = new CapturingClient(List.of(response("已记住。")));
    var conversation = new ConversationManager();
    var registry = ToolRegistry.createDefault();

    try (var executor =
        new ToolExecutor(
            registry,
            new ToolExecutionContext(projectRoot, Duration.ofSeconds(2), new FileStateCache()))) {
      var coordinator =
          new AgentTurnCoordinator(
              client,
              registry,
              executor,
              conversation,
              ToolApiProtocol.OPENAI,
              new AgentLoopConfig(),
              new PromptRequestFactory(PromptBuilder.buildBundle(projectRoot)));

      AgentRun run = coordinator.startRun(requestText, AgentMode.EXECUTE);
      while (!(run.events().next() instanceof AgentEvent.LoopComplete)) {
        // drain the run
      }

      assertTrue(client.requests.getFirst().tools().isEmpty(), requestText);
    }
  }

  @Test
  void preparesContextBeforeSendingAndRebuildsRequestAfterCompaction() throws Exception {
    var client = new CapturingClient(List.of(response(summary()), response("done")));
    var conversation = new ConversationManager();
    conversation.addUserMessage("old goal");
    conversation.addAssistantMessage("O".repeat(40_000));
    conversation.addUserMessage("recent request");
    conversation.addAssistantMessage("R".repeat(40_000));
    conversation.addUserMessage("recent constraint");
    conversation.addAssistantMessage("recent answer");
    conversation.addUserMessage("last");
    var registry = ToolRegistry.createDefault();

    try (var executor =
            new ToolExecutor(
                registry,
                new ToolExecutionContext(
                    projectRoot, Duration.ofSeconds(2), new FileStateCache()));
        var contextManager = new ContextManager(projectRoot, client, 30_000)) {
      var coordinator =
          new AgentTurnCoordinator(
              client,
              registry,
              executor,
              conversation,
              ToolApiProtocol.OPENAI,
              new AgentLoopConfig(),
              new PromptRequestFactory(PromptBuilder.buildBundle(projectRoot)),
              contextManager);
      var completed = new AtomicReference<List<Message>>();
      coordinator.setCompletionListener(completed::set);

      var events = new ArrayList<AgentEvent>();
      AgentRun run = coordinator.startRun("hello", AgentMode.EXECUTE);
      while (true) {
        AgentEvent event = run.events().next();
        events.add(event);
        if (event instanceof AgentEvent.LoopComplete) break;
      }

      assertEquals(2, client.requests.size());
      assertTrue(client.requests.getFirst().tools().isEmpty());
      assertTrue(
          client.requests.get(1).history().stream()
              .anyMatch(message -> message.textContent().contains("用户目标与约束")));
      assertTrue(
          client.requests.get(1).history().stream()
              .anyMatch(message -> message.textContent().equals("hello")));
      assertEquals(
          1, events.stream().filter(event -> event instanceof AgentEvent.TurnComplete).count());
      assertTrue(
          events.stream()
              .anyMatch(
                  event ->
                      event instanceof AgentEvent.CompactionStarted started
                          && started.trigger() == ContextTrigger.AUTO));
      assertTrue(events.stream().anyMatch(event -> event instanceof AgentEvent.CompactionComplete));
      assertTrue(
          completed.get().stream().anyMatch(message -> message.textContent().equals("hello")));
      assertTrue(
          completed.get().stream().anyMatch(message -> message.textContent().equals("done")));
      int startedIndex = -1;
      int completedIndex = -1;
      for (int index = 0; index < events.size(); index++) {
        if (events.get(index) instanceof AgentEvent.CompactionStarted && startedIndex < 0)
          startedIndex = index;
        if (events.get(index) instanceof AgentEvent.CompactionComplete && completedIndex < 0)
          completedIndex = index;
      }
      assertTrue(startedIndex >= 0 && completedIndex > startedIndex, events.toString());
    }
  }

  @Test
  void forceCompactsAndRetriesPromptTooLongOnlyOnce() throws Exception {
    var client =
        new CapturingClient(
            List.of(
                response(
                    new StreamEvent.Error("prompt_too_long", StreamEvent.ErrorKind.CONTEXT_LENGTH)),
                response(summary()),
                response("done")));
    var conversation = new ConversationManager();
    conversation.addUserMessage("old goal");
    conversation.addAssistantMessage("O".repeat(40_000));
    conversation.addUserMessage("recent request");
    conversation.addAssistantMessage("R".repeat(40_000));
    conversation.addUserMessage("recent constraint");
    conversation.addAssistantMessage("recent answer");
    conversation.addUserMessage("last");
    var registry = ToolRegistry.createDefault();

    try (var executor =
            new ToolExecutor(
                registry,
                new ToolExecutionContext(
                    projectRoot, Duration.ofSeconds(2), new FileStateCache()));
        var contextManager = new ContextManager(projectRoot, client, 128_000)) {
      var coordinator =
          new AgentTurnCoordinator(
              client,
              registry,
              executor,
              conversation,
              ToolApiProtocol.OPENAI,
              new AgentLoopConfig(),
              new PromptRequestFactory(PromptBuilder.buildBundle(projectRoot)),
              contextManager);

      var events = new ArrayList<AgentEvent>();
      AgentRun run = coordinator.startRun("hello", AgentMode.EXECUTE);
      while (true) {
        AgentEvent event = run.events().next();
        events.add(event);
        if (event instanceof AgentEvent.LoopComplete) break;
      }

      assertEquals(3, client.requests.size());
      assertTrue(
          client.requests.get(2).history().stream()
              .anyMatch(message -> message.textContent().contains("用户目标与约束")));
      assertEquals(
          1,
          client.requests.get(2).history().stream()
              .filter(message -> message.textContent().equals("hello"))
              .count());
      assertEquals(
          1, events.stream().filter(event -> event instanceof AgentEvent.TurnComplete).count());
      assertTrue(events.stream().anyMatch(event -> event instanceof AgentEvent.LoopComplete));
      assertTrue(
          events.stream()
              .anyMatch(
                  event ->
                      event instanceof AgentEvent.CompactionStarted started
                          && started.trigger() == ContextTrigger.EMERGENCY));
    }
  }

  @Test
  void doesNotRetryWhenTheCompactedRequestIsStillTooLong() throws Exception {
    var client =
        new CapturingClient(
            List.of(
                response(
                    new StreamEvent.Error("prompt_too_long", StreamEvent.ErrorKind.CONTEXT_LENGTH)),
                response(summary()),
                response(
                    new StreamEvent.Error(
                        "prompt_too_long", StreamEvent.ErrorKind.CONTEXT_LENGTH))));
    var conversation = new ConversationManager();
    conversation.addUserMessage("old goal");
    conversation.addAssistantMessage("O".repeat(40_000));
    conversation.addUserMessage("recent request");
    conversation.addAssistantMessage("R".repeat(40_000));
    conversation.addUserMessage("recent constraint");
    conversation.addAssistantMessage("recent answer");
    conversation.addUserMessage("last");
    var registry = ToolRegistry.createDefault();

    try (var executor =
            new ToolExecutor(
                registry,
                new ToolExecutionContext(
                    projectRoot, Duration.ofSeconds(2), new FileStateCache()));
        var contextManager = new ContextManager(projectRoot, client, 128_000)) {
      var coordinator =
          new AgentTurnCoordinator(
              client,
              registry,
              executor,
              conversation,
              ToolApiProtocol.OPENAI,
              new AgentLoopConfig(),
              new PromptRequestFactory(PromptBuilder.buildBundle(projectRoot)),
              contextManager);

      var events = new ArrayList<AgentEvent>();
      AgentRun run = coordinator.startRun("hello", AgentMode.EXECUTE);
      while (true) {
        AgentEvent event = run.events().next();
        events.add(event);
        if (event instanceof AgentEvent.LoopComplete) break;
      }

      assertEquals(3, client.requests.size());
      assertEquals(1, events.stream().filter(event -> event instanceof AgentEvent.Error).count());
      assertEquals(
          AgentEvent.ErrorCategory.CONTEXT,
          events.stream()
              .filter(AgentEvent.Error.class::isInstance)
              .map(AgentEvent.Error.class::cast)
              .findFirst()
              .orElseThrow()
              .category());
      assertEquals(
          0, events.stream().filter(event -> event instanceof AgentEvent.TurnComplete).count());
    }
  }

  @Test
  void manualCompactionRunsWithoutAddingAUserMessageOrAgentRound() throws Exception {
    var client = new CapturingClient(List.of(response(summary())));
    var conversation = new ConversationManager();
    conversation.addUserMessage("old goal");
    conversation.addAssistantMessage("O".repeat(40_000));
    conversation.addUserMessage("recent request");
    conversation.addAssistantMessage("R".repeat(40_000));
    conversation.addUserMessage("recent constraint");
    conversation.addAssistantMessage("recent answer");
    conversation.addUserMessage("last");
    var registry = ToolRegistry.createDefault();

    try (var executor =
            new ToolExecutor(
                registry,
                new ToolExecutionContext(
                    projectRoot, Duration.ofSeconds(2), new FileStateCache()));
        var contextManager = new ContextManager(projectRoot, client, 128_000)) {
      var coordinator =
          new AgentTurnCoordinator(
              client,
              registry,
              executor,
              conversation,
              ToolApiProtocol.OPENAI,
              new AgentLoopConfig(),
              new PromptRequestFactory(PromptBuilder.buildBundle(projectRoot)),
              contextManager);

      var events = new ArrayList<AgentEvent>();
      AgentRun run = coordinator.startManualCompaction(AgentMode.EXECUTE);
      while (true) {
        AgentEvent event = run.events().next();
        events.add(event);
        if (event instanceof AgentEvent.LoopComplete) break;
      }

      assertEquals(1, client.requests.size());
      assertTrue(client.requests.getFirst().tools().isEmpty());
      assertEquals(0, ((AgentEvent.LoopComplete) events.getLast()).totalRounds());
      assertTrue(
          events.stream()
              .anyMatch(
                  event ->
                      event instanceof AgentEvent.CompactionStarted started
                          && started.trigger() == ContextTrigger.MANUAL));
      assertTrue(events.stream().anyMatch(event -> event instanceof AgentEvent.CompactionComplete));
      assertEquals(
          1,
          conversation.getMessages().stream()
              .filter(message -> message.textContent().equals("last"))
              .count());
    }
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

  private static final class CapturingClient implements LlmClient {
    private final ArrayDeque<BlockingQueue<StreamEvent>> responses = new ArrayDeque<>();
    private final List<PromptRequest> requests = new ArrayList<>();

    private CapturingClient(List<BlockingQueue<StreamEvent>> responses) {
      this.responses.addAll(responses);
    }

    @Override
    public synchronized CancellableLlmStream openStream(PromptRequest request) {
      requests.add(request);
      return new CancellableLlmStream(responses.removeFirst(), () -> {});
    }
  }
}
