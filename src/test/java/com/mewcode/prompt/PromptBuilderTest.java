package com.mewcode.prompt;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PromptBuilderTest {

  @TempDir Path projectRoot;

  @Test
  void describesTheActualRootAndHowToResolveRelativeUserPaths() {
    Path root = projectRoot.toAbsolutePath().normalize();

    String prompt = String.join("\n\n", PromptBuilder.buildBundle(root).systemSegments());

    assertTrue(prompt.contains("The current project root is: " + root));
    assertTrue(prompt.contains("Resolve user-provided relative paths against that project root"));
    assertTrue(
        prompt.contains(
            "File paths and glob patterns passed to file and search tools must be absolute"));
    assertTrue(prompt.contains(root + "/.trae/skills/mew-spec/SKILL.md"));
    assertFalse(prompt.contains("/Users/mew/.trae"));
  }

  @Test
  void stablePromptDefersModeSpecificInstructionsToReminders() {
    String prompt = String.join("\n\n", PromptBuilder.buildBundle(projectRoot).systemSegments());

    assertTrue(prompt.contains("Runtime mode reminders"));
    assertFalse(prompt.contains("one tool result round"));
    assertTrue(prompt.contains("Continue"));
  }

  @Test
  void exposesSevenFixedModulesAndTheOptionalInstructionsSlot() {
    assertEquals(
        List.of(
            "identity",
            "system-constraints",
            "task-mode",
            "action-execution",
            "tool-usage",
            "tone",
            "text-output",
            "custom-instructions"),
        PromptBuilder.modules().stream().map(PromptModule::name).toList());
    assertEquals(7, PromptBuilder.fixedModules().size());
    assertTrue(
        PromptBuilder.optionalModules().stream().allMatch(module -> module.content().isEmpty()));
  }
}
