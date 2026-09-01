package com.mewcode.permission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mewcode.tool.ToolCall;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PermissionRuntimeTest {

  @Test
  void temporaryRulesLeadConfiguredRulesAndSnapshotsStayIsolated() {
    PermissionRuleEngine configured =
        new PermissionRuleEngine(
            List.of(PermissionRule.of("Bash(git *)", "deny", RuleSource.PROJECT)));
    PermissionRuntime runtime = new PermissionRuntime(PermissionMode.DEFAULT, configured);
    PermissionRuntime.Snapshot before = runtime.snapshot();

    runtime.addRule("Bash(git *)", "allow");
    PermissionRuntime.Snapshot after = runtime.snapshot();
    ToolCall call = new ToolCall("call", "Bash", Map.of("command", "git status"));

    assertEquals(
        RuleDecision.DENY, before.ruleEngine().match(call).orElseThrow().rule().decision());
    assertEquals(
        RuleDecision.ALLOW, after.ruleEngine().match(call).orElseThrow().rule().decision());
    assertEquals(RuleSource.SESSION, after.ruleEngine().match(call).orElseThrow().rule().source());
  }

  @Test
  void modeChangesRejectPlanAndResetRestoresStartupState() {
    PermissionRuntime runtime =
        new PermissionRuntime(PermissionMode.ACCEPT_EDITS, new PermissionRuleEngine());

    runtime.setMode("bypassPermissions");
    runtime.addRule("WriteFile(/tmp/*)", "deny");
    assertEquals(PermissionMode.BYPASS_PERMISSIONS, runtime.mode());
    assertThrows(IllegalArgumentException.class, () -> runtime.setMode("plan"));

    runtime.reset();

    assertEquals(PermissionMode.ACCEPT_EDITS, runtime.mode());
    assertTrue(runtime.temporaryRules().isEmpty());
  }

  @Test
  void snapshotsShareSessionGrantsWithTheConfiguredEngine() {
    PermissionRuleEngine configured = new PermissionRuleEngine();
    PermissionRuntime runtime = new PermissionRuntime(PermissionMode.DEFAULT, configured);
    configured.addSessionGrant("Bash(git status)");

    assertTrue(runtime.snapshot().ruleEngine().isSessionGranted("Bash(git status)"));
  }
}
