package com.mewcode.prompt;

import static org.junit.jupiter.api.Assertions.*;

import com.mewcode.agent.AgentMode;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PromptBuilderTest {

  @TempDir Path projectRoot;

  @Test
  void describesTheActualRootAndHowToResolveRelativeUserPaths() {
    Path root = projectRoot.toAbsolutePath().normalize();

    String prompt = PromptBuilder.buildSystemPrompt(root);

    assertTrue(prompt.contains("The current project root is: " + root));
    assertTrue(prompt.contains("Resolve user-provided relative paths against that project root"));
    assertTrue(
        prompt.contains(
            "File paths and glob patterns passed to file and search tools must be absolute"));
    assertTrue(prompt.contains(root + "/.trae/skills/mew-spec/SKILL.md"));
    assertFalse(prompt.contains("/Users/mew/.trae"));
  }

  @Test
  void planModeExplainsReadOnlyPlanningAndLoopPromptHasNoOneRoundLimit() {
    String planPrompt = PromptBuilder.buildSystemPrompt(projectRoot, AgentMode.PLAN);
    String executePrompt = PromptBuilder.buildSystemPrompt(projectRoot, AgentMode.EXECUTE);

    assertTrue(planPrompt.contains("planning mode"));
    assertTrue(planPrompt.contains("read-only"));
    assertFalse(planPrompt.contains("WriteFile"));
    assertFalse(executePrompt.contains("one tool result round"));
    assertTrue(executePrompt.contains("Continue"));
  }
}
