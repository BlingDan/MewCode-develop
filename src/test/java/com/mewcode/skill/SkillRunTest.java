package com.mewcode.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class SkillRunTest {

  @Test
  void rendersArgumentsAndCombinesActiveSkillsForOneRequest() {
    SkillRun run = new SkillRun();
    run.activate(
        skill("first", "Inspect {{arguments}} and {{arguments}}", List.of("ReadFile"), null),
        "a b");
    run.activate(skill("second", "Run checks", List.of("ReadFile", "Bash"), "backup"), "now");

    assertEquals(
        List.of("first", "second"),
        run.activeSkills().stream().map(a -> a.definition().meta().name()).toList());
    assertTrue(run.promptBlock().contains("Inspect a b and a b"));
    assertTrue(run.promptBlock().contains("## 用户输入\n\nnow"));
    assertEquals(java.util.Set.of("ReadFile", "Bash"), run.allowedTools());
    assertEquals("backup", run.preferredProvider().orElseThrow());

    run.clear();
    assertTrue(run.activeSkills().isEmpty());
    assertTrue(run.allowedTools().isEmpty());
    assertTrue(run.preferredProvider().isEmpty());
  }

  private static SkillDefinition skill(String name, String body, List<String> tools, String model) {
    Path entry = Path.of("/tmp", name + ".md");
    return new SkillDefinition(
        new SkillDefinition.Meta(
            name,
            name + " description",
            tools,
            SkillDefinition.Mode.SHARED,
            SkillDefinition.ForkContext.NONE,
            3,
            model),
        body,
        SkillDefinition.Source.PROJECT,
        entry,
        entry.getParent(),
        List.of());
  }
}
