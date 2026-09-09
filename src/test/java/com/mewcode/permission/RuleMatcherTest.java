package com.mewcode.permission;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class RuleMatcherTest {
  @Test
  void exactMatchesTheWholeCaseSensitiveValue() {
    var matcher = new RuleMatcher.Exact("git status");

    assertTrue(matcher.matches("git status"));
    assertFalse(matcher.matches("Git status"));
    assertFalse(matcher.matches("before git status"));
  }

  @Test
  void globPreservesWildcardAndDotAllSemantics() {
    var matcher = RuleMatcher.glob("=git ?status*");

    assertTrue(matcher.matches("=git xstatus\n--short"));
    assertTrue(matcher.matches("=git xstatus"));
    assertFalse(matcher.matches("!git status"));
  }

  @Test
  void regexUsesSearchUntilTheExpressionAddsAnchors() {
    var matcher = RuleMatcher.parse(Map.of("type", "regex", "value", "git\\s+status"));

    assertTrue(matcher.matches("run git status now"));
    assertFalse(
        RuleMatcher.parse(Map.of("type", "regex", "value", "^git status$"))
            .matches("run git status now"));
  }

  @Test
  void notWrapsAnotherMatcherAndCanBeNested() {
    var matcher =
        RuleMatcher.parse(
            Map.of(
                "type",
                "not",
                "inner",
                Map.of("type", "not", "inner", Map.of("type", "exact", "value", "blocked"))));

    assertTrue(matcher.matches("blocked"));
    assertFalse(matcher.matches("other"));
    assertFalse(new RuleMatcher.Not(new RuleMatcher.Exact("x")).matches(null));
  }

  @Test
  void rejectsMalformedStructuredDefinitions() {
    assertThrows(IllegalArgumentException.class, () -> RuleMatcher.parse(Map.of("type", "wat")));
    assertThrows(
        IllegalArgumentException.class,
        () -> RuleMatcher.parse(Map.of("type", "regex", "value", "[")));
    assertThrows(
        IllegalArgumentException.class,
        () -> RuleMatcher.parse(Map.of("type", "not", "inner", "exact")));
    assertThrows(
        IllegalArgumentException.class,
        () -> RuleMatcher.parse(Map.of("type", "exact", "value", 1)));
  }
}
