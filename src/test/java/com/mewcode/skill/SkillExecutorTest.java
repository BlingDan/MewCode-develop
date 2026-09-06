package com.mewcode.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.mewcode.agent.AgentLoopConfig;
import com.mewcode.agent.AgentMode;
import com.mewcode.agent.AgentRun;
import com.mewcode.conversation.Message;
import com.mewcode.conversation.TextBlock;
import com.mewcode.conversation.ToolResultBlock;
import com.mewcode.conversation.ToolUseBlock;
import com.mewcode.llm.StreamEvent;
import com.mewcode.testsupport.AgentTestRuntime;
import com.mewcode.testsupport.FakeLlmClient;
import com.mewcode.tool.ToolApiProtocol;
import com.mewcode.tool.ToolRegistry;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillExecutorTest {

  @TempDir Path temp;

  @Test
  void selectsNoneRecentAndFullWithoutSplittingToolTurns() {
    List<Message> history =
        List.of(
            new Message("user", "first"),
            new Message("assistant", "one"),
            new Message("user", "second"),
            new Message("assistant", List.of(new ToolUseBlock("id", "ReadFile", Map.of()))),
            new Message("user", List.of(new ToolResultBlock("id", "ok", false))),
            new Message("assistant", "two"),
            new Message("user", "third"),
            new Message("assistant", "three"));

    assertEquals(
        List.of(), SkillExecutor.selectHistory(history, SkillDefinition.ForkContext.NONE, 3));
    assertEquals(
        history, SkillExecutor.selectHistory(history, SkillDefinition.ForkContext.FULL, 3));
    assertEquals(
        history.subList(2, history.size()),
        SkillExecutor.selectHistory(history, SkillDefinition.ForkContext.RECENT, 2));
    assertEquals(
        new TextBlock("second"),
        SkillExecutor.selectHistory(history, SkillDefinition.ForkContext.RECENT, 2)
            .getFirst()
            .content()
            .getFirst());
  }

  @Test
  void runsTemporaryAgentAndReturnsOnlyItsFinalAssistantText() {
    var client = new FakeLlmClient();
    client.enqueue(
        new StreamEvent.TextDelta("fork summary"), new StreamEvent.StreamEnd("end_turn"));
    var registry = new ToolRegistry();
    var temporary = new com.mewcode.conversation.ConversationManager();
    temporary.loadMessages(
        List.of(new Message("user", "earlier"), new Message("assistant", "context")));
    SkillDefinition skill = forkSkill();
    SkillRun run = new SkillRun();
    run.activate(skill, "focus");

    try (var runtime =
        AgentTestRuntime.create(
            temp,
            client,
            registry,
            temporary,
            ToolApiProtocol.OPENAI,
            new AgentLoopConfig(),
            128_000)) {
      var result =
          SkillExecutor.runFork(
              new SkillExecutor.ForkRequest(
                  skill, "focus", temporary.getMessages(), AgentMode.EXECUTE, new AgentRun()),
              runtime.coordinator(),
              temporary,
              run);

      assertEquals("fork summary", result.content());
      assertEquals(4, temporary.getMessages().size());
    }
  }

  private static SkillDefinition forkSkill() {
    Path entry = Path.of("/tmp/review.md");
    return new SkillDefinition(
        new SkillDefinition.Meta(
            "review",
            "review",
            List.of(),
            SkillDefinition.Mode.FORK,
            SkillDefinition.ForkContext.FULL,
            3,
            null),
        "Review {{arguments}}",
        SkillDefinition.Source.BUILTIN,
        entry,
        entry.getParent(),
        List.of());
  }
}
