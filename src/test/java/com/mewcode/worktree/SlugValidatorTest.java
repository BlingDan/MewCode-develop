package com.mewcode.worktree;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class SlugValidatorTest {
  @Test
  void acceptsSafeNestedNamesAndLengthBoundaries() {
    assertEquals("one+two", assertDoesNotThrow(() -> validate("one/two")));
    assertEquals("a", assertDoesNotThrow(() -> validate("a")));
    assertEquals("a".repeat(64), assertDoesNotThrow(() -> validate("a".repeat(64))));
  }

  @Test
  void rejectsTraversalAndInvalidGitNames() {
    for (String input :
        new String[] {
          "",
          ".",
          "..",
          "a/../b",
          "a//b",
          "/a",
          "a/",
          ".hidden",
          "a..b",
          "a.lock",
          "a+b",
          "a b",
          "a\\b",
          "x;touch",
          "a".repeat(65)
        }) {
      assertThrows(IllegalArgumentException.class, () -> validate(input), input);
    }
  }

  @Test
  void rejectsLinkedManagementParentsAndTargets() throws Exception {
    Path root = Files.createTempDirectory("mew-slug-").toRealPath();
    try {
      Path outside = Files.createDirectory(root.resolve("outside"));
      Files.createSymbolicLink(root.resolve(".mewcode"), outside);
      assertThrows(
          java.io.IOException.class, () -> SlugValidator.safePath(root, ".mewcode/worktrees/new"));
      assertFalse(Files.exists(outside.resolve("worktrees")));
    } finally {
      Files.deleteIfExists(root.resolve(".mewcode"));
      Files.deleteIfExists(root.resolve("outside"));
      Files.deleteIfExists(root);
    }
  }

  private String validate(String input) {
    return SlugValidator.validate(input);
  }
}
