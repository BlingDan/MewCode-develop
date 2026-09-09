package com.mewcode.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mewcode.config.HookConfigLoader;
import com.mewcode.hook.HookAction;
import com.mewcode.hook.HookEngine;
import com.mewcode.hook.HookEvent;
import com.mewcode.hook.HookRule;
import com.mewcode.hook.HookSessionState;
import com.mewcode.permission.BashSandbox;
import com.mewcode.permission.BashSandboxRequest;
import com.mewcode.permission.SandboxedProcess;
import com.mewcode.prompt.PromptBuilder;
import com.mewcode.testsupport.FakeLlmClient;
import com.mewcode.tool.FileStateCache;
import com.mewcode.tool.ToolApiProtocol;
import com.mewcode.tool.ToolExecutionContext;
import com.mewcode.tool.ToolExecutor;
import com.mewcode.tool.ToolRegistry;
import com.mewcode.tool.support.CommandRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentTurnCoordinatorHookTest {
  @TempDir Path projectRoot;

  @Test
  void sendsLifecycleEventsAndConsumesHookReminderOnTheNextRequest() throws Exception {
    var client = new FakeLlmClient();
    client.enqueue(
        new com.mewcode.llm.StreamEvent.TextDelta("done"),
        new com.mewcode.llm.StreamEvent.StreamEnd("end_turn"));
    var state = new HookSessionState();
    var rules =
        List.of(
            rule("turn", HookEvent.TURN_START, new HookAction.Prompt("TURN_HOOK")),
            rule("message", HookEvent.MESSAGE_START, new HookAction.Prompt("MESSAGE_HOOK")),
            rule(
                "message-end",
                HookEvent.MESSAGE_END,
                new HookAction.Shell("printf 'message-end\\n' >> hook-events")),
            rule("stop", HookEvent.STOP, new HookAction.Shell("printf 'stop\\n' >> hook-events")));
    try (var hooks =
            new HookEngine(
                new HookConfigLoader.LoadedHooks(rules, List.of()),
                new CommandRunner(new ShellSandbox()),
                ignored -> {});
        var executor =
            new ToolExecutor(
                ToolRegistry.createDefault(),
                new ToolExecutionContext(
                    projectRoot, Duration.ofSeconds(2), new FileStateCache()))) {
      var coordinator =
          new AgentTurnCoordinator(
              client,
              ToolRegistry.createDefault(),
              executor,
              new com.mewcode.conversation.ConversationManager(),
              ToolApiProtocol.OPENAI,
              new AgentLoopConfig(),
              new PromptRequestFactory(PromptBuilder.buildBundle(projectRoot)));
      coordinator.configureHooks(hooks, state);

      await(coordinator.startRun("hello", AgentMode.EXECUTE));

      String reminder = client.requests().getFirst().reminder().orElseThrow().textContent();
      assertTrue(reminder.contains("TURN_HOOK"), reminder);
      assertTrue(reminder.contains("MESSAGE_HOOK"), reminder);
      assertTrue(reminder.indexOf("TURN_HOOK") < reminder.indexOf("MESSAGE_HOOK"));
      assertTrue(state.snapshotPrompts().texts().isEmpty());
      assertEquals("message-end\nstop\n", Files.readString(projectRoot.resolve("hook-events")));
      assertFalse(
          client.requests().getFirst().history().stream()
              .anyMatch(message -> message.textContent().contains("TURN_HOOK")));
    }
  }

  private HookRule rule(String name, HookEvent event, HookAction action) {
    return new HookRule(
        name, event, Optional.empty(), action, false, false, Duration.ofSeconds(2), projectRoot);
  }

  private static List<AgentEvent> await(AgentRun run) throws Exception {
    var events = new java.util.ArrayList<AgentEvent>();
    while (true) {
      AgentEvent event = run.events().poll(3, TimeUnit.SECONDS);
      if (event == null) throw new AssertionError("agent timeout");
      events.add(event);
      if (event instanceof AgentEvent.LoopComplete) return events;
    }
  }

  private static final class ShellSandbox implements BashSandbox {
    @Override
    public boolean isAvailable() {
      return true;
    }

    @Override
    public SandboxedProcess prepare(BashSandboxRequest request) {
      return new SandboxedProcess(
          List.of("/bin/sh", "-c", request.command()), request.projectRoot());
    }
  }
}
