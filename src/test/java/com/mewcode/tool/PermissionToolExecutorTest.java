package com.mewcode.tool;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mewcode.agent.CancellationToken;
import com.mewcode.permission.BashSandbox;
import com.mewcode.permission.BashSandboxRequest;
import com.mewcode.permission.PathAuthorizationStore;
import com.mewcode.permission.PermissionBroker;
import com.mewcode.permission.PermissionContext;
import com.mewcode.permission.PermissionGate;
import com.mewcode.permission.PermissionMode;
import com.mewcode.permission.PermissionResponse;
import com.mewcode.permission.PermissionRuleEngine;
import com.mewcode.permission.SandboxedProcess;
import com.mewcode.tool.impl.BashTool;
import com.mewcode.tool.impl.ReadFileTool;
import com.mewcode.tool.impl.WriteFileTool;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PermissionToolExecutorTest {
  @TempDir Path projectRoot;

  @Test
  void asksBeforeWritingAndExecutesAfterOneTimeApproval() {
    var registry = new ToolRegistry();
    registry.register(new WriteFileTool());
    var context = permissionContext(PermissionMode.DEFAULT, new PermissionBroker());
    context
        .permissionBroker()
        .setPublisher(
            request ->
                context
                    .permissionBroker()
                    .resolve(request.requestId(), PermissionResponse.ALLOW_ONCE));
    Path target = projectRoot.resolve("created.txt");

    try (var executor =
        new ToolExecutor(
            registry,
            new ToolExecutionContext(projectRoot, Duration.ofSeconds(2), new FileStateCache()),
            new PermissionGate())) {
      ToolInvocationResult result =
          executor.executeSingle(
              new ToolCall(
                  "call-1", "WriteFile", Map.of("path", target.toString(), "content", "ok")),
              context);
      assertFalse(result.result().isError(), result.result().content());
      assertTrue(Files.exists(target));
    }
  }

  @Test
  void hardDeniesDangerousBashWithoutPublishingAConfirmation() {
    var registry = new ToolRegistry();
    registry.register(new BashTool());
    var broker = new PermissionBroker();
    broker.setPublisher(
        request -> {
          throw new AssertionError("hard deny must not ask the user");
        });
    var context = permissionContext(PermissionMode.BYPASS_PERMISSIONS, broker);

    try (var executor =
        new ToolExecutor(
            registry,
            new ToolExecutionContext(projectRoot, Duration.ofSeconds(2), new FileStateCache()),
            new PermissionGate())) {
      ToolInvocationResult result =
          executor.executeSingle(
              new ToolCall("call-2", "Bash", Map.of("command", "rm -rf /")), context);
      assertTrue(result.result().isError());
      assertTrue(result.result().content().contains("不可逆的系统损坏"));
    }
  }

  @Test
  void asksForAnOutsideReadEvenInBypassModeAndUsesTheApprovedPath() throws Exception {
    var registry = new ToolRegistry();
    registry.register(new ReadFileTool());
    Path outside = projectRoot.resolveSibling("mewcode-executor-outside.txt");
    Files.writeString(outside, "outside");
    var broker = new PermissionBroker();
    broker.setPublisher(
        request -> broker.resolve(request.requestId(), PermissionResponse.ALLOW_ONCE));
    var context = permissionContext(PermissionMode.BYPASS_PERMISSIONS, broker);

    try (var executor =
        new ToolExecutor(
            registry,
            new ToolExecutionContext(projectRoot, Duration.ofSeconds(2), new FileStateCache()),
            new PermissionGate())) {
      ToolInvocationResult result =
          executor.executeSingle(
              new ToolCall("call-3", "ReadFile", Map.of("path", outside.toString())), context);
      assertFalse(result.result().isError(), result.result().content());
      assertTrue(result.result().content().contains("outside"));
    }
  }

  private PermissionContext permissionContext(PermissionMode mode, PermissionBroker broker) {
    return new PermissionContext(
        projectRoot,
        mode,
        new PermissionRuleEngine(),
        new PathAuthorizationStore(projectRoot),
        new AvailableSandbox(),
        broker,
        new CancellationToken());
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
}
