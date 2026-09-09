package com.mewcode.hook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mewcode.agent.CancellationToken;
import com.mewcode.permission.BashSandbox;
import com.mewcode.permission.BashSandboxRequest;
import com.mewcode.permission.SandboxedProcess;
import com.mewcode.tool.support.CommandRunner;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HookActionExecutorTest {
  @TempDir Path projectRoot;

  @Test
  void hookShellReceivesJsonOnStdinAndKeepsStreamsSeparate() throws Exception {
    var runner = new CommandRunner(new ShellSandbox());
    var result =
        runner.runHook(
            "read value; printf '%s' \"$value\"; printf '%s' \"$PWD\" >&2",
            projectRoot, "special ' value\n", Duration.ofSeconds(2), new CancellationToken());

    assertEquals("special ' value", result.stdout());
    assertTrue(result.stderr().endsWith(projectRoot.toString()), result.stderr());
    assertEquals(0, result.exitCode());
    assertFalse(result.timedOut());
    assertFalse(result.cancelled());
  }

  @Test
  void hookShellTimeoutDoesNotWaitForBlockedProcess() throws Exception {
    var runner = new CommandRunner(new ShellSandbox());
    long started = System.nanoTime();

    var result =
        runner.runHook(
            "sleep 2", projectRoot, "ignored", Duration.ofMillis(100), new CancellationToken());

    assertTrue(result.timedOut());
    assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() < 1500);
  }

  @Test
  void eventValuesRemainDataWhenPassedToShellStdin() throws Exception {
    var executor =
        new HookActionExecutor(new CommandRunner(new ShellSandbox()), HttpClient.newHttpClient());
    var state = new HookSessionState();
    var payload =
        Map.<String, Object>of(
            "cwd", projectRoot.toString(), "value", "$(touch should-not-exist); touch also-no");

    assertTrue(
        executor
            .execute(
                rule(
                    "literal-data",
                    HookEvent.STARTUP,
                    new HookAction.Shell("cat > captured-payload.json")),
                new HookInvocation(HookEvent.STARTUP, payload, state, new CancellationToken()))
            .isEmpty());

    String captured = Files.readString(projectRoot.resolve("captured-payload.json"));
    assertTrue(captured.contains("$(touch should-not-exist)"), captured);
    assertFalse(Files.exists(projectRoot.resolve("should-not-exist")));
    assertFalse(Files.exists(projectRoot.resolve("also-no")));
  }

  @Test
  void executorUsesShellRejectionProtocolAndQueuesPrompt() throws Exception {
    var runner = new CommandRunner(new ShellSandbox());
    var diagnostics = new ArrayList<String>();
    var executor = new HookActionExecutor(runner, HttpClient.newHttpClient(), diagnostics::add);
    var state = new HookSessionState();

    var rejection =
        executor.execute(
            rule(
                "deny-shell",
                HookEvent.PRE_TOOL_USE,
                new HookAction.Shell("printf 'reason from stderr' >&2; exit 2")),
            invocation(HookEvent.PRE_TOOL_USE, state));
    assertEquals(Optional.of(new HookRejection("deny-shell", "reason from stderr")), rejection);

    assertTrue(
        executor
            .execute(
                rule("prompt", HookEvent.STARTUP, new HookAction.Prompt("remember")),
                invocation(HookEvent.STARTUP, state))
            .isEmpty());
    assertEquals(List.of("remember"), state.snapshotPrompts().texts());
    assertTrue(diagnostics.isEmpty(), diagnostics.toString());
  }

  @Test
  void nonZeroShellAndMissingTemplateAreFailuresNotRejections() throws Exception {
    var runner = new CommandRunner(new ShellSandbox());
    var executor = new HookActionExecutor(runner, HttpClient.newHttpClient());
    var state = new HookSessionState();

    assertThrows(
        IOException.class,
        () ->
            executor.execute(
                rule("failed", HookEvent.STARTUP, new HookAction.Shell("exit 1")),
                invocation(HookEvent.STARTUP, state)));
    assertThrows(
        IOException.class,
        () ->
            executor.execute(
                rule(
                    "missing-template",
                    HookEvent.STARTUP,
                    new HookAction.Http(
                        java.net.URI.create("http://localhost:1"),
                        "POST",
                        Map.of(),
                        Optional.of("${missing}"))),
                invocation(HookEvent.STARTUP, state)));
  }

  @Test
  void httpSendsTemplatedBodyAndAcceptsOnlyValidBlockResponse() throws Exception {
    var requests = new AtomicInteger();
    var method = new ArrayList<String>();
    var body = new ArrayList<String>();
    HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext(
        "/hook",
        exchange -> {
          requests.incrementAndGet();
          method.add(exchange.getRequestMethod());
          body.add(
              new String(
                  exchange.getRequestBody().readAllBytes(),
                  java.nio.charset.StandardCharsets.UTF_8));
          byte[] response = "{\"decision\":\"block\",\"reason\":\"http reason\"}".getBytes();
          exchange.sendResponseHeaders(200, response.length);
          try (var output = exchange.getResponseBody()) {
            output.write(response);
          }
        });
    server.start();
    try {
      var executor =
          new HookActionExecutor(new CommandRunner(new ShellSandbox()), HttpClient.newHttpClient());
      var action =
          new HookAction.Http(
              java.net.URI.create("http://localhost:" + server.getAddress().getPort() + "/hook"),
              "POST",
              Map.of("Content-Type", "application/custom"),
              Optional.of("command=${tool_input.command}"));
      var result =
          executor.execute(
              rule("http-deny", HookEvent.PRE_TOOL_USE, action),
              invocation(
                  HookEvent.PRE_TOOL_USE,
                  new HookSessionState(),
                  Map.of("tool_input", Map.of("command", "echo $HOME"))));

      assertEquals(Optional.of(new HookRejection("http-deny", "http reason")), result);
      assertEquals(1, requests.get());
      assertEquals(List.of("POST"), method);
      assertEquals(List.of("command=echo $HOME"), body);
    } finally {
      server.stop(0);
    }
  }

  @Test
  void subagentIsLoggedAsSkippedWithoutStartingAnotherAgent() throws Exception {
    var diagnostics = new ArrayList<String>();
    var executor =
        new HookActionExecutor(
            new CommandRunner(new ShellSandbox()), HttpClient.newHttpClient(), diagnostics::add);

    assertTrue(
        executor
            .execute(
                rule(
                    "placeholder",
                    HookEvent.STARTUP,
                    new HookAction.Subagent("research", "find facts")),
                invocation(HookEvent.STARTUP, new HookSessionState()))
            .isEmpty());
    assertTrue(
        diagnostics.stream()
            .anyMatch(
                message ->
                    message.contains("not yet implemented") && message.contains("placeholder")));
  }

  private HookRule rule(String name, HookEvent event, HookAction action) {
    return new HookRule(
        name, event, Optional.empty(), action, false, false, Duration.ofSeconds(2), projectRoot);
  }

  private HookInvocation invocation(HookEvent event, HookSessionState state) {
    return invocation(event, state, Map.of());
  }

  private HookInvocation invocation(
      HookEvent event, HookSessionState state, Map<String, Object> extra) {
    var payload = new java.util.LinkedHashMap<String, Object>();
    payload.put("cwd", projectRoot.toString());
    payload.putAll(extra);
    return new HookInvocation(event, payload, state, new CancellationToken());
  }

  private static final class ShellSandbox implements BashSandbox {
    @Override
    public boolean isAvailable() {
      return true;
    }

    @Override
    public SandboxedProcess prepare(BashSandboxRequest request) {
      return new SandboxedProcess(
          List.of("/bin/sh", "-c", request.command()), request.projectRoot());
    }
  }
}
