package com.mewcode.hook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mewcode.agent.CancellationToken;
import com.mewcode.permission.BashSandbox;
import com.mewcode.permission.BashSandboxRequest;
import com.mewcode.permission.RuleMatcher;
import com.mewcode.permission.SandboxedProcess;
import com.mewcode.tool.support.CommandRunner;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class HookEngineTest {
  @Test
  void parsesFixedEventNamesAndOnlyBlockingEventsCanReject() {
    assertEquals(HookEvent.PRE_TOOL_USE, HookEvent.parse("PreToolUse"));
    assertEquals(HookEvent.STARTUP, HookEvent.parse("startup"));
    assertTrue(HookEvent.PRE_TOOL_USE.blocking());
    assertTrue(HookEvent.USER_PROMPT_SUBMIT.blocking());
    assertFalse(HookEvent.TURN_START.blocking());
    assertThrows(IllegalArgumentException.class, () -> HookEvent.parse("preToolUse"));
  }

  @Test
  void validatesActionDataAndCopiesHttpHeaders() {
    assertThrows(IllegalArgumentException.class, () -> new HookAction.Shell(" "));
    assertThrows(IllegalArgumentException.class, () -> new HookAction.Prompt(""));
    assertThrows(
        IllegalArgumentException.class,
        () -> new HookAction.Http(null, "POST", Map.of(), Optional.empty()));
    assertThrows(IllegalArgumentException.class, () -> new HookAction.Subagent("agent", " "));

    var headers = new java.util.HashMap<String, String>();
    headers.put("X-Test", "one");
    var action =
        new HookAction.Http(
            java.net.URI.create("http://localhost:8080/hook"), "POST", headers, Optional.empty());
    headers.put("X-Test", "two");
    assertEquals("one", action.headers().get("X-Test"));
  }

  @Test
  void conditionsReadNestedScalarFieldsAndDoNotNegateMissingValues() {
    var all =
        new HookRule.HookCondition(
            HookRule.HookCondition.Combination.ALL_OF,
            List.of(
                new HookRule.HookCondition.FieldMatch(
                    "tool_input.command", new RuleMatcher.Exact("git status")),
                new HookRule.HookCondition.FieldMatch(
                    "tool_input.dry_run", new RuleMatcher.Exact("true"))));
    var any =
        new HookRule.HookCondition(
            HookRule.HookCondition.Combination.ANY_OF,
            List.of(
                new HookRule.HookCondition.FieldMatch(
                    "tool_input.command", new RuleMatcher.Exact("git diff")),
                new HookRule.HookCondition.FieldMatch(
                    "tool_input.count", new RuleMatcher.Exact("2"))));
    var payload =
        Map.<String, Object>of(
            "tool_input", Map.of("command", "git status", "dry_run", true, "count", 2));

    assertTrue(all.matches(payload));
    assertTrue(any.matches(payload));
    assertFalse(
        new HookRule.HookCondition(
                HookRule.HookCondition.Combination.ALL_OF,
                List.of(
                    new HookRule.HookCondition.FieldMatch(
                        "missing", new RuleMatcher.Not(new RuleMatcher.Exact("anything")))))
            .matches(payload));
    assertFalse(
        new HookRule.HookCondition(
                HookRule.HookCondition.Combination.ALL_OF,
                List.of(
                    new HookRule.HookCondition.FieldMatch(
                        "tool_input", new RuleMatcher.Exact("[object]"))))
            .matches(payload));
  }

  @Test
  void onceStateAndReminderBatchesAreSessionScoped() {
    var state = new HookSessionState();
    assertTrue(state.tryStartOnce("once"));
    assertFalse(state.tryStartOnce("once"));
    state.markExecuted("once");
    assertFalse(state.tryStartOnce("once"));

    state.enqueuePrompt("first");
    var batch = state.snapshotPrompts();
    state.enqueuePrompt("second");
    assertEquals(List.of("first"), batch.texts());
    state.consumePrompts(batch);
    assertEquals(List.of("second"), state.snapshotPrompts().texts());
    state.consumePrompts(batch);
    assertEquals(List.of("second"), state.snapshotPrompts().texts());

    state.close();
    state.enqueuePrompt("late");
    assertTrue(state.snapshotPrompts().texts().isEmpty());
    assertFalse(state.tryStartOnce("new"));
  }

  @Test
  void onceStateAllowsOnlyOneConcurrentStarter() throws Exception {
    var state = new HookSessionState();
    var ready = new CountDownLatch(8);
    var start = new CountDownLatch(1);
    var winners = new ArrayList<Boolean>();
    try (var executor = Executors.newFixedThreadPool(8)) {
      for (int i = 0; i < 8; i++) {
        executor.submit(
            () -> {
              ready.countDown();
              start.await();
              synchronized (winners) {
                winners.add(state.tryStartOnce("once"));
              }
              return null;
            });
      }
      assertTrue(ready.await(2, TimeUnit.SECONDS));
      start.countDown();
    }
    assertEquals(1, winners.stream().filter(Boolean::booleanValue).count());
  }

  @Test
  void invocationKeepsARecursiveSnapshotAndAddsCommonFields() {
    var input = new java.util.HashMap<String, Object>();
    input.put("command", "echo original");
    var payload = new java.util.HashMap<String, Object>();
    payload.put("cwd", "/tmp/project");
    payload.put("tool_input", input);
    var state = new HookSessionState();
    var invocation =
        new HookInvocation(HookEvent.PRE_TOOL_USE, payload, state, new CancellationToken());

    input.put("command", "echo changed");
    payload.put("new_field", "not in snapshot");

    assertEquals("PreToolUse", invocation.payload().get("event"));
    assertEquals(
        "echo original", ((Map<?, ?>) invocation.payload().get("tool_input")).get("command"));
    assertFalse(invocation.payload().containsKey("new_field"));
    assertNotNull(invocation.state());
    assertFalse(invocation.cancellation().isCancelled());
  }

  @Test
  void dispatchesRulesInOrderContinuesFailuresAndStopsAfterRejection() throws Exception {
    var diagnostics = new ArrayList<String>();
    var state = new HookSessionState();
    var rules =
        List.of(
            rule("failed", HookEvent.PRE_TOOL_USE, new HookAction.Shell("exit 1"), false, false),
            rule(
                "prompt-after-failure",
                HookEvent.PRE_TOOL_USE,
                new HookAction.Prompt("after"),
                false,
                false),
            rule(
                "deny",
                HookEvent.PRE_TOOL_USE,
                new HookAction.Shell("printf denied >&2; exit 2"),
                false,
                false),
            rule(
                "prompt-after-deny",
                HookEvent.PRE_TOOL_USE,
                new HookAction.Prompt("must-not-run"),
                false,
                false));
    try (var engine =
        new HookEngine(
            new com.mewcode.config.HookConfigLoader.LoadedHooks(rules, List.of()),
            new CommandRunner(new ShellSandbox()),
            diagnostics::add)) {
      var rejection = engine.dispatch(invocation(HookEvent.PRE_TOOL_USE, state));

      assertEquals(Optional.of(new HookRejection("deny", "denied")), rejection);
      assertEquals(List.of("after"), state.snapshotPrompts().texts());
      assertTrue(diagnostics.stream().anyMatch(message -> message.contains("failed")));
    }
  }

  @Test
  void onceActionIsConsumedAfterFailureAndAsyncDispatchDoesNotBlock() throws Exception {
    var state = new HookSessionState();
    var failingOnce =
        rule("once-failure", HookEvent.STARTUP, new HookAction.Shell("exit 1"), true, false);
    var asyncPrompt =
        rule("async-prompt", HookEvent.STARTUP, new HookAction.Prompt("async"), true, true);
    try (var engine =
        new HookEngine(
            new com.mewcode.config.HookConfigLoader.LoadedHooks(
                List.of(failingOnce, asyncPrompt), List.of()),
            new CommandRunner(new ShellSandbox()),
            ignored -> {})) {
      engine.dispatch(invocation(HookEvent.STARTUP, state));
      engine.dispatch(invocation(HookEvent.STARTUP, state));

      assertTrue(awaitPrompt(state, "async"));
      assertEquals(1, state.snapshotPrompts().texts().stream().filter("async"::equals).count());
      assertFalse(state.tryStartOnce("once-failure"));
    }
  }

  @Test
  void cancellingSessionStopsAsyncWorkAndClosingPreventsNewDispatch() throws Exception {
    var state = new HookSessionState();
    var started = new AtomicBoolean();
    var rule = rule("long", HookEvent.STARTUP, new HookAction.Shell("sleep 2"), false, true);
    try (var engine =
        new HookEngine(
            new com.mewcode.config.HookConfigLoader.LoadedHooks(List.of(rule), List.of()),
            new CommandRunner(new BlockingShellSandbox(started)),
            ignored -> {})) {
      engine.dispatch(invocation(HookEvent.STARTUP, state));
      assertTrue(waitFor(started));
      engine.cancelSession(state);
      assertTrue(state.isClosed());
      assertTrue(engine.dispatch(invocation(HookEvent.STARTUP, state)).isEmpty());
    }
  }

  private static boolean awaitPrompt(HookSessionState state, String text)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (System.nanoTime() < deadline) {
      if (state.snapshotPrompts().texts().contains(text)) return true;
      Thread.sleep(10);
    }
    return false;
  }

  private static boolean waitFor(AtomicBoolean value) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (System.nanoTime() < deadline) {
      if (value.get()) return true;
      Thread.sleep(10);
    }
    return false;
  }

  private static HookRule rule(
      String name, HookEvent event, HookAction action, boolean onlyOnce, boolean async) {
    return new HookRule(
        name,
        event,
        Optional.empty(),
        action,
        onlyOnce,
        async,
        Duration.ofSeconds(2),
        Path.of("/tmp/hooks.yaml"));
  }

  private static HookInvocation invocation(HookEvent event, HookSessionState state) {
    return new HookInvocation(event, Map.of("cwd", "/tmp"), state, new CancellationToken());
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

  private static final class BlockingShellSandbox implements BashSandbox {
    private final AtomicBoolean started;

    private BlockingShellSandbox(AtomicBoolean started) {
      this.started = started;
    }

    @Override
    public boolean isAvailable() {
      return true;
    }

    @Override
    public SandboxedProcess prepare(BashSandboxRequest request) {
      started.set(true);
      return new SandboxedProcess(List.of("/bin/sh", "-c", "sleep 2"), request.projectRoot());
    }
  }
}
