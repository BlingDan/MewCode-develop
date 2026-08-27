package com.mewcode.permission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.mewcode.tool.ToolCall;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PermissionRuleEngineTest {
  @Test
  void matchesExactAndGlobTargets() {
    var engine =
        new PermissionRuleEngine(
            List.of(
                PermissionRule.of("Bash(git status)", "deny", RuleSource.PROJECT),
                PermissionRule.of("Bash(git *)", "allow", RuleSource.USER)));

    assertEquals(
        RuleDecision.DENY,
        engine
            .match(new ToolCall("call-1", "Bash", Map.of("command", "git status")))
            .orElseThrow()
            .rule()
            .decision());
    assertEquals(
        RuleDecision.ALLOW,
        engine
            .match(new ToolCall("call-2", "Bash", Map.of("command", "git diff")))
            .orElseThrow()
            .rule()
            .decision());
  }

  @Test
  void preservesTheMoreLocalOrderProvidedByTheLoader() {
    var engine =
        new PermissionRuleEngine(
            List.of(
                PermissionRule.of("Bash(git *)", "deny", RuleSource.LOCAL),
                PermissionRule.of("Bash(git *)", "allow", RuleSource.PROJECT),
                PermissionRule.of("Bash(git *)", "allow", RuleSource.USER)));

    assertEquals(
        RuleSource.LOCAL,
        engine
            .match(new ToolCall("call-3", "Bash", Map.of("command", "git status")))
            .orElseThrow()
            .rule()
            .source());
  }

  @Test
  void normalizesFilePathsAndIncludesGrepSearchScope() {
    var engine =
        new PermissionRuleEngine(
            List.of(
                PermissionRule.of("ReadFile(/project/src/App.java)", "allow", RuleSource.PROJECT),
                PermissionRule.of("Grep(TODO @ /project/src)", "deny", RuleSource.PROJECT)));

    assertEquals(
        RuleDecision.ALLOW,
        engine
            .match(
                new ToolCall("call-4", "ReadFile", Map.of("path", "/project/src/../src/App.java")),
                java.nio.file.Path.of("/project"))
            .orElseThrow()
            .rule()
            .decision());
    assertEquals(
        RuleDecision.DENY,
        engine
            .match(
                new ToolCall(
                    "call-5", "Grep", Map.of("pattern", "TODO", "path", "/project/src/../src")),
                java.nio.file.Path.of("/project"))
            .orElseThrow()
            .rule()
            .decision());
  }

  @Test
  void rejectsMalformedRulesAndUnknownDecisions() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new PermissionRule("Bash(git *)", null, RuleSource.USER));
    assertThrows(
        IllegalArgumentException.class, () -> PermissionRule.of("Bash", "allow", RuleSource.USER));
    assertThrows(
        IllegalArgumentException.class,
        () -> PermissionRule.of("Bash(git *)", "ask", RuleSource.USER));
  }
}
