package com.mewcode.permission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mewcode.tool.Tool;
import com.mewcode.tool.ToolCall;
import com.mewcode.tool.ToolCategory;
import com.mewcode.tool.ToolExecutionContext;
import com.mewcode.tool.ToolResult;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PermissionGateTest {
  @TempDir Path projectRoot;

  @Test
  void appliesDefaultModeAndBypassOnlyToOrdinaryOperations() {
    Tool write = new StubTool("WriteFile", ToolCategory.FILE, false, false);
    PermissionGate gate = new PermissionGate();
    ToolCall call =
        new ToolCall(
            "call-1", "WriteFile", Map.of("path", projectRoot.resolve("a.txt").toString()));

    assertEquals(
        PermissionDecision.ASK,
        gate.check(call, write, context(PermissionMode.DEFAULT)).decision());
    assertEquals(
        PermissionDecision.ALLOW,
        gate.check(call, write, context(PermissionMode.BYPASS_PERMISSIONS)).decision());
  }

  @Test
  void pathBoundaryAskSurvivesBypassMode() {
    Tool read = new StubTool("ReadFile", ToolCategory.FILE, true, false);
    Path outside = projectRoot.resolveSibling("mewcode-permission-gate-outside.txt");
    ToolCall call = new ToolCall("call-2", "ReadFile", Map.of("path", outside.toString()));

    PermissionCheck check =
        new PermissionGate().check(call, read, context(PermissionMode.BYPASS_PERMISSIONS));
    assertEquals(PermissionDecision.ASK, check.decision());
    assertTrue(check.pathOutside());
  }

  @Test
  void dangerousCommandIsDeniedBeforeModeAndRules() {
    Tool bash = new StubTool("Bash", ToolCategory.SHELL, false, true);
    var rules =
        new PermissionRuleEngine(List.of(PermissionRule.of("Bash(*)", "allow", RuleSource.LOCAL)));
    PermissionContext context = context(PermissionMode.BYPASS_PERMISSIONS, rules);
    PermissionCheck check =
        new PermissionGate()
            .check(new ToolCall("call-3", "Bash", Map.of("command", "rm -rf /")), bash, context);

    assertEquals(PermissionDecision.DENY, check.decision());
    assertEquals(PermissionReason.DANGEROUS_COMMAND, check.reason());
    assertTrue(check.message().contains("不可逆的系统损坏"));
  }

  @Test
  void explicitDenyWinsOverBypassAndLocalRuleWinsByOrder() {
    Tool bash = new StubTool("Bash", ToolCategory.SHELL, false, true);
    var rules =
        new PermissionRuleEngine(
            List.of(
                PermissionRule.of("Bash(git *)", "deny", RuleSource.LOCAL),
                PermissionRule.of("Bash(git *)", "allow", RuleSource.PROJECT)));
    PermissionCheck check =
        new PermissionGate()
            .check(
                new ToolCall("call-4", "Bash", Map.of("command", "git status")),
                bash,
                context(PermissionMode.BYPASS_PERMISSIONS, rules));

    assertEquals(PermissionDecision.DENY, check.decision());
    assertEquals(PermissionReason.RULE_DENY, check.reason());
  }

  private PermissionContext context(PermissionMode mode) {
    return context(mode, new PermissionRuleEngine());
  }

  private PermissionContext context(PermissionMode mode, PermissionRuleEngine rules) {
    return new PermissionContext(
        projectRoot,
        mode,
        rules,
        new PathAuthorizationStore(projectRoot),
        new AvailableSandbox(),
        new PermissionBroker(),
        new com.mewcode.agent.CancellationToken());
  }

  private static final class AvailableSandbox implements BashSandbox {
    @Override
    public boolean isAvailable() {
      return true;
    }

    @Override
    public SandboxedProcess prepare(BashSandboxRequest request) {
      return new SandboxedProcess(List.of("fake", request.command()), request.projectRoot());
    }
  }

  private record StubTool(String name, ToolCategory category, boolean readOnly, boolean destructive)
      implements Tool {
    @Override
    public String description() {
      return name;
    }

    @Override
    public Map<String, Object> inputSchema() {
      return Map.of("type", "object");
    }

    @Override
    public ToolResult execute(ToolExecutionContext context, Map<String, Object> input) {
      return ToolResult.success("ok");
    }

    @Override
    public boolean isReadOnly() {
      return readOnly;
    }

    @Override
    public boolean isDestructive() {
      return destructive;
    }

    @Override
    public boolean isConcurrencySafe(Map<String, Object> input) {
      return readOnly;
    }

    @Override
    public String validateInput(Map<String, Object> input) {
      return null;
    }
  }
}
