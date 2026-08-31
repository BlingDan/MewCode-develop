package com.mewcode.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mewcode.compact.ContextManager;
import com.mewcode.compact.ContextTrigger;
import com.mewcode.conversation.ConversationManager;
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
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
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
