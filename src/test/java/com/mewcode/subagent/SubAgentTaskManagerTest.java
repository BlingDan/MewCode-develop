package com.mewcode.subagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mewcode.agent.AgentEvent;
import com.mewcode.agent.AgentRun;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class SubAgentTaskManagerTest {

  @Test
  void tracksCompletionAndPublishesOneSafeNotification() throws Exception {
    try (var manager = new SubAgentTaskManager()) {
      var handle =
          manager.start(
              new SubAgentTaskManager.TaskRequest(
                  "session-1",
                  SubAgentTaskManager.TaskType.DEFINITION,
                  "inspect",
                  "inspect files",
                  true,
                  false,
                  () -> completedRun(),
                  null,
                  null,
                  null));

      var snapshot = handle.completion().get(2, TimeUnit.SECONDS);
      assertEquals(SubAgentTaskManager.Status.COMPLETED, snapshot.status());
      assertEquals("done", snapshot.result());
      assertEquals(2, snapshot.inputTokens());
      assertEquals(3, snapshot.outputTokens());
      var notices = manager.drainNotifications("session-1");
      assertEquals(1, notices.size());
      assertTrue(
          notices.getFirst().content().contains("<task-id>" + handle.taskId() + "</task-id>"));
      assertEquals(0, manager.drainNotifications("session-1").size());
    }
  }

  @Test
  void foregroundTaskBecomesVisibleWhenPublishedAndCanBeCancelled() throws Exception {
    try (var manager = new SubAgentTaskManager()) {
      var runHolder = new AgentRun[1];
      var handle =
          manager.start(
              new SubAgentTaskManager.TaskRequest(
                  "session-2",
                  SubAgentTaskManager.TaskType.FORK,
                  "wait",
                  "wait",
                  false,
                  false,
                  () -> {
                    runHolder[0] = new AgentRun();
                    return runHolder[0];
                  },
                  null,
                  null,
                  null));
      waitFor(() -> runHolder[0] != null);
      assertTrue(manager.list("session-2").isEmpty());
      assertEquals(Optional.of(handle.taskId()), manager.publish("session-2", handle.taskId()));
      assertEquals(1, manager.list("session-2").size());
      var update =
          manager.update(
              "session-2",
              new SubAgentTaskManager.TaskUpdate(
                  handle.taskId(), SubAgentTaskManager.Status.CANCELLED, null, null));
      assertTrue(update.accepted());
      assertEquals(
          SubAgentTaskManager.Status.CANCELLED,
          handle.completion().get(2, TimeUnit.SECONDS).status());
    }
  }

  @Test
  void cancelsAnUnpublishedForegroundTaskThroughTheParentHook() throws Exception {
    try (var manager = new SubAgentTaskManager()) {
      var runHolder = new AgentRun[1];
      var handle =
          manager.start(
              new SubAgentTaskManager.TaskRequest(
                  "session-3",
                  SubAgentTaskManager.TaskType.DEFINITION,
                  "wait",
                  "wait",
                  false,
                  false,
                  () -> {
                    runHolder[0] = new AgentRun();
                    return runHolder[0];
                  },
                  null,
                  null,
                  null));
      waitFor(() -> runHolder[0] != null);

      assertTrue(manager.cancel("session-3", handle.taskId()));
      assertEquals(
          SubAgentTaskManager.Status.CANCELLED,
          handle.completion().get(2, TimeUnit.SECONDS).status());
      assertEquals(AgentRun.State.CANCELLED, runHolder[0].state());
    }
  }

  @Test
  void keepsOnlyTheFinalTextAfterACompletedToolRound() throws Exception {
    try (var manager = new SubAgentTaskManager()) {
      var handle =
          manager.start(
              new SubAgentTaskManager.TaskRequest(
                  "session-4",
                  SubAgentTaskManager.TaskType.DEFINITION,
                  "inspect",
                  "inspect",
                  true,
                  false,
                  () -> {
                    var run = new AgentRun();
                    run.events().publish(new AgentEvent.StreamText("intermediate"));
                    run.events().publish(new AgentEvent.TurnComplete(1));
                    run.events().publish(new AgentEvent.ToolUse("call-1", "ReadFile", Map.of()));
                    run.events()
                        .publish(new AgentEvent.ToolResult("call-1", "ReadFile", "ok", false, 1));
                    run.events().publish(new AgentEvent.StreamText("final"));
                    run.events().publish(new AgentEvent.TurnComplete(2));
                    run.events().publish(new AgentEvent.LoopComplete(2));
                    run.complete();
                    return run;
                  },
                  null,
                  null,
                  null));

      assertEquals("final", handle.completion().get(2, TimeUnit.SECONDS).result());
    }
  }

  private static AgentRun completedRun() {
    var run = new AgentRun();
    run.events().publish(new AgentEvent.StreamText("done"));
    run.events().publish(new AgentEvent.Usage(OptionalLong.of(2), OptionalLong.of(3)));
    run.events().publish(new AgentEvent.LoopComplete(1));
    run.complete();
    return run;
  }

  private static void waitFor(java.util.function.BooleanSupplier condition) throws Exception {
    for (int i = 0; i < 100 && !condition.getAsBoolean(); i++) Thread.sleep(10);
    assertTrue(condition.getAsBoolean());
  }
}
