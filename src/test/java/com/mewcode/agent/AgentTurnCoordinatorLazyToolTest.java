package com.mewcode.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mewcode.conversation.ConversationManager;
import com.mewcode.llm.CancellableLlmStream;
import com.mewcode.llm.LlmClient;
import com.mewcode.llm.PromptRequest;
import com.mewcode.llm.StreamEvent;
import com.mewcode.prompt.PromptBuilder;
import com.mewcode.tool.FileStateCache;
import com.mewcode.tool.Tool;
import com.mewcode.tool.ToolApiProtocol;
import com.mewcode.tool.ToolCategory;
import com.mewcode.tool.ToolExecutionContext;
import com.mewcode.tool.ToolExecutor;
import com.mewcode.tool.ToolRegistry;
import com.mewcode.tool.ToolResult;
import com.mewcode.tool.impl.ToolSearchTool;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentTurnCoordinatorLazyToolTest {

  @TempDir Path projectRoot;

  @Test
  void searchesLocallyThenExposesToolInTheNextRound() throws Exception {
    var registry = new ToolRegistry();
    registry.register(new DeferredEchoTool());
    registry.register(new ToolSearchTool(registry));
    var client =
        new CapturingClient(
            List.of(
                response(
                    new StreamEvent.ToolCallComplete(
                        "search", "ToolSearch", Map.of("tool_name", "mcp_demo_echo"))),
                response(
                    new StreamEvent.ToolCallComplete(
                        "call", "mcp_demo_echo", Map.of("value", "ok"))),
                response(new StreamEvent.TextDelta("done"))));
    var conversation = new ConversationManager();

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

      awaitCompletion(coordinator.startRun("use the external tool", AgentMode.EXECUTE));
    }

    assertFalse(containsTool(client.requests.get(0), "mcp_demo_echo"));
    assertTrue(containsTool(client.requests.get(0), "ToolSearch"));
    assertTrue(
        client.requests.get(0).reminder().orElseThrow().textContent().contains("mcp_demo_echo"));
    assertTrue(containsTool(client.requests.get(1), "mcp_demo_echo"));
    assertFalse(containsTool(client.requests.get(1), "ToolSearch"));
    assertTrue(client.requests.get(2).history().size() > client.requests.get(1).history().size());
  }

  private static boolean containsTool(PromptRequest request, String name) {
    return request.tools().stream().anyMatch(tool -> name.equals(toolName(tool)));
  }

  private static String toolName(Map<String, Object> tool) {
    if (tool.containsKey("function")) {
      return String.valueOf(((Map<?, ?>) tool.get("function")).get("name"));
    }
    return String.valueOf(tool.get("name"));
  }

  private static void awaitCompletion(AgentRun run) throws Exception {
    while (!(run.events().next() instanceof AgentEvent.LoopComplete)) {}
  }

  private static BlockingQueue<StreamEvent> response(StreamEvent event) {
    return response(List.of(event));
  }

  private static BlockingQueue<StreamEvent> response(List<StreamEvent> events) {
    var queue = new LinkedBlockingQueue<StreamEvent>();
    queue.addAll(events);
    queue.add(
        new StreamEvent.StreamEnd(
            events.getFirst() instanceof StreamEvent.ToolCallComplete ? "tool_use" : "end_turn"));
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

  private static final class DeferredEchoTool implements Tool {
    @Override
    public String name() {
      return "mcp_demo_echo";
    }

    @Override
    public String description() {
      return "echo";
    }

    @Override
    public ToolCategory category() {
      return ToolCategory.MCP;
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
      return false;
    }

    @Override
    public boolean isDestructive() {
      return false;
    }

    @Override
    public boolean isConcurrencySafe(Map<String, Object> input) {
      return false;
    }

    @Override
    public String validateInput(Map<String, Object> input) {
      return null;
    }

    @Override
    public boolean shouldDefer() {
      return true;
    }
  }
}
