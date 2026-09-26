package com.mewcode.worktree;

import static org.junit.jupiter.api.Assertions.*;

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

  private String validate(String input) {
    return SlugValidator.validate(input);
  }
}
