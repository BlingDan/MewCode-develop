package com.mewcode.agent;

import static org.junit.jupiter.api.Assertions.*;

import com.mewcode.conversation.ConversationManager;
import com.mewcode.conversation.ToolResultBlock;
import com.mewcode.conversation.ToolUseBlock;
import com.mewcode.llm.CancellableLlmStream;
import com.mewcode.llm.LlmClient;
import com.mewcode.llm.PromptRequest;
import com.mewcode.llm.StreamEvent;
import com.mewcode.testsupport.AgentTestRuntime;
import com.mewcode.tool.Tool;
import com.mewcode.tool.ToolApiProtocol;
import com.mewcode.tool.ToolCategory;
import com.mewcode.tool.ToolExecutionContext;
import com.mewcode.tool.ToolRegistry;
import com.mewcode.tool.ToolResult;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentTurnCoordinatorTest {

  @TempDir Path tempDir;

  @Test
  void sendsAssistantToolUsesAndAllToolResultsInSinglePairedMessages() throws Exception {
    var first =
        queue(
            new StreamEvent.TextDelta("I will inspect both.\n"),
            new StreamEvent.ToolCallComplete("call-read", "Echo", Map.of("value", "one")),
            new StreamEvent.ToolCallComplete("call-search", "Echo", Map.of("value", "two")),
            new StreamEvent.StreamEnd("tool_use"));
    var second = queue(new StreamEvent.TextDelta("Done."), new StreamEvent.StreamEnd("end_turn"));
    var client = new QueueClient(List.of(first, second));
    var registry = new ToolRegistry();
    registry.register(new EchoTool());
    var conversation = new ConversationManager();

    try (var runtime =
        AgentTestRuntime.create(tempDir, client, registry, conversation, ToolApiProtocol.OPENAI)) {
      List<AgentEvent> seen =
          awaitCompletion(runtime.coordinator().startRun("inspect", AgentMode.EXECUTE));

      assertTrue(
          seen.stream()
              .anyMatch(
                  event ->
                      event instanceof AgentEvent.ToolResult
                          && ((AgentEvent.ToolResult) event).requestId().equals("call-read")));
      assertTrue(
          seen.stream()
              .anyMatch(
                  event ->
                      event instanceof AgentEvent.ToolResult
                          && ((AgentEvent.ToolResult) event).requestId().equals("call-search")));
      var started =
          seen.stream()
              .filter(event -> event instanceof AgentEvent.ToolUse)
              .map(event -> (AgentEvent.ToolUse) event)
              .toList();
      assertEquals(Map.of("value", "one"), started.get(0).input());
      assertEquals(Map.of("value", "two"), started.get(1).input());
      assertEquals(2, client.calls.size());
      assertFalse(client.toolRequests.get(0).isEmpty());
      assertFalse(client.toolRequests.get(1).isEmpty());
      assertEquals(1, client.calls.get(0).getMessages().size());
      assertEquals(3, client.calls.get(1).getMessages().size());

      var assistant = client.calls.get(1).getMessages().get(1);
      assertEquals("assistant", assistant.role());
      assertEquals(3, assistant.content().size());
      assertInstanceOf(ToolUseBlock.class, assistant.content().get(1));
      assertInstanceOf(ToolUseBlock.class, assistant.content().get(2));

      var results = client.calls.get(1).getMessages().get(2);
      assertEquals("user", results.role());
      assertEquals(2, results.content().size());
      assertInstanceOf(ToolResultBlock.class, results.content().get(0));
      assertInstanceOf(ToolResultBlock.class, results.content().get(1));
    }
  }

  @Test
  void keepsLoopingWhenTheNextResponseRequestsAnotherTool() throws Exception {
    var first =
        queue(
            new StreamEvent.ToolCallComplete("call-1", "Echo", Map.of("value", "one")),
            new StreamEvent.StreamEnd("tool_use"));
    var second =
        queue(
            new StreamEvent.ToolCallComplete("call-2", "Echo", Map.of("value", "two")),
            new StreamEvent.StreamEnd("tool_use"));
    var third =
        queue(new StreamEvent.TextDelta("Finished."), new StreamEvent.StreamEnd("end_turn"));
    var client = new QueueClient(List.of(first, second, third));
    var registry = new ToolRegistry();
    registry.register(new EchoTool());
    var conversation = new ConversationManager();

    try (var runtime =
        AgentTestRuntime.create(tempDir, client, registry, conversation, ToolApiProtocol.OPENAI)) {
      List<AgentEvent> events =
          awaitCompletion(runtime.coordinator().startRun("one round only", AgentMode.EXECUTE));

      assertEquals(3, client.calls.size());
      assertFalse(client.toolRequests.get(1).isEmpty());
      assertEquals(
          1, events.stream().filter(event -> event instanceof AgentEvent.LoopComplete).count());
      assertTrue(
          events.stream()
              .anyMatch(
                  event ->
                      event instanceof AgentEvent.StreamText text
                          && text.text().equals("Finished.")));
    }
  }

  @Test
  void parseFailureStillEmitsToolUseWithEmptyArguments() throws Exception {
    var first =
        queue(
            new StreamEvent.ToolCallParseError("call-invalid", "Echo", "invalid JSON"),
            new StreamEvent.StreamEnd("tool_use"));
    var second =
        queue(new StreamEvent.TextDelta("Recovered."), new StreamEvent.StreamEnd("end_turn"));
    var client = new QueueClient(List.of(first, second));
    var registry = new ToolRegistry();
    registry.register(new EchoTool());
    var conversation = new ConversationManager();

    try (var runtime =
        AgentTestRuntime.create(tempDir, client, registry, conversation, ToolApiProtocol.OPENAI)) {
      List<AgentEvent> events =
          awaitCompletion(runtime.coordinator().startRun("invalid", AgentMode.EXECUTE));

      var started =
          events.stream()
              .filter(event -> event instanceof AgentEvent.ToolUse)
              .map(event -> (AgentEvent.ToolUse) event)
              .findFirst()
              .orElseThrow();
      assertEquals("call-invalid", started.requestId());
      assertEquals("Echo", started.toolName());
      assertTrue(started.input().isEmpty());
    }
  }

  private static List<AgentEvent> awaitCompletion(AgentRun run) throws Exception {
    var result = new ArrayList<AgentEvent>();
    while (true) {
      AgentEvent event = run.events().next();
      assertNotNull(event, "agent turn timed out");
      result.add(event);
      if (event instanceof AgentEvent.LoopComplete) return result;
    }
  }

  private static BlockingQueue<StreamEvent> queue(StreamEvent... events) {
    var queue = new LinkedBlockingQueue<StreamEvent>();
    queue.addAll(List.of(events));
    return queue;
  }

  private static final class QueueClient implements LlmClient {
    private final List<BlockingQueue<StreamEvent>> responses;
    private final List<ConversationManager> calls = new ArrayList<>();
    private final List<List<Map<String, Object>>> toolRequests = new ArrayList<>();

    private QueueClient(List<BlockingQueue<StreamEvent>> responses) {
      this.responses = new ArrayList<>(responses);
    }

    @Override
    public synchronized CancellableLlmStream openStream(PromptRequest request) {
      var snapshot = new ConversationManager();
      snapshot.loadMessages(request.history());
      calls.add(snapshot);
      toolRequests.add(request.tools());
      return new CancellableLlmStream(responses.removeFirst(), () -> {});
    }
  }

  private static final class EchoTool implements Tool {
    @Override
    public String name() {
      return "Echo";
    }

    @Override
    public String description() {
      return "test echo";
    }

    @Override
    public ToolCategory category() {
      return ToolCategory.SEARCH;
    }

    @Override
    public Map<String, Object> inputSchema() {
      return Map.of("type", "object");
    }

    @Override
    public ToolResult execute(ToolExecutionContext context, Map<String, Object> input) {
      return ToolResult.success("echo:" + input.get("value"));
    }

    @Override
    public boolean isReadOnly() {
      return true;
    }

    @Override
    public boolean isDestructive() {
      return false;
    }

    @Override
    public boolean isConcurrencySafe(Map<String, Object> input) {
      return true;
    }

    @Override
    public String validateInput(ToolExecutionContext context, Map<String, Object> input) {
      return null;
    }
  }
}
