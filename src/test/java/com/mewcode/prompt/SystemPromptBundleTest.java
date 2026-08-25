package com.mewcode.prompt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SystemPromptBundleTest {

  @Test
  void sortsModulesSkipsBlankContentAndKeepsEnvironmentSeparate() {
    var bundle =
        new SystemPromptBundle(
            List.of(
                new PromptModule("late", 20, "late"),
                new PromptModule("empty", 15, "  "),
                new PromptModule("early", 10, "early")),
            new EnvironmentContext(Path.of("project"), Map.of("os", "test")));

    assertIterableEquals(
        List.of(
            "early\n\nlate",
            "The current project root is: "
                + Path.of("project").toAbsolutePath().normalize()
                + "\nos: test"),
        bundle.systemSegments());
    assertEquals(String.join("\n\n", bundle.systemSegments()), bundle.flattenedText());
  }

  @Test
  void rendersAttributeOrderDeterministically() {
    var first = new EnvironmentContext(Path.of("."), Map.of("z", "last", "a", "first"));
    var second = new EnvironmentContext(Path.of("."), Map.of("a", "first", "z", "last"));

    assertEquals(first.render(), second.render());
  }
}
