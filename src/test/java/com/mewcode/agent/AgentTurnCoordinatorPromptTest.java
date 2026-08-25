package com.mewcode.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

  private static BlockingQueue<StreamEvent> response(String text) {
    var queue = new LinkedBlockingQueue<StreamEvent>();
    queue.add(new StreamEvent.TextDelta(text));
    queue.add(new StreamEvent.StreamEnd("end_turn"));
    return queue;
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
